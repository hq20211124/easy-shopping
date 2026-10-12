/** 领域模型：字段与 server/* README 的真实响应对齐（camelCase）。 */

// ------------------------------------------------------------------ 通用

export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
  traceId?: string
}

/** 金额字段：后端 BigDecimal 序列化为 JSON number/string，统一宽松接收 */
export type Money = string | number | null

export interface PageResult<T> {
  total: number
  pageNum: number
  pageSize: number
  list: T[]
}

// ------------------------------------------------------------------ 认证

export interface TokenResponse {
  userId: number
  username: string
  nickname?: string | null
  roles: string[]
  accessToken: string
  refreshToken: string
  expiresIn: number
  tokenType: string
  isNewUser?: boolean
}

// ------------------------------------------------------------------ 商品

export interface ProductListItem {
  spuId: number
  spuCode?: string
  name: string
  subtitle?: string
  mainImage: string
  price: Money
  originalPrice?: Money
  salesCount?: number
  skuCount?: number
}

export interface ProductSpec {
  name: string
  values: string[]
}

export interface ProductSku {
  skuId: number
  skuCode?: string
  title?: string | null
  /** 规格属性矩阵：{颜色:'黑', 存储:'256GB'} */
  specs: Record<string, string>
  images?: string[]
  /** 1 在售可售；其余视为禁售/下架 */
  status: number
  /** core-price 回填，价格服务不可用时为 null（降级） */
  price: Money
}

export interface ProductDetail {
  spuId: number
  spuCode?: string
  name: string
  subtitle?: string | null
  mainImage: string
  images: string[]
  video?: string | null
  detail?: string | null
  price: Money
  originalPrice?: Money
  salesCount?: number
  specs: ProductSpec[]
  skus: ProductSku[]
}

// ------------------------------------------------------------------ 搜索

export interface SearchProductItem {
  spuId: number
  name: string
  mainImage: string
  price: Money
  originalPrice?: Money
  salesCount?: number
}

export interface SearchResult {
  query: string
  total: number
  /** 末页为 null */
  nextCursor: string | null
  list: SearchProductItem[]
}

export interface SuggestResult {
  query: string
  suggestions: { text: string; type: string; productId?: number }[]
}

export interface HotWord {
  keyword: string
  heat: number
}

// ------------------------------------------------------------------ 购物车

export interface CartItemVO {
  cartItemId: number
  productId: number
  skuId: number
  title: string
  mainImage: string
  price: Money
  quantity: number
  stock: number
  inStock: boolean
  selected: boolean
  invalid: boolean
  discountTags: string[]
}

export interface CartListResponse {
  items: CartItemVO[]
  totalQuantity: number
  selectedQuantity: number
  /** 服务端下发，禁止前端硬编码 */
  maxItems: number
  totalAmount: Money
  discountAmount: Money
  payableAmount: Money
}

// ------------------------------------------------------------------ 地址

export interface Address {
  id: number
  name: string
  mobile: string
  province: string
  city: string
  district?: string | null
  detail: string
  zipCode?: string | null
  isDefault?: boolean
}

export interface AddressPayload {
  name: string
  mobile: string
  province: string
  city: string
  district?: string
  detail: string
  zipCode?: string
  isDefault?: boolean
}

// ------------------------------------------------------------------ 订单（0 基 8 值状态机）

export interface OrderItemView {
  orderId: number
  orderNo: string
  storeId?: number | null
  spuId: number
  skuId: number
  skuName: string
  skuImage: string
  price: Money
  quantity: number
  totalAmount: Money
  discountAmount?: Money
}

export interface ActionView {
  type: string
  text: string
}

export interface OrderListVO {
  orderId: number
  orderNo: string
  status: number
  statusText: string
  paymentStatus?: string | null
  totalAmount: Money
  discountAmount: Money
  freightAmount: Money
  payAmount: Money
  itemCount: number
  storeCount: number
  items: OrderItemView[]
  payDeadline?: string | null
  createdAt: string
  actions: ActionView[]
}

export interface ChildOrderView {
  orderId: number
  orderNo: string
  storeId?: number | null
  status: number
  statusText: string
  totalAmount: Money
  discountAmount: Money
  payAmount: Money
  itemCount: number
}

export interface AmountDetailView {
  goodsAmount: Money
  freightAmount: Money
  discountAmount: Money
  payAmount: Money
}

export interface TimelineView {
  fromStatus: number
  toStatus: number
  toStatusText: string
  operatorType: number
  operatorId?: number | null
  reason?: string | null
  time: string
}

export interface OrderDetailVO {
  orderId: number
  orderNo: string
  status: number
  statusText: string
  paymentStatus?: string | null
  amountDetail: AmountDetailView
  items: OrderItemView[]
  children?: ChildOrderView[]
  receiverName: string
  receiverMobile: string
  receiverAddress: string
  remark?: string | null
  payType?: number | null
  payTime?: string | null
  payDeadline?: string | null
  deliveryTime?: string | null
  receiveTime?: string | null
  timeline: TimelineView[]
  actions: ActionView[]
  createdAt: string
}

export interface CreateOrderPayload {
  items: { skuId: number; quantity: number }[]
  addressId?: number
  receiverName?: string
  receiverMobile?: string
  receiverAddress?: string
  couponId?: number
  remark?: string
  source?: number
}

export interface CreateOrderResponse {
  orderId: number
  orderNo: string
  status: number
  statusText: string
  totalAmount: Money
  discountAmount: Money
  freightAmount: Money
  payAmount: Money
  expireSeconds?: number
  payDeadline: string
  subOrders: { orderId: number; orderNo: string; storeId?: number | null; payAmount: Money; itemCount: number }[]
}

// ------------------------------------------------------------------ 支付

export interface PrepayResponse {
  paymentId: number
  channel: number
  amount: Money
  payType?: string | null
  payParams?: Record<string, string>
  expireAt?: number | null
}

export interface PaymentStatusResponse {
  orderId: number
  paymentId: number
  channel?: number | null
  amount: Money
  /** pending/paid/refunding/refunded（英文名，禁止数字二次推断） */
  status: string
  paidAt?: string | null
}

// ------------------------------------------------------------------ 优惠券

export interface CouponRecord {
  /** 用户券记录 ID（preview/deduct 入参）；模板 ID 在 templateId */
  couponId: number
  templateId: number
  name: string
  /** 1 满减 / 2 折扣 / 3 无门槛 */
  type: number
  /** available 可用 / used 已使用 / expired 已过期 */
  status: string
  expireAt?: string | null
  receivedAt?: string | null
  usedOrderId?: number | null
  usedTime?: string | null
}

export interface CouponPreview {
  couponId: number
  templateId: number
  couponName: string
  totalAmount: Money
  discount: Money
  finalAmount: Money
  eligible: boolean
  reason?: string | null
}

// ------------------------------------------------------------------ 业务错误码（plans/android-backend 16.2 白名单）

export const BIZ_CODE = {
  OK: 0,
  PARAM_ERROR: 1001,
  UNAUTHORIZED: 1002,
  TOKEN_EXPIRED: 1003,
  NO_PERMISSION: 1004,
  CONFLICT: 1005,
  OUT_OF_STOCK: 3002,
  ORDER_STATUS_ERROR: 4002,
  PAYMENT_FAILED: 5001,
  UNKNOWN: 9999,
} as const
