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
 * 权限点/菜单注册表（plans/admin-backend.md 2.2.3）。
 *
 * <p>code 统一「模块:资源」，商品域统一 {@code product:*}（禁 goods:*）。
 * type：menu=菜单节点（前端动态路由）、permission=按钮/接口权限点。
 * P0 单表承载菜单+权限；TODO(P1) 补 plans 的 api_method/api_path 网关侧二次校验列。</p>
 */
@Entity
@Table(name = "admin_permission",
        uniqueConstraints = @UniqueConstraint(name = "uk_perm_code", columnNames = "code"),
        indexes = @Index(name = "idx_perm_parent", columnList = "parent_id"))
public class AdminPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 权限码/菜单键，如 product:list、user:roles（唯一） */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 64)
    private String title;

    /** menu / permission */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "parent_id")
    private Long parentId;

    /** 前端路由 path（仅 menu） */
    @Column(length = 128)
    private String path;

    /** 菜单可见所需权限码（空=登录即可见） */
    @Column(name = "required_perm", length = 64)
    private String requiredPerm;

    @Column(name = "order_num", nullable = false)
    private Integer orderNum = 0;

    protected AdminPermission() {
    }

    public AdminPermission(String code, String title, String type, Long parentId,
                           String path, String requiredPerm, Integer orderNum) {
        this.code = code;
        this.title = title;
        this.type = type;
        this.parentId = parentId;
        this.path = path;
        this.requiredPerm = requiredPerm;
        this.orderNum = orderNum == null ? 0 : orderNum;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getRequiredPerm() {
        return requiredPerm;
    }

    public void setRequiredPerm(String requiredPerm) {
        this.requiredPerm = requiredPerm;
    }

    public Integer getOrderNum() {
        return orderNum;
    }

    public void setOrderNum(Integer orderNum) {
        this.orderNum = orderNum;
    }
}
