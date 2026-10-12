/**
 * 查询边界工具（plans/admin-frontend.md 1.6 / 16.4）
 *  - pageSize 上限 100，超限前端拦截
 *  - 时间范围筛选上限 90 天（报表类 1 年），超限直接报错提示
 */

import { message } from 'antd';

export const PAGE_SIZE_MAX = 100;
export const TIME_RANGE_MAX_DAYS = 90;

/** 归一化分页参数：夹取到合法区间，防越界请求 */
export function normalizePage(pageNum?: number, pageSize?: number): { pageNum: number; pageSize: number } {
  const rawPage = Math.trunc(pageNum ?? 0);
  const rawSize = Math.trunc(pageSize ?? 0);
  return {
    pageNum: rawPage > 0 ? rawPage : 1,
    pageSize: Math.min(PAGE_SIZE_MAX, rawSize > 0 ? rawSize : 10),
  };
}

/** 校验时间范围（YYYY-MM-DD / ISO 字符串均可），超限提示并返回 false */
export function assertTimeRange(start?: string, end?: string, maxDays = TIME_RANGE_MAX_DAYS): boolean {
  if (!start || !end) return true;
  const s = new Date(start).getTime();
  const e = new Date(end).getTime();
  if (Number.isNaN(s) || Number.isNaN(e)) return true;
  const days = (e - s) / (24 * 3600 * 1000);
  if (days > maxDays) {
    message.warning(`时间范围不能超过 ${maxDays} 天，请缩小筛选区间`);
    return false;
  }
  if (days < 0) {
    message.warning('开始时间不能晚于结束时间');
    return false;
  }
  return true;
}
