/**
 * 权限按钮（plans/admin-frontend.md 2.4）
 * 无权限直接不渲染（隐藏而非禁用，避免"点了没反应"）
 */
import type { ReactNode } from 'react';
import { Button, type ButtonProps } from 'antd';
import { usePermission } from '@/hooks/usePermission';
import type { Permission } from '@/utils/permission';

interface AuthButtonProps extends Omit<ButtonProps, 'children'> {
  /** 权限码，数组语义 = 全部满足 */
  permission: Permission | Permission[];
  children?: ReactNode;
}

export function AuthButton({ permission, children, ...rest }: AuthButtonProps) {
  const { hasPermission } = usePermission();
  if (!hasPermission(permission)) return null;
  return <Button {...rest}>{children}</Button>;
}
