import { http } from './client'
import type { PaymentStatusResponse, PrepayResponse } from './types'

export const paymentApi = {
  /** POST /api/v1/payment/prepay（只传 orderId+channel，金额由服务端反查订单——裁决 C7 防改价） */
  prepay: (orderId: number, channel: number) =>
    http.post<PrepayResponse>('/api/v1/payment/prepay', { orderId, channel }),
  /** POST /api/v1/payment/mock/pay（P0 mock 付款，走与真实回调完全相同的处理链） */
  mockPay: (paymentId: number) =>
    http.post<{ paymentId: number; orderId: number; status: string }>('/api/v1/payment/mock/pay', { paymentId }),
  /** GET /api/v1/payment/status/{orderId}（轮询 pending/paid/refunding/refunded） */
  status: (orderId: number) => http.get<PaymentStatusResponse>(`/api/v1/payment/status/${orderId}`),
}
