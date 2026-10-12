import { describe, expect, it } from 'vitest';
import type { ProductSku, ProductSpec } from '@/types/domain';
import {
  buildSkuOptions,
  displayPrice,
  findMatchingSku,
  isFullySelected,
  isOptionEnabled,
  specSummary,
  toggleOption,
} from './sku';

// 颜色 × 存储 矩阵：
//   黑128 / 黑256 / 白128 / 白256(禁售) / 蓝256
const specs: ProductSpec[] = [
  { name: '颜色', values: ['黑', '白', '蓝'] },
  { name: '存储', values: ['128GB', '256GB'] },
];

const sku = (
  skuId: number,
  color: string,
  storage: string,
  status = 1,
  price: string | number | null = '3999.00',
): ProductSku => ({
  skuId,
  specs: { 颜色: color, 存储: storage },
  status,
  price,
});

const skus: ProductSku[] = [
  sku(1, '黑', '128GB'),
  sku(2, '黑', '256GB', 1, '4599.00'),
  sku(3, '白', '128GB'),
  sku(4, '白', '256GB', 0), // 禁售
  sku(5, '蓝', '256GB'),
];

describe('SKU 矩阵：命中与全选判定', () => {
  it('全部维度选中且唯一命中 → 返回该 SKU', () => {
    const matched = findMatchingSku(skus, { 颜色: '黑', 存储: '256GB' });
    expect(matched?.skuId).toBe(2);
  });

  it('部分维度选中 → 不命中（返回 null）', () => {
    expect(findMatchingSku(skus, { 颜色: '黑' })).toBeNull();
  });

  it('命中禁售 SKU 也算命中（详情展示由业务层拦截下单）', () => {
    const matched = findMatchingSku(skus, { 颜色: '白', 存储: '256GB' });
    expect(matched?.skuId).toBe(4);
  });

  it('isFullySelected 只在全部维度选中后为 true', () => {
    expect(isFullySelected(specs, { 颜色: '黑' })).toBe(false);
    expect(isFullySelected(specs, { 颜色: '黑', 存储: '128GB' })).toBe(true);
  });
});

describe('SKU 矩阵：选项可点性（联动禁售）', () => {
  it('未选任何维度时：只要有在售 SKU 的值就可点', () => {
    expect(isOptionEnabled(specs, skus, {}, '颜色', '黑')).toBe(true);
    expect(isOptionEnabled(specs, skus, {}, '颜色', '蓝')).toBe(true);
    expect(isOptionEnabled(specs, skus, {}, '存储', '128GB')).toBe(true);
  });

  it('选中「白」后：256GB 唯一 SKU 禁售 → 256GB 不可点', () => {
    const selected = { 颜色: '白' };
    expect(isOptionEnabled(specs, skus, selected, '存储', '128GB')).toBe(true);
    expect(isOptionEnabled(specs, skus, selected, '存储', '256GB')).toBe(false);
  });

  it('选中「蓝」后：128GB 无对应 SKU → 不可点', () => {
    const selected = { 颜色: '蓝' };
    expect(isOptionEnabled(specs, skus, selected, '存储', '128GB')).toBe(false);
    expect(isOptionEnabled(specs, skus, selected, '存储', '256GB')).toBe(true);
  });

  it('未选维度不参与限制：已选存储 256GB 时「黑/蓝」仍可点、「白」禁售组合不可点', () => {
    const selected = { 存储: '256GB' };
    expect(isOptionEnabled(specs, skus, selected, '颜色', '黑')).toBe(true);
    expect(isOptionEnabled(specs, skus, selected, '颜色', '蓝')).toBe(true);
    expect(isOptionEnabled(specs, skus, selected, '颜色', '白')).toBe(false);
  });

  it('buildSkuOptions 输出选中态 + 禁售态', () => {
    const groups = buildSkuOptions(specs, skus, { 颜色: '白' });
    const color = groups.find((g) => g.name === '颜色')!;
    expect(color.options.find((o) => o.value === '白')?.selected).toBe(true);
    const storage = groups.find((g) => g.name === '存储')!;
    expect(storage.options.find((o) => o.value === '256GB')?.enabled).toBe(false);
  });
});

describe('SKU 矩阵：状态迁移与价格降级', () => {
  it('toggleOption：选中后再次点击取消该维度', () => {
    expect(toggleOption({}, '颜色', '黑')).toEqual({ 颜色: '黑' });
    expect(toggleOption({ 颜色: '黑' }, '颜色', '黑')).toEqual({});
    expect(toggleOption({ 颜色: '黑' }, '存储', '256GB')).toEqual({ 颜色: '黑', 存储: '256GB' });
  });

  it('displayPrice：命中 SKU 用其价格；未命中用在售最低价；全降级返回 null', () => {
    expect(displayPrice(skus, findMatchingSku(skus, { 颜色: '黑', 存储: '256GB' }))).toBe('4599.00');
    expect(displayPrice(skus, null)).toBe('3999.00');
    expect(displayPrice([sku(9, '黑', '128GB', 1, null)], null)).toBeNull();
  });

  it('specSummary 生成已选规格摘要', () => {
    expect(specSummary(specs, { 颜色: '黑', 存储: '128GB' })).toBe('颜色:黑 / 存储:128GB');
  });
});
