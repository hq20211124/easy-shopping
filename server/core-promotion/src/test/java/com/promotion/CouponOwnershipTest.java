package com.promotion;

import com.easyshopping.common.api.BizErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.domain.CouponRecord;
import com.promotion.job.CouponExpireJob;
import com.promotion.repo.CouponRecordRepository;
import com.promotion.repo.CouponTemplateRepository;
import com.promotion.service.CouponService;
import com.promotion.web.InternalCouponController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 券的<b>越权防线</b>回归测试（P0-F 修复）。
 *
 * <p>原缺陷：{@code deduct/release} 注册在买家端路径下且「无 X-User-Id 即跳过归属校验」，
 * 任意能直连 :8088 的调用方都能核销/退回他人券（水平越权）。修复后：</p>
 * <ol>
 *   <li>买家端 {@code /api/v1/coupons/deduct|release} 无身份头 → 403/1004（不再静默放行）；</li>
 *   <li>带 A 的身份头核销 B 的券 → 403/1004（归属校验）；</li>
 *   <li>服务端间调用改走 {@code /api/v1/internal/coupons/{id}/deduct|release}，凭共享密钥
 *       {@code X-Internal-Token} → 可调用；缺密钥/错密钥 → 403/1004。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-promotion-auth-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "app.promotion.expire-cron=0 0 0 1 1 *",
        "app.promotion.deduct-reconcile-cron=0 0 0 1 1 *",
        "app.internal.token=test-internal-token"
})
@AutoConfigureMockMvc
class CouponOwnershipTest {

    private static final long OWNER = 1001L;
    private static final long ATTACKER = 2002L;
    private static final String INTERNAL_TOKEN = "test-internal-token";

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
    @Autowired
    private CouponExpireJob expireJob;

    @BeforeEach
    void setUp() {
        recordRepo.deleteAll();
        templateRepo.deleteAll();
    }

    // ---------- ① 无身份头调用买家端核销/退券 → 403/1004 ----------

    @Test
    void 买家端无身份头核销与退券一律403() throws Exception {
        long recordId = prepareCoupon();

        mockMvc.perform(post("/api/v1/coupons/deduct").contentType(MediaType.APPLICATION_JSON)
                        .content(deductJson(recordId, 900L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(BizErrorCode.NO_PERMISSION.getCode()));

        mockMvc.perform(post("/api/v1/coupons/release").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponId\":" + recordId + ",\"orderId\":" + 900L + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(BizErrorCode.NO_PERMISSION.getCode()));

        // 单数别名路径同样拦住（网关静态路由指向单数别名）
        mockMvc.perform(post("/api/v1/coupon/deduct").contentType(MediaType.APPLICATION_JSON)
                        .content(deductJson(recordId, 900L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(BizErrorCode.NO_PERMISSION.getCode()));

        // 券未被核销
        assertThat(recordRepo.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(CouponRecord.STATUS_AVAILABLE);
    }

    // ---------- ② A 用户不能核销 B 用户的券 ----------

    @Test
    void 不能核销他人券也不能退他人券() throws Exception {
        long recordId = prepareCoupon();

        mockMvc.perform(post("/api/v1/coupons/deduct").header("X-User-Id", String.valueOf(ATTACKER))
                        .contentType(MediaType.APPLICATION_JSON).content(deductJson(recordId, 901L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(BizErrorCode.NO_PERMISSION.getCode()));

        // 券主本人核销成功（确认不是「所有人都被拒」的假绿）
        mockMvc.perform(post("/api/v1/coupons/deduct").header("X-User-Id", String.valueOf(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(deductJson(recordId, 901L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("used"));

        // 他人不能把已核销的券退回（否则等于替用户取消用券）
        mockMvc.perform(post("/api/v1/coupons/release").header("X-User-Id", String.valueOf(ATTACKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponId\":" + recordId + ",\"orderId\":" + 901L + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(BizErrorCode.NO_PERMISSION.getCode()));

        assertThat(recordRepo.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(CouponRecord.STATUS_USED);
    }

    // ---------- ③ internal 端点：带密钥可调用 ----------

    @Test
    void internal端点带共享密钥可核销与退券() throws Exception {
        long recordId = prepareCoupon();

        mockMvc.perform(post("/api/v1/internal/coupons/" + recordId + "/deduct")
                        .header(InternalCouponController.HEADER_INTERNAL_TOKEN, INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":902}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("used"));
        assertThat(recordRepo.findById(recordId).orElseThrow().getUsedOrderId()).isEqualTo(902L);

        mockMvc.perform(post("/api/v1/internal/coupons/" + recordId + "/release")
                        .header(InternalCouponController.HEADER_INTERNAL_TOKEN, INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":902}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("available"));
        assertThat(recordRepo.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(CouponRecord.STATUS_AVAILABLE);
    }

    @Test
    void internal端点缺密钥或错密钥一律403() throws Exception {
        long recordId = prepareCoupon();

        mockMvc.perform(post("/api/v1/internal/coupons/" + recordId + "/deduct")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":903}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(BizErrorCode.NO_PERMISSION.getCode()));

        mockMvc.perform(post("/api/v1/internal/coupons/" + recordId + "/deduct")
                        .header(InternalCouponController.HEADER_INTERNAL_TOKEN, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":903}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(BizErrorCode.NO_PERMISSION.getCode()));

        assertThat(recordRepo.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(CouponRecord.STATUS_AVAILABLE);
    }

    // ---------- helpers ----------

    /** 建一张 OWNER 名下、可用的券，返回券记录 ID。 */
    private long prepareCoupon() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/admin/coupon")
                        .header("X-User-Id", "1").header("X-User-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        long templateId = data.path("id").asLong();
        return couponService.receive(templateId, OWNER).couponId();
    }

    private String templateJson() {
        return "{\"name\":\"越权防线测试券\",\"type\":1,\"value\":20"
                + ",\"minAmount\":100,\"totalCount\":10,\"perLimit\":5"
                + ",\"startTime\":\"" + Instant.now().minus(1, ChronoUnit.DAYS) + "\""
                + ",\"endTime\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}";
    }

    private String deductJson(long recordId, long orderId) {
        return "{\"couponId\":" + recordId + ",\"orderId\":" + orderId + "}";
    }
}
