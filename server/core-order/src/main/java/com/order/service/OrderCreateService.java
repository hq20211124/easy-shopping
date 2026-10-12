package com.order.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.order.client.InventoryClient;
import com.order.client.PriceClient;
import com.order.client.ProductClient;
import com.order.client.PromotionClient;
import com.order.client.dto.ClientDtos.InventoryItem;
import com.order.client.dto.ClientDtos.PriceView;
import com.order.client.dto.ClientDtos.SkuView;
import com.order.domain.OrderItem;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.domain.OrderStatusLog;
import com.order.dto.OrderDtos.CreateOrderRequest;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.dto.OrderDtos.SubOrderView;
import com.order.repo.OrderItemRepository;
import com.order.repo.OrderMainRepository;
import com.order.repo.OrderStatusLogRepository;
import com.order.support.AfterCommitEvents;
import com.order.support.DiscountAllocator;
import com.order.support.OrderNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 下单服务（android-backend 4.1.4 幂等/并发/拆单要点 + admin-backend 4.4.2 拆单规则）。
 *
 * <p>流程：入参校验 → core-product 拿 SKU → core-price 权威计价 → core-inventory 预占（幂等键=父单雪花 ID）
 * → core-promotion 优惠券「试算 + 核销」（preview → deduct，任一步失败降级 discount=0，见 {@link RestPromotionClient}）
 * → 按 store_id 拆单（优惠按比例分摊 + 尾差归集到金额最大的子单）→ 写 order_main / order_item（雪花 ID + 24 位 order_no）
 * → 设 pay_deadline → 事务提交后发 {@code order.created}。</p>
 *
 * <p><b>补偿</b>：预占成功后任何持久化失败都会调 {@code /release} 回补库存（P0 尽力而为；
 * P1 上 Saga + 本地消息表保证），不留幽灵锁。</p>
 */
@Service
public class OrderCreateService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreateService.class);

    private final ProductClient productClient;
    private final PriceClient priceClient;
    private final InventoryClient inventoryClient;
    private final PromotionClient promotionClient;
    private final OrderMainRepository orderMainRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final SnowflakeIdGenerator idGenerator;
    private final DomainEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;

    /** 付款超时分钟数（默认 30 分钟；plans C7 裁决：唯一事实来源是订单表 pay_deadline，各端不自定义，产品待书面确认）。 */
    private final long payTimeoutMinutes;

    public OrderCreateService(ProductClient productClient,
                              PriceClient priceClient,
                              InventoryClient inventoryClient,
                              PromotionClient promotionClient,
                              OrderMainRepository orderMainRepository,
                              OrderItemRepository orderItemRepository,
                              OrderStatusLogRepository orderStatusLogRepository,
                              OrderNoGenerator orderNoGenerator,
                              SnowflakeIdGenerator idGenerator,
                              DomainEventPublisher eventPublisher,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.order.pay-timeout-minutes:30}") long payTimeoutMinutes) {
        this.productClient = productClient;
        this.priceClient = priceClient;
        this.inventoryClient = inventoryClient;
        this.promotionClient = promotionClient;
        this.orderMainRepository = orderMainRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderStatusLogRepository = orderStatusLogRepository;
        this.orderNoGenerator = orderNoGenerator;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.payTimeoutMinutes = payTimeoutMinutes;
    }

    public CreateOrderResponse create(Long userId, CreateOrderRequest request) {
        // 1) 入参校验：同 SKU 合并数量（数量以服务端为准，整数禁止浮点）
        Map<Long, Integer> quantities = mergeQuantities(request.items());

        // 2) 商品信息（core-product）：缺失 3001、下架 3003
        List<Long> skuIds = new ArrayList<>(quantities.keySet());
        Map<Long, SkuView> skuMap = productClient.skus(skuIds).stream()
                .collect(Collectors.toMap(SkuView::skuId, sku -> sku, (a, b) -> a));
        for (Long skuId : skuIds) {
            SkuView sku = skuMap.get(skuId);
            if (sku == null) {
                throw new BizException(BizErrorCode.PRODUCT_NOT_FOUND, "商品不存在: skuId=" + skuId);
            }
            if (sku.onShelf() != null && !sku.onShelf()) {
                throw new BizException(BizErrorCode.PRODUCT_OFF_SHELF, "商品已下架: skuId=" + skuId);
            }
            if (sku.storeId() == null) {
                throw new BizException(BizErrorCode.PRODUCT_NOT_FOUND, "SKU 缺少店铺信息（拆单分组键）: skuId=" + skuId);
            }
        }

        // 3) 权威计价（core-price）：请求体金额一律忽略
        Map<Long, BigDecimal> priceMap = priceClient.prices(skuIds).stream()
                .filter(p -> p.price() != null)
                .collect(Collectors.toMap(PriceView::skuId, PriceView::price, (a, b) -> a));
        BigDecimal goodsTotal = BigDecimal.ZERO;
        for (Long skuId : skuIds) {
            BigDecimal price = priceMap.get(skuId);
            if (price == null) {
                throw new BizException(BizErrorCode.PRODUCT_NOT_FOUND, "商品价格缺失: skuId=" + skuId);
            }
            goodsTotal = goodsTotal.add(price.multiply(BigDecimal.valueOf(quantities.get(skuId))));
        }

        // 4) 父单标识：雪花主键 + 24 位订单号（预占库存的业务幂等键 = 父单 ID）
        long parentId = idGenerator.nextId();
        String parentOrderNo = orderNoGenerator.next(userId);
        Instant payDeadline = Instant.now().plus(Duration.ofMinutes(payTimeoutMinutes));

        // 5) 库存预占（与 pay_deadline 对齐；不足 3002 由 inventory 服务抛出，整单失败）
        List<InventoryItem> reserveItems = skuIds.stream()
                .map(skuId -> new InventoryItem(skuId, quantities.get(skuId)))
                .toList();
        long ttlSeconds = Math.max(1, Duration.between(Instant.now(), payDeadline).getSeconds());
        inventoryClient.reserve(parentId, reserveItems, ttlSeconds);
        try {
            // 6) 优惠券试算 + 核销（core-promotion 实际契约：preview {couponId, amount[]} → deduct {couponId, orderId}；
            //    任一环失败 P0 降级为不折扣，见 RestPromotionClient）
            BigDecimal discount = BigDecimal.ZERO;
            if (request.couponId() != null) {
                discount = promotionClient.deductCoupon(request.couponId(), userId, parentId, goodsTotal);
                if (discount == null || discount.signum() < 0) {
                    throw new BizException(BizErrorCode.PARAM_ERROR, "优惠金额非法");
                }
                if (discount.compareTo(goodsTotal) > 0) {
                    discount = goodsTotal; // 应付不得为负
                }
            }

            // 7) 拆单 + 持久化（同一本地事务）
            final BigDecimal goodsTotalFinal = goodsTotal;
            final BigDecimal discountFinal = discount;
            return txTemplate.execute(status ->
                    persist(userId, request, parentId, parentOrderNo, payDeadline,
                            quantities, skuMap, priceMap, goodsTotalFinal, discountFinal));
        } catch (RuntimeException e) {
            compensateRelease(parentId, e);
            throw e;
        }
    }

    // ------------------------------------------------------------------ 拆单与落库（事务内）

    private CreateOrderResponse persist(Long userId, CreateOrderRequest request,
                                        long parentId, String parentOrderNo, Instant payDeadline,
                                        Map<Long, Integer> quantities, Map<Long, SkuView> skuMap,
                                        Map<Long, BigDecimal> priceMap, BigDecimal goodsTotal,
                                        BigDecimal discount) {
        // 父单：保留原始金额 + 拆分标记（admin-backend 4.4.2 规则 3，不引入 SPLIT 状态）
        OrderMain parent = OrderMain.parent(parentId, parentOrderNo, userId);
        parent.setTotalAmount(goodsTotal);
        parent.setDiscountAmount(discount);
        parent.setFreightAmount(BigDecimal.ZERO); // TODO(P1): 运费模板按店铺计算，未发货前为 0
        parent.setPayAmount(goodsTotal.subtract(discount));
        // 核销成功的券记录 ID：关单/取消/超时关单时据此退券（AUDIT P0-3）。
        // 只在真实产生优惠（discount>0，即扣券成功）时落库——扣券失败已降级为 discount=0，
        // 此时写 couponId 会产生无谓的退券调用（AVAILABLE 券的 release 是幂等 no-op，但没必要）。
        parent.setCouponId(discount.signum() > 0 ? request.couponId() : null);
        parent.setPayDeadline(payDeadline);
        parent.setReceiverName(nvl(request.receiverName()));
        parent.setReceiverMobile(nvl(request.receiverMobile()));
        parent.setReceiverAddress(nvl(request.receiverAddress()));
        parent.setRemark(nvl(request.remark()));
        parent.setSource(request.source() == null ? 1 : request.source());
        parent.setSplitFlag(true);
        orderMainRepository.save(parent);
        orderStatusLogRepository.save(OrderStatusLog.created(idGenerator.nextId(), parent));

        // 按 store_id 分组（store_id 必填：core-product 的 SKU 视图提供；分组键排序保证子单顺序在多次重试间稳定）
        Map<Long, List<Long>> byStore = new LinkedHashMap<>();
        skuIdsSorted(quantities).forEach(skuId -> byStore
                .computeIfAbsent(skuMap.get(skuId).storeId(), k -> new ArrayList<>())
                .add(skuId));
        List<Long> storeIds = byStore.keySet().stream().sorted().toList();

        List<BigDecimal> storeGoods = storeIds.stream()
                .map(storeId -> storeGoodsAmount(byStore.get(storeId), quantities, priceMap))
                .collect(Collectors.toList());
        // 优惠按比例分摊到子单，尾差归集到金额最大的子单（admin-backend 4.4.2「尾差归入金额最大的子单」；
        // 分摊基数越大，同样 1 分钱尾差的相对误差越小 → 归到首单会让金额极小的店铺结算偏差被放大）
        List<BigDecimal> storeShares = DiscountAllocator.allocate(storeGoods, discount);

        List<SubOrderView> subOrders = new ArrayList<>();
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal sumChildrenPay = BigDecimal.ZERO;

        for (int i = 0; i < storeIds.size(); i++) {
            Long storeId = storeIds.get(i);
            List<Long> storeSkuIds = byStore.get(storeId);
            BigDecimal subGoods = storeGoods.get(i);
            BigDecimal subDiscount = storeShares.get(i);

            long childId = idGenerator.nextId();
            String childOrderNo = orderNoGenerator.next(userId);
            OrderMain child = OrderMain.child(childId, childOrderNo, parent, storeId, parentOrderNo);
            child.setTotalAmount(subGoods);
            child.setDiscountAmount(subDiscount);
            child.setFreightAmount(BigDecimal.ZERO);
            child.setPayAmount(subGoods.subtract(subDiscount));
            child.setPayDeadline(payDeadline);
            child.setSource(parent.getSource());
            orderMainRepository.save(child);
            orderStatusLogRepository.save(OrderStatusLog.created(idGenerator.nextId(), child));

            // 行级优惠分摊（Σ行 == 子单优惠，尾差同样归到金额最大的行）
            List<BigDecimal> lineBases = storeSkuIds.stream()
                    .map(skuId -> priceMap.get(skuId).multiply(BigDecimal.valueOf(quantities.get(skuId))))
                    .collect(Collectors.toList());
            List<BigDecimal> lineShares = DiscountAllocator.allocate(lineBases, subDiscount);
            for (int line = 0; line < storeSkuIds.size(); line++) {
                Long skuId = storeSkuIds.get(line);
                SkuView sku = skuMap.get(skuId);
                OrderItem item = new OrderItem(idGenerator.nextId(), childId, childOrderNo, storeId,
                        sku.spuId() == null ? 0L : sku.spuId(), skuId,
                        nvl(sku.name()), nvl(sku.image()), priceMap.get(skuId), quantities.get(skuId));
                item.setDiscountAmount(lineShares.get(line));
                orderItems.add(item);
            }

            sumChildrenPay = sumChildrenPay.add(child.getPayAmount());
            subOrders.add(new SubOrderView(childId, childOrderNo, storeId, child.getPayAmount(), storeSkuIds.size()));
        }
        orderItemRepository.saveAll(orderItems);

        // 金额校验（4.4.2 规则 5）：Σ子单实付 == 父单实付，不等拒绝拆单并告警（防分摊错误造成资损）
        if (sumChildrenPay.compareTo(parent.getPayAmount()) != 0) {
            log.error("拆单金额不平: parentNo={} Σchildren={} parentPay={}",
                    parentOrderNo, sumChildrenPay, parent.getPayAmount());
            throw new BizException(BizErrorCode.SYSTEM_ERROR, "拆单金额校验失败，请稍后重试");
        }

        // 事件在事务提交后（DEV.md 4.4）
        AfterCommitEvents.publish(eventPublisher, DomainEventNames.ORDER_CREATED, Map.of(
                "orderId", parent.getId(),
                "orderNo", parent.getOrderNo(),
                "userId", parent.getUserId(),
                "payAmount", parent.getPayAmount(),
                "itemCount", orderItems.size(),
                "subOrders", subOrders.stream().map(SubOrderView::orderNo).toList(),
                "payDeadline", payDeadline.toString()));

        long expireSeconds = Math.max(1, Duration.between(Instant.now(), payDeadline).getSeconds());
        return new CreateOrderResponse(parent.getId(), parent.getOrderNo(),
                OrderStatus.UNPAID.getValue(), OrderStatus.UNPAID.getText(),
                parent.getTotalAmount(), parent.getDiscountAmount(), parent.getFreightAmount(),
                parent.getPayAmount(), expireSeconds, parent.getPayDeadline(), subOrders);
    }

    // ------------------------------------------------------------------ 工具

    /** 合并同 SKU 数量（服务端为准；重复行是合法入参，静默合并）。 */
    private Map<Long, Integer> mergeQuantities(List<OrderItemRequest> items) {
        Map<Long, Integer> merged = new HashMap<>();
        for (OrderItemRequest item : items) {
            if (item.quantity() <= 0) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "quantity 必须大于 0");
            }
            merged.merge(item.skuId(), item.quantity(), Integer::sum);
        }
        return merged;
    }

    private List<Long> skuIdsSorted(Map<Long, Integer> quantities) {
        return quantities.keySet().stream().sorted().collect(Collectors.toList());
    }

    private BigDecimal storeGoodsAmount(List<Long> skuIds, Map<Long, Integer> quantities,
                                        Map<Long, BigDecimal> priceMap) {
        return skuIds.stream()
                .map(skuId -> priceMap.get(skuId).multiply(BigDecimal.valueOf(quantities.get(skuId))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 预占补偿：持久化失败时释放库存，避免幽灵锁死（4.4.4 v1.0 硬伤）。 */
    private void compensateRelease(long parentId, RuntimeException cause) {
        try {
            inventoryClient.release(parentId);
            log.warn("下单失败已补偿释放库存: parentId={} cause={}", parentId, cause.getMessage());
        } catch (Exception releaseError) {
            log.error("下单失败且库存补偿释放失败，需人工/对账补偿: parentId={}", parentId, releaseError);
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
