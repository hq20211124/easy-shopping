import { api } from './client';
import type { HotWord, SearchResult, SuggestResult } from '@/types/domain';

export interface SearchParams {
  q?: string;
  categoryId?: number;
  priceMin?: number;
  priceMax?: number;
  /** default/price_asc/price_desc/sales/newest */
  sort?: string;
  /** 上一页 nextCursor；换筛选/排序条件必须回首页（不传） */
  cursor?: string | null;
  pageSize?: number;
}

export const searchApi = {
  /** GET /api/v1/search/products（cursor 分页，末页 nextCursor=null） */
  products: (p: SearchParams = {}) =>
    api.get<SearchResult>('/v1/search/products', { ...p, cursor: p.cursor ?? undefined }),
  suggestions: (q: string, limit = 10) => api.get<SuggestResult>('/v1/search/suggestions', { q, limit }),
  hot: (limit = 10) => api.get<HotWord[]>('/v1/search/hot', { limit }),
};
