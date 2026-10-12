<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { orderApi } from '@/api/order'
import { fmtMoney } from '@/utils/format'
import type { Money, OrderListVO } from '@/api/types'

/** 订单列表：0 基 8 值状态页签（0待付款/1待发货/2待收货/3已完成/4退款中/5已退款/6已取消/7已关闭）。 */
const TABS = [
  { key: 'all', label: '全部' },
  { key: '0', label: '待付款' },
  { key: '1', label: '待发货' },
  { key: '2', label: '待收货' },
  { key: '3', label: '已完成' },
  { key: '4', label: '退款中' },
]

const active = ref('all')
const orders = ref<OrderListVO[]>([])
const loading = ref(false)
const finished = ref(false)
/** 列表拉取失败：区分「没有订单」与「订单拉不到」，避免加载失败时显示空列表误导用户 */
const failed = ref(false)
const page = ref(1)
const pageSize = 10

// 从「我的」页签入口进入时透传 status（0 基）
onLoad((options) => {
  const s = options?.status
  if (s !== undefined && TABS.some((t) => t.key === s)) active.value = s
})

async function load(reset = false) {
  if (loading.value) return
  if (reset) {
    page.value = 1
    finished.value = false
  }
  if (finished.value) return
  loading.value = true
  failed.value = false
  try {
    const res = await orderApi.list({ status: active.value, page: page.value, pageSize })
    const records = res.list || []
    orders.value = reset ? records : [...orders.value, ...records]
    if (records.length < pageSize) finished.value = true
    else page.value += 1
  } catch (e) {
    // toast 已由请求层统一弹出，这里只置错误态（禁止静默吞错）
    void e
    if (reset) orders.value = []
    failed.value = true
  } finally {
    loading.value = false
  }
}

onShow(() => void load(true))

function switchTab(key: string) {
  if (active.value === key) return
  active.value = key
  void load(true)
}

function goDetail(orderId: number) {
  uni.navigateTo({ url: `/subpkg/order/detail?orderId=${orderId}` })
}

function goPay(orderId: number, payAmount: Money) {
  uni.navigateTo({ url: `/subpkg/order/pay?orderId=${orderId}&payAmount=${encodeURIComponent(fmtMoney(payAmount))}` })
}

async function onCancel(o: OrderListVO) {
  const r = await uni.showModal({ title: '取消订单', content: '确定取消该订单吗？' })
  if (!r.confirm) return
  await orderApi.cancel(o.orderId)
  uni.showToast({ title: '已取消', icon: 'none' })
  load(true)
}

async function onConfirm(o: OrderListVO) {
  const r = await uni.showModal({ title: '确认收货', content: '确认已收到商品吗？' })
  if (!r.confirm) return
  await orderApi.confirm(o.orderId)
  uni.showToast({ title: '已确认', icon: 'success' })
  load(true)
}

/** 按服务端 actions 驱动按钮（type：pay/cancel/logistics/confirm），前端不自行推断可执行操作。 */
function onAction(o: OrderListVO, action: { type: string; text: string }) {
  if (action.type === 'cancel') onCancel(o)
  else if (action.type === 'confirm') onConfirm(o)
  else if (action.type === 'pay') goPay(o.orderId, o.payAmount)
}

function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}
</script>

<template>
  <view class="order-list">
    <scroll-view scroll-x class="tabs">
      <view
        class="tab"
        :class="{ on: active === t.key }"
        v-for="t in TABS"
        :key="t.key"
        @click="switchTab(t.key)"
      >
        {{ t.label }}
      </view>
    </scroll-view>

    <ec-empty
      v-if="failed && !orders.length"
      icon="📡"
      text="订单加载失败，请检查网络后重试"
      action-text="重新加载"
      @action="load(true)"
    />

    <ec-empty v-else-if="!loading && !orders.length" icon="📋" text="暂无订单" action-text="去逛逛" @action="goHome" />

    <view class="card" v-for="o in orders" :key="o.orderId" @click="goDetail(o.orderId)">
      <view class="row hd">
        <text class="no">订单号：{{ o.orderNo }}</text>
        <text class="st">{{ o.statusText }}</text>
      </view>
      <view class="row item" v-for="it in o.items" :key="it.orderId + '-' + it.skuId">
        <image class="cover" :src="it.skuImage" mode="aspectFill" />
        <view class="mid">
          <view class="title">{{ it.skuName }}</view>
          <text class="qty">×{{ it.quantity }}</text>
        </view>
        <ec-price :value="it.price" :size="26" />
      </view>
      <view class="row btm">
        <text class="count">共{{ o.itemCount }}件 实付</text>
        <ec-price :value="o.payAmount" :size="30" />
      </view>
      <view class="row ops" v-if="o.actions?.length" @click.stop>
        <view
          class="op"
          :class="{ primary: a.type === 'pay' }"
          v-for="a in o.actions"
          :key="a.type"
          @click="onAction(o, a)"
        >
          {{ a.text }}
        </view>
      </view>
    </view>

    <view class="foot" v-if="orders.length">{{ finished ? '没有更多了' : '加载中...' }}</view>
  </view>
</template>

<style lang="scss" scoped>
.order-list {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 40rpx;
}
.tabs {
  background: #fff;
  white-space: nowrap;
  position: sticky;
  top: 0;
  z-index: 10;
}
.tab {
  display: inline-block;
  padding: 24rpx 30rpx;
  font-size: 28rpx;
  color: #666;
  position: relative;
  &.on {
    color: #e4393c;
    font-weight: 600;
    &::after {
      content: '';
      position: absolute;
      left: 30rpx;
      right: 30rpx;
      bottom: 8rpx;
      height: 6rpx;
      border-radius: 3rpx;
      background: #e4393c;
    }
  }
}
.card {
  background: #fff;
  border-radius: 16rpx;
  margin: 20rpx;
  padding: 24rpx;
}
.hd {
  justify-content: space-between;
  font-size: 24rpx;
}
.no {
  color: #9aa0a6;
}
.st {
  color: #e4393c;
}
.item {
  padding: 20rpx 0;
  align-items: center;
}
.cover {
  width: 120rpx;
  height: 120rpx;
  border-radius: 12rpx;
  background: #f0f1f3;
  flex: none;
}
.mid {
  flex: 1;
  min-width: 0;
  margin: 0 16rpx;
}
.title {
  font-size: 26rpx;
  color: #1f2329;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.qty {
  font-size: 24rpx;
  color: #9aa0a6;
}
.btm {
  justify-content: flex-end;
  align-items: baseline;
  padding-top: 12rpx;
  border-top: 1rpx solid #f5f6f8;
}
.count {
  font-size: 24rpx;
  color: #666;
  margin-right: 12rpx;
}
.ops {
  justify-content: flex-end;
  margin-top: 20rpx;
  flex-wrap: wrap;
}
.op {
  font-size: 26rpx;
  color: #666;
  border: 1rpx solid #c9cdd4;
  border-radius: 32rpx;
  padding: 10rpx 30rpx;
  margin-left: 16rpx;
  &.primary {
    color: #e4393c;
    border-color: #e4393c;
  }
}
.foot {
  text-align: center;
  font-size: 24rpx;
  color: #c0c4cc;
  padding: 24rpx;
}
</style>
