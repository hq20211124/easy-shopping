import { api } from './client';
import type { PaymentStatusResponse, PrepayResponse } from '@/types/domain';

export const paymentApi = {
  /** POST /api/v1/payment/prepay（只传 orderId+channel，金额由服务端反查订单——裁决 C7 防改价） */
  prepay: (orderId: number, channel: number) =>
    api.post<PrepayResponse>('/v1/payment/prepay', { orderId, channel }),
  /** POST /api/v1/payment/mock/pay（P0 mock 付款，走与真实回调完全相同的处理链） */
  mockPay: (paymentId: number) =>
    api.post<{ paymentId: number; orderId: number; status: string }>('/v1/payment/mock/pay', { paymentId }),
  /** GET /api/v1/payment/status/{orderId}（轮询 pending/paid/refunding/refunded） */
  status: (orderId: number) => api.get<PaymentStatusResponse>(`/v1/payment/status/${orderId}`),
};
