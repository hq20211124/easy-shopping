package com.order.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.order.domain.OrderItem;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.domain.OrderStatusLog;
import com.order.dto.OrderDtos.ActionView;
import com.order.dto.OrderDtos.AdminOrderVO;
import com.order.dto.OrderDtos.AmountDetailView;
import com.order.dto.OrderDtos.ChildOrderView;
import com.order.dto.OrderDtos.OrderDetailVO;
import com.order.dto.OrderDtos.OrderItemView;
import com.order.dto.OrderDtos.OrderListVO;
import com.order.dto.OrderDtos.TimelineView;
import com.order.repo.OrderItemRepository;
import com.order.repo.OrderMainRepository;
import com.order.repo.OrderStatusLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单查询（买家端列表/详情 + 管理端列表/详情）。
 *
 * <p>买家端只可见父单（拆单后子单在详情/管理端展开）；状态 Tab 以服务端下发的
 * status/statusText/actions 为准（android-backend 4.3.4 落地约定 5），前端不自建状态文案表。</p>
 */
@Service
public class OrderQueryService {

    private final OrderMainRepository orderMainRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final OrderLifecycleService lifecycleService;

    public OrderQueryService(OrderMainRepository orderMainRepository,
                             OrderItemRepository orderItemRepository,
                             OrderStatusLogRepository orderStatusLogRepository,
                             OrderLifecycleService lifecycleService) {
        this.orderMainRepository = orderMainRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderStatusLogRepository = orderStatusLogRepository;
        this.lifecycleService = lifecycleService;
    }

    // ------------------------------------------------------------------ 买家端

    /** 买家订单列表（分页 + 状态 Tab；status 为空查全部，只查父单）。 */
    @Transactional(readOnly = true)
    public PageResult<OrderListVO> buyerList(Long userId, String status, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clamp(pageSize),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OrderMain> result;
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            result = orderMainRepository.findByUserIdAndParentOrderNoIsNull(userId, pageable);
        } else {
            result = orderMainRepository.findByUserIdAndOrderStatusAndParentOrderNoIsNull(
                    userId, parseStatus(status).getValue(), pageable);
        }
        List<OrderListVO> list = result.getContent().stream()
                .map(this::toBuyerListVO)
                .collect(Collectors.toList());
        return PageResult.of(list, result.getTotalElements(), page, clamp(pageSize));
    }

    /** 买家订单详情（含子单、金额明细、timeline；越权访问按 4001 防 IDOR 探测）。 */
    @Transactional(readOnly = true)
    public OrderDetailVO buyerDetail(Long orderId, Long userId) {
        OrderMain parent = lifecycleService.ownedParent(orderId, userId);
        List<OrderMain> children = orderMainRepository.findByParentOrderNo(parent.getOrderNo());
        List<OrderMain> group = new ArrayList<>();
        group.add(parent);
        group.addAll(children);
        Map<Long, List<OrderItem>> itemsByOrder = itemsOfGroup(group);

        List<OrderItemView> itemViews = group.stream()
                .flatMap(o -> itemsByOrder.getOrDefault(o.getId(), List.of()).stream())
                .map(this::toItemView)
                .collect(Collectors.toList());

        List<ChildOrderView> childViews = children.stream()
                .map(c -> toChildView(c, itemsByOrder.getOrDefault(c.getId(), List.of()).size()))
                .collect(Collectors.toList());

        List<TimelineView> timeline = orderStatusLogRepository
                .findByOrderIdOrderByCreatedAtAsc(parent.getId()).stream()
                .map(log -> new TimelineView(log.getFromStatus(), log.getToStatus(),
                        statusText(log.getToStatus()), log.getOperatorType(), log.getOperatorId(),
                        log.getReason(), log.getCreatedAt()))
                .collect(Collectors.toList());

        OrderStatus status = OrderStatus.of(parent.getOrderStatus());
        return new OrderDetailVO(parent.getId(), parent.getOrderNo(), status.getValue(), status.getText(),
                paymentStatus(status),
                new AmountDetailView(parent.getTotalAmount(), parent.getFreightAmount(),
                        parent.getDiscountAmount(), parent.getPayAmount()),
                itemViews, childViews,
                parent.getReceiverName(), parent.getReceiverMobile(), parent.getReceiverAddress(),
                parent.getRemark(), parent.getPayType(), parent.getPayTime(), parent.getPayDeadline(),
                parent.getDeliveryTime(), parent.getReceiveTime(),
                timeline, actionsOf(status), (long) parent.getVersion(), parent.getCreatedAt());
    }

    // ------------------------------------------------------------------ 服务间

    /**
     * 订单原始快照（core-payment 预支付取数 / 支付对账用，直连 :8086 的 internal 端点）。
     *
     * <p>不能复用 {@link #buyerDetail}：服务间调用不带 X-User-Id（DEV.md 4.5 内网直连），
     * 走买家接口会被 {@code requireUserId()} 判为 1002。此处只按 orderId 取数，不做身份校验。</p>
     */
    @Transactional(readOnly = true)
    public OrderMain internalOrder(Long orderId) {
        return orderMainRepository.findById(orderId)
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderId));
    }

    // ------------------------------------------------------------------ 管理端

    /** 管理端订单列表（多条件筛选；默认只列父单，includeChildren=true 时父子同列）。 */
    @Transactional(readOnly = true)
    public PageResult<AdminOrderVO> adminList(Long userId, Integer orderStatus, Long storeId, String orderNo,
                                              boolean includeChildren, int pageNum, int pageSize) {
        Specification<OrderMain> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (!includeChildren) {
                predicates.add(cb.isNull(root.get("parentOrderNo")));
            }
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (orderStatus != null) {
                predicates.add(cb.equal(root.get("orderStatus"), orderStatus));
            }
            if (storeId != null) {
                predicates.add(cb.equal(root.get("storeId"), storeId));
            }
            if (orderNo != null && !orderNo.isBlank()) {
                predicates.add(cb.equal(root.get("orderNo"), orderNo));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Pageable pageable = PageRequest.of(Math.max(pageNum - 1, 0), clamp(pageSize),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OrderMain> result = orderMainRepository.findAll(spec, pageable);
        List<AdminOrderVO> list = result.getContent().stream()
                .map(this::toAdminVO)
                .collect(Collectors.toList());
        return PageResult.of(list, result.getTotalElements(), pageNum, clamp(pageSize));
    }

    /** 管理端订单详情（含明细与子单）。 */
    @Transactional(readOnly = true)
    public AdminOrderVO adminDetail(Long orderId) {
        return toAdminVO(orderMainRepository.findById(orderId)
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderId)));
    }

    // ------------------------------------------------------------------ 组装

    private OrderListVO toBuyerListVO(OrderMain parent) {
        List<OrderMain> children = orderMainRepository.findByParentOrderNo(parent.getOrderNo());
        List<OrderMain> group = new ArrayList<>();
        group.add(parent);
        group.addAll(children);
        Map<Long, List<OrderItem>> itemsByOrder = itemsOfGroup(group);
        List<OrderItemView> itemViews = group.stream()
                .flatMap(o -> itemsByOrder.getOrDefault(o.getId(), List.of()).stream())
                .map(this::toItemView)
                .collect(Collectors.toList());
        OrderStatus status = OrderStatus.of(parent.getOrderStatus());
        return new OrderListVO(parent.getId(), parent.getOrderNo(), status.getValue(), status.getText(),
                paymentStatus(status), parent.getTotalAmount(), parent.getDiscountAmount(),
                parent.getFreightAmount(), parent.getPayAmount(),
                itemViews.stream().mapToInt(OrderItemView::quantity).sum(),
                Math.max(children.size(), 1), itemViews,
                parent.getPayDeadline(), parent.getCreatedAt(), actionsOf(status));
    }

    private AdminOrderVO toAdminVO(OrderMain order) {
        List<OrderMain> children = order.isParent()
                ? orderMainRepository.findByParentOrderNo(order.getOrderNo())
                : List.of();
        List<OrderMain> group = new ArrayList<>();
        group.add(order);
        group.addAll(children);
        Map<Long, List<OrderItem>> itemsByOrder = itemsOfGroup(group);
        List<OrderItemView> itemViews = group.stream()
                .flatMap(o -> itemsByOrder.getOrDefault(o.getId(), List.of()).stream())
                .map(this::toItemView)
                .collect(Collectors.toList());
        OrderStatus status = OrderStatus.of(order.getOrderStatus());
        return new AdminOrderVO(order.getId(), order.getOrderNo(), order.getUserId(),
                order.getParentOrderNo(), order.getStoreId(),
                status.getValue(), status.getText(), order.getPayType(), order.getPayTime(),
                order.getTotalAmount(), order.getDiscountAmount(), order.getFreightAmount(), order.getPayAmount(),
                order.getReceiverName(), order.getReceiverMobile(), order.getCloseReason(), order.getRemark(),
                order.isSplitFlag(), (long) order.getVersion(), itemViews,
                children.stream()
                        .map(c -> toChildView(c, itemsByOrder.getOrDefault(c.getId(), List.of()).size()))
                        .collect(Collectors.toList()),
                order.getCreatedAt());
    }

    private Map<Long, List<OrderItem>> itemsOfGroup(List<OrderMain> group) {
        List<Long> orderIds = group.stream().map(OrderMain::getId).collect(Collectors.toList());
        return orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }

    private OrderItemView toItemView(OrderItem item) {
        return new OrderItemView(item.getOrderId(), item.getOrderNo(), item.getStoreId(), item.getSpuId(),
                item.getSkuId(), item.getSkuName(), item.getSkuImage(), item.getPrice(), item.getQuantity(),
                item.getTotalAmount(), item.getDiscountAmount());
    }

    private ChildOrderView toChildView(OrderMain child, int itemCount) {
        OrderStatus status = OrderStatus.of(child.getOrderStatus());
        return new ChildOrderView(child.getId(), child.getOrderNo(), child.getStoreId(),
                status.getValue(), status.getText(), child.getTotalAmount(), child.getDiscountAmount(),
                child.getPayAmount(), itemCount);
    }

    // ------------------------------------------------------------------ 状态工具

    /** 状态查询参数解析：接受 0 基数字（0~7）或枚举名（unpaid/paid/.../closed/all）。 */
    static OrderStatus parseStatus(String status) {
        try {
            if (status.chars().allMatch(Character::isDigit)) {
                return OrderStatus.of(Integer.parseInt(status));
            }
            return OrderStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "非法订单状态: " + status + "（接受 0~7 或 unpaid/paid/shipped/completed/refunding/refunded/cancelled/closed）");
        }
    }

    /** payment_status（ios-frontend 8.4 轮询专用，前端不得用数字 status 二次推断）。 */
    static String paymentStatus(OrderStatus status) {
        return switch (status) {
            case UNPAID -> "pending_payment";
            case PAID, SHIPPED, COMPLETED -> "paid";
            case REFUNDING -> "refunding";
            case REFUNDED -> "refunded";
            case CANCELLED, CLOSED -> "closed";
        };
    }

    private String statusText(int statusValue) {
        return OrderStatus.of(statusValue).getText();
    }

    /** 按钮由服务端按状态下发（前端只渲染，保证三端文案一致）。 */
    static List<ActionView> actionsOf(OrderStatus status) {
        return switch (status) {
            case UNPAID -> List.of(new ActionView("pay", "去付款"), new ActionView("cancel", "取消订单"));
            case PAID -> List.of();
            case SHIPPED -> List.of(new ActionView("logistics", "查看物流"),
                    new ActionView("confirm", "确认收货"));
            default -> List.of();
        };
    }

    private static int clamp(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 200);
    }
}
