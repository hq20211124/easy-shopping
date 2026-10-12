/**
 * 权限纯函数（对齐 plans/admin-frontend.md 1.4 路由守卫与 2.4 按钮级权限）
 *
 * 权限码命名规范 `模块:操作`，编码以后端权限点注册表为准
 * （plans/admin-backend.md 2.2.3：product:* / order:* / refund:* / system:* …，前端禁止另造 goods:*）
 */

export type Permission = string;

/** 单个权限码校验；数组语义 = 全部满足 */
export function hasPermission(
  required: Permission | Permission[] | undefined,
  owned: Permission[],
): boolean {
  if (!required) return true;
  if (Array.isArray(required)) return required.every((p) => owned.includes(p));
  return owned.includes(required);
}

export interface PermissionMenuItem {
  key: string;
  label: string;
  path?: string;
  permission?: Permission | Permission[];
  children?: PermissionMenuItem[];
}

/** 菜单按权限过滤：子级全被过滤且自身无权限标识时父级一并隐藏（纯函数，不改原数组） */
export function filterMenuByPermission(
  menus: PermissionMenuItem[],
  owned: Permission[],
): PermissionMenuItem[] {
  return menus.flatMap((menu) => {
    if (!hasPermission(menu.permission, owned)) return [];
    if (!menu.children?.length) return [menu];
    const children = filterMenuByPermission(menu.children, owned);
    if (children.length === 0 && !menu.permission) return [];
    return [{ ...menu, children }];
  });
}
