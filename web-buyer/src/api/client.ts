/** axios 封装：统一信封解包 / Bearer 注入 / 写接口幂等键 / 业务错误 Toast / 1002 跳登录。 */

import axios, { type AxiosRequestConfig } from 'axios';
import { BIZ_CODE, type ApiResponse } from '@/types/api';
import { useAuthStore } from '@/store/auth';
import { toast } from '@/store/toast';

export class BizError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.code = code;
    this.name = 'BizError';
  }
}

const instance = axios.create({ baseURL: '/api', timeout: 15000 });

instance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  // 写接口必须带 Idempotency-Key（core-* 幂等拦截器强制；重试复用同一 key 防重复提交）
  if (config.method && config.method !== 'get') {
    config.headers['Idempotency-Key'] = crypto.randomUUID();
  }
  return config;
});

/** 业务错误统一处理：Toast 响应 msg（3002 库存不足/券失效等），1002 清会话跳登录。 */
function fail(envelope: ApiResponse<unknown>): never {
  if (envelope.code === BIZ_CODE.UNAUTHORIZED) {
    useAuthStore.getState().clear();
    if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
      window.location.assign('/login');
    }
  }
  toast.error(envelope.msg || '请求失败，请稍后重试');
  throw new BizError(envelope.code, envelope.msg);
}

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const resp = await instance.request<ApiResponse<T>>({ method: 'get', ...config });
    const envelope = resp.data;
    if (envelope && typeof envelope === 'object' && 'code' in envelope) {
      if (envelope.code === BIZ_CODE.OK) return envelope.data;
      return fail(envelope);
    }
    return envelope as unknown as T;
  } catch (e) {
    if (e instanceof BizError) throw e;
    if (axios.isAxiosError(e)) {
      const data = e.response?.data as ApiResponse<unknown> | undefined;
      // 后端 401/403/404/409 带信封返回（DEV.md：全局处理器映射 HTTP 状态）
      if (data && typeof data === 'object' && 'code' in data) return fail(data);
    }
    toast.error('网络异常，请检查网络后重试');
    throw new BizError(BIZ_CODE.UNKNOWN, '网络异常');
  }
}

export const api = {
  get: <T>(url: string, params?: Record<string, string | number | undefined | null>) =>
    request<T>({
      url,
      params: params
        ? Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== ''))
        : undefined,
    }),
  post: <T>(url: string, data?: unknown) => request<T>({ url, method: 'post', data }),
  put: <T>(url: string, data?: unknown) => request<T>({ url, method: 'put', data }),
  del: <T>(url: string, data?: unknown) => request<T>({ url, method: 'delete', data }),
};
