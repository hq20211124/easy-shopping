/**
 * 路由守卫（plans/admin-frontend.md 1.4 权限路由 / 2.1 登录态校验）
 *  - RequireAuth：未登录跳转 /login 并保留 redirect 参数；已登录但未拉到用户信息时先引导拉取
 *  - RequirePermission：无权限直接展示 403 页（菜单同时按权限过滤，双保险；后端仍做二次校验）
 */
import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/auth';
import { usePermission } from '@/hooks/usePermission';
import { tokenStore } from '@/utils/token';
import { PageLoading } from '@/components/PageLoading';
import type { Permission } from '@/utils/permission';
import NoPermission from '@/pages/error/403';

export function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation();
  const profile = useAuthStore((s) => s.profile);
  const loading = useAuthStore((s) => s.loading);
  const fetchProfile = useAuthStore((s) => s.fetchProfile);
  const token = tokenStore.get();

  useEffect(() => {
    if (token && !profile && !loading) {
      fetchProfile().catch(() => useAuthStore.getState().reset());
    }
  }, [token, profile, loading, fetchProfile]);

  if (!token) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  if (!profile) return <PageLoading full />;
  return <>{children}</>;
}

export function RequirePermission({
  permission,
  children,
}: {
  permission: Permission | Permission[];
  children: ReactNode;
}) {
  const { hasPermission } = usePermission();
  if (!hasPermission(permission)) return <NoPermission />;
  return <>{children}</>;
}
