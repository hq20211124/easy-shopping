<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { addressApi } from '@/api/address'
import { useCheckoutStore } from '@/store/checkout'
import type { Address } from '@/api/types'

/** 地址列表：select=1 时为结算页选择模式，选中即回填结算草稿并返回。 */
const checkout = useCheckoutStore()
const list = ref<Address[]>([])
const selectMode = ref(false)
/** 加载失败：显式错误态（原先失败后 list 恒空 → 显示"还没有收货地址"，与"接口挂了"无法区分） */
const failed = ref(false)

onLoad((options) => {
  selectMode.value = options?.select === '1'
})

onShow(() => void load())

async function load() {
  failed.value = false
  try {
    const res = await addressApi.list()
    list.value = res.addresses || []
  } catch (e) {
    // toast 已由请求层统一弹出；显式置错误态，禁止静默显示"空地址列表"
    void e
    list.value = []
    failed.value = true
  }
}

function choose(a: Address) {
  if (selectMode.value) {
    checkout.setAddress(a)
    uni.navigateBack()
  }
}

function goEdit(id?: number) {
  uni.navigateTo({ url: id ? `/subpkg/user/address-edit?id=${id}` : '/subpkg/user/address-edit' })
}

async function setDefault(a: Address) {
  await addressApi.setDefault(a.id)
  await load()
  uni.showToast({ title: '已设为默认', icon: 'none' })
}

async function remove(a: Address) {
  const r = await uni.showModal({ title: '删除地址', content: '确定删除该地址吗？' })
  if (!r.confirm) return
  await addressApi.remove(a.id)
  await load()
}
</script>

<template>
  <view class="address">
    <ec-empty
      v-if="failed"
      icon="📡"
      text="地址加载失败，请检查网络后重试"
      action-text="重新加载"
      @action="load"
    />

    <ec-empty v-else-if="!list.length" icon="📍" text="暂无收货地址" action-text="添加地址" @action="goEdit()" />

    <view class="card" v-for="a in list" :key="a.id" @click="choose(a)">
      <view class="row top">
        <text class="name">{{ a.name }}</text>
        <text class="mobile">{{ a.mobile }}</text>
        <text class="def" v-if="a.isDefault">默认</text>
      </view>
      <view class="detail">{{ a.province }}{{ a.city }}{{ a.district || '' }} {{ a.detail }}</view>
      <view class="row ops" @click.stop>
        <view class="row set" @click="!a.isDefault && setDefault(a)">
          <view class="cb" :class="{ on: a.isDefault }"><text v-if="a.isDefault">✓</text></view>
          <text>设为默认</text>
        </view>
        <view class="row acts">
          <text class="act" @click="goEdit(a.id)">编辑</text>
          <text class="act danger" @click="remove(a)">删除</text>
        </view>
      </view>
    </view>

    <view class="bar">
      <view class="btn" @click="goEdit()">＋ 新增收货地址</view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.address {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 20rpx 20rpx 180rpx;
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 20rpx;
}
.top {
  align-items: center;
}
.name {
  font-size: 30rpx;
  font-weight: 600;
  color: #1f2329;
}
.mobile {
  margin-left: 20rpx;
  font-size: 26rpx;
  color: #666;
}
.def {
  margin-left: 16rpx;
  font-size: 20rpx;
  color: #e4393c;
  border: 1rpx solid #e4393c;
  border-radius: 6rpx;
  padding: 2rpx 10rpx;
}
.detail {
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #666;
}
.ops {
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid #f5f6f8;
  justify-content: space-between;
  align-items: center;
}
.set {
  font-size: 26rpx;
  color: #666;
}
.cb {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  border: 2rpx solid #c9cdd4;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  color: #fff;
  margin-right: 12rpx;
  &.on {
    background: #e4393c;
    border-color: #e4393c;
  }
}
.acts {
  font-size: 26rpx;
}
.act {
  color: #666;
  margin-left: 32rpx;
  &.danger {
    color: #e4393c;
  }
}
.bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: #fff;
  box-shadow: 0 -4rpx 16rpx rgba(0, 0, 0, 0.06);
}
.btn {
  background: #e4393c;
  color: #fff;
  font-size: 30rpx;
  text-align: center;
  padding: 22rpx;
  border-radius: 48rpx;
}
</style>
