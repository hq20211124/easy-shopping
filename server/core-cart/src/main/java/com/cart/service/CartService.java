package com.cart.service;

import com.cart.client.InventoryClient;
import com.cart.client.PriceClient;
import com.cart.client.ProductClient;
import com.cart.client.dto.ClientDtos.PriceView;
import com.cart.client.dto.ClientDtos.SkuView;
import com.cart.client.dto.ClientDtos.StockView;
import com.cart.domain.CartItem;
import com.cart.dto.CartDtos.AddRequest;
import com.cart.dto.CartDtos.AddResponse;
import com.cart.dto.CartDtos.CartItemVO;
import com.cart.dto.CartDtos.CartListResponse;
import com.cart.dto.CartDtos.RemoveRequest;
import com.cart.dto.CartDtos.RemoveResponse;
import com.cart.dto.CartDtos.SelectRequest;
import com.cart.dto.CartDtos.SelectResponse;
import com.cart.dto.CartDtos.UpdateRequest;
import com.cart.dto.CartDtos.UpdateResponse;
import com.cart.repo.CartItemRepository;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 购物车服务（plans/android-backend.md 第七章 + 16.4 P0 清单；plans/ios-backend.md 4.2.1）。
 *
 * <ul>
 *   <li><b>cartItemId 定位行</b>：加购合并、修改、删除、勾选全部以 cartItemId（或本人 userId 过滤）为键，
 *       跨用户 IDOR 一律 3004；</li>
 *   <li><b>加购合并 + 唯一键兜底</b>：同 SKU 复用一行累加（{@code uk_user_sku}），并发重复加购时
 *       捕获唯一键冲突转为累加 → 幂等重放 0 重复行；</li>
 *   <li><b>失效标记</b>：列表按 core-product 状态回写下架/删除行 {@code invalid=true}（可恢复），
 *       失效行保留在车内但不计入勾选与金额；</li>
 *   <li><b>TOCTOU 禁止</b>：加购不查库存强校验（预留在下单时由 core-order 预占 3002 裁决），
 *       列表仅取库存做展示；</li>
 *   <li>容量上限 {@code app.cart.max-items}（默认 200）与单行上限 {@code app.cart.max-quantity}（默认 999）
 *       由服务端下发/校验，前端禁止硬编码（ios-frontend 7.2）。</li>
 * </ul>
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartItemRepository cartRepository;
    private final ProductClient productClient;
    private final PriceClient priceClient;
    private final InventoryClient inventoryClient;
    private final SnowflakeIdGenerator idGenerator;
    private final TransactionTemplate txTemplate;
    private final int maxItems;
    private final int maxQuantity;

    public CartService(CartItemRepository cartRepository,
                       ProductClient productClient,
                       PriceClient priceClient,
                       InventoryClient inventoryClient,
                       SnowflakeIdGenerator idGenerator,
                       PlatformTransactionManager transactionManager,
                       @Value("${app.cart.max-items:200}") int maxItems,
                       @Value("${app.cart.max-quantity:999}") int maxQuantity) {
        this.cartRepository = cartRepository;
        this.productClient = productClient;
        this.priceClient = priceClient;
        this.inventoryClient = inventoryClient;
        this.idGenerator = idGenerator;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.maxItems = maxItems;
        this.maxQuantity = maxQuantity;
    }

    // ------------------------------------------------------------------ 加入购物车

    /**
     * 加入购物车：同 SKU 合并数量（幂等重放 0 重复行），新行走雪花 ID + 唯一键兜底。
     *
     * <p>加购前只校验商品在架（3001/3003），不查库存（TOCTOU 禁止）。</p>
     */
    public AddResponse add(Long userId, AddRequest request) {
        if (request.skuId() == null) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "skuId 不能为空");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1 || quantity > maxQuantity) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "quantity 必须在 1~" + maxQuantity + " 之间");
        }
        SkuView sku = requireOnShelf(request.skuId());
        Long productId = sku.spuId() == null ? 0L : sku.spuId();

        for (int attempt = 1; ; attempt++) {
            var existing = cartRepository.findByUserIdAndSkuId(userId, request.skuId());
            if (existing.isPresent()) {
                return incrementOrExceed(userId, existing.get(), quantity);
            }
            if (cartRepository.countByUserId(userId) >= maxItems) {
                throw new BizException(BizErrorCode.PARAM_ERROR,
                        "购物车已满（上限 " + maxItems + " 件商品）");
            }
            try {
                Long cartItemId = txTemplate.execute(status -> {
                    CartItem item = CartItem.create(idGenerator.nextId(), idGenerator.nextId(),
                            userId, productId, request.skuId(), quantity);
                    cartRepository.save(item);
                    return item.getCartItemId();
                });
                return new AddResponse(cartItemId, cartRepository.sumQuantity(userId));
            } catch (DataIntegrityViolationException e) {
                // 并发重复加购：uk_user_sku 唯一键兜底 → 重读后转累加（下次循环）
                if (attempt >= 2) {
                    throw new BizException(BizErrorCode.SYSTEM_ERROR, "并发加购冲突，请重试");
                }
                log.info("加购唯一键冲突，转为合并: userId={} skuId={}", userId, request.skuId());
            }
        }
    }

    /** 累加数量（带上限）；0 行 = 超上限或行被并发删除，重读裁决。 */
    private AddResponse incrementOrExceed(Long userId, CartItem existing, int quantity) {
        Instant now = Instant.now();
        int rows = txTemplate.execute(status -> cartRepository
                .incrementQuantityCapped(userId, existing.getSkuId(), quantity, maxQuantity, now));
        if (rows == 0) {
            // 行还在 → 超上限；行没了 → 并发删除，交由上层循环重新插入
            var fresh = cartRepository.findByUserIdAndSkuId(userId, existing.getSkuId());
            if (fresh.isPresent()) {
                throw new BizException(BizErrorCode.PARAM_ERROR,
                        "单商品数量最多 " + maxQuantity + " 件");
            }
            throw new BizException(BizErrorCode.SYSTEM_ERROR, "购物车行并发变更，请重试");
        }
        return new AddResponse(existing.getCartItemId(), cartRepository.sumQuantity(userId));
    }

    // ------------------------------------------------------------------ 列表（含失效标记与汇总）

    /**
     * 购物车列表：商品/价格/库存三源聚合 + 失效标记回写 + 金额汇总。
     *
     * <p>失效行（3001 缺失 / 3003 下架）仍返回但不计入 {@code selectedQuantity} 与金额；
     * {@code totalQuantity} 含失效行（车内总件数口径）。</p>
     */
    public CartListResponse list(Long userId) {
        List<CartItem> rows = cartRepository.findByUserIdOrderByCreatedAtAsc(userId);
        if (rows.isEmpty()) {
            return new CartListResponse(List.of(), 0, 0, maxItems,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<Long> skuIds = rows.stream().map(CartItem::getSkuId).distinct().toList();
        Map<Long, SkuView> skuMap = productClient.skus(skuIds).stream()
                .filter(s -> s.skuId() != null)
                .collect(Collectors.toMap(SkuView::skuId, s -> s, (a, b) -> a));
        Map<Long, BigDecimal> priceMap = priceClient.prices(skuIds).stream()
                .filter(p -> p.skuId() != null && p.price() != null)
                .collect(Collectors.toMap(PriceView::skuId, PriceView::price, (a, b) -> a));
        Map<Long, Integer> stockMap = inventoryClient.stocks(skuIds).stream()
                .filter(s -> s.skuId() != null)
                .collect(Collectors.toMap(StockView::skuId,
                        s -> s.availableStock() == null ? 0 : s.availableStock(), (a, b) -> a));

        List<CartItemVO> views = new ArrayList<>(rows.size());
        List<CartItem> dirty = new ArrayList<>();
        int totalQuantity = 0;
        int selectedQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (CartItem row : rows) {
            SkuView sku = skuMap.get(row.getSkuId());
            boolean invalid = sku == null || Boolean.FALSE.equals(sku.onShelf());
            if (invalid != row.isInvalid()
                    || (sku != null && sku.spuId() != null && !sku.spuId().equals(row.getProductId()))) {
                row.setInvalid(invalid);
                if (sku != null && sku.spuId() != null) {
                    row.setProductId(sku.spuId());
                }
                row.touch();
                dirty.add(row); // 失效标记回写（下次列表直读，不重复判定）
            }

            BigDecimal price = priceMap.get(row.getSkuId());
            Integer stock = stockMap.get(row.getSkuId());
            int stockValue = stock == null ? 0 : stock;
            boolean inStock = !invalid && stockValue > 0;

            views.add(new CartItemVO(
                    row.getCartItemId(), row.getProductId(), row.getSkuId(),
                    sku == null ? null : sku.name(),
                    sku == null ? null : sku.image(),
                    price, row.getQuantity(), stockValue, inStock,
                    row.isSelected(), invalid, List.of()));

            totalQuantity += row.getQuantity();
            if (!invalid && row.isSelected()) {
                selectedQuantity += row.getQuantity();
                if (price != null) {
                    totalAmount = totalAmount.add(price.multiply(BigDecimal.valueOf(row.getQuantity())));
                }
            }
        }

        if (!dirty.isEmpty()) {
            txTemplate.executeWithoutResult(status -> cartRepository.saveAll(dirty));
        }

        // P0 促销未接入：优惠恒 0（与 core-order 下单同口径，TODO 待 core-promotion 就绪）
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal payable = totalAmount.subtract(discount).max(BigDecimal.ZERO);
        return new CartListResponse(views, totalQuantity, selectedQuantity, maxItems,
                totalAmount, discount, payable);
    }

    // ------------------------------------------------------------------ 修改 / 删除 / 勾选

    /** 修改数量/勾选（POST /api/v1/cart/update、PUT /api/v1/cart/{cartItemId}）。 */
    public UpdateResponse update(Long userId, Long cartItemId, UpdateRequest request) {
        if (request.quantity() == null && request.selected() == null) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "quantity 与 selected 至少传一个");
        }
        // 归属校验（IDOR 防护：跨用户按 3004 处理）
        cartRepository.findByUserIdAndCartItemId(userId, cartItemId)
                .orElseThrow(() -> new BizException(BizErrorCode.CART_ITEM_NOT_FOUND,
                        "购物车项不存在: " + cartItemId));

        if (request.quantity() != null) {
            int quantity = request.quantity();
            if (quantity < 1 || quantity > maxQuantity) {
                throw new BizException(BizErrorCode.PARAM_ERROR,
                        "quantity 必须在 1~" + maxQuantity + " 之间");
            }
            int rows = txTemplate.execute(status -> cartRepository
                    .setQuantity(userId, cartItemId, quantity, Instant.now()));
            if (rows == 0) {
                throw new BizException(BizErrorCode.CART_ITEM_NOT_FOUND,
                        "购物车项不存在: " + cartItemId); // 并发删除
            }
        }
        if (request.selected() != null) {
            txTemplate.executeWithoutResult(status -> cartRepository
                    .setSelected(userId, List.of(cartItemId), request.selected(), Instant.now()));
        }
        return new UpdateResponse(cartItemId,
                cartRepository.sumQuantity(userId), cartRepository.sumSelectedQuantity(userId));
    }

    /** 批量删除（POST /api/v1/cart/remove；DELETE 单行复用本方法）。 */
    public RemoveResponse remove(Long userId, RemoveRequest request) {
        if (request.cartItemIds() == null || request.cartItemIds().isEmpty()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "cartItemIds 不能为空");
        }
        List<Long> ids = request.cartItemIds().stream().distinct().toList();
        // 归属校验：任一 cartItemId 不属于本人 → 3004（不泄露他人行是否存在）
        Set<Long> owned = cartRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(CartItem::getCartItemId).collect(Collectors.toCollection(HashSet::new));
        for (Long id : ids) {
            if (!owned.contains(id)) {
                throw new BizException(BizErrorCode.CART_ITEM_NOT_FOUND, "购物车项不存在: " + id);
            }
        }
        txTemplate.executeWithoutResult(status -> cartRepository.deleteByUserIdAndCartItemIdIn(userId, ids));
        return new RemoveResponse(cartRepository.sumQuantity(userId));
    }

    /**
     * 勾选（POST /api/v1/cart/select）：{@code cartItemIds} 缺省/空 = 全选或取消全选，
     * 否则只改指定行（单选/多选同接口）。
     */
    public SelectResponse select(Long userId, SelectRequest request) {
        boolean selected = request.selected() == null || request.selected();
        List<Long> ids = request.cartItemIds() == null ? List.of()
                : request.cartItemIds().stream().distinct().toList();

        if (ids.isEmpty()) {
            txTemplate.executeWithoutResult(status ->
                    cartRepository.setSelectedAll(userId, selected, Instant.now()));
        } else {
            Set<Long> owned = cartRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                    .map(CartItem::getCartItemId).collect(Collectors.toCollection(HashSet::new));
            for (Long id : ids) {
                if (!owned.contains(id)) {
                    throw new BizException(BizErrorCode.CART_ITEM_NOT_FOUND, "购物车项不存在: " + id);
                }
            }
            txTemplate.executeWithoutResult(status ->
                    cartRepository.setSelected(userId, ids, selected, Instant.now()));
        }
        return new SelectResponse(cartRepository.sumQuantity(userId),
                cartRepository.sumSelectedQuantity(userId));
    }

    // ------------------------------------------------------------------ 工具

    /** 加购前商品在架校验（3001 缺失 / 3003 下架）。 */
    private SkuView requireOnShelf(Long skuId) {
        List<SkuView> found = productClient.skus(List.of(skuId));
        SkuView sku = found == null || found.isEmpty() ? null : found.get(0);
        if (sku == null || sku.skuId() == null) {
            throw new BizException(BizErrorCode.PRODUCT_NOT_FOUND, "商品不存在: skuId=" + skuId);
        }
        if (Boolean.FALSE.equals(sku.onShelf())) {
            throw new BizException(BizErrorCode.PRODUCT_OFF_SHELF, "商品已下架: skuId=" + skuId);
        }
        return sku;
    }
}
