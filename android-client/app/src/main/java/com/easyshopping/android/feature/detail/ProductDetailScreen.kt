package com.easyshopping.android.feature.detail

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
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.easyshopping.android.core.network.ProductDetail
import com.easyshopping.android.core.ui.components.EmptyState
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.OriginPriceText
import com.easyshopping.android.core.ui.components.PriceText
import com.easyshopping.android.core.ui.components.PrimaryButton
import com.easyshopping.android.core.ui.components.QuantityStepper
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary

/**
 * 商品详情页（plans/android-frontend.md 4.4）：主图/标题/价格 + 多规格 SKU 矩阵 + 底部加购。
 * 规格矩阵：不可组合置灰（valueEnabled），命中 SKU 后展示其标题；未选齐时提示"请选择规格"。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    spuId: Long,
    onBack: () -> Unit,
    onGoCart: () -> Unit,
    onRequireLogin: () -> Unit,
    vm: ProductDetailViewModel = viewModel(
        key = "product_detail_$spuId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ProductDetailViewModel(spuId) as T
        },
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showSpecSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                is ProductDetailEffect.Added -> {
                    showSpecSheet = false
                    snackbar.showSnackbar("已加入购物车（共 ${e.totalQuantity} 件）")
                }

                ProductDetailEffect.RequireLogin -> onRequireLogin()
            }
        }
    }
    // 加购错误（含 3002 库存不足）：直接展示服务端 msg
    LaunchedEffect(state.messageId) {
        if (state.messageId > 0 && state.error.isNotEmpty()) snackbar.showSnackbar(state.error)
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("商品详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onGoCart) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = "购物车")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { DetailBottomBar(state, onSpec = { showSpecSheet = true }, onAdd = { vm.onIntent(ProductDetailIntent.AddToCart) }) },
    ) { padding ->
        val detail = state.detail
        when {
            state.loading && detail == null -> LoadingState(Modifier.padding(padding))
            detail == null -> EmptyState(
                message = state.error.ifEmpty { "商品不存在或已下架" },
                actionText = "重试",
                onAction = { vm.onIntent(ProductDetailIntent.Reload) },
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item(key = "image") {
                    AsyncImage(
                        model = state.matchedSku?.images?.firstOrNull() ?: detail.mainImage,
                        contentDescription = detail.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
                item(key = "info") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            PriceText(state.matchedSku?.price ?: detail.price, fontSize = 22)
                            Spacer(Modifier.width(8.dp))
                            OriginPriceText(detail.originalPrice, state.matchedSku?.price ?: detail.price)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(detail.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        detail.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = TextSecondary, fontSize = 13.sp, maxLines = 2)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("已售 ${detail.salesCount}", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                item(key = "spec_entry") {
                    SpecEntryRow(detail, state.selectedSpecs) { showSpecSheet = true }
                }
                item(key = "divider1") { HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider) }
                item(key = "desc_title") {
                    Text(
                        "商品详情",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                item(key = "desc") {
                    // TODO(P1)：富文本详情（服务端目前返回纯文本/HTML 片段，P0 按纯文本渲染）
                    Text(
                        detail.detail?.takeIf { it.isNotBlank() } ?: "暂无详情",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }

    if (showSpecSheet && state.detail != null) {
        SpecSheet(
            detail = state.detail,
            state = state,
            onDismiss = { showSpecSheet = false },
            onSelect = { n, v -> vm.onIntent(ProductDetailIntent.SelectSpec(n, v)) },
            onQuantity = { d -> vm.onIntent(ProductDetailIntent.ChangeQuantity(d)) },
            onConfirm = { vm.onIntent(ProductDetailIntent.AddToCart) },
        )
    }
}

/** 详情页规格入口：展示已选摘要，未选齐时引导选择 */
@Composable
private fun SpecEntryRow(detail: ProductDetail, selected: Map<String, String>, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("选择规格", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (selected.isEmpty()) "暂未选择" else selected.values.joinToString(" / "),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(">", color = TextSecondary, fontSize = 14.sp)
    }
}

/** 底部操作栏：加购按钮（未选规格时先弹规格面板） */
@Composable
private fun DetailBottomBar(
    state: ProductDetailUiState,
    onSpec: () -> Unit,
    onAdd: () -> Unit,
) {
    androidx.compose.material3.Surface(tonalElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val allSelected = state.detail?.specs?.all { state.selectedSpecs.containsKey(it.name) } == true
            Box(Modifier.weight(1f)) {
                PrimaryButton(
                    text = if (state.adding) "加购中…" else "加入购物车",
                    onClick = { if (allSelected && state.matchedSku != null) onAdd() else onSpec() },
                    loading = state.adding,
                )
            }
        }
    }
}

/**
 * 规格选择弹层（SKU 矩阵）：点击值 → SelectSpec；不可用值置灰。
 * 确认按钮：规格齐全 → 加购；否则提示。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SpecSheet(
    detail: ProductDetail,
    state: ProductDetailUiState,
    onDismiss: () -> Unit,
    onSelect: (String, String) -> Unit,
    onQuantity: (Int) -> Unit,
    onConfirm: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = state.matchedSku?.images?.firstOrNull() ?: detail.mainImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    PriceText(state.matchedSku?.price ?: detail.price, fontSize = 20)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.matchedSku != null) (state.matchedSku.title ?: detail.name)
                        else if (state.selectedSpecs.isEmpty()) detail.name
                        else "${state.selectedSpecs.values.joinToString("/")}",
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)
            Spacer(Modifier.height(8.dp))

            detail.specs.forEach { group ->
                Text(group.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.values.forEach { value ->
                        val enabled = state.valueEnabled[ProductDetailViewModel.key(group.name, value)] ?: true
                        val selected = state.selectedSpecs[group.name] == value
                        SpecValueChip(
                            text = value,
                            selected = selected,
                            enabled = enabled,
                            onClick = { onSelect(group.name, value) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("数量", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                QuantityStepper(
                    quantity = state.quantity,
                    onDecrease = { onQuantity(-1) },
                    onIncrease = { onQuantity(1) },
                )
            }
            Spacer(Modifier.height(16.dp))

            val ready = state.matchedSku != null
            PrimaryButton(
                text = if (ready) "加入购物车" else "请选择规格",
                onClick = { if (ready) onConfirm() },
                enabled = ready,
                loading = state.adding,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpecValueChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.background
        selected -> BrandOrange.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.background
    }
    val fg = when {
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        selected -> BrandOrange
        else -> com.easyshopping.android.core.ui.theme.TextPrimary
    }
    Text(
        text = text,
        fontSize = 13.sp,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}


