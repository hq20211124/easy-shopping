package com.admin.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 数据字典项（订单状态/支付方式等枚举统一下发，web-admin 禁止硬编码）。
 * P0 单表（type 维度冗余）；TODO(P1) 拆 admin_dict_type 字典类型表（plans/admin-backend.md 2.5.2）。
 */
@Entity
@Table(name = "admin_dict",
        uniqueConstraints = @UniqueConstraint(name = "uk_dict_type_value", columnNames = {"dict_type", "dict_value"}),
        indexes = @Index(name = "idx_dict_type", columnList = "dict_type"))
public class AdminDict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 字典类型编码，如 order_status / pay_type */
    @Column(name = "dict_type", nullable = false, length = 64)
    private String dictType;

    /** 项编码（类型内唯一），如 0 / 1 / unpaid */
    @Column(name = "dict_value", nullable = false, length = 64)
    private String dictValue;

    @Column(name = "dict_label", nullable = false, length = 64)
    private String dictLabel;

    /** 1-启用 0-停用 */
    @Column(nullable = false)
    private Integer status = 1;

    @Column(nullable = false)
    private Integer sort = 0;

    @Column(length = 256)
    private String remark;

    protected AdminDict() {
    }

    public AdminDict(String dictType, String dictValue, String dictLabel, Integer sort, String remark) {
        this.dictType = dictType;
        this.dictValue = dictValue;
        this.dictLabel = dictLabel;
        this.sort = sort == null ? 0 : sort;
        this.remark = remark;
    }

    public Long getId() {
        return id;
    }

    public String getDictType() {
        return dictType;
    }

    public void setDictType(String dictType) {
        this.dictType = dictType;
    }

    public String getDictValue() {
        return dictValue;
    }

    public void setDictValue(String dictValue) {
        this.dictValue = dictValue;
    }

    public String getDictLabel() {
        return dictLabel;
    }

    public void setDictLabel(String dictLabel) {
        this.dictLabel = dictLabel;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
