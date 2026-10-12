package com.easyshopping.common.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：所有异常统一收敛为 {@link ApiResponse} + 规范 HTTP 状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按错误码映射 HTTP 状态（401/403/404/409），其余 200 + 业务码。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        log.warn("biz error code={} msg={}", ex.getCode(), ex.getMsg());
        return ResponseEntity.status(httpStatus(ex.getErrorCode()))
                .body(ApiResponse.error(ex.getCode(), ex.getMsg()));
    }

    /** 参数校验失败（@Valid + @RequestBody）→ 1001。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String msg = fe == null ? BizErrorCode.PARAM_ERROR.getMsg()
                : fe.getField() + " " + fe.getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.error(BizErrorCode.PARAM_ERROR.getCode(), msg));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleParam(Exception ex) {
        log.warn("param error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(BizErrorCode.PARAM_ERROR));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(BizErrorCode.NOT_FOUND.getCode(), "请求方法不支持"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(BizErrorCode.NOT_FOUND));
    }

    /** 兜底：未预期异常 → 9999 + HTTP 500，记录完整堆栈。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(BizErrorCode.SYSTEM_ERROR));
    }

    /** 错误码 → HTTP 状态映射（plans/android-backend.md 13.1）。 */
    static HttpStatusCode httpStatus(BizErrorCode code) {
        return switch (code) {
            case NOT_LOGIN, TOKEN_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case NO_PERMISSION -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENT_CONFLICT -> HttpStatus.CONFLICT;
            case SYSTEM_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.OK;
        };
    }
}
