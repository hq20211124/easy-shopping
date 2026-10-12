<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShareAppMessage } from '@dcloudio/uni-app'
import { productApi } from '@/api/product'
import { cartApi } from '@/api/cart'
import type { ProductDetail, ProductSku } from '@/api/types'
import { useCartStore } from '@/store/cart'
import { useUserStore } from '@/store/user'
import { useCheckoutStore } from '@/store/checkout'

/** 商品详情：swiper 图集 + 多规格 SKU 矩阵 + 加购/立即购买。 */
const product = ref<ProductDetail | null>(null)
/** 加载失败/参数无效：显式错误态，避免只剩空 swiper 与无效按钮的"白屏" */
const failed = ref(false)
const skuVisible = ref(false)
const skuMode = ref<'cart' | 'buy'>('cart')
const cartStore = useCartStore()
const userStore = useUserStore()
const checkoutStore = useCheckoutStore()
const adding = ref(false)

const images = computed(() => product.value?.images?.length ? product.value.images : [product.value?.mainImage || ''])

async function load(spuId: number) {
  failed.value = false
  try {
    product.value = await productApi.detail(spuId)
  } catch (e) {
    // 3001 不存在 / 3003 未上架 / 网络异常：toast 已由请求层处理，这里补可退出的错误态
    void e
    product.value = null
    failed.value = true
  }
}

function goBack() {
  const pages = getCurrentPages()
  if (pages.length > 1) uni.navigateBack()
  else uni.switchTab({ url: '/pages/index/index' })
}

function requireLogin(): boolean {
  if (userStore.isLoggedIn) return true
  uni.navigateTo({ url: '/pages/login/index' })
  return false
}

function goCart() {
  uni.switchTab({ url: '/pages/cart/index' })
}

function openSku(mode: 'cart' | 'buy') {
  if (!product.value) return
  if (!requireLogin()) return
  skuMode.value = mode
  skuVisible.value = true
}

/** 单规格商品直接加购/购买，多规格拉起矩阵选择器 */
async function onAction(mode: 'cart' | 'buy') {
  if (!product.value) return
  if (!requireLogin()) return
  if (product.value.skus.length > 1 || product.value.specs.length > 0) {
    openSku(mode)
    return
  }
  const sku = product.value.skus[0]
  if (!sku) return
  await handleSku({ sku, quantity: 1 }, mode)
}

async function handleSku(payload: { sku: ProductSku; quantity: number }, mode = skuMode.value) {
  if (mode === 'cart') {
    if (adding.value) return
    adding.value = true
    try {
      await cartApi.add(payload.sku.skuId, payload.quantity)
      uni.showToast({ title: '已加入购物车', icon: 'success' })
      cartStore.refreshCount()
    } finally {
      adding.value = false
    }
    return
  }
  // 立即购买：写结算草稿 → 结算页（金额以后端下单响应为准）
  const p = product.value!
  checkoutStore.start([
    {
      skuId: payload.sku.skuId,
      title: payload.sku.title || p.name,
      image: payload.sku.images?.[0] || p.mainImage,
      price: payload.sku.price ?? p.price,
      quantity: payload.quantity,
    },
  ])
  uni.navigateTo({ url: '/pages/checkout/index' })
}

onLoad((options) => {
  const spuId = Number(options?.spuId || 0)
  if (!spuId) {
    // 入口传错参数（如误传 skuId）时不再静默白屏：置错误态 + 提示，从购物车/搜索列表重新进入即可
    failed.value = true
    uni.showToast({ title: '商品参数无效，请重新进入', icon: 'none' })
    return
  }
  load(spuId)
})

onShareAppMessage(() => ({
  title: product.value?.name || '易购商城',
  path: `/pages/product/detail?spuId=${product.value?.spuId ?? ''}`,
}))
</script>

<template>
  <view class="detail">
    <ec-empty
      v-if="failed"
      icon="🚧"
      text="商品加载失败或已下架"
      action-text="返回"
      @action="goBack"
    />
    <template v-else>
      <swiper v-if="images.length" class="detail__gallery" indicator-dots circular>
        <swiper-item v-for="(img, i) in images" :key="i">
          <image class="detail__gallery-img" :src="img" mode="aspectFill" />
        </swiper-item>
      </swiper>

      <view v-if="product" class="detail__card">
        <view class="detail__price-row">
          <ec-price :value="product.price" size="lg" />
          <text v-if="product.originalPrice" class="detail__origin">¥{{ product.originalPrice }}</text>
          <text class="detail__sales">已售{{ product.salesCount ?? 0 }}</text>
        </view>
        <view class="detail__title">{{ product.name }}</view>
        <view v-if="product.subtitle" class="detail__subtitle">{{ product.subtitle }}</view>

        <view class="detail__spec-entry" @click="openSku('cart')">
          <text class="detail__spec-label">选择</text>
          <text class="detail__spec-value">
            {{ product.specs.map((s) => s.name).join(' / ') || '规格' }} ▾
          </text>
        </view>
      </view>

      <view v-if="product?.detail" class="detail__section">
        <view class="detail__section-title">商品详情</view>
        <!-- 富文本：小程序端用 rich-text 渲染（服务端已做 XSS 处理口径） -->
        <rich-text :nodes="product.detail" />
      </view>

      <view class="detail__placeholder" />

      <!-- 无商品数据时不渲染底部操作条：按钮点了也不会有任何动作（静默空转） -->
      <view class="detail__bar" v-if="product">
        <view class="detail__bar-icon" @click="goCart">
          🛒
          <text class="detail__bar-badge" v-if="cartStore.count > 0">{{ cartStore.count > 99 ? '99+' : cartStore.count }}</text>
        </view>
        <button class="detail__bar-btn detail__bar-btn--cart" @click="onAction('cart')">加入购物车</button>
        <button class="detail__bar-btn detail__bar-btn--buy" @click="onAction('buy')">立即购买</button>
      </view>

      <ec-sku-matrix
        v-model:visible="skuVisible"
        :product="product"
        :confirm-text="skuMode === 'cart' ? '加入购物车' : '立即购买'"
        @confirm="handleSku"
      />
    </template>
  </view>
</template>

<style scoped>
.detail__gallery {
  height: 750rpx;
  background: #fff;
}
.detail__gallery-img {
  width: 100%;
  height: 750rpx;
}
.detail__card {
  background: #fff;
  padding: 24rpx;
}
.detail__price-row {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}
.detail__origin {
  font-size: 24rpx;
  color: #999;
  text-decoration: line-through;
}
.detail__sales {
  margin-left: auto;
  font-size: 22rpx;
  color: #999;
}
.detail__title {
  font-size: 32rpx;
  font-weight: 600;
  margin-top: 16rpx;
}
.detail__subtitle {
  font-size: 24rpx;
  color: #999;
  margin-top: 8rpx;
}
.detail__spec-entry {
  display: flex;
  margin-top: 24rpx;
  padding: 20rpx 0 8rpx;
  border-top: 1rpx solid #f5f5f5;
}
.detail__spec-label {
  color: #999;
  font-size: 26rpx;
}
.detail__spec-value {
  margin-left: 24rpx;
  font-size: 26rpx;
}
.detail__section {
  background: #fff;
  margin-top: 20rpx;
  padding: 24rpx;
}
.detail__section-title {
  font-size: 28rpx;
  font-weight: 600;
  margin-bottom: 16rpx;
}
.detail__placeholder {
  height: 140rpx;
}
.detail__bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: #fff;
  display: flex;
  align-items: center;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  border-top: 1rpx solid #f0f0f0;
}
.detail__bar-icon {
  position: relative;
  font-size: 48rpx;
  padding: 0 24rpx;
}
.detail__bar-badge {
  position: absolute;
  top: -8rpx;
  right: 8rpx;
  background: #e4393c;
  color: #fff;
  font-size: 18rpx;
  border-radius: 20rpx;
  padding: 2rpx 10rpx;
}
.detail__bar-btn {
  flex: 1;
  border-radius: 44rpx;
  font-size: 28rpx;
  color: #fff;
  margin-left: 16rpx;
}
.detail__bar-btn--cart {
  background: #ff9800;
}
.detail__bar-btn--buy {
  background: #e4393c;
}
</style>
