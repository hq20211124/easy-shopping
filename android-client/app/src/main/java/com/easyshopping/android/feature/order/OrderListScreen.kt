package com.easyshopping.android.feature.order

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.easyshopping.android.core.network.OrderListVO
import com.easyshopping.android.core.network.OrderStatus
import com.easyshopping.android.core.ui.components.EmptyState
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.OrderStatusChip
import com.easyshopping.android.core.ui.components.PriceText
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary

/**
 * 订单列表（plans/android-frontend.md 4.7）：Tab 过滤 + page 分页 + 行内操作（取消/去支付/确认收货）。
 * 操作按钮一律按服务端 actions[] 渲染；金额与状态用服务端原文。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OrderListScreen(
    initialTab: String,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onGoPay: (Long) -> Unit,
) {
    var selectedTab by remember {
        androidx.compose.runtime.mutableStateOf(OrderTabs.byKey(initialTab).key)
    }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("我的订单") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = OrderTabs.all.indexOfFirst { it.key == selectedTab }) {
                OrderTabs.all.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab.key,
                        onClick = { selectedTab = tab.key },
                        text = { Text(tab.label, fontSize = 13.sp) },
                    )
                }
            }
            // key = tab：切 Tab 即换 VM 实例，各自独立分页/滚动
            OrderListContent(
                tabKey = selectedTab,
                snackbar = snackbar,
                onOpenDetail = onOpenDetail,
                onGoPay = onGoPay,
            )
        }
    }
}

@Composable
private fun OrderListContent(
    tabKey: String,
    snackbar: SnackbarHostState,
    onOpenDetail: (Long) -> Unit,
    onGoPay: (Long) -> Unit,
    vm: OrderListViewModel = viewModel(
        key = "order_list_$tabKey",
        factory = OrderListVmFactory(tabKey),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.onIntent(OrderListIntent.Refresh) }
    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                is OrderListEffect.Toast -> snackbar.showSnackbar(e.message)
                OrderListEffect.RequireLogin -> snackbar.showSnackbar("登录已失效，请重新登录")
            }
        }
    }

    when {
        state.loading && state.orders.isEmpty() -> LoadingState()
        state.error.isNotEmpty() && state.orders.isEmpty() -> EmptyState(
            message = state.error,
            actionText = "重试",
            onAction = { vm.onIntent(OrderListIntent.Refresh) },
        )

        state.orders.isEmpty() -> EmptyState(message = "暂无订单")

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(state.orders.size, key = { state.orders[it].orderId }) { i ->
                val order = state.orders[i]
                OrderCard(
                    order = order,
                    busy = state.opOrderId == order.orderId,
                    onOpen = { onOpenDetail(order.orderId) },
                    onCancel = { vm.onIntent(OrderListIntent.Cancel(order.orderId)) },
                    onConfirm = { vm.onIntent(OrderListIntent.Confirm(order.orderId)) },
                    onGoPay = { onGoPay(order.orderId) },
                )
            }
            item(key = "footer") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            state.loadingMore -> "加载中…"
                            !state.hasMore -> "没有更多了"
                            else -> ""
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                LaunchedEffect(state.orders.size) { vm.onIntent(OrderListIntent.LoadMore) }
            }
        }
    }
}

/**
 * 订单卡片：状态 chip + 商品缩略 + 合计 + 操作按钮。
 * 操作按钮来自服务端 actions[]，前端只负责把 type 映射到行为。
 */
@Composable
private fun OrderCard(
    order: OrderListVO,
    busy: Boolean,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onGoPay: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onOpen() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                order.orderNo,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OrderStatusChip(status = order.status, statusText = order.statusText)
        }
        Spacer(Modifier.height(8.dp))

        order.items.take(2).forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = item.skuImage,
                    contentDescription = item.skuName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.background),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.skuName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PriceText(item.price, fontSize = 13)
                }
                Text("x${item.quantity}", color = TextSecondary, fontSize = 12.sp)
            }
        }
        if (order.items.size > 2) {
            Text(
                "等共 ${order.itemCount} 件",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // 待付款展示支付倒计时（读 payDeadline，不硬编码分钟数）
            if (order.status == OrderStatus.UNPAID.value) {
                PayDeadlineText(order.payDeadline, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text("共 ${order.itemCount} 件，合计：", color = TextSecondary, fontSize = 12.sp)
            PriceText(order.payAmount, fontSize = 16)
        }

        // 操作按钮：仅渲染服务端 actions[] 里出现的动作
        val hasCancel = order.actions.any { it.type == "cancel" }
        val hasPay = order.actions.any { it.type == "pay" }
        val hasConfirm = order.actions.any { it.type == "confirm" }
        if (hasCancel || hasPay || hasConfirm) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                if (hasCancel) {
                    SmallAction("取消订单", primary = false, onClick = onCancel)
                }
                if (hasPay) {
                    SmallAction("去支付", primary = true, onClick = onGoPay)
                }
                if (hasConfirm) {
                    SmallAction("确认收货", primary = true, onClick = onConfirm)
                }
            }
        }
    }
}

/** 待付款倒计时：以服务端 payDeadline 为准 */
@Composable
internal fun PayDeadlineText(payDeadline: String?, modifier: Modifier = Modifier) {
    val deadline = remember(payDeadline) { runCatching { java.time.Instant.parse(payDeadline) }.getOrNull() }
    if (deadline == null) {
        if (payDeadline != null) Text("支付截止 $payDeadline", color = TextSecondary, fontSize = 11.sp, modifier = modifier)
        return
    }
    val now = androidx.compose.runtime.produceState(initialValue = java.time.Instant.now()) {
        while (true) {
            value = java.time.Instant.now()
            kotlinx.coroutines.delay(1000)
        }
    }.value
    val remain = java.time.Duration.between(now, deadline)
    Text(
        text = if (remain.isNegative) "支付已超时" else "剩余支付时间 " + formatRemain(remain.seconds),
        color = if (remain.isNegative) MaterialTheme.colorScheme.error else BrandOrange,
        fontSize = 11.sp,
        modifier = modifier,
    )
}

private fun formatRemain(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

@Composable
private fun SmallAction(text: String, primary: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = true,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (primary) BrandOrange else TextSecondary,
        )
    }
}
