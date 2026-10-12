/** 认证态：access token 仅短时凭据（P0 persist 到 localStorage，Electron 封装时迁 safeStorage）。 */

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { TokenResponse } from '@/types/domain';

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  userId: number | null;
  username: string | null;
  nickname: string | null;
  setSession: (token: TokenResponse) => void;
  clear: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      userId: null,
      username: null,
      nickname: null,
      setSession: (token) =>
        set({
          accessToken: token.accessToken,
          refreshToken: token.refreshToken,
          userId: token.userId,
          username: token.username,
          nickname: token.nickname ?? token.username,
        }),
      clear: () => set({ accessToken: null, refreshToken: null, userId: null, username: null, nickname: null }),
    }),
    { name: 'web-buyer-auth' },
  ),
);
