package com.promotion;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.promotion.domain.CouponRecord;
import com.promotion.domain.CouponTemplate;
import com.promotion.repo.CouponRecordRepository;
import com.promotion.repo.CouponTemplateRepository;
import com.promotion.service.CouponService;
import com.promotion.web.dto.PreviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 券<b>试算 / 核销</b>的边界完备性测试（只补测试；发现的 2 处真缺陷已在类注释中列明并最小修复）。
 *
 * <p>口径来源（以 plans 为准）：{@code plans/admin-backend.md} 7.2.1（管理端券接口，无买家侧核销端点）、
 * 4.4.1（超时关单 → 营销侧回退占用券）、{@code plans/ios-backend.md} 5.2.4（买家端只有
 * list/claim/available 三个券端点，<b>核销不是买家端能力</b>，只由 core-order 下单链路经
 * {@code /api/v1/internal/coupons/{id}/deduct} 调用）。因此本服务的「订单已支付后再调用 deduct」
 * 语义只能是：<b>同 orderId 幂等成功（订单重试安全）、不同 orderId 拒绝</b>——
 * 本服务不查订单状态（跨域查询只服务于 P0-G 兜底回收），该职责在 core-order。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-promotion-boundary-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "app.promotion.expire-cron=0 0 0 1 1 *",
        "app.promotion.deduct-reconcile-cron=0 0 0 1 1 *"
})
@AutoConfigureMockMvc
class CouponBoundaryTest {

    private static final long BUYER = 1001L;
    private static final long ATTACKER = 2002L;

    @Autowired
    private MockMvc mockMvc;
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

    // ================= 试算入参边界 =================

    @Test
    @DisplayName("preview 空数组 → HTTP 400/1001（不再以 200 信封静默返回 eligible=false）")
    void previewEmptyAmountArrayIsBadRequest() throws Exception {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1), BUYER).couponId();

        mockMvc.perform(post("/api/v1/coupons/preview").header("X-User-Id", String.valueOf(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponId\":" + recordId + ",\"amount\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(BizErrorCode.PARAM_ERROR.getCode()));

        // 服务层同样拒绝（internal/服务内调用不经过 Bean 校验）
        BizException e = assertThrows(BizException.class,
                () -> couponService.preview(recordId, List.of(), BUYER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("preview amount 缺失/含 null 元素/负数 → 一律 1001")
    void previewIllegalAmountsRejected() throws Exception {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1), BUYER).couponId();

        mockMvc.perform(post("/api/v1/coupons/preview").header("X-User-Id", String.valueOf(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponId\":" + recordId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(BizErrorCode.PARAM_ERROR.getCode()));

        mockMvc.perform(post("/api/v1/coupons/preview").header("X-User-Id", String.valueOf(BUYER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponId\":" + recordId + ",\"amount\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(BizErrorCode.PARAM_ERROR.getCode()));

        BizException negative = assertThrows(BizException.class,
                () -> couponService.preview(recordId, List.of(new BigDecimal("10"), new BigDecimal("-1")), BUYER));
        assertThat(negative.getCode()).isEqualTo(BizErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("非法入参优先于券状态：已过期券 + 空数组仍报 1001（不伪装成「券已过期」）")
    void illegalAmountBeatsRecordState() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1), BUYER).couponId();
        expireRecord(recordId);

        BizException e = assertThrows(BizException.class,
                () -> couponService.preview(recordId, List.of(), BUYER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("试算他人券 → 1004（归属优先于参数校验）")
    void previewForeignCouponRejected() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1), BUYER).couponId();
        BizException e = assertThrows(BizException.class,
                () -> couponService.preview(recordId, List.of(new BigDecimal("100")), ATTACKER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.NO_PERMISSION.getCode());
    }

    @Test
    @DisplayName("试算不存在券记录 → 404")
    void previewUnknownRecordIsNotFound() {
        BizException e = assertThrows(BizException.class,
                () -> couponService.preview(999999L, List.of(new BigDecimal("10")), BUYER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.NOT_FOUND.getCode());
    }

    // ================= 门槛边界 =================

    @Test
    @DisplayName("门槛边界：恰好等于门槛可用、低 1 分不可用（跨多商品求和后判定）")
    void thresholdBoundaryUsesOrderTotal() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "100", 1), BUYER).couponId();

        PreviewResponse justEnough = couponService.preview(recordId, List.of(new BigDecimal("60"), new BigDecimal("40")), BUYER);
        assertThat(justEnough.eligible()).isTrue();
        assertThat(justEnough.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(justEnough.discount()).isEqualByComparingTo("20.00");
        assertThat(justEnough.finalAmount()).isEqualByComparingTo("80.00");
        assertThat(justEnough.reason()).isNull();

        PreviewResponse oneCentShort = couponService.preview(recordId,
                List.of(new BigDecimal("60"), new BigDecimal("39.99")), BUYER);
        assertThat(oneCentShort.eligible()).isFalse();
        assertThat(oneCentShort.discount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(oneCentShort.reason()).contains("未满足最低消费");
    }

    // ================= 折扣券边界 =================

    @Test
    @DisplayName("折扣券口径：value=8.5 表示 85 折（减 15%），HALF_UP 两位小数")
    void discountCouponRoundingBoundaries() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_DISCOUNT, "8.5", "0", 1), BUYER).couponId();

        assertThat(couponService.preview(recordId, List.of(new BigDecimal("100")), BUYER).discount())
                .isEqualByComparingTo("15.00");
        // 9.99 × 15% = 1.4985 → HALF_UP → 1.50
        assertThat(couponService.preview(recordId, List.of(new BigDecimal("9.99")), BUYER).discount())
                .isEqualByComparingTo("1.50");
        // 0.05 × 15% = 0.0075 → HALF_UP → 0.01（不进位到 0）
        assertThat(couponService.preview(recordId, List.of(new BigDecimal("0.05")), BUYER).discount())
                .isEqualByComparingTo("0.01");
        // 零元订单 → 优惠 0，不返回负数
        PreviewResponse zero = couponService.preview(recordId, List.of(BigDecimal.ZERO), BUYER);
        assertThat(zero.eligible()).isTrue();
        assertThat(zero.discount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.finalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("折扣券上限：9.9 折只减 1%，最终金额不为负")
    void steepDiscountKeepsPositiveAmount() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_DISCOUNT, "9.9", "0", 1), BUYER).couponId();
        PreviewResponse preview = couponService.preview(recordId, List.of(new BigDecimal("1000")), BUYER);

        assertThat(preview.eligible()).isTrue();
        assertThat(preview.discount()).isEqualByComparingTo("10.00");
        assertThat(preview.finalAmount()).isEqualByComparingTo("990.00");
        assertThat(preview.finalAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("折扣券 value 越界（=10 / =0 / 负数）建模板即拒 1001；0<v<10 通过")
    void discountValueValidationBoundaries() throws Exception {
        assertThat(createTemplateViaApi(CouponTemplate.TYPE_DISCOUNT, "10", "0")).isEqualTo(
                BizErrorCode.PARAM_ERROR.getCode());
        assertThat(createTemplateViaApi(CouponTemplate.TYPE_DISCOUNT, "0", "0")).isEqualTo(
                BizErrorCode.PARAM_ERROR.getCode());
        assertThat(createTemplateViaApi(CouponTemplate.TYPE_DISCOUNT, "-1", "0")).isEqualTo(
                BizErrorCode.PARAM_ERROR.getCode());
        assertThat(createTemplateViaApi(CouponTemplate.TYPE_DISCOUNT, "9.99", "0")).isZero();
        assertThat(createTemplateViaApi(CouponTemplate.TYPE_DISCOUNT, "0.01", "0")).isZero();
    }

    @Test
    @DisplayName("满减券面额 > 订单额 → 优惠封顶为订单额（finalAmount 不为负）")
    void fullCutDiscountCapsAtOrderAmount() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "50", "0", 1), BUYER).couponId();
        PreviewResponse preview = couponService.preview(recordId, List.of(new BigDecimal("20")), BUYER);

        assertThat(preview.eligible()).isTrue();
        assertThat(preview.discount()).isEqualByComparingTo("20.00");
        assertThat(preview.finalAmount()).isEqualByComparingTo("0.00");
    }

    // ================= 券状态对试算的影响 =================

    @Test
    @DisplayName("已核销券试算 → eligible=false 且带原因（不抛异常，前端可展示）")
    void previewOnUsedCouponIsIneligible() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1), BUYER).couponId();
        couponService.deduct(recordId, 600L, BUYER);

        PreviewResponse preview = couponService.preview(recordId, List.of(new BigDecimal("1000")), BUYER);
        assertThat(preview.eligible()).isFalse();
        assertThat(preview.discount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(preview.reason()).contains("券不可用");
    }

    @Test
    @DisplayName("已过期券试算 → eligible=false（reason 含「券已过期」）")
    void previewOnExpiredCouponIsIneligible() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1), BUYER).couponId();
        expireRecord(recordId);

        PreviewResponse preview = couponService.preview(recordId, List.of(new BigDecimal("1000")), BUYER);
        assertThat(preview.eligible()).isFalse();
        assertThat(preview.reason()).contains("券已过期");
    }

    // ================= 核销边界（订单已支付后再调用） =================

    @Test
    @DisplayName("订单已支付后重复核销（同 orderId）→ 幂等成功且不重复计数")
    void deductAfterOrderPaidIsIdempotentForSameOrder() {
        long templateId = newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1);
        long recordId = couponService.receive(templateId, BUYER).couponId();

        couponService.deduct(recordId, 700L, BUYER);
        CouponRecordResponseView first = new CouponRecordResponseView(couponService.deduct(recordId, 700L, BUYER));
        CouponRecordResponseView second = new CouponRecordResponseView(couponService.deduct(recordId, 700L, BUYER));

        assertThat(first.status()).isEqualTo("used");
        assertThat(second.status()).isEqualTo("used");
        assertThat(recordRepo.findById(recordId).orElseThrow().getUsedOrderId()).isEqualTo(700L);
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount())
                .as("订单重试不得重复计数").isEqualTo(1);
    }

    @Test
    @DisplayName("换一个订单再核销已被占用的券 → 4002（不得把券挪到别的单）")
    void deductWithAnotherOrderRejected() {
        long recordId = couponService.receive(newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1), BUYER).couponId();
        couponService.deduct(recordId, 701L, BUYER);

        BizException e = assertThrows(BizException.class, () -> couponService.deduct(recordId, 702L, BUYER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR.getCode());
        assertThat(recordRepo.findById(recordId).orElseThrow().getUsedOrderId()).isEqualTo(701L);
    }

    @Test
    @DisplayName("已核销券被「错单退回」→ 4002 且状态不变（防退错单的券）")
    void releaseWithMismatchedOrderRejected() {
        long templateId = newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1);
        long recordId = couponService.receive(templateId, BUYER).couponId();
        couponService.deduct(recordId, 703L, BUYER);

        BizException e = assertThrows(BizException.class, () -> couponService.release(recordId, 999L, BUYER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR.getCode());
        assertThat(recordRepo.findById(recordId).orElseThrow().getStatus()).isEqualTo(CouponRecord.STATUS_USED);
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("过期券核销 → 4002（过期判定在 SQL WHERE 内，非先查后改）")
    void deductExpiredCouponRejected() {
        long templateId = newTemplate(CouponTemplate.TYPE_FULL_CUT, "20", "0", 1);
        long recordId = couponService.receive(templateId, BUYER).couponId();
        expireRecord(recordId);

        BizException e = assertThrows(BizException.class, () -> couponService.deduct(recordId, 704L, BUYER));
        assertThat(e.getCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR.getCode());
        assertThat(templateRepo.findById(templateId).orElseThrow().getUsedCount()).isZero();
    }

    // ================= helpers =================

    /** 轻量视图：只取核销响应里用得到的字段（避免测试依赖整个 DTO）。 */
    private record CouponRecordResponseView(String status) {
        CouponRecordResponseView(com.promotion.web.dto.CouponRecordResponse response) {
            this(response.status());
        }
    }

    private long newTemplate(int type, String value, String minAmount, int perLimit) {
        CouponTemplate template = new CouponTemplate();
        template.setId(idGenerator.nextId());
        template.setName("边界测试券");
        template.setType(type);
        template.setValue(new BigDecimal(value));
        template.setMinAmount(new BigDecimal(minAmount));
        template.setTotalCount(100);
        template.setPerLimit(perLimit);
        template.setStartTime(Instant.now().minus(1, ChronoUnit.DAYS));
        template.setEndTime(Instant.now().plus(1, ChronoUnit.DAYS));
        template.setStatus(1);
        return templateRepo.saveAndFlush(template).getId();
    }

    private int createTemplateViaApi(int type, String value, String minAmount) throws Exception {
        String json = "{\"name\":\"折扣边界券\",\"type\":" + type + ",\"value\":" + value
                + ",\"minAmount\":" + minAmount + ",\"totalCount\":10,\"perLimit\":1"
                + ",\"startTime\":\"" + Instant.now().minus(1, ChronoUnit.DAYS) + "\""
                + ",\"endTime\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}";
        String body = mockMvc.perform(post("/api/admin/coupon")
                        .header("X-User-Id", "1").header("X-User-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("code").asInt();
    }

    private void expireRecord(long recordId) {
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        record.setExpireAt(Instant.now().minus(1, ChronoUnit.HOURS));
        recordRepo.saveAndFlush(record);
    }
}