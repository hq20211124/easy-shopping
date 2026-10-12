/**
 * axios 请求层（对齐 plans/admin-frontend.md 1.6 请求层规范 + **server 实际实现**）
 *
 *  - 统一响应结构 { code, msg, data, traceId }（server/common ApiResponse：字段名是 msg 而非 plans 的 message）
 *  - 401 → 并发刷新等待队列（只触发一次刷新，成功统一重放；失败清空状态跳登录并保留 redirect）
 *  - 403 → 立即重新拉取权限（由注册的回调触发），并提示无权限
 *  - 业务错误码兜底文案（server/common/BizErrorCode；后端 msg 优先，本表仅在 msg 缺失时兜底）
 *  - 写接口（POST/PUT/PATCH/DELETE）自动补 Idempotency-Key 请求头：
 *    server/common/idempotent/IdempotentInterceptor 校验 —— 缺失 → 1001 参数错误，重复 → 1005 幂等冲突
 *
 * 已对齐 server 实际实现（README / server/common）
 */
import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { message } from 'antd';
import { tokenStore } from '@/utils/token';
import { ApiError, type ApiResponse } from '@/types/api';

const baseURL = import.meta.env.VITE_API_BASE || '/api';

/** 业务错误码兜底文案（server/common/api/BizErrorCode；码表只增不改，改语义需全端同步） */
const ERROR_CODE_TEXT: Record<number, string> = {
  1001: '参数错误',
  1002: '未登录',
  1003: '登录状态已失效，请重新登录',
  1004: '无权限',
  1005: '请勿重复提交',
  404: '资源不存在',
  2001: '用户不存在',
  2002: '密码错误',
  2003: '验证码错误',
  2004: '验证码已过期',
  2005: '短信发送过于频繁',
  3001: '商品不存在',
  3002: '库存不足',
  3003: '商品已下架/失效',
  3004: '购物车项不存在',
  4001: '订单不存在',
  4002: '订单状态错误',
  5001: '支付失败',
  5002: '余额不足',
  9999: '系统错误',
};

/** 幂等键覆盖的写方法（server/common IdempotentInterceptor 只拦截写请求） */
const IDEMPOTENT_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
/** 认证域不需要幂等键（刷新/登录/登出本身可重放，且 refresh 走单飞队列） */
const IDEMPOTENT_SKIP = /^\/admin\/auth\//;

/** 生成幂等键：优先 Web Crypto UUID，降级为时间戳 + 随机串（兼容非安全上下文） */
function newIdempotencyKey(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  } catch {
    /* 降级 */
  }
  return `k_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
}

export const http = axios.create({
  baseURL,
  timeout: 15000,
  // Refresh Token 由 httpOnly Cookie（Secure; SameSite=Strict）承载，跨域联调时需要
  withCredentials: true,
});

/* ------------------------------------------------------------ 401/403 回调（避免与 stores 循环依赖） */

let unauthorizedHandler: (() => void) | null = null;
let forbiddenHandler: (() => void) | null = null;

/** 由 App 注册：401 刷新失败后清空登录态并跳转登录页（保留 redirect） */
export function registerAuthHandlers(onUnauthorized: () => void, onForbidden: () => void): () => void {
  unauthorizedHandler = onUnauthorized;
  forbiddenHandler = onForbidden;
  return () => {
    unauthorizedHandler = null;
    forbiddenHandler = null;
  };
}

/* ------------------------------------------------------------ 401 并发刷新等待队列 */

let refreshing: Promise<string> | null = null;

/**
 * 单飞刷新：多个 401 同时到达只触发一次刷新，其余排队等待同一结果（admin-backend 2.1.2 约定）
 * 已对齐 server 实际实现（AdminAuthController.refresh）：
 * refreshToken 优先读 httpOnly Cookie「ADMIN_RT」（path=/api/admin, SameSite=Strict），
 * body 仅作联调兜底；响应体仍是 { accessToken, expiresIn, tokenType }。
 */
function refreshAccessToken(): Promise<string> {
  if (!refreshing) {
    refreshing = axios
      .post(`${baseURL}/admin/auth/refresh`, {}, { timeout: 10000, withCredentials: true })
      .then((res) => {
        const body = res.data as ApiResponse<{ accessToken: string }>;
        const token = body?.data?.accessToken;
        if (!token) throw new ApiError(body?.code ?? 401, body?.message ?? body?.msg ?? '刷新登录失败');
        tokenStore.set(token);
        return token;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

/** 401 统一处理：尝试刷新并重放原请求；刷新失败跳登录 */
async function handleUnauthorized(config: InternalAxiosRequestConfig): Promise<unknown> {
  const token = await refreshAccessToken();
  config.headers.Authorization = `Bearer ${token}`;
  return http(config);
}

/* ------------------------------------------------------------ 拦截器 */

http.interceptors.request.use((config) => {
  const token = tokenStore.get();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  // 写接口自动补幂等键（server/common IdempotentInterceptor：缺失 1001 / 重复 1005「请勿重复提交」）
  const method = (config.method ?? 'get').toUpperCase();
  const url = config.url ?? '';
  if (IDEMPOTENT_METHODS.has(method) && !IDEMPOTENT_SKIP.test(url) && !config.headers['Idempotency-Key']) {
    config.headers['Idempotency-Key'] = newIdempotencyKey();
  }
  return config;
});

/** 错误文案：后端 msg 优先（server ApiResponse.msg），缺失时按 BizErrorCode 码表兜底 */
function extractMessage(body: unknown, fallback: string, code?: number): string {
  if (body && typeof body === 'object' && ('msg' in body || 'message' in body)) {
    const envelope = body as ApiResponse;
    const msg = envelope.msg ?? envelope.message;
    if (msg) return msg;
  }
  if (code !== undefined && ERROR_CODE_TEXT[code]) return ERROR_CODE_TEXT[code];
  return fallback;
}

http.interceptors.response.use(
  (res) => {
    const body = res.data as ApiResponse | undefined;
    // 非统一结构（如二进制下载）直接放行
    if (!body || typeof body !== 'object' || !('code' in body)) return res.data;
    if (body.code === 0) return body.data;
    // 业务码 401（mock 方言）走刷新队列；server 实际实现把 1002/1003 映射为 HTTP 401，走下方 error 分支
    if (body.code === 401) {
      return handleUnauthorized(res.config).catch((err) => {
        unauthorizedHandler?.();
        throw err;
      });
    }
    if (body.code === 403) {
      forbiddenHandler?.();
    }
    const err = new ApiError(body.code, extractMessage(body, '请求失败', body.code), body.traceId);
    if (!res.config.silent) message.error(err.message);
    return Promise.reject(err);
  },
  async (error: unknown) => {
    if (!axios.isAxiosError(error)) {
      message.error('网络异常，请稍后重试');
      return Promise.reject(error);
    }
    const config = error.config as InternalAxiosRequestConfig | undefined;
    const status = error.response?.status;
    const body = error.response?.data as ApiResponse | undefined;

    if (status === 401 && config && !config._retried) {
      config._retried = true;
      try {
        return await handleUnauthorized(config);
      } catch (refreshErr) {
        unauthorizedHandler?.();
        return Promise.reject(refreshErr);
      }
    }
    if (status === 403) forbiddenHandler?.();
    const msg = extractMessage(
      body,
      error.message === 'Network Error' ? '网络异常，请稍后重试' : '请求失败',
      body?.code ?? status,
    );
    if (!config?.silent) message.error(msg);
    return Promise.reject(new ApiError(body?.code ?? status ?? -1, msg, body?.traceId));
  },
);

/* ------------------------------------------------------------ 方法封装 */

export function get<T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> {
  // 拦截器已解包 body.data，运行时即为 T
  return http.get(url, { params, ...config }) as unknown as Promise<T>;
}

export function post<T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T> {
  return http.post(url, data, config) as unknown as Promise<T>;
}

export function put<T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T> {
  return http.put(url, data, config) as unknown as Promise<T>;
}

export function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.delete(url, config) as unknown as Promise<T>;
}
