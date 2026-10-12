/**
 * 登录态全局轻状态（zustand）
 *
 * 口径（plans/admin-frontend.md 2.1）：
 *  - Access Token 只存内存（utils/token.ts），store 内只保留用户信息与权限码
 *  - 退出登录清空 Zustand、React Query 缓存与内存 Token（16.4 敏感数据不落地）
 */
import { create } from 'zustand';
import { getAuthInfo, login as loginApi, logout as logoutApi, type AuthProfile, type LoginParams } from '@/api/auth';
import { tokenStore } from '@/utils/token';

interface AuthState {
  profile: AuthProfile | null;
  loading: boolean;
  /** 登录成功后写入 token 并拉取用户信息 */
  login: (params: LoginParams) => Promise<AuthProfile>;
  /** 拉取/静默刷新用户信息与权限（每 5 分钟静默刷新的调用入口，见前端 2.4） */
  fetchProfile: () => Promise<AuthProfile | null>;
  logout: () => Promise<void>;
  /** 清空本地状态（401 刷新失败、被踢时调用） */
  reset: () => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  profile: null,
  loading: false,

  async login(params) {
    const { accessToken } = await loginApi(params);
    tokenStore.set(accessToken);
    return get().fetchProfile() as Promise<AuthProfile>;
  },

  async fetchProfile() {
    if (get().loading) return get().profile;
    set({ loading: true });
    try {
      const profile = await getAuthInfo();
      set({ profile, loading: false });
      return profile;
    } catch (err) {
      set({ loading: false });
      throw err;
    }
  },

  async logout() {
    try {
      await logoutApi();
    } catch {
      /* 登出接口失败也要清本地状态 */
    } finally {
      get().reset();
    }
  },

  reset() {
    tokenStore.clear();
    set({ profile: null, loading: false });
  },
}));
