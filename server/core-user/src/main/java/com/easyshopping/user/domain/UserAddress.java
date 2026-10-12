package com.easyshopping.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 收货地址（软删，plans/ios-backend.md 5.2.2 / plans/android-backend.md 5.2.2）。
 *
 * <p>默认地址唯一性由业务层保证（设默认时先清同用户其它默认，AddressService）。
 * 主键：P0 非分片小表用 H2 自增；P1 迁移时改雪花 ID（裁决 C13）。</p>
 */
@Entity
@Table(name = "user_address", indexes = @Index(name = "idx_addr_user", columnList = "user_id"))
public class UserAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String name;

    /** 明文手机号仅存库；出参统一脱敏（MaskUtil.maskMobile） */
    @Column(nullable = false, length = 20)
    private String mobile;

    @Column(nullable = false, length = 32)
    private String province;

    @Column(nullable = false, length = 32)
    private String city;

    @Column(length = 32)
    private String district;

    @Column(nullable = false, length = 256)
    private String detail;

    @Column(name = "zip_code", length = 10)
    private String zipCode;

    /** 每用户至多一个默认地址（业务层保证） */
    @Column(name = "is_default", nullable = false)
    private boolean defaultValue = false;

    @Column(precision = 10, scale = 6)
    private java.math.BigDecimal latitude;

    @Column(precision = 10, scale = 6)
    private java.math.BigDecimal longitude;

    /** 软删标记（订单地址快照审计依赖） */
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserAddress() {
    }

    public UserAddress(Long userId, String name, String mobile, String province, String city,
                       String district, String detail, String zipCode, boolean isDefault,
                       java.math.BigDecimal latitude, java.math.BigDecimal longitude) {
        this.userId = userId;
        this.name = name;
        this.mobile = mobile;
        this.province = province;
        this.city = city;
        this.district = district;
        this.detail = detail;
        this.zipCode = zipCode;
        this.defaultValue = isDefault;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void updateFrom(String name, String mobile, String province, String city,
                           String district, String detail, String zipCode,
                           java.math.BigDecimal latitude, java.math.BigDecimal longitude) {
        this.name = name;
        this.mobile = mobile;
        this.province = province;
        this.city = city;
        this.district = district;
        this.detail = detail;
        this.zipCode = zipCode;
        this.latitude = latitude;
        this.longitude = longitude;
        this.updatedAt = Instant.now();
    }

    public void markDefault(boolean isDefault) {
        this.defaultValue = isDefault;
        this.updatedAt = Instant.now();
    }

    public void softDelete() {
        this.deleted = true;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getMobile() {
        return mobile;
    }

    public String getProvince() {
        return province;
    }

    public String getCity() {
        return city;
    }

    public String getDistrict() {
        return district;
    }

    public String getDetail() {
        return detail;
    }

    public String getZipCode() {
        return zipCode;
    }

    public boolean isDefault() {
        return defaultValue;
    }

    public java.math.BigDecimal getLatitude() {
        return latitude;
    }

    public java.math.BigDecimal getLongitude() {
        return longitude;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
