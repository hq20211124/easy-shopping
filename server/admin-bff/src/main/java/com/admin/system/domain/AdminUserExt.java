package com.admin.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 管理端用户扩展信息（本服务本地；账号本体在 core-user，userId 跨服务引用）。
 * permVer：权限版本号，角色授权变更时 +1（plans/admin-backend.md 2.1.4；P1 前端比对强登）。
 */
@Entity
@Table(name = "admin_user_ext")
public class AdminUserExt {

    /** 主键 = core-user 的 userId（手动赋值，不用自增/雪花） */
    @Id
    private Long userId;

    @Column(name = "dept_id")
    private Long deptId;

    @Column(name = "perm_ver", nullable = false)
    private Long permVer = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AdminUserExt() {
    }

    public AdminUserExt(Long userId, Long deptId) {
        this.userId = userId;
        this.deptId = deptId;
    }

    public void bumpPermVer() {
        this.permVer = (this.permVer == null ? 0L : this.permVer) + 1;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public Long getPermVer() {
        return permVer;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
