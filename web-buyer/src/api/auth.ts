import { api } from './client';
import type { TokenResponse } from '@/types/domain';

export interface LoginPayload {
  username: string;
  password: string;
}

export interface RegisterPayload {
  mobile: string;
  code: string;
  password: string;
}

export const authApi = {
  /** POST /api/v1/auth/login（用户名+密码；手机号即用户名） */
  login: (p: LoginPayload) => api.post<TokenResponse>('/v1/auth/login', p),
  /** POST /api/v1/auth/register（手机号+验证码；P0 测试码固定 123456） */
  register: (p: RegisterPayload) => api.post<TokenResponse>('/v1/auth/register', p),
  refresh: (refreshToken: string) => api.post<TokenResponse>('/v1/auth/refresh', { refreshToken }),
  logout: (refreshToken: string | null) => api.post<{ success: boolean }>('/v1/auth/logout', { refreshToken }),
};
