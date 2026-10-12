<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useCartStore } from '@/store/cart'
import { useUserStore } from '@/store/user'
import { useCheckoutStore } from '@/store/checkout'
import { cartApi } from '@/api/cart'
import { fmtMoney, toCents } from '@/utils/format'
import type { CartItemVO, CartListResponse } from '@/api/types'

/** 购物车页：有效/失效分区、单选/全选、步进器、失效清理、结算。 */
const cartStore = useCartStore()
const user = useUserStore()
const checkout = useCheckoutStore()

const data = ref<CartListResponse | null>(null)
const loading = ref(false)

const validItems = computed(() => data.value?.items.filter((i) => !i.invalid) || [])
const invalidItems = computed(() => data.value?.items.filter((i) => i.invalid) || [])
const selectedItems = computed(() => validItems.value.filter((i) => i.selected))
const allSelected = computed(() => validItems.value.length > 0 && validItems.value.every((i) => i.selected))
const selectedAmount = computed(() =>
  selectedItems.value.reduce((s, i) => s + toCents(i.price) * i.quantity, 0),
)

async function load() {
  loading.value = true
  try {
    data.value = await cartApi.list()
    cartStore.count = data.value.totalQuantity
  } finally {
    loading.value = false
  }
}

onShow(() => {
  if (user.token) load()
})

async function toggleLine(item: CartItemVO) {
  if (item.invalid) return
  await cartApi.update(item.cartItemId, { selected: !item.selected })
  await load()
}

async function toggleAll() {
  await cartApi.select(null, !allSelected.value)
  await load()
}

async function changeQuantity(item: CartItemVO, quantity: number) {
  await cartApi.update(item.cartItemId, { quantity })
  await load()
}

async function removeLine(item: CartItemVO) {
  await cartApi.remove([item.cartItemId])
  uni.showToast({ title: '已删除', icon: 'none' })
  await load()
}

async function clearInvalid() {
  const ids = invalidItems.value.map((i) => i.cartItemId)
  if (!ids.length) return
  await cartApi.remove(ids)
  await load()
}

function goDetail(skuId: number) {
  uni.navigateTo({ url: `/pages/product/detail?skuId=${skuId}` })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/index' })
}

function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}

function settle() {
  if (!selectedItems.value.length) {
    uni.showToast({ title: '请勾选商品', icon: 'none' })
    return
  }
  checkout.start(
    selectedItems.value.map((i) => ({
      skuId: i.skuId,
      title: i.title,
      image: i.mainImage,
      price: i.price,
      quantity: i.quantity,
      cartItemId: i.cartItemId,
    })),
  )
  uni.navigateTo({ url: '/pages/checkout/index' })
}
</script>

<template>
  <view class="cart">
    <view class="hd row" v-if="user.token && validItems.length">
      <text>共 {{ validItems.length }} 种商品</text>
      <text class="link" @click="toggleAll">{{ allSelected ? '取消全选' : '全选' }}</text>
    </view>

    <view v-if="!user.token" class="login-tip">
      <ec-empty icon="🏠" text="请先登录后查看购物车" action-text="去登录" @action="goLogin" />
    </view>
    <ec-empty
      v-else-if="!loading && !validItems.length && !invalidItems.length"
      icon="🛒"
      text="购物车还是空的"
      action-text="去逛逛"
      @action="goHome"
    />

    <template v-else>
      <view class="card" v-for="l in validItems" :key="l.cartItemId">
        <view class="line row">
          <view class="cb" :class="{ on: l.selected }" @click="toggleLine(l)">
            <text v-if="l.selected">✓</text>
          </view>
          <image class="cover" :src="l.mainImage" mode="aspectFill" @click="goDetail(l.skuId)" />
          <view class="mid">
            <view class="title" @click="goDetail(l.skuId)">{{ l.title }}</view>
            <view class="tags" v-if="l.discountTags?.length">
              <text class="tag" v-for="(t, i) in l.discountTags" :key="i">{{ t }}</text>
            </view>
            <view class="row btm">
              <ec-price :value="l.price" :size="32" />
              <ec-stepper :model-value="l.quantity" :min="1" @change="(v: number) => changeQuantity(l, v)" />
            </view>
          </view>
          <view class="del" @click="removeLine(l)">×</view>
        </view>
      </view>

      <view class="invalid" v-if="invalidItems.length">
        <view class="inv-hd row">
          失效商品（{{ invalidItems.length }}）
          <text class="link" @click="clearInvalid">清空</text>
        </view>
        <view class="line row" v-for="l in invalidItems" :key="l.cartItemId">
          <view class="cb dis"></view>
          <image class="cover gray" :src="l.mainImage" mode="aspectFill" />
          <view class="mid">
            <view class="title gray">{{ l.title }}</view>
            <view class="spec">已失效</view>
          </view>
        </view>
      </view>

      <view class="placeholder"></view>

      <view class="settle-bar row" v-if="validItems.length">
        <view class="row total" @click="toggleAll">
          <view class="cb" :class="{ on: allSelected }"><text v-if="allSelected">✓</text></view>
          <text class="lbl">全选</text>
        </view>
        <view class="row amt">
          <text>合计：</text>
          <ec-price :value="fmtMoney(selectedAmount)" :size="36" />
        </view>
        <view class="btn" :class="{ dis: !selectedItems.length }" @click="settle">
          结算({{ selectedItems.length }})
        </view>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.cart {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 40rpx;
}
.hd {
  padding: 24rpx 28rpx;
  font-size: 26rpx;
  color: #666;
}
.card {
  background: #fff;
  border-radius: 16rpx;
  margin: 0 20rpx 20rpx;
}
.line {
  padding: 24rpx 20rpx;
  position: relative;
}
.cb {
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  border: 2rpx solid #c9cdd4;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  color: #fff;
  margin-right: 20rpx;
  flex: none;
  &.on {
    background: #e4393c;
    border-color: #e4393c;
  }
  &.dis {
    background: #f0f1f3;
  }
}
.cover {
  width: 140rpx;
  height: 140rpx;
  border-radius: 12rpx;
  background: #f0f1f3;
  flex: none;
  &.gray {
    opacity: 0.5;
  }
}
.mid {
  flex: 1;
  min-width: 0;
  margin-left: 20rpx;
}
.title {
  font-size: 28rpx;
  color: #1f2329;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  &.gray {
    color: #9aa0a6;
  }
}
.tags {
  margin-top: 8rpx;
}
.tag {
  font-size: 20rpx;
  color: #e4393c;
  border: 1rpx solid #e4393c;
  border-radius: 6rpx;
  padding: 2rpx 8rpx;
  margin-right: 8rpx;
}
.spec {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #9aa0a6;
}
.btm {
  margin-top: 14rpx;
  justify-content: space-between;
}
.del {
  position: absolute;
  right: 24rpx;
  top: 20rpx;
  font-size: 40rpx;
  color: #c9cdd4;
  line-height: 1;
  padding: 6rpx;
}
.invalid {
  background: #fff;
  border-radius: 16rpx;
  margin: 0 20rpx;
}
.inv-hd {
  padding: 20rpx 24rpx;
  font-size: 26rpx;
  color: #9aa0a6;
  border-bottom: 1rpx solid #f0f1f3;
  justify-content: space-between;
}
.placeholder {
  height: 140rpx;
}
.settle-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: #fff;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  box-shadow: 0 -4rpx 16rpx rgba(0, 0, 0, 0.06);
  align-items: center;
}
.total {
  font-size: 26rpx;
  color: #1f2329;
}
.lbl {
  margin-left: 12rpx;
}
.amt {
  flex: 1;
  justify-content: flex-end;
  margin-right: 20rpx;
  font-size: 26rpx;
}
.btn {
  background: #e4393c;
  color: #fff;
  font-size: 30rpx;
  padding: 18rpx 44rpx;
  border-radius: 44rpx;
  &.dis {
    opacity: 0.4;
  }
}
.link {
  color: #e4393c;
  font-size: 26rpx;
}
</style>
