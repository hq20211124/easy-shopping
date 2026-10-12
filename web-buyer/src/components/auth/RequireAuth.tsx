/** 登录守卫：未登录跳登录页并记录来源，登录后回跳。 */

import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';

export function RequireAuth({ children }: { children: ReactNode }) {
  const accessToken = useAuthStore((s) => s.accessToken);
  const location = useLocation();
  if (!accessToken) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return children;
}
