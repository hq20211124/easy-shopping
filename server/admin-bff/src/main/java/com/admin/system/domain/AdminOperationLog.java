package com.admin.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 操作日志（AOP 记录，plans/admin-backend.md 2.4；敏感字段出参按前端 5.2/5.5 脱敏）。
 * P0 同步落库；TODO(P1) 异步 + 批量写 + 归档 Elasticsearch。
 */
@Entity
@Table(name = "admin_operation_log", indexes = {
        @Index(name = "idx_oplog_operator", columnList = "operator_id"),
        @Index(name = "idx_oplog_created", columnList = "created_at")
})
public class AdminOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name", length = 64)
    private String operatorName;

    @Column(nullable = false, length = 64)
    private String module;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 16)
    private String method;

    @Column(length = 256)
    private String path;

    /** 操作内容摘要（参数已截断脱敏，禁止落密码等敏感字段） */
    @Column(length = 2000)
    private String content;

    @Column(length = 64)
    private String ip;

    /** 1-成功 0-失败 */
    @Column(nullable = false)
    private Integer status = 1;

    @Column(name = "error_msg", length = 512)
    private String errorMsg;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "after_data", length = 2000)
    private String afterData;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** 供操作日志切面等包外组件构造（JPA 仍用 protected 无参构造加载）。 */
    public static AdminOperationLog create() {
        return new AdminOperationLog();
    }

    protected AdminOperationLog() {
    }

    public Long getId() {
        return id;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public void setAfterData(String afterData) {
        this.afterData = afterData;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public String getModule() {
        return module;
    }

    public String getAction() {
        return action;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getContent() {
        return content;
    }

    public String getIp() {
        return ip;
    }

    public Integer getStatus() {
        return status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getAfterData() {
        return afterData;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
