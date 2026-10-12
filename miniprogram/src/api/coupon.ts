import { http } from './client'
import type { CouponPreview, CouponRecord, Money } from './types'

export const couponApi = {
  /** GET /api/v1/coupons/my（status：0可用/1已使用/2已过期；后端返回英文枚举时不过滤） */
  my: () => http.get<CouponRecord[]>('/api/v1/coupons/my', {}, true),
  /**
   * POST /api/v1/coupons/preview（试算：couponId=用户券记录 ID，amount=订单商品金额列表）
   * 不满足门槛返回 eligible=false + reason，不抛异常
   */
  preview: (couponId: number, amount: Money[]) => http.post<CouponPreview>('/api/v1/coupons/preview', { couponId, amount }, true),
}
