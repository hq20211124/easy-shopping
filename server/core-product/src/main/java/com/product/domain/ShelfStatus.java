package com.product.domain;

/**
 * 商品上下架状态（仅上下架，不承载审核语义）。
 *
 * <p>出处：plans/admin-backend.md 3.3.2 状态映射表——
 * {@code admin_product_spu.status}：0-下架、1-上架；<b>上架前置条件 = audit_status=2</b>；
 * 下架不回退审核状态。</p>
 */
public enum ShelfStatus {

    /** 0 下架（买家端不可见） */
    OFF_SHELF(0),
    /** 1 上架 */
    ON_SHELF(1);

    private final int value;

    ShelfStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
