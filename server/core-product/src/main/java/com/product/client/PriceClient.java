package com.product.client;

import com.easyshopping.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * core-price(:8083) 客户端（跨服务只走 HTTP，禁止直连对方 DB —— DEV.md 4.4）。
 *
 * <p>契约（本模块 README 有完整清单）：</p>
 * <ul>
 *   <li>批量写价：{@code POST /api/v1/internal/price/skus} body=[{skuId,price,marketPrice}]（商品创建/编辑时同步 SKU 价格）</li>
 *   <li>批量查价：{@code GET /api/v1/price/skus?ids=}（商品详情回填 SKU 现价）</li>
 * </ul>
 *
 * <p>超时按 DEV.md 3 口径：连接 1s / 读 3s。P0 全部<b>容错降级</b>：
 * 价格服务不可用时只记 warn 不阻断主流程（价格可能短暂缺省，P1 由 price.changed 事件最终一致）。</p>
 */
@Component
public class PriceClient {

    private static final Logger log = LoggerFactory.getLogger(PriceClient.class);

    private static final ParameterizedTypeReference<ApiResponse<List<Map<String, Object>>>> PRICE_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final boolean enabled;

    public PriceClient(@Value("${service.urls.core-price}") String baseUrl,
                       @Value("${app.product.price-client.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** 商品创建/编辑后把 SKU 价格同步到 core-price（容错：失败只告警，P1 走事件补偿）。 */
    public void syncSkuPrices(List<SkuPriceView> views) {
        if (!enabled || views == null || views.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                    .uri("/api/v1/internal/price/skus")
                    // 契约按服务方：core-price 的 InternalPriceBatchInput 是 {items:[...]} 包装对象。
                    // 早期发裸数组 [ ] 会被 Jackson 拒掉（400/1001）→ 同步静默降级 → 下单取不到价
                    .body(Map.of("items", views))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("[price-client] 同步 SKU 价格失败（降级继续）: size={} err={}", views.size(), e.getMessage());
        }
    }

    /** 批量查 SKU 现价：skuId -> price；不可用或缺失的 SKU 不出现在结果中（容错降级）。 */
    public Map<Long, BigDecimal> fetchSkuPrices(List<Long> skuIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (!enabled || skuIds == null || skuIds.isEmpty()) {
            return result;
        }
        try {
            ApiResponse<List<Map<String, Object>>> resp = restClient.get()
                    .uri(b -> b.path("/api/v1/price/skus").queryParam("ids", String.join(",", toStrs(skuIds))).build())
                    .retrieve()
                    .body(PRICE_LIST_TYPE);
            if (resp == null || resp.data() == null) {
                return result;
            }
            for (Map<String, Object> row : resp.data()) {
                Object skuId = row.get("skuId");
                Object price = row.get("price");
                if (skuId != null && price != null) {
                    result.put(((Number) skuId).longValue(), new BigDecimal(price.toString()));
                }
            }
        } catch (Exception e) {
            log.warn("[price-client] 批量查价失败（降级返回空）: size={} err={}", skuIds.size(), e.getMessage());
        }
        return result;
    }

    private static List<String> toStrs(List<Long> ids) {
        List<String> l = new ArrayList<>(ids.size());
        for (Long id : ids) {
            l.add(String.valueOf(id));
        }
        return l;
    }

    /** 批量写价入参（core-price 内部契约）。 */
    public record SkuPriceView(Long skuId, BigDecimal price, BigDecimal marketPrice) {
    }
}
