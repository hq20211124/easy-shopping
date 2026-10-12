package com.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商品 SPU/SKU/类目/上下架服务 :8082。
 *
 * <p>P0 骨架：仅应用入口 + 健康检查 + 占位接口；业务实现见 plans/*-backend.md 对应章节。</p>
 */
@SpringBootApplication
public class CoreProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreProductApplication.class, args);
    }
}