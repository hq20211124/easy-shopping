package com.order.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 存量明文 → 密文的一次性刷数（可重复执行、幂等；<b>默认不执行</b>，须显式开关打开）。
 *
 * <p><b>开关</b>：{@code app.crypto.receiver-mobile.backfill-enabled=true}
 * （由 {@link ReceiverMobileBackfillRunner} 在<b>启动期</b>触发，见该类注释）。默认 false——
 * 刷数会全表扫 + 逐行 UPDATE，属于运维动作而非服务日常行为，绝不能默认打开。</p>
 *
 * <p><b>实现选择：绕开实体、直接走 JDBC</b>。原因是 {@code receiver_mobile} 上挂了
 * {@link ReceiverMobileConverter}，任何实体读出来的都是<b>明文</b>，无法再区分「这行是存量明文」
 * 与「这行是密文」——只能按前缀判定，而前缀只存在于原始列值里。用
 * {@code SELECT id, receiver_mobile ... WHERE receiver_mobile NOT LIKE 'v1:%'} 精确圈定存量行，
 * 再按原值回写密文，顺带避免「把实体 load 进来再 save」触发脏检查把无关列一起重写。</p>
 *
 * <p><b>幂等与并发安全</b>：① 分页用 keyset（{@code WHERE id > lastId ORDER BY id LIMIT n}），
 * 不用 OFFSET——OFFSET 会在边改边翻时漏行/重复；② UPDATE 带
 * {@code AND receiver_mobile = <原值>} 乐观守卫，命中 0 行说明该行已被其它实例刷过，跳过即可；
 * ③ 密文随机 IV，因此「同一行刷两次」也不会产生判定歧义；
 * ④ 已经带 {@code v1:} 前缀的行 / 空串 / NULL 行<b>一律跳过</b>（不含 PII，刷了也只是浪费）。</p>
 *
 * <p><b>失败语义</b>：单行解密/加密异常不吞——记录订单 ID 后<b>继续处理下一行</b>并计数，
 * 整批结束时若 {@code failed > 0} 打 ERROR（刷数是运维动作，不能因为一行坏数据把整批卡住，
 * 但也绝不能静默跳过：坏行会被读取侧的 fail-fast 解密异常挡住，必须有人看见）。</p>
 */
@Service
public class ReceiverMobileBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ReceiverMobileBackfillService.class);

    /** 默认批大小；P0 数据量小，批大小只影响回滚粒度，不影响正确性。 */
    public static final int DEFAULT_BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ReceiverMobileCipher cipher;

    public ReceiverMobileBackfillService(JdbcTemplate jdbcTemplate, ReceiverMobileCipher cipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.cipher = cipher;
    }

    /**
     * 刷数结果（供运维核对与用例断言）。
     *
     * @param converted 实际由明文改写为密文的行数
     * @param failed    刷数失败的行数（>0 必须人工介入）
     */
    public record BackfillResult(int converted, int failed) {
    }

    /**
     * 把存量明文手机号刷成密文（幂等，可重复调用）。
     *
     * @param batchSize 分页大小
     * @return 转换行数 / 失败行数
     */
    public BackfillResult backfill(int batchSize) {
        int size = Math.max(1, batchSize);
        if (!cipher.isEncryptionEnabled()) {
            log.warn("[手机号加密已关闭] 跳过存量刷数：enabled=false 时加密是恒等映射，跑了不会产生密文");
            return new BackfillResult(0, 0);
        }
        long start = System.currentTimeMillis();
        long lastId = 0L;
        int total = 0;
        int failed = 0;
        while (true) {
            List<Row> rows = loadBatch(lastId, size);
            if (rows.isEmpty()) {
                break;
            }
            for (Row row : rows) {
                lastId = Math.max(lastId, row.id());
                try {
                    total += convertOne(row);
                } catch (RuntimeException e) {
                    failed++;
                    log.error("收货人手机号刷数失败（该行仍为明文，读取时会因格式非 v1: 而按明文直出）: orderId={} cause={}",
                            row.id(), e.toString());
                }
            }
            if (rows.size() < size) {
                break;
            }
        }
        log.info("收货人手机号存量刷数完成: converted={} failed={} costMs={}", total, failed,
                System.currentTimeMillis() - start);
        if (failed > 0) {
            log.error("[刷数告警] {} 行未完成转换，需人工排查后再重跑（重跑幂等）", failed);
        }
        return new BackfillResult(total, failed);
    }

    /**
     * 单行转换：命中 0 行 = 已被并发实例刷过（乐观守卫拦下），不算失败。
     *
     * <p>不加 {@code @Transactional}：本方法是同类内部调用（不走代理，注解本就不生效），
     * 单条 UPDATE 由 JdbcTemplate 自动提交即可；逐行提交还让「刷到一半失败」天然可续跑（幂等）。</p>
     */
    public int convertOne(Row row) {
        String cipherText = cipher.encrypt(row.mobile());
        int updated = jdbcTemplate.update(
                "UPDATE order_main SET receiver_mobile = ? WHERE id = ? AND receiver_mobile = ?",
                cipherText, row.id(), row.mobile());
        if (updated == 0) {
            log.debug("收货人手机号刷数跳过（行已被并发刷新）: orderId={}", row.id());
        }
        return updated;
    }

    private List<Row> loadBatch(long lastId, int size) {
        return jdbcTemplate.query("SELECT id, receiver_mobile FROM order_main "
                        + "WHERE id > ? AND receiver_mobile IS NOT NULL AND receiver_mobile <> '' "
                        + "AND receiver_mobile NOT LIKE '" + ReceiverMobileCipher.VERSION_PREFIX + "%' "
                        + "ORDER BY id LIMIT " + size,
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("receiver_mobile")), lastId);
    }

    /** 待刷行（id + 列里的原始值）。 */
    public record Row(long id, String mobile) {
    }
}