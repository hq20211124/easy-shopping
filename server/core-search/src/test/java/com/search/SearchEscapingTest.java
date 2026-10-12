package com.search;

import com.search.domain.SearchProduct;
import com.search.repo.SearchProductRepository;
import com.search.service.HotWordService.HotWord;
import com.search.service.SearchService;
import com.search.service.SearchService.SearchItem;
import com.search.service.SearchService.SearchResponse;
import com.search.service.SearchService.SuggestionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索 LIKE 转义与防注入测试（mem H2，JPA 参数绑定）。
 * 关键词含 %/_/SQL 片段时必须按字面量匹配或安全返回空，绝不允许通配符泄漏或注入。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-search-escape-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.search.sync-enabled=false" // 测试不拉 core-product
})
class SearchEscapingTest {

    @Autowired
    private SearchService searchService;

    @Autowired
    private SearchProductRepository repo;

    @BeforeEach
    void seed() {
        repo.deleteAll();
        save(1L, "特惠 100%_纯棉 T恤", 100L, new BigDecimal("59.00"));
        save(2L, "百分百好礼盒装", 80L, new BigDecimal("99.00"));
        save(3L, "普通商品手机壳", 50L, new BigDecimal("19.90"));
        save(4L, "100%羊毛被", 30L, new BigDecimal("199.00"));
    }

    @Test
    void percentAndUnderscoreAreMatchedLiterally() {
        // q=100%：转义后只命中含字面量 "100%" 的 1/4 号，不会命中全部（未转义时 % 为通配符会全表命中）
        SearchResponse resp = searchService.search("100%", null, null, null, "default", null, 20);
        assertThat(resp.list()).hasSize(2);
        assertThat(resp.list()).extracting(SearchItem::spuId).containsExactlyInAnyOrder(1L, 4L);

        // q=_：下划线是 LIKE 单字符通配符，转义后只命中含字面量 "_" 的 1 号
        SearchResponse underscore = searchService.search("_", null, null, null, "default", null, 20);
        assertThat(underscore.list()).hasSize(1);
        assertThat(underscore.list().get(0).spuId()).isEqualTo(1L);
    }

    @Test
    void sqlFragmentIsTreatedAsPlainText_noInjection() {
        // SQL 片段按字面量匹配 → 安全返回空（不报错、不泄漏数据）
        SearchResponse injected = searchService.search("' OR '1'='1", null, null, null, "default", null, 20);
        assertThat(injected.list()).isEmpty();
        assertThat(injected.total()).isZero();

        // 通配符组合 q=%'%' 转义后为字面量 "'%'"，无命中
        SearchResponse wildcards = searchService.search("%'%", null, null, null, "default", null, 20);
        assertThat(wildcards.list()).isEmpty();

        // 正常关键词不受转义影响
        SearchResponse ok = searchService.search("手机", null, null, null, "default", null, 20);
        assertThat(ok.list()).hasSize(1);
        assertThat(ok.list().get(0).spuId()).isEqualTo(3L);
    }

    @Test
    void suggestionsAndHotWords() {
        // 标题前缀匹配：转义生效 —— product 类建议只命中字面前缀 "100%" 的 4 号；
        // 若未转义，模式 "100%%" 会把 1/2/3/4 全部命中（前缀通配泄漏）
        SuggestionResponse s = searchService.suggestions("100%", 10);
        assertThat(s.suggestions()).filteredOn("type", "product")
                .singleElement()
                .satisfies(item -> assertThat(item.productId()).isEqualTo(4L));

        // 正常前缀同样只命中对应商品
        SuggestionResponse s2 = searchService.suggestions("特惠", 10);
        assertThat(s2.suggestions()).filteredOn("type", "product")
                .singleElement()
                .satisfies(item -> assertThat(item.productId()).isEqualTo(1L));

        // 热搜：检索上报 + TopN（其他用例也会制造检索流量，故按包含断言而非首位）
        searchService.search("手机壳", null, null, null, "default", null, 10);
        java.util.List<HotWord> hot = searchService.hotWords(10);
        assertThat(hot).isNotEmpty();
        assertThat(hot).filteredOn(h -> h.keyword().equals("手机壳"))
                .singleElement()
                .satisfies(h -> assertThat(h.heat()).isGreaterThanOrEqualTo(1));
    }

    private void save(long spuId, String name, long sales, BigDecimal price) {
        SearchProduct p = new SearchProduct();
        p.setId(spuId);
        p.setSpuId(spuId);
        p.setName(name);
        p.setSalesCount(sales);
        p.setMinPrice(price);
        p.setMarketPrice(price);
        p.setShelfStatus(1);
        p.setCategoryId(10L);
        repo.save(p);
    }
}
