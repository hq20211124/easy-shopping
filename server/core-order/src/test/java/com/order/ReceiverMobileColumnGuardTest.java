package com.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.order.crypto.ReceiverMobileColumnGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期密文列宽巡检测试。
 *
 * <p>巡检存在的意义：H2（{@code ddl-auto=update}）与 MySQL 都<b>不会</b>把已有列从
 * {@code varchar(20)} 自动扩到 {@code varchar(255)}，存量库不扩列的话第一笔下单会报
 * 一个看不懂的 {@code Value too long for column}。这里用「真把列改窄再触发巡检」的方式，
 * 验证巡检会打出<b>带修复 SQL</b>的 ERROR，而不是默默放行。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-column-guard-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000",
        "app.crypto.receiver-mobile.enabled=true",
        "app.crypto.receiver-mobile.keyring=k1=ZGV2LW9yZGVyLW1vYmlsZS1rZXktMzJieXRlLTAwMDE="
})
class ReceiverMobileColumnGuardTest extends AbstractOrderTest {

    @Autowired
    private ReceiverMobileColumnGuard columnGuard;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ch.qos.logback.classic.Logger guardLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        guardLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ReceiverMobileColumnGuard.class);
        appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        guardLogger.detachAppender(appender);
        appender.stop();
        // 还原列宽，避免影响其它用例（create-drop 库虽独立，但本类用例之间共享）
        jdbcTemplate.execute("ALTER TABLE order_main ALTER COLUMN receiver_mobile VARCHAR(255)");
    }

    private String logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private boolean hasLevel(Level level) {
        return appender.list.stream().anyMatch(e -> e.getLevel() == level);
    }

    @Test
    void 列宽足够_巡检通过并打出实际列宽() {
        columnGuard.run(null);

        assertThat(hasLevel(Level.INFO)).isTrue();
        assertThat(logs()).contains("列宽巡检通过").doesNotContain("[密文列过窄]");
    }

    @Test
    void 列宽不足_打ERROR并给出修复SQL() {
        jdbcTemplate.execute("ALTER TABLE order_main ALTER COLUMN receiver_mobile VARCHAR(20)");

        columnGuard.run(null);

        assertThat(hasLevel(Level.ERROR)).isTrue();
        assertThat(logs()).contains("[密文列过窄]").contains("order_main")
                .contains("VARCHAR(255)").contains("ALTER TABLE order_main");
    }
}