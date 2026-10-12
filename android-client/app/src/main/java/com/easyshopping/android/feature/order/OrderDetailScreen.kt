package com.easyshopping.android.feature.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.OrderDetailVO
import com.easyshopping.android.core.network.OrderStatus
import com.easyshopping.android.core.network.TimelineView
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.core.ui.components.EmptyState
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.OrderStatusChip
import com.easyshopping.android.core.ui.components.PriceText
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.launch

data class OrderDetailUiState(
    val loading: Boolean = false,
    val detail: OrderDetailVO? = null,
    val opOrderId: Long? = null,
    val error: String = "",
    val messageId: Int = 0,
)

sealed interface OrderDetailIntent {
    data object Refresh : OrderDetailIntent
    data object Cancel : OrderDetailIntent
    data object Confirm : OrderDetailIntent
}

sealed interface OrderDetailEffect {
    data class Toast(val message: String) : OrderDetailEffect
    data object Changed : OrderDetailEffect
}

/**
 * 订单详情（plans/android-frontend.md 4.7.3）：金额明细 + 商品 + 收货信息 + 时间线 + 操作。
 * 操作按钮同样只按服务端 actions[] 渲染。
 */
class OrderDetailViewModel(
    private val orderId: Long,
    private val api: Api = ServiceLocator.api,
) : MviViewModel<OrderDetailUiState, OrderDetailIntent, OrderDetailEffect>(OrderDetailUiState()) {

    init {
        refresh()
    }

    override fun onIntent(intent: OrderDetailIntent) {
        when (intent) {
            OrderDetailIntent.Refresh -> refresh()
            OrderDetailIntent.Cancel -> act { api.cancelOrder(orderId, mapOf("reason" to "用户主动取消")) }
            OrderDetailIntent.Confirm -> act { api.confirmOrder(orderId) }
        }
    }

    private fun refresh() {
        setState { copy(loading = detail == null, error = "") }
        viewModelScope.launch {
            try {
                val d = safeCall { api.orderDetail(orderId) }
                setState { copy(loading = false, detail = d) }
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
            }
        }
    }

    private fun act(call: suspend () -> Any) {
        setState { copy(opOrderId = orderId) }
        viewModelScope.launch {
            try {
                call()
                refresh()
                sendEffect(OrderDetailEffect.Changed)
            } catch (e: Throwable) {
                sendEffect(OrderDetailEffect.Toast(e.toApiMessage()))
            } finally {
                setState { copy(opOrderId = null) }
            }
        }
    }
}

class OrderDetailVmFactory(private val orderId: Long) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OrderDetailViewModel(orderId) as T
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    orderId: Long,
    onBack: () -> Unit,
    onGoPay: (Long) -> Unit,
    vm: OrderDetailViewModel = viewModel(
        key = "order_detail_$orderId",
        factory = OrderDetailVmFactory(orderId),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                is OrderDetailEffect.Toast -> snackbar.showSnackbar(e.message)
                OrderDetailEffect.Changed -> snackbar.showSnackbar("操作成功")
            }
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("订单详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val d = state.detail
            if (d != null) {
                OrderDetailBottomBar(
                    detail = d,
                    busy = state.opOrderId == orderId,
                    onCancel = { vm.onIntent(OrderDetailIntent.Cancel) },
                    onConfirm = { vm.onIntent(OrderDetailIntent.Confirm) },
                    onGoPay = { onGoPay(orderId) },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.detail == null -> EmptyState(
                message = state.error.ifEmpty { "订单不存在" },
                actionText = "重试",
                onAction = { vm.onIntent(OrderDetailIntent.Refresh) },
                modifier = Modifier.padding(padding),
            )

            else -> {
                val d = state.detail!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    item(key = "status") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(16.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OrderStatusChip(status = d.status, statusText = d.statusText)
                                Spacer(Modifier.weight(1f))
                                Text("单号 ${d.orderNo}", color = TextSecondary, fontSize = 11.sp)
                            }
                            // 待付款：pay_deadline 倒计时（不硬编码分钟数）
                            if (d.status == OrderStatus.UNPAID.value) {
                                Spacer(Modifier.height(8.dp))
                                PayDeadlineText(d.payDeadline, Modifier.fillMaxWidth())
                            }
                        }
                    }
                    item(key = "addr") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp),
                        ) {
                            Text("收货信息", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Row {
                                Text(d.receiverName ?: "-", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(10.dp))
                                Text(d.receiverMobile ?: "-", color = TextSecondary, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(d.receiverAddress ?: "-", color = TextSecondary, fontSize = 13.sp)
                        }
                    }

                    items(d.items.size, key = { d.items[it].skuId }) { i ->
                        val item = d.items[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = item.skuImage,
                                contentDescription = item.skuName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.background),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.skuName, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                PriceText(item.price, fontSize = 13)
                            }
                            Text("x${item.quantity}", color = TextSecondary, fontSize = 13.sp)
                        }
                    }

                    item(key = "amount") {
                        val a = d.amountDetail
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp),
                        ) {
                            Text("金额明细", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            AmountRow("商品金额", a?.goodsAmount)
                            AmountRow("运费", a?.freightAmount)
                            AmountRow("优惠", a?.discountAmount, negative = true)
                            HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Text("实付款", fontSize = 13.sp)
                                Spacer(Modifier.weight(1f))
                                PriceText(a?.payAmount, fontSize = 16)
                            }
                        }
                    }

                    if (d.children.isNotEmpty()) {
                        item(key = "children_title") {
                            Text(
                                "子订单（${d.children.size}）",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        items(d.children.size, key = { d.children[it].orderId }) { i ->
                            val c = d.children[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.orderNo, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(c.statusText, fontSize = 12.sp, color = BrandOrange)
                                Spacer(Modifier.width(8.dp))
                                PriceText(c.payAmount, fontSize = 14)
                            }
                        }
                    }

                    if (d.timeline.isNotEmpty()) {
                        item(key = "timeline_title") {
                            Text(
                                "订单动态",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        items(d.timeline.size, key = { it }) { i ->
                            TimelineRow(d.timeline[i], isLast = i == d.timeline.lastIndex)
                        }
                    }

                    d.remark?.takeIf { it.isNotBlank() }?.let { remark ->
                        item(key = "remark") {
                            Text(
                                "备注：$remark",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String?, negative: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(
            (if (negative) "-¥" else "¥") + com.easyshopping.android.core.network.Money.twoDecimals(value),
            color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TimelineRow(t: TimelineView, isLast: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(BrandOrange, RoundedCornerShape(4.dp)),
            )
            if (!isLast) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(com.easyshopping.android.core.ui.theme.Divider),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                t.toStatusText ?: ("→ 状态${t.toStatus}"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            t.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextSecondary, fontSize = 12.sp)
            }
            t.time?.let {
                Text(it, color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun OrderDetailBottomBar(
    detail: OrderDetailVO,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onGoPay: () -> Unit,
) {
    val hasCancel = detail.actions.any { it.type == "cancel" }
    val hasPay = detail.actions.any { it.type == "pay" }
    val hasConfirm = detail.actions.any { it.type == "confirm" }
    if (!hasCancel && !hasPay && !hasConfirm) return

    androidx.compose.material3.Surface(tonalElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (hasCancel) {
                OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(16.dp)) {
                    Text("取消订单", fontSize = 12.sp, color = TextSecondary)
                }
            }
            if (hasConfirm) {
                OutlinedButton(onClick = onConfirm, shape = RoundedCornerShape(16.dp)) {
                    Text("确认收货", fontSize = 12.sp, color = BrandOrange)
                }
            }
            if (hasPay) {
                OutlinedButton(onClick = onGoPay, enabled = !busy, shape = RoundedCornerShape(16.dp)) {
                    Text("去支付", fontSize = 12.sp, color = BrandOrange)
                }
            }
        }
    }
}
