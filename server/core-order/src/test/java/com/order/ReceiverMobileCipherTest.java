package com.order.crypto;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收货人手机号 AES-256-GCM 加解密单元测试（不依赖 Spring 容器，直接构造 {@link ReceiverMobileCipher}）。
 *
 * <p>覆盖：随机 IV / 往返一致 / 空值与超长 / 密文篡改与格式非法 / 缺密钥 fail-closed /
 * <b>跨密钥轮换（写新读旧双向）</b> / 回滚开关（停写不停读）/ keyring 配置校验。</p>
 */
class ReceiverMobileCipherTest {

    private static final String KEY_A = b64("0123456789abcdef0123456789abcdef");
    private static final String KEY_B = b64("fedcba9876543210fedcba9876543210");
    private static final String MOBILE = "13800000000";

    private static String b64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static ReceiverMobileCipher cipher(String keyring) {
        return new ReceiverMobileCipher(true, keyring, "");
    }

    // ------------------------------------------------------------------ 基本往返

    @Test
    void 同一手机号两次加密_密文不同_随机IV() {
        ReceiverMobileCipher cipher = cipher("k1=" + KEY_A);

        String first = cipher.encrypt(MOBILE);
        String second = cipher.encrypt(MOBILE);

        assertThat(first).startsWith("v1:k1:").isNotEqualTo(second);
        // 密文段与 IV 段必须都不同（IV 随机 = 相同明文不产生相同密文，阻断「密文相等 ⇒ 手机号相等」的推断）
        assertThat(first.split(":")[2]).isNotEqualTo(second.split(":")[2]);
        assertThat(cipher.decrypt(first)).isEqualTo(MOBILE);
        assertThat(cipher.decrypt(second)).isEqualTo(MOBILE);
    }

    @Test
    void 密文格式自描述_四段可解析() {
        ReceiverMobileCipher cipher = cipher("k1=" + KEY_A);

        String[] parts = cipher.encrypt(MOBILE).split(":", -1);

        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("v1");
        assertThat(parts[1]).isEqualTo("k1");
        assertThat(Base64.getDecoder().decode(parts[2])).hasSize(12); // 96 位 IV
        // 11 字节明文 + 16 字节 GCM 标签 = 27 字节
        assertThat(Base64.getDecoder().decode(parts[3])).hasSize(11 + 16);
    }

    @Test
    void 加密解密往返一致_含多组输入() {
        ReceiverMobileCipher cipher = cipher("k1=" + KEY_A);

        for (String plain : Set.of("13800000000", "19912345678", "+8613800138000", " 138 0000 0000 ",
                "13800000000-转派", "短")) {
            assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain);
        }
    }

    @Test
    void 空值与超长_空值原样_超长可往返() {
        ReceiverMobileCipher cipher = cipher("k1=" + KEY_A);

        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();          // 订单表默认空串：不造密文
        assertThat(cipher.encrypt("   ")).isEqualTo("   "); // 空白视为「未填」
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("")).isEmpty();

        String longMobile = "1".repeat(200); // 远超真实长度，验证不因超长截断/抛错
        assertThat(cipher.decrypt(cipher.encrypt(longMobile))).isEqualTo(longMobile);
    }

    // ------------------------------------------------------------------ 存量明文兼容

    @Test
    void 存量明文行_按明文直出_不报错() {
        ReceiverMobileCipher cipher = cipher("k1=" + KEY_A);

        assertThat(cipher.decrypt(MOBILE)).isEqualTo(MOBILE);
        assertThat(cipher.decrypt("18612345678")).isEqualTo("18612345678");
        assertThat(ReceiverMobileCipher.isCiphertext(MOBILE)).isFalse();
    }

    // ------------------------------------------------------------------ 密钥轮换

    @Test
    void 轮换后_新密钥写旧密钥密文仍可解() {
        ReceiverMobileCipher old = cipher("k1=" + KEY_A);
        String writtenByOldKey = old.encrypt(MOBILE);

        // 新配置：新密钥放首位，旧密钥保留
        ReceiverMobileCipher rotated = cipher("k2=" + KEY_B + ",k1=" + KEY_A);

        assertThat(rotated.activeKeyId()).isEqualTo("k2");
        assertThat(rotated.decrypt(writtenByOldKey)).isEqualTo(MOBILE);
        String writtenByNewKey = rotated.encrypt(MOBILE);
        assertThat(writtenByNewKey).startsWith("v1:k2:");
        assertThat(rotated.decrypt(writtenByNewKey)).isEqualTo(MOBILE);
    }

    @Test
    void 轮换窗口内_全部实例都能双向解密_旧实例也能读新密钥写的行() {
        // 滚动发布安全的轮换：阶段一「新密钥进 keyring 但暂不启用」，
        // 所有实例（含仍持有旧配置的老实例）此时都认得 k2，但写的还是 k1
        ReceiverMobileCipher phase1 = new ReceiverMobileCipher(true, "k1=" + KEY_A + ",k2=" + KEY_B, "k1");
        ReceiverMobileCipher phase2 = new ReceiverMobileCipher(true, "k1=" + KEY_A + ",k2=" + KEY_B, "k2");
        ReceiverMobileCipher oldInstance = new ReceiverMobileCipher(true, "k1=" + KEY_A, "");

        String writtenInPhase1 = phase1.encrypt(MOBILE);
        String writtenInPhase2 = phase2.encrypt(MOBILE);

        // 阶段一新写的仍带 k1 → 老实例可读
        assertThat(writtenInPhase1).startsWith("v1:k1:");
        assertThat(oldInstance.decrypt(writtenInPhase1)).isEqualTo(MOBILE);
        // 阶段二新写的带 k2 → 只有已经拿到新配置的实例才可读（这正是必须两阶段的原因）
        assertThat(writtenInPhase2).startsWith("v1:k2:");
        assertThat(phase1.decrypt(writtenInPhase2)).isEqualTo(MOBILE);
        assertThat(phase2.decrypt(writtenInPhase1)).isEqualTo(MOBILE);
    }

    @Test
    void activeKeyId显式指定_必须存在于keyring() {
        assertThatThrownBy(() -> new ReceiverMobileCipher(true, "k1=" + KEY_A + ",k2=" + KEY_B, "k3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-key-id");
    }

    @Test
    void 未指定activeKeyId_默认取keyring首位() {
        assertThat(new ReceiverMobileCipher(true, "k2=" + KEY_B + ",k1=" + KEY_A, "").activeKeyId())
                .isEqualTo("k2");
        assertThat(new ReceiverMobileCipher(true, "k2=" + KEY_B + ",k1=" + KEY_A, null).activeKeyId())
                .isEqualTo("k2");
    }

    @Test
    void 摘除旧密钥后_历史密文failClosed_提示缺少keyId() {
        ReceiverMobileCipher old = cipher("k1=" + KEY_A);
        String writtenByOldKey = old.encrypt(MOBILE);

        // 运维摘掉旧密钥前忘了刷数 → 必须报错，绝不能返回密文/空串
        ReceiverMobileCipher withoutOld = cipher("k2=" + KEY_B);

        assertThatThrownBy(() -> withoutOld.decrypt(writtenByOldKey))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(BizErrorCode.SYSTEM_ERROR))
                .hasMessageContaining("k1");
    }

    // ------------------------------------------------------------------ 失败语义（不静默返回垃圾）

    @Test
    void 密文被篡改_GCM认证失败_抛业务异常而非返回垃圾() {
        ReceiverMobileCipher cipher = cipher("k1=" + KEY_A);
        String[] parts = cipher.encrypt(MOBILE).split(":", -1);

        String ct = parts[3];
        char first = ct.charAt(0);
        char flipped = first == 'A' ? 'B' : 'A';
        String tampered = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + flipped + ct.substring(1);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(BizErrorCode.SYSTEM_ERROR))
                .hasMessageContaining("校验失败");
    }

    @Test
    void 密钥不匹配_认证失败抛异常() {
        String writtenByA = cipher("k1=" + KEY_A).encrypt(MOBILE);
        // 同样的 keyId 但换了密钥材料（轮换时「同名换钥」是绝对禁止的）
        ReceiverMobileCipher other = cipher("k1=" + KEY_B);

        assertThatThrownBy(() -> other.decrypt(writtenByA))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(BizErrorCode.SYSTEM_ERROR));
    }

    @Test
    void 密文段数或base64非法_抛明确异常() {
        ReceiverMobileCipher cipher = cipher("k1=" + KEY_A);

        assertThatThrownBy(() -> cipher.decrypt("v1:k1:aaa"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("格式非法");
        assertThatThrownBy(() -> cipher.decrypt("v1:k1:###:###"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(BizErrorCode.SYSTEM_ERROR));
    }

    // ------------------------------------------------------------------ 开关与配置

    @Test
    void 关闭开关_写入退回明文_读取仍兼容解密历史密文() {
        ReceiverMobileCipher enabledCipher = cipher("k1=" + KEY_A);
        String historicalCipherText = enabledCipher.encrypt(MOBILE);

        ReceiverMobileCipher disabled = new ReceiverMobileCipher(false, "k1=" + KEY_A, "");

        assertThat(disabled.isEncryptionEnabled()).isFalse();
        assertThat(disabled.encrypt(MOBILE)).isEqualTo(MOBILE);          // 停写：退回明文
        assertThat(disabled.decrypt(MOBILE)).isEqualTo(MOBILE);          // 明文照读
        assertThat(disabled.decrypt(historicalCipherText)).isEqualTo(MOBILE); // 停写不停读：密文仍能读
    }

    @Test
    void 关闭开关且带密钥_仍能解密历史密文行() {
        // 这是「应急关闭加密」的真实场景：开关关了但 keyring 还在，历史密文行必须照常读出来
        ReceiverMobileCipher disabled = new ReceiverMobileCipher(false, "k1=" + KEY_A, "");

        assertThat(disabled.decrypt(cipher("k1=" + KEY_A).encrypt(MOBILE))).isEqualTo(MOBILE);
    }

    @Test
    void 启用却无密钥_启动失败_不静默降级明文() {
        assertThatThrownBy(() -> new ReceiverMobileCipher(true, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keyring");
        assertThatThrownBy(() -> new ReceiverMobileCipher(true, null, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 密钥配置非法_启动失败并说明原因() {
        assertThatThrownBy(() -> new ReceiverMobileCipher(true, "no-equals-sign", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("格式非法");
        assertThatThrownBy(() -> new ReceiverMobileCipher(true, "k1=not-base64!!", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> new ReceiverMobileCipher(true, "k1=" + b64("too-short"), ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("长度非法");
        assertThatThrownBy(() -> new ReceiverMobileCipher(true, "k1=" + KEY_A + ",k1=" + KEY_B, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("重复");
        assertThatThrownBy(() -> new ReceiverMobileCipher(true, "k:1=" + KEY_A, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("密钥标识非法");
    }

    @Test
    void 关闭开关且无密钥_允许启动_但不解密() {
        ReceiverMobileCipher disabled = new ReceiverMobileCipher(false, "", "");

        assertThat(disabled.encrypt(MOBILE)).isEqualTo(MOBILE);
        assertThat(disabled.activeKeyId()).isNull();
    }

    @Test
    void 异常链还原_JPA包装后的解密失败仍能取出业务异常() {
        BizException biz = new BizException(BizErrorCode.SYSTEM_ERROR, "收货人手机号密文校验失败（测试）");
        RuntimeException jpaWrapped = new IllegalStateException("Error attempting to apply AttributeConverter",
                new RuntimeException("nested", biz));

        assertThat(ReceiverMobileCipher.unwrapPiiFailure(jpaWrapped)).isSameAs(biz);
        // 非 PII 类异常不误判
        assertThat(ReceiverMobileCipher.unwrapPiiFailure(
                new RuntimeException("Error attempting to apply AttributeConverter",
                        new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在")))).isNull();
        assertThat(ReceiverMobileCipher.unwrapPiiFailure(new RuntimeException("普通异常"))).isNull();
    }
}