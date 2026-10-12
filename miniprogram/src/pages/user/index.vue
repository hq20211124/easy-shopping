<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useUserStore } from '@/store/user'
import { useCartStore } from '@/store/cart'

/** 我的：登录态 + 订单快捷入口（status 透传 0 基状态）+ 地址管理。 */
const user = useUserStore()
const cartStore = useCartStore()

const ORDER_ENTRIES = [
  { status: '0', label: '待付款', icon: '💳' },
  { status: '1', label: '待发货', icon: '📦' },
  { status: '2', label: '待收货', icon: '🚚' },
  { status: '4', label: '退款/售后', icon: '🔧' },
]

onShow(() => {
  if (user.token) cartStore.refreshCount()
})

function goLogin() {
  uni.navigateTo({ url: '/pages/login/index' })
}

function goOrders(status: string) {
  if (!user.token) {
    goLogin()
    return
  }
  uni.navigateTo({ url: `/subpkg/order/list?status=${status}` })
}

function goAllOrders() {
  if (!user.token) {
    goLogin()
    return
  }
  uni.navigateTo({ url: '/subpkg/order/list' })
}

function goAddress() {
  if (!user.token) {
    goLogin()
    return
  }
  uni.navigateTo({ url: '/subpkg/user/address' })
}

async function onLogout() {
  const r = await uni.showModal({ title: '退出登录', content: '确定退出当前账号吗？' })
  if (!r.confirm) return
  await user.logout()
  cartStore.count = 0
  uni.showToast({ title: '已退出', icon: 'none' })
}
</script>

<template>
  <view class="user">
    <view class="header">
      <view class="row info" @click="!user.token && goLogin()">
        <view class="avatar">{{ user.token ? (user.nickname || '友').slice(0, 1) : '👤' }}</view>
        <view class="mid">
          <view class="name">{{ user.token ? user.nickname : '点击登录' }}</view>
          <view class="sub">{{ user.token ? `账号：${user.username}` : '登录后享购物车多端同步' }}</view>
        </view>
      </view>
    </view>

    <view class="card">
      <view class="row hd" @click="goAllOrders">
        <text>我的订单</text>
        <text class="more" v-if="user.token">全部订单 ›</text>
      </view>
      <view class="row entries">
        <view class="entry" v-for="e in ORDER_ENTRIES" :key="e.status" @click="goOrders(e.status)">
          <text class="eicon">{{ e.icon }}</text>
          <text class="elabel">{{ e.label }}</text>
        </view>
      </view>
    </view>

    <view class="card">
      <view class="row cell" @click="goAddress">
        <text>📍 收货地址</text>
        <text class="more">›</text>
      </view>
    </view>

    <view class="logout" v-if="user.token" @click="onLogout">退出登录</view>
  </view>
</template>

<style lang="scss" scoped>
.user {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 40rpx;
}
.header {
  background: linear-gradient(135deg, #e4393c 0%, #ff6a5e 100%);
  padding: 60rpx 32rpx 80rpx;
}
.info {
  align-items: center;
}
.avatar {
  width: 110rpx;
  height: 110rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48rpx;
  color: #fff;
  flex: none;
}
.mid {
  margin-left: 24rpx;
}
.name {
  font-size: 36rpx;
  font-weight: 600;
  color: #fff;
}
.sub {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.85);
}
.card {
  background: #fff;
  border-radius: 16rpx;
  margin: -40rpx 20rpx 20rpx;
  padding: 24rpx;
  position: relative;
}
.hd {
  justify-content: space-between;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f2329;
  padding-bottom: 16rpx;
}
.more {
  font-size: 24rpx;
  color: #9aa0a6;
  font-weight: 400;
}
.entries {
  justify-content: space-between;
  padding-top: 8rpx;
}
.entry {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.eicon {
  font-size: 44rpx;
}
.elabel {
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #666;
}
.cell {
  justify-content: space-between;
  font-size: 28rpx;
  color: #1f2329;
  padding: 8rpx 0;
}
.logout {
  margin: 40rpx 20rpx;
  background: #fff;
  border-radius: 16rpx;
  text-align: center;
  padding: 26rpx;
  font-size: 28rpx;
  color: #e4393c;
}
</style>
