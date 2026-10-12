<script setup lang="ts">
/** 空状态。 */
defineProps<{ text?: string }>()
</script>

<template>
  <view class="ec-empty">
    <text class="ec-empty__icon">🕳️</text>
    <text class="ec-empty__text">{{ text || '暂无数据' }}</text>
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
</style>
