package com.order.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.MaskUtil;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.order.crypto.ReceiverMobileCipher;
import com.order.domain.OrderItem;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.domain.OrderStatusLog;
import com.order.dto.OrderDtos.ActionView;
import com.order.dto.OrderDtos.AdminOrderVO;
import com.order.dto.OrderDtos.AmountDetailView;
import com.order.dto.OrderDtos.ChildOrderView;
import com.order.dto.OrderDtos.InternalOrderSnapshot;
import com.order.dto.OrderDtos.OrderDetailVO;
import com.order.dto.OrderDtos.OrderItemView;
import com.order.dto.OrderDtos.OrderListVO;
import com.order.dto.OrderDtos.ReceiverMobileView;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 订单查询（买家端列表/详情 + 管理端列表/详情）。
 *
 * <p>买家端只可见父单（拆单后子单在详情/管理端展开）；状态 Tab 以服务端下发的
 * status/statusText/actions 为准（android-backend 4.3.4 落地约定 5），前端不自建状态文案表。</p>
 *
 * <p><b>隐私口径</b>：收货人手机号在所有出参统一脱敏（见 {@link #maskMobile}，算法已收敛到
 * {@code com.easyshopping.common.api.MaskUtil}），管理端列表/详情与买家端列表/详情一律不返回明文；
 * 管理端确需明文（发货/联系快递）只能走 {@link #receiverMobileForShip} 的
 * <b>带专门权限码 + 留痕</b>专用接口，禁止批量导出。</p>
 *
 * <p><b>存储口径</b>：手机号落库已是 AES-256-GCM 密文（{@link ReceiverMobileCipher} + Converter，
 * 见模块 README 11 节），本类拿到的一律是<b>解密后</b>的明文，出参仍按上述口径脱敏；
 * {@link #piiRead} 负责把 JPA 层层包装的解密异常还原为带原因的 9999 业务异常。</p>
 */
@Service
public class OrderQueryService {

    private final OrderMainRepository orderMainRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final OrderLifecycleService lifecycleService;
    private final SnowflakeIdGenerator idGenerator;

    public OrderQueryService(OrderMainRepository orderMainRepository,
                             OrderItemRepository orderItemRepository,
                             OrderStatusLogRepository orderStatusLogRepository,
                             OrderLifecycleService lifecycleService,
                             SnowflakeIdGenerator idGenerator) {
        this.orderMainRepository = orderMainRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderStatusLogRepository = orderStatusLogRepository;
        this.lifecycleService = lifecycleService;
        this.idGenerator = idGenerator;
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
        return piiRead(() -> buyerDetailInternal(orderId, userId));
    }

    private OrderDetailVO buyerDetailInternal(Long orderId, Long userId) {
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
                parent.getReceiverName(), maskMobile(parent.getReceiverMobile()), parent.getReceiverAddress(),
                parent.getRemark(), parent.getPayType(), parent.getPayTime(), parent.getPayDeadline(),
                parent.getDeliveryTime(), parent.getReceiveTime(),
                timeline, actionsOf(status), (long) parent.getVersion(), parent.getCreatedAt());
    }

    // ------------------------------------------------------------------ 服务间

    /**
     * 服务间订单字段快照（core-payment 预支付取数 / 对账用，直连 :8086 的 internal 端点）。
     *
     * <p>不能复用 {@link #buyerDetail}：服务间调用不带 X-User-Id（DEV.md 4.5 内网直连），
     * 走买家接口会被 {@code requireUserId()} 判为 1002。此处只按 orderId 取数，不做身份校验。</p>
     *
     * <p><b>只返回显式字段快照，禁止返回 {@link OrderMain} 实体</b>（审计 P1 遗留项）：
     * 实体整返等于把<b>明文 receiver_mobile / 收货地址 / 备注</b>一并开放给
     * core-payment、core-promotion（对账）等服务间调用方，而它们并不需要这些字段——
     * 隐私字段一旦整返，删除成本极高（已被抓包/日志/第三方留存）。</p>
     *
     * <p>保留字段与解析口径见 {@link InternalOrderSnapshot}（调用方已消费字段一个不多、一个不少）。</p>
     */
    @Transactional(readOnly = true)
    public InternalOrderSnapshot internalOrder(Long orderId) {
        return piiRead(() -> {
            OrderMain order = orderMainRepository.findById(orderId)
                    .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderId));
            return InternalOrderSnapshot.of(order);
        });
    }

    // ------------------------------------------------------------------ 管理端

    /** 管理端订单列表（多条件筛选；默认只列父单，includeChildren=true 时父子同列）。 */
    @Transactional(readOnly = true)
    public PageResult<AdminOrderVO> adminList(Long userId, Integer orderStatus, Long storeId, String orderNo,
                                              boolean includeChildren, int pageNum, int pageSize) {
        return piiRead(() -> adminListInternal(userId, orderStatus, storeId, orderNo, includeChildren, pageNum, pageSize));
    }

    private PageResult<AdminOrderVO> adminListInternal(Long userId, Integer orderStatus, Long storeId, String orderNo,
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
        return piiRead(() -> toAdminVO(orderMainRepository.findById(orderId)
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderId))));
    }

    /**
     * 管理端查看收货人<b>明文</b>手机号（发货 / 联系快递场景；plans 12.6「明文仅在必要场景按需返回」）。
     *
     * <p><b>与列表/详情出参的唯一区别</b>：① 必须在管理端专用接口上带专门权限码
     * {@code order:mobile:read} 调用（校验在 {@code AdminOrderController}，fail-closed）；
     * ② <b>每次查看写一条操作留痕</b>（订单状态流水 order_status_log，operator=管理端，
     * reason=「{@link OrderStatusLog#REASON_VIEW_RECEIVER_MOBILE}」），
     * 满足「谁、何时、看了哪一单」可追溯；③ 返回值只有本单明文，无列表/导出入口。</p>
     *
     * <p><b>口径说明</b>：「订单 owner 本人可见明文」属产品未决口径（AGENTS.md 已登记为待确认），
     * 本类只开放<b>管理端</b>这一条路，且要求权限码 + 留痕；买家端仍一律脱敏
     * （见 {@link #maskMobile} 注释中的保守口径说明）。</p>
     *
     * <p>写留痕因此<b>不是</b> readOnly 事务；若留痕写入失败必须整体失败——留痕失败还把明文发出去，
     * 等于没有留痕（合规上不可接受）。</p>
     *
     * @param orderNo 订单号（对外展示的 24 位编号；不接受雪花 ID，避免与子单/父单歧义）
     * @param adminId 操作人（管理端用户 ID，为空即失去留痕意义，由调用方先行拦截）
     * @return 该订单的明文收货人手机号
     */
    @Transactional
    public ReceiverMobileView receiverMobileForShip(String orderNo, Long adminId) {
        return piiRead(() -> {
            OrderMain order = orderMainRepository.findByOrderNo(orderNo)
                    .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
            // 留痕：复用订单状态流水（不新建表），from==to 表示「无状态变更的操作留痕」
            orderStatusLogRepository.save(OrderStatusLog.receiverMobileViewed(idGenerator.nextId(), order, adminId));
            return new ReceiverMobileView(order.getOrderNo(), order.getReceiverMobile());
        });
    }

    /**
     * 读取链路的密文失败还原：加解密在 JPA {@code AttributeConverter} 里，异常会被 JPA 规范
     * （{@code PersistenceException}）与 Spring Data 异常翻译（{@code JpaSystemException}）<b>连包两层</b>，
     * 直接冒泡只会得到一句无信息量的「系统错误」。这里把 {@link ReceiverMobileCipher} 抛的原始 9999
     * 业务异常沿 cause 链还原出来，让接口/日志明确区分「密文被篡改」「缺密钥」「格式非法」三类
     * PII 故障（码仍是 9999，fail-closed 语义不变）。
     *
     * <p>catch 范围是 {@code RuntimeException} 但只做「有 PII 因果才改写、否则原样抛出」，
     * 不改变任何既有异常语义（找不到 PII 异常时原异常直接继续上抛）。</p>
     */
    private static <T> T piiRead(Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            BizException pii = ReceiverMobileCipher.unwrapPiiFailure(e);
            if (pii != null) {
                throw pii;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 收货人手机号脱敏：保留前 3 后 4（{@code 138****0000}）。
     *
     * <p><b>依据</b>：plans/ios-backend.md 1.2.1 DDL 注释明确
     * 「user_address.mobile / order_main.receiver_mobile 等收货信息同为个人敏感信息，
     * 接口输出统一按 12.5 规则脱敏」，12.6 隐私原则 2 要求「接口返回遵循最小化原则，
     * 手机号、地址在列表类接口一律脱敏，明文仅在必要场景按需返回」；
     * admin-backend 4.4.3 同样要求「敏感列（手机号、地址）脱敏后再写文件」。</p>
     *
     * <p><b>关于「订单 owner 本人查看自己订单是否可返回明文」</b>：plans 未对订单 owner 开明文例外
     * （脱敏口径是按字段而非按调用方角色规定的），因此按保守口径<b>买家端详情同样脱敏</b>。
     * 若产品确认「本人可见明文」，须先在 plans 12.5/12.6 增补该例外并配套
     * 「仅本人 + 留痕」的专用接口，不能由本类自行开口子（否则管理端/导出链路会跟着漏出明文）。</p>
     *
     * <p>口径与 core-user 一致；算法已收敛为公共工具 {@link MaskUtil}（审计登记项：
     * MaskUtil 从 core-user 提到 common）。本方法保留为薄委托，避免既有调用方/用例失效。
     * <b>落库已是 AES-256-GCM 密文</b>（审计 P1 登记项，{@link ReceiverMobileCipher} +
     * {@link com.order.crypto.ReceiverMobileConverter}）：实体内存里是解密后的明文，
     * 本方法照旧按 plans 规则脱敏，存量明文行同样按明文脱敏；
     * 出参明文仅由 {@link #receiverMobileForShip} 的带权限 + 留痕接口提供。</p>
     */
    public static String maskMobile(String mobile) {
        return MaskUtil.maskMobile(mobile);
    }

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
                order.getReceiverName(), maskMobile(order.getReceiverMobile()), order.getCloseReason(), order.getRemark(),
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
