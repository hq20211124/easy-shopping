import { defineStore } from 'pinia'
import { ref } from 'vue'
import { cartApi } from '@/api/cart'

/** 购物车角标（tabBar 徽标）；数量以后端下发为准。 */
export const useCartStore = defineStore('cart', () => {
  const count = ref(0)

  async function refreshCount() {
    try {
      const res = await cartApi.list()
      count.value = res.totalQuantity
    } catch {
      /* 游客态/网络异常不打扰用户 */
    }
  }

  return { count, refreshCount }
})
