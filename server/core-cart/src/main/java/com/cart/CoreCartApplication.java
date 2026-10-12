package com.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 购物车服务 :8085。
 *
 * <p>P0 骨架：仅应用入口 + 健康检查 + 占位接口；业务实现见 plans/*-backend.md 对应章节。</p>
 */
@SpringBootApplication
public class CoreCartApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreCartApplication.class, args);
    }
}