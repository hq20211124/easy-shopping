package com.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务（:8086）：下单/拆单/8 值状态机（0 基）/pay_deadline 超时关单/查询。
 *
 * <p>P0：下单即按 store_id 拆单（优惠比例分摊 + 尾差归集首单）、库存预占联动、
 * 超时关单调度（每 10s 扫 pay_deadline，与支付回调 version CAS 裁决）。
 * 业务设计见 plans/admin-backend.md 四、订单服务 / plans/android-backend.md 4.x。</p>
 */
@SpringBootApplication
@EnableScheduling
public class CoreOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreOrderApplication.class, args);
    }
}
