/**
 * 展示层格式化工具
 *
 * 金额规范（plans/admin-frontend.md 9.6 / 16.4）：
 *  - 传输与存储一律以「分」为整数，前端禁止浮点运算金额
 *  - 展示统一走 formatAmount（分 → 元，字符串安全）
 */

/** 分 → 元字符串（123456 → "1234.56"） */
export function formatAmount(cents: number | null | undefined): string {
  if (cents === null || cents === undefined || Number.isNaN(cents)) return '-';
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(Math.trunc(cents));
  const yuan = Math.floor(abs / 100);
  const cent = String(abs % 100).padStart(2, '0');
  return `${sign}${yuan.toLocaleString('zh-CN')}.${cent}`;
}

/** 手机号脱敏（列表展示用；后端脱敏为主、前端为辅） */
export function maskPhone(phone: string | null | undefined): string {
  if (!phone) return '-';
  if (phone.includes('****')) return phone;
  if (phone.length < 11) return phone;
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`;
}

/** 数字千分位（非金额场景） */
export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-';
  return value.toLocaleString('zh-CN');
}
