import { api } from './client';
import type { CouponPreview, CouponRecord } from '@/types/domain';

export const couponApi = {
  /** GET /api/v1/coupons/my（我的券；status: available/used/expired，缺省全部） */
  my: (status?: string) => api.get<CouponRecord[]>('/v1/coupons/my', { status }),
  /** POST /api/v1/coupons/preview（试算；不满足门槛 eligible=false 不抛异常） */
  preview: (couponId: number, amount: (string | number)[]) =>
    api.post<CouponPreview>('/v1/coupons/preview', { couponId, amount }),
};
