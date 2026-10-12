package com.easyshopping.android.feature.cart

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.easyshopping.android.core.network.CartItem
import com.easyshopping.android.core.ui.components.EmptyState
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.PriceText
import com.easyshopping.android.core.ui.components.QuantityStepper
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary

/**
 * 购物车页（plans/android-frontend.md 4.3）：有效区 + 失效区 + 全选 + 合计结算。
 * 金额展示全部走服务端返回的 payableAmount（core-cart 是权威金额来源）。
 */
@Composable
fun CartScreen(
    onOpenProduct: (Long) -> Unit,
    onCheckout: () -> Unit,
    onRequireLogin: () -> Unit,
    vm: CartViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 登录态由 NavGraph 统一接管
    LaunchedEffect(state.needLogin) { if (state.needLogin) onRequireLogin() }

    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                is CartEffect.Toast -> snackbar.showSnackbar(e.message)
                CartEffect.RequireLogin -> onRequireLogin()
            }
        }
    }

    val validItems = state.items.filter { !it.invalid }
    val invalidItems = state.items.filter { it.invalid }
    val allSelected = validItems.isNotEmpty() && validItems.all { it.selected }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
            Text("购物车", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)

        when {
            state.loading && state.items.isEmpty() -> LoadingState()
            state.error.isNotEmpty() && state.items.isEmpty() -> EmptyState(
                message = state.error,
                actionText = "重试",
                onAction = { vm.onIntent(CartIntent.Refresh) },
            )

            state.items.isEmpty() -> EmptyState(message = "购物车还是空的，去逛逛吧")

            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(validItems.size, key = { validItems[it].cartItemId }) { i ->
                    CartRow(
                        item = validItems[i],
                        busy = state.opItemId == validItems[i].cartItemId,
                        onOpen = { validItems[i].productId?.let(onOpenProduct) },
                        onIncrease = { vm.onIntent(CartIntent.Increase(validItems[i])) },
                        onDecrease = { vm.onIntent(CartIntent.Decrease(validItems[i])) },
                        onToggle = { vm.onIntent(CartIntent.ToggleSelect(validItems[i])) },
                        onRemove = { vm.onIntent(CartIntent.Remove(validItems[i])) },
                    )
                }
                if (invalidItems.isNotEmpty()) {
                    item(key = "invalid_header") {
                        Text(
                            "失效商品（${invalidItems.size}）",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    items(invalidItems.size, key = { invalidItems[it].cartItemId }) { i ->
                        InvalidCartRow(invalidItems[i]) {
                            vm.onIntent(CartIntent.Remove(invalidItems[i]))
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        "上限 ${state.maxItems} 件由服务端下发，超出请分批下单",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (state.items.isNotEmpty()) {
            CartSettleBar(
                allSelected = allSelected,
                selectedQuantity = state.selectedQuantity,
                payableAmount = state.payableAmount,
                onToggleAll = { vm.onIntent(CartIntent.ToggleSelectAll) },
                onCheckout = onCheckout,
            )
        }
        SnackbarHost(snackbar)
    }
}

@Composable
private fun CartRow(
    item: CartItem,
    busy: Boolean,
    onOpen: () -> Unit,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (item.selected) "取消选择" else "选择",
            tint = if (item.selected) BrandOrange else TextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { onToggle() }
                .padding(4.dp),
        )
        Spacer(Modifier.width(8.dp))
        AsyncImage(
            model = item.mainImage,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background)
                .clickable { onOpen() },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onOpen() },
            )
            item.discountTags.forEach { tag ->
                Text(tag, color = BrandOrange, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriceText(item.price, fontSize = 15)
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    QuantityStepper(
                        quantity = item.quantity,
                        onDecrease = onDecrease,
                        onIncrease = onIncrease,
                        showDelete = true,
                        onDelete = onRemove,
                    )
                }
            }
            if (!item.inStock) {
                Text("库存不足", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
    }
}

/** 失效区（下架/SKU 变更）：置灰 + 一键清理 */
@Composable
private fun InvalidCartRow(item: CartItem, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.mainImage,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 13.sp,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.LineThrough,
            )
            Text("商品已失效", color = TextSecondary, fontSize = 11.sp)
        }
        Text(
            "删除",
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { onRemove() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 底部结算栏：全选 + 合计 + 去结算 */
@Composable
private fun CartSettleBar(
    allSelected: Boolean,
    selectedQuantity: Int,
    payableAmount: String,
    onToggleAll: () -> Unit,
    onCheckout: () -> Unit,
) {
    Surface(tonalElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (allSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = "全选",
                tint = if (allSelected) BrandOrange else TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onToggleAll() }
                    .padding(4.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("全选", fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("已选 $selectedQuantity 件", color = TextSecondary, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("合计：", fontSize = 13.sp)
                    PriceText(payableAmount, fontSize = 17)
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selectedQuantity > 0) BrandOrange else TextSecondary.copy(alpha = 0.3f))
                    .clickable(enabled = selectedQuantity > 0) { onCheckout() }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("去结算($selectedQuantity)", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp)
            }
        }
    }
}

