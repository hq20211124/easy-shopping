<script setup lang="ts">
/** 价格展示：金额一律以后端下发为准，仅做两位小数格式化。size 传数字（rpx）。 */
import { computed } from 'vue'
import { fmtMoney } from '@/utils/format'
import type { Money } from '@/api/types'

const props = defineProps<{ value: Money; size?: number | 'sm' | 'lg' }>()

const px = computed(() => {
  if (typeof props.size === 'number') return `${props.size}rpx`
  return props.size === 'lg' ? '44rpx' : '28rpx'
})
</script>

<template>
  <text class="ec-price" :style="{ fontSize: px }">
    <text class="ec-price__symbol">¥</text>{{ fmtMoney(value) }}
  </text>
</template>

<style scoped>
.ec-price {
  color: #e4393c;
  font-weight: 600;
}
.ec-price__symbol {
  font-size: 0.7em;
}
</style>
