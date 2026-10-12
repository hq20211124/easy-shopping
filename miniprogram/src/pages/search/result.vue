<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import { searchApi } from '@/api/search'
import type { SearchProductItem } from '@/api/types'

/**
 * 搜索结果：关键词 + 筛选（价格区间）+ 排序 + cursor 分页「加载更多」。
 * cursor 传上一页 nextCursor；换排序/筛选必须回到首页（不传 cursor，core-search README §3.4）。
 */
const keyword = ref('')
const categoryId = ref<number | undefined>(undefined)
const sort = ref('default')
const sortIndex = ref(0)
const priceMin = ref('')
const priceMax = ref('')
const showFilter = ref(false)

const list = ref<SearchProductItem[]>([])
const total = ref(0)
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const initialized = ref(false)
/** 加载失败：区分「没搜到」与「搜索接口挂了」，避免失败时显示"暂无结果"误导用户 */
const failed = ref(false)

const sorts = [
  { label: '综合', value: 'default' },
  { label: '销量', value: 'sales' },
  { label: '价格↑', value: 'price_asc' },
  { label: '价格↓', value: 'price_desc' },
  { label: '新品', value: 'newest' },
]

const finished = computed(() => initialized.value && !nextCursor.value)

async function fetchPage(reset: boolean) {
  if (loading.value) return
  if (!reset && !nextCursor.value) return
  loading.value = true
  if (reset) failed.value = false
  try {
    const res = await searchApi.products({
      q: keyword.value,
      categoryId: categoryId.value,
      sort: sort.value,
      priceMin: priceMin.value ? Number(priceMin.value) : undefined,
      priceMax: priceMax.value ? Number(priceMax.value) : undefined,
      cursor: reset ? undefined : nextCursor.value || undefined,
      pageSize: 10,
    })
    list.value = reset ? res.list : [...list.value, ...res.list]
    total.value = res.total
    nextCursor.value = res.nextCursor
    initialized.value = true
  } catch {
    // toast 已由请求层统一弹出；首页加载失败时置错误态（翻页失败保留已有结果，不清空）
    initialized.value = true
    if (reset) {
      list.value = []
      failed.value = true
    }
  } finally {
    loading.value = false
  }
}

function selectSort(i: number) {
  sortIndex.value = i
  sort.value = sorts[i].value
  fetchPage(true) // 换排序回首页，不传 cursor
}

function applyFilter() {
  showFilter.value = false
  fetchPage(true) // 换筛选回首页
}

function resetFilter() {
  priceMin.value = ''
  priceMax.value = ''
  applyFilter()
}

function goDetail(spuId: number) {
  uni.navigateTo({ url: `/pages/product/detail?spuId=${spuId}` })
}

const hasFilter = computed(() => Boolean(priceMin.value || priceMax.value))

onLoad((options) => {
  if (options?.keyword) keyword.value = decodeURIComponent(options.keyword)
  if (options?.categoryId) categoryId.value = Number(options.categoryId)
  fetchPage(true)
})

onReachBottom(() => fetchPage(false))
</script>

<template>
  <view class="result">
    <view class="result__sortbar">
      <view
        v-for="(s, i) in sorts"
        :key="s.value"
        class="result__sort"
        :class="{ 'is-active': sortIndex === i }"
        @click="selectSort(i)"
      >{{ s.label }}</view>
      <view class="result__sort" :class="{ 'is-active': hasFilter }" @click="showFilter = !showFilter">
        筛选{{ hasFilter ? ' ·' : '' }} ▾
      </view>
    </view>

    <view v-if="showFilter" class="result__filter">
      <view class="result__filter-row">
        <input v-model="priceMin" class="result__filter-input" type="digit" placeholder="最低价" />
        <text>—</text>
        <input v-model="priceMax" class="result__filter-input" type="digit" placeholder="最高价" />
      </view>
      <view class="result__filter-actions">
        <button class="result__filter-btn result__filter-btn--ghost" @click="resetFilter">重置</button>
        <button class="result__filter-btn" @click="applyFilter">确定</button>
      </view>
    </view>

    <view class="result__summary">
      <text v-if="initialized">共 {{ total }} 件商品</text>
    </view>

    <ec-empty
      v-if="failed && !list.length"
      icon="📡"
      text="搜索结果加载失败，请检查网络后重试"
      action-text="重新搜索"
      @action="fetchPage(true)"
    />

    <ec-empty v-else-if="finished && !list.length" text="没有找到相关商品" />

    <view class="result__list">
      <view v-for="item in list" :key="item.spuId" class="result__item" @click="goDetail(item.spuId)">
        <image class="result__img" :src="item.mainImage" mode="aspectFill" />
        <view class="result__info">
          <text class="result__name">{{ item.name }}</text>
          <text class="result__sales">已售{{ item.salesCount ?? 0 }}</text>
          <view class="result__price-row">
            <ec-price :value="item.price" />
            <text v-if="item.originalPrice" class="result__origin">¥{{ item.originalPrice }}</text>
          </view>
        </view>
      </view>
    </view>

    <view v-if="list.length" class="result__footer">
      <text v-if="loading">加载中…</text>
      <text v-else-if="finished">— 没有更多了 —</text>
      <text v-else>上拉加载更多</text>
    </view>
  </view>
</template>

<style scoped>
.result {
  padding-bottom: 40rpx;
}
.result__sortbar {
  display: flex;
  background: #fff;
  position: sticky;
  top: 0;
  z-index: 10;
}
.result__sort {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  font-size: 26rpx;
  color: #666;
}
.result__sort.is-active {
  color: #e4393c;
  font-weight: 600;
}
.result__filter {
  background: #fff;
  padding: 24rpx;
  border-top: 1rpx solid #f5f5f5;
}
.result__filter-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.result__filter-input {
  flex: 1;
  background: #f5f5f5;
  border-radius: 8rpx;
  padding: 14rpx 20rpx;
  font-size: 26rpx;
}
.result__filter-actions {
  display: flex;
  gap: 20rpx;
  margin-top: 24rpx;
}
.result__filter-btn {
  flex: 1;
  background: #e4393c;
  color: #fff;
  border-radius: 40rpx;
  font-size: 26rpx;
}
.result__filter-btn--ghost {
  background: #f5f5f5;
  color: #666;
}
.result__summary {
  padding: 16rpx 24rpx 0;
  font-size: 22rpx;
  color: #999;
}
.result__list {
  padding: 0 24rpx;
}
.result__item {
  display: flex;
  background: #fff;
  border-radius: 16rpx;
  padding: 20rpx;
  margin-top: 20rpx;
}
.result__img {
  width: 200rpx;
  height: 200rpx;
  border-radius: 12rpx;
  background: #f5f5f5;
  flex-shrink: 0;
}
.result__info {
  flex: 1;
  margin-left: 20rpx;
  display: flex;
  flex-direction: column;
}
.result__name {
  font-size: 28rpx;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.result__sales {
  font-size: 22rpx;
  color: #999;
  margin-top: 8rpx;
}
.result__price-row {
  margin-top: auto;
  display: flex;
  align-items: baseline;
  gap: 12rpx;
}
.result__origin {
  font-size: 22rpx;
  color: #999;
  text-decoration: line-through;
}
.result__footer {
  text-align: center;
  color: #999;
  font-size: 24rpx;
  padding: 24rpx 0;
}
</style>
