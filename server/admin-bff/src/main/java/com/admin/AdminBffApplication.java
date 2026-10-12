package com.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 管理端 API 聚合 BFF :8090（21 个 admin-* 细分 P1 前的聚合承载）。
 *
 * <p>P0 骨架：仅应用入口 + 健康检查 + 占位接口；业务实现见 plans/*-backend.md 对应章节。</p>
 */
@SpringBootApplication
public class AdminBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminBffApplication.class, args);
    }
}