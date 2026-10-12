package com.search;

import com.search.domain.SearchProduct;
import com.search.repo.SearchProductRepository;
import com.search.service.SearchService;
import com.search.service.SearchService.SearchItem;
import com.search.service.SearchService.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cursor 分页测试（plans/ios-backend.md 3.2.3 cursor/next_cursor 口径）：
 * 深翻页不重不漏、nextCursor 末页为 null、价格排序/区间/类目筛选正确。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-search-cursor-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.search.sync-enabled=false"
})
class SearchCursorPaginationTest {

    @Autowired
    private SearchService searchService;

    @Autowired
    private SearchProductRepository repo;

    @BeforeEach
    void seed() {
        repo.deleteAll();
        // 25 条：价格 10.00 ~ 34.00 递增，销量 = 100 - id
        for (long id = 1; id <= 25; id++) {
            SearchProduct p = new SearchProduct();
            p.setId(id);
            p.setSpuId(id);
            p.setName("商品" + id);
            p.setMinPrice(BigDecimal.valueOf(9 + id)); // 10.00 ~ 34.00
            p.setMarketPrice(BigDecimal.valueOf(9 + id));
            p.setSalesCount(100 - id);
            p.setShelfStatus(1);
            p.setCategoryId(id <= 15 ? 10L : 20L);
            repo.save(p);
        }
        // 1 条下架商品：任何搜索都不允许出现
        SearchProduct off = new SearchProduct();
        off.setId(99L);
        off.setSpuId(99L);
        off.setName("已下架商品");
        off.setMinPrice(new BigDecimal("5.00"));
        off.setSalesCount(999L);
        off.setShelfStatus(0);
        repo.save(off);
    }

    @Test
    void priceAscCursorPagination_coversAllWithoutDuplicateOrMiss() {
        List<Long> visited = collectAllByCursor("price_asc", 10);
        assertThat(visited).hasSize(25);
        assertThat(new HashSet<>(visited)).hasSize(25); // 不重复
        assertThat(visited).isSorted(); // price_asc 按价格升序（价格唯一所以等价于 id 升序）
        assertThat(visited).doesNotContain(99L); // 下架不可见
    }

    @Test
    void defaultSortCursorPagination_coversAllWithoutDuplicateOrMiss() {
        // default(综合) = 销量倒序；销量 = 100 - id，故首条为 id=1（销量 99）
        List<Long> visited = collectAllByCursor("default", 7);
        assertThat(visited).hasSize(25);
        assertThat(new HashSet<>(visited)).hasSize(25);
        assertThat(visited.get(0)).isEqualTo(1L);
    }

    @Test
    void filtersAndTotal() {
        // 类目筛选
        SearchResponse byCat = searchService.search(null, 10L, null, null, "default", null, 50);
        assertThat(byCat.total()).isEqualTo(15);
        // 价格区间 [20, 30]
        SearchResponse byPrice = searchService.search(null, null, new BigDecimal("20"),
                new BigDecimal("30"), "default", null, 50);
        assertThat(byPrice.total()).isEqualTo(11); // 价格 20~30 共 11 条
        // 组合：关键词 + 价格
        SearchResponse combo = searchService.search("商品", null, new BigDecimal("10"),
                new BigDecimal("12"), "default", null, 50);
        assertThat(combo.total()).isEqualTo(3); // 价格 10/11/12
    }

    private List<Long> collectAllByCursor(String sort, int pageSize) {
        List<Long> visited = new ArrayList<>();
        String cursor = null;
        int rounds = 0;
        do {
            SearchResponse resp = searchService.search(null, null, null, null, sort, cursor, pageSize);
            for (SearchItem item : resp.list()) {
                visited.add(item.spuId());
            }
            cursor = resp.nextCursor();
            rounds++;
            assertThat(rounds).isLessThan(20); // 防死循环
        } while (cursor != null);
        return visited;
    }

    @Test
    void invalidCursorRejected() {
        try {
            searchService.search(null, null, null, null, "default", "not-base64-@@@", 10);
            throw new AssertionError("非法 cursor 应抛 1001");
        } catch (com.easyshopping.common.api.BizException e) {
            assertThat(e.getCode()).isEqualTo(com.easyshopping.common.api.BizErrorCode.PARAM_ERROR.getCode());
        }
    }
}
