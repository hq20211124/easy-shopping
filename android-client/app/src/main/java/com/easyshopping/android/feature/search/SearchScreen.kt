package com.easyshopping.android.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easyshopping.android.core.ui.components.AppTopBar
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary

/**
 * 搜索输入页：联想建议 + 热搜词 + 本地历史（plans/android-frontend.md 4.2.1）。
 * 提交后跳 SearchResult（cursor 分页在结果页处理）。
 */
@Composable
fun SearchScreen(
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var submitted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                is SearchEffect.GoResult -> {
                    if (!submitted) {   // 防止 IME 搜索键重复触发两次跳转
                        submitted = true
                        focusManager.clearFocus()
                        onSearch(e.keyword)
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "搜索", onBack = onBack)

        OutlinedTextField(
            value = state.keyword,
            onValueChange = { vm.onIntent(SearchIntent.KeywordChanged(it)) },
            placeholder = { Text("搜索商品", color = TextSecondary, fontSize = 14.sp) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            trailingIcon = {
                if (state.searching) {
                    CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                } else if (state.keyword.isNotEmpty()) {
                    IconButton(onClick = { vm.onIntent(SearchIntent.KeywordChanged("")) }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.onIntent(SearchIntent.Submit(state.keyword)) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
        )

        LazyColumn(Modifier.fillMaxSize()) {
            // 联想建议（有输入时优先展示）
            if (state.keyword.isNotBlank() && state.suggestions.isNotEmpty()) {
                items(state.suggestions.size) { i ->
                    val s = state.suggestions[i]
                    Text(
                        text = s.text,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.onIntent(SearchIntent.Submit(s.text)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            if (state.keyword.isBlank()) {
                if (state.history.isNotEmpty()) {
                    item(key = "history_header") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("搜索历史", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { vm.onIntent(SearchIntent.ClearHistory) }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空历史", tint = TextSecondary)
                            }
                        }
                    }
                    item(key = "history") {
                        FlowChipRow(state.history) {
                            vm.onIntent(SearchIntent.Submit(it))
                        }
                    }
                }
                if (state.hotWords.isNotEmpty()) {
                    item(key = "hot_header") {
                        Text(
                            "热门搜索",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    item(key = "hot") {
                        FlowChipRow(state.hotWords.map { it.keyword }) {
                            vm.onIntent(SearchIntent.Submit(it))
                        }
                    }
                }
            }
        }
    }
}

/** 简单流式标签行（P0 手写换行，不引 flowlayout 依赖） */
@Composable
private fun FlowChipRow(words: List<String>, onClick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // P0 单行展示（超出可横向滚动场景少），TODO(P1) 真流式换行
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            words.forEach { w ->
                Text(
                    text = w,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .background(BrandOrange.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable { onClick(w) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
