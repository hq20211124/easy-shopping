/** 全站布局：顶栏（搜索/购物车角标/用户）+ 内容区 + 底部。 */

import { type FormEvent, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { cartApi } from '@/api/cart';
import { authApi } from '@/api/auth';
import { useAuthStore } from '@/store/auth';
import { toast } from '@/store/toast';

export function AppLayout() {
  const navigate = useNavigate();
  const { accessToken, nickname, clear } = useAuthStore();
  const [keyword, setKeyword] = useState('');

  const { data: cart } = useQuery({
    queryKey: ['cart', 'badge'],
    queryFn: cartApi.list,
    enabled: !!accessToken,
    staleTime: 30_000,
  });

  const onSearch = (e: FormEvent) => {
    e.preventDefault();
    const q = keyword.trim();
    navigate(q ? `/search?q=${encodeURIComponent(q)}` : '/search');
  };

  const onLogout = async () => {
    try {
      await authApi.logout(useAuthStore.getState().refreshToken);
    } catch {
      // 登出接口失败不阻塞本地会话清理
    }
    clear();
    toast.success('已退出登录');
    navigate('/');
  };

  return (
    <div className="flex min-h-screen flex-col bg-gray-50">
      <header className="sticky top-0 z-40 border-b border-gray-200 bg-white">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-6 px-4">
          <Link to="/" className="shrink-0 text-lg font-bold text-red-600">
            惠购商城
          </Link>
          <form onSubmit={onSearch} className="flex flex-1 items-center">
            <input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="搜索商品"
              className="h-9 w-full max-w-md rounded-l border border-gray-300 px-3 text-sm outline-none focus:border-red-400"
            />
            <button type="submit" className="h-9 rounded-r bg-red-500 px-5 text-sm text-white hover:bg-red-600">
              搜索
            </button>
          </form>
          <nav className="flex items-center gap-4 text-sm">
            <NavLink to="/cart" className="relative text-gray-700 hover:text-red-600">
              购物车
              {!!cart && cart.totalQuantity > 0 && (
                <span className="absolute -right-3 -top-2 rounded-full bg-red-500 px-1.5 text-xs text-white">
                  {cart.totalQuantity}
                </span>
              )}
            </NavLink>
            <NavLink to="/orders" className="text-gray-700 hover:text-red-600">
              我的订单
            </NavLink>
            <NavLink to="/address" className="text-gray-700 hover:text-red-600">
              地址管理
            </NavLink>
            {accessToken ? (
              <span className="flex items-center gap-2">
                <span className="text-gray-700">{nickname ?? '我'}</span>
                <button type="button" onClick={onLogout} className="text-gray-400 hover:text-red-600">
                  退出
                </button>
              </span>
            ) : (
              <Link to="/login" className="text-red-600 hover:underline">
                登录
              </Link>
            )}
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        <Outlet />
      </main>
      <footer className="border-t border-gray-200 bg-white py-6 text-center text-xs text-gray-400">
        惠购商城 · P0 演示环境 · 金额与库存以服务端返回为准
      </footer>
    </div>
  );
}
