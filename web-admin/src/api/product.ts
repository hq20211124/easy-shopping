/**
 * 商品域接口（SPU）
 * 契约出处：plans/admin-backend.md 3.2.1「商品接口」、3.3.2「商品审核流程」
 * 已对齐 server 实际实现（README / server）：
 *  - 网关把 /api/admin/product/** 直连 core-product（server/gateway application.yml admin-product 路由）
 *  - 列表出入参 = core-product AdminProductController#list（入参 page/pageSize + keyword/categoryId/auditStatus/shelfStatus）
 *  - 详情出入参 = AdminProductController#detail → ProductService.ProductDetail.toAdminMap()
 *  - SKU 现价唯一归属 core-price：GET /api/v1/price/skus?ids=1,2 → [{skuId, price, marketPrice}]（元）
 *
 * 适配策略（不改页面视图模型，只在 API 层做字段映射）：
 *  - 枚举用后端 0 基数字（auditStatus 0-3 / shelfStatus 0-1），前端展示层按 options 渲染
 *  - 金额后端为 BigDecimal「元」，前端展示/传输为「分」→ 在本层换算（admin-frontend 9.6）
 *  - 后端未提供的字段（stock/categoryName/brandName/auditRecords）保留为可选，页面渲染 "-"，不编造数据
 */
import { get, post, put } from './request';
import type { PageQuery, PageResult } from '@/types/api';

/** 商品审核状态（core-product AuditStatus：0-draft 1-auditing 2-on_shelf 3-rejected，对外唯一真相） */
export type AuditStatus = 0 | 1 | 2 | 3;
/** 上下架状态（core-product ShelfStatus：0-下架 1-上架；上架前置 = auditStatus=2） */
export type ShelfStatus = 0 | 1;

/** 审核状态常量（避免页面散落魔法数字） */
export const AUDIT = { DRAFT: 0, AUDITING: 1, ON_SHELF: 2, REJECTED: 3 } as const;
/** 上下架常量 */
export const SHELF = { OFF: 0, ON: 1 } as const;

/* ------------------------------------------------------------ 视图模型（页面使用，保持稳定） */

export interface ProductSku {
  skuCode: string;
  /** 规格文案（由后端 specs 对象拼接，如「颜色:黑 / 容量:256GB」） */
  spec: string;
  /** 单位：分（来源 core-price） */
  price: number;
  /** 库存：core-product 未下发（库存归 core-inventory），缺字段时页面显示 "-" */
  stock?: number;
  enabled: boolean;
}

export interface AuditRecord {
  time: string;
  operator: string;
  action: string;
  opinion: string;
}

export interface ProductListItem {
  id: number;
  spuCode: string;
  /** SPU 名称（后端 name，前端展示为标题） */
  title: string;
  subtitle: string;
  categoryId: number;
  /** 类目名称：core-product 列表未下发（需另调 /api/v1/category），缺字段时页面显示 "-" */
  categoryName?: string;
  /** 品牌名称：后端无 brand 字段（缺口），页面显示 "-" */
  brandName?: string;
  /** 销售价（分）= 后端 Spu.minPrice（元）×100 */
  price: number;
  /** 划线价（分）= 后端 Spu.marketPrice（元）×100 */
  linePrice: number;
  /** 成本价（分）：列表接口不下发（仅详情 SKU 级 costPrice，且后端按 cost:read 判权脱敏） */
  costPrice?: number | null;
  /** 库存：core-product 未下发 */
  stock?: number;
  /** 累计销量（后端 salesCount；plans 的 30 日口径后端未提供，按累计值展示） */
  sales30d: number;
  shelfStatus: ShelfStatus;
  auditStatus: AuditStatus;
  auditRemark: string;
  /** ISO-8601（后端 Instant 序列化），页面直接展示原值 */
  updatedAt: string;
}

export interface ProductDetail extends ProductListItem {
  skus: ProductSku[];
  /** 审核记录：core-product 未实现 audit_records（缺口），暂为空数组，页面显示空态提示 */
  auditRecords: AuditRecord[];
}

export interface ProductQuery extends PageQuery {
  keyword?: string;
  /** 空 = 全部；传值则精确匹配某一状态（0-3） */
  auditStatus?: AuditStatus | '';
  /** 空 = 全部；传值则精确匹配某一状态（0-1） */
  shelfStatus?: ShelfStatus | '';
}

/* ------------------------------------------------------------ 后端原始结构（toAdminMap / Spu） */

/** core-price 价行（BigDecimal 元） */
interface PriceRow {
  skuId: number;
  price: number;
  marketPrice: number | null;
}

/** toAdminMap 的 SKU 视图（无 price/marketPrice —— 价格在 core-price） */
interface RawSku {
  skuId: number;
  skuCode: string;
  title: string;
  specs: Record<string, string>;
  images: string[];
  /** 1-启用(上架) 0-停用 */
  status: number;
  /** 敏感字段：无 cost:read 时后端脱敏为 null */
  costPrice: number | null;
}

/** core-product 管理端详情视图（toAdminMap），字段名与后端一致 */
interface RawDetail {
  spuId: number;
  spuCode: string;
  name: string;
  subtitle: string;
  mainImage: string | null;
  images: string[];
  video: string | null;
  detail: string | null;
  /** 兜底价（= Spu.minPrice，元） */
  price: number | null;
  /** 划线价（= Spu.marketPrice，元） */
  originalPrice: number | null;
  salesCount: number;
  categoryId: number;
  auditStatus: AuditStatus;
  shelfStatus: ShelfStatus;
  auditRemark: string | null;
  skus: RawSku[];
  createdAt: string;
  updatedAt: string;
}

/**
 * core-product 列表项（Spu 实体直接序列化，元 + Instant）。字段名与详情 toAdminMap 不同：
 * 列表是 `id/minPrice/marketPrice`，详情是 `spuId/price/originalPrice` → toListItem 两路兼容。
 */
interface RawSpu extends RawDetail {
  deleted: boolean;
  /** 列表侧主键（= 详情的 spuId） */
  id?: number;
  /** 列表侧兜底价（= 详情的 price / Spu.minPrice，元） */
  minPrice?: number | null;
  /** 列表侧划线价（= 详情的 originalPrice / Spu.marketPrice，元） */
  marketPrice?: number | null;
}

/* ------------------------------------------------------------ 换算工具（元 ↔ 分） */

/** 元(BigDecimal, 序列化为 number) → 分：后端金额一律 BigDecimal，前端禁止浮点运算金额（9.6） */
function toCents(yuan: unknown): number {
  if (yuan === null || yuan === undefined || yuan === '') return 0;
  return Math.round(Number(yuan) * 100);
}

/** 分 → 元（写回后端 SkuInput.price 时用；保留两位小数） */
function toYuan(cents: number): number {
  return Math.round(cents) / 100;
}

function specText(specs: Record<string, string> | undefined): string {
  if (!specs) return '';
  return Object.entries(specs)
    .map(([k, v]) => `${k}:${v}`)
    .join(' / ');
}

/* ------------------------------------------------------------ 映射 */

function toListItem(raw: RawSpu): ProductListItem {
  return {
    // 列表接口回 id、详情接口回 spuId（字段名不一致，见 RawSpu 注释）
    id: raw.spuId ?? raw.id,
    spuCode: raw.spuCode,
    title: raw.name,
    subtitle: raw.subtitle ?? '',
    categoryId: raw.categoryId,
    // 列表回 minPrice/marketPrice、详情回 price/originalPrice
    price: toCents(raw.price ?? raw.minPrice),
    linePrice: toCents(raw.originalPrice ?? raw.marketPrice),
    sales30d: raw.salesCount ?? 0,
    shelfStatus: raw.shelfStatus,
    auditStatus: raw.auditStatus,
    auditRemark: raw.auditRemark ?? '',
    updatedAt: raw.updatedAt,
  };
}

function toDetail(raw: RawDetail, prices: Map<number, PriceRow>): ProductDetail {
  const skus: ProductSku[] = (raw.skus ?? []).map((sku) => ({
    skuCode: sku.skuCode,
    spec: specText(sku.specs),
    // SKU 现价唯一归属 core-price（Spu 上只有冗余展示价）
    price: toCents(prices.get(sku.skuId)?.price ?? raw.price ?? 0),
    enabled: sku.status === 1,
  }));
  return {
    ...toListItem(raw as RawSpu),
    // 详情价取 SPU 冗余价（minPrice），与列表一致
    price: toCents(raw.price),
    linePrice: toCents(raw.originalPrice),
    skus,
    auditRecords: [],
  };
}

/* ------------------------------------------------------------ 接口 */

/**
 * 商品列表：GET /api/admin/product/list
 * 后端分页入参是 `page`（core-product AdminProductController），不是 plans 的 pageNum → 本层换名；
 * PageResult 仍为 { total, pageNum, pageSize, list }（common PageResult），page 值原样回传。
 */
export function listProducts(params: ProductQuery): Promise<PageResult<ProductListItem>> {
  const { pageNum, pageSize, keyword, auditStatus, shelfStatus } = params;
  return get<PageResult<RawSpu>>('/admin/product/list', {
    page: pageNum,
    pageSize,
    keyword: keyword || undefined,
    auditStatus: auditStatus === '' || auditStatus === undefined ? undefined : auditStatus,
    shelfStatus: shelfStatus === '' || shelfStatus === undefined ? undefined : shelfStatus,
  }).then((page) => ({ ...page, list: page.list.map(toListItem) }));
}

/** 拉取 SKU 现价（core-price；gateway: /api/v1/price/** → core-price） */
async function fetchSkuPrices(skuIds: number[]): Promise<Map<number, PriceRow>> {
  if (skuIds.length === 0) return new Map();
  const rows = await get<PriceRow[]>('/v1/price/skus', { ids: skuIds.join(',') });
  return new Map(rows.map((row) => [row.skuId, row]));
}

/** 商品详情：GET /api/admin/product/{id}（toAdminMap + core-price 回填 SKU 价） */
export async function getProductDetail(id: number): Promise<ProductDetail> {
  const raw = await get<RawDetail>(`/admin/product/${id}`);
  const prices = await fetchSkuPrices((raw.skus ?? []).map((sku) => sku.skuId));
  return toDetail(raw, prices);
}

export interface ProductUpdateParams {
  title?: string;
  subtitle?: string;
  /** 销售价（分）：后端无 SPU 级价格字段，编辑时按「商品级改价」下发到全部 SKU（缺口见 docs/api-contract-check.md） */
  price?: number;
}

/**
 * 更新商品：PUT /api/admin/product/{id}
 * 已对齐 server 实际实现（core-product AdminProductController#update / ProductService.update）：
 *  - 后端要求**完整 ProductInput**（name/categoryId/skus 必填、每个 SKU price > 0），不能只传 title/price
 *  - 更新为草稿/审核拒绝状态限定（auditStatus 0/3），其余状态后端返回 1001
 *  - SKU 全量替换（先删后插）：本层先取详情 + core-price 现价，重组入参后提交（skuCode 保持不变）
 */
export async function updateProduct(id: number, data: ProductUpdateParams): Promise<{ success: boolean }> {
  const raw = await get<RawDetail>(`/admin/product/${id}`);
  const skus = raw.skus ?? [];
  const prices = await fetchSkuPrices(skus.map((sku) => sku.skuId));
  const editedYuan = data.price === undefined ? undefined : toYuan(data.price);

  await put(`/admin/product/${id}`, {
    name: data.title ?? raw.name,
    subtitle: data.subtitle ?? raw.subtitle,
    categoryId: raw.categoryId,
    mainImage: raw.mainImage,
    images: (raw.images ?? []).join(',') || raw.mainImage || undefined,
    video: raw.video,
    detail: raw.detail,
    skus: skus.map((sku) => {
      const current = prices.get(sku.skuId);
      // 商品级改价：编辑框里的价格应用到该商品全部 SKU（后端无 SPU 级价格字段）
      const price = editedYuan ?? current?.price ?? raw.price ?? 0;
      // core-price 约束 marketPrice ≥ price，否则整批同步被拒（fail-safe 仅告警）→ 改高价时须同步抬 marketPrice
      const market = current?.marketPrice ?? price;
      return {
        skuCode: sku.skuCode,
        title: sku.title,
        spec: sku.specs,
        images: (sku.images ?? []).join(',') || raw.mainImage || undefined,
        price,
        marketPrice: Math.max(market, price),
        costPrice: sku.costPrice ?? undefined,
      };
    }),
  });
  return { success: true };
}

export interface BatchShelfResult {
  /** P0 后端为同步返回，无 taskId（P1 改异步任务后由后端下发） */
  taskId: string;
  successCount: number;
  failCount: number;
  failures: Array<{ id: number; reason: string }>;
}

/**
 * 批量上下架：POST /api/admin/product/batch-on-shelf | batch-off-shelf（3.2.1）
 * 已对齐 server 实际实现（ProductService.batchShelf）：
 *  - 单次上限 500 条，超限后端直接 1001；**同步**返回 { success: [ids], failed: [{id, reason}] }
 *  - 单个商品失败不中断整批（如未审核通过不能上架），失败明细在 failed 中
 */
export function batchShelf(ids: number[], shelf: ShelfStatus): Promise<BatchShelfResult> {
  return post<{ success?: number[]; failed?: Array<{ id: number; reason: string }> }>(
    `/admin/product/batch-${shelf === SHELF.ON ? 'on' : 'off'}-shelf`,
    { ids },
  ).then((res) => ({
    taskId: '',
    successCount: res.success?.length ?? 0,
    failCount: res.failed?.length ?? 0,
    failures: res.failed ?? [],
  }));
}
