package com.price.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.price.client.CouponPreviewClient;
import com.price.domain.SkuPrice;
import com.price.repo.SkuPriceRepository;
import com.price.web.PriceDtos.CalculateInput;
import com.price.web.PriceDtos.CalculateItemInput;
import com.price.web.PriceDtos.InternalPriceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 价格查询/试算 —— 计价口径出处 plans/admin-backend.md 7.3：
 * 售价 = 商品价 - 商品级优惠 - 优惠券（P0 无商品级优惠，优惠券归 core-promotion 核销、本服务仅 preview）。
 *
 * <p>金额全部 BigDecimal + 2 位小数 HALF_UP（DEV.md 4.3 禁 Double）；
 * 价格变更发 product.updated(changeType=price) 事件（P1 建议登记专用 price.changed，见模块 README）。</p>
 */
@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    private static final int PRICE_SCALE = 2;

    private final SkuPriceRepository repo;
    private final SnowflakeIdGenerator idGenerator;
    private final DomainEventPublisher eventPublisher;
    private final CouponPreviewClient couponPreviewClient;

    public PriceService(SkuPriceRepository repo, SnowflakeIdGenerator idGenerator,
                        DomainEventPublisher eventPublisher, CouponPreviewClient couponPreviewClient) {
        this.repo = repo;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
        this.couponPreviewClient = couponPreviewClient;
    }

    /** 单 SKU 改价（PUT /api/admin/price/skus/{skuId}）：upsert + 发价格变更事件。 */
    @Transactional
    public SkuPrice upsert(Long skuId, BigDecimal price, BigDecimal marketPrice) {
        BigDecimal normalized = normalizePrice(price, "price");
        BigDecimal market = marketPrice == null ? normalized : normalizePrice(marketPrice, "marketPrice");
        if (market.compareTo(normalized) < 0) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "marketPrice 不能低于 price");
        }
        SkuPrice entity = repo.findBySkuId(skuId).orElseGet(() -> {
            SkuPrice e = new SkuPrice();
            e.setId(idGenerator.nextId());
            e.setSkuId(skuId);
            return e;
        });
        entity.setPrice(normalized);
        entity.setMarketPrice(market);
        SkuPrice saved = repo.save(entity);
        publishPriceChanged(List.of(skuId));
        return saved;
    }

    /** 批量写价（内部契约 POST /api/v1/internal/price/skus，core-product 商品创建/编辑时调用）。 */
    @Transactional
    public void upsertBatch(List<InternalPriceItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // 逐条复用 upsert（含校验与价格变更事件发布），事务内批量提交
        for (InternalPriceItem item : items) {
            upsert(item.skuId(), item.price(), item.marketPrice());
        }
    }

    /** 批量查价（GET /api/v1/price/skus?ids=）：按请求顺序返回，缺失的 skuId 不返回。 */
    @Transactional(readOnly = true)
    public List<PriceView> listBySkuIds(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        Map<Long, SkuPrice> bySkuId = new LinkedHashMap<>();
        for (SkuPrice p : repo.findBySkuIdIn(skuIds)) {
            bySkuId.put(p.getSkuId(), p);
        }
        List<PriceView> out = new ArrayList<>();
        for (Long skuId : skuIds) {
            SkuPrice p = bySkuId.get(skuId);
            if (p != null) {
                out.add(new PriceView(p.getSkuId(), p.getPrice(), p.getMarketPrice()));
            }
        }
        return out;
    }

    /**
     * 下单试算（POST /api/v1/price/calculate）：
     * 小计 = 单价 × 数量（2 位 HALF_UP）；应付 = Σ小计 - 优惠券抵扣（不小于 0）。
     */
    @Transactional(readOnly = true)
    public CalculateResponse calculate(CalculateInput in) {
        if (in.items() == null || in.items().isEmpty()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "items 必填且不能为空");
        }
        List<CalculateItemView> itemViews = new ArrayList<>();
        Map<Long, BigDecimal> amounts = new LinkedHashMap<>();
        BigDecimal total = zero();
        int totalQuantity = 0;
        for (CalculateItemInput item : in.items()) {
            if (item.quantity() == null || item.quantity() < 1 || item.quantity() > 999) {
                throw new BizException(BizErrorCode.PARAM_ERROR,
                        "quantity 必须在 1~999 之间: skuId=" + item.skuId());
            }
            SkuPrice price = repo.findBySkuId(item.skuId())
                    .orElseThrow(() -> new BizException(BizErrorCode.PRODUCT_NOT_FOUND,
                            "SKU 价格不存在: " + item.skuId()));
            BigDecimal subtotal = price.getPrice()
                    .multiply(BigDecimal.valueOf(item.quantity()))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            itemViews.add(new CalculateItemView(item.skuId(), item.quantity(),
                    price.getPrice(), price.getMarketPrice(), subtotal));
            amounts.put(item.skuId(), subtotal);
            total = total.add(subtotal);
            totalQuantity += item.quantity();
        }
        // 优惠券：核销逻辑在 core-promotion（只调不查库）；未就绪/失败降级 discount=0
        BigDecimal discount = zero();
        String note = null;
        if (in.couponId() != null) {
            discount = couponPreviewClient.previewCouponDiscount(in.couponId(), amounts)
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            if (!couponPreviewClient.enabled()) {
                note = couponPreviewClient.notConfiguredNote();
            }
            if (discount.compareTo(total) > 0) {
                discount = total; // 抵扣不超过应付（防负数应付）
            }
        }
        CouponView coupon = new CouponView(in.couponId(), discount, discount.signum() > 0, note);
        return new CalculateResponse(itemViews, totalQuantity,
                total.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                discount, total.subtract(discount).setScale(PRICE_SCALE, RoundingMode.HALF_UP), coupon);
    }

    // ---- 内部工具 ----

    private BigDecimal normalizePrice(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new BizException(BizErrorCode.PARAM_ERROR, field + " 必须 > 0");
        }
        return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** 价格变更事件：P0 借用已注册的 product.updated（changeType=price）；专用 price.changed 待 04-messaging 登记。 */
    private void publishPriceChanged(List<Long> skuIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changeType", "price");
        payload.put("skuIds", skuIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publish(DomainEventNames.PRODUCT_UPDATED, payload);
                }
            });
        } else {
            eventPublisher.publish(DomainEventNames.PRODUCT_UPDATED, payload);
        }
    }

    private static BigDecimal zero() {
        return BigDecimal.valueOf(0).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    // ---- 响应视图 ----

    /** GET /api/v1/price/skus 响应元素。 */
    public record PriceView(Long skuId, BigDecimal price, BigDecimal marketPrice) {
    }

    /** 试算条目：小计 = unitPrice × quantity。 */
    public record CalculateItemView(Long skuId, Integer quantity, BigDecimal unitPrice,
                                    BigDecimal marketPrice, BigDecimal subtotal) {
    }

    /** 优惠券视图：applied=false 时 discount 恒 0（P0 未接入/不可用降级）。 */
    public record CouponView(Long couponId, BigDecimal discount, boolean applied, String note) {
    }

    /** 试算响应：payableAmount = totalAmount - discountAmount。 */
    public record CalculateResponse(List<CalculateItemView> items, Integer totalQuantity,
                                    BigDecimal totalAmount, BigDecimal discountAmount,
                                    BigDecimal payableAmount, CouponView coupon) {
    }
}
