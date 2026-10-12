// 活体联调：web-admin 真实模式 7 项待联调验证（VITE_USE_MOCK=false，全部走 :5173 dev proxy → :8080 网关）
// 清单出处：web-admin/docs/api-contract-check.md §四「待联调验证项」
// 用法：node web-admin/scripts/live-check.mjs   （前置：start-all.ps1 已起后端）
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const BASE = 'http://localhost:5173'; // dev proxy（真实模式）
const DIRECT_USER = 'http://127.0.0.1:8081';

const stamp = Date.now();
const uid = String(stamp).slice(-8);
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${name}${ok ? '' : `   <<< ${detail}`}`);
}
async function call(method, pathName, { body, token, idem, cookie, base = BASE, headers = {} } = {}) {
  const h = { 'Content-Type': 'application/json', ...headers };
  if (token) h['Authorization'] = `Bearer ${token}`;
  if (idem) h['Idempotency-Key'] = idem;
  if (cookie) h.Cookie = cookie;
  const resp = await fetch(base + pathName, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  let json = null;
  try {
    json = await resp.json();
  } catch {
    /* 非 JSON */
  }
  const setCookie = resp.headers.getSetCookie?.() ?? [];
  return { http: resp.status, code: json?.code, msg: json?.msg, data: json?.data, json, setCookie };
}

console.log(`[env] admin dev proxy=${BASE}  step=${uid}`);

/* ------------------------------------------------------------------ 0. 建号 + 登录 */
const adminUser = `liveadm_${uid}`;
await call('POST', '/api/v1/internal/users', {
  base: DIRECT_USER,
  body: { username: adminUser, password: 'Live#12345', nickname: 'Live Admin', roles: 'ADMIN' },
  idem: `adm-${uid}`,
});
const login = await call('POST', '/api/admin/auth/login', { body: { username: adminUser, password: 'Live#12345' } });
const token = login.data?.accessToken;
const rtCookie = (login.setCookie.find((c) => c.startsWith('ADMIN_RT=')) ?? '').split(';')[0];
check('0. 管理员登录（经 :5173 代理，拿到 accessToken + ADMIN_RT Cookie）',
  login.code === 0 && !!token && !!rtCookie,
  `code=${login.code} cookie=${rtCookie ? 'yes' : 'no'}`);
const A = { token };

// 测试买家 + 订单数据（后续 ⑤⑦ 用）
const buyerMobile = `137${uid}${String(stamp % 10000).padStart(4, '0')}`.slice(0, 11);
await call('POST', '/api/v1/auth/register', { body: { mobile: buyerMobile, code: '123456', password: 'Buyer#12345' }, idem: `reg-${uid}` });
const buyerLogin = await call('POST', '/api/v1/auth/login', { body: { username: buyerMobile, password: 'Buyer#12345' } });
const buyer = { token: buyerLogin.data?.accessToken };

const cat = await call('POST', '/api/admin/category', { ...A, body: { name: `LiveCate${uid}`, parentId: 0, sort: 1 }, idem: `cat-${uid}` });
const categoryId = cat.data?.id;
const mkProduct = (tag, price) =>
  call('POST', '/api/admin/product', {
    ...A,
    idem: `p-${tag}-${uid}`,
    body: {
      name: `LiveProd${tag}${uid}`,
      subtitle: 'live',
      categoryId,
      skus: [
        { skuCode: `${tag}-A-${uid}`, title: 'A', spec: { color: 'black' }, price, marketPrice: price + 100 },
        { skuCode: `${tag}-B-${uid}`, title: 'B', spec: { color: 'white' }, price: price / 2, marketPrice: price / 2 + 50 },
      ],
    },
  });

const p1 = await mkProduct('EDIT', 200); // ④ 用：草稿可编辑
const editSpu = p1.data?.id;
const p2 = await mkProduct('APPROVE', 300); // ③ 用：要走到审核通过
const approveSpu = p2.data?.id;

async function skusOf(spuId) {
  const d = await call('GET', `/api/admin/product/${spuId}`, A);
  return d.data?.skus ?? [];
}
async function pricesOf(ids) {
  const r = await call('GET', `/api/v1/price/skus?ids=${ids.join(',')}`, A);
  return new Map((Array.isArray(r.data) ? r.data : []).map((x) => [x.skuId, x]));
}

/* ================================================================ ① 401 → 单飞 refresh 时序 */
console.log('\n===== ① 401 → refresh → 重放 时序 =====');
const bad = await call('GET', '/api/admin/auth/info', { token: 'not-a-valid-jwt' });
check('①-a 无效 token → HTTP 401 + 业务码 1002/1003', bad.http === 401 && [1002, 1003].includes(bad.code),
  `http=${bad.http} code=${bad.code} msg=${bad.msg}`);

const refresh1 = await call('POST', '/api/admin/auth/refresh', { cookie: rtCookie, body: {} });
const newToken = refresh1.data?.accessToken;
const rotatedCookie = (refresh1.setCookie.find((c) => c.startsWith('ADMIN_RT=')) ?? '').split(';')[0];
check('①-b 带 ADMIN_RT Cookie refresh → 新 accessToken', refresh1.code === 0 && !!newToken && newToken !== token,
  `code=${refresh1.code}`);
check('①-b2 refresh 轮换 Cookie 已下发', !!rotatedCookie && rotatedCookie !== rtCookie, '');

const replay = await call('GET', '/api/admin/auth/info', { token: newToken });
check('①-c 用新 token 重放原请求 → 200 code 0', replay.http === 200 && replay.code === 0,
  `http=${replay.http} code=${replay.code}`);

// 并发刷新：单飞由前端 refreshing Promise 保证（浏览器侧另测），服务端只验证多次 refresh 的可重放性
const [rf2, rf3] = await Promise.all([
  call('POST', '/api/admin/auth/refresh', { cookie: rotatedCookie, body: {} }),
  call('POST', '/api/admin/auth/refresh', { cookie: rotatedCookie, body: {} }),
]);
check('①-d 同一 Cookie 并发 refresh 不 5xx（前端只放行一次）',
  [rf2, rf3].every((r) => r.http < 500), `codes=${rf2.code}/${rf3.code} http=${rf2.http}/${rf3.http}`);

const dead = await call('POST', '/api/admin/auth/refresh', { cookie: 'ADMIN_RT=invalid-token', body: {} });
check('①-e refresh 自身 401/1003（前端据此登出）', dead.http === 401 && [1002, 1003].includes(dead.code),
  `http=${dead.http} code=${dead.code} msg=${dead.msg}`);

/* ================================================================ ② getMenus 空树回退静态菜单 */
console.log('\n===== ② getMenus 空树回退 =====');
const menus = await call('GET', '/api/admin/auth/menus', A);
check('②-a 真实 getMenus 返回菜单树（code 0 + 非空数组）',
  menus.code === 0 && Array.isArray(menus.data) && menus.data.length > 0,
  `code=${menus.code} len=${Array.isArray(menus.data) ? menus.data.length : 'n/a'}`);
const layoutSrc = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '../src/components/BasicLayout.tsx'), 'utf8');
check('②-b BasicLayout 空/异常回退静态 MENUS（remoteMenus?.length ? ... : MENUS）',
  /remoteMenus\?\.length\s*\?\s*toPermissionMenus\(remoteMenus\)\s*:\s*MENUS/.test(layoutSrc), '');
check('②-c 菜单查询失败不重试（retry:false，避免死循环）', /retry:\s*false/.test(layoutSrc), '');

/* ================================================================ ③ 幂等头经网关透传（审核 / 发货） */
console.log('\n===== ③ 幂等头经网关透传 =====');
await expectCode('③-a 提交审核（带幂等键）', 'POST', `/api/admin/product/${approveSpu}/submit-audit`, 0, { ...A, idem: `sub-${uid}` });
const approveKey = `apv-${uid}`;
const apv1 = await call('POST', `/api/admin/product/${approveSpu}/approve`, { ...A, idem: approveKey });
check('③-b 审核通过首次成功', apv1.code === 0, `code=${apv1.code} msg=${apv1.msg}`);
const apv2 = await call('POST', `/api/admin/product/${approveSpu}/approve`, { ...A, idem: approveKey });
check('③-c 同键重复审核 → 409/1005（幂等生效）',
  apv2.http === 409 && apv2.code === 1005, `http=${apv2.http} code=${apv2.code} msg=${apv2.msg}`);

// 发货：admin-bff → core-order 转发链路（web-admin closeOrder 同链路）
const mkOrder = async (idem) => {
  const r = await call('POST', '/api/v1/orders', {
    ...buyer,
    idem,
    body: { items: [{ skuId: (await skusOf(approveSpu)).find((s) => s.skuCode === `APPROVE-A-${uid}`)?.skuId ?? 0, quantity: 1 }],
      receiverName: '联调', receiverMobile: '13700001111', receiverAddress: '联调路 1 号' },
  });
  return r.data;
};
// 库存账本（直连 internal，管理端无库存写口）
const approveSkus = await skusOf(approveSpu);
const skuA2 = approveSkus.find((s) => s.skuCode === `APPROVE-A-${uid}`)?.skuId;
{
  const g = await call('GET', `/api/v1/inventory/stocks?skuIds=${skuA2}`, { base: 'http://127.0.0.1:8084' });
  if (!Array.isArray(g.data) || g.data.length === 0) {
    await call('POST', '/api/v1/internal/inventory/stocks', {
      base: 'http://127.0.0.1:8084', body: { skuId: skuA2, availableStock: 5 }, idem: `stk-${uid}`,
    });
  }
}
const order1 = await mkOrder(`o1-${uid}`); // 状态 0（待付款）
const order2 = await mkOrder(`o2-${uid}`); // 下面支付后发货

// 订单 2 走到「待发货」：prepay + mock pay
const pre = await call('POST', '/api/v1/payment/prepay', { ...buyer, body: { orderId: order2?.orderId, channel: 1 } });
await call('POST', '/api/v1/payment/mock/pay', { ...buyer, body: { paymentId: pre.data?.paymentId } });
let st2 = null;
for (let i = 0; i < 10 && st2 !== 1; i++) {
  const d = await call('GET', `/api/v1/orders/${order2?.orderId}`, buyer);
  st2 = d.data?.status;
  if (st2 !== 1) await new Promise((r) => setTimeout(r, 800));
}
check('③-d 订单 2 已支付 → 状态 1（待发货）', st2 === 1, `status=${st2}`);

const shipKey = `ship-${uid}`;
const ship1 = await call('POST', `/api/admin/order/${order2?.orderId}/ship`, { ...A, body: { trackingNo: 'LIVE123456' }, idem: shipKey });
check('③-e 发货首次成功（幂等键经网关 + admin-bff → core-order）', ship1.code === 0, `code=${ship1.code} msg=${ship1.msg}`);
const ship2 = await call('POST', `/api/admin/order/${order2?.orderId}/ship`, { ...A, body: { trackingNo: 'LIVE123456' }, idem: shipKey });
check('③-f 同键重复发货 → 409/1005', ship2.http === 409 && ship2.code === 1005,
  `http=${ship2.http} code=${ship2.code} msg=${ship2.msg}`);
const noKey = await call('POST', `/api/admin/order/${order2?.orderId}/close`, { ...A, body: { reason: 'no key' } });
check('③-g 写接口缺幂等键 → 1001（拦截器在该链路生效）', noKey.code === 1001, `http=${noKey.http} code=${noKey.code} msg=${noKey.msg}`);

/* ================================================================ ④ updateProduct 全量替换不覆盖价格 */
console.log('\n===== ④ updateProduct SKU 全量替换 =====');
const detail1 = await call('GET', `/api/admin/product/${editSpu}`, A);
const skus1 = detail1.data?.skus ?? [];
const ids1 = skus1.map((s) => s.skuId);
const priceBefore = await pricesOf(ids1);
const beforeVals = ids1.map((i) => priceBefore.get(i)?.price);
check('④-a 改前 core-price 价（200/100）', beforeVals.length === 2 && Number(beforeVals[0]) === 200 && Number(beforeVals[1]) === 100,
  JSON.stringify(beforeVals));

// 模拟 web-admin 适配层「未改价」提交：SKU 全量 + 价格回填 core-price 现价
const putBody = (priceOverride) => ({
  name: detail1.data.name,
  subtitle: detail1.data.subtitle,
  categoryId: detail1.data.categoryId,
  mainImage: detail1.data.mainImage,
  images: detail1.data.images?.join(',') || undefined,
  video: detail1.data.video,
  detail: detail1.data.detail,
  skus: skus1.map((s) => ({
    skuCode: s.skuCode,
    title: s.title,
    spec: s.specs,
    images: (s.images ?? []).join(',') || undefined,
    price: priceOverride ?? priceBefore.get(s.skuId)?.price,
    // 与修正后前端一致：core-price 约束 marketPrice ≥ price，改高价时抬 marketPrice
    marketPrice: Math.max(priceBefore.get(s.skuId)?.marketPrice ?? 0, priceOverride ?? priceBefore.get(s.skuId)?.price ?? 0),
    costPrice: s.costPrice ?? undefined,
  })),
});
const upd1 = await call('PUT', `/api/admin/product/${editSpu}`, { ...A, body: putBody(undefined), idem: `u1-${uid}` });
check('④-b 未改价提交成功', upd1.code === 0, `code=${upd1.code} msg=${upd1.msg}`);

const detail2 = await call('GET', `/api/admin/product/${editSpu}`, A);
const skus2 = detail2.data?.skus ?? [];
const priceAfter = await pricesOf(skus2.map((s) => s.skuId));
const afterVals = skus2.map((s) => priceAfter.get(s.skuId)?.price);
check('④-c 未编辑 SKU 价格未被覆盖（仍 200/100）',
  afterVals.length === 2 && Number(afterVals[0]) === 200 && Number(afterVals[1]) === 100, JSON.stringify(afterVals));
check('④-d SKU 数量与 skuCode 保持（全量替换不丢 SKU）',
  skus2.length === 2 && skus2.every((s) => skus1.some((o) => o.skuCode === s.skuCode)),
  JSON.stringify(skus2.map((s) => s.skuCode)));

// 商品级改价（编辑框改价 → 应用到全部 SKU，前端注释已声明该口径）
const upd2 = await call('PUT', `/api/admin/product/${editSpu}`, { ...A, body: putBody(250), idem: `u2-${uid}` });
const detail3 = await call('GET', `/api/admin/product/${editSpu}`, A);
const price3 = await pricesOf((detail3.data?.skus ?? []).map((s) => s.skuId));
const vals3 = (detail3.data?.skus ?? []).map((s) => price3.get(s.skuId)?.price);
check('④-e 商品级改价口径成立（改价落到全部 SKU）',
  upd2.code === 0 && vals3.every((v) => Number(v) === 250), JSON.stringify(vals3));

/* ================================================================ ⑤ orderNo 精确查询 */
console.log('\n===== ⑤ listOrders orderNo 精确匹配 =====');
const orderNo = order1?.orderNo;
const qExact = await call('GET', `/api/admin/order/list?pageNum=1&pageSize=10&orderNo=${orderNo}`, A);
check('⑤-a 精确 orderNo 命中 1 条', qExact.code === 0 && qExact.data?.total === 1 &&
  qExact.data?.list?.[0]?.orderNo === orderNo, `total=${qExact.data?.total}`);
const prefix = String(orderNo).slice(0, 16);
const qPrefix = await call('GET', `/api/admin/order/list?pageNum=1&pageSize=10&orderNo=${prefix}`, A);
check('⑤-b 前缀（截断）查询 0 条 → 确认为精确匹配', qPrefix.code === 0 && qPrefix.data?.total === 0,
  `total=${qPrefix.data?.total}`);
const lastDigit = String(orderNo).slice(-1);
const wrongDigit = lastDigit === '9' ? '8' : '9'; // 保证与原末位不同，避免碰撞
const qWrong = await call('GET', `/api/admin/order/list?pageNum=1&pageSize=10&orderNo=${String(orderNo).slice(0, -1)}${wrongDigit}`, A);
check('⑤-c 改末位查询 0 条', qWrong.code === 0 && qWrong.data?.total === 0, `total=${qWrong.data?.total}`);
const orderListSrc = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '../src/pages/order/list.tsx'), 'utf8');
check('⑤-d 前端筛选项已标注「精确查询」', /精确/.test(orderListSrc), '');

/* ================================================================ ⑥ 日志 keywords / 时间互斥 */
console.log('\n===== ⑥ 操作日志 关键字与时间互斥 =====');
const kw = await call('GET', `/api/admin/system/operation-log?pageNum=1&pageSize=5&operator=${adminUser}&createdAtStart=2020-01-01T00:00:00%2B08:00&createdAtEnd=2020-01-02T00:00:00%2B08:00`, A);
check('⑥-a 关键字 + 远古时间范围 → 关键字优先（返回命中）',
  kw.code === 0 && (kw.data?.total ?? 0) > 0, `code=${kw.code} total=${kw.data?.total}`);
const timeOnly = await call('GET', '/api/admin/system/operation-log?pageNum=1&pageSize=5&createdAtStart=2020-01-01T00:00:00%2B08:00&createdAtEnd=2020-01-02T00:00:00%2B08:00', A);
check('⑥-b 仅时间范围 → 按时间过滤（0 条）', timeOnly.code === 0 && timeOnly.data?.total === 0,
  `total=${timeOnly.data?.total}`);
const kwOnly = await call('GET', `/api/admin/system/operation-log?pageNum=1&pageSize=5&operator=${adminUser}`, A);
check('⑥-c 仅关键字 → 命中', kwOnly.code === 0 && (kwOnly.data?.total ?? 0) > 0, `total=${kwOnly.data?.total}`);
const badTime = await call('GET', '/api/admin/system/operation-log?pageNum=1&pageSize=5&createdAtStart=2020/01/01 00:00:00', A);
check('⑥-d 非 ISO 时间格式 → 1001（前端已做 ISO 序列化）', badTime.code === 1001, `http=${badTime.http} code=${badTime.code}`);
const logSrc = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '../src/pages/system/log.tsx'), 'utf8');
check('⑥-e 前端时间已序列化为 ISO-8601（toIsoDateTime）',
  readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '../src/api/system.ts'), 'utf8').includes('toIsoDateTime'), '');
check('⑥-f 页面有「关键字与时间互斥」提示', /互斥/.test(logSrc), '');

/* ================================================================ ⑦ 状态机非法流转错误码展示 */
console.log('\n===== ⑦ 订单状态机非法流转错误码 =====');
const shipUnpaid = await call('POST', `/api/admin/order/${order1?.orderId}/ship`, { ...A, body: { trackingNo: 'X' }, idem: `ship0-${uid}` });
check('⑦-a 待付款(0) 直接发货 → 4002', shipUnpaid.code === 4002, `http=${shipUnpaid.http} code=${shipUnpaid.code} msg=${shipUnpaid.msg}`);
check('⑦-b 4002 带后端中文文案（前端 msg 优先展示）', typeof shipUnpaid.msg === 'string' && shipUnpaid.msg.length > 0, String(shipUnpaid.msg));
const closeUnpaid = await call('POST', `/api/admin/order/${order1?.orderId}/close`, { ...A, body: { reason: 'test' }, idem: `close0-${uid}` });
check('⑦-c 待付款(0) 关闭合法 → 0（对照组）', closeUnpaid.code === 0, `code=${closeUnpaid.code} msg=${closeUnpaid.msg}`);
const closeAgain = await call('POST', `/api/admin/order/${order1?.orderId}/close`, { ...A, body: { reason: 'again' }, idem: `close1-${uid}` });
check('⑦-d 终态(已关闭 7) 再关单 → 4002（终态拦截）', closeAgain.code === 4002,
  `http=${closeAgain.http} code=${closeAgain.code} msg=${closeAgain.msg}`);
const closeShipped = await call('POST', `/api/admin/order/${order2?.orderId}/close`, { ...A, body: { reason: 'x' }, idem: `closeS-${uid}` });
check('⑦-e 已发货(2) 关单 → 4002（白名单外流转）', closeShipped.code === 4002,
  `code=${closeShipped.code} msg=${closeShipped.msg}`);
const markPaid = await call('POST', `/api/admin/order/${order1?.orderId}/mark-paid`, { ...A, body: {}, idem: `mp-${uid}` });
const orderApiSrc = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '../src/api/order.ts'), 'utf8');
check('⑦-f mark-paid 前后端一致缺失（契约表已更正，无孤儿端点/调用）',
  markPaid.http === 404 && !/markOrderPaid|mark-paid/.test(orderApiSrc),
  `http=${markPaid.http} 前端含调用=${/markOrderPaid|mark-paid/.test(orderApiSrc)}`);
const reqSrc = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '../src/api/request.ts'), 'utf8');
check('⑦-g 前端 ERROR_CODE_TEXT 含 4002 兜底文案', /4002:\s*'订单状态错误'/.test(reqSrc), '');
check('⑦-h 前端统一错误提示走 extractMessage（后端 msg 优先）', /function extractMessage/.test(reqSrc), '');

/* ------------------------------------------------------------------ 汇总 */
const failed = results.filter((r) => !r.ok);
console.log('\n==================================================');
console.log(`  web-admin 7 项联调：通过 ${results.length - failed.length} / 失败 ${failed.length}`);
console.log('==================================================');
console.log(JSON.stringify({
  allPass: failed.length === 0,
  checks: results,
  orderNoUnpaid: order1?.orderNo,
  orderNoPaid: order2?.orderNo,
}, null, 2));
process.exit(failed.length === 0 ? 0 : 1);

/* ------------------------------------------------------------------ 工具 */
async function expectCode(name, method, p, want, opts) {
  const r = await call(method, p, opts);
  check(name, r.code === want, `http=${r.http} code=${r.code} msg=${r.msg}`);
  return r;
}
