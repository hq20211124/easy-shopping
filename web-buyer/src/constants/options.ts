/** 前端静态配置：分类入口与排序项（P0 后端无买家端类目树接口，作为首页/筛选入口内容配置）。 */

export interface CategoryEntry {
  id: number;
  name: string;
}

export const CATEGORY_ENTRIES: CategoryEntry[] = [
  { id: 11, name: '手机数码' },
  { id: 12, name: '电脑办公' },
  { id: 13, name: '家用电器' },
  { id: 14, name: '服饰鞋包' },
  { id: 15, name: '食品生鲜' },
];

export interface SortOption {
  value: string;
  label: string;
}

/** 与 core-search sort 白名单一致：default/price_asc/price_desc/sales/newest。 */
export const SORT_OPTIONS: SortOption[] = [
  { value: 'default', label: '综合' },
  { value: 'sales', label: '销量' },
  { value: 'price_asc', label: '价格↑' },
  { value: 'price_desc', label: '价格↓' },
  { value: 'newest', label: '新品' },
];
