// 活体联调：web-buyer 真实模式全链路（VITE_USE_MOCK=false，全部走 :5174 dev proxy → :8080 网关）
// 断言口径与 scripts/mock-chain-check.mjs 一致，另补任务书要求的真实链路项：
//   注册→登录→搜索→详情→加购→下单(0 基状态=0 + pay_deadline)→prepay→mock 支付→查单=1
//   →领券下单用券减免→库存不足 3002→同 Idempotency-Key 重复下单 409/1005
// 前置：server/scripts/start-all.ps1 已起后端；web-buyer/.env.development 的 VITE_USE_MOCK=false
// 用法：node web-buyer/scripts/real-chain-check.mjs
const BASE = 'http://localhost:5174'; // dev proxy（真实模式）
const GW_DIRECT = 'http://127.0.0.1:8080';
const DIRECT_USER = 'http://127.0.0.1:8081';
const DIRECT_INV = 'http://127.0.0.1:8084';

const stamp = Date.now();
const uid = String(stamp).slice(-8);

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${name}${ok ? '' : `   <<< ${detail}`}`);
}

async function call(base, method, path, { body, token, idem } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (idem) headers['Idempotency-Key'] = idem;
  const resp = await fetch(base + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null;
  try {
    json = await resp.json();
  } catch {
    /* 非 JSON */
  }
  return { http: resp.status, code: json?.code, msg: json?.msg, data: json?.data, json };
}
const api = (method, path, opts) => call(BASE, method, path, opts);

async function expect(name, method, path, want, opts) {
  const r = await api(method, path, opts);
  const ok = Array.isArray(want) ? want.includes(r.code) : r.code === want;
  check(name, ok, `http=${r.http} code=${r.code} msg=${r.msg ?? ''}`);
  return r;
}

console.log(`[env] proxy=${BASE}  gateway=${GW_DIRECT}`);

/* ------------------------------------------------------------------ 后端测试数据（管理端口子） */
const adminUser = `live_admin_${uid}`;
const adminPass = 'Live#12345';
const category = `LiveCate${uid}`;
const keyword = `LivePhone${uid}`;

const created = await call(DIRECT_USER, 'POST', '/api/v1/internal/users', {
  body: { username: adminUser, password: adminPass, nickname: 'Live Admin', roles: 'ADMIN' },
  idem: `live-admin-${uid}`,
});
console.log(`  [info] 直连 :8081 建管理员 code=${created.code}（1001=已存在可忽略）`);

const adminLogin = await api('POST', '/api/admin/auth/login', { body: { username: adminUser, password: adminPass } });
const adminToken = adminLogin.data?.accessToken;
check('管理端登录（真实接口）', adminLogin.code === 0 && !!adminToken, `code=${adminLogin.code}`);

const A = { token: adminToken };
const cat = await expect('建类目', 'POST', '/api/admin/category', 0, { ...A, body: { name: category, parentId: 0, sort: 1 }, idem: `cat-${uid}` });
const categoryId = cat.data?.id;

const prod = await expect('建商品（草稿）', 'POST', '/api/admin/product', 0, {
  ...A,
  body: {
    name: keyword,
    subtitle: 'live integration',
    categoryId,
    skus: [
      { skuCode: `LIVE-A-${uid}`, title: 'SKU A', spec: { color: 'black' }, price: 199.0, marketPrice: 299.0 },
      { skuCode: `LIVE-B-${uid}`, title: 'SKU B', spec: { color: 'white' }, price: 99.0, marketPrice: 159.0 },
    ],
  },
  idem: `prod-${uid}`,
});
const spuId = prod.data?.id;

const detail = await expect('商品详情拿 SKU', 'GET', `/api/admin/product/${spuId}`, 0, A);
const skus = detail.data?.skus ?? [];
const skuA = skus.find((s) => s.skuCode === `LIVE-A-${uid}`)?.skuId;
const skuB = skus.find((s) => s.skuCode === `LIVE-B-${uid}`)?.skuId;
check('两个 SKU 就绪', !!skuA && !!skuB, `skuA=${skuA} skuB=${skuB}`);

await expect('提交审核', 'POST', `/api/admin/product/${spuId}/submit-audit`, 0, { ...A, idem: `sub-${uid}` });
const approve = await expect('审核通过（自动上架）', 'POST', `/api/admin/product/${spuId}/approve`, 0, { ...A, idem: `apv-${uid}` });
check('审核后 auditStatus=2 / shelfStatus=1', approve.data?.auditStatus === 2 && approve.data?.shelfStatus === 1,
  JSON.stringify(approve.data ?? {}));

// 库存账本（管理端无库存写接口，直连 :8084 internal；已有记录不覆盖）
for (const [skuId, want, label] of [[skuA, 5, 'A'], [skuB, 0, 'B']]) {
  const g = await call(DIRECT_INV, 'GET', `/api/v1/inventory/stocks?skuIds=${skuId}`);
  const rows = Array.isArray(g.data) ? g.data : [];
  if (rows.length === 0) {
    const p = await call(DIRECT_INV, 'POST', '/api/v1/internal/inventory/stocks', {
      body: { skuId, availableStock: want },
      idem: `stock-${label}-${uid}`,
    });
    check(`库存账本 ${label} 新建`, p.code === 0, `code=${p.code}`);
  } else {
    check(`库存账本 ${label} 已存在（available=${rows[0].availableStock}）`, true);
  }
}

/* ------------------------------------------------------------------ 买家链路 */
const mobile = `139${uid}${String(stamp % 10000).padStart(4, '0')}`.slice(0, 11);
const buyerPass = 'Buyer#12345';

await expect('买家注册', 'POST', '/api/v1/auth/register', 0, {
  body: { mobile, code: '123456', password: buyerPass },
  idem: `reg-${uid}`,
});
const login = await expect('买家登录', 'POST', '/api/v1/auth/login', 0, { body: { username: mobile, password: buyerPass } });
const buyer = { token: login.data?.accessToken };
check('买家 accessToken 返回', !!buyer.token, '');

// 搜索（core-search 5s 全量同步，轮询）
let hit = false;
let searchTotal = -1;
for (let i = 0; i < 12 && !hit; i++) {
  const s = await api('GET', `/api/v1/search/products?q=${encodeURIComponent(keyword)}&pageSize=10`);
  searchTotal = s.data?.total ?? -1;
  if (s.code === 0 && searchTotal >= 1 && (s.data?.list ?? []).some((x) => x.name === keyword)) hit = true;
  if (!hit) await new Promise((r) => setTimeout(r, 3000));
}
check('搜索命中新建商品', hit, `total=${searchTotal}`);

const pd = await expect('商品详情（买家端）', 'GET', `/api/v1/products/${spuId}`, 0, buyer);
check('详情含两个 SKU', (pd.data?.skus ?? []).length === 2, `skus=${(pd.data?.skus ?? []).length}`);

// 地址
const addr = await expect('新增收货地址', 'POST', '/api/v1/user/addresses', 0, {
  ...buyer,
  body: { name: '联调买家', mobile: '13700001111', province: '广东省', city: '深圳市', district: '南山区', detail: '科技园南路88号', isDefault: true },
  idem: `addr-${uid}`,
});
const addressId = addr.data?.addressId;

// 加购 + 幂等重复
const cartKey = `cart-${uid}`;
await expect('加购', 'POST', '/api/v1/cart/add', 0, { ...buyer, body: { skuId: skuA, quantity: 2 }, idem: cartKey });
const cartDup = await expect('加购同幂等键重复', 'POST', '/api/v1/cart/add', 1005, { ...buyer, body: { skuId: skuA, quantity: 2 }, idem: cartKey });
check('重复幂等键 HTTP 409', cartDup.http === 409, `http=${cartDup.http}`);
const cartList = await expect('购物车列表', 'GET', '/api/v1/cart/list', 0, buyer);
check('购物车含目标 SKU', (cartList.data?.items ?? []).some((i) => i.skuId === skuA), `items=${(cartList.data?.items ?? []).length}`);

/* ---------------- 下单（0 基状态 0 + pay_deadline） ---------------- */
const orderBody = {
  items: [{ skuId: skuA, quantity: 1 }],
  addressId,
  receiverName: '联调买家',
  receiverMobile: '13700001111',
  receiverAddress: '广东省深圳市南山区科技园南路88号',
  remark: 'live',
};
const orderKey = `order-${uid}`;
const o1 = await expect('下单', 'POST', '/api/v1/orders', 0, { ...buyer, body: orderBody, idem: orderKey });
const order = o1.data;
check('初始状态 = 0（0 基 8 值）', order?.status === 0, `status=${order?.status}`);
check('pay_deadline 已生成', !!order?.payDeadline, `payDeadline=${order?.payDeadline}`);
check('拆单子单数 = 1', (order?.subOrders ?? []).length === 1, `sub=${(order?.subOrders ?? []).length}`);
const expectPay = Number(order.totalAmount) + Number(order.freightAmount) - Number(order.discountAmount);
check('金额自洽 payAmount = total + freight - discount', Math.abs(Number(order.payAmount) - expectPay) < 0.005,
  `total=${order.totalAmount} freight=${order.freightAmount} discount=${order.discountAmount} pay=${order.payAmount}`);

// 幂等重复下单 → 409/1005（不产生第二单）
const beforeList = await api('GET', '/api/v1/orders?page=1&pageSize=100', buyer);
const dup = await api('POST', '/api/v1/orders', { body: orderBody, token: buyer.token, idem: orderKey });
const afterList = await api('GET', '/api/v1/orders?page=1&pageSize=100', buyer);
check('重复下单 HTTP 409 / code 1005', dup.http === 409 && dup.code === 1005, `http=${dup.http} code=${dup.code} msg=${dup.msg}`);
check('重复下单未产生第二单', (afterList.data?.total ?? -1) === (beforeList.data?.total ?? -1),
  `before=${beforeList.data?.total} after=${afterList.data?.total}`);

/* ---------------- 支付链 ---------------- */
const prepay = await expect('prepay（金额=后端定额）', 'POST', '/api/v1/payment/prepay', 0, {
  ...buyer,
  body: { orderId: order.orderId, channel: 1 },
});
const amountOk = Math.abs(Number(prepay.data?.amount) - Number(order.payAmount)) < 0.005;
check('prepay.amount == order.payAmount', amountOk, `prepay=${prepay.data?.amount} order=${order.payAmount}`);

await expect('mock 支付', 'POST', '/api/v1/payment/mock/pay', 0, { ...buyer, body: { paymentId: prepay.data?.paymentId } });
const payStatus = await expect('支付状态 paid', 'GET', `/api/v1/payment/status/${order.orderId}`, 0, buyer);
check('payment status = paid', payStatus.data?.status === 'paid', `status=${payStatus.data?.status}`);

let paid = null;
for (let i = 0; i < 10 && paid !== 1; i++) {
  const d = await api('GET', `/api/v1/orders/${order.orderId}`, buyer);
  paid = d.data?.status;
  if (paid !== 1) await new Promise((r) => setTimeout(r, 1000));
}
check('支付后订单状态 = 1（待发货）', paid === 1, `status=${paid}`);

// 订单列表 0 基 Tab
const listAll = await api('GET', '/api/v1/orders?page=1&pageSize=10', buyer);
const listUnpaid = await api('GET', '/api/v1/orders?status=0&page=1', buyer);
check('订单列表（全部）', listAll.code === 0, `code=${listAll.code} total=${listAll.data?.total}`);
check('订单列表 status=0', listUnpaid.code === 0, `code=${listUnpaid.code}`);

/* ---------------- 领券 → 用券下单 ---------------- */
const now = new Date();
const coupon = await expect('管理端建券模板', 'POST', '/api/admin/coupon', 0, {
  ...A,
  body: {
    name: `LiveCoupon${uid}`,
    type: 1,
    value: 10,
    minAmount: 50,
    totalCount: 100,
    perLimit: 1,
    startTime: new Date(now.getTime() - 60000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
    endTime: new Date(now.getTime() + 7 * 86400000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
  },
  idem: `coup-${uid}`,
});
const templateId = coupon.data?.id;
const recv = await expect('买家领券', 'POST', '/api/v1/coupons/receive', 0, { ...buyer, body: { couponId: templateId } });
const recordId = recv.data?.couponId;
check('返回用户券记录 ID', !!recordId, `recordId=${recordId}`);

const preview = await expect('券试算', 'POST', '/api/v1/coupons/preview', 0, {
  ...buyer,
  body: { couponId: recordId, amount: ['199.00'] },
});
check('试算可用（eligible）', preview.data?.eligible === true, JSON.stringify(preview.data ?? {}));

const couponOrder = await expect('用券下单', 'POST', '/api/v1/orders', 0, {
  ...buyer,
  body: { ...orderBody, couponId: recordId, remark: 'coupon' },
  idem: `corder-${uid}`,
});
const co = couponOrder.data;
check('优惠减免 = 10（券面额）', Math.abs(Number(co.discountAmount) - 10) < 0.005, `discount=${co.discountAmount}`);
const expectPay2 = Number(co.totalAmount) + Number(co.freightAmount) - Number(co.discountAmount);
check('实付 = 总额 + 运费 - 减免', Math.abs(Number(co.payAmount) - expectPay2) < 0.005,
  `total=${co.totalAmount} discount=${co.discountAmount} pay=${co.payAmount}`);

/* ---------------- 库存不足 3002 ---------------- */
await expect('库存不足下单 → 3002', 'POST', '/api/v1/orders', 3002, {
  ...buyer,
  body: { items: [{ skuId: skuB, quantity: 9999 }], receiverName: '联调买家', receiverMobile: '13700001111', receiverAddress: '缺货街 1 号' },
  idem: `nostock-${uid}`,
});

/* ------------------------------------------------------------------ 汇总 */
const failed = results.filter((r) => !r.ok);
console.log('\n==================================================');
console.log(`  web-buyer 真实链路：通过 ${results.length - failed.length} / 失败 ${failed.length}`);
console.log('==================================================');
console.log(JSON.stringify({
  allPass: failed.length === 0,
  checks: results,
  orderId: order?.orderId,
  orderNo: order?.orderNo,
  statusAfterPay: paid,
  couponOrderId: co?.orderId,
  discount: co?.discountAmount,
}, null, 2));
process.exit(failed.length === 0 ? 0 : 1);
