package com.easyshopping.common.api;

/**
 * 敏感字段脱敏（列表/详情出参统一走这里；明文手机号不出服务）。
 *
 * <p><b>口径来源</b>：plans 12.5（手机号 138****8000，保留前 3 后 4）与 12.6 隐私原则 2
 * 「接口返回遵循最小化原则：手机号、地址在列表类接口一律脱敏，明文仅在必要场景按需返回」；
 * 与 plans/ios-backend.md 5.2.1、android-backend.md 5.1.3 的示例一致。</p>
 *
 * <p><b>为什么放 common</b>：原算法在 core-user（{@code com.easyshopping.user.service.MaskUtil}），
 * core-order 另抄了一份同算法副本（审计登记项：MaskUtil 应从 core-user 提到 common）。
 * 本类是公共口径的唯一实现，core-order 已改为复用；core-user 暂保留原副本（不在本次改动范围），
 * 后续统一时<b>只允许替换引用，禁止改动算法</b>。</p>
 *
 * <p>零三方依赖、纯静态方法；隐私算法必须可被任意模块无差别复用，否则必然出现 N 份副本漂移。</p>
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /**
     * 手机号脱敏：保留前 3 后 4（{@code 138****8000}）；长度不足 7 位整体打码。
     *
     * <p><b>空值语义</b>：null / 空串 / 纯空白<b>原样返回</b>（不转 null）。
     * 订单表 {@code receiver_mobile} 默认为空串，原样返回可避免前端渲染出字符串 "null"；
     * 需要「空值即 null」的调用方请自行判空（core-user 的旧副本对空值返回 null，
     * 两者只在空值语义上有别，<b>脱敏算法完全一致</b>）。</p>
     */
    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return mobile;
        }
        if (mobile.length() < 7) {
            return "****";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }
}