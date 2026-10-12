package com.product.web;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.UserContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * core-product 入参 DTO（jakarta.validation 校验，失败自动 1001 —— DEV.md 4.2 步骤 2）。
 *
 * <p>字段来源：plans/admin-backend.md 3.2.1（商品 CRUD）、plans/android-backend.md 3.2.3
 * / plans/ios-backend.md 3.2.2（详情字段）、plans/admin-backend.md 3.2.2（类目 CRUD）。</p>
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    /** SKU 创建/编辑入参。price/marketPrice 会同步到 core-price（价格唯一归属），本服务仅冗余展示值。 */
    public record SkuInput(
            String skuCode,
            String title,
            Map<String, String> spec,
            String images,
            BigDecimal price,
            BigDecimal marketPrice,
            BigDecimal costPrice) {
    }

    /** 商品创建入参（P0：创建即草稿，skus 必填至少 1 个）。 */
    public record ProductInput(
            String name,
            String subtitle,
            Long categoryId,
            String mainImage,
            String images,
            String video,
            String detail,
            List<SkuInput> skus) {
    }

    /** 类目创建入参。 */
    public record CategoryCreateInput(String name, Long parentId, Integer sort) {
    }

    /** 类目更新入参。 */
    public record CategoryUpdateInput(String name, Integer sort) {
    }

    /** 类目状态变更入参：1-启用 0-禁用。 */
    public record CategoryStatusInput(Integer status) {
    }

    /** 类目排序批量入参。 */
    public record CategorySortItem(Long id, Integer sort) {
    }

    /** 审核驳回入参：reason 必填（admin-backend 3.3.2 规则 4）。 */
    public record AuditRejectInput(String reason) {
    }

    /** 退回修改入参：reason 必填（admin-backend 3.3.2 规则 5）。 */
    public record AuditReturnInput(String reason) {
    }

    /** 批量上下架入参（P0 同步小批量，单次 ≤500 —— 对齐 admin-backend 3.3.3 上限口径）。 */
    public record BatchShelfInput(List<Long> ids) {
    }

    // ---- 校验辅助（Controller 层显式调用，避免过度分层） ----

    public static void requireProductInput(ProductInput in) {
        if (in == null || isBlank(in.name()) || in.categoryId() == null || in.skus() == null || in.skus().isEmpty()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "name/categoryId/skus 必填且 skus 至少 1 个");
        }
        for (SkuInput sku : in.skus()) {
            if (sku.spec() == null || sku.spec().isEmpty()) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "SKU 规格 spec 必填");
            }
            if (sku.price() == null || sku.price().signum() <= 0) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "SKU price 必须 > 0");
            }
        }
    }

    public static String requireReason(String reason, String action) {
        if (isBlank(reason)) {
            throw new BizException(BizErrorCode.PARAM_ERROR, action + "原因必填");
        }
        return reason.trim();
    }

    public static void requireAdmin() {
        // P0 信任内网（DEV.md 4.5），但管理端接口仍校验网关注入的角色头
        // 契约对齐：网关注入的是 core-user 的 ADMIN，兼容历史 ROLE_ADMIN 写法（见 UserContext.isAdmin）
        if (!UserContext.isAdmin()) {
            throw new BizException(BizErrorCode.NO_PERMISSION);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
