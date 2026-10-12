# EasyShopping iOS 客户端（ios-client）

买家端 iOS App，P0 核心购物流闭环。**Swift + SwiftUI，零第三方依赖**（无 SPM/CocoaPods，仅用
Foundation / SwiftUI / WebKit / UIKit 系统框架），最低支持 iOS 16.0。

> ⚠️ **编译状态声明：未经 Xcode 编译验证。**
> 本项目在 Windows 环境下开发，当前机器**没有安装 Xcode / Swift 工具链**（已确认 `swift`、
> `swiftc` 不存在），因此**无法执行编译**。交付前已做结构化自查（见文末「编译自查清单」），
> 但**首次在 Mac 上编译时仍可能出现需要修复的编译错误**，请按下方「Mac 构建步骤」执行并对照
> 自查清单逐项确认。

## 一、环境要求

| 项 | 要求 |
|----|------|
| 系统 | macOS（Xcode 只能跑在 macOS） |
| Xcode | 15.x 及以上（iOS 16.0 SDK；Swift 5.9+ 工具链按 Swift 5 模式编译） |
| XcodeGen | `brew install xcodegen`（生成 `EasyShopping.xcodeproj`） |
| 后端 | 按仓库根目录 `DEV.md` 启动 `server/` 各服务 + `gateway`（:8080） |

## 二、Mac 构建步骤

```bash
# 1. 进入本目录
cd ios-client

# 2. 生成 Xcode 工程（project.yml → EasyShopping.xcodeproj，工程文件不入库）
brew install xcodegen   # 已安装可跳过
xcodegen generate

# 3. 打开工程
open EasyShopping.xcodeproj
```

Xcode 中：

1. 选择 target **EasyShopping** → Signing & Capabilities → 真机调试时选择自己的 Team（**模拟器构建不需要签名**）。
2. 选择模拟器（iPhone 15 / iOS 16+）→ **⌘R** 运行。
3. 若首次编译报错，对照文末「编译自查清单」逐项排查；本项目声明的常见风险点都写在各文件头注释里。

## 三、后端联调

### 启动服务

按仓库根目录 `DEV.md`（「服务一览」「启动顺序」章节）启动业务服务，**最后启动 gateway（:8080）**：

```bash
# 例（Windows 开发机，Maven 全路径见 DEV.md 工具表）
C:\D\tools\maven\bin\mvn.cmd -q -DskipTests package
java -jar server/core-user/target/core-user-0.1.0-SNAPSHOT.jar
# ……其余 core-* 服务同理，最后
java -jar server/gateway/target/gateway-0.1.0-SNAPSHOT.jar
```

自检：`curl http://localhost:8080/actuator/health`（gateway）应返回健康信息。

### 客户端指向

| 场景 | baseURL | 说明 |
|------|---------|------|
| 模拟器 | `http://localhost:8080`（默认值） | 见 `Core/Network/APIClient.swift` 顶部 `baseURL` |
| 真机 | `http://<开发机局域网IP>:8080` | 修改 `baseURL`；**HTTP 明文会被 ATS 拦截**，见下 |
| 生产 | `https://域名` | 上线前必须切 https |

- 模拟器直连本机：`Info.plist` 已开 `NSAllowsLocalNetworking`，无需改 ATS。
- **真机联调**：NSAllowsLocalNetworking 覆盖的是 localhost / `.local`，**不含局域网 IP 明文 HTTP**。
  临时做法：Debug 包给 `NSAppTransportSecurity` 加 `NSAllowsArbitraryLoads = YES`；
  正式包一律走 HTTPS（提审包禁止全局放行，口径见 `plans/ios-frontend.md` 2.3）。
- 已知环境问题：本机 WSL 的 `wslrelay` 占用 IPv6 回环 8081，会导致 core-user 启动失败，
  用 `--server.port=18081` 覆盖并同步改 gateway 路由（DEV.md「本机注意」）。

### 关键契约（已在代码中对齐，改动前先查 `architecture/00-conflict-register.md`）

- 响应信封 `{code,msg,data,traceId}`，HTTP 200 也可能业务失败 → **先解信封再看状态码**（APIClient 已实现）。
- 分页 `{total,pageNum,pageSize,list}`；字段 camelCase；金额一律 `Decimal`（**禁止 Double**）。
- **解码失败禁止 `try?` 静默**：`APIClient` 会把 DecodingError 的字段路径与响应体片段抛到 UI，
  用于第一时间暴露"模型字段类型与后端相反"这类编译期发现不了的契约错（见七章）。
- 后端下发的**枚举字符串不得当数字解**：订单状态是 0 基 8 值 `Int`（`OrderStatus` 单点定义），
  券状态是英文串 `available/used/expired`（`CouponStatus` 单点定义）。
- 订单状态 **0 基 8 值**：0待付款/1待发货/2待收货/3已完成/4退款中/5已退款/6已取消/7已关闭，
  唯一映射点 `Features/Order/OrderModels.swift` 的 `OrderStatus`。
- 支付超时读订单 `payDeadline`（ISO8601），**不硬编码分钟数**（裁决 C7）。
- 写接口（非 GET）在 APIClient 内自动带 `Idempotency-Key: UUID`；401/1002/1003 → 单飞刷新 Token 后**只重试一次**。
- 错误提示直接展示服务端 `msg`（3002 库存不足、券失效等），前端不改写文案。
- Access Token 仅存内存，Refresh Token 持久化于 UserDefaults（P1 应迁 Keychain，见 plans/ios-frontend 3.2）。

## 四、目录结构

```
ios-client/
├── project.yml                     # XcodeGen 配置（bundle id / iOS 16.0 / Swift 5）
├── README.md
├── .gitignore                      # 忽略 EasyShopping.xcodeproj / build 等
└── EasyShopping/
    ├── App/                        # 入口与全局状态
    │   ├── EasyShoppingApp.swift   # @main + RootView（登录 sheet / 会话失效收敛）+ MainTabView
    │   ├── AppState.swift          # 登录态 / 购物车角标 / 结算页选中地址
    │   └── AppRouter.swift         # Route 枚举 + 每 Tab 独立 NavigationStack + RouteDestination 映射
    ├── Core/
    │   ├── Network/APIClient.swift # 唯一网络入口：信封解码 / Token 刷新重试 / 幂等键
    │   ├── UI/                     # AppTheme+Money、通用组件（倒计时/Toast/步进器/空态）、RemoteImage、RichTextView(WKWebView)
    │   └── Utils/                  # ISODate（ISO8601 解析）、RemoteURL
    ├── Features/                   # 页面（View + ViewModel + Models 按功能分目录）
    │   ├── Auth/                   # 登录 / 注册
    │   ├── Home/                   # 首页（商品流 + 热搜词）
    │   ├── Search/                 # 搜索（联想 + 历史 + cursor 分页结果）
    │   ├── Detail/                 # 商品详情（SKU 规格矩阵）
    │   ├── Cart/                   # 购物车
    │   ├── Checkout/               # 结算（地址 / 优惠券试算 / 提交订单）
    │   ├── Pay/                    # 支付（mock 支付 + 状态轮询）
    │   ├── Order/                  # 订单列表（状态 Tab）+ 订单详情（倒计时 / 取消 / 确认收货）
    │   └── User/                   # 我的 + 收货地址管理
    └── Resources/
        ├── Info.plist              # ATS（仅放行本地网络）/ 竖屏 / 启动图
        └── Assets.xcassets         # AccentColor（品牌红）/ AppIcon 占位
```

## 五、页面清单 ↔ 接口映射（P0）

| 页面 | 文件 | 依赖接口 |
|------|------|----------|
| 登录/注册 | `Auth/` | `POST /api/v1/auth/login`、`POST /api/v1/auth/register`（P0 固定验证码 123456）、`POST /api/v1/auth/refresh`、`POST /api/v1/auth/logout` |
| 首页 | `Home/` | `GET /api/v1/products?page&pageSize`、`GET /api/v1/search/hot` |
| 搜索 | `Search/` | `GET /api/v1/search/products?q&cursor&pageSize`、`GET /api/v1/search/suggestions?q&limit` |
| 商品详情 | `Detail/` | `GET /api/v1/products/{spuId}`（specs + skus 矩阵）、`POST /api/v1/cart` |
| 购物车 | `Cart/` | `GET /api/v1/cart`、`PUT /api/v1/cart/{cartItemId}`、`DELETE /api/v1/cart/{cartItemId}`、`POST /api/v1/cart/select` |
| 结算 | `Checkout/` | `GET /api/v1/user/addresses`、`GET /api/v1/coupons/my`、`POST /api/v1/coupons/preview`、`POST /api/v1/orders` |
| 支付 | `Pay/` | `POST /api/v1/payment/prepay`、`POST /api/v1/payment/mock/pay`、`GET /api/v1/payment/status/{orderId}`（1s/2s/4s 退避轮询，30s 上限） |
| 订单列表 | `Order/OrderListView` | `GET /api/v1/orders?status&page&pageSize`（Tab：全部/0/1/2/3） |
| 订单详情 | `Order/OrderDetailView` | `GET /api/v1/orders/{orderId}`、`POST /api/v1/orders/{id}/cancel`、`POST /api/v1/orders/{id}/confirm` |
| 我的 | `User/ProfileView` | `GET /api/v1/user/profile` |
| 收货地址 | `User/Address*` | `GET/POST/PUT/DELETE /api/v1/user/addresses[/{id}]`、`POST /api/v1/user/addresses/{id}/default` |

游客可浏览首页/搜索/详情；加购、下单、看个人信息、看订单时前置弹登录（plans/ios-frontend 2.1）。

## 六、P0 降级清单（服务端未提供，客户端降级方案）

| 计划项 | P0 实际 | 位置 |
|--------|---------|------|
| iOS BFF 首页聚合 `GET /api/v1/bff/ios/home` | 未实现 → 首页降级为「商品列表 + 热搜词」两接口 | `Home/` |
| 订单预览 `POST /api/v1/orders/preview` | 未实现 → 结算金额客户端本地计算，提交以服务端计价为准 | `Checkout/` |
| 促销/优惠券抵扣 | core-order P0 促销降级（试算不折扣）→ 券试算仅展示；**仅 `eligible=true` 时提交携带 `couponId`**；选了券但 `eligible=false`/试算失败时**必须弹确认**，用户明确同意才按原价下单（禁止静默丢券） | `Checkout/CheckoutViewModel` |
| 物流查询 | 未实现 → 「查看物流」toast 提示 P1 接入 | `Order/` |
| 真实支付渠道（支付宝/微信等） | core-payment mock → `POST /payment/mock/pay` 直接回调成功 | `Pay/` |
| 短信验证码 | 固定 `123456` | `Auth/` |
| 售后 Tab（退款中/已退款 4、5） | 列表 `status` 仅支持单值查询 → Tab 只做 0~3 | `Order/OrderListView` |
| 点选地址省市区三级联动 | P0 手填省/市/区文本（服务端地址无 region 库接口） | `User/AddressEditView` |
| 图片磁盘缓存/预取 | 用系统 `AsyncImage`，P1 再引入图片库 | `Core/UI/RemoteImage` |
| Refresh Token 入 Keychain | P0 存 UserDefaults，P1 迁移 | `APIClient` / `AppState` |

## 七、P0 契约修复记录（2026-10-12，前端审计 AUDIT/02-frontend.md P0-1~P0-4）

> 背景：本项目在 Windows 开发，**无法编译**（无 Xcode/Swift 工具链），所以"模型字段类型与后端相反"
> 这类错误**编译期不会暴露**，只会在运行时被 `JSONDecoder` 抛错、被 `APIClient` 的静默兜底吞掉，
> 表现为 `APIError.decode("HTTP 200")`——**支付与优惠券两条链路 100% 不可用且全程无提示**。
> 下表每一项都以 `server/` **实际源码**为准（不是 plans 规划稿）：**稳定 DTO 用「文件 + record 行」定位，
> 仍在演进的 Service 用「文件 + 方法名」定位**（行号会漂，符号名不会），改动时可直接回溯。

### 7.1 支付：`Features/Pay/PayModels.swift`

| 字段 | 后端出处（真值） | 修复前（iOS） | 修复后（iOS） | 影响 |
|------|------------------|---------------|---------------|------|
| `paymentId` | `PrepayResponse.java:17` `Long paymentId`（雪花，序列左移 1bit → 偶数） | `Int64?` | `Int64?`（不变） | — |
| `channel` | `PrepayResponse.java:17` `Integer channel`（`PaymentService#prepay` 校验 1~4） | `Int?` | `Int?`（不变） | — |
| `amount` | `PrepayResponse.java:17` `BigDecimal amount`；`PaymentService#prepay` 服务端定额（裁决 C7） | `Decimal?` | `Decimal?`（不变） | — |
| `payType` | `PrepayResponse.java:17` `String payType`；取值见 `PaymentService#payTypeOf` | `String?` | `String?`（不变） | — |
| **`payParams`** | **`PrepayResponse.java:17-18` `Map<String,String> payParams`**；`PaymentService#payParamsOf` 的三个 switch 分支**全部** `Map.of(...)` 返回 ≥2 键，**永不为 null** | **`String?`** ❌ | **`[String: String]`（非可选）** ✅ | JSONDecoder 解对象进 `String?` 抛 typeMismatch → 整包失败 → 支付页 100% 报"数据解析失败（HTTP 200）" |
| **`expireAt`** | **`PrepayResponse.java:18` `Long expireAt`**；`PaymentService#toPrepayResponse` 以 `.getEpochSecond()` 下发（**epoch 秒**，非 ISO 串） | **`String?`** ❌ | **`Int64?` + `expireAtDate: Date?`** ✅ | 即使修好 payParams，此字段仍会让整包二次解析失败 |

其余支付模型同步补齐后端出处注释（`MockPayResponse.java:4`、`PaymentStatusResponse.java:10-11`）。

### 7.2 优惠券：`Features/Checkout/CheckoutModels.swift`

| 字段 | 后端出处（真值） | 修复前 | 修复后 | 影响 |
|------|------------------|--------|--------|------|
| **`CheckoutCoupon.status`** | **`CouponRecordResponse.java:11` `String status`**，值来自 `CouponRecord#statusText()`（`available`/`used`/`expired`/`unknown`）；实测断言见 `CouponIntegrationTest` 的 `.andExpect(jsonPath("$.data.status").value("available"))` | **`Int?`** ❌ | **`String?` + `CouponStatus` 单点映射** ✅ | JSONDecoder 解 `"available"` 进 `Int?` 抛 typeMismatch → `loadCoupons` catch → `coupons=[]` → 券区恒空 |
| **`CouponPreviewRequest.amount`** | **`PreviewRequest.java:14-15` `List<@NotNull BigDecimal> amount`**；`CouponService#preview` 服务端求和后算门槛/面额（`amounts` 为空直接 400） | **`Decimal`（标量）** ❌ | **`[Decimal]`（数组）** ✅ | Jackson `MismatchedInputException` → 400 → 试算恒失败 → `submit()` 因 `usable=false` 静默丢券 |
| `CouponPreviewRequest.couponId` | `PreviewRequest.java:14` `Long couponId`；`CouponService#preview` 的形参名就是 `recordId`，并由 `requireOwnedRecord()` 校验归属 → 是**用户券记录 ID**，**不是模板 ID** | `Int64` | `Int64`（不变，已核对正确） | — |
| `CouponPreviewResponse` | `PreviewResponse.java:12-14`（`couponId/templateId/couponName/totalAmount/discount/finalAmount/**eligible**/reason`） | `Codable` | `Decodable`（字段名本就正确，仅收紧为只解码） | — |

`CheckoutCoupon` 同时按 `CouponRecordResponse.java:11-13` 补齐注释：`couponId`=记录 ID / `templateId`=模板 ID /
`expireAt`·`receivedAt`= `Instant` → ISO-8601 字符串。

**集中映射（单点定义，禁止散落字符串比较）**：`enum CouponStatus: String { available, used, expired, unknown }`
+ `CouponCoupon.statusValue`（未识别串一律降级 `.unknown`，页面永不错字崩溃）。

### 7.3 静默失败治理（P0-4）

| 位置 | 修复前 | 修复后 |
|------|--------|--------|
| `APIClient.perform`（原 `:218`） | `guard let envelope = try? JSONDecoder().decode(...) else { throw APIError.decode("HTTP \(status)") }` —— `try?` 把 DecodingError 全部吃掉，只剩一个 HTTP 码 | `do/catch` + `describe(_:)`（输出**字段路径 + 期望类型**，如"字段类型不匹配（期望 Dictionary<String,String>）@data.payParams"）+ `snippet(_:)`（响应体前 240 字符）。**解码失败必须可读地上抛到 UI** |
| `CheckoutViewModel.loadCoupons` | `catch { coupons = [] }` —— 静默，用户以为"没有券" | 写入 `couponLoadError`，结算页券入口红色提示 + 券弹窗内「重新加载优惠券」按钮 |
| `CheckoutViewModel.previewCoupon` | `catch { preview = nil }` —— 静默 | 写入 `couponPreviewError` 并在券卡/金额明细展示；新增请求序号 `previewSeq` 丢弃过期响应（快速切券不再被"后发先到"覆盖） |
| `CheckoutViewModel.submit` | `let couponId = (preview?.usable ?? false) ? selectedCouponId : nil` —— **静默按原价成交** | 选了券但 `eligible != true` → **阻断下单**，弹 `confirmationDialog`（"该券不可用 / 仍按原价支付 / 返回选择优惠券"），用户明确确认后才以 `couponId = nil` 建单；券卡与金额明细同时常驻橙色提示 |
| `CheckoutViewModel.performSubmit` / `confirmSubmitWithoutCoupon` | — | 拆出独立的 `performSubmit(address:couponId:router:)`，确认路径不再被二次拦截 |

> 仅 `APIClient.performRefresh` 保留 `try?`：它的契约就是"换不到新 Token 就返回 nil"，
> 调用方据此 `clearTokens()` + 发 `.sessionExpired` 收敛到重新登录，不构成静默降级（已在代码注释留档）。

### 7.4 P1/P2 同批项

| 项 | 修复 |
|----|------|
| `OrderListView.swift:9` `@StateObject` 双重初始化 backing store（与 `OrderDetailView`/`CheckoutView`/`PayView` 不一致） | 改为 `@StateObject private var vm: OrderListViewModel`（不给默认值），单点初始化 |
| `APIClient` 非 Sendable 单例 + 可变状态（`accessToken`/`refreshTask`/`storedRefreshToken`），并发 refresh 单飞有竞态（AUDIT P1-3） | 类型标 `@MainActor`：读 `refreshTask` 到写 `refreshTask` 之间无 await 挂起点，串行化由 actor 保证，**顺带修掉"多个并发 401 各发一次 refresh → refresh token 轮转 → 误踢登录"**。所有调用方本来就是 `@MainActor` 的 ViewModel，调用写法不变 |
| Swift 版本约束留档 | `APIClient.swift` 顶部「并发标注（改动前必读）」+ `project.yml` 显式 `SWIFT_STRICT_CONCURRENCY: minimal`：**切 Swift 6 语言模式或 `complete` 会成片报错，那是预期迁移成本** |

---

## 八、编译自查清单（Mac 首次编译前逐项确认）

已在 Windows 侧完成的结构化自查（**43 个 Swift 文件**）：

- [x] 全部文件 UTF-8 编码、花括号/圆括号/方括号配对检查通过；
- [x] `swift`/`swiftc` 在本机不存在 → 无法编译，仅做静态结构自查；
- [x] `RouteDestination` 引用的全部页面类型与初始化签名逐一核对
      （`CheckoutView(items:)`、`PayView(orderId:)`、`OrderListView(initialStatus:)`、
      `OrderDetailView(orderId:)`、`AddressListView(selecting:)`、`AddressEditView(addressId:)`）；
- [x] `Notification.Name.sessionExpired` 定义于 `APIClient.swift`（曾漏定义，已补）；
- [x] `CheckoutItem`（Route 关联值）与 `CartViewModel.checkoutItems()`、`ProductDetailViewModel.buyNowItem()` 成员一致；
- [x] `EmptyStateView` 的 action 必须同时给 `actionTitle` 才会渲染（3 处已补）；
- [x] `RemoteImage(urlString:)`、`CountdownText`、`AmountText`、`QuantityStepper`、`.toast()` 等组件调用签名核对；
- [x] `ForEach` 均有稳定 id（`Identifiable` 或 `id: \.self` / `\.offset`），枚举用于标签/`Hashable` 场景均已显式声明；
- [x] 金额字段全部 `Decimal`（JSONDecoder 原生支持），无 `Double` 参与金额；
- [x] 时间字段 `String?` + `ISODate` 解析（服务端 `Instant` 经 Spring Boot 默认配置序列化为
      ISO-8601 字符串，兼容毫秒/纳秒小数位）；
- [x] 与真实服务联调核对过信封与关键响应结构（auth/profile/addresses 实测通过；幂等键缺失会返回 1001，已由 APIClient 自动携带）。

**2026-10-12 P0 契约修复批次的增量自查**（同样在无编译器的 Windows 上完成）：

- [x] 本轮改动的每一个模型字段都能指到 `server/**` 的具体源码行（见上节「P0 契约修复记录」），
      没有一个是"看起来对就改"；
- [x] 用脚本（剥离注释/字符串字面量后计括号）复扫全部 **43 个 Swift 文件**：括号配对 0 失败；
- [x] 所有 `APIClient.shared` 调用点核对过隔离上下文——**所有同步调用（`hasRefreshToken` /
      `setAccessToken` / `storedRefreshToken` / `clearTokens`）都在 `@MainActor` 的 `AppState` 内**，
      其余全部是 `await` 的 async 调用点，标 `@MainActor` 不会引入同步跨隔离错误；
- [x] 新增符号引用闭环：`CouponStatus` / `CouponFallbackNotice` / `displayName` / `statusValue` /
      `reloadCoupons()` / `previewing` / `couponLoadError` / `couponPreviewError` /
      `couponProblemNotice` / `hasCouponProblem` / `cancelSubmit()` /
      `confirmSubmitWithoutCoupon(router:)` / `expireAtDate` 均已定义且有使用点；
- [x] 本轮**未使用** Swift 5.7+ 的 `if let x` / `guard let x` 简写（统一显式 `= x`），
      也未引入任何三方依赖（保持零 SPM 依赖）；新用到的 API 均为 iOS 15+ 且已在
      deploymentTarget 16.0 之内：`.confirmationDialog(_:isPresented:titleVisibility:actions:message:)`、
      `.overlay(alignment:)`、`Decimal`、`JSONDecoder`。

Mac 上首次编译请额外注意：

1. **先 `xcodegen generate`**（`.xcodeproj` 不入库，直接开会提示不存在）；
2. 若报 `Multiple commands produce ... Info.plist`：确认用的是本仓库 `project.yml`
   （已 `excludes: Resources/Info.plist` + `GENERATE_INFOPLIST_FILE: NO`），删掉旧工程重新 generate；
3. iOS 16.0 模拟器运行（用到 `.presentationDetents`、`NavigationStack`、`TimelineView` 等 iOS 16 API）；
4. 编译错误优先对照本 README「编译自查清单」与各文件头注释中的口径说明修复。

## 九、测试与验收（联调手顺）

1. 启动全部后端服务 + gateway（见「三、后端联调」）；
2. 模拟器运行 App → 注册（手机号任意合法格式，验证码 `123456`）→ 登录；
3. 首页浏览 → 搜索（联想/历史/结果翻页）→ 详情选 SKU → 加购 / 立即购买；
4. 购物车改数量、勾选、删除 → 结算（选地址、试券）→ 提交订单；
5. 支付页选渠道 → 确认支付 → 轮询成功 → 查看订单详情（含支付倒计时）；
6. 订单列表切 Tab、下拉刷新、取消待付款订单、确认收货；
7. 我的 → 地址管理：新增/编辑/设默认/删除（编辑时需重填手机号，列表出参已脱敏）；
8. 杀进程重启 → 应通过 Refresh Token 恢复登录态；断网/服务停机 → 错误态与服务端 msg 直显。

### P0 契约修复批次的验收重点（2026-10-12 新增）

1. **支付**：下单 → 收银台 → 确认支付，必须走到"支付成功"。
   若仍失败，toast/错误页文案现在是**可读定位信息**（形如
   `数据解析失败（HTTP 200：字段类型不匹配（期望 Dictionary<String,String>）@data.payParams；响应片段 {...}）`），
   **直接把这段文案贴回来定位**，不要再报"支付失败"了事。
2. **优惠券**：结算页点券入口 → 应能看到券列表且每张带状态文案（可用/已使用/已过期）；
   选一张满减券（订单金额达标）→ 金额明细「优惠」应立刻出现 `-面额`；
   选一张不达门槛的券 → 券卡与金额明细出现橙色常驻提示，提交订单时弹「该券不可用」确认框。
3. **静默失败验证**：停掉 `core-promotion`（:8088）后重进结算页 →
   券入口应显示红色「加载失败」并提供「重新加载优惠券」，而不是"暂无可用"。
4. **并发 401**：冷启动后同时触发多个请求并让 access token 过期 → 不应被误踢登录
   （`APIClient` 已标 `@MainActor`，refresh 单飞由 actor 串行保证）。
