package com.easyshopping.android.feature.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.easyshopping.android.core.network.CartItem
import com.easyshopping.android.core.network.Money
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.PriceText
import com.easyshopping.android.core.ui.components.PrimaryButton
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary

/**
 * 结算页（plans/android-frontend.md 4.5）：收货地址 + 商品清单 + 优惠券（降级）+ 备注 + 提交。
 * 金额展示为本地预估；真实金额以下单响应为准（core-price 实时计价）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    onBack: () -> Unit,
    onOrdered: (Long) -> Unit,
    onRequireLogin: () -> Unit,
    initialItems: List<CartItem> = emptyList(),
    vm: CheckoutViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        if (initialItems.isNotEmpty()) vm.setItems(initialItems)
        vm.effect.collect { e ->
            when (e) {
                is CheckoutEffect.Ordered -> onOrdered(e.orderId)
                is CheckoutEffect.Toast -> snackbar.showSnackbar(e.message)
                CheckoutEffect.RequireLogin -> onRequireLogin()
                CheckoutEffect.NeedAddress -> snackbar.showSnackbar("请先添加收货地址")
            }
        }
    }
    LaunchedEffect(state.messageId) {
        if (state.messageId > 0 && state.error.isNotEmpty()) snackbar.showSnackbar(state.error)
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("确认订单") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { CheckoutBottomBar(vm.estimatedPayable(), state.submitting) { vm.onIntent(CheckoutIntent.Submit) } },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item(key = "address") { AddressCard(state.address) }
                item(key = "divider1") { HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider) }

                items(state.items.size, key = { state.items[it].cartItemId }) { i ->
                    CheckoutItemRow(state.items[i])
                }
                item(key = "divider2") { HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider) }

                if (state.coupons.isNotEmpty()) {
                    item(key = "coupon_title") {
                        SectionTitle("优惠券")
                    }
                    items(state.coupons.size, key = { state.coupons[it].couponId }) { i ->
                        CouponRow(
                            coupon = state.coupons[i],
                            selected = state.selectedCouponId == state.coupons[i].couponId,
                            onClick = {
                                vm.onIntent(
                                    CheckoutIntent.SelectCoupon(
                                        if (state.selectedCouponId == state.coupons[i].couponId) null
                                        else state.coupons[i].couponId,
                                    ),
                                )
                            },
                        )
                    }
                    item(key = "coupon_note") {
                        Text(
                            "P0 无结算预览接口，此处展示“我的未使用券”，最终优惠以下单结果为准",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                item(key = "divider3") { HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider) }

                item(key = "remark") {
                    Column(Modifier.padding(12.dp)) {
                        SectionTitle("订单备注")
                        OutlinedTextField(
                            value = state.remark,
                            onValueChange = { vm.onIntent(CheckoutIntent.RemarkChanged(it)) },
                            placeholder = { Text("选填，可填写与卖家协商的内容", color = TextSecondary, fontSize = 13.sp) },
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun AddressCard(address: com.easyshopping.android.core.network.Address?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        if (address == null) {
            Text(
                "暂无收货地址，请先在「我的-地址管理」添加",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(address.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(address.mobile, color = TextSecondary, fontSize = 13.sp)
                    if (address.isDefault == true) {
                        Spacer(Modifier.width(6.dp))
                        Text("默认", color = BrandOrange, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(address.fullAddress, color = TextSecondary, fontSize = 13.sp, maxLines = 3)
            }
        }
    }
}

@Composable
private fun CheckoutItemRow(item: CartItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.mainImage,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PriceText(item.price, fontSize = 14)
        }
        Text("x${item.quantity}", color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun CouponRow(
    coupon: com.easyshopping.android.core.network.CouponRecord,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(coupon.name ?: "优惠券", fontSize = 14.sp)
            coupon.minAmount?.takeIf { it != "0" }?.let {
                Text("满 ${Money.twoDecimals(it)} 可用", color = TextSecondary, fontSize = 11.sp)
            }
        }
        Text(
            "-¥" + Money.twoDecimals(coupon.value),
            color = MaterialTheme.colorScheme.error,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .border(
                    width = 1.5.dp,
                    color = if (selected) BrandOrange else TextSecondary,
                    shape = RoundedCornerShape(9.dp),
                )
                .background(if (selected) BrandOrange else MaterialTheme.colorScheme.background),
        )
    }
}

@Composable
private fun CheckoutBottomBar(estimated: String, submitting: Boolean, onSubmit: () -> Unit) {
    androidx.compose.material3.Surface(tonalElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("预估合计（以下单结果为准）", color = TextSecondary, fontSize = 11.sp)
                PriceText(estimated, fontSize = 18)
            }
            Box(Modifier.width(160.dp)) {
                PrimaryButton(
                    text = if (submitting) "提交中…" else "提交订单",
                    onClick = onSubmit,
                    loading = submitting,
                )
            }
        }
    }
}
