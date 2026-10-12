package com.easyshopping.android.feature.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.easyshopping.android.core.network.HotWord
import com.easyshopping.android.core.network.ProductCard
import com.easyshopping.android.core.ui.components.EmptyState
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.OriginPriceText
import com.easyshopping.android.core.ui.components.PriceText
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary

/**
 * 首页（P0）：搜索入口 + 热搜词 + 商品双列瀑布流（cursor/翻页加载更多）。
 *
 * TODO(P1)：Banner/金刚区/秒杀位 —— 后端 `GET /api/v1/aggregation/home` 尚未实现
 * （android-frontend 1.6.2 登记为待契约），聚合接口就绪后在此接入一次拉齐
 * banners/categories/recommendations/unreadCount/cartInfo。
 */
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    onOpenSearchResult: (String) -> Unit,
    onOpenProduct: (Long) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.onIntent(HomeIntent.Refresh) }

    // 一次性副作用：热搜词点击跳搜索结果页
    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                is HomeEffect.OpenSearch -> onOpenSearchResult(e.keyword)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部搜索入口（占位输入框，点击跳搜索页 —— 4.1.1 HomeTopBar）
        HomeSearchBar(onClick = onOpenSearch)

        when {
            state.loading && state.products.isEmpty() -> LoadingState()
            state.error.isNotEmpty() && state.products.isEmpty() -> EmptyState(
                message = state.error,
                actionText = "重试",
                onAction = { vm.onIntent(HomeIntent.Refresh) },
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.hotWords.isNotEmpty()) {
                    item(key = "hot") { HotWordSection(state.hotWords) { keyword ->
                        vm.onHotWord(keyword)
                    } }
                }
                items(
                    count = (state.products.size + 1) / 2,
                    key = { it },
                ) { row ->
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        Box(Modifier.weight(1f)) {
                            state.products.getOrNull(row * 2)?.let {
                                ProductCardItem(it) { onOpenProduct(it.spuId) }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            state.products.getOrNull(row * 2 + 1)?.let {
                                ProductCardItem(it) { onOpenProduct(it.spuId) }
                            }
                        }
                    }
                }
                if (state.loadingMore) {
                    item(key = "more") {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("加载中…", color = TextSecondary, fontSize = 12.sp) }
                    }
                } else if (!state.hasMore) {
                    item(key = "end") {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("没有更多了", color = TextSecondary, fontSize = 12.sp) }
                    }
                }
                // 触底自动加载（列表长度变化时重新触发，避免只加载一次）
                item(key = "trigger") {
                    LaunchedEffect(state.products.size) { vm.onIntent(HomeIntent.LoadMore) }
                }
            }
        }
    }
}

@Composable
private fun HomeSearchBar(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.background)
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = "搜索", tint = TextSecondary, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("搜索商品", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun HotWordSection(words: List<HotWord>, onWord: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("热门搜索", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            words.take(6).forEach { w ->
                Text(
                    text = w.keyword,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(BrandOrange.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable { onWord(w.keyword) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 双列商品卡片（列表图 400x400 WebP 口径见 1.6.6，Coil 按 URL 载入）。 */
@Composable
private fun ProductCardItem(card: ProductCard, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(bottom = 8.dp),
    ) {
        AsyncImage(
            model = card.mainImage,
            contentDescription = card.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background),
        )
        Text(
            text = card.name,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            PriceText(card.price, fontSize = 15)
            Spacer(Modifier.width(6.dp))
            OriginPriceText(card.originalPrice, card.price)
            Spacer(Modifier.weight(1f))
            Text("已售${card.salesCount}", color = TextSecondary, fontSize = 11.sp)
        }
        if (card.skuCount > 1) {
            Text(
                text = "共${card.skuCount}种规格",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
