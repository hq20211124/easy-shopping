/** 领域模型：字段与 server/* README 的真实响应对齐（camelCase）。 */

// ------------------------------------------------------------------ 商品

export interface ProductListItem {
  spuId: number;
  spuCode?: string;
  name: string;
  subtitle?: string;
  mainImage: string;
  price: string | number | null;
  originalPrice?: string | number | null;
  salesCount?: number;
  skuCount?: number;
}

export interface ProductSpec {
  name: string;
  values: string[];
}

export interface ProductSku {
  skuId: number;
  skuCode?: string;
  title?: string | null;
  /** 规格属性矩阵：{颜色:'黑', 存储:'256GB'} */
  specs: Record<string, string>;
  images?: string[];
  /** 1 在售可售；其余视为禁售/下架 */
  status: number;
  /** core-price 回填，价格服务不可用时为 null（降级） */
  price: string | number | null;
}

export interface ProductDetail {
  spuId: number;
  spuCode?: string;
  name: string;
  subtitle?: string | null;
  mainImage: string;
  images: string[];
  video?: string | null;
  detail?: string | null;
  price: string | number | null;
  originalPrice?: string | number | null;
  salesCount?: number;
  specs: ProductSpec[];
  skus: ProductSku[];
}

// ------------------------------------------------------------------ 搜索

export interface SearchProductItem {
  spuId: number;
  name: string;
  mainImage: string;
  price: string | number | null;
  originalPrice?: string | number | null;
  salesCount?: number;
}

export interface SearchResult {
  query: string;
  total: number;
  /** 末页为 null */
  nextCursor: string | null;
  list: SearchProductItem[];
}

export interface SuggestResult {
  query: string;
  suggestions: { text: string; type: string; productId?: number }[];
}

export interface HotWord {
  keyword: string;
  heat: number;
}

// ------------------------------------------------------------------ 购物车

export interface CartItemVO {
  cartItemId: number;
  productId: number;
  skuId: number;
  title: string;
  mainImage: string;
  price: string | number | null;
  quantity: number;
  stock: number;
  inStock: boolean;
  selected: boolean;
  invalid: boolean;
  discountTags: string[];
}

export interface CartListResponse {
  items: CartItemVO[];
  totalQuantity: number;
  selectedQuantity: number;
  /** 服务端下发，禁止前端硬编码 */
  maxItems: number;
  totalAmount: string | number | null;
  discountAmount: string | number | null;
  payableAmount: string | number | null;
}

// ------------------------------------------------------------------ 地址

export interface Address {
  id: number;
  name: string;
  mobile: string;
  province: string;
  city: string;
  district?: string | null;
  detail: string;
  zipCode?: string | null;
  isDefault?: boolean;
}

export interface AddressPayload {
  name: string;
  mobile: string;
  province: string;
  city: string;
  district?: string;
  detail: string;
  zipCode?: string;
  isDefault?: boolean;
}

// ------------------------------------------------------------------ 订单（0 基 8 值状态机）

export interface OrderItemView {
  orderId: number;
  orderNo: string;
  storeId?: number | null;
  spuId: number;
  skuId: number;
  skuName: string;
  skuImage: string;
  price: string | number;
  quantity: number;
  totalAmount: string | number;
  discountAmount?: string | number;
}

export interface ActionView {
  type: string;
  text: string;
}

export interface OrderListVO {
  orderId: number;
  orderNo: string;
  status: number;
  statusText: string;
  paymentStatus?: string | null;
  totalAmount: string | number;
  discountAmount: string | number;
  freightAmount: string | number;
  payAmount: string | number;
  itemCount: number;
  storeCount: number;
  items: OrderItemView[];
  payDeadline?: string | null;
  createdAt: string;
  actions: ActionView[];
}

export interface ChildOrderView {
  orderId: number;
  orderNo: string;
  storeId?: number | null;
  status: number;
  statusText: string;
  totalAmount: string | number;
  discountAmount: string | number;
  payAmount: string | number;
  itemCount: number;
}

export interface AmountDetailView {
  goodsAmount: string | number;
  freightAmount: string | number;
  discountAmount: string | number;
  payAmount: string | number;
}

export interface TimelineView {
  fromStatus: number;
  toStatus: number;
  toStatusText: string;
  operatorType: number;
  operatorId?: number | null;
  reason?: string | null;
  time: string;
}

export interface OrderDetailVO {
  orderId: number;
  orderNo: string;
  status: number;
  statusText: string;
  paymentStatus?: string | null;
  amountDetail: AmountDetailView;
  items: OrderItemView[];
  children?: ChildOrderView[];
  receiverName: string;
  receiverMobile: string;
  receiverAddress: string;
  remark?: string | null;
  payType?: number | null;
  payTime?: string | null;
  payDeadline?: string | null;
  deliveryTime?: string | null;
  receiveTime?: string | null;
  timeline: TimelineView[];
  actions: ActionView[];
  createdAt: string;
}

export interface CreateOrderPayload {
  items: { skuId: number; quantity: number }[];
  addressId?: number;
  receiverName?: string;
  receiverMobile?: string;
  receiverAddress?: string;
  couponId?: number;
  remark?: string;
  source?: number;
}

export interface CreateOrderResponse {
  orderId: number;
  orderNo: string;
  status: number;
  statusText: string;
  totalAmount: string | number;
  discountAmount: string | number;
  freightAmount: string | number;
  payAmount: string | number;
  expireSeconds?: number;
  payDeadline: string;
  subOrders: { orderId: number; orderNo: string; storeId?: number | null; payAmount: string | number; itemCount: number }[];
}

// ------------------------------------------------------------------ 支付

export interface PrepayResponse {
  paymentId: number;
  channel: number;
  amount: string | number;
  payType?: string | null;
  payParams?: Record<string, string>;
  expireAt?: number | null;
}

export interface PaymentStatusResponse {
  orderId: number;
  paymentId: number;
  channel?: number | null;
  amount: string | number;
  /** pending/paid/refunding/refunded（英文名，禁止数字二次推断） */
  status: string;
  paidAt?: string | null;
}

// ------------------------------------------------------------------ 优惠券

export interface CouponRecord {
  /** 用户券记录 ID（preview/deduct 入参）；模板 ID 在 templateId */
  couponId: number;
  templateId: number;
  name: string;
  /** 1 满减 / 2 折扣 / 3 无门槛 */
  type: number;
  /** available 可用 / used 已使用 / expired 已过期（core-promotion CouponRecordResponse.status） */
  status: string;
  expireAt?: string | null;
  receivedAt?: string | null;
  usedOrderId?: number | null;
  usedTime?: string | null;
}

export interface CouponPreview {
  couponId: number;
  templateId: number;
  couponName: string;
  totalAmount: string | number;
  discount: string | number;
  finalAmount: string | number;
  eligible: boolean;
  reason?: string | null;
}

// ------------------------------------------------------------------ 认证

export interface TokenResponse {
  userId: number;
  username: string;
  nickname?: string | null;
  roles: string[];
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  tokenType: string;
  isNewUser?: boolean;
}
