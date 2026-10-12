package com.promotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 优惠券领取/核销/促销服务 :8088。
 *
 * <p>P0 实现：券模板 CRUD（管理端）/ 领取（防超领、限领）/ 试算 / 核销（防重复）/ 关单退回 / 过期翻转。
 * 业务契约见本模块 README 与 plans/admin-backend.md 七、营销服务。</p>
 */
@SpringBootApplication
@EnableScheduling // 过期券翻转定时任务（CouponExpireJob）；P1 换 MQ 延迟消息 + 分布式调度
public class CorePromotionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorePromotionApplication.class, args);
    }
}