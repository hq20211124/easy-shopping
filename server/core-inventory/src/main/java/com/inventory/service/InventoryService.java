package com.inventory.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.inventory.domain.ReservationStatus;
import com.inventory.domain.Stock;
import com.inventory.domain.StockReservation;
import com.inventory.dto.InventoryDtos.InitStockRequest;
import com.inventory.dto.InventoryDtos.InventoryItem;
import com.inventory.dto.InventoryDtos.OperationResult;
import com.inventory.dto.InventoryDtos.PreviewItem;
import com.inventory.dto.InventoryDtos.PreviewResult;
import com.inventory.dto.InventoryDtos.ReserveResult;
import com.inventory.dto.InventoryDtos.StockView;
import com.inventory.repo.StockRepository;
import com.inventory.repo.StockReservationRepository;
import com.inventory.support.AfterCommitEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 库存核心服务：预占（reserve）/ 释放（release）/ 扣减（deduct）/ 校验（preview）。
 *
 * <p><b>并发安全模型</b>（admin-backend 5.3.1 v1.1：Redis 仅是闸门、DB 是账本；P0 无 Redis，
 * 直接以 DB {@code @Version} 乐观锁 + 条件校验保证不超卖）：</p>
 * <ul>
 *   <li>每次「读-校验-改-写」放在独立事务（{@link TransactionTemplate}）中，
 *       乐观锁冲突（{@link OptimisticLockingFailureException}）时整单元重试，最多 {@code MAX_OPTIMISTIC_RETRIES} 次；</li>
 *   <li>重试耗尽仍冲突 → 抛 {@link BizErrorCode#OUT_OF_STOCK}(3002)「库存紧张，请重试」，绝不静默超卖；</li>
 *   <li>可用库存不足同样返回 3002（TOCTOU 竞态由「同事务内校验+改写+乐观锁」闭合，见库存并发测试）；</li>
 *   <li>release / deduct 以 {@code orderId} 为业务幂等键：预占单状态机 ACTIVE→RELEASED/DEDUCTED
 *       单向流转（{@code @Version} 保证并发只成功一次），重复调用只生效一次——
 *       释放比扣减更危险，重复释放等于白送库存（4.4.4 第 4 条）；</li>
 *   <li><b>release / deduct 为逐条独立短事务</b>（P0-E 幽灵锁修复）：每条预占单自成一个事务，
 *       单条失败只记 ERROR 告警并继续，不会把已成功的条目整体回滚；</li>
 * </ul>
 *
 * <p>预占有效期 = 订单 {@code pay_deadline}；到期由 {@code InventoryExpireScheduler} 每 30s 扫描释放
 * （P0 内存扫兜底，P1 换 RabbitMQ 延迟队列 deliveryTimestamp 直取 pay_deadline，见 4.4.4）。</p>
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    /** 乐观锁冲突重试上限（超过按 3002 返回，让客户端刷新重试）。 */
    static final int MAX_OPTIMISTIC_RETRIES = 5;

    /** 预占默认 TTL（秒）：与订单 pay_deadline 默认 30 分钟对齐（产品待确认参数，见 order 侧配置）。 */
    public static final long DEFAULT_RESERVE_TTL_SECONDS = 30 * 60;

    private final StockRepository stockRepository;
    private final StockReservationRepository reservationRepository;
    private final DomainEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;

    public InventoryService(StockRepository stockRepository,
                            StockReservationRepository reservationRepository,
                            DomainEventPublisher eventPublisher,
                            PlatformTransactionManager transactionManager) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 查询

    /** 预占前校验（只读不占库）：任一 SKU 可用不足则 allAvailable=false。 */
    public PreviewResult preview(List<InventoryItem> items) {
        List<PreviewItem> result = new ArrayList<>(items.size());
        boolean allAvailable = true;
        for (InventoryItem item : items) {
            int available = stockRepository.findBySkuId(item.skuId())
                    .map(Stock::getAvailableStock).orElse(0);
            boolean sufficient = available >= item.quantity();
            allAvailable &= sufficient;
            result.add(new PreviewItem(item.skuId(), item.quantity(), sufficient, available));
        }
        return new PreviewResult(allAvailable, result);
    }

    /** 批量查询库存视图（GET /api/v1/inventory/stocks）。 */
    public List<StockView> stocks(List<Long> skuIds) {
        return stockRepository.findBySkuIdIn(skuIds).stream()
                .map(s -> new StockView(s.getSkuId(), s.getAvailableStock(), s.getLockedStock(),
                        s.getAvailableStock() + s.getLockedStock()))
                .toList();
    }

    // ------------------------------------------------------------------ 预占

    /**
     * 预占库存：available → locked，并落预占单（expireAt 由订单 pay_deadline 派生）。
     *
     * <p>幂等：同一 orderId 已有 ACTIVE 预占单时直接返回（下单重试安全）；
     * 全 SKU 原子成功，任一不足整体失败（不存在部分预占）。</p>
     */
    public ReserveResult reserve(Long orderId, List<InventoryItem> items, Duration ttl) {
        if (items == null || items.isEmpty()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "items 不能为空");
        }
        Instant expireAt = Instant.now().plus(ttl);
        withOptimisticRetry("reserve", orderId, () -> {
            if (reservationRepository.existsByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE)) {
                log.info("库存预占幂等命中，跳过: orderId={}", orderId);
                return null;
            }
            for (InventoryItem item : items) {
                Stock stock = stockRepository.findBySkuId(item.skuId())
                        .orElseThrow(() -> new BizException(BizErrorCode.OUT_OF_STOCK,
                                "SKU 无库存记录: " + item.skuId()));
                if (stock.getAvailableStock() < item.quantity()) {
                    throw new BizException(BizErrorCode.OUT_OF_STOCK,
                            "SKU 库存不足: skuId=" + item.skuId() + " 需要 " + item.quantity()
                                    + "，可用 " + stock.getAvailableStock());
                }
                stock.reserve(item.quantity());
                stockRepository.saveAndFlush(stock); // @Version 条件写，冲突抛 OptimisticLockingFailureException
                AfterCommitEvents.publish(eventPublisher, DomainEventNames.INVENTORY_CHANGE,
                        inventoryChange(item.skuId(), -item.quantity(), stock, "reserve", orderId));
            }
            for (InventoryItem item : items) {
                reservationRepository.save(new StockReservation(orderId, item.skuId(), item.quantity(), expireAt));
            }
            return null;
        });
        return new ReserveResult(orderId, true, expireAt);
    }

    // ------------------------------------------------------------------ 释放

    /**
     * 释放预占（订单取消/超时关单）：locked → available，预占单 ACTIVE→RELEASED。
     *
     * <p><b>逐条独立短事务（P0-E 幽灵锁修复）</b>：每条预占单自成一个事务，单条失败（sku_stock 记录缺失、
     * 乐观锁耗尽、账本被并发扣减到不足…）只记录该条 ERROR 告警并继续处理下一条，
     * <b>绝不因为第 N 条失败把前 N-1 条已完成的释放整体回滚</b>——那会让订单已关单但库存永久卡在 locked
     * （plans/admin-backend.md 4.4.4 点名的「库存被幽灵锁死」v1.0 硬伤）。失败条目保持 ACTIVE，
     * 由本方法重试、{@code InventoryExpireScheduler} 超时扫描或人工对账兜底。</p>
     *
     * <p>幂等：事务内重读预占单并二次校验 status=ACTIVE，已释放/已出库的条目直接跳过，
     * 重复 release 不会把 locked 打成负数也不会白送库存。</p>
     *
     * @return 本次实际释放成功的条目数（失败条目不计入）
     */
    public OperationResult release(Long orderId) {
        List<StockReservation> actives =
                reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
        int changed = 0;
        for (StockReservation reservation : actives) {
            try {
                if (releaseOne(orderId, reservation.getId())) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.error("[inventory-release-failed] 预占释放失败，该条保持 ACTIVE 待重试/对账: "
                                + "orderId={} reservationId={} skuId={} quantity={}",
                        orderId, reservation.getId(), reservation.getSkuId(), reservation.getQuantity(), e);
            }
        }
        return new OperationResult(orderId, changed);
    }

    /** 单条预占释放（独立事务 + 乐观锁重试）；返回是否真的发生了释放。 */
    private boolean releaseOne(Long orderId, Long reservationId) {
        return withOptimisticRetry("release", orderId, () -> {
            StockReservation fresh = reservationRepository.findById(reservationId).orElse(null);
            if (fresh == null || fresh.getStatus() != ReservationStatus.ACTIVE) {
                return false; // 已被扣减/超时释放/其他并发请求处理 → 幂等跳过
            }
            Stock stock = stockRepository.findBySkuId(fresh.getSkuId())
                    .orElseThrow(() -> new BizException(BizErrorCode.SYSTEM_ERROR,
                            "库存记录丢失，需人工核对: skuId=" + fresh.getSkuId()));
            requireLockedEnough(stock, fresh, "release");
            stock.release(fresh.getQuantity());
            stockRepository.saveAndFlush(stock);
            fresh.moveTo(ReservationStatus.RELEASED);
            reservationRepository.saveAndFlush(fresh); // @Version 防与扣减/扫描并发双写
            AfterCommitEvents.publish(eventPublisher, DomainEventNames.INVENTORY_CHANGE,
                    inventoryChange(fresh.getSkuId(), fresh.getQuantity(), stock, "release", orderId));
            return true;
        });
    }

    // ------------------------------------------------------------------ 扣减

    /**
     * 扣减（确认收货出库）：locked 减少（货权转移，不回补 available），预占单 ACTIVE→DEDUCTED。
     *
     * <p>与 {@link #release} 同样的逐条独立短事务语义（P0-E）：单条失败不影响其它条目，
     * 失败条目保持 ACTIVE 待重试/对账。</p>
     *
     * <p>幂等：事务内重读并校验 status=ACTIVE，未预占/已出库/已释放的订单重复调用返回 changed=0。</p>
     */
    public OperationResult deduct(Long orderId) {
        List<StockReservation> actives =
                reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
        int changed = 0;
        for (StockReservation reservation : actives) {
            try {
                if (deductOne(orderId, reservation.getId())) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.error("[inventory-deduct-failed] 预占扣减失败，该条保持 ACTIVE 待重试/对账: "
                                + "orderId={} reservationId={} skuId={} quantity={}",
                        orderId, reservation.getId(), reservation.getSkuId(), reservation.getQuantity(), e);
            }
        }
        return new OperationResult(orderId, changed);
    }

    /** 单条预占扣减（独立事务 + 乐观锁重试）；返回是否真的发生了扣减。 */
    private boolean deductOne(Long orderId, Long reservationId) {
        return withOptimisticRetry("deduct", orderId, () -> {
            StockReservation fresh = reservationRepository.findById(reservationId).orElse(null);
            if (fresh == null || fresh.getStatus() != ReservationStatus.ACTIVE) {
                return false;
            }
            Stock stock = stockRepository.findBySkuId(fresh.getSkuId())
                    .orElseThrow(() -> new BizException(BizErrorCode.SYSTEM_ERROR,
                            "库存记录丢失，需人工核对: skuId=" + fresh.getSkuId()));
            requireLockedEnough(stock, fresh, "deduct");
            stock.deduct(fresh.getQuantity());
            stockRepository.saveAndFlush(stock);
            fresh.moveTo(ReservationStatus.DEDUCTED);
            reservationRepository.saveAndFlush(fresh);
            AfterCommitEvents.publish(eventPublisher, DomainEventNames.INVENTORY_CHANGE,
                    inventoryChange(fresh.getSkuId(), -fresh.getQuantity(), stock, "deduct", orderId));
            return true;
        });
    }

    // ------------------------------------------------------------------ 超时释放

    /**
     * 扫描并释放超时预占（P0 每 30s 内存扫；P1 换 MQ 延迟队列，deliveryTimestamp 直取 pay_deadline）。
     *
     * @return 本次释放的预占单数量
     */
    public int sweepExpiredReservations() {
        List<StockReservation> expired =
                reservationRepository.findByStatusAndExpireAtBefore(ReservationStatus.ACTIVE, Instant.now());
        int released = 0;
        for (StockReservation reservation : expired) {
            try {
                boolean done = withOptimisticRetry("expire-release", reservation.getOrderId(), () -> {
                    StockReservation fresh = reservationRepository.findById(reservation.getId()).orElse(null);
                    if (fresh == null || fresh.getStatus() != ReservationStatus.ACTIVE) {
                        return false; // 已被取消/扣减路径处理，跳过
                    }
                    Stock stock = stockRepository.findBySkuId(fresh.getSkuId())
                            .orElseThrow(() -> new BizException(BizErrorCode.SYSTEM_ERROR,
                                    "库存记录丢失，需人工核对: skuId=" + fresh.getSkuId()));
                    stock.release(fresh.getQuantity());
                    stockRepository.saveAndFlush(stock);
                    fresh.moveTo(ReservationStatus.EXPIRED);
                    reservationRepository.saveAndFlush(fresh);
                    AfterCommitEvents.publish(eventPublisher, DomainEventNames.INVENTORY_CHANGE,
                            inventoryChange(fresh.getSkuId(), fresh.getQuantity(), stock,
                                    "expire-release", fresh.getOrderId()));
                    return true;
                });
                if (done) {
                    released++;
                }
            } catch (RuntimeException e) {
                // 单条失败不阻塞整轮扫描，下一轮重试（与 release/deduct 同样的逐条隔离口径）
                log.error("[inventory-expire-release-failed] 超时预占释放失败，下轮重试: reservationId={} skuId={}",
                        reservation.getId(), reservation.getSkuId(), e);
            }
        }
        return released;
    }

    // ------------------------------------------------------------------ 初始化（P0 联调）

    /** P0 联调用：初始化 SKU 库存记录（已存在则拒绝，避免误覆盖账本）。 */
    public Long initStock(InitStockRequest request) {
        return withOptimisticRetry("init-stock", null, () -> {
            if (stockRepository.findBySkuId(request.skuId()).isPresent()) {
                throw new BizException(BizErrorCode.PARAM_ERROR,
                        "SKU 库存记录已存在，请走管理端调整: skuId=" + request.skuId());
            }
            Stock stock = stockRepository.save(new Stock(request.skuId(), request.availableStock()));
            return stock.getId();
        });
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 乐观锁冲突整单元重试（每次重试都是全新事务，重读最新版本）。
     * 冲突超上限 → 3002；业务异常（如库存不足）原样抛出不重试。
     */
    private <T> T withOptimisticRetry(String op, Long bizKey, java.util.function.Supplier<T> work) {
        OptimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
            try {
                return txTemplate.execute(status -> work.get());
            } catch (OptimisticLockingFailureException e) {
                last = e;
                log.warn("库存乐观锁冲突重试: op={} bizKey={} attempt={}/{}", op, bizKey, attempt, MAX_OPTIMISTIC_RETRIES);
            }
        }
        log.error("库存乐观锁冲突重试耗尽: op={} bizKey={}", op, bizKey, last);
        throw new BizException(BizErrorCode.OUT_OF_STOCK, "库存紧张，请稍后重试");
    }

    /**
     * 账本下界保护：释放/扣减前确认 locked 覆盖本条预占数量。
     * 预占单状态机（ACTIVE 单向流转）已保证重复调用不会二次扣减；这里再兜一层，
     * 防止历史脏数据/并发把 locked 打成负数（负 locked = 凭空多出可售库存）。
     */
    private void requireLockedEnough(Stock stock, StockReservation reservation, String op) {
        if (stock.getLockedStock() < reservation.getQuantity()) {
            throw new BizException(BizErrorCode.SYSTEM_ERROR,
                    "锁定库存不足（账本异常，需人工核对）: op=" + op + " skuId=" + stock.getSkuId()
                            + " quantity=" + reservation.getQuantity() + " locked=" + stock.getLockedStock());
        }
    }

    private Map<String, Object> inventoryChange(Long skuId, int delta, Stock stock, String bizType, Long orderId) {
        return Map.of(
                "skuId", skuId,
                "delta", delta,
                "availableStock", stock.getAvailableStock(),
                "lockedStock", stock.getLockedStock(),
                "bizType", bizType,
                "orderId", orderId == null ? "" : orderId);
    }
}
