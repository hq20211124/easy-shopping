package com.payment.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.payment.client.OrderClient;
import com.payment.client.OrderSnapshot;
import com.payment.domain.PaymentNotifyLog;
import com.payment.domain.PaymentOrder;
import com.payment.repo.PaymentNotifyLogRepository;
import com.payment.repo.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 支付回调处理（plans/admin-backend.md 回调幂等三步 + miniprogram-backend.md 8.1.1）。
 *
 * <p><b>回调幂等三步</b>（真实渠道回调与 {@code /payment/mock/pay} 共用本处理链）：</p>
 * <ol>
 *   <li>① {@code notify_log} 唯一键去重：<b>只对 PROCESSED（与处理中的 RECEIVED）判重</b>；
 *       上一轮被拒绝（REJECTED）的同一 notifyId 允许重投重试；</li>
 *   <li>② 状态 CAS：仅 {@code pending → paid} 允许，已 paid/终态拒绝（SQL 条件更新，并发只成功一次）；</li>
 *   <li>③ 金额校验：通知金额 ≠ 支付单金额 → 告警日志 + BizException，事务回滚（② 的状态翻转一并撤销）。</li>
 * </ol>
 *
 * <p><b>第二道防线（③ 之后、入账提交前，P1）</b>：③ 只证明「通知金额 == 支付单金额」，而支付单金额的源头
 * 是 prepay 时刻反查到的订单 {@code payAmount}。若 prepay 之后订单金额被改（管理端改价、优惠重算），
 * ③ 会拿着旧支付单金额照常入账，钱与单据不一致且完全静默。故入账前再反查一次订单当前 {@code payAmount}
 * 与支付单金额比对：<b>不一致 → ERROR 告警 + 拒绝入账</b>（让不一致暴露，而不是静默入账）；
 * <b>反查失败/超时 → 仅告警放行</b>（见 {@link #verifyOrderPayAmount} 的权衡说明）。</p>
 *
 * <p><b>为什么 ① 只判 PROCESSED（P1-1 修复）</b>：① 的日志先独立提交（否则与 ②③ 同事务回滚后
 * 连「已处理」都记不住）。若 ① 对所有已存在的行一律判重，则 ③ 失败回滚后，渠道按标准语义用
 * <b>同一 notifyId</b> 重投正确金额的通知会被永久判为「重复」→ 返回成功给渠道但支付单永远停在
 * pending（钱收了不入账，且对账任务只扫 paid，覆盖不到 pending → 无自动恢复路径）。
 * 因此本实现把日志行标为 RECEIVED/PROCESSED/REJECTED 三态，③ 失败置 REJECTED 放行重试。</p>
 *
 * <p>入账（本地事务提交）后才通知订单服务并发 {@code payment.success} 事件；
 * 订单通知失败不回滚支付单（支付事实已成立），由对账任务扫描补偿（见 {@code PaymentReconcileJob}）。</p>
 *
 * <p><b>为什么不用 {@code @Idempotent}</b>：common 的幂等拦截器是进程内内存 Map（单实例有效），
 * 支付回调必须多实例安全 → 幂等落在 DB 唯一键 + 状态 CAS 上（本类即该实现）。</p>
 */
@Service
public class PaymentNotifyService {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotifyService.class);

    /**
     * RECEIVED 视为「处理中」的时长上限：超过该窗口仍未 PROCESSED（进程在中途崩溃）即视为陈旧，
     * 允许渠道重投重试。② 的状态 CAS 与金额校验本身幂等，重放不会重复入账。
     */
    private static final Duration STALE_IN_FLIGHT = Duration.ofMinutes(5);

    private final PaymentOrderRepository paymentRepo;
    private final PaymentNotifyLogRepository notifyLogRepo;
    private final OrderClient orderClient;
    private final DomainEventPublisher eventPublisher;
    private final SnowflakeIdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;

    public PaymentNotifyService(PaymentOrderRepository paymentRepo,
                                PaymentNotifyLogRepository notifyLogRepo,
                                OrderClient orderClient,
                                DomainEventPublisher eventPublisher,
                                SnowflakeIdGenerator idGenerator,
                                PlatformTransactionManager transactionManager) {
        this.paymentRepo = paymentRepo;
        this.notifyLogRepo = notifyLogRepo;
        this.orderClient = orderClient;
        this.eventPublisher = eventPublisher;
        this.idGenerator = idGenerator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 处理支付成功回调。本方法自身不开事务（第 ① 步要先独立提交去重日志，
     * 避免与 ②③ 同事务回滚后失去去重能力）。
     */
    public NotifyOutcome handleNotify(NotifyCommand cmd) {
        // ① notify_log 去重：只对已 PROCESSED（与处理中的 RECEIVED）判重；REJECTED 放行重投
        if (!claimNotifyId(cmd)) {
            log.info("重复支付通知（notify_id 去重）notifyId={} paymentId={}", cmd.notifyId(), cmd.paymentId());
            return NotifyOutcome.repeated();
        }

        // ② 状态 CAS + ③ 金额校验 + ④ 反查订单应付金额（同一本地事务：不符 → 异常回滚，② 的状态翻转一并撤销）
        NotifyOutcome outcome;
        try {
            outcome = transactionTemplate.execute(s -> casAndVerify(cmd));
        } catch (RuntimeException e) {
            // ②③④ 失败：把该 notify_id 标记为 REJECTED，使渠道按标准语义用同一 notifyId
            // 修正后重投能被处理（否则钱已收但支付单永远停在 pending，无自动恢复路径）
            markRejected(cmd.notifyId());
            throw e;
        }
        if (outcome == null || !outcome.paid()) {
            // 幂等确认（支付单已 paid）同样算该通知已被消费，标 PROCESSED
            markProcessed(cmd.notifyId());
            return NotifyOutcome.repeated();
        }

        // 入账成功（事务已提交）→ 通知订单状态机 + 发 payment.success（资金事件，对齐 DomainEventNames 注册表）
        markProcessed(cmd.notifyId());
        notifyOrderAndPublish(cmd.paymentId());
        return outcome;
    }

    /**
     * 第 ① 步：占用（并按需创建）notify_id 去重行。
     *
     * @return {@code true}=本次可以继续处理 ②③；{@code false}=重复通知，直接返回成功
     */
    private boolean claimNotifyId(NotifyCommand cmd) {
        var existing = notifyLogRepo.findByNotifyId(cmd.notifyId());
        if (existing.isPresent()) {
            PaymentNotifyLog row = existing.get();
            if (row.processed()) {
                return false; // 已入账：一律判重
            }
            if (row.rejected()) {
                // 上一轮被拒绝 → 放行重投（不新增行，复用同一行以保住 notify_id 唯一键）
                log.info("支付通知曾被拒绝，允许同 notifyId 重投重试: notifyId={} paymentId={}",
                        cmd.notifyId(), cmd.paymentId());
                return true;
            }
            // RECEIVED：处理中。若已陈旧（进程在中途崩溃）放行重试，否则视为并发中的重复通知
            if (isStaleInFlight(row)) {
                log.warn("支付通知长时间停留在处理中，疑似中断，允许同 notifyId 重投: notifyId={} createdAt={}",
                        cmd.notifyId(), row.getCreatedAt());
                return true;
            }
            return false;
        }
        try {
            // 仓库 save 自带事务：去重日志先独立提交；并发同 notifyId 撞唯一键 → 按重复处理
            notifyLogRepo.saveAndFlush(newNotifyLog(cmd));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("并发重复支付通知（唯一键冲突）notifyId={} paymentId={}", cmd.notifyId(), cmd.paymentId());
            return false;
        }
    }

    /** 处理中的日志行超过 {@link #STALE_IN_FLIGHT} 未出结果即视为中断遗留，可重投。 */
    private boolean isStaleInFlight(PaymentNotifyLog notifyLog) {
        Instant createdAt = notifyLog.getCreatedAt();
        return createdAt != null && createdAt.isBefore(Instant.now().minus(STALE_IN_FLIGHT));
    }

    /** 标记已入账（判重依据）。失败只告警：钱已入账事实不受影响，重投会被 ② 的 CAS 幂等吸收。 */
    private void markProcessed(String notifyId) {
        updateStatus(notifyId, PaymentNotifyLog.STATUS_PROCESSED);
    }

    /** 标记被拒绝（放行同 notifyId 重投）。 */
    private void markRejected(String notifyId) {
        updateStatus(notifyId, PaymentNotifyLog.STATUS_REJECTED);
    }

    private void updateStatus(String notifyId, String status) {
        try {
            notifyLogRepo.findByNotifyId(notifyId).ifPresent(row -> {
                row.setProcessStatus(status);
                notifyLogRepo.save(row); // repo 方法自带事务，单独提交（不在 ②③ 的事务里）
            });
        } catch (Exception e) {
            log.error("[支付告警] 通知日志状态更新失败: notifyId={} status={}", notifyId, status, e);
        }
    }

    private PaymentNotifyLog newNotifyLog(NotifyCommand cmd) {
        PaymentOrder payment = paymentRepo.findById(cmd.paymentId())
                .orElseThrow(() -> new BizException(BizErrorCode.PAYMENT_FAILED,
                        "支付单不存在 paymentId=" + cmd.paymentId()));
        PaymentNotifyLog notifyLog = new PaymentNotifyLog();
        notifyLog.setId(idGenerator.nextId()); // 雪花 ID（裁决 C13，禁止自增）
        notifyLog.setNotifyId(cmd.notifyId());
        notifyLog.setPaymentId(payment.getId());
        notifyLog.setOrderId(payment.getOrderId());
        notifyLog.setAmount(cmd.amount());
        notifyLog.setThirdPartyNo(cmd.thirdPartyNo());
        return notifyLog;
    }

    /** ② 状态 CAS + ③ 金额校验 + ④ 反查订单应付金额（在事务内执行，④ 的依赖抖动放行不阻断）。 */
    private NotifyOutcome casAndVerify(NotifyCommand cmd) {
        PaymentOrder payment = paymentRepo.findById(cmd.paymentId())
                .orElseThrow(() -> new BizException(BizErrorCode.PAYMENT_FAILED,
                        "支付单不存在 paymentId=" + cmd.paymentId()));

        // ② 状态 CAS：仅 pending → paid
        int rows = paymentRepo.casMarkPaid(payment.getId(),
                PaymentOrder.STATUS_PENDING, PaymentOrder.STATUS_PAID,
                cmd.thirdPartyNo(), Instant.now());
        if (rows == 0) {
            if (PaymentOrder.STATUS_PAID == payment.getStatus()) {
                // 换了通知号但支付单已 paid：按重复通知幂等返回成功（渠道停止重试）
                log.info("支付单已入账，重复通知幂等返回 paymentId={} notifyId={}", payment.getId(), cmd.notifyId());
                return NotifyOutcome.repeated();
            }
            // 终态（退款中/已退款）收到成功回调：告警并拒绝（已退款还收到扣款成功，须人工介入）
            log.error("[支付告警] 终态支付单收到成功回调，拒绝 paymentId={} status={} notifyId={}",
                    payment.getId(), payment.statusText(), cmd.notifyId());
            throw new BizException(BizErrorCode.PAYMENT_FAILED,
                    "支付单终态，拒绝回调 status=" + payment.statusText());
        }

        // ③ 金额校验：通知金额 ≠ 支付单金额 → 告警 + 拒绝（回滚 ②）
        if (cmd.amount() == null || cmd.amount().compareTo(payment.getAmount()) != 0) {
            log.error("[支付告警] 回调金额与支付单不一致，拒绝入账 paymentId={} orderId={} 通知金额={} 支付单金额={} notifyId={}",
                    payment.getId(), payment.getOrderId(), cmd.amount(), payment.getAmount(), cmd.notifyId());
            throw new BizException(BizErrorCode.PAYMENT_FAILED, "支付回调金额与支付单不一致");
        }

        // ④ 第二道防线：反查订单当前应付金额再比对一次（防止 prepay 之后订单被改价仍按旧金额静默入账）
        verifyOrderPayAmount(payment, cmd);
        return NotifyOutcome.firstPaid();
    }

    /**
     * 第二道防线：反查订单当前 {@code payAmount} 与支付单金额二次比对。
     *
     * <p><b>为什么必须做</b>：③ 的比对基准是「支付单金额」，而支付单金额来自 prepay 时刻的订单快照。
     * prepay 之后若订单被改价（管理端改价、优惠重算等），③ 会用旧金额照常入账——钱与单据不一致却完全静默，
     * 直到对账才发现。</p>
     *
     * <p><b>性能与可用性权衡（勿改成「失败即拒绝」）</b>：这里是一次同步 HTTP，回调是热路径，
     * 订单服务抖动会直接放大成「入账失败」。两个方向的错误代价极不对称：
     * <ul>
     *   <li>因订单服务抖动拒绝入账 → <b>用户已扣款但系统显示未支付</b>（真实资损 + 客诉 + 需批量补单）；</li>
     *   <li>漏掉一次比对 → 异常场景靠人工对账/告警兜底（金额漂移本身已是上游问题，不是本层引入的）。</li>
     * </ul>
     * 因此口径是：<b>反查失败/超时 → 只告警放行</b>（沿用本类既有语义「支付事实已成立、对账兜底」，
     * 与 {@link #notifyOrderAndPublish} 中订单通知失败不回滚一致）；
     * <b>反查成功且金额不一致 → 告警 + 拒绝入账</b>，让钱与单据不一致暴露出来而不是静默沉淀。
     * 另：④ 只在 ② 的 {@code pending → paid} 真实翻转分支里执行，重复通知不重复反查。</p>
     */
    private void verifyOrderPayAmount(PaymentOrder payment, NotifyCommand cmd) {
        OrderSnapshot order;
        try {
            order = orderClient.getOrder(payment.getOrderId());
        } catch (Exception e) {
            log.warn("[支付告警] 反查订单应付金额失败，放行入账（钱已收，不因依赖抖动拒绝）"
                            + " paymentId={} orderId={} 支付单金额={} notifyId={} err={}",
                    payment.getId(), payment.getOrderId(), payment.getAmount(), cmd.notifyId(), e.toString());
            return;
        }
        if (order == null || order.payAmount() == null) {
            log.warn("[支付告警] 反查订单应付金额缺少 payAmount，放行入账（待人工/对账核实）"
                            + " paymentId={} orderId={} 支付单金额={} notifyId={}",
                    payment.getId(), payment.getOrderId(), payment.getAmount(), cmd.notifyId());
            return;
        }
        if (payment.getAmount().compareTo(order.payAmount()) != 0) {
            log.error("[支付告警] 支付单金额与订单当前应付金额不一致（疑似 prepay 后改价），拒绝入账"
                            + " paymentId={} orderId={} 支付单金额={} 订单应付={} 通知金额={} orderStatus={} notifyId={}",
                    payment.getId(), payment.getOrderId(), payment.getAmount(), order.payAmount(),
                    cmd.amount(), order.status(), cmd.notifyId());
            throw new BizException(BizErrorCode.PAYMENT_FAILED, "支付回调金额与订单应付金额不一致");
        }
    }

    private void notifyOrderAndPublish(Long paymentId) {
        PaymentOrder p = paymentRepo.findById(paymentId).orElseThrow();
        try {
            orderClient.markPaid(p.getOrderId(), p.getId(), p.getAmount());
            paymentRepo.markOrderPaidNotified(p.getId()); // repo 方法自带事务，单独提交
        } catch (Exception e) {
            // 支付事实已成立，订单侧通知失败不回滚；对账任务每小时扫描 status=paid & 未确认 的单子补偿
            log.error("[支付告警] 支付已入账但订单 paid 通知失败，等待对账补偿 paymentId={} orderId={}",
                    p.getId(), p.getOrderId(), e);
        }
        eventPublisher.publish(DomainEventNames.PAYMENT_SUCCESS, Map.of(
                "paymentId", p.getId(),
                "orderId", p.getOrderId(),
                "userId", p.getUserId(),
                "amount", p.getAmount(),
                "channel", p.getChannel(),
                "thirdPartyNo", p.getThirdPartyNo() == null ? "" : p.getThirdPartyNo(),
                "paidAt", p.getPaidAt() == null ? Instant.now() : p.getPaidAt()));
    }
}
