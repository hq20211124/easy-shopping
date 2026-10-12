<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { orderApi } from '@/api/order'
import { fmtMoney, fmtDateTime } from '@/utils/format'
import { useCountdown } from '@/utils/countdown'
import type { Money, OrderDetailVO } from '@/api/types'

/** 订单详情：子单分组、pay_deadline 倒计时（C7）、服务端 actions 驱动操作。 */
const order = ref<OrderDetailVO | null>(null)
const countdown = useCountdown()

onLoad((options) => {
  const orderId = Number(options?.orderId || 0)
  if (orderId) load(orderId)
})

onShow(() => {
  if (order.value) load(order.value.orderId)
})

async function load(orderId: number) {
  order.value = await orderApi.detail(orderId)
  if (order.value.status === 0) countdown.start(order.value.payDeadline)
}

function goPay(orderId: number, payAmount: Money) {
  uni.redirectTo({ url: `/subpkg/order/pay?orderId=${orderId}&payAmount=${encodeURIComponent(fmtMoney(payAmount))}` })
}

async function onCancel() {
  const r = await uni.showModal({ title: '取消订单', content: '确定取消该订单吗？' })
  if (!r.confirm || !order.value) return
  await orderApi.cancel(order.value.orderId)
  await load(order.value.orderId)
}

async function onConfirm() {
  const r = await uni.showModal({ title: '确认收货', content: '确认已收到商品吗？' })
  if (!r.confirm || !order.value) return
  await orderApi.confirm(order.value.orderId)
  await load(order.value.orderId)
}

function onAction(action: { type: string; text: string }) {
  if (!order.value) return
  if (action.type === 'cancel') onCancel()
  else if (action.type === 'confirm') onConfirm()
  else if (action.type === 'pay') goPay(order.value.orderId, order.value.amountDetail.payAmount)
  else if (action.type === 'logistics') uni.showToast({ title: '暂无物流信息', icon: 'none' })
}
</script>

<template>
  <view class="detail" v-if="order">
    <view class="status card">
      <view class="st">{{ order.statusText }}</view>
      <view class="cd" v-if="order.status === 0 && !countdown.expired">
        支付剩余 {{ countdown.text }}
      </view>
    </view>

    <view class="card addr">
      <view class="row">
        <text class="name">{{ order.receiverName }}</text>
        <text class="mobile">{{ order.receiverMobile }}</text>
      </view>
      <view class="addr-detail">{{ order.receiverAddress }}</view>
    </view>

    <view class="card" v-if="order.children?.length">
      <view class="sec-title">子订单（{{ order.children.length }}）</view>
      <view class="row child" v-for="c in order.children" :key="c.orderId">
        <view class="mid">
          <view class="cno">{{ c.orderNo }}</view>
          <text class="cst">{{ c.statusText }}</text>
        </view>
        <ec-price :value="c.payAmount" :size="28" />
      </view>
    </view>

    <view class="card">
      <view class="sec-title">商品</view>
      <view class="row item" v-for="it in order.items" :key="it.orderId + '-' + it.skuId">
        <image class="cover" :src="it.skuImage" mode="aspectFill" />
        <view class="mid">
          <view class="title">{{ it.skuName }}</view>
          <text class="qty">×{{ it.quantity }}</text>
        </view>
        <ec-price :value="it.price" :size="26" />
      </view>
    </view>

    <view class="card">
      <view class="sec-title">金额明细</view>
      <view class="row amt"><text>商品金额</text><text>¥{{ fmtMoney(order.amountDetail.goodsAmount) }}</text></view>
      <view class="row amt"><text>运费</text><text>¥{{ fmtMoney(order.amountDetail.freightAmount) }}</text></view>
      <view class="row amt"><text>优惠</text><text class="red">-¥{{ fmtMoney(order.amountDetail.discountAmount) }}</text></view>
      <view class="row amt strong"><text>实付</text><ec-price :value="order.amountDetail.payAmount" :size="32" /></view>
    </view>

    <view class="card">
      <view class="sec-title">订单信息</view>
      <view class="row meta"><text>订单号</text><text class="mono">{{ order.orderNo }}</text></view>
      <view class="row meta"><text>下单时间</text><text>{{ fmtDateTime(order.createdAt) }}</text></view>
      <view class="row meta" v-if="order.payTime"><text>支付时间</text><text>{{ fmtDateTime(order.payTime) }}</text></view>
      <view class="row meta" v-if="order.remark"><text>备注</text><text>{{ order.remark }}</text></view>
    </view>

    <view class="card" v-if="order.timeline?.length">
      <view class="sec-title">订单动态</view>
      <view class="row tl" v-for="(t, i) in order.timeline" :key="i">
        <view class="tl-mid">
          <view class="tl-title">{{ t.toStatusText }}</view>
          <text class="tl-time">{{ fmtDateTime(t.time) }}</text>
        </view>
      </view>
    </view>

    <view class="placeholder"></view>

    <view class="bar row" v-if="order.actions?.length">
      <view
        class="op"
        :class="{ primary: a.type === 'pay' }"
        v-for="a in order.actions"
        :key="a.type"
        @click="onAction(a)"
      >
        {{ a.text }}
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.detail {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 20rpx;
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 20rpx;
}
.status {
  .st {
    font-size: 40rpx;
    font-weight: 600;
    color: #e4393c;
  }
  .cd {
    margin-top: 12rpx;
    font-size: 26rpx;
    color: #ff8f1f;
  }
}
.addr {
  .name {
    font-size: 30rpx;
    font-weight: 600;
  }
  .mobile {
    margin-left: 20rpx;
    font-size: 26rpx;
    color: #666;
  }
  .addr-detail {
    margin-top: 10rpx;
    font-size: 26rpx;
    color: #666;
  }
}
.sec-title {
  font-size: 28rpx;
  font-weight: 600;
  margin-bottom: 12rpx;
}
.child {
  padding: 16rpx 0;
  border-top: 1rpx solid #f5f6f8;
  justify-content: space-between;
}
.cno {
  font-size: 24rpx;
  color: #666;
}
.cst {
  font-size: 24rpx;
  color: #9aa0a6;
}
.item {
  padding: 16rpx 0;
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
.amt {
  justify-content: space-between;
  padding: 12rpx 0;
  font-size: 26rpx;
  color: #666;
  &.strong {
    color: #1f2329;
    border-top: 1rpx solid #f5f6f8;
    margin-top: 8rpx;
    padding-top: 16rpx;
  }
}
.red {
  color: #e4393c;
}
.meta {
  justify-content: space-between;
  padding: 10rpx 0;
  font-size: 26rpx;
  color: #666;
}
.mono {
  color: #1f2329;
}
.tl {
  padding: 12rpx 0;
  border-top: 1rpx solid #f5f6f8;
}
.tl-title {
  font-size: 26rpx;
  color: #1f2329;
}
.tl-time {
  font-size: 22rpx;
  color: #9aa0a6;
}
.placeholder {
  height: 140rpx;
}
.bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: #fff;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  box-shadow: 0 -4rpx 16rpx rgba(0, 0, 0, 0.06);
  justify-content: flex-end;
}
.op {
  font-size: 28rpx;
  color: #666;
  border: 1rpx solid #c9cdd4;
  border-radius: 36rpx;
  padding: 14rpx 40rpx;
  margin-left: 20rpx;
  &.primary {
    color: #fff;
    background: #e4393c;
    border-color: #e4393c;
  }
}
</style>
