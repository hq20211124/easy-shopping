<script setup lang="ts">
/** 空状态：图标 + 文案 + 可选操作按钮。 */
defineProps<{ icon?: string; text?: string; actionText?: string }>()
defineEmits<{ (e: 'action'): void }>()
</script>

<template>
  <view class="ec-empty">
    <text class="ec-empty__icon">{{ icon || '🕳️' }}</text>
    <text class="ec-empty__text">{{ text || '暂无数据' }}</text>
    <view class="ec-empty__btn" v-if="actionText" @click="$emit('action')">{{ actionText }}</view>
  </view>
</template>

<style scoped>
.ec-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 120rpx 0;
  color: #999;
}
.ec-empty__icon {
  font-size: 80rpx;
  margin-bottom: 20rpx;
}
.ec-empty__text {
  font-size: 26rpx;
}
.ec-empty__btn {
  margin-top: 32rpx;
  font-size: 26rpx;
  color: #e4393c;
  border: 1rpx solid #e4393c;
  border-radius: 32rpx;
  padding: 12rpx 40rpx;
}
</style>
