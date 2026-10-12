/**
 * Access Token 存取
 *
 * 正式口径（plans/admin-frontend.md 2.1 / admin-backend.md 2.1.2）：
 *  - Access Token 只存内存变量，业务请求经 Authorization: Bearer 携带
 *  - Refresh Token 存 httpOnly Cookie，仅刷新接口可用
 *
 * 已对齐 server 实际实现（server/admin-bff AdminAuthController）：
 * login/refresh 响应体只给 accessToken/expiresIn/tokenType，refreshToken 由 Set-Cookie 下发
 * （Cookie 名 ADMIN_RT、path=/api/admin、httpOnly、SameSite=Strict，生产 HTTPS 必须 Secure）。
 * 因此 memory 是正式策略；session 分支仅保留给「后端未就绪/非浏览器客户端联调」的兜底。
 * 联调口径：.env.development 里 VITE_ACCESS_TOKEN_STORAGE=memory 即为正式行为（刷新页面需重新登录）。
 */
const STORAGE_KEY = 'admin_access_token';

const useSession = import.meta.env.VITE_ACCESS_TOKEN_STORAGE === 'session';

let memoryToken: string | null = null;

function readSession(): string | null {
  if (!useSession) return null;
  try {
    return sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export const tokenStore = {
  get(): string | null {
    if (memoryToken) return memoryToken;
    memoryToken = readSession();
    return memoryToken;
  },
  set(token: string): void {
    memoryToken = token;
    if (useSession) {
      try {
        sessionStorage.setItem(STORAGE_KEY, token);
      } catch {
        /* 隐私模式等场景降级为纯内存 */
      }
    }
  },
  clear(): void {
    memoryToken = null;
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      /* noop */
    }
  },
};
