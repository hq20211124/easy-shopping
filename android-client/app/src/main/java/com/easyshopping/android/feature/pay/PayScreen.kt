package com.easyshopping.android.feature.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easyshopping.android.core.network.Money
import com.easyshopping.android.core.ui.components.EmptyState
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.PrimaryButton
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.SuccessGreen
import com.easyshopping.android.core.ui.theme.TextSecondary
import java.time.Duration
import java.time.Instant

private data class PayChannel(val code: Int, val label: String)

private val channels = listOf(
    PayChannel(1, "支付宝"),
    PayChannel(2, "微信支付"),
    PayChannel(3, "银行卡"),
    PayChannel(4, "余额"),
)

/**
 * 收银台页（plans/android-frontend.md 4.6）：渠道选择 + pay_deadline 倒计时 + 轮询结果。
 * 支付结果完全以服务端轮询为准（paid 才算成功），本地点击只触发 prepay + mock/pay。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PayScreen(
    orderId: Long,
    onBack: () -> Unit,
    onGoOrders: (String) -> Unit,
    onGoHome: () -> Unit,
    vm: PayViewModel = viewModel(
        key = "pay_$orderId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PayViewModel(orderId) as T
        },
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                is PayEffect.Paid -> onGoOrders("all")
                is PayEffect.Toast -> snackbar.showSnackbar(e.message)
            }
        }
    }
    LaunchedEffect(state.messageId) {
        if (state.messageId > 0 && state.error.isNotEmpty()) snackbar.showSnackbar(state.error)
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("收银台") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.onIntent(PayIntent.StopPolling)
                        onBack()
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.order == null -> EmptyState(
                message = state.error.ifEmpty { "订单不存在" },
                actionText = "重试",
                onAction = { vm.onIntent(PayIntent.Load) },
                modifier = Modifier.padding(padding),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.paid) {
                    PaidBanner(onGoOrders = onGoOrders, onGoHome = onGoHome)
                } else {
                    // 待付款：金额 + pay_deadline 倒计时（不硬编码分钟数，读服务端字段）
                    PayAmountCard(
                        payAmount = state.order?.amountDetail?.payAmount ?: state.order?.items?.firstOrNull()?.totalAmount,
                        payDeadline = state.order?.payDeadline,
                    )
                    HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)
                    ChannelList(state.channel) { vm.onIntent(PayIntent.SelectChannel(it)) }
                    HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        PrimaryButton(
                            text = if (state.paying) "拉起支付中…"
                            else if (state.polling) "支付结果确认中…"
                            else "确认支付",
                            onClick = { vm.onIntent(PayIntent.Pay) },
                            loading = state.paying || state.polling,
                            enabled = !state.polling,
                        )
                    }
                    if (state.polling) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在向服务端确认支付结果…", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                    // TODO(P1)：接入真实三方 SDK（支付宝/微信）后，此处 payParams 用于拉起收银台
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "P0 为模拟支付（走 core-payment 完整入账链路），未接入三方 SDK",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PaidBanner(onGoOrders: (String) -> Unit, onGoHome: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(SuccessGreen.copy(alpha = 0.12f), RoundedCornerShape(36.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = SuccessGreen, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("支付成功", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.width(200.dp)) {
            PrimaryButton(text = "查看订单", onClick = { onGoOrders("all") })
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(200.dp)) {
            PrimaryButton(text = "返回首页", onClick = onGoHome)
        }
    }
}

@Composable
private fun PayAmountCard(payAmount: String?, payDeadline: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text("支付金额", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text("¥" + Money.twoDecimals(payAmount), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        payDeadline?.let { PayCountdown(it) }
    }
}

/** pay_deadline 倒计时：以服务端下发的时间为准（不在客户端写死支付超时分钟数） */
@Composable
private fun PayCountdown(payDeadline: String) {
    val deadline = remember(payDeadline) {
        runCatching { Instant.parse(payDeadline) }.getOrNull()
    }
    if (deadline == null) {
        Text("支付截止：$payDeadline", color = TextSecondary, fontSize = 12.sp)
        return
    }
    // 每秒刷新的剩余时间
    val now = androidx.compose.runtime.produceState(initialValue = Instant.now()) {
        while (true) {
            value = Instant.now()
            kotlinx.coroutines.delay(1000)
        }
    }.value

    val remain = Duration.between(now, deadline)
    val text = if (remain.isNegative) {
        "支付超时，订单将自动关闭"
    } else {
        val total = remain.seconds
        "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (remain.isNegative) text else "支付剩余 $text",
        color = if (remain.isNegative) MaterialTheme.colorScheme.error else BrandOrange,
        fontSize = 13.sp,
    )
}

@Composable
private fun ChannelList(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Text(
            "选择支付方式",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        channels.forEach { ch ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(ch.code) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(BrandOrange.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ch.label.take(1), color = BrandOrange, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(ch.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            if (selected == ch.code) BrandOrange else MaterialTheme.colorScheme.background,
                            RoundedCornerShape(9.dp),
                        ),
                )
            }
        }
    }
}
