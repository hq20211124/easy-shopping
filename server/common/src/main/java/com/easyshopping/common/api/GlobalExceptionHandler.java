package com.easyshopping.common.api;

import com.easyshopping.common.idempotent.IdempotentInterceptor;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * 业务异常：按错误码映射 HTTP 状态（401/403/404/409），其余 200 + 业务码。
     *
     * <p>同时给请求打上「业务失败」标记：{@code @Idempotent} 拦截器在 afterCompletion 里据此
     * <b>释放幂等占位</b>——否则 DispatcherServlet 在 advice 消化异常后会把 {@code ex} 置空，
     * 拦截器会把业务失败误判为成功，同键重试被 1005 挡住（见 IdempotentInterceptor 类注释）。</p>
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest request) {
        log.warn("biz error code={} msg={}", ex.getCode(), ex.getMsg());
        markBusinessError(request);
        return ResponseEntity.status(httpStatus(ex.getErrorCode()))
                .body(ApiResponse.error(ex.getCode(), ex.getMsg()));
    }

    /** 参数校验失败（@Valid + @RequestBody）→ 1001。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                               HttpServletRequest request) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String msg = fe == null ? BizErrorCode.PARAM_ERROR.getMsg()
                : fe.getField() + " " + fe.getDefaultMessage();
        markBusinessError(request);
        return ResponseEntity.badRequest().body(ApiResponse.error(BizErrorCode.PARAM_ERROR.getCode(), msg));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleParam(Exception ex, HttpServletRequest request) {
        log.warn("param error: {}", ex.getMessage());
        markBusinessError(request); // 参数错误属业务失败：用户改正后可用同一幂等键重试
        return ResponseEntity.badRequest().body(ApiResponse.error(BizErrorCode.PARAM_ERROR));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException ex,
                                                           HttpServletRequest request) {
        markSystemError(request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(BizErrorCode.NOT_FOUND.getCode(), "请求方法不支持"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex,
                                                             HttpServletRequest request) {
        markSystemError(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(BizErrorCode.NOT_FOUND));
    }

    /** 兜底：未预期异常 → 9999 + HTTP 500，记录完整堆栈。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception", ex);
        markSystemError(request); // 系统异常：幂等占位保留到 TTL 过期，防重复提交副作用
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(BizErrorCode.SYSTEM_ERROR));
    }

    /** 标记「业务失败」（@Idempotent 拦截器据此释放占位，见 IdempotentInterceptor 类注释）。 */
    private static void markBusinessError(HttpServletRequest request) {
        if (request != null) {
            request.setAttribute(IdempotentInterceptor.ATTR_BUSINESS_ERROR, Boolean.TRUE);
        }
    }

    /** 标记「系统失败」（@Idempotent 拦截器据此保留 PROCESSING 占位）。 */
    private static void markSystemError(HttpServletRequest request) {
        if (request != null) {
            request.setAttribute(IdempotentInterceptor.ATTR_SYSTEM_ERROR, Boolean.TRUE);
        }
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
