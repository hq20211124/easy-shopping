/** 商品卡片（首页/搜索/推荐复用）。 */

import { Link } from 'react-router-dom';
import { formatPrice } from '@/utils/format';
import type { ProductListItem, SearchProductItem } from '@/types/domain';

export function ProductCard({ item }: { item: ProductListItem | SearchProductItem }) {
  return (
    <Link
      to={`/product/${item.spuId}`}
      className="group flex flex-col overflow-hidden rounded-lg border border-gray-200 bg-white transition-shadow hover:shadow-md"
    >
      <div className="aspect-square overflow-hidden bg-gray-100">
        <img src={item.mainImage} alt={item.name} loading="lazy" className="h-full w-full object-cover transition-transform group-hover:scale-105" />
      </div>
      <div className="flex flex-1 flex-col gap-1 p-3">
        <p className="line-clamp-2 text-sm text-gray-800">{item.name}</p>
        <div className="mt-auto flex items-baseline gap-2">
          <span className="text-base font-semibold text-red-600">{formatPrice(item.price)}</span>
          {'originalPrice' in item && item.originalPrice && (
            <span className="text-xs text-gray-400 line-through">{formatPrice(item.originalPrice)}</span>
          )}
        </div>
        {'salesCount' in item && item.salesCount !== undefined && (
          <span className="text-xs text-gray-400">已售 {item.salesCount}</span>
        )}
      </div>
    </Link>
  );
}
