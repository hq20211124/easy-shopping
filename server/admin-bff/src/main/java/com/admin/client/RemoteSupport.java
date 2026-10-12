package com.admin.client;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.UserContext;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.List;

/**
 * 服务间调用基础设施：RestClient + 超时（连接 1s / 读 3s，DEV.md 3 口径）+ 统一错误翻译。
 *
 * <p>P0 静态地址（service.urls.*）；P1 换 OpenFeign + Nacos。禁止直查其他服务 DB
 * （plans/admin-backend.md 1.1.2），聚合一律走 HTTP。</p>
 *
 * <p><b>头透传（集成契约）</b>：BFF 收到网关注入的 {@code X-User-Id}/{@code X-User-Roles} 与客户端的
 * {@code Idempotency-Key}，代理转发时必须原样带给 core 服务——core-product/core-price 校验
 * 管理员角色、core-order 写接口强制幂等键，缺头会被 403/1001 拒绝。</p>
 */
public final class RemoteSupport {

    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String HEADER_IDEMPOTENCY_KEY_FALLBACK = "X-Idempotency-Key";

    private RemoteSupport() {
    }

    public static RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(RemoteSupport::forwardHeaders)
                .build();
    }

    /** 请求执行时取当前请求上下文（拦截器在业务线程执行，ThreadLocal 可用），透传身份与幂等键。 */
    private static org.springframework.http.client.ClientHttpResponse forwardHeaders(
            HttpRequest request, byte[] body,
            org.springframework.http.client.ClientHttpRequestExecution execution) throws java.io.IOException {
        Long userId = UserContext.userId();
        if (userId != null) {
            request.getHeaders().set(UserContext.HEADER_USER_ID, String.valueOf(userId));
        }
        List<String> roles = UserContext.roles();
        if (!roles.isEmpty()) {
            request.getHeaders().set(UserContext.HEADER_USER_ROLES, String.join(",", roles));
        }
        // 幂等键取自「进来的那个请求」（出站请求是新构造的，头上什么都没有）
        String key = inboundHeader(HEADER_IDEMPOTENCY_KEY);
        if (key == null || key.isBlank()) {
            key = inboundHeader(HEADER_IDEMPOTENCY_KEY_FALLBACK);
        }
        if (key != null && !key.isBlank()) {
            request.getHeaders().set(HEADER_IDEMPOTENCY_KEY, key);
        }
        return execution.execute(request, body);
    }

    /** 读入站请求头（服务间调用无入站请求时返回 null）。 */
    private static String inboundHeader(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader(name);
        }
        return null;
    }

    /** 拼接转发查询串（过滤 null 值；分页/筛选参数原样透传给 core 服务）。 */
    public static String uriWithParams(String path, java.util.Map<String, String> params) {
        org.springframework.web.util.UriComponentsBuilder builder =
                org.springframework.web.util.UriComponentsBuilder.fromPath(path);
        params.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                builder.queryParam(k, v);
            }
        });
        return builder.toUriString();
    }

    /** 解开统一信封：code!=0 转 BizException（保码保消息，未知码兜底 9999）。 */
    public static <T> T unwrap(ApiResponse<T> resp, String service) {
        if (resp == null) {
            throw new BizException(BizErrorCode.SYSTEM_ERROR, service + " 无响应");
        }
        if (resp.code() != 0) {
            // 透传下游业务码（如 core-order 的 4002 订单状态错误），避免被统一掩码成 9999
            BizErrorCode mapped = BizErrorCode.fromCode(resp.code());
            if (mapped != null) {
                throw new BizException(mapped, resp.msg());
            }
            throw new BizException(BizErrorCode.SYSTEM_ERROR, service + ": " + resp.msg() + " (code=" + resp.code() + ")");
        }
        return resp.data();
    }

    /** 连接失败/超时 → 9999（服务暂不可用）；HTTP 4xx/5xx → 按状态翻译。 */
    public static RuntimeException translate(Exception e, String service) {
        if (e instanceof ResourceAccessException) {
            return new BizException(BizErrorCode.SYSTEM_ERROR, service + " 暂不可用（连接超时）", e);
        }
        if (e instanceof RestClientResponseException ex) {
            return switch (ex.getStatusCode().value()) {
                case 400 -> new BizException(BizErrorCode.PARAM_ERROR, service + " 参数错误", ex);
                case 401 -> new BizException(BizErrorCode.NOT_LOGIN, service + " 认证失败", ex);
                case 403 -> new BizException(BizErrorCode.NO_PERMISSION, service + " 无权限", ex);
                case 404 -> new BizException(BizErrorCode.NOT_FOUND, service + " 资源不存在", ex);
                default -> new BizException(BizErrorCode.SYSTEM_ERROR, service + " 调用失败: HTTP " + ex.getStatusCode().value(), ex);
            };
        }
        if (e instanceof BizException biz) {
            return biz;
        }
        return new BizException(BizErrorCode.SYSTEM_ERROR, service + " 调用异常", e);
    }
}
