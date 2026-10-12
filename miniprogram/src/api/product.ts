import { http } from './client'
import type { PageResult, ProductDetail, ProductListItem } from './types'

export const productApi = {
  /** GET /api/v1/products（列表分页筛选；sort: default/price_asc/price_desc/sales/newest） */
  list: (params: {
    categoryId?: number
    keyword?: string
    sort?: string
    priceMin?: number
    priceMax?: number
    page?: number
    pageSize?: number
  }) => http.get<PageResult<ProductListItem>>('/api/v1/products', params),
  /** GET /api/v1/products/{spuId}（详情含 specs/skus；不存在 3001、未上架 3003） */
  detail: (spuId: number) => http.get<ProductDetail>(`/api/v1/products/${spuId}`),
}
