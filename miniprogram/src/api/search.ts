import { http } from './client'
import type { HotWord, SearchResult, SuggestResult } from './types'

export const searchApi = {
  /**
   * GET /api/v1/search/products（关键词+筛选+排序+cursor 分页）
   * cursor 传上一页 nextCursor；换排序/筛选条件必须回到首页（不传 cursor）
   */
  products: (params: {
    q: string
    categoryId?: number
    priceMin?: number
    priceMax?: number
    sort?: string
    cursor?: string
    pageSize?: number
  }) => http.get<SearchResult>('/api/v1/search/products', params, true),
  /** GET /api/v1/search/suggestions（搜索联想） */
  suggestions: (q: string, limit = 10) => http.get<SuggestResult>('/api/v1/search/suggestions', { q, limit }, true),
  /** GET /api/v1/search/hot（热搜词，无流量时返回种子词） */
  hot: (limit = 10) => http.get<HotWord[]>('/api/v1/search/hot', { limit }, true),
}
