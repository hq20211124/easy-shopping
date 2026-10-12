package com.easyshopping.common.api;

/**
 * 业务错误码。
 *
 * <p>码空间基准：{@code plans/android-backend.md} 16.2（买家端专属码表，前端已依赖的
 * {@code 0/1001/1002/1003/1004/3002/4002/9999} 语义不可变更）。
 * 标注「补齐」的码为骨架新增，管理端（admin-bff）P0 暂复用同一码表，P1 细分 admin-* 服务时再拆分下发通道。</p>
 *
 * <p>HTTP 状态映射（对齐 plans/android-backend.md 13.1、plans/admin-backend.md 接口通用规范）：
 * 401=未认证/Token 失效，403=无权限，409=幂等冲突，429=限流，500=系统错误，其余业务错误 HTTP 200 + 业务码。</p>
 */
public enum BizErrorCode {

    SUCCESS(0, "成功"),

    // ---- 1xxx 通用 ----
    PARAM_ERROR(1001, "参数错误"),
    NOT_LOGIN(1002, "未登录"),
    TOKEN_EXPIRED(1003, "Token过期"),
    NO_PERMISSION(1004, "无权限"),
    IDEMPOTENT_CONFLICT(1005, "幂等冲突：请勿重复提交"), // 补齐：写接口幂等键冲突（plans/admin-backend.md 10.1.1）
    NOT_FOUND(404, "资源不存在"),                        // 网关路由未命中/接口不存在

    // ---- 2xxx 用户 ----
    USER_NOT_FOUND(2001, "用户不存在"),
    PASSWORD_ERROR(2002, "密码错误"),
    CAPTCHA_ERROR(2003, "验证码错误"),
    CAPTCHA_EXPIRED(2004, "验证码已过期"),
    SMS_TOO_FREQUENT(2005, "短信发送过于频繁"),

    // ---- 3xxx 商品/购物车 ----
    PRODUCT_NOT_FOUND(3001, "商品不存在"),
    OUT_OF_STOCK(3002, "库存不足"),
    PRODUCT_OFF_SHELF(3003, "商品已下架/失效"),
    CART_ITEM_NOT_FOUND(3004, "购物车项不存在"), // 补齐：购物车以 cartItemId 定位行（plans/android-backend.md P0 缺口清单）

    // ---- 4xxx 订单 ----
    ORDER_NOT_FOUND(4001, "订单不存在"),
    ORDER_STATUS_ERROR(4002, "订单状态错误"),

    // ---- 5xxx 支付 ----
    PAYMENT_FAILED(5001, "支付失败"),
    BALANCE_NOT_ENOUGH(5002, "余额不足"),

    // ---- 9xxx 系统 ----
    SYSTEM_ERROR(9999, "系统错误");

    private final int code;
    private final String msg;

    BizErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
