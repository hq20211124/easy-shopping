/**
 * Vite mock 中间件：VITE_USE_MOCK=true 时拦截 /api/**，响应结构对齐 server/* README
 * （信封 {code,msg,data,traceId}、分页 list/total、cursor 搜索、0 基订单状态）。
 * 后端就绪后设 VITE_USE_MOCK=false 走 proxy → 网关 :8080，前端代码零改动。
 */

import type { Connect, Plugin } from 'vite';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { MOCK_HOT_WORDS, MOCK_PRODUCTS } from './data';
import {
  addresses,
  cartItems,
  cartList,
  cents,
  coupons,
  createOrder,
  currentUserId,
  err,
  findSku,
  idempotencySeen,
  issueToken,
  maskMobile,
  mockPay,
  nextId,
  ok,
  orderListVO,
  orders,
  payments,
  prepay,
  previewCoupon,
  seedOrders,
  transition,
  users,
  type MockOrder,
} from './store';

interface Ctx {
  body: Record<string, unknown>;
  query: URLSearchParams;
  params: Record<string, string>;
  userId: number;
}

type MockResult = ReturnType<typeof ok> | ReturnType<typeof err> & { status?: number };
type Handler = (ctx: Ctx) => MockResult;

// ------------------------------------------------------------------ 工具

/** 商品列表项（买家端冗余视图；price 取在售 SKU 最低价）。 */
function minPrice(p: (typeof MOCK_PRODUCTS)[number]): number | null {
  const sellable = p.skus.filter((s) => s.status === 1);
  if (!sellable.length) return null;
  return Math.min(...sellable.map((s) => s.price));
}

function productListItem(p: (typeof MOCK_PRODUCTS)[number]) {
  return {
    spuId: p.spuId,
    spuCode: p.spuCode,
    name: p.name,
    subtitle: p.subtitle,
    mainImage: p.mainImage,
    price: minPrice(p)?.toFixed(2) ?? null,
    originalPrice: Math.max(...p.skus.map((s) => s.marketPrice)).toFixed(2),
    salesCount: p.salesCount,
    skuCount: p.skus.length,
  };
}

function productDetail(p: (typeof MOCK_PRODUCTS)[number]) {
  return {
    spuId: p.spuId,
    spuCode: p.spuCode,
    name: p.name,
    subtitle: p.subtitle,
    mainImage: p.mainImage,
    images: p.images,
    video: null,
    detail: p.detail,
    price: minPrice(p)?.toFixed(2) ?? null,
    originalPrice: Math.max(...p.skus.map((s) => s.marketPrice)).toFixed(2),
    salesCount: p.salesCount,
    specs: p.specs,
    skus: p.skus.map((s) => ({
      skuId: s.skuId,
      skuCode: s.skuCode,
      title: s.title,
      specs: s.specs,
      images: s.images,
      status: s.status,
      price: s.price.toFixed(2),
    })),
  };
}

/** cursor 分页：Base64Url(offset)，末页 nextCursor=null（对齐 core-search README）。 */
function encodeCursor(offset: number): string {
  return Buffer.from(String(offset)).toString('base64url');
}
function decodeCursor(cursor: string | null): number {
  if (!cursor) return 0;
  const n = Number(Buffer.from(cursor, 'base64url').toString());
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

function pageSlice<T>(list: T[], page: number, pageSize: number) {
  const start = (Math.max(1, page) - 1) * pageSize;
  return { total: list.length, pageNum: page, pageSize, list: list.slice(start, start + pageSize) };
}

function num(v: string | null | undefined): number | undefined {
  const n = Number(v);
  return v !== null && v !== '' && Number.isFinite(n) ? n : undefined;
}

function sortProducts<T>(list: T[], sort: string | null, priceOf: (t: T) => number | null, salesOf: (t: T) => number): T[] {
  const arr = [...list];
  switch (sort) {
    case 'price_asc':
      return arr.sort((a, b) => (priceOf(a) ?? 0) - (priceOf(b) ?? 0));
    case 'price_desc':
      return arr.sort((a, b) => (priceOf(b) ?? 0) - (priceOf(a) ?? 0));
    case 'newest':
      return arr.reverse(); // mock：以夹具顺序近似上架时间
    case 'sales':
    default:
      return arr.sort((a, b) => salesOf(b) - salesOf(a)); // default=综合=销量倒序
  }
}

function detailOrThrow(spuId: number) {
  const p = MOCK_PRODUCTS.find((x) => x.spuId === spuId);
  if (!p) throw { code: 3001, msg: '商品不存在' };
  if (!p.onShelf) throw { code: 3003, msg: '商品已下架' };
  return p;
}

function requireNumber(v: unknown, name: string): number {
  const n = Number(v);
  if (!Number.isFinite(n)) throw { code: 1001, msg: `${name} 参数不合法` };
  return n;
}

function orderDetailVO(o: MockOrder) {
  const byStore = [...new Set(o.items.map((i) => i.storeId))];
  const children = byStore.map((sid) => {
    const rows = o.items.filter((i) => i.storeId === sid);
    const total = rows.reduce((s, i) => s + cents(i.totalAmount), 0);
    const discount = rows.reduce((s, i) => s + cents(i.discountAmount), 0);
    return {
      orderId: o.orderId,
      orderNo: o.orderNo,
      storeId: sid,
      status: o.status,
      statusText: orderListVO(o).statusText,
      totalAmount: (total / 100).toFixed(2),
      discountAmount: (discount / 100).toFixed(2),
      payAmount: ((total - discount) / 100).toFixed(2),
      itemCount: rows.reduce((s, i) => s + i.quantity, 0),
    };
  });
  const goods = o.items.reduce((s, i) => s + cents(i.totalAmount), 0);
  return {
    ...orderListVO(o),
    amountDetail: {
      goodsAmount: (goods / 100).toFixed(2),
      freightAmount: o.freightAmount.toFixed(2),
      discountAmount: o.discountAmount.toFixed(2),
      payAmount: o.payAmount.toFixed(2),
    },
    children,
    receiverName: o.receiverName,
    receiverMobile: o.receiverMobile,
    receiverAddress: o.receiverAddress,
    remark: o.remark ?? null,
    payType: o.status >= 1 ? 1 : null,
    payTime: o.payTime ?? null,
    payDeadline: o.status === 0 ? o.payDeadline : null,
    deliveryTime: null,
    receiveTime: null,
    timeline: o.timeline,
  };
}

// ------------------------------------------------------------------ 路由表

const routes: [string, string, Handler][] = [
  // 认证（core-user）
  [
    'POST',
    '/api/v1/auth/login',
    ({ body }) => {
      const username = String(body.username ?? '');
      const password = String(body.password ?? '');
      const user = users.find((u) => u.username === username);
      if (!user) throw { code: 2001, msg: '用户不存在' };
      if (user.password !== password) throw { code: 2002, msg: '密码错误' };
      return ok(issueToken(user));
    },
  ],
  [
    'POST',
    '/api/v1/auth/register',
    ({ body }) => {
      const mobile = String(body.mobile ?? '');
      const code = String(body.code ?? '');
      const password = String(body.password ?? '');
      if (!/^1[3-9]\d{9}$/.test(mobile)) throw { code: 1001, msg: '手机号格式不正确' };
      if (code !== '123456') throw { code: 2003, msg: '验证码错误（P0 测试码固定 123456）' };
      if (password.length < 6) throw { code: 1001, msg: '密码长度 6~32 位' };
      let user = users.find((u) => u.username === mobile);
      if (!user) {
        user = { userId: nextId(), username: mobile, password, nickname: `用户${mobile.slice(-4)}`, roles: ['USER'] };
        users.push(user);
      }
      return ok(issueToken(user));
    },
  ],
  [
    'POST',
    '/api/v1/auth/refresh',
    ({ body }) => {
      const refreshToken = String(body.refreshToken ?? '');
      const userId = Number(refreshToken.split('.')[3] ?? '') || 10001;
      const user = users.find((u) => u.userId === userId) ?? users[0];
      return ok(issueToken(user));
    },
  ],
  ['POST', '/api/v1/auth/logout', () => ok({ success: true })],
  ['GET', '/api/v1/auth/me', ({ userId }) => ok({ userId })],

  // 地址（core-user）
  [
    'GET',
    '/api/v1/user/addresses',
    ({ userId }) =>
      ok({
        addresses: addresses.map((a) => ({ ...a, mobile: maskMobile(a.mobile) })),
        __userId: userId,
      }),
  ],
  [
    'POST',
    '/api/v1/user/addresses',
    ({ body }) => {
      const address = {
        id: nextId(),
        name: String(body.name ?? ''),
        mobile: String(body.mobile ?? ''),
        province: String(body.province ?? ''),
        city: String(body.city ?? ''),
        district: body.district ? String(body.district) : undefined,
        detail: String(body.detail ?? ''),
        isDefault: body.isDefault === true || addresses.length === 0,
      };
      if (address.isDefault) addresses.forEach((a) => (a.isDefault = false));
      addresses.push(address);
      return ok({ addressId: address.id });
    },
  ],
  [
    'PUT',
    '/api/v1/user/addresses/:id',
    ({ body, params }) => {
      const a = addresses.find((x) => x.id === Number(params.id));
      if (!a) throw { code: 404, msg: '地址不存在' };
      Object.assign(a, {
        name: String(body.name ?? a.name),
        mobile: String(body.mobile ?? a.mobile),
        province: String(body.province ?? a.province),
        city: String(body.city ?? a.city),
        district: body.district ? String(body.district) : a.district,
        detail: String(body.detail ?? a.detail),
      });
      if (body.isDefault === true) {
        addresses.forEach((x) => (x.isDefault = x.id === a.id));
      }
      return ok({ updated: true });
    },
  ],
  [
    'DELETE',
    '/api/v1/user/addresses/:id',
    ({ params }) => {
      const idx = addresses.findIndex((x) => x.id === Number(params.id));
      if (idx < 0) throw { code: 404, msg: '地址不存在' };
      const [removed] = addresses.splice(idx, 1);
      if (removed.isDefault && addresses[0]) addresses[0].isDefault = true; // 默认位自动顺延
      return ok({ deleted: true });
    },
  ],
  [
    'POST',
    '/api/v1/user/addresses/:id/default',
    ({ params }) => {
      const id = Number(params.id);
      if (!addresses.some((x) => x.id === id)) throw { code: 404, msg: '地址不存在' };
      addresses.forEach((x) => (x.isDefault = x.id === id));
      return ok({ success: true });
    },
  ],

  // 商品（core-product）
  [
    'GET',
    '/api/v1/products',
    ({ query }) => {
      const keyword = query.get('keyword');
      const categoryId = num(query.get('categoryId'));
      const priceMin = num(query.get('priceMin'));
      const priceMax = num(query.get('priceMax'));
      const page = num(query.get('page')) ?? 1;
      const pageSize = Math.min(50, num(query.get('pageSize')) ?? 20);
      let list = MOCK_PRODUCTS.filter((p) => p.onShelf);
      if (keyword) list = list.filter((p) => p.name.includes(keyword));
      if (categoryId) list = list.filter((p) => p.categoryId === categoryId);
      if (priceMin !== undefined) list = list.filter((p) => (minPrice(p) ?? 0) >= priceMin);
      if (priceMax !== undefined) list = list.filter((p) => (minPrice(p) ?? Infinity) <= priceMax);
      list = sortProducts(list, query.get('sort'), minPrice, (p) => p.salesCount);
      return ok(pageSlice(list.map(productListItem), page, pageSize));
    },
  ],
  [
    'GET',
    '/api/v1/products/:spuId',
    ({ params }) => ok(productDetail(detailOrThrow(Number(params.spuId)))),
  ],

  // 搜索（core-search：cursor 分页 + 建议 + 热搜）
  [
    'GET',
    '/api/v1/search/products',
    ({ query }) => {
      const q = query.get('q') ?? '';
      const categoryId = num(query.get('categoryId'));
      const priceMin = num(query.get('priceMin'));
      const priceMax = num(query.get('priceMax'));
      const pageSize = Math.min(50, num(query.get('pageSize')) ?? 20);
      let list = MOCK_PRODUCTS.filter((p) => p.onShelf);
      if (q) list = list.filter((p) => p.name.includes(q) || p.subtitle.includes(q));
      if (categoryId) list = list.filter((p) => p.categoryId === categoryId);
      if (priceMin !== undefined) list = list.filter((p) => (minPrice(p) ?? 0) >= priceMin);
      if (priceMax !== undefined) list = list.filter((p) => (minPrice(p) ?? Infinity) <= priceMax);
      list = sortProducts(list, query.get('sort'), minPrice, (p) => p.salesCount);
      const offset = decodeCursor(query.get('cursor'));
      const rows = list.slice(offset, offset + pageSize).map((p) => ({
        spuId: p.spuId,
        name: p.name,
        mainImage: p.mainImage,
        price: minPrice(p)?.toFixed(2) ?? null,
        originalPrice: Math.max(...p.skus.map((s) => s.marketPrice)).toFixed(2),
        salesCount: p.salesCount,
      }));
      const next = offset + pageSize;
      return ok({ query: q, total: list.length, nextCursor: next < list.length ? encodeCursor(next) : null, list: rows });
    },
  ],
  [
    'GET',
    '/api/v1/search/suggestions',
    ({ query }) => {
      const q = query.get('q') ?? '';
      const limit = Math.min(20, num(query.get('limit')) ?? 10);
      const products = q ? MOCK_PRODUCTS.filter((p) => p.onShelf && p.name.includes(q)).slice(0, limit) : [];
      return ok({
        query: q,
        suggestions: [
          ...products.map((p) => ({ text: p.name.slice(0, 16), type: 'product', productId: p.spuId })),
          ...(q ? [{ text: q, type: 'keyword' }] : []),
        ],
      });
    },
  ],
  [
    'GET',
    '/api/v1/search/hot',
    ({ query }) => {
      const limit = Math.min(20, num(query.get('limit')) ?? 10);
      return ok(MOCK_HOT_WORDS.slice(0, limit).map((keyword, i) => ({ keyword, heat: MOCK_HOT_WORDS.length - i })));
    },
  ],

  // 购物车（core-cart：写接口 @Idempotent；以 cartItemId 定位行）
  [
    'GET',
    '/api/v1/cart/list',
    ({ userId }) => {
      seedOrders(userId);
      return ok(cartList(userId));
    },
  ],
  [
    'POST',
    '/api/v1/cart/add',
    ({ body, userId }) => {
      const skuId = requireNumber(body.skuId, 'skuId');
      const quantity = body.quantity === undefined ? 1 : requireNumber(body.quantity, 'quantity');
      const found = findSku(skuId);
      if (!found) throw { code: 3001, msg: '商品不存在' };
      if (!found.product.onShelf || found.sku.status !== 1) throw { code: 3003, msg: '商品已下架' };
      if (quantity < 1) throw { code: 1001, msg: '数量不合法' };
      let row = cartItems.find((c) => c.userId === userId && c.skuId === skuId);
      if (row) {
        // 加购合并：同 SKU 累加，超上限报错而非静默截断
        if (row.quantity + quantity > 999) throw { code: 1001, msg: '单商品数量已达上限' };
        row.quantity += quantity;
      } else {
        if (cartItems.filter((c) => c.userId === userId).length >= 200) throw { code: 1001, msg: '购物车已满（上限 200 件商品）' };
        row = { cartItemId: nextId(), userId, productId: found.product.spuId, skuId, quantity, selected: true };
        cartItems.push(row);
      }
      const list = cartList(userId);
      return ok({ cartItemId: row.cartItemId, cartTotalQuantity: list.totalQuantity });
    },
  ],
  [
    'POST',
    '/api/v1/cart/update',
    ({ body, userId }) => {
      const cartItemId = requireNumber(body.cartItemId, 'cartItemId');
      const row = cartItems.find((c) => c.cartItemId === cartItemId && c.userId === userId);
      if (!row) throw { code: 3004, msg: '购物车行不存在' };
      if (body.quantity !== undefined) {
        const q = requireNumber(body.quantity, 'quantity');
        if (q < 1) throw { code: 1001, msg: '数量不合法' };
        const found = findSku(row.skuId);
        if (found && q > found.sku.stock) throw { code: 3002, msg: `库存不足，仅剩 ${found.sku.stock} 件` };
        row.quantity = q;
      }
      if (body.selected !== undefined) row.selected = body.selected === true;
      const list = cartList(userId);
      return ok({ cartItemId: row.cartItemId, cartTotalQuantity: list.totalQuantity, selectedQuantity: list.selectedQuantity });
    },
  ],
  [
    'POST',
    '/api/v1/cart/remove',
    ({ body, userId }) => {
      const ids = Array.isArray(body.cartItemIds) ? body.cartItemIds.map(Number) : [];
      if (!ids.length) throw { code: 1001, msg: 'cartItemIds 不能为空' };
      for (let i = cartItems.length - 1; i >= 0; i--) {
        const c = cartItems[i];
        if (c.userId === userId && ids.includes(c.cartItemId)) cartItems.splice(i, 1);
      }
      return ok({ cartTotalQuantity: cartList(userId).totalQuantity });
    },
  ],
  [
    'POST',
    '/api/v1/cart/select',
    ({ body, userId }) => {
      const selected = body.selected === true;
      const ids = Array.isArray(body.cartItemIds) ? body.cartItemIds.map(Number) : [];
      cartItems.forEach((c) => {
        if (c.userId !== userId) return;
        if (ids.length === 0 || ids.includes(c.cartItemId)) c.selected = selected;
      });
      const list = cartList(userId);
      return ok({ cartTotalQuantity: list.totalQuantity, selectedQuantity: list.selectedQuantity });
    },
  ],

  // 订单（core-order：0 基状态机；写接口幂等）
  [
    'POST',
    '/api/v1/orders',
    ({ body, userId }) => {
      seedOrders(userId);
      const rawItems = Array.isArray(body.items) ? body.items : [];
      if (!rawItems.length) throw { code: 1001, msg: 'items 不能为空' };
      const items = rawItems.map((it: Record<string, unknown>) => ({
        skuId: requireNumber(it.skuId, 'skuId'),
        quantity: requireNumber(it.quantity, 'quantity'),
      }));
      let order: MockOrder;
      try {
        order = createOrder({
          userId,
          items,
          receiverName: String(body.receiverName ?? ''),
          receiverMobile: String(body.receiverMobile ?? ''),
          receiverAddress: String(body.receiverAddress ?? ''),
          couponId: body.couponId ? requireNumber(body.couponId, 'couponId') : undefined,
          remark: body.remark ? String(body.remark) : undefined,
        });
      } catch (e) {
        const biz = e as { code: number; msg: string };
        return err(biz.code, biz.msg);
      }
      return ok({
        orderId: order.orderId,
        orderNo: order.orderNo,
        status: order.status,
        statusText: orderListVO(order).statusText,
        totalAmount: order.totalAmount.toFixed(2),
        discountAmount: order.discountAmount.toFixed(2),
        freightAmount: order.freightAmount.toFixed(2),
        payAmount: order.payAmount.toFixed(2),
        expireSeconds: 1800,
        payDeadline: order.payDeadline,
        subOrders: order.storeIds.map((sid) => {
          const rows = order.items.filter((i) => i.storeId === sid);
          return {
            orderId: order.orderId,
            orderNo: order.orderNo,
            storeId: sid,
            payAmount: (rows.reduce((s, i) => s + cents(i.totalAmount), 0) / 100).toFixed(2),
            itemCount: rows.reduce((s, i) => s + i.quantity, 0),
          };
        }),
      });
    },
  ],
  [
    'GET',
    '/api/v1/orders',
    ({ query, userId }) => {
      seedOrders(userId);
      const status = query.get('status');
      const page = num(query.get('page')) ?? 1;
      const pageSize = Math.min(50, num(query.get('pageSize')) ?? 20);
      let list = orders.filter((o) => o.userId === userId);
      if (status && status !== 'all') list = list.filter((o) => o.status === Number(status));
      const { total, pageNum } = pageSlice(list, page, pageSize);
      return ok({ total, pageNum, pageSize, list: list.slice((pageNum - 1) * pageSize, pageNum * pageSize).map(orderListVO) });
    },
  ],
  [
    'GET',
    '/api/v1/orders/:orderId',
    ({ params, userId }) => {
      const order = orders.find((o) => o.orderId === Number(params.orderId) && o.userId === userId);
      if (!order) throw { code: 404, msg: '订单不存在' };
      return ok(orderDetailVO(order));
    },
  ],
  [
    'POST',
    '/api/v1/orders/:orderId/cancel',
    ({ params, body, userId }) => {
      const order = orders.find((o) => o.orderId === Number(params.orderId) && o.userId === userId);
      if (!order) throw { code: 404, msg: '订单不存在' };
      try {
        transition(order, 6); // 仅待付款可取消（状态机白名单裁决）
      } catch (e) {
        const biz = e as { code: number; msg: string };
        return err(biz.code, biz.msg);
      }
      const reason = body?.reason ? String(body.reason) : undefined;
      if (reason) order.timeline[order.timeline.length - 1].reason = reason;
      return ok({ orderId: order.orderId, orderNo: order.orderNo, status: order.status, statusText: orderListVO(order).statusText });
    },
  ],
  [
    'POST',
    '/api/v1/orders/:orderId/confirm',
    ({ params, userId }) => {
      const order = orders.find((o) => o.orderId === Number(params.orderId) && o.userId === userId);
      if (!order) throw { code: 404, msg: '订单不存在' };
      try {
        transition(order, 3);
      } catch (e) {
        const biz = e as { code: number; msg: string };
        return err(biz.code, biz.msg);
      }
      return ok({ orderId: order.orderId, orderNo: order.orderNo, status: order.status, statusText: orderListVO(order).statusText });
    },
  ],

  // 支付（core-payment：prepay 防改价；mock/pay 走真实回调链路；查单轮询）
  [
    'POST',
    '/api/v1/payment/prepay',
    ({ body, userId }) => {
      try {
        const payment = prepay(userId, requireNumber(body.orderId, 'orderId'), requireNumber(body.channel ?? 1, 'channel'));
        return ok({
          paymentId: payment.paymentId,
          channel: payment.channel,
          amount: payment.amount.toFixed(2),
          payType: 'mock',
          payParams: { mock: 'true' },
          expireAt: null,
        });
      } catch (e) {
        const biz = e as { code: number; msg: string };
        return err(biz.code, biz.msg);
      }
    },
  ],
  [
    'POST',
    '/api/v1/payment/mock/pay',
    ({ body }) => {
      try {
        const payment = mockPay(requireNumber(body.paymentId, 'paymentId'));
        return ok({ paymentId: payment.paymentId, orderId: payment.orderId, status: payment.status, paidAt: payment.paidAt });
      } catch (e) {
        const biz = e as { code: number; msg: string };
        return err(biz.code, biz.msg);
      }
    },
  ],
  [
    'GET',
    '/api/v1/payment/status/:orderId',
    ({ params, userId }) => {
      const order = orders.find((o) => o.orderId === Number(params.orderId) && o.userId === userId);
      if (!order) throw { code: 404, msg: '订单不存在' };
      const payment = payments.find((p) => p.orderId === order.orderId);
      return ok({
        orderId: order.orderId,
        paymentId: payment?.paymentId ?? 0,
        channel: payment?.channel ?? null,
        amount: order.payAmount.toFixed(2),
        status: payment?.status ?? (order.status >= 1 ? 'paid' : 'pending'),
        paidAt: payment?.paidAt ?? null,
      });
    },
  ],

  // 优惠券（core-promotion：preview 失败降级 eligible=false，不抛异常）
  [
    'GET',
    '/api/v1/coupons/my',
    ({ query, userId }) => {
      seedOrders(userId);
      const status = query.get('status');
      const list = coupons
        .filter((c) => status === null || c.status === status)
        .map((c) => ({
          couponId: c.couponId,
          templateId: c.templateId,
          name: c.name,
          type: c.type,
          status: c.status === '0' ? 'available' : c.status === '1' ? 'used' : 'expired',
          expireAt: c.expireAt,
          receivedAt: c.receivedAt,
          usedOrderId: c.usedOrderId ?? null,
          usedTime: null,
        }));
      return ok(list);
    },
  ],
  [
    'POST',
    '/api/v1/coupons/preview',
    ({ body }) => {
      const couponId = requireNumber(body.couponId, 'couponId');
      const amountList = Array.isArray(body.amount) ? body.amount.map(Number) : [Number(body.amount)];
      return ok(previewCoupon(coupons.find((c) => c.couponId === couponId), amountList));
    },
  ],
];

function compile(pattern: string): { regex: RegExp; keys: string[] } | null {
  const keys: string[] = [];
  const source = pattern
    .split('/')
    .map((seg) => {
      if (!seg.startsWith(':')) return seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      keys.push(seg.slice(1));
      return '([^/]+)';
    })
    .join('/');
  return { regex: new RegExp(`^${source}$`), keys };
}

const compiled = routes.map(([method, path, handler]) => ({ method, ...compile(path)!, handler }));

function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw) as Record<string, unknown>);
      } catch {
        resolve({});
      }
    });
  });
}

function envelope(result: MockResult, res: ServerResponse): void {
  const status = 'status' in result && typeof result.status === 'number' ? result.status : 200;
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify({ code: result.code, msg: result.msg, data: result.data, traceId: result.traceId }));
}

const mockMiddleware: Connect.NextHandleFunction = (req, res, next) => {
  const url = new URL(req.url ?? '/', 'http://localhost');
  const path = url.pathname;
  if (!path.startsWith('/api/')) return next(); // 非 API 请求交给 Vite 静态/转换中间件
  void handleApi(req, res, url);
};

async function handleApi(req: IncomingMessage, res: ServerResponse, url: URL): Promise<void> {
  const path = url.pathname;
  const method = req.method ?? 'GET';
  const hit = compiled.find((r) => r.method === method && r.regex.test(path));
  if (!hit) return envelope(err(404, `mock 未实现的接口：${method} ${path}`), res);

  // 写接口幂等（对齐 common IdempotentInterceptor：缺键 1001、重复 1005/HTTP 409）
  if (method !== 'GET') {
    const key = (req.headers['idempotency-key'] ?? req.headers['x-idempotency-key']) as string | undefined;
    if (key) {
      const scoped = `${method}:${path}:${key}`;
      if (idempotencySeen.has(scoped)) {
        res.statusCode = 409;
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
        res.end(JSON.stringify({ code: 1005, msg: '重复请求（幂等键已使用）', data: null, traceId: `mock-${nextId()}` }));
        return;
      }
      idempotencySeen.add(scoped);
    }
  }

  const body = await readBody(req);
  const params: Record<string, string> = {};
  const m = path.match(hit.regex)!;
  hit.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
  const userId = currentUserId(req.headers.authorization) ?? 10001;
  try {
    envelope(hit.handler({ body, query: url.searchParams, params, userId }), res);
  } catch (e) {
    const biz = e as { code?: number; msg?: string };
    envelope(err(biz.code ?? 9999, biz.msg ?? '系统异常'), res);
  }
};

export function mockApiPlugin(): Plugin {
  return {
    name: 'mock-api',
    configureServer(server) {
      server.middlewares.use(mockMiddleware);
    },
    configurePreviewServer(server) {
      server.middlewares.use(mockMiddleware);
    },
  };
}
