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
| 促销/优惠券抵扣 | core-order P0 促销降级（试算不折扣）→ 券试算仅展示；仅 `eligible=true` 时提交携带 `couponId` | `Checkout/CheckoutViewModel` |
| 物流查询 | 未实现 → 「查看物流」toast 提示 P1 接入 | `Order/` |
| 真实支付渠道（支付宝/微信等） | core-payment mock → `POST /payment/mock/pay` 直接回调成功 | `Pay/` |
| 短信验证码 | 固定 `123456` | `Auth/` |
| 售后 Tab（退款中/已退款 4、5） | 列表 `status` 仅支持单值查询 → Tab 只做 0~3 | `Order/OrderListView` |
| 点选地址省市区三级联动 | P0 手填省/市/区文本（服务端地址无 region 库接口） | `User/AddressEditView` |
| 图片磁盘缓存/预取 | 用系统 `AsyncImage`，P1 再引入图片库 | `Core/UI/RemoteImage` |
| Refresh Token 入 Keychain | P0 存 UserDefaults，P1 迁移 | `APIClient` / `AppState` |

## 七、编译自查清单（Mac 首次编译前逐项确认）

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

Mac 上首次编译请额外注意：

1. **先 `xcodegen generate`**（`.xcodeproj` 不入库，直接开会提示不存在）；
2. 若报 `Multiple commands produce ... Info.plist`：确认用的是本仓库 `project.yml`
   （已 `excludes: Resources/Info.plist` + `GENERATE_INFOPLIST_FILE: NO`），删掉旧工程重新 generate；
3. iOS 16.0 模拟器运行（用到 `.presentationDetents`、`NavigationStack`、`TimelineView` 等 iOS 16 API）；
4. 编译错误优先对照本 README「编译自查清单」与各文件头注释中的口径说明修复。

## 八、测试与验收（联调手顺）

1. 启动全部后端服务 + gateway（见「三、后端联调」）；
2. 模拟器运行 App → 注册（手机号任意合法格式，验证码 `123456`）→ 登录；
3. 首页浏览 → 搜索（联想/历史/结果翻页）→ 详情选 SKU → 加购 / 立即购买；
4. 购物车改数量、勾选、删除 → 结算（选地址、试券）→ 提交订单；
5. 支付页选渠道 → 确认支付 → 轮询成功 → 查看订单详情（含支付倒计时）；
6. 订单列表切 Tab、下拉刷新、取消待付款订单、确认收货；
7. 我的 → 地址管理：新增/编辑/设默认/删除（编辑时需重填手机号，列表出参已脱敏）；
8. 杀进程重启 → 应通过 Refresh Token 恢复登录态；断网/服务停机 → 错误态与服务端 msg 直显。
