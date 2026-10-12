# AUDIT — 代码质量与业务逻辑完备性审计总册

> 审计时间：P0 交付后（提交 `0d4111a` 之后）。范围：`server/`（13 模块）+ 五端（web-admin、web-buyer、miniprogram、android-client、ios-client）。
> 规模：**千万 DAU 口径**（容量真值 `plans/00-架构总纲.md`）。
> 原则：每条发现必须给**证据（文件:行）与后果**；修复必须带**回归测试**，不得靠删除既有断言"通过"。

## 一、报告清单与分工

| 报告 | 范围 | 审计者 | 审计结论 | 修复后 |
|------|------|--------|---------|--------|
| [00-main-critical.md](00-main-critical.md) | 资金与数据关键路径深审（状态机/分摊/支付回调/对账/幂等/雪花ID）+ 跨切面口径扫描 | 主会话（逐行读 + 与 plans 交叉核对） | **P0: 0 · P1: 2 · P2: 5** | P1 全部修复 |
| [01-backend.md](01-backend.md) | server/ 全量 13 模块 | 子代理 | **P0: 8 类(11 项) · P1: 12 · P2: 9** | **P0 全部修复** |
| [02-frontend.md](02-frontend.md) | 五端前端 | 子代理（含实测：安卓 `compileDebugKotlin`、两端 `tsc -b`、headless Chrome 点击） | **P0: 5 · P1: 7 · P2: 18** | **P0 全部修复** |

**合计 P0 = 16 项（11 后端 + 5 前端）、P1 = 21 项、P2 = 32 项。**

## 二、P0 修复台账（16/16 已修复）

### 后端（11 项）

| # | 位置 | 缺陷 → 修复 | 状态 |
|---|------|------------|------|
| B1 | `OrderStateMachine.java:104-107` | 幂等短路早于终态校验，终态订单同状态请求被静默判成功 → 拆出 `assertNotTerminal()` 前置；`from==target` 改走子单漂移自愈（补齐+写流水，不触发事件）；子单无法合法补齐则抛 4002 | ✅ |
| B2 | `OrderStateMachine.java:117-126` | CAS 败方重读命中即 `continue`，可能组内不一致却发事件 → 败方计数+告警，循环后**整组一致性断言**，任一不在目标态整体回滚 | ✅ |
| B3 | `OrderCreateService.java:208` + `DiscountAllocator.java:51-53` | 优惠尾差归「首单」而非 plans 要求的「金额最大子单」→ `indexOfMaxBase()`（并列取首个保确定性）；空/负/全零入参按参数错误拒绝 | ✅ |
| B4 | `OrderQueryService.java:112,211` | 收货人手机号未脱敏 → 与 core-user `MaskUtil` 同算法脱敏（买家端详情亦脱敏，plans 12.5/12.6 未给 owner 开明文例外） | ✅ |
| B5 | `PaymentService.java:83` + `InternalOrderController.java:50-51` | 预支付取优惠前 `totalAmount` → **有券订单多收钱**；快照补 `payAmount`，`RestOrderClient` **只认 payAmount 并删除回退**（fail-closed），非正应付直接拒绝预支付 | ✅ |
| B6 | `RestPaymentClient.java:30-38` | 退款空实现（仅 WARN），两条补偿路径失效 → 接通真实 `POST /api/v1/payment/refund`，`PaymentClient.refund` 改返回"是否受理"，失败告警但**不打断关单** | ✅ |
| B7 | `OrderLifecycleService.java:285-287` | `triggerRefund` 无条件发"退款已应用"事件 → **仅在退款确实被受理时**发事件，失败只告警 | ✅ |
| B8 | `RestInventoryClient.java:23-54` | 三个库存写接口无降级 → 下游失败按语义降级/告警，不直接 500 | ✅ |
| B9 | `InventoryService.java:148-167` | release 逐条同事务，第 N 条失败回滚前 N-1 条 → **库存幽灵锁**；改逐条独立短事务 + 事务内重读闭合 TOCTOU + `requireLockedEnough` 防 `locked` 变负（否则凭空多出可售库存） | ✅ |
| B10 | `CouponService.java:119,135` + `CouponUserController.java:64-67` | ①无身份头跳过归属校验（水平越权）②核销后订单失败无退券 → 买家端强制归属校验（1004/403）；新增 internal 端点走 `X-Internal-Token` 常量时间比较、**默认留空=拒全部**；域内新增核销回退兜底（**必须回查订单**，UNKNOWN 一律不退——宁可不退也不误退） | ✅ |
| B11 | `PasswordHasher.java:31` | 单轮 SHA-256 无密钥拉伸 → PBKDF2-HMAC-SHA256 **21 万次迭代** + 16B 随机盐 + 版本前缀；双格式兼容 + 登录时 `rehashIfNeeded` **自动升级存量用户** | ✅ |
| P1-a | `PaymentNotifyService.java:69-92` | 金额校验失败后同 notifyId 重试被永久判重 → `notify_log` 增 `process_status`，REJECTED 放行同号重投；补测试「同号修正金额可成功入账」 | ✅ |
| P1-b | `IdempotentInterceptor.java:51-53` | 业务失败不释放幂等占位 → 两态 `PROCESSING`/`SUCCESS`，业务失败带值删除、系统异常保留（防真实重复提交） | ✅ |

### 前端（5 项）

| # | 位置 | 缺陷 → 修复 | 状态 |
|---|------|------------|------|
| F1 | `ios-client/Features/Pay/PayModels.swift:34-41` | `payParams` 声明 `String?`（后端 `Map<String,String>` **非空**）、`expireAt` 声明 `String?`（后端 epoch `Long`）→ **iOS 支付 100% 失败**；按后端 DTO 逐字段改正 | ✅ |
| F2 | `CheckoutModels.swift:61` | `CouponPreviewRequest.amount` 标量 vs 后端 `List<BigDecimal>` → 改数组 | ✅ |
| F3 | `CheckoutModels.swift:71` | `CheckoutCoupon.status: Int?` vs 后端英文枚举 → 改 `String?` + 单点映射 | ✅ |
| F4 | `miniprogram/cart/index.vue:70-72` + `product/detail.vue:89` | 传 `skuId` 读 `spuId` → **详情页永久白屏**；改传 `productId`（即 SPU 主键，后端 `CartService` 已回填），参数无效时显示可退出错误态 | ✅ |
| F5 | `web-admin/src/api/` 售后 3 函数 | 路径缺 `/admin` + 文档谎称"已隐藏" → 对齐 plans 4.2.2 路径，并以 `AFTER_SALE_AVAILABLE` 开关**诚实下线**（后端无该域接口，属 P1） | ✅ |

## 三、修复过程的验证证据

| 验证项 | 结果 |
|--------|------|
| 后端全量测试 `mvn -o test` | **EXIT=0**（A组 81 例 core-order、B组 core-payment 12 例、C组 common 13/user 36/inventory 16/promotion 22） |
| 端到端冒烟 `server/scripts/smoke.ps1` | **32/32 断言全绿**（13 步），含"应付=总额+运费-优惠""prepay 金额=订单应付"（B5 修复验证） |
| 新增测试 | **+80 个**（A 27 / B 17 / C 32 / E 3+新增页面错误态用例），**既有断言零删除** |
| 小程序 | `type-check` + `build:h5` + `build:mp-weixin` 全绿；headless Chrome 真实点击 **7/7** |
| web-admin | `build`/`tsc -b`(0 error)/`lint`/`test`(18 例) 全绿；headless Chrome 真实点击 **10/10** |
| iOS | 43 文件括号/结构/引用一致性自查全通过；**⚠️ 仍未经过 Xcode 编译验证**（本机无 Swift 工具链） |
| 安卓 | 审计代理实测 `gradle :app:compileDebugKotlin` **BUILD SUCCESSFUL** |

## 四、贯穿全项目的三条系统性教训（已全部转化为修复要求）

1. **Mock 掩盖契约缺陷**：两个资金 P0（B5/B6）都发生在被 `@MockBean` 掉的跨服务边界上——测试桩构造不出 `total≠pay` 场景，于是"防改价测试全绿"却仍有错账。→ 回归测试桩改为 `total=199.00 / pay=189.00` 并显式断言"≠199.00"。
2. **静默失败是放大器**：F1~F3 被 `try?` 吞掉表现为"点了没反应"；iOS 结算页券不可用时静默按原价下单。→ 解码失败一律上抛到 UI（输出**字段路径+期望类型**）；选了券但试算失败改为**阻断式确认**。
3. **文档与代码偏离未登记**：F5（声称已隐藏实则未隐藏）、B3（尾差归集对象与 plans 原文不符）、B6（退款 TODO 注释路径与真实接口不符）、web-admin README 声称"lint 通过"实则长期为红。→ 契约表整表重建（每行标注复核方式与后端文件:行号），未核对的标注不得进文档。

## 五、遗留与后续（审计登记项的补齐状态）

**全部 P1 已补齐**（CI 全绿验证），仅剩需项目组决策或 P1 迭代的条目：

### 已补齐（2026-10-12 第二轮）

| 项 | 内容 |
|----|------|
| 管理端明文手机号 | `GET /api/admin/orders/{orderNo}/receiver-mobile`——三重闸门（管理端角色 + 专门权限码 `order:mobile:read` **fail-closed** + 操作人非空），同事务写操作留痕（复用 `order_status_log`，不新建表）；列表/详情仍脱敏 |
| internal 接口收窄 | `internalOrder` 由返回整个 `OrderMain` 实体改为 **6 字段快照**（orderId/userId/totalAmount/payAmount/status/payDeadline，按 grep 实测的消费方确定）；**明文手机号/姓名/地址/remark/couponId 等一律不再外泄**给 core-payment 与 core-promotion |
| MaskUtil 提取 | `common/api/MaskUtil.java`（core-user 原副本保留，待统一） |
| 每人限领并发超发 | 「先查后写」改为**悲观临界区**（`SELECT ... FOR UPDATE` + 显式 `READ_COMMITTED`——RR 下事务快照在加锁前固定会失效）；并发测试 16~24 线程证明「限领 N 则恰成功 N」。⚠️ **条件 UPDATE + 子查询的写法实测无效**（子查询按语句快照在等锁前求值，16 线程限领 1 成功 10 个），代码与 README 已标注"勿再尝试" |
| 券回收兜底加固 | 越界状态码（如 99）判 `UNKNOWN` 而非 `ORDER_ALIVE`；`used_order_id` 为空的脏数据改为 WARN 留痕；9 例 fail-safe 矩阵测试 |
| SKU 更新语义 | `PUT /api/admin/product/{id}` 由「先删后插」改为**按 skuId 合并**：带 skuId 按 id 更新（**id 稳定，价格/库存绑定不再错位**），不带则新增；`costPrice` 等字段**未携带即保留库中原值**（无 `cost:read` 角色的编辑不再抹成本价）；未携带的既有 SKU 软删留痕；校验 skuId 重复/跨商品/软删、`marketPrice≥price`、`costPrice≥0` |
| 支付回调第二道防线 | 反查订单 `payAmount` 与支付单金额比对：一致→入账；**不一致→拒绝 + 告警**（notify_log 标 REJECTED，修正后同 notifyId 可重投）；**反查失败/超时→放行入账**——两个方向的错误代价不对称，因抖动拒绝会造成"用户已扣款显示未支付"的真实资损 |
| iOS 编译验证 | 建立 GitHub Actions CI（6 job），macOS runner 首次编译即抓出 `TokenKeys` 未定义（`@MainActor` 重构遗漏），修复后 **xcodebuild 成功** |

### 仍需项目组决策

- ⚠️ **放开商品编辑入口前须书面确认**：`PUT /api/admin/product/{id}` 现在是"提交即完整 SKU 列表"，漏行 = 该 SKU 软删（web-admin 需先回传 `skuId`，见 core-product README 的前端同步清单）
- **订单 owner 是否可见明文手机号**：现按 plans 12.5/12.6 保守口径一律脱敏；若产品要求本人可见明文，须先改 plans 再开"仅本人 + 留痕"的专用接口（不得直接放开现有接口）
- 券有效期取模板 `endTime` 快照 = **产品口径**（改期不追溯存量券），已写入代码与 README
- 券模板行热点（同一热门券串行）：彻底消除需 P1 上分桶或独立计数表（计数表须先在 plans DDL 登记）

### P1 迭代项（已登记，未做）

- 后端：`internalOrder` 快照已收窄，但落库手机号仍为明文（建议 AES-256-GCM，P1）；支付回调"反查失败"分支需接告警通道观测其静默失效；每人限领的分桶优化；`core-user` 侧 `MaskUtil` 与 common 版统一
- 后端缺口（前端已诚实下线/占位）：售后域 6 端点、订单备注接口、`AdminOrderVO` 缺字段、`AdminDataController` 工作台为 stub
- 工程：`start-all.ps1` 冷启动探测超时已由 3s 放宽至 10s（11 JVM 同启会吃满 CPU，过短超时会把"还在启动"误判为"起不来"）

## 六、审计方法与可信度（未能验证的部分如实标注）

- **逐行读**服务层/领域层/网络层关键实现，与 `plans/` 章节交叉核对（不只看 README）。
- **状态机白名单已逐边核对**：plans `admin-backend.md:2223-2231` 六行十三边 vs `OrderStateMachine.TRANSITIONS` **完全一致**（留证表见 00 报告 §4.1），**修复过程中未改动白名单**。
- **主会话独立复核**：B5/B6/B10 三条 P0 均由主会话读源码坐实（B5 追加确认 `OrderMain` 同时存在 `totalAmount` 与 `payAmount`），P1-a 追加测试证据（`PaymentIntegrationTest:147-163` 原用例用"新通知号"绕开了同号重试语义）。
- **未验证**：iOS 未经 Xcode 编译；无生产级压测与故障注入；安卓/iOS/小程序未做真机联调（仅契约对拍 + 构建验证）。