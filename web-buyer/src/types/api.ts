/** 后端统一响应信封（DEV.md：{code,msg,data,traceId}）。 */
export interface ApiResponse<T> {
  code: number;
  msg: string;
  data: T;
  traceId: string;
}

/** 统一分页信封：{total, pageNum, pageSize, list}（common PageResult）。 */
export interface PageResult<T> {
  total: number;
  pageNum: number;
  pageSize: number;
  list: T[];
}

/** 业务错误码（对齐 plans/android-backend.md 16.2 白名单）。 */
export const BIZ_CODE = {
  OK: 0,
  PARAM_ERROR: 1001,
  UNAUTHORIZED: 1002,
  FORBIDDEN: 1004,
  CONFLICT: 1005,
  PRODUCT_NOT_FOUND: 3001,
  OUT_OF_STOCK: 3002,
  PRODUCT_OFF_SHELF: 3003,
  ORDER_STATUS_ERROR: 4002,
  PAY_ERROR: 5001,
  UNKNOWN: 9999,
} as const;
