/**
 * 订单域接口（订单 + 退款售后）
 * 契约出处：plans/admin-backend.md 4.2.1「订单接口」、4.2.2「售后接口」、4.3「订单状态机（8 值）」
 * 已对齐 server 实际实现（README / server）：
 *  - 列表/详情/关单由 admin-bff AdminOrderController 代理到 core-order AdminOrderController（权限点 order:list/detail/ship/close）
 *  - 列表入参：userId / orderStatus / storeId / orderNo（**orderNo 精确匹配**，cb.equal）/ includeChildren / pageNum / pageSize
 *  - 出参 AdminOrderVO：status(0-7) + receiverName/receiverMobile + items[skuName, quantity] + 金额 BigDecimal「元」
 *
 * 适配策略（不改页面视图模型，只在 API 层做字段映射）：
 *  - status → orderStatus；receiverName/receiverMobile → buyerName/buyerPhone（后端无买家昵称字段）
 *  - 金额 元 → 分（admin-frontend 9.6：页面展示按「分」）
 *  - 后端未下发字段（payDeadline/receiverAddress/shippedAt/adminRemarks/storeName）保留可选，页面降级展示，不编造
 */
import { get, post, put } from './request';
import type { PageQuery, PageResult } from '@/types/api';

/** 订单 8 值状态（admin-backend 4.3 / core-order OrderStatus：0待付款…7已关闭，全系统唯一定义，任一端禁止自造） */
export type OrderStatus = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7;

export interface OrderListItem {
  id: number;
  orderNo: string;
  orderStatus: OrderStatus;
  statusText: string;
  /** 收货人姓名（AdminOrderVO.receiverName；后端未下发买家昵称） */
  buyerName: string;
  /** 收货人手机号（AdminOrderVO.receiverMobile；列表脱敏以后端为主） */
  buyerPhone: string;
  /** 店铺：后端仅下发 storeId，店铺名缺口 → 显示 `店铺#<storeId>` */
  storeName: string;
  itemCount: number;
  productTitle: string;
  /** 商品总额（分）= AdminOrderVO.totalAmount（元）×100 */
  totalAmount: number;
  /** 运费（分）= freightAmount（元）×100 */
  freight: number;
  /** 优惠（分）= discountAmount（元）×100 */
  discount: number;
  /** 实付（分）= payAmount（元）×100 */
  payAmount: number;
  /** 支付方式：1支付宝 2微信 3银行卡(银联) 4余额（后端下发 Integer，未支付为 null） */
  payType: number;
  /** 支付截止时间：AdminOrderVO 未下发该字段（缺口）→ 空串，页面显示 "-" */
  payDeadline: string;
  createdAt: string;
}

export interface OrderQuery extends PageQuery {
  orderNo?: string;
  /** 买家**用户ID**（core-order 入参是 userId 数值，不支持昵称模糊查询） */
  buyer?: string;
  /** 空 = 全部；传值则精确匹配某一状态 */
  orderStatus?: OrderStatus | '';
}

/** core-order AdminOrderVO 原始结构（元 + Instant）；mock 环境额外下发的可选字段用 ? 标注（真实后端缺省降级） */
interface RawOrderItem {
  skuName: string;
  quantity: number;
}

interface RawOrder {
  id: number;
  orderNo: string;
  userId: number;
  storeId: number | null;
  status: OrderStatus;
  statusText: string;
  payType: number | null;
  payTime: string | null;
  totalAmount: number;
  discountAmount: number;
  freightAmount: number;
  payAmount: number;
  receiverName: string | null;
  receiverMobile: string | null;
  closeReason: string | null;
  remark: string | null;
  version: number | null;
  items: RawOrderItem[] | null;
  children: Array<{ itemCount: number }> | null;
  createdAt: string;
  /** 后端未下发（缺口）：mock 环境补发以便开发期联调，真实后端缺省时页面显示 "-" */
  payDeadline?: string | null;
  receiverAddress?: string | null;
  shippedAt?: string | null;
  adminRemarks?: Array<{ time: string; operator: string; content: string }> | null;
}

/** 元(BigDecimal) → 分 */
function toCents(yuan: unknown): number {
  if (yuan === null || yuan === undefined || yuan === '') return 0;
  return Math.round(Number(yuan) * 100);
}

/**
 * 订单备注能力开关：后端未实现 remark 接口，mock 环境可用、直连真实后端时隐藏入口（避免点了必 404）
 * TODO(联调)：core-order 补齐 PUT /api/admin/order/{id}/remark 后移除此开关
 */
export const ORDER_REMARK_AVAILABLE = import.meta.env.VITE_USE_MOCK !== 'false';

function toListItem(raw: RawOrder): OrderListItem {
  const items = raw.items ?? [];
  const itemCount =
    items.reduce((sum, item) => sum + (item.quantity ?? 0), 0) ||
    (raw.children ?? []).reduce((sum, child) => sum + (child.itemCount ?? 0), 0);
  return {
    id: raw.id,
    orderNo: raw.orderNo,
    orderStatus: raw.status,
    statusText: raw.statusText,
    buyerName: raw.receiverName ?? '',
    buyerPhone: raw.receiverMobile ?? '',
    storeName: raw.storeId == null ? '' : `店铺#${raw.storeId}`,
    itemCount,
    productTitle: items.length > 0 ? `${items[0].skuName}${items.length > 1 ? ` 等 ${items.length} 件` : ''}` : '',
    totalAmount: toCents(raw.totalAmount),
    freight: toCents(raw.freightAmount),
    discount: toCents(raw.discountAmount),
    payAmount: toCents(raw.payAmount),
    payType: raw.payType ?? 0,
    payDeadline: raw.payDeadline ?? '',
    createdAt: raw.createdAt,
  };
}

/**
 * 订单列表：GET /api/admin/order/list（core-order AdminOrderController#list）
 * orderNo 为**精确匹配**（后端 cb.equal），非模糊查询
 */
export function listOrders(params: OrderQuery): Promise<PageResult<OrderListItem>> {
  const { pageNum, pageSize, orderNo, buyer, orderStatus } = params;
  const userId = buyer && /^\d+$/.test(buyer) ? Number(buyer) : undefined;
  return get<PageResult<RawOrder>>('/admin/order/list', {
    pageNum,
    pageSize,
    orderNo: orderNo || undefined,
    userId,
    orderStatus: orderStatus === '' || orderStatus === undefined ? undefined : orderStatus,
  }).then((page) => ({ ...page, list: page.list.map(toListItem) }));
}

export interface OrderDetail extends OrderListItem {
  paidAt: string | null;
  /** 发货时间：AdminOrderVO 未下发（缺口）→ 恒 null */
  shippedAt: string | null;
  /** 收货地址：AdminOrderVO 未下发 receiverAddress（缺口）→ 空串 */
  receiver: { name: string; phone: string; address: string };
  /** 买家订单备注（AdminOrderVO.remark） */
  remark: string;
  /** 内部管理员备注：后端未实现（PUT remark 接口缺口）→ 空数组，页面显示空态 */
  adminRemarks: Array<{ time: string; operator: string; content: string }>;
  /** 乐观锁版本号：状态变更类操作以后端校验为准，冲突提示刷新重试（5.3） */
  version: number;
}

/** 订单详情：GET /api/admin/order/{id}（core-order AdminOrderController#detail，admin-bff 需 order:detail 权限点） */
export async function getOrderDetail(id: number): Promise<OrderDetail> {
  const raw = await get<RawOrder>(`/admin/order/${id}`);
  return {
    ...toListItem(raw),
    paidAt: raw.payTime ?? null,
    // 后端 AdminOrderVO 未下发 shippedAt / receiverAddress / adminRemarks（缺口），mock 环境补发
    shippedAt: raw.shippedAt ?? null,
    receiver: { name: raw.receiverName ?? '', phone: raw.receiverMobile ?? '', address: raw.receiverAddress ?? '' },
    remark: raw.remark ?? '',
    adminRemarks: raw.adminRemarks ?? [],
    version: raw.version ?? 0,
  };
}

/**
 * 订单备注：PUT /api/admin/order/{id}/remark
 * ❌ 后端未实现（server 侧无该接口，admin-bff/core-order 均无对应 Controller）——
 * 仅 mock 提供，联调真实后端时会返回 404「资源不存在」，见 docs/api-contract-check.md
 */
export function addOrderRemark(id: number, content: string): Promise<{ success: boolean }> {
  return put(`/admin/order/${id}/remark`, { content });
}

/**
 * 关闭订单：POST /api/admin/order/{id}/close（4.2.1；联动 4.4.4 释放锁定库存）
 * 已对齐 server 实际实现：body { reason }，响应 { orderId, orderNo, status }；admin-bff 侧 @Idempotent（重复关闭 1005）
 */
export function closeOrder(id: number, reason: string): Promise<{ success: boolean }> {
  return post(`/admin/order/${id}/close`, { reason }).then(() => ({ success: true }));
}

/* ------------------------------------------------------------ 退款售后（plans/admin-backend 4.2.2） */

/**
 * 售后（退款/售后单）能力开关：**后端未实现**，P1 由 `admin-order` 拆分后的售后域提供
 * （plans/00-架构总纲「演进阶段：21 个 admin-*」，其中不含 after-sale；server 侧无任何
 * `after-sale` Controller，网关 `/api/admin/**` 兜底到 admin-bff 后必然 404）。
 *
 * 口径与 `ORDER_REMARK_AVAILABLE` 一致：仅 mock 环境放行（mock 已提供 `/api/admin/after-sale/*` 路由），
 * `VITE_USE_MOCK=false` 直连真实后端时**关闭入口**（菜单 + 路由 + 按钮），页面给出显式说明，
 * 不允许出现"能点进去但永远转圈/空表"的空转。
 */
export const AFTER_SALE_AVAILABLE = import.meta.env.VITE_USE_MOCK !== 'false';

export interface AfterSaleItem {
  id: number;
  afterSaleNo: string;
  orderNo: string;
  /** 1退款 2退货退款 3换货 */
  type: number;
  /** 退款金额（分）；累计退款不得超过订单实付（5.6） */
  amount: number;
  reason: string;
  buyerNote: string;
  /** 0待审核 1已同意 2已拒绝 3已完成 */
  status: number;
  createdAt: string;
  handleNote: string;
}

export interface AfterSaleQuery extends PageQuery {
  status?: number | '';
}

/**
 * 售后列表：GET /api/admin/after-sale/list（plans/admin-backend 4.2.2）
 * ❌ 后端未实现（server 侧无 after-sale Controller，网关兜底路由到 admin-bff 后 404）——
 * 仅 mock 提供，真实后端下由 `AFTER_SALE_AVAILABLE` 关闭入口，见 docs/api-contract-check.md
 */
export function listAfterSales(params: AfterSaleQuery): Promise<PageResult<AfterSaleItem>> {
  return get('/admin/after-sale/list', params);
}

/** 审核通过：POST /api/admin/after-sale/{id}/approve（❌ 后端未实现，仅 mock） */
export function approveAfterSale(id: number, note?: string): Promise<{ success: boolean }> {
  return post(`/admin/after-sale/${id}/approve`, { note });
}

/** 审核拒绝：POST /api/admin/after-sale/{id}/reject（❌ 后端未实现，仅 mock；reason 必填） */
export function rejectAfterSale(id: number, reason: string): Promise<{ success: boolean }> {
  return post(`/admin/after-sale/${id}/reject`, { reason });
}
