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
 * 角色（plans/admin-backend.md 2.2.2 预置角色 / 2.3.1 数据范围）。
 *
 * <p>data_scope：1-全部 2-本部门 3-本部门及下级 4-仅本人 5-自定义 6-本店铺（merchant）。
 * P0 实际生效三档：1 全部 / 2 本部门 / 6 本店铺；3/4/5 存表但查询改写留 TODO（见 DataScopeResolver）。</p>
 */
@Entity
@Table(name = "admin_role",
        uniqueConstraints = @UniqueConstraint(name = "uk_role_code", columnNames = "code"))
public class AdminRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    /** 数据范围 1~6（plans/admin-backend.md 2.3.1） */
    @Column(name = "data_scope", nullable = false)
    private Integer dataScope = 1;

    /** 1-启用 0-停用 */
    @Column(nullable = false)
    private Integer status = 1;

    /** 内置角色不可编辑/删除（super_admin 等，plans 2.2.2 约束） */
    @Column(nullable = false)
    private boolean builtin = false;

    @Column(length = 256)
    private String remark;

    protected AdminRole() {
    }

    public AdminRole(String code, String name, Integer dataScope, boolean builtin, String remark) {
        this.code = code;
        this.name = name;
        this.dataScope = dataScope == null ? 1 : dataScope;
        this.builtin = builtin;
        this.remark = remark;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getDataScope() {
        return dataScope;
    }

    public void setDataScope(Integer dataScope) {
        this.dataScope = dataScope;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
