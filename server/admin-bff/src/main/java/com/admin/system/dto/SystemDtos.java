package com.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 系统管理出入参（对齐 web-admin src/api/user.ts / src/api/system.ts 的字段命名）。 */
public final class SystemDtos {

    private SystemDtos() {
    }

    /* -------------------- 用户（账号本体在 core-user，经 internal API 代理） -------------------- */

    public record AdminUserVO(
            Long id,
            String username,
            String nickname,
            String deptName,
            List<String> roles,
            Integer status,
            Instant lastLoginAt,
            Instant createdAt) {
    }

    public record AdminUserCreateReq(
            @NotBlank @Size(max = 64) String username,
            @Size(max = 64) String nickname,
            Long deptId,
            List<String> roles,
            @NotBlank @Size(min = 6, max = 32) String password) {
    }

    public record AdminUserUpdateReq(
            @Size(max = 64) String nickname,
            @Size(max = 20) String mobile,
            @Size(min = 6, max = 32) String password,
            Integer status) {
    }

    public record UserRolesReq(@NotNull List<String> roles) {
    }

    /* -------------------- 角色 -------------------- */

    public record RoleVO(
            Long id,
            String code,
            String name,
            Integer dataScope,
            Integer status,
            Long userCount,
            String remark,
            Boolean builtin) {
    }

    public record RoleReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 64) String name,
            Integer dataScope,
            Integer status,
            @Size(max = 256) String remark) {
    }

    public record RolePermissionsReq(@NotNull List<String> permissions) {
    }

    /* -------------------- 权限点 -------------------- */

    /** 对齐 web-admin PermissionTreeNode（type=menu|permission） */
    public record PermTreeNodeVo(String key, String title, String type, List<PermTreeNodeVo> children) {
    }

    public record PermissionReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 64) String title,
            /** menu / permission */
            @NotBlank @Size(max = 16) String type,
            Long parentId,
            @Size(max = 128) String path,
            @Size(max = 64) String requiredPerm,
            Integer orderNum) {
    }

    /* -------------------- 部门 -------------------- */

    public record DeptVO(Long id, String name, Long parentId, Long userCount) {
    }

    public record DeptReq(@NotBlank @Size(max = 64) String name, Long parentId) {
    }

    /* -------------------- 字典（web-admin DictType / DictItem） -------------------- */

    public record DictTypeVO(Long id, String type, String name, String remark, Integer status) {
    }

    public record DictItemVO(Long id, String code, String label, Integer sort, Integer status) {
    }

    public record DictReq(
            @NotBlank @Size(max = 64) String dictType,
            @NotBlank @Size(max = 64) String dictValue,
            @NotBlank @Size(max = 64) String dictLabel,
            Integer sort,
            Integer status,
            @Size(max = 256) String remark) {
    }

    /* -------------------- 配置 -------------------- */

    public record ConfigVO(Long id, String key, String value, String remark) {
    }

    public record ConfigReq(
            @NotBlank @Size(max = 128) String key,
            @NotBlank @Size(max = 1024) String value,
            @Size(max = 256) String remark) {
    }
}
