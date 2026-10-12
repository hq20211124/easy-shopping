/**
 * 订单 0 基 8 值状态机的前端映射（唯一事实来源：server/core-order README §2，
 * 与 admin-backend 4.3 白名单一致）。前端只做展示与 Tab 过滤，禁止自行推断状态流转。
 */

export interface OrderStatusMeta {
  value: number;
  text: string;
}

export const ORDER_STATUSES: readonly OrderStatusMeta[] = [
  { value: 0, text: '待付款' },
  { value: 1, text: '待发货' },
  { value: 2, text: '待收货' },
  { value: 3, text: '已完成' },
  { value: 4, text: '退款中' },
  { value: 5, text: '已退款' },
  { value: 6, text: '已取消' },
  { value: 7, text: '已关闭' },
];

export interface OrderTab {
  label: string;
  /** undefined = 全部 */
  status?: number;
}

/** 订单列表 Tab（全部 + 8 个状态）。 */
export const ORDER_TABS: readonly OrderTab[] = [
  { label: '全部' },
  ...ORDER_STATUSES.map((s) => ({ label: s.text, status: s.value })),
];

/** 状态值 → 文案；未知状态兜底「未知状态」（禁止用数字二次推断）。 */
export function statusText(status: number): string {
  return ORDER_STATUSES.find((s) => s.value === status)?.text ?? '未知状态';
}

/** 状态 Tag 配色（展示层）。 */
export function statusTone(status: number): string {
  switch (status) {
    case 0:
      return 'text-orange-600 bg-orange-50';
    case 1:
    case 2:
      return 'text-blue-600 bg-blue-50';
    case 3:
      return 'text-green-600 bg-green-50';
    case 4:
      return 'text-amber-700 bg-amber-50';
    case 5:
      return 'text-gray-600 bg-gray-100';
    default:
      return 'text-gray-500 bg-gray-50';
  }
}

/** 是否待付款（决定倒计时/去支付展示）。 */
export function isUnpaid(status: number): boolean {
  return status === 0;
}
