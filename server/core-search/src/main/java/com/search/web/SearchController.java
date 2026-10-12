package com.search.web;

import com.easyshopping.common.api.ApiResponse;
import com.search.service.HotWordService;
import com.search.service.HotWordService.HotWord;
import com.search.service.SearchService;
import com.search.service.SearchService.SearchResponse;
import com.search.service.SearchService.SuggestionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品搜索接口（P0 DB LIKE；P1 ES —— architecture/06-search-recommend.md）。
 *
 * <p>端点（任务 P0 契约为主，附 plans 两端别名以兼容前端直调）：</p>
 * <ul>
 *   <li>GET /api/v1/search/products（别名：/api/v1/search [ios 3.2.3]、/api/v1/search/keyword [android 3.3.2]）</li>
 *   <li>GET /api/v1/search/suggestions（别名：/api/v1/search/suggest [android 3.3.2]）</li>
 *   <li>GET /api/v1/search/hot</li>
 * </ul>
 */
@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping({"/api/v1/search/products", "/api/v1/search", "/api/v1/search/keyword"})
    public ApiResponse<SearchResponse> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) BigDecimal priceMin,
            @RequestParam(required = false) BigDecimal priceMax,
            @RequestParam(required = false) String sort, // default/price_asc/price_desc/sales/newest
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ApiResponse.ok(searchService.search(q, categoryId, priceMin, priceMax, sort, cursor, pageSize));
    }

    @GetMapping({"/api/v1/search/suggestions", "/api/v1/search/suggest"})
    public ApiResponse<SuggestionResponse> suggestions(
            @RequestParam(name = "q") String q,
            @RequestParam(defaultValue = "10") Integer limit) {
        return ApiResponse.ok(searchService.suggestions(q, limit));
    }

    @GetMapping("/api/v1/search/hot")
    public ApiResponse<List<HotWord>> hot(@RequestParam(defaultValue = "10") Integer limit) {
        return ApiResponse.ok(searchService.hotWords(limit));
    }
}
