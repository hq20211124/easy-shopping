# 02 · 五端前端只读审计报告

> 审计对象：`web-admin`、`web-buyer`、`miniprogram`、`android-client`、`ios-client`
> 对账基准：**`server/` 实际实现**（各服务 Controller / admin-bff / gateway 路由 / `DataInitializer` 权限注册表 / `IdempotentInterceptor`），不以 `plans/` 规划稿为准
> 审计日期：2026-10-12　　审计方式：只读代码 + 实测验证（见文末「验证记录」）
> 约束遵守：本次审计**未修改任何被审计文件**，唯一写入为本报告

---

## 零、结论摘要

| 维度 | 评级 | 一句话 |
|------|------|--------|
| API 契约一致性 | **差** | web-admin / web-buyer / miniprogram / android **路径与字段基本正确**；**iOS 有 3 处致命类型不匹配导致支付与优惠券两条链路整体失效** |
| 业务逻辑正确性 | 中 | 0 基 8 值状态机、C7 倒计时、防改价三口径**全端达标**；SKU 联动、Tab 覆盖、幂等键复用存在跨端不一致 |
| 代码质量 | 中上 | 三端 TS `strict:true` 且 `tsc -b` **实测 0 error**；Android `:app:compileDebugKotlin` **实测 BUILD SUCCESSFUL**；iOS 未经编译但无结构性语法错误 |

**关键风险提示：iOS 端的三处 P0 是"编译通过但运行时必炸"的类型契约错配**——`JSONDecoder` 对类型不匹配直接抛错并被 `try?` 吞掉，最终表现为静默降级（支付页永远失败、优惠券区永远空白），Mac 上编译一次也发现不了，必须靠联调暴露。

---

## 一、A. API 契约一致性（最高优先级）

### A-1　逐端核对结果总表

| 域 | web-admin | web-buyer | miniprogram | android | ios |
|----|-----------|-----------|-------------|---------|-----|
| auth | ✅ 5 函数全对 | ✅ 4 函数全对（refresh 定义未调用，见 P1-6） | ✅ 3 函数全对（无 refresh） | ✅ 5 函数全对 | ⚠️ 路径对，2 处字段类型错（P0-1/P0-3） |
| product | ✅ `/admin/product/list` 参数名 `page` 换名正确 | ✅ | ✅ | ✅ | ✅ |
| cart | — | ✅ | ✅ | ✅ | ✅（含 RESTful 别名 `/cart/{id}` PUT/DELETE） |
| order | ⚠️ 售后路径错（P0-5） | ✅ | ✅ | ✅ | ✅ |
| payment | — | ⚠️ 响应体多声明 `orderId`（无害） | ⚠️ 同左 | ✅ | ❌ **payParams 类型错 → 支付全断（P0-1）** |
| coupon | — | ✅ `amount: (string\|number)[]` ✅ | ✅ | ✅（降级只读 `my(0)`） | ❌ **2 处类型错（P0-2/P0-3）** |
| search | — | ✅ | ✅ | ✅ | ✅ |
| address | — | ✅ | ✅ | ✅ | ✅ |
| system/user/role/dict/log | ✅ 9 函数逐条对 | — | — | — | — |

### A-2　发现列表

---

**[P0] `ios-client/EasyShopping/Features/Pay/PayModels.swift:34-41` — `PrepayResponse.payParams` 声明为 `String?`，后端下发 JSON 对象 → iOS 支付页 100% 失败**

- 前端（`PayModels.swift:39`）：`let payParams: String?`
- 后端（`server/core-payment/.../web/dto/PrepayResponse.java`）：`Map<String, String> payParams`
- 且**该字段永远非 null**（`PaymentService.payParamsOf:127-134` 三个分支全部 `Map.of(...)` 返回至少 2 个键）
- 失败链：`APIClient.perform:218` `guard let envelope = try? JSONDecoder().decode(ApiResponse<PrepayResponse>.self, ...)` → JSONDecoder 对 `"payParams": {...}` 解 `String?` 抛 `typeMismatch` → `try?` 吞掉 → `throw APIError.decode("HTTP 200")`
- 后果：`PayViewModel.pay():72-86` 必定进入 `phase = .failed("数据解析失败（HTTP 200）")`，**iOS 端支付闭环完全不可用**。连带 `PrepayResponse.expireAt: String?`（`PayModels.swift:40`）也是错的——后端是 `Long expireAt`（epoch 秒，`PaymentService:110`），即使修好 payParams，expireAt 仍会让整包解析再次失败。
- 建议：`payParams: [String: String]?`、`expireAt: Int64?`；并补一个"未知字段类型错 → 抛可读错误"的解码回归测试。

**[P0] `ios-client/EasyShopping/Features/Checkout/CheckoutModels.swift:69-72` — 券试算请求 `amount` 是标量，后端是数组 → 券试算永久失败**

- 前端：`struct CouponPreviewRequest: Codable { let couponId: Int64; let amount: Decimal }`（`CheckoutModels.swift:70-71`）
- 后端（`core-promotion/.../web/dto/PreviewRequest.java`）：`record PreviewRequest(@NotNull Long couponId, @NotNull List<@NotNull BigDecimal> amount)`
- 失败场景：`CheckoutViewModel.previewCoupon():120` 发 `{"couponId":1,"amount":99.00}` → Jackson `MismatchedInputException` → 400 → `catch { preview = nil }`（`CheckoutViewModel.swift:127-129`）
- 连锁后果：`CheckoutViewModel.submit():149` `let couponId = (preview?.usable ?? false) ? selectedCouponId : nil` → **用户选了券、看到券名，但订单不带 `couponId`、按原价成交**，全程无任何错误提示（`CouponPreviewResponse.usable` 默认 false 不报错）。这是"静默数据错误"，比报错更危险。
- 对照：web-buyer `coupon.ts:8` 与 miniprogram `coupon.ts:11` 都正确传数组 `amount: (string|number)[]`。
- 建议：iOS 改 `let amount: [Decimal]`（`Decimal` 数组经 `JSONEncoder` 序列化为 JSON 数组，Jackson 可直接反序列化为 `List<BigDecimal>`）。

**[P0] `ios-client/EasyShopping/Features/Checkout/CheckoutModels.swift:57-66` — `CheckoutCoupon.status: Int?`，后端下发英文枚举串 → iOS 优惠券区永远为空**

- 前端：`let status: Int?`（`CheckoutModels.swift:61`）
- 后端（`core-promotion/.../web/dto/CouponRecordResponse.java`）：`String status`，实测值 `"available"/"used"/"expired"`（`CouponIntegrationTest.java:117` 断言 `$.data.status == "available"`）
- 失败链：`CheckoutViewModel.loadCoupons():95-103` 整包解码抛错 → `catch { coupons = [] }` → 结算页优惠券卡永远显示"暂无可用"
- 顺带：`checkout.ts:96` 传 `status=0`（后端 `CouponUserController.my` 的 `Integer status`，0=可用）**参数是对的**，只是回参类型错。
- 对照：web-buyer `domain.ts:280` 是 `status: string` ✅；miniprogram `types.ts:313` 是 `status: string` ✅；android `Dtos.kt:423` 是 `status: String?` ✅。**只有 iOS 错**。
- 建议：`let status: String?`。

**[P0] `miniprogram/src/pages/cart/index.vue:70-72` + `pages/product/detail.vue:88-91` — 跳转参数名 `skuId` vs 读取参数名 `spuId` → 购物车进详情页永久白屏**

```ts
// pages/cart/index.vue:70-72
function goDetail(skuId: number) {
  uni.navigateTo({ url: `/pages/product/detail?skuId=${skuId}` })
}
// pages/product/detail.vue:88-91
onLoad((options) => {
  const spuId = Number(options?.spuId || 0)   // ← 读的是 spuId
  if (spuId) load(spuId)                       // ← spuId = 0，永不执行
})
```
- 绑定点：`pages/cart/index.vue:125`（图片）与 `:127`（标题）两处都挂了 `goDetail(l.skuId)`
- 失败场景：用户加购 → 进购物车 → 点商品图/标题 → 详情页 `product` 恒为 `null` → 页面只剩空 swiper 和空按钮区，**没有任何错误提示**，用户只能手动返回。
- 修复还需注意：即使把参数名改对，`CartItemVO` 里没有 `spuId` 字段（有 `productId`），应传 `l.productId`（后端 `CartDtos.CartItemVO.productId` 即 SPU 主键，见 `CartService` 用 `productId` 关联 SPU）。
- 建议：`goDetail(spuId: number)` + `:spuId=${l.productId}`。

**[P0] `web-admin/src/api/order.ts:216-227` — 售后三接口路径少了 `/admin` 段，mock 与真实后端都取不到数据**

```ts
// src/api/order.ts:216-218
export function listAfterSales(params: AfterSaleQuery) {
  return get('/after-sale/list', params);   // baseURL='/api' → /api/after-sale/list
}
```
- mock 路由是 `/^\/api\/admin\/after-sale\/list$/`（`mock/index.ts:645`）→ **mock 模式也不匹配**
- 真实后端：`server` 下无任何 `after-sale` Controller，网关 `admin-bff` 兜底后 404
- 失败场景：`/order/refund` 路由存在（`config/routes.tsx:71`）、菜单项存在（`config/menus.ts:27`）、权限码 `refund:list` 存在 → **用户能正常点进「退款/售后」页**，但页面 `useQuery` 永远报错，只弹一个「资源不存在」toast，表格永远空。
- 文档失真：`docs/api-contract-check.md:120` 声称"售后列表/详情入口隐藏；`order/refund.tsx` 仅展示订单侧退款数据"——**代码里既没隐藏入口，页面也没有任何订单侧退款数据回退**，与文档描述完全不符。
- 建议：二选一——(a) 后端补 `AdminAfterSaleController`；(b) 前端把三个函数的路径补成 `/admin/after-sale/*` 并在 `menus.ts` / `routes.tsx` 里用 `VITE_USE_MOCK` 开关隐藏入口，同时修正文档。

---

**[P1] `web-buyer/src/api/client.ts:25` — `crypto.randomUUID()` 无降级，非安全上下文下所有写请求抛 TypeError**

```ts
instance.interceptors.request.use((config) => {
  ...
  if (config.method && config.method !== 'get') {
    config.headers['Idempotency-Key'] = crypto.randomUUID();   // ← 无 typeof 守卫
  }
```
- `crypto.randomUUID` 仅在 **secure context**（https 或 `localhost`）可用。PC 买家端在局域网用 `http://192.168.x.x:5173` 访问时 `crypto.randomUUID` 为 `undefined` → 拦截器抛 `TypeError: crypto.randomUUID is not a function` → **加购/下单/改地址全部失败**。
- 对照：`web-admin/src/api/request.ts:50-57` 有 `typeof crypto.randomUUID === 'function'` 守卫 + `k_${ts}_${rand}` 降级；`miniprogram/src/utils/uuid.ts` 有完整 UUID v4 降级。**只有 web-buyer 漏了**。
- 建议：把 web-admin 的 `newIdempotencyKey()` 实现原样搬到 web-buyer `client.ts`。

**[P1] `web-buyer/src/api/client.ts:24-26` 与 `miniprogram/src/api/client.ts:83` — 幂等键每次请求都新生成，"同一次提交重试复用同 key"未实现**

```ts
// web-buyer client.ts:24-26（每进入一次拦截器就覆盖）
config.headers['Idempotency-Key'] = crypto.randomUUID();
// miniprogram client.ts:83（同样每次新生成）
...(method === 'GET' ? {} : { 'Idempotency-Key': uuid() }),
```
- 后端 `IdempotentInterceptor:49` 的作用域键 = `method + URI + key`，**同 key 才去重**；`OrderController.create` 带 `@Idempotent`（`OrderController.java:53`）。
- 失败场景：web-buyer `CheckoutPage.tsx:67-82` 的 `submit` mutation —— axios `timeout: 15000`（`client.ts:18`），下单请求被网关/网络吞掉但**服务端已建单**时，前端收到 9999「网络异常」；用户点「提交订单」重试 → **新 key → 服务端再建一单** → 重复下单。miniprogram `pages/checkout/index.vue:82-107` 同理（`uni.request` `timeout: 15000`，`client.ts:78`），虽然有 `submitting` 守卫，但**超时后守卫已释放**。
- 对照：Android **已正确实现**——`CheckoutViewModel.kt:60` 把 key 存在 VM 字段，`submit()` 失败保持不变（`catch` 不重置），成功后才在 `:142` 换新 key。web-admin 也正确：`request.ts:124` 有 `!config.headers['Idempotency-Key']` 守卫，401 重放（`request.ts:113` `http(config)`）时复用原 key。
- 建议：web-buyer / miniprogram 在下单调用点生成 key 并随 mutation 参数传递，或在 client 层加"同一 `method+url+payload` 在 N 秒内复用 key"的缓存。

**[P1] `ios-client/EasyShopping/Core/Network/APIClient.swift:246-270` — refresh 单飞有竞态，多个 401 可并发各起一次刷新 → 误踢登录**

```swift
private func refreshPayload() async -> TokenPayload? {
    if let task = refreshTask { return await task.value }   // ← 判空与赋值之间无同步
    guard let refresh = storedRefreshToken else { return nil }
    let task = Task<TokenPayload?, Never> { await APIClient.performRefresh(...) }
    refreshTask = task                                          // ← 两个 Task 可同时走到这里
```
- 后端 `AuthService.refresh` 会**轮转 refresh token**（core-user `AuthController.java:48` → `authService.refresh`），P0 宽限 60s（`TokenRotationStore`）。
- 失败场景：App 冷启动后首页并发 3 个请求（`products` / `search/hot` / `cart`），access token 恰好过期 → 3 个 401 → 三处 `refreshTask == nil` 同时成立 → 发出 3 次 refresh → 第 1 次成功并轮转，第 2、3 次拿旧 token 被拒 → `refreshPayload()` 返回 nil → `perform:236-238` `clearTokens()` + 发 `.sessionExpired` → **用户刚登录就被踢回登录页**。
- 对照：`web-admin/request.ts:83-107` 用模块级 `let refreshing: Promise<string> | null` 做单飞；`android/TokenAuthenticator.kt:50-71` 用 `synchronized(lock)` + "排队期间已刷新过就复用"双重保护。**只有 iOS 不安全**。
- 建议：把 `refreshTask` 包进 `actor RefreshGate`（或至少 `@MainActor` 隔离 `APIClient`）。

**[P1] `web-admin/docs/api-contract-check.md` — 32 项对账表与代码/后端大面积不符（10 处实证）**

| # | 文档行 | 文档写的 | 代码实际 | 后端实际 | 判定 |
|---|-------|---------|---------|---------|------|
| 1 | :44 | `refreshToken` 响应 `{accessToken,refreshToken}` | 无此导出，refresh 在 `request.ts:91` 内联 | `AdminAuthController.toBody:114-120` = `{accessToken,expiresIn,tokenType}`，refreshToken 走 httpOnly Cookie | ❌ 字段错 |
| 2 | :47 | `getCurrentUser` `GET /api/admin/auth/me` | `getAuthInfo()` → `/admin/auth/info`（`api/auth.ts:51`） | 后端**只有** `/api/admin/auth/info`（`AdminAuthController.java:98`），**无 `/me`** | ❌ 路径不存在 |
| 3 | :56 | `listProducts` `GET /api/admin/product/spu/list` | `/admin/product/list`（`api/product.ts:218`） | `AdminProductController.java:41` `/api/admin/product/list` | ❌ 文档路径不存在 |
| 4 | :57 | `getProductDetail` `GET /api/admin/product/spu/{id}` | `/admin/product/{id}`（`api/product.ts:236`） | `AdminProductController.java:53` | ❌ 同上 |
| 5 | :59-60 | `batchShelf` `POST .../spu/batch-shelf`、`batchOffShelf` `.../spu/batch-off-shelf` | `/admin/product/batch-on-shelf` / `batch-off-shelf`（`api/product.ts:305`），且**无 `batchOffShelf` 导出** | `AdminProductController.java:155,163` | ❌ 路径错 + 函数不存在 |
| 6 | :69 | `getOrderDetail` `GET /api/admin/order/{orderNo}` | `/admin/order/${id}`（`api/order.ts:159`） | `core-order AdminOrderController.java:62` `{orderId}` | ❌ 文档参数名错（代码对） |
| 7 | :70-71 | `shipOrder` `PUT .../ship`；`closeOrder` `PUT .../close` | **代码中无 `shipOrder`**；`closeOrder` 是 `POST`（`api/order.ts:186`） | `POST`（`admin-bff AdminOrderController.java:54`） | ❌ 方法错 + 函数不存在 |
| 8 | :73-74 | `listRefunds` `/api/admin/order/refund/list`；`handleRefund` `PUT /{id}/{approve\|reject}` | 代码里是 `listAfterSales` `/after-sale/list`（`api/order.ts:217`） | **都不存在** | ❌ 两端都对不上 |
| 9 | :82 | `listUsers` `GET /api/admin/system/user/list`；`getUserDetail` `/{id}` | `listAdminUsers()` → `/admin/system/user`（`api/user.ts:32`），**无 getUserDetail** | `SystemUserController.java:35` `/api/admin/system/user`，**无 `/{id}` GET** | ❌ 路径错 + 函数不存在 |
| 10 | :92 | `listLogs` `GET /api/admin/system/log/list` | `listOperationLogs()` → `/admin/system/operation-log`（`api/system.ts:89`） | `SystemLogController.java:35` `/api/admin/system/operation-log` | ❌ 文档路径不存在 |

另有两处统计口径失真：
- :19 声明合计 32 项，但 `src/api/*` 实际导出 **30 个函数**（auth 5 / product 4 / order 7 / user 9 / system 5）。
- :93 声称「字典/部门/角色/配置 CRUD ✅ 一致」——实际 `src/api/` 只有 `listDictTypes` / `listDictItems` / `listRoles` / `getPermissionTree` / `getRolePermissions` / `updateRolePermissions`，**dept 与 config 后端 Controller 齐全但前端零调用**，`routes.tsx` 也无对应页面。

> 文档中「✅ 一致」的标注给人虚假信心，实际有 10 处路径/方法级错误。建议把该文档从"对账表"降级为"历史记录"，并重新以 `src/api/*` 为准生成。

**[P2] `web-buyer/src/api/auth.ts:20` — `authApi.refresh` 定义后全项目零调用；miniprogram 完全没有 refresh 接口**

- grep 结果：web-buyer 中 `authApi.refresh` 仅出现在定义处；`refreshToken` 只在 `store/auth.ts` 存取与 `AppLayout.tsx:31` 登出时使用。
- miniprogram `api/client.ts:11` 定义了 `REFRESH_TOKEN_KEY` 并在 `setTokens` 写入，但 `api/auth.ts` 只有 login/register/logout 三个函数。
- 后果：access token TTL 7200s（`gateway/application.yml app.jwt.access-ttl-seconds`），过期后 `fail()`（`client.ts:31-40`）直接 `clear()` + `window.location.assign('/login')` → **用户每 2 小时被强制重新登录一次**，refresh token 形同虚设。
- 建议：web-buyer 在 `client.ts` 的 `fail()` 里加单飞 refresh（可复用 web-admin `request.ts:91-114` 的实现）；miniprogram 补 `/api/v1/auth/refresh` 调用。

**[P2] `web-buyer/src/api/payment.ts:9-10` 与 `miniprogram/src/api/payment.ts:9-10` — `mockPay` 响应类型多声明 `orderId`**

- 前端：`api.post<{ paymentId: number; orderId: number; status: string }>(...)`
- 后端 `MockPayResponse(Long paymentId, String status)` —— **无 `orderId`**
- 运行时无害（多声明字段不校验），但属于类型与事实不符，误导后续开发。android `Dtos.kt:398` 与 iOS `PayModels.swift:44-47` 都写对了（只有两个字段）。

**[P2] `web-admin/src/api/system.ts:98-122` — 工作台/待办在 mock 下有数据、真实后端恒为空，属于"mock 比真实更美化"的反向失真**

- 后端 `AdminDataController.java:22-35` 是硬编码 stub（`gmv=0L`、`items=List.of()`）。
- mock `mock/index.ts:530-547` 返回 `gmv: 128654300`、`orderCount: 3482`、7 日趋势、3 条待办。
- 后果：开发在 mock 下做的看板视觉验收，联调后变成全 0 空态，且 `dashboard/index.tsx` 无空态文案区分，容易被误判为 bug。
- 建议：mock 对齐 stub 返回全 0，或在 `docs` 与 dashboard 上显式标注"P0 stub"。

---

## 二、B. 业务逻辑正确性

### B-1　订单状态映射（0 基 8 值）

**结论：枚举值本身五端全部正确且单点定义，但 Tab 覆盖范围四端不一致。**

| 端 | 单点定义位置 | 值域核对 |
|----|-------------|---------|
| 后端真值 | `core-order/domain/OrderStatus.java:32-46` | 0待付款 1待发货 2待收货 3已完成 4退款中 5已退款 6已取消 7已关闭 |
| web-admin | `config/constants.ts:15-24` `ORDER_STATUS_OPTIONS` | ✅ 8 值逐值一致 |
| web-buyer | `utils/orderStatus.ts:11-20` `ORDER_STATUSES` | ✅ 8 值逐值一致 |
| android | `core/network/Dtos.kt:230-238` `enum OrderStatus` | ✅ 8 值逐值一致 |
| ios | `Features/Order/OrderModels.swift:7-37` `enum OrderStatus` | ✅ 8 值逐值一致 |
| miniprogram | **无枚举定义**，`subpkg/order/list.vue:9-16` 内联字面量数组 | 值对，但**未单点定义** |

**[P1] `miniprogram/src/subpkg/order/list.vue:9-16` + `ios/OrderModels.swift:40-46` + `android/OrderListViewModel.kt:43-58` + `web-buyer/utils/orderStatus.ts:29-32` — 订单列表 Tab 覆盖范围四端各不相同**

| 端 | Tab 集合 |
|----|---------|
| web-buyer | 全部 + **8 个状态**（0~7 全覆盖） |
| miniprogram | 全部 + **0~4**（缺 5已退款 / 6已取消 / 7已关闭） |
| android | 全部 + **0~3**（缺 4退款中 / 5已退款 / 6已取消 / 7已关闭） |
| ios | 全部 + **0~3**（同上，`OrderTab` 注释说明"服务端 status 参数为单值不支持合并查询"） |

- 失败场景：用户在 iOS/安卓端支付后申请退款，订单转为 4/5 → 用户在订单列表**找不到任何入口查看退款单**，只能切「全部」在长列表里翻。AGENTS.md 明确要求"各端枚举与之逐值一致"，枚举一致但**入口覆盖不一致**属于对同一口径的不同实现。
- 建议：三端统一到 0~7 全覆盖（后端 `OrderQueryService.buyerList:64-68` 已支持 `status=4|5|6|7` 精确查询，只是没人用）；miniprogram 抽 `ORDER_TABS` 到 `constants/` 单点导出。

**[P2] `miniprogram/src/pages/*` — 订单状态无 enum 单点定义**

`subpkg/order/list.vue:9-16` 与 `subpkg/order/detail.vue` 里散落 `status === 0` 等魔法数（`:24`），与 AGENTS.md「任一端禁止自造状态、禁止散落魔法数」不符。建议抽 `src/constants/order.ts`。

### B-2　支付超时（裁决 C7）

**结论：五端全部读服务端 `pay_deadline`，零硬编码分钟数 ✅**

| 端 | 证据 |
|----|------|
| web-buyer | `hooks/useCountdown.ts:5-18` 入参 `deadline`；`OrderListPage.tsx:134`、`OrderDetailPage.tsx:24`、`PayPage.tsx:31` 均传 `order.payDeadline` |
| miniprogram | `utils/countdown.ts:18-43` `start(deadline)`；`subpkg/order/detail.vue:24` `countdown.start(order.value.payDeadline)` |
| android | `Dtos.kt:279,330` `payDeadline: String?`；`TimeParser.parseInstant` 解析失败返回 null（`Serialization.kt:92-98`，注释"不猜分钟数"） |
| ios | `Components.swift:152-178` `CountdownText(deadline:)`；`OrderListView.swift:149`、`PayViewModel.swift:44` 均读 `payDeadline` |
| web-admin | `order/detail.tsx:132-136` 展示 `order.payDeadline` 原值 + "以后端下发为准"说明（管理端不做倒计时，符合定位） |

补充：miniprogram `utils/countdown.ts:45` 有 `onUnmounted(stop)` ✅；web-buyer `useCountdown.ts:15` 有 `clearInterval` ✅；iOS 用 `TimelineView(.periodic)` 无需手动清理 ✅。

### B-3　防改价（客户端不传金额）

**结论：五端下单/prepay 均不传金额 ✅**

| 端 | 证据 |
|----|------|
| web-buyer | `api/order.ts:7` `api.post('/v1/orders', p)`，`CreateOrderPayload`（`domain.ts:224-233`）**无任何金额字段**；`api/payment.ts:7` 只传 `{orderId, channel}` |
| miniprogram | `api/order.ts:6`；`CreateOrderPayload`（`types.ts:257-266`）无金额；`pages/checkout/index.vue:90-97` 只传 items/地址/couponId/remark |
| android | `Dtos.kt:340-348` `CreateOrderRequest` 无金额；`Api.kt:100-103` 注释"金额不随请求提交，由 core-price 实时计价" |
| ios | `CheckoutModels.swift:25-40` `CreateOrderRequest` 无金额；`PayModels.swift:61-64` `PrepayRequest(orderId, channel)` |
| web-admin | `api/product.ts:261-285` 商品改价是**管理写操作**，需鉴权 + 状态机守卫，非买家防改价范畴 |

各端结算页都有"以服务端计算为准"文案（web-buyer `CheckoutPage.tsx:224`、miniprogram 无显式文案但注释在 `pages/checkout/index.vue:66`）✅。

### B-4　SKU 矩阵（选中/禁售/缺货联动）

**[P1] `android-client/.../feature/detail/ProductDetailViewModel.kt:138-160` — 匹配与置灰逻辑都不判 `status == 1`，禁售/缺货组合在安卓端完全不可见**

```kotlin
// findMatch:138-143 —— 未过滤 status
return d.skus.firstOrNull { sku -> selected.all { (k, v) -> sku.specs[k] == v } }
// computeEnabled:149-160 —— 同样未过滤 status
map[key(group.name, value)] = d.skus.any { sku -> probe.all { (k, v) -> sku.specs[k] == v } }
```
- 后端 `ProductService.toBuyerMap:636` 每条 SKU 下发 `status`（`Sku.status`，1=上架随 SPU 同步，见 `internalSkuViews:421` 的 `onShelf` 别名）。
- 对照四端实现：
  - web-buyer `utils/sku.ts:27-29` `isSellable(sku) => sku.status === 1`，`isOptionEnabled:49` 用它过滤置灰，`ProductDetailPage.requireSku():59` 二次拦截 ✅
  - miniprogram `ec-sku-matrix.vue:54-59` `valueDisabled` 判 `sku.status === 1`，`:47` `matchedSellable` ✅
  - ios `ProductDetailViewModel.selectedSku:48-52` `guard sku.onShelf`，`isOptionDisabled:100` 同判 ✅
  - **android 两处都缺 ❌**
- 失败场景：运营把「黑色/256GB」这个 SKU 停用，web-buyer/小程序/iOS 会把「黑色」「256GB」相关组合置灰且按钮禁用；安卓端照常可选，用户点「加入购物车」→ 服务端 `CartService.requireOnShelf:311-320` 抛 3003「商品已下架」→ 用户看到的是"加购失败"而不是"该规格已售罄"。
- 建议：`findMatch` 改 `d.skus.firstOrNull { sku -> sku.status == 1 && selected.all { ... } }`；`computeEnabled` 的 `any { }` 同样加 `it.status == 1`。（注意 `sku.status` 默认值是 `1`，见 `Dtos.kt:61`，字段缺失时会误判为可售——需与后端确认是否要改成 `status: Int? = null` + `status == 1` 判定。）

**[P2] 四端"取消选择"语义不统一**

- web-buyer `utils/sku.ts:73-81` `toggleOption` 再次点击 → `delete next[specName]`（删 key）
- android `ProductDetailViewModel.selectSpec:92-95` → `selectedSpecs - name`（删 key）
- ios `ProductDetailViewModel.toggle:90-94` → `selectedSpecs[group] = nil`（值置 nil）
- **miniprogram `ec-sku-matrix.vue:62-65` → `selected.value[dimension] = ''`（置空串）**

三者都能跑，但 miniprogram 的空串需要在 `matchedSku:41` 和 `valueDisabled:58` 各加一次 `!selected.value[d.name]` 判空，漏一处就是 bug。建议四端统一为"删 key"。

### B-5　游标分页（不重不漏、换条件重置）

| 端 | 实现 | 评价 |
|----|------|------|
| web-buyer | `SearchPage.tsx:24-39` `useInfiniteQuery`，`queryKey = ['search','products',q,categoryId,sort,priceMin,priceMax]`，`getNextPageParam: last.nextCursor` | ✅ 换任一筛选条件 → queryKey 变 → 自动重置；游标透传 |
| miniprogram | `search/result.vue:35-58` `fetchPage(reset)`，`:46` `cursor: reset ? undefined : nextCursor`；`:63/68` 换排序/筛选都 `fetchPage(true)` | ✅ 逻辑正确；⚠️ `:36` `if (loading.value) return` —— 快速连点排序时后一次请求被丢弃，排序最终可能停在前一次（见 P2-4） |
| android | `SearchResultScreen` + `Api.kt:53-59` 透传 cursor | ✅ |
| ios | `SearchViewModel.search(reset:)` `:47-53` reset 时清 `results/cursor`；`:76-77` 用 `Set(spuId)` 去重 | ✅ 额外做了去重，防重复 |

**[P2] `miniprogram/src/pages/search/result.vue:36` — `loading` 守卫丢弃换条件请求**

`selectSort(i)` → `fetchPage(true)` 若上一次请求未完成则直接 `return`，而 `sort.value` 已被改掉 → 界面上高亮是新排序、列表是旧排序的数据。建议改用请求序号（seq/token）丢弃过期响应，而不是丢弃新请求。

**[P2] `miniprogram/src/subpkg/order/list.vue:43-44` — 订单列表用 `records.length < pageSize` 判末页，其余三端用 `total`**

后端 `PageResult.total` 是权威总数（`common/PageResult`）。用长度判末页在 `pageSize` 与后端默认值不一致、或后端做过滤后返回不满页时会多发一次空请求。建议改用 `res.total`（`types.ts:16` 已有 `total` 字段，只是没用）。

### B-6　金额计算

**[P2] `web-buyer/src/utils/amount.ts:16-45` — 分运算实现正确，但 `CheckoutPage.tsx:40,61` 绕过了它**

`amount.ts` 的 `lineTotal`/`sumSelected`/`payable` 都走 `toCents` 整数运算 ✅（`toCents:48-52` 对字符串金额 `Number()` 后 `Math.round(n*100)`，正确）。但：

```tsx
// CheckoutPage.tsx:40
const goodsAmount = rows.reduce((sum, r) => sum + lineTotal(r.price, r.quantity), 0);  // ✅ 用了 lineTotal
// CheckoutPage.tsx:61
const payable = Math.max(0, goodsAmount + freight - discount);   // ❌ 浮点直减，未走 amount.ts 的 payable()
```

`discount` 来自 `Number(preview.discount)`（`:60`），浮点直减有精度风险（如 `0.1 + 0.2`）。现有用例未触发（P0 运费恒 0、金额两位小数），但一旦接入运费或分摊优惠就会出现 `99.989999` 类展示。建议改用 `amount.ts:42-45` 已有的 `payable(goodsAmount, discountAmount)`。

**[P2] 四端优惠试算降级路径**

| 端 | 降级行为 | 评价 |
|----|---------|------|
| web-buyer | `CheckoutPage.tsx:45-58` try/catch → `toast.info('优惠试算暂不可用，已按无优惠结算')` + `retry:false` | ✅ 有用户可见提示 |
| miniprogram | `pages/checkout/index.vue:61-70` catch → 静默 `discount=0`，**且 `previewDisabled=true` 永不复位** | ⚠️ 见 P2-5 |
| android | `CheckoutViewModel.load():96-98` `myCoupons(0)` 失败 → `emptyList()`；注释说明"P0 无 preview 接口" | ✅ 明确降级，不阻断下单 |
| ios | `CheckoutViewModel.loadCoupons:100-102` catch → `coupons = []`；`previewCoupon:127-129` catch → `preview = nil` | ⚠️ 完全静默，用户选券后被静默丢弃（放大 P0-2 的危害） |

**[P2] `miniprogram/src/pages/checkout/index.vue:68` — `previewDisabled` 一旦置位本页永久失效**

```ts
} catch (e) {
  previewDisabled.value = true   // ← :68，没有任何复位路径
  discount.value = 0
}
```
一次网络抖动（本页 `couponApi.preview` 因任何原因失败，含 1002 被踢）后，用户在本页生命周期内再选任何券都不再试算。建议在 `pickCoupon`（`:77-80`）或 `onShow` 里复位。

### B-7　鉴权（401 处理与 refresh 单飞）

| 端 | 401/1002 处理 | refresh 单飞 | 路由守卫 | 权限码菜单过滤 |
|----|--------------|------------|---------|--------------|
| web-admin | ✅ `request.ts:170-178` HTTP 401 → 单飞刷新 + 重放；`_retried` 防死循环 | ✅ `:83-107` 模块级 `refreshing` Promise | ✅ `RouteGuard.tsx:15-34` RequireAuth + redirect | ✅ `BasicLayout.tsx:69-78` 后端菜单优先，`:101-110` `filterMenuByPermission` 兜底 |
| web-buyer | ⚠️ `client.ts:31-40` 仅判业务码 1002 → `clear()` + `window.location.assign('/login')` | ❌ 无（`authApi.refresh` 死代码） | ✅ `RequireAuth.tsx:7-12` | — |
| miniprogram | ⚠️ `client.ts:56-64` 1002/1003 → `reLaunch` 登录页 | ❌ 无 | ✅ `getCurrentPages().route` 判断避免重入 | — |
| android | ✅ OkHttp `Authenticator`（`NetworkClient.kt:74-80`） | ✅ `TokenAuthenticator.kt:50-71` `synchronized(lock)` + 复用已刷新 token | ✅ 各 Screen 检查 `session.isLoggedIn` 发 `RequireLogin` Effect | — |
| ios | ⚠️ `APIClient.swift:229-239` HTTP 401 / 业务码 1002/1003 → 刷新重试一次 | ⚠️ **有 `refreshTask` 但无同步**（见 P1-5） | ✅ `AppState.requireLogin()` 游客可浏览 | — |

web-admin 权限码与后端注册表**完全一致**（`docs:102` 列的 25 码与 `DataInitializer.java:58-85` 逐一核对通过），`menus.ts:13-41` 静态菜单与后端 `DataInitializer` 的 `path/requiredPerm` 也一致 ✅。

**[P2] `web-buyer` 与 `miniprogram` 缺失 refresh 链路** — 见 A-6（P2 项），此处不重复。

**[P2] `web-buyer/src/components/auth/RequireAuth.tsx:10` — 登录后回跳丢 query**

```tsx
return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
```
state 里带了 `search`，但 `LoginPage` 是否消费未验证；`ProductDetailPage.tsx:69,78` 又用了另一套 `state: { from: '/product/...' }`（**只带 pathname，不带 query**）。建议统一到一种（推荐 `?redirect=` query 参数，与 web-admin `RouteGuard.tsx:29` 一致）。

### B-8　mock 保真度（切 `VITE_USE_MOCK=false` 是否零改动可连）

**结论：不满足"零改动"。**

| 端 | 结论 |
|----|------|
| web-admin | ❌ **不满足**：售后 3 个函数路径与 mock 路由不匹配（P0-5）；工作台 mock 有数据、真实为 stub（A-7） |
| web-buyer | ✅ 满足：BUYER_TSC_EXIT=0，无 mock 分支代码，`VITE_API_BASE` 仅用于 baseURL |
| miniprogram | ⚠️ 基本满足：`client.ts:8` `USE_MOCK = VITE_USE_MOCK === 'true'`，切 false 即走 `realRequest`。但 `BASE_URL` 默认 `''`（`:7`），真机/模拟器需显式配 `VITE_API_BASE`，且 `uni.request` 无 devServer 代理时需小程序后台配置 request 合法域名 |

web-admin mock 优点值得肯定：`mock/index.ts:802` 信封 `{code,msg,data,traceId}`、`:441-446` `PageResult{total,pageNum,pageSize,list}`、`:812-821` 完整复刻了 `IdempotentInterceptor` 的"缺键 1001 / 重复 1005"语义、`:811-812` 正确豁免 `/api/admin/auth/*` —— 保真度在 P0 级 mock 中属优秀。

### B-9　plans 要求但未实现/降级的功能（五端清单）

| 功能项 | web-admin | web-buyer | miniprogram | android | ios |
|--------|-----------|-----------|-------------|---------|-----|
| 售后/退款中心 | ❌ 后端无接口，页面空转（P0-5） | ❌ 无入口 | ❌ 无入口 | ❌ 无入口 | ❌ Tab 不含 4/5 |
| 订单发货（管理端） | ❌ 前端无 `shipOrder`（后端有） | — | — | — | — |
| 商品审核任务中心 | ❌ 前端无 `/admin/audit/task/*` 调用 | — | — | — | — |
| 订单备注 | ⚠️ `VITE_USE_MOCK` 开关，生产隐藏 | — | — | — | — |
| 工作台/待办 | ⚠️ 后端 stub 恒 0 | — | — | — | — |
| 物流查询 | — | ❌ | ❌ | ❌ | ❌ toast「P1 接入」 |
| 订单预览 `POST /orders/preview` | — | ⚠️ 客户端本地算 | ⚠️ 客户端本地算 | ⚠️ `estimatedPayable()` | ⚠️ `subtotal` 本地算 |
| 地址省市区三级联动 | — | ❌ 手填 | ❌ 手填 | ❌ 手填 | ❌ 手填 |
| 真实支付渠道 | — | ⚠️ 仅 mockPay | ⚠️ 仅 mockPay | ⚠️ 仅 mockPay | ⚠️ 仅 mockPay |
| 签名验签（安卓 13.1） | — | — | — | ⚠️ `SIGN_ENABLED=false`，且 `secret` 恒 null（`Interceptors.kt:66`） | — |
| Refresh Token 持久化 | ✅ httpOnly Cookie（正式口径） | ⚠️ localStorage（`store/auth.ts:35`） | ⚠️ `uni.setStorageSync` 明文 | ⚠️ `SessionStore`（待查 KeyStore） | ⚠️ UserDefaults（README 已声明 P1 迁 Keychain） |
| 分页上限守卫 | ✅ `utils/query.ts:13-20` | ❌ | ❌ | ❌ | ❌ |

---

## 三、C. 代码质量

### C-1　TypeScript strict（web 三端）

**实测结论：全部 `strict: true`，且 web-admin / web-buyer `tsc -b` 实测 0 error。**

| 工程 | 配置 | 实测 |
|------|------|------|
| web-admin | `tsconfig.app.json`（`strict` + `noUnusedLocals` + `noUnusedParameters`） | ✅ `ADMIN_TSC_EXIT=0` |
| web-buyer | `tsconfig.app.json`（同上 + `verbatimModuleSyntax`） | ✅ `BUYER_TSC_EXIT=0` |
| miniprogram | `tsconfig.json` `strict: true`（未开 `noUnusedLocals`） | 未执行 `vue-tsc`（工程无该依赖），仅静态核对 |

> 这**证实了 `docs/api-contract-check.md:148-149` 的 build 通过声明**（虽然该文档的契约表本身错得很，见 P1-6）。

**[P2] `web-admin/src/api/request.ts:98` — 混用 `message` 与 `message` 双拼写防御**

```ts
throw new ApiError(body?.code ?? 401, body?.message ?? body?.msg ?? '刷新登录失败');
```
`ApiResponse` 类型只有 `msg`（`types/api.ts`），`body?.message` 永远 `undefined`；`:132` 的 `extractMessage` 同样 `'msg' in body || 'message' in body`。这是 plans 旧口径（`message`）的残留。后端 `ApiResponse.java:10` 明确是 `msg`。建议清理，避免后人误以为后端有两套字段名。

**[P2] `web-buyer/src/api/client.ts:46-50` — 信封解析的 `return fail(...)` 在 `try` 内，`BizError` 会被自catch 一次**

```ts
try {
  ...
  if (envelope.code === BIZ_CODE.OK) return envelope.data;
  return fail(envelope);          // 抛 BizError
} catch (e) {
  if (e instanceof BizError) throw e;   // 立即原样抛出
  ...
}
```
逻辑正确（`instanceof` 分支先命中），但 `fail()` 在 `try` 内抛出会让 `stack` 多一层，且未来有人把 `BizError` 改成 `Error` 子类就会走进"网络异常"分支。建议把信封解包挪出 `try`，`try` 只包网络调用。

**[P2] `web-buyer/src/pages/payment/PayPage.tsx:38-43` — 轮询 `setTimeout` 链无取消，组件卸载后继续发请求**

```ts
for (let i = 0; i < 10; i++) {
  const status = await paymentApi.status(id);
  if (status.status === 'paid') return;
  await new Promise((r) => setTimeout(r, 500));   // ← 无 AbortSignal / 无 unmount 守卫
}
```
用户点「确认支付」后立即返回，循环仍会跑满 10 次（5 秒）发 10 个 `GET /payment/status/{id}`。对比 miniprogram `subpkg/order/pay.vue:55-66` 与 iOS `PayViewModel.poll():111`（`while !Task.isCancelled`）都有生命周期感知。建议 web-buyer 用 `AbortController` + `useEffect` cleanup。

**[P2] `web-buyer/src/pages/cart/CartPage.tsx:61-64` — 单行勾选写入的是「切换前」的集合，UI 会闪回**

```tsx
const onToggleOne = (cartItemId: number, selected: boolean) => {
  setChecked(new Set(selectedIds));   // ← 旧集合，不是 selected 切换后的集合
  toggleOne.mutate({ cartItemId, selected });
};
```
`selected` 来自 `e.target.checked`（新值），但本地 `checked` 写的是旧集合 → 请求返回前该行 checkbox 回弹到原状态，靠 `onSuccess: refreshCart` 的服务端回刷纠正。miniprogram `pages/cart/index.vue:41-45` 同样模式（写接口后 `await load()`）。建议本地先按 `selected` 写入新状态再发请求。

**[P2] web-admin 多页缺错误态/重试入口**

`order/list.tsx:37-41`、`product/list.tsx:56-60`、`user/list.tsx:52-57` 等的 `useQuery` 只取 `data` / `isLoading`，`isError` 未处理。请求失败时 `request.ts:185` 弹一次 toast，页面停留在**空表格**且没有重试按钮，用户无法区分"没有数据"和"接口挂了"。建议统一加 `isError` → `<Result status="error" extra={<Button onClick={refetch}>重试</Button>}/>`。

**[P2] `android-client/.../feature/pay/PayViewModel.kt:83` — `paid = d.status != 0` 把取消/关闭也判成已支付**

```kotlin
paid = d.status != 0,   // 0=待付款（OrderStatus.UNPAID）
if (currentState.paid) startPolling()
```
0 基状态机里 6已取消 / 7已关闭 / 4退款中 都不是 0 → 用户点进已取消订单的支付页，会看到"已支付"并启动最长 3 分钟的轮询（`:123` `while (waited <= MAX_WAIT_MS)`），最终弹"支付结果确认超时，请稍后在订单列表查看"。iOS 对应位置 `PayViewModel.load():48` 用 `order.status == OrderStatus.paid.rawValue`（**精确判 1**）是对的。建议 Android 也改成 `d.status == 1`。

### C-2　Kotlin：MVI / 协程 / Compose / 序列化

**实测：`:app:compileDebugKotlin` BUILD SUCCESSFUL（Gradle 8.2 + AGP 8.2.2 + Kotlin 1.9.22 + JDK17，1m41s，0 error）。**

- **MVI 单向数据流 ✅**：`core/mvi/MviViewModel.kt:31-33` `setState` 用 `MutableStateFlow.update{}`（CAS 原子读改写，多线程 intent 安全）；`:26-27` Effect 走 `Channel(BUFFERED).receiveAsFlow()`（不丢事件，不进 State）——设计正确。
- **协程取消 ✅**：`OrderListViewModel.kt:70,96` `loadJob?.cancel()`；`PayViewModel.kt:117` `pollJob?.cancel()`；`SearchViewModel.kt:44,63` `suggestJob?.cancel()`；全部 `viewModelScope.launch` 随 VM 销毁自动取消。
- **Compose 重组安全**：`messageId: Int` 自增计数模式（`ProductDetailUiState.messageId`、`CheckoutUiState.messageId`）避免"相同文案不触发重组"的经典坑 ✅。
- **`AmountSerializer` 正确性 ⚠️**：
  ```kotlin
  @Serializer(forClass = String::class)          // Serialization.kt:27
  object AmountSerializer : KSerializer<String> { ... }
  ```
  编译器明确警告：*"@Serializer annotation has no effect on class 'AmountSerializer', because all members of KSerializer are already overridden"*（该注解只对**外部/内部类**的自定义 serializer 有意义，对已完整实现 `KSerializer` 的 object 是冗余）。同时 `:27` 与 `:50`（`coerceInputValues`）都需要 `@OptIn(ExperimentalSerializationApi::class)`。功能上 `deserialize:31-37` 的宽松解析（JsonPrimitive / JsonNull → "0"）是正确的，但建议删掉误导性的注解并加 opt-in 注解。
- 其它编译警告（非阻断）：`NetworkClient.kt:104` 多余的 `as? T` 强转；6 处 `Icons.Filled.ArrowBack` 已废弃（应换 `Icons.AutoMirrored.Filled.ArrowBack`，否则 RTL 布局箭头方向错）；5 处 `ViewModelProvider.Factory` 的 `Unchecked cast: XxxViewModel to T`（可用 `modelClass.cast()` 消除）。

### C-3　React hooks / 定时器 / 错误边界

| 项 | 状态 |
|----|------|
| `web-admin` 定时器 | 仅 `localStorage.setItem`（`BasicLayout.tsx:80-82`），无泄漏 |
| `web-buyer` 定时器 | `useCountdown.ts:14-16` 有 `clearInterval` ✅；`PayPage` 轮询 ❌（见 C-1） |
| `miniprogram` 定时器 | `utils/countdown.ts:37-45` `start` 先 `stop()` + `onUnmounted(stop)` ✅ |
| 订阅清理 | web-buyer/miniprogram 无 `addEventListener` 类订阅；iOS `RichTextView` 的 `WKNavigationDelegate` 由 SwiftUI 持有 ✅ |
| 错误边界 | web-admin `components/ErrorBoundary.tsx` 全局 ✅；web-buyer **无 ErrorBoundary**（见下） |
| 空态/加载态 | web-buyer 较完整（`HomePage`/`CartPage`/`SearchPage` 都有 `isLoading` + 空态 + 跳转）；miniprogram 用 `ec-empty` 组件 ✅；web-admin 缺错误态（C-1） |

**[P2] `web-buyer/src/main.tsx` — 无全局 ErrorBoundary，页面级异常会白屏整个 SPA**

web-admin 有 `App.tsx:28` `<ErrorBoundary>` 包裹，web-buyer 的 `main.tsx` 需确认（本审计未逐行确认，标记为待核实）。React Router v7 的 `errorElement` 也未配置（对比 `web-admin/config/routes.tsx:52,62` 每个路由都有 `errorElement: lazyPage(ServerError)`）。建议对齐 web-admin。

### C-4　三端一致性（web-buyer ↔ miniprogram ↔ android ↔ ios）

**API 路径：完全统一 ✅**（去掉各自 base 前缀后逐条比对）：

| 域 | web-buyer | miniprogram | android | ios |
|----|-----------|-------------|---------|-----|
| 登录 | `/v1/auth/login` | `/api/v1/auth/login` | `api/v1/auth/login` | `/api/v1/auth/login` |
| 购物车 | `/v1/cart/{list,add,update,remove,select}` | 同 | 同 | `/api/v1/cart`、`/api/v1/cart/{id}` PUT/DELETE、`/cart/select`（RESTful 别名，core-cart `CartController.java:48,71,93` 确认等价 ✅） |
| 下单 | `/v1/orders` | 同 | 同 | 同 |
| 券 | `/v1/coupons/{my,preview}` | 同 | 仅 `my` | `/api/v1/coupons/{my,preview}` |
| 搜索 | `/v1/search/{products,suggestions,hot}` | 同 | 同 | 同 |

**差异项**：
1. 分页参数名：web-buyer/miniprogram/android 用 `page`+`pageSize`（后端 `OrderController.java:63-64`、`BuyerProductController.java:39-40` ✅）；**web-admin 商品列表用 `page`**（后端 `AdminProductController.java:46` ✅）而**订单列表用 `pageNum`**（后端 `core-order AdminOrderController.java:55` ✅）——后端本身不统一，前端各自适配正确。
2. 状态映射：0 基一致 ✅（见 B-1）。
3. 金额口径：四个买家端**统一用「元」**（后端 `BigDecimal` → JSON number/string），无「分」混用；web-admin 单独用「分」并在 API 层换算（`api/order.ts:90-94`、`api/product.ts:152-161`），换算正确（`Math.round(Number(yuan) * 100)`）✅。
4. 券状态过滤：web-buyer `CheckoutPage.tsx:44` 前端过滤 `c.status === 'available'`；miniprogram `pages/checkout/index.vue:154,157` 同样前端过滤；android `myCoupons(0)`、ios `status=0` 由**后端过滤**。两种做法都对（后端 `CouponUserController.java:76-81` 支持 `Integer status`），但口径不统一。

---

## 四、iOS 编译风险评级（逐文件）

> ios-client 在 Windows 上开发，**无法编译**（README 已声明）。以下为逐文件静态过审结果。

**总体评级：🟡 中低风险 —— 预计首次 Xcode 编译 error 数 0~3，无结构性语法错误；但 iOS 的真正风险是 3 个 P0 运行时类型错（编译发现不了）。**

| 文件 | 风险 | 说明 |
|------|------|------|
| `Features/Order/OrderListView.swift:9,14-18` | 🟡 中 | `@StateObject private var vm = OrderListViewModel()`（`:9`）与 `init` 里 `_vm = StateObject(wrappedValue: {...}())`（`:14-18`）**双重初始化属性包装器 backing store**。Swift 5 语言模式下 struct 的 `var` 可重新赋值，预计可编过，但每次 `init` 白建一个 VM 对象，且与同文件 `OrderDetailView.swift:8,14` 与 `CheckoutView.swift:8,15` 的规范写法不一致。**规范改法**：`@StateObject private var vm: OrderListViewModel`（不给默认值）。 |
| `Core/Network/APIClient.swift:94-121` | 🟡 中 | `final class APIClient` 是**非 Sendable 单例 + 可变状态**（`accessToken`、`refreshTask`、`storedRefreshToken`），被多个 `@MainActor` VM 跨 `await` 访问。`project.yml:11` 设 `SWIFT_VERSION: "5.0"` 且**未设 `SWIFT_STRICT_CONCURRENCY`** → Xcode 15/16 默认 minimal，能编。一旦切 Swift 6 语言模式或开 `complete`，`actor` 隔离与 `Sendable` 会成片报错。建议现在就改 `actor APIClient`（成本低、防未来重写）。 |
| `Core/Network/APIClient.swift:253` | 🟢 低 | `Task<TokenPayload?, Never> { await APIClient.performRefresh(baseURL: base, refreshToken: refresh) }` —— `performRefresh` 是 `private static`，不捕获 `self`；`URLRequest` 在 iOS 16 SDK 下是 `Sendable`。可编。 |
| `Core/Network/APIClient.swift:230` | 🟢 低 | `if retryOnAuth, status == 401 \|\| envelope.code == 1002 \|\| ...` —— Swift 的逗号是 condition-list 分隔符，`\|\|` 绑定更紧，实际解析为 `(retryOnAuth), (status==401 \|\| code==1002 \|\| code==1003)`，**语义正确**（易被误读为运算符优先级 bug，特此确认无问题）。 |
| `Core/Network/APIClient.swift:168` | 🟢 低 | `if !(body is EmptyBody)` —— 泛型值对具体类型做 `is` 检查合法。 |
| `Core/Network/APIClient.swift:278-281` | 🟢 低 | 函数体内声明局部 `struct RefreshBody: Encodable` 合法（`performRefresh` 非泛型）。 |
| `App/AppRouter.swift:5-20` | 🟢 低 | `enum Route: Hashable` 关联值含 `[CheckoutItem]`；`CheckoutItem: Hashable`（`CheckoutModels.swift:13`）含 `Decimal?`（`Decimal: Hashable` ✅）。 |
| `App/AppRouter.swift:80-103` | 🟢 低 | `NavigationHost<Root: View>` + `NavigationStack(path: Binding<[Route]>)`，`[Route]` 满足 `MutableCollection & RandomAccessCollection & Hashable Element` ✅。 |
| `App/AppRouter.swift:106-127` | 🟢 低 | `RouteDestination` 7 个 case 的初始化签名与实际视图逐一核对一致：`ProductDetailView(spuId:)`（`:11`）、`CheckoutView(items:)`（`:13`）、`PayView(orderId:)`（`:12`）、`OrderListView(initialStatus:)`（`:12`）、`OrderDetailView(orderId:)`（`:12`）、`AddressListView(selecting:)`（`:6` 合成成员初始化）、`AddressEditView(addressId:)`（`:13`）。**全部匹配 ✅** |
| `App/EasyShoppingApp.swift:5` | 🟢 低 | `@StateObject private var appState = AppState.shared`，`AppState` 是 `@MainActor`。`App` 的 `body` 是 `@MainActor`，但 struct 的属性初始化表达式不在隔离上下文里 → Swift 5 minimal 下最多 warning。 |
| `App/EasyShoppingApp.swift:69` | 🟢 低 | `.onChange(of:perform:)` 是 iOS 16 单参数版本 ✅（iOS 17 SDK 下会 deprecation warning，不影响构建）。 |
| `Core/UI/Components.swift:159` | 🟢 低 | `TimelineView(.periodic(from: .now, by: 1))` iOS 15+ ✅。 |
| `Core/UI/RichTextView.swift` | 🟢 低 | `UIViewRepresentable` + `WKWebView`；`makeCoordinator()` 返回 `NSObject & WKNavigationDelegate` ✅；`navigationDelegate` 是 weak 引用，由 SwiftUI 持有 Coordinator ✅。 |
| `Features/Order/OrderListView.swift:194` | 🟢 低 | `@ViewBuilder` 函数体内先 `let title = ...` 再 `Button {}`：Swift 5.4+ 的 result builder 允许局部声明 ✅。 |
| `Features/User/UserModels.swift:7-41` | 🟢 低 | `Address` 声明了自定义 `init(from:)` → **成员初始化器被抑制**，但全项目无人手工构造 `Address`（grep 确认），只从 JSON 解码 ✅。 |
| `Features/Pay/PayViewModel.swift:129-130` | 🟢 低 | `try? await Task.sleep(nanoseconds: delay)` + `delay = min(delay * 2, 4_000_000_000)`，`UInt64` 字面量在范围内 ✅。 |
| `project.yml` | 🟡 中 | 建议补 `SWIFT_STRICT_CONCURRENCY: minimal`（显式声明）与 `DEVELOPMENT_TEAM` 占位，减少首次构建的签名噪音。 |

---

## 五、验证记录（本审计实测）

| 验证项 | 命令 | 结果 |
|--------|------|------|
| Android 编译 | `gradle :app:compileDebugKotlin`（Gradle 8.2 / AGP 8.2.2 / Kotlin 1.9.22 / JDK17 / compileSdk 34） | ✅ **BUILD SUCCESSFUL**（1m41s，0 error，16 warning） |
| web-admin 类型检查 | `npx tsc -b --pretty false` | ✅ **0 error** |
| web-buyer 类型检查 | `npx tsc -b --pretty false` | ✅ **0 error** |
| miniprogram 类型检查 | — | ⏭ 未执行（工程未装 `vue-tsc`），仅静态核对 |
| iOS 编译 | — | ⏭ 无法执行（Windows 无 Xcode/Swift 工具链），见第四章 |
| 后端契约基准 | 逐个读取 40 个 Controller + 12 个 DTO | ✅ 完成 |

**Android 编译警告清单**（均非阻断）：
`Serialization.kt:27` `@Serializer` 无效 + 需 opt-in；`Serialization.kt:50` 需 opt-in；`NetworkClient.kt:104` 多余强转；`Common.kt:53` / `CheckoutScreen.kt:92` / `ProductDetailScreen.kt:107` / `OrderDetailScreen.kt:163` / `OrderListScreen.kt:80` / `PayScreen.kt:102` 共 6 处 `ArrowBack` 已废弃；`OrderDetailScreen.kt:132` / `OrderListViewModel.kt:175` / `AddressEditScreen.kt:138` / `AddressListScreen.kt:135` 共 4 处 Unchecked cast。

---

## 六、总体结论

1. **契约层面：4/5 端合格，iOS 端有 3 处致命类型错配。** web-admin / web-buyer / miniprogram / android 的请求路径、方法、参数名、响应字段与后端 Controller/DTO **逐条核对基本正确**，适配层（web-admin 的元↔分换算、字段名映射）做得扎实。**iOS 端 `payParams`（Map→String）、`expireAt`（Long→String）、`CheckoutCoupon.status`（String→Int）、`CouponPreviewRequest.amount`（List→标量）四处类型声明与后端相反**，且都被 `try?` + `catch` 静默吞掉，表现为"支付页永远失败""优惠券区永远空白""选券无效但无提示"，属于典型的编译可过、联调必炸。

2. **业务口径层面：C7（不硬编码超时）、防改价（不传金额）、0 基 8 值状态机三项硬口径五端全部达标**，这是本项目最扎实的部分。缺口在**边缘一致性**：订单 Tab 覆盖四端各不相同、SKU 禁售联动安卓独有缺失、幂等键"重试复用"只有安卓实现。

3. **工程质量层面：三端 TS strict 且类型检查 0 error，Android 实测编译通过**，MVI 基座（StateFlow CAS + Channel Effect）、倒计时 hook 清理、错误边界等基础设施到位。主要债务集中在：web-admin 的 `api-contract-check.md` 契约表（10 处错误标注，会持续误导后续开发）、web-buyer 缺 ErrorBoundary 与 refresh 链路、以及 iOS 的并发安全债务（切 Swift 6 会成片报错）。

---

## 七、修复优先级清单（含工作量）

### 第一批 · P0（契约断裂，必须修，约 2.5 人日）

| # | 端 | 文件:行 | 修复动作 | 工作量 |
|---|----|---------|---------|--------|
| 1 | iOS | `PayModels.swift:39-40` | `payParams: [String: String]?`、`expireAt: Int64?`；补 prepay 响应解码单测 | **0.5 人日** |
| 2 | iOS | `CheckoutModels.swift:61` | `status: String?`；同步检查 `CheckoutView` 是否按字符串判可用态 | **0.25 人日** |
| 3 | iOS | `CheckoutModels.swift:71` | `amount: [Decimal]`；`CheckoutViewModel:120` 改传 `[subtotal]`；补"试算失败要有可见提示" | **0.5 人日** |
| 4 | miniprogram | `pages/cart/index.vue:70-72` + `:125,127` | 参数名改 `spuId`、入参改用 `l.productId` | **0.25 人日** |
| 5 | web-admin | `api/order.ts:216-227` + `menus.ts:27` + `routes.tsx:71` + `docs/api-contract-check.md:120` | 三选一：补 `/admin/` 前缀并在真实模式隐藏入口 / 补后端 Controller / 明确标注 stub。同步修正文档 | **1 人日**（含联调验证） |

### 第二批 · P1（完备性缺口，建议 1 个迭代内，约 5 人日）

| # | 端 | 项 | 工作量 |
|---|----|----|--------|
| 6 | web-buyer | `client.ts:25` 补 `crypto.randomUUID` 降级（照搬 `web-admin/request.ts:50-57`） | 0.25 人日 |
| 7 | web-buyer + miniprogram | 下单幂等键「同一次提交重试复用」 | 0.5 人日 |
| 8 | iOS | `APIClient.swift:246-270` refresh 单飞改 `actor` 门闩 | 0.5 人日 |
| 9 | web-admin | 重写 `docs/api-contract-check.md`（以 `src/api/*` 为准重新生成 30 项表） | 0.5 人日 |
| 10 | android | `ProductDetailViewModel.findMatch:138` / `computeEnabled:154` 补 `status == 1` 过滤 | 0.5 人日 |
| 11 | 四端 | 订单 Tab 统一到 0~7（miniprogram 抽单点常量） | 0.5 人日 |
| 12 | web-admin | `api/product.ts:255-287` SKU 全量替换副作用（skuId 变更）与 `costPrice` 被清空 —— 与后端确认 + 前端补 `cost:read` 守卫 | 1 人日（需后端配合） |
| 13 | web-admin | 多页补 `isError` 错误态 + 重试按钮 | 0.5 人日 |

### 第三批 · P2（改进项，约 3 人日）

| # | 项 | 工作量 |
|---|----|--------|
| 14 | web-buyer 补 ErrorBoundary + 每路由 `errorElement`（对齐 web-admin） | 0.5 人日 |
| 15 | web-buyer / miniprogram 补 refresh 链路（单飞 + 重放），消除「每 2 小时强制重登」 | 1 人日 |
| 16 | `CheckoutPage.tsx:61` 改用 `amount.ts:42` 的 `payable()`；miniprogram `previewDisabled` 复位；miniprogram 订单列表改用 `total` 判末页；`search/result.vue:36` 改用请求序号 | 0.5 人日 |
| 17 | `PayPage.tsx:38` 轮询加取消；`CartPage.tsx:61` 本地勾选即时回显 | 0.25 人日 |
| 18 | android：删 `@Serializer` 无效注解 + 加 `@OptIn`；6 处 `ArrowBack` 换 `AutoMirrored`；4 处 `Factory` unchecked cast | 0.5 人日 |
| 19 | android `PayViewModel.kt:83` 改精确判 `status == 1` | 0.1 人日 |
| 20 | iOS：`OrderListView.swift:9` 改规范 StateObject 写法；`APIClient` 改 `actor`；`project.yml` 补 `SWIFT_STRICT_CONCURRENCY` | 0.5 人日（可与 #1-3 同批） |
| 21 | 清理 `web-admin/request.ts:98,132` 的 `message` 死分支；mock 工作台对齐 stub | 0.25 人日 |

**合计：P0 约 2.5 人日、P1 约 5 人日、P2 约 3.5 人日。**

**建议执行顺序**：先做 iOS #1~#3（Mac 上一次联调即可全部暴露，但修复成本极低且收益是"支付/优惠券两条链路从不可用到可用"）→ miniprogram #4（一行修复，用户可感）→ web-admin #5 → 再统一处理 #6~#9（鉴权与幂等链路，跨端同构，可一次性对齐）。

---

## 附：发现索引

| 编号 | 级别 | 端 | 标题 |
|------|------|----|------|
| P0-1 | P0 | ios | `PrepayResponse.payParams`/`expireAt` 类型错 → 支付页 100% 失败 |
| P0-2 | P0 | ios | `CouponPreviewRequest.amount` 标量 vs 数组 → 券试算永久失败且静默丢弃用户所选券 |
| P0-3 | P0 | ios | `CheckoutCoupon.status: Int?` vs 英文枚举 → 优惠券区永远为空 |
| P0-4 | P0 | miniprogram | 购物车跳详情参数 `skuId` vs `spuId` → 详情页永久白屏 |
| P0-5 | P0 | web-admin | 售后接口路径少 `/admin` → 退款页取不到数据；文档声称"已隐藏"与代码不符 |
| P1-1 | P1 | web-buyer | `crypto.randomUUID()` 无降级 → 非安全上下文写接口全挂 |
| P1-2 | P1 | web-buyer/miniprogram | 幂等键每次新生成 → 重试可能重复下单（安卓已修） |
| P1-3 | P1 | ios | refresh 单飞竞态 → 多 401 并发刷新轮转 → 误踢登录 |
| P1-4 | P1 | web-admin | `api-contract-check.md` 32 项表 10 处实证错误 |
| P1-5 | P1 | 四端 | 订单 Tab 覆盖范围不一致（8 / 5 / 4 / 4） |
| P1-6 | P1 | android | SKU 匹配与置灰不判 `status`，禁售联动缺失 |
| P1-7 | P1 | web-admin | `updateProduct` 全量 SKU 替换 → skuId 变更 / costPrice 被清空 |
| P2-1 | P2 | web-buyer/miniprogram | refresh 链路死代码，2 小时强制重登 |
| P2-2 | P2 | web-buyer/miniprogram | `mockPay` 响应类型多声明 `orderId` |
| P2-3 | P2 | web-admin | mock 工作台比真实后端"更美化" |
| P2-4 | P2 | miniprogram | `search/result.vue:36` loading 守卫丢弃换条件请求 |
| P2-5 | P2 | miniprogram | `previewDisabled` 永不复位 |
| P2-6 | P2 | miniprogram | 订单状态无 enum 单点定义 |
| P2-7 | P2 | web-buyer | `CheckoutPage` payable 绕过 `amount.ts` 分运算 |
| P2-8 | P2 | web-buyer/miniprogram | 登录回跳参数两套并存 |
| P2-9 | P2 | web-admin | `request.ts` 残留 `message` 死分支 |
| P2-10 | P2 | web-admin | 多页缺 `isError` 错误态与重试 |
| P2-11 | P2 | web-buyer | `PayPage` 轮询无取消 |
| P2-12 | P2 | web-buyer/miniprogram | `CartPage` 勾选本地态回弹 |
| P2-13 | P2 | android | `PayViewModel` 把已取消订单判为已支付 |
| P2-14 | P2 | android | `@Serializer` 无效注解 / opt-in / 废弃图标 / unchecked cast |
| P2-15 | P2 | ios | `OrderListView` StateObject 双重初始化 |
| P2-16 | P2 | ios | `APIClient` 非 Sendable，切 Swift 6 会成片报错 |
| P2-17 | P2 | 四端 | 分页参数名/上限守卫不统一 |
| P2-18 | P2 | 四端 | 券状态过滤口径（前端过滤 vs 后端过滤）不统一 |