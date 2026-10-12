/**
 * 按钮级权限 Hooks（plans/admin-frontend.md 2.4）
 * 权限码以 plans/admin-backend.md 2.2.3 权限点注册表为准（product:* / order:* / refund:* / system:*）
 */
import { useAuthStore } from '@/stores/auth';
import { hasPermission as checkPermission, type Permission } from '@/utils/permission';

export function usePermission() {
  const permissions = useAuthStore((s) => s.profile?.permissions ?? []);
  const hasPermission = (required: Permission | Permission[] | undefined): boolean =>
    checkPermission(required, permissions);
  return { permissions, hasPermission };
}
