package com.product.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.client.PriceClient;
import com.product.client.PriceClient.SkuPriceView;
import com.product.domain.AuditStateMachine;
import com.product.domain.AuditStatus;
import com.product.domain.ShelfStatus;
import com.product.domain.Sku;
import com.product.domain.Spu;
import com.product.repo.CategoryRepository;
import com.product.repo.SkuRepository;
import com.product.repo.SpuRepository;
import com.product.web.ProductDtos;
import com.product.web.ProductDtos.ProductInput;
import com.product.web.ProductDtos.SkuInput;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品 SPU/SKU + 审核状态机 + 上下架 + 回收站（P0 简化）。
 *
 * <p>出处：plans/admin-backend.md 3.1 功能点 / 3.2.1 商品接口 / 3.3.2 商品审核流程；
 * 买家端契约对齐 plans/android-backend.md 3.1~3.2、plans/ios-backend.md 3.2.1/3.2.2。</p>
 *
 * <p>事件（DomainEventNames 权威注册表）：PRODUCT_CREATED / PRODUCT_UPDATED / PRODUCT_DELETED /
 * AUDIT_SUBMITTED / AUDIT_COMPLETED / AUDIT_RETURNED；发布遵循「本地事务提交后」
 * （P0 用 TransactionSynchronization，P1 迁本地消息表 —— DEV.md 4.4）。</p>
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private static final int BATCH_API_MAX_IDS = 500; // 批量接口单次上限（admin-backend 3.3.3）

    private final SpuRepository spuRepo;
    private final SkuRepository skuRepo;
    private final CategoryRepository categoryRepo;
    private final SnowflakeIdGenerator idGenerator;
    private final DomainEventPublisher eventPublisher;
    private final PriceClient priceClient;
    private final ObjectMapper objectMapper;

    public ProductService(SpuRepository spuRepo, SkuRepository skuRepo, CategoryRepository categoryRepo,
                          SnowflakeIdGenerator idGenerator, DomainEventPublisher eventPublisher,
                          PriceClient priceClient, ObjectMapper objectMapper) {
        this.spuRepo = spuRepo;
        this.skuRepo = skuRepo;
        this.categoryRepo = categoryRepo;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
        this.priceClient = priceClient;
        this.objectMapper = objectMapper;
    }

    // ==================== 管理端：查询 ====================

    /** 管理端分页（GET /api/admin/product/list）：keyword 模糊 + 类目/审核态/上下架筛选。 */
    @Transactional(readOnly = true)
    public PageResult<Spu> adminPage(String keyword, Long categoryId, Integer auditStatus,
                                     Integer shelfStatus, int pageNum, int pageSize) {
        int size = clamp(pageSize);
        Specification<Spu> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isFalse(root.get("deleted")));
            if (keyword != null && !keyword.isBlank()) {
                ps.add(cb.like(root.get("name"), "%" + escapeLike(keyword) + "%", '\\'));
            }
            if (categoryId != null) {
                ps.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (auditStatus != null) {
                ps.add(cb.equal(root.get("auditStatus"), auditStatus));
            }
            if (shelfStatus != null) {
                ps.add(cb.equal(root.get("shelfStatus"), shelfStatus));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Spu> page = spuRepo.findAll(spec,
                PageRequest.of(Math.max(pageNum - 1, 0), size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResult.of(page.getContent(), page.getTotalElements(), pageNum, size);
    }

    /** 管理端详情（GET /api/admin/product/{id}）：SPU + 全部 SKU（含 costPrice，脱敏在 Controller 判权）。 */
    @Transactional(readOnly = true)
    public ProductDetail adminDetail(Long id) {
        Spu spu = mustGet(id);
        return new ProductDetail(spu, skuRepo.findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(id));
    }

    // ==================== 管理端：CRUD ====================

    /** 创建商品（POST /api/admin/product）：P0 创建即草稿（auditStatus=draft），发布 product.created。 */
    @Transactional
    public Spu create(ProductInput in) {
        ProductDtos.requireProductInput(in);
        requireCategoryExists(in.categoryId());
        Spu spu = new Spu();
        spu.setId(idGenerator.nextId());
        spu.setSpuCode("spu-" + spu.getId());
        applySpuInput(spu, in);
        spu.setAuditStatus(AuditStatus.DRAFT.getValue());
        spu.setShelfStatus(ShelfStatus.OFF_SHELF.getValue());
        spu = spuRepo.save(spu);
        saveSkus(spu, in.skus());
        syncPricesToPriceService(spu, in.skus());
        publishAfterCommit(DomainEventNames.PRODUCT_CREATED,
                Map.of("spuId", spu.getId(), "categoryId", spu.getCategoryId(), "changeType", "create"));
        return spu;
    }

    /** 编辑商品（PUT /api/admin/product/{id}）：仅 draft/rejected 可编辑（admin-backend 3.3.2 流转图）。 */
    @Transactional
    public Spu update(Long id, ProductInput in) {
        Spu spu = mustGet(id);
        Integer audit = spu.getAuditStatus();
        if (audit != AuditStatus.DRAFT.getValue() && audit != AuditStatus.REJECTED.getValue()) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "仅草稿/审核拒绝状态可编辑，当前: " + AuditStatus.of(audit));
        }
        ProductDtos.requireProductInput(in);
        requireCategoryExists(in.categoryId());
        applySpuInput(spu, in);
        spu = spuRepo.save(spu);
        // P0 简化：SKU 全量替换（先删后插；价格随新 SKU 重新同步到 core-price）
        skuRepo.deleteBySpuId(id);
        skuRepo.flush();
        saveSkus(spu, in.skus());
        syncPricesToPriceService(spu, in.skus());
        publishAfterCommit(DomainEventNames.PRODUCT_UPDATED,
                Map.of("spuId", spu.getId(), "changeType", "update"));
        return spu;
    }

    /** 软删除进回收站（DELETE /api/admin/product/{id}）：审核中禁止删除（admin-backend 3.3.2 并发口径）。 */
    @Transactional
    public void softDelete(Long id) {
        Spu spu = mustGet(id);
        if (spu.getAuditStatus() == AuditStatus.AUDITING.getValue()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "审核中商品禁止删除");
        }
        spu.setDeleted(true);
        spu.setShelfStatus(ShelfStatus.OFF_SHELF.getValue());
        spuRepo.save(spu);
        publishAfterCommit(DomainEventNames.PRODUCT_DELETED, Map.of("spuId", id));
    }

    /** 回收站恢复（POST /api/admin/product/{id}/restore）：恢复后强制下架，需重新上架。 */
    @Transactional
    public Spu restore(Long id) {
        Spu spu = spuRepo.findById(id)
                .filter(Spu::isDeleted)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "商品不在回收站: " + id));
        spu.setDeleted(false);
        spu.setShelfStatus(ShelfStatus.OFF_SHELF.getValue());
        return spuRepo.save(spu);
    }

    /** 彻底删除（DELETE /api/admin/product/{id}/permanent）：SPU + SKU 物理删除。 */
    @Transactional
    public void permanentDelete(Long id) {
        Spu spu = spuRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.PRODUCT_NOT_FOUND));
        skuRepo.deleteBySpuId(id);
        skuRepo.flush();
        spuRepo.delete(spu);
        publishAfterCommit(DomainEventNames.PRODUCT_DELETED, Map.of("spuId", id, "changeType", "permanent"));
    }

    // ==================== 管理端：审核状态机 ====================

    /** 提交审核（POST /api/admin/product/{id}/submit-audit）：draft/rejected → auditing，发 audit.submitted。 */
    @Transactional
    public Spu submitAudit(Long id) {
        Spu spu = mustGet(id);
        AuditStateMachine.validate(spu.getAuditStatus(), AuditStatus.AUDITING.getValue());
        spu.setAuditStatus(AuditStatus.AUDITING.getValue());
        spu.setAuditRemark(null);
        Spu saved = saveWithConcurrencyRetry(spu);
        publishAfterCommit(DomainEventNames.AUDIT_SUBMITTED,
                Map.of("spuId", id, "bizType", 1, "operator", currentUserOrSystem()));
        return saved;
    }

    /** 审核通过（POST /api/admin/product/{id}/approve）：auditing → on_shelf + 自动上架（3.3.2 规则 3）。 */
    @Transactional
    public Spu approve(Long id) {
        Spu spu = mustGet(id);
        AuditStateMachine.validate(spu.getAuditStatus(), AuditStatus.ON_SHELF.getValue());
        spu.setAuditStatus(AuditStatus.ON_SHELF.getValue());
        spu.setShelfStatus(ShelfStatus.ON_SHELF.getValue()); // 通过自动上架
        Spu saved = saveWithConcurrencyRetry(spu);
        setSkuStatus(id, ShelfStatus.ON_SHELF.getValue());
        // 说明：plans 3.3.2 提到的 product.on_shelf 事件未在 DomainEventNames 注册表登记，
        // P0 以 product.updated(changeType=audit_approved, shelfStatus=1) 承载；登记新事件见 04-messaging §7.1
        publishAfterCommit(DomainEventNames.PRODUCT_UPDATED,
                Map.of("spuId", id, "changeType", "audit_approved", "shelfStatus", ShelfStatus.ON_SHELF.getValue()));
        publishAfterCommit(DomainEventNames.AUDIT_COMPLETED,
                Map.of("spuId", id, "bizType", 1, "result", "approved"));
        return saved;
    }

    /** 审核驳回（POST /api/admin/product/{id}/reject）：auditing → rejected，reason 必填（3.3.2 规则 4）。 */
    @Transactional
    public Spu reject(Long id, String reason) {
        String r = ProductDtos.requireReason(reason, "驳回");
        Spu spu = mustGet(id);
        AuditStateMachine.validate(spu.getAuditStatus(), AuditStatus.REJECTED.getValue());
        spu.setAuditStatus(AuditStatus.REJECTED.getValue());
        spu.setAuditRemark(r);
        Spu saved = saveWithConcurrencyRetry(spu);
        publishAfterCommit(DomainEventNames.AUDIT_COMPLETED,
                Map.of("spuId", id, "bizType", 1, "result", "rejected", "reason", r));
        return saved;
    }

    /** 退回修改（POST /api/admin/product/{id}/return）：auditing → draft，带意见（3.3.2 规则 5）。 */
    @Transactional
    public Spu returnToDraft(Long id, String reason) {
        String r = ProductDtos.requireReason(reason, "退回");
        Spu spu = mustGet(id);
        AuditStateMachine.validate(spu.getAuditStatus(), AuditStatus.DRAFT.getValue());
        spu.setAuditStatus(AuditStatus.DRAFT.getValue());
        spu.setAuditRemark(r);
        Spu saved = saveWithConcurrencyRetry(spu);
        publishAfterCommit(DomainEventNames.AUDIT_RETURNED,
                Map.of("spuId", id, "bizType", 1, "reason", r));
        return saved;
    }

    // ==================== 管理端：上下架 ====================

    /** 上架（POST /api/admin/product/{id}/on-shelf）：前置条件 auditStatus=2（3.3.2 状态映射表）。 */
    @Transactional
    public Spu onShelf(Long id) {
        Spu spu = mustGet(id);
        if (spu.getAuditStatus() != AuditStatus.ON_SHELF.getValue()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "商品未通过审核，禁止上架");
        }
        spu.setShelfStatus(ShelfStatus.ON_SHELF.getValue());
        Spu saved = spuRepo.save(spu);
        setSkuStatus(id, ShelfStatus.ON_SHELF.getValue());
        publishAfterCommit(DomainEventNames.PRODUCT_UPDATED,
                Map.of("spuId", id, "changeType", "on_shelf", "shelfStatus", ShelfStatus.ON_SHELF.getValue()));
        return saved;
    }

    /** 下架（POST /api/admin/product/{id}/off-shelf）：不回退审核状态（3.3.2 状态映射表）。 */
    @Transactional
    public Spu offShelf(Long id) {
        Spu spu = mustGet(id);
        spu.setShelfStatus(ShelfStatus.OFF_SHELF.getValue());
        Spu saved = spuRepo.save(spu);
        setSkuStatus(id, ShelfStatus.OFF_SHELF.getValue());
        publishAfterCommit(DomainEventNames.PRODUCT_UPDATED,
                Map.of("spuId", id, "changeType", "off_shelf", "shelfStatus", ShelfStatus.OFF_SHELF.getValue()));
        return saved;
    }

    /** 批量上下架（P0 同步小批量 ≤500；P1 按 admin-backend 3.3.3 改异步任务 + taskId 轮询）。 */
    @Transactional
    public Map<String, Object> batchShelf(List<Long> ids, boolean on) {
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_API_MAX_IDS) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "批量操作单次上限" + BATCH_API_MAX_IDS + "条，请分批提交");
        }
        List<Long> success = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                if (on) {
                    onShelf(id);
                } else {
                    offShelf(id);
                }
                success.add(id);
            } catch (BizException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", e.getMsg());
                failed.add(f);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        return result;
    }

    // ==================== 买家端 ====================

    /**
     * 买家列表（GET /api/v1/products，兼容 GET /api/v1/products/category/{categoryId}）：
     * 参数契约对齐 plans/ios-backend.md 3.2.1，卡片字段对齐 plans/android-backend.md 3.1.3。
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> buyerList(Long categoryId, String keyword, String sort,
                                                     BigDecimal priceMin, BigDecimal priceMax,
                                                     int pageNum, int pageSize) {
        int size = clamp(pageSize);
        Sort order = switch (sort == null || sort.isBlank() ? "default" : sort) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "minPrice").and(Sort.by(Sort.Direction.ASC, "id"));
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "minPrice").and(Sort.by(Sort.Direction.ASC, "id"));
            case "sales" -> Sort.by(Sort.Direction.DESC, "salesCount").and(Sort.by(Sort.Direction.ASC, "id"));
            case "newest" -> Sort.by(Sort.Direction.DESC, "id");
            default -> Sort.by(Sort.Direction.DESC, "salesCount").and(Sort.by(Sort.Direction.ASC, "id")); // 综合
        };
        Specification<Spu> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isFalse(root.get("deleted")));
            ps.add(cb.equal(root.get("shelfStatus"), ShelfStatus.ON_SHELF.getValue()));
            if (categoryId != null) {
                ps.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (keyword != null && !keyword.isBlank()) {
                ps.add(cb.like(root.get("name"), "%" + escapeLike(keyword) + "%", '\\'));
            }
            if (priceMin != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("minPrice"), priceMin));
            }
            if (priceMax != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("minPrice"), priceMax));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Spu> page = spuRepo.findAll(spec, PageRequest.of(Math.max(pageNum - 1, 0), size, order));
        Map<Long, Long> skuCounts = countSkuPerSpu(page.getContent());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Spu spu : page.getContent()) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("spuId", spu.getId());
            card.put("spuCode", spu.getSpuCode());
            card.put("name", spu.getName());
            card.put("subtitle", spu.getSubtitle());
            card.put("mainImage", spu.getMainImage());
            card.put("price", spu.getMinPrice());        // 冗余价（SKU 现价以 core-price 为准，见 README）
            card.put("originalPrice", spu.getMarketPrice());
            card.put("salesCount", spu.getSalesCount());
            card.put("skuCount", skuCounts.getOrDefault(spu.getId(), 0L));
            list.add(card);
        }
        return PageResult.of(list, page.getTotalElements(), pageNum, size);
    }

    /**
     * 买家详情（GET /api/v1/products/{spuId}）：SPU + SKU 列表 + 规格聚合；
     * 字段名 camelCase（plans/android-backend.md 3.2.3 口径；ios 侧 snake_case 由 BFF 映射 —— DEV.md 3 响应信封差异同源）。
     * SKU 现价回填自 core-price（容错降级，价格服务不可用时为 null）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buyerDetail(Long spuId) {
        Spu spu = spuRepo.findById(spuId).filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BizException(BizErrorCode.PRODUCT_NOT_FOUND));
        if (spu.getShelfStatus() != ShelfStatus.ON_SHELF.getValue()) {
            throw new BizException(BizErrorCode.PRODUCT_OFF_SHELF);
        }
        List<Sku> skus = skuRepo.findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(spuId);
        Map<String, Object> out = new ProductDetail(spu, skus).toBuyerMap();
        // SKU 现价回填：core-price 是价格唯一归属（P0 冗余价只用于兜底展示）
        Map<Long, BigDecimal> prices = priceClient.fetchSkuPrices(skus.stream().map(Sku::getId).toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skuViews = (List<Map<String, Object>>) out.get("skus");
        for (Map<String, Object> skuView : skuViews) {
            skuView.put("price", prices.get(((Number) skuView.get("skuId")).longValue()));
        }
        return out;
    }

    // ==================== 服务间内部契约 ====================

    /** 批量 SKU 视图（GET /api/v1/internal/skus?ids=，下单/购物车链路用，plans 未明确时的 P0 契约）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> internalSkuViews(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Sku sku : skuRepo.findByIdInAndDeletedFalse(ids)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", sku.getId());
            m.put("spuId", sku.getSpuId());
            // storeId：P0 单店铺（自营），固定 0；plans 店铺维度 P1 接入后由 SPU/店铺提供。
            // core-order 按 store_id 拆单、core-cart 展示均依赖该字段（集成契约对齐）。
            m.put("storeId", 0L);
            m.put("title", sku.getTitle());
            m.put("spec", sku.getSpec()); // JSON 字符串，如 {"颜色":"黑","容量":"256GB"}
            m.put("images", sku.getImages());
            m.put("status", sku.getStatus());
            // 消费方（core-order/core-cart 的 SkuView）读取的别名字段：name/image/onShelf
            m.put("name", sku.getTitle());
            m.put("image", sku.getImages());
            m.put("onShelf", Integer.valueOf(1).equals(sku.getStatus())); // 1=上架，随 SPU 上下架同步
            out.add(m);
        }
        return out;
    }

    /** 冗余同步视图分页（GET /api/v1/internal/products，core-search 启动/定时拉取用）。 */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> internalProducts(int pageNum, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), 500);
        Page<Spu> page = spuRepo.findAll(PageRequest.of(Math.max(pageNum - 1, 0), size,
                Sort.by(Sort.Direction.DESC, "id")));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Spu spu : page.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("spuId", spu.getId());
            m.put("name", spu.getName());
            m.put("mainImage", spu.getMainImage());
            m.put("categoryId", spu.getCategoryId());
            m.put("minPrice", spu.getMinPrice());
            m.put("marketPrice", spu.getMarketPrice());
            m.put("salesCount", spu.getSalesCount());
            m.put("shelfStatus", spu.getShelfStatus()); // core-search 只索引 shelfStatus=1
            list.add(m);
        }
        return PageResult.of(list, page.getTotalElements(), pageNum, size);
    }

    // ==================== 内部工具 ====================

    private void requireCategoryExists(Long categoryId) {
        if (!categoryRepo.findById(categoryId).map(c -> !c.isDeleted()).orElse(false)) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "类目不存在: " + categoryId);
        }
    }

    private void applySpuInput(Spu spu, ProductInput in) {
        spu.setName(in.name().trim());
        spu.setSubtitle(in.subtitle());
        spu.setCategoryId(in.categoryId());
        spu.setMainImage(in.mainImage());
        spu.setImages(in.images());
        spu.setVideo(in.video());
        spu.setDetail(in.detail());
        // 冗余价：minPrice = min(sku.price)；marketPrice = max(sku.marketPrice ?: price)
        BigDecimal min = null;
        BigDecimal market = null;
        for (SkuInput s : in.skus()) {
            min = (min == null || s.price().compareTo(min) < 0) ? s.price() : min;
            BigDecimal mk = s.marketPrice() == null ? s.price() : s.marketPrice();
            market = (market == null || mk.compareTo(market) > 0) ? mk : market;
        }
        spu.setMinPrice(min);
        spu.setMarketPrice(market);
    }

    private void saveSkus(Spu spu, List<SkuInput> inputs) {
        for (SkuInput in : inputs) {
            Sku sku = new Sku();
            sku.setId(idGenerator.nextId());
            sku.setSpuId(spu.getId());
            sku.setSkuCode(in.skuCode() == null || in.skuCode().isBlank()
                    ? "sku-" + idGenerator.nextId() : in.skuCode().trim());
            sku.setTitle(in.title() == null || in.title().isBlank()
                    ? spu.getName() + " " + summarizeSpec(in.spec()) : in.title().trim());
            sku.setSpec(toJson(in.spec()));
            sku.setImages(in.images() != null ? in.images() : spu.getMainImage());
            sku.setCostPrice(in.costPrice());
            sku.setStatus(spu.getShelfStatus()); // 新建 SKU 随 SPU 当前上下架状态
            skuRepo.save(sku);
        }
    }

    private void setSkuStatus(Long spuId, int status) {
        for (Sku sku : skuRepo.findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(spuId)) {
            sku.setStatus(status);
            skuRepo.save(sku);
        }
    }

    /** 价格同步到 core-price（内部批量写价契约；容错降级，见 PriceClient）。 */
    private void syncPricesToPriceService(Spu spu, List<SkuInput> inputs) {
        List<Sku> saved = skuRepo.findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(spu.getId());
        List<SkuPriceView> views = new ArrayList<>();
        // saveSkus 与输入按序一一对应；优先按 skuCode 匹配（乱序防御）
        Map<String, SkuInput> byCode = new HashMap<>();
        for (SkuInput in : inputs) {
            if (in.skuCode() != null && !in.skuCode().isBlank()) {
                byCode.put(in.skuCode().trim(), in);
            }
        }
        for (int i = 0; i < saved.size(); i++) {
            Sku sku = saved.get(i);
            SkuInput in = i < inputs.size() ? byCode.getOrDefault(sku.getSkuCode(), inputs.get(i)) : null;
            if (in != null) {
                views.add(new SkuPriceView(sku.getId(), in.price(),
                        in.marketPrice() == null ? in.price() : in.marketPrice()));
            }
        }
        priceClient.syncSkuPrices(views);
    }

    /** P0 简化逐 spu 计数（数据量小；P1 改 group by 一次查）。 */
    private Map<Long, Long> countSkuPerSpu(List<Spu> spus) {
        Map<Long, Long> m = new HashMap<>();
        for (Spu spu : spus) {
            m.put(spu.getId(), (long) skuRepo.findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(spu.getId()).size());
        }
        return m;
    }

    private Spu mustGet(Long id) {
        return spuRepo.findById(id).filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BizException(BizErrorCode.PRODUCT_NOT_FOUND));
    }

    /** 乐观锁冲突按业务错误码处理（admin-backend 3.3.2 规则 6：并发审核只有一人成功）。 */
    private Spu saveWithConcurrencyRetry(Spu spu) {
        try {
            return spuRepo.saveAndFlush(spu);
        } catch (OptimisticLockingFailureException e) {
            log.warn("spu optimistic lock conflict: id={}", spu.getId());
            throw new BizException(BizErrorCode.IDEMPOTENT_CONFLICT, "该商品已被处理，请刷新后重试");
        }
    }

    /** 事件在本地事务提交后再发（P0 无本地消息表，用事务同步器近似；P1 迁消息表 —— DEV.md 4.4）。 */
    private void publishAfterCommit(String eventName, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publish(eventName, payload);
                }
            });
        } else {
            eventPublisher.publish(eventName, payload);
        }
    }

    private String currentUserOrSystem() {
        Long uid = UserContext.userId();
        return uid == null ? "system" : String.valueOf(uid);
    }

    private String toJson(Map<String, String> spec) {
        try {
            return objectMapper.writeValueAsString(spec == null ? Map.of() : spec);
        } catch (Exception e) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "spec 序列化失败");
        }
    }

    /** 解析 SKU 规格 JSON（内部契约 spec 字段为 JSON 字符串）。 */
    public static Map<String, String> parseSpec(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String summarizeSpec(Map<String, String> spec) {
        if (spec == null || spec.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        spec.values().forEach(v -> sb.append(' ').append(v));
        return sb.toString().trim();
    }

    /** LIKE 通配符转义（配合 JPA 参数绑定防注入；core-search 同款处理）。 */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static int clamp(int pageSize) {
        // 分页口径：pageSize 默认 20、上限 200（plans/admin-backend.md 接口通用规范）
        return Math.min(Math.max(pageSize, 1), 200);
    }

    /**
     * 详情聚合（SPU + SKU + 规格组）。
     * 管理端视图含 costPrice（Controller 层按 cost:read 判权脱敏，plans/admin-backend.md 2.2.3）；
     * 买家视图仅上架 SKU 的可售信息，SKU 现价由调用方从 core-price 回填。
     */
    public record ProductDetail(Spu spu, List<Sku> skus) {

        /** 买家详情视图（camelCase 字段，plans/android-backend.md 3.2.3 口径）。 */
        public Map<String, Object> toBuyerMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("spuId", spu.getId());
            out.put("spuCode", spu.getSpuCode());
            out.put("name", spu.getName());
            out.put("subtitle", spu.getSubtitle());
            out.put("mainImage", spu.getMainImage());
            out.put("images", splitImages(spu.getImages()));
            out.put("video", spu.getVideo());
            out.put("detail", spu.getDetail());
            out.put("price", spu.getMinPrice());       // 兜底价（SKU 现价以 core-price 为准）
            out.put("originalPrice", spu.getMarketPrice());
            out.put("salesCount", spu.getSalesCount());
            out.put("specs", specGroups());
            List<Map<String, Object>> skuViews = new ArrayList<>();
            for (Sku sku : skus) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("skuId", sku.getId());
                m.put("skuCode", sku.getSkuCode());
                m.put("title", sku.getTitle());
                m.put("specs", parseSpec(sku.getSpec()));
                m.put("images", splitImages(sku.getImages()));
                m.put("status", sku.getStatus());
                skuViews.add(m);
            }
            out.put("skus", skuViews);
            return out;
        }

        /** 管理端详情视图（含 costPrice + 审核字段；Controller 层对 costPrice 判权脱敏）。 */
        public Map<String, Object> toAdminMap() {
            Map<String, Object> out = toBuyerMap();
            out.put("categoryId", spu.getCategoryId());
            out.put("auditStatus", spu.getAuditStatus());
            out.put("shelfStatus", spu.getShelfStatus());
            out.put("auditRemark", spu.getAuditRemark());
            out.put("deleted", spu.isDeleted());
            out.put("createdAt", spu.getCreatedAt());
            out.put("updatedAt", spu.getUpdatedAt());
            List<Map<String, Object>> skuViews = new ArrayList<>();
            for (Sku sku : skus) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("skuId", sku.getId());
                m.put("skuCode", sku.getSkuCode());
                m.put("title", sku.getTitle());
                m.put("specs", parseSpec(sku.getSpec()));
                m.put("images", splitImages(sku.getImages()));
                m.put("status", sku.getStatus());
                m.put("costPrice", sku.getCostPrice()); // 敏感字段：Controller 判 cost:read 后脱敏
                skuViews.add(m);
            }
            out.put("skus", skuViews);
            return out;
        }

        /** 规格组聚合：遍历 SKU spec 合并为 [{name, values:[...]}]（android 3.2.3 / ios 3.2.2 spec_groups）。 */
        List<Map<String, Object>> specGroups() {
            Map<String, Set<String>> grouped = new LinkedHashMap<>();
            for (Sku sku : skus) {
                for (Map.Entry<String, String> e : parseSpec(sku.getSpec()).entrySet()) {
                    grouped.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue());
                }
            }
            List<Map<String, Object>> groups = new ArrayList<>();
            grouped.forEach((name, values) -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("name", name);
                g.put("values", new ArrayList<>(values));
                groups.add(g);
            });
            return groups;
        }

        static List<String> splitImages(String images) {
            if (images == null || images.isBlank()) {
                return List.of();
            }
            return List.of(images.split(","));
        }
    }
}
