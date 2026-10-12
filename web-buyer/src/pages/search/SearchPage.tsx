/** 搜索结果页：关键词 + 类目/价格筛选 + 排序 + cursor 分页「加载更多」。 */

import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useInfiniteQuery } from '@tanstack/react-query';
import { searchApi } from '@/api/search';
import { ProductCard } from '@/components/business/ProductCard';
import { CATEGORY_ENTRIES, SORT_OPTIONS } from '@/constants/options';

const PAGE_SIZE = 20;

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const categoryId = Number(params.get('categoryId')) || undefined;
  const sort = params.get('sort') ?? 'default';
  const priceMin = params.get('priceMin') ?? '';
  const priceMax = params.get('priceMax') ?? '';
  const [minInput, setMinInput] = useState(priceMin);
  const [maxInput, setMaxInput] = useState(priceMax);

  const queryKey = ['search', 'products', q, categoryId ?? '', sort, priceMin, priceMax];

  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }) =>
      searchApi.products({
        q,
        categoryId,
        sort,
        priceMin: priceMin ? Number(priceMin) : undefined,
        priceMax: priceMax ? Number(priceMax) : undefined,
        cursor: pageParam,
        pageSize: PAGE_SIZE,
      }),
    initialPageParam: null as string | null,
    // cursor 分页：末页 nextCursor=null；换条件必须回首页（core-search README §1）
    getNextPageParam: (last) => last.nextCursor,
  });

  const items = useMemo(() => data?.pages.flatMap((p) => p.list) ?? [], [data]);
  const total = data?.pages[0]?.total ?? 0;

  const update = (next: Record<string, string | undefined>) => {
    const merged = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (v === undefined || v === '') merged.delete(k);
      else merged.set(k, v);
    }
    setParams(merged);
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-lg border border-gray-200 bg-white p-4">
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span className="text-gray-500">分类：</span>
          <button
            type="button"
            onClick={() => update({ categoryId: undefined })}
            className={!categoryId ? 'font-semibold text-red-600' : 'text-gray-600 hover:text-red-600'}
          >
            全部
          </button>
          {CATEGORY_ENTRIES.map((c) => (
            <button
              key={c.id}
              type="button"
              onClick={() => update({ categoryId: String(c.id) })}
              className={categoryId === c.id ? 'font-semibold text-red-600' : 'text-gray-600 hover:text-red-600'}
            >
              {c.name}
            </button>
          ))}
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
          <span className="text-gray-500">排序：</span>
          {SORT_OPTIONS.map((s) => (
            <button
              key={s.value}
              type="button"
              onClick={() => update({ sort: s.value === 'default' ? undefined : s.value })}
              className={sort === s.value ? 'font-semibold text-red-600' : 'text-gray-600 hover:text-red-600'}
            >
              {s.label}
            </button>
          ))}
          <span className="mx-2 text-gray-300">|</span>
          <span className="text-gray-500">价格：</span>
          <input
            value={minInput}
            onChange={(e) => setMinInput(e.target.value.replace(/[^\d.]/g, ''))}
            placeholder="最低"
            className="h-8 w-20 rounded border border-gray-300 px-2 text-xs outline-none focus:border-red-400"
          />
          <span className="text-gray-300">—</span>
          <input
            value={maxInput}
            onChange={(e) => setMaxInput(e.target.value.replace(/[^\d.]/g, ''))}
            placeholder="最高"
            className="h-8 w-20 rounded border border-gray-300 px-2 text-xs outline-none focus:border-red-400"
          />
          <button
            type="button"
            onClick={() => update({ priceMin: minInput || undefined, priceMax: maxInput || undefined })}
            className="rounded border border-red-400 px-3 py-1 text-xs text-red-500 hover:bg-red-50"
          >
            确定
          </button>
        </div>
      </div>

      <p className="text-sm text-gray-500">
        {q ? `「${q}」` : '全部商品'} 共 {total} 件
      </p>

      {isLoading ? (
        <p className="py-20 text-center text-gray-400">加载中…</p>
      ) : items.length === 0 ? (
        <div className="py-20 text-center text-gray-400">
          <p>没有找到相关商品</p>
          <Link to="/" className="mt-2 inline-block text-sm text-red-500 hover:underline">
            回首页逛逛
          </Link>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
            {items.map((item) => (
              <ProductCard key={item.spuId} item={item} />
            ))}
          </div>
          <div className="py-4 text-center">
            {hasNextPage ? (
              <button
                type="button"
                onClick={() => fetchNextPage()}
                disabled={isFetchingNextPage}
                className="rounded border border-gray-300 bg-white px-8 py-2 text-sm text-gray-600 hover:border-red-400 hover:text-red-600 disabled:opacity-50"
              >
                {isFetchingNextPage ? '加载中…' : '加载更多'}
              </button>
            ) : (
              <span className="text-sm text-gray-400">已加载全部商品</span>
            )}
          </div>
        </>
      )}
    </div>
  );
}
