package com.cart;

import com.cart.client.InventoryClient;
import com.cart.client.PriceClient;
import com.cart.client.ProductClient;
import com.cart.client.dto.ClientDtos.PriceView;
import com.cart.client.dto.ClientDtos.SkuView;
import com.cart.client.dto.ClientDtos.StockView;
import com.cart.domain.CartItem;
import com.cart.repo.CartItemRepository;
import com.easyshopping.common.api.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * core-cart 测试基类：mem H2（不污染 ./data 开发库）+ 三个下游客户端全部 Mock。
 *
 * <p>下游契约：core-product {@code GET /api/v1/internal/skus?ids=}、core-price
 * {@code GET /api/v1/price/skus?ids=}、core-inventory {@code GET /api/v1/inventory/stocks?skuIds=}。
 * 商品目录用可变 Map 打桩，便于用例中途「下架 / 删品」验证失效标记回写。</p>
 */
abstract class AbstractCartTest {

    @Autowired
    protected CartItemRepository cartItemRepository;

    /** 幂等拦截器是单例 Bean、去重 Map 跨用例存续 → 每例清空，避免测试间撞键。 */
    @Autowired
    protected com.easyshopping.common.idempotent.IdempotentInterceptor idempotentInterceptor;

    @MockBean
    protected ProductClient productClient;
    @MockBean
    protected PriceClient priceClient;
    @MockBean
    protected InventoryClient inventoryClient;

    /** 可变商品目录（core-product）：skuId → SkuView。 */
    protected final Map<Long, SkuView> skuCatalog = new HashMap<>();
    /** 可变价格目录（core-price）：skuId → 价格。 */
    protected final Map<Long, BigDecimal> priceCatalog = new HashMap<>();
    /** 可变库存目录（core-inventory）：skuId → 可用库存。 */
    protected final Map<Long, Integer> stockCatalog = new HashMap<>();

    @BeforeEach
    void resetCartState() {
        clearIdempotencyKeys();
        cartItemRepository.deleteAll();
        skuCatalog.clear();
        priceCatalog.clear();
        stockCatalog.clear();
        Mockito.reset(productClient, priceClient, inventoryClient);
        when(productClient.skus(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(skuCatalog::get).filter(Objects::nonNull).toList();
        });
        when(priceClient.prices(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(skuCatalog::get).filter(Objects::nonNull)
                    .map(sku -> new PriceView(sku.skuId(), priceCatalog.get(sku.skuId())))
                    .filter(p -> p.price() != null)
                    .toList();
        });
        when(inventoryClient.stocks(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().filter(stockCatalog::containsKey)
                    .map(id -> new StockView(id, stockCatalog.get(id), 0, stockCatalog.get(id)))
                    .toList();
        });
    }

    /** 清空 P0 内存幂等占位（反射，common 未暴露 reset API；见 IdempotentInterceptor.seen）。 */
    protected void clearIdempotencyKeys() {
        try {
            var field = com.easyshopping.common.idempotent.IdempotentInterceptor.class
                    .getDeclaredField("seen");
            field.setAccessible(true);
            ((java.util.Map<?, ?>) field.get(idempotentInterceptor)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("清空幂等键失败", e);
        }
    }

    /** 上架一个 SKU（价 + 库存一并打桩）。 */
    protected void onShelf(long skuId, long spuId, String price, int stock) {
        skuCatalog.put(skuId, new SkuView(skuId, spuId, 90001L, "SKU-" + skuId,
                "img-" + skuId + ".jpg", true));
        priceCatalog.put(skuId, new BigDecimal(price));
        stockCatalog.put(skuId, stock);
    }

    /** 下架（onShelf=false，行仍在车里 → 失效标记）。 */
    protected void offShelf(long skuId) {
        SkuView old = skuCatalog.get(skuId);
        if (old != null) {
            skuCatalog.put(skuId, new SkuView(old.skuId(), old.spuId(), old.storeId(),
                    old.name(), old.image(), false));
        }
    }

    /** 商品从目录彻底消失（core-product 404 → 失效标记）。 */
    protected void removeSku(long skuId) {
        skuCatalog.remove(skuId);
    }

    // ------------------------------------------------------------------ HTTP 便捷方法

    protected static MockHttpServletRequestBuilder getJson(String uri, long userId) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri)
                .header(UserContext.HEADER_USER_ID, String.valueOf(userId));
    }

    /** 写接口：默认带幂等键（用例需要验证缺键时显式传 null）。 */
    protected static MockHttpServletRequestBuilder postJson(String uri, long userId,
                                                            String idempotencyKey, String body) {
        MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.post(uri)
                .header(UserContext.HEADER_USER_ID, String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return builder;
    }

    protected static MockHttpServletRequestBuilder putJson(String uri, long userId,
                                                           String idempotencyKey, String body) {
        MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.put(uri)
                .header(UserContext.HEADER_USER_ID, String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return builder;
    }

    protected static MockHttpServletRequestBuilder deleteJson(String uri, long userId,
                                                              String idempotencyKey) {
        MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.delete(uri)
                .header(UserContext.HEADER_USER_ID, String.valueOf(userId));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return builder;
    }

    /** 本人购物车行数。 */
    protected long rowCount(long userId) {
        return cartItemRepository.findAll().stream()
                .filter(item -> item.getUserId() == userId)
                .count();
    }

    protected List<CartItem> rowsOf(long userId) {
        return cartItemRepository.findAll().stream()
                .filter(item -> item.getUserId() == userId)
                .toList();
    }
}
