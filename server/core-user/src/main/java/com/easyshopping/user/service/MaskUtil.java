package com.easyshopping.user.service;

/**
 * 敏感字段脱敏（列表/详情出参统一走这里；明文手机号/身份证不出服务）。
 *
 * <p>对齐契约：plans/ios-backend.md 5.2.1（mobile 138****8000）、
 * plans/android-backend.md 5.1.3（email z***@example.com）。</p>
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /** 手机号脱敏：保留前 3 后 4（11 位）；非法长度整体打码。 */
    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return null;
        }
        if (mobile.length() < 7) {
            return "****";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    /** 邮箱脱敏：保留首字符与域名（z***@example.com）。 */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
