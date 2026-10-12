/**
 * 售后域契约回归（P0-5）
 *
 * 背景：审计实证 `listAfterSales/approveAfterSale/rejectAfterSale` 三个函数曾写成 `/after-sale/*`
 * （baseURL=`/api` → 实际请求 `/api/after-sale/list`），既不匹配 mock 路由 `/api/admin/after-sale/*`，
 * 也不匹配后端（server 全仓无 after-sale Controller）。同时文档声称"售后入口已隐藏"，与代码不符。
 *
 * 本测试锁死两件事：
 *  1. 三个函数的 URL 必须是 plans/admin-backend 4.2.2 的 `/admin/after-sale/*`（拼上 baseURL 即 `/api/admin/...`）
 *  2. 后端未实现 → `AFTER_SALE_AVAILABLE` 必须真正下线菜单入口（口径同 `ORDER_REMARK_AVAILABLE`）
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const calls = vi.hoisted(() => [] as Array<{ fn: string; url: string }>);

vi.mock('./request', () => ({
  get: (url: string) => {
    calls.push({ fn: 'GET', url });
    return Promise.resolve({ list: [], total: 0, pageNum: 1, pageSize: 10 });
  },
  post: (url: string) => {
    calls.push({ fn: 'POST', url });
    return Promise.resolve({});
  },
  put: (url: string) => {
    calls.push({ fn: 'PUT', url });
    return Promise.resolve({});
  },
  del: (url: string) => {
    calls.push({ fn: 'DELETE', url });
    return Promise.resolve({});
  },
}));

/** 递归收集菜单里出现过的 path */
function menuPaths(items: { path?: string; children?: unknown }[]): string[] {
  return items.flatMap((item) => [
    ...(item.path ? [item.path] : []),
    ...(Array.isArray(item.children) ? menuPaths(item.children as { path?: string; children?: unknown }[]) : []),
  ]);
}

beforeEach(() => {
  calls.length = 0;
  vi.resetModules();
});

describe('售后接口路径（plans/admin-backend 4.2.2）', () => {
  it('三个接口都必须带 /admin 前缀（baseURL=/api → /api/admin/after-sale/*）', async () => {
    const { approveAfterSale, listAfterSales, rejectAfterSale } = await import('./order');

    await listAfterSales({ pageNum: 1, pageSize: 10, status: '' });
    await approveAfterSale(7, 'ok');
    await rejectAfterSale(7, 'no');

    expect(calls).toEqual([
      { fn: 'GET', url: '/admin/after-sale/list' },
      { fn: 'POST', url: '/admin/after-sale/7/approve' },
      { fn: 'POST', url: '/admin/after-sale/7/reject' },
    ]);
  });
});

describe('售后特性开关 AFTER_SALE_AVAILABLE', () => {
  it('mock 模式（VITE_USE_MOCK != false）：入口保留（mock 已提供 /api/admin/after-sale/*）', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'true');
    const order = await import('./order');
    const { MENUS } = await import('@/config/menus');

    expect(order.AFTER_SALE_AVAILABLE).toBe(true);
    expect(menuPaths(MENUS as never)).toContain('/order/refund');
  });

  it('真实后端（VITE_USE_MOCK=false）：开关关闭且静态菜单不含退款/售后入口', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'false');
    const order = await import('./order');
    const { MENUS } = await import('@/config/menus');

    expect(order.AFTER_SALE_AVAILABLE).toBe(false);
    expect(menuPaths(MENUS as never)).not.toContain('/order/refund');
    // 其余入口不受影响
    expect(menuPaths(MENUS as never)).toContain('/order/list');
  });
});
