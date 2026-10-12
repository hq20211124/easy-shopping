package com.easyshopping.android.feature.search

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.HotWord
import com.easyshopping.android.core.network.SuggestionItem
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SearchUiState(
    val keyword: String = "",
    val suggestions: List<SuggestionItem> = emptyList(),
    val hotWords: List<HotWord> = emptyList(),
    /** 本地搜索历史（P0 SharedPreferences；P1 迁 Room 并做云端同步） */
    val history: List<String> = emptyList(),
    val searching: Boolean = false,
)

sealed interface SearchIntent {
    data class KeywordChanged(val value: String) : SearchIntent
    data class Submit(val keyword: String) : SearchIntent
    data class RemoveHistory(val keyword: String) : SearchIntent
    data object ClearHistory : SearchIntent
}

sealed interface SearchEffect {
    data class GoResult(val keyword: String) : SearchEffect
}

/**
 * 搜索首页：输入联想（防抖 300ms）+ 热搜词 + 本地历史。
 * 真正的结果列表在 SearchResultScreen（cursor 分页），这里只负责"选词"。
 */
class SearchViewModel(
    private val api: Api = ServiceLocator.api,
    private val historyStore: SearchHistoryStore = SearchHistoryStore(ServiceLocator.appContext),
) : MviViewModel<SearchUiState, SearchIntent, SearchEffect>(SearchUiState(history = historyStore.load())) {

    private var suggestJob: Job? = null

    override fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.KeywordChanged -> onKeywordChanged(intent.value)
            is SearchIntent.Submit -> submit(intent.keyword)
            is SearchIntent.RemoveHistory -> {
                historyStore.remove(intent.keyword)
                setState { copy(history = historyStore.load()) }
            }
            SearchIntent.ClearHistory -> {
                historyStore.clear()
                setState { copy(history = emptyList()) }
            }
        }
    }

    private fun onKeywordChanged(value: String) {
        setState { copy(keyword = value) }
        suggestJob?.cancel()
        if (value.isBlank()) {
            setState { copy(suggestions = emptyList(), searching = false) }
            return
        }
        // 防抖：连续输入只在停顿后请求一次联想
        suggestJob = viewModelScope.launch {
            delay(300)
            setState { copy(searching = true) }
            runCatching { safeCall { api.suggestions(value.trim(), 8) } }
                .onSuccess { resp -> setState { copy(suggestions = resp.suggestions, searching = false) } }
                .onFailure { setState { copy(searching = false) } }
        }
    }

    private fun submit(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        historyStore.add(keyword)
        setState { copy(history = historyStore.load()) }
        sendEffect(SearchEffect.GoResult(keyword))
    }

    init {
        viewModelScope.launch {
            runCatching { safeCall { api.hotWords(10) } }
                .onSuccess { words -> setState { copy(hotWords = words) } }
        }
    }
}

/**
 * 搜索历史本地存储（P0 SharedPreferences，最多 10 条，去重 + 最近优先）。
 * TODO(P1)：迁 Room + 账号云同步（plans/android-frontend.md 4.2.2）。
 */
class SearchHistoryStore(context: Context) {
    private val sp = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun load(): List<String> = sp.getStringSet(KEY, emptySet())?.toList().orEmpty()

    fun add(keyword: String) {
        val next = LinkedHashSet<String>()
        next.add(keyword)
        next.addAll(load().filter { it != keyword })
        sp.edit().putStringSet(KEY, next.take(MAX).toSet()).apply()
    }

    fun remove(keyword: String) {
        sp.edit().putStringSet(KEY, load().filter { it != keyword }.toSet()).apply()
    }

    fun clear() = sp.edit().remove(KEY).apply()

    companion object {
        private const val KEY = "history"
        private const val MAX = 10
    }
}
