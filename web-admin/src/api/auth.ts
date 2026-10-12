/**
 * 认证域接口
 * 契约出处：plans/admin-backend.md 2.5.1「认证接口」+ 2.1.2「Token 规格」
 * 已对齐 server 实际实现（server/admin-bff AdminAuthController / AuthDtos / CaptchaService / RbacService）
 */
import { get, post } from './request';

export interface LoginParams {
  username: string;
  password: string;
  /** 连续失败 3 次后后端强制校验图形验证码（2.5.1 登录安全策略；提供 captchaId 即强校验，CaptchaService） */
  captchaId?: string;
  captchaCode?: string;
}

export interface LoginResult {
  /** Access Token（2h，随 body 返回）；Refresh Token 由后端写入 httpOnly Cookie「ADMIN_RT」（path=/api/admin） */
  accessToken: string;
  /** access token 有效期（秒，后端 app.jwt.access-ttl-seconds = 7200） */
  expiresIn: number;
  tokenType: string;
}

/** 登录：POST /api/admin/auth/login（admin-backend 2.5.1；响应体 {accessToken, expiresIn, tokenType}） */
export function login(params: LoginParams): Promise<LoginResult> {
  return post<LoginResult>('/admin/auth/login', params);
}

/** 登出：POST /api/admin/auth/logout（黑名单当前 Token + 清 refresh Cookie，返回 {success:true}） */
export function logout(): Promise<{ success: boolean }> {
  return post('/admin/auth/logout');
}

/** 当前用户信息：GET /api/admin/auth/info（角色、权限码、dataScope、permVer，2.5.1） */
export interface AuthProfile {
  userId: number;
  username: string;
  nickname: string;
  roles: string[];
  /** 权限码列表，命名 `模块:操作`，以后端 2.2.3 权限点注册表为准（DataInitializer 25 个码） */
  permissions: string[];
  /** 数据范围 1~6（admin-backend 2.3.1；前端只控入口，隔离由后端 SQL 拦截保证） */
  dataScope: number;
  permVer: number;
  /** 部门（未配置部门的管理员后端下发 null） */
  deptId: number | null;
  deptName: string | null;
}

export function getAuthInfo(): Promise<AuthProfile> {
  return get<AuthProfile>('/admin/auth/info');
}

/**
 * 菜单节点：GET /api/admin/auth/menus（RbacService.MenuNode）
 * 字段语义与前端 utils/permission.ts 的 PermissionMenuItem 一致，后端下发的是已按权限过滤的菜单树
 * （type=permission 的权限点不进菜单树；dashboard 无 requiredPerm，恒可见）
 */
export interface AdminMenuNode {
  key: string;
  label: string;
  /** 父级菜单也有 path（如 /system），前端只取叶子节点 path 做路由跳转 */
  path?: string | null;
  permission?: string | null;
  children?: AdminMenuNode[] | null;
}

/** 按权限过滤的菜单树（动态菜单数据源；接口异常时前端回退静态 MENUS 配置） */
export function getMenus(): Promise<AdminMenuNode[]> {
  return get<AdminMenuNode[]>('/admin/auth/menus');
}

/** 图形验证码：GET /api/admin/auth/captcha（SVG data URL，2.5.1；网关白名单免 Token） */
export function getCaptcha(): Promise<{ captchaId: string; image: string }> {
  return get('/admin/auth/captcha');
}
