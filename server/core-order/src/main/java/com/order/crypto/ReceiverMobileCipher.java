package com.order.crypto;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 收货人手机号字段级加密（AES-256-GCM，自描述密文 + 多密钥轮换）。
 *
 * <p><b>为什么落库加密（审计 P1 登记项「落库仍明文」）</b>：接口出参已按 plans 12.5/12.6 脱敏
 * （P0-B4 修复），但 {@code order_main.receiver_mobile} 列里躺的是明文——库文件/备份/只读副本/
 * 运维直连 DBA 均可绕过接口层拿到全量手机号，属于典型的「接口合规、存储裸奔」。</p>
 *
 * <p><b>密文格式（自描述，便于轮换与明文/密文共存）</b>：
 * <pre>{@code v1:<keyId>:<iv_base64>:<ciphertext_base64>}</pre>
 * - {@code v1}：格式版本前缀。读取时<b>以此前缀判定是密文还是存量明文</b>——
 *   不带前缀一律按明文原样返回，因此<b>存量明文行与新密文行可在同一列共存</b>，无需停机刷数；
 * - {@code keyId}：密钥标识（配置里的 {@code k1=} 那个名字），用于多密钥轮换定位；
 * - {@code iv}：12 字节随机 IV（每次加密<b>必新</b>，SecureRandom），base64；
 * - {@code ciphertext}：明文 UTF-8 字节 + 16 字节 GCM 认证标签，base64。
 * 手机号 11 位的实际长度约 65 字符，故列宽取 {@code varchar(255)}（H2/MySQL 通用，见
 * {@link com.order.domain.OrderMain#getReceiverMobile()}）。</p>
 *
 * <p><b>算法</b>：AES/GCM/NoPadding，密钥 256 位、认证标签 128 位。GCM 同时提供机密性与
 * 完整性——密文被篡改/密钥不匹配时 {@code doFinal} 抛 {@code AEADBadTagException}，
 * 本类将其转成<b>明确的业务异常（9999）</b>，绝不降级返回空串或垃圾字符（返回垃圾会让
 * 下游把它当真手机号发短信/打客服电话，是比报错更糟的失败模式）。</p>
 *
 * <p><b>密钥轮换</b>：配置 {@code app.crypto.receiver-mobile.keyring=k1=<b64>,k2=<b64>}，
 * <b>第一个为当前加密密钥</b>，其余仅用于解密。轮换 = 把新密钥加到<b>首位</b>并保留旧密钥 →
 * 新数据用新密钥、旧数据仍可读 → 待存量刷数完成后（旧密钥无引用）再摘除旧密钥。
 * 缺失 keyId 即 fail-closed 抛错，强制运维先把密钥补齐。</p>
 *
 * <p><b>密钥来源可替换</b>：本类只认配置项（P0/P1 无 KMS/Vault），但刻意把「取密钥」收敛在
 * {@link #parseKeyring} 一处；P2 接入 Vault/KMS 时只需另提供一份 keyring 装载实现，
 * 加解密算法与密文格式无需改动。</p>
 *
 * <p><b>开关语义（{@code app.crypto.receiver-mobile.enabled}，默认 true）</b>：
 * <ul>
 *   <li>开启：写入加密、读取按格式自动兼容明文/密文；</li>
 *   <li>关闭：<b>仅停止写入加密</b>（应急回滚开关，新数据退回明文），<b>读取仍然解密</b>——
 *       否则已加密的存量行会直接把 {@code v1:...} 当手机号返回给前端/管理端。
 *       生产建议常开，关闭只在「密文链路出故障需要立刻恢复下单」时临时使用。</li>
 * </ul></p>
 *
 * <p><b>缺密钥 = 启动失败（fail-fast），不静默降级明文</b>：本 Bean 在容器启动时被创建，
 * 密钥缺失/长度不对/base64 非法直接抛 {@link IllegalStateException} 让启动失败。
 * 理由与支付域「金额 fail-closed」同源——PII 路径静默降级为明文，等于没人发现的情况下
 * 又把手机号裸写回库，比启动失败危险得多。运维要临时明文跑，必须<b>显式</b>把 enabled 置 false。</p>
 *
 * <p><b>日志纪律</b>：本类所有日志与异常消息只允许出现 keyId 与失败原因，
 * <b>禁止</b>出现密文片段或明文（{@code GlobalExceptionHandler} 会 WARN 打印 BizException 消息）。</p>
 */
@Component
public class ReceiverMobileCipher {

    private static final Logger log = LoggerFactory.getLogger(ReceiverMobileCipher.class);

    /** 密文格式版本前缀（读取判定密文/明文的唯一依据）。 */
    public static final String VERSION_PREFIX = "v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** GCM 推荐 96 位 IV（12 字节）；JDK 17 对 GCM 强制限制该长度。 */
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int PART_COUNT = 4;

    /** keyId 白名单：不含分隔符 ':'，长度受列宽约束（密文里 keyId 是原样拼接的）。 */
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final boolean enabled;
    private final String activeKeyId;
    private final SecretKey activeKey;
    /** keyId → 密钥（首个为当前加密密钥，其余仅解密），保持配置书写顺序便于审计。 */
    private final Map<String, SecretKey> keyring;

    private final SecureRandom random = new SecureRandom();

    public ReceiverMobileCipher(@Value("${app.crypto.receiver-mobile.enabled:true}") boolean enabled,
                                @Value("${app.crypto.receiver-mobile.keyring:}") String keyringSpec,
                                @Value("${app.crypto.receiver-mobile.active-key-id:}") String activeKeyIdOverride) {
        this.enabled = enabled;
        Map<String, SecretKey> parsed = parseKeyring(keyringSpec);
        if (enabled) {
            if (parsed.isEmpty()) {
                throw new IllegalStateException("app.crypto.receiver-mobile.keyring 未配置：收货人手机号加密已启用"
                        + "（app.crypto.receiver-mobile.enabled=true）却拿不到任何密钥。"
                        + "PII 路径不得静默降级为明文落库，故直接启动失败；"
                        + "确需临时明文请显式配置 app.crypto.receiver-mobile.enabled=false。");
            }
            this.activeKeyId = resolveActiveKeyId(parsed, activeKeyIdOverride);
            this.activeKey = parsed.get(this.activeKeyId);
        } else {
            // 关闭开关：仍装载密钥，使「历史密文行」在明文模式下依然可读（读取端始终兼容解密）
            this.activeKeyId = parsed.isEmpty() ? null : resolveActiveKeyId(parsed, activeKeyIdOverride);
            this.activeKey = parsed.isEmpty() ? null : parsed.get(this.activeKeyId);
            log.warn("[手机号加密已关闭] 新写入将退回明文落库，历史密文行仍按密文解密读取；"
                    + "生产环境建议常开 app.crypto.receiver-mobile.enabled");
        }
        this.keyring = parsed;
        log.info("收货人手机号加密已装载: enabled={} activeKeyId={} keyIds={}",
                enabled, activeKeyId, parsed.keySet());
    }

    /**
     * 写密钥选取：未显式指定 {@code active-key-id} 时取 keyring 的<b>首个</b>；
     * 显式指定则用它（必须是 keyring 里的 keyId），用于<b>滚动发布安全的两阶段轮换</b>：
     * <pre>
     * 阶段一（所有实例都认得新密钥，但仍用旧密钥写）：keyring=k1=&lt;旧&gt;,k2=&lt;新&gt;，active-key-id=k1
     * 阶段二（所有实例改用新密钥写）              ：keyring=k1=&lt;旧&gt;,k2=&lt;新&gt;，active-key-id=k2
     * 阶段三（存量刷数完成、k1 无引用）            ：keyring=k2=&lt;新&gt;，active-key-id=k2
     * </pre>
     * 之所以必须两阶段：若阶段一就把新密钥放首位，老版本实例（keyring 里只有 k1）读到新实例写的
     * {@code v1:k2:...} 会 fail-closed 报「缺少密钥 k2」——滚动发布期间订单查询直接不可用。
     */
    private static String resolveActiveKeyId(Map<String, SecretKey> parsed, String override) {
        if (override == null || override.isBlank()) {
            return parsed.keySet().iterator().next();
        }
        String keyId = override.trim();
        if (!parsed.containsKey(keyId)) {
            throw new IllegalStateException("app.crypto.receiver-mobile.active-key-id=" + keyId
                    + " 不在 keyring 中（可选：" + parsed.keySet() + "）");
        }
        return keyId;
    }

    /**
     * 加密为自描述密文；null/空白原样返回（空串是「本单未填手机号」的既有语义，不值得造一段密文）。
     *
     * <p>开关关闭时返回原文（应急回滚，见类注释）。</p>
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (!enabled) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX + activeKeyId + ":"
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            // 不带原文：异常消息会进 BizException → GlobalExceptionHandler WARN 日志
            throw new BizException(BizErrorCode.SYSTEM_ERROR,
                    "收货人手机号加密失败（keyId=" + activeKeyId + "），请检查密钥配置", e);
        }
    }

    /**
     * 解密；<b>非 {@code v1:} 前缀一律按存量明文原样返回</b>（明文/密文共存的关键）。
     *
     * <p>解密失败（密文被篡改 / keyId 缺失 / base64 非法）一律抛 9999 业务异常，
     * <b>不返回空串也不返回密文本身</b>——调用方是脱敏/明文出参链路，静默兜底会把
     * {@code v1:xxx} 直接当作手机号渲染给用户。</p>
     */
    public String decrypt(String stored) {
        if (stored == null || !isCiphertext(stored)) {
            return stored; // 存量明文（含空串）直出
        }
        String[] parts = requireParts(stored);
        String keyId = parts[1];
        SecretKey key = keyring.get(keyId);
        if (key == null) {
            log.error("收货人手机号密文缺少解密密钥: keyId={} loadedKeyIds={}", keyId, keyring.keySet());
            throw new BizException(BizErrorCode.SYSTEM_ERROR,
                    "收货人手机号密文无法解密：缺少密钥 keyId=" + keyId + "（请先补齐密钥再清理历史密钥）");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[2]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[3]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 认证标签校验失败 = 密文被篡改或密钥不匹配，绝不返回密文/空串
            log.error("收货人手机号密文解密失败（认证不通过或密文非法）: keyId={} cause={}", keyId, e.toString());
            throw new BizException(BizErrorCode.SYSTEM_ERROR,
                    "收货人手机号密文校验失败（数据被篡改或密钥不匹配，keyId=" + keyId + "）", e);
        }
    }

    /**
     * 是否为 v1 密文（读取侧判定依据；存量明文走直出分支）。
     *
     * <p>只认版本前缀，不校验段数：段数异常的密文也必须当密文处理（并抛错），
     * 绝不能因为「解析不出来」就退回明文直出——那等于把攻击者构造的垃圾串当手机号用。</p>
     */
    public static boolean isCiphertext(String stored) {
        return stored != null && stored.startsWith(VERSION_PREFIX);
    }

    /** 密文段拆解：段数不对即格式非法（fail-fast，避免下游 ArrayIndexOutOfBounds）。 */
    private static String[] requireParts(String stored) {
        String[] parts = stored.split(":", -1);
        if (parts.length != PART_COUNT) {
            log.error("收货人手机号密文格式非法（段数={}，应为 v1:keyId:iv:ct）", parts.length);
            throw new BizException(BizErrorCode.SYSTEM_ERROR, "收货人手机号密文格式非法（段数错误）");
        }
        return parts;
    }

    /**
     * 从异常链里还原「收货人手机号解密失败」的业务异常，没有则返回 null。
     *
     * <p><b>为什么需要它</b>：加解密发生在 JPA {@code AttributeConverter} 里，而 JPA 规范要求
     * Converter 抛出的异常被包装成 {@code PersistenceException}（Hibernate 原话
     * "Error attempting to apply AttributeConverter"）。直接冒泡到 {@code GlobalExceptionHandler}
     * 会只剩一句无信息量的「系统错误」，运维看不出是密文被篡改还是缺密钥——这正是 PII 故障最需要
     * 明确信息的场景。故在读取链路上还原原始 {@link BizException}（码不变，仍是 9999 fail-closed）。</p>
     */
    public static BizException unwrapPiiFailure(Throwable error) {
        for (Throwable t = error; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof BizException biz && isPiiFailure(biz)) {
                return biz;
            }
            if (t.getCause() == null) {
                break;
            }
        }
        return null;
    }

    /** 判定是否为「收货人手机号密文」类失败（靠消息前缀，避免与其它 9999 场景混淆）。 */
    private static boolean isPiiFailure(BizException biz) {
        return biz.getMsg() != null && biz.getMsg().startsWith("收货人手机号密文");
    }

    /** 是否处于加密写入模式（回滚开关状态，供迁移/巡检只读）。 */
    public boolean isEncryptionEnabled() {
        return enabled;
    }

    /** 当前加密用 keyId（写巡检/告警用，不含密钥材料）。 */
    public String activeKeyId() {
        return activeKeyId;
    }

    /**
     * keyring 配置解析：{@code k1=<base64>,k2=<base64>}，首个为当前加密密钥。
     *
     * <p><b>base64 自带 '=' 补位，故只按第一个 '=' 切分</b>（用 {@code split("=")} 会把密钥腰斩）。</p>
     */
    static Map<String, SecretKey> parseKeyring(String keyringSpec) {
        Map<String, SecretKey> parsed = new LinkedHashMap<>();
        if (keyringSpec == null || keyringSpec.isBlank()) {
            return parsed;
        }
        for (String entry : keyringSpec.split(",")) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException("app.crypto.receiver-mobile.keyring 格式非法: '" + item
                        + "'（应为 keyId=base64Key，多个用英文逗号分隔）");
            }
            String keyId = item.substring(0, eq).trim();
            String base64 = item.substring(eq + 1).trim();
            if (!KEY_ID.matcher(keyId).matches()) {
                throw new IllegalStateException("app.crypto.receiver-mobile.keyring 密钥标识非法: '" + keyId
                        + "'（只允许字母/数字/下划线/中划线，长度 1~32）");
            }
            if (base64.isEmpty()) {
                throw new IllegalStateException("app.crypto.receiver-mobile.keyring 密钥为空: keyId=" + keyId);
            }
            byte[] material;
            try {
                material = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("app.crypto.receiver-mobile.keyring 密钥不是合法 base64: keyId=" + keyId);
            }
            if (material.length != KEY_BYTES) {
                throw new IllegalStateException("app.crypto.receiver-mobile.keyring 密钥长度非法: keyId=" + keyId
                        + " 期望 " + KEY_BYTES + " 字节（AES-256），实际 " + material.length + " 字节");
            }
            if (parsed.put(keyId, new SecretKeySpec(material, "AES")) != null) {
                throw new IllegalStateException("app.crypto.receiver-mobile.keyring 密钥标识重复: keyId=" + keyId);
            }
        }
        return parsed;
    }
}