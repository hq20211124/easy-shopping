package com.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单主表（order_main，plans/ios-backend.md 1.2.1 共享核心表 + admin-backend 1.2.2 admin_order 合并口径）。
 *
 * <p>主键雪花（裁决 C13：分库分表禁止 AUTO_INCREMENT）；{@code order_no} 24 位定长编码
 * = 时间 14 + 分片槽位 4 + 序列 6（00-架构总纲 4.3.1，槽位可反解分区）。</p>
 *
 * <p><b>拆单模型</b>：下单即按 store_id 拆单（本 P0 与 plans 4.4.2「支付后拆单」的差异点见 README）——
 * 父单（{@code parent_order_no = null, store_id = null}）保留原始金额并标记 {@code split_flag}，
 * 子单（{@code parent_order_no = 父单号, store_id 必填}）承载明细与店铺金额；父子状态始终一致，
 * 由状态机级联（{@link com.order.service.OrderStateMachine}）。</p>
 */
@Entity
@Table(name = "order_main", uniqueConstraints = @UniqueConstraint(name = "uk_order_no", columnNames = "order_no"),
        indexes = {
                @Index(name = "idx_user_status", columnList = "user_id, order_status"),
                @Index(name = "idx_status_deadline", columnList = "order_status, pay_deadline"),
                @Index(name = "idx_parent_order_no", columnList = "parent_order_no")
        })
public class OrderMain {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    /** 订单编号（24 位：时间14+槽位4+序列6，对外展示，禁止用雪花 ID 当订单号）。 */
    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 父订单号（拆单后子单回填；父单为 null）。 */
    @Column(name = "parent_order_no", length = 32)
    private String parentOrderNo;

    /** 店铺 ID（子单必填；父单 null 表示未拆/聚合）。 */
    @Column(name = "store_id")
    private Long storeId;

    /** 状态（8 值 0 基，见 {@link OrderStatus}）。 */
    @Column(name = "order_status", nullable = false)
    private int orderStatus = OrderStatus.UNPAID.getValue();

    /** 支付方式：1-支付宝 2-微信 3-银行卡(银联) 4-余额（buyer 端扩展，与数据字典同源）。 */
    @Column(name = "pay_type")
    private Integer payType;

    @Column(name = "pay_time")
    private Instant payTime;

    /** 付款截止时间（超时关单唯一事实来源，各端倒计时同源；分钟数为待产品确认参数）。 */
    @Column(name = "pay_deadline")
    private Instant payDeadline;

    /** 关闭/取消原因（timeout 超时 / manual 手工 / refund 退款完成）。 */
    @Column(name = "close_reason", length = 255)
    private String closeReason;

    /** 发货时间（后台发货时回填）。 */
    @Column(name = "delivery_time")
    private Instant deliveryTime;

    /** 确认收货时间。 */
    @Column(name = "receive_time")
    private Instant receiveTime;

    /** 商品总金额（优惠前）。 */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** 优惠金额。 */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** 运费（P0 未接运费模板恒为 0，未发货前运费为 0）。 */
    @Column(name = "freight_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal freightAmount = BigDecimal.ZERO;

    /** 应付金额 = 商品总额 - 优惠 + 运费（支付域定额口径，见 {@code InternalOrderController}）。 */
    @Column(name = "pay_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal payAmount;

    /**
     * 本单核销的用户优惠券记录 ID（{@code coupon_record.id}，非券模板 ID）；未用券为 null。
     *
     * <p><b>关单退券的定位依据</b>：超时关单 / 买家取消 / 后台关闭三条路径据此调用
     * {@code POST /api/v1/coupons/release} 把券退回 {@code available}（P0-3 修复）。
     * 下单时由 {@code OrderCreateService} 写入；存量数据为 null 时退券被安全跳过（只告警不吞券静默失败）。</p>
     */
    @Column(name = "coupon_id")
    private Long couponId;

    // ---- 收货地址快照（下单时固化，后续改地址不影响历史订单；P1 落库加密 + 输出脱敏）----
    @Column(name = "receiver_name", length = 64)
    private String receiverName = "";

    @Column(name = "receiver_mobile", length = 20)
    private String receiverMobile = "";

    @Column(name = "receiver_address", length = 512)
    private String receiverAddress = "";

    @Column(name = "remark", length = 512)
    private String remark = "";

    /** 来源：1-iOS 2-Android 3-Web 4-MiniApp 5-AppClip。 */
    @Column(name = "source", nullable = false)
    private int source = 1;

    /** 父单已拆分标记（不引入 SPLIT 状态，admin-backend 4.4.2 规则 3）。 */
    @Column(name = "split_flag", nullable = false)
    private boolean splitFlag;

    /** 乐观锁版本号（状态/金额变更条件更新，admin-backend 4.3 规则 4）。 */
    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected OrderMain() {
    }

    /** 父单（聚合单）。 */
    public static OrderMain parent(Long id, String orderNo, Long userId) {
        OrderMain order = new OrderMain();
        order.id = id;
        order.orderNo = orderNo;
        order.userId = userId;
        return order;
    }

    /** 子单（店铺单）。 */
    public static OrderMain child(Long id, String orderNo, OrderMain parentOrder, Long storeId, String parentOrderNo) {
        OrderMain order = new OrderMain();
        order.id = id;
        order.orderNo = orderNo;
        order.userId = parentOrder.getUserId();
        order.parentOrderNo = parentOrderNo;
        order.storeId = storeId;
        return order;
    }

    public boolean isParent() {
        return parentOrderNo == null;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public String getParentOrderNo() {
        return parentOrderNo;
    }

    public Long getStoreId() {
        return storeId;
    }

    public int getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(int orderStatus) {
        this.orderStatus = orderStatus;
    }

    public Integer getPayType() {
        return payType;
    }

    public void setPayType(Integer payType) {
        this.payType = payType;
    }

    public Instant getPayTime() {
        return payTime;
    }

    public void setPayTime(Instant payTime) {
        this.payTime = payTime;
    }

    public Instant getPayDeadline() {
        return payDeadline;
    }

    public void setPayDeadline(Instant payDeadline) {
        this.payDeadline = payDeadline;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(String closeReason) {
        this.closeReason = closeReason;
    }

    public Instant getDeliveryTime() {
        return deliveryTime;
    }

    public Instant getReceiveTime() {
        return receiveTime;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getFreightAmount() {
        return freightAmount;
    }

    public void setFreightAmount(BigDecimal freightAmount) {
        this.freightAmount = freightAmount;
    }

    public BigDecimal getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(BigDecimal payAmount) {
        this.payAmount = payAmount;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverMobile() {
        return receiverMobile;
    }

    public void setReceiverMobile(String receiverMobile) {
        this.receiverMobile = receiverMobile;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public void setReceiverAddress(String receiverAddress) {
        this.receiverAddress = receiverAddress;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public int getSource() {
        return source;
    }

    public void setSource(int source) {
        this.source = source;
    }

    public boolean isSplitFlag() {
        return splitFlag;
    }

    public void setSplitFlag(boolean splitFlag) {
        this.splitFlag = splitFlag;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
