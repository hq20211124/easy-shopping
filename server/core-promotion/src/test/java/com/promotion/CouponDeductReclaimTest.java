package com.promotion;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.domain.CouponRecord;
import com.promotion.repo.CouponRecordRepository;
import com.promotion.repo.CouponTemplateRepository;
import com.promotion.service.CouponService;
import com.promotion.support.DeductedOrderVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 核销未确认兜底回收（P0-G）回归测试。
 *
 * <p>背景：核销（券置 used）与订单落库/支付成功是跨服务两步。订单最终失败/取消/关闭时若未退券，
 * 券会永久停留 used——用户既拿不到券、订单也没有。本测试覆盖「核销超时 + 回查订单确认订单没了
 * → 回收为 available」的回收路径，以及三条不可误退的保护：订单仍有效不回收、下游不可达（UNKNOWN）
 * 不回收、未超时不回收。</p>
 *
 * <p>每个用例都把 {@code used_time}（核销时间 = deducted_at）往前拨 61 分钟，
 * 保证候选集<b>确实包含</b>该券——否则「不该回收」的用例会因为候选集为空而假绿。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-promotion-reclaim-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "app.promotion.expire-cron=0 0 0 1 1 *",
        "app.promotion.deduct-reconcile-cron=0 0 0 1 1 *",
        "app.promotion.deduct-timeout-minutes=60"
})
@AutoConfigureMockMvc
class CouponDeductReclaimTest {

    private static final long BUYER = 1001L;
    /** 核销后已过去 61 分钟 > 默认超时 60 分钟。 */
    private static final long AGED_MINUTES = 61L;
    private static final Duration TIMEOUT = Duration.ofMinutes(60);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CouponService couponService;
    @Autowired
    private CouponTemplateRepository templateRepo;
    @Autowired
    private CouponRecordRepository recordRepo;

    @BeforeEach
    void setUp() {
        recordRepo.deleteAll();
        templateRepo.deleteAll();
    }

    // ---------- 核销后模拟订单不存在 → 回收回 available ----------

    @Test
    void 核销后订单不存在_回收任务把券退回可用() throws Exception {
        long templateId = createTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 910L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);
        assertThat(recordRepo.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(CouponRecord.STATUS_USED);

        // 回查订单结论 = 订单不存在/已取消/已关闭，且已超过核销超时窗口
        int reclaimed = couponService.reclaimUnconfirmedDeducted(
                Instant.now(), TIMEOUT, orderId -> DeductedOrderVerifier.Outcome.ORDER_GONE);

        assertThat(reclaimed).isEqualTo(1);
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(CouponRecord.STATUS_AVAILABLE);
        assertThat(record.getUsedOrderId()).isNull();
        assertThat(record.getUsedTime()).isNull();
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isZero();
    }

    // ---------- 重复执行回收幂等不报错 ----------

    @Test
    void 重复执行回收幂等不报错不重复扣计数() throws Exception {
        long templateId = createTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 911L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isEqualTo(1);

        DeductedOrderVerifier gone = orderId -> DeductedOrderVerifier.Outcome.ORDER_GONE;
        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT, gone)).isEqualTo(1);
        // 重复执行不报错，且不会把 usedCount 扣成负数
        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT, gone)).isZero();
        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT, gone)).isZero();
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isZero();
        assertThat(recordRepo.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(CouponRecord.STATUS_AVAILABLE);
    }

    // ---------- 保护：订单仍有效不回收（不能误退已消费券） ----------

    @Test
    void 订单仍有效_不回收() throws Exception {
        long templateId = createTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 912L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);

        int reclaimed = couponService.reclaimUnconfirmedDeducted(
                Instant.now(), TIMEOUT, orderId -> DeductedOrderVerifier.Outcome.ORDER_ALIVE);

        assertThat(reclaimed).isZero();
        assertUsedUntouched(templateId, recordId);
    }

    // ---------- 保护：下游不可达（UNKNOWN）不回收 ----------

    @Test
    void 下游不可达UNKNOWN_不回收() throws Exception {
        long templateId = createTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 913L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);

        int reclaimed = couponService.reclaimUnconfirmedDeducted(
                Instant.now(), TIMEOUT, orderId -> DeductedOrderVerifier.Outcome.UNKNOWN);

        assertThat(reclaimed).isZero();
        assertUsedUntouched(templateId, recordId);
    }

    // ---------- 保护：未超时（仍在下单支付窗口内）不回收 ----------

    @Test
    void 未超时_不回收() throws Exception {
        long templateId = createTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 914L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);

        // 超时窗口远大于「核销至今」，候选集为空（订单结论不参与判定）
        int reclaimed = couponService.reclaimUnconfirmedDeducted(
                Instant.now(), Duration.ofDays(365), orderId -> DeductedOrderVerifier.Outcome.ORDER_GONE);

        assertThat(reclaimed).isZero();
        assertUsedUntouched(templateId, recordId);
    }

    // ---------- 保护：校验器抛异常按 UNKNOWN 处理（不回收、不崩掉整轮） ----------

    @Test
    void 校验器抛异常按UNKNOWN处理_不回收且不抛出() throws Exception {
        long templateId = createTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 915L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);

        DeductedOrderVerifier boom = orderId -> {
            throw new IllegalStateException("core-order 连接超时");
        };
        assertThat(couponService.reclaimUnconfirmedDeducted(Instant.now(), TIMEOUT, boom)).isZero();
        assertUsedUntouched(templateId, recordId);
    }

    // ---------- 默认入口：core-order 未启动（不可达）时不误退 ----------

    @Test
    void 默认入口在下游不可达时不误退() throws Exception {
        long templateId = createTemplate();
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 916L, BUYER);
        ageDeductTime(recordId, AGED_MINUTES);

        // 注入的 REST 校验器在本测试环境没有 core-order（:8086），结论应为 UNKNOWN → 不回收
        assertThat(couponService.reclaimUnconfirmedDeducted()).isZero();
        assertUsedUntouched(templateId, recordId);
    }

    // ---------- helpers ----------

    /** 券应仍处于「已核销且计数未回退」状态（误退会让用户白拿一张券）。 */
    private void assertUsedUntouched(long templateId, long recordId) {
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(CouponRecord.STATUS_USED);
        assertThat(record.getUsedOrderId()).isNotNull();
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isEqualTo(1);
    }

    /** 把核销时间往前拨，模拟「核销后已超过超时窗口」。 */
    private void ageDeductTime(long recordId, long minutes) {
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        record.setUsedTime(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        recordRepo.saveAndFlush(record);
    }

    private long createTemplate() throws Exception {
        String json = "{\"name\":\"回收测试券\",\"type\":1,\"value\":20"
                + ",\"minAmount\":100,\"totalCount\":10,\"perLimit\":5"
                + ",\"startTime\":\"" + Instant.now().minus(1, ChronoUnit.DAYS) + "\""
                + ",\"endTime\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}";
        MvcResult result = mockMvc.perform(post("/api/admin/coupon")
                        .header("X-User-Id", "1").header("X-User-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        return data.path("id").asLong();
    }

    // 保留错误码引用自检，确保 BizErrorCode/BizException 的编译期依赖不误删
    @Test
    void 常量引用自检() {
        assertThat(BizErrorCode.NO_PERMISSION.getCode()).isEqualTo(1004);
        assertThatThrownBy(() -> {
            throw new BizException(BizErrorCode.NO_PERMISSION, "自检");
        }).isInstanceOf(BizException.class);
    }
}
