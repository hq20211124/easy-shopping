/** 金额展示格式化：全链路两位小数（仅做展示，以接口返回为准）。 */

import { lineTotal } from './amount';

/** 任意金额输入（接口可能返回字符串/数字/null）转两位小数字符串，非法值降级 0.00。 */
export function formatAmount(value: number | string | null | undefined): string {
  const n = typeof value === 'string' ? Number(value) : value;
  if (n === null || n === undefined || Number.isNaN(n) || !Number.isFinite(n)) return '0.00';
  return n.toFixed(2);
}

/** 带 ¥ 前缀的金额。 */
export function formatPrice(value: number | string | null | undefined): string {
  return `¥${formatAmount(value)}`;
}

/** 行小计（单价 × 数量，分运算防浮点误差）。 */
export function lineTotalText(price: number | string | null | undefined, quantity: number): string {
  return formatPrice(lineTotal(price, quantity));
}

/** 电话脱敏（展示用）。 */
export function maskMobile(mobile: string | null | undefined): string {
  if (!mobile) return '';
  return mobile.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2');
}

/** ISO 时间 → "YYYY-MM-DD HH:mm"（服务端时区 Asia/Shanghai，直接展示）。 */
export function formatDateTime(iso: string | number | null | undefined): string {
  if (!iso) return '';
  const d = new Date(typeof iso === 'number' ? iso : iso);
  if (Number.isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 毫秒 → "HH:mm:ss"（不足一小时显示 mm:ss）。 */
export function formatCountdown(ms: number): string {
  if (ms <= 0) return '00:00';
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const p = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${p(h)}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}
