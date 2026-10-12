<script setup lang="ts">
/** 数量步进器（受控组件）。 */
const props = defineProps<{ modelValue: number; min?: number; max?: number; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: number): void }>()

const min = () => props.min ?? 1
const max = () => props.max ?? 99

const dec = () => {
  if (props.disabled || props.modelValue <= min()) return
  emit('update:modelValue', props.modelValue - 1)
}
const inc = () => {
  if (props.disabled || props.modelValue >= max()) return
  emit('update:modelValue', props.modelValue + 1)
}
</script>

<template>
  <view class="stepper">
    <view class="stepper__btn" :class="{ 'is-disabled': disabled || modelValue <= min() }" @click="dec">−</view>
    <text class="stepper__num">{{ modelValue }}</text>
    <view class="stepper__btn" :class="{ 'is-disabled': disabled || modelValue >= max() }" @click="inc">＋</view>
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
