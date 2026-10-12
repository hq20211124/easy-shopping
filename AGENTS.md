# AGENTS.md

## 项目概览

五终端电商平台：**P0 已实现可运行**。后端 13 个 Spring Boot 模块（H2/内存事件的单机微服务形态），五终端全部交付（管理端/PC买家端/小程序/安卓/iOS），核心购物闭环（注册登录 → 搜索 → 加购 → 下单拆单 → 支付 → 订单）经 13 步冒烟 + 46 项真实链路验证全绿。

管理端用于商家/平台管理（仅浏览器 BS），其余四端为买家购物客户端，共享同一后端。

**规模口径：千万 DAU**（1000 万 DAU / 峰值在线 200 万 / 网关入口 60 万 QPS / 压测 75 万），容量真值唯一入口 `plans/00-架构总纲.md`。物理上限约 3000~5000 万 DAU。

## 目录结构

```
plans/            11 份文档（00-架构总纲 + 10 份实施手册，规划真值）
server/           后端：13 模块 Maven monorepo（构建/冒烟/启停脚本见 DEV.md）
web-admin/        管理端（React 19 + AntD 5 + Vite，16 页）
web-buyer/        PC 买家端渲染层（React 19 + Tailwind，11 页，后续可套 Electron 壳）
miniprogram/      小程序（uni-app + Vue3，14 页，h5/mp-weixin 双构建）
android-client/   安卓（Kotlin + Compose + MVI，13 路由，assembleDebug 可出 APK）
ios-client/       iOS（SwiftUI 零三方依赖 + XcodeGen，Windows 无法编译，见其 README 声明）
AUDIT/            代码质量与业务逻辑审计（README 总册 + 01后端/02前端/00主审深审）
DEV.md            后端构建/运行/开发规范（Maven 全路径、端口表、事件与错误码规范）
AGENTS.md         本文件
```

`architecture/` 12 份亿级文档已删除，原文见 git 历史 v1（`ae33a94`）；plans/ 文首「引用口径」将旧编号引用统一解析到 `plans/00-架构总纲.md`。

## 关键约定（修改代码/文档前必读）

### 唯一事实来源

| 事实 | 真值位置 |
|------|----------|
| 订单状态机 | **0基** 8 值（0待付款/1待发货/2待收货/3已完成/4退款中/5已退款/6已取消/7已关闭），见 admin-backend 4.3、`server/core-order` `OrderStatus`（enum 单点定义，各端枚举与之逐值一致） |
| `order_no` 编码 | 24 位 = 时间14 + 槽位4 + 序列6（00-架构总纲 §3、admin-backend 1.2.1） |
| 支付超时 | 订单表 `pay_deadline` 字段，**各端不硬编码分钟数**（裁决 C7；默认 30 分钟待书面确认） |
| 响应信封 | `{code,msg,data,traceId}`，分页 `list/total`（DEV.md） |
| 写接口幂等 | `Idempotency-Key` 请求头强制；重复=409/1005，不产生第二单（common `@Idempotent`） |
| 服务命名 | `core-user/product/price/inventory/cart/order/payment/promotion/search` + `admin-bff`（P0 聚合，plans 21 个 admin-* 为 P1 拆分）+ `gateway` |
| 缓存 Key | 四段式 `{业务域}:{模块}:{标识}:{版本}`（ios-backend） |
| 签名窗口 | ±5 分钟 / Nonce TTL 10 分钟（android-backend 13.1；安卓端开关 `SIGN_ENABLED`，后端 P0 未开验签） |
| MQ 事件 | 30 个事件名以 `common/DomainEventNames` 常量为准（P0 进程内分发，P1 接 RabbitMQ） |
| 容量口径 | `plans/00-架构总纲.md`（含 22 层容量表、可用性 99.9%→P1 双活 99.95%、终审 7 项待决策） |

### 审计后新增的行为约定（改代码前必读）

`AUDIT/` 审计出 16 项 P0（4 项资金/权益级）并已全部修复，随之而来的行为约定：

- **幂等语义已变**：`Idempotency-Key` 占位区分 `PROCESSING`/`SUCCESS`——**业务失败释放占位（同键可重试）**，系统异常/超时保留（防真实重复提交）。写「首次失败→重放应 409」的用例需按新语义更新。
- **支付金额 fail-closed**：prepay 只认订单快照的 `payAmount`（**删除 totalAmount 回退**），缺失或 ≤0 直接 4002 拒绝、不落支付单。发布顺序必须 **core-order 先于 core-payment**。
- **券接口双入口**：买家端 `/api/v1/coupons/*` 强制 `X-User-Id` 归属校验（缺失=1004/403）；服务间调用走 `/api/v1/internal/coupons/*` + `X-Internal-Token`（常量时间比较，**默认留空=拒绝全部**）。
- **密码哈希 PBKDF2-HMAC-SHA256 21 万次迭代**（格式 `pbkdf2_sha256$iterations$salt$hash`，兼容旧 `s256$`，登录时自动 rehash 升级）。
- **退款语义**：`PaymentClient.refund` 返回"是否被支付域受理"；**仅受理成功才发 `ORDER_REFUND_APPLIED` 事件**，失败只告警不打断关单。
- **订单隐私**：收货人手机号一律脱敏；`internalOrder` 目前返回整实体（含明文），P1 应收窄为字段快照。
- **⚠️ 放开商品编辑入口前须书面确认**：`PUT /api/admin/product/{id}` 是 SKU 全量替换，会清空 `costPrice`（审计登记项）。

### 陷阱

- `android-frontend.md` 中的 `core-network`/`core-ui` 等是**安卓客户端模块名**，与后端 `core-*` 服务同名不同物，禁止混淆。
- **平台编码 GBK**：PowerShell `Get-Content` 默认按 ANSI 读 UTF-8 中文文件会乱码与误匹配，须用 `[System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)`；PowerShell 5.1 无 `??` 运算符。
- 本机环境：Maven 用全路径 `C:\D\tools\maven\bin\mvn.cmd`；JDK17 在 `C:\Android\jdk-17`；Android SDK 在 `C:\Android`（compileSdk 34）；WSL wslrelay 占用 `::1:8081-8083`，服务一律绑 `127.0.0.1`。
- 雪花 ID 为偶数序列（序列左移 1bit 防 JS `JSON.parse` 精度丢失），勿改回标准雪花。
- 后端技术栈 P0 为 H2(MySQL 模式)+内存事件（单机可运行）；生产演进为 MySQL 16库64分片+RabbitMQ（**维持 RabbitMQ 不迁移**，触发条件见总纲），**不做单元化/异地多活/OceanBase/RocketMQ**。

## 工作流程约定

- 修改口径/契约前，先查 `plans/00-架构总纲.md`（含终审裁决与 7 项待决策），避免重新引入冲突。
- 跨文档/跨端同步后，在目标文件文末追加记录章节（审查记录 / 跨文件同步记录）。
- 后端改动跑 `C:\D\tools\maven\bin\mvn.cmd -q test`；联调用 `server/scripts/{start-all,stop-all,smoke}.ps1`；真实模式验证清单见 `server/LIVE-INTEGRATION-REPORT.md`。
- 前端默认 `VITE_USE_MOCK=true`（离线可演示），切真实模式步骤见各端 README 与联调报告。

## 待项目组书面确认项（7 项，登记于 00-架构总纲终审记录）

- 支付超时分钟数（建议 30 分钟）
- MySQL 物理实例数 48 vs 64 主（待预压测回填单实例 TPS）
- iOS App Clip 阶段一是否提供
- 三方支付/登录资质审核周期
- 安卓热修复 Tinker × AGP 8.x PoC
- 运维编制（SRE 4~6 人 vs 历史 8~10 人）
- 买家端搜索/推荐是否独立成 `core-*` 服务
