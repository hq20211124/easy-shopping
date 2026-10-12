package com.easyshopping.android.feature.checkout

import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Address
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.CouponRecord
import com.easyshopping.android.core.network.CreateOrderItem
import com.easyshopping.android.core.network.CreateOrderRequest
import com.easyshopping.android.core.network.Money
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.launch
import java.util.UUID

data class CheckoutUiState(
    val loading: Boolean = false,
    /** 已选中的购物车行（进入结算前由购物车页通过 setItems 注入） */
    val items: List<com.easyshopping.android.core.network.CartItem> = emptyList(),
    val address: Address? = null,
    val addresses: List<Address> = emptyList(),
    /** 服务端可用券（P0 无 preview 接口，降级为 my?status=0 自助选择） */
    val coupons: List<CouponRecord> = emptyList(),
    val selectedCouponId: Long? = null,
    val remark: String = "",
    val submitting: Boolean = false,
    val error: String = "",
    val messageId: Int = 0,
)

sealed interface CheckoutIntent {
    data object Load : CheckoutIntent
    data class SelectAddress(val address: Address) : CheckoutIntent
    data class SelectCoupon(val couponId: Long?) : CheckoutIntent
    data class RemarkChanged(val value: String) : CheckoutIntent
    data object Submit : CheckoutIntent
}

sealed interface CheckoutEffect {
    data class Ordered(val orderId: Long) : CheckoutEffect
    data class Toast(val message: String) : CheckoutEffect
    data object RequireLogin : CheckoutEffect
    data object NeedAddress : CheckoutEffect
}

/**
 * 结算页（plans/android-frontend.md 4.5）。
 *
 * 关键口径：
 *  - 下单金额只提交 skuId+quantity，**金额以后端 core-price 实时计价为准**，前端不传价
 *  - 写接口必带 Idempotency-Key（UUID，本 VM 生命周期内一次提交一个 key，重试复用）
 *  - 3002 库存不足 / 4002 参数错 / 券失效 → 展示服务端 msg（Snackbar），不自造文案
 *  - TODO(P1)：`cart-checkout/preview` 与 `/coupon/available` 上线后替换本地估算与 my 降级
 */
class CheckoutViewModel(
    private val api: Api = ServiceLocator.api,
) : MviViewModel<CheckoutUiState, CheckoutIntent, CheckoutEffect>(CheckoutUiState()) {

    /** 本次提交的幂等键：同一"提交动作"的重试必须复用，避免重复下单 */
    private var idempotencyKey: String = UUID.randomUUID().toString()

    init {
        load()
    }

    override fun onIntent(intent: CheckoutIntent) {
        when (intent) {
            CheckoutIntent.Load -> load()
            is CheckoutIntent.SelectAddress -> setState { copy(address = intent.address) }
            is CheckoutIntent.SelectCoupon -> setState { copy(selectedCouponId = intent.couponId) }
            is CheckoutIntent.RemarkChanged -> setState { copy(remark = intent.value.take(MAX_REMARK) ) }
            CheckoutIntent.Submit -> submit()
        }
    }

    /** 购物车页把已选行传进来（P0 不新增接口，金额在服务端结算） */
    fun setItems(items: List<com.easyshopping.android.core.network.CartItem>) {
        setState { copy(items = items) }
    }

    private fun load() {
        if (!ServiceLocator.session.isLoggedIn) {
            sendEffect(CheckoutEffect.RequireLogin)
            return
        }
        setState { copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                // 结算清单：以服务端购物车 selected=true 的有效行为准（避免导航传参丢失/过期）
                val cart = runCatching { safeCall { api.cartList() } }.getOrNull()
                val selectedItems = cart?.items?.filter { it.selected && !it.invalid }.orEmpty()
                // 地址：取默认地址（isDefault=true），无则取第一条
                val addresses: List<com.easyshopping.android.core.network.Address> =
                    runCatching { safeCall { api.addresses() }.addresses }.getOrElse { emptyList() }
                val default = addresses.firstOrNull { it.isDefault == true } ?: addresses.firstOrNull()
                // 券：降级用 my?status=0（未使用），无 /coupon/available
                val coupons: List<com.easyshopping.android.core.network.CouponRecord> =
                    runCatching { safeCall { api.myCoupons(0) } }.getOrElse { emptyList() }
                val hadItems = currentState.items.isNotEmpty()
                setState {
                    copy(
                        loading = false,
                        items = if (hadItems) items else selectedItems,
                        addresses = addresses,
                        address = address ?: default,
                        coupons = coupons,
                    )
                }
                if (cart != null && !hadItems && selectedItems.isEmpty()) {
                    sendEffect(CheckoutEffect.Toast("没有已选中的商品可结算"))
                }
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
            }
        }
    }

    private fun submit() {
        val s = currentState
        if (s.address == null) {
            sendEffect(CheckoutEffect.NeedAddress)
            return
        }
        if (s.items.isEmpty()) {
            setState { copy(error = "没有可结算的商品", messageId = messageId + 1) }
            return
        }
        setState { copy(submitting = true, error = "") }
        viewModelScope.launch {
            try {
                val body = CreateOrderRequest(
                    items = s.items.map { CreateOrderItem(skuId = it.skuId, quantity = it.quantity) },
                    addressId = s.address.id,
                    receiverName = s.address.name,
                    receiverMobile = s.address.mobile,
                    receiverAddress = s.address.fullAddress,
                    couponId = s.selectedCouponId,
                    remark = s.remark.takeIf { it.isNotBlank() },
                )
                val resp = safeCall { api.createOrder(idempotencyKey, body) }
                // 下单成功后换新 key（下一次提交是新的动作）
                idempotencyKey = UUID.randomUUID().toString()
                setState { copy(submitting = false) }
                sendEffect(CheckoutEffect.Ordered(resp.orderId))
            } catch (e: Throwable) {
                // 服务端已按 Idempotency-Key 去重；此处只提示，用户可安全重试
                setState { copy(submitting = false, error = e.toApiMessage(), messageId = messageId + 1) }
                sendEffect(CheckoutEffect.Toast(e.toApiMessage()))
            }
        }
    }

    /** 本地估算：Σ(单价×数量) - 券面额（仅结算页展示预估，实际以下单响应与 core-price 计价为准） */
    fun estimatedPayable(): String {
        val s = currentState
        val goods = s.items.fold(java.math.BigDecimal.ZERO) { acc, item ->
            val price = runCatching { java.math.BigDecimal(item.price) }.getOrElse { java.math.BigDecimal.ZERO }
            acc.add(price.multiply(java.math.BigDecimal.valueOf(item.quantity.toLong())))
        }
        val coupon = s.coupons.firstOrNull { it.couponId == s.selectedCouponId }
            ?.let { runCatching { java.math.BigDecimal(it.value ?: "0") }.getOrNull() }
            ?: java.math.BigDecimal.ZERO
        return goods.subtract(coupon).max(java.math.BigDecimal.ZERO)
            .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    companion object {
        private const val MAX_REMARK = 200
    }
}
