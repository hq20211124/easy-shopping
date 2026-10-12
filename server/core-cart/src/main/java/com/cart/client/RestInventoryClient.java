package com.cart.client;

import com.cart.client.dto.ClientDtos.StockView;
import com.easyshopping.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** core-inventory REST 实现（服务间直连 :8084，不走网关）。 */
@Component
public class RestInventoryClient implements InventoryClient {

    private final RestClient restClient;

    public RestInventoryClient(@Value("${service.urls.core-inventory}") String baseUrl) {
        this.restClient = ClientSupport.client(baseUrl);
    }

    @Override
    public List<StockView> stocks(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        ApiResponse<List<StockView>> response = restClient.get()
                .uri("/api/v1/inventory/stocks?skuIds={ids}", ClientSupport.joinIds(skuIds))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<StockView>>>() {
                });
        List<StockView> data = ClientSupport.unwrap(response, "core-inventory");
        return data == null ? List.of() : data;
    }
}
