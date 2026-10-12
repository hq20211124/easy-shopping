/**
 * P0 本地 mock：结构对齐 server/* README 的真实响应（信封 {code,msg,data}，data 结构同后端 DTO）。
 * 仅当 VITE_USE_MOCK=true 时启用；切换 .env 即可无缝联调真实网关 http://localhost:8080。
 * mock 内置内存态购物车/订单/支付，保证「加购→结算→下单→支付→轮询→订单」闭环可走通。
 */

import type {
  ApiResponse,
  Address,
  CartItemVO,
  CouponRecord,
  Money,
  OrderDetailVO,
  OrderListVO,
  ProductDetail,
  ProductListItem,
} from './types'

// ------------------------------------------------------------------ 工具

const ok = <T>(data: T): ApiResponse<T> => ({ code: 0, msg: 'ok', data, traceId: 'mock' })
const err = (code: number, msg: string): ApiResponse<never> => ({ code, msg, data: null as never, traceId: 'mock' })

const yuan = (n: number): string => n.toFixed(2)

let idSeq = 100000
const nextId = () => ++idSeq

// ------------------------------------------------------------------ 商品数据

interface MockSku {
  skuId: number
  skuCode: string
  title: string
  specs: Record<string, string>
  images: string[]
  status: number
  price: string
}

interface MockProduct {
  spuId: number
  spuCode: string
  name: string
  subtitle: string
  mainImage: string
  images: string[]
  video: string
  detail: string
  price: string
  originalPrice: string
  salesCount: number
  categoryId: number
  specs: { name: string; values: string[] }[]
  skus: MockSku[]
}

const img = (seed: string) => `https://picsum.photos/seed/${seed}/300/300`

function makeProduct(
  spuId: number,
  name: string,
  subtitle: string,
  categoryId: number,
  price: number,
  market: number,
  sales: number,
  specs: { name: string; values: string[] }[],
  skuStatuses: number[] = [],
): MockProduct {
  const images = [img(`p${spuId}a`), img(`p${spuId}b`), img(`p${spuId}c`)]
  // 生成规格组合 SKU（笛卡尔积；status=0 表示禁售/无货，用于矩阵置灰演示）
  const combos: Record<string, string>[] = specs
    .reduce<Record<string, string>[]>(
      (acc, spec) => acc.flatMap((base) => spec.values.map((v) => ({ ...base, [spec.name]: v }))),
      [{}],
    )
  const skus: MockSku[] = combos.map((c, i) => ({
    skuId: spuId * 100 + i + 1,
    skuCode: `${spuCode(spuId)}-${i + 1}`,
    title: `${name} ${Object.values(c).join(' ')}`,
    specs: c,
    images,
    status: skuStatuses[i] ?? 1,
    price: yuan(price + i),
  }))
  return {
    spuId,
    spuCode: spuCode(spuId),
    name,
    subtitle,
    mainImage: images[0],
    images,
    video: '',
    detail: `<p>${name}</p><p>${subtitle}</p><img src="${images[1]}" style="width:100%"/>`,
    price: yuan(price),
    originalPrice: yuan(market),
    salesCount: sales,
    categoryId,
    specs,
    skus,
  }
}

const spuCode = (id: number) => `SPU${String(id).padStart(6, '0')}`

const products: MockProduct[] = [
  makeProduct(1001, '旗舰智能手机 5G 全网通', '骁龙旗舰芯 · 1 亿像素 · 5000mAh', 1, 3999, 4599, 1200, [
    { name: '颜色', values: ['曜石黑', '冰川白'] },
    { name: '存储', values: ['128GB', '256GB', '512GB'] },
  ], [1, 1, 1, 1, 0, 1]), // 第 5 个组合（冰川白 256GB? 见矩阵）禁售
  makeProduct(1002, '轻薄笔记本电脑 14 英寸', '2.8K 高刷屏 · 16G+512G · 1.3kg', 1, 5299, 5999, 860, [
    { name: '颜色', values: ['星空灰', '月光银'] },
  ]),
  makeProduct(1003, '真无线蓝牙耳机 降噪版', '42dB 主动降噪 · 30 小时续航', 2, 399, 599, 5600, [
    { name: '颜色', values: ['雅致白', '石墨黑'] },
  ]),
  makeProduct(1004, '机械键盘 87 键 热插拔', 'Gasket 结构 · 三模连接 · RGB', 2, 259, 359, 2300, [
    { name: '轴体', values: ['红轴', '茶轴', '青轴'] },
  ]),
  makeProduct(1005, '空气炸锅 5L 大容量', '360° 热风循环 · 90-200°C', 3, 189, 299, 8900, [
    { name: '容量', values: ['5L'] },
  ]),
  makeProduct(1006, '电动牙刷 声波充电款', '杜邦刷毛 · 五档模式 · IPX7', 3, 159, 259, 15600, [
    { name: '颜色', values: ['珍珠白', '蜜桃粉'] },
  ]),
]

const findProduct = (spuId: number) => products.find((p) => p.spuId === spuId)

function toListItem(p: MockProduct): ProductListItem {
  return {
    spuId: p.spuId,
    spuCode: p.spuCode,
    name: p.name,
    subtitle: p.subtitle,
    mainImage: p.mainImage,
    price: p.price,
    originalPrice: p.originalPrice,
    salesCount: p.salesCount,
    skuCount: p.skus.length,
  }
}

function toDetail(p: MockProduct): ProductDetail {
  return {
    spuId: p.spuId,
    spuCode: p.spuCode,
    name: p.name,
    subtitle: p.subtitle,
    mainImage: p.mainImage,
    images: p.images,
    video: p.video,
    detail: p.detail,
    price: p.price,
    originalPrice: p.originalPrice,
    salesCount: p.salesCount,
    specs: p.specs,
    skus: p.skus,
  }
}

// ------------------------------------------------------------------ 会话/地址/券

interface MockSession {
  userId: number
  username: string
}

let session: MockSession | null = null

const addresses: Address[] = [
  {
    id: 9001,
    name: '张三',
    mobile: '138****8000',
    province: '广东省',
    city: '深圳市',
    district: '南山区',
    detail: '科技园路 1 号 8 栋 808',
    isDefault: true,
  },
]

const coupons: CouponRecord[] = [
  { couponId: 8001, templateId: 7001, name: '满 300 减 30', type: 1, status: 'available', expireAt: new Date(Date.now() + 7 * 864e5).toISOString() },
  { couponId: 8002, templateId: 7002, name: '无门槛减 5 元', type: 3, status: 'available', expireAt: new Date(Date.now() + 3 * 864e5).toISOString() },
]

// ------------------------------------------------------------------ 购物车（内存态）

interface MockCartRow extends CartItemVO {
  userId: number
}

const cart: MockCartRow[] = []

function cartListData() {
  const mine = cart.filter((r) => r.userId === session?.userId)
  const valid = mine.filter((r) => !r.invalid)
  const selected = valid.filter((r) => r.selected)
  const sum = (rows: MockCartRow[]): string =>
    yuan(rows.reduce((s, r) => s + Number(r.price ?? 0) * r.quantity, 0))
  return {
    items: mine,
    totalQuantity: mine.reduce((s, r) => s + r.quantity, 0),
    selectedQuantity: selected.reduce((s, r) => s + r.quantity, 0),
    maxItems: 200,
    totalAmount: sum(valid),
    discountAmount: yuan(0),
    payableAmount: sum(selected),
  }
}

function requireLogin(): ApiResponse<never> | null {
  return session ? null : err(1002, '未登录或登录已过期')
}

// ------------------------------------------------------------------ 订单/支付（内存态）

type PaymentStatus = 'pending' | 'paid' | 'refunding' | 'refunded'

interface MockOrder {
  order: OrderDetailVO
  paymentStatus: PaymentStatus
  paymentId: number
}

const orders: MockOrder[] = []

/** mock 服务端支付超时口径（真实后端 pay-timeout-minutes=30，唯一事实来源是订单 pay_deadline）。 */
const MOCK_PAY_TIMEOUT_MS = 30 * 60 * 1000

function buildOrder(
  items: { skuId: number; quantity: number }[],
  receiver: { name?: string; mobile?: string; address?: string },
  couponId?: number,
  remark?: string,
): MockOrder {
  const rows = items.map((it) => {
    const product = products.find((p) => p.skus.some((s) => s.skuId === it.skuId))!
    const sku = product.skus.find((s) => s.skuId === it.skuId)!
    const total = Number(sku.price) * it.quantity
    return {
      orderId: 0,
      orderNo: '',
      storeId: 0,
      spuId: product.spuId,
      skuId: sku.skuId,
      skuName: sku.title,
      skuImage: sku.images[0],
      price: sku.price,
      quantity: it.quantity,
      totalAmount: yuan(total),
      discountAmount: yuan(0),
    }
  })
  const goodsAmount = rows.reduce((s, r) => s + Number(r.totalAmount), 0)
  // 试算同后端：满 300 减 30 / 无门槛减 5；不满足门槛不可用（eligible=false 不抛错）
  const coupon = coupons.find((c) => c.couponId === couponId && c.status === 'available')
  let discount = 0
  if (coupon) {
    if (coupon.type === 3) discount = 5
    else if (coupon.type === 1 && goodsAmount >= 300) discount = 30
  }
  const payAmount = Math.max(goodsAmount - discount, 0)
  const orderId = nextId()
  const orderNo = `${new Date().toISOString().slice(0, 10).replace(/-/g, '')}0000${String(orderId).slice(-6)}`
  const now = new Date()
  const payDeadline = new Date(now.getTime() + MOCK_PAY_TIMEOUT_MS).toISOString()
  rows.forEach((r) => {
    r.orderId = orderId
    r.orderNo = orderNo
  })
  const order: OrderDetailVO = {
    orderId,
    orderNo,
    status: 0,
    statusText: '待付款',
    paymentStatus: 'pending_payment',
    amountDetail: {
      goodsAmount: yuan(goodsAmount),
      freightAmount: yuan(0),
      discountAmount: yuan(discount),
      payAmount: yuan(payAmount),
    },
    items: rows,
    children: [
      {
        orderId,
        orderNo,
        storeId: 0,
        status: 0,
        statusText: '待付款',
        totalAmount: yuan(goodsAmount),
        discountAmount: yuan(discount),
        payAmount: yuan(payAmount),
        itemCount: rows.length,
      },
    ],
    receiverName: receiver.name || '张三',
    receiverMobile: receiver.mobile || '138****8000',
    receiverAddress: receiver.address || '广东省深圳市南山区科技园路 1 号 8 栋 808',
    remark: remark || '',
    payType: null,
    payTime: null,
    payDeadline,
    deliveryTime: null,
    receiveTime: null,
    timeline: [{ fromStatus: -1, toStatus: 0, toStatusText: '待付款', operatorType: 1, operatorId: session?.userId ?? null, reason: null, time: now.toISOString() }],
    actions: [
      { type: 'pay', text: '去付款' },
      { type: 'cancel', text: '取消订单' },
    ],
    createdAt: now.toISOString(),
  }
  if (coupon) coupon.status = 'used'
  return { order, paymentStatus: 'pending', paymentId: orderId }
}

const ORDER_STATUS_TEXT = ['待付款', '待发货', '待收货', '已完成', '退款中', '已退款', '已取消', '已关闭']

function statusActions(status: number): { type: string; text: string }[] {
  // 与后端 actionsOf 一致：按钮由服务端按状态下发
  if (status === 0) return [{ type: 'pay', text: '去付款' }, { type: 'cancel', text: '取消订单' }]
  if (status === 2) return [{ type: 'logistics', text: '查看物流' }, { type: 'confirm', text: '确认收货' }]
  return []
}

function toListVO(rec: MockOrder): OrderListVO {
  const o = rec.order
  return {
    orderId: o.orderId,
    orderNo: o.orderNo,
    status: o.status,
    statusText: o.statusText,
    paymentStatus: o.paymentStatus,
    totalAmount: o.amountDetail.goodsAmount,
    discountAmount: o.amountDetail.discountAmount,
    freightAmount: o.amountDetail.freightAmount,
    payAmount: o.amountDetail.payAmount,
    itemCount: o.items.length,
    storeCount: o.children?.length ?? 1,
    items: o.items,
    payDeadline: o.payDeadline,
    createdAt: o.createdAt,
    actions: statusActions(o.status),
  }
}

function advanceStatus(rec: MockOrder, to: number) {
  rec.order.status = to
  rec.order.statusText = ORDER_STATUS_TEXT[to]
  rec.order.timeline.push({
    fromStatus: rec.order.children?.[0]?.status ?? 0,
    toStatus: to,
    toStatusText: ORDER_STATUS_TEXT[to],
    operatorType: to === 1 ? 2 : 1,
    operatorId: null,
    reason: null,
    time: new Date().toISOString(),
  })
  rec.order.children?.forEach((c) => {
    c.status = to
    c.statusText = ORDER_STATUS_TEXT[to]
  })
  rec.order.actions = statusActions(to)
}

// ------------------------------------------------------------------ 搜索 cursor（模拟 Base64Url("排序键|id")；小程序无 btoa，用 encodeURIComponent 等价模拟）

const encodeCursor = (offset: number) => encodeURIComponent(`off|${offset}`)
const decodeCursor = (cursor?: string): number => {
  if (!cursor) return 0
  try {
    const m = /off\|(\d+)/.exec(decodeURIComponent(cursor))
    return m ? Number(m[1]) : 0
  } catch {
    return 0
  }
}

// ------------------------------------------------------------------ 路由

type Handler = (params: Record<string, string>, query: Record<string, string>, body: Record<string, unknown>) => ApiResponse<unknown>

const routes: { method: string; pattern: RegExp; handler: Handler }[] = []

const route = (method: string, path: string, handler: Handler) => {
  const pattern = new RegExp(
    '^' +
      path.replace(/\/:([A-Za-z]+)/g, '/(?<$1>[^/]+)').replace(/\//g, '\\/') +
      '$',
  )
  routes.push({ method, pattern, handler })
}

// ---- 认证（P0：手机号+密码/验证码 123456，对齐 core-user README） ----

route('POST', '/api/v1/auth/login', (_p, _q, body) => {
  const username = String(body.username || '')
  const password = String(body.password || '')
  if (!username || !password) return err(1001, '用户名/密码不能为空')
  session = { userId: 90001, username }
  return ok({
    userId: 90001,
    username,
    nickname: username,
    roles: ['USER'],
    accessToken: 'mock-access-token',
    refreshToken: 'mock-refresh-token',
    expiresIn: 7200,
    tokenType: 'Bearer',
    isNewUser: false,
  })
})

route('POST', '/api/v1/auth/register', (_p, _q, body) => {
  const mobile = String(body.mobile || '')
  const code = String(body.code || '')
  if (!/^1[3-9]\d{9}$/.test(mobile)) return err(1001, '手机号格式不正确')
  if (code !== '123456') return err(2003, '验证码错误')
  session = { userId: 90001, username: mobile }
  return ok({
    userId: 90001,
    username: mobile,
    nickname: mobile,
    roles: ['USER'],
    accessToken: 'mock-access-token',
    refreshToken: 'mock-refresh-token',
    expiresIn: 7200,
    tokenType: 'Bearer',
    isNewUser: true,
  })
})

route('POST', '/api/v1/auth/logout', () => {
  session = null
  return ok({ success: true })
})

// ---- 地址 ----

route('GET', '/api/v1/user/addresses', () => {
  const gate = requireLogin()
  if (gate) return gate
  return ok({ addresses: [...addresses].sort((a, b) => Number(b.isDefault) - Number(a.isDefault)) })
})

route('POST', '/api/v1/user/addresses', (_p, _q, body) => {
  const gate = requireLogin()
  if (gate) return gate
  const id = nextId()
  const item: Address = {
    id,
    name: String(body.name || ''),
    mobile: String(body.mobile || ''),
    province: String(body.province || ''),
    city: String(body.city || ''),
    district: String(body.district || ''),
    detail: String(body.detail || ''),
    isDefault: Boolean(body.isDefault) || addresses.length === 0,
  }
  if (item.isDefault) addresses.forEach((a) => (a.isDefault = false))
  addresses.push(item)
  return ok({ addressId: id })
})

route('PUT', '/api/v1/user/addresses/:addressId', (params, _q, body) => {
  const item = addresses.find((a) => a.id === Number(params.addressId))
  if (!item) return err(404, '地址不存在')
  Object.assign(item, {
    name: String(body.name || item.name),
    mobile: String(body.mobile || item.mobile),
    province: String(body.province || item.province),
    city: String(body.city || item.city),
    district: String(body.district || item.district),
    detail: String(body.detail || item.detail),
  })
  if (body.isDefault) {
    addresses.forEach((a) => (a.isDefault = a.id === item.id))
  }
  return ok({ updated: true })
})

route('DELETE', '/api/v1/user/addresses/:addressId', (params) => {
  const idx = addresses.findIndex((a) => a.id === Number(params.addressId))
  if (idx < 0) return err(404, '地址不存在')
  const [removed] = addresses.splice(idx, 1)
  if (removed.isDefault && addresses.length) addresses[0].isDefault = true // 默认位自动顺延
  return ok({ deleted: true })
})

route('POST', '/api/v1/user/addresses/:addressId/default', (params) => {
  addresses.forEach((a) => (a.isDefault = a.id === Number(params.addressId)))
  return ok({ success: true })
})

// ---- 商品 ----

route('GET', '/api/v1/products', (_p, query) => {
  const keyword = (query.keyword || '').trim()
  const categoryId = query.categoryId ? Number(query.categoryId) : null
  const sort = query.sort || 'default'
  const priceMin = query.priceMin ? Number(query.priceMin) : null
  const priceMax = query.priceMax ? Number(query.priceMax) : null
  let list = products.filter((p) => {
    if (keyword && !p.name.includes(keyword)) return false
    if (categoryId && p.categoryId !== categoryId) return false
    if (priceMin !== null && Number(p.price) < priceMin) return false
    if (priceMax !== null && Number(p.price) > priceMax) return false
    return true
  })
  list = sortProducts(list, sort)
  const page = Math.max(Number(query.page || 1), 1)
  const pageSize = Math.min(Math.max(Number(query.pageSize || 20), 1), 200)
  const start = (page - 1) * pageSize
  return ok({
    total: list.length,
    pageNum: page,
    pageSize,
    list: list.slice(start, start + pageSize).map(toListItem),
  })
})

function sortProducts(list: MockProduct[], sort: string): MockProduct[] {
  const arr = [...list]
  switch (sort) {
    case 'price_asc':
      return arr.sort((a, b) => Number(a.price) - Number(b.price))
    case 'price_desc':
      return arr.sort((a, b) => Number(b.price) - Number(a.price))
    case 'sales':
      return arr.sort((a, b) => b.salesCount - a.salesCount)
    case 'newest':
      return arr.sort((a, b) => b.spuId - a.spuId)
    default:
      return arr.sort((a, b) => b.salesCount - a.salesCount)
  }
}

route('GET', '/api/v1/products/:spuId', (params) => {
  const p = findProduct(Number(params.spuId))
  if (!p) return err(3001, '商品不存在')
  return ok(toDetail(p))
})

// ---- 搜索（cursor 分页） ----

route('GET', '/api/v1/search/products', (_p, query) => {
  const q = (query.q || '').trim()
  const sort = query.sort || 'default'
  const priceMin = query.priceMin ? Number(query.priceMin) : null
  const priceMax = query.priceMax ? Number(query.priceMax) : null
  let list = products.filter((p) => {
    if (q && !p.name.includes(q)) return false
    if (priceMin !== null && Number(p.price) < priceMin) return false
    if (priceMax !== null && Number(p.price) > priceMax) return false
    return true
  })
  list = sortProducts(list, sort)
  const pageSize = Math.min(Math.max(Number(query.pageSize || 20), 1), 50)
  const offset = decodeCursor(query.cursor)
  const page = list.slice(offset, offset + pageSize)
  const nextOffset = offset + pageSize
  return ok({
    query: q,
    total: list.length,
    nextCursor: nextOffset < list.length ? encodeCursor(nextOffset) : null, // 末页为 null
    list: page.map((p) => ({
      spuId: p.spuId,
      name: p.name,
      mainImage: p.mainImage,
      price: p.price,
      originalPrice: p.originalPrice,
      salesCount: p.salesCount,
    })),
  })
})

route('GET', '/api/v1/search/suggestions', (_p, query) => {
  const q = (query.q || '').trim()
  const suggestions = products
    .filter((p) => q && p.name.includes(q))
    .slice(0, 10)
    .map((p) => ({ text: p.name, type: 'product', productId: p.spuId }))
  return ok({ query: q, suggestions })
})

route('GET', '/api/v1/search/hot', () =>
  ok([
    { keyword: '手机', heat: 120 },
    { keyword: '耳机', heat: 98 },
    { keyword: '键盘', heat: 76 },
    { keyword: '笔记本', heat: 65 },
    { keyword: '空气炸锅', heat: 40 },
  ]),
)

// ---- 购物车 ----

route('GET', '/api/v1/cart/list', () => {
  const gate = requireLogin()
  if (gate) return gate
  return ok(cartListData())
})

route('POST', '/api/v1/cart/add', (_p, _q, body) => {
  const gate = requireLogin()
  if (gate) return gate
  const skuId = Number(body.skuId)
  const quantity = Math.max(Number(body.quantity || 1), 1)
  const product = products.find((p) => p.skus.some((s) => s.skuId === skuId))
  const sku = product?.skus.find((s) => s.skuId === skuId)
  if (!product || !sku) return err(3001, '商品不存在')
  if (sku.status !== 1) return err(3003, '商品已下架')
  const existing = cart.find((r) => r.userId === session!.userId && r.skuId === skuId)
  if (existing) {
    // 同 SKU 服务端合并（uk_user_sku）
    existing.quantity += quantity
    existing.invalid = false
    return ok({ cartItemId: existing.cartItemId, cartTotalQuantity: cartListData().totalQuantity })
  }
  const row: MockCartRow = {
    userId: session!.userId,
    cartItemId: nextId(),
    productId: product.spuId,
    skuId,
    title: sku.title,
    mainImage: sku.images[0],
    price: sku.price,
    quantity,
    stock: 20,
    inStock: true,
    selected: true,
    invalid: false,
    discountTags: [],
  }
  cart.push(row)
  return ok({ cartItemId: row.cartItemId, cartTotalQuantity: cartListData().totalQuantity })
})

route('POST', '/api/v1/cart/update', (_p, _q, body) => {
  const row = cart.find((r) => r.cartItemId === Number(body.cartItemId) && r.userId === session?.userId)
  if (!row) return err(3004, '购物车行不存在')
  if (body.quantity !== undefined) row.quantity = Math.max(Number(body.quantity), 1)
  if (body.selected !== undefined) row.selected = Boolean(body.selected)
  const data = cartListData()
  return ok({ cartItemId: row.cartItemId, cartTotalQuantity: data.totalQuantity, selectedQuantity: data.selectedQuantity })
})

route('POST', '/api/v1/cart/remove', (_p, _q, body) => {
  const ids = (body.cartItemIds as number[]) || []
  for (let i = cart.length - 1; i >= 0; i--) {
    if (ids.includes(cart[i].cartItemId) && cart[i].userId === session?.userId) cart.splice(i, 1)
  }
  return ok({ cartTotalQuantity: cartListData().totalQuantity })
})

route('POST', '/api/v1/cart/select', (_p, _q, body) => {
  const selected = Boolean(body.selected)
  const ids = (body.cartItemIds as number[]) || null
  cart
    .filter((r) => r.userId === session?.userId && !r.invalid)
    .forEach((r) => {
      if (!ids || ids.includes(r.cartItemId)) r.selected = selected
    })
  const data = cartListData()
  return ok({ cartTotalQuantity: data.totalQuantity, selectedQuantity: data.selectedQuantity })
})

// ---- 优惠券 ----

route('GET', '/api/v1/coupons/my', (_p, query) => {
  const gate = requireLogin()
  if (gate) return gate
  const status = query.status
  const list = status ? coupons.filter((c) => c.status === status) : coupons
  return ok(list)
})

route('POST', '/api/v1/coupons/preview', (_p, _q, body) => {
  const coupon = coupons.find((c) => c.couponId === Number(body.couponId))
  if (!coupon) return err(404, '优惠券不存在')
  const total = ((body.amount as Money[]) || []).reduce<number>((sum, a) => sum + Number(a ?? 0), 0)
  const threshold = coupon.type === 1 ? 300 : 0
  if (total < threshold) {
    // 不满足门槛：eligible=false + reason，不抛异常（后端契约）
    return ok({
      couponId: coupon.couponId,
      templateId: coupon.templateId,
      couponName: coupon.name,
      totalAmount: yuan(total),
      discount: yuan(0),
      finalAmount: yuan(total),
      eligible: false,
      reason: `未满足使用门槛（需满 ${threshold} 元）`,
    })
  }
  const discount = coupon.type === 3 ? 5 : 30
  return ok({
    couponId: coupon.couponId,
    templateId: coupon.templateId,
    couponName: coupon.name,
    totalAmount: yuan(total),
    discount: yuan(discount),
    finalAmount: yuan(Math.max(total - discount, 0)),
    eligible: true,
    reason: null,
  })
})

// ---- 订单 ----

route('POST', '/api/v1/orders', (_p, _q, body) => {
  const gate = requireLogin()
  if (gate) return gate
  const items = (body.items as { skuId: number; quantity: number }[]) || []
  if (!items.length) return err(1001, 'items 不能为空')
  for (const it of items) {
    const sku = products.flatMap((p) => p.skus).find((s) => s.skuId === it.skuId)
    if (!sku) return err(3001, '商品不存在')
    if (sku.status !== 1) return err(3003, '商品已下架')
  }
  const rec = buildOrder(
    items,
    { name: String(body.receiverName || ''), mobile: String(body.receiverMobile || ''), address: String(body.receiverAddress || '') },
    body.couponId ? Number(body.couponId) : undefined,
    body.remark ? String(body.remark) : undefined,
  )
  orders.unshift(rec)
  // 下单成功后从购物车移除对应 SKU（已购）
  for (let i = cart.length - 1; i >= 0; i--) {
    if (cart[i].userId === session!.userId && items.some((it) => it.skuId === cart[i].skuId)) cart.splice(i, 1)
  }
  const o = rec.order
  return ok({
    orderId: o.orderId,
    orderNo: o.orderNo,
    status: o.status,
    statusText: o.statusText,
    totalAmount: o.amountDetail.goodsAmount,
    discountAmount: o.amountDetail.discountAmount,
    freightAmount: o.amountDetail.freightAmount,
    payAmount: o.amountDetail.payAmount,
    expireSeconds: MOCK_PAY_TIMEOUT_MS / 1000,
    payDeadline: o.payDeadline,
    subOrders: (o.children || []).map((c) => ({
      orderId: c.orderId,
      orderNo: c.orderNo,
      storeId: c.storeId,
      payAmount: c.payAmount,
      itemCount: c.itemCount,
    })),
  })
})

route('GET', '/api/v1/orders', (_p, query) => {
  const gate = requireLogin()
  if (gate) return gate
  const status = query.status && query.status !== 'all' ? Number(query.status) : null
  const page = Math.max(Number(query.page || 1), 1)
  const pageSize = Math.min(Math.max(Number(query.pageSize || 20), 1), 200)
  const matched = orders.filter((rec) => status === null || rec.order.status === status)
  const start = (page - 1) * pageSize
  return ok({
    total: matched.length,
    pageNum: page,
    pageSize,
    list: matched.slice(start, start + pageSize).map(toListVO),
  })
})

route('GET', '/api/v1/orders/:orderId', (params) => {
  const rec = orders.find((r) => r.order.orderId === Number(params.orderId))
  if (!rec) return err(404, '订单不存在')
  return ok(rec.order)
})

route('POST', '/api/v1/orders/:orderId/cancel', (params) => {
  const rec = orders.find((r) => r.order.orderId === Number(params.orderId))
  if (!rec) return err(404, '订单不存在')
  if (rec.order.status !== 0) return err(4002, '当前订单状态不允许取消')
  advanceStatus(rec, 6)
  const coupon = coupons.find((c) => c.status === 'used' && c.usedOrderId === rec.order.orderId)
  if (coupon) coupon.status = 'available' // 关单退券
  return ok({ orderId: rec.order.orderId, orderNo: rec.order.orderNo, status: 6, statusText: '已取消' })
})

route('POST', '/api/v1/orders/:orderId/confirm', (params) => {
  const rec = orders.find((r) => r.order.orderId === Number(params.orderId))
  if (!rec) return err(404, '订单不存在')
  if (rec.order.status !== 2) return err(4002, '当前订单状态不允许确认收货')
  advanceStatus(rec, 3)
  rec.order.receiveTime = new Date().toISOString()
  return ok({ orderId: rec.order.orderId, orderNo: rec.order.orderNo, status: 3, statusText: '已完成' })
})

// ---- 支付 ----

route('POST', '/api/v1/payment/prepay', (_p, _q, body) => {
  const rec = orders.find((r) => r.order.orderId === Number(body.orderId))
  if (!rec) return err(404, '订单不存在')
  if (rec.order.status !== 0) return err(4002, '当前订单状态不可支付')
  return ok({
    paymentId: rec.paymentId,
    channel: Number(body.channel || 2),
    amount: rec.order.amountDetail.payAmount, // 服务端定额，防改价（C7）
    payType: 'mock_jsapi',
    payParams: { mock: 'true' },
    expireAt: rec.order.payDeadline ? new Date(rec.order.payDeadline).getTime() : null,
  })
})

route('POST', '/api/v1/payment/mock/pay', (_p, _q, body) => {
  const rec = orders.find((r) => r.paymentId === Number(body.paymentId))
  if (!rec) return err(4001, '支付单不存在')
  if (rec.paymentStatus !== 'paid') {
    rec.paymentStatus = 'paid'
    advanceStatus(rec, 1) // 支付成功 0 待付款 → 1 待发货
    rec.order.paymentStatus = 'paid'
    rec.order.payTime = new Date().toISOString()
  }
  return ok({ paymentId: rec.paymentId, orderId: rec.order.orderId, status: 'paid' })
})

route('GET', '/api/v1/payment/status/:orderId', (params) => {
  const rec = orders.find((r) => r.order.orderId === Number(params.orderId))
  if (!rec) return err(404, '订单不存在')
  return ok({
    orderId: rec.order.orderId,
    paymentId: rec.paymentId,
    channel: 2,
    amount: rec.order.amountDetail.payAmount,
    status: rec.paymentStatus,
    paidAt: rec.order.payTime,
  })
})

// ------------------------------------------------------------------ 分发

export async function mockRequest<T>(method: string, url: string, body: Record<string, unknown>): Promise<ApiResponse<T>> {
  const [path, qs = ''] = url.split('?')
  const query: Record<string, string> = {}
  // 小程序运行时无 URLSearchParams，手动解析 query
  if (qs) {
    qs.split('&').forEach((pair) => {
      const [k, v] = pair.split('=')
      if (k) query[decodeURIComponent(k)] = decodeURIComponent(v || '')
    })
  }
  for (const r of routes) {
    if (r.method !== method) continue
    const m = r.pattern.exec(path)
    if (!m) continue
    const params = (m.groups || {}) as Record<string, string>
    return r.handler(params, query, body) as ApiResponse<T>
  }
  return err(9999, `mock 未实现: ${method} ${path}`)
}
