package com.admin.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限点校验（「模块:资源」命名，商品域统一 product:*，禁 goods:* —— plans/admin-backend.md 2.2.3）。
 * 缺权抛 1004（HTTP 403）。super_admin 角色绕过（2.2.2）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /** 所需权限码 */
    String value();
}
