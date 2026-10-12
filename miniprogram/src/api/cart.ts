import { http } from './client'
import type { CartListResponse } from './types'

export const cartApi = {
  /** GET /api/v1/cart/list（三源聚合：商品+价格+库存；失效行仍返回） */
  list: () => http.get<CartListResponse>('/api/v1/cart/list'),
  /** POST /api/v1/cart/add（同 SKU 服务端合并；写接口自动带 Idempotency-Key） */
  add: (skuId: number, quantity = 1) =>
    http.post<{ cartItemId: number; cartTotalQuantity: number }>('/api/v1/cart/add', { skuId, quantity }),
  /** POST /api/v1/cart/update（以 cartItemId 定位行，禁止用 skuId） */
  update: (cartItemId: number, p: { quantity?: number; selected?: boolean }) =>
    http.post<{ cartItemId: number; cartTotalQuantity: number; selectedQuantity: number }>('/api/v1/cart/update', {
      cartItemId,
      ...p,
    }),
  /** POST /api/v1/cart/remove（批量删除） */
  remove: (cartItemIds: number[]) =>
    http.post<{ cartTotalQuantity: number }>('/api/v1/cart/remove', { cartItemIds }),
  /** POST /api/v1/cart/select（cartItemIds 缺省 = 全选/取消全选） */
  select: (cartItemIds: number[] | null, selected: boolean) =>
    http.post<{ cartTotalQuantity: number; selectedQuantity: number }>('/api/v1/cart/select', {
      cartItemIds: cartItemIds ?? undefined,
      selected,
    }),
}
