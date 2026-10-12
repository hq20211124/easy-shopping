<script setup lang="ts">
/** 价格展示：金额一律以后端下发为准，仅做两位小数格式化。 */
import { fmtMoney } from '@/utils/format'
import type { Money } from '@/api/types'

defineProps<{ value: Money; size?: 'sm' | 'lg' }>()
</script>

<template>
  <text class="ec-price" :class="[size === 'lg' ? 'ec-price--lg' : 'ec-price--sm']">
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
.ec-price--sm {
  font-size: 28rpx;
}
.ec-price--lg {
  font-size: 44rpx;
}
</style>
