/** 首页：分类入口 + 热搜词 + 新品推荐网格（楼层为 P0 静态配置）。 */

import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { productApi } from '@/api/product';
import { searchApi } from '@/api/search';
import { ProductCard } from '@/components/business/ProductCard';
import { CATEGORY_ENTRIES } from '@/constants/options';

export function HomePage() {
  const navigate = useNavigate();
  const { data: hot } = useQuery({ queryKey: ['search', 'hot'], queryFn: () => searchApi.hot(8), staleTime: 300_000 });
  const { data: newest } = useQuery({
    queryKey: ['products', 'home-newest'],
    queryFn: () => productApi.list({ sort: 'newest', page: 1, pageSize: 12 }),
  });

  return (
    <div className="flex flex-col gap-8">
      <section className="grid gap-6 lg:grid-cols-[240px_1fr]">
        <nav className="rounded-lg border border-gray-200 bg-white p-4">
          <p className="mb-3 text-sm font-semibold text-gray-800">全部分类</p>
          <ul className="flex flex-col gap-1">
            {CATEGORY_ENTRIES.map((c) => (
              <li key={c.id}>
                <Link
                  to={`/search?categoryId=${c.id}`}
                  className="block rounded px-2 py-1.5 text-sm text-gray-600 hover:bg-red-50 hover:text-red-600"
                >
                  {c.name}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
        <div className="flex flex-col gap-4">
          <div className="flex h-48 items-center rounded-lg bg-gradient-to-r from-red-500 via-orange-400 to-amber-300 px-10 text-white">
            <div>
              <p className="text-3xl font-bold">新人专享 · 品质好物直达</p>
              <p className="mt-2 text-sm text-white/80">P0 演示环境，全链路购物闭环</p>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2 rounded-lg border border-gray-200 bg-white px-4 py-3 text-sm">
            <span className="text-gray-400">热搜：</span>
            {(hot ?? []).map((h) => (
              <button
                key={h.keyword}
                type="button"
                onClick={() => navigate(`/search?q=${encodeURIComponent(h.keyword)}`)}
                className="rounded-full bg-gray-100 px-3 py-1 text-gray-600 hover:bg-red-50 hover:text-red-600"
              >
                {h.keyword}
              </button>
            ))}
          </div>
        </div>
      </section>

      <section>
        <div className="mb-4 flex items-baseline justify-between">
          <h2 className="text-lg font-semibold text-gray-800">新品推荐</h2>
          <Link to="/search" className="text-sm text-gray-400 hover:text-red-600">
            查看全部 →
          </Link>
        </div>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
          {(newest?.list ?? []).map((p) => (
            <ProductCard key={p.spuId} item={p} />
          ))}
        </div>
      </section>
    </div>
  );
}
