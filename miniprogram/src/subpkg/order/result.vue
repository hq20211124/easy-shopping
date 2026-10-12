<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { fmtMoney } from '@/utils/format'

/** 支付结果页：success 由支付页轮询终态后透传。 */
const success = ref(false)
const orderId = ref(0)
const amount = ref('0.00')

onLoad((options) => {
  success.value = options?.success === '1'
  orderId.value = Number(options?.orderId || 0)
  amount.value = options?.payAmount ? decodeURIComponent(options.payAmount) : '0.00'
})

function goOrderDetail() {
  uni.redirectTo({ url: `/subpkg/order/detail?orderId=${orderId.value}` })
}

function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}
</script>

<template>
  <view class="result">
    <view class="icon" :class="success ? 'ok' : 'fail'">{{ success ? '✓' : '!' }}</view>
    <view class="title">{{ success ? '支付成功' : '支付未完成' }}</view>
    <view class="amount" v-if="success">¥{{ fmtMoney(amount) }}</view>
    <view class="tip" v-else>订单仍为待付款，可在订单列表继续支付</view>

    <view class="btn primary" @click="goOrderDetail">查看订单</view>
    <view class="btn" @click="goHome">返回首页</view>
  </view>
</template>

<style lang="scss" scoped>
.result {
  min-height: 100vh;
  background: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 120rpx;
}
.icon {
  width: 140rpx;
  height: 140rpx;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 80rpx;
  color: #fff;
  &.ok {
    background: #22a06b;
  }
  &.fail {
    background: #ff8f1f;
  }
}
.title {
  margin-top: 32rpx;
  font-size: 40rpx;
  font-weight: 600;
  color: #1f2329;
}
.amount {
  margin-top: 16rpx;
  font-size: 56rpx;
  font-weight: 600;
  color: #1f2329;
}
.tip {
  margin-top: 16rpx;
  font-size: 26rpx;
  color: #9aa0a6;
}
.btn {
  width: 560rpx;
  text-align: center;
  font-size: 30rpx;
  padding: 22rpx 0;
  border-radius: 48rpx;
  border: 1rpx solid #c9cdd4;
  color: #666;
  margin-top: 32rpx;
  &.primary {
    background: #e4393c;
    border-color: #e4393c;
    color: #fff;
  }
}
</style>
