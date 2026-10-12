import { describe, expect, it } from 'vitest';
import { lineTotal, payable, sumSelected, sumValid } from './amount';
import { formatAmount, formatCountdown, formatPrice, maskMobile } from './format';

describe('金额汇总（勾选口径，分为单位防浮点误差）', () => {
  const items = [
    { price: '19.90', quantity: 3, selected: true, invalid: false }, // 59.70
    { price: 0.1, quantity: 3, selected: true, invalid: false }, // 0.30（0.1×3 浮点陷阱）
    { price: '100.00', quantity: 1, selected: false, invalid: false }, // 未勾选不计
    { price: '50.00', quantity: 2, selected: true, invalid: true }, // 失效不计
  ];

  it('sumSelected 只汇总已勾选且未失效行', () => {
    expect(sumSelected(items)).toBe(60);
  });

  it('sumValid 汇总全部未失效行（含未勾选）', () => {
    expect(sumValid(items)).toBe(160);
  });

  it('lineTotal 单行小计两位小数', () => {
    expect(lineTotal('19.90', 3)).toBe(59.7);
    expect(lineTotal(0.1, 3)).toBe(0.3);
    expect(lineTotal(null, 5)).toBe(0);
    expect(lineTotal('9.99', 0)).toBe(0);
  });

  it('payable = 商品金额 - 优惠，且不为负', () => {
    expect(payable(100, 20)).toBe(80);
    expect(payable(10.01, 0.01)).toBe(10);
    expect(payable(5, 10)).toBe(0);
  });

  it('空车/全失效 → 0.00', () => {
    expect(sumSelected([])).toBe(0);
    expect(sumSelected([{ price: '9.90', quantity: 2, selected: true, invalid: true }])).toBe(0);
  });
});

describe('金额展示格式化（全链路两位小数）', () => {
  it('formatAmount 字符串/数字/null 都输出两位小数', () => {
    expect(formatAmount('1234.5')).toBe('1234.50');
    expect(formatAmount(0)).toBe('0.00');
    expect(formatAmount(null)).toBe('0.00');
    expect(formatAmount(undefined)).toBe('0.00');
    expect(formatAmount('abc')).toBe('0.00');
    expect(formatAmount('-8.5')).toBe('-8.50');
  });

  it('formatPrice 带 ¥ 前缀', () => {
    expect(formatPrice('9.9')).toBe('¥9.90');
  });

  it('maskMobile 脱敏', () => {
    expect(maskMobile('13812345678')).toBe('138****5678');
    expect(maskMobile('')).toBe('');
  });

  it('formatCountdown 倒计时格式', () => {
    expect(formatCountdown(59_000)).toBe('00:59');
    expect(formatCountdown(3_600_000)).toBe('01:00:00');
    expect(formatCountdown(-1)).toBe('00:00');
  });
});
