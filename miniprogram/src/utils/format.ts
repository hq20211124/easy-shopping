import type { Money } from '@/api/types'

/** 金额统一两位小数展示；以后端下发为准，不在前端做业务计算。 */
export function fmtMoney(v: Money): string {
  if (v === null || v === undefined || v === '') return '0.00'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}

/** 金额转分（整数），仅用于购物车底部栏等本地预估展示，避免浮点误差。 */
export function toCents(v: Money): number {
  const n = Number(v ?? 0)
  return Number.isFinite(n) ? Math.round(n * 100) : 0
}

/** ISO 时间 → 'YYYY-MM-DD HH:mm'（本地时区）。 */
export function fmtDateTime(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
