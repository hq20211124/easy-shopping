import { http } from './client'
import type { TokenResponse } from './types'

export const authApi = {
  /** POST /api/v1/auth/login（P0：手机号+密码；core-user README） */
  login: (username: string, password: string) =>
    http.post<TokenResponse>('/api/v1/auth/login', { username, password }),
  /** POST /api/v1/auth/register（手机号+验证码，P0 固定 123456） */
  register: (mobile: string, code: string, password: string) =>
    http.post<TokenResponse>('/api/v1/auth/register', { mobile, code, password }),
  /** POST /api/v1/auth/logout（refreshToken 丢失时也允许静默登出） */
  logout: (refreshToken?: string) => http.post<{ success: boolean }>('/api/v1/auth/logout', { refreshToken }),
}
