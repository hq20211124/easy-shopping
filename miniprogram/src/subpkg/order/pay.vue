<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { paymentApi } from '@/api/payment'
import { useCartStore } from '@/store/cart'
import { fmtMoney } from '@/utils/format'

/**
 * 支付页：prepay → mock 付款 → 轮询支付状态 → 结果页。
 * 金额由 URL 透传仅做展示，实付金额以后端 prepay 响应为准（C7 防改价）。
 */
const orderId = ref(0)
const displayAmount = ref('0.00')
const channel = ref(2) // 2=微信（P0 mock）
const paying = ref(false)
const prepaying = ref(false)
const amount = ref('0.00')
const cartStore = useCartStore()

const CHANNELS = [
  { value: 2, label: '微信支付' },
  { value: 1, label: '支付宝支付' },
]

onLoad((options) => {
  orderId.value = Number(options?.orderId || 0)
  displayAmount.value = options?.payAmount ? decodeURIComponent(options.payAmount) : '0.00'
  amount.value = displayAmount.value
})

async function pay() {
  if (paying.value || prepaying.value) return
  prepaying.value = true
  try {
    // 1. 预支付：服务端按订单实付金额生成支付单
    const prepay = await paymentApi.prepay(orderId.value, channel.value)
    amount.value = fmtMoney(prepay.amount)
    paying.value = true
    // 2. P0 mock 付款：走与真实回调相同的处理链
    await paymentApi.mockPay(prepay.paymentId)
    // 3. 轮询订单支付状态（2s × 5 次），确认终态后跳结果页
    const paid = await pollPaid()
    cartStore.refreshCount() // 支付成功后同步购物车角标
    uni.redirectTo({
      url: `/subpkg/order/result?orderId=${orderId.value}&success=${paid ? 1 : 0}&payAmount=${encodeURIComponent(amount.value)}`,
    })
  } catch (e) {
    void e // 业务错误 toast 已由请求层处理
  } finally {
    paying.value = false
    prepaying.value = false
  }
}

async function pollPaid(): Promise<boolean> {
  for (let i = 0; i < 5; i++) {
    await new Promise((r) => setTimeout(r, 2000))
    try {
      const st = await paymentApi.status(orderId.value)
      if (st.status === 'paid') return true
    } catch (e) {
      void e
    }
  }
  return false
}

function backToOrders() {
  uni.redirectTo({ url: '/subpkg/order/list' })
}
</script>

<template>
  <view class="pay">
    <view class="card amount-card">
      <view class="label">订单金额（元）</view>
      <view class="amount">¥{{ amount }}</view>
      <view class="oid">订单号：{{ orderId }}</view>
    </view>

    <view class="card">
      <view
        class="row ch"
        :class="{ on: channel === c.value }"
        v-for="c in CHANNELS"
        :key="c.value"
        @click="channel = c.value"
      >
        <text>{{ c.label }}</text>
        <view class="cb"><text v-if="channel === c.value">✓</text></view>
      </view>
    </view>

    <view class="btn" :class="{ dis: paying }" @click="pay">
      {{ paying ? '支付处理中...' : `确认支付 ¥${amount}` }}
    </view>
    <view class="link" @click="backToOrders">暂不支付，查看订单</view>
  </view>
</template>

<style lang="scss" scoped>
.pay {
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
.amount-card {
  text-align: center;
  padding: 48rpx 24rpx;
}
.label {
  font-size: 26rpx;
  color: #9aa0a6;
}
.amount {
  margin-top: 16rpx;
  font-size: 72rpx;
  font-weight: 600;
  color: #1f2329;
}
.oid {
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #c0c4cc;
}
.ch {
  justify-content: space-between;
  padding: 24rpx 0;
  font-size: 28rpx;
  color: #1f2329;
  border-bottom: 1rpx solid #f5f6f8;
  &:last-child {
    border-bottom: none;
  }
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
}
.ch.on .cb {
  background: #e4393c;
  border-color: #e4393c;
}
.btn {
  background: #e4393c;
  color: #fff;
  font-size: 32rpx;
  text-align: center;
  padding: 26rpx;
  border-radius: 48rpx;
  margin: 40rpx 20rpx 0;
  &.dis {
    opacity: 0.5;
  }
}
.link {
  text-align: center;
  font-size: 26rpx;
  color: #666;
  padding: 30rpx;
}
</style>
