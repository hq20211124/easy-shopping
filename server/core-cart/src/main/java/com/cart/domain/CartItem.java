package com.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 购物车行（shopping_cart，plans/ios-backend.md 4.3 DDL + android-backend P0 缺口清单）。
 *
 * <ul>
 *   <li>主键与对外 {@code cartItemId} 均为雪花（裁决 C13：分库分表禁止 AUTO_INCREMENT）；</li>
 *   <li>同一用户同一 SKU 只有一行（{@code uk_user_sku}）——重复加购合并数量，幂等重放 0 重复行；</li>
 *   <li><b>以 cartItemId 定位行</b>（android-backend 16.4：同一 SKU 多规格需区分，禁止用 skuId 定位）；</li>
 *   <li>{@code invalid} 失效标记：列表时按 core-product 商品状态回写（3001 缺失 / 3003 下架 → 失效），
 *       失效行仍保留在车内但不计入勾选与金额；</li>
 *   <li>数量 Integer（禁止浮点），金额不落车（结算时以 core-price 权威计价，客户端不可自证金额）。</li>
 * </ul>
 */
@Entity
@Table(name = "shopping_cart",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_sku", columnNames = {"user_id", "sku_id"}),
        indexes = @Index(name = "idx_cart_user", columnList = "user_id"))
public class CartItem {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    /** 对外展示的购物车项 ID（接口定位键，区别于主键）。 */
    @Column(name = "cart_item_id", nullable = false, unique = true)
    private Long cartItemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 商品 SPU（加购时由 core-product SKU 视图解析）。 */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "quantity", nullable = false)
    private int quantity = 1;

    /** 勾选态（默认勾选，plans DDL selected TINYINT DEFAULT 1）。 */
    @Column(name = "selected", nullable = false)
    private boolean selected = true;

    /** 失效标记（商品下架/删除时由列表链路回写；失效不计入金额与勾选汇总）。 */
    @Column(name = "invalid", nullable = false)
    private boolean invalid = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CartItem() {
    }

    public static CartItem create(Long id, Long cartItemId, Long userId, Long productId, Long skuId, int quantity) {
        CartItem item = new CartItem();
        item.id = id;
        item.cartItemId = cartItemId;
        item.userId = userId;
        item.productId = productId;
        item.skuId = skuId;
        item.quantity = quantity;
        return item;
    }

    public Long getId() {
        return id;
    }

    public Long getCartItemId() {
        return cartItemId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isInvalid() {
        return invalid;
    }

    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
