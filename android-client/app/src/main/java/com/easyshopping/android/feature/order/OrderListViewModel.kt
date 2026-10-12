package com.easyshopping.android.feature.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.ActionView
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.OrderListVO
import com.easyshopping.android.core.network.OrderStatus
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class OrderListUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val orders: List<OrderListVO> = emptyList(),
    val error: String = "",
    /** 正在操作（取消/确认）的订单，用于按钮 loading 与防重复点击 */
    val opOrderId: Long? = null,
    val messageId: Int = 0,
)

sealed interface OrderListIntent {
    data object Refresh : OrderListIntent
    data object LoadMore : OrderListIntent
    data class Cancel(val orderId: Long) : OrderListIntent
    data class Confirm(val orderId: Long) : OrderListIntent
}

sealed interface OrderListEffect {
    data class Toast(val message: String) : OrderListEffect
    data object RequireLogin : OrderListEffect
}

/**
 * Tab → 服务端 status 查询参数映射（订单状态 0 基 8 值，唯一真源 OrderStatus）。
 * 注意：这里传给服务端的是状态数字字符串，服务端同样按 0 基解释。
 */
object OrderTabs {
    val ALL = TabKey("all", "全部")
    val UNPAID = TabKey("unpaid", "待付款", OrderStatus.UNPAID)
    val PAID = TabKey("paid", "待发货", OrderStatus.PAID)
    val SHIPPED = TabKey("shipped", "待收货", OrderStatus.SHIPPED)
    val COMPLETED = TabKey("completed", "已完成", OrderStatus.COMPLETED)

    data class TabKey(val key: String, val label: String, val status: OrderStatus? = null)

    val all = listOf(ALL, UNPAID, PAID, SHIPPED, COMPLETED)

    fun byKey(key: String): TabKey = all.firstOrNull { it.key == key } ?: ALL

    /** Tab → 服务端 status 参数（all 传 "all"，其余传状态数字） */
    fun statusParam(tab: TabKey): String = tab.status?.value?.toString() ?: "all"
}

/**
 * 订单列表（plans/android-frontend.md 4.7）：Tab 过滤 + page 分页 + 取消/确认收货。
 * 操作按钮按服务端返回的 actions[] 渲染，前端不自行推断可执行动作。
 */
class OrderListViewModel(
    private val tab: OrderTabs.TabKey,
    private val api: Api = ServiceLocator.api,
) : MviViewModel<OrderListUiState, OrderListIntent, OrderListEffect>(OrderListUiState()) {

    private var page = 1
    private var loadJob: Job? = null

    init {
        if (!ServiceLocator.session.isLoggedIn) {
            sendEffect(OrderListEffect.RequireLogin)
        } else {
            refresh()
        }
    }

    override fun onIntent(intent: OrderListIntent) {
        when (intent) {
            OrderListIntent.Refresh -> refresh()
            OrderListIntent.LoadMore -> loadMore()
            is OrderListIntent.Cancel -> cancel(intent.orderId)
            is OrderListIntent.Confirm -> confirm(intent.orderId)
        }
    }

    private fun refresh() {
        if (!ServiceLocator.session.isLoggedIn) {
            sendEffect(OrderListEffect.RequireLogin)
            return
        }
        page = 1
        setState { copy(loading = orders.isEmpty(), error = "") }
        loadJob?.cancel()
        loadJob = launchPage(reset = true)
    }

    private fun loadMore() {
        val s = currentState
        if (s.loading || s.loadingMore || !s.hasMore) return
        setState { copy(loadingMore = true) }
        launchPage(reset = false)
    }

    private fun launchPage(reset: Boolean) = viewModelScope.launch {
        try {
            val next = if (reset) 1 else page + 1
            val resp = safeCall {
                api.orders(status = OrderTabs.statusParam(tab), page = next, pageSize = PAGE_SIZE)
            }
            page = next
            setState {
                copy(
                    loading = false,
                    loadingMore = false,
                    orders = if (reset) resp.list else orders + resp.list,
                    hasMore = resp.list.isNotEmpty() && (if (reset) resp.list.size else orders.size + resp.list.size) < resp.total,
                    error = "",
                )
            }
        } catch (e: Throwable) {
            setState {
                copy(
                    loading = false,
                    loadingMore = false,
                    error = if (orders.isEmpty()) e.toApiMessage() else "",
                )
            }
            if (isReloginError(e)) sendEffect(OrderListEffect.RequireLogin)
        }
    }

    /** 取消订单：POST /api/v1/orders/{id}/cancel（服务端校验状态机，仅待付款可取消） */
    private fun cancel(orderId: Long) {
        setState { copy(opOrderId = orderId) }
        viewModelScope.launch {
            try {
                safeCall { api.cancelOrder(orderId, mapOf("reason" to "用户主动取消")) }
                sendEffect(OrderListEffect.Toast("订单已取消"))
                refresh()
            } catch (e: Throwable) {
                sendEffect(OrderListEffect.Toast(e.toApiMessage()))
            } finally {
                setState { copy(opOrderId = null) }
            }
        }
    }

    /** 确认收货：POST /api/v1/orders/{id}/confirm（仅待收货可确认，服务端校验） */
    private fun confirm(orderId: Long) {
        setState { copy(opOrderId = orderId) }
        viewModelScope.launch {
            try {
                safeCall { api.confirmOrder(orderId) }
                sendEffect(OrderListEffect.Toast("确认收货成功"))
                refresh()
            } catch (e: Throwable) {
                sendEffect(OrderListEffect.Toast(e.toApiMessage()))
            } finally {
                setState { copy(opOrderId = null) }
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}

/** 订单列表 VM 工厂：Tab 作为构造参数，每个 Tab 独立一份 VM（保留各自分页与滚动位置） */
class OrderListVmFactory(private val tabKey: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OrderListViewModel(OrderTabs.byKey(tabKey)) as T
}

/** 从服务端 actions[] 里找指定类型（cancel/confirm/pay…），前端不自造按钮 */
fun List<ActionView>.findAction(type: String): ActionView? = firstOrNull { it.type == type }
