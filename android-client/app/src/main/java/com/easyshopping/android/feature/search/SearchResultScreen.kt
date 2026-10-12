package com.easyshopping.android.feature.search

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import com.easyshopping.android.core.network.SearchItem
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.core.ui.components.AppTopBar
import com.easyshopping.android.core.ui.components.EmptyState
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.OriginPriceText
import com.easyshopping.android.core.ui.components.PriceText
import com.easyshopping.android.core.ui.theme.TextSecondary
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class SearchResultUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** 服务端返回的下一页游标；null 且非加载中 = 已到底（core-search README §3.4） */
    val hasMore: Boolean = true,
    val total: Long = 0,
    val items: List<SearchItem> = emptyList(),
    val error: String = "",
)

sealed interface SearchResultIntent {
    data object Refresh : SearchResultIntent
    data object LoadMore : SearchResultIntent
}

/**
 * 搜索结果页：**cursor 分页**（core-search README §3.4）。
 *
 * 关键口径：
 *  - 首屏不带 cursor，翻页带上一页返回的 nextCursor；末页 nextCursor=null → hasMore=false
 *  - 换关键词时由 NavGraph 用 vmKey 重建 VM，**禁止**在旧游标上拼新条件（服务端会忽略）
 */
class SearchResultViewModel(
    private val keyword: String,
    private val api: Api = ServiceLocator.api,
) : MviViewModel<SearchResultUiState, SearchResultIntent, Unit>(SearchResultUiState()) {

    private var cursor: String? = null
    private var loadJob: Job? = null

    init {
        refresh()
    }

    override fun onIntent(intent: SearchResultIntent) {
        when (intent) {
            SearchResultIntent.Refresh -> refresh()
            SearchResultIntent.LoadMore -> loadMore()
        }
    }

    private fun refresh() {
        cursor = null
        setState { copy(loading = items.isEmpty(), error = "", hasMore = true) }
        loadJob?.cancel()
        loadJob = launchLoad(reset = true)
    }

    private fun loadMore() {
        val s = currentState
        if (s.loading || s.loadingMore || !s.hasMore) return
        setState { copy(loadingMore = true) }
        launchLoad(reset = false)
    }

    private fun launchLoad(reset: Boolean) = viewModelScope.launch {
        try {
            val resp = safeCall { api.search(q = keyword, cursor = cursor, pageSize = PAGE_SIZE) }
            cursor = resp.nextCursor
            setState {
                copy(
                    loading = false,
                    loadingMore = false,
                    items = if (reset) resp.list else items + resp.list,
                    total = resp.total,
                    hasMore = !resp.nextCursor.isNullOrBlank(),
                    error = "",
                )
            }
        } catch (e: Throwable) {
            setState {
                copy(
                    loading = false,
                    loadingMore = false,
                    error = if (items.isEmpty()) e.toApiMessage() else "",
                )
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}

/** 换关键词必须换 VM 实例（key 绑定 keyword），否则游标会串页 */
class SearchResultVmFactory(private val keyword: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SearchResultViewModel(keyword) as T
}

@Composable
fun SearchResultScreen(
    keyword: String,
    onOpenProduct: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SearchResultViewModel = viewModel(
        key = "search_result_$keyword",
        factory = SearchResultVmFactory(keyword),
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "“$keyword”的结果", onBack = onBack)

        when {
            state.loading && state.items.isEmpty() -> LoadingState()
            state.error.isNotEmpty() && state.items.isEmpty() -> EmptyState(
                message = state.error,
                actionText = "重试",
                onAction = { vm.onIntent(SearchResultIntent.Refresh) },
            )

            state.items.isEmpty() -> EmptyState(message = "没有找到相关商品")

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "total") {
                    Text(
                        "共 ${state.total} 件商品",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                items(state.items.size, key = { state.items[it].spuId }) { i ->
                    SearchResultItem(state.items[i]) { onOpenProduct(state.items[i].spuId) }
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
                    // 触底自动加载下一页（cursor 取自上一页 nextCursor）
                    LaunchedEffect(state.items.size) { vm.onIntent(SearchResultIntent.LoadMore) }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(item: SearchItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = item.mainImage,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background),
        )
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                PriceText(item.price, fontSize = 15)
                Spacer(Modifier.width(6.dp))
                OriginPriceText(item.originalPrice, item.price)
                Spacer(Modifier.weight(1f))
                Text("已售${item.salesCount}", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}
