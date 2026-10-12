package com.easyshopping.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户账号（P0 买家 + 管理端账号共用一张表；TODO(P1) 拆 admin-user —— plans/admin-backend.md 1.1.3）。
 *
 * <p>角色存 CSV：{@code USER}（买家）/ {@code ADMIN}（管理端）。JWT 中的 roles 即来源于此
 * （骨架 JWT 仅支持 roles 字段；plans 2.1.3 的 permissions/dataScope/permVer 由 admin-bff 本地 RBAC 承载）。</p>
 *
 * <p>主键：P0 非分片小表用 H2 自增；P1 迁移时改雪花 ID（裁决 C13，禁止分片表自增）。</p>
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** 手机号（买家注册来源，可空；唯一性由 P1 迁移脚本保证，H2 下建了普通索引） */
    @Column(length = 20)
    private String mobile;

    /**
     * 密码哈希（{@code PasswordHasher} 生成，绝不回传前端、绝不落日志）。
     * P0-H 起为 {@code pbkdf2_sha256$迭代数$盐$哈希}；存量 {@code s256$盐$哈希}（单轮 SHA-256）
     * 仅用于兼容校验，登录成功后按 {@code PasswordHasher.rehashIfNeeded} 自动升级。
     */
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(length = 64)
    private String nickname;

    @Column(length = 256)
    private String avatar;

    @Column(length = 128)
    private String email;

    /** 0-未知 1-男 2-女 */
    @Column(nullable = false)
    private Integer gender = 0;

    /** yyyy-MM-dd */
    @Column(length = 10)
    private String birthday;

    /** CSV：USER / ADMIN */
    @Column(nullable = false, length = 64)
    private String roles = "USER";

    /** 1-正常 0-停用 */
    @Column(nullable = false)
    private Integer status = 1;

    /** 连续登录失败次数（达 5 次锁定 15 分钟，plans/admin-backend.md 2.5.1） */
    @Column(name = "login_fail_count", nullable = false)
    private Integer loginFailCount = 0;

    /** 锁定截止时间；null = 未锁定 */
    @Column(name = "lock_until")
    private Instant lockUntil;

    /** PIPL 软删标记（注销后保留 30 天审计，architecture/04-messaging §7.1 user.deleted） */
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserAccount() {
    }

    public UserAccount(String username, String mobile, String passwordHash, String nickname, String roles) {
        this.username = username;
        this.mobile = mobile;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.roles = roles == null || roles.isBlank() ? "USER" : roles;
    }

    public boolean isLocked() {
        return lockUntil != null && lockUntil.isAfter(Instant.now());
    }

    /** 登录失败：计数 +1，达阈值（5 次）锁定 15 分钟。 */
    public void recordLoginFailure(int maxFails, int lockMinutes) {
        this.loginFailCount = (this.loginFailCount == null ? 0 : this.loginFailCount) + 1;
        if (this.loginFailCount >= maxFails) {
            this.lockUntil = Instant.now().plusSeconds(lockMinutes * 60L);
        }
        touch();
    }

    /** 登录成功：清零失败计数与锁定、记录最近登录时间。 */
    public void recordLoginSuccess() {
        this.loginFailCount = 0;
        this.lockUntil = null;
        this.lastLoginAt = Instant.now();
        touch();
    }

    public void resetLoginFail() {
        this.loginFailCount = 0;
        this.lockUntil = null;
        touch();
    }

    public void softDelete() {
        this.deleted = true;
        this.status = 0;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
        touch();
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        touch();
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
        touch();
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
        touch();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
        touch();
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
        touch();
    }

    public String getBirthday() {
        return birthday;
    }

    public void setBirthday(String birthday) {
        this.birthday = birthday;
        touch();
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
        touch();
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
        touch();
    }

    public Integer getLoginFailCount() {
        return loginFailCount;
    }

    public Instant getLockUntil() {
        return lockUntil;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
