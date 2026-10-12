/**
 * 侧边菜单配置（静态兜底）
 *
 * 权限码命名 `模块:操作`，与后端权限点注册表一致
 * （server/admin-bff DataInitializer：product:* / order:* / refund:* / system:* / cost:read / report:view，
 *  前端禁止另造 goods:* 一套）。
 *
 * 已对齐 server 实际实现：GET /api/admin/auth/menus（RbacService.MenuNode）已就绪，
 * BasicLayout 优先使用后端下发的菜单树；接口异常/空返回时回退本文件的静态配置（两者菜单结构一致）。
 */
import type { PermissionMenuItem } from '@/utils/permission';

export const MENUS: PermissionMenuItem[] = [
  { key: 'dashboard', label: '工作台', path: '/dashboard' },
  {
    key: 'product',
    label: '商品运营',
    permission: 'product:list',
    children: [{ key: 'product:list', label: '商品列表', path: '/product/list', permission: 'product:list' }],
  },
  {
    key: 'order',
    label: '订单管理',
    permission: 'order:list',
    children: [
      { key: 'order:list', label: '订单列表', path: '/order/list', permission: 'order:list' },
      { key: 'order:refund', label: '退款/售后', path: '/order/refund', permission: 'refund:list' },
    ],
  },
  {
    key: 'system',
    label: '系统管理',
    permission: 'system:user',
    children: [
      { key: 'user:list', label: '用户列表', path: '/user/list', permission: 'system:user' },
      { key: 'user:roles', label: '角色权限', path: '/user/roles', permission: 'system:role' },
      { key: 'system:dict', label: '数据字典', path: '/system/dict', permission: 'system:dict' },
      { key: 'system:log', label: '操作日志', path: '/system/log', permission: 'system:log' },
    ],
  },
];
