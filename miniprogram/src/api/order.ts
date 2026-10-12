import { http } from './client'
import type { CreateOrderPayload, CreateOrderResponse, OrderDetailVO, OrderListVO, PageResult } from './types'

export const orderApi = {
  /** POST /api/v1/orders（下单；写接口必带 Idempotency-Key，由 client 统一注入） */
  create: (payload: CreateOrderPayload) => http.post<CreateOrderResponse>('/api/v1/orders', payload),
  /** GET /api/v1/orders（我的订单分页；status：0~7 或 all，0 基 8 值状态机） */
  list: (params: { status?: string; page?: number; pageSize?: number }) =>
    http.get<PageResult<OrderListVO>>('/api/v1/orders', params),
  /** GET /api/v1/orders/{orderId}（详情含子单/金额明细/timeline/actions） */
  detail: (orderId: number) => http.get<OrderDetailVO>(`/api/v1/orders/${orderId}`),
  /** POST /api/v1/orders/{orderId}/cancel（仅待付款可取消） */
  cancel: (orderId: number, reason?: string) =>
    http.post<{ orderId: number; status: number; statusText: string }>(
      `/api/v1/orders/${orderId}/cancel`,
      reason ? { reason } : {},
    ),
  /** POST /api/v1/orders/{orderId}/confirm（确认收货：待收货 → 已完成） */
  confirm: (orderId: number) =>
    http.post<{ orderId: number; status: number; statusText: string }>(`/api/v1/orders/${orderId}/confirm`, {}),
}
