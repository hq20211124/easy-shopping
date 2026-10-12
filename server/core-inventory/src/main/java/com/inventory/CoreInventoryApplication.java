package com.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 库存预占/释放/扣减服务（:8084）。
 *
 * <p>P0：预占 = available→locked（@Version 乐观锁 + 重试上限，绝不超卖）；
 * 确认收货扣减、取消/超时释放；预占超时由进程内扫描兜底（P1 换 MQ 延迟队列）。
 * 业务设计见 plans/admin-backend.md 五、库存服务 / 4.4.4。</p>
 */
@SpringBootApplication
@EnableScheduling
public class CoreInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreInventoryApplication.class, args);
    }
}
