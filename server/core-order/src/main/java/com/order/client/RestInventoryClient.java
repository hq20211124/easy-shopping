package com.order.client;

import com.easyshopping.common.api.ApiResponse;
import com.order.client.dto.ClientDtos.InventoryItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** core-inventory REST 实现（服务间直连 :8084，不走网关）。 */
@Component
public class RestInventoryClient implements InventoryClient {

    private final RestClient restClient;

    public RestInventoryClient(@Value("${service.urls.core-inventory}") String baseUrl) {
        this.restClient = ClientSupport.client(baseUrl);
    }

    @Override
    public void reserve(Long orderId, List<InventoryItem> items, long ttlSeconds) {
        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri("/api/v1/internal/inventory/reserve")
                .body(Map.of("orderId", orderId, "items", items, "ttlSeconds", ttlSeconds))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                });
        ClientSupport.unwrap(response, "core-inventory");
    }

    @Override
    public void release(Long orderId) {
        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri("/api/v1/internal/inventory/release")
                .body(Map.of("orderId", orderId))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                });
        ClientSupport.unwrap(response, "core-inventory");
    }

    @Override
    public void deduct(Long orderId) {
        ApiResponse<Map<String, Object>> response = restClient.post()
                .uri("/api/v1/internal/inventory/deduct")
                .body(Map.of("orderId", orderId))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                });
        ClientSupport.unwrap(response, "core-inventory");
    }
}
