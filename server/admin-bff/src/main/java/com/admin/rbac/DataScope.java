package com.admin.rbac;

/**
 * 数据权限五档 + 商家档（plans/admin-backend.md 2.3.1）。
 *
 * <p>P0 实际生效三档：1-全部 / 2-本部门 / 6-本店铺（merchant）；
 * 3-本部门及下级、4-仅本人、5-自定义 存表可用但查询改写留 TODO（勿用"一个方法包打天下"，
 * 按 plans 2.3.1 以 SQL 注解切面 + 各仓储显式拼装为准）。</p>
 */
public enum DataScope {

    ALL(1, "全部数据"),
    DEPT_ONLY(2, "本部门"),
    DEPT_AND_CHILD(3, "本部门及下级"),
    SELF(4, "仅本人"),
    CUSTOM(5, "自定义部门"),
    STORE_ONLY(6, "本店铺（merchant）");

    private final int code;
    private final String desc;

    DataScope(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int code() {
        return code;
    }

    public String desc() {
        return desc;
    }

    public static DataScope of(int code) {
        for (DataScope scope : values()) {
            if (scope.code == code) {
                return scope;
            }
        }
        throw new IllegalArgumentException("未知数据范围: " + code);
    }
}
