package com.cart.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车 DTO（契约：plans/android-frontend.md 1.6.x P0 接口 + plans/ios-backend.md 4.2.1）。
 *
 * <p>字段 camelCase 与 common 响应信封一致；数量 Integer、金额 BigDecimal，禁止浮点（DEV.md 4）。
 * 写接口一律以 {@code cartItemId} 定位行，禁止用 skuId（android-backend 16.4）。</p>
 */
public final class CartDtos {

    private CartDtos() {
    }

    // ------------------------------------------------------------------ 请求

    /**
     * 加入购物车 {@code POST /api/v1/cart/add}。
     *
     * <p>只传 skuId（android「SKU + 数量」口径）；productId 由服务端从 core-product SKU 视图解析。</p>
     */
    public record AddRequest(
            @NotNull(message = "skuId 不能为空") Long skuId,
            Integer quantity) {
    }

    /**
     * 修改数量/勾选 {@code POST /api/v1/cart/update} 或 {@code PUT /api/v1/cart/{cartItemId}}。
     *
     * <p>POST 动作式以 body 的 {@code cartItemId} 定位行；RESTful 形态由路径覆盖（controller 注入）。</p>
     */
    public record UpdateRequest(Long cartItemId, Integer quantity, Boolean selected) {
    }

    /** 批量删除 {@code POST /api/v1/cart/remove}。 */
    public record RemoveRequest(
            @NotEmpty(message = "cartItemIds 不能为空") List<Long> cartItemIds) {
    }

    /**
     * 勾选 {@code POST /api/v1/cart/select}。
     *
     * <p>{@code cartItemIds} 缺省/空 = 全选或取消全选（与 android「勾选通常本地持久化 + 异步同步」兼容）。</p>
     */
    public record SelectRequest(List<Long> cartItemIds, Boolean selected) {
    }

    // ------------------------------------------------------------------ 响应

    public record AddResponse(Long cartItemId, int cartTotalQuantity) {
    }

    public record UpdateResponse(Long cartItemId, int cartTotalQuantity, int selectedQuantity) {
    }

    public record RemoveResponse(int cartTotalQuantity) {
    }

    public record SelectResponse(int cartTotalQuantity, int selectedQuantity) {
    }

    /**
     * 购物车行视图（GET /api/v1/cart/list）。
     *
     * <p>{@code invalid}=商品失效（下架/删除）标记，失效行仍返回但不计入金额与勾选汇总；
     * {@code price} 为 core-price 展示价（结算时以权威计价为准）；{@code discountTags} P0 恒空（促销未接入）。</p>
     */
    public record CartItemVO(Long cartItemId, Long productId, Long skuId, String title, String mainImage,
                             BigDecimal price, int quantity, int stock, boolean inStock,
                             boolean selected, boolean invalid, List<String> discountTags) {
    }

    /** 购物车列表（含汇总；{@code maxItems} 服务端下发，前端禁止硬编码，ios-frontend 7.2）。 */
    public record CartListResponse(List<CartItemVO> items, int totalQuantity, int selectedQuantity,
                                   int maxItems, BigDecimal totalAmount, BigDecimal discountAmount,
                                   BigDecimal payableAmount) {
    }
}
