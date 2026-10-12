package com.easyshopping.common.api;

import java.util.List;

/**
 * 统一分页信封：{@code {total, pageNum, pageSize, list}}。
 * 分页请求参数统一 {@code pageNum}（从 1 起）/ {@code pageSize}（≤200，见 plans/admin-backend.md 接口通用规范）。
 */
public record PageResult<T>(long total, int pageNum, int pageSize, List<T> list) {

    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        return new PageResult<>(total, pageNum, pageSize, list);
    }

    public static <T> PageResult<T> empty(int pageNum, int pageSize) {
        return new PageResult<>(0, pageNum, pageSize, List.of());
    }
}
