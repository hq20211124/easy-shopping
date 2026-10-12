package com.inventory.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.inventory.domain.ReservationStatus;
import com.inventory.domain.Stock;
import com.inventory.domain.StockReservation;
import com.inventory.dto.InventoryDtos.InventoryItem;
import com.inventory.dto.InventoryDtos.OperationResult;
import com.inventory.repo.StockRepository;
import com.inventory.repo.StockReservationRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存并发与幂等测试（mem H2，不污染 ./data 开发库）。
 *
 * <p>核心证明：<b>TOCTOU 竞态闭合</b>——两线程同时抢最后 1 件，恰有一人成功、一人得 3002，
 * 最终 available=0 且 locked=1（不存在超卖/双占）。见 {@code 并发预占_两线程抢最后一件_只有一人成功()}。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-inventory-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 测试里关闭周期扫描，超时释放用例直接调 sweepExpiredReservations() 保证确定性
        "app.inventory.expire-scan-ms=3600000"
})
class InventoryServiceTest {

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private StockReservationRepository reservationRepository;

    @BeforeEach
    void cleanAndSeed() {
        reservationRepository.deleteAll();
        stockRepository.deleteAll();
    }

    private void seedStock(Long skuId, int available) {
        stockRepository.save(new Stock(skuId, available));
    }

    private Stock stockOf(Long skuId) {
        return stockRepository.findBySkuId(skuId).orElseThrow();
    }

    /** 指定订单下某 SKU 的预占单（断言单条状态用）。 */
    private StockReservation reservationOf(Long orderId, Long skuId) {
        return reservationRepository.findByOrderId(orderId).stream()
                .filter(r -> r.getSkuId().equals(skuId))
                .findFirst()
                .orElseThrow();
    }

    /** 挂 ERROR 日志采集器（断言「失败条目有告警」）。 */
    private ListAppender<ILoggingEvent> attachErrorAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(InventoryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(InventoryService.class)).detachAppender(appender);
        appender.stop();
    }

    // -------------------------------------------------------------- 并发（TOCTOU 证明）

    @Test
    void 并发预占_两线程抢最后一件_只有一人成功() throws Exception {
        seedStock(20001L, 1); // 只剩最后 1 件

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                long orderId = 900_001L + i; // 两个不同订单抢同一件库存
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        inventoryService.reserve(orderId, List.of(new InventoryItem(20001L, 1)),
                                Duration.ofMinutes(30));
                        success.incrementAndGet();
                    } catch (BizException e) {
                        if (e.getErrorCode() == BizErrorCode.OUT_OF_STOCK) {
                            outOfStock.incrementAndGet();
                        } else {
                            throw e;
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 恰好一人成功、一人 3002 —— 证明乐观锁闭合了「先查后改」的 TOCTOU 窗口
        assertThat(success.get()).isEqualTo(1);
        assertThat(outOfStock.get()).isEqualTo(1);

        Stock stock = stockOf(20001L);
        assertThat(stock.getAvailableStock()).isEqualTo(0);
        assertThat(stock.getLockedStock()).isEqualTo(1); // 未超卖：总账 = 1 件
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(reservationRepository.findByStatusAndExpireAtBefore(ReservationStatus.ACTIVE,
                java.time.Instant.now().plusSeconds(3600))).hasSize(1);
    }

    // -------------------------------------------------------------- 预占

    @Test
    void 预占_库存充足_可用转锁定() {
        seedStock(100L, 10);
        inventoryService.reserve(1L, List.of(new InventoryItem(100L, 3)), Duration.ofMinutes(30));

        Stock stock = stockOf(100L);
        assertThat(stock.getAvailableStock()).isEqualTo(7);
        assertThat(stock.getLockedStock()).isEqualTo(3);
        assertThat(reservationRepository.findByOrderIdAndStatus(1L, ReservationStatus.ACTIVE)).hasSize(1);
    }

    @Test
    void 预占_库存不足_抛3002且不留脏账() {
        seedStock(100L, 2);
        assertThatThrownBy(() ->
                inventoryService.reserve(2L, List.of(new InventoryItem(100L, 5)), Duration.ofMinutes(30)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.OUT_OF_STOCK));

        Stock stock = stockOf(100L);
        assertThat(stock.getAvailableStock()).isEqualTo(2);
        assertThat(stock.getLockedStock()).isEqualTo(0);
        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    void 预占_多SKU任一不足_整体回滚无部分预占() {
        seedStock(100L, 10);
        seedStock(200L, 1);
        assertThatThrownBy(() -> inventoryService.reserve(3L,
                List.of(new InventoryItem(100L, 2), new InventoryItem(200L, 5)), Duration.ofMinutes(30)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.OUT_OF_STOCK));

        assertThat(stockOf(100L).getAvailableStock()).isEqualTo(10); // 未部分预占
        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    void 预占_同一订单重复调用_幂等() {
        seedStock(100L, 10);
        inventoryService.reserve(4L, List.of(new InventoryItem(100L, 3)), Duration.ofMinutes(30));
        inventoryService.reserve(4L, List.of(new InventoryItem(100L, 3)), Duration.ofMinutes(30)); // 重试/重放

        Stock stock = stockOf(100L);
        assertThat(stock.getAvailableStock()).isEqualTo(7); // 只扣一次
        assertThat(reservationRepository.findByOrderIdAndStatus(4L, ReservationStatus.ACTIVE)).hasSize(1);
    }

    // -------------------------------------------------------------- 释放 / 扣减

    @Test
    void 释放_取消订单_库存回补且重复释放幂等() {
        seedStock(100L, 10);
        inventoryService.reserve(5L, List.of(new InventoryItem(100L, 4)), Duration.ofMinutes(30));

        var first = inventoryService.release(5L);
        var second = inventoryService.release(5L); // 重复释放不得白送库存

        assertThat(first.changed()).isEqualTo(1);
        assertThat(second.changed()).isZero();
        Stock stock = stockOf(100L);
        assertThat(stock.getAvailableStock()).isEqualTo(10); // 回补一次
        assertThat(stock.getLockedStock()).isEqualTo(0);
    }

    @Test
    void 扣减_确认收货_锁定转出库且重复扣减幂等() {
        seedStock(100L, 10);
        inventoryService.reserve(6L, List.of(new InventoryItem(100L, 4)), Duration.ofMinutes(30));

        var first = inventoryService.deduct(6L);
        var second = inventoryService.deduct(6L);

        assertThat(first.changed()).isEqualTo(1);
        assertThat(second.changed()).isZero();
        Stock stock = stockOf(100L);
        assertThat(stock.getAvailableStock()).isEqualTo(6); // 出库不回补 available
        assertThat(stock.getLockedStock()).isEqualTo(0);   // 货权已转移
    }

    @Test
    void 扣减_已释放订单_不再出库() {
        seedStock(100L, 10);
        inventoryService.reserve(7L, List.of(new InventoryItem(100L, 4)), Duration.ofMinutes(30));
        inventoryService.release(7L);

        assertThat(inventoryService.deduct(7L).changed()).isZero();
        assertThat(stockOf(100L).getAvailableStock()).isEqualTo(10);
    }

    // -------------------------------------------------------------- P0-E 幽灵锁（逐条独立事务）

    @Test
    void 释放_中间SKU账本缺失_首尾仍已释放且失败条目有告警() {
        seedStock(300L, 10);
        seedStock(301L, 10);
        seedStock(302L, 10);
        inventoryService.reserve(20L, List.of(new InventoryItem(300L, 2), new InventoryItem(301L, 3),
                new InventoryItem(302L, 4)), Duration.ofMinutes(30));

        // 人为制造数据损坏：中间 SKU 的 sku_stock 记录被删（管理端删档/清理脚本/迁移遗漏）
        stockRepository.deleteById(stockOf(301L).getId());
        stockRepository.flush();

        ListAppender<ILoggingEvent> errors = attachErrorAppender();
        OperationResult result;
        try {
            result = inventoryService.release(20L);
        } finally {
            detachAppender(errors);
        }

        // 第 2 条抛异常不影响第 1、3 条：整体方法不抛异常，成功条目已提交
        assertThat(result.changed()).isEqualTo(2);
        assertThat(stockOf(300L).getAvailableStock()).isEqualTo(10);
        assertThat(stockOf(300L).getLockedStock()).isZero();
        assertThat(stockOf(302L).getAvailableStock()).isEqualTo(10);
        assertThat(stockOf(302L).getLockedStock()).isZero();

        // 失败条目保持 ACTIVE（等重试/超时扫描/对账），库存终值不被白送
        assertThat(reservationOf(20L, 300L).getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservationOf(20L, 301L).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservationOf(20L, 302L).getStatus()).isEqualTo(ReservationStatus.RELEASED);

        // 失败条目必须有 ERROR 告警（可被日志采集/值班发现）
        assertThat(errors.list).anyMatch(e -> e.getFormattedMessage().contains("[inventory-release-failed]")
                && e.getFormattedMessage().contains("skuId=301"));
    }

    @Test
    void 释放_账本补回后重试_幂等补齐且不产生负库存() {
        seedStock(400L, 10);
        seedStock(401L, 10);
        inventoryService.reserve(21L, List.of(new InventoryItem(400L, 2), new InventoryItem(401L, 3)),
                Duration.ofMinutes(30));
        stockRepository.deleteById(stockOf(401L).getId());
        stockRepository.flush();
        assertThat(inventoryService.release(21L).changed()).isEqualTo(1); // 401 失败

        // 重复 release：已释放条目幂等跳过，失败条目再次尝试（此时账本仍缺失，仍失败），locked 不为负
        assertThat(inventoryService.release(21L).changed()).isZero();
        assertThat(stockOf(400L).getLockedStock()).isZero();
        assertThat(stockOf(400L).getAvailableStock()).isEqualTo(10);

        // 账本按预占单恢复（人工/对账修复）后再释放一次：把幽灵锁解开，且只回补一次
        Stock repaired = new Stock(401L, 3);
        repaired.reserve(3); // available=0 / locked=3，与预占单一致
        stockRepository.saveAndFlush(repaired);
        assertThat(inventoryService.release(21L).changed()).isEqualTo(1);
        assertThat(stockOf(401L).getAvailableStock()).isEqualTo(3);
        assertThat(stockOf(401L).getLockedStock()).isZero();
        assertThat(inventoryService.release(21L).changed()).isZero(); // 再调不重复回补
        assertThat(stockOf(401L).getAvailableStock()).isEqualTo(3);
    }

    @Test
    void 释放_账本锁定量少于预占量_拒绝释放并告警_不产生负库存() {
        seedStock(450L, 10);
        inventoryService.reserve(23L, List.of(new InventoryItem(450L, 4)), Duration.ofMinutes(30));
        // 账本漂移：locked 被别处清零，但预占单仍 ACTIVE（此时直接释放会让 locked 变负 = 凭空多出可售库存）
        Stock drifted = stockOf(450L);
        drifted.release(4);
        stockRepository.saveAndFlush(drifted);
        assertThat(stockOf(450L).getLockedStock()).isZero();

        ListAppender<ILoggingEvent> errors = attachErrorAppender();
        OperationResult result;
        try {
            result = inventoryService.release(23L);
        } finally {
            detachAppender(errors);
        }

        assertThat(result.changed()).isZero();
        assertThat(stockOf(450L).getLockedStock()).isZero();     // 没有变成 -4
        assertThat(stockOf(450L).getAvailableStock()).isEqualTo(10); // 账本原样（漂移前后一致），交给人工核对
        assertThat(errors.list).anyMatch(e -> e.getFormattedMessage().contains("[inventory-release-failed]")
                && e.getFormattedMessage().contains("skuId=450"));
    }

    @Test
    void 扣减_中间SKU账本缺失_其余条目仍已出库() {
        seedStock(500L, 10);
        seedStock(501L, 10);
        seedStock(502L, 10);
        inventoryService.reserve(22L, List.of(new InventoryItem(500L, 1), new InventoryItem(501L, 1),
                new InventoryItem(502L, 1)), Duration.ofMinutes(30));
        stockRepository.deleteById(stockOf(501L).getId());
        stockRepository.flush();

        assertThat(inventoryService.deduct(22L).changed()).isEqualTo(2);
        assertThat(stockOf(500L).getLockedStock()).isZero();
        assertThat(stockOf(500L).getAvailableStock()).isEqualTo(9); // 出库不回补
        assertThat(stockOf(502L).getLockedStock()).isZero();
        assertThat(reservationOf(22L, 501L).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }

    // -------------------------------------------------------------- 超时释放

    @Test
    void 预占超时_扫描任务释放库存() {
        seedStock(100L, 10);
        // TTL 为负 => 立即过期，模拟 pay_deadline 已到
        inventoryService.reserve(8L, List.of(new InventoryItem(100L, 4)), Duration.ofSeconds(-1));

        int released = inventoryService.sweepExpiredReservations();

        assertThat(released).isEqualTo(1);
        Stock stock = stockOf(100L);
        assertThat(stock.getAvailableStock()).isEqualTo(10);
        assertThat(stock.getLockedStock()).isEqualTo(0);
        List<StockReservation> reservations = reservationRepository.findByOrderId(8L);
        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        // 再扫一轮不重复释放
        assertThat(inventoryService.sweepExpiredReservations()).isZero();
        assertThat(stockOf(100L).getAvailableStock()).isEqualTo(10);
    }

    // -------------------------------------------------------------- 校验 / 查询

    @Test
    void 校验_预览不占库() {
        seedStock(100L, 5);
        var result = inventoryService.preview(List.of(new InventoryItem(100L, 3), new InventoryItem(999L, 1)));
        assertThat(result.allAvailable()).isFalse();
        assertThat(stockOf(100L).getAvailableStock()).isEqualTo(5); // 只读不占
        assertThat(reservationRepository.count()).isZero();
    }

    @Test
    void 查询_批量库存视图() {
        seedStock(100L, 5);
        inventoryService.reserve(9L, List.of(new InventoryItem(100L, 2)), Duration.ofMinutes(30));

        var views = inventoryService.stocks(List.of(100L, 999L));
        assertThat(views).hasSize(1);
        assertThat(views.get(0).availableStock()).isEqualTo(3);
        assertThat(views.get(0).lockedStock()).isEqualTo(2);
        assertThat(views.get(0).totalStock()).isEqualTo(5);
    }
}
