package com.easyshopping.android.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================================
// 通用分页信封：{total, pageNum, pageSize, list}（server common PageResult）
// ============================================================================

@Serializable
data class PageResult<T>(
    val total: Long = 0,
    val pageNum: Int = 1,
    val pageSize: Int = 0,
    val list: List<T> = emptyList(),
)

// ============================================================================
// 认证（core-user AuthDtos）
// ============================================================================

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(val mobile: String, val code: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

// ============================================================================
// 商品（core-product 买家端）
// ============================================================================

/** 商品卡片（GET /api/v1/products 的 list 行）。 */
@Serializable
data class ProductCard(
    val spuId: Long,
    val spuCode: String? = null,
    val name: String,
    val subtitle: String? = null,
    val mainImage: String? = null,
    @Serializable(with = AmountSerializer::class) val price: String = "0",
    @Serializable(with = AmountSerializer::class) val originalPrice: String? = null,
    val salesCount: Long = 0,
    val skuCount: Int = 0,
)

/** 规格组（详情 specs：[{name, values[]}]）。 */
@Serializable
data class SpecGroup(val name: String, val values: List<String> = emptyList())

/** SKU 视图（specs 为 规格名→规格值 映射）。 */
@Serializable
data class SkuView(
    val skuId: Long,
    val skuCode: String? = null,
    val title: String? = null,
    val specs: Map<String, String> = emptyMap(),
    val images: List<String> = emptyList(),
    val status: Int = 1,
    @Serializable(with = AmountSerializer::class) val price: String? = null,
)

@Serializable
data class ProductDetail(
    val spuId: Long,
    val spuCode: String? = null,
    val name: String,
    val subtitle: String? = null,
    val mainImage: String? = null,
    val images: List<String> = emptyList(),
    val video: String? = null,
    val detail: String? = null,
    @Serializable(with = AmountSerializer::class) val price: String? = null,
    @Serializable(with = AmountSerializer::class) val originalPrice: String? = null,
    val salesCount: Long = 0,
    val specs: List<SpecGroup> = emptyList(),
    val skus: List<SkuView> = emptyList(),
)

// ============================================================================
// 搜索（core-search）
// ============================================================================

@Serializable
data class SearchItem(
    val spuId: Long,
    val name: String,
    val mainImage: String? = null,
    @Serializable(with = AmountSerializer::class) val price: String? = null,
    @Serializable(with = AmountSerializer::class) val originalPrice: String? = null,
    val salesCount: Long = 0,
)

/**
 * 搜索结果（cursor 分页：cursor 传上一页 nextCursor，末页 nextCursor=null）。
 * 换排序/筛选条件必须回首页（不带 cursor），见 core-search README §3.4。
 */
@Serializable
data class SearchResponse(
    val query: String? = null,
    val total: Long = 0,
    val nextCursor: String? = null,
    val list: List<SearchItem> = emptyList(),
)

@Serializable
data class SuggestionItem(
    val text: String,
    val type: String = "keyword",
    val productId: Long? = null,
)

@Serializable
data class SuggestionResponse(
    val query: String? = null,
    val suggestions: List<SuggestionItem> = emptyList(),
)

@Serializable
data class HotWord(val keyword: String, val heat: Int = 0)

// ============================================================================
// 购物车（core-cart）
// ============================================================================

@Serializable
data class CartItem(
    val cartItemId: Long,
    val productId: Long? = null,
    val skuId: Long,
    val title: String,
    val mainImage: String? = null,
    @Serializable(with = AmountSerializer::class) val price: String = "0",
    val quantity: Int = 1,
    val stock: Int = 0,
    val inStock: Boolean = true,
    val selected: Boolean = false,
    val invalid: Boolean = false,
    val discountTags: List<String> = emptyList(),
)

@Serializable
data class CartListResponse(
    val items: List<CartItem> = emptyList(),
    val totalQuantity: Int = 0,
    val selectedQuantity: Int = 0,
    /** 服务端下发的行数上限，前端禁止硬编码 */
    val maxItems: Int = 200,
    @Serializable(with = AmountSerializer::class) val totalAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val discountAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val payableAmount: String = "0",
)

@Serializable
data class CartAddRequest(val skuId: Long, val quantity: Int = 1)

@Serializable
data class CartAddResponse(val cartItemId: Long? = null, val cartTotalQuantity: Int = 0)

@Serializable
data class CartUpdateRequest(
    val cartItemId: Long? = null,
    val quantity: Int? = null,
    val selected: Boolean? = null,
)

@Serializable
data class CartRemoveRequest(val cartItemIds: List<Long>)

@Serializable
data class CartSelectRequest(val cartItemIds: List<Long>? = null, val selected: Boolean? = null)

@Serializable
data class CartOpResponse(
    val cartTotalQuantity: Int = 0,
    val selectedQuantity: Int = 0,
)

// ============================================================================
// 地址（core-user AddressDtos）
// ============================================================================

@Serializable
data class Address(
    val id: Long,
    val name: String,
    val mobile: String,
    val province: String = "",
    val city: String = "",
    val district: String? = null,
    val detail: String = "",
    val zipCode: String? = null,
    val isDefault: Boolean? = false,
) {
    /** 一行展示：省市区 + 详细地址。 */
    val fullAddress: String
        get() = listOf(province, city, district.orEmpty(), detail)
            .filter { it.isNotBlank() }
            .joinToString("")
}

@Serializable
data class AddressListResponse(val addresses: List<Address> = emptyList())

@Serializable
data class AddressRequest(
    val name: String,
    val mobile: String,
    val province: String,
    val city: String,
    val district: String? = null,
    val detail: String,
    val zipCode: String? = null,
    val isDefault: Boolean? = null,
)

@Serializable
data class AddressIdResponse(val addressId: Long? = null, val success: Boolean? = null, val deleted: Boolean? = null)

// ============================================================================
// 订单（core-order OrderDtos；状态 0 基 8 值以 server/core-order README §2 为准）
// ============================================================================

/**
 * 订单状态枚举 —— **全 App 唯一定义点**（AGENTS.md：0 基，不是 1 基）。
 * 0待付款/1待发货/2待收货/3已完成/4退款中/5已退款/6已取消/7已关闭。
 */
enum class OrderStatus(val value: Int, val label: String) {
    UNPAID(0, "待付款"),
    PAID(1, "待发货"),
    SHIPPED(2, "待收货"),
    COMPLETED(3, "已完成"),
    REFUNDING(4, "退款中"),
    REFUNDED(5, "已退款"),
    CANCELLED(6, "已取消"),
    CLOSED(7, "已关闭");

    companion object {
        /** 非法值兜底到 UNPAID 前先返回 null（UI 展示 statusText 原文）。 */
        fun of(value: Int?): OrderStatus? = entries.firstOrNull { it.value == value }
    }
}

@Serializable
data class OrderItemView(
    val orderId: Long? = null,
    val orderNo: String? = null,
    val storeId: Long? = null,
    val spuId: Long? = null,
    val skuId: Long,
    val skuName: String,
    val skuImage: String? = null,
    @Serializable(with = AmountSerializer::class) val price: String = "0",
    val quantity: Int = 1,
    @Serializable(with = AmountSerializer::class) val totalAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val discountAmount: String = "0",
)

/** 操作按钮（服务端按状态机下发，前端只渲染，禁止自建文案表）。 */
@Serializable
data class ActionView(val type: String, val text: String)

@Serializable
data class OrderListVO(
    val orderId: Long,
    val orderNo: String,
    val status: Int,
    val statusText: String,
    val paymentStatus: String? = null,
    @Serializable(with = AmountSerializer::class) val totalAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val discountAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val freightAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val payAmount: String = "0",
    val itemCount: Int = 0,
    val storeCount: Int = 0,
    val items: List<OrderItemView> = emptyList(),
    val payDeadline: String? = null,
    val createdAt: String? = null,
    val actions: List<ActionView> = emptyList(),
)

@Serializable
data class AmountDetail(
    @Serializable(with = AmountSerializer::class) val goodsAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val freightAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val discountAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val payAmount: String = "0",
)

@Serializable
data class TimelineView(
    val fromStatus: Int = -1,
    val toStatus: Int = -1,
    val toStatusText: String? = null,
    val operatorType: Int = 0,
    val operatorId: Long? = null,
    val reason: String? = null,
    val time: String? = null,
)

@Serializable
data class ChildOrderView(
    val orderId: Long,
    val orderNo: String,
    val storeId: Long? = null,
    val status: Int,
    val statusText: String,
    @Serializable(with = AmountSerializer::class) val payAmount: String = "0",
    val itemCount: Int = 0,
)

@Serializable
data class OrderDetailVO(
    val orderId: Long,
    val orderNo: String,
    val status: Int,
    val statusText: String,
    val paymentStatus: String? = null,
    val amountDetail: AmountDetail? = null,
    val items: List<OrderItemView> = emptyList(),
    val children: List<ChildOrderView> = emptyList(),
    val receiverName: String? = null,
    val receiverMobile: String? = null,
    val receiverAddress: String? = null,
    val remark: String? = null,
    val payType: Int? = null,
    val payTime: String? = null,
    val payDeadline: String? = null,
    val deliveryTime: String? = null,
    val receiveTime: String? = null,
    val timeline: List<TimelineView> = emptyList(),
    val actions: List<ActionView> = emptyList(),
    val createdAt: String? = null,
)

/** 下单入参（金额由服务端 core-price 实时计价，客户端传的金额一律被忽略）。 */
@Serializable
data class CreateOrderRequest(
    val items: List<CreateOrderItem>,
    val addressId: Long? = null,
    val receiverName: String? = null,
    val receiverMobile: String? = null,
    val receiverAddress: String? = null,
    val couponId: Long? = null,
    val remark: String? = null,
)

@Serializable
data class CreateOrderItem(val skuId: Long, val quantity: Int)

@Serializable
data class SubOrderView(
    val orderId: Long,
    val orderNo: String,
    val storeId: Long? = null,
    @Serializable(with = AmountSerializer::class) val payAmount: String = "0",
    val itemCount: Int = 0,
)

@Serializable
data class CreateOrderResponse(
    val orderId: Long,
    val orderNo: String,
    val status: Int,
    val statusText: String? = null,
    @Serializable(with = AmountSerializer::class) val totalAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val discountAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val freightAmount: String = "0",
    @Serializable(with = AmountSerializer::class) val payAmount: String = "0",
    val expireSeconds: Long? = null,
    val payDeadline: String? = null,
    val subOrders: List<SubOrderView> = emptyList(),
)

// ============================================================================
// 支付（core-payment；channel：1支付宝/2微信/3银行卡/4余额）
// ============================================================================

@Serializable
data class PrepayRequest(val orderId: Long, val channel: Int)

@Serializable
data class PrepayResponse(
    val paymentId: Long,
    val channel: Int? = null,
    @Serializable(with = AmountSerializer::class) val amount: String = "0",
    val payType: String? = null,
    val payParams: Map<String, String> = emptyMap(),
    val expireAt: Long? = null,
)

@Serializable
data class MockPayRequest(val paymentId: Long)

@Serializable
data class MockPayResponse(val paymentId: Long? = null, val status: String? = null)

/** status: pending/paid/refunding/refunded（英文名，禁止用数字二次推断）。 */
@Serializable
data class PaymentStatusResponse(
    val orderId: Long? = null,
    val paymentId: Long? = null,
    val channel: Int? = null,
    @Serializable(with = AmountSerializer::class) val amount: String = "0",
    val status: String? = null,
    val paidAt: String? = null,
)

// ============================================================================
// 优惠券（core-promotion；券降级：接口不可用时结算页展示"不使用优惠券"）
// ============================================================================

@Serializable
data class CouponRecord(
    val couponId: Long,
    val templateId: Long? = null,
    val name: String? = null,
    val type: Int? = null,
    val value: String? = null,
    val minAmount: String? = null,
    val status: String? = null,
    val expireAt: String? = null,
)

// ============================================================================
// 用户资料（core-user UserDtos）
// ============================================================================

@Serializable
data class UserProfile(
    val userId: Long? = null,
    val username: String? = null,
    val nickname: String? = null,
    val avatar: String? = null,
    val mobile: String? = null,
    val email: String? = null,
    val gender: Int? = null,
    val birthday: String? = null,
    val registerDate: String? = null,
    val level: Int? = null,
    val levelName: String? = null,
    val points: Int? = null,
    val verified: Boolean? = null,
)
