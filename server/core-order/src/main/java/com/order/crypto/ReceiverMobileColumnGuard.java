package com.order.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 启动期列宽巡检：确认 {@code order_main.receiver_mobile} 装得下密文。
 *
 * <p><b>为什么需要巡检（而不是等写库报错）</b>：密文比 11 位手机号长（实测约 65 字符），
 * 而 plans DDL 与存量 H2 文件库里的列是 {@code varchar(20)}。Hibernate {@code ddl-auto=update}
 * <b>只会加表/加列，不会改已有列的长度</b>，于是：
 * <ul>
 *   <li>新建库（测试 {@code create-drop} / 生产 Flyway 建表）→ 实体声明的 varchar(255)，一切正常；</li>
 *   <li>存量库（本地 {@code ./data/core-order} 的旧文件）→ 列宽仍是 20，
 *       第一笔下单会以一个含义极不直观的 {@code Value too long for column} 报错。</li>
 * </ul>
 * 巡检在启动时把这个坑<b>变成一条带修复 SQL 的 ERROR 日志</b>，而不是运行期一个看不懂的 SQL 异常。</p>
 *
 * <p><b>为什么不 fail-fast 拦停</b>：本巡检只 ERROR 不抛异常，避免两种更糟的情况——
 * ① 运维临时连到只读副本 / 元数据查询被 DB 账号权限挡住时把服务卡在起不来；
 * ② 存量列过窄时服务<b>并非完全不可用</b>（查询、脱敏、明文专用接口都正常，只是新建单会失败）。
 * 真正的「PII 静默降级」风险已由 {@link ReceiverMobileCipher} 的缺密钥 fail-fast 挡住，
 * 本条属于可用性/可运维性提示，量级不同。</p>
 */
@Component
public class ReceiverMobileColumnGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReceiverMobileColumnGuard.class);

    /** 与 {@code OrderMain#receiverMobile} 的 {@code @Column(length)} 保持一致。 */
    private static final int REQUIRED_LENGTH = 255;

    private static final String TABLE = "order_main";
    private static final String COLUMN = "receiver_mobile";

    private final DataSource dataSource;
    private final ReceiverMobileCipher cipher;

    public ReceiverMobileColumnGuard(DataSource dataSource, ReceiverMobileCipher cipher) {
        this.dataSource = dataSource;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!cipher.isEncryptionEnabled()) {
            log.info("收货人手机号加密未启用，跳过列宽巡检");
            return;
        }
        Integer size;
        try (Connection connection = dataSource.getConnection()) {
            size = columnSize(connection.getMetaData(), connection);
        } catch (SQLException e) {
            log.warn("[列宽巡检跳过] 无法读取列元数据（不影响功能，写库时仍会给出数据库错误）: {}", e.toString());
            return;
        }
        if (size == null) {
            log.warn("[列宽巡检跳过] 未找到 {}.{} 列元数据（不影响功能）", TABLE, COLUMN);
            return;
        }
        if (size < REQUIRED_LENGTH) {
            log.error("[密文列过窄] {}.{} 当前长度为 {}，密文需要 {}（手机号明文加密后约 65 字符）。"
                            + "H2(ddl-auto=update)/MySQL 均不会自动扩列，请手工执行："
                            + "ALTER TABLE order_main MODIFY COLUMN receiver_mobile VARCHAR(255);"
                            + "（H2 亦可用 ALTER TABLE order_main ALTER COLUMN receiver_mobile VARCHAR(255)）",
                    TABLE, COLUMN, size, REQUIRED_LENGTH);
            return;
        }
        log.info("收货人手机号密文列宽巡检通过: {}.{} = {}", TABLE, COLUMN, size);
    }

    /**
     * 读列长度；标识符大小写在 H2（{@code DATABASE_TO_LOWER}）与 MySQL 上不一致，
     * 故先按当前库的小写名精确查，再退化为全表扫描后忽略大小写匹配。
     */
    private Integer columnSize(DatabaseMetaData metaData, Connection connection) throws SQLException {
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, TABLE, COLUMN)) {
            Integer direct = firstSize(rs);
            if (direct != null) {
                return direct;
            }
        }
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null,
                TABLE.toUpperCase(), COLUMN.toUpperCase())) {
            Integer upper = firstSize(rs);
            if (upper != null) {
                return upper;
            }
        }
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, null, null)) {
            while (rs.next()) {
                if (TABLE.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && COLUMN.equals(rs.getString("COLUMN_NAME"))) {
                    return rs.getInt("COLUMN_SIZE");
                }
            }
        }
        return null;
    }

    private Integer firstSize(ResultSet rs) throws SQLException {
        if (rs.next()) {
            return rs.getInt("COLUMN_SIZE");
        }
        return null;
    }
}