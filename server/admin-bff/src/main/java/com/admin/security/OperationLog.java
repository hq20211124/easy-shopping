package com.admin.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志标记（plans/admin-backend.md 2.4；AOP 落 admin_operation_log）。
 * 写接口（增删改/审核/授权）必须标注；密码等敏感字段不入日志。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /** 所属模块，如 system / product / order */
    String module();

    /** 动作名，如 创建角色 */
    String action();
}
