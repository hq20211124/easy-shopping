<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { addressApi } from '@/api/address'
import type { AddressPayload } from '@/api/types'

/** 地址编辑：id 有值为编辑，无值为新增。 */
const id = ref(0)
const form = ref<AddressPayload>({
  name: '',
  mobile: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  isDefault: false,
})
const saving = ref(false)
/**
 * 编辑态回填失败：原值没拿到时表单是全空的，若还允许保存会用空值覆盖真实地址（静默数据损坏）。
 * 因此显式置位后禁用保存并给出可返回的提示。
 */
const loadFailed = ref(false)

onLoad(async (options) => {
  id.value = Number(options?.id || 0)
  if (!id.value) return
  try {
    const res = await addressApi.list()
    const a = res.addresses.find((x) => x.id === id.value)
    if (a) {
      form.value = {
        name: a.name,
        mobile: a.mobile,
        province: a.province,
        city: a.city,
        district: a.district || '',
        detail: a.detail,
        isDefault: !!a.isDefault,
      }
    } else {
      loadFailed.value = true
    }
  } catch (e) {
    // toast 已由请求层统一弹出；显式禁用保存，禁止用空表单覆盖原地址
    void e
    loadFailed.value = true
  }
})

const PHONE_RE = /^1[3-9]\d{9}$/

function goBack() {
  uni.navigateBack()
}

async function save() {
  if (saving.value) return
  if (loadFailed.value) {
    uni.showToast({ title: '原地址未加载成功，请返回重试', icon: 'none' })
    return
  }
  const f = form.value
  if (!f.name.trim()) {
    uni.showToast({ title: '请输入收货人姓名', icon: 'none' })
    return
  }
  if (!PHONE_RE.test(f.mobile)) {
    uni.showToast({ title: '请输入正确手机号', icon: 'none' })
    return
  }
  if (!f.province.trim() || !f.city.trim() || !f.detail.trim()) {
    uni.showToast({ title: '请完整填写地址', icon: 'none' })
    return
  }
  saving.value = true
  try {
    if (id.value) await addressApi.update(id.value, f)
    else await addressApi.create(f)
    uni.showToast({ title: '已保存', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 500)
  } catch (e) {
    void e
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <view class="edit">
    <ec-empty
      v-if="loadFailed"
      icon="⚠️"
      text="原地址加载失败，保存已禁用（避免用空表单覆盖）"
      action-text="返回"
      @action="goBack"
    />
    <template v-else>
      <view class="card">
        <view class="row field">
          <text class="label">收货人</text>
          <input class="input" v-model="form.name" placeholder="姓名" placeholder-class="ph" maxlength="20" />
        </view>
        <view class="row field">
          <text class="label">手机号</text>
          <input
            class="input"
            v-model="form.mobile"
            type="number"
            placeholder="11 位手机号"
            placeholder-class="ph"
            maxlength="11"
          />
        </view>
        <view class="row field">
          <text class="label">省</text>
          <input class="input" v-model="form.province" placeholder="省份" placeholder-class="ph" maxlength="20" />
        </view>
        <view class="row field">
          <text class="label">市</text>
          <input class="input" v-model="form.city" placeholder="城市" placeholder-class="ph" maxlength="20" />
        </view>
        <view class="row field">
          <text class="label">区</text>
          <input class="input" v-model="form.district" placeholder="区/县（选填）" placeholder-class="ph" maxlength="20" />
        </view>
        <view class="row field">
          <text class="label">详细地址</text>
          <input class="input" v-model="form.detail" placeholder="街道门牌等" placeholder-class="ph" maxlength="100" />
        </view>
      </view>

      <view class="card">
        <view class="row field">
          <text class="label">设为默认地址</text>
          <switch :checked="form.isDefault" color="#e4393c" @change="(e: any) => (form.isDefault = e.detail.value)" />
        </view>
      </view>

      <view class="btn" :class="{ dis: saving }" @click="save">{{ saving ? '保存中...' : '保存' }}</view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.edit {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 20rpx;
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 8rpx 24rpx;
  margin-bottom: 20rpx;
}
.field {
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f5f6f8;
  align-items: center;
  &:last-child {
    border-bottom: none;
  }
}
.label {
  width: 180rpx;
  font-size: 28rpx;
  color: #1f2329;
  flex: none;
}
.input {
  flex: 1;
  font-size: 28rpx;
  color: #1f2329;
}
.ph {
  color: #c0c4cc;
}
.btn {
  background: #e4393c;
  color: #fff;
  font-size: 30rpx;
  text-align: center;
  padding: 24rpx;
  border-radius: 48rpx;
  margin: 40rpx 20rpx 0;
  &.dis {
    opacity: 0.5;
  }
}
</style>
