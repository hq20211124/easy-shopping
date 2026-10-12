import type { ProductSku, ProductSpec } from '@/types/domain';

/**
 * SKU 规格矩阵选择逻辑（纯函数；详情页 SkuPanel 与测试共用）。
 *
 * 规则：
 * - 全部规格维度都选中且命中唯一 SKU → 已选中 SKU；
 * - 选项「可点」当且仅当存在一个在售 SKU：该维度值等于本选项，且其余维度与当前已选一致（未选维度不限制）；
 * - 禁售（status !== 1）SKU 不参与可点判定，其组合展示为禁售态；
 * - 价格服务降级（price=null）不影响可选性，价格展示降级为最低价/暂无报价。
 */

export type SelectedSpecs = Record<string, string | undefined>;

export interface SkuOption {
  value: string;
  enabled: boolean;
  selected: boolean;
}

export interface SkuGroupOptions {
  name: string;
  options: SkuOption[];
}

/** SKU 在售判定（core-product：status=1 上架）。 */
export function isSellable(sku: ProductSku): boolean {
  return sku.status === 1;
}

/** 按当前已选规格找唯一命中 SKU（须全部维度选中）。 */
export function findMatchingSku(skus: ProductSku[], selected: SelectedSpecs): ProductSku | null {
  const matched = skus.filter((sku) =>
    Object.entries(selected).every(([name, value]) => value !== undefined && sku.specs[name] === value),
  );
  return matched.length === 1 ? matched[0] : null;
}

/** 某规格维度的某选项是否可点。 */
export function isOptionEnabled(
  specs: ProductSpec[],
  skus: ProductSku[],
  selected: SelectedSpecs,
  specName: string,
  value: string,
): boolean {
  const otherDims = specs.filter((s) => s.name !== specName).map((s) => s.name);
  return skus.some(
    (sku) =>
      isSellable(sku) &&
      sku.specs[specName] === value &&
      otherDims.every((dim) => selected[dim] === undefined || sku.specs[dim] === selected[dim]),
  );
}

/** 构建整套规格选择面板数据（含选中态/禁售态）。 */
export function buildSkuOptions(
  specs: ProductSpec[],
  skus: ProductSku[],
  selected: SelectedSpecs,
): SkuGroupOptions[] {
  return specs.map((spec) => ({
    name: spec.name,
    options: spec.values.map((value) => ({
      value,
      enabled: isOptionEnabled(specs, skus, selected, spec.name, value),
      selected: selected[spec.name] === value,
    })),
  }));
}

/** 点击某选项后的状态迁移：再次点击取消本维度；切换其他值直接覆盖。 */
export function toggleOption(selected: SelectedSpecs, specName: string, value: string): SelectedSpecs {
  const next: SelectedSpecs = { ...selected };
  if (next[specName] === value) {
    delete next[specName];
  } else {
    next[specName] = value;
  }
  return next;
}

/** 是否全部维度已选。 */
export function isFullySelected(specs: ProductSpec[], selected: SelectedSpecs): boolean {
  return specs.every((s) => selected[s.name] !== undefined);
}

/** 展示价：命中 SKU 用其价格；未命中用在售 SKU 最低价；全部降级返回 null（展示「暂无报价」）。 */
export function displayPrice(
  skus: ProductSku[],
  matched: ProductSku | null,
): string | number | null {
  if (matched) return matched.price;
  const prices = skus
    .filter(isSellable)
    .map((s) => (s.price === null || s.price === undefined ? null : Number(s.price)))
    .filter((p): p is number => p !== null && !Number.isNaN(p));
  if (prices.length === 0) return null;
  return Math.min(...prices).toFixed(2);
}

/** 选中规格摘要文案：「颜色:黑 / 存储:256GB」。 */
export function specSummary(specs: ProductSpec[], selected: SelectedSpecs): string {
  const parts = specs
    .filter((s) => selected[s.name] !== undefined)
    .map((s) => `${s.name}:${selected[s.name]}`);
  return parts.join(' / ');
}
