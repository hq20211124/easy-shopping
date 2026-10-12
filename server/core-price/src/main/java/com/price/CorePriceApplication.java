package com.price;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 价格查询/计算服务 :8083。
 *
 * <p>P0 骨架：仅应用入口 + 健康检查 + 占位接口；业务实现见 plans/*-backend.md 对应章节。</p>
 */
@SpringBootApplication
public class CorePriceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorePriceApplication.class, args);
    }
}