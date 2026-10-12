import { api } from './client';
import type { CartListResponse } from '@/types/domain';

export const cartApi = {
  /** GET /api/v1/cart/list（三源聚合：商品 + 价格 + 库存；失效行仍返回） */
  list: () => api.get<CartListResponse>('/v1/cart/list'),
  /** POST /api/v1/cart/add（同 SKU 服务端合并；写接口自动带 Idempotency-Key） */
  add: (skuId: number, quantity = 1) =>
    api.post<{ cartItemId: number; cartTotalQuantity: number }>('/v1/cart/add', { skuId, quantity }),
  /** POST /api/v1/cart/update（以 cartItemId 定位行） */
  update: (cartItemId: number, p: { quantity?: number; selected?: boolean }) =>
    api.post<{ cartItemId: number; cartTotalQuantity: number; selectedQuantity: number }>('/v1/cart/update', {
      cartItemId,
      ...p,
    }),
  /** POST /api/v1/cart/remove（批量删除） */
  remove: (cartItemIds: number[]) =>
    api.post<{ cartTotalQuantity: number }>('/v1/cart/remove', { cartItemIds }),
  /** POST /api/v1/cart/select（cartItemIds 缺省 = 全选/取消全选） */
  select: (cartItemIds: number[] | null, selected: boolean) =>
    api.post<{ cartTotalQuantity: number; selectedQuantity: number }>('/v1/cart/select', {
      cartItemIds: cartItemIds ?? undefined,
      selected,
    }),
};
