package com.easyshopping.android.feature.cart

import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.CartItem
import com.easyshopping.android.core.network.CartRemoveRequest
import com.easyshopping.android.core.network.CartSelectRequest
import com.easyshopping.android.core.network.CartUpdateRequest
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.launch

data class CartUiState(
    val loading: Boolean = false,
    val items: List<CartItem> = emptyList(),
    /** 服务端下发的加购上限，**禁止前端硬编码**（core-cart README） */
    val maxItems: Int = 200,
    val totalQuantity: Int = 0,
    val selectedQuantity: Int = 0,
    /** 已选商品合计（服务端权威金额，客户端只做展示与步进预估） */
    val payableAmount: String = "0",
    val totalAmount: String = "0",
    val discountAmount: String = "0",
    val opItemId: Long? = null,
    val error: String = "",
    val messageId: Int = 0,
    val needLogin: Boolean = false,
)

sealed interface CartIntent {
    data object Refresh : CartIntent
    data class Increase(val item: CartItem) : CartIntent
    data class Decrease(val item: CartItem) : CartIntent
    data class ToggleSelect(val item: CartItem) : CartIntent
    data object ToggleSelectAll : CartIntent
    data class Remove(val item: CartItem) : CartIntent
}

sealed interface CartEffect {
    data class Toast(val message: String) : CartEffect
    data object RequireLogin : CartEffect
}

/**
 * 购物车（plans/android-frontend.md 4.3）：步进/全选/失效区/合计。
 *
 * 关键口径（core-cart README）：
 *  - 写接口一律用 **cartItemId** 定位（禁用 skuId）
 *  - `cart/select` 的 cartItemIds 传 null（或空数组）= 全选/取消全选当前全部有效项
 *  - 数量步进立即落库（服务端返回权威 total/payable），失败回滚并提示服务端 msg
 *  - maxItems 用服务端返回值
 */
class CartViewModel(
    private val api: Api = ServiceLocator.api,
) : MviViewModel<CartUiState, CartIntent, CartEffect>(CartUiState()) {

    init {
        if (!ServiceLocator.session.isLoggedIn) {
            setState { copy(needLogin = true) }
        } else {
            refresh()
        }
    }

    override fun onIntent(intent: CartIntent) {
        when (intent) {
            CartIntent.Refresh -> refresh()
            is CartIntent.Increase -> changeQuantity(intent.item, intent.item.quantity + 1)
            is CartIntent.Decrease -> changeQuantity(intent.item, intent.item.quantity - 1)
            is CartIntent.ToggleSelect -> toggleSelect(listOf(intent.item.cartItemId), !intent.item.selected)
            CartIntent.ToggleSelectAll -> {
                // 全选/取消全选：ids 传 null，服务端按「有效项全选/全不选」语义处理
                val allSelected = currentState.items.filter { !it.invalid }.all { it.selected }
                toggleSelect(null, !allSelected)
            }

            is CartIntent.Remove -> remove(intent.item)
        }
    }

    /** 拉取购物车（列表 + 权威合计），登录失效时引导登录 */
    fun refresh() {
        if (!ServiceLocator.session.isLoggedIn) {
            setState { copy(needLogin = true) }
            return
        }
        setState { copy(loading = items.isEmpty(), error = "") }
        viewModelScope.launch {
            try {
                val resp = safeCall { api.cartList() }
                setState {
                    copy(
                        loading = false,
                        items = resp.items,
                        maxItems = resp.maxItems,
                        totalQuantity = resp.totalQuantity,
                        selectedQuantity = resp.selectedQuantity,
                        payableAmount = resp.payableAmount,
                        totalAmount = resp.totalAmount,
                        discountAmount = resp.discountAmount,
                    )
                }
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
            }
        }
    }

    private fun changeQuantity(item: CartItem, next: Int) {
        if (next < 1) return
        setState { copy(opItemId = item.cartItemId) }
        viewModelScope.launch {
            try {
                safeCall {
                    api.cartUpdate(CartUpdateRequest(cartItemId = item.cartItemId, quantity = next))
                }
                refresh()
            } catch (e: Throwable) {
                // 3002 库存不足 / 4002 超上限：展示服务端 msg 并恢复列表
                toast(e.toApiMessage())
                refresh()
            } finally {
                setState { copy(opItemId = null) }
            }
        }
    }

    private fun toggleSelect(ids: List<Long>?, selected: Boolean) {
        setState { copy(opItemId = ids?.firstOrNull()) }
        viewModelScope.launch {
            try {
                safeCall { api.cartSelect(CartSelectRequest(cartItemIds = ids, selected = selected)) }
                refresh()
            } catch (e: Throwable) {
                toast(e.toApiMessage())
                refresh()
            } finally {
                setState { copy(opItemId = null) }
            }
        }
    }

    private fun remove(item: CartItem) {
        setState { copy(opItemId = item.cartItemId) }
        viewModelScope.launch {
            try {
                safeCall { api.cartRemove(CartRemoveRequest(listOf(item.cartItemId))) }
                refresh()
            } catch (e: Throwable) {
                toast(e.toApiMessage())
                refresh()
            } finally {
                setState { copy(opItemId = null) }
            }
        }
    }

    private fun toast(msg: String) {
        sendEffect(CartEffect.Toast(msg))
    }
}
