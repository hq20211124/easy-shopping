package com.admin.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 系统配置项（KV；修改历史与一键回滚 P1，plans/admin-backend.md 2.5.2）。 */
@Entity
@Table(name = "admin_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_config_key", columnNames = "config_key"))
public class AdminConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 128)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 1024)
    private String configValue;

    @Column(length = 256)
    private String remark;

    protected AdminConfig() {
    }

    public AdminConfig(String configKey, String configValue, String remark) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.remark = remark;
    }

    public Long getId() {
        return id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
