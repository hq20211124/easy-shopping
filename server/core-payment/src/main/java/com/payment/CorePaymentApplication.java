package com.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 支付 mock/回调幂等/退款 :8087。
 *
 * <p>P0 实现：prepay（防改价）/ mock 支付 / 回调幂等三步 / 查单 / 退款 / 对账兜底定时任务。
 * 业务契约见本模块 README 与 plans/*-backend.md 对应章节。</p>
 */
@SpringBootApplication
@EnableScheduling // 对账兜底定时任务（PaymentReconcileJob）；P1 换 MQ 定时消息 + 分布式调度
public class CorePaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorePaymentApplication.class, args);
    }
}