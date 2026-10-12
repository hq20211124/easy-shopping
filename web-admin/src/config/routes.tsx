/**
 * 路由配置（React Router v7 data router）
 *
 *  - 按模块聚合 + 路由懒加载，首屏只加载布局与工作台（plans/admin-frontend.md 1.3）
 *  - 权限路由：RequireAuth 登录守卫 + RequirePermission 权限守卫（1.4）；菜单按权限码过滤（2.3）
 *  - 403（Layout 内）/ 404（兜底）/ 500 三类错误页齐备 + 全局 ErrorBoundary（1.3 错误兜底）
 */
import { Suspense, lazy, type ComponentType, type ReactNode } from 'react';
import { Navigate, createBrowserRouter } from 'react-router-dom';
import BasicLayout from '@/components/BasicLayout';
import { RequireAuth, RequirePermission } from '@/components/RouteGuard';
import { PageLoading } from '@/components/PageLoading';
// 403 页面被 RouteGuard 静态引用，这里直接静态导入避免重复分包（vite 动态导入告警）
import NoPermission from '@/pages/error/403';

const Login = lazy(() => import('@/pages/login'));
const Dashboard = lazy(() => import('@/pages/dashboard'));
const ProductList = lazy(() => import('@/pages/product/list'));
const ProductDetail = lazy(() => import('@/pages/product/detail'));
const OrderList = lazy(() => import('@/pages/order/list'));
const OrderDetail = lazy(() => import('@/pages/order/detail'));
const OrderRefund = lazy(() => import('@/pages/order/refund'));
const UserList = lazy(() => import('@/pages/user/list'));
const UserRoles = lazy(() => import('@/pages/user/roles'));
const SystemDict = lazy(() => import('@/pages/system/dict'));
const SystemLog = lazy(() => import('@/pages/system/log'));
const NotFound = lazy(() => import('@/pages/error/404'));
const ServerError = lazy(() => import('@/pages/error/500'));

/** 懒加载页面包装：Suspense 统一 loading 兜底 */
function lazyPage(Comp: ComponentType): ReactNode {
  return (
    <Suspense fallback={<PageLoading />}>
      <Comp />
    </Suspense>
  );
}

/** 受保护页面 = 登录守卫 + 权限守卫 + 懒加载 */
function protectedPage(Comp: ComponentType, permission: string): ReactNode {
  return (
    <RequireAuth>
      <RequirePermission permission={permission}>{lazyPage(Comp)}</RequirePermission>
    </RequireAuth>
  );
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: lazyPage(Login),
    errorElement: lazyPage(ServerError),
    handle: { title: '登录' },
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <BasicLayout />
      </RequireAuth>
    ),
    errorElement: lazyPage(ServerError),
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: lazyPage(Dashboard), handle: { title: '工作台' } },
      // 商品管理（权限码见 admin-backend 2.2.3 product:*）
      { path: 'product/list', element: protectedPage(ProductList, 'product:list'), handle: { title: '商品列表' } },
      { path: 'product/:id', element: protectedPage(ProductDetail, 'product:list'), handle: { title: '商品详情' } },
      // 订单管理（order:* / refund:*）
      { path: 'order/list', element: protectedPage(OrderList, 'order:list'), handle: { title: '订单列表' } },
      { path: 'order/refund', element: protectedPage(OrderRefund, 'refund:list'), handle: { title: '退款售后' } },
      { path: 'order/:id', element: protectedPage(OrderDetail, 'order:detail'), handle: { title: '订单详情' } },
      // 用户与角色（system:*）
      { path: 'user/list', element: protectedPage(UserList, 'system:user'), handle: { title: '用户管理' } },
      { path: 'user/roles', element: protectedPage(UserRoles, 'system:role'), handle: { title: '角色权限' } },
      // 系统
      { path: 'system/dict', element: protectedPage(SystemDict, 'system:dict'), handle: { title: '数据字典' } },
      { path: 'system/log', element: protectedPage(SystemLog, 'system:log'), handle: { title: '操作日志' } },
      { path: '403', element: <NoPermission />, handle: { title: '无权限' } },
      { path: '*', element: lazyPage(NotFound), handle: { title: '页面不存在' } },
    ],
  },
  { path: '/500', element: lazyPage(ServerError), handle: { title: '服务异常' } },
  { path: '*', element: lazyPage(NotFound), handle: { title: '页面不存在' } },
]);
