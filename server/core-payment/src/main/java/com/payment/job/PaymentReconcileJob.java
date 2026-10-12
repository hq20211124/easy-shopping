package com.payment.job;

import com.payment.client.OrderClient;
import com.payment.client.OrderSnapshot;
import com.payment.domain.PaymentOrder;
import com.payment.repo.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对账兜底（plans/admin-backend.md 13.2.2 定时对账的 P0 落地）：
 * 每小时扫描 {@code status=paid 但订单侧未确认 paid} 的支付单 → HTTP 反查订单 → 补偿通知 / 告警。
 *
 * <p>覆盖场景：支付回调入账成功但 {@code POST /orders/{id}/paid} 失败（订单服务宕机/超时）。
 * 订单已取消/关闭（6/7）却收到支付成功 → P0 告警人工介入，P1 自动退款
 * （plans 关单与回调竞争：已取消订单收到回调 → 自动退款）。</p>
 *
 * <p><b>P0 局限</b>：进程内 {@code @Scheduled} 单实例调度，多副本部署会重复扫描（补偿本身幂等，
 * 无资金风险，但浪费）；P1 换 MQ 定时消息 + 分布式调度（xxl-job 分片），见 TODO。</p>
 */
@Component
public class PaymentReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileJob.class);

    private final PaymentOrderRepository paymentRepo;
    private final OrderClient orderClient;

    public PaymentReconcileJob(PaymentOrderRepository paymentRepo, OrderClient orderClient) {
        this.paymentRepo = paymentRepo;
        this.orderClient = orderClient;
    }

    /** 每小时第 15 分执行（避开整点高峰；cron 可由配置覆盖，测试置为远期禁用自动触发）。 */
    @Scheduled(cron = "${app.payment.reconcile-cron:0 15 * * * *}")
    public void schedule() {
        reconcile();
    }

    /**
     * 执行一轮对账补偿（公开方法便于测试与人工触发）。
     *
     * @return 本轮补偿成功条数
     */
    public int reconcile() {
        List<PaymentOrder> pending = paymentRepo.findByStatusAndOrderPaidNotifiedFalse(PaymentOrder.STATUS_PAID);
        if (pending.isEmpty()) {
            return 0;
        }
        int fixed = 0;
        for (PaymentOrder payment : pending) {
            try {
                fixed += compensate(payment);
            } catch (Exception e) {
                // 单笔失败不阻断整轮；下轮重试
                log.error("[支付告警] 对账补偿失败，下轮重试 paymentId={} orderId={}",
                        payment.getId(), payment.getOrderId(), e);
            }
        }
        log.info("payment reconcile finished scanned={} fixed={}", pending.size(), fixed);
        return fixed;
    }

    private int compensate(PaymentOrder payment) {
        OrderSnapshot order = orderClient.getOrder(payment.getOrderId());
        if (order.unpaid()) {
            // 订单仍待付款 → 补偿通知（订单侧状态机幂等，重复通知直接返回成功）
            orderClient.markPaid(payment.getOrderId(), payment.getId(), payment.getAmount());
            paymentRepo.markOrderPaidNotified(payment.getId());
            log.info("对账补偿成功：订单已置 paid paymentId={} orderId={}", payment.getId(), payment.getOrderId());
            return 1;
        }
        if (order.cancelledOrClosed()) {
            // 支付成功但订单已取消/关闭：P1 自动退款，P0 告警人工介入
            log.error("[支付告警] 支付成功但订单已取消/关闭，需人工/自动退款(P1 TODO) paymentId={} orderId={} orderStatus={}",
                    payment.getId(), payment.getOrderId(), order.status());
            return 0;
        }
        // 订单已进入后续状态（1待发货~8已完成）：说明 paid 事实成立，只补确认标记
        paymentRepo.markOrderPaidNotified(payment.getId());
        return 1;
    }
}
