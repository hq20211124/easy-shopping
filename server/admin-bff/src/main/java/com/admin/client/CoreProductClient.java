package com.admin.client;

import com.easyshopping.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * core-product 客户端（商品审核聚合，P0 由 core-product 承载 admin 商品/审核接口，
 * TODO(P1) 拆 admin-product / admin-audit 细分服务 —— plans/admin-backend.md 1.1.1）。
 *
 * <p><b>调用路径</b>（以 core-product 实际实现为准，集成核对后修正；审核任务 = SPU，
 * taskId 即 spuId，core-product 无独立 /api/admin/audit/task/* 实现）：
 * <ul>
 *   <li>GET  /api/admin/product/list —— 商品列表（分页转发；审核任务列表复用，auditStatus 透传）</li>
 *   <li>GET  /api/admin/product/{id} —— 商品详情</li>
 *   <li>POST /api/admin/product/{id}/approve —— 审核通过（auditing → on_shelf 自动上架）</li>
 *   <li>POST /api/admin/product/{id}/reject —— 审核驳回（body {reason}）</li>
 * </ul>
 * 远端未就绪时返回 9999「core-product 暂不可用」（依赖集成，见 README）。</p>
 */
@Component
public class CoreProductClient {

    private static final String SERVICE = "core-product";
    private static final ParameterizedTypeReference<ApiResponse<Object>> OBJECT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public CoreProductClient(@Value("${service.urls.core-product}") String baseUrl) {
        this.restClient = RemoteSupport.restClient(baseUrl);
    }

    public Object productList(Map<String, String> params) {
        try {
            return RemoteSupport.unwrap(restClient.get()
                    .uri(RemoteSupport.uriWithParams("/api/admin/product/list", params))
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public Object productDetail(long id) {
        try {
            return RemoteSupport.unwrap(restClient.get().uri("/api/admin/product/{id}", id)
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    /** 审核任务列表 = 商品列表（core-product 无独立 audit/task 端点，按 auditStatus 筛选透传）。 */
    public Object auditTaskList(Map<String, String> params) {
        try {
            return RemoteSupport.unwrap(restClient.get()
                    .uri(RemoteSupport.uriWithParams("/api/admin/product/list", params))
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    /** 审核通过：core-product 实际路径为 POST /api/admin/product/{id}/approve（taskId = spuId）。 */
    public Object auditApprove(long taskId, Map<String, Object> body) {
        try {
            return RemoteSupport.unwrap(restClient.post()
                    .uri("/api/admin/product/{id}/approve", taskId)
                    .body(body == null ? Map.of() : body)
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    /** 审核驳回：core-product 实际路径为 POST /api/admin/product/{id}/reject（body {reason}）。 */
    public Object auditReject(long taskId, Map<String, Object> body) {
        try {
            return RemoteSupport.unwrap(restClient.post()
                    .uri("/api/admin/product/{id}/reject", taskId)
                    .body(body == null ? Map.of() : body)
                    .retrieve().body(OBJECT_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }
}
