// 一次性联调脚本：验证 mock 全链路（注册→加购→下单→支付→轮询→订单列表→地址）
const base = 'http://localhost:5174';

async function call(method, path, body, token, idem) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (idem) headers['Idempotency-Key'] = idem;
  const resp = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const json = await resp.json();
  return { http: resp.status, code: json.code, msg: json.msg, data: json.data };
}

const results = [];
const check = (name, r, expectCode = 0) => results.push({ name, ok: r.code === expectCode, code: r.code, http: r.http, msg: r.msg });

// 1. 注册（验证码 123456）
const reg = await call('POST', '/api/v1/auth/register', { mobile: '13812345678', code: '123456', password: 'pass123' });
check('register', reg);
const token = reg.data?.accessToken;

// 2. 购物车列表
const cart = await call('GET', '/api/v1/cart/list', null, token);
check('cart list', cart);

// 3. 加购 + 幂等重复（同键应 1005）
const add1 = await call('POST', '/api/v1/cart/add', { skuId: 100102, quantity: 1 }, token, 'idem-1');
check('cart add', add1);
const add2 = await call('POST', '/api/v1/cart/add', { skuId: 100102, quantity: 1 }, token, 'idem-1');
check('cart add idem-repeat(expect 1005)', add2, 1005);

// 4. 券试算（满1000减100 对 4599 → 可用）
const preview = await call('POST', '/api/v1/coupons/preview', { couponId: 20001, amount: ['4599.00'] }, token);
check('coupon preview', preview);

// 5. 下单（带券）
const order = await call('POST', '/api/v1/orders', {
  items: [{ skuId: 100102, quantity: 1 }],
  addressId: 30001,
  receiverName: '张三',
  receiverMobile: '138****8000',
  receiverAddress: '广东省深圳市南山区科技园南路88号',
  couponId: 20001,
}, token, 'idem-order-1');
check('order create', order);
const orderId = order.data?.orderId;
const payAmount = order.data?.payAmount;

// 6. 库存不足（数量 9999 → 3002）
const stockErr = await call('POST', '/api/v1/orders', { items: [{ skuId: 100102, quantity: 9999 }] }, token, 'idem-2');
check('order out-of-stock(expect 3002)', stockErr, 3002);

// 7. 支付链
const prepay = await call('POST', '/api/v1/payment/prepay', { orderId, channel: 1 }, token);
check('prepay', prepay);
const mockPay = await call('POST', '/api/v1/payment/mock/pay', { paymentId: prepay.data?.paymentId }, token);
check('mock pay', mockPay);
const status = await call('GET', `/api/v1/payment/status/${orderId}`, null, token);
check('payment status=paid', status);
const paidOk = status.data?.status === 'paid';
const orderPaid = await call('GET', `/api/v1/orders/${orderId}`, null, token);
const statusAfter = orderPaid.data?.status;

// 8. 订单列表 Tab（0基）
const listAll = await call('GET', '/api/v1/orders?page=1&pageSize=10', null, token);
const listUnpaid = await call('GET', '/api/v1/orders?status=0&page=1', null, token);
check('order list', listAll);
check('order list status=0', listUnpaid);

// 9. 地址 CRUD
const addr = await call('POST', '/api/v1/user/addresses', { name: '测试', mobile: '13700001111', province: '北京市', city: '北京市', district: '海淀区', detail: '中关村1号', isDefault: false }, token, 'idem-addr-1');
check('address create', addr);
const addrList = await call('GET', '/api/v1/user/addresses', null, token);
check('address list', addrList);

// 10. 搜索 cursor 分页
const p1 = await call('GET', '/api/v1/search/products?pageSize=3', null, token);
const p2 = await call('GET', `/api/v1/search/products?pageSize=3&cursor=${encodeURIComponent(p1.data.nextCursor)}`, null, token);
const noOverlap = p1.data.list.every(x => !p2.data.list.some(y => y.spuId === x.spuId));

const summary = {
  allPass: results.every(r => r.ok) && paidOk && statusAfter === 1 && noOverlap,
  checks: results,
  orderPayAmount: payAmount,
  orderStatusAfterPay: statusAfter,
  paymentStatus: status.data?.status,
  cursorNoOverlap: noOverlap,
  listTotal: listAll.data?.total,
  cartItems: cart.data?.items?.length,
};
console.log(JSON.stringify(summary, null, 2));
