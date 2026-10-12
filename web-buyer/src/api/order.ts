import { api } from './client';
import type { PageResult } from '@/types/api';
import type { CreateOrderPayload, CreateOrderResponse, OrderDetailVO, OrderListVO } from '@/types/domain';

export const orderApi = {
  /** POST /api/v1/orders（0 基状态机；写接口自动带 Idempotency-Key，重复提交 1005 不重复建单） */
  create: (p: CreateOrderPayload) => api.post<CreateOrderResponse>('/v1/orders', p),
  /** GET /api/v1/orders（status=0..7 或不传=全部） */
  list: (p: { status?: number | null; page?: number; pageSize?: number }) =>
    api.get<PageResult<OrderListVO>>('/v1/orders', { status: p.status ?? undefined, page: p.page, pageSize: p.pageSize }),
  detail: (orderId: number) => api.get<OrderDetailVO>(`/v1/orders/${orderId}`),
  /** 仅待付款可取消（已付款走退款）；后端 4002 裁决非法流转 */
  cancel: (orderId: number, reason?: string) =>
    api.post<{ orderId: number; status: number; statusText: string }>(`/v1/orders/${orderId}/cancel`, { reason }),
  /** 确认收货（待收货 → 已完成） */
  confirmReceipt: (orderId: number) =>
    api.post<{ orderId: number; status: number; statusText: string }>(`/v1/orders/${orderId}/confirm`),
};
