/**
 * 用户与角色权限域接口（RBAC）
 * 契约出处：plans/admin-backend.md 2.5.2「系统管理接口」、2.2「RBAC 权限模型」、2.2.3「权限点注册表」
 * 已对齐 server 实际实现（server/admin-bff SystemUserController / SystemRoleController / SystemDtos / RbacService）：
 *  - 用户：list/create/update/delete + PUT /{id}/roles（**角色与用户资料是两个接口**，update 不接受 roles）
 *  - 角色：list + GET/PUT /{id}/permissions（{permissions:[...] }）+ 权限树 GET /system/permission/tree
 *  - 分页 PageResult{total, pageNum, pageSize, list}（common PageResult）；写接口带 @Idempotent（缺幂等键 1001）
 */
import { del, get, post, put } from './request';
import type { PageQuery, PageResult } from '@/types/api';

export interface AdminUser {
  id: number;
  username: string;
  nickname: string;
  /** 部门名称（未配置部门时后端下发 null） */
  deptName: string | null;
  roles: string[];
  /** 1 启用 0 禁用 */
  status: number;
  /** 最近登录时间（Instant ISO-8601；从未登录为 null） */
  lastLoginAt: string | null;
  createdAt: string;
}

export interface AdminUserQuery extends PageQuery {
  keyword?: string;
}

/** 管理员列表：GET /api/admin/system/user（@RequirePerm system:user） */
export function listAdminUsers(params: AdminUserQuery): Promise<PageResult<AdminUser>> {
  return get('/admin/system/user', params);
}

export interface AdminUserParams {
  username: string;
  nickname: string;
  deptId?: number | null;
  /** 角色编码列表；**仅创建接口接受 roles**，编辑请用 setUserRoles */
  roles?: string[];
  /** 口径 6~32 位（AdminUserCreateReq/@Size(min=6)），前端表单按 ≥8 位约束 */
  password?: string;
}

/**
 * 新增管理员：POST /api/admin/system/user（AdminUserCreateReq：username/password 必填，roles/deptId 可选）
 * 响应 { userId, success } → 统一收敛为 { success }
 */
export function createAdminUser(data: AdminUserParams): Promise<{ success: boolean }> {
  return post('/admin/system/user', data).then(() => ({ success: true }));
}

export interface AdminUserUpdateParams {
  nickname?: string;
  /** 新密码（6~32，二次验证要求见 admin-backend 2.5.2） */
  password?: string;
  /** 1 启用 0 禁用 */
  status?: number;
}

/**
 * 编辑管理员：PUT /api/admin/system/user/{id}
 * 已对齐后端 AdminUserUpdateReq：只接受 nickname/mobile/password/status —— **不含 username 与 roles**，
 * 角色变更必须走 setUserRoles（后端 SystemUserController#setRoles）
 */
export function updateAdminUser(id: number, data: AdminUserUpdateParams): Promise<{ success: boolean }> {
  return put(`/admin/system/user/${id}`, data).then(() => ({ success: true }));
}

/** 分配角色：PUT /api/admin/system/user/{id}/roles（UserRolesReq{roles}；@RequirePerm system:role） */
export function setUserRoles(id: number, roles: string[]): Promise<{ success: boolean }> {
  return put(`/admin/system/user/${id}/roles`, { roles }).then(() => ({ success: true }));
}

/** 删除管理员（软删）：DELETE /api/admin/system/user/{id} */
export function deleteAdminUser(id: number): Promise<{ success: boolean }> {
  return del(`/admin/system/user/${id}`).then(() => ({ success: true }));
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
  /** 内置角色（super_admin）不可编辑/删除（2.2.2 防提权约束，后端 DataInitializer 种子） */
  builtin?: boolean;
}

/** 角色列表：GET /api/admin/system/role（RoleVO） */
export function listRoles(params: PageQuery): Promise<PageResult<Role>> {
  return get('/admin/system/role', params);
}

export interface PermissionTreeNode {
  key: string;
  title: string;
  /** menu=菜单节点 permission=权限点（后端权限注册表 DataInitializer） */
  type: 'menu' | 'permission';
  children?: PermissionTreeNode[];
}

/** 权限点树（菜单+按钮）：GET /api/admin/system/permission/tree（RbacService.permissionTree → PermTreeNodeVo） */
export function getPermissionTree(): Promise<PermissionTreeNode[]> {
  return get('/admin/system/permission/tree');
}

/** 查询角色已授权限：GET /api/admin/system/role/{id}/permissions → { permissions: string[] } */
export function getRolePermissions(id: number): Promise<{ permissions: string[] }> {
  return get(`/admin/system/role/${id}/permissions`);
}

/** 角色授权：PUT /api/admin/system/role/{id}/permissions（body { permissions }；后端刷新相关用户 permVer） */
export function updateRolePermissions(id: number, permissions: string[]): Promise<{ success: boolean }> {
  return put(`/admin/system/role/${id}/permissions`, { permissions }).then(() => ({ success: true }));
}
