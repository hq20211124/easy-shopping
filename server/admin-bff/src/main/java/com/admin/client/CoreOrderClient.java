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
}
