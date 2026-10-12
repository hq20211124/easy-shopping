package com.admin.system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 部门（数据范围 2/3/5 的挂靠维度；P0 非分片小表用自增，P1 迁移改雪花）。 */
@Entity
@Table(name = "admin_dept",
        uniqueConstraints = @UniqueConstraint(name = "uk_dept_name", columnNames = "name"))
public class AdminDept {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "parent_id")
    private Long parentId;

    protected AdminDept() {
    }

    public AdminDept(String name, Long parentId) {
        this.name = name;
        this.parentId = parentId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }
}
