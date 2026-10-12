package com.search.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.search.domain.SearchProduct;
import com.search.repo.SearchProductRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 商品搜索（P0 DB LIKE + cursor 分页；P1 迁 ES，architecture/06-search-recommend.md）。
 *
 * <p>契约：GET /api/v1/search/products（任务 P0 约定 + plans/ios-backend.md 3.2.3 的 cursor/next_cursor 口径）、
 * GET /api/v1/search/suggestions（ios 3.2.2 suggestions 结构简化版）、GET /api/v1/search/hot（ios 3.2.4 hot_keywords）。
 * 防注入：关键词 LIKE 通配符转义 + JPA 参数绑定（value 走绑定参数，禁止拼接 SQL）。</p>
 */
@Service
public class SearchService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_SUGGESTIONS = 10;

    private final SearchProductRepository repo;
    private final HotWordService hotWordService;

    public SearchService(SearchProductRepository repo, HotWordService hotWordService) {
        this.repo = repo;
        this.hotWordService = hotWordService;
    }

    /**
     * 关键词搜索。
     *
     * @param q        关键词（可空 = 纯筛选/浏览；非空时计入热搜）
     * @param sort     default(综合=销量)/price_asc/price_desc/sales/newest
     * @param cursor   上一页返回的 nextCursor（首页不传；排序切换后失效属预期）
     * @param pageSize 1~50（对齐 plans/ios-backend.md 3.2.1 page_size max=50）
     */
    @Transactional(readOnly = true)
    public SearchResponse search(String q, Long categoryId, BigDecimal priceMin, BigDecimal priceMax,
                                 String sort, String cursor, Integer pageSize) {
        SortSpec sortSpec = SortSpec.parse(sort);
        Cursor cur = Cursor.decode(cursor);
        hotWordService.record(q);

        Specification<SearchProduct> base = buildFilterSpec(q, categoryId, priceMin, priceMax);
        Specification<SearchProduct> withCursor = cur == null ? base : base.and(buildCursorSpec(sortSpec, cur));
        int size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        Page<SearchProduct> page = repo.findAll(withCursor, PageRequest.of(0, size, sortSpec.toSort()));
        long total = repo.count(base);

        List<SearchItem> items = new ArrayList<>();
        for (SearchProduct p : page.getContent()) {
            items.add(new SearchItem(p.getSpuId(), p.getName(), p.getMainImage(),
                    p.getMinPrice(), p.getMarketPrice(), p.getSalesCount()));
        }
        // nextCursor = 本页末条的 sortKey|id（Base64Url）；页未满说明已到底，返回 null
        String nextCursor = page.getContent().size() == size
                ? Cursor.encode(sortSpec, page.getContent().get(page.getContent().size() - 1))
                : null;
        return new SearchResponse(q, total, nextCursor, items);
    }

    /** 搜索建议：热搜词前缀匹配（type=keyword）+ 商品标题前缀匹配（type=product，按销量取前 N）。 */
    @Transactional(readOnly = true)
    public SuggestionResponse suggestions(String q, Integer limit) {
        if (q == null || q.isBlank()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "q 必填");
        }
        int n = Math.min(Math.max(limit == null ? 10 : limit, 1), MAX_SUGGESTIONS);
        String escaped = escapeLike(q.trim());
        List<SuggestionItem> items = new ArrayList<>();
        hotWordService.matchPrefix(q, n).forEach(k -> items.add(new SuggestionItem(k, "keyword", null)));
        Page<SearchProduct> page = repo.findAll(visibleOnly((root, cq, cb) ->
                        cb.like(root.get("name"), escaped + "%", '\\')),
                PageRequest.of(0, n, Sort.by(Sort.Direction.DESC, "salesCount").and(Sort.by("id"))));
        for (SearchProduct p : page.getContent()) {
            items.add(new SuggestionItem(p.getName(), "product", p.getSpuId()));
        }
        List<SuggestionItem> result = items.size() > n ? List.copyOf(items.subList(0, n)) : items;
        return new SuggestionResponse(q, result);    }

    /** 热搜词：内存 TopN（无流量时返回运营种子词）。 */
    public List<HotWordService.HotWord> hotWords(int limit) {
        return hotWordService.top(Math.min(Math.max(limit, 1), 20));
    }

    // ==================== 内部实现 ====================

    /** 基础过滤：仅上架 + 关键词/类目/价格区间（count 与翻页共用，不受 cursor 影响）。 */
    private Specification<SearchProduct> buildFilterSpec(String q, Long categoryId,
                                                         BigDecimal priceMin, BigDecimal priceMax) {
        String keyword = (q == null || q.isBlank()) ? null : escapeLike(q.trim());
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("shelfStatus"), 1));
            if (keyword != null) {
                ps.add(cb.like(root.get("name"), "%" + keyword + "%", '\\'));
            }
            if (categoryId != null) {
                ps.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (priceMin != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("minPrice"), priceMin));
            }
            if (priceMax != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("minPrice"), priceMax));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Specification<SearchProduct> visibleOnly(Specification<SearchProduct> extra) {
        return (root, cq, cb) -> cb.and(
                cb.equal(root.get("shelfStatus"), 1),
                extra.toPredicate(root, cq, cb));
    }

    /**
     * cursor 谓词（映射 id 游标 + 排序键做稳定翻页，杜绝深 OFFSET —— plans/ios-backend.md 1728 分页优化）：
     * price_asc  → (price > p) OR (price = p AND id > id)
     * price_desc → (price < p) OR (price = p AND id > id)
     * sales      → (sales < s) OR (sales = s AND id > id)
     * newest     → id < id
     */
    private Specification<SearchProduct> buildCursorSpec(SortSpec sortSpec, Cursor cur) {
        return (root, cq, cb) -> {
            switch (sortSpec.field()) {
                case PRICE -> {
                    BigDecimal key = cur.key() == null ? BigDecimal.ZERO : cur.key();
                    Predicate beyondKey = sortSpec.priceAsc()
                            ? cb.greaterThan(root.get("minPrice"), key)
                            : cb.lessThan(root.get("minPrice"), key);
                    return cb.or(beyondKey, cb.and(
                            cb.equal(root.get("minPrice"), key),
                            cb.greaterThan(root.get("id"), cur.id())));
                }
                case SALES -> {
                    long key = cur.key() == null ? 0L : cur.key().longValue();
                    return cb.or(
                            cb.lessThan(root.get("salesCount"), key),
                            cb.and(
                                    cb.equal(root.get("salesCount"), key),
                                    cb.greaterThan(root.get("id"), cur.id())));
                }
                default -> {
                    return cb.lessThan(root.get("id"), cur.id());
                }
            }
        };
    }

    /** LIKE 转义：\ → \\，% → \%，_ → \_（与 core-product 同款处理，配合参数绑定防注入）。 */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ==================== DTO ====================

    /** 搜索结果：total = 全量命中数（不受 cursor 影响），nextCursor 末页为 null。 */
    public record SearchResponse(String query, Long total, String nextCursor, List<SearchItem> list) {
    }

    /** 结果条目（字段对齐 plans/android-backend.md 3.1.3 卡片）。 */
    public record SearchItem(Long spuId, String name, String mainImage,
                             BigDecimal price, BigDecimal originalPrice, Long salesCount) {
    }

    /** 搜索建议响应。 */
    public record SuggestionResponse(String query, List<SuggestionItem> suggestions) {
    }

    /** 建议条目：type=keyword(热搜词)/product(商品标题)，product 附 productId。 */
    public record SuggestionItem(String text, String type, Long productId) {
    }

    // ==================== 排序/游标 ====================

    /** 排序键（default 综合 = 销量倒序）。 */
    enum SortField {
        PRICE, SALES, ID
    }

    record SortSpec(SortField field, boolean priceAsc) {

        static SortSpec parse(String sort) {
            return switch (sort == null || sort.isBlank() ? "default" : sort) {
                case "price_asc" -> new SortSpec(SortField.PRICE, true);
                case "price_desc" -> new SortSpec(SortField.PRICE, false);
                case "sales" -> new SortSpec(SortField.SALES, false);
                case "newest" -> new SortSpec(SortField.ID, false);
                default -> new SortSpec(SortField.SALES, false); // default 综合
            };
        }

        Sort toSort() {
            return switch (field) {
                case PRICE -> priceAsc
                        ? Sort.by(Sort.Direction.ASC, "minPrice").and(Sort.by(Sort.Direction.ASC, "id"))
                        : Sort.by(Sort.Direction.DESC, "minPrice").and(Sort.by(Sort.Direction.ASC, "id"));
                case SALES -> Sort.by(Sort.Direction.DESC, "salesCount").and(Sort.by(Sort.Direction.ASC, "id"));
                case ID -> Sort.by(Sort.Direction.DESC, "id");
            };
        }
    }

    /** cursor 载荷：Base64Url("排序键|id")；ID 排序只编码 id。 */
    record Cursor(BigDecimal key, long id) {

        static String encode(SortSpec sortSpec, SearchProduct last) {
            String raw = switch (sortSpec.field()) {
                case PRICE -> (last.getMinPrice() == null ? BigDecimal.ZERO : last.getMinPrice())
                        .stripTrailingZeros().toPlainString() + "|" + last.getId();
                case SALES -> (last.getSalesCount() == null ? 0L : last.getSalesCount()) + "|" + last.getId();
                case ID -> String.valueOf(last.getId());
            };
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (!raw.contains("|")) {
                    return new Cursor(null, Long.parseLong(raw));
                }
                String[] parts = raw.split("\\|", 2);
                return new Cursor(new BigDecimal(parts[0]), Long.parseLong(parts[1]));
            } catch (Exception e) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "cursor 非法");
            }
        }
    }
}
