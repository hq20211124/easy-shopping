package com.search.service;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.search.domain.SearchProduct;
import com.search.repo.SearchProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 搜索数据同步：从 core-product HTTP 拉取冗余（<b>禁止直连对方 DB</b> —— DEV.md 4.4）。
 *
 * <p>P0 策略：启动后首次 + 每 {@code app.search.sync-interval-ms}（默认 60s）全量拉取
 * {@code GET /api/v1/internal/products}（分页，每页 200，单轮上限 50 页），
 * 只索引 shelfStatus=1 的商品；本轮未出现的 spuId 从冗余表删除（覆盖下架/删除）。
 * 超时 1s/3s（DEV.md 3），失败仅告警不中断（下一轮重试）。P1 改 MQ 增量（product.* 事件）+ ES。</p>
 */
@Service
public class SearchSyncService {

    private static final Logger log = LoggerFactory.getLogger(SearchSyncService.class);

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES_PER_ROUND = 50;

    private static final ParameterizedTypeReference<ApiResponse<PageResult<Map<String, Object>>>> PAGE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final SearchProductRepository repo;
    private final SnowflakeIdGenerator idGenerator;
    private final RestClient restClient;
    private final boolean enabled;

    public SearchSyncService(SearchProductRepository repo, SnowflakeIdGenerator idGenerator,
                             @Value("${service.urls.core-product}") String productBaseUrl,
                             @Value("${app.search.sync-enabled:true}") boolean enabled) {
        this.repo = repo;
        this.idGenerator = idGenerator;
        this.enabled = enabled;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().baseUrl(productBaseUrl).requestFactory(factory).build();
    }

    /** 启动后首次（2s）+ 周期（60s）全量同步；core-product 不可用时仅告警（搜索降级为空库）。 */
    @Scheduled(fixedDelayString = "${app.search.sync-interval-ms:60000}",
            initialDelayString = "${app.search.sync-initial-delay-ms:2000}")
    public void sync() {
        if (!enabled) {
            return;
        }
        try {
            doSync();
        } catch (Exception e) {
            log.warn("[search-sync] 同步失败（下一轮重试）: {}", e.getMessage());
        }
    }

    // 非事务方法：各仓储操作自带事务（删除见 repository 的 @Transactional），HTTP 拉取不占用事务
    protected void doSync() {
        Set<Long> seen = new HashSet<>();
        int upserted = 0;
        for (int page = 1; page <= MAX_PAGES_PER_ROUND; page++) {
            final int pageNo = page; // lambda 需要 effectively final
            ApiResponse<PageResult<Map<String, Object>>> resp = restClient.get()
                    .uri(b -> b.path("/api/v1/internal/products")
                            .queryParam("page", pageNo).queryParam("pageSize", PAGE_SIZE).build())
                    .retrieve()
                    .body(PAGE_TYPE);
            if (resp == null || resp.data() == null || resp.data().list().isEmpty()) {
                break;
            }
            for (Map<String, Object> row : resp.data().list()) {
                long spuId = ((Number) row.get("spuId")).longValue();
                seen.add(spuId);
                Integer shelf = (Integer) row.get("shelfStatus");
                if (shelf != null && shelf == 1) {
                    upsert(row, spuId);
                    upserted++;
                }
            }
            if (page * PAGE_SIZE >= resp.data().total()) {
                break;
            }
        }
        // 本轮未出现的（已下架/已删除）从冗余表清理
        List<Long> stale = repo.findAll().stream()
                .map(SearchProduct::getSpuId)
                .filter(id -> !seen.contains(id))
                .toList();
        if (!stale.isEmpty()) {
            repo.deleteBySpuIdIn(stale);
        }
        log.info("[search-sync] 同步完成: indexed={} removed={}", upserted, stale.size());
    }

    private void upsert(Map<String, Object> row, long spuId) {
        SearchProduct entity = repo.findBySpuId(spuId).orElseGet(() -> {
            SearchProduct p = new SearchProduct();
            p.setId(idGenerator.nextId());
            p.setSpuId(spuId);
            return p;
        });
        entity.setName(str(row.get("name")));
        entity.setMainImage(str(row.get("mainImage")));
        entity.setCategoryId(num(row.get("categoryId")));
        entity.setMinPrice(dec(row.get("minPrice")));
        entity.setMarketPrice(dec(row.get("marketPrice")));
        entity.setSalesCount(num(row.get("salesCount")) == null ? 0L : num(row.get("salesCount")));
        entity.setShelfStatus(1);
        repo.save(entity);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static BigDecimal dec(Object o) {
        return o == null ? null : new BigDecimal(o.toString());
    }
}
