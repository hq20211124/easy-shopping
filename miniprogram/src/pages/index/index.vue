<script setup lang="ts">
import { ref } from 'vue'
import { onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { productApi } from '@/api/product'
import { searchApi } from '@/api/search'
import type { HotWord, ProductListItem } from '@/api/types'

/** 首页：搜索入口 + 分类导航（静态 P0）+ 热搜词 + 推荐流（真实 /api/v1/products 分页）。 */
const hotWords = ref<HotWord[]>([])
const feed = ref<ProductListItem[]>([])
/** 推荐流加载失败：区分"暂无商品"与"接口挂了"，避免加载失败时显示空列表误导用户 */
const feedFailed = ref(false)
const page = ref(1)
const loading = ref(false)
const noMore = ref(false)

const categories = [
  { id: 1, name: '数码', icon: '📱' },
  { id: 2, name: '外设', icon: '⌨️' },
  { id: 3, name: '家居', icon: '🏠' },
  { id: 0, name: '全部', icon: '🛍️' },
]

async function loadFeed(reset = false) {
  if (loading.value || (!reset && noMore.value)) return
  loading.value = true
  if (reset) feedFailed.value = false
  try {
    if (reset) {
      page.value = 1
      noMore.value = false
    }
    const res = await productApi.list({ page: page.value, pageSize: 10, sort: 'default' })
    feed.value = reset ? res.list : [...feed.value, ...res.list]
    noMore.value = feed.value.length >= res.total
    page.value++
  } catch (e) {
    // toast 已由请求层统一弹出；首页失败时明确标记空态为"加载失败"而非"暂无商品"
    // （不置 noMore：已有数据时仍允许上拉重试）
    void e
    feedFailed.value = true
  } finally {
    loading.value = false
  }
}

function goSearch(keyword = '') {
  uni.navigateTo({ url: `/pages/search/index?keyword=${encodeURIComponent(keyword)}` })
}

function goCategory(id: number) {
  uni.navigateTo({ url: `/pages/search/result?categoryId=${id}` })
}

function goDetail(spuId: number) {
  uni.navigateTo({ url: `/pages/product/detail?spuId=${spuId}` })
}

onPullDownRefresh(async () => {
  await Promise.all([
    loadFeed(true),
    searchApi
      .hot(5)
      .then((h) => (hotWords.value = h))
      .catch(() => {
        // 热搜为增强信息，失败不阻塞首页（toast 已由请求层弹出）
        hotWords.value = []
      }),
  ])
  uni.stopPullDownRefresh()
})

onReachBottom(() => void loadFeed())

// 首次加载
void loadFeed(true)
searchApi
  .hot(5)
  .then((h) => (hotWords.value = h))
  .catch(() => {
    hotWords.value = []
  })
</script>

<template>
  <view class="home">
    <view class="home__search" @click="goSearch()">
      <text class="home__search-placeholder">🔍 {{ hotWords[0]?.keyword || '搜索商品' }}</text>
    </view>

    <view class="home__banner">🎉 新人专享 · 全场包邮</view>

    <view class="home__category">
      <view v-for="c in categories" :key="c.id" class="home__category-item" @click="goCategory(c.id)">
        <text class="home__category-icon">{{ c.icon }}</text>
        <text class="home__category-name">{{ c.name }}</text>
      </view>
    </view>

    <view v-if="hotWords.length" class="home__hot">
      <text class="home__hot-title">热搜</text>
      <view class="home__hot-list">
        <text
          v-for="(w, i) in hotWords"
          :key="w.keyword"
          class="home__hot-word"
          :class="{ 'is-top': i < 3 }"
          @click="goSearch(w.keyword)"
        >{{ w.keyword }}</text>
      </view>
    </view>

    <view class="home__section-title">为你推荐</view>
    <view class="home__feed">
      <view v-for="p in feed" :key="p.spuId" class="home__card" @click="goDetail(p.spuId)">
        <image class="home__card-img" :src="p.mainImage" mode="aspectFill" />
        <view class="home__card-body">
          <text class="home__card-name">{{ p.name }}</text>
          <view class="home__card-bottom">
            <ec-price :value="p.price" />
            <text class="home__card-sales">已售{{ p.salesCount ?? 0 }}</text>
          </view>
        </view>
      </view>
    </view>
    <ec-empty
      v-if="feedFailed && !feed.length"
      icon="📡"
      text="推荐商品加载失败，请检查网络后重试"
      action-text="重新加载"
      @action="loadFeed(true)"
    />
    <ec-empty v-else-if="!loading && !feed.length" icon="🛍️" text="暂无推荐商品" />
    <view v-if="noMore && feed.length && !feedFailed" class="home__no-more">— 已经到底啦 —</view>
  </view>
</template>

<style scoped>
.home {
  padding: 20rpx 24rpx 40rpx;
}
.home__search {
  background: #fff;
  border-radius: 40rpx;
  padding: 18rpx 32rpx;
}
.home__search-placeholder {
  color: #999;
  font-size: 26rpx;
}
.home__banner {
  margin-top: 20rpx;
  background: linear-gradient(90deg, #ff6034, #e4393c);
  color: #fff;
  border-radius: 16rpx;
  padding: 36rpx 32rpx;
  font-size: 30rpx;
}
.home__category {
  display: flex;
  background: #fff;
  border-radius: 16rpx;
  margin-top: 20rpx;
  padding: 24rpx 0;
}
.home__category-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}
.home__category-icon {
  font-size: 48rpx;
}
.home__category-name {
  font-size: 24rpx;
  color: #666;
}
.home__hot {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 16rpx;
  margin-top: 20rpx;
  padding: 20rpx 24rpx;
}
.home__hot-title {
  font-size: 26rpx;
  font-weight: 600;
  margin-right: 20rpx;
}
.home__hot-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx 24rpx;
}
.home__hot-word {
  font-size: 24rpx;
  color: #666;
}
.home__hot-word.is-top {
  color: #e4393c;
}
.home__section-title {
  font-size: 30rpx;
  font-weight: 600;
  margin: 28rpx 4rpx 16rpx;
}
.home__feed {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
}
.home__card {
  width: 48%;
  background: #fff;
  border-radius: 16rpx;
  overflow: hidden;
  margin-bottom: 20rpx;
}
.home__card-img {
  width: 100%;
  height: 340rpx;
  background: #f5f5f5;
}
.home__card-body {
  padding: 16rpx 20rpx 20rpx;
}
.home__card-name {
  font-size: 26rpx;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  min-height: 72rpx;
}
.home__card-bottom {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 12rpx;
}
.home__card-sales {
  font-size: 20rpx;
  color: #999;
}
.home__no-more {
  text-align: center;
  color: #999;
  font-size: 24rpx;
  padding: 20rpx 0;
}
</style>
