package com.easyshopping.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 全上下文启动测试：验证 JPA + H2（MODE=MySQL）+ common 自动装配（雪花/JWT/事件）在本服务可用。
 * 数据源覆盖为内存库，避免测试污染 ./data 开发库文件。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-user-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CoreUserApplicationTests {

    @Test
    void contextLoads() {
    }
}
