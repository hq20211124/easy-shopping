<script setup lang="ts">
/** 数量步进器：v-model 受控 + change 事件（供需要落库的场景监听）。 */
import { toRefs } from 'vue'

const props = defineProps<{ modelValue: number; min?: number; max?: number; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: number): void; (e: 'change', v: number): void }>()

const { modelValue, min, max, disabled } = toRefs(props)

const lower = () => min.value ?? 1
const upper = () => max.value ?? 99

function apply(v: number) {
  if (v < lower() || v > upper()) return
  emit('update:modelValue', v)
  emit('change', v)
}

const dec = () => {
  if (disabled.value || modelValue.value <= lower()) return
  apply(modelValue.value - 1)
}
const inc = () => {
  if (disabled.value || modelValue.value >= upper()) return
  apply(modelValue.value + 1)
}
</script>

<template>
  <view class="stepper">
    <view class="stepper__btn" :class="{ 'is-disabled': disabled || modelValue <= lower() }" @click="dec">−</view>
    <text class="stepper__num">{{ modelValue }}</text>
    <view class="stepper__btn" :class="{ 'is-disabled': disabled || modelValue >= upper() }" @click="inc">＋</view>
  </view>
</template>

<style scoped>
.stepper {
  display: flex;
  align-items: center;
}
.stepper__btn {
  width: 52rpx;
  height: 52rpx;
  line-height: 48rpx;
  text-align: center;
  border: 1rpx solid #ddd;
  border-radius: 8rpx;
  font-size: 32rpx;
  color: #333;
  background: #fff;
}
.stepper__btn.is-disabled {
  color: #ccc;
  background: #f7f7f7;
}
.stepper__num {
  min-width: 72rpx;
  text-align: center;
  font-size: 28rpx;
}
</style>
