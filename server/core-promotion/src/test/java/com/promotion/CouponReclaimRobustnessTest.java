package com.promotion;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.promotion.domain.CouponRecord;
import com.promotion.domain.CouponTemplate;
import com.promotion.job.CouponDeductReconcileJob;
import com.promotion.repo.CouponRecordRepository;
import com.promotion.repo.CouponTemplateRepository;
import com.promotion.service.CouponService;
import com.promotion.support.DeductedOrderVerifier;
import com.promotion.support.RestDeductedOrderVerifier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 核销兜底回收（P0-G 后续）的<b>稳健性</b>回归测试——验证 fail-safe 性质，<b>不改业务语义</b>。
 *
 * <p>判定链：{@code used} 且核销超时 → <b>回查订单</b> → 只有「订单确实没了」（不存在/已取消/已关闭）
 * 才回收。铁律：<b>任何不确定都判 UNKNOWN 且不回收</b>——误回收 = 把已支付订单真消费掉的券白送用户
 * （资损 + 薅羊毛）；漏回收只是人工可查的对账项。</p>
 *
 * <p>覆盖：① <b>未知/越界状态码</b>（既非 0-7 的 8 值）→ 不回收——用真实
 * {@link RestDeductedOrderVerifier} 打桩 core-order 端到端验证（不是只测 lambda 桩）；
 * ② 状态字段缺失/非数字（契约漂移）→ 不回收；③ 正常 0 基 8 值映射未被加固误伤；
 * ④ 4001/其他业务码/不可达/空响应体 → UNKNOWN；⑤ 单条回查超时只影响本条，不中断同轮其他记录；
 * ⑥ 无订单绑定的脏数据跳过；⑦ 调度任务吞异常；⑧ 测试环境 cron 置远期（禁用自动触发）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-promotion-reclaim-robust-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "app.promotion.expire-cron=0 0 0 1 1 *",
        "app.promotion.deduct-reconcile-cron=0 0 0 1 1 *",
        "app.promotion.deduct-timeout-minutes=60"
})
class CouponReclaimRobustnessTest {

    private static final long BUYER = 1001L;
    /** 核销后已过去 61 分钟 > 默认超时 60 分钟（保证候选集确实包含该券）。 */
    private static final long AGED_MINUTES = 61L;
    private static final Duration TIMEOUT = Duration.ofMinutes(60);

    @Autowired
    private CouponService couponService;
    @Autowired
    private CouponTemplateRepository templateRepo;
    @Autowired
    private CouponRecordRepository recordRepo;
    @Autowired
    private Environment environment;

    private HttpServer stubCoreOrder;

    @BeforeEach
    void setUp() {
        recordRepo.deleteAll();
        templateRepo.deleteAll();
    }

    @AfterEach
    void tearDown() {
        if (stubCoreOrder != null) {
            stubCoreOrder.stop(0);
            stubCoreOrder = null;
        }
    }

    // ---------- ① 回查返回未知/越界状态码 → 绝不回收（fail-safe，本项核心） ----------

    @Test
    @DisplayName("未知状态码（99/8/-1，既非 0-7）：判 UNKNOWN → 绝不回收")
    void unknownOrderStatusNeverReclaims() throws Exception {
        long templateId = newTemplate();
        int[] unknownStatuses = {99, 8, -1};
        long[] recordIds = new long[unknownStatuses.length];

        for (int i = 0; i < unknownStatuses.length; i++) {
            long recordId = couponService.receive(templateId, BUYER).couponId();
            recordIds[i] = recordId;
            couponService.deduct(recordId, 700L + i, BUYER);
            ageDeductTime(recordId, AGED_MINUTES);
        }

        Map<Long, Integer> statusByOrder = Map.of(700L, 99, 701L, 8, 702L, -1);
        RestDeductedOrderVerifier verifier = startStub(path -> {
            long orderId = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            return envelope(0, "{\"status\":" + statusByOrder.get(orderId) + "}");
        });

        for (long orderId = 700L; orderId <= 702L; orderId++) {
            assertThat(verifier.verify(orderId))
                    .as("orderId=%s 越界状态码必须判 UNKNOWN（不得判 ORDER_GONE）", orderId)
                    .isEqualTo(DeductedOrderVerifier.Outcome.UNKNOWN);
        }
        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT, verifier))
                .as("回查结论不确定时一律不回收").isZero();
        for (long recordId : recordIds) {
            assertUsedUntouched(templateId, recordId, unknownStatuses.length);
        }
    }

    @Test
    @DisplayName("状态字段缺失/非数字（契约漂移）→ 不回收")
    void missingOrNonNumericStatusNeverReclaims() throws Exception {
        long templateId = newTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 710L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);

        RestDeductedOrderVerifier nonNumeric = startStub(path -> envelope(0, "{\"status\":\"weird\"}"));
        assertThat(nonNumeric.verify(710L)).isEqualTo(DeductedOrderVerifier.Outcome.UNKNOWN);

        RestDeductedOrderVerifier missing = startStub(path -> envelope(0, "{\"otherField\":1}"));
        assertThat(missing.verify(710L)).isEqualTo(DeductedOrderVerifier.Outcome.UNKNOWN);

        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT, nonNumeric)).isZero();
        assertUsedUntouched(templateId, recordId);
    }

    // ---------- ② 正常 0 基 8 值映射未被加固误伤 ----------

    @Test
    @DisplayName("0 基 8 值口径不变：6/7 → 回收；0~5 → 不回收")
    void canonicalStatusMappingUnchanged() throws Exception {
        long templateId = newTemplate();
        long alive = couponService.receive(templateId, BUYER).couponId();
        long cancelled = couponService.receive(templateId, BUYER).couponId();
        long closed = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(alive, 801L, BUYER);
        couponService.deduct(cancelled, 802L, BUYER);
        couponService.deduct(closed, 803L, BUYER);
        ageDeductTime(alive, AGED_MINUTES);
        ageDeductTime(cancelled, AGED_MINUTES);
        ageDeductTime(closed, AGED_MINUTES);

        Map<Long, Integer> statusByOrder = new ConcurrentHashMap<>();
        statusByOrder.put(801L, 0); // 待付款 → 订单仍有效
        statusByOrder.put(802L, 6); // 已取消 → 可回收
        statusByOrder.put(803L, 7); // 已关闭 → 可回收
        RestDeductedOrderVerifier verifier = startStub(path -> {
            long orderId = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            return envelope(0, "{\"status\":" + statusByOrder.get(orderId) + "}");
        });

        assertThat(verifier.verify(801L)).isEqualTo(DeductedOrderVerifier.Outcome.ORDER_ALIVE);
        assertThat(verifier.verify(802L)).isEqualTo(DeductedOrderVerifier.Outcome.ORDER_GONE);
        assertThat(verifier.verify(803L)).isEqualTo(DeductedOrderVerifier.Outcome.ORDER_GONE);

        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT, verifier)).isEqualTo(2);
        assertThat(recordRepo.findById(alive).orElseThrow().getStatus()).isEqualTo(CouponRecord.STATUS_USED);
        assertThat(recordRepo.findById(cancelled).orElseThrow().getStatus()).isEqualTo(CouponRecord.STATUS_AVAILABLE);
        assertThat(recordRepo.findById(closed).orElseThrow().getStatus()).isEqualTo(CouponRecord.STATUS_AVAILABLE);
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isEqualTo(1);
    }

    // ---------- ③ 响应码口径 + 传输层失败一律 UNKNOWN ----------

    @Test
    @DisplayName("4001 订单不存在 → 回收；其他业务失败码 → UNKNOWN")
    void businessCodeMapping() throws Exception {
        RestDeductedOrderVerifier notFound =
                startStub(path -> envelope(BizErrorCode.ORDER_NOT_FOUND.getCode(), "{}"));
        assertThat(notFound.verify(900L)).isEqualTo(DeductedOrderVerifier.Outcome.ORDER_GONE);

        RestDeductedOrderVerifier bizFail = startStub(path -> envelope(5000, "{}"));
        assertThat(bizFail.verify(900L)).isEqualTo(DeductedOrderVerifier.Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("core-order 不可达（连接被拒）/ 空 data → UNKNOWN，不抛异常")
    void unreachableOrEmptyDataIsUnknown() throws Exception {
        // 端口 1 上不会有服务：连接失败必须被吞成 UNKNOWN（只告警，不阻塞整轮扫描）
        RestDeductedOrderVerifier dead = new RestDeductedOrderVerifier("http://127.0.0.1:1");
        assertThat(dead.verify(901L)).isEqualTo(DeductedOrderVerifier.Outcome.UNKNOWN);
        assertThat(dead.verify(null)).isEqualTo(DeductedOrderVerifier.Outcome.UNKNOWN);

        RestDeductedOrderVerifier emptyData = startStub(path -> envelope(0, "null"));
        assertThat(emptyData.verify(901L)).isEqualTo(DeductedOrderVerifier.Outcome.UNKNOWN);
    }

    // ---------- ④ 单条回查超时只影响本条，不中断同轮其他记录 ----------

    @Test
    @DisplayName("首条回查抛读超时：同轮后续记录仍被正常回收")
    void verifierTimeoutDoesNotAbortRound() {
        long templateId = newTemplate();
        long timedOut = couponService.receive(templateId, BUYER).couponId();
        long gone = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(timedOut, 910L, BUYER);
        couponService.deduct(gone, 911L, BUYER);
        ageDeductTime(timedOut, AGED_MINUTES);
        ageDeductTime(gone, AGED_MINUTES);

        List<Long> calls = new ArrayList<>();
        DeductedOrderVerifier flaky = orderId -> {
            calls.add(orderId);
            if (orderId == 910L) {
                throw new IllegalStateException("Read timed out"); // 模拟 1s 读超时
            }
            return DeductedOrderVerifier.Outcome.ORDER_GONE;
        };

        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT, flaky)).isEqualTo(1);
        assertThat(calls).contains(910L, 911L);
        assertThat(recordRepo.findById(timedOut).orElseThrow().getStatus()).isEqualTo(CouponRecord.STATUS_USED);
        assertThat(recordRepo.findById(gone).orElseThrow().getStatus()).isEqualTo(CouponRecord.STATUS_AVAILABLE);
    }

    // ---------- ⑤ 无订单绑定的脏数据：跳过且不动状态 ----------

    @Test
    @DisplayName("used_order_id 为空的脏数据：不回收、不崩溃")
    void recordWithoutOrderBindingIsSkipped() {
        long templateId = newTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 920L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);

        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        record.setUsedOrderId(null);
        recordRepo.saveAndFlush(record);

        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT,
                orderId -> DeductedOrderVerifier.Outcome.ORDER_GONE)).isZero();
        assertThat(recordRepo.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(CouponRecord.STATUS_USED);
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isEqualTo(1);
    }

    // ---------- ⑥ 调度任务：异常只告警不崩；测试环境 cron 已禁用 ----------

    @Test
    @DisplayName("兜底任务遇到异常只记日志不抛出（不阻塞后续调度）")
    void jobSwallowsException() {
        CouponService exploding = new CouponService(templateRepo, recordRepo, null, null, 60L) {
            @Override
            public int reclaimUnconfirmedDeducted() {
                throw new IllegalStateException("core-order 全面不可用");
            }
        };
        assertThatCode(new CouponDeductReconcileJob(exploding)::schedule).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cron 可配且测试环境置为远期：不会自动触发真实回收")
    void cronDisabledInTests() {
        assertThat(environment.getProperty("app.promotion.deduct-reconcile-cron")).isEqualTo("0 0 0 1 1 *");
        assertThat(environment.getProperty("app.promotion.expire-cron")).isEqualTo("0 0 0 1 1 *");
        assertThat(environment.getProperty("app.promotion.deduct-timeout-minutes")).isEqualTo("60");
    }

    // ---------- helpers ----------

    /** 券应仍处于「已核销且计数未回退」状态（误退会让用户白拿一张券）。 */
    private void assertUsedUntouched(long templateId, long recordId, int expectedUsedCount) {
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(CouponRecord.STATUS_USED);
        assertThat(record.getUsedOrderId()).isNotNull();
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isEqualTo(expectedUsedCount);
    }

    private void assertUsedUntouched(long templateId, long recordId) {
        assertUsedUntouched(templateId, recordId, 1);
    }

    private void ageDeductTime(long recordId, long minutes) {
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        record.setUsedTime(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        recordRepo.saveAndFlush(record);
    }

    /** 建一张生效中的满减券（避开管理端 HTTP，专注回收逻辑）。 */
    private long newTemplate() {
        CouponTemplate template = new CouponTemplate();
        template.setId(new SnowflakeIdGenerator(25).nextId());
        template.setName("兜底回收稳健性测试券");
        template.setType(CouponTemplate.TYPE_FULL_CUT);
        template.setValue(new BigDecimal("20"));
        template.setMinAmount(BigDecimal.ZERO);
        template.setTotalCount(50);
        template.setPerLimit(10);
        template.setStartTime(Instant.now().minus(1, ChronoUnit.DAYS));
        template.setEndTime(Instant.now().plus(1, ChronoUnit.DAYS));
        template.setStatus(1);
        return templateRepo.saveAndFlush(template).getId();
    }

    /** 打桩 core-order internal 订单快照端点，返回真实的 {@link RestDeductedOrderVerifier}。 */
    private RestDeductedOrderVerifier startStub(StubResponder responder) throws IOException {
        if (stubCoreOrder != null) {
            stubCoreOrder.stop(0);
        }
        stubCoreOrder = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubCoreOrder.createContext("/api/v1/internal/orders/", exchange -> {
            byte[] payload = responder.respond(exchange.getRequestURI().getPath())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        stubCoreOrder.start();
        return new RestDeductedOrderVerifier("http://127.0.0.1:" + stubCoreOrder.getAddress().getPort());
    }

    private String envelope(int code, String dataJson) {
        return "{\"code\":" + code + ",\"msg\":\"\",\"data\":" + dataJson + "}";
    }

    @FunctionalInterface
    private interface StubResponder {
        String respond(String path);
    }
}