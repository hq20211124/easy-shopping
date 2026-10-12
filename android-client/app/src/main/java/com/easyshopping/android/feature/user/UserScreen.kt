package com.easyshopping.android.feature.user

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary

/**
 * 「我的」页（plans/android-frontend.md 4.9）：头像/昵称 + 订单 Tab 快捷入口 + 常用功能 + 退出。
 * TODO(P1)：足迹/消息/收藏（后端未提供买家侧读取端点，P0 只留入口不跳转）。
 */
@Composable
fun UserScreen(
    onRequireLogin: () -> Unit,
    onOpenOrders: (String) -> Unit,
    onOpenAddresses: () -> Unit,
    vm: UserViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.needLogin) { if (state.needLogin) onRequireLogin() }
    LaunchedEffect(Unit) { vm.onIntent(UserIntent.Load) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 头部：头像 + 昵称 + 会员信息
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(BrandOrange.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.nickname.ifEmpty { "未登录" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.profile?.mobile ?: state.profile?.levelName ?: "登录后同步订单与优惠券",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            if (state.loading) {
                LoadingState(Modifier.size(24.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        // 订单快捷入口（Tab key 与 OrderTabs 一致）
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                OrderEntry("待付款", "unpaid", Modifier.weight(1f), onOpenOrders)
                OrderEntry("待发货", "paid", Modifier.weight(1f), onOpenOrders)
                OrderEntry("待收货", "shipped", Modifier.weight(1f), onOpenOrders)
                OrderEntry("已完成", "completed", Modifier.weight(1f), onOpenOrders)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    Text(
                        "全部订单",
                        color = BrandOrange,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clickable { onOpenOrders("all") }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        MenuRow("地址管理", onClick = onOpenAddresses)
        HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)
        // TODO(P1)：足迹/消息/收藏（后端未提供买家侧读取端点，P0 留占位不跳转）
        MenuRow("足迹", placeholder = true)
        HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)
        MenuRow("消息", placeholder = true)
        HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)
        MenuRow("收藏", placeholder = true)

        Spacer(Modifier.height(24.dp))
        Text(
            "退出登录",
            color = MaterialTheme.colorScheme.error,
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable { vm.onIntent(UserIntent.Logout) }
                .padding(vertical = 14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OrderEntry(label: String, tabKey: String, modifier: Modifier, onOpen: (String) -> Unit) {
    Column(
        modifier = modifier
            .clickable { onOpen(tabKey) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun MenuRow(label: String, placeholder: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (placeholder) {
            Text("敬请期待", color = TextSecondary, fontSize = 12.sp)
        } else {
            Text(">", color = TextSecondary, fontSize = 14.sp)
        }
    }
}
