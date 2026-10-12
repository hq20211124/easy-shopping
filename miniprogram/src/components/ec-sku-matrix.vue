<script setup lang="ts">
/**
 * 多规格 SKU 矩阵选择器：
 * - 按「规格值集合」匹配 sku（sku.specs = {规格名: 规格值}），不依赖名称拼接（plans 5.4）
 * - 三态联动：某规格组合不存在/禁售(status!=1) 时置灰不可选
 * - 价格/可售性以服务端 sku 数据为准，前端不自行计算
 */
import { computed, ref, watch } from 'vue'
import type { ProductDetail, ProductSku } from '@/api/types'

const props = defineProps<{
  visible: boolean
  product: ProductDetail | null
  /** 立即购买/加购模式由父组件决定 */
  confirmText?: string
}>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'confirm', payload: { sku: ProductSku; quantity: number }): void
}>()

const selected = ref<Record<string, string>>({})
const quantity = ref(1)

watch(
  () => props.visible,
  (v) => {
    if (v) {
      selected.value = {}
      quantity.value = 1
    }
  },
)

const specs = computed(() => props.product?.specs ?? [])
const skus = computed(() => props.product?.skus ?? [])

/** 当前选择是否已命中唯一 SKU（所有维度都已选） */
const matchedSku = computed<ProductSku | null>(() => {
  const dims = specs.value
  if (!dims.length || dims.some((d) => !selected.value[d.name])) return null
  return (
    skus.value.find((sku) => dims.every((d) => sku.specs[d.name] === selected.value[d.name])) ?? null
  )
})

const matchedSellable = computed(() => matchedSku.value && matchedSku.value.status === 1)

/** 展示价：命中 SKU 用其价格，否则回落 SPU 最低价 */
const displayPrice = computed(() => matchedSku.value?.price ?? props.product?.price ?? null)

/** 该规格值在「其余已选维度」约束下是否存在可售 SKU —— 决定置灰 */
function valueDisabled(dimension: string, value: string): boolean {
  return !skus.value.some(
    (sku) =>
      sku.status === 1 &&
      sku.specs[dimension] === value &&
      Object.entries(selected.value).every(([name, v]) => name === dimension || !v || sku.specs[name] === v),
  )
}

function toggleValue(dimension: string, value: string) {
  if (valueDisabled(dimension, value)) return
  selected.value = { ...selected.value, [dimension]: selected.value[dimension] === value ? '' : value }
}

function confirm() {
  if (!matchedSellable.value || !matchedSku.value) return
  emit('confirm', { sku: matchedSku.value, quantity: quantity.value })
  emit('update:visible', false)
}

const hint = computed(() => {
  if (!props.visible) return ''
  if (specs.value.some((d) => !selected.value[d.name])) return '请选择规格'
  if (!matchedSku.value) return '该规格组合不存在'
  return matchedSellable.value ? '' : '该规格已售罄/停售'
})
</script>

<template>
  <view v-if="visible" class="sku-mask" @click="emit('update:visible', false)">
    <view class="sku-popup" @click.stop>
      <view class="sku-header">
        <image class="sku-header__img" :src="matchedSku?.images?.[0] || product?.mainImage" mode="aspectFill" />
        <view class="sku-header__info">
          <ec-price :value="displayPrice" size="lg" />
          <text v-if="product?.originalPrice" class="sku-header__origin">¥{{ product.originalPrice }}</text>
          <text class="sku-header__hint">{{ hint }}</text>
        </view>
        <text class="sku-header__close" @click="emit('update:visible', false)">✕</text>
      </view>

      <scroll-view scroll-y class="sku-body">
        <view v-for="spec in specs" :key="spec.name" class="sku-dim">
          <text class="sku-dim__name">{{ spec.name }}</text>
          <view class="sku-dim__values">
            <view
              v-for="value in spec.values"
              :key="value"
              class="sku-value"
              :class="{ 'is-active': selected[spec.name] === value, 'is-disabled': valueDisabled(spec.name, value) }"
              @click="toggleValue(spec.name, value)"
            >
              {{ value }}
            </view>
          </view>
        </view>

        <view class="sku-qty">
          <text class="sku-dim__name">数量</text>
          <ec-stepper v-model="quantity" :max="99" />
        </view>
      </scroll-view>

      <view class="sku-footer">
        <button
          class="sku-footer__btn"
          :disabled="!matchedSellable"
          @click="confirm"
        >
          {{ confirmText || '确定' }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.sku-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 99;
  display: flex;
  align-items: flex-end;
}
.sku-popup {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  max-height: 75vh;
  display: flex;
  flex-direction: column;
}
.sku-header {
  display: flex;
  padding: 24rpx;
  position: relative;
}
.sku-header__img {
  width: 160rpx;
  height: 160rpx;
  border-radius: 12rpx;
  background: #f5f5f5;
}
.sku-header__info {
  flex: 1;
  margin-left: 20rpx;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8rpx;
}
.sku-header__origin {
  font-size: 24rpx;
  color: #999;
  text-decoration: line-through;
}
.sku-header__hint {
  font-size: 24rpx;
  color: #e4393c;
}
.sku-header__close {
  position: absolute;
  right: 24rpx;
  top: 24rpx;
  font-size: 32rpx;
  color: #999;
  padding: 8rpx;
}
.sku-body {
  flex: 1;
  max-height: 50vh;
  padding: 0 24rpx;
}
.sku-dim {
  margin-bottom: 24rpx;
}
.sku-dim__name {
  font-size: 26rpx;
  color: #666;
  display: block;
  margin-bottom: 12rpx;
}
.sku-dim__values {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.sku-value {
  padding: 12rpx 32rpx;
  background: #f5f5f5;
  border-radius: 40rpx;
  font-size: 26rpx;
  color: #333;
  border: 1rpx solid transparent;
}
.sku-value.is-active {
  background: #fff1f0;
  color: #e4393c;
  border-color: #e4393c;
}
.sku-value.is-disabled {
  color: #ccc;
  background: #fafafa;
  text-decoration: line-through;
}
.sku-qty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 0 32rpx;
}
.sku-footer {
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
}
.sku-footer__btn {
  background: #e4393c;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.sku-footer__btn[disabled] {
  background: #f5a8a8;
  color: #fff;
}
</style>
