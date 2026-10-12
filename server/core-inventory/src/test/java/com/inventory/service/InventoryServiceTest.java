package com.inventory.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.inventory.domain.ReservationStatus;
import com.inventory.domain.Stock;
import com.inventory.domain.StockReservation;
import com.inventory.dto.InventoryDtos.InventoryItem;
import com.inventory.repo.StockRepository;
import com.inventory.repo.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
