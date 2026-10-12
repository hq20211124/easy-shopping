package com.promotion;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.domain.CouponRecord;
import com.promotion.job.CouponExpireJob;
import com.promotion.repo.CouponRecordRepository;
import com.promotion.repo.CouponTemplateRepository;
import com.promotion.service.CouponService;
import com.promotion.web.dto.PreviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 优惠券模块集成测试（mem H2，不污染 ./data 开发库）。
 *
 * <p>覆盖任务要求：③ 预占最后 1 张券并发安全 ④ 核销并发只成功一次，
 * 另覆盖 防超领/每人限领、试算（满减+折扣）、核销/退回幂等、过期翻转、管理端 CRUD 与权限。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-promotion-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "app.promotion.expire-cron=0 0 0 1 1 *", // 远期 cron：禁用自动触发，测试手动调 expireDue()
        "app.promotion.deduct-reconcile-cron=0 0 0 1 1 *" // 远期 cron：禁用自动触发，测试手动调回收
})
@AutoConfigureMockMvc
class CouponIntegrationTest {

    private static final long BUYER = 1001L;

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
        // 各测试方法共享同一 H2 mem 上下文：先清库保证用例独立
        recordRepo.deleteAll();
        templateRepo.deleteAll();
    }

    // ---------- 管理端 CRUD + 权限 ----------

    @Test
    void adminCrudAndRoleCheck() throws Exception {
        // 无 admin 角色 → 403/1004
        mockMvc.perform(post("/api/admin/coupon").header("X-User-Id", "2")
                        .contentType(MediaType.APPLICATION_JSON).content(templateJson("1", "20", "100", 10, 2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));

        // 任务契约别名 POST /api/admin/coupons 与 plans 路径等价
        long id = createTemplate("1", "20", "100", 10, 2);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/coupon/" + id).header("X-User-Id", "1").header("X-User-Roles", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(10))
                .andExpect(jsonPath("$.data.receivedCount").value(0));

        // 已发放的模板不可删除
        couponService.receive(id, BUYER);
        mockMvc.perform(delete("/api/admin/coupon/" + id).header("X-User-Id", "1").header("X-User-Roles", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4002));
    }

    // ---------- 领取：防超领 + 每人限领 ----------

    @Test
    void receiveEnforcesPerLimitAndTotal() throws Exception {
        long id = createTemplate("1", "20", "100", 2, 2); // 总量 2、每人限领 2

        // 别名路径领取（/api/v1/coupons/receive 单复数双注册，此处验证单数别名）
        mockMvc.perform(post("/api/v1/coupon/receive").header("X-User-Id", String.valueOf(BUYER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"couponId\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("available"));
        couponService.receive(id, BUYER); // 第 2 张：未超限

        // 第 3 张：超每人限领 → 4002
        BizException perLimit = assertThrows(BizException.class, () -> couponService.receive(id, BUYER));
        assertEquals(BizErrorCode.ORDER_STATUS_ERROR.getCode(), perLimit.getCode());

        // 另一用户领第 3 张：超总量 → 3002
        BizException soldOut = assertThrows(BizException.class, () -> couponService.receive(id, 2002L));
        assertEquals(BizErrorCode.OUT_OF_STOCK.getCode(), soldOut.getCode());
        assertEquals(2, templateRepo.findById(id).orElseThrow().getReceivedCount());
    }

    // ---------- ③ 预占最后 1 张券并发安全 ----------

    @Test
    void lastCouponReservedExactlyOnceUnderConcurrency() throws Exception {
        long id = createTemplate("1", "10", "50", 1, 1); // 总量 1：并发抢最后 1 张

        int threads = 8;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        runConcurrently(threads, i -> {
            try {
                couponService.receive(id, 3000L + i); // 不同用户，排除限领干扰
                success.incrementAndGet();
            } catch (BizException e) {
                assertEquals(BizErrorCode.OUT_OF_STOCK.getCode(), e.getCode());
                soldOut.incrementAndGet();
            }
        });

        assertEquals(1, success.get(), "最后 1 张券只允许 1 个用户抢到");
        assertEquals(threads - 1, soldOut.get());
        assertEquals(1, templateRepo.findById(id).orElseThrow().getReceivedCount());
        assertEquals(1, recordRepo.findByCouponId(id).size());
    }

    // ---------- ④ 核销并发只成功一次 ----------

    @Test
    void deductSucceedsExactlyOnceUnderConcurrency() throws Exception {
        long templateId = createTemplate("1", "20", "100", 10, 5);
        long recordId = couponService.receive(templateId, BUYER).couponId();

        int threads = 8;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(threads, i -> {
            try {
                couponService.deduct(recordId, 900L + i, BUYER); // 不同订单并发核销同一张券
                success.incrementAndGet();
            } catch (BizException e) {
                assertEquals(BizErrorCode.ORDER_STATUS_ERROR.getCode(), e.getCode());
                conflict.incrementAndGet();
            }
        });

        assertEquals(1, success.get(), "同一张券并发核销只允许 1 次成功");
        assertEquals(threads - 1, conflict.get());
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        assertEquals(CouponRecord.STATUS_USED, record.getStatus());
        assertTrue(record.getUsedOrderId() >= 900L && record.getUsedOrderId() < 900L + threads);
        assertEquals(1, templateRepo.findById(templateId).orElseThrow().getUsedCount());
    }

    // ---------- 核销/退回幂等 ----------

    @Test
    void deductAndReleaseAreIdempotent() throws Exception {
        long templateId = createTemplate("1", "20", "100", 10, 5);
        long recordId = couponService.receive(templateId, BUYER).couponId();

        // 同订单重复核销：幂等成功
        couponService.deduct(recordId, 555L, BUYER);
        couponService.deduct(recordId, 555L, BUYER);
        assertEquals(1, templateRepo.findById(templateId).orElseThrow().getUsedCount(), "重复核销不重复计数");

        // 他订单核销：拒绝
        BizException conflict = assertThrows(BizException.class, () -> couponService.deduct(recordId, 556L, BUYER));
        assertEquals(BizErrorCode.ORDER_STATUS_ERROR.getCode(), conflict.getCode());

        // 关单退回 + 重复退回幂等
        couponService.release(recordId, 555L, BUYER);
        couponService.release(recordId, 555L, BUYER);
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        assertEquals(CouponRecord.STATUS_AVAILABLE, record.getStatus());
        assertEquals(0, templateRepo.findById(templateId).orElseThrow().getUsedCount());

        // 退回后可再次核销（换订单）
        couponService.deduct(recordId, 557L, BUYER);
        assertEquals(557L, recordRepo.findById(recordId).orElseThrow().getUsedOrderId());
    }

    /** P0-F：无身份头调用（服务端层等价场景）必须拒绝，不得静默跳过归属校验。 */
    @Test
    void deductAndReleaseRejectMissingIdentity() throws Exception {
        long templateId = createTemplate("1", "20", "100", 10, 5);
        long recordId = couponService.receive(templateId, BUYER).couponId();

        BizException noUser = assertThrows(BizException.class, () -> couponService.deduct(recordId, 800L, null));
        assertEquals(BizErrorCode.NO_PERMISSION.getCode(), noUser.getCode());

        BizException noUserRelease = assertThrows(BizException.class, () -> couponService.release(recordId, 800L, null));
        assertEquals(BizErrorCode.NO_PERMISSION.getCode(), noUserRelease.getCode());

        // 券状态未被改变
        assertEquals(CouponRecord.STATUS_AVAILABLE, recordRepo.findById(recordId).orElseThrow().getStatus());
    }

    @Test
    void deductRejectsForeignUser() throws Exception {
        long templateId = createTemplate("1", "20", "100", 10, 5);
        long recordId = couponService.receive(templateId, BUYER).couponId();
        BizException e = assertThrows(BizException.class, () -> couponService.deduct(recordId, 1L, 2002L));
        assertEquals(BizErrorCode.NO_PERMISSION.getCode(), e.getCode());
    }

    // ---------- 试算：满减 + 折扣 ----------

    @Test
    void previewComputesFullCutAndDiscount() throws Exception {
        // 满减券：满 100 减 20
        long fullCutId = createTemplate("1", "20", "100", 10, 5);
        long fullCutRecord = couponService.receive(fullCutId, BUYER).couponId();

        PreviewResponse ok = couponService.preview(fullCutRecord, List.of(new BigDecimal("60"), new BigDecimal("50")), BUYER);
        assertTrue(ok.eligible());
        assertEquals(0, ok.discount().compareTo(new BigDecimal("20.00")));
        assertEquals(0, ok.finalAmount().compareTo(new BigDecimal("90.00")));

        PreviewResponse below = couponService.preview(fullCutRecord, List.of(new BigDecimal("40"), new BigDecimal("50")), BUYER);
        assertFalse(below.eligible());
        assertEquals(0, below.discount().compareTo(BigDecimal.ZERO));

        // 折扣券：8.5 折（value=8.5）
        long discountId = createTemplate("2", "8.5", "0", 10, 5);
        long discountRecord = couponService.receive(discountId, BUYER).couponId();
        PreviewResponse discount = couponService.preview(discountRecord, List.of(new BigDecimal("100")), BUYER);
        assertTrue(discount.eligible());
        assertEquals(0, discount.discount().compareTo(new BigDecimal("15.00")));
        assertEquals(0, discount.finalAmount().compareTo(new BigDecimal("85.00")));
    }

    // ---------- 过期翻转 ----------

    @Test
    void expireJobFlipsAndBlocksDeduct() throws Exception {
        long templateId = createTemplate("1", "20", "100", 10, 5);
        long recordId = couponService.receive(templateId, BUYER).couponId();

        // 把过期时间改到过去（模拟到期）
        CouponRecord record = recordRepo.findById(recordId).orElseThrow();
        record.setExpireAt(Instant.now().minus(1, ChronoUnit.HOURS));
        recordRepo.save(record);

        expireJob.schedule();
        assertEquals(CouponRecord.STATUS_EXPIRED, recordRepo.findById(recordId).orElseThrow().getStatus());

        // 过期券不可核销
        BizException e = assertThrows(BizException.class, () -> couponService.deduct(recordId, 777L, BUYER));
        assertEquals(BizErrorCode.ORDER_STATUS_ERROR.getCode(), e.getCode());
    }

    // ---------- helpers ----------

    private String templateJson(String type, String value, String minAmount, int totalCount, int perLimit) {
        return "{\"name\":\"测试券\",\"type\":" + type + ",\"value\":" + value
                + ",\"minAmount\":" + minAmount + ",\"totalCount\":" + totalCount
                + ",\"perLimit\":" + perLimit
                + ",\"startTime\":\"" + Instant.now().minus(1, ChronoUnit.DAYS) + "\""
                + ",\"endTime\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}";
    }

    /** 通过管理端 API 创建模板（同时验证接口链路），返回模板 ID。 */
    private long createTemplate(String type, String value, String minAmount, int totalCount, int perLimit) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/coupon")
                        .header("X-User-Id", "1").header("X-User-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson(type, value, minAmount, totalCount, perLimit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        return data.path("id").asLong();
    }

    /** N 线程同时起跑执行 action（下标 0..n-1）。 */
    private void runConcurrently(int threads, java.util.function.IntConsumer action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                action.accept(index);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }
}
