<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { storeToRefs } from 'pinia'
import { useCheckoutStore } from '@/store/checkout'
import { useUserStore } from '@/store/user'
import { addressApi } from '@/api/address'
import { couponApi } from '@/api/coupon'
import { orderApi } from '@/api/order'
import { fmtMoney, toCents } from '@/utils/format'
import type { Address, CouponRecord } from '@/api/types'

/** 确认订单页：地址 / 优惠券 / 备注 / 金额试算 / 提交。 */
const checkout = useCheckoutStore()
const user = useUserStore()
const { lines, address, couponId, remark } = storeToRefs(checkout)

const coupons = ref<CouponRecord[]>([])
const showCoupons = ref(false)
const submitting = ref(false)
const previewDisabled = ref(false)

const goodsAmount = computed(() => lines.value.reduce((s, l) => s + toCents(l.price) * l.quantity, 0))
const discount = ref(0)
const payAmount = computed(() => Math.max(goodsAmount.value - discount.value, 0))

onShow(async () => {
  if (!lines.value.length) {
    uni.showToast({ title: '没有待结算商品', icon: 'none' })
    setTimeout(() => uni.navigateBack(), 600)
    return
  }
  if (!address.value && user.token) {
    try {
      const res = await addressApi.list()
      checkout.setAddress(res.addresses.find((a: Address) => a.isDefault) || res.addresses[0] || null)
    } catch (e) {
      void e
    }
  }
  loadCoupons()
  runPreview()
})

watch(couponId, () => runPreview())

async function loadCoupons() {
  if (!user.token) return
  try {
    coupons.value = await couponApi.my()
  } catch (e) {
    void e
  }
}

async function runPreview() {
  if (!couponId.value || previewDisabled.value) {
    discount.value = 0
    return
  }
  try {
    const amounts = lines.value.map((l) => fmtMoney(toCents(l.price) * l.quantity))
    const res = await couponApi.preview(couponId.value, amounts)
    discount.value = res.eligible ? toCents(res.discount) : 0
  } catch (e) {
    // 预览接口不可用时降级：不阻断下单，最终以创建订单返回金额为准（C1）
    void e
    previewDisabled.value = true
    discount.value = 0
  }
}

function chooseAddress() {
  uni.navigateTo({ url: '/subpkg/user/address?select=1' })
}

function pickCoupon(id: number | null) {
  checkout.setCoupon(id)
  showCoupons.value = false
}

async function submit() {
  if (submitting.value) return
  if (!address.value) {
    uni.showToast({ title: '请选择收货地址', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const res = await orderApi.create({
      items: lines.value.map((l) => ({ skuId: l.skuId, quantity: l.quantity })),
      receiverName: address.value.name,
      receiverMobile: address.value.mobile,
      receiverAddress: `${address.value.province}${address.value.city}${address.value.district}${address.value.detail}`,
      couponId: couponId.value || undefined,
      remark: remark.value || undefined,
    })
    checkout.reset()
    // 金额以后端创建订单返回为准，跳转支付页并带订单号
    uni.redirectTo({
      url: `/subpkg/order/pay?orderId=${res.orderId}&payAmount=${encodeURIComponent(fmtMoney(res.payAmount))}`,
    })
  } catch (e) {
    void e // 业务错误（如 3002 优惠券失效）toast 已处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="checkout">
    <!-- 地址 -->
    <view class="card addr" @click="chooseAddress">
      <template v-if="address">
        <view class="row">
          <text class="name">{{ address.name }}</text>
          <text class="mobile">{{ address.mobile }}</text>
        </view>
        <view class="detail">{{ address.province }}{{ address.city }}{{ address.district }} {{ address.detail }}</view>
      </template>
      <view v-else class="none">请选择收货地址</view>
      <text class="arrow">›</text>
    </view>

    <!-- 商品 -->
    <view class="card">
      <view class="line row" v-for="l in lines" :key="l.skuId">
        <image class="cover" :src="l.image" mode="aspectFill" />
        <view class="mid">
          <view class="title">{{ l.title }}</view>
        </view>
        <view class="right">
          <ec-price :value="l.price" :size="28" />
          <text class="qty">×{{ l.quantity }}</text>
        </view>
      </view>
    </view>

    <!-- 优惠券 -->
    <view class="card">
      <view class="row opt" @click="showCoupons = !showCoupons">
        <text>优惠券</text>
        <view class="row">
          <text class="val" :class="{ red: discount > 0 }">
            {{ discount > 0 ? `-¥${fmtMoney(discount)}` : couponId ? '已选' : (coupons.length ? `${coupons.length}张可用` : '暂无可用') }}
          </text>
          <text class="arrow">›</text>
        </view>
      </view>
      <view class="coupon-list" v-if="showCoupons">
        <view
          class="coupon row"
          :class="{ dis: c.status !== 'available' }"
          v-for="c in coupons"
          :key="c.couponId"
          @click="c.status === 'available' && pickCoupon(c.couponId)"
        >
          <view class="cleft">
            <text class="cval">{{ c.type === 3 ? '无门槛' : c.type === 1 ? '满减' : '折扣' }}</text>
          </view>
          <view class="cmid">
            <view class="cname">{{ c.name }}</view>
            <view class="cexp">{{ c.status === 'used' ? '已使用' : c.status === 'expired' ? '已过期' : '可用' }}</view>
          </view>
          <view class="cbt" :class="{ on: couponId === c.couponId }">
            <text v-if="couponId === c.couponId">✓</text>
          </view>
        </view>
        <view class="row opt" v-if="couponId" @click="pickCoupon(null)">
          <text class="val">不使用优惠券</text>
        </view>
      </view>
      <view class="row opt">
        <text>备注</text>
        <input class="remark" v-model="remark" placeholder="选填，给商家留言" placeholder-class="ph" maxlength="100" />
      </view>
    </view>

    <!-- 金额明细 -->
    <view class="card">
      <view class="row opt"><text>商品金额</text><text>¥{{ fmtMoney(goodsAmount) }}</text></view>
      <view class="row opt"><text>运费</text><text>¥0.00</text></view>
      <view class="row opt" v-if="discount > 0"><text>优惠券</text><text class="red">-¥{{ fmtMoney(discount) }}</text></view>
      <view class="hint" v-if="previewDisabled">优惠信息暂不可用，优惠以提交订单时计算为准</view>
    </view>

    <view class="placeholder"></view>

    <view class="bar row">
      <view class="row total">
        <text>合计：</text>
        <ec-price :value="fmtMoney(payAmount)" :size="36" />
      </view>
      <view class="btn" :class="{ dis: submitting }" @click="submit">{{ submitting ? '提交中...' : '提交订单' }}</view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.checkout {
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
.addr {
  position: relative;
  .name {
    font-size: 32rpx;
    font-weight: 600;
  }
  .mobile {
    margin-left: 20rpx;
    font-size: 28rpx;
    color: #666;
  }
  .detail {
    margin-top: 10rpx;
    font-size: 26rpx;
    color: #666;
  }
  .none {
    font-size: 28rpx;
    color: #9aa0a6;
    padding: 16rpx 0;
  }
}
.arrow {
  position: absolute;
  right: 24rpx;
  top: 50%;
  transform: translateY(-50%);
  color: #c9cdd4;
  font-size: 36rpx;
}
.line {
  padding: 16rpx 0;
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
.spec {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #9aa0a6;
}
.right {
  text-align: right;
}
.qty {
  display: block;
  font-size: 24rpx;
  color: #9aa0a6;
  margin-top: 8rpx;
}
.opt {
  justify-content: space-between;
  padding: 20rpx 0;
  font-size: 28rpx;
  color: #1f2329;
  border-top: 1rpx solid #f0f1f3;
  &:first-child {
    border-top: none;
  }
}
.val {
  color: #9aa0a6;
  font-size: 26rpx;
  &.red {
    color: #e4393c;
  }
}
.red {
  color: #e4393c;
}
.coupon-list {
  border-top: 1rpx solid #f0f1f3;
}
.coupon {
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f5f6f8;
  &.dis {
    opacity: 0.5;
  }
}
.cleft {
  width: 140rpx;
  text-align: center;
  flex: none;
}
.cval {
  color: #e4393c;
  font-size: 36rpx;
  font-weight: 600;
}
.cmid {
  flex: 1;
  min-width: 0;
}
.cname {
  font-size: 26rpx;
}
.cexp {
  font-size: 22rpx;
  color: #9aa0a6;
  margin-top: 6rpx;
}
.cbt {
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  border: 2rpx solid #c9cdd4;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  color: #fff;
  flex: none;
  &.on {
    background: #e4393c;
    border-color: #e4393c;
  }
}
.remark {
  flex: 1;
  text-align: right;
  font-size: 26rpx;
}
.ph {
  color: #c0c4cc;
}
.hint {
  font-size: 22rpx;
  color: #ff8f1f;
  padding-top: 12rpx;
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
  align-items: center;
  justify-content: flex-end;
}
.total {
  font-size: 26rpx;
  margin-right: 24rpx;
}
.btn {
  background: #e4393c;
  color: #fff;
  font-size: 30rpx;
  padding: 18rpx 48rpx;
  border-radius: 44rpx;
  &.dis {
    opacity: 0.5;
  }
}
</style>
