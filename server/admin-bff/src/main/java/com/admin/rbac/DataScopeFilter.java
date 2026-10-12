package com.admin.rbac;

/**
 * 数据范围解析结果（P0 供列表/聚合接口拼查询条件；SQL 改写落点见 plans/admin-backend.md 2.3.2）。
 *
 * @param scope    实际生效范围
 * @param deptId   scope=2/3/5 时的部门约束（P0 仅支持 = 部门）
 * @param userId   scope=4 时的本人约束
 * @param storeId  scope=6 时的店铺约束（merchant 专有）
 */
public record DataScopeFilter(DataScope scope, Long deptId, Long userId, Long storeId) {

    public static DataScopeFilter of(DataScope scope, Long deptId, Long userId) {
        return new DataScopeFilter(scope, scope == DataScope.DEPT_ONLY ? deptId : null,
                scope == DataScope.SELF ? userId : null,
                scope == DataScope.STORE_ONLY ? userId : null);
    }

    /** 是否对查询施加了约束（false = 全量可见）。 */
    public boolean isRestricted() {
        return scope != DataScope.ALL;
    }
}
