/**
 * 金额汇总（纯函数，供购物车/结算页展示层使用）。
 *
 * 口径：只汇总「已勾选且未失效」的行，行金额 = 单价 × 数量；
 * 用「分」做整数运算规避浮点误差；**最终应付以接口返回为准**（DEV.md：客户端不可自证金额）。
 */

export interface AmountLine {
  price: number | string | null | undefined;
  quantity: number;
  selected?: boolean;
  invalid?: boolean;
}

/** 单行小计（元，两位小数）。 */
export function lineTotal(price: number | string | null | undefined, quantity: number): number {
  const cents = toCents(price) * Math.max(0, Math.floor(quantity || 0));
  return cents / 100;
}

/** 汇总选中（且未失效）行的金额（元，两位小数）。 */
export function sumSelected(items: AmountLine[]): number {
  let cents = 0;
  for (const item of items) {
    if (item.invalid || !item.selected) continue;
    cents += toCents(item.price) * Math.max(0, Math.floor(item.quantity || 0));
  }
  return cents / 100;
}

/** 汇总全部未失效行金额（未勾选也计入，用于「已选/总计」双口径展示）。 */
export function sumValid(items: AmountLine[]): number {
  let cents = 0;
  for (const item of items) {
    if (item.invalid) continue;
    cents += toCents(item.price) * Math.max(0, Math.floor(item.quantity || 0));
  }
  return cents / 100;
}

/** 应付 = 商品金额 - 优惠（不小于 0；仅展示）。 */
export function payable(goodsAmount: number, discountAmount: number): number {
  const cents = Math.max(0, Math.round(goodsAmount * 100) - Math.round(discountAmount * 100));
  return cents / 100;
}

/** 元 → 分（字符串金额精确到分，避免 0.1+0.2 误差）。 */
function toCents(value: number | string | null | undefined): number {
  const n = typeof value === 'string' ? Number(value) : value;
  if (n === null || n === undefined || Number.isNaN(n)) return 0;
  return Math.round(n * 100);
}
