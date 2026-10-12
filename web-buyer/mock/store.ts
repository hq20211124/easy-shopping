/**
 * Mock 可变状态 + 业务逻辑：结构与行为对齐 server/*（信封 {code,msg,data,traceId}、
 * 0 基 8 值订单状态机、cursor 分页、Idempotency-Key 幂等）。
 */

import { MOCK_PRODUCTS, type MockProduct, type MockSku } from './data';

// ------------------------------------------------------------------ 基础

let idSeq = 50000;
export const nextId = (): number => ++idSeq;

export const ok = (data: unknown) => ({ code: 0, msg: 'ok', data, traceId: `mock-${nextId()}` });
export const err = (code: number, msg: string, status = 200) => ({ code, msg, data: null, traceId: `mock-${nextId()}`, status });

/** 金额按分计算，规避浮点误差（与前端 amount.ts 同口径）。 */
export const cents = (v: number | string | null | undefined): number =>
  Math.round((typeof v === 'string' ? Number(v) : (v ?? 0)) * 100);

export interface FoundSku {
  product: MockProduct;
  sku: MockSku;
}

export function findSku(skuId: number): FoundSku | null {
  for (const product of MOCK_PRODUCTS) {
    const sku = product.skus.find((s) => s.skuId === skuId);
    if (sku) return { product, sku };
  }
  return null;
}

// ------------------------------------------------------------------ 认证

export interface MockUser {
  userId: number;
  username: string;
  password: string;
  nickname: string;
  roles: string[];
}

export const users: MockUser[] = [
  { userId: 10001, username: '13800138000', password: '123456', nickname: '演示买家', roles: ['USER'] },
];

/** token → userId（P0 mock：token 即身份，网关真实实现为 JWT）。 */
export const tokens = new Map<string, number>();

export function issueToken(user: MockUser) {
  const accessToken = `mock.jwt.access.${user.userId}.${nextId()}`;
  const refreshToken = `mock.jwt.refresh.${user.userId}.${nextId()}`;
  tokens.set(accessToken, user.userId);
  return {
    userId: user.userId,
    username: user.username,
    nickname: user.nickname,
    roles: user.roles,
    accessToken,
    refreshToken,
    expiresIn: 7200,
    tokenType: 'Bearer',
    isNewUser: false,
  };
}

export function currentUserId(authorization: string | undefined): number | null {
  if (!authorization?.startsWith('Bearer ')) return null;
  return tokens.get(authorization.slice(7)) ?? 10001; // mock 兜底：免登录联调
}

// ------------------------------------------------------------------ 幂等（对齐 common IdempotentInterceptor）

export const idempotencySeen = new Set<string>();

// ------------------------------------------------------------------ 购物车

export interface MockCartItem {
  cartItemId: number;
  userId: number;
  productId: number;
  skuId: number;
  quantity: number;
  selected: boolean;
}

export const cartItems: MockCartItem[] = [
  { cartItemId: 40001, userId: 10001, productId: 1001, skuId: 100101, quantity: 1, selected: true },
  { cartItemId: 40002, userId: 10001, productId: 1004, skuId: 100404, quantity: 2, selected: true },
  { cartItemId: 40003, userId: 10001, productId: 1003, skuId: 1003, quantity: 1, selected: false },
  // 下架商品（1008 onShelf=false）→ 失效行演示
  { cartItemId: 40004, userId: 10001, productId: 1008, skuId: 1008, quantity: 1, selected: true },
];

export interface CartItemVO {
  cartItemId: number;
  productId: number;
  skuId: number;
  title: string;
  mainImage: string;
  price: number | null;
  quantity: number;
  stock: number;
  inStock: boolean;
  selected: boolean;
  invalid: boolean;
  discountTags: string[];
}
export function cartList(userId: number) {
  const rows: CartItemVO[] = cartItems
    .filter((c) => c.userId === userId)
    .map((c) => {
      const found = findSku(c.skuId);
      const product = found?.product;
      const sku = found?.sku;
      const invalid = !product?.onShelf || !sku || sku.status !== 1;
      return {
        cartItemId: c.cartItemId,
        productId: c.productId,
        skuId: c.skuId,
        title: product ? `${product.name} ${sku?.title ?? ''}`.trim() : '商品已删除',
        mainImage: product?.mainImage ?? '',
        price: sku ? sku.price : null,
        quantity: c.quantity,
        stock: sku?.stock ?? 0,
        inStock: (sku?.stock ?? 0) > 0,
        selected: c.selected && !invalid,
        invalid,
        discountTags: [],
      };
    });

  const valid = rows.filter((r) => !r.invalid);
  const selectedRows = valid.filter((r) => r.selected);
  const totalCents = valid.reduce((sum, r) => sum + cents(r.price) * r.quantity, 0);
  const payableCents = selectedRows.reduce((sum, r) => sum + cents(r.price) * r.quantity, 0);
  return {
    items: rows,
    totalQuantity: rows.reduce((sum, r) => sum + r.quantity, 0),
    selectedQuantity: selectedRows.reduce((sum, r) => sum + r.quantity, 0),
    maxItems: 200, // 服务端下发，前端禁止硬编码
    totalAmount: (totalCents / 100).toFixed(2),
    discountAmount: '0.00', // P0 促销未接入恒 0（core-cart README §3）
    payableAmount: (payableCents / 100).toFixed(2),
  };
}

// ------------------------------------------------------------------ 地址

export interface MockAddress {
  id: number;
  name: string;
  mobile: string;
  province: string;
  city: string;
  district?: string;
  detail: string;
  zipCode?: string;
  isDefault: boolean;
}

export const addresses: MockAddress[] = [
  {
    id: 30001,
    name: '张三',
    mobile: '13800138000',
    province: '广东省',
    city: '深圳市',
    district: '南山区',
    detail: '科技园南路 88 号 3 栋 1201',
    isDefault: true,
  },
  {
    id: 30002,
    name: '李四',
    mobile: '13912345678',
    province: '浙江省',
    city: '杭州市',
    district: '西湖区',
    detail: '文一西路 100 号 A 座 502',
    isDefault: false,
  },
];

/** 出参手机号脱敏（对齐 core-user AddressResponse）。 */
export function maskMobile(m: string): string {
  return m.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2');
}

// ------------------------------------------------------------------ 优惠券

export interface MockCouponRecord {
  couponId: number;
  templateId: number;
  name: string;
  /** 1 满减 / 2 折扣 / 3 无门槛 */
  type: number;
  /** 满减/无门槛=面额；折扣=折扣值（如 8.5） */
  value: number;
  minAmount: number;
  status: '0' | '1' | '2';
  expireAt: string;
  receivedAt: string;
  usedOrderId?: number;
}

export const coupons: MockCouponRecord[] = [
  { couponId: 20001, templateId: 101, name: '满1000减100', type: 1, value: 100, minAmount: 1000, status: '0', expireAt: future(7), receivedAt: past(3) },
  { couponId: 20002, templateId: 102, name: '全场8.5折券', type: 2, value: 8.5, minAmount: 0, status: '0', expireAt: future(15), receivedAt: past(2) },
  { couponId: 20003, templateId: 103, name: '满50减5', type: 1, value: 5, minAmount: 50, status: '1', expireAt: future(30), receivedAt: past(10), usedOrderId: 60002 },
];

function future(days: number): string {
  return new Date(Date.now() + days * 86400_000).toISOString();
}
function past(days: number): string {
  return new Date(Date.now() - days * 86400_000).toISOString();
}

/** 券试算：对齐 core-promotion preview（不满足门槛 eligible=false，不抛异常）。 */
export function previewCoupon(coupon: MockCouponRecord | undefined, amountList: number[]) {
  if (!coupon) return { eligible: false, reason: '优惠券不存在', discount: 0, totalAmount: 0, finalAmount: 0 };
  const total = amountList.reduce((s, a) => s + cents(a), 0);
  const now = Date.now();
  if (coupon.status !== '0') {
    return { eligible: false, reason: '优惠券不可用', discount: 0, totalAmount: total / 100, finalAmount: total / 100 };
  }
  if (new Date(coupon.expireAt).getTime() < now) {
    return { eligible: false, reason: '优惠券已过期', discount: 0, totalAmount: total / 100, finalAmount: total / 100 };
  }
  if (total < cents(coupon.minAmount)) {
    return {
      eligible: false,
      reason: `未满足满 ${coupon.minAmount.toFixed(2)} 元门槛`,
      discount: 0,
      totalAmount: total / 100,
      finalAmount: total / 100,
    };
  }
  let discountCents = 0;
  if (coupon.type === 1 || coupon.type === 3) discountCents = Math.min(cents(coupon.value), total);
  else if (coupon.type === 2) discountCents = Math.round((total * (10 - coupon.value)) / 10); // 8.5折 = 付 85%
  const finalCents = Math.max(0, total - discountCents);
  return {
    couponId: coupon.couponId,
    templateId: coupon.templateId,
    couponName: coupon.name,
    eligible: true,
    reason: null,
    totalAmount: (total / 100).toFixed(2),
    discount: (discountCents / 100).toFixed(2),
    finalAmount: (finalCents / 100).toFixed(2),
  };
}

// ------------------------------------------------------------------ 订单（0 基 8 值状态机）

export const STATUS_TEXT: Record<number, string> = {
  0: '待付款',
  1: '待发货',
  2: '待收货',
  3: '已完成',
  4: '退款中',
  5: '已退款',
  6: '已取消',
  7: '已关闭',
};

export interface MockOrderItem {
  spuId: number;
  skuId: number;
  skuName: string;
  skuImage: string;
  price: number;
  quantity: number;
  totalAmount: number;
  discountAmount: number;
  storeId: number;
}

export interface MockOrder {
  orderId: number;
  orderNo: string;
  userId: number;
  status: number;
  storeIds: number[];
  items: MockOrderItem[];
  totalAmount: number;
  discountAmount: number;
  freightAmount: number;
  payAmount: number;
  receiverName: string;
  receiverMobile: string;
  receiverAddress: string;
  remark?: string;
  payDeadline: string;
  createdAt: string;
  payTime?: string;
  timeline: { fromStatus: number; toStatus: number; toStatusText: string; operatorType: number; reason?: string; time: string }[];
}

/** pay_deadline = 建单 + 30 分钟（唯一事实来源是订单表字段，前端不硬编码分钟数）。 */
export const PAY_TIMEOUT_MS = 30 * 60_000;

export const orders: MockOrder[] = [];

export function orderNo(now = new Date()): string {
  const p = (n: number, len = 2) => String(n).padStart(len, '0');
  const t = `${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}`;
  return `${t}${p(Math.floor(Math.random() * 10000), 4)}${p(Math.floor(Math.random() * 1000000), 6)}`.slice(0, 24);
}

export function createOrder(input: {
  userId: number;
  items: { skuId: number; quantity: number }[];
  receiverName: string;
  receiverMobile: string;
  receiverAddress: string;
  couponId?: number;
  remark?: string;
}) {
  // 逐行取权威价（服务端计价，客户端不可自证）
  const rows: MockOrderItem[] = [];
  for (const it of input.items) {
    const found = findSku(it.skuId);
    if (!found) throw { code: 3001, msg: `商品 SKU 不存在：${it.skuId}` };
    if (!found.product.onShelf || found.sku.status !== 1) throw { code: 3003, msg: '部分商品已下架，请返回购物车调整' };
    if (it.quantity < 1 || it.quantity > found.sku.stock) throw { code: 3002, msg: `「${found.product.name}」库存不足` };
    rows.push({
      spuId: found.product.spuId,
      skuId: found.sku.skuId,
      skuName: `${found.product.name} ${found.sku.title}`.trim(),
      skuImage: found.sku.images[0] ?? found.product.mainImage,
      price: found.sku.price,
      quantity: it.quantity,
      totalAmount: (cents(found.sku.price) * it.quantity) / 100,
      discountAmount: 0,
      storeId: found.product.storeId,
    });
  }
  const totalCents = rows.reduce((s, r) => s + cents(r.totalAmount), 0);

  // 优惠券：核销（对齐 core-promotion deduct 的失败语义 → 3002/4002）
  let discountCents = 0;
  if (input.couponId) {
    const coupon = coupons.find((c) => c.couponId === input.couponId);
    if (!coupon || coupon.status !== '0') throw { code: 4002, msg: '优惠券不可用或已被占用' };
    if (new Date(coupon.expireAt).getTime() < Date.now()) throw { code: 3002, msg: '优惠券已过期，请重新选择' };
    const pv = previewCoupon(coupon, [totalCents / 100]);
    if (!pv.eligible) throw { code: 3002, msg: `优惠券不可用：${pv.reason ?? ''}` };
    discountCents = cents(pv.discount);
    coupon.status = '1';
  }

  const now = new Date();
  const payAmountCents = Math.max(0, totalCents - discountCents);
  // 按 storeId 拆单（创建时拆单，core-order README §3）；优惠按店铺金额比例分摊，尾差归首单
  const storeIds = [...new Set(rows.map((r) => r.storeId))].sort((a, b) => a - b);
  let allocated = 0;
  storeIds.forEach((sid, i) => {
    const storeRows = rows.filter((r) => r.storeId === sid);
    const storeTotal = storeRows.reduce((s, r) => s + cents(r.totalAmount), 0);
    const share = i === storeIds.length - 1 ? discountCents - allocated : Math.round((discountCents * storeTotal) / totalCents);
    allocated += share;
    storeRows.forEach((r, j) => {
      const rowShare = j === storeRows.length - 1 ? share : Math.round((share * cents(r.totalAmount)) / storeTotal);
      r.discountAmount = rowShare / 100;
    });
  });

  const order: MockOrder = {
    orderId: nextId(),
    orderNo: orderNo(now),
    userId: input.userId,
    status: 0,
    storeIds,
    items: rows,
    totalAmount: totalCents / 100,
    discountAmount: discountCents / 100,
    freightAmount: 0, // P0 运费恒 0
    payAmount: payAmountCents / 100,
    receiverName: input.receiverName,
    receiverMobile: input.receiverMobile,
    receiverAddress: input.receiverAddress,
    remark: input.remark,
    payDeadline: new Date(now.getTime() + PAY_TIMEOUT_MS).toISOString(),
    createdAt: now.toISOString(),
    timeline: [{ fromStatus: -1, toStatus: 0, toStatusText: '待付款', operatorType: 1, time: now.toISOString() }],
  };
  orders.unshift(order);
  return order;
}

/** 状态流转（白名单校验，对齐 OrderStateMachine；幂等重放 no-op）。 */
export function transition(order: MockOrder, target: number): boolean {
  if (order.status === target) return false;
  const allowed: Record<number, number[]> = {
    0: [1, 6],
    1: [2, 4, 6, 7],
    2: [3, 4],
    3: [4, 7],
    4: [5, 3, 7],
    5: [7],
    6: [],
    7: [],
  };
  if (!allowed[order.status]?.includes(target)) throw { code: 4002, msg: `订单状态不允许该操作（${STATUS_TEXT[order.status]} → ${STATUS_TEXT[target]}）` };
  order.status = target;
  order.timeline.push({
    fromStatus: order.timeline[order.timeline.length - 1]?.toStatus ?? 0,
    toStatus: target,
    toStatusText: STATUS_TEXT[target],
    operatorType: 1,
    time: new Date().toISOString(),
  });
  return true;
}

/** 订单视图：按子单（storeId）分组渲染。 */
export function orderListVO(o: MockOrder) {
  const stores = [...new Set(o.items.map((i) => i.storeId))];
  return {
    orderId: o.orderId,
    orderNo: o.orderNo,
    status: o.status,
    statusText: STATUS_TEXT[o.status],
    paymentStatus: o.status >= 1 ? 'paid' : 'pending',
    totalAmount: o.totalAmount.toFixed(2),
    discountAmount: o.discountAmount.toFixed(2),
    freightAmount: o.freightAmount.toFixed(2),
    payAmount: o.payAmount.toFixed(2),
    itemCount: o.items.reduce((s, i) => s + i.quantity, 0),
    storeCount: stores.length,
    items: o.items.map((i) => ({
      orderId: o.orderId,
      orderNo: o.orderNo,
      storeId: i.storeId,
      spuId: i.spuId,
      skuId: i.skuId,
      skuName: i.skuName,
      skuImage: i.skuImage,
      price: i.price.toFixed(2),
      quantity: i.quantity,
      totalAmount: i.totalAmount.toFixed(2),
      discountAmount: i.discountAmount.toFixed(2),
    })),
    payDeadline: o.status === 0 ? o.payDeadline : null,
    createdAt: o.createdAt,
    actions: orderActions(o),
  };
}

export function orderActions(o: MockOrder): { type: string; text: string }[] {
  if (o.status === 0) return [{ type: 'pay', text: '去支付' }, { type: 'cancel', text: '取消订单' }];
  if (o.status === 2) return [{ type: 'confirm', text: '确认收货' }];
  return [];
}

// ------------------------------------------------------------------ 支付

export interface MockPayment {
  paymentId: number;
  orderId: number;
  userId: number;
  channel: number;
  amount: number;
  /** pending / paid / refunding / refunded */
  status: 'pending' | 'paid' | 'refunding' | 'refunded';
  paidAt?: string;
}

export const payments: MockPayment[] = [];

export function prepay(userId: number, orderId: number, channel: number): MockPayment {
  const order = orders.find((o) => o.orderId === orderId && o.userId === userId);
  if (!order) throw { code: 404, msg: '订单不存在' };
  if (order.status !== 0) throw { code: 4002, msg: '订单当前状态不可支付' };
  let payment = payments.find((p) => p.orderId === orderId);
  if (!payment) {
    payment = { paymentId: nextId(), orderId, userId, channel, amount: order.payAmount, status: 'pending' };
    payments.push(payment);
  }
  return payment;
}

/** mock 付款：走与真实回调相同的链路（幂等，重复调用不重复入账）。 */
export function mockPay(paymentId: number): MockPayment {
  const payment = payments.find((p) => p.paymentId === paymentId);
  if (!payment) throw { code: 4001, msg: '支付单不存在' };
  if (payment.status === 'paid') return payment;
  if (payment.status !== 'pending') throw { code: 5001, msg: '支付单状态不允许付款' };
  const order = orders.find((o) => o.orderId === payment.orderId);
  if (!order) throw { code: 404, msg: '订单不存在' };
  payment.status = 'paid';
  payment.paidAt = new Date().toISOString();
  transition(order, 1); // 回调入账成功 → 订单 0→1（竞态时按状态机裁决）
  order.payTime = payment.paidAt;
  return payment;
}

// ------------------------------------------------------------------ 种子数据

/** 种子订单：待付款（倒计时演示）/ 待收货 / 已完成。 */
export function seedOrders(userId: number): void {
  if (orders.length > 0) return;
  const mk = (status: number, skuId: number, quantity: number, ageMin: number) => {
    const found = findSku(skuId)!;
    const priceC = cents(found.sku.price);
    const now = new Date(Date.now() - ageMin * 60_000);
    const o: MockOrder = {
      orderId: nextId(),
      orderNo: orderNo(now),
      userId,
      status: 0, // 建单固定待付款，再按目标状态走状态机推进
      storeIds: [found.product.storeId],
      items: [
        {
          spuId: found.product.spuId,
          skuId: found.sku.skuId,
          skuName: `${found.product.name} ${found.sku.title}`.trim(),
          skuImage: found.sku.images[0] ?? found.product.mainImage,
          price: found.sku.price,
          quantity,
          totalAmount: (priceC * quantity) / 100,
          discountAmount: 0,
          storeId: found.product.storeId,
        },
      ],
      totalAmount: (priceC * quantity) / 100,
      discountAmount: 0,
      freightAmount: 0,
      payAmount: (priceC * quantity) / 100,
      receiverName: '张三',
      receiverMobile: '138****8000',
      receiverAddress: '广东省深圳市南山区科技园南路 88 号 3 栋 1201',
      payDeadline: new Date(now.getTime() + PAY_TIMEOUT_MS).toISOString(),
      createdAt: now.toISOString(),
      timeline: [{ fromStatus: -1, toStatus: 0, toStatusText: '待付款', operatorType: 1, time: now.toISOString() }],
    };
    if (status >= 1) transition(o, 1);
    if (status >= 2) transition(o, 2);
    if (status >= 3) transition(o, 3);
    orders.push(o);
  };
  mk(0, 100101, 1, 5); // 待付款（payDeadline 尚余 ~25 分钟，倒计时演示）
  mk(2, 100404, 1, 2880); // 待收货
  mk(3, 1003, 1, 20000); // 已完成
}
