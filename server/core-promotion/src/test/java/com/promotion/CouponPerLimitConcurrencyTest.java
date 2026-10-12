package com.promotion;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.promotion.domain.CouponRecord;
import com.promotion.domain.CouponTemplate;
import com.promotion.repo.CouponRecordRepository;
import com.promotion.repo.CouponTemplateRepository;
import com.promotion.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 每人限领的<b>并发正确性</b>回归测试（审计 A6-P1：限领预检「先查后写」可被并发突破）。
 *
 * <p><b>缺陷</b>：原 {@code receive} 先 {@code countByCouponIdAndUserId} 预检、再 {@code save}，
 * 两者之间没有原子性——同一用户并发 N 次领取时全部读到 {@code owned=0 < perLimit}，全部通过预检，
 * 限领 1 的券会被同一用户领走 N 张（薅羊毛经典手法）。修复：限领计数下沉到一条带子查询条件的 UPDATE
 * （{@code reserveStockWithPerLimit}），「计数 + 自增」由数据库在同一语句内完成。</p>
 *
 * <p><b>断言口径</b>：成功数<b>恰为</b> perLimit；失败数 = 线程数 - perLimit，且失败只能是
 * 超每人限领（4002，沿用原错误码）或总量领完（3002）；该用户在该模板下的券记录数不超限、
 * 无重复记录 ID、全部处于 available；模板 {@code receivedCount} 与成功数一致（无泄漏预占）。</p>
 *
 * <p>并发用例重复多轮（{@link #ROUNDS}）以放大偶发窗口——单轮通过不能证明没有 TOCTOU。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-promotion-perlimit-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "app.promotion.expire-cron=0 0 0 1 1 *",
        "app.promotion.deduct-reconcile-cron=0 0 0 1 1 *"
})
class CouponPerLimitConcurrencyTest {

    /** 并发线程数（任务书要求 8~16，取上限覆盖「最后 1 个名额」的高竞争场景）。 */
    private static final int THREADS = 16;
    /** 重复轮数：放大竞态窗口，避免单轮偶然通过。 */
    private static final int ROUNDS = 12;

    private static final long BUYER = 1001L;

    @Autowired
    private CouponService couponService;
    @Autowired
    private CouponTemplateRepository templateRepo;
    @Autowired
    private CouponRecordRepository recordRepo;
    @Autowired
    private SnowflakeIdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        recordRepo.deleteAll();
        templateRepo.deleteAll();
    }

    // ---------- ① 限领 1：同一用户 16 线程抢「最后一个名额」 ----------

    @Test
    @DisplayName("限领1：同一用户 16 线程并发领取，成功数恰为 1")
    void perLimit1_sameUserOnlyOneSucceeds() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long templateId = newTemplate(200, 1);

            Result result = receiveConcurrently(templateId, THREADS, i -> BUYER);

            assertThat(result.success.get())
                    .as("round %d：限领 1 时同一用户并发领取只允许 1 次成功", round)
                    .isEqualTo(1);
            assertThat(result.perLimitRejected.get()).as("round %d：其余请求应被 4002 限领拒绝", round)
                    .isEqualTo(THREADS - 1);
            assertThat(result.soldOut.get()).isZero();
            assertRecordState(templateId, BUYER, 1);
        }
    }

    // ---------- ② 限领 3：16 线程抢 3 个名额，恰好 3 个成功 ----------

    @Test
    @DisplayName("限领3：同一用户 16 线程并发领取，成功数恰为 3")
    void perLimit3_sameUserExactlyThreeSucceed() throws Exception {
        int perLimit = 3;
        for (int round = 0; round < ROUNDS; round++) {
            long templateId = newTemplate(200, perLimit);

            Result result = receiveConcurrently(templateId, THREADS, i -> BUYER);

            assertThat(result.success.get())
                    .as("round %d：限领 %d 时成功数必须恰好为 %d", round, perLimit, perLimit)
                    .isEqualTo(perLimit);
            assertThat(result.perLimitRejected.get()).isEqualTo(THREADS - perLimit);
            assertThat(result.soldOut.get()).isZero();
            assertRecordState(templateId, BUYER, perLimit);
        }
    }

    // ---------- ③ 限领判定按用户隔离：A 用户被限领不得影响 B 用户 ----------

    @Test
    @DisplayName("限领按用户独立计数：4 用户 × 4 线程，每人恰好领到 1 张")
    void perLimitIsScopedPerUser() throws Exception {
        int users = 4;
        int threadsPerUser = 4;
        int total = users * threadsPerUser;
        long templateId = newTemplate(200, 1);

        Result result = receiveConcurrently(templateId, total, i -> 4000L + i % users);

        assertThat(result.success.get()).isEqualTo(users);
        assertThat(result.perLimitRejected.get()).isEqualTo(total - users);
        for (long userId = 4000L; userId < 4000L + users; userId++) {
            assertRecordState(templateId, userId, 1);
        }
        assertThat(templateRepo.findById(templateId).orElseThrow().getReceivedCount()).isEqualTo(users);
        assertThat(recordRepo.findByCouponId(templateId)).hasSize(users);
    }

    // ---------- ④ 限领 + 总量双上限同时生效 ----------

    @Test
    @DisplayName("总量与限领同时生效：限领2/总量5 时成功数=5，单用户不超 2 张")
    void perLimitAndStockBothEnforced() throws Exception {
        int perLimit = 2;
        int totalCount = 5;
        int users = 8;
        int requests = users * 3; // 每用户 3 线程并发
        long templateId = newTemplate(totalCount, perLimit);

        Result result = receiveConcurrently(templateId, requests, i -> 5000L + i % users);

        assertThat(result.success.get()).isEqualTo(totalCount);
        // 超出的请求只能被「超每人限领」(4002) 或「总量领完」(3002) 拒绝
        assertThat(result.perLimitRejected.get() + result.soldOut.get()).isEqualTo(requests - totalCount);
        for (long userId = 5000L; userId < 5000L + users; userId++) {
            assertThat(recordRepo.countByCouponIdAndUserId(templateId, userId))
                    .as("userId=%s 不得突破每人限领", userId).isLessThanOrEqualTo(perLimit);
        }
        assertThat(templateRepo.findById(templateId).orElseThrow().getReceivedCount()).isEqualTo(totalCount);
        assertThat(recordRepo.findByCouponId(templateId)).hasSize(totalCount);
    }

    // ---------- ⑤ 管理端定向发放计入用户持券数 ----------

    @Test
    @DisplayName("定向发放计入持券数：已发 1 张时限领1 的用户无法再自助领取")
    void distributeCountsAgainstPerUserLimit() {
        long templateId = newTemplate(200, 1);
        couponService.distribute(templateId, BUYER); // 发放不占限领名额，但计入该用户持券数

        BizException e = assertThrows(BizException.class, () -> couponService.receive(templateId, BUYER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR.getCode());
        assertRecordState(templateId, BUYER, 1);

        // 其他用户不受牵连
        assertThat(couponService.receive(templateId, 2002L).status()).isEqualTo("available");
    }

    // ---------- ⑥ 串行对照：顺序领取同样受限领约束（防止用例只覆盖并发分支） ----------

    @Test
    @DisplayName("顺序领取：第 perLimit+1 次领取返回 4002")
    void sequentialReceiveRespectsPerLimit() {
        long templateId = newTemplate(200, 2);
        couponService.receive(templateId, BUYER);
        couponService.receive(templateId, BUYER);
        BizException e = assertThrows(BizException.class, () -> couponService.receive(templateId, BUYER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR.getCode());
        assertRecordState(templateId, BUYER, 2);
    }

    // ---------- helpers ----------

    /** 并发结果统计（按错误码分流）。 */
    private static final class Result {
        private final AtomicInteger success = new AtomicInteger();
        private final AtomicInteger perLimitRejected = new AtomicInteger();
        private final AtomicInteger soldOut = new AtomicInteger();
        private final AtomicInteger unexpected = new AtomicInteger();
    }

    /**
     * 起 {@code threads} 个线程同时领取同一张券（用户按 {@code userIdFor} 分配）。
     * 业务失败按错误码分流统计；<b>非预期异常由 {@code Future.get} 原样抛出 → 用例失败（不吞异常）</b>。
     */
    private Result receiveConcurrently(long templateId, int threads, IntFunction<Long> userIdFor)
            throws Exception {
        Result result = new Result();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long userId = userIdFor.apply(i);
            tasks.add(() -> {
                start.await();
                try {
                    couponService.receive(templateId, userId);
                    result.success.incrementAndGet();
                } catch (BizException e) {
                    if (e.getCode() == BizErrorCode.ORDER_STATUS_ERROR.getCode()) {
                        result.perLimitRejected.incrementAndGet();
                    } else if (e.getCode() == BizErrorCode.OUT_OF_STOCK.getCode()) {
                        result.soldOut.incrementAndGet();
                    } else {
                        result.unexpected.incrementAndGet();
                    }
                }
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(result.unexpected.get()).as("出现非预期业务错误码").isZero();
        return result;
    }

    /** 建一张生效中的满减券（绕开 HTTP，直接落库，专注并发语义）。 */
    private long newTemplate(int totalCount, int perLimit) {
        CouponTemplate template = new CouponTemplate();
        template.setId(idGenerator.nextId());
        template.setName("限领并发测试券");
        template.setType(CouponTemplate.TYPE_FULL_CUT);
        template.setValue(new BigDecimal("20"));
        template.setMinAmount(BigDecimal.ZERO);
        template.setTotalCount(totalCount);
        template.setPerLimit(perLimit);
        template.setStartTime(Instant.now().minus(1, ChronoUnit.DAYS));
        template.setEndTime(Instant.now().plus(1, ChronoUnit.DAYS));
        template.setStatus(1);
        templateRepo.saveAndFlush(template);
        return template.getId();
    }

    /** 断言：该用户在模板下的券记录数 == expected、无重复记录 ID、状态均为 available。 */
    private void assertRecordState(long templateId, long userId, int expected) {
        List<CouponRecord> records = recordRepo.findByCouponIdAndUserId(templateId, userId);
        assertThat(records).as("userId=%s 的券记录数", userId).hasSize(expected);
        assertThat(records.stream().map(CouponRecord::getId).collect(Collectors.toSet()))
                .as("券记录 ID 不得重复").hasSize(expected);
        assertThat(records).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(CouponRecord.STATUS_AVAILABLE));
        assertThat(recordRepo.countByCouponIdAndUserId(templateId, userId))
                .as("countByCouponIdAndUserId 不得超限").isEqualTo(expected);
    }
}