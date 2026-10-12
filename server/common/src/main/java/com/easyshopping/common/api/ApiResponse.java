package com.easyshopping.common.api;

/**
 * 统一响应信封：{@code {code, msg, data, traceId}}。
 *
 * <p>说明：任务骨架约定字段名为 {@code msg}；plans 侧（admin-backend 接口通用规范、android-frontend 1.6.3）
 * 写的是 {@code message}（且带 timestamp/requestId）。该差异属契约口径问题，如需与前端联调对齐，
 * 只需在本类给 {@code msg} 加 {@code @JsonProperty("message")}（一处收敛），禁止各服务自行包装。</p>
 */
public record ApiResponse<T>(int code, String msg, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(BizErrorCode.SUCCESS.getCode(), BizErrorCode.SUCCESS.getMsg(), data, TraceIds.get());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(BizErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMsg());
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null, TraceIds.get());
    }
}
