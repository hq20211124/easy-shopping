/** 权限纯函数单测（admin-frontend 1.4 filterRoutes/filterMenu 语义、2.4 按钮级权限） */
import { describe, expect, it } from 'vitest';
import { filterMenuByPermission, hasPermission, type PermissionMenuItem } from './permission';

describe('hasPermission', () => {
  const owned = ['product:list', 'order:list', 'order:detail'];

  it('无权限要求时一律放行', () => {
    expect(hasPermission(undefined, owned)).toBe(true);
  });

  it('单权限码：命中才放行', () => {
    expect(hasPermission('product:list', owned)).toBe(true);
    expect(hasPermission('product:create', owned)).toBe(false);
  });

  it('数组权限码：语义为全部满足', () => {
    expect(hasPermission(['product:list', 'order:list'], owned)).toBe(true);
    expect(hasPermission(['product:list', 'product:create'], owned)).toBe(false);
  });
});

describe('filterMenuByPermission', () => {
  const menus: PermissionMenuItem[] = [
    { key: 'dashboard', label: '工作台', path: '/dashboard' },
    {
      key: 'product',
      label: '商品管理',
      permission: 'product:list',
      children: [
        { key: 'product:list', label: '商品列表', path: '/product/list', permission: 'product:list' },
        { key: 'product:create', label: '发布商品', path: '/product/create', permission: 'product:create' },
      ],
    },
    {
      key: 'finance',
      label: '财务管理',
      children: [{ key: 'finance:view', label: '对账', path: '/finance', permission: 'finance:read' }],
    },
  ];

  it('无权限模块整体隐藏（父级无自身权限标识且子级全被过滤时一并隐藏）', () => {
    const result = filterMenuByPermission(menus, ['product:list']);
    expect(result.map((m) => m.key)).toEqual(['dashboard', 'product']);
    expect(result[1].children?.map((c) => c.key)).toEqual(['product:list']);
  });

  it('全量权限可见全部菜单', () => {
    const result = filterMenuByPermission(menus, ['product:list', 'product:create', 'finance:read']);
    expect(result).toHaveLength(3);
  });

  it('纯函数：不修改原始配置', () => {
    const snapshot = JSON.stringify(menus);
    filterMenuByPermission(menus, []);
    expect(JSON.stringify(menus)).toBe(snapshot);
  });
});
