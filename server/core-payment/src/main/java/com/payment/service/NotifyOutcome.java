package com.payment.service;

/**
 * 回调处理结果。
 *
 * @param paid      是否本次首次入账（true=状态由 pending 翻转为 paid）
 * @param duplicate 是否重复通知（notify_id 去重命中或状态 CAS 未命中且已 paid）
 */
public record NotifyOutcome(boolean paid, boolean duplicate) {

    static NotifyOutcome firstPaid() {
        return new NotifyOutcome(true, false);
    }

    /** 重复通知（notify_id 去重命中，或状态 CAS 未命中且已 paid）。 */
    static NotifyOutcome repeated() {
        return new NotifyOutcome(false, true);
    }
}
