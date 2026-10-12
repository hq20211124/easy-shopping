package com.easyshopping.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公共脱敏工具口径测试（审计登记项：MaskUtil 从 core-user 提到 common）。
 *
 * <p>断言同时钉住两件事：① 脱敏算法（前 3 后 4）与 plans 12.5 一致，
 * ② 空值语义为「原样返回」——core-order 的 {@code OrderQueryService.maskMobile}
 * 与 core-user 的旧副本在空值上不同（前者原样、后者返回 null），算法必须一致。</p>
 */
class MaskUtilTest {

    @Test
    void 手机号脱敏_保留前3后4() {
        assertThat(MaskUtil.maskMobile("13800001234")).isEqualTo("138****1234");
        assertThat(MaskUtil.maskMobile("1380000")).isEqualTo("138****0000"); // 7 位：前3+后4 恰好全覆盖
        assertThat(MaskUtil.maskMobile("13800000")).isEqualTo("138****0000"); // 8 位：中间至少 1 位打码
    }

    @Test
    void 长度不足7位_整体打码() {
        assertThat(MaskUtil.maskMobile("12345")).isEqualTo("****");
        assertThat(MaskUtil.maskMobile("123456")).isEqualTo("****");
    }

    @Test
    void 空值_原样返回() {
        assertThat(MaskUtil.maskMobile(null)).isNull();
        assertThat(MaskUtil.maskMobile("")).isEmpty();          // 订单表默认空串：不转 null，避免前端渲染 "null"
        assertThat(MaskUtil.maskMobile("   ")).isEqualTo("   ");
    }

    @Test
    void 脱敏结果不泄露完整号码() {
        String masked = MaskUtil.maskMobile("13800000000");
        assertThat(masked).doesNotContain("13800").doesNotContain("00000");
        assertThat(masked.length()).isEqualTo(11);
    }
}