package com.admin.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 角色-权限关联（permission_code 引用 admin_permission.code；角色授权变更后刷新相关用户 permVer）。 */
@Entity
@Table(name = "admin_role_permission",
        uniqueConstraints = @UniqueConstraint(name = "uk_role_perm", columnNames = {"role_id", "permission_code"}),
        indexes = @Index(name = "idx_rp_code", columnList = "permission_code"))
public class AdminRolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "permission_code", nullable = false, length = 64)
    private String permissionCode;

    protected AdminRolePermission() {
    }

    public AdminRolePermission(Long roleId, String permissionCode) {
        this.roleId = roleId;
        this.permissionCode = permissionCode;
    }

    public Long getId() {
        return id;
    }

    public Long getRoleId() {
        return roleId;
    }

    public String getPermissionCode() {
        return permissionCode;
    }
}
