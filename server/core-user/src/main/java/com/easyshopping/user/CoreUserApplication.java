package com.easyshopping.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户/认证/地址服务入口（:8081）。
 *
 * <p>职责：登录（JWT + refresh）、用户资料、收货地址。对应买家端共享服务 core-user
 * （plans/admin-backend.md 1.1.3），管理端 admin-user/admin-auth 的 P0 聚合入口为 admin-bff。</p>
 */
@SpringBootApplication
public class CoreUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreUserApplication.class, args);
    }
}
