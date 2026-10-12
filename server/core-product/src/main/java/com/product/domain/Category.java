package com.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 商品类目（树形，管理端维护）。
 *
 * <p>出处：plans/admin-backend.md 3.2.2 类目接口 / 3.1 类目管理（树 CRUD、排序、启用/禁用）。
 * 主键用雪花 ID（裁决 C13，禁止自增，见 DEV.md 4.3）；{@code parentId=0} 表示根节点。</p>
 */
@Entity
@Table(name = "category")
public class Category {

    @Id
    private Long id;

    /** 父类目 ID，0 = 根 */
    private Long parentId = 0L;

    @Column(nullable = false, length = 64)
    private String name;

    /** 同级排序值，越小越靠前 */
    private Integer sort = 0;

    /** 1-启用 0-禁用（禁用仅影响选择，不影响历史数据） */
    private Integer status = 1;

    /** 软删标记（P0 回收站简化：删除即置位，不做恢复接口） */
    private boolean deleted = false;

    @Version
    private Long version;

    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
