package com.order.client;

import com.easyshopping.common.api.ApiResponse;
import com.order.client.dto.ClientDtos.SkuView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** core-product REST 实现。 */
@Component
public class RestProductClient implements ProductClient {

    private final RestClient restClient;

    public RestProductClient(@Value("${service.urls.core-product}") String baseUrl) {
        this.restClient = ClientSupport.client(baseUrl);
    }

    @Override
    public List<SkuView> skus(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        ApiResponse<List<SkuView>> response = restClient.get()
                .uri("/api/v1/internal/skus?ids={ids}", ClientSupport.joinIds(skuIds))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<SkuView>>>() {
                });
        List<SkuView> data = ClientSupport.unwrap(response, "core-product");
        return data == null ? List.of() : data;
    }
}
