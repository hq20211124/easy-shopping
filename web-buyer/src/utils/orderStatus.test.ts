import { describe, expect, it } from 'vitest';
import { ORDER_STATUSES, ORDER_TABS, isUnpaid, statusText, statusTone } from './orderStatus';

describe('订单 0 基 8 值状态映射', () => {
  it('8 个状态值与文案一一对应（0 待付款 … 7 已关闭）', () => {
    expect(ORDER_STATUSES.map((s) => s.value)).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
    expect(statusText(0)).toBe('待付款');
    expect(statusText(1)).toBe('待发货');
    expect(statusText(2)).toBe('待收货');
    expect(statusText(3)).toBe('已完成');
    expect(statusText(4)).toBe('退款中');
    expect(statusText(5)).toBe('已退款');
    expect(statusText(6)).toBe('已取消');
    expect(statusText(7)).toBe('已关闭');
  });

  it('未知状态值兜底文案', () => {
    expect(statusText(8)).toBe('未知状态');
    expect(statusText(-1)).toBe('未知状态');
  });

  it('Tab 列表 = 全部 + 8 状态，label 唯一', () => {
    expect(ORDER_TABS[0]).toEqual({ label: '全部' });
    expect(ORDER_TABS).toHaveLength(9);
    const labels = ORDER_TABS.map((t) => t.label);
    expect(new Set(labels).size).toBe(labels.length);
    expect(ORDER_TABS.filter((t) => t.status !== undefined)).toHaveLength(8);
  });

  it('仅待付款展示支付倒计时', () => {
    expect(isUnpaid(0)).toBe(true);
    expect(isUnpaid(1)).toBe(false);
    expect(isUnpaid(6)).toBe(false);
  });

  it('状态 Tag 配色非空且分组稳定', () => {
    expect(statusTone(0)).toContain('orange');
    expect(statusTone(3)).toContain('green');
    expect(statusTone(7)).toContain('gray');
  });
});
