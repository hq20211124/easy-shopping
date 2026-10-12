/** 请求封装：统一信封解包 / Bearer 注入 / 写接口幂等键 / 401 跳登录 / mock 分流。 */

import { BIZ_CODE, type ApiResponse } from '@/api/types'
import { uuid } from '@/utils/uuid'
import { mockRequest } from './mock'

const BASE_URL = import.meta.env.VITE_API_BASE || ''
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

const TOKEN_KEY = 'ec_token'
const REFRESH_TOKEN_KEY = 'ec_refresh_token'

export function getToken(): string {
  return (uni.getStorageSync(TOKEN_KEY) as string) || ''
}

export function setTokens(accessToken: string, refreshToken: string) {
  uni.setStorageSync(TOKEN_KEY, accessToken)
  uni.setStorageSync(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens() {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(REFRESH_TOKEN_KEY)
}

export class BizError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'BizError'
  }
}

export interface RequestOptions {
  /** GET 时作为 query 参数（自动过滤空值）；其余方法作为 JSON body */
  data?: Record<string, unknown>
  /** 静默：不弹错误 toast（调用方自行处理） */
  silent?: boolean
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

function redirectLogin() {
  clearTokens()
  const pages = getCurrentPages()
  const current = (pages[pages.length - 1] as { route?: string } | undefined)?.route || ''
  if (!current.startsWith('pages/login/')) {
    uni.reLaunch({ url: '/pages/login/index' })
  }
}

/** 业务错误统一处理：1002/1003 清会话跳登录；其余 toast 响应 msg（3002 库存不足/券失效等）。 */
function fail(envelope: ApiResponse<unknown>, silent: boolean): never {
  if (envelope.code === BIZ_CODE.UNAUTHORIZED || envelope.code === BIZ_CODE.TOKEN_EXPIRED) {
    redirectLogin()
  }
  if (!silent) {
    uni.showToast({ title: envelope.msg || '请求失败，请稍后重试', icon: 'none' })
  }
  throw new BizError(envelope.code, envelope.msg)
}

async function realRequest<T>(method: Method, url: string, options: RequestOptions): Promise<ApiResponse<T>> {
  return new Promise((resolve) => {
    uni.request({
      url: BASE_URL + url,
      method,
      // GET 的 data 由 uni 自动拼接为 query；非 GET 序列化为 JSON body
      data:
        method === 'GET'
          ? Object.fromEntries(
              Object.entries(options.data || {}).filter(([, v]) => v !== undefined && v !== null && v !== ''),
            )
          : options.data || {},
      timeout: 15000,
      header: {
        'Content-Type': 'application/json',
        ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
        // 写接口必须带 Idempotency-Key（core-* 幂等拦截器强制；重试复用同一 key 防重复提交）
        ...(method === 'GET' ? {} : { 'Idempotency-Key': uuid() }),
      },
      success: (res) => {
        const body = res.data as ApiResponse<T> | undefined
        if (body && typeof body === 'object' && 'code' in body) {
          resolve(body)
        } else {
          resolve({ code: BIZ_CODE.UNKNOWN, msg: `服务响应异常(${res.statusCode})`, data: null as T })
        }
      },
      fail: () => {
        resolve({ code: BIZ_CODE.UNKNOWN, msg: '网络异常，请检查网络后重试', data: null as T })
      },
    })
  })
}

async function request<T>(method: Method, url: string, options: RequestOptions = {}): Promise<T> {
  const envelope = USE_MOCK
    ? await mockRequest<T>(method, url, options.data || {})
    : await realRequest<T>(method, url, options)
  if (envelope.code === BIZ_CODE.OK) return envelope.data
  return fail(envelope, options.silent === true)
}

export const http = {
  get: <T>(url: string, data?: Record<string, unknown>, silent?: boolean) =>
    request<T>('GET', url, { data, silent }),
  post: <T>(url: string, data?: Record<string, unknown>, silent?: boolean) =>
    request<T>('POST', url, { data, silent }),
  put: <T>(url: string, data?: Record<string, unknown>, silent?: boolean) =>
    request<T>('PUT', url, { data, silent }),
  del: <T>(url: string, data?: Record<string, unknown>, silent?: boolean) =>
    request<T>('DELETE', url, { data, silent }),
}

export const isMockMode = () => USE_MOCK
