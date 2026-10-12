/**
 * 用户与角色权限域接口（RBAC）
 * 契约出处：plans/admin-backend.md 2.5.2「系统管理接口」、2.2「RBAC 权限模型」、2.2.3「权限点注册表」
 */
import { del, get, post, put } from './request';
import type { PageQuery, PageResult } from '@/types/api';

export interface AdminUser {
  id: number;
  username: string;
  nickname: string;
  deptName: string;
  roles: string[];
  /** 1 启用 0 禁用 */
  status: number;
  lastLoginAt: string;
  createdAt: string;
}

export interface AdminUserQuery extends PageQuery {
  keyword?: string;
}

/** 管理员列表：GET /api/admin/system/user（2.5.2） */
export function listAdminUsers(params: AdminUserQuery): Promise<PageResult<AdminUser>> {
  return get('/admin/system/user', params);
}

export interface AdminUserParams {
  username: string;
  nickname: string;
  deptId?: number;
  roles?: string[];
  password?: string;
}

/** 新增管理员：POST /api/admin/system/user（2.5.2；重置密码需二次验证） */
export function createAdminUser(data: AdminUserParams): Promise<{ success: boolean }> {
  return post('/admin/system/user', data);
}

/** 编辑管理员：PUT /api/admin/system/user/{id}（2.5.2） */
export function updateAdminUser(id: number, data: Partial<AdminUserParams>): Promise<{ success: boolean }> {
  return put(`/admin/system/user/${id}`, data);
}

/** 删除管理员（软删）：DELETE /api/admin/system/user/{id}（2.5.2） */
export function deleteAdminUser(id: number): Promise<{ success: boolean }> {
  return del(`/admin/system/user/${id}`);
}

export interface Role {
  id: number;
  code: string;
  name: string;
  /** 数据范围 1全部/2本部门/3本部门及以下/4仅本人/5自定义/6本店铺（2.3.1） */
  dataScope: number;
  status: number;
  userCount: number;
  remark: string;
  /** 内置角色不可编辑/删除（2.2.2 防提权约束） */
  builtin?: boolean;
}

/** 角色列表：GET /api/admin/system/role（2.5.2） */
export function listRoles(params: PageQuery): Promise<PageResult<Role>> {
  return get('/admin/system/role', params);
}

export interface PermissionTreeNode {
  key: string;
  title: string;
  /** menu=菜单节点 permission=权限点（2.2.3 注册表） */
  type: 'menu' | 'permission';
  children?: PermissionTreeNode[];
}

/** 权限点树（菜单+按钮+接口）：GET /api/admin/system/permission/tree（2.5.2） */
export function getPermissionTree(): Promise<PermissionTreeNode[]> {
  return get('/admin/system/permission/tree');
}

/** 查询角色已授权限：GET /api/admin/system/role/{id}/permissions */
export function getRolePermissions(id: number): Promise<{ permissions: string[] }> {
  return get(`/admin/system/role/${id}/permissions`);
}

/** 角色授权：PUT /api/admin/system/role/{id}/permissions（2.5.2；写入后刷新相关用户 permVer） */
export function updateRolePermissions(id: number, permissions: string[]): Promise<{ success: boolean }> {
  return put(`/admin/system/role/${id}/permissions`, { permissions });
}
