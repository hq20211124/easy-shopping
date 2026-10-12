/**
 * 系统域接口（数据字典 / 操作日志 / 工作台）
 * 契约出处：
 *  - 字典、日志：plans/admin-backend.md 2.5.2
 *  - 工作台/待办：plans/admin-backend.md 17.4（admin-data 服务）
 * 已对齐 server 实际实现（server/admin-bff SystemDictController / SystemLogController / AdminDataController）：
 *  - 字典类型 GET /system/dict（keyword/pageNum/pageSize）；字典项 GET /system/dict/items?type=xxx
 *  - 日志 GET /system/operation-log：operator/module 关键字与时间范围**二选一**（关键字优先，P0 限制）
 *  - 工作台/待办为 P0 stub（gmv/orderCount 恒 0、trend/items 恒空），P1 由 admin-data 聚合
 */
import { get } from './request';
import type { PageQuery, PageResult } from '@/types/api';

/* ------------------------------------------------------------ 数据字典（2.5.2 / 前端 12.4） */

export interface DictType {
  id: number;
  /** 字典类型编码，如 order_status、pay_type */
  type: string;
  name: string;
  remark: string;
  status: number;
}

export interface DictItem {
  id: number;
  code: string;
  label: string;
  sort: number;
  status: number;
}

/** 字典类型列表：GET /api/admin/system/dict（订单状态/支付方式等枚举统一从此下发，禁止硬编码） */
export function listDictTypes(params: PageQuery & { keyword?: string }): Promise<PageResult<DictType>> {
  return get('/admin/system/dict', params);
}

/** 字典项列表：GET /api/admin/system/dict/items?type=xxx */
export function listDictItems(type: string): Promise<DictItem[]> {
  return get('/admin/system/dict/items', { type });
}

/* ------------------------------------------------------------ 操作日志（2.5.2 / 前端 12.2） */

export interface OperationLog {
  id: number;
  operator: string;
  module: string;
  action: string;
  content: string;
  ip: string;
  /** 1 成功 0 失败 */
  status: number;
  duration: number;
  createdAt: string;
  /** 操作前后值（敏感操作留痕，前端 5.2/5.5） */
  beforeData: Record<string, unknown> | null;
  afterData: Record<string, unknown> | null;
}

export interface OperationLogQuery extends PageQuery {
  operator?: string;
  module?: string;
  /** 起始时间，接受 "YYYY-MM-DD HH:mm:ss" 或 ISO 字符串，本层统一转 ISO 后端 @DateTimeFormat(ISO.DATE_TIME) */
  createdAtStart?: string;
  createdAtEnd?: string;
}

/**
 * "YYYY-MM-DD HH:mm:ss" → "YYYY-MM-DDTHH:mm:ss+08:00"（带本地时区偏移）。
 * 后端 SystemLogController 用 @DateTimeFormat(ISO.DATE_TIME) 解析 Instant，必须含时区，
 * 否则 400/1001；此处按浏览器本地时区补偏移，语义为「用户选择的本地墙钟时间」。
 */
function toIsoDateTime(value?: string): string | undefined {
  if (!value) return undefined;
  const iso = value.includes('T') ? value : value.replace(' ', 'T');
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso; // 交后端 1001 报错
  const off = -d.getTimezoneOffset(); // 分钟，UTC-本地 取反 → 本地-UTC
  const sign = off >= 0 ? '+' : '-';
  const abs = Math.abs(off);
  const hh = String(Math.floor(abs / 60)).padStart(2, '0');
  const mm = String(abs % 60).padStart(2, '0');
  return `${iso}${sign}${hh}:${mm}`;
}

/** 操作日志列表：GET /api/admin/system/operation-log（@RequirePerm system:log） */
export function listOperationLogs(params: OperationLogQuery): Promise<PageResult<OperationLog>> {
  return get('/admin/system/operation-log', {
    ...params,
    createdAtStart: toIsoDateTime(params.createdAtStart),
    createdAtEnd: toIsoDateTime(params.createdAtEnd),
  });
}

/* ------------------------------------------------------------ 工作台（17.4 admin-data） */

export interface WorkbenchData {
  /** 今日 GMV（分） */
  gmv: number;
  orderCount: number;
  newMember: number;
  pendingCount: number;
  trend: Array<{ date: string; gmv: number; orderCount: number }>;
}

/** 工作台概览：GET /api/admin/data/workbench（数据权限联动：无权限指标后端不返回，前端 3.1） */
export function getWorkbench(): Promise<WorkbenchData> {
  return get('/admin/data/workbench');
}

export interface TodoItem {
  key: string;
  title: string;
  count: number;
  path: string;
}

/** 待办聚合：GET /api/admin/data/todo */
export function getTodoList(): Promise<{ items: TodoItem[] }> {
  return get('/admin/data/todo');
}
