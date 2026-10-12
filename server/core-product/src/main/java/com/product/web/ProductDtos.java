package com.product.web;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.UserContext;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * core-product 入参 DTO（jakarta.validation 校验，失败自动 1001 —— DEV.md 4.2 步骤 2）。
 *
 * <p>字段来源：plans/admin-backend.md 3.2.1（商品 CRUD）、plans/android-backend.md 3.2.3
 * / plans/ios-backend.md 3.2.2（详情字段）、plans/admin-backend.md 3.2.2（类目 CRUD）。</p>
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    /**
     * SKU 创建/编辑入参。price/marketPrice 会同步到 core-price（价格唯一归属），本服务仅冗余展示值。
     *
     * <p><b>编辑语义（P1-2：SKU 由「全量替换」改为「按 skuId 合并」，勿回退）</b>：</p>
     * <ul>
     *   <li>{@code skuId} 为空 → 新增行（服务端生成雪花 ID）；非空 → 按该 skuId <b>更新既有行</b>，
     *       <b>禁止按行序号匹配</b>（序号匹配会让前端调序/漏行时静默覆盖别人的 SKU）。</li>
     *   <li>{@code costPrice}/{@code images}/{@code title}/{@code skuCode} 等字段
     *       <b>未携带（null/空）则保留库中原值</b>，只有显式传值才更新；
     *       管理端详情对无 {@code cost:read} 角色的请求会把 costPrice 脱敏为 null，
     *       原「全量替换」语义会让这类角色的每次编辑都把成本价抹掉（资损 + 数据丢失）。</li>
     *   <li>请求的 {@code skus} 列表是<b>该商品的权威全量集合</b>：库中存在但请求未携带的 SKU 会被<b>软删除</b>
     *       （保留行数据供对账/售后追溯，不物理删以免订单/库存/价格出现悬空引用）。</li>
     * </ul>
     */
    public record SkuInput(
            Long skuId,
            String skuCode,
            String title,
            Map<String, String> spec,
            String images,
            BigDecimal price,
            BigDecimal marketPrice,
            BigDecimal costPrice) {

        /**
         * 创建场景便捷构造（无 skuId，服务端生成雪花 ID）。
         *
         * <p>保留 7 参构造以兼容既有调用点（编辑语义变更不应迫使无关代码改动）。</p>
         */
        public SkuInput(String skuCode, String title, Map<String, String> spec, String images,
                        BigDecimal price, BigDecimal marketPrice, BigDecimal costPrice) {
            this(null, skuCode, title, spec, images, price, marketPrice, costPrice);
        }
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
        Set<Long> seenSkuIds = new HashSet<>();
        for (SkuInput sku : in.skus()) {
            if (sku.spec() == null || sku.spec().isEmpty()) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "SKU 规格 spec 必填");
            }
            if (sku.price() == null || sku.price().signum() <= 0) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "SKU price 必须 > 0");
            }
            // 集合一致性：同一次请求内 skuId 不得重复（否则按 skuId 合并时后一行会静默覆盖前一行）
            if (sku.skuId() != null && !seenSkuIds.add(sku.skuId())) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "同一次请求内 skuId 重复: " + sku.skuId());
            }
            // 价格一致性：与 core-price 的 marketPrice ≥ price 约束保持同一口径/同一错误码（DEV.md 4.4 跨服务契约一致）
            if (sku.marketPrice() != null && sku.marketPrice().compareTo(sku.price()) < 0) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "marketPrice 不能低于 price");
            }
            // 价格非负：成本价缺失合法（缺省=未维护），但不允许负数
            if (sku.costPrice() != null && sku.costPrice().signum() < 0) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "costPrice 不能为负数");
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
