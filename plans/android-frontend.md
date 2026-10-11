# 安卓客户端前端开发计划

> 项目：大型电商平台（淘宝级别）买家移动端  
> 平台：Android  
> 文档版本：v1.2（v1.0 经架构评审后修订为 v1.1，v1.2 为跨文件同步修订；修订项见文末「审查记录」与「跨文件同步记录」）  
> 更新日期：2026-10-11  
> 接口契约基准：`plans/android-backend.md`（后端 v1.1），本文所有接口路径、响应格式、错误码以后端文档为准

---

## 一、项目架构与工程化

### 1.1 技术选型

| 方案 | 优势 | 劣势 | 适用场景 |
|------|------|------|----------|
| **Kotlin + Jetpack Compose**（推荐） | 原生性能最佳、与 Android 系统深度集成、Google 主推、包体积小、启动快 | 团队需熟悉 Kotlin/Compose、跨端需另建 | 大型电商首选，长期维护成本低 |
| Flutter | 跨端一致性好、热重载开发效率高、UI 表现力强 | 包体积较大、与原生交互需 channel、复杂场景性能略逊 | 中小团队快速迭代 |
| React Native | JS 生态丰富、前端团队可复用 | 性能瓶颈、复杂列表卡顿、升级成本高 | 已有 RN 积累的团队 |

**最终推荐：Kotlin + Jetpack Compose + MVI 架构**

理由：
- 淘宝级别电商对启动速度、列表流畅度、内存控制要求极高，原生方案最优
- Compose 声明式 UI 适合电商复杂多变的页面
- MVI（Model-View-Intent）单向数据流便于状态管理和测试

**MVI 组件约定（统一到每个 feature 模块，避免各页面各写各的）**：

| 组件 | 职责 | 约定 |
|------|------|------|
| `XxxUiState` | 页面状态 | 单一 `data class`，不可变（`val`），包含 Loading/Content/Error/Empty 子态 |
| `XxxIntent` | 用户意图 | `sealed interface`，点击、输入、下拉刷新、加载更多统一建模 |
| `XxxEffect` | 一次性副作用 | 跳转、Toast、震动等，经 `Channel`/`SharedFlow` 派发，不进 State |
| `XxxViewModel` | 唯一状态持有者 | `onIntent()` → reducer 更新 `StateFlow<UiState>`，禁止在 Composable 内直接改状态 |
| `XxxUseCase` | 业务用例 | 仅在有跨仓储编排/缓存策略时才建，简单 CRUD 直接走 Repository |

- **状态提升**：Tab 级页面状态提升到 `MainScreen` 的 ViewModel，配合 Navigation 的 `saveState/restoreState` 保持滚动与选中态
- **测试切面**：UiState + Intent 是纯 Kotlin 对象，单测不需要 Android 环境（对应 16.1）

**选型风险与边界（反向视角）**：
- Compose 生态约束：个别三方 SDK（运营商一键登录、语音识别、地图）仍是 View 体系，需用 `AndroidView` 包装，须在 1.2 的 `library-*` 中统一封装，禁止 feature 内直接 `interop`
- 2026 年节点 Kotlin/Compose 版本快速迭代，版本统一以 1.5.3 版本目录为**唯一真源**（20.1 清单与之对齐），升级走版本目录单点变更
- 若后续要复用到 iOS/小程序，业务规则必须沉到 Domain 层，UI 层不放业务逻辑，为跨端迁移留口

### 1.2 目录结构

```
app/
├── build.gradle.kts                    # 应用级构建配置
├── proguard-rules.pro                  # 混淆规则
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/shopping/app/
│   │   ├── App.kt                      # Application 入口
│   │   ├── MainActivity.kt             # 单 Activity 容器
│   │   ├── di/                         # Hilt 依赖注入
│   │   │   ├── AppModule.kt
│   │   │   ├── NetworkModule.kt
│   │   │   ├── DatabaseModule.kt
│   │   │   └── RepositoryModule.kt
│   │   ├── core/                       # 核心基础层
│   │   │   ├── common/                 # 通用工具、扩展函数
│   │   │   ├── network/                # 网络层（Retrofit + OkHttp）
│   │   │   ├── database/               # Room 数据库
│   │   │   ├── datastore/              # DataStore 偏好存储
│   │   │   ├── model/                  # 数据模型（DTO/Entity/DO）
│   │   │   └── ui/                     # 通用 UI 组件、主题
│   │   ├── feature/                    # 业务模块（按功能划分，源码实际位于 modules/feature，见 1.2.1）
│   │   │   ├── splash/                 # 启动页
│   │   │   ├── onboarding/             # 引导页
│   │   │   ├── auth/                   # 登录注册
│   │   │   ├── home/                   # 首页
│   │   │   ├── category/               # 分类
│   │   │   ├── product/                # 商品（含 SKU、收藏、分享）
│   │   │   ├── search/                 # 搜索
│   │   │   ├── cart/                   # 购物车
│   │   │   ├── order/                  # 订单（含物流、评价）
│   │   │   ├── aftersale/              # 售后（退款/退货/换货）
│   │   │   ├── checkout/               # 结算（含收银台、支付）
│   │   │   ├── seckill/                # 秒杀/活动承接页
│   │   │   ├── scan/                   # 扫码识物 / 扫码登录确认
│   │   │   ├── profile/                # 个人中心（含足迹）
│   │   │   ├── message/                # 消息通知
│   │   │   ├── customer/               # 客服 + 工单
│   │   │   └── settings/               # 设置
│   │   ├── navigation/                 # 导航路由（源码位于 modules/core/core-navigation）
│   │   │   ├── AppNavGraph.kt
│   │   │   └── Screen.kt               # 路由密封类
│   └── res/                            # 资源文件
│       ├── values/                     # 字符串、颜色、尺寸
│       ├── drawable/                   # 图标、背景
│       ├── font/                       # 字体
│       └── xml/                        # 配置
├── gradle/
│   └── libs.versions.toml              # 版本目录
└── build.gradle.kts                    # 项目级构建配置

# 多模块（Gradle Module）
modules/
├── core/                               # 核心基础模块
│   ├── core-common/                    # 通用工具
│   ├── core-network/                   # 网络库（拦截器/签名/Token 刷新/错误码，见 1.6）
│   ├── core-database/                  # 数据库
│   ├── core-datastore/                 # DataStore 封装（KV、搜索历史、开关）
│   ├── core-model/                     # 数据模型（DTO/Entity/DO + 分页信封）
│   ├── core-ui/                        # UI 组件库
│   ├── core-navigation/                # 导航（路由表 + 跳转 API）
│   └── core-api/                       # 跨模块服务接口（SPI，feature 之间唯一通信通道）
├── feature/                            # 业务功能模块
│   ├── feature-splash/
│   ├── feature-onboarding/
│   ├── feature-auth/
│   ├── feature-home/
│   ├── feature-category/
│   ├── feature-product/
│   ├── feature-search/
│   ├── feature-cart/
│   ├── feature-order/                  # 订单 + 物流 + 评价
│   ├── feature-aftersale/              # 售后
│   ├── feature-checkout/               # 结算 + 收银台
│   ├── feature-seckill/                # 秒杀/活动
│   ├── feature-scan/                   # 扫码
│   ├── feature-profile/
│   ├── feature-message/
│   ├── feature-customer/
│   └── feature-settings/
└── library/                            # 第三方库封装（含 View 体系 SDK 的 Compose 包装）
    ├── library-push/                   # 推送封装（FCM + 厂商通道 + Token 上报）
    ├── library-image/                  # 图片加载封装
    ├── library-webview/                # WebView 封装（安全加固见 1.6.7）
    ├── library-share/                  # 分享封装
    ├── library-biometric/              # 生物识别封装
    └── library-analytics/              # 埋点/监控封装
```

#### 1.2.1 工程结构说明（澄清 `app/` 与 `modules/` 的关系）

- **`app/` 是唯一可发布的壳工程**：只包含 `Application`、`MainActivity`、Hilt 聚合模块、`AppNavGraph` 聚合、渠道/签名配置；**不含任何业务 UI**
- **业务代码全部位于 `modules/feature/*`**：上文 `app/src/main/java/.../feature/` 为逻辑分层示意，实际以 `modules/` 为准，二选一避免"两套目录"歧义
- **每个 feature 模块内部结构统一**：
  ```
  feature-xxx/
  ├── api/       # 对外暴露的接口（仅 Navigation/core-api 需要的最小面）
  ├── data/      # DataSource + RepositoryImpl + DTO
  ├── domain/    # Entity + UseCase + Repository 接口
  ├── ui/        # Screen + ViewModel + UiState/Intent/Effect + 组件
  └── debug/     # 独立运行的 MockModule + 启动 Activity
  ```
- **依赖方向**：`app → feature-* → core-* → library-*`；`feature` 之间零依赖，跨模块只通过 `core-api` 接口 + `core-navigation` 路由；接口实现用 Hilt 在 `app` 中绑定（避免循环依赖）

### 1.3 组件化设计

#### 1.3.1 分层架构

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                │
│         Screen → ViewModel → UiState                  │
├─────────────────────────────────────────────────────┤
│                  Domain Layer                        │
│    UseCase → Repository Interface → Entity           │
├─────────────────────────────────────────────────────┤
│                  Data Layer                          │
│  RepositoryImpl → RemoteDataSource → LocalDataSource │
│                  ↕                    ↕              │
│              Retrofit API          Room DB           │
└─────────────────────────────────────────────────────┘
```

**MVI 数据流（与 1.1 约定对应，避免落地成"伪 MVI"的 MVVM）**：

```
Compose Screen --Intent--> ViewModel --UseCase--> Repository --Remote/Local-->
        ▲                                             │
        └────────── UiState (StateFlow) ◀── reducer ◀──┘
        └────────── Effect (Channel)   ◀── 一次性事件（跳转/Toast）
```

- 单一数据源：一个 Screen 对应一个 ViewModel、一个 UiState；禁止多 ViewModel 各持一半状态
- UI 无业务逻辑：Composable 只做渲染与 Intent 上报，价格计算、库存判断等一律在 Domain/Data 层
- 副作用不进状态：弹窗开关、跳转、Toast 走 Effect，避免旋转/进程重建后重复触发

#### 1.3.2 通用 UI 组件库（core-ui）

| 组件 | 用途 | 说明 |
|------|------|------|
| `AppTopBar` | 顶部导航栏 | 支持标题、返回、右侧操作 |
| `AppButton` | 按钮 | 主要/次要/禁用/加载态 |
| `AppDialog` | 弹窗 | 确认框、底部弹出、全屏弹窗 |
| `AppLoading` | 加载态 | 骨架屏、进度条、Lottie 动画 |
| `AppEmpty` | 空状态 | 空数据、网络错误、无权限 |
| `AppToast` | 轻提示 | 成功/警告/错误 |
| `AppSnackbar` | 底部提示 | 带操作按钮 |
| `AppSwipeRefresh` | 下拉刷新 | 统一刷新样式；内部实现基于 Material3 `PullToRefreshBox`，不引第三方下拉库 |
| `AppLoadMore` | 加载更多 | 列表底部加载 |
| `AppImage` | 图片组件 | 占位图、渐进加载、圆角 |
| `AppPrice` | 价格组件 | 现价/原价/优惠标签 |
| `AppTag` | 标签 | 促销标签、状态标签 |
| `AppRating` | 评分 | 星级展示 |
| `AppCountDown` | 倒计时 | 秒杀/活动倒计时 |
| `AppCarousel` | 轮播 | Banner 轮播 |
| `AppGrid` | 网格布局 | 商品网格 |
| `AppTabLayout` | 标签页 | 顶部/底部标签 |
| `AppSearchBar` | 搜索栏 | 首页/搜索页复用 |
| `AppAddressPicker` | 地址选择器 | 省市区三级联动 |
| `AppSkuSelector` | 规格选择器 | 弹窗式规格选择 |

#### 1.3.3 设计规范

- **色彩体系**：主色（品牌色）、辅助色、功能色（成功/警告/错误/信息）、中性色
- **字体规范**：标题（18sp/16sp/14sp）、正文（14sp/12sp）、辅助（12sp/10sp）
- **间距规范**：4dp 基础网格（4/8/12/16/24/32dp）
- **圆角规范**：小（4dp）、中（8dp）、大（16dp）、全圆（999dp）
- **阴影规范**：卡片阴影、弹窗阴影、导航栏阴影

### 1.4 路由设计

#### 1.4.1 路由方案

采用 **Jetpack Navigation Compose** + 类型安全路由（Navigation 2.8+）

```kotlin
// Screen.kt - 路由密封类（Navigation 2.8+ 类型安全路由）
// 规则：① 所有入参必须 Uri.encode；② 可选参数用 query 形式并在 composable 声明 defaultValue；
//       ③ ID 类参数用 Long（与后端 BIGINT 对齐），避免 String 隐式转换错误
sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Onboarding : Screen("onboarding")
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Home : Screen("home")
    data object Category : Screen("category")
    data object Cart : Screen("cart")
    data object Profile : Screen("profile")
    data object ProductDetail : Screen("product/{productId}") {
        fun createRoute(productId: Long) = "product/$productId"
    }
    data object Search : Screen("search")
    data object SearchResult : Screen("search/result/{keyword}") {
        // 关键词含空格、#、?、中文时未编码会导致路由解析失败（原实现缺陷）
        fun createRoute(keyword: String) = "search/result/${Uri.encode(keyword)}"
    }
    data object Checkout : Screen("checkout")
    data object OrderList : Screen("order/list?tab={tab}") {
        fun createRoute(tab: String = "all") = "order/list?tab=$tab"
    }
    data object OrderDetail : Screen("order/{orderId}") {
        fun createRoute(orderId: Long) = "order/$orderId"
    }
    data object Logistics : Screen("order/logistics/{orderId}") {
        fun createRoute(orderId: Long) = "order/logistics/$orderId"
    }
    data object Review : Screen("order/review/{orderId}") {      // 新增：发表评价（对应 9.6）
        fun createRoute(orderId: Long) = "order/review/$orderId"
    }
    data object AddressList : Screen("address/list")
    data object AddressEdit : Screen("address/edit?addressId={addressId}") {
        // 可选参数走 query 形式，并在 composable() 中声明 defaultValue = null
        fun createRoute(addressId: Long? = null) =
            addressId?.let { "address/edit?addressId=$it" } ?: "address/edit"
    }
    data object Favorite : Screen("favorite")
    data object Coupon : Screen("coupon")
    data object Message : Screen("message")
    data object CustomerService : Screen("customer")
    data object Settings : Screen("settings")
    data object WebView : Screen("webview?url={url}") {
        // URL 整体编码为 query 参数，避免 {url} 中的 / 被当作路径分隔符（原实现缺陷）
        fun createRoute(url: String) = "webview?url=${Uri.encode(url)}"
    }
}
```

#### 1.4.2 导航图结构

```
冷启动
  └─ Splash ──(未同意隐私政策)──> Onboarding(含协议弹窗)
  └─ Splash ──(已同意 & 已登录)──────────────┐
  └─ Splash ──(已同意 & 未登录 & 非强制引导)─┐│
                                             ▼▼
                                           Main
                          ├── Home (Tab)  ├── Category (Tab)
                          ├── Cart (Tab)  └── Profile (Tab)
                          └── 二级页面（push 进栈，返回回主 Tab）
        ├── ProductDetail / SearchResult / Store / Seckill / Scan
        ├── Checkout → Payment → PaymentResult
        ├── OrderList → OrderDetail → Logistics / Review / AfterSale
        └── AddressList → AddressEdit / Message / Customer / Settings ...
```

- **启动分发**（对应 2.1.1）：协议同意 → 版本强制更新拦截 → 登录态判断 → 深链暂存队列回放
- **Tab 状态保持**：`NavHost` 内每个 Tab 使用独立子图，切 Tab 时 `saveState()/restoreState()`，保留滚动位置与筛选条件
- **返回栈**：仅 Main 内 Tab 切换不出栈；二级页面 `popUpTo(home)`；支付成功页 `popUpTo(order/detail)`

#### 1.4.3 底部导航（MainScreen）

| Tab | 图标 | 路由 | 徽标 |
|-----|------|------|------|
| 首页 | 首页图标 | home | 无 |
| 分类 | 分类图标 | category | 无 |
| 购物车 | 购物车图标 | cart | 商品数量（来自首页聚合 `cartInfo.count`） |
| 我的 | 个人图标 | profile | 消息数（来自 `notifications.unreadCount`） |

> 注：底部导航**只存在于 MainScreen**，Home/Category/Cart/Profile 各自页面内不再重复渲染（修正原 4.1.1 中 HomeScreen 内嵌 BottomNavigation 的重复定义）。

#### 1.4.4 Deeplink 与外部路由

| 来源 | 形式 | 处理 |
|------|------|------|
| 推送点击 | `easy-shopping://order/80001`（后端 6.2.3 deeplink 字段） | `SingleTop` Activity + 统一 `DeepLinkDispatcher` 解析 |
| 分享回流/短链 | `https://m.example.com/p/{spuId}` | Android App Links（`autoVerify`）+ 兜底弹窗打开 |
| 站内 H5 | `easy-shopping://webview?url=...` | WebView 容器桥接，白名单域名校验 |
| 扫码 | 商品码/登录码 | 路由到 feature-scan |

- `AndroidManifest`：`intent-filter` 声明 scheme/host，`launchMode="singleTask"`，避免重复入栈
- **冷启动暂存**：启动未完成时收到的 deeplink 先入队，Splash 完成分发后再回放，防止丢深链
- 深链参数一律走 `Screen.createRoute()` 编码，禁止字符串拼接

#### 1.4.5 跨模块路由与服务注册

- `core-navigation` 持有全部 `Screen` 与 `NavGraphBuilder` 注册扩展；**聚合 NavGraph 只在 `app` 模块组装**，feature 只注册自己的子图（`fun NavGraphBuilder.featureHomeGraph()`）
- feature 之间的能力调用（如"商品详情→加入购物车后刷新角标""订单→客服带上下文"）通过 `core-api` 接口 + Hilt 注入实现，**禁止直接依赖对方模块**
- 未匹配路由统一降级到 404/首页，避免崩溃；线上开启路由命中埋点

### 1.5 多模块管理

#### 1.5.1 模块依赖规则

```
app → feature-* → core-* → library-*
```

- `feature` 模块之间**不允许直接依赖**，通过 `core-navigation` 路由跳转、通过 `core-api` 接口通信（见 1.4.5）
- `core` 模块之间**不允许循环依赖**；`core-navigation` 与 `core-api` 不得反向依赖任何 `feature`
- `library` 模块为最底层，不依赖其他模块
- **依赖守卫**：CI 中用 `gradle dependencies` + 自定义 task 校验上述规则，违规即构建失败（仅靠约定必然腐化）
- **API 面收敛**：feature 的 `api/` 包用 `@RestrictTo` 或显式 `internal` 控制可见性，防止其它模块反射式滥用

#### 1.5.2 模块独立运行

每个 `feature` 模块支持独立 debug 运行：
- 每个 feature 模块包含 `debug/` 目录，提供 Mock 数据
- 通过 `buildConfigField` 控制是否独立运行
- 独立运行时使用 Hilt 测试模块替换依赖

#### 1.5.3 版本管理

使用 **Gradle Version Catalog**（`libs.versions.toml`）统一管理依赖版本，**该文件是全项目版本唯一真源**（20.1 附录与之保持一致，禁止两处各写各的）：

```toml
[versions]
kotlin = "2.0.21"            # Kotlin 与 Compose Compiler 插件版本强绑定
compose-bom = "2024.10.01"
hilt = "2.52"
retrofit = "2.11.0"
okhttp = "4.12.0"
room = "2.6.1"
coil = "2.7.0"
navigation = "2.8.3"
coroutines = "1.9.0"

[libraries]
# ... 依赖声明

[plugins]
# ... 插件声明
```

- 升级流程：版本目录单点改 → 全量 CI 回归（Macrobenchmark + 兼容性用例）→ 合并
- Compose BOM 只管 UI 依赖，三方 SDK（推送/地图/支付）单独锁版本并验证兼容矩阵

### 1.6 网络与数据层（新增：原文档仅在目录中出现 `core/network`，缺实现级设计）

#### 1.6.1 接口契约对齐（与 `android-backend.md` v1.1 对照，**以右列为准**）

| 业务 | 原文路径（已废弃） | 后端契约路径 |
|------|--------------------|--------------|
| 发送验证码 | `/api/auth/send-code` | `POST /api/v1/auth/sms-code` |
| 验证码登录 | `/api/auth/login/code` | `POST /api/v1/auth/login/sms` |
| 密码登录 | `/api/auth/login/password` | `POST /api/v1/auth/login/password` |
| 第三方登录 | `/api/auth/login/third` | `POST /api/v1/auth/login/oauth` |
| 一键登录 | `/api/auth/login/onekey` | `POST /api/v1/auth/login/one-click` |
| 刷新 Token / 登出 | 原文缺失 | `POST /api/v1/auth/token/refresh`、`POST /api/v1/auth/logout` |
| 生物识别 | 原文缺失 | `POST /api/v1/auth/biometric/{enable,disable,verify}`、`/api/v1/auth/face/verify` |
| 设备注册 / 推送 Token 上报 | 原文缺失 | `POST /api/v1/device/register`、`/bind`、`/unbind`、`GET /list` |
| 商品列表 | `/api/product/list` | `GET /api/v1/products/category/{categoryId}`、`/brand/{brandId}`、`/filter`、`/new`、`/hot` |
| 商品详情 | `/api/product/detail` | `GET /api/v1/products/{spuId}`、`GET /api/v1/products/{spuId}/sku/{skuId}` |
| 商品评价 | `/api/product/review` | `GET /api/v1/products/{spuId}/reviews` |
| 推荐流 | `/api/home/recommend`、`/api/product/recommend` | `GET /api/v1/recommendations/home`、`/similar/{spuId}`、`/match/{spuId}`、`/also-bought/{spuId}` |
| 搜索结果 | `/api/search/result` | `GET /api/v1/search/keyword` |
| 搜索建议 / 热门 | `/api/search/suggest`、`/hot` | `GET /api/v1/search/suggest`、`/hot` |
| 语音 / 图片搜索 | `/api/search/image` | `POST /api/v1/search/voice`、`POST /api/v1/search/image` |
| 收藏 | `/api/favorite/*` | `GET/POST /api/v1/user/favorites`、`DELETE /api/v1/user/favorites/{spuId}`、`POST .../batch-delete` |
| 地址 | `/api/address/*` | `GET/POST /api/v1/user/addresses`、`PUT/DELETE .../{addressId}`、`POST .../{addressId}/default` |
| 用户信息 | `/api/user/info`、`/api/user/update` | `GET/PUT /api/v1/user/profile`；头像 `POST /api/v1/user/avatar` |
| 积分 | `/api/user/points` 与 `/api/points/*` 混用 | `GET /api/v1/user/points`、`GET .../points/records`、`POST .../points/exchange`、`POST .../points/sign` |
| 订单 | `/api/order/*` | `GET /api/v1/orders`、`GET /api/v1/orders/{orderId}`、`POST .../cancel|confirm|rebuy`、`DELETE .../{orderId}` |
| 物流 | `/api/logistics/track`（运单号） | `GET /api/v1/logistics/{orderId}`、`/{orderId}/detail`、`/{orderId}/courier`（**按订单号查询，参数语义变更**） |
| 支付 | `/api/payment/params`、`/result` | `POST /api/v1/payment/create`、`GET /api/v1/payment/{paymentId}`、`POST .../verify-password`、`.../biometric-pay` |
| 消息通知 | `/api/message/*` | `GET /api/v1/notifications`、`/{id}`、`POST /{id}/read`、`/read-all`、`DELETE /{id}`、`GET /unread-count`、`GET/PUT /settings` |
| 在线客服 | `/api/customer/*` | `POST /api/v1/cs/session`、`.../{sessionId}/message`、`GET .../messages`、`.../close`、`.../rate`、`GET /api/v1/cs/status` |
| 工单 | `/api/ticket/*` | `POST /api/v1/cs/ticket`、`GET /api/v1/cs/tickets`、`/{ticketId}`、`POST .../reply|close|rate` |
| 分享 | `/api/share/config`、`/shortlink` | `POST /api/v1/share/create`、`GET /api/v1/share/{shareCode}`、`POST .../click`、`POST /api/v1/share/poster` |
| 版本检测 | `/api/app/version` | `GET /api/v1/update/check?platform=android&versionCode=&channel=&deviceId=` |
| 首页 | `/api/home/config`、`/home/seckill` | `GET /api/v1/aggregation/home`（聚合：banners/categories/recommendations/unread/cartInfo） |
| 通知设置 | `/api/notification/settings` | `GET/PUT /api/v1/notifications/settings` |
| 扫码（原文缺失） | — | `POST /api/v1/scan/product`、`GET /api/v1/scan/history` |

**落地方式**：路径常量集中在 `core-network` 的 `ApiPaths` 对象，禁止各 feature 字符串字面量散落；联调阶段以后端 OpenAPI 生成 Retrofit 接口（OpenAPI Generator），从机制上消除手写偏差。

#### 1.6.2 后端尚未定义的接口（前端依赖，**联调前必须闭环**）

| 优先级 | 缺口 | 影响 |
|--------|------|------|
| P0 | 购物车全套 `/cart/*`（list/add/update/remove/select/batch/stock-check） | 后端仅有 `POST /api/v1/order/cart-checkout`，**购物车与结算链路阻塞**；可用优惠券属 P1 `/coupon/available`，不在本行 |
| P0 | 通用文件上传 `/file/upload` | 头像、售后图片、工单图片、客服图片均无上传通道 |
| P0 | 支付结果通知之外的**评价发表**接口 | 9.1 订单列表"去评价"按钮无落地 |
| P0 | 注册 `POST /api/v1/auth/register`（**或**后端明确「验证码登录即自动注册」并关闭独立注册入口） | 3.2 注册页无落地接口；后端 16.4 已登记为 P0 并要求第 1-4 周内二选一闭环（不能两处都没有） |
| P1 | 优惠券 `/coupon/*`、售后 `/aftersale/*` | 结算优惠与售后模块无法联调 |
| P1 | 首页秒杀/活动配置、闪屏 `/splash/config`、引导 `/onboarding/config` | 启动与首页活动位降级 |
| P1 | 埋点批量上报 `/track/*`（后端拟定 `POST /api/v1/track/batch`） | 曝光/漏斗数据缺失 |
| P1 | 账号安全与配置：改密 `/user/password/change`、换绑 `/user/phone/bind`、踢设备、推送配置 `/push/config`、用户统计 `/user/stats`、收藏单查 `/user/favorites/{spuId}`、分类树 `/category/tree` | 后端 16.4 将这些列为 **P1**（第 5-18 周），账户安全页/推送设置/分类页依赖；原表误列 P2 已上调 |
| P2 | 协议文本 `/agreement/*`、权限文案 `/permission/config`、省市区 `/region/list` | 可降级为内置资源/随包 JSON |
| P2 | 搜索纠错 `/search/correct`、搜索发现 `/search/discovery` | 对应功能降级 |

**与后端 16.4「待契约接口清单」的对账口径（【跨文件同步 S1】）**：本表每一行都必须能在 `android-backend.md` 16.4 或其正文找到对应项，优先级与交付阶段以 16.4 为准——**P0**：购物车全套（第 1-4 周出契约 / 第 5-8 周实现）、文件上传（第 1-4 周）、评价发表 `POST /api/v1/orders/{orderId}/review`（第 1-4 周出契约 / 第 9-12 周实现）、注册（第 1-4 周二选一）；**P1**：售后 `/aftersale/*` 与优惠券 `/coupon/*`（第 9-12 周）、分类树与收藏单查（第 5-8 周）、埋点 `POST /api/v1/track/batch` 与账号安全类（第 13-18 周）；**P2**：协议/权限/region、搜索增强、扫码付款、cursor 分页（第 19-22 周）。出现「两边都没有」的接口即评审失败项，P0 未闭环前相关模块不得进入联调。

约定：**未出现在后端文档中的路径一律视为"待契约"**，在本文各模块数据依赖表中以 `⏳待契约` 标注，不得按前端臆测直接开发。

#### 1.6.3 统一响应、错误码与分页

- 响应信封以后端 16.1 为准：`{ code, message, data, timestamp, requestId }`
- 错误码处理集中在 `core-network`，feature 只感知 `Result`/`UiState.Error`：

| code | 客户端统一行为 |
|------|----------------|
| 0 | 成功，解析 `data` |
| 1001 | 参数错误 → 展示 `message`，不重试 |
| 1002 / 1003 | 未登录 / Token 过期 → 走 1.6.4 刷新，刷新失败清态跳登录 |
| 1004 | 无权限 → 引导开通/提示 |
| 3002 | 库存不足 → 购物车/结算页局部刷新库存 |
| 4002 | 订单状态错误 → 刷新订单列表 |
| 9999 | 系统错误 → 通用重试条 |

- 同时兼容 HTTP 401/403（网关层拦截不一定走业务码）
- 分页统一 `{ total, page, pageSize, list }`；首页 feed 深翻页（page>100）建议 cursor 分页（后端暂未提供，列入待契约）
- 序列化统一 **kotlinx.serialization**（Kotlin 优先、混淆规则少），金额以后端 `DECIMAL(10,2)` 为准用 `BigDecimal`/分字符串展示，**禁止 Double 参与金额计算**；时间统一 ISO-8601 + 本地时区格式化

#### 1.6.4 Token 生命周期与拦截器链

- 拦截器顺序：`CommonParams`（deviceId/版本/渠道/语言/时间戳/nonce/签名） → `Auth`（注入 Access Token） → `Retry`（仅幂等 GET，指数退避） → `Log`（debug 才开，脱敏）
- 签名：HMAC-SHA256，secret 登录后下发并存 **Keystore**；timestamp 容差 5min + nonce 防重放（对齐后端 13.1）
- 刷新：401 时经 `Authenticator` **单飞刷新**（并发请求只刷新一次，其余等待复用新 Token）；Refresh Token 轮换、旧 Token 失效（后端 2.2）
- 刷新失败 → 清空本地凭据与用户数据 → 跳登录并回跳原页面；登出必须调 `POST /api/v1/auth/logout`
- 多端互踢（后端 Token 黑名单）：收到 1002 时提示"账号在其他设备登录"

#### 1.6.5 超时、弱网与缓存

| 项 | 约定 |
|----|------|
| 超时 | 连接 5s / 读 10s / 写 10s；首页聚合 8s |
| 协议 | HTTP/2、开启 Brotli/Gzip、连接复用（对齐后端 12.5.1） |
| 请求合并 | 首页走 `GET /api/v1/aggregation/home`，避免 5 个并发首屏请求 |
| 缓存 | 可缓存 GET 采用 stale-while-revalidate（先展示磁盘缓存→后台刷新→增量重组） |
| 弱网 | 顶部弱网提示条、图片降清（200x200 列表图）、失败重试按钮、超时兜底文案 |
| 离线 | 见 13.2，写操作进 Room 离线队列，幂等键 `clientRequestId` |

#### 1.6.6 图片与资源契约

- 按后端 12.4.3 URL 规则 `https://cdn/{width}x{height}/{format}/{path}` 由 `library-image` 提供 `ImageUrlBuilder`，按容器尺寸请求（列表 400x400、详情 800x800），杜绝原图直载 OOM
- Coil 内存缓存按 `memoryClass/8` 上限、磁盘缓存 LRU 256MB；列表统一 WebP + 占位图 + 渐进加载

#### 1.6.7 WebView 与传输安全（详见十七章，此处为网络层职责）

- 仅允许 `https`、域白名单、禁用 `file://` 与任意 `javascript:` 跳转；`addJavascriptInterface` 接口白名单 + `@JavascriptInterface` 最小化
- JSBridge 异步回调带超时与鉴权；H5 与原生登录态通过 Cookie 同步
- 全站 HTTPS + 证书锁定（Pin 证书轮换预案）；禁用明文流量（`usesCleartextTraffic=false`）

---

## 二、启动与初始化

### 2.1 启动页（Splash）

#### 2.1.1 功能点

| 功能 | 说明 |
|------|------|
| 品牌展示 | Logo + 品牌名称 + Slogan |
| 初始化 | SDK 初始化、配置拉取、数据库迁移 |
| 闪屏广告 | 支持运营配置的全屏广告（可跳过） |
| 版本检测 | 强制更新、推荐更新、热修复检查 |
| 路由分发 | 根据登录状态跳转首页/登录页 |

#### 2.1.2 页面/组件

```
SplashScreen
├── BrandSection（Logo + Slogan）
├── AdSection（闪屏广告，3s 倒计时跳过）
├── LoadingIndicator（初始化进度）
└── VersionDialog（更新弹窗）
```

#### 2.1.3 技术要点

- **启动优化**：使用 `App Startup` 库管理初始化器，支持懒加载和依赖关系
- **启动超时**：初始化设置 3s 超时，超时后进入首页（非关键 SDK 后台继续）
- **闪屏广告**：本地缓存 + 网络更新，支持 deeplink 跳转
- **启动模式**：冷启动/温启动/热启动区分处理
- **防闪退**：启动阶段 try-catch 保护，异常时进入安全模式

#### 2.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 闪屏广告配置 | 接口 `/api/v1/splash/config` ⏳待契约（后端 10 章未定义，降级策略：拉取失败或超时不展示广告直接进首页） | 图片、跳转链接、展示时长 |
| 版本信息 | 接口 `GET /api/v1/update/check`（后端 10.1.2） | `versionCode`、`updateType`（0 普通/1 强制/2 灰度）、`downloadUrl`、`updateDesc`、`grayRatio` |
| 灰度配置 | 接口 `GET /api/v1/update/gray-config` | 灰度放量判定（按 deviceId 哈希命中） |
| 热修复补丁 | 更新服务热修复模块 | 补丁下载与安装（限制与替代方案见 2.4.2） |

### 2.2 引导页（Onboarding）

#### 2.2.1 功能点

| 功能 | 说明 |
|------|------|
| 功能介绍 | 3-5 页滑动介绍核心功能 |
| 首次安装检测 | 仅首次安装或清除数据后展示 |
| 隐私政策 | 首次启动必须同意隐私政策 |
| 引导完成 | 标记完成状态，后续不再展示 |

#### 2.2.2 页面/组件

```
OnboardingScreen
├── HorizontalPager（引导页滑动）
│   ├── GuidePage1（购物便捷）
│   ├── GuidePage2（品质保障）
│   ├── GuidePage3（优惠多多）
│   └── GuidePage4（隐私政策同意）
├── PageIndicator（页面指示器）
└── ActionButton（跳过/下一步/开始使用）
```

#### 2.2.3 技术要点

- **HorizontalPager**：Compose 原生分页组件
- **动画**：Lottie 动画展示功能特性
- **隐私合规**：未同意隐私政策时禁止初始化第三方 SDK
- **状态持久化**：使用 DataStore 记录引导完成状态

#### 2.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 引导页配置 | 接口 `/api/v1/onboarding/config` ⏳待契约 | 图片、标题、副标题（失败时用本地内置 4 张兜底） |
| 隐私政策内容 | 接口 `/api/v1/agreement/privacy` ⏳待契约 | 隐私政策文本（建议内置最近一次快照，避免启动白屏） |

### 2.3 权限申请

#### 2.3.1 功能点

| 功能 | 说明 |
|------|------|
| 权限分组 | 必需权限、可选权限、特殊权限 |
| 申请时机 | 使用时申请（避免启动时集中申请） |
| 权限说明 | 申请前弹窗说明用途 |
| 拒绝处理 | 引导用户去设置页开启 |
| 权限监听 | 监听权限变化，动态调整功能 |

#### 2.3.2 权限清单

| 权限 | 用途 | 必要性 | 申请时机 |
|------|------|--------|----------|
| `INTERNET` | 网络访问 | 必需 | 安装时 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 | 必需 | 安装时 |
| `CAMERA` | 扫码识物、拍照 | 可选 | 使用时（对应 5.x / feature-scan） |
| `READ_MEDIA_IMAGES` | 图片选择（≥33） | 可选 | 使用时 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 部分照片访问（≥14） | 可选 | 使用时（优先走系统 Photo Picker，**无需权限**） |
| `READ_EXTERNAL_STORAGE` | 图片读取（≤32，兼容旧系统） | 可选 | 使用时 |
| `WRITE_EXTERNAL_STORAGE` | 仅 ≤28 保存图片；**29+ 走 MediaStore/SAF，不再需要** | 可选 | 使用时（`maxSdkVersion=28`） |
| `POST_NOTIFICATIONS` | 消息推送展示（≥33） | 可选 | 首次产生订阅意图后（如下单成功/开启物流提醒），**禁止启动即申请** |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 附近门店、地址智能填写（对应后端 7 章定位服务） | 可选 | 使用时（优先粗略定位，精确定位单独说明） |
| `RECORD_AUDIO` | 语音搜索 | 可选 | 使用时 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 客服会话、补丁/大图下载（≥14 必须声明类型） | 必需 | 安装时声明，运行时按需启动 |
| ~~`READ_PHONE_STATE`~~ | ~~设备标识~~ | **移除** | Android 10+ 已禁止读取 IMEI/MEID；设备标识改用 `Settings.Secure.ANDROID_ID` + 自生成 UUID（配合后端 `/api/v1/device/register`） |
| ~~`BIND_NOTIFICATION_LISTENER_SERVICE`~~ | ~~通知监听~~ | **移除** | 高敏感权限，电商场景无必要，属过度申请（工信部检测项） |
| ~~`QUERY_ALL_PACKAGES`~~ | 应用列表 | **不申请** | 合规红线，如需检测支付/社交 App 用 `PackageManager.queryIntentActivities` 可见范围 |

#### 2.3.3 技术要点

- **权限框架**：使用 `ActivityResultContracts.RequestPermission`，封装 `PermissionManager` 统一处理申请流程
- **申请顺序**：隐私政策同意 → 用户触发场景 → 前置用途说明弹窗 → 系统弹窗；拒绝一次不再弹（`shouldShowRequestPermissionRationale` 判断）→ 引导设置页
- **永久拒绝处理**：被"不再询问"拒绝后走应用详情页设置，返回时用 `repeatOnLifecycle` 复查权限状态
- **厂商适配**：小米/华为/OPPO/vivo/荣耀 自启动与后台弹窗权限差异，按 15.3 处理；推送权限引导页按厂商跳转对应设置
- **合规**：遵循《个人信息保护法》与工信部 164/165 号文，最小化权限、SDK 采集清单随包申报、权限使用点位埋点留证

#### 2.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 权限说明文案 | 本地内置 + 接口 `/api/v1/permission/config` ⏳待契约 | 各权限的用途说明（内置为主，接口仅运营微调） |

### 2.4 热修复

#### 2.4.1 功能点

| 功能 | 说明 |
|------|------|
| 补丁下载 | 启动时检查并下载补丁 |
| 补丁加载 | 运行时加载补丁代码 |
| 补丁管理 | 版本管理、回滚机制 |
| 安全校验 | 签名验证、完整性校验 |

#### 2.4.2 技术方案

| 方案 | 说明 |
|------|------|
| **Tinker**（推荐） | 微信开源，支持 dex/so/资源替换，成熟稳定 |
| **Robust** | 美团开源，即时生效，但维护较少 |
| **Sophix** | 阿里开源，全量替换，适合阿里系 |

**推荐 Tinker**：与 Android 系统兼容性好，支持 dex、so、资源文件修复，社区活跃、文档完善

**必须同时明确的限制与替代方案（否则方案不可实施）**：

| 限制 | 说明 | 对策 |
|------|------|------|
| Google Play 禁止动态下发可执行代码 | 热修复（Tinker）在 Play 分发的包中违规 | 海外包关闭热修复；国内渠道包启用，双包策略 |
| AGP 8.x / Kotlin 2.x / Compose 兼容 | Tinker 官方对新版 AGP 支持滞后 | 第一阶段做技术验证（PoC），失败则降级方案 |
| 仅能修 Java/Kotlin 逻辑与资源 | 无法修 Compose 编译产物中的部分问题、无法修三方 SDK 内部崩溃 | 结合远程开关 + H5 容器降级 + 强更兜底 |
| 补丁风险 | 补丁错误会导致连环崩溃 | 灰度 1%→5%→20%→全量，崩溃率超阈值自动回滚（2.4.3） |

替代/补充：**远程开关（配置中心）**、**资源热更新（仅 res/assets）**、**H5 化承载非核心功能**、**强更（走 `GET /api/v1/update/check`）**。

#### 2.4.3 技术要点

- **补丁下发**：启动时静默检查，WiFi 环境下预下载
- **加载时机**：Application `attachBaseContext` 时加载
- **安全机制**：补丁签名验证、版本匹配校验
- **灰度发布**：支持按比例、按用户群推送补丁
- **回滚机制**：补丁异常时自动回滚到上一个版本

#### 2.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 补丁信息 | 热修复平台接口 | 补丁版本、下载 URL、MD5 |

---

## 三、登录与账户

### 3.1 登录页

#### 3.1.1 功能点

| 功能 | 说明 |
|------|------|
| 手机号登录 | 手机号 + 短信验证码 |
| 密码登录 | 手机号 + 密码 |
| 第三方登录 | 微信、QQ、支付宝、Google |
| 一键登录 | 运营商一键登录（中国移动/联通/电信） |
| 忘记密码 | 手机号验证后重置 |
| 注册入口 | 跳转注册页面 |

#### 3.1.2 页面/组件

```
LoginScreen
├── LogoSection（品牌 Logo）
├── TabSwitch（验证码登录/密码登录）
├── PhoneInput（手机号输入，+86 区号）
├── VerifyCodeInput（验证码输入 + 获取验证码）
├── PasswordInput（密码输入 + 显示/隐藏）
├── LoginButton（登录按钮，加载态）
├── ThirdPartyLogin（第三方登录区域）
│   ├── WeChatLogin
│   ├── QQLogin
│   ├── AlipayLogin
│   └── GoogleLogin
├── OneKeyLogin（运营商一键登录）
└── BottomLinks（忘记密码/注册/用户协议）
```

#### 3.1.3 技术要点

- **输入校验**：手机号格式实时校验，验证码 6 位数字
- **倒计时**：获取验证码后 60s 倒计时
- **错误处理**：账号不存在、密码错误、账号冻结等
- **安全**：密码加密传输（RSA + AES），防暴力破解
- **一键登录**：集成运营商 SDK，适配多厂商

#### 3.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 发送验证码 | 接口 `POST /api/v1/auth/sms-code` | `mobile` + `type`(login/register/reset) + `captchaToken`(图形验证码防刷) → `expireIn`/`cooldown` |
| 验证码登录 | 接口 `POST /api/v1/auth/login/sms` | 手机号 + 验证码 → `{ accessToken, refreshToken, ... }` |
| 密码登录 | 接口 `POST /api/v1/auth/login/password` | 手机号 + 密码 → Token |
| 第三方登录 | 接口 `POST /api/v1/auth/login/oauth` | 平台(wechat/qq/weibo/alipay) + code → Token |
| 一键登录 | 接口 `POST /api/v1/auth/login/one-click` | 运营商 token → 登录态 |
| 扫码登录确认 | 接口 `POST /api/v1/auth/login/qr-confirm` | 本机作为"被扫端"确认 PC 端登录（配合 `/api/v1/scan-login/*`） |
| 用户协议 | 接口 `/api/v1/agreement/user` ⏳待契约 | 协议内容 |

### 3.2 注册页

#### 3.2.1 功能点

| 功能 | 说明 |
|------|------|
| 手机号注册 | 手机号 + 验证码 + 密码 |
| 密码强度 | 实时检测密码强度 |
| 邀请码 | 可选邀请码输入 |
| 协议同意 | 用户协议 + 隐私政策 |

#### 3.2.2 页面/组件

```
RegisterScreen
├── PhoneInput
├── VerifyCodeInput
├── PasswordInput（带强度指示）
├── ConfirmPasswordInput
├── InviteCodeInput（可选）
├── AgreementCheckbox（协议勾选）
└── RegisterButton
```

#### 3.2.3 技术要点

- **密码强度**：长度 8-20 位，需包含字母 + 数字
- **防刷**：同一手机号 60s 内只能发送一次验证码
- **协议合规**：未勾选协议禁止注册

#### 3.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 发送验证码 | 接口 `POST /api/v1/auth/sms-code`（type=register） | 注册场景 |
| 注册 | 接口 `POST /api/v1/auth/register` ⏳待契约（后端 2.1 未定义注册接口，需补齐或复用 sms 登录自动注册） | 手机号 + 验证码 + 密码 + 邀请码 |

### 3.3 生物识别

#### 3.3.1 功能点

| 功能 | 说明 |
|------|------|
| 指纹识别 | 指纹快速登录/支付验证 |
| 面部识别 | 人脸快速登录 |
| 设置管理 | 开启/关闭生物识别 |
| 降级处理 | 生物识别失败时降级为密码 |

#### 3.3.2 页面/组件

```
BiometricDialog
├── BiometricIcon（指纹/人脸图标）
├── BiometricPrompt（系统生物识别弹窗）
└── FallbackButton（使用密码）
```

#### 3.3.3 技术要点

- **BiometricPrompt**：使用 AndroidX Biometric 库
- **版本兼容**：Android 6.0+ 支持指纹，Android 10+ 支持面部
- **安全存储**：密钥存储在 Keystore，生物识别解锁
- **降级策略**：生物识别失败 3 次后强制使用密码

#### 3.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 生物识别开关 | 本地 DataStore + 服务端 `POST /api/v1/auth/biometric/enable|disable` | 本地与账号绑定，换设备需重新开启 |
| 指纹/人脸验证 | `POST /api/v1/auth/biometric/verify`、`POST /api/v1/auth/face/verify` | 服务端只验签，**生物特征不出设备**（后端 2.4.3） |

### 3.4 账户安全

#### 3.4.1 功能点

| 功能 | 说明 |
|------|------|
| 修改密码 | 旧密码验证 + 新密码设置 |
| 绑定手机 | 更换绑定手机号 |
| 绑定邮箱 | 绑定/更换邮箱 |
| 登录设备管理 | 查看/踢出登录设备 |
| 账户注销 | 注销账户（需验证身份） |

#### 3.4.2 页面/组件

```
SecuritySettingsScreen
├── ChangePasswordSection
├── BindPhoneSection
├── BindEmailSection
├── LoginDeviceList
└── AccountCancellation
```

#### 3.4.3 技术要点

- **身份验证**：敏感操作需二次验证（短信/密码）
- **设备管理**：记录设备型号、登录时间、IP
- **注销保护**：注销前需满足无未完成订单等条件

#### 3.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 修改密码 | 接口 `POST /api/v1/user/password/change` ⏳待契约 | 旧密码 + 新密码 |
| 换绑手机 | 接口 `POST /api/v1/user/phone/bind` ⏳待契约 | 新手机号 + 验证码 |
| 设备列表 | 接口 `GET /api/v1/device/list`（后端 2.3.2） | 当前登录设备（型号、系统、App 版本、最近登录） |
| 解绑/踢出设备 | 接口 `POST /api/v1/device/unbind` | 使对应 Refresh Token 失效（后端无独立 kick 接口，用 unbind 表达） |
| 实名认证 | 接口 `POST /api/v1/user/verify` | 高风险操作/提现场景 |

### 3.5 会话与 Token 管理（新增：原文档完全缺失，属 P0 流程）

#### 3.5.1 功能点

| 功能 | 说明 |
|------|------|
| Token 存储 | Access/Refresh Token 加密存储于 EncryptedSharedPreferences/Keystore |
| 自动续期 | Access 2h、Refresh 30d、轮换（对齐后端 2.2.2） |
| 静默刷新 | 401 单飞刷新，失败则清态跳登录（实现见 1.6.4） |
| 登出 | 服务端登出 + 本地清理（凭据、用户库、推送解绑） |
| 多端互踢 | 后端 Token 黑名单，被踢时提示并回登录页 |
| 账号切换 | 清空上一账号本地缓存（搜索历史、足迹、离线队列） |

#### 3.5.2 技术要点

- **不落地明文**：Token 不进普通 DataStore/日志/崩溃附件；日志与埋点全量脱敏
- **登出一致性**：先调 `POST /api/v1/auth/logout`，成功与否都清本地（防止服务端异常卡死登出）
- **推送解绑**：登出/换号时调用 `POST /api/v1/device/unbind`，避免串推（对应后端 `push:device` 缓存清理策略）
- **登录态探测**：冷启动用本地 Token 乐观进首页，首个请求 1002/1003 再跳登录，避免启动白屏

#### 3.5.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 刷新 Token | `POST /api/v1/auth/token/refresh` | 轮换双令牌 |
| 登出 | `POST /api/v1/auth/logout` | 服务端失效 |
| 设备解绑 | `POST /api/v1/device/unbind` | 推送 Token 解绑 |

---

## 四、首页

### 4.1 功能点

| 功能 | 说明 |
|------|------|
| 搜索入口 | 顶部搜索栏，点击跳转搜索页 |
| 消息入口 | 右上角消息图标，带未读角标 |
| Banner 轮播 | 运营位轮播图，支持 deeplink |
| 分类导航 | 金刚区分类入口（两行 10 个） |
| 活动入口 | 限时秒杀、品牌特卖、新品首发等 |
| 商品推荐 | 个性化推荐商品流 |
| 下拉刷新 | 刷新首页数据 |
| 返回顶部 | 滚动时显示返回顶部按钮 |

#### 4.1.1 页面/组件

```
HomeScreen
├── HomeTopBar（NestedScroll 连动：滚动收起、回弹展开）
│   ├── SearchBar（搜索入口）
│   └── MessageIcon（消息入口 + 角标）
├── HomeFeed（LazyColumn 单一滚动容器；禁止再套 NestedScrollView——Compose 中无此物，属原表述错误）
│   ├── BannerSection（轮播图，LazyItem + HorizontalPager）
│   ├── CategorySection（金刚区分类，FlowRow 两行 10 个）
│   ├── ActivitySection（活动入口）
│   │   ├── SeckillEntry（限时秒杀）
│   │   ├── BrandEntry（品牌特卖）
│   │   └── NewEntry（新品首发）
│   ├── SeckillSection（秒杀商品横滑，LazyRow）
│   ├── RecommendSection（推荐商品流，LazyVerticalGrid 双列）
│   │   ├── ProductCard（双列商品卡片）
│   │   └── ProductCard
│   └── LoadMoreFooter（分页加载指示）
└── BackToTopButton（滚动超过 2 屏显示）

（底部导航只在 MainScreen 渲染，见 1.4.3，HomeScreen 不再内嵌）
```

#### 4.1.2 技术要点

- **状态管理**：单一 `LazyColumn` 承载全部 section，推荐流内部嵌 `LazyVerticalGrid`；混合滚动用 `NestedScrollConnection` 实现 TopBar 收起/展开
- **首屏请求数**：走后端聚合接口 `GET /api/v1/aggregation/home`（banners/categories/recommendations/unreadCount/cartInfo 一次带回），避免 5 个并发请求拖慢首屏；秒杀/活动位单独按需拉取
- **图片优化**：Coil 异步加载，列表 400x400 WebP（见 1.6.6），占位图 + 渐进加载
- **预加载**：首页数据磁盘缓存，二次进入秒开（stale-while-revalidate，见 1.6.5）
- **下拉刷新**：Material3 `PullToRefreshBox`（`Accompanist-swiperefresh` 已停止维护，不建议引入）
- **性能**：列表项设置 `key`/`contentType`、图片内存缓存上限、Banner 懒加载（首屏外不预取）

#### 4.1.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 首页聚合 | 接口 `GET /api/v1/aggregation/home`（后端 12.1.3） | banners、categories、hotProducts、recommendations、`notifications.unreadCount`、`cartInfo.count` |
| 秒杀数据 | 接口 `/api/v1/seckill/list` ⏳待契约 | 秒杀商品列表（后端营销中心接口未开放，降级隐藏活动位） |
| 个性化推荐 | 接口 `GET /api/v1/recommendations/home` | 个性化推荐流（feed 分页） |
| 分类导航（金刚区） | 接口 `GET /api/v1/category/tree` ⏳待契约 | 分类树（对应后端类目服务） |
| 消息未读数 | 接口 `GET /api/v1/notifications/unread-count` | 未读消息数（与购物车角标一起来自聚合接口，二次刷新才单查） |

### 4.2 商品推荐

#### 4.2.1 功能点

| 功能 | 说明 |
|------|------|
| 双列瀑布流 | 商品卡片双列展示 |
| 商品信息 | 图片、标题、价格、销量、标签 |
| 个性化排序 | 基于用户偏好排序 |
| 无限滚动 | 滚动到底部自动加载更多 |
| 曝光埋点 | 商品曝光时上报 |

#### 4.2.2 页面/组件

```
RecommendSection
├── SectionHeader（标题 + 更多）
├── ProductGrid（双列网格）
│   ├── ProductCard
│   │   ├── ProductImage（商品图）
│   │   ├── ProductTitle（标题，2 行省略）
│   │   ├── ProductPrice（现价 + 原价）
│   │   ├── ProductTags（标签：包邮/正品等）
│   │   └── ProductSales（销量）
│   └── ProductCard
└── LoadMoreIndicator（加载更多）
```

#### 4.2.3 技术要点

- **瀑布流**：`LazyVerticalGrid` + 动态高度
- **图片**：Coil 加载，支持 WebP 格式
- **曝光**：`onGloballyPositioned` 监听可见性
- **预加载**：提前加载下一页数据

#### 4.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 推荐商品 | 接口 `GET /api/v1/recommendations/home` | 分页加载（page/pageSize，见 1.6.3） |
| 商品曝光 | 埋点批量上报 `POST /api/v1/track/batch` ⏳待契约（后端 16.4 拟定路径；曝光/点击等作为事件类型放在 body，不另开 `/track/exposure`） | 本地缓冲 + 批量上报 + 失败重试，用户拒绝个性化推荐后仅上报去标识事件 |

---

## 五、商品浏览

### 5.1 商品列表页

#### 5.1.1 功能点

| 功能 | 说明 |
|------|------|
| 分类筛选 | 左侧分类 + 右侧商品列表 |
| 排序 | 综合/销量/价格/新品 |
| 筛选 | 品牌、价格区间、属性 |
| 列表/网格切换 | 单列/双列展示 |
| 下拉刷新 | 刷新列表 |
| 加载更多 | 分页加载 |

#### 5.1.2 页面/组件

```
ProductListScreen
├── ListTopBar
│   ├── BackButton
│   ├── SearchBar
│   └── FilterButton
├── CategorySidebar（左侧分类）
├── FilterBar（排序 + 筛选）
│   ├── SortTabs（综合/销量/价格/新品）
│   └── FilterSheet（筛选弹窗）
├── ProductList（商品列表）
│   ├── ProductCard（单列/双列）
│   └── ...
└── LoadMoreIndicator
```

#### 5.1.3 技术要点

- **分类联动**：左侧分类选中时右侧列表联动更新
- **筛选器**：底部弹窗展示筛选选项
- **状态保持**：切换分类时保持滚动位置
- **缓存**：已加载分类数据本地缓存

#### 5.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 分类列表 | 接口 `GET /api/v1/category/tree` ⏳待契约 | 三级分类 |
| 商品列表 | 接口 `GET /api/v1/products/category/{categoryId}`（后端 3.1.2） | 支持 `page/pageSize/sort/priceMin/priceMax`；品牌维度走 `/api/v1/products/brand/{brandId}`，组合筛选走 `/api/v1/products/filter` |
| 筛选选项 | 接口 `GET /api/v1/products/filter` | 品牌、属性、价格区间 |

### 5.2 商品详情页

#### 5.2.1 功能点

| 功能 | 说明 |
|------|------|
| 图片轮播 | 商品主图轮播，支持缩放 |
| 价格展示 | 现价、原价、优惠信息 |
| 商品标题 | 标题 + 副标题 + 标签 |
| 规格选择 | 弹窗选择 SKU（颜色/尺寸等） |
| 商品详情 | 图文详情（WebView/原生渲染） |
| 评价展示 | 用户评价列表 |
| 推荐商品 | 相关推荐 |
| 底部操作栏 | 客服、收藏、加入购物车、立即购买 |
| 分享 | 生成分享海报 |

#### 5.2.2 页面/组件

```
ProductDetailScreen
├── DetailTopBar（透明 → 滚动变色）
│   ├── BackButton
│   ├── ShareButton
│   └── MoreButton
├── DetailFeed（LazyColumn 单一滚动容器；TopBar 透明→滚动变色用 NestedScrollConnection）
│   ├── ImageCarousel（主图轮播）
│   ├── PriceSection（价格区域）
│   │   ├── CurrentPrice（现价）
│   │   ├── OriginalPrice（原价）
│   │   └── DiscountTags（优惠标签）
│   ├── TitleSection（标题 + 标签）
│   ├── SkuSelector（规格选择入口）
│   ├── PromotionSection（促销信息）
│   ├── ShopSection（店铺信息）
│   ├── ReviewSection（评价区域）
│   │   ├── ReviewSummary（评分 + 好评率）
│   │   └── ReviewList（评价列表）
│   ├── DetailSection（图文详情）
│   └── RecommendSection（推荐商品）
├── BottomActionBar
│   ├── CustomerService（客服）
│   ├── Favorite（收藏）
│   ├── Cart（购物车 + 角标）
│   ├── AddToCart（加入购物车）
│   └── BuyNow（立即购买）
└── SkuDialog（规格选择弹窗）
```

#### 5.2.3 技术要点

- **图片浏览**：支持手势缩放、双击放大、左右滑动
- **SKU 选择**：弹窗展示规格组合，实时更新价格与库存；价格以后端 `/api/v1/products/{spuId}/sku/{skuId}` 为准，客户端不做金额计算（见 1.6.3）
- **图文详情**：WebView 渲染（安全加固见 1.6.7），H5 侧图片懒加载 + 预热 WebView 池实现"秒开"；纯图集详情可降级原生渲染
- **状态保持**：滚动位置保持，返回时恢复（Navigation `saveState`）
- **登录门槛**：未登录可浏览详情，收藏/加购/立即购买时引导登录并回跳（对应 1.4.2）
- **分享海报**：Canvas 绘制分享海报（生成走后端 `POST /api/v1/share/poster`，本地绘制仅作兜底）

#### 5.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 商品详情 | 接口 `GET /api/v1/products/{spuId}`（后端 3.2.2） | SPU 信息 + SKU 列表 + 详情 HTML |
| SKU 详情 | 接口 `GET /api/v1/products/{spuId}/sku/{skuId}` | 单规格价格、库存 |
| 评价列表 | 接口 `GET /api/v1/products/{spuId}/reviews` | 分页加载 |
| 相关推荐 | 接口 `GET /api/v1/recommendations/similar/{spuId}`（另有 `/match/`、`/also-bought/`） | 相关推荐 |
| 收藏状态 | 本地收藏列表比对；`GET /api/v1/user/favorites/{spuId}` ⏳单查接口待契约 | 是否已收藏 |
| 加入购物车 | 接口 `POST /api/v1/cart/add` ⏳待契约（后端 P0 缺口，见 1.6.2） | SKU + 数量 |
| 分享配置 | 接口 `POST /api/v1/share/create` | 标题、描述、图片、`shareCode` 短链 |

### 5.3 图片浏览器

#### 5.3.1 功能点

| 功能 | 说明 |
|------|------|
| 全屏浏览 | 沉浸式全屏图片浏览 |
| 手势缩放 | 双指缩放、双击放大 |
| 左右滑动 | 切换图片 |
| 保存图片 | 长按保存到相册 |
| 图片下载 | 批量下载 |

#### 5.3.2 页面/组件

```
ImageViewerScreen
├── ImagePager（全屏图片滑动）
│   └── ZoomableImage（可缩放图片）
├── ImageIndicator（页码指示器）
└── ImageActions（保存/下载）
```

#### 5.3.3 技术要点

- **手势处理**：`Modifier.pointerInput` 处理缩放手势
- **图片加载**：Coil 加载原图，支持渐进显示
- **内存管理**：大图分块加载，防止 OOM
- **保存权限**：Android 10+ 使用 MediaStore API

#### 5.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 图片列表 | 商品详情接口 | 图片 URL 列表 |

### 5.4 收藏

#### 5.4.1 功能点

| 功能 | 说明 |
|------|------|
| 收藏商品 | 商品详情页收藏 |
| 收藏列表 | 我的收藏页面 |
| 取消收藏 | 列表页左滑取消 |
| 收藏分类 | 按店铺/商品分类 |
| 降价提醒 | 收藏商品降价通知 |

#### 5.4.2 页面/组件

```
FavoriteScreen
├── FavoriteTabs（商品/店铺）
├── FavoriteList
│   ├── FavoriteItem
│   │   ├── ProductImage
│   │   ├── ProductTitle
│   │   ├── ProductPrice
│   │   └── PriceDropTag（降价标签）
│   └── FavoriteItem
└── SwipeToDismiss（左滑取消收藏）
```

#### 5.4.3 技术要点

- **左滑操作**：`SwipeToDismiss` 组件
- **批量操作**：长按进入多选模式
- **降价监控**：后台定时检查收藏商品价格变化

#### 5.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 收藏列表 | 接口 `GET /api/v1/user/favorites`（后端 5.3.2） | 分页加载（后端 `user_favorite` 仅支持商品收藏，**店铺收藏 Tab 需后端扩展或暂不上线**） |
| 添加收藏 | 接口 `POST /api/v1/user/favorites` | `spuId` |
| 取消收藏 | 接口 `DELETE /api/v1/user/favorites/{spuId}` | 商品 ID |
| 批量取消 | 接口 `POST /api/v1/user/favorites/batch-delete` | 批量管理模式 |

### 5.5 分享

#### 5.5.1 功能点

| 功能 | 说明 |
|------|------|
| 分享渠道 | 微信、朋友圈、QQ、微博、复制链接 |
| 分享海报 | 生成带二维码的商品海报 |
| 分享链接 | 短链接分享 |
| 分享统计 | 分享次数统计 |

#### 5.5.2 页面/组件

```
ShareSheet
├── SharePoster（分享海报预览）
├── ShareChannels（分享渠道）
│   ├── WeChat（微信好友）
│   ├── Moments（朋友圈）
│   ├── QQ
│   ├── Weibo
│   └── CopyLink
└── CancelButton
```

#### 5.5.3 技术要点

- **海报生成**：Canvas 绘制，包含商品图、标题、价格、二维码
- **分享 SDK**：集成微信/QQ/微博 SDK
- **短链接**：生成短链接，支持 deeplink
- **二维码**：商品详情页二维码

#### 5.5.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 分享内容 | 接口 `POST /api/v1/share/create`（后端 8.1.2） | 标题、描述、图片、短链、`shareCode`（渠道追踪） |
| 海报生成 | 接口 `POST /api/v1/share/poster`（后端 8.2.2） | 服务端合成带二维码海报（含模板 `GET /api/v1/share/poster-templates`） |
| 分享回流 | 接口 `POST /api/v1/share/{shareCode}/click` | 回流统计，配合 1.4.4 App Links |

---

## 六、搜索与筛选

### 6.1 搜索页

#### 6.1.1 功能点

| 功能 | 说明 |
|------|------|
| 搜索输入 | 实时搜索建议 |
| 搜索历史 | 本地存储搜索历史 |
| 热门搜索 | 服务端配置的热门搜索词 |
| 搜索发现 | 搜索发现词、趋势词 |
| 语音搜索 | 语音输入搜索 |
| 图片搜索 | 拍照/相册搜索商品 |

#### 6.1.2 页面/组件

```
SearchScreen
├── SearchTopBar
│   ├── BackButton
│   ├── SearchInput（自动聚焦）
│   ├── VoiceButton（语音搜索）
│   └── CameraButton（图片搜索）
├── SearchContent
│   ├── SearchHistorySection
│   │   ├── SectionHeader（搜索历史 + 清空）
│   │   └── HistoryTags（历史标签）
│   ├── HotSearchSection
│   │   ├── SectionHeader（热门搜索）
│   │   └── HotTags（热门标签，带排名）
│   └── SearchDiscoverySection
│       ├── SectionHeader（搜索发现）
│       └── DiscoveryTags（发现词）
└── SearchSuggestList（搜索建议）
    └── SuggestItem
```

#### 6.1.3 技术要点

- **搜索建议**：输入时实时请求搜索建议接口（防抖 300ms + 取消上一请求）
- **历史记录**：本地 DataStore 存储，最多 20 条；登录后与服务端 `GET /api/v1/search/history` 合并（本地优先，去重按时间倒序）
- **热门搜索**：服务端配置，支持运营调整
- **语音搜索**：优先走服务端 `POST /api/v1/search/voice`；讯飞 SDK 仅作弱网/离线兜底，且必须在隐私协议同意后初始化（SDK 清单申报见 2.3.3）
- **图片搜索**：拍照/上传图片走 `POST /api/v1/search/image`，图片先经 `/api/v1/file/upload` ⏳待契约

#### 6.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 搜索建议 | 接口 `GET /api/v1/search/suggest` | 关键词 → 建议词列表 |
| 热门搜索 | 接口 `GET /api/v1/search/hot` | 热门搜索词 |
| 搜索历史 | 接口 `GET /api/v1/search/history` | 多端同步 |
| 搜索发现 | 接口 `/api/v1/search/discovery` ⏳待契约 | 发现词（缺失时隐藏该 section） |
| 语音识别 | 接口 `POST /api/v1/search/voice` | 语音 → 文字（本地 SDK 兜底） |
| 图片搜索 | 接口 `POST /api/v1/search/image` | 图片 → 商品列表 |

### 6.2 搜索结果页

#### 6.2.1 功能点

| 功能 | 说明 |
|------|------|
| 搜索结果 | 商品列表展示 |
| 筛选器 | 品牌、价格、属性筛选 |
| 排序 | 综合/销量/价格/新品 |
| 列表/网格切换 | 单列/双列展示 |
| 无结果 | 推荐商品/搜索建议 |

#### 6.2.2 页面/组件

```
SearchResultScreen
├── ResultTopBar
│   ├── BackButton
│   ├── SearchInput（显示关键词）
│   └── SearchButton
├── FilterBar
│   ├── SortTabs（综合/销量/价格/新品）
│   ├── FilterButton（筛选）
│   └── LayoutToggle（列表/网格）
├── ResultList
│   ├── ProductCard
│   └── ...
├── EmptyResult（无结果）
│   ├── EmptyImage
│   ├── EmptyText
│   └── RecommendSection（推荐商品）
└── FilterSheet（筛选弹窗）
```

#### 6.2.3 技术要点

- **筛选器**：底部弹窗，支持多选和单选
- **排序**：综合排序（默认）、销量优先、价格升/降、新品优先
- **无结果处理**：展示推荐商品或搜索建议
- **搜索纠错**：自动纠正错别字

#### 6.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 搜索结果 | 接口 `GET /api/v1/search/keyword`（后端 3.3.2，原 `/api/search/result` 废弃） | `q/page/pageSize/sort/brandIds/priceMin/priceMax` |
| 筛选选项 | 接口 `GET /api/v1/products/filter` | 品牌、属性、价格区间（与商品列表共用一套筛选器组件） |
| 搜索纠错 | 接口 `/api/v1/search/correct` ⏳待契约 | 缺失时用 suggest 返回的高热近似词兜底 |

### 6.3 筛选器

#### 6.3.1 功能点

| 功能 | 说明 |
|------|------|
| 品牌筛选 | 多选品牌 |
| 价格区间 | 自定义价格区间 |
| 属性筛选 | 商品属性（颜色/尺寸等） |
| 服务筛选 | 包邮、正品保障等 |
| 重置/确定 | 重置筛选条件或应用 |

#### 6.3.2 页面/组件

```
FilterSheet
├── FilterHeader（重置 + 确定）
├── FilterContent
│   ├── BrandSection（品牌列表）
│   ├── PriceSection（价格区间）
│   │   ├── PriceInput（最低/最高）
│   │   └── PriceRangeSlider（滑块）
│   ├── AttributeSection（属性筛选）
│   └── ServiceSection（服务筛选）
└── FilterFooter（确定按钮）
```

#### 6.3.3 技术要点

- **多选/单选**：品牌多选，属性单选
- **价格区间**：支持手动输入和滑块选择
- **状态保持**：筛选条件在页面切换时保持

#### 6.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 筛选选项 | 接口 `GET /api/v1/products/filter` | 品牌、属性、服务 |

### 6.4 搜索历史

#### 6.4.1 功能点

| 功能 | 说明 |
|------|------|
| 历史记录 | 本地存储搜索历史 |
| 历史管理 | 单条删除、全部清空 |
| 历史搜索 | 点击历史词直接搜索 |

#### 6.4.2 页面/组件

```
SearchHistorySection
├── SectionHeader（搜索历史 + 清空按钮）
├── HistoryTags（标签云）
│   └── HistoryTag（可点击 + 删除）
└── EmptyHistory（无历史记录）
```

#### 6.4.3 技术要点

- **存储**：DataStore 存储，最多 20 条
- **去重**：重复搜索词只保留一条
- **排序**：最近搜索在前

#### 6.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 搜索历史 | 本地 DataStore（主）+ `GET /api/v1/search/history`（登录后合并同步） | 搜索词列表 |

### 6.5 语音搜索

#### 6.5.1 功能点

| 功能 | 说明 |
|------|------|
| 语音输入 | 按住说话 |
| 语音识别 | 实时语音转文字 |
| 识别结果 | 自动填入搜索框并搜索 |
| 错误处理 | 识别失败提示 |

#### 6.5.2 页面/组件

```
VoiceSearchDialog
├── VoiceAnimation（声波动画）
├── VoiceText（识别中文字）
├── HintText（请说话）
└── CancelButton
```

#### 6.5.3 技术要点

- **语音 SDK**：集成百度/讯飞语音识别 SDK
- **权限**：需要录音权限
- **降噪**：支持环境降噪
- **语言**：支持普通话、方言、英语

#### 6.5.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 语音识别 | 语音 SDK | 语音 → 文字 |

---

## 七、购物车

### 7.1 购物车页

#### 7.1.1 功能点

| 功能 | 说明 |
|------|------|
| 商品列表 | 展示购物车商品 |
| 选择/取消 | 单选/全选 |
| 数量修改 | 增减数量 |
| 删除商品 | 左滑删除/批量删除 |
| 优惠券提示 | 可用优惠券提示 |
| 库存校验 | 库存不足提示 |
| 失效商品 | 失效商品单独分组 |
| 结算 | 选中商品结算 |
| 编辑模式 | 进入编辑模式（删除/移入收藏） |

#### 7.1.2 页面/组件

```
CartScreen
├── CartTopBar
│   ├── Title（购物车 + 数量）
│   └── EditButton（编辑/完成）
├── CartContent
│   ├── ShopGroup（按店铺分组）
│   │   ├── ShopHeader（店铺名 + 优惠券入口）
│   │   └── CartItem
│   │       ├── Checkbox（选择）
│   │       ├── ProductImage
│   │       ├── ProductInfo
│   │       │   ├── ProductTitle
│   │       │   ├── ProductSku（规格）
│   │       │   └── ProductPrice
│   │       └── QuantitySelector（数量选择）
│   └── InvalidGroup（失效商品）
│       └── InvalidItem
├── RecommendSection（推荐商品）
└── CartBottomBar
    ├── SelectAll（全选）
    ├── TotalPrice（合计）
    └── CheckoutButton（结算 + 数量）
```

#### 7.1.3 技术要点

- **本地缓存**：购物车数据本地缓存，支持离线查看
- **同步**：登录后同步服务端购物车数据
- **库存校验**：实时校验库存，库存不足自动标记
- **价格计算**：实时计算选中商品总价
- **编辑模式**：进入编辑模式后底部栏变为删除/移入收藏
- **动画**：数量变化时价格动画过渡

#### 7.1.4 数据依赖

> ⚠️ **P0 契约风险**：后端《android-backend.md》目前**只有 `POST /api/v1/order/cart-checkout`，没有购物车（cart）任何接口**，本节全部接口均为"待契约"，必须在第一阶段与后端闭环，否则第七/八章整体阻塞（见 1.6.2）。

| 数据 | 来源 | 说明 |
|------|------|------|
| 购物车列表 | 接口 `GET /api/v1/cart/list` ⏳待契约 | 按店铺分组 + 失效商品分组 |
| 添加购物车 | 接口 `POST /api/v1/cart/add` ⏳待契约 | SKU + 数量 + `clientRequestId`（幂等） |
| 修改数量 | 接口 `POST /api/v1/cart/update` ⏳待契约 | cartItemId + 数量（**用购物车项 ID，不用商品 ID**，同一 SKU 多规格需区分） |
| 删除商品 | 接口 `POST /api/v1/cart/remove` ⏳待契约 | cartItemId 列表 |
| 勾选状态 | 接口 `POST /api/v1/cart/select` ⏳待契约 | 勾选通常**本地持久化 + 异步同步**，避免每次点击一个请求 |
| 批量操作 | 接口 `POST /api/v1/cart/batch-remove`、`/batch-favorite` ⏳待契约 | 批量删除/移入收藏 |
| 库存校验 | 结算时统一校验；实时逐项 `/cart/stock-check` ⏳待契约（不建议每次加减都请求，弱网体验差） | SKU + 数量 → 库存状态 |
| 可用优惠券 | 接口 `/api/v1/coupon/available` ⏳待契约 | 可用优惠券列表 |
| 推荐商品 | 接口 `GET /api/v1/recommendations/home` | 购物车底部推荐 |

### 7.2 批量操作

#### 7.2.1 功能点

| 功能 | 说明 |
|------|------|
| 批量删除 | 选中多个商品删除 |
| 批量收藏 | 选中多个商品移入收藏 |
| 批量分享 | 选中多个商品分享 |

#### 7.2.2 页面/组件

```
EditModeBottomBar
├── SelectAll（全选）
├── DeleteButton（删除 + 数量）
├── FavoriteButton（移入收藏 + 数量）
└── ShareButton（分享）
```

#### 7.2.3 技术要点

- **多选**：长按进入多选模式，点击切换选中
- **操作确认**：删除前弹窗确认
- **动画**：删除时列表项动画移除

#### 7.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 批量删除 | 接口 `POST /api/v1/cart/batch-remove` ⏳待契约 | cartItemId 列表 |
| 批量收藏 | 接口 `POST /api/v1/cart/batch-favorite` ⏳待契约 | 复用 `POST /api/v1/user/favorites` 批量语义，cartItemId 列表 |

### 7.3 优惠券提示

#### 7.3.1 功能点

| 功能 | 说明 |
|------|------|
| 可用券提示 | 展示可用优惠券数量 |
| 领券入口 | 跳转领券中心 |
| 最优券推荐 | 自动推荐最优优惠券 |

#### 7.3.2 页面/组件

```
CouponTipBar
├── CouponIcon
├── CouponText（可用 X 张优惠券）
├── BestCouponTag（最优券）
└── ArrowIcon
```

#### 7.3.3 技术要点

- **实时计算**：根据购物车商品实时计算可用优惠券
- **最优推荐**：自动选择最优优惠券组合

#### 7.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 可用优惠券 | 接口 `GET /api/v1/coupon/available` ⏳待契约（后端营销中心优惠券接口未开放） | 可用优惠券列表 |

### 7.4 库存校验

#### 7.4.1 功能点

| 功能 | 说明 |
|------|------|
| 实时校验 | 修改数量时校验库存 |
| 库存不足 | 提示库存不足，自动调整为最大库存 |
| 失效商品 | 下架/无库存商品标记为失效 |

#### 7.4.2 页面/组件

```
StockWarning
├── WarningIcon
├── WarningText（库存不足，仅剩 X 件）
└── AdjustButton（调整为最大库存）
```

#### 7.4.3 技术要点

- **实时校验**：数量变化时调用库存校验接口
- **自动调整**：超过库存时自动调整为最大库存
- **失效标记**：下架/无库存商品自动标记

#### 7.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 库存校验 | 结算预览统一校验（`POST /api/v1/order/preview`；错误码 `3002 库存不足` 按 1.6.3 统一处理）；单点 `/api/v1/cart/stock-check` ⏳待契约 | SKU + 数量 → 库存状态 |

---

## 八、下单结算

### 8.1 确认订单页

#### 8.1.1 功能点

| 功能 | 说明 |
|------|------|
| 收货地址 | 选择/新增收货地址 |
| 商品信息 | 展示购买商品 |
| 配送方式 | 选择配送方式 |
| 优惠券 | 选择/使用优惠券 |
| 积分抵扣 | 使用积分抵扣 |
| 订单备注 | 输入订单备注 |
| 价格明细 | 商品总额、运费、优惠、实付 |
| 提交订单 | 提交订单 |

#### 8.1.2 页面/组件

```
CheckoutScreen
├── CheckoutTopBar
│   ├── BackButton
│   └── Title（确认订单）
├── CheckoutContent
│   ├── AddressSection
│   │   ├── AddressCard（收货人 + 电话 + 地址）
│   │   └── AddressSelector（地址选择弹窗）
│   ├── ProductSection
│   │   ├── ShopHeader（店铺名）
│   │   └── ProductItem
│   │       ├── ProductImage
│   │       ├── ProductTitle
│   │       ├── ProductSku
│   │       ├── ProductPrice
│   │       └── ProductQuantity
│   ├── DeliverySection（配送方式）
│   │   └── DeliverySelector
│   ├── CouponSection
│   │   ├── CouponSelector（优惠券选择）
│   │   └── CouponTip（优惠金额）
│   ├── PointsSection（积分抵扣）
│   │   ├── PointsSwitch（是否使用）
│   │   └── PointsInput（使用数量）
│   ├── RemarkSection（订单备注）
│   │   └── RemarkInput
│   └── PriceSection（价格明细）
│       ├── ProductTotal（商品总额）
│       ├── Freight（运费）
│       ├── Discount（优惠金额）
│       └── PointsDiscount（积分抵扣）
└── CheckoutBottomBar
    ├── TotalPrice（实付金额）
    └── SubmitButton（提交订单）
```

#### 8.1.3 技术要点

- **地址选择**：底部弹窗选择地址，支持新增/编辑
- **优惠券**：默认选中服务端推荐的最优券（`availableCoupons`），支持手动切换，切换后重新 `preview`
- **积分抵扣**：以 `availablePoints` 为上限，开关变化即重新 `preview`，不在本地折算金额
- **价格明细**：所有金额字段直接渲染 `POST /api/v1/order/preview` 响应（客户端**不计算**、不累加，避免精度与优惠口径不一致）
- **提交校验**：地址、库存、优惠券有效性由服务端校验，错误码按 1.6.3 映射到对应模块局部刷新
- **防重复提交**：提交按钮加载态锁定 + `clientRequestId` 幂等键，防止弱网重复下单

#### 8.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 订单预览 | 接口 `POST /api/v1/order/preview`（后端 4.1.3） | 入参 `items[{skuId,quantity,shopId}] + addressId + couponId + usePoints`；返回 `totalAmount/discountAmount/couponDiscount/pointsDiscount/freight/payAmount/address/availableCoupons/availablePoints`——**页面价格明细字段全部以该响应为准，客户端不参与金额计算** |
| 地址列表 | 接口 `GET /api/v1/user/addresses` | 收货地址 |
| 可用优惠券 | 接口 `GET /api/v1/coupon/available` ⏳待契约（预览响应已含 `availableCoupons` 时可直接使用） | 可用优惠券 |
| 积分余额 | 接口 `GET /api/v1/user/points` | 积分余额（预览响应的 `availablePoints` 为可用上限） |
| 提交订单 | 接口 `POST /api/v1/order/create` | 订单信息 → 订单 ID（带 `clientRequestId` 防重复下单） |
| 购物车批量结算 | 接口 `POST /api/v1/order/cart-checkout` | 多店铺购物车结算 |

### 8.2 地址选择

#### 8.2.1 功能点

| 功能 | 说明 |
|------|------|
| 地址列表 | 展示收货地址列表 |
| 默认地址 | 标记默认地址 |
| 新增地址 | 新增收货地址 |
| 编辑地址 | 编辑已有地址 |
| 删除地址 | 删除地址 |
| 地址搜索 | 搜索地址 |

#### 8.2.2 页面/组件

```
AddressSelectorSheet
├── SheetHeader（选择地址 + 关闭）
├── AddressList
│   ├── AddressItem
│   │   ├── AddressInfo（收货人 + 电话 + 地址）
│   │   ├── DefaultTag（默认标签）
│   │   └── SelectIcon（选中图标）
│   └── AddressItem
└── AddAddressButton（新增地址）
```

#### 8.2.3 技术要点

- **默认地址**：自动选中默认地址
- **地址校验**：手机号、地址格式校验
- **三级联动**：省市区三级联动选择

#### 8.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 地址列表 | 接口 `GET /api/v1/user/addresses`（后端 5.2.2） | 收货地址 |
| 新增地址 | 接口 `POST /api/v1/user/addresses` | 姓名/手机号/省市区/详细地址/经纬度/isDefault |
| 编辑地址 | 接口 `PUT /api/v1/user/addresses/{addressId}` | 地址 ID + 信息 |
| 删除地址 | 接口 `DELETE /api/v1/user/addresses/{addressId}` | 地址 ID |
| 设置默认地址 | 接口 `POST /api/v1/user/addresses/{addressId}/default` | 默认地址（原前端缺失，补入） |
| 省市区 | 随包内置 JSON（后端无 `/api/v1/region/list`，降级为内置；定位解析走 `GET /api/v1/user/address/resolve`） | 省市区数据 |

### 8.3 支付方式

#### 8.3.1 功能点

| 功能 | 说明 |
|------|------|
| 支付方式 | 支付宝、微信支付、银行卡、货到付款 |
| 支付密码 | 输入支付密码 |
| 指纹支付 | 指纹验证支付 |
| 支付结果 | 支付成功/失败处理 |

#### 8.3.2 页面/组件

```
PaymentScreen
├── PaymentTopBar
│   ├── BackButton
│   └── Title（收银台）
├── OrderInfo
│   ├── OrderAmount（订单金额）
│   └── OrderNumber（订单号）
├── PaymentMethods
│   ├── AlipayOption
│   ├── WeChatOption
│   ├── BankCardOption
│   └── CODOption（货到付款）
├── PaymentPasswordInput（支付密码）
└── PayButton（确认支付）
```

#### 8.3.3 技术要点

- **支付 SDK**：集成支付宝、微信支付 SDK（需商户资质与应用签名审核，列入 19.3 风险）
- **支付密码**：6 位数字密码，错误 5 次锁定；密码经服务端公钥加密后传 `payPassword` 字段，禁止明文与日志
- **指纹支付**：走 `POST /api/v1/payment/biometric-pay`
- **结果确认**：轮询 `GET /api/v1/payment/{paymentId}`；用户从三方 App 返回时立即查询一次
- **异常处理**：拉起三方 SDK 失败/未安装 → 引导下载或换渠道；用户取消支付 → 回待付款订单，不产生脏状态
- **安全**：支付页 `FLAG_SECURE` 防截屏；金额以服务端 `payAmount` 为准；防串单（paymentId 与 orderId 绑定校验）

#### 8.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 发起支付 | 接口 `POST /api/v1/payment/create`（后端 4.2.3） | 入参 `orderId/payChannel(alipay\|wechat\|unionpay\|balance)/payPassword(加密)/useBalance`；返回 `paymentId/payAmount/payParams`（各渠道拉起参数） |
| 支付结果 | 接口 `GET /api/v1/payment/{paymentId}` | 轮询 1s→3s→5s→10s 退避，30s 未决转后台查询并展示"支付结果确认中" |
| 支付密码 | 接口 `POST /api/v1/payment/verify-password` | 6 位密码校验 |
| 生物识别支付 | 接口 `POST /api/v1/payment/biometric-pay` | 指纹/人脸确认 |

### 8.4 订单备注

#### 8.4.1 功能点

| 功能 | 说明 |
|------|------|
| 备注输入 | 输入订单备注 |
| 常用备注 | 快捷选择常用备注 |
| 字数限制 | 最多 50 字 |

#### 8.4.2 页面/组件

```
RemarkInput
├── RemarkTextField（多行输入）
├── CharacterCount（字数统计）
└── QuickRemarks（常用备注标签）
    ├── Tag（放快递柜）
    ├── Tag（送货上门）
    └── Tag（工作日送货）
```

#### 8.4.3 技术要点

- **字数限制**：最多 50 字，实时统计
- **常用备注**：本地存储常用备注

#### 8.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 常用备注 | 本地 DataStore | 常用备注列表 |

---

## 九、订单管理

### 9.1 订单列表

#### 9.1.1 功能点

| 功能 | 说明 |
|------|------|
| 订单状态 | 全部/待付款/待发货/待收货/待评价/已完成/已取消（退款中 4 / 已退款 5 并入售后入口展示，已关闭 7 按需并入已取消，**Tab↔枚举映射以 `android-backend.md` 4.3.4 为准**） |
| 订单卡片 | 展示订单信息 |
| 订单操作 | 取消订单、确认收货、评价、售后 |
| 下拉刷新 | 刷新订单列表 |
| 加载更多 | 分页加载 |

#### 9.1.2 页面/组件

```
OrderListScreen
├── OrderTopBar
│   ├── BackButton
│   └── Title（我的订单）
├── OrderTabs（状态标签）
│   ├── Tab（全部）
│   ├── Tab（待付款）
│   ├── Tab（待发货）
│   ├── Tab（待收货）
│   ├── Tab（待评价）
│   └── Tab（已完成）
├── OrderList
│   ├── OrderCard
│   │   ├── OrderHeader（店铺名 + 状态）
│   │   ├── ProductItem
│   │   │   ├── ProductImage
│   │   │   ├── ProductTitle
│   │   │   ├── ProductSku
│   │   │   └── ProductQuantity
│   │   ├── OrderFooter（合计 + 数量）
│   │   └── OrderActions（操作按钮）
│   │       ├── CancelButton（取消订单）
│   │       ├── PayButton（去支付）
│   │       ├── ConfirmButton（确认收货）
│   │       ├── ReviewButton（去评价）
│   │       └── AfterSaleButton（售后）
│   └── OrderCard
└── EmptyOrder（空订单）
```

#### 9.1.3 技术要点

- **状态筛选**：Tab 切换筛选订单状态
- **操作按钮**：根据订单状态显示不同操作
- **下拉刷新**：刷新当前状态订单
- **加载更多**：分页加载订单

#### 9.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 订单列表 | 接口 `GET /api/v1/orders`（后端 4.3.2 / 4.3.4） | `status=` 只接受枚举名 `all\|unpaid\|paid\|shipped\|completed\|cancelled\|refunding\|refunded\|closed`（**不接受 `pending` 等自造值**，非法值按 1001 参数错误）；8 值状态机与 `admin-backend.md` 4.3 同源（0待付款/1待发货/2待收货/3已完成/4退款中/5已退款/6已取消/7已关闭）；**「待评价」不是订单状态**，用 `status=completed&reviewed=false`；`statusText/actions` 由后端下发，Tab 与枚举映射收敛在一处 |
| 取消订单 | 接口 `POST /api/v1/orders/{orderId}/cancel` | 订单 ID + 取消原因 |
| 确认收货 | 接口 `POST /api/v1/orders/{orderId}/confirm` | 订单 ID |
| 删除订单 | 接口 `DELETE /api/v1/orders/{orderId}` | 订单 ID |
| 再次购买 | 接口 `POST /api/v1/orders/{orderId}/rebuy` | 补入（原前端缺失）→ 加购后跳购物车 |

### 9.2 订单详情

#### 9.2.1 功能点

| 功能 | 说明 |
|------|------|
| 订单状态 | 展示订单当前状态 |
| 收货地址 | 展示收货地址 |
| 商品信息 | 展示订单商品 |
| 价格明细 | 商品总额、运费、优惠、实付 |
| 订单信息 | 订单号、创建时间、支付时间 |
| 物流信息 | 查看物流轨迹 |
| 订单操作 | 取消、支付、确认收货、评价、售后 |

#### 9.2.2 页面/组件

```
OrderDetailScreen
├── OrderTopBar
│   ├── BackButton
│   └── Title（订单详情）
├── OrderContent
│   ├── StatusSection（订单状态）
│   │   ├── StatusIcon
│   │   ├── StatusText
│   │   └── StatusDesc
│   ├── AddressSection（收货地址）
│   │   ├── AddressInfo
│   │   └── CopyButton
│   ├── ProductSection（商品信息）
│   │   └── ProductItem
│   ├── PriceSection（价格明细）
│   │   ├── ProductTotal
│   │   ├── Freight
│   │   ├── Discount
│   │   └── TotalPrice
│   ├── InfoSection（订单信息）
│   │   ├── OrderNumber
│   │   ├── CreateTime
│   │   ├── PayTime
│   │   └── PayMethod
│   └── LogisticsSection（物流信息）
│       └── LogisticsButton
└── OrderBottomBar
    └── OrderActions（操作按钮）
```

#### 9.2.3 技术要点

- **状态展示**：根据订单状态展示不同 UI
- **时间线**：订单状态时间线展示
- **操作按钮**：根据订单状态显示不同操作

#### 9.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 订单详情 | 接口 `GET /api/v1/orders/{orderId}` | 订单 ID → 详情 |
| 物流信息 | 接口 `GET /api/v1/logistics/{orderId}` | 订单 ID → 物流轨迹 |

### 9.3 物流跟踪

#### 9.3.1 功能点

| 功能 | 说明 |
|------|------|
| 物流轨迹 | 展示物流节点 |
| 物流地图 | 展示物流地图（可选） |
| 快递员信息 | 快递员姓名、电话 |
| 物流订阅 | 物流状态变更通知 |

#### 9.3.2 页面/组件

```
LogisticsScreen
├── LogisticsTopBar
│   ├── BackButton
│   └── Title（物流详情）
├── LogisticsInfo
│   ├── CompanyName（物流公司）
│   ├── TrackingNumber（运单号）
│   └── CopyButton
├── LogisticsTimeline（物流时间线）
│   ├── TimelineItem
│   │   ├── TimePoint
│   │   ├── StatusText
│   │   └── Description
│   └── TimelineItem
└── CourierInfo（快递员信息）
    ├── CourierName
    └── CourierPhone
```

#### 9.3.3 技术要点

- **时间线**：垂直时间线展示物流节点
- **状态订阅**：推送通知物流状态变更
- **地图展示**：可选展示物流地图

#### 9.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 物流轨迹 | 接口 `GET /api/v1/logistics/{orderId}`（**按订单号查询，非运单号**——参数语义与原表述不同） | 轨迹节点 |
| 物流详情 | 接口 `GET /api/v1/logistics/{orderId}/detail` | 详情 |
| 配送员信息 | 接口 `GET /api/v1/logistics/{orderId}/courier` | 姓名、电话（拨打走 `Intent.ACTION_DIAL`，不直接外呼） |

### 9.4 确认收货

#### 9.4.1 功能点

| 功能 | 说明 |
|------|------|
| 确认收货 | 确认已收到商品 |
| 验证方式 | 轻量二次确认弹窗；仅当用户开启支付密码且订单金额较大时走支付密码验证（避免过度设计拖累转化） |
| 自动确认 | 发货后 15 天自动确认收货（与 9.4.3 一致） |

#### 9.4.2 页面/组件

```
ConfirmReceiveDialog
├── DialogTitle（确认收货）
├── DialogContent（确认已收到商品？）
├── PasswordInput（支付密码）
└── ActionButtons（取消 + 确认）
```

#### 9.4.3 技术要点

- **二次验证**：默认确认弹窗；满足"开启支付密码 + 大额"条件时才走 `/api/v1/payment/verify-password`
- **自动确认**：发货后 15 天自动确认收货（服务端定时任务，客户端仅展示倒计时）

#### 9.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 确认收货 | 接口 `POST /api/v1/orders/{orderId}/confirm` | 订单 ID（默认二次确认弹窗即可） |

### 9.5 售后

#### 9.5.1 功能点

| 功能 | 说明 |
|------|------|
| 申请售后 | 退货/退款/换货 |
| 售后进度 | 查看售后处理进度 |
| 售后详情 | 售后详情页 |
| 取消售后 | 取消售后申请 |

#### 9.5.2 页面/组件

```
AfterSaleScreen
├── AfterSaleTopBar
│   ├── BackButton
│   └── Title（售后）
├── AfterSaleType（售后类型）
│   ├── RefundOnly（仅退款）
│   ├── ReturnRefund（退货退款）
│   └── Exchange（换货）
├── AfterSaleReason（售后原因）
│   └── ReasonSelector
├── AfterSaleAmount（退款金额）
│   └── AmountInput
├── AfterSaleDescription（问题描述）
│   └── DescriptionInput
├── AfterSaleImages（上传图片）
│   └── ImageUploader
└── SubmitButton（提交申请）
```

#### 9.5.3 技术要点

- **售后类型**：仅退款、退货退款、换货
- **图片上传**：支持多张图片上传
- **进度跟踪**：实时更新售后处理进度

#### 9.5.4 数据依赖

> ⚠️ 后端《android-backend.md》16.4 已把售后登记为 **P1 契约缺口**（拟定路径 `/api/v1/aftersale/{create,list,{id},cancel,return-logistics}`，第 9-12 周交付），本节仍为"待契约"（见 1.6.2 P1）；**售后状态须与后端 4.3.4 订单状态机的 4 退款中 / 5 已退款 / 7 已关闭 联动**，前端不得自建售后状态枚举。注意 `apply` vs 后端拟定的 `create` 命名需在契约评审时二选一。

| 数据 | 来源 | 说明 |
|------|------|------|
| 申请售后 | 接口 `POST /api/v1/aftersale/apply` ⏳待契约（后端 16.4 拟定名 `create`，路径命名待契约评审统一） | 订单 ID + 类型（仅退款/退货退款/换货）+ 原因 + 金额 + 凭证 |
| 售后列表 | 接口 `GET /api/v1/aftersale/list` ⏳待契约 | 原文档缺失列表页接口，订单详情"售后"入口需要 |
| 售后详情 | 接口 `GET /api/v1/aftersale/{id}` ⏳待契约 | 售后 ID → 详情与进度 |
| 取消售后 | 接口 `POST /api/v1/aftersale/{id}/cancel` ⏳待契约 | 售后 ID |
| 退货物流寄回 | 接口 `POST /api/v1/aftersale/{id}/return-logistics` ⏳待契约（后端 16.4 已列入 aftersale 契约，原前端缺失） | 运单号 + 物流公司 + 凭证图片（退货退款/换货场景） |
| 凭证图片上传 | 接口 `POST /api/v1/file/upload` ⏳待契约（P0） | 多图上传，返回 URL 列表 |

### 9.6 发表评价（新增：原 9.1 有"去评价"按钮却无对应模块与接口，属闭环缺口）

#### 9.6.1 功能点

| 功能 | 说明 |
|------|------|
| 评分 | 1-5 星 + 文字/图片评价 |
| 评价标签 | 服务端下发的快捷标签（如"做工精细""色差小"） |
| 晒图 | 最多 9 张，先压缩后上传 |
| 匿名评价 | 可选匿名 |
| 评价入口 | 订单列表"去评价"、订单详情；已评价订单跳详情 |

#### 9.6.2 技术要点

- **资格校验**：仅 `completed`（已完成）且 `reviewed=false` 的订单可评价，服务端二次校验防绕过（后端 16.4 口径）
- **草稿保护**：评价内容本地暂存，误退出不丢失
- **防刷**：同订单同商品仅一次有效评价；图片先本地压缩（长边 ≤1600）再上传
- **回流**：评价成功后刷新订单状态与商品评价数

#### 9.6.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 提交评价 | 接口 `POST /api/v1/orders/{orderId}/review` ⏳待契约（后端 16.4 拟定为**订单维度**：订单 `completed` 且 `reviewed=false` 时方可提交；原 `POST /api/v1/products/{spuId}/reviews` 与后端不符，后端仅有 GET 评价列表） | 评分 + 内容 + 图片 + 匿名标记 |
| 晒图上传 | 接口 `POST /api/v1/file/upload` ⏳待契约 | 多图上传 |

---

## 十、个人中心

### 10.1 个人信息

#### 10.1.1 功能点

| 功能 | 说明 |
|------|------|
| 头像 | 修改头像 |
| 昵称 | 修改昵称 |
| 性别 | 修改性别 |
| 生日 | 修改生日 |
| 个性签名 | 修改个性签名 |

#### 10.1.2 页面/组件

```
ProfileScreen
├── ProfileHeader
│   ├── Avatar（头像）
│   ├── Nickname（昵称）
│   └── EditButton（编辑）
├── ProfileStats
│   ├── FavoriteCount（收藏）
│   ├── CouponCount（优惠券）
│   ├── PointsCount（积分）
│   └── FootprintCount（足迹）
├── ProfileMenu
│   ├── AddressMenu（收货地址）
│   ├── SecurityMenu（账户安全）
│   ├── NotificationMenu（消息通知）
│   ├── PrivacyMenu（隐私设置）
│   └── SettingsMenu（设置）
└── ProfileActions
    ├── ShareApp（分享App）
    ├── CustomerService（联系客服）
    └── Logout（退出登录）
```

#### 10.1.3 技术要点

- **头像上传**：拍照/相册选择，图片裁剪
- **信息编辑**：弹窗编辑个人信息
- **数据统计**：实时统计用户数据

#### 10.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 用户信息 | 接口 `GET /api/v1/user/profile`（后端 5.1.2） | 用户基本信息 |
| 修改头像 | 接口 `POST /api/v1/user/avatar` | 上传头像（依赖 `/api/v1/file/upload` ⏳待契约） |
| 修改信息 | 接口 `PUT /api/v1/user/profile` | 昵称/性别/生日/签名 |
| 用户统计 | 接口 `GET /api/v1/user/stats` ⏳待契约（缺失时各项数字分别由 favorites/coupon/points/recommendations-history 接口拼装） | 收藏/优惠券/积分/足迹 |

### 10.2 收货地址

#### 10.2.1 功能点

| 功能 | 说明 |
|------|------|
| 地址列表 | 展示收货地址 |
| 新增地址 | 新增收货地址 |
| 编辑地址 | 编辑已有地址 |
| 删除地址 | 删除地址 |
| 默认地址 | 设置默认地址 |
| 地址搜索 | 搜索地址 |

#### 10.2.2 页面/组件

```
AddressListScreen
├── AddressTopBar
│   ├── BackButton
│   ├── Title（收货地址）
│   └── AddButton
├── AddressList
│   ├── AddressItem
│   │   ├── AddressInfo（收货人 + 电话 + 地址）
│   │   ├── DefaultTag（默认标签）
│   │   └── Actions（编辑 + 删除）
│   └── AddressItem
└── EmptyAddress（空地址）
```

#### 10.2.3 技术要点

- **三级联动**：省市区三级联动选择
- **地址解析**：智能解析地址文本
- **默认地址**：只能有一个默认地址

#### 10.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 地址列表 | 接口 `GET /api/v1/user/addresses`（后端 5.2.2） | 收货地址 |
| 新增地址 | 接口 `POST /api/v1/user/addresses` | 姓名/手机号/省市区/详细地址/经纬度/isDefault |
| 编辑地址 | 接口 `PUT /api/v1/user/addresses/{addressId}` | 地址 ID + 信息 |
| 删除地址 | 接口 `DELETE /api/v1/user/addresses/{addressId}` | 地址 ID |
| 设置默认地址 | 接口 `POST /api/v1/user/addresses/{addressId}/default` | 默认地址（原前端缺失，补入） |
| 省市区 | 随包内置 JSON（后端无 `/api/v1/region/list`，降级为内置；定位解析走 `GET /api/v1/user/address/resolve`） | 省市区数据 |

### 10.3 我的收藏

#### 10.3.1 功能点

| 功能 | 说明 |
|------|------|
| 收藏列表 | 展示收藏商品 |
| 收藏分类 | 按店铺/商品分类 |
| 取消收藏 | 左滑取消收藏 |
| 降价提醒 | 收藏商品降价通知 |
| 批量管理 | 批量取消收藏 |

#### 10.3.2 页面/组件

```
FavoriteScreen
├── FavoriteTabs（商品/店铺）
├── FavoriteList
│   ├── FavoriteItem
│   │   ├── ProductImage
│   │   ├── ProductTitle
│   │   ├── ProductPrice
│   │   └── PriceDropTag（降价标签）
│   └── FavoriteItem
└── SwipeToDismiss（左滑取消收藏）
```

#### 10.3.3 技术要点

- **左滑操作**：`SwipeToDismiss` 组件
- **批量操作**：长按进入多选模式
- **降价监控**：后台定时检查收藏商品价格变化

#### 10.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 收藏列表 | 接口 `GET /api/v1/user/favorites`（后端 5.3.2） | 分页加载 |
| 取消收藏 | 接口 `DELETE /api/v1/user/favorites/{spuId}` | 商品 ID |
| 批量取消 | 接口 `POST /api/v1/user/favorites/batch-delete` | 批量管理 |

### 10.4 我的优惠券

#### 10.4.1 功能点

| 功能 | 说明 |
|------|------|
| 优惠券列表 | 展示优惠券 |
| 优惠券分类 | 未使用/已使用/已过期 |
| 领券中心 | 跳转领券中心 |
| 优惠券详情 | 查看优惠券详情 |

#### 10.4.2 页面/组件

```
CouponScreen
├── CouponTabs（未使用/已使用/已过期）
├── CouponList
│   ├── CouponItem
│   │   ├── CouponAmount（优惠金额）
│   │   ├── CouponName（优惠券名称）
│   │   ├── CouponCondition（使用条件）
│   │   ├── CouponPeriod（有效期）
│   │   └── UseButton（去使用）
│   └── CouponItem
└── EmptyCoupon（空优惠券）
```

#### 10.4.3 技术要点

- **状态区分**：未使用/已使用/已过期
- **使用引导**：点击"去使用"跳转商品列表
- **过期提醒**：优惠券即将过期提醒

#### 10.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 优惠券列表 | 接口 `GET /api/v1/coupon/list` ⏳待契约（后端 16.4 仅登记了 `available` 与 `receive`，**`list` 属"两边都没有"，须在第 1 周契约评审补登记**，否则降级为只用 `available` 拼装） | 状态 + 分页 |
| 领券 | 接口 `POST /api/v1/coupon/{couponId}/receive` ⏳待契约（对齐后端 16.4 拟定路径，原 `/coupon/receive` 无 ID 段） | 优惠券 ID（领券中心入口在 7.3，属同一套接口） |

### 10.5 积分

#### 10.5.1 功能点

| 功能 | 说明 |
|------|------|
| 积分余额 | 展示积分余额 |
| 积分明细 | 积分收支记录 |
| 积分兑换 | 积分兑换商品/优惠券 |
| 积分任务 | 做任务赚积分 |

#### 10.5.2 页面/组件

```
PointsScreen
├── PointsHeader
│   ├── PointsBalance（积分余额）
│   └── PointsRules（积分规则）
├── PointsTabs（明细/兑换/任务）
├── PointsList
│   ├── PointsItem
│   │   ├── PointsChange（积分变化）
│   │   ├── PointsDesc（描述）
│   │   └── PointsTime（时间）
│   └── PointsItem
└── PointsExchange（积分兑换）
    └── ExchangeItem
```

#### 10.5.3 技术要点

- **积分明细**：分页加载积分收支记录
- **积分兑换**：积分兑换商品/优惠券
- **积分任务**：签到、评价、分享等任务

#### 10.5.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 积分余额 | 接口 `GET /api/v1/user/points`（后端 5.4.2） | 积分余额（**全文统一此路径**，修正原文 `/api/user/points` 与 `/api/points/*` 两套并存的自相矛盾） |
| 积分明细 | 接口 `GET /api/v1/user/points/records` | 分页加载 |
| 积分兑换 | 接口 `POST /api/v1/user/points/exchange` | 积分 + 商品 ID |
| 每日签到 | 接口 `POST /api/v1/user/points/sign` | 签到任务（原前端缺失，补入） |
| 积分任务 | 接口 `GET /api/v1/user/points/tasks` ⏳待契约 | 任务列表 |

### 10.6 设置

#### 10.6.1 功能点

| 功能 | 说明 |
|------|------|
| 账号安全 | 修改密码、绑定手机、生物识别开关 |
| 隐私设置 | 个性化推荐开关、广告标识（关闭后仅不个性化，不降功能）、权限管理入口 |
| 通知设置 | 推送通知开关、免打扰（详见 11.5） |
| 通用设置 | 外观（深色模式：跟随系统/浅色/深色）、字体大小、语言（简体中文/英文）、清除缓存 |
| 关于 | 版本信息、检查更新、用户协议、隐私政策、ICP 备案信息、**第三方 SDK 清单与采集说明**（监管强制） |
| 账户注销 | 注销入口（校验无未完成订单/售后，对应 3.4；应用商店与监管强制要求） |
| 退出登录 | 退出当前账号（调 `POST /api/v1/auth/logout` + 本地清理，见 3.5） |

#### 10.6.2 页面/组件

```
SettingsScreen
├── SettingsTopBar
│   ├── BackButton
│   └── Title（设置）
├── SettingsList
│   ├── AccountSection
│   │   ├── ChangePassword（修改密码）
│   │   ├── BindPhone（绑定手机）
│   │   └── BiometricLogin（生物识别）
│   ├── PrivacySection
│   │   ├── PersonalizedRecommend（个性化推荐）
│   │   ├── LocationService（位置服务）
│   │   └── AdTracking（广告追踪）
│   ├── NotificationSection
│   │   ├── PushNotification（推送通知）
│   │   └── NotificationSettings（通知设置）
│   ├── GeneralSection
│   │   ├── FontSize（字体大小）
│   │   ├── ClearCache（清除缓存）
│   │   └── Language（语言）
│   └── AboutSection
│       ├── Version（版本信息）
│       ├── UserAgreement（用户协议）
│       ├── PrivacyPolicy（隐私政策）
│       └── CheckUpdate（检查更新）
└── LogoutButton（退出登录）
```

#### 10.6.3 技术要点

- **缓存清理**：清理图片缓存、数据缓存
- **版本检测**：检查新版本
- **退出登录**：清除本地数据，跳转登录页

#### 10.6.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 修改密码 | 接口 `POST /api/v1/user/password/change` ⏳待契约 | 旧密码 + 新密码 |
| 换绑手机 | 接口 `POST /api/v1/user/phone/bind` ⏳待契约 | 新手机号 + 验证码 |
| 版本检测 | 接口 `GET /api/v1/update/check`（后端 10.1.2） | 版本信息（与 2.1 同一接口） |
| 用户协议 | 接口 `/api/v1/agreement/user` ⏳待契约 | 协议内容 |
| 隐私政策 | 接口 `/api/v1/agreement/privacy` ⏳待契约 | 隐私政策 |

### 10.7 浏览足迹（新增：个人中心有入口、无模块，属闭环缺口）

#### 10.7.1 功能点

| 功能 | 说明 |
|------|------|
| 足迹列表 | 最近浏览商品，按时间倒序分组 |
| 清空足迹 | 单条删除、全部清空 |
| 个性化开关 | 用户关闭个性化推荐时，足迹仅本地可见 |
| 降价标记 | 足迹中收藏商品带降价标签 |

#### 10.7.2 技术要点

- **本地 + 服务端双写**：浏览时先写本地 Room（保证秒开），登录态下异步同步服务端
- **去重与上限**：按 spuId 去重，本地保留最近 200 条
- **合规**：足迹属个人信息，隐私政策须明示；注销账号时一并清除（3.4）

#### 10.7.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 足迹列表 | 接口 `GET /api/v1/recommendations/history`（后端 3.4.2） | 分页加载 |
| 清空足迹 | 本地清空 + 服务端 `DELETE /api/v1/recommendations/history` ⏳待契约 | 后端缓存 `browse:user:{userId}` 需提供清理入口 |

---

## 十一、消息通知

### 11.1 消息列表

#### 11.1.1 功能点

| 功能 | 说明 |
|------|------|
| 消息分类 | 系统通知、物流提醒、促销推送、客服消息 |
| 消息列表 | 展示消息列表 |
| 未读标记 | 未读消息标记 |
| 消息操作 | 删除、标记已读 |
| 消息详情 | 查看消息详情 |

#### 11.1.2 页面/组件

```
MessageScreen
├── MessageTopBar
│   ├── BackButton
│   ├── Title（消息）
│   └── MarkAllRead（全部已读）
├── MessageTabs（消息分类）
│   ├── Tab（全部）
│   ├── Tab（系统通知）
│   ├── Tab（物流提醒）
│   ├── Tab（促销推送）
│   └── Tab（客服消息）
├── MessageList
│   ├── MessageItem
│   │   ├── MessageIcon
│   │   ├── MessageTitle
│   │   ├── MessageContent
│   │   ├── MessageTime
│   │   └── UnreadDot（未读标记）
│   └── MessageItem
└── EmptyMessage（空消息）
```

#### 11.1.3 技术要点

- **消息分类**：按类型分类展示
- **未读标记**：未读消息显示红点
- **消息推送**：集成 FCM + 厂商推送通道
- **消息同步**：多端消息同步

#### 11.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 消息列表 | 接口 `GET /api/v1/notifications`（后端 6.2.2） | `type=order\|logistics\|system\|promotion` + 分页；后端 6.2.3 已返回 `deeplink`，点击统一走 1.4.4 |
| 消息详情 | 接口 `GET /api/v1/notifications/{id}` | 消息 ID → 详情 |
| 标记已读 | 接口 `POST /api/v1/notifications/{id}/read` | 消息 ID |
| 全部已读 | 接口 `POST /api/v1/notifications/read-all` | 原前端缺失，补入 |
| 删除消息 | 接口 `DELETE /api/v1/notifications/{id}` | 消息 ID |
| 未读数 | 接口 `GET /api/v1/notifications/unread-count` | 未读消息数（首页角标复用） |

### 11.2 系统通知

#### 11.2.1 功能点

| 功能 | 说明 |
|------|------|
| 系统公告 | 平台公告、系统维护通知 |
| 活动通知 | 促销活动通知 |
| 安全通知 | 登录提醒、密码修改提醒 |

#### 11.2.2 页面/组件

```
SystemNotificationItem
├── NotificationIcon
├── NotificationTitle
├── NotificationContent
├── NotificationTime
└── UnreadDot
```

#### 11.2.3 技术要点

- **富文本**：支持富文本消息内容
- **跳转**：点击消息跳转对应页面

#### 11.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 系统通知 | 接口 `GET /api/v1/notifications?type=system` | 分类查询统一走通知列表接口，不再单设 `/api/message/system` |

### 11.3 物流提醒

#### 11.3.1 功能点

| 功能 | 说明 |
|------|------|
| 发货提醒 | 订单发货通知 |
| 派送提醒 | 订单派送通知 |
| 签收提醒 | 订单签收通知 |
| 物流异常 | 物流异常通知 |

#### 11.3.2 页面/组件

```
LogisticsNotificationItem
├── LogisticsIcon
├── LogisticsTitle（您的订单已发货）
├── LogisticsContent（物流公司 + 运单号）
├── LogisticsTime
└── ViewLogisticsButton（查看物流）
```

#### 11.3.3 技术要点

- **物流跳转**：点击跳转物流详情页
- **状态同步**：物流状态实时同步

#### 11.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 物流通知 | 接口 `GET /api/v1/notifications?type=logistics` | 统一通知列表接口（点击带 `deeplink` 跳物流详情，见 9.3） |

### 11.4 促销推送

#### 11.4.1 功能点

| 功能 | 说明 |
|------|------|
| 促销活动 | 促销活动通知 |
| 优惠券到期 | 优惠券即将过期提醒 |
| 降价提醒 | 收藏商品降价通知 |
| 个性化推荐 | 基于用户偏好的推荐 |

#### 11.4.2 页面/组件

```
PromotionNotificationItem
├── PromotionIcon
├── PromotionTitle
├── PromotionContent
├── PromotionTime
└── ViewPromotionButton（查看活动）
```

#### 11.4.3 技术要点

- **个性化**：基于用户行为推送个性化内容
- **频率控制**：避免过度推送

#### 11.4.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 促销通知 | 接口 `GET /api/v1/notifications?type=promotion` | 统一通知列表接口；频控由服务端负责（后端 6.1.4：单用户单类每日 ≤5 条、22:00-08:00 免打扰） |

### 11.5 推送配置

#### 11.5.1 功能点

| 功能 | 说明 |
|------|------|
| 推送开关 | 开启/关闭推送 |
| 分类开关 | 按类型开启/关闭推送 |
| 免打扰 | 设置免打扰时间段 |
| 推送渠道 | FCM + 厂商通道 |

#### 11.5.2 页面/组件

```
NotificationSettingsScreen
├── SettingsTopBar
│   ├── BackButton
│   └── Title（通知设置）
├── SettingsList
│   ├── PushSwitch（推送通知）
│   ├── SystemSwitch（系统通知）
│   ├── LogisticsSwitch（物流提醒）
│   ├── PromotionSwitch（促销推送）
│   ├── DoNotDisturb（免打扰）
│   │   ├── DndSwitch
│   │   └── DndTimePicker（时间段）
│   └── ChannelSettings（推送渠道）
│       ├── FcmChannel
│       └── VendorChannel（厂商通道）
└── SaveButton（保存设置）
```

#### 11.5.3 技术要点

- **推送通道**：集成 FCM + 厂商推送（华为/小米/OPPO/vivo）
- **通道选择**：优先使用厂商通道，FCM 作为兜底
- **免打扰**：夜间免打扰，静默接收推送
- **权限适配**：Android 13+ 需要通知权限

#### 11.5.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 读取推送设置 | 接口 `GET /api/v1/notifications/settings`（后端 6.2.2，单接口 GET/PUT） | 分类开关、免打扰时段 |
| 保存推送设置 | 接口 `PUT /api/v1/notifications/settings` | 保存设置 |
| 推送 Token 上报 | 接口 `POST /api/v1/device/register`（含 `pushToken`/`pushChannel`，后端 2.3.3） | Token 变化（卸载重装、定期轮换）时重新上报 |

### 11.6 推送通道落地（新增：原文只有"FCM + 厂商"一句，无法实施）

#### 11.6.1 通道选型与判定

| 场景 | 通道 | 说明 |
|------|------|------|
| 海外设备（有 GMS） | FCM | 高优先级消息 |
| 国内无 GMS 设备 | 厂商通道 | 启动时检测 `GoogleApiAvailability`，无 GMS 立即判定厂商 |
| 华为/荣耀 | 华为 Push Kit（荣耀需单独接入荣耀 Push） | 长尾遗漏点，须单列 |
| 小米/红米 | Mi Push | |
| OPPO/一加/realme | OPPO Push（需企业资质与审核） | 审核周期列入 19.3 风险 |
| vivo/iQOO | vivo Push | |
| 三星/魅族等 | 三星 FCM 兼容 + 魅族 Push（量小时仅 FCM 兜底） | 按 DAU 占比排优先级 |
| 全部兜底 | 应用内长连接/轮询未读数 | 厂商通道配额用尽或全失败时 |

- 优先级策略对齐后端 6.1.3：订单/物流走厂商高优通道，促销走 FCM 普通优先级
- **多通道 Token 管理**：每台设备同时上报 `channel + token`，厂商 Token 失效（`onTokenRefresh`/返回码 7000xxx）立即重新注册并回传
- **送达统计**：利用后端 `push_record` 表统计到达/点击，客户端上报点击与展示事件

#### 11.6.2 通知展示与点击

- 通知渠道按后端 `type` 拆分（order/logistics/system/promotion），各自可独立关闭（Android 8.0+ 必须建 Channel，否则静默丢弃）
- Android 13+ 首次开启物流提醒/下单成功时机申请 `POST_NOTIFICATIONS`（见 2.3.2）
- 富媒体推送（大图/按钮，后端 6.1.1）：自定义 Notification + `RemoteViews`，点击统一路由 `DeepLinkDispatcher`（1.4.4），冷启动走暂存回放
- 应用在前台：静默合并到站内信，不弹通知
- 秒杀开始等确定性事件可用本地通知兜底（不依赖通道）

#### 11.6.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 推送配置 | 接口 `/api/v1/push/config` ⏳待契约 | 通道降级开关 |
| Token 上报 | `POST /api/v1/device/register` | 见 11.5.4 |

---

## 十二、客服系统

### 12.1 在线客服

#### 12.1.1 功能点

| 功能 | 说明 |
|------|------|
| 智能客服 | 机器人自动回复 |
| 人工客服 | 转接人工客服 |
| 常见问题 | 常见问题快捷入口 |
| 商品咨询 | 咨询当前浏览商品 |
| 订单咨询 | 咨询订单问题 |

#### 12.1.2 页面/组件

```
CustomerServiceScreen
├── ServiceTopBar
│   ├── BackButton
│   └── Title（客服）
├── ServiceContent
│   ├── QuickQuestions（常见问题）
│   │   └── QuestionTag
│   ├── ServiceOptions
│   │   ├── SmartService（智能客服）
│   │   ├── HumanService（人工客服）
│   │   └── OrderService（订单客服）
│   └── RecentContacts（最近联系）
│       └── ContactItem
└── ServiceBottomBar
    └── InputBar（输入框 + 发送）
```

#### 12.1.3 技术要点

- **WebSocket**：实时消息通信；连接由 `library-cs` 单例管理——握手带 Access Token、30s 心跳、断线指数退避重连（1s→32s）、进程后台即断、回前台立即重连并拉取离线消息（`GET /api/v1/cs/session/{sessionId}/messages?after={msgId}`）
- **消息类型**：文本、图片、商品卡片、订单卡片（图片先走 `/api/v1/file/upload` ⏳待契约）
- **消息可靠性**：本地先落库（Room）+ 待发送队列 + 客户端消息 ID 去重，弱网不丢不重
- **智能回复**：机器人会话（`type=robot`）；服务端判定转人工
- **转人工**：`GET /api/v1/cs/status` 查询坐席在线状态，离线引导提交工单（12.2）

#### 12.1.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 创建会话 | 接口 `POST /api/v1/cs/session`（后端 11.1.2） | type=human/robot，返回 `sessionId` |
| 客服在线状态 | 接口 `GET /api/v1/cs/status` | 坐席排队/离线 |
| 发送消息 | 接口 `POST /api/v1/cs/session/{sessionId}/message` | HTTP 发送 + WebSocket 接收（双通道兜底） |
| 消息记录 | 接口 `GET /api/v1/cs/session/{sessionId}/messages` | 历史消息 + 增量拉取 |
| 常见问题 | 接口 `/api/v1/cs/faq` ⏳待契约 | 常见问题列表 |

### 12.2 工单系统

#### 12.2.1 功能点

| 功能 | 说明 |
|------|------|
| 提交工单 | 提交问题工单 |
| 工单列表 | 查看工单列表 |
| 工单详情 | 查看工单处理进度 |
| 工单评价 | 评价工单处理结果 |

#### 12.2.2 页面/组件

```
TicketScreen
├── TicketTopBar
│   ├── BackButton
│   └── Title（工单）
├── TicketForm
│   ├── TicketType（工单类型）
│   ├── TicketTitle（标题）
│   ├── TicketDescription（描述）
│   ├── TicketImages（上传图片）
│   └── SubmitButton（提交）
├── TicketList
│   ├── TicketItem
│   │   ├── TicketTitle
│   │   ├── TicketStatus（状态）
│   │   └── TicketTime
│   └── TicketItem
└── TicketDetail
    ├── TicketInfo
    ├── TicketProgress（处理进度）
    └── TicketEvaluation（评价）
```

#### 12.2.3 技术要点

- **工单类型**：商品问题、订单问题、物流问题、其他
- **进度跟踪**：实时更新工单处理进度
- **评价反馈**：处理完成后评价

#### 12.2.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 提交工单 | 接口 `POST /api/v1/cs/ticket`（后端 11.2.2） | 分类（refund/exchange/complaint/other）+ 标题 + 描述 + 图片 |
| 工单列表 | 接口 `GET /api/v1/cs/tickets` | 工单列表 |
| 工单详情 | 接口 `GET /api/v1/cs/tickets/{ticketId}` | 工单 ID → 详情与进度 |
| 工单回复 | 接口 `POST /api/v1/cs/tickets/{ticketId}/reply` | 补充信息 |
| 关闭工单 | 接口 `POST /api/v1/cs/tickets/{ticketId}/close` | 关闭 |
| 评价工单 | 接口 `POST /api/v1/cs/tickets/{ticketId}/rate` | 工单 ID + 评价 |

### 12.3 消息记录

#### 12.3.1 功能点

| 功能 | 说明 |
|------|------|
| 聊天记录 | 查看历史聊天记录 |
| 消息类型 | 文本、图片、商品卡片、订单卡片 |
| 消息搜索 | 搜索聊天记录 |
| 消息导出 | 导出聊天记录 |

#### 12.3.2 页面/组件

```
ChatHistoryScreen
├── ChatTopBar
│   ├── BackButton
│   ├── Title（聊天记录）
│   └── SearchButton
├── ChatList
│   ├── ChatItem
│   │   ├── ChatTime
│   │   ├── ChatContent（文本/图片/卡片）
│   │   └── ChatDirection（发送/接收）
│   └── ChatItem
└── ChatSearch（搜索）
```

#### 12.3.3 技术要点

- **消息类型**：文本、图片、商品卡片、订单卡片
- **消息同步**：多端消息同步
- **消息搜索**：全文搜索聊天记录

#### 12.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 聊天记录 | 接口 `GET /api/v1/cs/session/{sessionId}/messages` | 历史消息（后端 `cs_message` 表按 session 存储） |
| 消息搜索 | 后端暂无接口，**先用本地 Room 全文索引（FTS4）实现**，`/api/v1/cs/search` ⏳待契约 | 关键词 → 消息 |
| 消息导出 | 本地导出为图片/文本（分享自身会话，合规上仅允许导出与本人相关的记录） | 导出聊天记录 |

---

## 十三、离线能力

### 13.1 本地缓存

#### 13.1.1 功能点

| 功能 | 说明 |
|------|------|
| 数据缓存 | 商品、订单等数据本地缓存 |
| 图片缓存 | 图片本地缓存 |
| 配置缓存 | 配置信息本地缓存 |
| 缓存管理 | 缓存清理、缓存大小统计 |

#### 13.1.2 技术要点

- **数据库**：Room 数据库存储结构化数据
- **文件缓存**：图片、JSON 等文件缓存
- **缓存策略**：LRU 缓存策略，自动清理过期数据
- **缓存同步**：网络恢复后同步数据

#### 13.1.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 缓存数据 | 本地 Room DB | 商品、订单等 |
| 图片缓存 | Coil 磁盘缓存 | 图片文件 |

### 13.2 离线浏览

#### 13.2.1 功能点

| 功能 | 说明 |
|------|------|
| 离线商品 | 缓存商品信息离线查看 |
| 离线订单 | 缓存订单信息离线查看 |
| 离线购物车 | 离线添加购物车（联网后同步） |
| 网络恢复 | 网络恢复后自动同步 |

#### 13.2.2 技术要点

- **离线检测**：`ConnectivityManager` 监听（对应 13.3），配合请求超时判定"假在线"
- **数据同步**：网络恢复后按队列顺序回放；写操作携带 `clientRequestId` 幂等键，服务端按幂等去重（对齐 1.6.4）
- **冲突处理**：以服务端为准（Server-wins）——回放后若服务端返回最新态，直接覆盖本地并提示"数据已更新"；不做双向合并（电商场景下合并收益低、风险高）
- **队列上限与降级**：离线队列上限 200 条，超限提示"离线操作已达上限"；下单、支付**不进离线队列**（必须在线，避免超卖/价格变动纠纷）
- **多端冲突**：购物车以服务端 Redis 为准，本地仅缓存展示态

#### 13.2.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 网络状态 | ConnectivityManager | 网络连接状态 |
| 离线队列 | 本地 Room DB | 离线操作队列 |

### 13.3 网络状态检测

#### 13.3.1 功能点

| 功能 | 说明 |
|------|------|
| 网络检测 | 实时检测网络连接状态 |
| 网络类型 | WiFi/4G/5G/无网络 |
| 网络质量 | 网络速度检测 |
| 网络切换 | 网络切换时自动处理 |

#### 13.3.2 页面/组件

```
NetworkStatusView
├── NetworkIcon（网络图标）
├── NetworkText（网络状态文字）
└── RetryButton（重试）
```

#### 13.3.3 技术要点

- **ConnectivityManager**：监听网络状态变化
- **网络质量**：检测网络速度，弱网提示
- **自动重试**：网络恢复后自动重试失败请求

#### 13.3.4 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 网络状态 | ConnectivityManager | 网络连接状态 |

---

## 十四、性能优化

### 14.1 启动速度

#### 14.1.1 功能点

| 功能 | 说明 |
|------|------|
| 启动优化 | 减少启动时间 |
| 懒加载 | 非关键 SDK 延迟初始化 |
| 预加载 | 预加载关键资源 |
| 启动监控 | 启动耗时监控 |

#### 14.1.2 技术要点

- **App Startup**：使用 `App Startup` 库管理初始化器，声明依赖关系，非必要初始化器懒加载
- **懒加载**：非关键 SDK（推送、地图、语音、分享）延迟到使用时或首帧后初始化；**第三方 SDK 必须在隐私协议同意后才初始化**（2.2.3）
- **启动任务预算**：主线程同步任务总预算 ≤200ms，超预算任务一律移出主线程；用 `reportFullyDrawn()` 标记启动完成口径
- **预加载**：首页聚合接口在 Application 末段预热（不阻塞首帧）
- **启动监控**：线上双轨——国内自建/火山引擎 APM，海外 Firebase Performance（**Firebase 在无 GMS 的国内设备不可用，不能作为唯一监控**，见 18.3）
- **Baseline Profiles**：Macrobenchmark 生成并随包分发，覆盖首页/商品详情/购物车三条主链路

#### 14.1.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 启动耗时 | Firebase Performance | 启动时间监控 |

### 14.2 列表流畅度

#### 14.2.1 功能点

| 功能 | 说明 |
|------|------|
| 列表优化 | 减少列表卡顿 |
| 图片优化 | 图片懒加载、渐进加载 |
| 内存优化 | 减少内存占用 |
| 帧率监控 | 监控列表滑动帧率 |

#### 14.2.2 技术要点

- **Compose 优化**：使用 `key` 和 `contentType` 优化重组；避免在 Composable 内做计算/格式化（提到 ViewModels）
- **图片优化**：Coil 按容器尺寸加载（1.6.6），列表禁用原图
- **内存管理**：及时释放不用的资源，Bitmap 复用、避免 Compose 中持有大对象
- **帧率监控**：线上用 **JankStats**（Firebase Performance 不提供 Compose 帧级指标）；指标口径改为"60Hz 设备 P90 帧耗时 <16.7ms、掉帧率(Janky frames) <1%"，120Hz 设备单独分档
- **嵌套列表**：`LazyColumn` 内嵌 `LazyRow/LazyVerticalGrid` 时限制预取（`beyondBoundsItemCount=0`）

#### 14.2.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 帧率数据 | Firebase Performance | 帧率监控 |

### 14.3 内存占用

#### 14.3.1 功能点

| 功能 | 说明 |
|------|------|
| 内存监控 | 监控内存使用 |
| 内存泄漏 | 检测和修复内存泄漏 |
| 图片内存 | 图片内存优化 |
| 对象池 | 对象复用 |

#### 14.3.2 技术要点

- **LeakCanary**：检测内存泄漏
- **图片内存**：控制图片缓存大小
- **对象池**：复用频繁创建的对象
- **内存监控**：监控内存使用，超过阈值告警

#### 14.3.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 内存数据 | Android Profiler | 内存使用监控 |

### 14.4 电量优化

#### 14.4.1 功能点

| 功能 | 说明 |
|------|------|
| 电量监控 | 监控电量消耗 |
| 后台优化 | 减少后台电量消耗 |
| 网络优化 | 减少网络请求电量消耗 |
| 定位优化 | 减少定位电量消耗 |

#### 14.4.2 技术要点

- **WorkManager**：使用 WorkManager 管理后台任务
- **批量处理**：批量处理网络请求，减少唤醒次数
- **定位优化**：使用低功耗定位模式
- **电量监控**：监控电量消耗，优化高耗电功能

#### 14.4.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 电量数据 | BatteryManager | 电量消耗监控 |

### 14.5 线上质量指标（新增：原文只有测试期目标值，缺线上口径与告警）

| 指标 | 目标 | 口径 |
|------|------|------|
| 冷启动 | P50 ≤1.2s、P90 ≤2.0s | `reportFullyDrawn`，按机型分档（旗舰/中端/低端），国内主流在售中端机为准 |
| 首屏接口成功率 | ≥99.5% | `aggregation/home` 成功（含降级展示缓存） |
| 崩溃率 | Crash-free users ≥99.5%（周） | 剔除厂商 ROM 主动杀进程 |
| ANR 率 | ANR-free ≥99.5% | 主链路单独统计 |
| 页面打开时长 | P90 ≤1.5s | 商品详情、购物车、订单列表 |
| 图片加载成功率 | ≥99% | 含降级占位 |
| 推送 | 到达率 ≥85%、点击率按活动基线 | 按通道（厂商/FCM）分列 |
| 埋点成功率 | ≥99.5% | 本地缓冲 + 批量补传 |
| 流量 | 首页冷启动 ≤300KB | 含图片，超限告警 |

- 监控 SDK 双轨（对齐 18.3）：国内用合规国产 APM + 自建埋点上报 `/api/v1/track/*` ⏳待契约；海外用 Firebase 全家桶
- 告警：任一指标劣化超基线 20% 自动告警并支持远程开关降级

---

## 十五、适配与兼容

### 15.1 屏幕尺寸适配

#### 15.1.1 功能点

| 功能 | 说明 |
|------|------|
| 屏幕适配 | 适配各种屏幕尺寸 |
| 刘海屏 | 适配刘海屏/挖孔屏 |
| 折叠屏 | 适配折叠屏 |
| 平板 | 适配平板设备 |
| 横竖屏 | 支持横竖屏切换 |

#### 15.1.2 技术要点

- **布局适配**：Compose 用 `BoxWithConstraints`/`WindowSizeClass` + `maxWidth` 限制实现自适应（**ConstraintLayout 属 View 体系，Compose 仅在必要时经 AndroidView 使用**）
- **大屏适配**：`WindowSizeClass` 区分 compact/medium/expanded，中大屏双栏（左列表右详情）、推荐流改三列
- **刘海屏/挖孔屏**：`WindowInsets` + `displayCutout`，`edge-to-edge` 沉浸式
- **折叠屏**：监听 `WindowInfo.isScreenLaunchedOnOuter`/铰链角度，展开态保持页面上下文（避免折叠导致重建丢状态）；多窗口尺寸变化不杀页面
- **多窗口**：支持分屏，`resizeableActivity` 默认开启，禁止固定方向

#### 15.1.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 屏幕信息 | DisplayMetrics | 屏幕尺寸、密度 |

### 15.2 系统版本兼容

#### 15.2.1 功能点

| 功能 | 说明 |
|------|------|
| 版本兼容 | 兼容 Android 6.0+（minSdk 23，见技术要点） |
| API 适配 | 不同 API 级别适配 |
| 权限适配 | 不同版本权限差异 |
| 推送适配 | 不同版本推送差异 |

#### 15.2.2 技术要点

- **minSdk 23（Android 6.0）**：权限模型统一为运行时权限，砍掉 ≤22 分支的兼容成本；2026 年低端机存量已极低，保留 21 只增加测试矩阵不增加留存（如业务要求 21，须在 16.5 增补 5.x 真机矩阵与 `WRITE_EXTERNAL_STORAGE` 分支）
- **targetSdk 35（Android 15）**：Google Play 与国内主流商店 2025 年起强制要求 targetSdk 35，原"targetSdk 34"已不满足上架条件；同时适配 Android 15 的预测式返回、前台服务限制
- **API 检查**：统一 `SdkVersionProvider` 封装，禁止散落 `Build.VERSION.SDK_INT` 判断
- **权限适配**：6.0+ 动态权限、13+ 通知权限、14+ 前台服务类型与部分照片权限（见 2.3.2）
- **推送适配**：8.0+ 通知渠道，13+ 通知运行时权限（见 11.6）
- **JVM 目标**：`jvmTarget=17`，开启 R8 full mode 与资源收缩

#### 15.2.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 系统版本 | Build.VERSION | API 级别 |

### 15.3 厂商适配

#### 15.3.1 功能点

| 功能 | 说明 |
|------|------|
| 推送通道 | 华为/小米/OPPO/vivo 推送 |
| 权限管理 | 各厂商权限管理差异 |
| 后台保活 | 各厂商后台保活策略 |
| 通知渠道 | 各厂商通知渠道差异 |
| 安装器 | 各厂商安装器差异 |

#### 15.3.2 技术要点

- **推送通道**：集成华为/小米/OPPO/vivo 推送 SDK
- **权限管理**：适配各厂商权限管理页面
- **后台保活**：使用 JobScheduler/WorkManager 替代后台服务
- **通知渠道**：创建通知渠道，适配各厂商通知样式
- **安装器**：适配各厂商安装器行为

#### 15.3.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 厂商信息 | Build.MANUFACTURER | 设备厂商 |
| 推送 Token | 厂商推送 SDK | 推送 Token |

### 15.4 推送通道

#### 15.4.1 功能点

| 功能 | 说明 |
|------|------|
| FCM | Firebase Cloud Messaging（仅 GMS 设备） |
| 华为推送 | Huawei Push Kit |
| 荣耀推送 | Honor Push Kit（荣耀独立生态，**易遗漏**） |
| 小米推送 | Mi Push |
| OPPO 推送 | OPPO Push（需企业资质与审核） |
| vivo 推送 | vivo Push |
| 魅族/三星等长尾 | 魅族 Push；三星走 FCM 兜底（按 DAU 占比决定是否单接） |
| 通道选择 | 优先使用厂商通道，FCM 作为兜底；无 GMS 时判定逻辑见 11.6.1 |

#### 15.4.2 技术要点

- **通道选择**：优先使用厂商通道，FCM 作为兜底；**无 GMS 判定、Token 失效重注册、通知点击路由的完整落地见 11.6**
- **Token 管理**：管理多个推送通道的 Token，统一经 `POST /api/v1/device/register` 上报（后端 2.3.3）
- **消息格式**：统一消息格式，适配各厂商字段与富媒体能力差异
- **送达监控**：监控推送送达率与点击率（后端 `push_record`）；厂商通道有每日配额，超配额自动降级 FCM/站内信

#### 15.4.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 推送 Token | 厂商推送 SDK | 推送 Token |
| 推送配置 | 接口 `/api/push/config` | 推送配置 |

### 15.5 权限管理

#### 15.5.1 功能点

| 功能 | 说明 |
|------|------|
| 权限申请 | 动态权限申请 |
| 权限说明 | 权限用途说明 |
| 权限引导 | 引导用户开启权限 |
| 权限监听 | 监听权限变化 |

#### 15.5.2 技术要点

- **权限分组**：必需权限、可选权限、特殊权限
- **申请时机**：使用时申请，避免启动时集中申请
- **权限说明**：申请前弹窗说明用途
- **拒绝处理**：引导用户去设置页开启
- **厂商适配**：适配各厂商权限管理差异

#### 15.5.3 数据依赖

| 数据 | 来源 | 说明 |
|------|------|------|
| 权限配置 | 本地内置 + 接口 `/api/v1/permission/config` ⏳待契约 | 权限说明文案 |

### 15.6 显示、语言与无障碍（新增：电商强制项，原文缺失）

| 维度 | 要求 |
|------|------|
| 深色模式 | 全局 `dynamicDarkColorScheme`/品牌暗色，图片深色变体，WebView H5 跟随；设置项支持"跟随系统/浅/深"（10.6） |
| 字体缩放 | 禁止用固定 `sp` 导致 200% 放大错位；价格/按钮用 `dp` 兜底，正文支持系统字体缩放，关键链路（结算）做 130%/200% 两档截图回归 |
| 无障碍 | 关键控件设置 `contentDescription`；价格、倒计时、促销标签加语义；禁用仅靠颜色传达信息（色弱） |
| 多语言 | 资源外置 strings，先简体中文 + 英文；货币、日期、数字按 Locale 格式化（禁止硬编码） |
| 横屏 | 支付/视频/图片浏览器支持横屏，其余页面锁定竖屏并在 `configChanges` 保留状态 |
| 性别/年龄等敏感 | 生日、性别编辑为可选，不强制 |

---

## 十六、测试与质量保障

### 16.1 单元测试

#### 16.1.1 测试范围

| 模块 | 测试内容 |
|------|----------|
| ViewModel | 业务逻辑、状态管理 |
| UseCase | 业务用例 |
| Repository | 数据层逻辑 |
| Utils | 工具函数 |

#### 16.1.2 技术要点

- **JUnit4 优先**：Android 本地单测与 Robolectric/MockK 生态以 JUnit4 为主；如坚持 JUnit5 需在 gradle 开启 `useJUnitPlatform()` 并验证 Hilt/MockK/Turbine 兼容（混用两套会增加构建复杂度）
- **MockK**：Mock 框架（Kotlin 友好）
- **Turbine**：Flow 测试
- **Kotest**：断言库（可选，避免与 JUnit 断言风格混用）
- **覆盖率门禁**：Domain/ViewModel 层行覆盖 ≥70%，CI 不达标不合并

### 16.2 UI 测试

#### 16.2.1 测试范围

| 模块 | 测试内容 |
|------|----------|
| 页面渲染 | 页面是否正常渲染 |
| 交互逻辑 | 点击、滑动等交互 |
| 状态变化 | UI 状态变化 |

#### 16.2.2 技术要点

- **Compose Testing**：Compose UI 测试
- **Espresso**：传统 View 测试
- **Robolectric**：本地 JVM 运行 Instrumentation 测试

### 16.3 集成测试

#### 16.3.1 测试范围

| 模块 | 测试内容 |
|------|----------|
| 网络层 | API 请求/响应 |
| 数据库 | 数据持久化 |
| 端到端流程 | 完整业务流程 |

#### 16.3.2 技术要点

- **MockWebServer**：模拟 API 服务器
- **Hilt Testing**：依赖注入测试
- **Room Testing**：数据库测试

### 16.4 性能测试

#### 16.4.1 测试范围

> 指标口径：以 **Macrobenchmark + 中端真机（如 Redmi/骁龙 6 系）** 为基准，线上按机型分档统计（14.5）；原"单一阈值"不区分机型，线上必然大面积不达标。

| 指标 | 旗舰机（P90） | 中端机（P90） | 低端机（P90） | 口径 |
|------|---------------|---------------|---------------|------|
| 冷启动 | ≤1.0s | ≤1.5s | ≤2.5s | `reportFullyDrawn`，含 Baseline Profile |
| 热启动 | ≤0.5s | ≤0.8s | ≤1.2s | 进程存活场景 |
| 列表流畅度 | 掉帧率 <1% | <2% | <5% | JankStats，60Hz 设备 P90 帧耗时 <16.7ms |
| 前台内存 | ≤100MB | ≤120MB | ≤150MB | Java 堆 PSS，不含图片磁盘缓存 |
| 包体积 | arm64-v8a APK ≤30MB（AAB 分发时按拆分后单设备计） | | | R8 full mode + 资源收缩 + WebP + 按 ABI 拆分；14 个 feature + 微信/QQ/支付宝/地图/语音/4 家推送 SDK 后 30MB 是**紧约束**，超预算优先砍低频 SDK 为按需下载 |

#### 16.4.2 技术要点

- **Macrobenchmark**：启动与滚动基准（CI 每日跑，性能回退即阻断）
- **Baseline Profiles**：覆盖主链路
- **LeakCanary**：内存泄漏检测（debug 构建）
- **JankStats**：帧耗时与掉帧
- **APM**：线上性能监控双轨（国内 APM + 海外 Firebase，见 14.5/18.3）
- **截图回归**：关键页面在浅色/深色 + 100%/130% 字体下的截图对比

### 16.5 兼容性测试

#### 16.5.1 测试范围

| 维度 | 范围 |
|------|------|
| 系统版本 | Android 6.0 - 15（与 minSdk 23 / targetSdk 35 对齐，见 15.2.2） |
| 屏幕尺寸 | 手机、平板、折叠屏；含 720p 低端屏与 2K 屏 |
| 厂商 | 华为/荣耀、小米/红米、OPPO/一加/realme、vivo/iQOO、三星、魅族 |
| 特殊场景 | 深色模式、字体 130%/200%、分屏、无 GMS 设备、折叠展开态 |

#### 16.5.2 技术要点

- **Firebase Test Lab**：海外设备云（国内网络受限时用国内云真机）
- **STF /WeTest / 阿里 MQC**：本地与国内设备农场
- **自动化测试**：Compose UI 自动化 + 主链路回归脚本，纳入每日构建
- **厂商矩阵**：推送、后台保活、安装三类问题必须在真机矩阵验证，模拟器无效

### 16.6 质量门禁（新增）

| 门禁 | 工具/手段 | 阈值 |
|------|-----------|------|
| 静态检查 | detekt + ktlint + Android Lint（Compose 规则） | 0 error |
| 单测 | JUnit + MockK + Turbine | Domain/VM 覆盖率 ≥70% |
| 模块依赖 | 自定义 Gradle 校验（1.5.1） | 违规即失败 |
| 性能回退 | Macrobenchmark 每日基准 | 较基线劣化 >5% 阻断 |
| API 契约 | OpenAPI diff（对齐 1.6.1） | 接口变更未同步契约即失败 |
| 依赖安全 | 依赖 CVE 扫描 + 密钥硬编码扫描 | 高危 0 容忍 |
| 隐私合规 | SDK 采集检测（启动行为抓取） | 未同意前 0 采集 |
| 包体积 | size budget task | 超 30MB 告警、超 33MB 失败 |

---

## 十七、客户端安全与合规（新增章节）

### 17.1 传输与接口安全

| 项 | 方案 |
|----|------|
| 加密传输 | 全站 HTTPS + TLS 1.2/1.3，禁用明文流量 |
| 证书锁定 | OkHttp CertificatePinner，双证书备份 + 轮换预案（防止中间人抓包改包） |
| 请求签名 | HMAC-SHA256 + timestamp + nonce 防重放（对齐后端 13.1，实现见 1.6.4） |
| 关键参数二次加密 | 登录密码、支付密码、手机号走非对称加密，禁止明文与日志 |
| 抓包防护 | debug 可用代理，release 检测到代理/VPN 时对支付链路告警 |

### 17.2 代码与逆向防护

| 项 | 方案 |
|----|------|
| 混淆 | R8 full mode + 资源混淆（AndResGuard），keep 规则纳入 CI 校验 |
| 加固 | 应用加固（DEX 加壳/反调试/反注入），国内渠道要求；评估对启动性能影响 |
| 完整性校验 | 启动时校验签名与 DEX 完整性，检测到篡改则上报风控并降级（禁用支付） |
| root/模拟器/Hook 检测 | 仅做**风控信号上报**（不直接闪退，避免误伤与"恶意软件"判定） |
| 调试保护 | `FLAG_SECURE`（支付/个人信息页防截屏、防投屏）、禁用日志输出 |
| SDK 合规 | 全部三方 SDK 清单与采集行为登记，隐私协议中列明（工信部检测必查） |

### 17.3 数据与隐私合规

- **同意前零采集**：未同意隐私政策前，不初始化任何三方 SDK、不申请权限、不采集设备信息（2.2.3/14.1.2 已约束，此处为验收标准）
- **最小化采集**：设备标识用自生成 UUID（不取 IMEI/通讯录/应用列表，见 2.3.2）
- **本地存储加密**：Token、手机号等进 Keystore/EncryptedSharedPreferences；日志与崩溃附件脱敏（手机号、地址打码）
- **用户权利**：撤回同意（个性化开关）、账号注销（3.4）、导出/清除个人数据、足迹清除（10.7）
- **双版本合规**：国内（个保法/工信部 164·165 号文/商店审核）与海外（GDPR/CCPA、Google Play 政策——禁止动态下发代码，影响 2.4.2 热修复策略）分别建立检查表
- **上架检查**：应用商店要求的隐私政策链接、权限用途说明、SDK 清单、未成年人保护、备案信息（10.6）齐全

### 17.4 业务安全（客户端侧）

- 防薅羊毛：领券/秒杀客户端加行为验证码与频控提示，服务端为准（后端 13.3）
- 防重复下单/重复支付：幂等键 + 支付结果确认页（8.1.3/8.3.3）
- 防截断攻击：关键流程（支付、优惠券核销）服务端校验，客户端提示

---

## 十八、发布与运维

### 18.1 构建配置

#### 18.1.1 构建类型

| 类型 | 用途 | 配置 |
|------|------|------|
| debug | 开发调试 | 可调试、详细日志 |
| release | 正式发布 | 混淆、优化、签名 |
| staging | 预发布测试 | 接近生产环境 |

#### 18.1.2 技术要点

- **R8 full mode**：代码混淆和优化，资源收缩 + WebP 压缩（包体积口径见 16.4.1）
- **签名配置**：keystore 由 CI 密钥库托管，禁止入库；渠道包签名分离
- **构建变体**：`flavor × buildType` 矩阵——渠道：Google Play(AAB)、华为、小米、OPPO、vivo、荣耀、官网 APK；环境：dev/staging/prod（对应不同 BaseUrl，见 1.6.4）
- **渠道信息**：`BuildConfig.CHANNEL` + 安装来源记录（`InstallSourceInfo`），供灰度与归因
- **产物校验**：APK/AAB 体积门禁、manifest 合并结果检查（权限、`usesCleartextTraffic`、组件导出）

### 18.2 灰度发布

#### 18.2.1 功能点

| 功能 | 说明 |
|------|------|
| 灰度发布 | 按比例逐步发布 |
| 灰度策略 | 按用户群/地区/设备 |
| 回滚机制 | 发现问题快速回滚 |
| 版本管理 | 多版本管理 |

#### 18.2.2 技术要点

- **Google Play**：使用 Google Play 内测→封闭→公开分阶段投放（海外包）
- **国内渠道**：华为/小米/OPPO/vivo/荣耀应用市场各自有审核与灰度节奏，提前 1-2 周提审；大促期间冻结版本
- **热修复**：紧急问题走 Tinker（限制与合规见 2.4.2），灰度 1%→5%→20%→全量 + 崩溃率自动回滚
- **版本控制**：语义化版本（SemVer）+ versionCode 单调递增；强更走 `updateType=1`
- **回滚**：应用市场支持版本回滚有限，主用热修复回滚 + 新版本覆盖安装兜底

### 18.3 监控告警

#### 18.3.1 功能点

| 功能 | 说明 |
|------|------|
| 崩溃监控 | 监控应用崩溃 |
| 性能监控 | 监控启动、页面加载 |
| 业务监控 | 监控关键业务指标 |
| 告警通知 | 异常时告警通知 |

#### 18.3.2 技术要点

- **双轨监控（关键修正）**：Firebase 全家桶在无 GMS 的国内设备上不可用，**不能作为唯一监控**
  - 海外：Firebase Crashlytics / Performance / Analytics
  - 国内：合规国产 APM（崩溃/性能）+ 自建埋点上报（`/api/v1/track/*` ⏳待契约）+ Sentry 自托管版兜底
- **告警联动**：崩溃率/ANR 率/核心接口成功率劣化触发告警（阈值见 14.5），支持远程开关降级与热修复
- **自定义埋点**：业务指标监控（曝光、点击、下单漏斗），事件模型统一在 `library-analytics` 定义

### 18.4 数据分析

#### 18.4.1 功能点

| 功能 | 说明 |
|------|------|
| 用户行为 | 用户行为分析 |
| 转化漏斗 | 购买转化漏斗 |
| 留存分析 | 用户留存分析 |
| A/B 测试 | 功能 A/B 测试 |

#### 18.4.2 技术要点

- **行为分析双轨**：海外 Google Analytics/Firebase；国内用合规国产分析 SDK 或自建数仓，禁止未告知采集
- **A/B 测试**：远程配置驱动（实验开关、放量、指标回收），与灰度发布共用一套配置中心
- **自定义埋点**：事件模型（页面/曝光/点击/下单漏斗）+ 本地缓冲 + 批量上报 + 失败重试，成功率纳入 14.5
- **合规联动**：用户拒绝个性化推荐后仅上报去标识化事件，不打用户画像标签

---

## 十九、项目排期

### 19.1 阶段划分

| 阶段 | 时间 | 里程碑 | 后端依赖（对齐 `android-backend.md` 15.1，共 26 周） |
|------|------|--------|----------|
| **第一阶段：基础架构** | 第 1-4 周 | 工程搭建、core 模块、网络层（签名/Token/错误码）、路由与组件库；**第 1 周完成接口契约评审并冻结**（1.6.1 对齐 + 1.6.2 P0 缺口闭环） | 后端 1-4 周：数据库、网关、基础服务 |
| **第二阶段：核心功能** | 第 5-12 周 | 登录注册、首页、商品、搜索、购物车、下单支付 | 后端 5-12 周：认证、商品、订单、支付 |
| **第三阶段：扩展功能** | 第 13-18 周 | 订单管理、物流、个人中心、消息推送、分享、扫码 | 后端 13-18 周：推送、定位、分享、扫码 |
| **第四阶段：完善功能** | 第 19-22 周 | 客服、工单、售后、评价、版本更新与热修复 | 后端 19-22 周：客服、更新、性能 |
| **第五阶段：联调压测与灰度** | 第 23-26 周 | 全链路联调、性能与兼容性压测、灰度发布 | 后端 23-26 周：测试、灰度、上线 |
| **第六阶段：上线与运维** | 持续 | 监控、迭代、优化 | — |

> **排期修正**：原"22 周完成并灰度发布"早于后端 26 周上线计划，灰度发布缺少服务端支撑，属跨文档排期错位；现与后端里程碑对齐，并把"接口契约冻结"提到第 1 周（购物车/优惠券/售后等 P0 缺口见 1.6.2，未闭环须砍范围或延期）。

### 19.2 人员配置

| 角色 | 人数 | 职责 |
|------|------|------|
| 技术负责人 | 1 | 架构设计、技术决策、代码审查 |
| 安卓开发 | 4-6 | 功能开发、单元测试 |
| UI 设计师 | 1-2 | UI 设计、交互设计 |
| 测试工程师 | 1-2 | 功能测试、性能测试 |
| 产品经理 | 1 | 需求管理、产品规划 |

### 19.3 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 接口契约缺口 | 购物车/优惠券/售后/上传等后端未定义，核心链路阻塞 | 第 1 周契约冻结评审；P0 未闭环则降级方案（砍功能/换接口）并升级到项目级 |
| 技术选型风险 | 架构不稳定 | 提前技术验证（PoC：Tinker×AGP 8、运营商一键登录、地图 SDK Compose 包装） |
| 三方 SDK 资质与审核 | 微信/支付宝/厂商推送账号与应用签名审核 1-2 周 | 第一阶段并行提交申请，排期预留缓冲 |
| 人员风险 | 进度延迟 | 关键岗位备份，知识共享；SDK 对接安排专人 |
| 需求变更 | 影响排期 | 敏捷开发，迭代交付，变更走影响评估 |
| 性能问题 | 用户体验差 | 提前性能测试，14.5 指标纳入日常监控 |
| 兼容性问题 | 部分用户无法使用 | 真机矩阵 + 灰度发布（16.5） |
| 合规/审核风险 | 下架、整改、通报 | 17.3 合规检查表前置；隐私 SDK 检测纳入 CI（16.6） |
| 大促冻结 | 版本窗口冲突 | 大促前 2 周冻结发版，热修复预案演练 |

---

## 二十、附录

### 20.1 技术栈清单

| 类别 | 技术 | 版本（**以 1.5.3 版本目录为唯一真源**） |
|------|------|------|
| 开发语言 | Kotlin | 2.0.21 |
| UI 框架 | Jetpack Compose（BOM） | 2024.10.01 |
| 架构模式 | MVI + Clean Architecture | - |
| 依赖注入 | Hilt | 2.52 |
| 网络请求 | Retrofit + OkHttp | 2.11.0 / 4.12.0 |
| 序列化 | kotlinx.serialization | 随 Kotlin 版本 |
| 图片加载 | Coil | 2.7.0 |
| 数据库 | Room | 2.6.1 |
| 异步处理 | Kotlin Coroutines + Flow | 1.9.0 |
| 导航 | Navigation Compose | 2.8.3 |
| 本地存储 | DataStore | 1.0.0 |
| 推送 | FCM + 厂商推送（华为/荣耀/小米/OPPO/vivo） | 见 11.6 |
| 热修复 | Tinker（国内包；海外包禁用，见 2.4.2） | - |
| 崩溃/性能监控 | 双轨：国内合规 APM + 海外 Firebase（见 17/18.3） | - |
| 埋点统计 | 自建上报 + 双轨分析 SDK（见 18.4） | - |

### 20.2 第三方 SDK 清单

| SDK | 用途 |
|------|------|
| 微信 SDK | 微信登录、分享、支付 |
| QQ SDK | QQ 登录、分享 |
| 微博 SDK | 微博分享（可选） |
| 支付宝 SDK | 支付宝支付 |
| 银联/银行 SDK | 银行卡支付（按渠道） |
| 运营商一键登录 SDK | 移动/联通/电信取号（3 家需分别接入与适配） |
| 华为推送 | 华为设备推送 |
| 荣耀推送 | 荣耀设备推送（独立生态） |
| 小米推送 | 小米/红米设备推送 |
| OPPO 推送 | OPPO/一加/realme 设备推送 |
| vivo 推送 | vivo/iQOO 设备推送 |
| 高德/百度地图 | 地图、定位、地址解析 |
| 讯飞语音 | 语音识别兜底（见 6.1.3） |
| 应用加固 | DEX 加固/反调试（国内渠道要求，见 17.2） |
| 合规 APM/分析 | 国内崩溃监控、性能监控、行为分析（双轨见 17/18.3/18.4） |
| Firebase 全家桶 | FCM/Crashlytics/Performance/Analytics/A-B（仅海外 GMS 设备） |

> 说明：三方 SDK 全量清单与采集行为须与隐私政策披露一致（17.3），并纳入 CI 隐私检测（16.6）。

### 20.3 设计资源

| 资源 | 说明 |
|------|------|
| 设计规范 | 色彩、字体、间距、圆角 |
| 图标库 | 统一图标库 |
| 插画库 | 空状态、引导页插画 |
| Lottie 动画 | 加载动画、引导动画 |

---

> **文档说明**：本计划为安卓客户端前端开发的完整指导文档，涵盖架构设计、功能模块、技术要点、数据依赖、测试与发布等方面。实际开发过程中需根据项目实际情况进行调整和优化。

---

## 审查记录

> 审查范围：架构与选型、模块化与路由、与 `plans/android-backend.md`（v1.1）的接口契约一致性、可实施性、安卓特有问题（碎片化/推送/权限/热修复）、性能指标与安全性。  
> 结构变更说明：新增「十七、客户端安全与合规」，原「十七、发布与运维」「十八、项目排期」「十九、附录」顺延为十八/十九/二十章。

### v1.0 → v1.1 修改记录

| 编号 | 问题描述（严重级别） | 修改位置与内容 |
|------|----------------------|----------------|
| 1 | 【致命·一致性】前后端接口契约全线不一致：前端统一用 `/api/*`，后端统一 `/api/v1/*`；且资源命名、路径结构、参数语义大量偏差（商品/订单/地址/收藏/消息/客服/工单/支付/物流等） | 新增 1.6.1 契约对照表（以 `android-backend.md` 为准）；重写全文 30+ 处数据依赖表 |
| 2 | 【致命·完整性】前端引用的接口后端大量缺失（购物车全套、优惠券、售后、评价发表、文件上传、闪屏/引导/协议、埋点等），原文档未做任何标注，联调必然爆雷 | 新增 1.6.2 缺口清单（P0/P1/P2 + 降级方案）；各模块数据依赖表以 `⏳待契约` 标注 |
| 3 | 【严重·一致性】前端内部接口自相矛盾：积分同时存在 `/api/user/points` 与 `/api/points/*` 两套路径 | 10.5.4、8.1.4 统一为 `GET/POST /api/v1/user/points*` |
| 4 | 【致命·完整性】缺失 Token 刷新、登出、多端互踢、账号切换流程（仅在后端存在对应接口） | 新增 3.5「会话与 Token 管理」、1.6.4「Token 生命周期与拦截器链」 |
| 5 | 【严重·可实施性】网络层完全没有设计（拦截器、签名、错误码、分页、序列化、超时弱网、WebView 加固） | 新增 1.6 整章（1.6.1-1.6.7） |
| 6 | 【严重·合理性】`app/` 单模块与 `modules/` 多模块两套目录并存，职责歧义；缺 core-api、跨模块通信方案 | 新增 1.2.1 工程结构说明；1.5.1 补 core-api 通信与 CI 依赖守卫；1.4.5 补跨模块路由注册 |
| 7 | 【中等·合理性】选型写 MVI，但无 Intent/State/Effect 约定，落地易退化为 MVVM | 1.1 补「MVI 组件约定」表 + Compose 互操作等选型风险 |
| 8 | 【严重·合理性】路由设计缺陷：参数未 URL 编码（关键词/WebView 崩溃）、可选参数 `{id?}` 写法错误、缺少 Deeplink 与外部路由 | 重写 1.4.1 Screen.kt（编码 + query 可选参数 + defaultValue）；新增 1.4.4 Deeplink、1.4.5 跨模块注册；1.4.2 导航图补启动分发与 Tab 状态保持 |
| 9 | 【中等·合理性】首页/详情用 `NestedScrollView`（View 体系概念，Compose 不存在）；BottomNavigation 同时出现在 HomeScreen 与 MainScreen | 4.1.1、5.2.2 改 LazyColumn 单滚动容器 + NestedScrollConnection；底部导航归 MainScreen（1.4.3 注明） |
| 10 | 【轻微】下拉刷新引用不存在的 `SwipeRefresh` 组件 | 4.1.2 改 Material3 `PullToRefreshBox`（Accompanist 已停止维护） |
| 11 | 【严重·合规】权限清单过时且过度：`READ_PHONE_STATE` 取设备标识（Android 10+ 禁 IMEI）、`WRITE_EXTERNAL_STORAGE` 未限版本、通知监听/应用列表属过度申请 | 2.3.2 修订权限表（移除/限版本/补 Android 14 部分照片与前台服务类型）；2.3.3 补申请顺序与拒绝处理 |
| 12 | 【严重·可实施性】热修复方案未评估合规与工程限制（Google Play 禁止动态下发代码、Tinker×AGP 8.x 兼容） | 2.4.2 补限制矩阵、双包策略、PoC 要求与替代方案（远程开关/资源热更/H5/强更） |
| 13 | 【中等·一致性】版本自相矛盾：1.5.3（Kotlin 2.0.21 等）与附录（Kotlin 1.9.22 等）两套 | 1.5.3 声明为唯一真源；20.1 附录表对齐 |
| 14 | 【严重·完整性】模块缺失：订单有"去评价"入口但无评价模块与接口、售后无列表页、个人中心有"足迹"入口无模块、首页有秒杀入口无承接页、相机权限声明了扫码却无扫码模块 | 模块树补 feature-aftersale/feature-seckill/feature-scan；新增 9.6「发表评价」、10.7「浏览足迹」；9.5 补售后列表接口依赖 |
| 15 | 【严重·安卓特有】推送方案不可实施：仅"FCM+厂商"一句，缺荣耀/长尾通道、无 GMS 判定、Token 上报接口、通知点击路由、渠道拆分 | 新增 11.6「推送通道落地」（判定矩阵/Token/点击/本地兜底）；修订 15.4 补荣耀、魅族、三星与配额降级 |
| 16 | 【中等·可实施性】客服 WebSocket 缺连接管理与消息可靠性设计 | 12.1.3 补心跳/指数重连/离线补偿/本地落库去重；12.3.4 消息搜索降级本地 FTS4 |
| 17 | 【严重·安全】无客户端安全章节（传输加固、逆向防护、隐私合规、同意前零采集、业务安全） | 新增「十七、客户端安全与合规」（17.1-17.4），后续章节顺延为十八/十九/二十 |
| 18 | 【严重·可实施性】Firebase 全家桶（FCM/Crashlytics/Performance/GA）在国内无 GMS 设备不可用，却被作为唯一监控与分析方案 | 14.1.2、14.5、18.3、18.4 改"国内合规 APM + 自建埋点 / 海外 Firebase"双轨 |
| 19 | 【中等·性能】性能指标不现实且无口径：单一冷启动 1.5s、60fps、内存 150MB、包 30MB（未区分机型/统计口径/包形态） | 16.4.1 改旗舰/中端/低端分档 + P90 口径 + JankStats 掉帧率替代帧率；新增 14.5 线上质量指标（崩溃/ANR/成功率/流量/推送） |
| 20 | 【中等·安卓特有】`minSdk 21 / targetSdk 34` 已不满足 2026 年 Google Play 与国内商店上架要求 | 15.2.2 改 minSdk 23 / targetSdk 35 并说明理由；16.5.1 兼容范围同步改 Android 6.0-15 |
| 21 | 【轻微】屏幕适配引用 View 体系 ConstraintLayout | 15.1.2 改 BoxWithConstraints/WindowSizeClass 大屏双栏 + 折叠屏连续性 |
| 22 | 【严重·一致性】排期与后端冲突：前端 22 周完成并"灰度发布"，后端 26 周才上线，灰度无服务端支撑 | 19.1 改为与后端 26 周逐阶段对齐 + 后端依赖列；接口契约冻结提前到第 1 周 |
| 23 | 【中等·完整性】风险表缺 SDK 资质审核、合规审核、接口缺口、大促冻结等真实风险 | 19.3 扩充至 9 项 |
| 24 | 【严重·安全】支付流程缺异常处理与安全细节（拉起失败、用户取消、防截屏、串单） | 8.3.3 扩写异常处理与安全项 |
| 25 | 【严重·一致性】结算页"客户端实时计算实付金额"与后端 preview 返回金额口径冲突（精度与优惠规则不一致） | 8.1.3、5.2.3 明确客户端不计算金额、以 preview 响应为准 |
| 26 | 【中等】离线冲突策略缺失（仅一句"冲突处理"） | 13.2.2 明确 Server-wins + `clientRequestId` 幂等 + 下单支付不进离线队列 |
| 27 | 【轻微·合理性】确认收货要求密码/指纹验证属过度设计，拖累转化 | 9.4.1、9.4.3 改轻量二次确认，大额且开启支付密码时才验证 |
| 28 | 【中等·一致性】物流接口前端按运单号查询，后端按订单号查询 | 9.3.4 对齐 `GET /api/v1/logistics/{orderId}` 并注明参数语义变更 |
| 29 | 【中等·完整性】深色模式、多语言、无障碍、字体缩放完全缺失（电商强制项） | 新增 15.6；10.6.1 补外观/语言/账户注销/SDK 清单披露 |
| 30 | 【中等·质量】测试缺质量门禁；JUnit5 与 Android 生态兼容性未说明 | 16.1.2 改 JUnit4 优先并注明 JUnit5 前提；新增 16.6 质量门禁（静态检查/覆盖率/性能回退/契约 diff/隐私检测/包体积） |
| 31 | 【中等·完整性】分页与响应契约未在客户端侧定义 | 1.6.3 统一响应信封、错误码客户端行为、分页 `{total,page,pageSize,list}`、kotlinx.serialization 与金额处理 |
| 32 | 【轻微·完整性】后端已有但前端未接入的能力：扫码识物/扫码登录确认、附近门店、浏览历史、签到、再次购买、实名认证、分享回流统计 | 3.1.4、9.1.4、10.5.4、模块树（feature-scan）等处补入；定位服务与 2.3.2 权限关联说明 |

**遗留待办（需项目组决策，非文档层面可闭环）**：
1. ~~1.6.2 中 P0 缺口（购物车/上传/评价发表）需后端排期承诺，否则第七、八、九章范围须裁剪~~ **【跨文件同步 S1：已在文档层面闭环】**`android-backend.md` 16.4 已逐项登记 P0 缺口并给出计划交付（购物车全套、文件上传、评价发表 `POST /api/v1/orders/{orderId}/review` 均为第 1-4 周出契约，实现分别在第 5-8 / 1-4 / 9-12 周；注册为第 1-4 周必须二选一），并写明双向对账与「P0 未闭环前相关模块不得进入联调」的闭环流程，前端 1.6.2 已按 16.4 同步优先级与路径（见 1.6.2 对账口径）。剩余为**执行跟踪项**：若第 1 周契约冻结评审仍未通过，才升级为项目级范围裁剪决策（见 19.1）；
2. 微信/支付宝/厂商推送/运营商一键登录的资质与审核周期需商务并行推进（19.3）；
3. Tinker × AGP 8.x × Compose 的 PoC 须在第一阶段完成，失败则启用 2.4.2 替代方案。

## 跨文件同步记录

### v1.1 → v1.2

- [S1] `android-backend.md` 16.4「待契约接口清单」→ **P0 项与优先级逐项对账**：① P0 登记缺失——注册 `POST /api/v1/auth/register`（后端 P0，第 1-4 周二选一）在 3.2.4 已标 `⏳待契约` 但 1.6.2 未列 → 1.6.2 补 P0 行；② 优先级错位——改密/换绑/踢设备/推送配置/用户统计/收藏单查/分类树后端列为 P1，前端误列 P2 → 1.6.2 拆出 P1 行；③ 购物车 P0 行误含 `coupon`（属 P1 `/coupon/available`）→ 修正归属；④ 1.6.2 表下新增「与后端 16.4 对账口径」段（P0/P1/P2 逐项交付阶段）
- [S1] `android-backend.md` 16.4 具体路径 → **`⏳待契约` 标注与拟定路径对齐**：评价发表由 `POST /api/v1/products/{spuId}/reviews` 改为订单维度 `POST /api/v1/orders/{orderId}/review`（9.6.2 资格校验同步为 `completed` 且 `reviewed=false`）；埋点由 `/api/v1/track/exposure` 改为批量 `POST /api/v1/track/batch`（4.2.4）；领券改为 `POST /api/v1/coupon/{couponId}/receive`（10.4.4）；售后补 `POST /api/v1/aftersale/{id}/return-logistics`（后端已列、前端缺失）并注明 `apply/create` 命名待统一、售后状态与 4.3.4 状态机 4/5/7 联动；优惠券 `list` 属"两边都没有"，标注须在第 1 周契约评审补登记
- [S1] `android-backend.md` 4.3.4 + 审查记录 [1][2] → **订单状态枚举 8 值化残留修正**：9.1.4 原写 `status=pending` 且为 7 值错位枚举（4已完成/5已取消/6退款中/7已退款）→ 改为后端枚举名 `all|unpaid|paid|shipped|completed|cancelled|refunding|refunded|closed`、8 值含义、「待评价 = `completed&reviewed=false`」、`statusText/actions` 后端下发；9.1.1 Tab 行补 4/5 并入售后、7 并入已取消的映射说明
- [S1] `android-backend.md` 16.4 计划交付列 → **遗留待办第 1 项闭环**：原「P0 缺口需后端排期承诺」已在后端文档落地（16.4 逐项交付阶段 + 闭环流程），改为执行跟踪项；第 2、3 项（三方资质审核、Tinker×AGP 8.x PoC）确需项目组/商务决策，保持不变
- [S1] 文首文档版本 v1.1 → v1.2（与本节标题一致）
