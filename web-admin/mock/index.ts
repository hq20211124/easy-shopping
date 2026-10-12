/**
 * 开发期 Mock 中间件（Vite plugin）
 *
 * 契约对齐 plans/admin-backend.md：
 *  - 统一响应结构 { code, message, data, traceId }（2.5.2 接口通用规范），code=0 成功，401/403 走对应语义
 *  - 分页参数 pageNum/pageSize（pageSize ≤ 100 前端边界），返回 { list, total, pageNum, pageSize }
 *  - 仅当 VITE_USE_MOCK != false 时挂载；生产构建不包含本插件
 */
import type { Plugin } from 'vite';

class MockError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

type Body = Record<string, unknown>;
interface Ctx {
  method: string;
  path: string;
  query: URLSearchParams;
  body: Body;
}

type Handler = (ctx: Ctx) => unknown;
type Route = [method: string, pattern: RegExp, handler: Handler];

const AUTH_WHITELIST = [/^\/api\/admin\/auth\/(login|refresh|captcha)$/, /^\/api\/admin\/auth\/logout$/];

/* ---------------------------------------------------------------- 数据集 */

const PERMISSIONS = [
  'product:list', 'product:create', 'product:edit', 'product:on_shelf', 'product:off_shelf',
  'product:audit', 'product:export',
  'order:list', 'order:detail', 'order:remark', 'order:close', 'order:ship', 'order:price',
  'refund:list', 'refund:approve', 'refund:reject',
  'member:list', 'member:detail',
  'system:user', 'system:role', 'system:permission', 'system:dict', 'system:log',
  'report:view', 'cost:read',
];

const PROFILE = {
  userId: 10001,
  username: 'admin',
  nickname: '超级管理员',
  roles: ['super_admin'],
  permissions: PERMISSIONS,
  dataScope: 1,
  permVer: 12,
  deptId: 1,
  deptName: '平台运营部',
};

const AUDIT_STATUS = ['draft', 'auditing', 'on_shelf', 'rejected'] as const;
const SHELF_STATUS = ['off', 'on'] as const;

const PRODUCTS = Array.from({ length: 37 }, (_, i) => {
  const id = 1000 + i;
  return {
    id,
    spuCode: `SPU${String(id).padStart(8, '0')}`,
    title: `示例商品 ${i + 1} · ${['手机', '服装', '食品', '家居', '数码'][i % 5]}`,
    subtitle: `这是第 ${i + 1} 个用于联调的示例 SPU`,
    categoryId: 100 + (i % 6),
    categoryName: ['手机数码', '服饰鞋包', '食品生鲜', '家居家电', '美妆个护', '母婴玩具'][i % 6],
    brandName: ['华为', '小米', '耐克', '三只松鼠', '海尔'][i % 5],
    // 金额一律以「分」为单位传输（前端 9.6：禁止浮点运算金额）
    price: 9900 + i * 1350,
    linePrice: 19900 + i * 1350,
    costPrice: i % 3 === 0 ? 6000 + i * 900 : null, // 无 cost:read 权限时后端返回 ****（2.2.3 字段权限）
    stock: 100 + ((i * 37) % 900),
    sales30d: (i * 13) % 500,
    shelfStatus: SHELF_STATUS[i % 3 === 0 ? 0 : 1],
    auditStatus: AUDIT_STATUS[i % 4],
    auditRemark: i % 4 === 3 ? '主图不清晰，请重新上传后提交' : '',
    updatedAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')} 10:${String(i % 60).padStart(2, '0')}:00`,
    skus: [
      { skuCode: `${id}-A`, spec: '默认规格 / 黑色', price: 9900 + i * 1350, stock: 60 + (i % 50), enabled: true },
      { skuCode: `${id}-B`, spec: '高配 / 白色', price: 12900 + i * 1350, stock: 40 + (i % 30), enabled: i % 7 !== 0 },
    ],
    auditRecords: [
      { time: '2026-09-30 09:12:00', operator: '商家账号 merchant_01', action: '提交审核', opinion: '' },
      ...(i % 4 === 1
        ? [{ time: '2026-09-30 14:30:00', operator: '运营 operator_02', action: '审核通过', opinion: '资料齐全，准予上架' }]
        : []),
      ...(i % 4 === 3
        ? [{ time: '2026-10-01 11:05:00', operator: '运营 operator_02', action: '驳回', opinion: '主图不清晰，请重新上传后提交' }]
        : []),
    ],
  };
});

/** 订单 8 值状态（admin-backend 4.3：0-unpaid … 7-closed，任一端禁止自造） */
const ORDER_STATUS = [0, 1, 2, 3, 4, 5, 6, 7] as const;
const STATUS_CN = ['待付款', '待发货', '待收货', '已完成', '退款中', '已退款', '已取消', '已关闭'];

const ORDERS = Array.from({ length: 64 }, (_, i) => {
  const id = 500 + i;
  const status = ORDER_STATUS[i % 8];
  const items = PRODUCTS[i % PRODUCTS.length];
  return {
    id,
    orderNo: `2026100${(i % 9) + 1}${String(10000000 + i * 137).slice(0, 10)}`,
    orderStatus: status,
    statusText: STATUS_CN[status],
    buyerName: `买家${(i % 20) + 1}`,
    buyerPhone: `138****${String(1000 + i).slice(-4)}`,
    storeName: `示例店铺 ${(i % 5) + 1}`,
    itemCount: (i % 3) + 1,
    productTitle: items.title,
    productImage: '',
    // 金额单位：分
    totalAmount: items.price * ((i % 3) + 1) + 1000,
    freight: 800,
    discount: i % 4 === 0 ? 2000 : 0,
    payAmount: items.price * ((i % 3) + 1) + 1000 + 800 - (i % 4 === 0 ? 2000 : 0),
    payType: [1, 2, 3, 4][i % 4],
    payDeadline: '2026-10-12 12:00:00', // 支付超时以后端下发字段为准，前端不自定义分钟数
    createdAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')} ${String((i % 22) + 1).padStart(2, '0')}:20:00`,
    paidAt: status >= 1 && status !== 6 ? `2026-10-${String((i % 28) + 1).padStart(2, '0')} 13:00:00` : null,
    shippedAt: status >= 2 && status !== 6 ? `2026-10-${String((i % 28) + 1).padStart(2, '0')} 18:00:00` : null,
    receiver: { name: `收货人${(i % 20) + 1}`, phone: `159****${String(2000 + i).slice(-4)}`, address: `浙江省杭州市余杭区示例路 ${i + 1} 号` },
    remark: i % 5 === 0 ? '买家备注：请尽快发货' : '',
    adminRemarks: [] as Array<{ time: string; operator: string; content: string }>,
    version: 1,
  };
});

const AFTER_SALES = Array.from({ length: 9 }, (_, i) => ({
  id: 800 + i,
  afterSaleNo: `AS2026100${i + 1}000${i * 7 + 1}`,
  orderNo: ORDERS[i * 3].orderNo,
  type: [1, 2, 3][i % 3], // 1退款 2退货退款 3换货
  amount: ORDERS[i * 3].payAmount,
  reason: ['不想要了', '商品破损', '尺码不合适', '与描述不符'][i % 4],
  buyerNote: '申请仅退款，商品未拆封',
  status: [0, 1, 2, 3][i % 4], // 0待审核 1已同意 2已拒绝 3已完成
  createdAt: `2026-10-0${(i % 9) + 1} 15:30:00`,
  handleNote: '',
}));

const ADMIN_USERS = Array.from({ length: 12 }, (_, i) => ({
  id: 200 + i,
  username: `user_${String(i + 1).padStart(2, '0')}`,
  nickname: ['张运营', '李客服', '王财务', '赵商家'][i % 4] + (i + 1),
  deptName: ['平台运营部', '客户服务部', '财务部', '商家管理部'][i % 4],
  roles: i === 0 ? ['super_admin'] : [['operator'], ['cs_agent'], ['finance_clerk'], ['merchant']][i % 4],
  status: i === 11 ? 0 : 1,
  lastLoginAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')} 09:0${i % 10}:00`,
  createdAt: `2026-0${(i % 9) + 1}-1${i % 9} 10:00:00`,
}));

const ROLES = [
  { id: 1, code: 'super_admin', name: '超级管理员', dataScope: 1, status: 1, userCount: 1, remark: '系统内置，不可编辑/删除', builtin: true },
  { id: 2, code: 'operation_manager', name: '运营主管', dataScope: 3, status: 1, userCount: 3, remark: '本部门及以下数据' },
  { id: 3, code: 'operator', name: '运营专员', dataScope: 4, status: 1, userCount: 8, remark: '仅本人创建数据' },
  { id: 4, code: 'cs_agent', name: '客服专员', dataScope: 4, status: 1, userCount: 12, remark: '分配工单处理' },
  { id: 5, code: 'finance_clerk', name: '财务专员', dataScope: 4, status: 1, userCount: 4, remark: '仅本人处理的资金操作' },
  { id: 6, code: 'merchant', name: '商家', dataScope: 6, status: 1, userCount: 30, remark: '仅本店铺数据' },
];

const PERMISSION_TREE = [
  {
    key: 'menu:product', title: '商品管理', type: 'menu',
    children: [
      { key: 'menu:product:list', title: '商品列表', type: 'menu', children: [
        { key: 'product:list', title: 'product:list 查看商品', type: 'permission' },
        { key: 'product:create', title: 'product:create 发布商品', type: 'permission' },
        { key: 'product:edit', title: 'product:edit 编辑商品', type: 'permission' },
        { key: 'product:on_shelf', title: 'product:on_shelf 上架', type: 'permission' },
        { key: 'product:off_shelf', title: 'product:off_shelf 下架', type: 'permission' },
        { key: 'cost:read', title: 'cost:read 查看成本价', type: 'permission' },
      ] },
      { key: 'menu:product:audit', title: '商品审核', type: 'menu', children: [
        { key: 'product:audit', title: 'product:audit 审核商品', type: 'permission' },
      ] },
    ],
  },
  {
    key: 'menu:order', title: '订单管理', type: 'menu',
    children: [
      { key: 'menu:order:list', title: '订单列表', type: 'menu', children: [
        { key: 'order:list', title: 'order:list 查看订单', type: 'permission' },
        { key: 'order:detail', title: 'order:detail 订单详情', type: 'permission' },
        { key: 'order:remark', title: 'order:remark 订单备注', type: 'permission' },
        { key: 'order:close', title: 'order:close 关闭订单', type: 'permission' },
        { key: 'order:price', title: 'order:price 订单改价', type: 'permission' },
      ] },
      { key: 'menu:order:refund', title: '退款售后', type: 'menu', children: [
        { key: 'refund:list', title: 'refund:list 查看售后单', type: 'permission' },
        { key: 'refund:approve', title: 'refund:approve 同意退款', type: 'permission' },
        { key: 'refund:reject', title: 'refund:reject 拒绝退款', type: 'permission' },
      ] },
    ],
  },
  {
    key: 'menu:system', title: '系统设置', type: 'menu',
    children: [
      { key: 'menu:system:user', title: '用户管理', type: 'menu', children: [
        { key: 'system:user', title: 'system:user 管理员管理', type: 'permission' },
        { key: 'system:role', title: 'system:role 角色管理', type: 'permission' },
        { key: 'system:permission', title: 'system:permission 权限点管理', type: 'permission' },
      ] },
      { key: 'menu:system:dict', title: '数据字典', type: 'menu', children: [
        { key: 'system:dict', title: 'system:dict 字典管理', type: 'permission' },
      ] },
      { key: 'menu:system:log', title: '操作日志', type: 'menu', children: [
        { key: 'system:log', title: 'system:log 查看日志', type: 'permission' },
      ] },
    ],
  },
];

const ROLE_PERMISSIONS: Record<number, string[]> = {
  1: PERMISSIONS,
  2: PERMISSIONS.filter((p) => !p.startsWith('system:') && p !== 'cost:read'),
  3: PERMISSIONS.filter((p) => p.startsWith('product:') || p.startsWith('order:') || p.startsWith('report:')),
  4: PERMISSIONS.filter((p) => p.startsWith('order:') || p.startsWith('refund:') || p.startsWith('member:')),
  5: PERMISSIONS.filter((p) => p.startsWith('order:') || p.startsWith('refund:') || p.startsWith('report:')),
  6: PERMISSIONS.filter((p) => p.startsWith('product:') || p.startsWith('order:') || p.startsWith('shop:')),
};

const DICTS = [
  { id: 1, type: 'order_status', name: '订单状态', remark: '8 值状态机，与 admin-backend 4.3 严格一致', status: 1 },
  { id: 2, type: 'pay_type', name: '支付方式', remark: '1支付宝 2微信 3银行卡 4余额', status: 1 },
  { id: 3, type: 'audit_status', name: '商品审核状态', remark: 'draft/auditing/on_shelf/rejected', status: 1 },
  { id: 4, type: 'refund_reason', name: '退款原因', remark: '各端统一枚举', status: 1 },
  { id: 5, type: 'member_level', name: '会员等级', remark: '普通/银卡/金卡/钻石', status: 1 },
  { id: 6, type: 'logistics_company', name: '物流公司', remark: '', status: 0 },
];

const DICT_ITEMS: Record<string, Array<{ id: number; code: string; label: string; sort: number; status: number }>> = {
  order_status: ORDER_STATUS.map((code, i) => ({ id: i + 1, code: String(code), label: STATUS_CN[i], sort: i + 1, status: 1 })),
  pay_type: [
    { id: 1, code: '1', label: '支付宝', sort: 1, status: 1 },
    { id: 2, code: '2', label: '微信', sort: 2, status: 1 },
    { id: 3, code: '3', label: '银行卡(银联)', sort: 3, status: 1 },
    { id: 4, code: '4', label: '余额', sort: 4, status: 1 },
  ],
  audit_status: [
    { id: 1, code: 'draft', label: '待提交', sort: 1, status: 1 },
    { id: 2, code: 'auditing', label: '审核中', sort: 2, status: 1 },
    { id: 3, code: 'on_shelf', label: '已上架', sort: 3, status: 1 },
    { id: 4, code: 'rejected', label: '审核拒绝', sort: 4, status: 1 },
  ],
  refund_reason: [
    { id: 1, code: 'not_want', label: '不想要了', sort: 1, status: 1 },
    { id: 2, code: 'damaged', label: '商品破损', sort: 2, status: 1 },
    { id: 3, code: 'size', label: '尺码不合适', sort: 3, status: 1 },
    { id: 4, code: 'mismatch', label: '与描述不符', sort: 4, status: 1 },
  ],
  member_level: [
    { id: 1, code: '1', label: '普通会员', sort: 1, status: 1 },
    { id: 2, code: '2', label: '银卡', sort: 2, status: 1 },
    { id: 3, code: '3', label: '金卡', sort: 3, status: 1 },
    { id: 4, code: '4', label: '钻石', sort: 4, status: 1 },
  ],
  logistics_company: [
    { id: 1, code: 'SF', label: '顺丰速运', sort: 1, status: 1 },
    { id: 2, code: 'ZTO', label: '中通快递', sort: 2, status: 1 },
  ],
};

const OP_LOGS = Array.from({ length: 48 }, (_, i) => ({
  id: 300 + i,
  operator: ['张运营', '李客服', '王财务', '超级管理员'][i % 4],
  module: ['商品管理', '订单管理', '系统设置', '退款售后', '用户管理'][i % 5],
  action: ['上架商品', '关闭订单', '修改角色权限', '同意退款', '新增管理员', '修改订单备注'][i % 6],
  content: `对记录 ${1000 + i} 执行了操作`,
  ip: `192.168.${(i % 20) + 1}.${(i % 200) + 1}`,
  status: i % 9 === 0 ? 0 : 1,
  duration: 30 + (i % 200),
  createdAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')} ${String((i % 22) + 1).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}:00`,
  beforeData: { status: 'on_shelf', price: 9900 },
  afterData: { status: 'off_shelf', price: 8900 },
}));

/* ---------------------------------------------------------------- 工具 */

function paginate<T>(list: T[], query: URLSearchParams) {
  const pageNum = Math.max(1, Number(query.get('pageNum') ?? 1));
  const pageSize = Math.min(100, Math.max(1, Number(query.get('pageSize') ?? 10)));
  const start = (pageNum - 1) * pageSize;
  return { list: list.slice(start, start + pageSize), total: list.length, pageNum, pageSize };
}

function matchProduct(p: Record<string, unknown>, query: URLSearchParams) {
  const keyword = (query.get('keyword') ?? '').trim();
  const audit = query.get('auditStatus');
  const shelf = query.get('shelfStatus');
  return (
    (!keyword || String(p.title).includes(keyword) || String(p.spuCode).includes(keyword)) &&
    (!audit || p.auditStatus === audit) &&
    (!shelf || p.shelfStatus === shelf)
  );
}

function matchOrder(o: Record<string, unknown>, query: URLSearchParams) {
  const orderNo = (query.get('orderNo') ?? '').trim();
  const buyer = (query.get('buyer') ?? '').trim();
  const status = query.get('orderStatus');
  return (
    (!orderNo || String(o.orderNo).includes(orderNo)) &&
    (!buyer || String(o.buyerName).includes(buyer)) &&
    (status === null || status === '' || o.orderStatus === Number(status))
  );
}

/* ---------------------------------------------------------------- 路由 */

const routes: Route[] = [
  // 认证（admin-backend 2.5.1）
  ['POST', /^\/api\/admin\/auth\/login$/, (ctx) => {
    const { username, password } = ctx.body as { username?: string; password?: string };
    if (!username || !password) throw new MockError(400, '请输入账号和密码');
    if (password.length < 4) throw new MockError(400, '账号或密码错误');
    return { accessToken: `mock_at_${Date.now()}`, expiresIn: 7200, tokenType: 'Bearer' };
  }],
  ['POST', /^\/api\/admin\/auth\/refresh$/, () => ({ accessToken: `mock_at_${Date.now()}`, expiresIn: 7200 })],
  ['POST', /^\/api\/admin\/auth\/logout$/, () => ({ success: true })],
  ['GET', /^\/api\/admin\/auth\/info$/, () => PROFILE],
  ['GET', /^\/api\/admin\/auth\/captcha$/, () => ({ captchaId: 'mock-captcha', image: '' })],

  // 工作台（admin-backend 17.4）
  ['GET', /^\/api\/admin\/data\/workbench$/, () => ({
    gmv: 128654300, // 分
    orderCount: 3482,
    newMember: 517,
    pendingCount: 46,
    trend: Array.from({ length: 7 }, (_, i) => ({
      date: `10-0${i + 1}`,
      gmv: 92000000 + i * 5300000,
      orderCount: 2600 + i * 140,
    })),
  })],
  ['GET', /^\/api\/admin\/data\/todo$/, () => ({
    items: [
      { key: 'audit', title: '待审核商品', count: 18, path: '/product/list?auditStatus=auditing' },
      { key: 'ship', title: '待发货订单', count: 21, path: '/order/list?orderStatus=1' },
      { key: 'refund', title: '待处理退款', count: 5, path: '/order/refund' },
      { key: 'reply', title: '待回复工单', count: 2, path: '/dashboard' },
    ],
  })],

  // 商品（admin-backend 3.2.1）
  ['GET', /^\/api\/admin\/product\/list$/, (ctx) => paginate(PRODUCTS.filter((p) => matchProduct(p, ctx.query)), ctx.query)],
  ['GET', /^\/api\/admin\/product\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const item = PRODUCTS.find((p) => p.id === id);
    if (!item) throw new MockError(404, '商品不存在');
    return item;
  }],
  ['PUT', /^\/api\/admin\/product\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const item = PRODUCTS.find((p) => p.id === id);
    if (!item) throw new MockError(404, '商品不存在');
    Object.assign(item, ctx.body, { updatedAt: '2026-10-12 09:00:00' });
    return { success: true };
  }],
  ['POST', /^\/api\/admin\/product\/batch-(on|off)-shelf$/, (ctx) => {
    const ids = (ctx.body.ids as number[]) ?? [];
    if (ids.length === 0) throw new MockError(400, '请选择商品');
    if (ids.length > 500) throw new MockError(400, '批量操作单次上限500条，请分批提交');
    const shelf = ctx.path.includes('on-shelf') ? 'on' : 'off';
    PRODUCTS.forEach((p) => { if (ids.includes(p.id)) p.shelfStatus = shelf; });
    return { taskId: `batch_${Date.now()}`, successCount: ids.length, failCount: 0, failures: [] };
  }],

  // 订单（admin-backend 4.2.1）
  ['GET', /^\/api\/admin\/order\/list$/, (ctx) => paginate(ORDERS.filter((o) => matchOrder(o, ctx.query)), ctx.query)],
  ['GET', /^\/api\/admin\/order\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const item = ORDERS.find((o) => o.id === id);
    if (!item) throw new MockError(404, '订单不存在');
    return item;
  }],
  ['PUT', /^\/api\/admin\/order\/(\d+)\/remark$/, (ctx) => {
    const id = Number(ctx.path.match(/\/order\/(\d+)\//)?.[1]);
    const item = ORDERS.find((o) => o.id === id);
    if (!item) throw new MockError(404, '订单不存在');
    item.adminRemarks.push({ time: '2026-10-12 09:30:00', operator: PROFILE.nickname, content: String(ctx.body.content ?? '') });
    return { success: true };
  }],
  ['POST', /^\/api\/admin\/order\/(\d+)\/close$/, (ctx) => {
    const id = Number(ctx.path.match(/\/order\/(\d+)\//)?.[1]);
    const item = ORDERS.find((o) => o.id === id);
    if (!item) throw new MockError(404, '订单不存在');
    if (![0, 1].includes(item.orderStatus)) throw new MockError(400, '当前状态不允许关闭订单');
    item.orderStatus = 7;
    item.statusText = '已关闭';
    return { success: true };
  }],

  // 售后（admin-backend 4.2.2）
  ['GET', /^\/api\/admin\/after-sale\/list$/, (ctx) => {
    const status = ctx.query.get('status');
    const list = status === null || status === '' ? AFTER_SALES : AFTER_SALES.filter((a) => a.status === Number(status));
    return paginate(list, ctx.query);
  }],
  ['POST', /^\/api\/admin\/after-sale\/(\d+)\/(approve|reject)$/, (ctx) => {
    const id = Number(ctx.path.match(/\/after-sale\/(\d+)\//)?.[1]);
    const item = AFTER_SALES.find((a) => a.id === id);
    if (!item) throw new MockError(404, '售后单不存在');
    const action = ctx.path.endsWith('approve') ? 1 : 2;
    if (action === 2 && !ctx.body.reason) throw new MockError(400, '拒绝退款必须填写原因');
    item.status = action;
    item.handleNote = String(ctx.body.reason ?? ctx.body.note ?? '');
    return { success: true };
  }],

  // 系统管理（admin-backend 2.5.2）
  ['GET', /^\/api\/admin\/system\/user$/, (ctx) => {
    const keyword = (ctx.query.get('keyword') ?? '').trim();
    const list = ADMIN_USERS.filter((u) => !keyword || u.username.includes(keyword) || u.nickname.includes(keyword));
    return paginate(list, ctx.query);
  }],
  ['POST', /^\/api\/admin\/system\/user$/, (ctx) => {
    if (!ctx.body.username) throw new MockError(400, '用户名不能为空');
    ADMIN_USERS.unshift({ ...(ADMIN_USERS[0]), ...(ctx.body as object), id: 900 + ADMIN_USERS.length } as (typeof ADMIN_USERS)[number]);
    return { success: true };
  }],
  ['PUT', /^\/api\/admin\/system\/user\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const item = ADMIN_USERS.find((u) => u.id === id);
    if (!item) throw new MockError(404, '用户不存在');
    Object.assign(item, ctx.body);
    return { success: true };
  }],
  ['DELETE', /^\/api\/admin\/system\/user\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const idx = ADMIN_USERS.findIndex((u) => u.id === id);
    if (idx < 0) throw new MockError(404, '用户不存在');
    ADMIN_USERS.splice(idx, 1);
    return { success: true };
  }],
  ['GET', /^\/api\/admin\/system\/role$/, (ctx) => paginate(ROLES, ctx.query)],
  ['PUT', /^\/api\/admin\/system\/role\/(\d+)\/permissions$/, (ctx) => {
    const id = Number(ctx.path.match(/\/role\/(\d+)\//)?.[1]);
    if (!ROLES.some((r) => r.id === id)) throw new MockError(404, '角色不存在');
    ROLE_PERMISSIONS[id] = (ctx.body.permissions as string[]) ?? [];
    return { success: true };
  }],
  ['GET', /^\/api\/admin\/system\/role\/(\d+)\/permissions$/, (ctx) => {
    const id = Number(ctx.path.match(/\/role\/(\d+)\//)?.[1]);
    return { permissions: ROLE_PERMISSIONS[id] ?? [] };
  }],
  ['GET', /^\/api\/admin\/system\/permission\/tree$/, () => PERMISSION_TREE],
  ['GET', /^\/api\/admin\/system\/dict$/, (ctx) => {
    const keyword = (ctx.query.get('keyword') ?? '').trim();
    const list = DICTS.filter((d) => !keyword || d.name.includes(keyword) || d.type.includes(keyword));
    return paginate(list, ctx.query);
  }],
  ['GET', /^\/api\/admin\/system\/dict\/items$/, (ctx) => {
    const type = ctx.query.get('type') ?? '';
    return DICT_ITEMS[type] ?? [];
  }],
  ['GET', /^\/api\/admin\/system\/operation-log$/, (ctx) => {
    const operator = (ctx.query.get('operator') ?? '').trim();
    const module = (ctx.query.get('module') ?? '').trim();
    const list = OP_LOGS.filter(
      (l) => (!operator || l.operator.includes(operator)) && (!module || l.module === module),
    );
    return paginate(list, ctx.query);
  }],
];

/* ---------------------------------------------------------------- 插件 */

function readBody(req: NodeJS.ReadableStream): Promise<Body> {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (chunk) => { raw += chunk; });
    req.on('end', () => {
      if (!raw) return resolve({});
      try { resolve(JSON.parse(raw) as Body); } catch { resolve({}); }
    });
  });
}

export function mockApiPlugin(): Plugin {
  return {
    name: 'admin-mock-api',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = req.url ?? '';
        if (!url.startsWith('/api/')) return next();
        const [pathname = '', search = ''] = url.split('?');
        const method = (req.method ?? 'GET').toUpperCase();

        const respond = (status: number, payload: unknown) => {
          res.statusCode = status;
          res.setHeader('Content-Type', 'application/json; charset=utf-8');
          res.setHeader('Access-Control-Allow-Origin', '*');
          res.end(JSON.stringify(payload));
        };

        try {
          if (!AUTH_WHITELIST.some((re) => re.test(pathname))) {
            const auth = req.headers.authorization;
            if (!auth || !auth.startsWith('Bearer mock_at_')) {
              return respond(401, { code: 401, message: '登录状态已失效，请重新登录', data: null, traceId: 'mock' });
            }
          }
          const route = routes.find(([m, pattern]) => m === method && pattern.test(pathname));
          if (!route) return respond(404, { code: 404, message: `Mock 未实现的接口: ${method} ${pathname}`, data: null, traceId: 'mock' });
          const body = ['POST', 'PUT', 'PATCH'].includes(method) ? await readBody(req) : {};
          const data = route[2]({ method, path: pathname, query: new URLSearchParams(search), body });
          // 模拟网络延迟，便于观察 loading 态
          await new Promise((r) => setTimeout(r, 80 + Math.random() * 120));
          return respond(200, { code: 0, message: 'ok', data, traceId: `mock_${Date.now()}` });
        } catch (err) {
          if (err instanceof MockError) {
            return respond(err.status, { code: err.status, message: err.message, data: null, traceId: 'mock' });
          }
          return respond(500, { code: 500, message: (err as Error).message, data: null, traceId: 'mock' });
        }
      });
    },
  };
}
