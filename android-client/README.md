# easy-shopping-android

买家端 Android 客户端（P0）。技术栈：**Kotlin + Jetpack Compose + MVI**。

> 本目录对应 `plans/android-frontend.md`。后端契约以 `server/*/README.md` 与 `plans/android-backend.md` 为准；
> 服务命名 `core-*` 是**后端微服务**名，与下面的客户端包模块名（`core/network`、`core/ui`）**同名不同物**，勿混淆。

## 一、构建与运行

### 环境

- JDK 17（本仓库验证路径：`C:\Android\jdk-17`）
- Android SDK 34 / Build-Tools 34.0.0（`local.properties` 里 `sdk.dir` 指向 SDK 根）
- Gradle 8.4+（AGP 8.2.2）

### 构建命令

```powershell
$env:JAVA_HOME='C:\Android\jdk-17'
cd C:\D\agent\easy-shopping\android-client
gradle assembleDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 配置 baseUrl（`local.properties`，已 gitignore）

```properties
sdk.dir=C:\\Android
baseUrl=http://10.0.2.2:8080/
```

- 模拟器默认 `10.0.2.2` 指向宿主机 `localhost`，配合 `server/gateway` 的 8080 端口。
- 真机联调改为电脑局域网 IP（如 `http://192.168.1.10:8080/`），并保证手机与电脑同网段。
- 缺省值：`http://10.0.2.2:8080/`（见 `app/build.gradle.kts`）。

## 二、模块结构（P0 单 app module）

P0 只有一个 `:app` module，靠**包结构**分层。P1 拆多模块时按下表一对一迁移（客户端模块名，与后端 `core-*` 无关）：

| P0 包 | 职责 | P1 目标模块 |
|------|------|------------|
| `core/network` | OkHttp + Retrofit + kotlinx.serialization、响应信封、幂等/鉴权/签名拦截器、401 单飞刷新 | `:core:network` |
| `core/ui` | 主题、通用组件（价格/状态 Chip/步进器/空态） | `:core:ui` |
| `core/mvi` | `MviViewModel` 基座（StateFlow + Channel Effect） | `:core:mvi` |
| `di` | 手写 `ServiceLocator`（P0 不引 Hilt） | 迁 Hilt/Sealed DI |
| `navigation` | 路由表 `Screen` + `AppNavGraph` | `:core:navigation` |
| `feature/*` | 各业务页（Contract + ViewModel + Screen） | `:feature:auth`、`:feature:home` … |

```
app/src/main/java/com/easyshopping/android/
├── App.kt / MainActivity.kt        # 单 Activity；启动按登录态分流 login / main
├── di/ServiceLocator.kt            # 手写 DI（P0 无 Hilt）
├── core/
│   ├── network/                    # Api.kt（端点唯一真源）/ Dtos.kt / Interceptors.kt / TokenAuthenticator.kt
│   ├── ui/{theme,components}/
│   ├── mvi/MviViewModel.kt
│   └── navigation/{Screen.kt, AppNavGraph.kt}
└── feature/
    ├── auth/       # 登录 / 注册
    ├── home/       # 首页（商品双列 + 热搜）
    ├── search/     # 搜索输入 + 结果（cursor 分页）
    ├── detail/     # 商品详情 + 多规格 SKU 矩阵
    ├── cart/       # 购物车（步进/全选/失效区/合计）
    ├── checkout/   # 结算（地址 + 券降级 + 下单）
    ├── pay/        # 收银台（prepay + mock 支付 + 轮询）
    ├── order/      # 订单列表（Tab）+ 详情（倒计时/取消/确认收货）
    └── user/       # 我的 + 地址管理（列表/新增/编辑）
```

每个 feature 遵循 MVI：`XxxUiState` + `XxxIntent` + `XxxEffect` + `XxxViewModel` + `XxxScreen`。
**ViewModel 不持有 Navigator**，跳转一律通过 `Effect` 上抛，由 `AppNavGraph` 执行。

## 三、页面清单（P0）

| 页面 | 路由 | 说明 |
|------|------|------|
| 登录/注册 | `login` / `register` | 同页双 Tab；注册 P0 验证码固定 `123456`；登录成功 `popUpTo(0)` 清栈 |
| 首页 | `tab_home` | `/api/v1/products` page 分页 + `/api/v1/search/hot`；TODO(P1) Banner/金刚区 |
| 搜索输入 | `search` | 联想（防抖 300ms）+ 热搜 + 本地历史（SP，最多 10 条） |
| 搜索结果 | `search/result/{keyword}` | **cursor 分页**；`nextCursor=null` 即末页；换词重建 VM |
| 商品详情 | `product/{spuId}` | 多规格 SKU 矩阵（不可组合置灰）+ 加购 |
| 购物车 | `tab_cart` | 步进/全选/失效区/合计；`maxItems` 取服务端值 |
| 结算 | `checkout` | 默认地址 + 未使用券（降级）+ 备注 + 提交下单 |
| 收银台 | `pay/{orderId}` | 渠道 1~4 + `pay_deadline` 倒计时 + 状态轮询 |
| 订单列表 | `order/list?tab=` | Tab：all/unpaid/paid/shipped/completed；按钮按 `actions[]` 渲染 |
| 订单详情 | `order/{orderId}` | 金额明细 + 子订单 + timeline + 取消/确认收货 |
| 我的 | `tab_user` | 资料 + 订单快捷入口 + 地址管理 + 退出；足迹/消息/收藏为占位 |
| 地址列表 | `address/list` | 设默认 / 删除（服务端校验未完成订单引用） |
| 地址编辑 | `address/edit?addressId=` | 新增 / 编辑；手机号与必填项本地预校验 |

## 四、关键实现口径

### 1. 订单状态：0 基 8 值（**全 App 唯一定义点**）

`core/network/Dtos.kt` 的 `OrderStatus`：

```
0 待付款 / 1 待发货 / 2 待收货 / 3 已完成 / 4 退款中 / 5 已退款 / 6 已取消 / 7 已关闭
```

列表 Tab 与该枚举一一对应（`OrderTabs`）；**禁止**在别处重新定义状态数字。
（对齐 `AGENTS.md`「订单状态机 0 基」与 `server/core-order/README.md` §2。）

### 2. 金额：BigDecimal 字符串，禁 Double

- DTO 金额字段统一 `@Serializable(with = AmountSerializer::class)` → `String` 承载，兼容 JSON 数字/字符串。
- 运算走 `Money.twoDecimals / display / sum / subtract`（`core/network/Serialization.kt`）。
- **下单不传金额**：`CreateOrderRequest` 只有 `skuId + quantity`，实付以 core-price 计价与下单响应为准。

### 3. 幂等：写接口带 `Idempotency-Key`

- `CommonHeadersInterceptor` 对 **所有 POST/PUT/DELETE/PATCH** 自动注入 `Idempotency-Key: UUID`（服务端 `@Idempotent` 强制，缺头 → 1001）。
- 下单额外显式传 key（`Api.createOrder(idempotencyKey, body)`）：**同一"提交动作"的重试复用同一 key**，成功后才换新 key，避免网络重试重复建单。

### 4. 支付：以服务端轮询为准

- `POST /api/v1/payment/prepay {orderId, channel}` → `paymentId`；`channel ∈ 1 支付宝 / 2 微信 / 3 银行卡 / 4 余额`。
- P0 走 `POST /api/v1/payment/mock/pay {paymentId}`（与真实回调同一条 core-payment 处理链）。
- 结果轮询 `GET /api/v1/payment/status/{orderId}`，退避 1s/2s/4s/8s/16s → 30s，最多 3 分钟；
  `status` 用服务端原文 `pending/paid/refunding/refunded`，**禁用数字推断**，只有 `paid` 才算成功。
- **支付超时不硬编码分钟数**：倒计时一律读订单的 `payDeadline` 字段。

### 5. 购物车写接口用 `cartItemId`

`cart/update`、`cart/remove`、`cart/select` 全部以 `cartItemId` 定位，**禁用 skuId**；
`cart/select` 的 `cartItemIds` 传 `null` = 服务端按"全选/取消全选全部有效项"处理。

### 6. 签名：骨架 + 开关（后端未开验签）

`app/build.gradle.kts` → `BuildConfig.SIGN_ENABLED`（当前 `false`）。
`core/network/SignatureInterceptor` 已实现 `method\npath\ntimestamp\nnonce\nsortedQuery\nbodySha256` 的 HMAC-SHA256 骨架，
对齐 `plans/android-backend.md` 13.1（±5min 时间戳 / Nonce TTL 10min）。
后端 `edge-gateway` 尚未开启验签校验，故默认关闭；接入时置 `true` 并下发 secret（P0 占位未落盘，见文件内 TODO）。

### 7. 响应信封与错误

- 信封 `{code, msg, data, traceId}`，`code=0` 成功；兼容 `msg`/`message` 双字段。
- 业务错误统一收敛为 `ApiException(code, message)`；UI 一律展示**服务端 msg**（如 3002 库存不足、券失效），不自造文案。
- HTTP 401 由 `TokenAuthenticator` **单飞刷新**（并发只发一次 refresh，其余等结果），失败/被踢通过 `SessionEvents` 广播。

## 五、给联调代理的注意事项

1. **不要改 `core/network/Api.kt` 之外的地方拼 URL**。端点路径唯一真源在该文件，与 `server/*/README.md` 对齐。
2. **不要在客户端新增状态数字**。订单状态只认 `OrderStatus`；支付状态只认服务端英文原文。
3. **不要传金额**。下单/加购/结算都不提交金额字段，展示用的都是服务端返回值或本地预估（已标注"以下单结果为准"）。
4. **写接口不要手动拼 `Idempotency-Key`**，除非要表达"同一次提交的重试"（目前只有下单这么做）。
5. **网关白名单**（免登录）：`/api/v1/auth/login/**`、`/api/v1/auth/register`、`/api/v1/auth/refresh`、`/api/v1/products/**`、`/api/v1/search/**`；其余都要 `Authorization: Bearer <accessToken>`。详见 `server/gateway/src/main/resources/application.yml`。
6. **后端尚未提供、已做降级的接口**（勿在客户端硬等）：
   - `/api/v1/aggregation/home`（Banner/金刚区/推荐）→ 首页只做商品分页 + 热搜
   - `cart-checkout/preview`（结算预览）→ 结算页金额为本地预估
   - `/coupon/available` → 降级用 `GET /api/v1/coupons/my?status=0`
   - 评价相关接口 → 详情页不展示评价
   - 买家侧足迹/消息/收藏读取端点 → 「我的」页为占位入口
7. **模拟器 vs 真机**：模拟器用 `10.0.2.2`；真机需改成局域网 IP 并关闭手机流量（保证同网段）。
8. **编码**：所有 `.kt` 为 UTF-8。用 PowerShell 读取时务必 `[System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)`，
   `Get-Content` 默认按 ANSI 解析会导致中文乱码与匹配失败。
9. **P0 不做的事**：Hilt（手写 ServiceLocator）、Room（搜索历史用 SharedPreferences）、三方支付 SDK（mock 支付）、
   富文本详情渲染、图片/视频播放器。P1 再补，勿在 P0 引入额外依赖。

## 六、P1 待办（已在代码内以 `TODO(P1)` 标注）

- 拆多模块 + 迁 Hilt / Sealed DI
- 首页聚合接口（Banner/金刚区/推荐/未读消息/购物车角标）
- 结算预览与可用券接口
- 真实三方支付 SDK + 签名开关打开 + secret 落 Keystore
- 搜索历史迁 Room + 云同步；足迹/消息/收藏
- 富文本商品详情、图片/视频播放器
- `usesCleartextTraffic` 关闭（全站 HTTPS + 证书校验）
