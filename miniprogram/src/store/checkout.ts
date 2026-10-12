import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Address, Money } from '@/api/types'

export interface CheckoutLine {
  skuId: number
  title: string
  image: string
  price: Money
  quantity: number
  /** 购物车来源时携带，下单成功后用于清理本地已购行（服务端不自动删车） */
  cartItemId?: number
}

/**
 * 结算草稿：加购/立即购买 → 结算页共享状态。
 * 金额为本地预估展示，最终应付以后端「下单响应」为准。
 */
export const useCheckoutStore = defineStore('checkout', () => {
  const lines = ref<CheckoutLine[]>([])
  const address = ref<Address | null>(null)
  const couponId = ref<number | null>(null)
  const remark = ref('')

  function start(linesIn: CheckoutLine[]) {
    lines.value = linesIn
    couponId.value = null
    remark.value = ''
  }

  function setAddress(a: Address | null) {
    address.value = a
  }

  function reset() {
    lines.value = []
    couponId.value = null
    remark.value = ''
  }

  return { lines, address, couponId, remark, start, setAddress, reset }
})
