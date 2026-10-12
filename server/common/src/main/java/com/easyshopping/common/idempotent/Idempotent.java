package com.easyshopping.common.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 写接口幂等声明：拦截 {@code Idempotency-Key}（兼容 {@code X-Idempotency-Key}）请求头，
 * 同一 Key 在 TTL 内重复请求返回 1005 冲突。
 *
 * <p>所有写接口（POST/PUT/DELETE）必须携带幂等键，见 plans/admin-backend.md 10.1.1。
 * P0 为进程内内存去重（单实例有效）；生产多实例必须换 Redis SETNX + TTL，见 {@link IdempotentInterceptor}。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 幂等键请求头名。 */
    String keyHeader() default "Idempotency-Key";

    /** 去重窗口（秒）。 */
    long ttlSeconds() default 600;
}
