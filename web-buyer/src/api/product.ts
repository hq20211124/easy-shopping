import { api } from './client';
import type { PageResult } from '@/types/api';
import type { ProductDetail, ProductListItem } from '@/types/domain';

export interface ProductListParams {
  categoryId?: number;
  keyword?: string;
  /** default/price_asc/price_desc/sales/newest */
  sort?: string;
  priceMin?: number;
  priceMax?: number;
  page?: number;
  pageSize?: number;
}

export const productApi = {
  /** GET /api/v1/products（core-product 买家端分页） */
  list: (p: ProductListParams = {}) => api.get<PageResult<ProductListItem>>('/v1/products', { ...p }),
  /** GET /api/v1/products/{spuId}（含 specs/skus 矩阵；3001 不存在 / 3003 未上架） */
  detail: (spuId: number) => api.get<ProductDetail>(`/v1/products/${spuId}`),
};
