/**
 * 开发期 Mock 中间件（Vite plugin）
 *
 * 契约对齐 **server 实际实现**（mock 数据形状 = 真实后端下发形状，联调切 VITE_USE_MOCK=false 零改码）：
 *  - 统一响应结构 { code, msg, data, traceId }（server/common ApiResponse：字段是 msg 不是 message）
 *  - 分页 PageResult{total, pageNum, pageSize, list}（server/common PageResult）
 *  - 商品 = core-product：列表入参 `page`（非 pageNum），auditStatus/shelfStatus 为 0 基数字，金额 BigDecimal「元」
 *  - SKU 价 = core-price：GET /api/v1/price/skus?ids= → [{skuId, price, marketPrice}]
 *  - 订单 = core-order AdminOrderVO：status/statusText/receiverName/receiverMobile/payTime/items/children/version
 *  - 权限码/菜单 = admin-bff DataInitializer 注册表（25 个码，super_admin 全量）
 *  - 写接口幂等：POST/PUT/PATCH/DELETE 需 Idempotency-Key（缺失 1001、重复 1005 —— IdempotentInterceptor 语义）
 *  - 仅当 VITE_USE_MOCK != false 时挂载；生产构建不包含本插件
 *
 * 后端未实现的接口（订单备注 / 售后）保留在 mock 内，注释标明 ❌，真实后端联调时会 404（见 docs/api-contract-check.md）
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

/** 幂等键覆盖的写方法（IdempotentInterceptor 只拦截写请求；认证域豁免） */
const IDEMPOTENT_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const IDEMPOTENT_SKIP = /^\/api\/admin\/auth\//;

/* ---------------------------------------------------------------- 数据集 */

/**
 * 权限码 = admin-bff DataInitializer 注册表（25 个，super_admin 全量持有）
 * 说明：无 product:on_shelf / product:off_shelf / order:remark / refund:approve / refund:reject —— 前端已改用
 * product:edit / order:detail / refund:list（注册表为准，前端禁止另造）
 */
const PERMISSIONS = [
  'dashboard', 'product', 'product:list', 'order', 'order:list', 'order:refund', 'system',
  'user:list', 'user:roles', 'system:dict', 'system:log',
  'product:create', 'product:edit', 'product:audit',
  'order:detail', 'order:ship', 'order:close', 'refund:list',
  'system:permission', 'system:dept', 'system:config', 'system:user', 'system:role',
  'cost:read', 'report:view',
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

/**
 * 菜单树 = RbacService.MenuNode 形状（key/label/path/permission/children；permission 为 null 表示无门槛）
 * 与 DataInitializer 种子一致：dashboard 无 requiredPerm 恒可见；type=permission 的权限点不进菜单树
 */
const MENUS = [
  { key: 'dashboard', label: '工作台', path: '/dashboard', permission: null, children: null },
  {
    key: 'product', label: '商品运营', path: '/product/list', permission: 'product:list',
    children: [
      { key: 'product:list', label: '商品列表', path: '/product/list', permission: 'product:list', children: null },
    ],
  },
  {
    key: 'order', label: '订单管理', path: '/order/list', permission: 'order:list',
    children: [
      { key: 'order:list', label: '订单列表', path: '/order/list', permission: 'order:list', children: null },
      { key: 'order:refund', label: '退款/售后', path: '/order/refund', permission: 'refund:list', children: null },
    ],
  },
  {
    key: 'system', label: '系统管理', path: '/system', permission: 'system:user',
    children: [
      { key: 'user:list', label: '用户列表', path: '/user/list', permission: 'system:user', children: null },
      { key: 'user:roles', label: '角色权限', path: '/user/roles', permission: 'system:role', children: null },
      { key: 'system:dict', label: '数据字典', path: '/system/dict', permission: 'system:dict', children: null },
      { key: 'system:log', label: '操作日志', path: '/system/log', permission: 'system:log', children: null },
    ],
  },
];

/**
 * 商品 = core-product Spu（金额 BigDecimal「元」，Instant 用 ISO-8601 字符串）
 * auditStatus 0-draft 1-auditing 2-on_shelf 3-rejected；shelfStatus 0-下架 1-上架
 */
const AUDIT_STATUS = [0, 1, 2, 3] as const;
const SHELF_STATUS = [1, 0] as const;

interface MockSku {
  skuId: number;
  spuId: number;
  skuCode: string;
  title: string;
  specs: Record<string, string>;
  images: string[];
  status: number;
  costPrice: number | null;
}

interface MockSpu {
  id: number;
  spuCode: string;
  name: string;
  subtitle: string;
  categoryId: number;
  mainImage: string;
  images: string;
  video: string;
  detail: string;
  /** 冗余展示价 = min(sku.price)，单位「元」（core-price 为 SKU 价唯一归属） */
  minPrice: number;
  marketPrice: number;
  salesCount: number;
  auditStatus: number;
  shelfStatus: number;
  auditRemark: string;
  deleted: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
  skus: MockSku[];
}

const PRODUCTS: MockSpu[] = Array.from({ length: 37 }, (_, i) => {
  const id = 1000 + i;
  const priceYuan = 99 + i * 13.5;
  const marketYuan = 199 + i * 13.5;
  return {
    id,
    spuCode: `spu-${id}`,
    name: `示例商品 ${i + 1} · ${['手机', '服装', '食品', '家居', '数码'][i % 5]}`,
    subtitle: `这是第 ${i + 1} 个用于联调的示例 SPU`,
    categoryId: 100 + (i % 6),
    mainImage: `https://picsum.photos/seed/spu${id}/400`,
    images: `https://picsum.photos/seed/spu${id}/400,https://picsum.photos/seed/spu${id}b/400`,
    video: '',
    detail: '<p>富文本详情（mock）</p>',
    minPrice: priceYuan,
    marketPrice: marketYuan,
    salesCount: (i * 13) % 500,
    auditStatus: AUDIT_STATUS[i % 4],
    shelfStatus: SHELF_STATUS[i % 3 === 0 ? 0 : 1],
    auditRemark: i % 4 === 3 ? '主图不清晰，请重新上传后提交' : '',
    deleted: false,
    version: 1,
    createdAt: `2026-09-${String((i % 28) + 1).padStart(2, '0')}T09:00:00Z`,
    updatedAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')}T10:${String(i % 60).padStart(2, '0')}:00Z`,
    skus: [
      {
        skuId: id * 10 + 1,
        spuId: id,
        skuCode: `${id}-A`,
        title: `${`示例商品 ${i + 1}`} 默认规格`,
        specs: { 颜色: '黑色', 容量: '标准' },
        images: [`https://picsum.photos/seed/sku${id}a/300`],
        status: 1,
        costPrice: i % 3 === 0 ? priceYuan * 0.6 : null, // 无 cost:read 时后端脱敏为 null（字段级权限）
      },
      {
        skuId: id * 10 + 2,
        spuId: id,
        skuCode: `${id}-B`,
        title: `${`示例商品 ${i + 1}`} 高配规格`,
        specs: { 颜色: '白色', 容量: '高配' },
        images: [`https://picsum.photos/seed/sku${id}b/300`],
        status: 1,
        costPrice: i % 3 === 0 ? priceYuan * 0.75 : null,
      },
    ],
  };
});

/** core-price 价行（SKU 现价唯一归属；Spu.minPrice 只是展示冗余） */
const PRICES: Record<number, { price: number; marketPrice: number }> = {};
for (const spu of PRODUCTS) {
  spu.skus.forEach((sku, idx) => {
    PRICES[sku.skuId] = {
      price: Number((spu.minPrice + idx * 30).toFixed(2)),
      marketPrice: Number((spu.marketPrice + idx * 30).toFixed(2)),
    };
  });
}

/** 订单 8 值状态（core-order OrderStatus：0-unpaid … 7-closed，任一端禁止自造） */
const ORDER_STATUS_CN = ['待付款', '待发货', '待收货', '已完成', '退款中', '已退款', '已取消', '已关闭'];

interface MockAdminOrderVO {
  id: number;
  orderNo: string;
  userId: number;
  parentOrderNo: string | null;
  storeId: number;
  status: number;
  statusText: string;
  payType: number | null;
  payTime: string | null;
  totalAmount: number;
  discountAmount: number;
  freightAmount: number;
  payAmount: number;
  receiverName: string;
  receiverMobile: string;
  closeReason: string | null;
  remark: string;
  splitFlag: boolean;
  version: number;
  items: Array<{ skuName: string; quantity: number; price: number }>;
  children: Array<{ orderNo: string; itemCount: number }>;
  createdAt: string;
  /** 以下为后端 AdminOrderVO 未下发的字段（缺口），mock 补发以便开发期联调，真实后端缺省时页面显示 "-" */
  payDeadline: string | null;
  receiverAddress: string;
  shippedAt: string | null;
  adminRemarks: Array<{ time: string; operator: string; content: string }>;
}

const ORDERS: MockAdminOrderVO[] = Array.from({ length: 64 }, (_, i) => {
  const id = 500 + i;
  const status = i % 8;
  const spu = PRODUCTS[i % PRODUCTS.length];
  const qty = (i % 3) + 1;
  const totalYuan = Number((spu.minPrice * qty + 10).toFixed(2));
  const freightYuan = 8;
  const discountYuan = i % 4 === 0 ? 20 : 0;
  return {
    id,
    orderNo: `2026100${(i % 9) + 1}${String(10000000 + i * 137).slice(0, 10)}`,
    userId: 20000 + (i % 20),
    parentOrderNo: null,
    storeId: (i % 5) + 1,
    status,
    statusText: ORDER_STATUS_CN[status],
    payType: status === 0 || status === 6 ? null : [1, 2, 3, 4][i % 4],
    payTime:
      status >= 1 && status !== 6 ? `2026-10-${String((i % 28) + 1).padStart(2, '0')}T13:00:00Z` : null,
    totalAmount: totalYuan,
    discountAmount: discountYuan,
    freightAmount: freightYuan,
    payAmount: Number((totalYuan + freightYuan - discountYuan).toFixed(2)),
    receiverName: `收货人${(i % 20) + 1}`,
    receiverMobile: `159****${String(2000 + i).slice(-4)}`,
    closeReason: status === 7 ? '管理员手动关闭' : null,
    remark: i % 5 === 0 ? '买家备注：请尽快发货' : '',
    splitFlag: false,
    version: 1,
    items: [{ skuName: spu.name, quantity: qty, price: spu.minPrice }],
    children: [],
    createdAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')}T${String((i % 22) + 1).padStart(2, '0')}:20:00Z`,
    // 缺口字段（后端未下发）：mock 补发
    payDeadline: status === 0 ? '2026-10-12T12:00:00Z' : null,
    receiverAddress: `浙江省杭州市余杭区示例路 ${i + 1} 号`,
    shippedAt: status >= 2 && status !== 6 ? `2026-10-${String((i % 28) + 1).padStart(2, '0')}T18:00:00Z` : null,
    adminRemarks: [] as Array<{ time: string; operator: string; content: string }>,
  };
});

/** ❌ 售后：后端无 /api/admin/after-sale/** Controller，仅 mock 提供（联调真实后端会 404） */
const AFTER_SALES = Array.from({ length: 9 }, (_, i) => ({
  id: 800 + i,
  afterSaleNo: `AS2026100${i + 1}000${i * 7 + 1}`,
  orderNo: ORDERS[i * 3].orderNo,
  type: [1, 2, 3][i % 3],
  amount: ORDERS[i * 3].payAmount,
  reason: ['不想要了', '商品破损', '尺码不合适', '与描述不符'][i % 4],
  buyerNote: '申请仅退款，商品未拆封',
  status: [0, 1, 2, 3][i % 4],
  createdAt: `2026-10-0${(i % 9) + 1}T15:30:00Z`,
  handleNote: '',
}));

/** 管理员（SystemDtos.AdminUserVO：lastLoginAt/createdAt 为 Instant → ISO-8601 或 null） */
interface MockAdminUser {
  id: number;
  username: string;
  nickname: string;
  deptName: string | null;
  roles: string[];
  status: number;
  lastLoginAt: string | null;
  createdAt: string;
}

const ADMIN_USERS: MockAdminUser[] = Array.from({ length: 12 }, (_, i) => ({
  id: 200 + i,
  username: `user_${String(i + 1).padStart(2, '0')}`,
  nickname: ['张运营', '李客服', '王财务', '赵商家'][i % 4] + (i + 1),
  deptName: ['平台运营部', '客户服务部', '财务部', '商家管理部'][i % 4],
  roles: i === 0 ? ['super_admin'] : [['operator'], ['cs_agent'], ['finance_clerk'], ['merchant']][i % 4],
  status: i === 11 ? 0 : 1,
  lastLoginAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')}T09:0${i % 10}:00Z`,
  createdAt: `2026-0${(i % 9) + 1}-1${i % 9}T10:00:00Z`,
}));

const ROLES = [
  { id: 1, code: 'super_admin', name: '超级管理员', dataScope: 1, status: 1, userCount: 1, remark: '系统内置，不可编辑/删除', builtin: true },
  { id: 2, code: 'operation_manager', name: '运营主管', dataScope: 3, status: 1, userCount: 3, remark: '本部门及以下数据' },
  { id: 3, code: 'operator', name: '运营专员', dataScope: 4, status: 1, userCount: 8, remark: '仅本人创建数据' },
  { id: 4, code: 'cs_agent', name: '客服专员', dataScope: 4, status: 1, userCount: 12, remark: '分配工单处理' },
  { id: 5, code: 'finance_clerk', name: '财务专员', dataScope: 4, status: 1, userCount: 4, remark: '仅本人处理的资金操作' },
  { id: 6, code: 'merchant', name: '商家', dataScope: 6, status: 1, userCount: 30, remark: '仅本店铺数据' },
];

/** 权限树 = RbacService.permissionTree（PermTreeNodeVo：key/title/type/children，编码取注册表真码） */
const PERMISSION_TREE = [
  {
    key: 'dashboard', title: '工作台', type: 'menu',
    children: [
      { key: 'cost:read', title: '成本价查看', type: 'permission', children: null },
      { key: 'report:view', title: '报表查看', type: 'permission', children: null },
    ],
  },
  {
    key: 'product', title: '商品运营', type: 'menu',
    children: [
      { key: 'product:list', title: '商品列表', type: 'menu', children: null },
      { key: 'product:create', title: '新增商品', type: 'permission', children: null },
      { key: 'product:edit', title: '编辑商品', type: 'permission', children: null },
      { key: 'product:audit', title: '商品审核', type: 'permission', children: null },
    ],
  },
  {
    key: 'order', title: '订单管理', type: 'menu',
    children: [
      { key: 'order:list', title: '订单列表', type: 'menu', children: null },
      { key: 'order:refund', title: '退款/售后', type: 'menu', children: null },
      { key: 'order:detail', title: '订单详情', type: 'permission', children: null },
      { key: 'order:ship', title: '订单发货', type: 'permission', children: null },
      { key: 'order:close', title: '订单关单', type: 'permission', children: null },
      { key: 'refund:list', title: '售后列表', type: 'permission', children: null },
    ],
  },
  {
    key: 'system', title: '系统管理', type: 'menu',
    children: [
      { key: 'user:list', title: '用户列表', type: 'menu', children: null },
      { key: 'user:roles', title: '角色权限', type: 'menu', children: null },
      { key: 'system:dict', title: '数据字典', type: 'menu', children: null },
      { key: 'system:log', title: '操作日志', type: 'menu', children: null },
      { key: 'system:permission', title: '权限点管理', type: 'permission', children: null },
      { key: 'system:dept', title: '部门管理', type: 'permission', children: null },
      { key: 'system:config', title: '系统配置', type: 'permission', children: null },
      { key: 'system:user', title: '管理员管理', type: 'permission', children: null },
      { key: 'system:role', title: '角色管理', type: 'permission', children: null },
    ],
  },
];

const ROLE_PERMISSIONS: Record<number, string[]> = {
  1: PERMISSIONS,
  2: PERMISSIONS.filter((p) => !p.startsWith('system:') && p !== 'cost:read'),
  3: PERMISSIONS.filter((p) => p.startsWith('product:') || p.startsWith('order:') || p.startsWith('report:')),
  4: PERMISSIONS.filter((p) => p.startsWith('order:') || p.startsWith('refund:')),
  5: PERMISSIONS.filter((p) => p.startsWith('order:') || p.startsWith('refund:') || p.startsWith('report:')),
  6: PERMISSIONS.filter((p) => p.startsWith('product:') || p.startsWith('order:')),
};

const DICTS = [
  { id: 1, type: 'order_status', name: 'order_status', remark: null, status: 1 },
  { id: 2, type: 'pay_type', name: 'pay_type', remark: null, status: 1 },
  { id: 3, type: 'audit_status', name: 'audit_status', remark: null, status: 1 },
  { id: 4, type: 'shelf_status', name: 'shelf_status', remark: null, status: 1 },
  { id: 5, type: 'refund_reason', name: 'refund_reason', remark: null, status: 1 },
  { id: 6, type: 'member_level', name: 'member_level', remark: null, status: 1 },
  { id: 7, type: 'logistics_company', name: 'logistics_company', remark: null, status: 0 },
];

const DICT_ITEMS: Record<string, Array<{ id: number; code: string; label: string; sort: number; status: number }>> = {
  order_status: ORDER_STATUS_CN.map((label, code) => ({ id: code + 1, code: String(code), label, sort: code + 1, status: 1 })),
  pay_type: [
    { id: 1, code: '1', label: '支付宝', sort: 1, status: 1 },
    { id: 2, code: '2', label: '微信', sort: 2, status: 1 },
    { id: 3, code: '3', label: '银行卡(银联)', sort: 3, status: 1 },
    { id: 4, code: '4', label: '余额', sort: 4, status: 1 },
  ],
  audit_status: [
    { id: 1, code: '0', label: '待提交', sort: 1, status: 1 },
    { id: 2, code: '1', label: '审核中', sort: 2, status: 1 },
    { id: 3, code: '2', label: '已上架', sort: 3, status: 1 },
    { id: 4, code: '3', label: '审核拒绝', sort: 4, status: 1 },
  ],
  shelf_status: [
    { id: 1, code: '0', label: '已下架', sort: 1, status: 1 },
    { id: 2, code: '1', label: '已上架', sort: 2, status: 1 },
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

/** 操作日志（SystemLogController.toVO：beforeData 恒 null —— P0 不落库，afterData 为脱敏后入/出参） */
const OP_LOGS = Array.from({ length: 48 }, (_, i) => ({
  id: 300 + i,
  operator: ['张运营', '李客服', '王财务', '超级管理员'][i % 4],
  module: ['product', 'order', 'system', 'refund', 'user'][i % 5],
  action: ['上架商品', '关闭订单', '修改角色权限', '同意退款', '新增管理员'][i % 5],
  content: `对记录 ${1000 + i} 执行了操作`,
  ip: `192.168.${(i % 20) + 1}.${(i % 200) + 1}`,
  status: i % 9 === 0 ? 0 : 1,
  duration: 30 + (i % 200),
  createdAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')}T${String((i % 22) + 1).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}:00Z`,
  beforeData: null,
  afterData: { status: i % 2 === 0 ? 1 : 0, operator: 'admin' },
}));

/* ---------------------------------------------------------------- 工具 */

/** 分页（入参 page（商品）或 pageNum（其余），返回 PageResult{total,pageNum,pageSize,list}） */
function paginate<T>(list: T[], query: URLSearchParams, pageParamName: 'page' | 'pageNum' = 'pageNum') {
  const pageNum = Math.max(1, Number(query.get(pageParamName) ?? query.get('pageNum') ?? 1));
  const pageSize = Math.min(100, Math.max(1, Number(query.get('pageSize') ?? 20)));
  const start = (pageNum - 1) * pageSize;
  return { total: list.length, pageNum, pageSize, list: list.slice(start, start + pageSize) };
}

/** 商品列表筛选：keyword 模糊 + auditStatus/shelfStatus 数字精确（core-product adminPage 语义） */
function matchProduct(p: MockSpu, query: URLSearchParams) {
  const keyword = (query.get('keyword') ?? '').trim();
  const audit = query.get('auditStatus');
  const shelf = query.get('shelfStatus');
  return (
    (!keyword || p.name.includes(keyword) || p.spuCode.includes(keyword)) &&
    (audit === null || audit === '' || p.auditStatus === Number(audit)) &&
    (shelf === null || shelf === '' || p.shelfStatus === Number(shelf))
  );
}

/** 订单筛选：orderNo **精确匹配**（core-order cb.equal）+ userId 数值 + orderStatus 数值 */
function matchOrder(o: MockAdminOrderVO, query: URLSearchParams) {
  const orderNo = (query.get('orderNo') ?? '').trim();
  const userId = (query.get('userId') ?? '').trim();
  const status = query.get('orderStatus');
  return (
    (!orderNo || o.orderNo === orderNo) &&
    (!userId || String(o.userId) === userId) &&
    (status === null || status === '' || o.status === Number(status))
  );
}

/** toAdminMap（core-product ProductDetail.toAdminMap：含 specs 对象/图片数组/costPrice；无 price、stock、auditRecords） */
function toAdminMap(spu: MockSpu) {
  return {
    spuId: spu.id,
    spuCode: spu.spuCode,
    name: spu.name,
    subtitle: spu.subtitle,
    mainImage: spu.mainImage,
    images: spu.images.split(',').filter(Boolean),
    video: spu.video,
    detail: spu.detail,
    price: spu.minPrice,
    originalPrice: spu.marketPrice,
    salesCount: spu.salesCount,
    specs: { 颜色: ['黑色', '白色'], 容量: ['标准', '高配'] },
    skus: spu.skus.map((sku) => ({
      skuId: sku.skuId,
      skuCode: sku.skuCode,
      title: sku.title,
      specs: sku.specs,
      images: sku.images,
      status: sku.status,
      costPrice: sku.costPrice,
    })),
    categoryId: spu.categoryId,
    auditStatus: spu.auditStatus,
    shelfStatus: spu.shelfStatus,
    auditRemark: spu.auditRemark,
    deleted: spu.deleted,
    createdAt: spu.createdAt,
    updatedAt: spu.updatedAt,
  };
}

/* ---------------------------------------------------------------- 路由 */

const routes: Route[] = [
  /* ---- 认证（admin-bff AdminAuthController） ---- */
  ['POST', /^\/api\/admin\/auth\/login$/, (ctx) => {
    const { username, password } = ctx.body as { username?: string; password?: string };
    if (!username || !password) throw new MockError(1001, '用户名/密码不能为空');
    if (password.length < 4) throw new MockError(2002, '账号或密码错误');
    return { accessToken: `mock_at_${Date.now()}`, expiresIn: 7200, tokenType: 'Bearer' };
  }],
  ['POST', /^\/api\/admin\/auth\/refresh$/, () => ({
    accessToken: `mock_at_${Date.now()}`,
    expiresIn: 7200,
    tokenType: 'Bearer',
  })],
  ['POST', /^\/api\/admin\/auth\/logout$/, () => ({ success: true })],
  ['GET', /^\/api\/admin\/auth\/info$/, () => PROFILE],
  ['GET', /^\/api\/admin\/auth\/menus$/, () => MENUS],
  ['GET', /^\/api\/admin\/auth\/captcha$/, () => ({
    captchaId: 'mock-captcha',
    image: 'data:image/svg+xml;base64,',
  })],

  /* ---- 工作台（admin-bff AdminDataController：P0 stub） ---- */
  ['GET', /^\/api\/admin\/data\/workbench$/, () => ({
    gmv: 128654300,
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
      { key: 'audit', title: '待审核商品', count: 18, path: '/product/list?auditStatus=1' },
      { key: 'ship', title: '待发货订单', count: 21, path: '/order/list?orderStatus=1' },
      { key: 'refund', title: '待处理退款', count: 5, path: '/order/refund' },
    ],
  })],

  /* ---- 商品（core-product AdminProductController） ---- */
  ['GET', /^\/api\/admin\/product\/list$/, (ctx) =>
    paginate(PRODUCTS.filter((p) => matchProduct(p, ctx.query)), ctx.query, 'page')],
  ['GET', /^\/api\/admin\/product\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const item = PRODUCTS.find((p) => p.id === id);
    if (!item) throw new MockError(3001, '商品不存在');
    return toAdminMap(item);
  }],
  ['PUT', /^\/api\/admin\/product\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const item = PRODUCTS.find((p) => p.id === id);
    if (!item) throw new MockError(3001, '商品不存在');
    // 后端约束：仅 draft(0)/rejected(3) 可编辑（ProductService.update）
    if (item.auditStatus !== 0 && item.auditStatus !== 3) {
      throw new MockError(1001, `仅草稿/审核拒绝状态可编辑，当前: ${item.auditStatus}`);
    }
    const in_ = ctx.body as {
      name?: string; subtitle?: string; skus?: Array<{ skuCode?: string; price?: number; marketPrice?: number }>;
    };
    if (in_.name !== undefined) item.name = in_.name;
    if (in_.subtitle !== undefined) item.subtitle = in_.subtitle;
    // SKU 全量替换（先删后插，skuCode 保持）+ 价格重写 core-price
    if (in_.skus?.length) {
      for (const sku of item.skus) {
        const next = in_.skus.find((s) => s.skuCode === sku.skuCode);
        if (next?.price) {
          PRICES[sku.skuId] = {
            price: next.price,
            marketPrice: next.marketPrice ?? next.price,
          };
        }
      }
      item.minPrice = Math.min(...item.skus.map((sku) => PRICES[sku.skuId].price));
    }
    item.updatedAt = '2026-10-12T09:00:00Z';
    return item;
  }],
  ['POST', /^\/api\/admin\/product\/batch-(on|off)-shelf$/, (ctx) => {
    const ids = (ctx.body.ids as number[]) ?? [];
    if (ids.length === 0) throw new MockError(1001, '请选择商品');
    if (ids.length > 500) throw new MockError(1001, '批量操作单次上限500条，请分批提交');
    const on = ctx.path.includes('batch-on-shelf');
    const success: number[] = [];
    const failed: Array<{ id: number; reason: string }> = [];
    for (const id of ids) {
      const item = PRODUCTS.find((p) => p.id === id);
      if (!item) {
        failed.push({ id, reason: '商品不存在' });
        continue;
      }
      // 上架前置：auditStatus=2（ProductService.onShelf）
      if (on && item.auditStatus !== 2) {
        failed.push({ id, reason: '商品未通过审核，禁止上架' });
        continue;
      }
      item.shelfStatus = on ? 1 : 0;
      for (const sku of item.skus) sku.status = item.shelfStatus;
      success.push(id);
    }
    return { success, failed };
  }],

  /* ---- 订单（core-order AdminOrderController） ---- */
  ['GET', /^\/api\/admin\/order\/list$/, (ctx) => paginate(ORDERS.filter((o) => matchOrder(o, ctx.query)), ctx.query)],
  ['GET', /^\/api\/admin\/order\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const item = ORDERS.find((o) => o.id === id);
    if (!item) throw new MockError(4001, '订单不存在');
    return item;
  }],
  // ❌ 后端未实现（core-order/admin-bff 无 remark 接口）：仅 mock 提供（真实后端联调时该 PUT 会 404）
  ['PUT', /^\/api\/admin\/order\/(\d+)\/remark$/, (ctx) => {
    const id = Number(ctx.path.match(/\/order\/(\d+)\//)?.[1]);
    const item = ORDERS.find((o) => o.id === id);
    if (!item) throw new MockError(4001, '订单不存在');
    item.adminRemarks.push({
      time: '2026-10-12T09:30:00Z',
      operator: PROFILE.nickname,
      content: String(ctx.body.content ?? ''),
    });
    return { success: true };
  }],
  ['POST', /^\/api\/admin\/order\/(\d+)\/close$/, (ctx) => {
    const id = Number(ctx.path.match(/\/order\/(\d+)\//)?.[1]);
    const item = ORDERS.find((o) => o.id === id);
    if (!item) throw new MockError(4001, '订单不存在');
    // 状态机白名单：仅待付款(0)/待发货(1) 可关单（admin-backend 4.3）
    if (![0, 1].includes(item.status)) throw new MockError(4002, '当前状态不允许关闭订单');
    item.status = 7;
    item.statusText = '已关闭';
    item.closeReason = String(ctx.body.reason ?? '管理员手动关闭');
    return { orderId: item.id, orderNo: item.orderNo, status: item.status };
  }],

  /* ---- 售后（❌ 后端未实现，仅 mock） ---- */
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
    if (action === 2 && !ctx.body.reason) throw new MockError(1001, '拒绝退款必须填写原因');
    item.status = action;
    item.handleNote = String(ctx.body.reason ?? ctx.body.note ?? '');
    return { success: true };
  }],

  /* ---- 系统管理-用户（admin-bff SystemUserController） ---- */
  ['GET', /^\/api\/admin\/system\/user$/, (ctx) => {
    const keyword = (ctx.query.get('keyword') ?? '').trim();
    const list = ADMIN_USERS.filter((u) => !keyword || u.username.includes(keyword) || u.nickname.includes(keyword));
    return paginate(list, ctx.query);
  }],
  ['POST', /^\/api\/admin\/system\/user$/, (ctx) => {
    const { username, password, nickname, roles, deptId } = ctx.body as {
      username?: string; password?: string; nickname?: string; roles?: string[]; deptId?: number;
    };
    // AdminUserCreateReq 校验：username 必填、password 6~32
    if (!username) throw new MockError(1001, '用户名不能为空');
    if (!password || password.length < 6) throw new MockError(1001, '密码长度需 6~32 位');
    ADMIN_USERS.unshift({
      id: 900 + ADMIN_USERS.length,
      username,
      nickname: nickname ?? username,
      deptName: deptId ? `部门#${deptId}` : null,
      roles: roles ?? [],
      status: 1,
      lastLoginAt: null,
      createdAt: '2026-10-12T09:00:00Z',
    });
    return { userId: ADMIN_USERS[0].id, success: true };
  }],
  ['PUT', /^\/api\/admin\/system\/user\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const item = ADMIN_USERS.find((u) => u.id === id);
    if (!item) throw new MockError(404, '用户不存在');
    // AdminUserUpdateReq：只接受 nickname/mobile/password/status（**不含 username/roles**）
    const { nickname, status, password: _password, mobile: _mobile } = ctx.body as {
      nickname?: string; status?: number; password?: string; mobile?: string;
    };
    if (nickname !== undefined) item.nickname = nickname;
    if (status !== undefined) item.status = status;
    return { success: true };
  }],
  ['PUT', /^\/api\/admin\/system\/user\/(\d+)\/roles$/, (ctx) => {
    const id = Number(ctx.path.match(/\/user\/(\d+)\/roles/)?.[1]);
    const item = ADMIN_USERS.find((u) => u.id === id);
    if (!item) throw new MockError(404, '用户不存在');
    item.roles = (ctx.body.roles as string[]) ?? [];
    return { success: true };
  }],
  ['DELETE', /^\/api\/admin\/system\/user\/(\d+)$/, (ctx) => {
    const id = Number(ctx.path.match(/(\d+)$/)![1]);
    const idx = ADMIN_USERS.findIndex((u) => u.id === id);
    if (idx < 0) throw new MockError(404, '用户不存在');
    ADMIN_USERS.splice(idx, 1);
    return { success: true };
  }],

  /* ---- 系统管理-角色/权限（admin-bff SystemRoleController / SystemPermissionController） ---- */
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

  /* ---- 系统管理-字典（admin-bff SystemDictController） ---- */
  ['GET', /^\/api\/admin\/system\/dict$/, (ctx) => {
    const keyword = (ctx.query.get('keyword') ?? '').trim();
    const list = DICTS.filter((d) => !keyword || d.name.includes(keyword) || d.type.includes(keyword));
    return paginate(list, ctx.query);
  }],
  ['GET', /^\/api\/admin\/system\/dict\/items$/, (ctx) => {
    const type = ctx.query.get('type') ?? '';
    return DICT_ITEMS[type] ?? [];
  }],

  /* ---- 系统管理-操作日志（admin-bff SystemLogController：关键字 OR 时间范围） ---- */
  ['GET', /^\/api\/admin\/system\/operation-log$/, (ctx) => {
    const operator = (ctx.query.get('operator') ?? '').trim();
    const module = (ctx.query.get('module') ?? '').trim();
    const start = ctx.query.get('createdAtStart');
    const end = ctx.query.get('createdAtEnd');
    let list = OP_LOGS;
    // P0 语义：关键字优先（SystemLogController 注释：二选一）
    if (operator || module) {
      list = list.filter(
        (l) => (!operator || l.operator.includes(operator)) && (!module || l.module.includes(module)),
      );
    } else if (start || end) {
      const s = start ? Date.parse(start) : Number.NEGATIVE_INFINITY;
      const e = end ? Date.parse(end) : Number.POSITIVE_INFINITY;
      list = list.filter((l) => {
        const t = Date.parse(l.createdAt);
        return t >= s && t <= e;
      });
    }
    return paginate(list, ctx.query);
  }],

  /* ---- core-price（gateway /api/v1/price/** 直连，商品编辑回填 SKU 现价用） ---- */
  ['GET', /^\/api\/v1\/price\/skus$/, (ctx) => {
    const ids = (ctx.query.get('ids') ?? '')
      .split(',')
      .map((s) => Number(s.trim()))
      .filter((n) => !Number.isNaN(n) && n > 0);
    return ids
      .filter((id) => PRICES[id])
      .map((id) => ({ skuId: id, price: PRICES[id].price, marketPrice: PRICES[id].marketPrice }));
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
        const ok = (data: unknown) => respond(200, { code: 0, msg: '成功', data, traceId: `mock_${Date.now()}` });

        try {
          if (!AUTH_WHITELIST.some((re) => re.test(pathname))) {
            const auth = req.headers.authorization;
            if (!auth || !auth.startsWith('Bearer mock_at_')) {
              // 后端把 1002/1003 映射为 HTTP 401（GlobalExceptionHandler）
              return respond(401, { code: 1002, msg: '未登录', data: null, traceId: 'mock' });
            }
            // 幂等键校验（IdempotentInterceptor）：写接口缺键 1001 / 重复键 1005
            if (IDEMPOTENT_METHODS.has(method) && !IDEMPOTENT_SKIP.test(pathname)) {
              const idemKey = req.headers['idempotency-key'] ?? req.headers['x-idempotency-key'];
              if (!idemKey) {
                return respond(400, { code: 1001, msg: '参数错误：缺少幂等键 Idempotency-Key', data: null, traceId: 'mock' });
              }
              if (usedIdempotencyKeys.has(String(idemKey))) {
                return respond(409, { code: 1005, msg: '幂等冲突：请勿重复提交', data: null, traceId: 'mock' });
              }
              usedIdempotencyKeys.add(String(idemKey));
            }
          }
          const route = routes.find(([m, pattern]) => m === method && pattern.test(pathname));
          if (!route) {
            return respond(404, { code: 404, msg: `资源不存在: ${method} ${pathname}`, data: null, traceId: 'mock' });
          }
          const body = ['POST', 'PUT', 'PATCH'].includes(method) ? await readBody(req) : {};
          const data = route[2]({ method, path: pathname, query: new URLSearchParams(search), body });
          // 模拟网络延迟，便于观察 loading 态
          await new Promise((r) => setTimeout(r, 80 + Math.random() * 120));
          return ok(data);
        } catch (err) {
          if (err instanceof MockError) {
            return respond(err.status, { code: err.status, msg: err.message, data: null, traceId: 'mock' });
          }
          return respond(500, { code: 9999, msg: (err as Error).message, data: null, traceId: 'mock' });
        }
      });
    },
  };
}

/** 已消费的幂等键（mock 内存；真实后端在 IdempotentInterceptor 用 Redis/DB 校验） */
const usedIdempotencyKeys = new Set<string>();
