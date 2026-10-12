package com.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商品搜索（P0 DB like，P1 ES）:8089。
 *
 * <p>ENABLE scheduling：搜索冗余表启动时 + 定时从 core-product HTTP 拉取刷新
 * （见 com.search.service.SearchSyncService；禁止直连其他服务 DB —— DEV.md 4.4）。</p>
 */
@SpringBootApplication
@EnableScheduling
public class CoreSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreSearchApplication.class, args);
    }
}
