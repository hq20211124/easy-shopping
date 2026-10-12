package com.cart.client;

import com.cart.client.dto.ClientDtos.PriceView;
import com.easyshopping.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** core-price REST 实现（服务间直连 :8083，不走网关）。 */
@Component
public class RestPriceClient implements PriceClient {

    private final RestClient restClient;

    public RestPriceClient(@Value("${service.urls.core-price}") String baseUrl) {
        this.restClient = ClientSupport.client(baseUrl);
    }

    @Override
    public List<PriceView> prices(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        ApiResponse<List<PriceView>> response = restClient.get()
                .uri("/api/v1/price/skus?ids={ids}", ClientSupport.joinIds(skuIds))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<PriceView>>>() {
                });
        List<PriceView> data = ClientSupport.unwrap(response, "core-price");
        return data == null ? List.of() : data;
    }
}
