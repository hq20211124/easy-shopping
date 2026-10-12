<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { searchApi } from '@/api/search'
import type { HotWord, SuggestResult } from '@/api/types'

/** 搜索页：历史（本地 ≤10 条）+ 热搜词 + 联想（debounce + 请求序号防竞态）。 */
const HISTORY_KEY = 'ec_search_history'
const MAX_HISTORY = 10

const keyword = ref('')
const history = ref<string[]>([])
const hotWords = ref<HotWord[]>([])
const suggestions = ref<SuggestResult['suggestions']>([])
let suggestSeq = 0
let debounceTimer: ReturnType<typeof setTimeout> | null = null

function saveHistory(kw: string) {
  const trimmed = kw.trim()
  if (!trimmed) return
  history.value = [trimmed, ...history.value.filter((h) => h !== trimmed)].slice(0, MAX_HISTORY)
  uni.setStorageSync(HISTORY_KEY, history.value)
}

function clearHistory() {
  uni.removeStorageSync(HISTORY_KEY)
  history.value = []
}

function doSearch(kw: string) {
  const trimmed = kw.trim()
  if (!trimmed) return
  saveHistory(trimmed)
  uni.navigateTo({ url: `/pages/search/result?keyword=${encodeURIComponent(trimmed)}` })
}

/** 联想：debounce 300ms + 请求序号，期间有更新输入则丢弃旧结果（竞态防护，plans 6.1） */
function onInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(async () => {
    const kw = keyword.value.trim()
    if (!kw) {
      suggestions.value = []
      return
    }
    const seq = ++suggestSeq
    try {
      const res = await searchApi.suggestions(kw)
      if (seq !== suggestSeq) return
      suggestions.value = res.suggestions
    } catch {
      /* 联想失败静默 */
    }
  }, 300)
}

function onConfirm() {
  doSearch(keyword.value)
}

onLoad(async (options) => {
  history.value = ((uni.getStorageSync(HISTORY_KEY) as string[]) || []).slice(0, MAX_HISTORY)
  if (options?.keyword) {
    keyword.value = decodeURIComponent(options.keyword)
    doSearch(keyword.value)
    return
  }
  try {
    hotWords.value = await searchApi.hot(10)
  } catch {
    /* 游客可浏览：热搜失败静默 */
  }
})
</script>

<template>
  <view class="search">
    <view class="search__bar">
      <input
        v-model="keyword"
        class="search__input"
        confirm-type="search"
        placeholder="搜索商品"
        @input="onInput"
        @confirm="onConfirm"
      />
      <text class="search__btn" @click="onConfirm">搜索</text>
    </view>

    <view v-if="suggestions.length" class="search__suggest">
      <view v-for="s in suggestions" :key="s.text" class="search__suggest-item" @click="doSearch(s.text)">
        🔍 {{ s.text }}
      </view>
    </view>

    <template v-else>
      <view v-if="history.length" class="search__section">
        <view class="search__section-header">
          <text>搜索历史</text>
          <text class="search__clear" @click="clearHistory">清空</text>
        </view>
        <view class="search__tags">
          <text v-for="h in history" :key="h" class="search__tag" @click="doSearch(h)">{{ h }}</text>
        </view>
      </view>

      <view v-if="hotWords.length" class="search__section">
        <view class="search__section-header"><text>热门搜索</text></view>
        <view class="search__tags">
          <text
            v-for="(w, i) in hotWords"
            :key="w.keyword"
            class="search__tag"
            :class="{ 'is-top': i < 3 }"
            @click="doSearch(w.keyword)"
          >{{ w.keyword }}</text>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.search {
  padding: 20rpx 24rpx;
}
.search__bar {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 40rpx;
  padding: 12rpx 12rpx 12rpx 32rpx;
}
.search__input {
  flex: 1;
  font-size: 28rpx;
}
.search__btn {
  color: #e4393c;
  font-size: 28rpx;
  padding: 8rpx 24rpx;
}
.search__suggest {
  background: #fff;
  border-radius: 16rpx;
  margin-top: 16rpx;
}
.search__suggest-item {
  padding: 24rpx;
  border-bottom: 1rpx solid #f5f5f5;
  font-size: 26rpx;
  color: #666;
}
.search__section {
  margin-top: 32rpx;
}
.search__section-header {
  display: flex;
  justify-content: space-between;
  font-size: 26rpx;
  font-weight: 600;
  margin-bottom: 16rpx;
}
.search__clear {
  font-weight: 400;
  color: #999;
  font-size: 24rpx;
}
.search__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.search__tag {
  background: #fff;
  border-radius: 32rpx;
  padding: 10rpx 28rpx;
  font-size: 24rpx;
  color: #666;
}
.search__tag.is-top {
  color: #e4393c;
}
</style>
