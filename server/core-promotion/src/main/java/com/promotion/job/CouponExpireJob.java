package com.promotion.job;

import com.promotion.service.CouponService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期券状态翻转（定时扫 available 且 expireAt &lt; now → expired）。
 *
 * <p>条件更新单条 SQL 完成，多实例重复执行安全（幂等）。
 * P0 进程内调度；P1 换 MQ 延迟消息（券到期精确推送）+ 分布式调度。</p>
 */
@Component
public class CouponExpireJob {

    private static final Logger log = LoggerFactory.getLogger(CouponExpireJob.class);

    private final CouponService couponService;

    public CouponExpireJob(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 每小时第 20 分执行（cron 可配置覆盖，测试置为远期）。 */
    @Scheduled(cron = "${app.promotion.expire-cron:0 20 * * * *}")
    public void schedule() {
        int expired = couponService.expireDue();
        if (expired > 0) {
            log.info("coupon expire flipped count={}", expired);
        }
    }
}
