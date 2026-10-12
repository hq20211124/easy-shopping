# iOS客户端前端开发计划

> 项目：大型电商平台买家移动App（服务端目标：**千万 DAU**，DAU 1000 万 / 峰值在线 200 万；容量与可用性口径唯一真值见 `plans/00-架构总纲.md`）  
> 目标平台：iOS 16.0+（【修订-2】由 15.0 上调，理由见 1.1）  
> 当前日期：2026-10-11  
> 文档版本：v1.3（v1.1 审查修订 + v1.2 跨文件同步 + v1.3 实施手册修订，变更见文末《审查记录》《跨文件同步记录》《实施手册修订记录》）

---

## 一、项目架构与工程化

### 1.1 技术选型（Swift/Flutter/RN）

| 方案 | 优势 | 劣势 | 适用场景 |
|------|------|------|----------|
| **Swift + SwiftUI**（推荐） | 性能最佳、Apple生态深度集成、审核安全、包体积小 | 最低系统版本受SwiftUI新API限制（本项目定为 iOS 16+）、需单独维护Android端 | 追求极致体验、团队有iOS基因 |
| **Flutter** | 跨端一致、热重载、UI丰富 | 包体积大（+8MB）、审核风险（历史遗留）、复杂交互需Platform Channel | 需同时覆盖Android且团队Dart能力强 |
| **React Native** | 热更新（需合规）、Web团队复用 | 性能天花板低、RN模块依赖审核风险、升级成本 | 已有RN团队且对性能要求不极致 |

**推荐方案：Swift + SwiftUI（主工程）+ UIKit（底层复杂组件混合）**

理由：
- 电商平台对启动速度、滚动流畅度要求极高
- App Store审核对原生应用更友好
- 后期可嵌入Flutter模块做跨端复用（Flutter Module方式）
- 享受Metal性能优化和Swift Concurrency并发模型

#### 【修订-2】最低系统版本决策：iOS 16.0+

原计划最低支持 iOS 15.0，但正文大量使用了 iOS 16+ 才有的 API，二者矛盾。经盘点：

| API | 实际可用版本 | 使用位置 |
|-----|-------------|----------|
| `NavigationStack` / `NavigationPath` | iOS 16.0 | 1.4 路由、全部导航示例 |
| `.presentationDetents([.medium, .large])` | iOS 16.0 | 6.2 筛选面板 |
| `ViewThatFits` | iOS 16.0 | 15.1 自适应布局 |
| `AsyncImage` / `.refreshable` / `@FocusState` / `safeAreaInset` | iOS 15.0 | 5.1、4.1、6.1 |

**决策：最低支持版本从 iOS 15.0 提升为 iOS 16.0。**
理由：iOS 15 在 2026 年活跃设备占比已不足 3%，为其保留 `NavigationView` 双路径实现会显著增加导航层复杂度与回归成本。

若业务必须兼容 iOS 15，则需回退方案：`NavigationStack → NavigationView`（自定义 depth 绑定）、`presentationDetents → UIViewRepresentable 自实现底部抽屉`、`ViewThatFits → GeometryReader 手动计算`，成本估算增加 2~3 人周，且 `NavigationPath` 的类型化路由能力会丢失。

#### SwiftUI vs UIKit 深度对比

| 维度 | SwiftUI | UIKit | 电商场景建议 |
|------|---------|-------|-------------|
| **性能** | 中等，依赖框架优化 | 高，直接控制 | 默认 SwiftUI，掉帧再下沉（见混合策略） |
| **学习曲线** | 低 | 高 | 新成员快速上手用SwiftUI |
| **iOS 16兼容**（基线） | 主流API完整，少数需寄宿 | 完整 | 关键能力缺失时用 UIKit 寄宿 |
| **复杂交互** | 需UIViewRepresentable | 原生支持 | 复杂手势用UIKit |
| **列表性能** | LazyVStack/LazyVGrid 够用，需控状态粒度 | UITableView/UICollectionView最优 | 瀑布流先 LazyVGrid，FPS 不达标再下沉 |
| **动画** | 声明式，易实现 | 命令式，复杂 | 过渡动画用SwiftUI |
| **调试** | 较难 | 成熟工具链 | 性能问题排查用 Instruments + UIKit 视图树 |

**混合策略（【修订-3】与正文实现统一口径）：**

原表"列表页用 UIKit、SKU/支付用 UIKit"与 4.1/5.2/5.3 全部 SwiftUI 实现相互矛盾。统一为**"默认 SwiftUI、按数据下沉"**的判定规则：

| 页面 | 定选型 | 判定依据 |
|------|--------|----------|
| 首页（4.1） | SwiftUI `LazyVStack` + 分区 `Section` | 单元结构简单、需快速迭代 |
| 商品列表/搜索结果瀑布流（5.1、6.2） | SwiftUI `LazyVGrid` 起步；连续 2 个版本 FPS 不达标再下沉 `UICollectionViewCompositionalLayout` | 瀑布流高度不均，先验证真实数据 |
| 商品详情（5.2） | SwiftUI + WKWebView（图文详情） | 图文混排交给 H5，原生只做骨架 |
| SKU 选择器（5.2） | SwiftUI 布局 + `UIViewRepresentable` 寄宿 UIKit 手势/`UIPicker` 级联 | 复杂手势用 UIKit，布局用 SwiftUI |
| 支付（8.3、16.1） | UIKit 承载（`PKPaymentAuthorizationController` / SDK 回调均需 `UIViewController`） | 第三方 SDK 强依赖 UIKit 生命周期 |
| 设置/个人中心（10.x） | SwiftUI | 表单类，快速开发 |
| 直播/短视频（二期） | UIKit + AVPlayer/第三方播放器 | 视频渲染与手势冲突敏感 |

**下沉触发条件（写入性能基线，避免主观争论）：** 长列表滚动 P95 掉帧 > 5%、或主线程单帧 > 16.7ms 的 Cell 占比 > 10% 时，才把该列表替换为 UIKit 实现，且必须保持 `Route` 寻址不变。

### 1.2 目录结构

```
EasyShopping/
├── App/
│   ├── AppEntry.swift                  // @main入口
│   ├── AppDelegate.swift               // UIApplicationDelegate
│   ├── SceneDelegate.swift             // 多窗口Scene
│   └── EasyShoppingApp.swift           // SwiftUI App根
├── Core/
│   ├── Architecture/
│   │   ├── DI/                         // 依赖注入容器
│   │   │   ├── AppEnvironment.swift
│   │   │   └── DIContainer.swift
│   │   ├── Coordinator/               // 路由协调器
│   │   │   ├── AppCoordinator.swift
│   │   │   ├── TabCoordinator.swift
│   │   │   └── DeepLinkParser.swift   // 与后端 deep_link 协议对齐
│   │   ├── State/                      // 全局状态管理
│   │   │   ├── AppState.swift
│   │   │   └── Reducer/
│   │   └── Middleware/
│   │       ├── NetworkMonitor.swift
│   │       └── AnalyticsMiddleware.swift
│   ├── Analytics/                      // 【修订-28】埋点与APM（原缺失）
│   │   ├── TrackEvent.swift            // 事件定义（曝光/点击/停留）
│   │   ├── Tracker.swift               // 埋点上报（批量+重试+落地缓存）
│   │   └── APMManager.swift            // MetricKit 帧率/卡顿/崩溃/启动
│   ├── Bridge/                         // 【修订-28】WebView JSBridge
│   │   ├── JSBridge.swift              // WKScriptMessageHandler 白名单
│   │   └── H5AuthPlugin.swift          // 登录态/加购/跳转/支付结果回传
│   ├── Network/
│   │   ├── APIClient.swift             // URLSession封装
│   │   ├── Endpoint.swift              // 路由式Endpoint定义
│   │   ├── RequestSigner.swift         // 【修订-32】时间戳+nonce+HMAC签名
│   │   ├── Interceptor/
│   │   │   ├── AuthInterceptor.swift
│   │   │   ├── RetryInterceptor.swift
│   │   │   └── LoggingInterceptor.swift
│   │   ├── Cache/
│   │   │   ├── URLCacheManager.swift
│   │   │   └── ResponseCache.swift
│   │   └── WebSocket/
│   │       └── ChatWebSocket.swift
│   ├── Storage/
│   │   ├── UserDefaults+.swift
│   │   ├── KeychainWrapper.swift
│   │   ├── CoreDataStack.swift
│   │   └── FileManager+.swift
│   ├── Security/
│   │   ├── KeychainManager.swift
│   │   ├── CryptoManager.swift
│   │   ├── SSLPinner.swift             // 【修订-32】证书锁定
│   │   └── JailbreakDetection.swift
│   └── Utilities/
│       ├── Logger.swift
│       ├── CrashReporter.swift
│       ├── DeepLinkManager.swift
│       └── Constants.swift
├── Features/
│   ├── Splash/                         // 启动
│   ├── Onboarding/                     // 引导
│   ├── Auth/                           // 登录注册
│   ├── Home/                           // 首页
│   ├── Product/                        // 商品
│   ├── ProductCompare/                 // 商品对比（二期）
│   ├── Search/                         // 搜索
│   ├── Cart/                           // 购物车
│   ├── Checkout/                       // 结算
│   ├── PayResult/                      // 【修订-19】支付结果与轮询
│   ├── Order/                          // 订单
│   ├── Review/                         // 【修订-21】评价/晒单
│   ├── Profile/                        // 个人中心
│   ├── Message/                        // 消息
│   ├── CustomerService/                // 客服
│   ├── Settings/                       // 设置
│   ├── Live/                           // 直播（二期）
│   ├── Shop/                           // 店铺（二期）
│   ├── Member/                         // 会员（二期，虚拟商品走IAP）
│   ├── Social/                         // 社交（关注/晒单，二期）
│   ├── Footprint/                      // 浏览足迹（二期）
│   └── Widget/                         // Widget扩展
├── Shared/
│   ├── UIComponents/                   // 全局共享组件
│   │   ├── DS/                         // Design System
│   │   │   ├── Color+App.swift
│   │   │   ├── Font+App.swift
│   │   │   ├── Spacing.swift
│   │   │   └── ComponentStyle/
│   │   ├── Button/
│   │   ├── Input/
│   │   ├── Card/
│   │   ├── Dialog/
│   │   ├── Toast/
│   │   ├── EmptyState/
│   │   ├── LoadingView/
│   │   └── ErrorView/
│   ├── Layout/
│   │   ├── AdaptiveGrid.swift
│   │   ├── WaterfallLayout.swift
│   │   └── StickyHeader.swift
│   ├── Extensions/
│   │   ├── SwiftUI+.swift
│   │   ├── Combine+.swift
│   │   ├── URL+.swift
│   │   └── String+.swift
│   └── Resources/
│       ├── Assets.xcassets/
│       ├── Localizable.xcstrings/
│       └── Fonts/
├── Extensions/                         // App Extension
│   ├── EasyShoppingWidget/             // Widget扩展
│   ├── NotificationService/            // 通知扩展
│   └── ShareExtension/                 // 分享扩展
├── Tests/
│   ├── UnitTests/
│   ├── UITests/
│   └── IntegrationTests/
├── SPM/
│   └── Package.swift                   // Swift Package依赖
└── Fastlane/
    ├── Fastfile
    └── Matchfile
```

### 1.3 组件化设计

#### 设计系统（Design System）

```
DesignSystem/
├── Color/
│   ├── BrandColor.swift               // 主色、辅助色、渐变色
│   ├── SemanticColor.swift            // 成功/警告/错误/信息
│   └── DarkModeMapping.swift           // Dark Mode映射
├── Typography/
│   ├── AppFont.swift                  // 字体家族
│   ├── TextStyle.swift                // 语义化文本样式
│   └── DynamicTypeSupport.swift       // Dynamic Type支持
├── Spacing/
│   ├── Spacing.swift                  // 4pt基准间距系统
│   └── Radius.swift                   // 圆角体系
└── Components/
    ├── PrimaryButton.swift            // 主按钮（支持loading/disabled）
    ├── SecondaryButton.swift          // 次按钮
    ├── GhostButton.swift              // 幽灵按钮
    ├── InputField.swift               // 输入框（验证/错误态）
    ├── SearchBar.swift                // 搜索栏
    ├── TagView.swift                  // 标签
    ├── BadgeView.swift                // 角标
    ├── Stepper.swift                  // 数量步进器
    ├── ToastView.swift                // 轻提示
    ├── DialogView.swift               // 弹窗
    ├── ActionSheetView.swift          // 底部动作面板
    ├── BottomSheetView.swift          // 底部抽屉
    ├── SkeletonView.swift             // 骨架屏
    ├── PullToRefresh.swift            // 下拉刷新
    └── InfiniteScroll.swift           // 上拉加载
```

#### 组件通信机制

```swift
// 方案：Combine + EnvironmentObject 混合
// 1. 局部状态：@State
// 2. 共享状态：@EnvironmentObject（仅限 3 个以内：AppState / CartState / ConfigState）
// 3. 跨模块通信：NotificationCenter（命名空间隔离）
// 4. 数据流：Unidirectional Data Flow (UDF)

// 示例：购物车数量全局更新
extension Notification.Name {
    static let cartCountDidChange = Notification.Name("cartCountDidChange")
    static let userDidLogin = Notification.Name("userDidLogin")
    static let userDidLogout = Notification.Name("userDidLogout")
}
```

**【修订-5】通信机制使用边界（防止通知泛滥与全局状态爆炸）：**

| 场景 | 允许方式 | 禁止方式 |
|------|----------|----------|
| 页面内父子传值 | `@Binding` | — |
| 跨模块业务事件（登录/登出/加购成功） | `NotificationCenter` + 强类型枚举包装 | 直接散落字符串 key |
| 全局只读状态（登录态、配置、购物车数） | 单一 `AppState`（`@EnvironmentObject`） | 每个模块各挂一个 EnvironmentObject |
| 服务端数据 | Repository + `AsyncStream`/Combine 上报 | 在 View 里直接发请求 |
| 模块间导航 | 仅通过 `Coordinator.navigate(to: .xxx)` | 模块直接 `pushViewController` |

**命名规范：** 所有通知必须收敛到 `AppNotification` 枚举，禁止裸 `Notification.Name`，便于全局检索与单元测试：
```swift
enum AppNotification: String {
    case cartCountDidChange, userDidLogin, userDidLogout, tokenDidRefresh
    var name: Notification.Name { Notification.Name("com.easyshopping.\(rawValue)") }
}
```

### 1.4 路由设计

#### 路由架构

```swift
// MARK: - Route定义（【修订-6】补全覆盖 1.2 目录中全部 Feature）
enum Route: Hashable {
    // Tab主路由
    case home
    case category
    case cart
    case profile
    
    // 商品
    case productDetail(productId: String)
    case productImageViewer(productId: String, initialIndex: Int)
    case productReviews(productId: String)
    case productSkuSelector(productId: String)
    case productCompare(productIds: [String])       // 二期：商品对比
    case shopDetail(shopId: String)                 // 二期：店铺
    case liveRoom(roomId: String)                   // 二期：直播
    
    // 搜索
    case search
    case searchResult(keyword: String, filters: SearchFilters?)
    
    // 订单 / 结算 / 支付
    case checkout(itemIds: [String])                // 仅携带轻量ID，禁止携带聚合对象
    case payResult(orderId: String, from: PayChannel)   // 【修订-19】支付结果页
    case orderList(status: OrderStatus?)
    case orderDetail(orderId: String)
    case orderLogistics(orderId: String)
    case orderCancel(orderId: String)
    case applyRefund(orderId: String, itemId: String)
    case refundProgress(refundNo: String)
    case reviewPublish(orderId: String, itemId: String) // 【修订-21】发表评价
    
    // 用户
    case login
    case phoneLogin
    case addressList
    case addressEdit(addressId: String?)
    case favorites
    case coupons
    case couponDetail(couponId: String)
    case points
    case pointsMall                          // 二期：积分商城
    case memberCenter                        // 二期：会员（走IAP）
    case footprint                           // 二期：足迹
    case socialFeed(userId: String?)         // 二期：关注/晒单
    case settings
    case accountCancellation                 // 账号注销（合规必需）
    case privacyPolicy                       // 隐私政策
    case scan                                // 扫一扫
    
    // 内容
    case webView(url: String)
    case activityDetail(activityId: String)
    
    // 客服
    case customerService
    case chatHistory
    case ticketList
    case ticketDetail(ticketId: String)
    
    // 消息
    case messageCenter
    case messageDetail(notificationId: String)
}

// MARK: - 关联值约束
// 1) Route 的关联值必须是值类型且 Hashable；只允许携带 ID / 轻量筛选条件。
//    禁止携带 OrderPreview、[CartItem] 等大对象：NavigationPath 会持有整份快照，
//    造成内存增长与"页面重建后数据过期"（预览价格在用户停留期间可能已变化）。
// 2) 需要大对象时走参数通道：Coordinator 通过 `ParameterBox` 按 objectId 传递，
//    或 ViewModel 自行按 id 重新拉取（推荐，天然规避过期数据）。

// MARK: - DeepLink（【修订-34】与 ios-backend.md 的 deep_link 协议对齐）
enum DeepLink {
    case product(id: String)
    case activity(id: String)
    case coupon(id: String)
    case order(id: String)
    case logistics(orderId: String)
    case promotion(id: String)
    case messageCenter
    case customerService
    case external(url: URL)
    
    static func parse(_ url: URL) -> DeepLink? {
        // 后端下发的 deep_link 统一为 myapp:// 前缀（见 ios-backend.md 6.2.3 / 9.5 / 11.1.2）：
        //   myapp://order/detail/90001
        //   myapp://logistics/track/90001
        //   myapp://promotion/1001
        //   myapp://coupons  /  myapp://orders  /  myapp://cs
        // 自有 Universal Link: https://easyshopping.com/product/12345
        // URL Scheme 兜底: easyshopping://product/12345
        // ★ 三种前缀必须写入同一张映射表（DeepLinkParser），由本方法统一解析，
        //   避免后端下发链接在前端无法落地。Scheme/域名以“品牌常量”统一配置，
        //   见【修订-5】品牌标识对齐。
        return nil
    }
}
```

#### 路由实现

```swift
// 【修订-6】每个 Tab 独立持有 NavigationPath：
// 单一全局 path 会在切 Tab 时互相覆盖，且 popToRoot 会清空其它 Tab 的栈。
final class TabCoordinator: ObservableObject {
    @Published var paths: [Tab: NavigationPath] = [:]
    
    func navigate(_ tab: Tab, to route: Route) {
        paths[tab, default: NavigationPath()].append(route)
    }
    
    func popToRoot(_ tab: Tab) {
        paths[tab] = NavigationPath()
    }
}

final class AppCoordinator: ObservableObject {
    @Published var selectedTab: Tab = .home
    private var coordinators: [Tab: TabCoordinator] = [:]
    
    func navigate(to route: Route) {
        // 由 Route 推导目标 Tab（如 .productDetail 落在当前 Tab，.cart 落在购物车 Tab）
        let target = Route.resolveTab(for: route) ?? selectedTab
        selectedTab = target
        coordinators[target]?.navigate(target, to: route)
    }
    
    func handle(_ deepLink: DeepLink) {
        // DeepLink -> Route 显式映射，未覆盖的类型落到 H5 兜底（2.4）
    }
}

// 品牌标识集中配置（【修订-5】前后端不一致的唯一解法）
enum BrandConfig {
    static let urlScheme   = "easyshopping"      // TODO: 与后端 BundleID/CFBundleURLSchemes 同步
    static let universalHost = "easyshopping.com"
    static let bundleId    = "com.easyshopping"
    // ⚠ ios-backend.md 中使用的是 com.example.shop / myapp:// / example.com，
    //   上线前必须与后端统一为同一套常量，否则推送深链、BFF quick_entries、
    //   AASA 全部失效。见【修订-5】。
}

// Universal Link处理
// 注意：SwiftUI App 生命周期下，application(_:continue:) 由 @UIApplicationDelegateAdaptor 转发，
// 同时需在 SceneDelegate / onOpenURL 中处理 URL Scheme，两处都必须覆盖。
func application(_ application: UIApplication,
                 continue userActivity: NSUserActivity,
                 restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
    guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
          let url = userActivity.webpageURL else { return false }
    DeepLinkManager.shared.handle(url)
    return true
}
```

### 1.5 多模块管理

#### 模块划分

| 模块 | 职责 | 对外接口 |
|------|------|----------|
| `FeatureHome` | 首页数据与业务逻辑 | `HomeRepositoryProtocol` |
| `FeatureAuth` | 认证相关 | `AuthServiceProtocol` |
| `FeatureProduct` | 商品浏览 | `ProductRepositoryProtocol` |
| `FeatureCart` | 购物车 | `CartServiceProtocol` |
| `FeatureOrder` | 订单流程 | `OrderServiceProtocol` |
| `FeatureProfile` | 用户中心 | `ProfileServiceProtocol` |

#### 依赖注入

```swift
// 使用Swinject或手写DI
// 【修订-7修正】原代码 `.environmentObject(DIContainer.shared.container)` 注入的是
// Swinject `Container`，它不是 ObservableObject，无法作为 @EnvironmentObject 注入。
// 正确做法：注入业务容器本身，且 Container 作用域为 .container（单例）避免重复创建。
extension DIContainer {
    static let shared = DIContainer()
    
    func register() {
        // Network
        container.register(APIClientProtocol.self) { _ in
            APIClient(interceptors: [AuthInterceptor(), RetryInterceptor(), RequestSignerInterceptor()])
        }.inObjectScope(.container)
        
        // Repository
        container.register(HomeRepositoryProtocol.self) { r in
            HomeRepository(apiClient: r.resolve(APIClientProtocol.self)!,
                          cacheManager: r.resolve(CacheManagerProtocol.self)!)
        }
        
        // ViewModel：使用 .transient，避免跨页面共享可变状态
        container.register(HomeViewModel.self) { r in
            HomeViewModel(repository: r.resolve(HomeRepositoryProtocol.self)!,
                         analytics: r.resolve(AnalyticsServiceProtocol.self)!)
        }.inObjectScope(.transient)
    }
}
```

SwiftUI 侧注入：

```swift
WindowGroup {
    RootView()
        .environmentObject(AppState.shared)
        .environmentObject(DIContainer.shared)   // 业务容器，非 Swinject Container
}
```

---

## 二、启动与初始化

### 2.1 启动页（Splash）

**功能点：**
- 品牌Logo展示（0.8s）
- 初始化SDK（配置、网络、日志、崩溃监控）
- 路由分发（登录态检查 -> 首页/登录页）
- 开屏广告（可配置，支持静默期）
- 启动耗时埋点

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `SplashView` | 启动页主视图，全屏品牌动画 |
| `SplashViewModel` | 初始化任务编排，超时3s兜底 |
| `LaunchAdView` | 开屏广告视图（3s倒计时） |
| `LaunchAdCountdownButton` | 倒计时跳过按钮 |

**技术要点：**

```swift
// AppEntry.swift
// 【修订-7】1.2 目录中同时存在 AppDelegate.swift / SceneDelegate.swift / EasyShoppingApp.swift(@main)。
// 说明：采用 SwiftUI App 生命周期时，SceneDelegate 不会被自动实例化，
// 必须通过 UIApplicationDelegateAdaptor 挂载 AppDelegate，并在其中声明
// `application(_:configurationForConnecting:)` 返回自定义 SceneDelegate 才会生效。
// 二选一的推荐做法见下方“生命周期选型”。
@main
struct EasyShoppingApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var appState = AppState()
    
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .environmentObject(DIContainer.shared)
                .onOpenURL { url in                 // URL Scheme 兜底
                    DeepLinkManager.shared.handle(url)
                }
        }
    }
}

// 生命周期选型（二选一，禁止三份入口并存）
// 方案A（推荐）：SwiftUI App + @UIApplicationDelegateAdaptor(AppDelegate)
//   - AppDelegate 只做 push/ATS/SDK 初始化
//   - 需要 Scene 级回调（continue userActivity / openURLContexts）时，
//     在 AppDelegate 实现 configurationForConnecting 返回自定义 SceneDelegate
// 方案B：纯 UIKit AppDelegate + SceneDelegate + UIHostingController 承载 SwiftUI
//   - 适合需要精细控制启动窗口、闪屏的场景

// RootView决定首屏
struct RootView: View {
    @EnvironmentObject var appState: AppState
    
    var body: some View {
        Group {
            switch appState.launchPhase {
            case .initializing:
                SplashView()
            case .needsOnboarding:
                OnboardingView()
            // 【修订-7】原为 case .needsLogin: PhoneLoginView()（启动即登录墙）。
            // 改为游客可浏览：强制登录会显著降低转化，且 App Store 对"必须注册才能使用"
            // 的应用有拒审风险（Guideline 5.1.1 / 设计完整性）。
            // 登录时机改为：加购、下单、评价、查看个人信息等关键动作前置弹出。
            case .guest:
                MainTabView()
                .sheet(isPresented: $appState.presentLogin) { PhoneLoginView() }
            case .ready:   // 已登录
                MainTabView()
            }
        }
    }
}

// 启动任务编排（并发+超时）
final class LaunchTaskManager {
    func execute() async {
        async let config: Void = loadRemoteConfig()
        async let network: Void = setupNetwork()
        async let analytics: Void = setupAnalytics()
        async let crash: Void = setupCrashReporter()
        async let push: Void = registerPush()
        
        // 总超时3s
        _ = try? await withTimeout(seconds: 3) {
            _ = await (config, network, analytics, crash, push)
        }
    }
}

// withTimeout 并非标准库 API，需自行实现（否则示例不可编译）：
// 超时后取消子任务，任务结果以兜底值返回
func withTimeout<T>(seconds: Double,
                    operation: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw LaunchError.timeout
        }
        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}
```

**数据依赖：**
- `AppConfig`（远程配置，3s超时后使用本地默认值）
- `UserSession`（本地登录态检查）
- `LaunchAdConfig`（开屏广告配置，本地缓存+网络更新）

**启动优化指标（【修订-25】与 14.1 统一口径）：**

| 阶段 | 冷启动 | 热启动 | 首屏渲染 | 首屏可交互 | 适用机型 |
|------|--------|--------|----------|------------|----------|
| 首版目标（M5 灰度） | ≤ 2.5s | ≤ 1.0s | ≤ 1.8s | ≤ 2.8s | iPhone 12 及以上 |
| 迭代目标（上线后 2 个版本） | ≤ 2.0s | ≤ 0.8s | ≤ 1.5s | ≤ 2.5s | iPhone 12 及以上 |
| 低端机下限 | ≤ 3.5s | ≤ 1.5s | ≤ 2.5s | ≤ 4.0s | iPhone SE 2/8 |

> 度量口径：使用 `os_signpost` 埋 `processStartTime → didFinishLaunching → 首屏 `onAppear` → 可交互`四个区间，
> 经 MetricKit 的 `MXAppLaunchMetric` 与 APM 后台聚合，取 **P50/P90**（不取均值，避免被极端值稀释）。
> 必须区分 **prewarm 进程（iOS 后台预热）** 与真实冷启动，否则指标会虚低。
> 淘宝级电商App冷启动通常 2-3s，1.5s 一刀切指标过于乐观，故分阶段推进。

### 2.2 引导页（Onboarding）

**功能点：**
- 3-5页功能亮点滑动介绍
- 支持跳过和页码指示器
- 仅在首次安装或大版本更新展示
- 隐私政策弹窗（App Store审核强制）

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `OnboardingView` | 主容器，TabView页面滑动 |
| `OnboardingPage` | 单页内容（插画+标题+描述） |
| `PageIndicator` | 自定义页码指示器 |
| `PrivacyConsentDialog` | 隐私政策弹窗（首次启动强制） |

**技术要点：**

```swift
struct OnboardingView: View {
    @AppStorage("hasShownOnboarding") private var hasShownOnboarding = false
    @State private var currentPage = 0
    
    var body: some View {
        VStack {
            TabView(selection: $currentPage) {
                OnboardingPage(image: "search", title: "发现好物", description: "智能推荐你喜欢的商品")
                    .tag(0)
                OnboardingPage(image: "cart", title: "便捷购物", description: "一键下单，快速结算")
                    .tag(1)
                OnboardingPage(image: "delivery", title: "极速配送", description: "实时跟踪物流信息")
                    .tag(2)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            
            HStack {
                Button("跳过") { finish() }
                PageIndicator(current: $currentPage, count: 3)
                Button("下一步") { next() }
            }
        }
    }
}
```

**数据依赖：**
- `AppStorage`（引导页展示标记）
- `RemoteConfig`（引导页内容配置）

### 2.3 权限申请

**功能点：**
- 推送通知权限（启动后延迟申请，避免首启弹窗）
- 相册权限（分享/晒单时申请）
- 相机权限（扫一扫时申请）
- 定位权限（收货地址选择时申请）
- Face ID/Touch ID权限（支付验证时申请）

**权限申请时机策略：**

| 权限 | 申请时机 | 说明 |
|------|----------|------|
| 推送通知 | 首次登录后/首页加载完成 | 延迟申请提高通过率 |
| 相册 | 点击"保存图片"或"分享"时 | 按需申请 |
| 相机 | 点击"扫一扫"时 | 按需申请 |
| 定位 | 选择收货地址时 | 按需申请 |
| Face ID | 支付确认时 | 按需申请 |

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `PermissionManager` | 权限统一管理器 |
| `PermissionGuideView` | 权限引导页（去设置） |
| `NotificationPermissionCard` | 推送权限引导卡片 |

**技术要点：**

```swift
import UserNotifications

final class PermissionManager: ObservableObject {
    @Published var notificationStatus: UNAuthorizationStatus = .notDetermined
    
    func requestNotificationPermission() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let (granted, _) = try? await center.requestAuthorization(options: [.alert, .badge, .sound])
        return granted ?? false
    }
    
    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

// 延迟申请策略
struct HomeView: View {
    @EnvironmentObject var permissionManager: PermissionManager
    
    var body: some View {
        // ...
        .onAppear {
            // 【修订-9】加 status 判断，避免 onAppear 多次触发重复弹授权；
            // onAppear 每次页面重现都会执行，未判定时虽系统幂等，但会重复排队任务。
            guard permissionManager.notificationStatus == .notDetermined else { return }
            Task {
                try? await Task.sleep(nanoseconds: 3_000_000_000)   // 首页加载完成3s后申请
                _ = await permissionManager.requestNotificationPermission()
            }
        }
    }
}
```

**【修订-9】Info.plist 权限用途文案（缺失将直接崩溃或拒审）：**

| 键 | 用途文案（示例，需真实化） | 触发场景 |
|----|---------------------------|----------|
| `NSFaceIDUsageDescription` | "用于快速登录与支付验证" | **缺失会直接崩溃**（Face ID 必填） |
| `NSCameraUsageDescription` | "用于扫码识别商品与上传图片" | 扫一扫 |
| `NSPhotoLibraryUsageDescription` | "用于上传头像、晒单图片" | 晒单/头像 |
| `NSPhotoLibraryAddUsageDescription` | "用于保存商品海报到相册" | 保存分享海报（只写权限，iOS 11+ 独立键） |
| `NSLocationWhenInUseUsageDescription` | "用于选择收货地址、展示附近门店" | 地址选择 |
| `NSUserTrackingUsageDescription` | "用于广告效果归因与个性化推荐" | 若接入广告归因/IDFA；**不采集则必须删除此键** |
| `NSAppTransportSecurity` | ATS：仅允许 HTTPS，禁止 `NSAllowsArbitraryLoads` | 见 ios-backend.md 12.3 |

**隐私同意与 ATT（合规前置）：**
- 首次启动必须先展示隐私政策弹窗（2.2 `PrivacyConsentDialog`），用户同意前**不得初始化任何第三方 SDK、不得采集设备信息**；
- 同意后调用 `PUT /api/v1/privacy/consent-settings` 持久化用户选择（后端 12.1.2 已定义）；
- 涉及跨 App 跟踪时，按需触发 `ATTrackingManager.requestTrackingAuthorization`（iOS 14.5+），
  且必须在隐私弹窗**之后**申请，否则弹窗顺序违规会被拒审；
- 设置页提供"撤回同意"入口（`GET/PUT /api/v1/privacy/consent-settings`），撤回后停止采集并上报。

**数据依赖：**
- `UNUserNotificationCenter`（系统通知权限）
- `PHPhotoLibrary`（相册权限）
- `AVCaptureDevice`（相机权限）
- `CLLocationManager`（定位权限）
- `ATTrackingManager`（ATT，可选）
- `GET /api/v1/privacy/policy`、`PUT /api/v1/privacy/consent-settings`（隐私政策与授权状态）

### 2.4 热修复

**功能点：**
- 基于JSPatch替代方案（审核合规）
- 原生代码热修复（需苹果审核，仅限紧急修复）
- 配置热更新（远程配置）
- 资源热更新（图片、文案）
- H5兜底方案（关键页面WebView）

**技术方案对比：**

| 方案 | 审核风险 | 生效速度 | 适用场景 |
|------|----------|----------|----------|
| 远程配置 | 低 | 实时 | 文案、开关、运营位 |
| 资源热更新 | 中 | 下次启动 | 图片、主题 |
| JSPatch类 | **高** | 实时 | **不推荐** |
| H5容器 | 低 | 实时 | 活动页、非核心流程 |
| RN/Flutter热更新 | **高** | 实时 | **不推荐** |

**推荐方案：远程配置 + H5容器 + 审核后发版**

```swift
// 远程配置管理
final class RemoteConfigManager {
    static let shared = RemoteConfigManager()
    
    @Published var configs: RemoteConfigs = .default
    
    func fetchConfig() async {
        // 拉取远程配置（3s超时）
        // 合并本地默认值
        // 持久化到本地
    }
    
    // 使用
    func isFeatureEnabled(_ key: FeatureFlag) -> Bool {
        return configs.featureFlags[key.rawValue] ?? false
    }
}

// 热修复页面路由（H5兜底）
enum WebFallbackRoute: String {
    case productDetail = "product/detail"
    case activity = "activity"
}
```

**数据依赖：**
- `GET /api/v1/config`（后端 9.5 已定义：`version_code` / `platform` / `scene=home_page|config_flags|ab_tests`，返回 features、ab_tests、banners、config_hash）
- `WebView`容器（H5兜底，桥接协议见 13.4 JSBridge）

### 2.5 版本检查与灰度（【修订-28】原计划缺失）

**功能点：**
- 启动时静默检查新版本（普通更新）
- 低于 `force_below_version` 强制更新（阻断式弹窗，无法关闭）
- 灰度放量：按 deviceId 稳定哈希分桶，保证同一设备始终落在同一桶
- 版本公告 / 活动公告弹窗

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `VersionCheckService` | 启动阶段拉取版本信息（复用 3s 超时任务，不阻塞首屏） |
| `UpdateAlertView` | 普通更新（可稍后） |
| `ForceUpdateAlertView` | 强制更新（遮罩不可关闭，跳 App Store） |
| `GrayReleaseConfig` | 灰度分桶与开关落地 |

**数据依赖：**
- `GET /api/v1/version/check`（后端 9.2 已定义）
  - 入参：`version_code` / `version_name` / `platform` / `os_version` / `device_id` / `channel`
  - 出参：`latest_version_code` / `download_url` / `force_below_version` / `gray_percent` / `release_notes`

**实现要点：**
- 强更新判断必须用**数字版本号**（`version_code`，如 322），不能比较版本字符串；
- 灰度分桶算法与后端一致：`bucket = hash(device_id) % 100`，前端只读结果不自行决策；
- 灰度期间 Crashlytics/APM 按 `gray_bucket` 维度打标签，便于对比放量质量；
- 与 2.4 远程配置区分：**配置管功能开关，版本检查管可执行代码的更新边界**，二者不可混用（App Store 只允许前者）。

---

## 三、登录与账户

### 3.1 登录方式

**功能点：**
- 手机号验证码登录（主推）
- 账号密码登录
- 第三方登录（微信、Apple ID）
- Face ID/Touch ID快速登录
- 一键登录（运营商号码认证）

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `PhoneLoginView` | 手机号登录主页 |
| `VerificationCodeInput` | 验证码输入框（6位，自动填充） |
| `ThirdPartyLoginButtons` | 第三方登录按钮组 |
| `BiometricLoginButton` | Face ID/Touch ID按钮 |
| `OneTapLoginView` | 运营商一键登录 |
| `LoginAgreement` | 用户协议勾选 |
| `PasswordLoginView` | 密码登录页 |

**技术要点：**

```swift
// 验证码自动填充（iOS 12+）
struct VerificationCodeInput: UIViewRepresentable {
    @Binding var code: String
    
    func makeUIView(context: Context) -> UITextField {
        let tf = UITextField()
        tf.keyboardType = .numberPad
        tf.textContentType = .oneTimeCode  // 关键：支持短信自动填充
        tf.delegate = context.coordinator
        return tf
    }
}

// Apple Sign In
import AuthenticationServices

struct AppleSignInButton: UIViewRepresentable {
    func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
        let button = ASAuthorizationAppleIDButton(type: .signIn, style: .black)
        button.addTarget(context.coordinator, action: #selector(didTap), for: .touchUpInside)
        return button
    }
}

// Face ID/Touch ID
import LocalAuthentication

final class BiometricAuthManager {
    func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            return false
        }
        return try? await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) ?? false
    }
}
```

**数据依赖（【修订-4】已按 ios-backend.md 2.1 逐条对齐）：**

| 前端原写法 | 后端实际接口 | 处理 |
|-----------|-------------|------|
| `POST /api/v1/sms/send` | `POST /api/v1/auth/send-code`（入参 `mobile`/`scene`/`captcha_token`，返回 `code_id`/`expires_in`） | 已修正 |
| `POST /api/v1/auth/login/phone` | `POST /api/v1/auth/login/mobile`（入参 `mobile`/`code`/`device_info`） | 已修正 |
| `POST /api/v1/auth/login/{provider}` | `POST /api/v1/auth/login/apple`（仅定义 Apple） | 已修正并标注 |
| `POST /api/v1/auth/login/biometric` | `POST /api/v1/auth/biometric/challenge` → `verify`（两段式） | 已修正，见 3.4 |
| `POST /api/v1/auth/login/password` | **后端 2.1.1 概览声明了"账号密码"，但未定义 API** | ⚠ 待后端补充 `POST /api/v1/auth/login/password`，或本期隐藏入口 |
| 微信登录 | **后端 2.1.1 声明了微信/QQ/微博，但未定义 API** | ⚠ 待后端补充 `POST /api/v1/auth/login/wechat`，或本期隐藏入口 |
| 一键登录 | `POST /api/v1/auth/login/one-tap`（后端 2.1.4 已定义） | 已补充 |
| — | `POST /api/v1/auth/token/refresh`、`POST /api/v1/auth/token/revoke` | 已补充，见 3.2 |

**【修订-8】Sign in with Apple 强制规则：**
- 只要 App 提供了**第三方或社交账号登录**（微信、QQ、微博、Google…），App Store 就要求同时提供
  **Sign in with Apple**，否则拒审（Guideline 4.8 / 5.1.1(x)）。Apple ID 登录是硬依赖，不可下线；
- 微信登录在中国区可用，但微信 OpenSDK 需要在 `PrivacyInfo.xcprivacy` 与 App 隐私标签中声明所采集的数据；
- 若本期不实现微信登录，**必须同时隐藏入口**，不可留"微信登录"按钮点击无效（功能不完整是高频拒审原因）。

### 3.2 登录状态管理

**功能点：**
- Token管理（Access Token + Refresh Token）
- 自动续期（Token过期自动刷新）
- 登录态持久化（Keychain安全存储）
- 多设备登录冲突处理
- 退出登录清理

**技术要点：**

```swift
final class AuthManager: ObservableObject {
    @Published var isLoggedIn: Bool = false
    @Published var user: User?
    
    private let keychain = KeychainManager.shared
    private var refreshTask: Task<Void, Never>?     // 【修订-10】单飞锁：并发401只刷新一次
    
    // Token自动续期：单飞（single-flight）模式
    // 若不加锁，多个请求同时收到401会并发调用 refresh 接口，
    // 后端会因 refresh_token 已轮转（rotation）而使第一个之后的请求全部失败。
    func handleTokenRefresh() async throws {
        if let task = refreshTask {
            await task.value                       // 已有刷新在进行，等待结果
            return
        }
        guard let refreshToken = keychain.get(.refreshToken) else {
            throw AuthError.noRefreshToken
        }
        refreshTask = Task {
            do {
                // 后端：POST /api/v1/auth/token/refresh（ios-backend.md 2.2.3）
                let response = try await apiClient.request(.refreshToken(refreshToken))
                keychain.set(response.accessToken, for: .accessToken)
                keychain.set(response.refreshToken, for: .refreshToken)   // 轮转，旧refresh立即失效
            } catch {
                await MainActor.run { self.forceLogout(reason: .expired) }
            }
        }
        await refreshTask?.value
        refreshTask = nil
    }
    
    // 退出登录：仅删本地Token是不够的，必须完成服务端与本地的全量清理
    func logout(reason: LogoutReason) async {
        // 1. 服务端吊销（后端 2.2.3 已定义，原计划遗漏）
        //    POST /api/v1/auth/token/revoke { refresh_token }
        try? await apiClient.request(.revokeToken)
        // 2. 解绑设备与推送（后端 2.3.2 已定义）
        //    DELETE /api/v1/device/{device_id}  -> 该设备停止接收个性化推送
        try? await apiClient.request(.unbindDevice)
        // 3. 本地清理：Keychain
        keychain.delete(.accessToken)
        keychain.delete(.refreshToken)
        // 4. 【修订-25】多账号隔离：清空磁盘缓存与 CoreData，避免上一账号数据串号
        CacheManager.shared.removeAll(userId: user?.id)
        CoreDataStack.shared.deleteAll()
        ImageCache.default.clearCache()
        // 5. 状态与全局事件
        user = nil
        isLoggedIn = false
        NotificationCenter.default.post(name: AppNotification.userDidLogout.name, object: nil)
    }
}
```

### 3.3 账户安全

**功能点：**
- 设备指纹
- 登录设备管理（多端查看/踢出）
- 异常登录提醒
- 账号注销

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `DeviceManagementView` | 登录设备列表 |
| `SecuritySettingsView` | 安全设置页 |
| `AccountCancellationView` | 账号注销页 |

**数据依赖：**

- `GET /api/v1/auth/token/devices`（登录设备列表，后端 2.2.3）
- `DELETE /api/v1/device/{device_id}`（踢出指定设备，后端 2.3.2）
- `PUT /api/v1/device/push-token`（注册 APNs token，后端 2.3.2）
- `DELETE /api/v1/privacy/account-data`（**账号注销**，后端 12.1.2 已定义；
  App 内必须提供注销入口与二次确认、冷静期说明，这是 App Store 硬性要求）

### 3.4 生物识别（Face ID / Touch ID）实现要点（【修订-11】）

原 3.1 中的 `BiometricAuthManager` 只有本地校验，无法防重放，需补齐完整链路：

```swift
final class BiometricAuthManager {
    private let context = LAContext()
    
    func authenticate(reason: String) async throws -> LAContext? {
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            throw BiometricError.notAvailable(error?.localizedDescription)
        }
        context.localizedFallbackTitle = "使用密码"   // 允许降级，避免用户被锁死
        context.touchIDAuthenticationAllowableReuseDuration = 0  // 支付场景禁止复用
        guard try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                                               localizedReason: reason) else {
            throw BiometricError.failed
        }
        return context
    }
}
```

**服务端两段式校验（与后端 2.4.2 对齐，原计划只写了"登录"一个接口）：**

1. `POST /api/v1/auth/biometric/register` — 首次启用时，用 `LAContext` 生成的公钥（`SecKeyCopyPublicKey`）注册到服务端；
2. `POST /api/v1/auth/biometric/challenge` — 登录时向服务端取一次性随机数（防重放）；
3. 本地 `evaluatePolicy` 成功后，用私钥对 challenge 签名；
4. `POST /api/v1/auth/biometric/verify` — 服务端验签并下发 Token。

**注意事项：**
- 仅靠 `evaluatePolicy` 成功即认为登录成功是**不安全的**：本地凭据可被越狱工具伪造，必须做服务端 challenge/verify；
- 私钥必须存 Keychain 且使用 `kSecAccessControlBiometryCurrentSet`（生物特征变更即失效）；
- **支付场景不要用** `.deviceOwnerAuthenticationWithBiometrics`（不会回退到设备密码，用户无密码时流程死锁），
  应使用 `.deviceOwnerAuthentication` 或直接走支付密码/风控验证；
- `NSFaceIDUsageDescription` 必填（见 2.3）；
- 支持的生物特征由后端配置下发，前端不做硬编码开关。

---

## 四、首页

### 4.1 首页结构

**功能点：**
- 顶部搜索栏（固定/浮动）
- Banner轮播（自动播放+手动滑动）
- 金刚区（分类导航入口）
- 限时秒杀模块
- 商品推荐瀑布流
- 活动入口浮层
- 底部Tab栏

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `HomeView` | 首页容器 |
| `HomeSearchBar` | 顶部搜索栏（吸顶） |
| `BannerCarousel` | 轮播图组件 |
| `CategoryGrid` | 金刚区（2行5列） |
| `SeckillSection` | 限时秒杀模块 |
| `ProductFeed` | 推荐商品瀑布流 |
| `HomeFloatingLayer` | 活动浮层 |
| `StickyHeader` | 吸顶容器 |

**技术要点：**

```swift
// 【修订-12】吸顶实现修正：
// 原代码 `LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders])` 不会生效——
// pinnedViews 是 `Section` 的属性，不配合 Section 使用等于没写。
// 修正：搜索栏放进 Section(header:) 中。
struct HomeView: View {
    @StateObject private var vm = HomeViewModel()
    
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                Section {
                    // Banner
                    BannerCarousel(banners: vm.banners)
                    
                    // 金刚区
                    CategoryGrid(categories: vm.categories)
                    
                    // 秒杀
                    if let seckill = vm.seckill {
                        SeckillSection(seckill: seckill)
                    }
                    
                    // 推荐瀑布流
                    ProductFeed(products: vm.products)
                } header: {
                    HomeSearchBar()     // 吸顶搜索栏
                }
            }
        }
        .refreshable {
            await vm.refresh()
        }
    }
}

// Banner轮播（支持视频Banner）
// 【修订-13】原实现问题：
// 1) startAutoPlay 未定义，自动播放不可用；
// 2) TabView(.page) 无原生无限循环，末页回首页会"倒退"，需用 ±count 伪索引；
// 3) 120Hz 机型上 CADisplayLink 定时器要按 .default 优先级，避免后台空转耗电。
struct BannerCarousel: View {
    let banners: [Banner]
    @State private var currentIndex = Int.max / 2   // 伪索引实现近似无限循环
    @State private var timer: Timer?
    
    var body: some View {
        // 空数据保护：`i % banners.count` 在 count == 0 时会除零崩溃
        if banners.isEmpty {
            Color.clear.frame(height: 180)
        } else {
            TabView(selection: $currentIndex) {
                ForEach(0..<(banners.count * 100), id: \.self) { i in
                    BannerItem(banner: banners[i % banners.count])
                        .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .frame(height: 180)
            .onAppear { startAutoPlay() }
            .onDisappear { timer?.invalidate() }   // 离开页面停表，避免耗电（见 14.4）
        }
    }
    
    private func startAutoPlay() {
        guard timer == nil, banners.count > 1 else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in
            currentIndex += 1
        }
    }
}

// 秒杀倒计时
// 【修订-14】倒计时禁止用本地时间与 endTime 直接相减：
// 用户改本机时间即可"永久处于秒杀中"，或倒计时直接为负。
// 正确做法：服务端返回 endTime 时同时返回 server_now，前端计算 timeOffset = server_now - Date()，
// 倒计时 = endTime - (Date() + timeOffset)，并在前后台切换时重新校准。
struct SeckillSection: View {
    let seckill: SeckillInfo
    
    var body: some View {
        VStack(alignment: .leading) {
            HStack {
                Text("限时秒杀")
                    .font(.title2.bold())
                CountdownView(endTime: seckill.endTime,
                              serverNow: seckill.serverNow)   // 携带服务端基准时间
                Spacer()
                Button("更多 >") { /* ... */ }
            }
            .padding(.horizontal)
            
            ScrollView(.horizontal) {
                LazyHStack {
                    ForEach(seckill.items) { item in
                        SeckillItemView(item: item)
                    }
                }
            }
        }
    }
}
```

### 4.2 首页数据加载策略

**加载策略：**
1. **首屏数据**：本地缓存优先展示 -> 网络请求 -> 更新UI
2. **推荐流**：分页加载，每页20条
3. **预加载**：用户滚动到2/3时预加载下一页
4. **图片**：渐进式加载，先显示占位图

**数据依赖（【修订-4】与后端 11.1.2 BFF 对齐）：**

后端已提供 iOS 专属聚合接口，**首页不应再拆 5 个接口串行/并发请求**（额外 RTT + 首屏变慢）：

| 前端原写法 | 后端实际 | 处理 |
|-----------|---------|------|
| `GET /api/v1/home/config` | 合并进 `GET /api/v1/bff/ios/home` | 已修正 |
| `GET /api/v1/home/banners` | 同上（`data.banners`） | 已修正 |
| `GET /api/v1/home/categories` | 同上（`data.quick_entries`） | 已修正 |
| `GET /api/v1/home/seckill` | 同上（`data.flash_sale`） | 已修正 |
| `GET /api/v1/home/recommend` | 首屏：同上（`data.recommendations`）；翻页：`GET /api/v1/recommendations?scene=home&page=` | 已修正 |
| — | `data.user_info.unread_messages` / `cart_count` | 已补充（角标由 BFF 一并返回，省 2 次请求） |

- 首页**仅 1 次请求**渲染首屏（后端 11.1.2），瀑布流翻页走 `GET /api/v1/recommendations`（后端 3.2.4，`scene=home`）；
- Banner/金刚区点击下发的 `link` 字段为后端 deep_link（`myapp://...`），必须由 `DeepLinkParser` 统一解析（见 1.4）；
- 缓存策略：`staleWhileRevalidate`（先展示上次快照，静默刷新后 diff 局部刷新，避免整页闪白）。

---

## 五、商品浏览

### 5.1 商品列表页

**功能点：**
- 瀑布流展示
- 商品卡片（主图、标题、价格、销量、标签）
- 下拉刷新
- 上拉加载更多
- 排序切换（综合/销量/价格/新品）
- 筛选面板

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `ProductListView` | 列表页容器 |
| `ProductCard` | 商品卡片（瀑布流单元） |
| `SortFilterBar` | 排序筛选栏 |
| `FilterPanel` | 底部筛选面板 |
| `ProductGrid` | 瀑布流布局容器 |

**技术要点：**

```swift
// 瀑布流使用LazyVGrid
struct ProductGrid: View {
    let products: [Product]
    @StateObject private var vm = ProductListViewModel()
    
    private let columns = [
        GridItem(.flexible(), spacing: 8),
        GridItem(.flexible(), spacing: 8)
    ]
    
    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(products) { product in
                    NavigationLink(value: Route.productDetail(productId: product.id)) {
                        ProductCard(product: product)
                    }
                }
            }
            .padding(.horizontal, 8)
        }
    }
}

// 商品卡片
struct ProductCard: View {
    let product: Product
    
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // 主图（aspectRatio根据图片比例）
            // 【修订-13】原计划技术栈选了 Kingfisher（18），实现却用 AsyncImage，
            // 二者能力不匹配：AsyncImage 无磁盘缓存策略控制、无渐进式解码、
            // 无失败重试、无图片预取（prefetch），与 4.2"渐进式加载"要求冲突。
            // 统一为 Kingfisher（含占位、渐进式解码、自动重试、内存+磁盘双缓存）。
            KFImage(product.mainImageURL)
                .placeholder { SkeletonView() }
                .fade(duration: 0.2)
                .downsampling(size: CGSize(width: 375 * 2, height: 375 * 2))  // 按显示尺寸解码，见14.3
                .cancelOnDisappear(true)          // 快速滚动取消无用下载
                .resizable()
                .aspectRatio(contentMode: .fill)
            // 说明：全部列表统一用 Kingfisher，禁止混用 AsyncImage / SDWebImage
            .aspectRatio(product.imageRatio, contentMode: .fill)
            .clipped()
            
            // 标题（2行截断）
            Text(product.title)
                .font(.subheadline)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            
            // 价格
            HStack(alignment: .firstTextBaseline) {
                Text("¥\(product.price, specifier: "%.2f")")
                    .font(.headline)
                    .foregroundColor(.red)
                if let originalPrice = product.originalPrice {
                    Text("¥\(originalPrice, specifier: "%.2f")")
                        .font(.caption)
                        .strikethrough()
                        .foregroundColor(.secondary)
                }
            }
            
            // 销量
            Text("\(product.salesCount)人已购")
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .background(Color(.systemBackground))
        .cornerRadius(8)
    }
}
```

**数据依赖：**
- `GET /api/v1/products`（后端 3.2.1：商品列表，支持 `page`/`page_size`/`sort`/`category_id`/属性筛选）
- `GET /api/v1/search/filters`（筛选项聚合）— ⚠ **后端未定义**，需后端补充；本期可先用
  `GET /api/v1/search` 返回的聚合筛选（后端 3.2.3 中含聚合 facet）降级实现
- `GET /api/v1/user/favorites`（收藏）— ⚠ 后端有 `user_favorite` 表但**未定义 API**，需后端补充

**性能要求（与 14.2 对齐）：**
- 瀑布流必须预计算 Cell 高度缓存（相同 aspectRatio 的图片可先按默认高度占位，避免布局抖动）；
- 滚动到 2/3 时预取下一页数据 + `ImagePrefetcher` 预取图片；
- 列表刷新采用 diffable data source 语义（SwiftUI 中用 `IdentifiedArray` 或 `ForEach(id:)` 精确 diff），禁止整表 reload。

### 5.2 商品详情页

**功能点：**
- 主图轮播（支持视频）
- 价格展示（活动价、划线价、优惠标签）
- 规格选择（SKU选择器）
- 商品详情（图文详情WebView）
- 用户评价
- 推荐搭配
- 底部操作栏（客服、购物车、立即购买）

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `ProductDetailView` | 详情页容器 |
| `ProductImageGallery` | 图片画廊（支持视频） |
| `PriceSection` | 价格区域 |
| `SkuSelectorView` | SKU规格选择器 |
| `ProductSpecifications` | 商品参数 |
| `ProductDetailWebView` | 图文详情（WebView） |
| `ReviewSection` | 评价区域 |
| `RecommendSection` | 相关推荐 |
| `BottomActionBar` | 底部操作栏 |
| `ShareSheet` | 分享面板 |

**技术要点：**

```swift
// SKU选择器（核心交互组件）
struct SkuSelectorView: View {
    @Binding var selectedSku: Sku?
    let skuTree: SkuTree
    
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // 已选规格
            if let sku = selectedSku {
                Text("已选：\(sku.specificationText)")
                    .font(.subheadline)
            }
            
            // 规格选项
            ForEach(skuTree.dimensions) { dimension in
                VStack(alignment: .leading, spacing: 8) {
                    Text(dimension.name)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    FlowLayout(spacing: 8) {
                        ForEach(dimension.options) { option in
                            SkuOptionButton(
                                option: option,
                                isSelected: isSelected(dimension: dimension, option: option),
                                isDisabled: isDisabled(dimension: dimension, option: option)
                            ) {
                                selectOption(dimension: dimension, option: option)
                            }
                        }
                    }
                }
            }
            
            // 数量选择
            HStack {
                Text("数量")
                Spacer()
                Stepper(value: $quantity, in: 1...maxQuantity)
            }
        }
        .padding()
    }
}

// 底部操作栏
struct BottomActionBar: View {
    let product: Product
    @StateObject private var vm = ProductDetailViewModel()
    
    var body: some View {
        HStack(spacing: 12) {
            // 客服
            Button { vm.showCustomerService() } label: {
                VStack {
                    Image(systemName: "message")
                    Text("客服").font(.caption2)
                }
            }
            
            // 购物车（带角标）
            Button { vm.addToCart() } label: {
                VStack {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "cart")
                        if vm.cartCount > 0 {
                            Badge(count: vm.cartCount)
                        }
                    }
                    Text("购物车").font(.caption2)
                }
            }
            
            // 加入购物车
            Button { vm.addToCart() } label: {
                Text("加入购物车")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.orange)
                    .foregroundColor(.white)
                    .cornerRadius(24)
            }
            
            // 立即购买
            Button { vm.buyNow() } label: {
                Text("立即购买")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.red)
                    .foregroundColor(.white)
                    .cornerRadius(24)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }
}
```

**数据依赖（【修订-4】按后端 3.2.2 / 3.2.4 / 11.1.3 对齐）：**

| 前端原写法 | 后端实际 | 处理 |
|-----------|---------|------|
| `GET /api/v1/products/{id}` | ✅ 一致（已含 `skus`、`review_summary`、`delivery`、`promotion`） | 保留 |
| `GET /api/v1/products/{id}/sku` | **后端无此接口**，SKU 已内联在详情里 | 删除，改为详情字段解析 |
| `GET /api/v1/products/{id}/reviews` | **后端无评价列表 API**（仅有 `product_review` 表与 `review_summary` 摘要） | ⚠ 需后端补充 `GET /api/v1/products/{id}/reviews`；未就绪时评价区降级为"仅展示标签与总数" |
| `GET /api/v1/products/{id}/recommend` | `GET /api/v1/recommendations?scene=accessory&product_id=`（后端 3.2.4） | 已修正 |
| `POST /api/v1/favorites` | **后端无收藏 API**（仅有表结构） | ⚠ 需后端补充，或本期隐藏收藏入口 |
| `POST /api/v1/share` | ✅ 一致（后端 8.3） | 保留，补充 `GET /api/v1/share/{share_code}/track`、`GET /api/v1/user/share/stats` |
| — | `GET /api/v1/bff/ios/product/{product_id}`（后端 11.1.3 详情聚合） | 新增，作为详情首屏接口 |

**加购/下单前的二次校验（原计划缺失）：**
- SKU 库存以**加入购物车接口的返回值**为准，不能信任前端 `maxQuantity`；
- 详情停留期间价格可能变化：进入结算前必须走 `POST /api/v1/orders/preview`（后端 4.2.2），
  若返回价格与页面不一致，弹"价格已变动"确认框，禁止静默提交；
- 立即购买需先做库存校验（同 preview），失败给出可操作提示（到货提醒/换规格）。

### 5.3 图片浏览

**功能点：**
- 全屏图片浏览
- 手势缩放、双击放大
- 左右滑动切换
- 保存图片到相册

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `ImageViewerView` | 全屏图片浏览器 |
| `ZoomableImageView` | 可缩放图片组件 |
| `ImageViewerPage` | 单页图片 |

### 5.4 收藏与分享

**功能点：**
- 收藏/取消收藏
- 收藏列表
- 分享（微信、朋友圈、微博、复制链接）
- 生成分享海报

**技术要点：**

```swift
// 分享功能
import UIKit

final class ShareManager {
    func share(product: Product, from viewController: UIViewController) {
        let items: [Any] = [
            product.title,
            URL(string: product.shareURL)!,
            product.mainImage
        ]
        let activityVC = UIActivityViewController(activityItems: items, applicationActivities: nil)
        viewController.present(activityVC, animated: true)
    }
}

// 生成分享海报
func generateSharePoster(product: Product) -> UIImage {
    // 使用UIGraphicsImageRenderer绘制
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 375, height: 667))
    return renderer.image { context in
        // 绘制背景、商品图、标题、价格、二维码
    }
}
```

**第三方分享接入要点（原计划遗漏）：**
- 微信 OpenSDK 分享到会话/朋友圈**必须走 Universal Link 回调**（iOS 9.2+ 微信不再接受 URL Scheme 回调），
  因此 `WXApi.register(... universalLink:)` 与 16.4 的 AASA 必须同源、同一次配置；
- 微信/微博 SDK 需要在 `PrivacyInfo.xcprivacy` 与 App Store 隐私标签中声明其采集的数据类型，
  否则触发 **ITMS-91053** 上传告警或拒审；
- 保存海报到相册用 `PHPhotoLibrary` **仅写权限**（`NSPhotoLibraryAddUsageDescription`），见 2.3；
- 海报二维码需带渠道参数（`share_code`），与 `POST /api/v1/share` 上报埋点闭环，便于 8.3 分享统计。

---

## 六、搜索与筛选

### 6.1 搜索页

**功能点：**
- 搜索输入框（自动聚焦键盘）
- 搜索历史（本地存储，最多20条）
- 热门搜索（服务端下发）
- 搜索建议（实时联想）
- 搜索发现（推荐话题）

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `SearchView` | 搜索主页 |
| `SearchBar` | 搜索输入栏 |
| `SearchHistorySection` | 搜索历史 |
| `HotSearchSection` | 热门搜索 |
| `SearchSuggestionList` | 搜索建议列表 |
| `SearchDiscoverySection` | 搜索发现 |

**技术要点：**

```swift
struct SearchView: View {
    @StateObject private var vm = SearchViewModel()
    @FocusState private var isSearchFocused: Bool
    
    var body: some View {
        VStack(spacing: 0) {
            // 搜索栏
            SearchBar(text: $vm.keyword, onSearch: { vm.search() })
                .focused($isSearchFocused)
                .onAppear { isSearchFocused = true }
            
            if vm.keyword.isEmpty {
                // 搜索发现页
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        // 搜索历史
                        if !vm.history.isEmpty {
                            SearchHistorySection(
                                history: vm.history,
                                onSelect: { vm.keyword = $0; vm.search() },
                                onDelete: { vm.removeHistory($0) },
                                onClear: { vm.clearHistory() }
                            )
                        }
                        
                        // 热门搜索
                        HotSearchSection(items: vm.hotSearches) { item in
                            vm.keyword = item.keyword
                            vm.search()
                        }
                        
                        // 搜索发现
                        SearchDiscoverySection(items: vm.discoveries)
                    }
                    .padding()
                }
            } else {
                // 搜索建议
                SearchSuggestionList(suggestions: vm.suggestions) { suggestion in
                    vm.keyword = suggestion
                    vm.search()
                }
            }
        }
        .task(id: vm.keyword) {
            // 防抖300ms
            try? await Task.sleep(nanoseconds: 300_000_000)
            await vm.fetchSuggestions()
        }
    }
}
```

### 6.2 搜索结果页

**功能点：**
- 搜索结果列表（瀑布流）
- 排序切换（综合/销量/价格/新品）
- 筛选面板（品牌、价格区间、属性）
- 搜索无结果推荐

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `SearchResultView` | 搜索结果页 |
| `SearchFilterBar` | 筛选栏 |
| `SearchFilterPanel` | 筛选面板 |
| `PriceRangeInput` | 价格区间输入 |
| `SearchResultGrid` | 结果瀑布流 |
| `EmptySearchView` | 无结果页 |

**技术要点：**

```swift
// 筛选器模型
struct SearchFilters {
    var sort: SortOption = .comprehensive
    var priceRange: ClosedRange<Double>?
    var brands: [String] = []
    var attributes: [String: [String]] = [:]
    var onlyInStock: Bool = false
    var onlyWithDiscount: Bool = false
}

// 筛选面板（底部弹出）
struct SearchFilterPanel: View {
    @Binding var filters: SearchFilters
    
    var body: some View {
        NavigationStack {
            List {
                // 排序
                Section("排序") {
                    ForEach(SortOption.allCases) { option in
                        HStack {
                            Text(option.displayName)
                            Spacer()
                            if filters.sort == option {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.red)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { filters.sort = option }
                    }
                }
                
                // 价格区间
                Section("价格区间") {
                    PriceRangeInput(range: $filters.priceRange)
                }
                
                // 品牌
                Section("品牌") {
                    FlowLayout {
                        ForEach(brands, id: \.self) { brand in
                            FilterTag(
                                text: brand,
                                isSelected: filters.brands.contains(brand)
                            ) {
                                toggleBrand(brand)
                            }
                        }
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                HStack {
                    Button("重置") { filters = SearchFilters() }
                    Button("确定") { dismiss() }
                }
                .padding()
            }
        }
        .presentationDetents([.medium, .large])
    }
}
```

### 6.3 Siri建议

**功能点：**
- Core Spotlight索引商品
- Siri Shortcuts支持（"搜索iPhone手机壳"）
- Handoff支持（Mac上继续浏览）

**技术要点：**

```swift
import CoreSpotlight
import UniformTypeIdentifiers   // 【修订-24】kUTType* 已于 iOS 15 弃用，改用 UTType

final class SearchIndexManager {
    func indexProduct(_ product: Product) {
        let attributeSet = CSSearchableItemAttributeSet(contentType: UTType.item)
        attributeSet.title = product.title
        attributeSet.contentDescription = product.subtitle
        attributeSet.thumbnailData = product.thumbnailData
        attributeSet.keywords = product.searchKeywords
        attributeSet.domainIdentifier = "product"
        
        let item = CSSearchableItem(
            uniqueIdentifier: "product-\(product.id)",
            domainIdentifier: "com.easyshopping.product",
            attributeSet: attributeSet
        )
        
        CSSearchableIndex.default().indexSearchableItems([item])
    }
    
    // 商品被下架/删除时必须同步移除索引，否则 Spotlight 点开是死链（审核会测）
    func deindexProduct(id: String) {
        CSSearchableIndex.default().deleteSearchableItems(
            withIdentifiers: ["product-\(id)"])
    }
}

// Siri 建议（【修订-24】原代码用 INSearchForPhotosIntent 是"搜索照片"意图，
// 与商品搜索毫无关系，无法实现"搜索iPhone手机壳"的语音能力）
// 方案：App Shortcuts（iOS 16+，无需服务端，零配置）替代已废弃的 INIntent 自定义流程
import AppIntents

struct SearchProductsIntent: AppIntent {
    static var title: LocalizedStringResource = "搜索商品"
    @Parameter(title: "关键词") var keyword: String
    
    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        AppCoordinator.shared.navigate(to: .searchResult(keyword: keyword, filters: nil))
        return .result(dialog: "正在搜索 \(keyword)")
    }
}

struct EasyShoppingShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: SearchProductsIntent(),
                    phrases: ["搜索\(.applicationName)商品", "在\(.applicationName)搜索\(.applicationName)"])
    }
}
```

**数据依赖：**
- `GET /api/v1/search/hot`（后端 3.2.3 ✅）
- `GET /api/v1/search/suggestions`（后端 3.2.3 ✅）
- `GET /api/v1/search`（后端 3.2.3 ✅，`type=product|shop|brand`）
- `GET /api/v1/search/filters`（筛选项聚合）— ⚠ **后端未定义**，需补充或用 `search` 响应内的 facet 降级
- `GET /api/v1/search/discovery`（搜索发现）— ⚠ **后端未定义**，本期可由 `GET /api/v1/config?scene=search` 的运营配置替代

> 降级原则：后端未定义的接口一律**不写死在前端调用链**里，必须在本表标注 ⚠ 并给出降级路径，
> 否则联调阶段必然阻塞（见 20 风险表）。

---

## 七、购物车

### 7.1 购物车页

**功能点：**
- 商品列表（按店铺分组）
- 选择/全选
- 数量修改
- 删除商品
- 失效商品区域
- 优惠券提示
- 底部结算栏

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `CartView` | 购物车页容器 |
| `CartStoreSection` | 店铺分组 |
| `CartItemRow` | 商品行 |
| `CartItemCheckbox` | 选择框 |
| `QuantityStepper` | 数量步进器 |
| `InvalidItemsSection` | 失效商品区 |
| `CartBottomBar` | 底部结算栏 |
| `CouponHintBanner` | 优惠券提示 |

**技术要点：**

```swift
struct CartView: View {
    @StateObject private var vm = CartViewModel()
    
    var body: some View {
        VStack(spacing: 0) {
            if vm.items.isEmpty {
                EmptyCartView()
            } else {
                ScrollView {
                    LazyVStack(spacing: 0, pinnedViews: []) {
                        ForEach(vm.groupedByStore) { group in
                            CartStoreSection(
                                store: group.store,
                                items: group.items,
                                isSelected: vm.isStoreSelected(group.store.id),
                                onSelect: { vm.toggleStore(group.store.id, $0) }
                            )
                        }
                        
                        // 失效商品
                        if !vm.invalidItems.isEmpty {
                            InvalidItemsSection(items: vm.invalidItems)
                        }
                        
                        // 推荐商品
                        CartRecommendSection(products: vm.recommendations)
                    }
                }
                
                // 底部结算栏
                CartBottomBar(
                    totalPrice: vm.totalPrice,
                    selectedCount: vm.selectedCount,
                    isAllSelected: vm.isAllSelected,
                    onSelectAll: { vm.toggleSelectAll() },
                    onCheckout: { vm.checkout() }
                )
            }
        }
        .navigationTitle("购物车")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(vm.isEditing ? "完成" : "管理") {
                    vm.toggleEditing()
                }
            }
        }
    }
}

// 底部结算栏
struct CartBottomBar: View {
    let totalPrice: Decimal
    let selectedCount: Int
    let isAllSelected: Bool
    let onSelectAll: () -> Void
    let onCheckout: () -> Void
    
    var body: some View {
        HStack {
            Button(action: onSelectAll) {
                HStack {
                    Image(systemName: isAllSelected ? "checkmark.circle.fill" : "circle")
                    Text("全选")
                }
            }
            
            VStack(alignment: .trailing) {
                // 【修订-16】原代码 totalPrice 为 Decimal，
                // `Text("...\(x, specifier:)")` 的 specifier 重载只接受
                // Double/Float/Int 等数值类型，Decimal 不匹配，无法编译。
                // 改为 Decimal 专用格式化 API。
                Text("合计：\(totalPrice.formatted(.currency(code: "CNY")))")
                    .font(.headline)
                if totalPrice > 0 {
                    Text("不含运费")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            
            Button(action: onCheckout) {
                Text("结算(\(selectedCount))")
                    .frame(width: 120, height: 44)
                    .background(selectedCount > 0 ? Color.red : Color.gray)
                    .foregroundColor(.white)
                    .cornerRadius(22)
            }
            .disabled(selectedCount == 0)
        }
        .padding()
        .background(.ultraThinMaterial)
    }
}
```

### 7.2 批量操作

**功能点：**
- 批量删除
- 批量移入收藏（⚠ 收藏 API 后端未定义，见 5.2）
- 购物车数量限制（100件）— **【修订-15】该上限原为前端硬编码，后端 4.2.1 未约定**，
  必须由后端在 `GET /api/v1/cart` 响应中返回 `max_items`，前端读取该值，避免两端策略漂移
- 库存实时校验

**技术要点：**

```swift
// 【修订-15】原 `validateStock()` 调用的 `.validateStock(items)` 在后端不存在。
// 后端把库存/价格校验统一收敛到下单预览接口（ios-backend.md 4.2.2）：
// POST /api/v1/orders/preview  -> 返回不可用商品列表、价格变动、优惠券可用性
func validateBeforeCheckout() async throws -> OrderPreview {
    let items = selectedItems.map { CartCheckoutItem(skuId: $0.skuId, quantity: $0.quantity) }
    let preview = try await apiClient.request(.orderPreview(items))
    guard preview.invalidItems.isEmpty else { throw CartError.outOfStock(preview.invalidItems) }
    if preview.priceChanged { await PriceChangedAlert.shared.confirm() }   // 价格变动二次确认
    return preview
}

// 加购（以服务端返回为准）
func addToCart(skuId: String, quantity: Int) async throws {
    let result = try await apiClient.request(.addToCart(skuId: skuId, quantity: quantity))
    // POST /api/v1/cart：由服务端校验数量上限，前端只负责展示错误
    guard result.success else { throw CartError.serverRejected(result.message) }
    NotificationCenter.default.post(name: AppNotification.cartCountDidChange.name,
                                    object: result.cartCount)
}
```

### 7.3 购物车数据同步

**功能点：**
- 多端同步（登录后同步）
- 本地缓存（未登录可加购，存 `CacheManager` 的游客命名空间）
- 合并策略（登录时合并本地购物车）

**数据依赖（【修订-15】路径已对齐后端 4.2.1）：**

| 前端原写法 | 后端实际 | 处理 |
|-----------|---------|------|
| `GET /api/v1/cart` | ✅ 一致 | 保留 |
| `POST /api/v1/cart/items` | `POST /api/v1/cart` | 已修正 |
| `PUT /api/v1/cart/items/{id}` | `PUT /api/v1/cart/{cart_item_id}` | 已修正 |
| `DELETE /api/v1/cart/items/{id}` | `DELETE /api/v1/cart/{cart_item_id}` | 已修正 |
| `POST /api/v1/cart/sync`（登录合并） | **后端无同步接口** | ⚠ 需后端补充；降级方案：登录后拉取服务端购物车 + 读取本地游客购物车，逐条调用 `POST /api/v1/cart` 合并（需去重：同 skuId 取数量之和并受上限约束） |
| `POST /api/v1/orders/preview`（库存校验） | ✅ 一致 | 由 7.2 调整至此 |

**同步冲突规则（明确写死，避免两端各写各的）：**
- 数量冲突：**服务端为准**，本地仅在离线场景排队，上线后以服务端最新值覆盖 UI；
- 删除冲突：已删除项不复活，服务端 `version` 高于本地则丢弃本地删除操作；
- 合并接口就绪前，禁止"本地新增 + 远端删除"同时生效（会静默丢单）。

---

## 八、下单结算

### 8.1 确认订单页

**功能点：**
- 收货地址选择/新增
- 商品清单（按店铺分组）
- 配送方式选择
- 优惠券选择
- 发票信息
- 订单备注
- 价格明细（商品金额、运费、优惠、实付）
- 提交订单

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `CheckoutView` | 确认订单页容器 |
| `AddressSection` | 收货地址区域 |
| `ProductListSection` | 商品清单 |
| `DeliveryMethodSelector` | 配送方式选择 |
| `CouponSelector` | 优惠券选择 |
| `InvoiceSection` | 发票信息 |
| `RemarkInput` | 订单备注 |
| `PriceBreakdown` | 价格明细 |
| `SubmitOrderButton` | 提交订单按钮 |
| `PaymentMethodSheet` | 支付方式底部弹窗 |

**技术要点：**

```swift
struct CheckoutView: View {
    @StateObject private var vm = CheckoutViewModel()
    
    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 12) {
                    // 收货地址
                    AddressSection(address: vm.address) {
                        coordinator.navigate(to: .addressList)
                    }
                    
                    // 商品清单
                    ForEach(vm.orderGroups) { group in
                        CheckoutStoreSection(group: group)
                    }
                    
                    // 配送方式
                    DeliveryMethodSelector(
                        selected: $vm.deliveryMethod,
                        options: vm.deliveryOptions
                    )
                    
                    // 优惠券
                    CouponSelector(
                        availableCount: vm.availableCoupons,
                        selected: $vm.selectedCoupon
                    )
                    
                    // 发票
                    InvoiceSection(invoice: $vm.invoice)
                    
                    // 备注
                    RemarkInput(text: $vm.remark)
                    
                    // 价格明细
                    PriceBreakdown(
                        itemsTotal: vm.itemsTotal,
                        deliveryFee: vm.deliveryFee,
                        discount: vm.discount,
                        total: vm.total
                    )
                }
                .padding()
            }
            
            // 底部提交栏
            HStack {
                VStack(alignment: .leading) {
                    Text("实付：")
                    Text("¥\(vm.total, specifier: "%.2f")")
                        .font(.title2.bold())
                        .foregroundColor(.red)
                }
                Spacer()
                Button { vm.submitOrder() } label: {
                    Text("提交订单")
                        .frame(width: 140, height: 48)
                        .background(Color.red)
                        .foregroundColor(.white)
                        .cornerRadius(24)
                }
                .disabled(!vm.canSubmit)
            }
            .padding()
            .background(.ultraThinMaterial)
        }
        .navigationTitle("确认订单")
    }
}
```

### 8.2 地址选择

**功能点：**
- 地址列表
- 新增/编辑地址
- 地址搜索（地图选点）
- 定位地址

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `AddressListView` | 地址列表页 |
| `AddressEditView` | 地址编辑页 |
| `AddressSearchView` | 地址搜索页 |
| `MapLocationPicker` | 地图选点组件 |

### 8.3 支付方式

**功能点：**
- 微信支付
- 支付宝
- Apple Pay
- 银行卡支付
- 花呗/信用卡分期

**技术要点：**

```swift
// Apple PassKit
// 【修订-18】原代码有 4 处硬伤，此处给出可编译且与后端 7.2.2 对齐的版本：
//   1) `let paymentController` 在方法内赋值 → 不可变属性无法赋值，编译错误（改 var）；
//   2) 未调用后端 `POST /api/v1/payments/apple-pay/session` 获取 merchantSession，
//      真机上无法完成交易（PKPaymentRequest.merchantSession 必须由苹果校验后下发）；
//   3) PKPaymentAuthorizationControllerDelegate 必须在 didAuthorizePayment 中
//      调用 status/.complete 交还系统，否则授权后 UI 永远转圈；
//   4) 未处理 didFinish（结果关闭时机与后续轮询）。
import PassKit

final class ApplePayManager: NSObject, PKPaymentAuthorizationControllerDelegate {
    private var controller: PKPaymentAuthorizationController?
    private var onResult: ((Result<PaymentToken, PayError>) -> Void)?
    
    func presentPayment(for order: Order,
                        completion: @escaping (Result<PaymentToken, PayError>) -> Void) async {
        onResult = completion
        // 1) 先向后端换取 Apple Pay 支付会话
        guard let session = try? await apiClient.request(.applePaySession(orderId: order.id)) else {
            completion(.failure(.sessionError)); return
        }
        let paymentRequest = PKPaymentRequest()
        paymentRequest.merchantIdentifier = BrandConfig.merchantId          // merchant.com.easyshopping
        paymentRequest.countryCode = "CN"
        paymentRequest.currencyCode = "CNY"
        paymentRequest.supportedNetworks = [.unionPay, .visa, .masterCard]
        paymentRequest.merchantCapabilities = .capability3DS
        paymentRequest.merchantSession = session                            // 关键：后端下发
        paymentRequest.paymentSummaryItems = [
            PKPaymentSummaryItem(label: "商品金额", amount: NSDecimalNumber(decimal: order.itemsTotal)),
            PKPaymentSummaryItem(label: "运费", amount: NSDecimalNumber(decimal: order.deliveryFee)),
            PKPaymentSummaryItem(label: "优惠", amount: NSDecimalNumber(decimal: -order.discount)),
            PKPaymentSummaryItem(label: "实付", amount: NSDecimalNumber(decimal: order.total),
                                 type: .final)
        ]
        
        let controller = PKPaymentAuthorizationController(paymentRequest: paymentRequest)
        controller.delegate = self
        self.controller = controller
        await controller.present()
    }
    
    func paymentAuthorizationController(_ controller: PKPaymentAuthorizationController,
                                        didAuthorizePayment payment: PKPayment,
                                        handler completion: @escaping (PKPaymentAuthorizationResult) -> Void) {
        // 2) 把 payment.token 交给后端验签扣款（POST /api/v1/payments/apple-pay/complete）
        Task {
            do {
                let token = try await apiClient.request(.applePayComplete(payment.token))
                completion(PKPaymentAuthorizationResult(status: .success, errors: nil))
                onResult?(.success(token))
            } catch {
                completion(PKPaymentAuthorizationResult(status: .failure, errors: nil))
                onResult?(.failure(.serverRejected))
            }
        }
    }
    
    func paymentAuthorizationControllerDidFinish(_ controller: PKPaymentAuthorizationController) {
        controller.dismiss()
        controller = nil
    }
}
```

**支付方式覆盖矩阵（【修订-19】前端列了 5 种，后端只实现了 1 种）：**

| 支付方式 | 前端 8.3 | 后端 7.x 实际 | 处理 |
|----------|----------|---------------|------|
| Apple Pay | ✅ | ✅ `POST /payments/apple-pay/session` + `/complete` + `/refund` | 打通 |
| 微信支付 | ✅ | ⚠ 后端 7.1 功能点声明，**未定义 API** | 需后端补 `POST /api/v1/payments/wechat/create`，否则本期隐藏入口 |
| 支付宝 | ✅ | ⚠ 同上 | 需后端补 `POST /api/v1/payments/alipay/create` |
| 银行卡/花呗分期 | ✅ | ⚠ 同上 | 二期，先隐藏 |
| 余额支付 | — | 后端 7.1 声明 | 视需求补充 |

> 说明：微信/支付宝用于**实物商品**交易属于 Apple 允许的第三方支付（Guideline 3.1.3(a) 传统电商豁免），
> 不等同于"引导外部支付"；但**虚拟商品（会员/积分）必须走 IAP**，见 16.1。

**数据依赖（【修订-4】按后端 7.x 对齐）：**

- `POST /api/v1/orders/preview`（订单预览，后端 4.2.2 ✅）
- `POST /api/v1/orders`（提交订单，后端 4.2.2 ✅，**必须带幂等键**，见下方）
- `POST /api/v1/payments/apple-pay/session`（创建支付，后端 7.2.2）
- `POST /api/v1/payments/apple-pay/complete`（完成扣款，后端 7.2.2）
- `POST /api/v1/payments/wechat/create`、`POST /api/v1/payments/alipay/create` ⚠ 后端待补
- `GET /api/v1/user/coupons?status=available`（可用优惠券，后端 5.2.4；原 `GET /api/v1/coupons/available` 路径不存在）
- `GET /api/v1/user/addresses`（地址列表，后端 5.2.2；原 `GET /api/v1/addresses` 路径不存在）

**防重复下单（原计划缺失，后端已按幂等键设计）：**
- `POST /api/v1/orders` 必须携带幂等键（后端 4.4：用户ID+设备ID+商品ID+SKU+时间窗口），
  前端同时做本地节流：提交按钮 3s 内禁用 + 请求 UUID；
- 网络超时**不得直接重试写请求**，应改为查询 `GET /api/v1/orders?status=0`（待付款，8 值编码见 9.1）判断是否已创建成功。

### 8.4 支付结果页与回调轮询（【修订-19】原计划缺失）

从微信/支付宝 App 跳回时，服务端回调可能晚于客户端返回，**不能假设回调已到**：

| 组件 | 说明 |
|------|------|
| `PayResultView` | 支付结果页（成功/处理中/失败三态，禁止只做成功页） |
| `PayStatusPoller` | 轮询 `GET /api/v1/orders/{order_id}` 直到 `status != 0`（0=待付款，8 值编码见 9.1；不再使用 `pending_payment` 等自造枚举），指数退避 1s→2s→4s，上限 30s |
| `PayReturnHandler` | 处理 `URL Scheme` / `Universal Link` 回跳（微信 OpenSDK `onResp`、支付宝 `handleOpenURL`） |

- 处理中态必须可重进（用户中途杀进程，再次进入订单详情仍能查到真实状态）；
- 轮询超时后引导"查看订单"，不得让用户卡在未知态；
- 回跳 URL 必须与 16.4 Universal Links 及 `BrandConfig.urlScheme` 一致（微信要求 UL 与注册完全一致）。

---

## 九、订单管理

### 9.1 订单列表

**功能点：**
- 订单Tab分类（全部/待付款/待发货/待收货/待评价/售后）
- 订单卡片（店铺、商品、状态、金额）
- 操作按钮（付款、确认收货、评价、售后）
- 下拉刷新、上拉加载

**订单状态编码与 Tab 映射（【跨文件同步 S1】对齐 `ios-backend.md` 4.2.3 与 `admin-backend.md` 4.3 的 8 值状态机，禁止前端另造枚举）：**

| status | 后端 `status_text` | 归属 Tab | 卡片主操作按钮 |
|--------|-------------------|---------|--------------|
| 0 | 待付款 | 待付款 | 去付款 / 取消订单（`POST /orders/{id}/cancel`，响应 `status=6`） |
| 1 | 待发货 | 待发货 | 取消订单（已付款走自动退款流程）/ 申请退款 |
| 2 | 待收货 | 待收货 | 查看物流 / 确认收货 / 申请售后 |
| 3 | 已完成 | 待评价（未评价）+ 全部 | 去评价 / 申请售后 / 再次购买 |
| 4 | 退款中 | 售后 | 售后进度（只读） |
| 5 | 已退款 | 售后 | 售后详情 / 再次购买 |
| 6 | 已取消 | 全部 | 再次购买 |
| 7 | 已关闭 | 全部 | 再次购买 |

- **文案与按钮以后端下发为准**：列表/详情响应回传 `status_text` 与 `actions[]`，前端只负责渲染，不自建中文状态文案表（与 `ios-backend.md` 4.2.3 一致）；
- **查询参数**：`GET /api/v1/orders?status={0-7}` 只接受上表 8 个整数值，不传即「全部」；`status=6/7`（已取消/已关闭）只在「全部」Tab 出现，不做独立 Tab；
- ⚠ **两处需契约确认**：① 「待评价」不是独立状态，需 `status=3` + 未评价过滤（可参考 `android-backend.md` 4.3.4 的 `reviewed=false` 口径，`ios-backend.md` 暂未提供该参数）；② 「售后」Tab 需同时命中 4 与 5，而 `status` 目前只接受单值，需契约支持多值（如 `status=4,5`）或前端改为两次请求后合并。

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `OrderListView` | 订单列表页 |
| `OrderTabs` | 订单状态Tab |
| `OrderCard` | 订单卡片 |
| `OrderStatusBadge` | 状态标签 |
| `OrderActionButtons` | 操作按钮组 |

### 9.2 订单详情

**功能点：**
- 订单状态（可视化时间线）
- 收货信息
- 商品清单
- 价格明细
- 订单信息（单号、时间）
- 操作按钮

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `OrderDetailView` | 订单详情页 |
| `OrderStatusTimeline` | 订单状态时间线 |
| `OrderProductList` | 商品清单 |
| `OrderPriceDetail` | 价格明细 |
| `OrderInfoSection` | 订单信息 |
| `OrderActionBar` | 操作栏 |

### 9.3 物流跟踪

**功能点：**
- 物流信息时间线
- 地图轨迹展示
- 快递员信息
- 签收状态

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `LogisticsView` | 物流跟踪页 |
| `LogisticsTimeline` | 物流时间线 |
| `LogisticsMapView` | 地图轨迹 |
| `CourierInfoCard` | 快递员信息 |

### 9.4 确认收货

**功能点：**
- 确认收货弹窗
- 自动确认（15天后自动确认）
- 评价引导

### 9.5 售后

**功能点：**
- 申请退款/退货
- 售后进度跟踪
- 客服介入

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `RefundApplyView` | 退款申请页 |
| `RefundProgressView` | 售后进度页 |
| `RefundReasonSelector` | 退款原因选择 |

**数据依赖（【修订-4】【修订-21】按后端 4.2.3 / 7.4 对齐）：**

| 前端原写法 | 后端实际 | 处理 |
|-----------|---------|------|
| `GET /api/v1/orders` | ✅ 一致（`status` 为 **8 值**：0待付款 1待发货 2待收货 3已完成 4退款中 5已退款 6已取消 7已关闭，见后端 4.2.3；原 7 值「4已取消 5退款」已废弃） | 保留，**Tab 枚举值必须与该编码一一映射**（映射表见 9.1） |
| `GET /api/v1/orders/{id}` | ✅ 一致（返回 `amount_detail`/`timeline`/`actions`） | 保留 |
| `GET /api/v1/orders/{id}/logistics` | ✅ 一致 | 保留 |
| `POST /api/v1/orders/{id}/confirm`（确认收货） | ✅ 后端 4.2.5 已提供 `POST /api/v1/orders/{order_id}/confirm`（响应 `status=3` 已完成 + `points_earned`），列表/详情中的 `actions` 仅作按钮渲染依据 | 保留，前端直接对接 |
| `POST /api/v1/orders/{id}/cancel`（取消订单） | ✅ 后端 4.2.5 已提供（待付款直接取消；已付款走自动退款流程并返回 `refund_no`） | 保留，**注意响应 `status=6`（已取消，8 值编码）**，已付款取消后引导跳转售后进度页 |
| `POST /api/v1/refunds`（申请退款） | 后端为 `POST /api/v1/payments/refund`（7.4） | 已修正 |
| `GET /api/v1/refunds/{id}`（售后详情） | **后端无查询接口**（仅有 `refund_record` 表） | ⚠ 需后端补 `GET /api/v1/refunds/{refund_no}` |
| — | `POST /api/v1/payments/apple-pay/refund`（7.4） | 已补充（Apple Pay 原路退回） |

**9.6 发表评价 / 晒单（【修订-21】原计划只有一句"评价引导"，无模块）：**

| 组件 | 说明 |
|------|------|
| `ReviewPublishView` | 发表评价（评分 + 文字 + 最多9图 + 是否匿名） |
| `ReviewImagePicker` | 相册多选/拍照（复用 2.3 权限） |
| `ReviewRatingControl` | 星级评分 |
| `MyReviewsView` | 我的评价（含"待评价"订单入口） |

- **接口缺口**：前端需 `POST /api/v1/orders/{order_id}/items/{item_id}/reviews`、
  `POST /api/v1/user/reviews`（图片上传可复用头像上传的 OSS 直传签名接口），
  ⚠ 后端 3.4 仅有 `product_review` 表，**未定义任何评价写入 API，需后端补充**；
- 评价图片需本地压缩后再传（单图 ≤ 2MB），上传走**异步**，允许先发文字后补图；
- 内容安全：前端仅做敏感词即时提示，**服务端必须做机审**（发布后置审核态，`status=reviewing` 不外显）。

---

## 十、个人中心

### 10.1 个人信息

**功能点：**
- 头像、昵称、性别、生日
- 账户安全（修改密码、绑定手机）
- 账号注销

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `ProfileView` | 个人中心主页 |
| `ProfileHeader` | 用户信息头部 |
| `ProfileMenuItem` | 菜单项 |
| `EditProfileView` | 编辑个人信息页 |
| `SecuritySettingsView` | 安全设置页 |

### 10.2 收货地址

**功能点：**
- 地址列表（新增/编辑/删除/设为默认）
- 地址搜索
- 地图选点

### 10.3 我的收藏

**功能点：**
- 收藏商品列表
- 收藏店铺列表
- 降价提醒

### 10.4 我的优惠券

**功能点：**
- 优惠券列表（可用/已使用/已过期）
- 优惠券详情
- 领券中心入口

### 10.5 积分

**功能点：**
- 积分余额
- 积分明细
- 积分商城

### 10.6 设置

**功能点：**
- 账号与安全
- 通用设置（清除缓存、字体大小）
- 通知设置
- 隐私设置
- 关于我们
- 退出登录

**数据依赖（【修订-4】按后端 5.2.x 对齐，原路径多处不存在）：**

| 前端原写法 | 后端实际 | 处理 |
|-----------|---------|------|
| `GET /api/v1/user/profile` | ✅ 一致 | 保留 |
| `PUT /api/v1/user/profile` | ✅ 一致 | 保留 |
| — | `POST /api/v1/user/avatar`（5.2.1 头像直传） | 已补充 |
| `GET /api/v1/addresses` | `GET /api/v1/user/addresses` | 已修正 |
| `POST /api/v1/addresses` | `POST /api/v1/user/addresses` | 已修正 |
| `PUT /api/v1/addresses/{id}` | `PUT /api/v1/user/addresses/{address_id}` | 已修正 |
| `DELETE /api/v1/addresses/{id}` | `DELETE /api/v1/user/addresses/{address_id}` | 已修正 |
| — | `POST /api/v1/user/addresses/parse`（5.2.2 智能解析粘贴地址） | 已补充（地址编辑页"粘贴识别"） |
| `GET /api/v1/favorites`（收藏列表） | **后端无 API**（仅有表） | ⚠ 需后端补充 |
| `GET /api/v1/coupons` | `GET /api/v1/user/coupons`（5.2.4，按状态筛选） | 已修正 |
| — | `POST /api/v1/user/coupons/{coupon_id}/claim`（领券） | 已补充（领券中心） |
| `GET /api/v1/points/balance` | `GET /api/v1/user/points`（5.2.3） | 已修正 |
| `GET /api/v1/points/transactions` | `GET /api/v1/user/points/history` | 已修正 |
| — | `POST /api/v1/user/points/check-in`（每日签到） | 已补充 |
| `GET /api/v1/privacy/policy` 等 | 12.1.2 隐私接口 | 见 2.3 |

---

## 十一、消息通知

### 11.1 消息中心

**功能点：**
- 消息分类（系统通知、物流提醒、促销推送、互动消息）
- 未读消息角标
- 消息已读标记
- 消息删除

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `MessageCenterView` | 消息中心页 |
| `MessageTabBar` | 消息分类Tab |
| `MessageItem` | 消息列表项 |
| `MessageDetailView` | 消息详情页 |

### 11.2 推送配置

**功能点：**
- 推送总开关
- 分类开关（促销/物流/互动）
- 免打扰时段设置

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `NotificationSettingsView` | 推送设置页 |
| `NotificationToggle` | 开关组件 |
| `DoNotDisturbView` | 免打扰设置 |

**技术要点：**

```swift
// 本地通知（订单状态变化）
func sendLocalNotification(for event: OrderEvent) {
    let content = UNMutableNotificationContent()
    content.title = event.title
    content.body = event.body
    content.sound = .default
    content.userInfo = ["orderId": event.orderId]
    
    let request = UNNotificationRequest(
        identifier: event.id,
        content: content,
        trigger: nil
    )
    UNUserNotificationCenter.current().add(request)
}

// 远程推送处理（【修订-22】补全三个必处理场景，原计划只写了点击回调）
extension AppDelegate: UNUserNotificationCenterDelegate {
    // 1) 前台收到推送（是否展示横幅，由消息类型决定）
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler:
                                    @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .badge, .sound])
    }
    
    // 2) 用户点击推送 —— 普通/App存活场景
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        Task {
            await DeepLinkManager.shared.handle(response.notification.request.content.userInfo)
            completionHandler()
        }
    }
    
    // 3) 冷启动点击推送 —— App未存活时 didReceive 不会走，必须在
    //    didFinishLaunching 读取 launchOptions[.remoteNotification]，否则会丢路由
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        if let payload = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            pendingColdStartPayload = payload      // 存起来，等首屏 ready 后再路由
        }
        return true
    }
}
```

**推送载荷与深链（【修订-22】与后端 6.2.3 对齐）：**

后端 6.2.3 的推送载荷使用 `deep_link: "myapp://order/detail/90001"` 形式，必须与 1.4 `DeepLinkParser`
的路由表保持一致，否则点击推送会落到空页面。典型映射：

| 后端 deep_link | 前端 Route |
|----------------|-----------|
| `myapp://order/detail/{id}` | `.orderDetail(orderId:)` |
| `myapp://logistics/track/{id}` | `.orderLogistics(orderId:)` |
| `myapp://promotion/{id}` | `.activityDetail(activityId:)` |
| `myapp://coupons` / `myapp://orders` / `myapp://cs` | `.coupons` / `.orderList(nil)` / `.customerService` |

**数据依赖（【修订-4】路径已对齐后端 6.3.2）：**

| 前端原写法 | 后端实际 | 处理 |
|-----------|---------|------|
| `GET /api/v1/messages` | ✅ 一致（按 `type` 分类、`unread` 过滤） | 保留 |
| `PUT /api/v1/messages/{id}/read` | ✅ 一致 | 保留 |
| `DELETE /api/v1/messages/{id}` | ✅ 一致 | 保留 |
| — | `PUT /api/v1/messages/read-all`（全部已读） | 已补充 |
| `PUT /api/v1/user/notification-settings` | `PUT /api/v1/user/push-settings`（GET/PUT） | 已修正 |
| — | `POST /api/v1/device/bind`（设备注册，后端 2.3.2） | 已补充（登录后必调） |
| — | `PUT /api/v1/device/push-token`（APNs token 上报） | 已补充（didRegisterForRemoteNotifications 后必调） |

> 推送 token 变化（重装、系统回收）时必须重新上报，否则会持续推送到已失效 token。

---

## 十二、客服系统

### 12.1 在线客服

**功能点：**
- 实时聊天（WebSocket）
- 常见问题快捷入口
- 人工客服转接
- 聊天记录
- 满意度评价

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `CustomerServiceView` | 客服聊天页 |
| `ChatMessageList` | 消息列表 |
| `ChatInputBar` | 输入栏 |
| `QuickQuestionPanel` | 常见问题面板 |
| `ChatProductCard` | 聊天中的商品卡片 |
| `SatisfactionRating` | 满意度评价 |

**技术要点：**

```swift
// WebSocket聊天（【修订-23】补鉴权、心跳、断线重连——原实现连上即用，
// 后台30秒后 iOS 会挂起套接字，且未鉴权的 wss 连接后端必然拒绝）
final class ChatWebSocketManager: ObservableObject {
    @Published var messages: [ChatMessage] = []
    @Published var connectionState: ConnectionState = .disconnected
    
    private var webSocketTask: URLSessionWebSocketTask?
    private var heartbeatTimer: Timer?
    private var reconnectAttempts = 0
    
    func connect() {
        guard let token = KeychainManager.shared.get(.accessToken) else { return }
        // 后端 10.2.1：连接必须带鉴权参数（token 或 tickets 里的一次性 ticket）
        var components = URLComponents(string: "wss://chat.easyshopping.com/ws")!
        components.queryItems = [URLQueryItem(name: "token", value: token)]
        let url = components.url!
        
        let session = URLSession(configuration: .default,
                                 delegate: self, delegateQueue: .main)
        webSocketTask = session.webSocketTask(with: url)
        webSocketTask?.resume()
        connectionState = .connected
        reconnectAttempts = 0
        startHeartbeat()
        receiveMessage()
    }
    
    // 心跳：每25s ping，后端30s无心跳即断开（对齐后端 10.2.1 心跳约定）
    private func startHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 25, repeats: true) { [weak self] _ in
            self?.webSocketTask?.send(.string("{\"type\":\"ping\"}")) { _ in }
        }
    }
    
    // 断线重连：指数退避 1s/2s/4s/8s/最大30s，App 回前台立即重连
    private func scheduleReconnect() {
        reconnectAttempts += 1
        let delay = min(pow(2.0, Double(reconnectAttempts - 1)), 30)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard self?.connectionState != .connected else { return }
            self?.connect()
        }
    }
    
    private func receiveMessage() {
        webSocketTask?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    if let data = text.data(using: .utf8),
                       let chatMsg = try? JSONDecoder().decode(ChatMessage.self, from: data) {
                        // 按 messageId 去重：重连后服务端会重推离线消息
                        DispatchQueue.main.async {
                            guard !self.messages.contains(where: { $0.id == chatMsg.id }) else { return }
                            self.messages.append(chatMsg)
                        }
                    }
                case .data:
                    break
                @unknown default:
                    break
                }
                self.receiveMessage()
            case .failure:
                DispatchQueue.main.async { self.connectionState = .disconnected }
                self.scheduleReconnect()
            }
        }
    }
    
    // 历史消息：走 HTTP 而非 WebSocket（离线/冷启动场景 socket 不可用）
    // GET /api/v1/cs/sessions/{session_id}/messages?before_id=
    func loadHistory(sessionId: String) async { /* ... */ }
}
```

**数据依赖（【修订-4】【修订-23】按后端 10.3 对齐）：**

| 前端原写法 | 后端实际 | 处理 |
|-----------|---------|------|
| WebSocket `wss://chat.easyshopping.com/ws` | 后端 10.2.1 自定义消息协议（含 ping/pong、ack、历史拉取） | 已补鉴权与心跳 |
| `GET /api/v1/tickets`（工单列表） | `GET /api/v1/cs/tickets`（⚠ 需确认列表接口是否定义） | 路径前缀统一为 `/cs/` |
| `POST /api/v1/tickets`（创建工单） | `POST /api/v1/cs/tickets` | 已修正 |
| `GET /api/v1/tickets/{id}`（工单详情） | ⚠ 后端未定义详情查询 | 需后端补 `GET /api/v1/cs/tickets/{ticket_no}` |
| `POST /api/v1/tickets/{id}/reply`（工单回复） | ⚠ 后端未定义，仅有 `POST /api/v1/cs/tickets/{ticket_no}/rate`（评价） | 需后端补回复接口 |
| — | `POST /api/v1/cs/sessions`（10.3 创建会话，返回 session_id 供 ws 使用） | 已补充 |

### 12.2 工单系统

**功能点：**
- 提交工单
- 工单列表
- 工单详情
- 工单回复

**页面/组件：**

| 组件 | 说明 |
|------|------|
| `TicketListView` | 工单列表 |
| `TicketCreateView` | 创建工单 |
| `TicketDetailView` | 工单详情 |

**数据依赖：**
- `POST /api/v1/cs/sessions`（创建客服会话，后端 10.3）
- `GET /api/v1/cs/sessions/{session_id}/messages`（历史消息，后端 10.3）
- `POST /api/v1/cs/tickets`（创建工单，后端 10.3）
- `POST /api/v1/cs/tickets/{ticket_no}/rate`（工单评价，后端 10.3）
- `GET /api/v1/cs/tickets`、`GET /api/v1/cs/tickets/{ticket_no}`、
  `POST /api/v1/cs/tickets/{ticket_no}/reply` ⚠ **后端未定义，需补充**（见 12.1 对照表）
- WebSocket（实时消息，协议见后端 10.2.1）

---

## 十三、离线能力

### 13.1 本地缓存

**功能点：**
- 首页配置缓存
- 商品详情缓存
- 搜索历史缓存
- 图片缓存（NSCache + 磁盘）
- 离线浏览历史

**技术要点：**

```swift
// 多级缓存：内存 -> 磁盘 -> 网络
final class CacheManager {
    private let memoryCache = NSCache<NSString, NSData>()   // 见下方成本限制
    private let diskCache = DiskCache()
    
    // 缓存策略
    enum CacheStrategy {
        case memoryOnly
        case diskOnly
        case memoryAndDisk
        case networkFirst
        case cacheFirst
        case staleWhileRevalidate
    }
    
    init() {
        // 【修订-31】限制内存缓存占用，避免大JSON缓存把进程推到 jetsam 阈值
        memoryCache.totalCostLimit = 50 * 1024 * 1024      // 50MB
        memoryCache.countLimit = 300
    }
    
    // 【修订-17】原实现 switch 存在 `default: break`，函数无返回值，无法编译；
    // 且未处理 networkFirst/cacheFirst 两个最常用策略。
    func get<T: Codable>(_ key: String, type: T.Type, strategy: CacheStrategy) async throws -> T {
        switch strategy {
        case .memoryOnly:
            if let data = memoryCache.object(forKey: key as NSString) as? Data {
                return try JSONDecoder().decode(T.self, from: data)
            }
            throw CacheError.miss
            
        case .diskOnly:
            if let data = try? diskCache.read(key) {
                return try JSONDecoder().decode(T.self, from: data)
            }
            throw CacheError.miss
            
        case .memoryAndDisk:
            if let data = memoryCache.object(forKey: key as NSString) as? Data {
                return try JSONDecoder().decode(T.self, from: data)
            }
            if let data = try? diskCache.read(key) {
                memoryCache.setObject(data as NSData, forKey: key as NSString)
                return try JSONDecoder().decode(T.self, from: data)
            }
            throw CacheError.miss
            
        case .networkFirst:
            do {
                let fresh = try await fetchAndCache(key)
                return fresh
            } catch {
                if let data = try? diskCache.read(key),
                   let cached = try? JSONDecoder().decode(T.self, from: data) {
                    return cached          // 网络失败降级到缓存，不向上抛
                }
                throw error
            }
            
        case .cacheFirst:
            if let data = try? diskCache.read(key),
               let cached = try? JSONDecoder().decode(T.self, from: data) {
                return cached
            }
            return try await fetchAndCache(key)
            
        case .staleWhileRevalidate:
            // 先返回缓存，后台更新
            if let data = try? diskCache.read(key),
               let cached = try? JSONDecoder().decode(T.self, from: data) {
                Task { await fetchAndCache(key) }
                return cached
            }
            return try await fetchAndCache(key)
        }
    }
    
    // 【修订-31】多账号隔离：缓存Key必须包含 userId，登出时全量清理（见 3.2 logout）
    func cacheKey(_ raw: String, userId: String?) -> String {
        "cache.\(userId ?? "guest").\(raw)"
    }
}
```

> 图片缓存统一交给 Kingfisher（`ImageCache.default.costLimit` 设为 100MB，见 14.3），
> 不要自建第二套图片缓存，否则两套缓存各吃 100MB，低端机内存直接超标。

### 13.2 离线浏览

**功能点：**
- 离线商品浏览
- 离线购物车操作
- 网络恢复后自动同步

### 13.3 网络状态检测

**功能点：**
- 实时网络状态监听
- 弱网提示
- 离线页面展示
- 请求队列（离线时排队）

**技术要点：**

```swift
import Network

final class NetworkMonitor: ObservableObject {
    @Published var isConnected: Bool = true
    @Published var connectionType: ConnectionType = .wifi
    
    private let monitor = NWPathMonitor()
    
    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.isConnected = path.status == .satisfied
                self?.connectionType = ConnectionType(from: path)
            }
        }
        monitor.start(queue: DispatchQueue.global())
    }
}

// 离线队列
// 【修订-31】原实现有三个隐患：
//   1) 直接 removeAll，若某条请求失败会连带丢掉其余待发请求；
//   2) 写请求重放没有幂等键，网络抖动会造成重复加购/重复下单；
//   3) 支付、评价等敏感操作被排队重放，回前台后可能出现"用户不知情的扣款"。
final class OfflineQueue {
    private var pendingRequests: [QueuedRequest] = []
    
    // 仅允许入队：加购、删除、地址编辑等可重放的幂等写操作
    private let replayable: Set<APIName> = [.addToCart, .updateCart, .deleteCart,
                                            .addAddress, .updateAddress]
    
    func enqueue(_ request: APIRequest) throws {
        guard replayable.contains(request.name) else {
            throw OfflineQueueError.notReplayable(request.name)   // 支付/下单/评价不入队
        }
        pendingRequests.append(QueuedRequest(id: UUID().uuidString,   // 幂等键
                                             request: request,
                                             createdAt: Date()))
    }
    
    func sync() async {
        var remain: [QueuedRequest] = []
        for item in pendingRequests {
            do {
                // 后端 4.4 已有幂等键设计，客户端复用同一条 item.id
                try await apiClient.execute(item.request, idempotencyKey: item.id)
            } catch {
                // 单条失败只丢弃该条并上报，不阻塞后续
                remain.append(item)
                Logger.error("offline queue drop: \(item.id) \(error)")
            }
        }
        pendingRequests = remain
    }
}
```

### 13.4 WebView 与 JSBridge（【修订-28】原计划缺失）

商品图文详情、活动页均走 H5（2.4 兜底方案），必须有受控桥接：

| 桥接口（JS→Native） | 参数 | 说明 |
|----------------------|------|------|
| `getUserToken` | — | 返回短期 token（**不可返回 refresh_token**） |
| `addToCart` | skuId, qty | 转发到原生加购 |
| `navigate` | url / route | 仅允许白名单域名与已注册 Route |
| `payResult` | orderId, status | H5 支付结果回传（用于轮询终止） |
| `closePage` | — | 关闭当前 H5 容器 |
| `share` | title, image, link | 调起原生分享 |

**安全要求：**
- 域名白名单（`easyshopping.com` 及其子域），非白名单一律拒绝注入；
- `WKUserContentController` 按页面销毁时 `removeScriptMessageHandler(forName:)`，
  否则 handler 强引用 controller → **循环引用导致内存泄漏**（`addDelegate` 之后必须成对移除）；
- 关闭 `file://` 访问与 `allowFileAccessFromFileURLs`；
- 只暴露必要方法，禁止通用 `evaluateJavaScript` 透传用户输入；
- Cookie 同步：原生登录态与 H5 Cookie 需单向同步（登录成功后 `WKHTTPCookieStore` 注入，登出清除）。

---

## 十四、性能优化

### 14.1 启动速度

**优化指标（【修订-25】与 2.1 完全一致，避免两处指标打架）：**

| 阶段 | 冷启动 | 热启动 | 首屏渲染 | 首屏可交互 |
|------|--------|--------|----------|------------|
| 首版目标 | ≤ 2.5s | ≤ 1.0s | ≤ 1.8s | ≤ 2.8s |
| 迭代目标 | ≤ 2.0s | ≤ 0.8s | ≤ 1.5s | ≤ 2.5s |
| 低端机下限 | ≤ 3.5s | ≤ 1.5s | ≤ 2.5s | ≤ 4.0s |

> 度量：`os_signpost` + `MXAppLaunchMetric`，取 P50/P90；iPhone 12 为基准机，SE2 为下限机；
> 排除 prewarm 进程。口径见 2.1。

**优化策略：**

| 策略 | 具体措施 |
|------|----------|
| 减少启动任务 | 非必要SDK延迟初始化 |
| 二进制重排 | 用 `.ordinal` Order File 基于启动路径Profile生成 |
| 动态库合并 | 已由 SPM 静态链接，检查无 `.framework` 动态依赖（dyld4 优化） |
| 首屏预加载 | 核心数据本地缓存（13.1 staleWhileRevalidate） |
| 网络预连接 | DNS预解析、TCP预连接 |
| 渲染优化 | 首屏只渲染可见区域 |
| 度量与回归 | CI 集成启动耗时看板，单版本劣化 >10% 阻断合入 |

```swift
// 启动任务分级
enum LaunchTaskPriority {
    case critical    // 必须在首屏前完成
    case important   // 首屏后0.5s内
    case deferred    // 首屏后空闲时
    case lazy        // 用户交互后
}

// 延迟初始化示例
func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    // Critical
    setupLogger()
    setupCrashReporter()
    
    // Important（延迟0.5s）
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
        setupAnalytics()
        setupPush()
    }
    
    // Deferred（空闲时）
    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
        preloadHomeData()
        preloadImages()
    }
    
    return true
}
```

### 14.2 列表流畅度

**优化指标（【修订-25】按机型分档，原"60fps 即满帧"在 ProMotion 机型上是错的）：**

| 指标 | 60Hz 机型（SE2/13/14） | 120Hz 机型（Pro 系列） | 度量方式 |
|------|------------------------|------------------------|----------|
| 滚动帧率 | ≥ 58fps（达标率 ≥ 95%） | ≥ 110fps 为优秀，≥ 58fps 为底线 | `CADisplayLink` 采样 + Instruments |
| 单帧耗时 | P95 ≤ 16.7ms | P95 ≤ 16.7ms（120Hz 下按 8.3ms 为优秀线） | `os_signpost` |
| 卡顿（hitch） | hitch ratio < 1% | hitch ratio < 1% | MetricKit `MXCPUMetric`/`MXAnimationMetric` |
| 列表滑动帧率波动 | < 5fps | < 10fps | APM 后台聚合 |

> 说明：电商列表在 120Hz 上**不强制跑满 120fps**（功耗与发热代价高），底线是 60Hz 档的达标线；
> 但**不得出现明显顿挫**（hitch）。真机采集，禁止用模拟器数据作为验收依据。

**优化策略（按选型分两栏，避免把 UIKit 手段误加到 SwiftUI 上）：**

| 策略 | 具体措施 | 适用 |
|------|----------|------|
| 异步绘制 | 复杂视图预渲染（SwiftUI 中用 `drawingGroup()`） | 双方 |
| Cell复用 | 严格 dequeue | **仅 UIKit 下沉后的列表** |
| 高度缓存 | 预计算Cell高度 | 双方 |
| 图片异步解码 | 后台线程解码（Kingfisher `downsampling`） | 双方 |
| 减少离屏渲染 | 避免cornerRadius+masksToBounds，用 `clipShape` | 双方 |
| 按需加载 | 只加载可见Cell（LazyVStack/LazyVGrid 天然具备） | 双方 |
| 数据预取 | 滚动到2/3时预加载 + `ImagePrefetcher` | 双方 |
| 状态最小化 | `@StateObject` 粒度下沉到 Cell 级，避免整列表重算 | SwiftUI 特有 |
| 图层合并 | 列表滚动时关闭阴影/材质模糊，停滚再恢复 | SwiftUI 特有 |

### 14.3 内存占用

**优化指标（【修订-25】按机型分档，单一阈值在低端机会直接被系统杀掉）：**

| 指标 | 6GB+ 机型（Pro） | 4GB 机型（iPhone 12/13） | 3GB 机型（SE2/8） |
|------|------------------|--------------------------|-------------------|
| 前台常驻内存 | < 200MB | < 150MB | < 110MB |
| 后台内存 | < 60MB | < 50MB | < 40MB |
| 内存警告 | 收到即清图片内存缓存，连续2次警告降级到低清图 | 同左 | 同左 |

- 度量：Instruments Allocations / Leaks + `MXMemoryMetric`（后台聚合真实 OOM 率）；
- **OOM 属于崩溃**，需计入崩溃率指标（Crashlytics `erased`/`hang` 与 OOM 分开看）；
- 目标：无泄漏（Leaks 0 个）、OOM 率 < 0.1%。

**优化策略：**

| 策略 | 具体措施 |
|------|----------|
| 图片缓存限制 | Kingfisher `costLimit` 100MB（低端机 50MB），LRU淘汰 |
| 内存警告处理 | 及时释放缓存（`didReceiveMemoryWarning` → 清 NSCache + 释放离屏大图） |
| 大图解码缩放 | 按显示尺寸解码（KFImage `downsampling`），禁止 4000px 原图直接上屏 |
| 对象复用 | 避免频繁创建大对象 |
| 循环引用排查 | [weak self]、WKWebView/Timer/NotificationCenter 成对移除、Instruments检测 |

### 14.4 电量优化

**优化策略：**
- 减少网络请求合并（BFF 聚合，见 4.2）
- 定位按需开启（进入地址页才 startUpdating，离开立即 stop）
- 动画暂停（不可见时停表，见 4.1 BannerCarousel）
- 定时器合并（倒计时/心跳/轮询统一由 `TickerHub` 单定时器驱动）
- 减少CPU空转（后台降频、WebSocket 挂起）
- 后台任务不申请 `beginBackgroundTask` 做非必要上传

### 14.5 埋点、APM 与日志（【修订-28】原计划完全缺失）

电商平台的数据闭环依赖埋点，缺失会导致增长/推荐/风控全部失能：

| 能力 | 方案 | 要求 |
|------|------|------|
| 业务埋点 | 自研 `Tracker`（批量 20 条 / 30s 上报，失败落地重传） | 必须有：曝光、点击、停留时长、加购、下单漏斗、搜索词 |
| 埋点公共参数 | deviceId / userId / sessionId / pageId / ts / appVersion / os / network | 与后端约定同一 schema |
| 性能 APM | MetricKit（启动、帧率、卡顿、内存、耗电）+ `os_signpost` 自定义区间 | 真机聚合，T+0 看板 |
| 崩溃 | Crashlytics（18 已列） | 与灰度分桶打标签（2.5） |
| 日志 | `Logger` 分级（debug/info/warn/error），**敏感字段脱敏**（手机号/地址/Token 只留前后2位） | 对齐后端 12.5 数据脱敏规则 |
| 隐私 | 埋点字段进 `PrivacyInfo.xcprivacy` 声明与 App 隐私标签 | 见 16.2 |

- 埋点必须**幂等 + 可关**：隐私同意前不采集（2.3），撤回同意即停；
- 关键漏斗埋点需有自动化看板，转化率环比下跌 >10% 触发告警。

---

## 十五、适配与兼容

### 15.1 屏幕尺寸

**适配方案（【修订-26】单位为逻辑分辨率 pt，非物理像素；补齐 2025/2026 机型）：**

| 设备 | 逻辑分辨率 (pt) | 适配策略 |
|------|----------|----------|
| iPhone SE (2/3rd) | 375x667 | 紧凑布局，减少留白；**最窄基准，必须优先验收** |
| iPhone 12/13/14 | 390x844 | 标准布局 |
| iPhone 14/15/16/17 | 393x852 | 标准布局 |
| iPhone 14/15 Plus | 428x926 | 宽松布局 |
| iPhone 16/17 Plus | 430x932 | 宽松布局 |
| iPhone 14/15 Pro Max | 430x932 | 宽松布局 |
| iPhone 16 Pro / 17 Pro | 402x874 | 标准布局 + 灵动岛避让 |
| iPhone 16/17 Pro Max | 440x956 | 宽松布局 |
| iPhone 17 Air（超薄机型） | 按实际真机校准 | 动态岛 + 单摄，注意重量感与安全区 |
| iPad（若计划支持） | 768x1024+ | `NavigationSplitView` 分栏；**若本期不支持，需在 Info.plist 隐式裁剪 iPhone-only，避免审核"未适配 iPad"投诉** |

> 2026 年新增机型必须走**真机适配 checklist**：灵动岛遮挡、Home Indicator、
> 横屏（直播/视频页）与深色模式逐页过一遍。

**技术要点：**

```swift
// 使用ViewThatFits自适应
struct AdaptiveLayout<CompactContent: View, RegularContent: View>: View {
    @ViewBuilder var compact: () -> CompactContent
    @ViewBuilder var regular: () -> RegularContent
    
    var body: some View {
        ViewThatFits(in: .horizontal) {
            compact()
            regular()
        }
    }
}

// 安全区域适配
struct ContentView: View {
    var body: some View {
        ScrollView {
            // ...
        }
        .safeAreaInset(edge: .bottom) {
            // 底部操作栏自动避开Home Indicator
        }
    }
}
```

### 15.2 iOS版本兼容

**最低支持：iOS 16.0（【修订-2】决策，原为 15.0，与 NavigationStack 等 API 冲突）**

| 功能 | 最低版本 | 适配策略 |
|------|----------|----------|
| `NavigationStack` / `NavigationPath` / `presentationDetents` / `ViewThatFits` / App Intents | iOS 16 | 基线 API，直接使用 |
| SwiftUI 基础（AsyncImage/@FocusState/refreshable/safeAreaInset） | iOS 15 | — |
| Live Activities | iOS 16.1 | `if #available` 条件编译，低版本不显示 |
| Dynamic Island | iOS 16.1 | 条件编译 |
| StoreKit 2 `Product.purchase()` | iOS 15 | 可用，推荐 |
| StandBy / TipKit | iOS 17 | 条件编译，低版本自定义实现替代 |
| 120Hz ProMotion 滚动 | iOS 16 | 按机型分档验收（14.2） |

**降级策略统一约定：** 所有 `#available` 分支必须有**可测试的降级路径**，禁止写 `if #available` 后留空实现；
每个条件分支在测试用例中必须有对应低版本覆盖（最低版本机型纳入回归机池）。

### 15.3 Dark Mode

**功能点：**
- 自动跟随系统
- 支持手动切换
- 图片资源适配

**技术要点：**

```swift
// 颜色适配
extension Color {
    static let brandPrimary = Color("BrandPrimary") // Asset中配置Dark Mode
}

// 图片适配
// Assets.xcassets中为图片配置Dark Mode变体

// 强制模式切换
struct SettingsView: View {
    @AppStorage("colorScheme") private var colorScheme: String = "system"
    
    var body: some View {
        Picker("外观", selection: $colorScheme) {
            Text("跟随系统").tag("system")
            Text("浅色").tag("light")
            Text("深色").tag("dark")
        }
    }
}
```

### 15.4 Dynamic Type

**功能点：**
- 支持系统字体大小调节
- 自定义字体使用scaledFont
- 布局自适应

**技术要点：**

```swift
// 使用UIFontMetrics
// 【修订-26】原代码 `UIFont.asSwiftUIFont(weight:)` 并不存在，无法编译。
// 正确做法：UIFontMetrics 缩放后通过 `Font(uiFont)` 桥接（iOS 16 起有 Font(UIFont) 初始化器）。
extension Font {
    static func appFont(_ style: Font.TextStyle,
                        weight: Font.Weight = .regular) -> Font {
        let metrics = UIFontMetrics(forTextStyle: style)
        let base = UIFont.preferredFont(forTextStyle: style)
        let scaled = metrics.scaledFont(for: base,
                                        compatibleWith: UITraitCollection.preferredContentSizeCategory)
        return Font(scaled).weight(weight)
    }
}

// SwiftUI 原生方案（更简单，优先使用）
// Text("标题").font(.system(.headline, design: .default, weight: .semibold))

// 布局自适应（避免固定高度）
VStack {
    Text("标题")
        .font(.appFont(.headline))
    Text("内容")
        .font(.appFont(.body))
}
// 不使用固定frame高度；列表行高用 `.fixedSize(horizontal: false, vertical: true)`
```

### 15.5 无障碍（Accessibility）（【修订-28】原计划缺失）

| 要求 | 落地方式 |
|------|----------|
| VoiceOver（旁白） | 所有图片 `accessibilityLabel`（商品图读标题+价格）、价格读作"八千九百九十九元"、状态标签朗读 |
| 动态字体 | 15.4，禁止写死字号与固定行高 |
| 降低动态效果 | 尊重 `UIAccessibility.isReduceMotionEnabled`，关闭无限轮播与视差 |
| 对比度 | 文本与背景对比度 ≥ 4.5:1（深色模式单独校验） |
| 触达区域 | 可点击区域 ≥ 44x44pt（`contentShape` + `padding`） |
| 色彩不止一种 | 状态不能只靠颜色区分（如库存状态需配图标/文字） |
| Switch Control / Voice Control | 按钮需有可读名称（"加入购物车"而非"按钮3"） |

> 无障碍不是可选项：Apple 会在审核中抽查 VoiceOver 可用性，
> 且 Dynamic Type 关闭（大字号）导致文字截断是电商类高频投诉。

### 15.6 本地化与国际化（【修订-28】原计划缺失）

- 资源统一用 `Localizable.xcstrings`（1.2 目录已列），**禁止代码里硬编码用户可见文案**；
- 金额：`Decimal.formatted(.currency(code:))` 而非 `"¥" + String`（2.x 已按此修正）；
- 日期/时间：`Date.FormatStyle` + 用户时区，倒计时用 2.4 的服务端时间校准；
- 语言：首版仅简体中文，但**架构上必须先拆词表**，否则后续多语言改造成本翻倍；
  若计划出海，需在本版本即完成 `zh-Hans` / `en` 双份词表与伪本地化（Pseudo-Localization）检查；
- 右文布局（RTL）不做（暂无目标市场），但禁止用 `leading` 写死方向以外的绝对坐标。

---

## 十六、App Store合规

### 16.1 内购规则（IAP）

**功能点：**
- 虚拟商品必须使用Apple IAP（会员、虚拟积分/点数，**注意区分**）
- 实物商品可使用第三方支付（微信/支付宝，Guideline 3.1.3(a) 传统电商豁免）
- 不得引导用户使用外部支付

**审核要点：**

| 规则 | 说明 |
|------|------|
| 虚拟商品必须IAP | 会员、虚拟货币、付费内容等"App 内消费的数字商品" |
| 实物可用第三方 | 实物商品、线下服务、O2O（本 App 主体交易） |
| **优惠券要看归属** | 用于**实物**商品的优惠券、平台补贴 **不强制 IAP**；若优惠券可"充值购买且用于虚拟商品"则必须 IAP。上线前逐个 SKU 归类并留档 |
| 不得引导外部支付 | 不能在 App 内展示外部支付链接/二维码/诱导文案（如"微信更便宜"） |
| 恢复购买 | 必须提供"恢复购买"（Restore Purchases）入口，否则拒审 |
| 订阅规则 | 自动续费需明示价格、周期、续费规则与取消路径；价格变动需提前推送告知；提供 `manageSubscriptionsSheet` 跳系统管理 |
| 试用期 | 免费试用必须在购买页明确展示到期后价格 |

```swift
// StoreKit 2实现（【修订-20】补服务端验签与发货——只在客户端 transaction.finish()
// 是"自欺欺人"：越狱设备可伪造本地交易状态，虚拟商品必须以服务端结论为准）
import StoreKit

final class IAPManager: ObservableObject {
    @Published var products: [Product] = []
    private var updates: Task<Void, Never>?
    
    func loadProducts() async {
        let productIDs = ["com.easyshopping.vip.monthly",
                          "com.easyshopping.vip.yearly",
                          "com.easyshopping.vip.lifetime",
                          "com.easyshopping.points.100"]
        products = (try? await Product.products(for: productIDs)) ?? []
    }
    
    func purchase(_ product: Product, userId: String) async throws -> Transaction {
        // appAccountToken 绑定业务 userId，服务端据此发货，防止串号
        var options = Product.PurchaseOptions()
        options.appAccountToken = UUID(uuidString: userId.stableUUID) ?? nil
        
        let result = try await product.purchase(options: options)
        switch result {
        case .success(let verification):
            let transaction = try checkVerified(verification)
            // 1) 把 JWS 签名串上传后端验签（⚠ 后端未定义，需补 /iap/verify，见下）
            try await apiClient.request(.iapVerify(jws: transaction.jwsRepresentation))
            // 2) 服务端确认发货后才 finish，否则下次启动会重新询问未完成交易
            await transaction.finish()
            return transaction
        case .userCancelled:
            throw IAPError.userCancelled
        case .pending:
            throw IAPError.pending        // 家长批准/需外部认证，不得当作失败
        @unknown default:
            throw IAPError.unknown
        }
    }
    
    // 监听交易队列（App 启动即开启，处理续订、家人共享、退款）
    func observeTransactions() {
        updates = Task.detached {
            for await update in Transaction.updates {
                // 同样走服务端 verify + finish
            }
        }
    }
    
    func restorePurchases() async {
        // 【合规必需】恢复购买入口，设置页与会员页均需展示
        try? await AppStore.sync()
    }
}
```

**【修订-20】IAP 服务端接口缺口（阻塞项）：**

| 需要的接口 | 作用 | 后端现状 |
|-----------|------|----------|
| `POST /api/v1/iap/verify` | 校验 App Store 签名的 JWS，防伪造 | ⚠ **未定义，必须补** |
| `GET /api/v1/iap/transactions` | 未完成交易恢复、设备间同步 | ⚠ 未定义 |
| App Store Server Notifications V2 回调 | 订阅续订/退款/撤销 → 服务端发货或回收 | ⚠ 未定义 |
| `POST /api/v1/iap/entitlements` | 查询已购权益（会员状态） | ⚠ 未定义 |

> 在上述接口就绪前，**会员/积分购买不得上线**（否则出现"扣钱不发货"，属重大资损与投诉）。

### 16.2 隐私政策

**功能点：**
- 隐私政策页面（首次启动展示）
- 隐私清单（PrivacyInfo.xcprivacy）
- 数据收集声明
- 第三方SDK数据共享声明
- **App Store 隐私标签（App Privacy Labels）与隐私清单必须一致**

**技术要点：**

```xml
<!-- PrivacyInfo.xcprivacy -->
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <!-- 【修订-27】若使用 IDFA/广告归因，必须声明跟踪；否则此项为 false 且不得触发 ATT -->
    <key>NSPrivacyTracking</key>
    <false/>
    <key>NSPrivacyTrackingDomains</key>
    <array/>
    <key>NSPrivacyCollectedDataTypes</key>
    <array>
        <dict>
            <key>NSPrivacyCollectedDataType</key>
            <string>NSPrivacyCollectedDataTypeDeviceID</string>
            <key>NSPrivacyCollectedDataTypeLinked</key>
            <true/>
            <key>NSPrivacyCollectedDataTypeTracking</key>
            <false/>
            <key>NSPrivacyCollectedDataTypePurposes</key>
            <array>
                <string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string>
            </array>
        </dict>
        <!-- 需按实际采集补全（否则触发 ITMS-91053 / 拒审）：
             Name（昵称）、EmailAddress、PhoneNumber（手机号）、
             PhysicalAddress（收货地址）、Photos（晒单图片）、
             ProductInteraction（浏览/搜索埋点）、PurchaseHistory（订单）-->
    </array>
    <key>NSPrivacyAccessedAPITypes</key>
    <array>
        <dict>
            <key>NSPrivacyAccessedAPIType</key>
            <string>NSPrivacyAccessedAPICategoryUserDefaults</string>
            <key>NSPrivacyAccessedAPITypeReasons</key>
            <array>
                <string>CA92.1</string>
            </array>
        </dict>
        <!-- 【修订-27】必选原因（Required Reason API）不止 UserDefaults：
             File Timestamp（读取缓存文件 mtime，理由 DDA9.1）—— 图片/文件缓存会触发；
             System Boot Time（网络质量/耗时计算，理由 35F9.1）—— APM 会触发；
             Disk Space（缓存清理前检查剩余空间，理由 E174.1）—— 清缓存功能会触发。
             Kingfisher/自研磁盘缓存只要记录文件时间即需声明。-->
    </array>
</dict>
</plist>
```

**第三方 SDK 清单与隐私声明（上线前逐个核对）：**

| SDK | 需声明/处理 |
|-----|-------------|
| 微信 OpenSDK | 隐私标签 + 清单声明 + `didFinishLaunching` 前调 `registerApp`（迟调会审核失败） |
| 支付宝 SDK | 隐私标签 + URL Scheme 回调 |
| Crashlytics / Firebase | 清单声明、数据跨境说明（中国区需评估数据出境） |
| 推送 SDK（若用第三方） | 需在 `didFinishLaunching` 前初始化 |
| 地图/定位 SDK | 定位用途文案（2.3）+ 后台定位**不得**申请 |

**与后端对齐：** 数据收集/导出/删除走 `GET /api/v1/privacy/data-export`、
`DELETE /api/v1/privacy/account-data`、`GET/PUT /api/v1/privacy/consent-settings`（后端 12.1.2）。

### 16.3 审核注意事项

**常见拒审原因及对策：**

| 原因 | 对策 |
|------|------|
| 功能不完整 | 确保所有按钮有实际功能（本期未实现的登录/支付入口一律隐藏，见 3.1、8.3） |
| 崩溃/闪退 | 全面测试，修复所有崩溃 |
| 隐私政策缺失 | 提供可访问的隐私政策链接 |
| **启动即强制登录** | 改为游客可浏览，关键动作再登录（2.1 `RootView`） |
| **权限申请时机不当** | 先隐私弹窗 → 后按需申请 → 文案说明用途（2.3） |
| **第三方登录未配 Apple 登录** | 提供 Sign in with Apple（3.1，Guideline 4.8） |
| **隐私清单缺失/不全** | PrivacyInfo.xcprivacy + 隐私标签 + 必选原因API（16.2） |
| **SDK 未申报数据** | 微信/支付宝/推送 SDK 申报（16.2 表） |
| 内购不合规 | 虚拟商品使用IAP + 恢复购买（16.1） |
| 热修复违规 | 不使用JSPatch等违规方案（2.4） |
| 截图与实际不符 | 使用真实App截图 |
| 测试账号 | 提供Demo账号和密码（含未过审的灰度账号） |
| **支付结果与实际不符** | 支付结果页必须可查询真实状态（8.4） |
| **账号无法注销** | App 内提供注销入口（3.3 / Route.accountCancellation） |

**审核材料准备：**
- App截图（所有尺寸，含深色模式）
- App描述和关键词
- 测试账号（覆盖：新用户/老用户/有订单/会员/异常态）
- 审核备注（特殊功能说明、灰度开关、需要人工审核的活动页）
- 隐私政策URL、用户协议URL
- **中国区额外要求：** App 备案（工信部 ICP 备案，未备案无法上架/更新）、
  软件著作权（视分发渠道）、支付业务资质（若接入自有支付账户体系需牌照说明）、
  算法推荐关闭入口（《互联网信息服务算法推荐管理规定》，个性化推荐需可一键关闭，在 10.6 隐私设置中提供）

### 16.4 Universal Links（【修订-34】补全 entitlement 与前后端对齐）

**功能点：**
- 支持Universal Links跳转
- 分享链接可直接打开App
- 网页到App的无缝跳转
- 支付回跳（微信 OpenSDK 强制要求 UL 回调，见 5.4）

**配置：**

1. Xcode Target → Signing & Capabilities → **Associated Domains** 添加：
   `applinks:easyshopping.com`（⚠ 需与 `BrandConfig.universalHost` 一致）
2. `apple-app-site-association` 文件托管在：
   `https://easyshopping.com/.well-known/apple-app-site-association`

```json
{
    "applinks": {
        "details": [{
            "appIDs": ["TEAM_ID.com.easyshopping"],
            "components": [{
                "/": "/product/*",
                "comment": "商品详情页"
            }, {
                "/": "/activity/*",
                "comment": "活动页"
            }, {
                "/": "/coupon/*",
                "comment": "优惠券"
            }, {
                "comment": "排除订单等敏感路径，避免被外部站点误跳",
                "/": "/order/*",
                "NOT": [{}]
            }]
        }]
    }
}
```

**部署与验收清单（缺一不可，AASA 是最常见的"跳转不生效"根因）：**

| 检查项 | 要求 |
|--------|------|
| HTTPS | 有效证书，无自签 |
| HTTP 状态 | 200，**无重定向**（301/302 会失败） |
| Content-Type | `application/json`（`application/json;charset=utf-8` 亦可） |
| 无登录校验 | 文件必须公开可访问 |
| 生效时间 | iOS 会缓存 AASA，首次安装后可能延迟数小时；调试可用 `apple-app-site-association?xx` 加随机 query 绕过缓存 |
| appID | `TeamID.bundleId`，与签名证书一致 |
| **与后端对齐** | ⚠ `ios-backend.md` 8.2.1 的 AASA 使用占位符 `{TEAM_ID}`，appIDs 为 `{TEAM_ID}.com.easyshopping` **与** `{TEAM_ID}.com.easyshopping.Clip`（另含 `webcredentials` 与 `appclips` 段，后端 1.3.1 预留 `app_clip_bundle_id` 字段）。**是否提供 App Clip 双端同标为「阶段一待决策项」**：提供则前端需同步开发 Clip Target；不提供则后端 AASA 的 `appclips` 段与 `.Clip` 条目一并删除，避免苹果校验与实际能力不符 |
| 交互要求 | iOS 15+ 用户**不点"打开"**也能直接进 App，但仍需验证从 Safari、微信内置浏览器、短信、备忘录四个入口 |
| URL Scheme 兜底 | 非 AASA 覆盖场景（微信对话、二维码）用 `easyshopping://`，`CFBundleURLTypes` 必须注册；Scheme 与 UL 冲突时 **UL 优先**，用 `webpageURL` 判断 |

> 验收命令：`curl -I https://easyshopping.com/.well-known/apple-app-site-association`
> 以及 Xcode `Team ID` 与 AASA `appIDs` 逐字比对。

### 16.5 Widget扩展

**功能点：**
- 首页Widget（展示优惠券、秒杀商品）
- 物流Widget（展示最新物流状态）
- 中尺寸/大尺寸支持

**技术要点：**

```swift
import WidgetKit

struct ProductWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "com.easyshopping.widget", provider: Provider()) { entry in
            ProductWidgetView(entry: entry)
        }
        .configurationDisplayName("好货推荐")
        .description("快速查看精选商品")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

// App Group共享数据
// 注意：App Group 中的 UserDefaults 只能存轻量配置（Widget 数据），不得存 Token/用户敏感数据
let sharedDefaults = UserDefaults(suiteName: "group.com.easyshopping.widget")
```

### 16.6 安全加固（【修订-32】原计划缺失，与后端 12.2/12.5 对齐）

| 项 | 要求 |
|----|------|
| 传输 | 全站 HTTPS + ATS 默认策略（不加 `NSAllowsArbitraryLoads`）；**SSL Pinning**（证书/公钥双备份，证书轮换预案） |
| 请求签名 | Header 携带 `X-Timestamp` + `X-Nonce` + `X-Sign = HMAC-SHA256(method+path+body+ts+nonce, deviceSecret)`；后端校验时间窗 ±5min、nonce 去重（防重放） |
| 敏感数据存储 | Token/refresh_token 仅 Keychain，`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`；**禁止**写 UserDefaults / plist / 日志 |
| 日志脱敏 | 手机号、地址、Token、支付信息脱敏（对齐后端 12.5）；Release 包关闭请求体日志 |
| 越狱检测 | 检测到越狱 → 不阻断使用，但**禁用生物识别快速登录/自动填充支付**，并上报风控；越狱判定逻辑必须放在 Swift 层（避免被 hook 静默绕过） |
| 反调试/混淆 | `ptrace(PTRACE_DENY_ATTACH)`、符号剥离（Strip on Copy）、关键字符串加密存储 |
| 代码与依赖 | 关闭 `DYLD_INSERT_LIBRARIES` 注入、校验 `MobileProvision` 有效性；依赖固定版本号，避免供应链投毒 |
| WebView | 白名单域名 + 关闭文件访问（见 13.4） |
| 越权防护 | 前端不做任何"权限判断替代"（隐藏按钮 ≠ 鉴权），一切以接口返回为准 |
| 支付 | 支付相关接口二次校验（8.4），不在客户端保存银行卡号/CVV |

---

## 十七、开发里程碑

**【修订-29】排期口径说明：**
- 本表为**前端独立开发周期 19 周**；`ios-backend.md` 13.1 为 **36 周 / M1-M6**，
  两侧口径与起止时间**原不一致**，联调依赖会被低估。
- 对齐方式：前端**整体延后 8 周启动核心功能**，或按下方"接口就绪"门禁推进——
  阶段二/三的每个模块必须在对应接口**已联调通过**后才进入开发，否则先用 Mock 契约（由附录A 生成）。
- 每阶段结束必须产出：可演示 build + 接口联调记录 + 性能基线报告 + 用例通过率。

**【v1.3 实施手册三要素落点】**（有则就地强化、不重复）：
- **阶段任务分解**：五个阶段按周标注（W1-W19），逐条 checkbox 任务见各阶段；
- **验收标准**：每阶段新增「验收标准」（功能 + 性能 + 审核三类可验证条件）；
- **依赖与前置**：每阶段新增「依赖与前置」（后端接口就绪状态 + Apple 证书/账号/资质），外部资质汇总见 17.1 提审日历与 `ios-backend.md` 13.6.2；
- **平台容量口径（v1.3 同步）**：服务端目标已由「1 亿在线」降级为**千万 DAU**——网关入口 60 万 QPS / 压测目标 75 万 QPS / 订单峰值 5 万笔/s，**前端无需为亿级大促做专项客户端优化**；可用性 P0 99.9%（P1 同城双活后 99.95%），性能与压测口径以 `plans/00-架构总纲.md` 为准，后端 SLA 见 `ios-backend.md` 11.5。

### 阶段一：基础架构（4周，W1-W4）
- [ ] 项目初始化与CI/CD搭建（Fastlane + GitHub Actions，含签名与 TestFlight 自动分发）
- [ ] 网络层封装（含请求签名、错误码统一、SSL Pinning，见 16.6）
- [ ] 设计系统搭建
- [ ] 路由与DI框架（含 DeepLinkParser 与 myapp:// 映射表，见 1.4）
- [ ] 登录模块（含 Token 单飞刷新、设备绑定、Apple 登录，见 3.x）
- [ ] 埋点/APM 骨架（14.5，**必须早于业务开发**，否则历史数据缺失）
- [ ] 接口契约评审：**按附录A 逐条与后端确认**，输出 OpenAPI/Swagger 契约并冻结
- **验收标准**：① 功能——CI 首次构建成功并自动分发 TestFlight；登录（游客可浏览 + Apple 登录 + Token 单飞刷新）主链路可用；请求签名/防重放用例通过（**±5 分钟窗口 / Nonce TTL 10 分钟**，与 `ios-backend.md` 12.6 一致）；埋点事件在看板可查询。② 性能——冷启动 P90 达标基线建立（14.1），CI 启动耗时门禁生效。③ 审核——无（本阶段不出提审包）。
- **依赖与前置**：**Apple Developer Program 账号 + 开发证书/描述文件/APNs 推送证书（W1 必须到位，后端 13.6.2 (a)）**；后端契约 W2 冻结（G0 门禁）；Figma 设计稿与设计系统资源；CI 所需签名密钥入库。

### 阶段二：核心功能（6周，W5-W10）
- [ ] 首页模块（依赖 `GET /api/v1/bff/ios/home`）
- [ ] 商品列表与详情（依赖 `GET /api/v1/products/{id}`，评价区按降级方案）
- [ ] 搜索模块
- [ ] 购物车模块
- [ ] 下单结算流程（依赖 `POST /api/v1/orders` + 幂等键）
- [ ] 支付（Apple Pay 打通；微信/支付宝按后端 API 就绪情况开开关）
- **验收标准**：① 功能——「首页→商详→加购→结算→支付→订单可见」端到端主链路通过；接口契约测试全绿（附录 A A.1/A.2 项零偏差）；支付回调丢失场景按 8.4 轮询兜底可恢复。② 性能——列表滚动帧率达标（14.2 分档）、首屏资源与图片降采样生效（5.1/14.3）。③ 审核——无，但支付 SDK 隐私清单需提前核查（16.2）。
- **依赖与前置**：首页 BFF 后端联调就绪前（**后端 W12**）用 Mock；下单/支付接口以**后端 W14 提测（G2）、W16 联调（G3）**为放行门禁——未过 G2 不进入下单开发；**Apple Merchant ID + Payment Processing 证书 + 三方支付商户资质（W15 前）**；微信 OpenSDK / 支付宝 SDK（CocoaPods）与回跳 UL 配置。（本阶段引用的 W12/W14/W15/W16 均为**后端 36 周日历**，对齐方式见 17.1 注）

### 阶段三：扩展功能（4周，W11-W14）
- [ ] 订单管理（含确认收货/取消订单接口的联调）
- [ ] 评价/晒单（9.6）
- [ ] 个人中心
- [ ] 消息通知（含设备绑定与推送深链验证）
- [ ] 客服系统（WebSocket 鉴权/心跳/重连）
- [ ] 离线能力与 JSBridge（13.3、13.4）
- [ ] 版本检查与灰度（2.5）
- **验收标准**：① 功能——**订单 8 值状态机 Tab 映射回归用例全绿**（9.1，查询参数仅接受 0-7）；推送三场景深链跳转正确（11.2）；WebSocket 鉴权/25s 心跳/指数退避重连/按 messageId 去重用例通过（12.1）。② 性能——弱网重试与离线队列重放不产生重复下单（13.3 幂等键）。③ 审核——强登录墙自查通过（2.1 游客可浏览）。
- **依赖与前置**：后端推送（**后端 W22**）、客服/分享/更新（**后端 W26，G4 门禁**）就绪；**⚠ 缺口接口（收藏/评价/工单回复/购物车同步/IAP 验签）未按期补齐的一律隐藏入口**（第二十章）；AASA/UL 域名托管与 entitlement 生效（16.4）。（W22/W26 为**后端 36 周日历**，对齐方式见 17.1 注）

### 阶段四：优化与合规（3周，W15-W17）
- [ ] 性能优化（按 14.1/14.2/14.3 分档指标验收）
- [ ] Dark Mode 适配
- [ ] Dynamic Type + 无障碍（15.4、15.5）
- [ ] 多语言词表拆分（15.6）
- [ ] App Store 合规审查（16.1~16.6 checklist 逐项签字）
- [ ] 隐私清单/隐私标签/必选原因 API 核查
- [ ] Widget 开发
- [ ] 安全自查（越狱、抓包、重放、越权）
- [ ] 测试与修复（**左移到阶段一**：单测随代码提交，UI 自测每周跑）
- **验收标准**：① 功能——16.1~16.6 checklist **逐项签字**；登录/加购/下单三条 XCUITest 主链路全绿；单测覆盖率 ≥ 60%、核心链路 ≥ 80%（第十八章）。② 性能——14.1/14.2/14.3 **分档指标**（含 SE2 低端机）全部达标，CI 启动耗时门禁不回退。③ 审核——**隐私清单 PrivacyInfo.xcprivacy = 隐私标签 = 实际采集**三者一致；必选原因 API 理由码齐全；中国区备案材料齐备（16.3）。
- **依赖与前置**：Dark Mode/Dynamic Type 设计稿；后端合规接口（**后端 W31-W32，G6 门禁**）就绪；Widget 资源与展示时机；**App 备案与资质（上线前必须，后端 13.6.2 (a)）**。

### 阶段五：灰度发布（2周，W18-W19）
- [ ] TestFlight内测（内部 + 外部 100 人）
- [ ] 小流量灰度（按 2.5 分桶 5% → 20% → 50%，观察崩溃率/启动耗时/下单转化）
- [ ] 审核材料与备注准备（16.3）
- [ ] 全量发布 + 上线后 7 天值守

> 阶段五的灰度门槛（任一不达标即回滚）：崩溃率 < 0.1%、冷启动 P90 达标（14.1）、
> 下单成功率环比下跌 < 1%、ANR/卡顿无明显劣化。

- **验收标准**：① 功能——TestFlight 内测 ≥ 7 天无阻塞级缺陷、核心漏斗无回归。② 性能——灰度期间崩溃率 < 0.1%、冷启动 P90 达标、下单成功率环比跌幅 < 1%。③ 审核——**App Store 审核通过并完成首次发版**，全量后 7 天值守无 P0 故障。
- **依赖与前置**：后端 `ios-backend.md` **13.5 上线 Checklist 全项通过（后端 G8 提审门禁）**；IAP 沙箱验收、AASA 生产可达、隐私问卷与实际采集一致；灰度分桶配置与监控看板（2.5、14.5）。

#### 17.1 周度交付与审核提审节点日历（【v1.3】新增）

| 节点 | 周次 | 可交付 | 验收标准（功能/性能/审核） | 依赖与前置 |
|------|------|--------|---------------------------|------------|
| G0 契约冻结 | W2 | OpenAPI 契约 + Mock Server | 附录 A 逐条核销、双侧签字 | 后端 G0 门禁、`plans/00-架构总纲.md` 口径冻结 |
| G1 基础架构完成 | W4 | 可演示 build（登录+网络+APM） | 签名/防重放用例通过；TestFlight 自动分发成功 | Apple 账号/证书 W1 到位 |
| G2 交易链路可联调 | W8-W10 | 首页/商详/搜索/购物车/下单/支付 | 主链路 E2E 通过；**后端 G2（W14 提测）/G3（W16 联调）为放行门禁**，未过则继续 Mock | Merchant ID、支付资质、后端联调窗口 |
| G3 扩展功能完成 | W14 | 订单/消息/客服/离线/灰度能力 | 8 值状态机回归、推送深链、WebSocket 用例全绿 | 后端 G4（W26）支撑服务就绪 |
| G4 合规与性能达标 | W17 | 合规签字包 + 性能基线报告 | 16.x checklist 签字；14.x 分档指标达标；隐私三者一致 | 后端 G6（W32）、备案材料 |
| **G5 提审材料齐备** | **W17 末** | 审核说明/演示视频/隐私问卷/强更说明 | 16.3 逐项自检通过 | IAP 沙箱可用、AASA 生产可达、后端 13.5 Checklist 基本全项 |
| **G6 提交 App Store 审核** | **W18 初** | 提审包上传 App Store Connect | TestFlight 内测 ≥ 7 天无阻塞缺陷；灰度门槛达标 | **预留 1-2 周审核时长**；账号资质（付费协议、备案）齐备；审核被拒走修改-重提前置 3 天缓冲 |
| G7 审核通过与全量 | W18-W19 | 分批全量 5%→20%→50%→100% | 崩溃率 < 0.1%、转化无劣化；全量后 7 天值守 | 后端值班与回滚预案、监控看板 |

> **周次口径**：表中 W1-W19 为**前端 19 周日历**；括注的「后端 G2/G3/G4/G6/G7」为 `ios-backend.md` 13.6 的门禁节点（后端 36 周日历），两份日历按"接口就绪"门禁对齐，前端未到联调窗口时一律用 Mock 契约推进。
>
> **提审硬前置（缺一不可）**：① 后端 13.5 上线 Checklist 全项（含 IAP 四接口、AASA、支付回调验签、隐私合规）；② Apple 侧资质——付费协议/Merchant ID/APNs 证书/AASA 域名/App 备案；③ 客户端 16.1~16.6 签字与隐私三者一致；④ App Clip 是否提供的书面决策（阶段一内，见 16.4 与后端 8.2.1）。

---

## 十八、技术栈清单

| 类别 | 技术/工具 | 用途 |
|------|-----------|------|
| 开发语言 | Swift 5.9+ | 主开发语言 |
| UI框架 | SwiftUI（基线 iOS 16）+ UIKit 寄宿 | 声明式UI，复杂组件混编（见1.1） |
| 并发 | Swift Concurrency (async/await) | 异步处理 |
| 响应式 | Combine | 数据流 |
| 网络 | URLSession + Alamofire | 网络请求（Alamofire 便于拦截器/重试） |
| 图片 | Kingfisher | 图片加载/缓存（**统一图片库，禁用 AsyncImage 混用**，见5.1） |
| JSON | Codable | 数据解析 |
| 本地存储 | CoreData + UserDefaults + Keychain | 数据持久化 |
| 安全 | Keychain Services + SSL Pinning | 敏感数据存储与传输校验 |
| 崩溃/APM | Crashlytics + MetricKit + os_signpost | 崩溃、启动、帧率、卡顿（见14.5） |
| 分析 | 自研 Tracker（或 Firebase Analytics） | 用户行为分析 |
| 推送 | APNs + 第三方推送 | 消息推送 |
| 支付 | StoreKit 2 / 微信OpenSDK / 支付宝SDK | 支付能力 |
| 分享 | 微信OpenSDK（UL回调）/ 系统分享 | 分享 |
| 地图 | MapKit（或高德/百度，需合规申报） | 地图选点 |
| 热更新 | 远程配置（`GET /api/v1/config`） | 配置热更新（见2.4） |
| 版本管理 | `GET /api/v1/version/check` + 灰度分桶 | 更新与放量（见2.5） |
| CI/CD | Fastlane + GitHub Actions | 自动化构建、TestFlight 分发 |
| 包管理 | **Swift Package Manager + CocoaPods（双轨）** | 【修订-33】SPM 为主；**微信OpenSDK、支付宝SDK 仅提供 CocoaPods/手动集成**，必须保留 Podfile，否则无法引入支付与分享能力 |
| 单元测试 | XCTest + Swift Testing | 单元测试（覆盖率 ≥ 60% 核心链路 80%） |
| UI测试 | XCUITest | UI自动化（登录、加购、下单三条主链路） |
| 契约测试 | OpenAPI 生成的 Mock Server | 前后端并行开发（附录A） |
| 性能分析 | Instruments（Leaks/Allocations/Time Profiler/Core Animation） | 性能瓶颈分析 |
| 设计工具 | Figma | 设计稿 |

---

## 十九、团队配置建议

**【修订-30】原表分隔行 `|------|------||` 缺一列，Markdown 渲染会断表，已修复。**

| 角色 | 人数 | 职责 |
|------|------|------|
| 技术负责人 | 1 | 架构设计、技术选型、代码审查、接口契约评审 |
| iOS开发工程师 | 4-6 | 各模块开发（按模块分 2 个小组：交易链路 / 内容与增长） |
| UI/UX设计师 | 1-2 | 视觉设计、交互设计、设计系统维护 |
| 产品经理 | 1 | 需求管理、项目管理、验收 |
| 测试工程师 | 1-2 | 功能测试、性能测试、**兼容机型矩阵（最低版本+iPad）** |
| **QA自动化/AQA** | 1 | UI 自动化链路、CI 门禁、灰度质量看板 |
| **数据分析师** | 1（可兼职） | 埋点方案评审、漏斗与灰度数据（14.5） |
| 后端工程师 | 2-3 | API开发、数据支持、**附录A 中 ⚠ 缺口接口的补齐** |
| 运维工程师 | 1 | CI/CD、监控、发布 |

**工作量校验（原计划未给估算，容易低估）：**
- 按 4~6 名 iOS、19 周计算，可用人周约 76~114；
- 粗估本计划一期范围（不含直播/店铺/会员/社交/足迹/对比）约 90~110 人周（含联调、测试、返工 20%）；
- **结论：一期范围必须按上表"（二期）"标注裁剪，否则排期必然滑期。**

---

## 二十、风险与对策

**【修订-35】在原有 6 项基础上补充 9 项与本版审查直接相关的风险。**

| 风险 | 影响 | 对策 |
|------|------|------|
| App Store审核延迟 | 影响发布节奏 | 提前准备审核材料，预留1-2周审核时间 |
| 热修复方案受限 | 紧急Bug无法快速修复 | 远程配置+H5兜底+快速发版通道 |
| 第三方SDK审核风险 | 可能导致拒审 | 选择审核友好型SDK，避免使用JSPatch等；SDK隐私清单核查（16.2） |
| iOS版本适配成本 | 开发效率降低 | 最低支持 iOS 16（修订-2），`#available` 必须有可测降级路径 |
| 性能不达标 | 用户体验差 | 性能测试前置，设置明确分档指标（14.x），CI 启动耗时门禁 |
| 包体积过大 | 影响下载转化率 | 资源压缩、按需加载、移除无用依赖；监控 .ipa 体积（无 CELLULAR 优化时 >200MB 影响转化） |
| **前后端接口契约不一致（本版已发现 30+ 处）** | 联调阶段大面积返工、里程碑滑期 | 以**附录A 契约对照表**为唯一事实源，阶段一完成契约评审并冻结；缺口接口（⚠ 标记）由后端在对应阶段前补齐 |
| **后端接口缺口（收藏/评价/微信支付宝/工单回复/购物车同步/IAP验签等）** | 相关功能无法上线 | 需求评审时逐项确认"补齐 or 隐藏入口"，未补齐的一律**隐藏入口**而非留死按钮 |
| **支付回调丢失/状态不同步** | 用户扣款看不到订单，重大投诉 | 8.4 指数退避轮询 + 服务端对账 + 订单详情兜底 |
| **强登录墙被拒审 / 转化下降** | 拒审或漏斗流失 | 2.1 游客可浏览，关键动作前置登录 |
| **中国区备案/合规** | 无法上架或被下架 | App 备案、算法推荐关闭入口、隐私政策与权限文案（16.3） |
| **直播/UGC 内容安全** | 内容违规下架 | 二期模块上线前接入机审与举报链路（9.6 评价同理） |
| AASA/Universal Links 不生效 | 分享链接无法直达，影响裂变 | 16.4 部署验收清单，上线前 curl 校验 |
| 微信/支付宝回跳失败 | 支付后回不到 App | UL 与 SDK 注册一致性校验（5.4、8.4） |
| 低端机性能崩溃（OOM/卡顿） | 差评与流失 | 14.3 分档指标 + SE2 纳入回归机池 |

---

## 二十一、一期/二期范围界定（【修订-28】原目录与正文不一致）

1.2 目录中列出了 `ProductCompare / Live / Shop / Member / Social / Footprint` 等 Feature，
但正文第二~十六章**没有任何对应章节**，属于"目录承诺了、计划没排"的内部矛盾。处理如下：

| 模块 | 归属 | 本期处理 | 前置条件 |
|------|------|----------|----------|
| 启动/登录/首页/商品/搜索/购物车/结算/订单/个人中心/消息/客服/设置 | **一期** | 全量实现 | — |
| 评价/晒单（9.6）、支付结果（8.4）、版本灰度（2.5）、埋点APM（14.5）、JSBridge（13.4）、无障碍/多语言（15.5/15.6）、安全加固（16.6） | **一期（本次补入）** | 全量实现 | 后端补齐 ⚠ 接口 |
| 商品对比 ProductCompare | 二期 | **从目录中移除或标注"二期"**，不进入一期排期 | 接口与交互评审 |
| 直播 Live | 二期 | 目录保留、正文标注二期 | 播放器选型（AVPlayer/第三方）、横屏与清晰度、连麦方案、内容合规 |
| 店铺 Shop | 二期 | 同上 | 店铺主页接口、店铺关注 |
| 会员 Member | 二期 | 同上，**且必须先有 16.1 的 IAP 服务端接口** | `/iap/*` 四个接口就绪 |
| 社交 Social（关注/动态） | 二期 | 同上 | UGC 内容安全链路 |
| 足迹 Footprint | 二期 | 同上 | 浏览记录接口与隐私声明（浏览行为属个人数据） |
| App Clip | 待定 | **必须在阶段一决策**：后端 AASA 已预留 clip 条目与 `app_clip_bundle_id` | 与后端 8.2.1 同步，二选一 |
| iPad 适配 | 待定 | 阶段一决策（支持 / iPhone-only），见 15.1 | 设计稿与测试矩阵成本 |

**要求：** 目录中的每个 Feature 必须在本表有归属；未进入一期的模块，
在 1.2 目录注释中显式标注 `// 二期`，避免评审时被当成已排期范围。

---

## 附录A：接口契约对照总表（与 `ios-backend.md` 对齐，【修订-4】唯一事实源）

> 使用方式：阶段一开始，前后端**逐行确认**；`⚠ 待补` 项由后端在对应阶段前给出结论（补齐 or 前端隐藏入口）。

### A.1 已一致（前端路径保留）

`POST /api/v1/auth/token/refresh`、`POST /api/v1/orders/preview`、`POST /api/v1/orders`、
`GET /api/v1/orders`、`GET /api/v1/orders/{order_id}`、`GET /api/v1/orders/{order_id}/logistics`、
`GET /api/v1/products`、`GET /api/v1/products/{product_id}`、`GET /api/v1/search`、
`GET /api/v1/search/suggestions`、`GET /api/v1/search/hot`、`GET /api/v1/cart`、
`GET /api/v1/user/profile`、`PUT /api/v1/user/profile`、`GET /api/v1/messages`、
`PUT /api/v1/messages/{message_id}/read`、`DELETE /api/v1/messages/{message_id}`、
`POST /api/v1/share`

### A.2 前端路径写错（本版已修正）

| 章节 | 错误路径 | 正确路径 |
|------|----------|----------|
| 3.1 | `/sms/send` | `POST /auth/send-code` |
| 3.1 | `/auth/login/phone` | `POST /auth/login/mobile` |
| 3.1 | `/auth/login/{provider}` | `POST /auth/login/apple` |
| 3.1 | `/auth/login/biometric` | `POST /auth/biometric/challenge` + `/verify` |
| 4.2 | `/home/*` 五连发 | `GET /bff/ios/home`（+ `/recommendations` 翻页） |
| 5.2 | `/products/{id}/recommend` | `GET /recommendations?scene=accessory` |
| 7.3 | `/cart/items`（POST/PUT/DELETE） | `POST /cart`、`PUT /cart/{cart_item_id}`、`DELETE /cart/{cart_item_id}` |
| 8.3 | `/payments/{channel}/create` | `POST /payments/apple-pay/session` + `/complete` |
| 8.3 | `/coupons/available`、`/addresses` | `GET /user/coupons`、`GET /user/addresses` |
| 9.5 | `/refunds`（申请） | `POST /payments/refund`、`POST /payments/apple-pay/refund` |
| 10.x | `/addresses`（增删改） | `POST/PUT/DELETE /user/addresses[/{address_id}]` |
| 10.x | `/coupons`、`/points/balance`、`/points/transactions` | `GET /user/coupons`、`GET /user/points`、`GET /user/points/history` |
| 11.2 | `/user/notification-settings` | `GET/PUT /user/push-settings` |
| 12.2 | `/tickets*` | `POST /cs/tickets`、`POST /cs/tickets/{ticket_no}/rate` |

### A.3 前端调用了后端不存在的接口（⚠ 必须闭环）

| 接口 | 所在章节 | 结论（二选一，阶段一冻结） |
|------|----------|---------------------------|
| `POST /auth/login/password` | 3.1 | 后端补 or 前端隐藏"密码登录" |
| 微信登录 | 3.1 | 后端补 or 前端隐藏（**注意 Sign in with Apple 联动**） |
| `GET /products/{id}/reviews`、`POST .../reviews` | 5.2 / 9.6 | 后端补（已有表结构），否则评价区降级 |
| `GET/POST /user/favorites`、`POST /favorites` | 5.2 / 10.3 | 后端补 or 前端隐藏收藏入口 |
| `GET /search/filters`、`GET /search/discovery` | 6.x | 后端补 or 用 search facet / `GET /config?scene=search` 降级 |
| `POST /cart/sync`（登录合并购物车） | 7.3 | 后端补 or 前端逐条合并 |
| `POST /orders/{id}/confirm` | 9.4 | ✅ 已闭环：后端 4.2.5 已提供 `POST /orders/{order_id}/confirm` |
| `POST /orders/{id}/cancel` | 9.x | ✅ 已闭环：后端 4.2.5 已提供 `POST /orders/{order_id}/cancel`，响应 `status=6`（8 值编码） |
| `GET /refunds/{refund_no}` | 9.5 | 后端补 or 售后进度以订单详情承载 |
| `POST /payments/wechat/create`、`/alipay/create` | 8.3 | 后端补 or 本期仅上线 Apple Pay |
| 支付状态查询 | 8.4 | 复用 `GET /orders/{id}`，需确认返回 `payment_status` |
| 工单列表/详情/回复 | 12.2 | 后端补 |
| `/iap/verify`、`/iap/transactions`、`/iap/entitlements`、App Store Server Notifications | 16.1 | **后端必须补**，否则会员/积分不得上线 |
| `POST /device/bind`、`PUT /device/push-token`、`DELETE /device/{id}` | 3.3 / 11.2 | 后端已定义，**前端需接入** |
| `GET /version/check`、`GET /config` | 2.4 / 2.5 | 后端已定义，**前端需接入** |
| `GET/PUT /privacy/consent-settings`、`GET /privacy/policy`、`GET /privacy/data-export`、`DELETE /privacy/account-data` | 2.3 / 3.3 / 16.2 | 后端已定义，**前端需接入** |
| `POST /user/avatar`、`POST /user/addresses/parse`、`POST /user/coupons/{id}/claim`、`POST /user/points/check-in` | 10.x | 后端已定义，**前端需接入** |

### A.4 跨文档全局不一致（【修订-5】需一次性对齐）

| 项 | ios-frontend.md | ios-backend.md | 处理 |
|----|-----------------|----------------|------|
| BundleID | `com.easyshopping` | `com.example.shop` | 阶段一确定唯一值，双侧同步 |
| URL Scheme | `easyshopping://` | `myapp://`（deep_link） | 统一 scheme，或前端 Parser 同时兼容两种前缀（1.4 已写明） |
| 域名 | `easyshopping.com` | `example.com`、`map.example.com` | 统一 |
| AASA appID | `TEAM_ID.com.easyshopping` | `{TEAM_ID}.com.easyshopping` + `{TEAM_ID}.com.easyshopping.Clip`（含 `appclips` 段） | 域名与 TeamID 口径已一致；**App Clip 是否提供为双端「阶段一待决策项」**（不提供则后端删 `appclips` 段与 `.Clip` 条目） |

> 【跨文件同步 S1】`ios-backend.md` 1.2.2 约定 5 已将品牌标识统一为唯一真值：`com.easyshopping` / `easyshopping://` / `easyshopping.com` / `merchant.com.easyshopping` / `{TEAM_ID}.com.easyshopping`。上表「ios-backend.md」列除 AASA 行外均为修订前快照，以该约定为准，无需再做二选一对齐。
| Apple Pay merchantId | `merchant.com.easyshopping` | `merchant.com.example.shop` | 统一 |
| 最低系统版本 | 原 iOS 15 → **iOS 16**（本版） | `min_os_version` 默认 `14.0` | 后端改为 `16.0` |
| 首页接口形态 | 原 5 个细分接口 → **BFF 聚合**（本版） | `GET /bff/ios/home` | 前端已改，后端保证聚合字段稳定 |

---

## 审查记录

### v1.0 → v1.1 修改记录

- [1] 文档版本号头尾不一致（头部 v1.1、尾部 v1.0）→ 文首"目标平台/文档版本"与文末版本信息统一为 v1.1（iOS 16.0+），并指向本《审查记录》
- [2] 最低支持 iOS 15 与实际使用的 `NavigationStack`/`NavigationPath`/`presentationDetents`/`ViewThatFits`（均为 iOS 16+）矛盾 → 1.1 新增"【修订-2】最低系统版本决策"（提升至 iOS 16.0，并给出保留 iOS 15 的降级成本）；15.2 版本表同步改写
- [3] 混合策略（列表/SKU/支付用 UIKit）与正文实现（LazyVGrid、SwiftUI SKU）自相矛盾 → 1.1 混合策略改为"默认 SwiftUI + 按数据下沉"判定表，并写明下沉触发阈值；SwiftUI/UIKit 对比表结论同步修正
- [4] 数据依赖与 `ios-backend.md` 大面积不一致（约 30 处路径错误或后端根本没有该接口）→ 3.1、4.2、5.1、5.2、6.3、7.3、8.3、9.5、10.x、11.2、12.2 全部改为"原写法/后端实际/处理"对照表；新增**附录A 接口契约对照总表**（A.1 一致 / A.2 已改 / A.3 待补 / A.4 全局不一致）
- [5] 前后端品牌标识不一致（BundleID、域名、URL Scheme、AASA、merchantId，`myapp://` vs `easyshopping://`）→ 1.4 新增 `BrandConfig` 与三种前缀统一解析；附录 A.4 列出一次性对齐清单
- [6] `Route` 覆盖不全、`checkout(orderPreview:)` 携带大对象、单一全局 `NavigationPath` 破坏 Tab 状态 → 1.4 补全 Route（PayResult/Review/OrderCancel/TicketList/Scan 等）并写明关联值约束；路由实现改为按 Tab 独立 `NavigationPath`
- [7] 启动即强制登录（`needsLogin → PhoneLoginView`）有拒审与转化风险 → 2.1 `RootView` 改为游客可浏览 + 关键动作前置登录；补生命周期三入口（AppDelegate / SceneDelegate / @main）的桥接说明
- [8] 第三方登录未说明 Sign in with Apple 强制规则；密码登录/微信登录后端只有概览没有 API → 3.1 新增规则说明，并给出"后端补 or 前端隐藏入口"的处理策略
- [9] Info.plist 权限键缺失（`NSFaceIDUsageDescription` 缺失会直接崩溃）、ATT 与隐私同意时序缺失 → 2.3 新增 plist 键表 + 首启同意 → SDK 初始化 → ATT 的顺序约束，接入 `/privacy/consent-settings`
- [10] Token 刷新无并发去重；登出未调用 revoke、未解绑设备、未清多账号缓存 → 3.2 改为单飞（single-flight）刷新，logout 补 revoke / unbind / 缓存清理五步
- [11] 生物识别仅本地校验（可重放/伪造）、支付场景用错 policy、缺服务端 challenge/verify → 新增 3.4，对齐后端 2.4.2 的 register / challenge / verify 三段式
- [12] `LazyVStack(pinnedViews:)` 未配合 `Section`，吸顶搜索栏实际不会生效 → 4.1 代码改为 `Section { } header: { }`
- [13] 图片库混用（技术栈写 Kingfisher、实现用 AsyncImage），与"渐进式加载"要求冲突 → 5.1 改为 `KFImage`（占位 / 渐进解码 / 取消 / 降采样），4.2、14.3 注明统一口径
- [14] 秒杀倒计时使用本地时间，改系统时间即可作弊 → 4.1 `SeckillSection` 改为 `serverNow` 时间偏移校准
- [15] 购物车接口路径错误；`cart/sync`、`validateStock` 后端不存在；100 件上限为前端硬编码 → 7.2/7.3 改为 `POST /cart`、`PUT/DELETE /cart/{cart_item_id}`，库存与价格校验归并到 `orders/preview`，上限改为服务端下发，并写明同步冲突规则
- [16] `CartBottomBar` 对 `Decimal` 使用 `specifier:` 插值，无法编译 → 7.1 改为 `Decimal.formatted(.currency(code:))`，并在 8.1 标注 `vm.total` 同样处理
- [17] `CacheManager.get` 的 switch 存在 `default: break` 无返回值（编译错误），且未实现 networkFirst/cacheFirst → 13.1 补全六个分支实现并加内存成本上限
- [18] Apple Pay 示例 4 处硬伤（`let` 属性在方法内赋值、缺 merchantSession、delegate 未完成授权、无 didFinish）→ 8.3 重写为可编译并对接后端 `apple-pay/session` + `apple-pay/complete` 的实现
- [19] 支付方式前端列 5 种、后端只实现 1 种；缺支付结果页与回调轮询 → 8.3 新增覆盖矩阵与防重复下单；新增 **8.4 支付结果页与回调轮询**
- [20] IAP 只做客户端 `transaction.finish()`，缺服务端验签发货；"优惠券必须 IAP"表述有误；缺恢复购买与订阅细则 → 16.1 重写规则表 + StoreKit 2 补 `appAccountToken`/`Transaction.updates`/`restorePurchases`，并列出 4 个后端必补接口
- [21] `POST /orders/{id}/confirm`、`/refunds*` 与后端不符；缺取消订单与评价模块 → 9.5 改为对照表；新增 **9.6 发表评价/晒单**；1.2 目录新增 `Features/Review`、`Features/PayResult`
- [22] 推送设置路径错误；缺设备绑定/token 上报；推送深链 `myapp://` 未对齐；缺冷启动点击处理 → 11.2 改写：三个推送回调场景 + deep_link 映射表 + device 接口
- [23] WebSocket 无鉴权、心跳、重连、消息去重；客服/工单接口路径错误 → 12.1 重写 `ChatWebSocketManager`（token 鉴权、25s 心跳、指数退避、按 messageId 去重、历史走 HTTP）；12.2 改为 `/cs/*` 对照表
- [24] `kUTTypeItem` 已弃用；`INSearchForPhotosIntent` 与商品搜索无关；`search/filters`、`search/discovery` 后端无 → 6.3 改为 `UTType` + App Intents/AppShortcuts，并给出降级方案
- [25] 性能指标口径不统一（2.1 与 14.1 冷启动互相打架；"60fps 满帧"在 ProMotion 上不成立；内存单一阈值）→ 2.1 与 14.1 统一为分档表 + P50/P90 度量口径（含 prewarm 排除）；14.2 按 60/120Hz 分档并区分 UIKit/SwiftUI 手段；14.3 按机型分档
- [26] 设备表单位写成"分辨率"且遗漏新机型；`UIFont.asSwiftUIFont` API 不存在 → 15.1 改为 pt 并补 iPhone 17 系列与 iPad 支持决策；15.4 改为 `Font(uiFont)` 正确写法
- [27] 隐私清单缺 Tracking、数据类型不全、必选原因 API 只写了 UserDefaults → 16.2 补 `NSPrivacyTracking`、数据类型清单、File Timestamp/Boot Time/Disk Space 理由码，新增第三方 SDK 申报表与中国区合规项
- [28] 完整性缺口：埋点 APM、版本检查与灰度、JSBridge、无障碍、多语言；目录列了直播/店铺/会员/社交/足迹/对比但正文无章节 → 新增 2.5、13.4、14.5、15.5、15.6 与**第二十一章 一期/二期范围界定**；1.2 目录补 `Analytics/`、`Bridge/` 并标注二期
- [29] 里程碑 checkbox 格式错误（漏 `-`）；排期与后端（36 周）口径不匹配；测试未左移 → 第十七章重写：接口就绪门禁、埋点前置、测试左移、灰度回滚阈值
- [30] 团队表分隔行少一列导致 Markdown 断表；缺 AQA/数据角色；无工作量估算 → 第十九章修复表格，补角色与人周校验结论
- [31] 离线队列直接 `removeAll` 丢数据、重放无幂等、敏感操作被重放；缓存无多账号隔离 → 13.3 改为白名单重放 + 幂等键 + 单条失败保队；13.1 增加 `cacheKey(userId)` 与内存上限，3.2 登出清理联动
- [32] 安全面缺失：无 SSL Pinning、无请求签名防重放、无日志脱敏、越狱检测无落地策略 → 1.2 目录补 `SSLPinner`/`RequestSigner`；新增 **16.6 安全加固**
- [33] 包管理只写 SPM，但微信 OpenSDK/支付宝 SDK 仅支持 CocoaPods/手动集成 → 第十八章改为 SPM + CocoaPods 双轨，并补 APM、契约测试等技术栈
- [34] Universal Links 缺 Associated Domains entitlement、AASA 部署校验，且与后端 AASA（含 App Clip、example.shop）不一致 → 16.4 重写：entitlement、JSON 配置、部署验收清单、Safari/微信/短信/备忘录四入口验证、与后端二选一同步
- [35] 风险表缺关键风险（契约不一致、接口缺口、支付回调丢失、强登录墙、备案、AASA 失效、回跳失败、低端机 OOM、内容安全）→ 第二十章补充 9 项风险与对策

## 跨文件同步记录

### v1.1 → v1.2

- [S1] `ios-backend.md`（4.2.3 / 4.2.5 / 1.2.2 约定 5 / 8.2.1 审查记录 [3][11][12]）→ **订单状态机 8 值化**：`status` 由 7 值（4=已取消）统一为 8 值（0待付款 1待发货 2待收货 3已完成 4退款中 5已退款 6已取消 7已关闭），与 `admin-backend.md` 4.3 同源 → 修改 9.1（新增「订单状态编码与 Tab 映射」表：Tab 归属、`status_text` 文案、按状态的按钮逻辑、查询参数只接受 0-7、文案与按钮以后端下发为准；并登记「待评价」「售后」两处待契约项）、9.5 数据依赖表（`GET /api/v1/orders` 状态描述改为 8 值）、8.3 防重复下单（`?status=pending` → `?status=0`）、8.4 `PayStatusPoller`（`status != pending_payment` → `status != 0`）
- [S1] `ios-backend.md` 4.2.5（新增 `POST /orders/{order_id}/confirm` 与 `/cancel`）→ **取消/确认收货接口已闭环，且 cancel 响应 `status` 由 4 改为 6** → 修改 9.5 数据依赖表两行（confirm 由「后端无此接口」改为已提供；cancel 由「前后端均需补」改为已提供并标注 `status=6`、已付款返回 `refund_no`）、附录 A.3 对应两行标注 ✅ 已闭环
- [S1] `ios-backend.md` 8.2.1 + 1.2.2 约定 5 → **App Clip / AASA 决策口径对齐**：后端 AASA 实际为 `{TEAM_ID}.com.easyshopping` + `{TEAM_ID}.com.easyshopping.Clip`（含 `webcredentials`/`appclips` 段），品牌标识已统一为 `easyshopping` 系真值 → 修改 16.4 部署清单「与后端对齐」行（原写 `ABCDE12345.com.example.shop` 已过期）、附录 A.4 AASA 行并补充「1.2.2 约定 5 为品牌唯一真值」说明；确认 16.4 与附录 A 均将「是否提供 App Clip」标为**阶段一待决策项**，与后端 8.2.1 注 4 口径一致（提供则开发 Clip Target，不提供则后端删 `appclips` 段）
- [S1] 文首 / 文末文档版本 v1.1 → v1.2（与本节标题一致）

---

## 实施手册修订记录

### v1.2 → v1.3

> 修订背景：项目目标由「1 亿在线」**降级为千万 DAU**（DAU 1000 万 / 峰值在线 200 万 / 网关入口 60 万 QPS / 压测目标 75 万 QPS），`architecture/` 12 份文档删除、收敛为 `plans/00-架构总纲.md`。
> **亿级残留核查结论**：本文件 grep 核查（`architecture/`、`00-conflict-register`、`09-performance-testing`、630、756、10000、RocketMQ、OceanBase、单元化、1 亿、亿级、99.99）**均无残留**；1.2 目录中的 `Core/Architecture/` 为**客户端代码目录名**，与已删除的 `architecture/` 文档无关，保留不改。

- [F1] **文首口径**：项目目标标注**千万 DAU** 容量口径并指向 `plans/00-架构总纲.md`；移除「（淘宝级别）」旧表述；版本号 v1.2 → **v1.3**
- [F2] **十七章排期口径**：新增「v1.3 实施手册三要素落点」导语与**平台容量口径说明**（前端无需为亿级大促做专项优化；可用性 P0 99.9%、P1 同城双活后 99.95%；压测/性能口径指向 `plans/00-架构总纲.md` 与后端 11.5）
- [F3] **五个阶段就地强化**：标题补**周次区间（W1-W19）**，每阶段新增——**验收标准**（功能 + 性能 + 审核三类可验证条件，含阈值：崩溃率 < 0.1%、启动 P90、帧率分档、单测覆盖率 ≥ 60%/核心 80%、隐私三者一致）与**依赖与前置**（后端接口就绪周与 G2/G3/G4 门禁、Apple 账号/证书/Merchant ID/备案、⚠ 缺口接口隐藏入口规则）
- [F4] **新增 17.1 周度交付与审核提审节点日历（G0-G7）**：按周可交付、验收标准、依赖与前置；含 **G5 提审材料齐备（W17 末）**、**G6 提交 App Store 审核（W18 初，预留 1-2 周审核 + 3 天重提缓冲）**、G7 审核通过与分批全量；表下注明前后端双日历（19 周 / 36 周）对齐方式，并列出**提审硬前置四项**（后端 13.5 全项、Apple 资质、客户端 16.x 签字与隐私一致、App Clip 书面决策）
- [F5] **保留不改项复核**：签名窗口 ±5 分钟 / Nonce TTL 10 分钟（16.6 与后端 12.6 一致）、附录 A 接口契约、**订单 8 值状态机 Tab 映射**（9.1）、AASA / IAP 契约（16.1/16.4）、19 周排期与后端 36 周的对齐方式，本轮逐条复核保持原样

---

*文档版本：v1.3（千万 DAU 降级 + 实施手册修订版）*  
*最后更新：2026-10-11*  
*本次审查：35 项问题（v1.1），涉及 1.1/1.2/1.4/1.5/2.1/2.3/2.4/2.5/3.1/3.2/3.3/3.4/4.1/4.2/5.1/5.2/5.4/6.3/7.1/7.2/7.3/8.3/8.4/9.5/9.6/10.x/11.2/12.1/12.2/13.1/13.3/13.4/14.x/15.x/16.x/17/18/19/20/21/附录A；跨文件同步 S1（v1.2）；**v1.3 实施手册修订：5 项（F1-F5）**，涉及 文首 / 17 章各阶段 / 新增 17.1 / 文末版本*
