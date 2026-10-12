package com.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * SKU 库存账本（唯一事实来源，admin-backend 5.3.1 v1.1 修正：「DB 才是账本」）。
 *
 * <p>两段式库存：{@code available}（可用，预占前）/ {@code locked}（预占锁定，未出库）。
 * 下单 = 预占（available → locked），确认收货 = 出库（locked → 消失），
 * 取消/超时关单 = 释放（locked → available）。金额无关，数量为整数，禁止浮点。</p>
 *
 * <p>P0 非分片小表，主键自增仅为 H2 建表便利，P1 迁移分片时改雪花（裁决 C13，见 DEV.md 4.3）；
 * 业务唯一键为 {@code sku_id}。乐观锁 {@code @Version}：并发预占/释放冲突时由
 * {@code InventoryService} 重试，超上限按 3002 库存不足返回。</p>
 */
@Entity
@Table(name = "sku_stock", uniqueConstraints = @UniqueConstraint(name = "uk_sku_id", columnNames = "sku_id"))
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SKU ID（业务唯一键）。 */
    @Column(name = "sku_id", nullable = false, unique = true)
    private Long skuId;

    /** 可用库存（未锁定）。 */
    @Column(name = "available_stock", nullable = false)
    private int availableStock;

    /** 锁定库存（已预占、未出库）。 */
    @Column(name = "locked_stock", nullable = false)
    private int lockedStock;

    /** 乐观锁版本号（并发控制，禁止绕过）。 */
    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Stock() {
    }

    public Stock(Long skuId, int availableStock) {
        this.skuId = skuId;
        this.availableStock = availableStock;
        this.lockedStock = 0;
    }

    /** 预占：available → locked（调用方负责 @Version 重试与总量校验）。 */
    public void reserve(int quantity) {
        this.availableStock -= quantity;
        this.lockedStock += quantity;
        this.updatedAt = Instant.now();
    }

    /** 释放：locked → available（取消/超时关单）。 */
    public void release(int quantity) {
        this.lockedStock -= quantity;
        this.availableStock += quantity;
        this.updatedAt = Instant.now();
    }

    /** 出库：locked 消失（确认收货，货权已转移，不再回补）。 */
    public void deduct(int quantity) {
        this.lockedStock -= quantity;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSkuId() {
        return skuId;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public void setAvailableStock(int availableStock) {
        this.availableStock = availableStock;
    }

    public int getLockedStock() {
        return lockedStock;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
