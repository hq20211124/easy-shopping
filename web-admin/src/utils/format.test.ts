/** 金额与查询边界工具单测（admin-frontend 9.6 金额精度 / 16.4 查询边界） */
import { describe, expect, it } from 'vitest';
import { formatAmount, maskPhone } from './format';
import { normalizePage, PAGE_SIZE_MAX } from './query';

describe('formatAmount（分 → 元）', () => {
  it('常规金额', () => {
    expect(formatAmount(123456)).toBe('1,234.56');
    expect(formatAmount(5)).toBe('0.05');
    expect(formatAmount(0)).toBe('0.00');
  });

  it('负数与空值', () => {
    expect(formatAmount(-123456)).toBe('-1,234.56');
    expect(formatAmount(null)).toBe('-');
    expect(formatAmount(undefined)).toBe('-');
  });
});

describe('maskPhone', () => {
  it('后端已脱敏的号码原样返回', () => {
    expect(maskPhone('138****1234')).toBe('138****1234');
  });
  it('完整号码脱敏中间四位', () => {
    expect(maskPhone('13812341234')).toBe('138****1234');
  });
});

describe('normalizePage（pageSize 上限 100，16.4）', () => {
  it('超出上限夹取到 100', () => {
    expect(normalizePage(1, 500).pageSize).toBe(PAGE_SIZE_MAX);
  });
  it('非法值回退默认', () => {
    expect(normalizePage(0, -1)).toEqual({ pageNum: 1, pageSize: 10 });
  });
});
