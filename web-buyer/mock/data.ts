/**
 * Mock 静态夹具：结构对齐 server/* README 的真实响应（camelCase、金额两位小数、0 基状态）。
 * 图片用 SVG data URI，避免外网依赖。
 */

export interface MockSku {
  skuId: number;
  skuCode: string;
  title: string;
  specs: Record<string, string>;
  images: string[];
  /** 1 在售；其余禁售 */
  status: number;
  price: number;
  marketPrice: number;
  stock: number;
}

export interface MockProduct {
  spuId: number;
  spuCode: string;
  name: string;
  subtitle: string;
  categoryId: number;
  /** 拆单维度：同 storeId 商品归一个子单 */
  storeId: number;
  onShelf: boolean;
  mainImage: string;
  images: string[];
  detail: string;
  salesCount: number;
  specs: { name: string; values: string[] }[];
  skus: MockSku[];
}

/** SVG 占位图（data URI）。 */
export function img(label: string, hue: number): string {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600">` +
    `<rect width="600" height="600" fill="hsl(${hue},70%,84%)"/>` +
    `<text x="300" y="315" font-size="44" text-anchor="middle" fill="hsl(${hue},55%,32%)" ` +
    `font-family="sans-serif">${label}</text></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

/** 笛卡尔积生成 SKU 矩阵；disabled 存放「颜色=蓝 / 存储=256GB」这类禁售组合的拼接键。 */
function matrix(
  base: number,
  specs: { name: string; values: string[] }[],
  priceOf: (comb: Record<string, string>) => [number, number],
  disabled: string[] = [],
  stock = 99,
): MockSku[] {
  let combos: Record<string, string>[] = [{}];
  for (const spec of specs) {
    combos = combos.flatMap((c) => spec.values.map((v) => ({ ...c, [spec.name]: v })));
  }
  return combos.map((comb, i) => {
    const key = specs.map((s) => comb[s.name]).join('|');
    const [price, marketPrice] = priceOf(comb);
    return {
      skuId: base * 100 + i,
      skuCode: `SKU-${base}-${i + 1}`,
      title: Object.values(comb).join(' '),
      specs: comb,
      images: [],
      status: disabled.includes(key) ? 0 : 1,
      price,
      marketPrice,
      stock,
    };
  });
}

function defineProduct(
  p: Omit<MockProduct, 'mainImage' | 'images' | 'skus'> & { image: string; skus: MockSku[] },
): MockProduct {
  const mainImage = img(p.image, p.spuId % 360);
  const skus = p.skus.map((s) => ({ ...s, images: s.images.length ? s.images : [mainImage] }));
  return {
    ...p,
    mainImage,
    images: [mainImage, img(`${p.image}-2`, (p.spuId + 40) % 360), img(`${p.image}-3`, (p.spuId + 80) % 360)],
    skus,
  };
}

const phoneSpecs = [
  { name: '颜色', values: ['曜石黑', '珍珠白', '星空蓝'] },
  { name: '存储', values: ['128GB', '256GB'] },
];
const phoneSkus = matrix(1001, phoneSpecs, (c) => (c['存储'] === '256GB' ? [4599, 4999] : [3999, 4399]), [
  '星空蓝|256GB', // 禁售组合，供 SKU 矩阵禁售态演示
]);

const laptopSpecs = [
  { name: '颜色', values: ['银色', '深空灰'] },
  { name: '内存', values: ['16GB', '32GB'] },
];
const laptopSkus = matrix(1002, laptopSpecs, (c) => (c['内存'] === '32GB' ? [7999, 8599] : [6499, 6999]));

const shoeSpecs = [
  { name: '颜色', values: ['云朵白', '曜石黑'] },
  { name: '尺码', values: ['40', '41', '42'] },
];
const shoeSkus = matrix(1004, shoeSpecs, () => [299, 359]);

const headphoneSpecs = [{ name: '颜色', values: ['皓月白', '午夜黑'] }];
const headphoneSkus = matrix(1007, headphoneSpecs, () => [599, 699], ['午夜黑']);

const single = (base: number, price: number, marketPrice: number, stock = 99): MockSku[] => [
  {
    skuId: base,
    skuCode: `SKU-${base}`,
    title: '默认规格',
    specs: {},
    images: [],
    status: 1,
    price,
    marketPrice,
    stock,
  },
];

export const MOCK_PRODUCTS: MockProduct[] = [
  defineProduct({
    spuId: 1001,
    spuCode: 'SPU-1001',
    name: '星辰 Pro 5G 智能手机 骁龙旗舰芯片 1亿像素',
    subtitle: '旗舰影像 | 120Hz 高刷屏 | 66W 快充',
    categoryId: 11,
    storeId: 1,
    onShelf: true,
    image: '星辰Pro手机',
    salesCount: 12800,
    detail: '<p>星辰 Pro 搭载旗舰级影像系统，1 亿像素主摄 + 超广角微距，昼夜皆清晰。</p>',
    specs: phoneSpecs,
    skus: phoneSkus,
  }),
  defineProduct({
    spuId: 1002,
    spuCode: 'SPU-1002',
    name: '星辰 Air 14 轻薄笔记本电脑 2.8K 高刷屏',
    subtitle: '1.2kg 轻薄机身 | 16 小时续航',
    categoryId: 12,
    storeId: 1,
    onShelf: true,
    image: '星辰Air笔记本',
    salesCount: 5300,
    detail: '<p>星辰 Air 14 全金属机身，兼顾性能与便携。</p>',
    specs: laptopSpecs,
    skus: laptopSkus,
  }),
  defineProduct({
    spuId: 1003,
    spuCode: 'SPU-1003',
    name: '清风 家用智能空气净化器 除甲醛除雾霾',
    subtitle: 'CADR 400m³/h | APP 智能操控',
    categoryId: 13,
    storeId: 1,
    onShelf: true,
    image: '清风净化器',
    salesCount: 3400,
    detail: '<p>清风净化器，三重滤网深度净化。</p>',
    specs: [],
    skus: single(1003, 1299, 1599),
  }),
  defineProduct({
    spuId: 1004,
    spuCode: 'SPU-1004',
    name: '云柔 男女同款休闲运动鞋 轻质透气跑步鞋',
    subtitle: '云感中底 | 透气网面',
    categoryId: 14,
    storeId: 2,
    onShelf: true,
    image: '云柔运动鞋',
    salesCount: 23000,
    detail: '<p>云柔跑鞋，回弹缓震。</p>',
    specs: shoeSpecs,
    skus: shoeSkus,
  }),
  defineProduct({
    spuId: 1005,
    spuCode: 'SPU-1005',
    name: '鲜果时刻 赣南脐橙 净重5kg 当季现摘',
    subtitle: '果肉细嫩 | 甜度 13°+',
    categoryId: 15,
    storeId: 2,
    onShelf: true,
    image: '赣南脐橙',
    salesCount: 45000,
    detail: '<p>产地直发，新鲜到家。</p>',
    specs: [],
    skus: single(1005, 49.9, 69.9, 500),
  }),
  defineProduct({
    spuId: 1006,
    spuCode: 'SPU-1006',
    name: '极影 27英寸 4K 144Hz 电竞显示器 HDR400',
    subtitle: 'Fast IPS | 1ms 响应',
    categoryId: 12,
    storeId: 1,
    onShelf: true,
    image: '极影显示器',
    salesCount: 2100,
    detail: '<p>极影电竞显示器，满血画质。</p>',
    specs: [{ name: '尺寸', values: ['27英寸', '32英寸'] }],
    skus: matrix(1006, [{ name: '尺寸', values: ['27英寸', '32英寸'] }], (c) =>
      c['尺寸'] === '32英寸' ? [2599, 2999] : [1899, 2199],
    ),
  }),
  defineProduct({
    spuId: 1007,
    spuCode: 'SPU-1007',
    name: '星辰 Buds 真无线蓝牙耳机 主动降噪 42小时续航',
    subtitle: '45dB 深度降噪 | 空间音频',
    categoryId: 11,
    storeId: 1,
    onShelf: true,
    image: '星辰Buds耳机',
    salesCount: 31000,
    detail: '<p>星辰 Buds，静听世界。</p>',
    specs: headphoneSpecs,
    skus: headphoneSkus,
  }),
  defineProduct({
    spuId: 1008,
    spuCode: 'SPU-1008',
    name: '暖冬 智能恒湿加湿器（已下架演示品）',
    subtitle: '用于演示 3003 未上架与购物车失效态',
    categoryId: 13,
    storeId: 1,
    onShelf: false,
    image: '暖冬加湿器',
    salesCount: 100,
    detail: '<p>演示商品：已下架。</p>',
    specs: [],
    skus: single(1008, 199, 259, 0),
  }),
];

export interface MockCategory {
  id: number;
  name: string;
}

export const MOCK_CATEGORIES: MockCategory[] = [
  { id: 11, name: '手机数码' },
  { id: 12, name: '电脑办公' },
  { id: 13, name: '家用电器' },
  { id: 14, name: '服饰鞋包' },
  { id: 15, name: '食品生鲜' },
];

export const MOCK_HOT_WORDS = ['手机', '笔记本', '耳机', '显示器', '脐橙', '跑鞋', '净化器'];
