package com.admin.client;

import com.easyshopping.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * core-order 客户端（订单管理聚合）。
 *
 * <p><b>调用路径</b>（plans/admin-backend.md 4.2.1；对端由并行代理实现）：
 * <ul>
 *   <li>GET  /api/admin/order/list —— 订单列表（分页转发，8 值状态机 0~7 见 admin-backend 4.3）</li>
 *   <li>GET  /api/admin/order/{id} —— 订单详情</li>
 *   <li>POST /api/admin/order/{id}/ship —— 发货（幂等 Key 透传）</li>
 *   <li>POST /api/admin/order/{id}/close —— 手动关单（body {reason}）</li>
 * </ul>
 * 未就绪时返回 9999「core-order 暂不可用」（依赖集成，见 README）。</p>
 */
@Component
public class CoreOrderClient {

    private static final String SERVICE = "core-order";
    /** 查看收货人明文手机号的权限码（core-order 的 X-Admin-Perms 头按此 fail-closed 校验）。 */
    private static final String PERM_VIEW_RECEIVER_MOBILE = "order:mobile:read";
    private static final ParameterizedTypeReference<ApiResponse<Object>> OBJECT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public CoreOrderClient(@Value("${service.urls.core-order}") String baseUrl) {
        this.restClient = RemoteSupport.restClient(baseUrl);
    }

    public Object orderList(Map<String, String> params) {
        try {
            return RemoteSupport.unwrap(restClient.get()
                    .uri(RemoteSupport.uriWithParams("/api/admin/order/list", params))
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public Object orderDetail(long id) {
        try {
            return RemoteSupport.unwrap(restClient.get().uri("/api/admin/order/{id}", id)
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public Object ship(long id, Map<String, Object> body) {
        try {
            return RemoteSupport.unwrap(restClient.post()
                    .uri("/api/admin/order/{id}/ship", id)
                    .body(body == null ? Map.of() : body)
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public Object close(long id, Map<String, Object> body) {
        try {
            return RemoteSupport.unwrap(restClient.post()
                    .uri("/api/admin/order/{id}/close", id)
                    .body(body == null ? Map.of() : body)
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    /**
     * 查看收货人<b>明文</b>手机号（GET /api/admin/order/{orderNo}/receiver-mobile）。
     *
     * <p>core-order 侧 fail-closed：必须带 {@code X-Admin-Perms} 头且包含
     * {@code order:mobile:read} 才返回明文（明文 PII 不能靠「没带头」默认放行）。
     * 浏览器请求不带这个头，因此由本 BFF 在鉴权通过后显式注入——本方法只应被
     * 带 {@code @RequirePerm("order:mobile:read")} 的端点调用。</p>
     */
    public Object receiverMobile(String orderNo) {
        try {
            return RemoteSupport.unwrap(restClient.get()
                    .uri("/api/admin/order/{orderNo}/receiver-mobile", orderNo)
                    .header("X-Admin-Perms", PERM_VIEW_RECEIVER_MOBILE)
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }
}
