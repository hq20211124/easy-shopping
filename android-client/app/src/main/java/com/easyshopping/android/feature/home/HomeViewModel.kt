package com.easyshopping.android.feature.home

import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.HotWord
import com.easyshopping.android.core.network.ProductCard
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val products: List<ProductCard> = emptyList(),
    val hotWords: List<HotWord> = emptyList(),
    val error: String = "",
)

sealed interface HomeIntent {
    data object Refresh : HomeIntent
    data object LoadMore : HomeIntent
}

sealed interface HomeEffect {
    data class OpenSearch(val keyword: String) : HomeEffect
}

/**
 * 首页：商品流 page 分页（core-product `/api/v1/products`），热搜词来自 core-search `/api/v1/search/hot`。
 * 热搜点击 → Effect 交由 Nav 跳转，ViewModel 不持有 Navigator（单向数据流）。
 */
class HomeViewModel(
    private val api: Api = ServiceLocator.api,
) : MviViewModel<HomeUiState, HomeIntent, HomeEffect>(HomeUiState()) {

    private var page = 1
    private var loadJob: Job? = null

    init {
        loadHotWords()
    }

    override fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.Refresh -> refresh()
            HomeIntent.LoadMore -> loadMore()
        }
    }

    /** 热搜词点击 → Effect 跳转（Nav 由 Screen 收集，ViewModel 不持 Navigator）。 */
    fun onHotWord(keyword: String) {
        sendEffect(HomeEffect.OpenSearch(keyword))
    }

    private fun refresh() {
        page = 1
        setState { copy(loading = products.isEmpty(), error = "") }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val resp = safeCall { api.products(page = 1, pageSize = PAGE_SIZE) }
                setState {
                    copy(
                        loading = false,
                        products = resp.list,
                        hasMore = resp.list.size < resp.total,
                        error = "",
                    )
                }
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
            }
        }
        loadHotWords()
    }

    private fun loadMore() {
        val s = currentState
        if (s.loading || s.loadingMore || !s.hasMore) return
        setState { copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val next = page + 1
                val resp = safeCall { api.products(page = next, pageSize = PAGE_SIZE) }
                page = next
                setState {
                    copy(
                        loadingMore = false,
                        products = products + resp.list,
                        hasMore = resp.list.isNotEmpty() && products.size + resp.list.size < resp.total,
                    )
                }
            } catch (e: Throwable) {
                setState { copy(loadingMore = false) }
            }
        }
    }

    private fun loadHotWords() {
        viewModelScope.launch {
            // 热搜失败不阻塞首页（降级隐藏）
            runCatching { safeCall { api.hotWords(6) } }
                .onSuccess { words -> setState { copy(hotWords = words) } }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}
