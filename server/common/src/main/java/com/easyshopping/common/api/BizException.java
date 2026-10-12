package com.easyshopping.common.api;

/**
 * 业务异常：由 {@link GlobalExceptionHandler} 统一转为 {@link ApiResponse}。
 * 业务代码只允许抛本异常（或其子类）表达可预期错误，禁止直接返回错误结构。
 */
public class BizException extends RuntimeException {

    private final BizErrorCode errorCode;

    public BizException(BizErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    public BizException(BizErrorCode errorCode, String msg) {
        super(msg);
        this.errorCode = errorCode;
    }

    public BizException(BizErrorCode errorCode, String msg, Throwable cause) {
        super(msg, cause);
        this.errorCode = errorCode;
    }

    public BizErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }

    public String getMsg() {
        return getMessage();
    }
}
