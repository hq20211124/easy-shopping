package com.payment;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEvent;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.payment.client.OrderClient;
import com.payment.client.OrderSnapshot;
import com.payment.domain.PaymentNotifyLog;
import com.payment.domain.PaymentOrder;
import com.payment.job.PaymentReconcileJob;
import com.payment.repo.PaymentNotifyLogRepository;
import com.payment.repo.PaymentOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付模块集成测试（mem H2，不污染 ./data 开发库）。
 *
 * <p>覆盖任务要求：① 同一 notify 重复 10 次只生效 1 次 ② 金额不符拒绝 ⑤ 退款幂等
 * ⑥ prepay 忽略客户端金额字段（防改价 C7）且取<b>应付金额</b>而非商品总额（P0-1），
 * 另覆盖 mock 支付同链路、对账兜底、金额不符后<b>同 notifyId</b> 重投可恢复（P1-1），
 * 以及回调第二道防线（P1）：入账前反查订单 payAmount —— 一致则入账、不一致拒绝、
 * <b>反查失败/超时仍然入账</b>（依赖抖动不得阻断入账，钱已收）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-payment-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.payment.reconcile-cron=0 0 0 1 1 *" // 远期 cron：禁用自动触发，测试手动调 reconcile()
})
@AutoConfigureMockMvc
class PaymentIntegrationTest {

    private static final long USER = 99L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PaymentOrderRepository paymentRepo;
    @Autowired
    private PaymentNotifyLogRepository notifyLogRepo;
    @Autowired
    private PaymentReconcileJob reconcileJob;
    @Autowired
    private CapturingEventPublisher events;

    @MockBean
    private OrderClient orderClient;

    @BeforeEach
    void setUp() {
        // 各测试方法共享同一 H2 mem 上下文：先清库保证用例独立
        notifyLogRepo.deleteAll();
        paymentRepo.deleteAll();
        Mockito.reset(orderClient);
        events.clear();
        stubOrder(100L, "199.00", 0);
    }

    // ---------- ⑥ prepay 忽略客户端金额字段（防改价 C7）+ 取应付金额而非商品总额（P0-1） ----------

    @Test
    void prepayIgnoresClientAmount() throws Exception {
        // 有券订单：商品总额 199.00（优惠前）≠ 应付 189.00（优惠后）。
        // 桩数据必须能区分这两个字段——否则「取错字段」的缺陷会被单金额桩完全掩盖。
        stubOrder(100L, "199.00", "189.00", 0);

        // 客户端恶意传入金额 0.01 与伪造 userId：全部忽略，以订单服务反查结果为准
        mockMvc.perform(post("/api/v1/payment/prepay")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":100,\"channel\":2,\"amount\":0.01,\"totalAmount\":0.01,\"userId\":12345}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.amount").value(189.00))
                .andExpect(jsonPath("$.data.channel").value(2));

        verify(orderClient).getOrder(100L);
        PaymentOrder saved = paymentRepo.findByOrderId(100L).orElseThrow();
        // 支付单必须落「应付金额」，落「商品总额」即为有券订单多收一个券面额的资损
        assertEquals(0, saved.getAmount().compareTo(new BigDecimal("189.00")),
                "支付单金额必须等于订单应付金额 payAmount（199.00 是优惠前的商品总额）");
        assertNotEquals(0, saved.getAmount().compareTo(new BigDecimal("199.00")),
                "支付单金额不得等于商品总额 totalAmount");
        assertEquals(USER, saved.getUserId());

        // 轮询查单同样只暴露服务端定额
        mockMvc.perform(get("/api/v1/payment/status/100").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(189.00));
    }

    @Test
    void prepayRejectsNonPositivePayAmount() throws Exception {
        // 异常订单（应付为 0 / 负数）：拒绝预支付，绝不落支付单
        stubOrder(100L, "199.00", "0.00", 0);
        mockMvc.perform(post("/api/v1/payment/prepay")
                        .header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":100,\"channel\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4002));

        stubOrder(100L, "199.00", "-10.00", 0);
        mockMvc.perform(post("/api/v1/payment/prepay")
                        .header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":100,\"channel\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4002));

        assertTrue(paymentRepo.findByOrderId(100L).isEmpty(), "应付金额异常时不得创建支付单");
    }

    @Test
    void prepaySameOrderReturnsSamePaymentId() throws Exception {
        long first = prepay(100L, 2);
        long second = prepay(100L, 1); // 换渠道重复预支付也复用原支付单
        assertEquals(first, second);
        assertEquals(1, paymentRepo.count());
    }

    // ---------- ① 同一 notify 重复 10 次只生效 1 次 ----------

    @Test
    void duplicateNotifyTenTimesEffectiveOnce() throws Exception {
        long paymentId = prepay(100L, 2);
        String body = "{\"notifyId\":\"N-100\",\"paymentId\":" + paymentId
                + ",\"amount\":199.00,\"thirdPartyNo\":\"T-100\"}";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.result").value(i == 0 ? "SUCCESS" : "DUPLICATE"));
        }

        assertEquals(PaymentOrder.STATUS_PAID, paymentRepo.findById(paymentId).orElseThrow().getStatus());
        assertEquals(1, notifyLogRepo.count(), "notify_log 唯一键去重：10 次通知只落 1 条");
        verify(orderClient, times(1)).markPaid(eq(100L), eq(paymentId), any());
        assertEquals(1, events.count(DomainEventNames.PAYMENT_SUCCESS), "payment.success 只发一次");
        assertTrue(paymentRepo.findById(paymentId).orElseThrow().getOrderPaidNotified());
    }

    // ---------- ② 金额不符拒绝 ----------

    @Test
    void notifyAmountMismatchRejected() throws Exception {
        long paymentId = prepay(100L, 2);
        // 渠道通知金额 10.00 ≠ 支付单 199.00 → 拒绝入账 + 告警日志
        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-BAD\",\"paymentId\":" + paymentId
                                + ",\"amount\":10.00,\"thirdPartyNo\":\"T-BAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5001));

        assertEquals(PaymentOrder.STATUS_PENDING,
                paymentRepo.findById(paymentId).orElseThrow().getStatus(), "金额不符必须拒绝且状态不翻转");
        verify(orderClient, Mockito.never()).markPaid(any(), any(), any());

        // 金额正确的通知（新通知号）仍可正常入账
        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-OK\",\"paymentId\":" + paymentId
                                + ",\"amount\":199.00,\"thirdPartyNo\":\"T-OK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));
        assertEquals(PaymentOrder.STATUS_PAID, paymentRepo.findById(paymentId).orElseThrow().getStatus());
    }

    /**
     * 金额不符被拒后，渠道按标准语义用<b>同一 notifyId</b> 修正金额重投 → 必须能正常入账。
     *
     * <p>这是 P1-1 的核心回归：① 的去重日志先独立提交，③ 失败回滚 ② 后该行已在库；
     * 若 ① 对所有已存在的行一律判重，同 notifyId 的合法重试会被永久判为「重复」→
     * 返回成功给渠道但支付单永远停在 pending（钱收了不入账，且对账只扫 paid 覆盖不到）。</p>
     */
    @Test
    void notifySameNotifyIdRetryAfterAmountMismatchSucceeds() throws Exception {
        long paymentId = prepay(100L, 2);

        // ① 首次通知：金额 10.00 ≠ 支付单 199.00 → 拒绝 + 标记 REJECTED
        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-SAME\",\"paymentId\":" + paymentId
                                + ",\"amount\":10.00,\"thirdPartyNo\":\"T-SAME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5001));
        assertEquals(PaymentOrder.STATUS_PENDING, paymentRepo.findById(paymentId).orElseThrow().getStatus());
        assertEquals(PaymentNotifyLog.STATUS_REJECTED,
                notifyLogRepo.findByNotifyId("N-SAME").orElseThrow().getProcessStatus(),
                "③ 失败必须把去重行标 REJECTED，否则同 notifyId 重试会被永久拦截");

        // ② 渠道修正金额后用同一 notifyId 重投 → 必须成功入账
        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-SAME\",\"paymentId\":" + paymentId
                                + ",\"amount\":199.00,\"thirdPartyNo\":\"T-SAME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));

        assertEquals(PaymentOrder.STATUS_PAID, paymentRepo.findById(paymentId).orElseThrow().getStatus());
        verify(orderClient, times(1)).markPaid(eq(100L), eq(paymentId), any());
        assertEquals(1, notifyLogRepo.count(), "重投复用同一去重行，不新增行");
        assertEquals(PaymentNotifyLog.STATUS_PROCESSED,
                notifyLogRepo.findByNotifyId("N-SAME").orElseThrow().getProcessStatus());
        assertEquals(1, events.count(DomainEventNames.PAYMENT_SUCCESS));

        // ③ 入账后同一 notifyId 再投 → 判重，不重复入账/不发第二次事件
        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-SAME\",\"paymentId\":" + paymentId
                                + ",\"amount\":199.00,\"thirdPartyNo\":\"T-SAME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value("DUPLICATE"));
        verify(orderClient, times(1)).markPaid(eq(100L), eq(paymentId), any());
        assertEquals(1, events.count(DomainEventNames.PAYMENT_SUCCESS));
    }

    // ---------- ④ 第二道防线：入账前反查订单应付金额（P1） ----------

    /**
     * 基线：订单应付金额与支付单一致 → 正常入账（第二道防线不得误伤正常链路）。
     */
    @Test
    void notifyWithMatchingOrderPayAmountPays() throws Exception {
        long paymentId = prepay(100L, 2);
        notify("{\"notifyId\":\"N-OK-AMT\",\"paymentId\":" + paymentId
                + ",\"amount\":199.00,\"thirdPartyNo\":\"T-OK-AMT\"}");

        assertEquals(PaymentOrder.STATUS_PAID, paymentRepo.findById(paymentId).orElseThrow().getStatus());
        // ④ 必须真的反查了订单（而不是只在金额不符时才查）
        verify(orderClient, times(2)).getOrder(100L); // 1 次 prepay + 1 次入账前反查
        verify(orderClient, times(1)).markPaid(eq(100L), eq(paymentId), any());
        assertEquals(1, events.count(DomainEventNames.PAYMENT_SUCCESS));
    }

    /**
     * prepay 之后订单被改价（管理端改价/优惠重算）→ 入账前反查发现支付单金额 ≠ 订单应付金额 →
     * <b>拒绝入账 + ERROR 告警</b>，钱与单据的不一致必须暴露而不是静默沉淀。
     */
    @Test
    void notifyRejectedWhenOrderPayAmountChangedAfterPrepay() throws Exception {
        long paymentId = prepay(100L, 2); // 落支付单 199.00
        // prepay 之后订单应付被改成 99.00（渠道按 199.00 扣了钱）
        stubOrder(100L, "199.00", "99.00", 0);

        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-PRICE\",\"paymentId\":" + paymentId
                                + ",\"amount\":199.00,\"thirdPartyNo\":\"T-PRICE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5001));

        assertEquals(PaymentOrder.STATUS_PENDING,
                paymentRepo.findById(paymentId).orElseThrow().getStatus(), "订单金额漂移必须拒绝入账且状态不翻转");
        verify(orderClient, Mockito.never()).markPaid(any(), any(), any());
        assertEquals(0, events.count(DomainEventNames.PAYMENT_SUCCESS), "拒绝入账不得发 payment.success");
        assertEquals(PaymentNotifyLog.STATUS_REJECTED,
                notifyLogRepo.findByNotifyId("N-PRICE").orElseThrow().getProcessStatus(),
                "拒绝后必须置 REJECTED，订单金额修好后同 notifyId 重投可恢复");

        // 订单金额修回 199.00 后用同一 notifyId 重投 → 正常入账（有自动恢复路径）
        stubOrder(100L, "199.00", 0);
        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-PRICE\",\"paymentId\":" + paymentId
                                + ",\"amount\":199.00,\"thirdPartyNo\":\"T-PRICE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));
        assertEquals(PaymentOrder.STATUS_PAID, paymentRepo.findById(paymentId).orElseThrow().getStatus());
    }

    /**
     * <b>关键回归网</b>：反查订单失败/超时<b>不得回滚入账</b>。
     *
     * <p>钱已经被渠道收了 —— 若因订单服务抖动拒绝入账，用户会看到「已扣款但订单未支付」，
     * 这是比漏掉一次金额比对严重得多的资损。故：依赖不可用 → 只告警放行（沿用本类
     * 「支付事实已成立、对账兜底」的既有语义）。</p>
     */
    @Test
    void notifyStillPaysWhenOrderPayAmountLookupFails() throws Exception {
        // 场景一：订单服务返回错误（RestClient 不可用 → BizException SYSTEM_ERROR）
        long paymentId = prepay(100L, 2);
        doThrow(new BizException(BizErrorCode.SYSTEM_ERROR, "订单服务不可用")).when(orderClient).getOrder(100L);

        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-DOWN\",\"paymentId\":" + paymentId
                                + ",\"amount\":199.00,\"thirdPartyNo\":\"T-DOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));

        assertEquals(PaymentOrder.STATUS_PAID,
                paymentRepo.findById(paymentId).orElseThrow().getStatus(), "反查失败不得回滚入账（钱已收）");
        verify(orderClient, times(1)).markPaid(eq(100L), eq(paymentId), any());
        assertEquals(1, events.count(DomainEventNames.PAYMENT_SUCCESS));

        // 场景二：读超时（RestClient 抛 ResourceAccessException）→ 同样放行入账
        stubOrder(101L, "50.00", 0);
        long paymentId2 = prepay(101L, 2);
        doThrow(new ResourceAccessException("Read timed out")).when(orderClient).getOrder(101L);

        mockMvc.perform(post("/api/v1/payment/notify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notifyId\":\"N-TIMEOUT\",\"paymentId\":" + paymentId2
                                + ",\"amount\":50.00,\"thirdPartyNo\":\"T-TIMEOUT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));

        assertEquals(PaymentOrder.STATUS_PAID,
                paymentRepo.findById(paymentId2).orElseThrow().getStatus(), "反查超时不得回滚入账（钱已收）");
        verify(orderClient, times(1)).markPaid(eq(101L), eq(paymentId2), any());
    }

    // ---------- mock 支付走与真实回调相同处理链 ----------

    @Test
    void mockPayUsesSameChainAndIsIdempotent() throws Exception {
        long paymentId = prepay(100L, 2);
        String body = "{\"paymentId\":" + paymentId + "}";

        mockMvc.perform(post("/api/v1/payment/mock/pay").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("paid"));

        // 重复 mock 支付：幂等（mock:{paymentId} 去重），不重复入账
        mockMvc.perform(post("/api/v1/payment/mock/pay").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(orderClient, times(1)).markPaid(eq(100L), eq(paymentId), any());
        assertEquals(1, events.count(DomainEventNames.PAYMENT_SUCCESS));
    }

    // ---------- ⑤ 退款幂等 ----------

    @Test
    void refundIsIdempotent() throws Exception {
        long paymentId = prepay(100L, 2);
        mockPay(paymentId);

        String body = "{\"orderId\":100,\"reason\":\"测试退款\"}";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/payment/refund")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.status").value("refunded"));
        }

        PaymentOrder payment = paymentRepo.findById(paymentId).orElseThrow();
        assertEquals(PaymentOrder.STATUS_REFUNDED, payment.getStatus());
        assertEquals(1, events.count(DomainEventNames.PAYMENT_REFUND), "重复退款不重复发事件");
    }

    @Test
    void refundUnpaidOrderRejected() throws Exception {
        prepay(100L, 2); // 不支付
        mockMvc.perform(post("/api/v1/payment/refund")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4002));
    }

    // ---------- 对账兜底：paid 但订单未确认 → 补偿 ----------

    @Test
    void reconcileCompensatesPaidButOrderUnpaid() throws Exception {
        long paymentId = prepay(100L, 2);
        // 首次回调时订单服务不可用：支付入账成功但订单通知失败
        doThrow(new BizException(BizErrorCode.SYSTEM_ERROR, "订单服务不可用"))
                .when(orderClient).markPaid(eq(100L), any(), any());
        notify("{\"notifyId\":\"N-RC\",\"paymentId\":" + paymentId + ",\"amount\":199.00,\"thirdPartyNo\":\"T-RC\"}");
        assertEquals(PaymentOrder.STATUS_PAID, paymentRepo.findById(paymentId).orElseThrow().getStatus());
        assertEquals(Boolean.FALSE, paymentRepo.findById(paymentId).orElseThrow().getOrderPaidNotified());

        // 订单服务恢复且订单仍待付款 → 对账补偿重新通知
        Mockito.reset(orderClient);
        stubOrder(100L, "199.00", 0);
        int fixed = reconcileJob.reconcile();
        assertEquals(1, fixed);
        verify(orderClient).markPaid(eq(100L), eq(paymentId), any());
        assertTrue(paymentRepo.findById(paymentId).orElseThrow().getOrderPaidNotified());
    }

    @Test
    void reconcileMarksNotifiedWhenOrderAlreadyPaid() throws Exception {
        long paymentId = prepay(100L, 2);
        doThrow(new BizException(BizErrorCode.SYSTEM_ERROR, "订单服务不可用"))
                .when(orderClient).markPaid(eq(100L), any(), any());
        notify("{\"notifyId\":\"N-RC2\",\"paymentId\":" + paymentId + ",\"amount\":199.00,\"thirdPartyNo\":\"T-RC2\"}");

        // 订单侧已由其他途径置为已支付（1待发货）→ 只补确认标记，不再通知
        Mockito.reset(orderClient);
        stubOrder(100L, "199.00", 1);
        int fixed = reconcileJob.reconcile();
        assertEquals(1, fixed);
        Mockito.verify(orderClient, Mockito.never()).markPaid(any(), any(), any());
        assertTrue(paymentRepo.findById(paymentId).orElseThrow().getOrderPaidNotified());
    }

    // ---------- helpers ----------

    /** 无券订单打桩：商品总额 == 应付金额。 */
    private void stubOrder(long orderId, String amount, int status) {
        stubOrder(orderId, amount, amount, status);
    }

    /**
     * 有券订单打桩：商品总额（优惠前）≠ 应付金额（优惠后）。
     * 定额一律取 payAmount，totalAmount 仅供对账参考——必须能构造两者不等，否则测不出「取错字段」。
     */
    private void stubOrder(long orderId, String totalAmount, String payAmount, int status) {
        when(orderClient.getOrder(orderId))
                .thenReturn(new OrderSnapshot(orderId, USER, new BigDecimal(totalAmount),
                        new BigDecimal(payAmount), status, null));
    }

    private long prepay(long orderId, int channel) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payment/prepay")
                        .header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + orderId + ",\"channel\":" + channel + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return data(result).path("paymentId").asLong();
    }

    private void mockPay(long paymentId) throws Exception {
        mockMvc.perform(post("/api/v1/payment/mock/pay").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":" + paymentId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private void notify(String body) throws Exception {
        mockMvc.perform(post("/api/v1/payment/notify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    // ---------- 事件捕获 ----------

    /** 捕获领域事件（进程内 DomainEventPublisher 的测试替身，common 自动装配按 @ConditionalOnMissingBean 退让）。 */
    static class CapturingEventPublisher implements DomainEventPublisher {
        private final SnowflakeIdGenerator idGenerator;
        private final List<DomainEvent> published = new CopyOnWriteArrayList<>();

        CapturingEventPublisher(SnowflakeIdGenerator idGenerator) {
            this.idGenerator = idGenerator;
        }

        @Override
        public void publish(String eventName, Object data) {
            published.add(new DomainEvent(idGenerator.nextId(), eventName, Instant.now(), data));
        }

        long count(String eventName) {
            return published.stream().filter(e -> e.eventName().equals(eventName)).count();
        }

        void clear() {
            published.clear();
        }
    }

    @TestConfiguration
    static class EventCaptureConfig {
        @Bean
        CapturingEventPublisher capturingEventPublisher(SnowflakeIdGenerator idGenerator) {
            return new CapturingEventPublisher(idGenerator);
        }
    }
}
