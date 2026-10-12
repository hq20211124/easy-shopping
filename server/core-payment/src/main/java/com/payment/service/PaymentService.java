package com.payment.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.payment.client.OrderClient;
import com.payment.client.OrderSnapshot;
import com.payment.domain.PaymentOrder;
import com.payment.repo.PaymentOrderRepository;
import com.payment.web.dto.PaymentStatusResponse;
import com.payment.web.dto.PrepayResponse;
import com.payment.web.dto.RefundResponse;
import org.springframework.dao.DataIntegrityViolationException;import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 支付核心服务：预支付 / mock 支付 / 查单 / 退款。
 *
 * <p><b>防改价（裁决 C7）</b>：支付金额一律来自订单服务反查（{@code OrderClient#getOrder}），
 * prepay 只收 orderId，客户端传入的金额字段被忽略；渠道回调再做金额二次校验（见 {@link PaymentNotifyService}）。</p>
 */
@Service
public class PaymentService {

    /** 支付超时兜底（分钟）：仅当订单服务未返回 pay_deadline 时使用；
     *  唯一事实来源是订单表 pay_deadline（分钟数为待产品确认参数，建议 30，见 ios-backend 支付超时行）。 */
    private static final Duration EXPIRE_FALLBACK = Duration.ofMinutes(30);

    private final PaymentOrderRepository paymentRepo;
    private final SnowflakeIdGenerator idGenerator;
    private final OrderClient orderClient;
    private final PaymentNotifyService notifyService;
    private final DomainEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentOrderRepository paymentRepo,
                          SnowflakeIdGenerator idGenerator,
                          OrderClient orderClient,
                          PaymentNotifyService notifyService,
                          DomainEventPublisher eventPublisher,
                          PlatformTransactionManager transactionManager) {
        this.paymentRepo = paymentRepo;
        this.idGenerator = idGenerator;
        this.orderClient = orderClient;
        this.notifyService = notifyService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 预支付：只收 orderId + channel，金额/用户从订单服务反查（防改价）。
     * 同一订单仅一个进行中支付单：重复请求返回原 paymentId（uk_payment_order 唯一约束兜底并发）。
     * <p>不开外层事务：订单服务远程调用不占用 DB 连接；写库各自成小事务，撞唯一键即复用已有支付单。</p>
     */
    public PrepayResponse prepay(long userId, Long orderId, Integer channel) {
        if (channel == null || channel < 1 || channel > 4) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "不支持的支付渠道 channel=" + channel + "（1支付宝/2微信/3银行卡/4余额）");
        }
        // 金额/用户以订单服务为准（裁决 C7）：客户端传入的任何金额字段不参与本方法
        OrderSnapshot order = orderClient.getOrder(orderId);
        if (order.userId() != null && order.userId() != userId) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "无权支付他人订单");
        }

        PaymentOrder existing = paymentRepo.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            return reuse(existing, channel, order.payDeadline());
        }

        PaymentOrder payment = new PaymentOrder();
        payment.setId(idGenerator.nextId());
        payment.setOrderId(orderId);
        payment.setUserId(userId);
        payment.setChannel(channel);
        payment.setAmount(order.totalAmount());
        payment.setStatus(PaymentOrder.STATUS_PENDING);
        payment.setOrderPaidNotified(false);
        try {
            paymentRepo.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // 并发 prepay 撞唯一键：复用对方刚创建的支付单
            existing = paymentRepo.findByOrderId(orderId)
                    .orElseThrow(() -> new BizException(BizErrorCode.PAYMENT_FAILED, "支付单创建失败"));
            return reuse(existing, channel, order.payDeadline());
        }
        return toPrepayResponse(payment, order.payDeadline());
    }

    /** 复用已有支付单（重复预支付语义）。 */
    private PrepayResponse reuse(PaymentOrder payment, Integer channel, Instant payDeadline) {
        switch (payment.getStatus()) {
            case PaymentOrder.STATUS_PENDING -> {
                return toPrepayResponse(payment, payDeadline);
            }
            case PaymentOrder.STATUS_PAID -> throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "订单已支付，请勿重复支付");
            default -> throw new BizException(BizErrorCode.PAYMENT_FAILED,
                    "订单退款中/已退款，不能再次支付 status=" + payment.statusText());
        }
    }

    private PrepayResponse toPrepayResponse(PaymentOrder payment, Instant payDeadline) {
        long expireAt = (payDeadline != null ? payDeadline : payment.getCreatedAt().plus(EXPIRE_FALLBACK))
                .getEpochSecond();
        return new PrepayResponse(payment.getId(), payment.getChannel(), payment.getAmount(),
                payTypeOf(payment.getChannel()), payParamsOf(payment), expireAt);
    }

    private String payTypeOf(int channel) {
        return switch (channel) {
            case 1 -> "alipay_mock";
            case 2 -> "wechat_mock";
            case 3 -> "bank_card_mock";
            case 4 -> "balance_mock";
            default -> "unknown";
        };
    }

    /** mock 支付参数（P1 接真实渠道：支付宝 {tradeNO} / 微信 {package, nonceStr, signType, timeStamp}，见 miniprogram 8.1.2）。 */
    private Map<String, String> payParamsOf(PaymentOrder payment) {
        String mockTradeNo = "MOCK-" + payment.getId();
        return switch (payment.getChannel()) {
            case 1 -> Map.of("tradeNO", mockTradeNo, "mock", "true");
            case 2 -> Map.of("package", "prepay_id=" + mockTradeNo, "nonceStr", mockTradeNo,
                    "signType", "RSA", "timeStamp", String.valueOf(System.currentTimeMillis() / 1000), "mock", "true");
            default -> Map.of("prepayId", mockTradeNo, "mock", "true");
        };
    }

    /**
     * P0 mock 支付：模拟用户付款成功，直接走与真实回调完全相同的处理链
     * （幂等键固定 {@code mock:{paymentId}}，重复调用不会重复入账）。
     */
    public MockPayResult mockPay(Long paymentId, long userId) {
        PaymentOrder payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new BizException(BizErrorCode.PAYMENT_FAILED, "支付单不存在 paymentId=" + paymentId));
        if (payment.getUserId() != null && payment.getUserId() != userId) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "无权操作他人支付单");
        }
        if (payment.getStatus() != PaymentOrder.STATUS_PENDING) {
            // 幂等：非待支付直接回当前状态（已支付不再扣款）
            return new MockPayResult(paymentId, payment.statusText());
        }
        notifyService.handleNotify(new NotifyCommand(
                "mock:" + paymentId, paymentId, payment.getAmount(), "MOCK-" + paymentId));
        PaymentOrder after = paymentRepo.findById(paymentId).orElseThrow();
        return new MockPayResult(paymentId, after.statusText());
    }

    /** 查单（轮询）。 */
    public PaymentStatusResponse status(Long orderId, Long userId) {
        PaymentOrder payment = paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "支付单不存在 orderId=" + orderId));
        if (userId != null && payment.getUserId() != null && !payment.getUserId().equals(userId)) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "无权查询他人支付单");
        }
        return new PaymentStatusResponse(orderId, payment.getId(), payment.getChannel(),
                payment.getAmount(), payment.statusText(),
                payment.getPaidAt() == null ? null : payment.getPaidAt().toString());
    }

    /**
     * 退款（供订单关单/售后服务调用）：paid → refunding → refunded，幂等。
     * P0 mock 渠道同步出账；P1 真实渠道异步（refunding 停留至渠道退款回调）。
     */
    public RefundResponse refund(Long orderId, String reason) {
        String actualReason = (reason == null || reason.isBlank()) ? "unspecified" : reason;
        RefundResult result = transactionTemplate.execute(s -> doRefund(orderId, actualReason));
        if (result == null) {
            throw new BizException(BizErrorCode.SYSTEM_ERROR, "退款事务未执行");
        }
        if (result.freshlyRefunded()) {
            // 本地事务提交后发退款事件（事务消息口径见 DomainEventNames.PAYMENT_REFUND）
            eventPublisher.publish(DomainEventNames.PAYMENT_REFUND, Map.of(
                    "orderId", orderId,
                    "paymentId", result.paymentId(),
                    "amount", result.amount(),
                    "reason", actualReason));
        }
        return new RefundResponse(orderId, result.paymentId(), "refunded", result.amount());
    }

    private RefundResult doRefund(Long orderId, String reason) {
        PaymentOrder payment = paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "支付单不存在 orderId=" + orderId));
        Instant now = Instant.now();
        if (payment.getStatus() == PaymentOrder.STATUS_REFUNDED) {
            return RefundResult.idempotent(payment); // 重复退款：直接返回终态（幂等）
        }
        if (payment.getStatus() == PaymentOrder.STATUS_REFUNDING) {
            // 上次调用中断在中间态：续跑完成 refunding → refunded
            paymentRepo.casMarkRefunded(payment.getId(),
                    PaymentOrder.STATUS_REFUNDING, PaymentOrder.STATUS_REFUNDED, now);
            return RefundResult.fresh(payment);
        }
        int rows = paymentRepo.casStartRefund(payment.getId(),
                PaymentOrder.STATUS_PAID, PaymentOrder.STATUS_REFUNDING, reason, now);
        if (rows == 0) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "仅已支付订单可退款，当前状态=" + payment.statusText());
        }
        // P0 mock 渠道同步出账
        paymentRepo.casMarkRefunded(payment.getId(),
                PaymentOrder.STATUS_REFUNDING, PaymentOrder.STATUS_REFUNDED, now);
        return RefundResult.fresh(payment);
    }

    /** mock 支付结果。 */
    public record MockPayResult(Long paymentId, String status) {
    }

    /** 退款内部结果：freshlyRefunded=true 才发事件（幂等重放不重复发）。 */
    private record RefundResult(Long paymentId, BigDecimal amount, boolean freshlyRefunded) {
        static RefundResult fresh(PaymentOrder p) {
            return new RefundResult(p.getId(), p.getAmount(), true);
        }

        static RefundResult idempotent(PaymentOrder p) {
            return new RefundResult(p.getId(), p.getAmount(), false);
        }
    }
}
