/**
 * API 公共类型
 *
 * 契约出处：**server 实际实现** server/common/api/ApiResponse.java（`{code, msg, data, traceId}`）
 * + plans/admin-backend.md 2.5.2「接口通用规范」（plans 写作 message，实现为 msg，**以实现为准**）
 *  - 分页参数统一 pageNum / pageSize（前端边界 pageSize ≤ 100，见 admin-frontend 1.6 / 16.4）
 *  - 分页返回 PageResult{total, pageNum, pageSize, list}（server/common/api/PageResult.java）
 */

/** 后端统一响应结构（server/common ApiResponse：字段名只有 msg，不存在 message） */
export interface ApiResponse<T = unknown> {
  code: number;
  msg?: string;
  data: T;
  traceId?: string;
}

/** 分页查询入参 */
export interface PageQuery {
  pageNum: number;
  pageSize: number;
}

/** 分页返回结构（list/total） */
export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

/** 业务错误（拦截器解包失败时抛出） */
export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
    public readonly traceId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** 分页请求配置扩展（自定义字段，非 axios 官方字段） */
declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 静默模式：不弹全局错误提示（如列表轮询场景） */
    silent?: boolean;
    /** 已经历 401 刷新重放，防止死循环 */
    _retried?: boolean;
  }
}
