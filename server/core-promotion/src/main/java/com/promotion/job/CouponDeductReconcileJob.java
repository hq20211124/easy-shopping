package com.promotion.job;

import com.promotion.service.CouponService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 核销未确认兜底回收（P0-G）。
 *
 * <p>核销（券置 used）与订单落库/支付成功是跨服务两步：订单最终失败、创建中途回滚、或关单链路
 * 未退券时，券会永久停留 used——用户既拿不到券、订单也没有。本任务按
 * 「核销超过 {@code app.promotion.deduct-timeout-minutes} + 回查订单确认订单确实没了」
 * 把券条件更新回 available。</p>
 *
 * <p>幂等：回收走 {@code used → available} 条件更新，重复执行只有第一次生效，不会把模板
 * usedCount 扣成负数。进程内调度（P0），多副本重复扫描同样安全（CAS 收敛）。</p>
 */
@Component
public class CouponDeductReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CouponDeductReconcileJob.class);

    private final CouponService couponService;

    public CouponDeductReconcileJob(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 每 10 分钟执行一次（cron 可配置覆盖，测试置为远期）。 */
    @Scheduled(cron = "${app.promotion.deduct-reconcile-cron:0 */10 * * * *}")
    public void schedule() {
        int reclaimed;
        try {
            reclaimed = couponService.reclaimUnconfirmedDeducted();
        } catch (RuntimeException e) {
            log.error("[coupon-deduct-reclaim] 兜底回收任务异常，下轮重试", e);
            return;
        }
        if (reclaimed > 0) {
            log.warn("[coupon-deduct-reclaim] 本轮回收核销未确认的券 count={}（已 WARN 逐条打印）", reclaimed);
        }
    }
}
