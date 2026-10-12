# core-order 订单服务

交易域核心服务：下单（按店铺拆单 + 优惠分摊）、8 值 0 基状态机、`pay_deadline` 超时关单、
关单与支付回调竞态裁决、买家/管理端订单接口。对应 `plans/*-backend.md` 订单章节
（admin-backend 4.x、android-backend 4.2、ios-backend 4.4）。

- 端口 `8086`，P0 H2 文件库 `./data/core-order`
- 雪花 `app.worker-id: 6`；`order_no` 24 位 = 时间 14 + 槽位 4 + 序列 6（`architecture/02-data-storage.md` §3.2）
- 支付超时：订单表 `pay_deadline` 字段，`app.order.pay-timeout-minutes=30`（**各端不自定义分钟数**）

## 1. 接口契约

### 1.1 买家端

| 方法 | 路径 | 幂等 | 说明 |
|------|------|------|------|
| POST | `/api/v1/orders`（别名 `/api/v1/order/create`） | `@Idempotent`，必带 `Idempotency-Key` | 下单：校验 → 预占库存 → 扣券（P0 降级不折扣）→ 按 store_id 拆单 → 写父/子单与订单行 |
| GET | `/api/v1/orders?status=&page=&pageSize=` | – | 我的订单分页 |
| GET | `/api/v1/orders/{orderId}` | – | 订单详情（归属校验，越权 404/权限码） |
| POST | `/api/v1/orders/{orderId}/cancel` | – | 买家取消（仅待付款；已付款走退款） |
| POST | `/api/v1/orders/{orderId}/confirm` | – | 确认收货（待收货 → 已完成，联动 `core-inventory deduct`） |

### 1.2 管理端（`ROLE_ADMIN`，经 `UserContext.roles()` 校验；内部直调无角色头时按内网信任）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/order/list`（别名 `/api/admin/orders`） | 后台订单分页（多条件） |
| GET | `/api/admin/order/{orderId}`（别名 `/api/admin/orders/{orderId}`） | 订单详情 |
| POST | `/api/admin/order/{orderId}/ship`（别名 `/api/admin/orders/.../ship`） | 发货（重复发货幂等空操作） |
| POST | `/api/admin/order/{orderId}/close`（别名 `/api/admin/orders/.../close`） | 手工关单（待付款→已取消；已支付待发货→已关闭+退款；已发货→拒绝） |
| GET | `/api/admin/order/{orderNo}/receiver-mobile`（别名 `/api/admin/orders/{orderNo}/receiver-mobile`） | **明文手机号专用接口**（发货/联系快递）：需权限码 `order:mobile:read` + 操作人身份，**每次查看写一条操作留痕**；仅返回本单明文，无批量/导出入口 |

> **明文接口契约**（审计 P1 遗留项落地）：
> ① 权限码 `order:mobile:read` 由 admin-bff RBAC 侧注册同名权限点，校验通过后经
> `X-Admin-Perms`（英文逗号分隔的权限码集合）透传给 core-order 做**二次校验**
> （plans/admin-backend 2.3.2「双重校验」；**缺头即拒绝 1004**，不适用「空角色头=内网放行」的 P0 口径）；
> ② `X-User-Id` 必须非空（无操作人即无法留痕 → 1002）；
> ③ 留痕复用 `order_status_log`（`from == to` 表示无状态变更的操作留痕，
> `reason = "查看收货人明文手机号"`，`operator_type = 2` 管理端）；
> ④ 按 **orderNo**（24 位）而非雪花 ID 定位，避免父子单歧义。
> 已知副作用：买家端 timeline 与状态流水同源，用户会看到这条商家侧查看动作（透明化提示；如需隐藏须在买家 timeline 过滤）。

### 1.3 内部跨服务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/internal/orders/{orderId}/paid` | core-payment 支付成功回调；幂等（重复回调 `ALREADY_PAID`），与超时关单并发时 version CAS 裁决 |
| GET | `/api/v1/internal/orders/{orderId}` | 订单**字段快照**（core-payment prepay 取额 / core-promotion 券回收对账）。**定额口径 = `payAmount`（应付）；`totalAmount`（优惠前）仅供参考** |

> **internal 快照字段集（收窄后）**：仅 `orderId / userId / totalAmount / payAmount / status / payDeadline`
> 6 个字段，每个字段都对应一个已知消费方（core-payment `RestOrderClient#toSnapshot`、
> core-promotion `RestDeductedOrderVerifier`）。**不返回** `receiverMobile` / `receiverName` /
> `receiverAddress` / `remark`（收货个人信息，服务间调用方一个都不消费）与 `couponId` / `payType` /
> `version` 等无消费方字段（审计 P1：原 `OrderQueryService.internalOrder` 返回整个 `OrderMain` 实体）。
> **字段名不得增删**——core-payment 按 `orderId`/`payAmount` 取值，改名即导致 prepay fail-closed（9999）。

> **隐私口径**：买家端与管理端的订单出参中 `receiverMobile` 一律脱敏（`138****0000`），
> 脱敏算法取自公共工具 `com.easyshopping.common.api.MaskUtil`（plans ios-backend 12.5/12.6 与
> admin-backend 4.4.3）；明文只从 1.2 节的「带权限 + 查看留痕」专用接口出，且**不得批量导出**。
> 「订单 owner 本人可见明文」仍是产品未决口径（AGENTS.md 登记待确认），本模块**未开口子**。
> 落库侧：`receiver_mobile` 自 P1 起为 **AES-256-GCM 密文**（`v1:keyId:iv:ct`），
> 读取时解密、写出参前脱敏，明文/密文同列共存（见 11 节）。

## 2. 状态机（0 基 8 值，AGENTS.md 唯一事实来源）

| 值 | 状态 | 允许的下一步（`OrderStateMachine.TRANSITIONS`，与 admin-backend 4.3 白名单一致） |
|----|------|--------------|
| 0 | 待付款 UNPAID | 1 支付成功 / 6 取消 |
| 1 | 待发货 PAID | 2 发货 / 4 退款中 / 6 取消 / 7 关闭（超时关单后退款） |
| 2 | 待收货 SHIPPED | 3 确认收货 / 4 退款中（售后） |
| 3 | 已完成 COMPLETED | 4 退款中 / 7 关闭 |
| 4 | 退款中 REFUNDING | 5 已退款 / 3 退款失败回滚 / 7 关闭 |
| 5 | 已退款 REFUNDED | 7 系统归档关闭（**仅 operator.type=3 系统任务**，plans 5→7） |
| 6 | 已取消 CANCELLED | 终态（无资金流取消） |
| 7 | 已关闭 CLOSED | 终态（已支付后关闭） |

- `OrderStateMachine.transition` 执行顺序：**终态校验 → 幂等短路 → 白名单 → 整组 CAS → 组内一致性断言**
  （终态必须先于幂等，否则 `cancelled/closed` 的同状态重复请求会被静默判成功）；
- 白名单外流转抛 `ORDER_STATUS_ERROR(4002)`；CAS 败方重读裁决——重读已在目标态则记 WARN 并交由
  **组内一致性断言**复核（整组必须都在目标态，否则抛 4002 整体回滚，禁止「父单成功、子单没跟上」的半成功）；
- 父单已在目标态时**不再直接 `return false`**，仍扫描子单**自愈父子漂移**（补齐落后的子单并写流水，
  原因含「子单状态漂移自愈」）；补不齐（子单终态/白名单外）时抛 4002 暴露数据问题；
- 每次流转写 `order_status_log`（含建单 `-1 → 0` 初始条目），`orderId` 落 NOT NULL 列（修复过的隐性缺陷）；
- 所有流转走 `OrderMainRepository` 的 CAS：`WHERE id=? AND version=? AND order_status=?`，
  同时 `version+1`，并发只有一方成功，输的一方重读裁决。

## 3. 下单与拆单（P0 口径）

流程：入参校验 → core-product/price 取商品与价 → `core-inventory preview/reserve` 预占
→ core-promotion 扣券 → 按 `store_id` 拆子单 → 写 `order_main`（父+子）/`order_item` → 发 `order.created`。

- **创建时拆单**（与 `plans/*` 中「先建父单、支付后再拆」的描述不同，README 特此标注）；
- 优惠按子单金额比例分摊，**尾差归集到金额最大的子单**（并列取原顺序首个，保证确定性；
  与 plans/admin-backend.md 4.4.2「尾差归入金额最大的子单」一致），
  保证 `Σ子单优惠 = 父单优惠`；行级优惠同理 `Σ行 = 子单优惠`，尾差归到金额最大的行；
  `DiscountAllocator` 另对空基数 / 负数基数 / 负数优惠做参数校验（不会产出破坏不变式的分摊结果）；
- 金额 BigDecimal，行金额/优惠不落浮点（DEV.md 4）；
- **P0 促销降级**：core-promotion 核销契约未就绪 → 优惠按 0 计 + WARN 日志 + TODO
  （`RestPromotionClient`：P1 必须删除降级分支，失败要抛错回滚，禁止静默按原价成交）；
- 运费 P0 恒 0（TODO：运费模板按店铺计算）。

## 4. 超时关单与竞态裁决（测试要求②⑥）

- `pay_deadline = 建单时间 + pay-timeout-minutes`（默认 30 分钟，产品待确认）；
- `OrderTimeoutCloseScheduler` 每 `app.order.timeout-scan-ms`（默认 10000ms）扫 `now > pay_deadline`
  且状态 0 的父单 → CAS 置 6 已取消 → 释放库存（`core-inventory release`）→ **退券（`core-promotion release`，
  仅当订单落有 `coupon_id`）** → 发 `order.cancelled`；买家取消 / 后台关闭同样走「释放库存 + 退券」，
  退券失败只告警、不回滚关单（券由对账兜底）；
- **关单 vs 支付回调竞态**：双方都用 `version + order_status` CAS，先到者胜——
  - 关单赢：支付回调重读后发现 `CLOSED/REFUNDING` → 调 `paymentClient.refund()` 原路退款
    （真实调用 `POST /api/v1/payment/refund`，body={orderId, reason}，P0 幂等），
    仅当支付域<b>受理</b>才发 `order.refund.applied`，否则只打 `[退款告警]` 日志，返回 `REFUND_TRIGGERED`；
  - 回调赢：扫描重读后发现已 `PAID` → 静默跳过；
  - CAS 冲突在 `markPaid` 的重试循环里重读裁决，最多重试后抛 4002 让调用方查单；
- **P0 内存扫描是兜底不是主通道**，P1 改 MQ 延迟队列（`deliveryTimestamp` 直取 `pay_deadline`）。

## 5. 领域事件（事务提交后发布，`AfterCommitEvents`）

| 事件 | 触发 |
|------|------|
| `DomainEventNames.ORDER_CREATED` | 建单成功 |
| `DomainEventNames.ORDER_PAID` | 支付回调成功 |
| `DomainEventNames.ORDER_CANCELLED` | 买家取消 / 超时关单 / 后台关闭 |
| `DomainEventNames.ORDER_STATUS_CHANGED` | 每次状态流转（含 from/to） |
| `DomainEventNames.ORDER_REFUND_APPLIED` | 进入退款中 |

事件名只用 `DomainEventNames` 常量（DEV.md 4.4，禁止自造字符串）。
`order.shipped` / `order.completed` 注册表暂无常量 → 由 `order.status.changed` 的 from/to 承载，
TODO 待登记后补发。

## 6. 幂等

写接口挂 `@Idempotent`（common `IdempotentInterceptor`，P0 内存占位）：
键 = `METHOD:URI:Idempotency-Key` → 缺键 `1001`，重复 `1005`（HTTP 409）。
P1 换 Redis `SET NX EX`（多副本下内存幂等会失效）。库存侧另有业务幂等（orderId 预占单）。

## 7. 配置

```yaml
app:
  worker-id: 6
  order:
    pay-timeout-minutes: 30   # 唯一事实来源是订单表 pay_deadline
    timeout-scan-ms: 10000    # P0 内存扫，P1 → MQ 延迟队列
  crypto:
    receiver-mobile:
      enabled: true           # 落库加密总开关（应急回滚用，生产建议常开）
      keyring: k1=<base64 32 字节密钥>   # 首位=当前加密密钥，其余仅解密
      active-key-id: k1       # 可选；不填取 keyring 首位（滚动发布安全轮换见 11.3）
      backfill-enabled: false # 存量明文一次性刷数开关（默认关闭）
```

生产密钥经 `APP_CRYPTO_RECEIVER_MOBILE_KEYRING` 环境变量注入（见 11.3），dev 默认 key **仅供本地**。

## 8. 测试

`C:\D\tools\maven\bin\mvn.cmd -q -pl core-order test`

| 类 | 覆盖 |
|----|------|
| `OrderStateMachineTest`（18） | 8 值状态机全迁移矩阵、非法迁移 4002、幂等重放 no-op、流水写入、**终态同状态重复请求 4002**、**父子漂移自愈**、**子单不可流转时整体回滚** |
| `OrderStateMachineCasTest`（3） | **CAS 败方三条路径**：重读已被并发方推进→正常提交、重读非目标态→整体回滚、CAS 假成功→组内一致性断言拦截 |
| `OrderCreateSplitTest`（5） | 多店铺拆单、优惠比例分摊 + 尾差归集到金额最大子单（Σ恒等）、`order_no` 24 位、预占联动 |
| `DiscountAllocatorTest`（12） | 分摊器不变式：尾差落最大基数、并列取首个、Σ恒等、空/负基数拒绝 |
| `OrderPrivacyMaskTest`（5） | 收货人手机号脱敏：买家详情 / 管理端列表 / 管理端详情 / 实体侧读到的是解密后明文 / 脱敏规则边界（断言经 common `MaskUtil`） |
| `OrderAdminReceiverMobileTest`（11） | **明文专用接口**：有权返回明文 + 写留痕（订单号/操作人/事由/from==to）、无权限码 403/1004、不含本码 403、空白码 403、买家角色 403、无操作人 401/1002、订单不存在 4001、每次查看累计留痕、返回体无批量结构、其它出参仍脱敏、留痕不改状态不发事件 |
| `OrderInternalSnapshotTest`（7） | **快照字段收窄**：字段集恰好 6 个、无明文手机号/姓名/地址/remark/couponId、按 core-payment 解析口径取额（pay=90≠total=100）、core-promotion 口径 status 为数字、订单不存在 4001 |
| `OrderIdempotencyTest`（3） | **场景④** 同键 409/1005 只建一笔、缺键 1001、键作用域按 METHOD:URI 隔离 |
| `OrderTimeoutCloseTest`（4） | **场景②** 超时扫描 → 6 已取消 + 释放库存 + 事件；未到期不关 |
| `OrderRaceTest`（3） | **场景⑥** 关单 vs 支付回调：确定性两向 + 并发 latch 终态裁决 |
| `OrderLifecycleTest`（10） | 支付/发货/确认收货/取消/后台关单、事件与流水、幂等空操作 |
| `web/HealthControllerTest`（1） | 健康检查信封 |
| `crypto/ReceiverMobileCipherTest`（19） | **加解密核心**：随机 IV / 密文四段格式 / 往返一致（含超长）/ 空值语义 / 存量明文直出 / **跨密钥轮换**（写新读旧、`active-key-id` 两阶段、摘旧密钥 fail-closed）/ 篡改与格式非法抛 9999 / **开关停写不停读** / keyring 配置非法启动失败 / JPA 包装异常还原 |
| `ReceiverMobileCryptoTest`（14） | **读写路径全覆盖**：落库为密文不含明文、同号两次密文不同、买家/管理端出参仍脱敏、管理端明文接口解密后返回明文且留痕、internal 快照无 receiver 字段、事件载荷无明文、**存量明文行读取正常**、明文密文同列共存、篡改/缺密钥/段数非法 fail-closed |
| `ReceiverMobileBackfillTest`（4） | **存量刷数**：只转明文行（密文行/空值行不动）、keyset 分页、**幂等**（重跑 0 行）、刷数后出参口径不变、开关默认关闭时 Runner 不装配 |
| `ReceiverMobileDisabledTest`（5） | **回滚开关** `enabled=false`：新写入退回明文、历史密文行仍能读、keyId 缺失 fail-closed、刷数跳过、开关之间互不影响 |
| `ReceiverMobileColumnGuardTest`（2） | **列宽巡检**：列宽足够打 INFO；列宽不足打 ERROR 并给出扩列 SQL |

测试为 mem H2 唯一库名 + `ddl-auto=create-drop` + 把扫描间隔调大以禁用调度器；
下游 5 个客户端全部 `@MockBean`（不依赖外部进程）。

## 9. P0 与 plans 的差异 / TODO

- **创建时拆单**（plans 描述为支付后拆）——本模块口径，需在计划文档回写；
- 促销降级 0 折扣、运费 0；退款/退券已接通真实通道（失败只告警、对账兜底）；
- 下单核销成功后若订单持久化失败，券仍停留在已核销（TODO P1：Saga/本地消息表补偿）；
- `order_main.coupon_id` 依赖下单侧落库（`OrderCreateService`）；未落库的存量订单退券安全跳过；
- 物流单号未落 logistics 服务（TODO P1）；
- `order.shipped` / `order.completed` 事件名待注册表登记；
- 「按收货人手机号检索订单」在密文化后不可用，P1 需按 11.6 加 `receiver_mobile_hash`
  （HMAC-SHA256 + 独立检索密钥）列与索引（TODO P1）；
- 手机号密钥目前只来自配置，P2 需接 KMS/Vault（`ReceiverMobileCipher` 的 keyring 装载点已预留）。

## 11. 收货人手机号落库加密（AES-256-GCM，审计 P1 登记项）

接口出参早已按 plans 12.5/12.6 脱敏（P0-B4），但 `order_main.receiver_mobile` 列里是明文——
库文件、备份、只读副本、运维直连都能绕过接口层拿到全量手机号。本节把该列改为
**字段级加密**，并把密钥管理、存量兼容、检索限制一并交代清楚。

### 11.1 密文格式（自描述，明文/密文同列共存）

```
v1:<keyId>:<iv_base64>:<ciphertext_base64>
```

| 段 | 含义 |
|----|------|
| `v1` | 格式版本前缀，**读取侧判定「密文还是存量明文」的唯一依据** |
| `keyId` | 密钥标识（keyring 里的 `k1=`），多密钥轮换靠它定位解密密钥 |
| `iv_base64` | 12 字节（96 位）随机 IV，**每次加密必新**（`SecureRandom`） |
| `ciphertext_base64` | 明文 UTF-8 字节 + 16 字节 GCM 认证标签 |

算法：`AES/GCM/NoPadding`，密钥 256 位、标签 128 位。11 位手机号的密文约 **65 字符**。

- **GCM 提供机密性 + 完整性**：密文被改一个字符或密钥不对，`doFinal` 抛
  `AEADBadTagException` → 本模块转成 **`9999` 明确业务异常**，绝不返回空串/密文串
  （返回垃圾会让下游把它当真手机号发短信/打客服，比报错更糟）；
- **随机 IV 的收益**：同一手机号每次密文都不同，无法用「密文相等」反推「手机号相等」，
  也让刷数天然幂等（不存在「是不是同一份密文」的判定歧义）。

### 11.2 列宽：varchar(20) → varchar(255)

明文 11 位 + 密文封装后约 65 字符，原 `varchar(20)` 装不下。选择**改列长为 255**
（而非新增列）：H2 与 MySQL 都原生支持 `varchar(255)`，且读写语义仍在同一列，
「按前缀识别明文/密文」的规则只有一处实现，不存在「两个列谁权威」的歧义。

> ⚠️ **存量库必须手工扩列一次**（H2 `ddl-auto=update` 与 MySQL 都**不会**改已有列长度）：
>
> ```sql
> -- MySQL
> ALTER TABLE order_main MODIFY COLUMN receiver_mobile VARCHAR(255);
> -- H2（本地 ./data/core-order）
> ALTER TABLE order_main ALTER COLUMN receiver_mobile VARCHAR(255);
> ```
>
> 启动期 `ReceiverMobileColumnGuard` 会读列元数据：列宽不足直接打 **ERROR 并附上修复 SQL**
> （不会静默放行、也不会把服务卡在起不来）。本地最省事的做法：删掉 `./data/core-order.*` 重来。

### 11.3 密钥管理与轮换

配置 `app.crypto.receiver-mobile.keyring=k1=<base64>,k2=<base64>`，**首位为当前加密密钥**，
其余仅用于解密。密钥来源可替换：P0/P1 无 KMS/Vault，经配置注入；P2 接 Vault/KMS 时
只需替换「装载 keyring」这一处实现，算法与密文格式不动。

| 开关 | 默认 | 语义 |
|------|------|------|
| `enabled` | `true` | `false` = **只停写不停读**（见下） |
| `keyring` | dev 默认 key | 生产经 `APP_CRYPTO_RECEIVER_MOBILE_KEYRING` 注入 |
| `active-key-id` | 空（取 keyring 首位） | 显式指定写密钥，滚动发布安全轮换用 |
| `backfill-enabled` | `false` | 存量刷数开关，见 11.4 |

**缺密钥 = 启动失败（fail-fast）**：`enabled=true` 却拿不到密钥时直接抛异常让容器起不来，
**不静默降级成明文落库**（与支付域「金额 fail-closed」同源：PII 路径静默降级等于没人发现地
把手机号裸写回库）。运维要临时明文跑，必须**显式** `enabled=false`。

**开关是「只停写不停读」**：`enabled=false` 时新写入退回明文，但**读取仍按前缀解密**——
否则已加密的存量行会把 `v1:k1:...` 直接当手机号渲染给买家/管理端。
生产建议常开，关闭只在「密文链路出故障、需要立刻恢复下单」时临时使用。

**轮换（三阶段，必须分三步）**：

```
阶段一  keyring=k1=<旧>,k2=<新>，active-key-id=k1     所有实例认得 k2，但仍用 k1 写
阶段二  keyring=k1=<旧>,k2=<新>，active-key-id=k2     全部实例改用 k2 写
阶段三  keyring=k2=<新>，active-key-id=k2             存量刷数完成、k1 无引用后摘除
```

为什么必须分阶段：若阶段一就把新密钥放首位，**滚动发布期间**仍持旧配置（keyring 只有 k1）的
实例读到新实例写的 `v1:k2:...` 会 fail-closed 报「缺少密钥 k2」，订单查询直接不可用。
`active-key-id` 就是为了把「认得新密钥」和「用新密钥写」这两件事解耦。

### 11.4 存量明文兼容与刷数

- **读取零改造**：没有 `v1:` 前缀一律按明文直出，所以明文行与密文行可在同一列共存，
  **不需要停机刷数**即可上线；
- **写入零改造**：`OrderMain.receiverMobile` 挂了 JPA `AttributeConverter`
  （`ReceiverMobileConverter`），**列里是密文、实体里是明文**——
  写入自动加密、读取自动解密，`OrderQueryService` 的脱敏与明文接口一行未改，
  也就不会出现「某条新链路忘了 decrypt、把 `v1:` 串当手机号渲染」的隐蔽事故；
- **刷数是显式开关的一次性动作**（`backfill-enabled=true` 时启动期执行，默认关闭）：
  绕开实体走 JDBC 扫 `receiver_mobile NOT LIKE 'v1:%'` 的行，用 keyset 分页
  （`id > lastId ... LIMIT n`，不用 OFFSET，边改边翻不漏行）逐行改写，UPDATE 带
  `AND receiver_mobile = <原值>` 乐观守卫，命中 0 行即视为已被并发实例刷过（幂等）；
  已加密行与空串/NULL 行一律跳过；单行失败只记 ERROR 并计数，不中断整批（坏行会 fail-closed
  挡住读取，必须有人看见，故结束时报 `[刷数告警]`）。

### 11.5 读写路径覆盖清单（自检）

| # | 路径 | 处理 |
|---|------|------|
| 1 | 下单写父单 `OrderCreateService.persist` | Converter 自动加密（父子单同源，父单承载收货快照） |
| 2 | 买家端详情 `buyerDetail` | 读时解密 → `MaskUtil.maskMobile` 脱敏 |
| 3 | 买家端列表 `buyerList` | 不返回手机号字段 |
| 4 | 管理端列表 `adminList` | 读时解密 → 脱敏 |
| 5 | 管理端详情 `adminDetail` | 读时解密 → 脱敏 |
| 6 | 管理端明文专用接口 `receiverMobileForShip` | 读时解密 → 返回**本单明文**（权限码 + 留痕不变，见 1.2） |
| 7 | 服务间快照 `internalOrder` | 字段集 6 个，**不含 receiver\* 字段**（`ReceiverMobileCryptoTest` 用 record components 钉住） |
| 8 | 订单导出/对账 | 本模块无导出接口；对账走 7 的 internal 快照（不含手机号） |
| 9 | 领域事件载荷（`ORDER_CREATED/PAID/CANCELLED/STATUS_CHANGED/REFUND_APPLIED`） | 载荷只含 orderId/orderNo/userId/金额/状态，**无 receiver 键也无手机号值**（用例断言） |
| 10 | 日志 | 全模块无 `getReceiverMobile()` 入日志；`OrderMain` 无 `toString`/Lombok 打印；异常消息只含 keyId |
| 11 | 超时关单/状态机/支付回调 | 走实体读取，解密失败同样 fail-closed（见 11.6 影响面） |

### 11.6 已知限制与运维注意

- **检索限制（AES-GCM 无法等值查询）**：`WHERE receiver_mobile = '138…'` 以后**不再可用**
  （密文每次都不同，且加了 GCM 认证也不能拿来做等值匹配）。若后续要「按手机号查订单」，
  P1 方案：新增 `receiver_mobile_hash` 列存 **`HMAC-SHA256(检索密钥, 手机号)`**
  （Base64/HEX 定长，可建普通 B-Tree 索引等值查），密钥与加密密钥**分开存放**——
  检索密钥不能与加密密钥同源，否则 hash 泄露即等同于密文泄露（彩虹表/GCM 不可枚举但 hash 可被定向爆破）。
  **本任务未实现该列**，仅登记方案。
- **解密失败是「整单读取失败」**：加解密挂在实体字段上，一行坏数据会让该订单的
  买家详情 / 管理端查询 / 状态流转 / prepay 快照全部 fail-closed（9999）。这是刻意的取舍
  （篡改或密钥丢失属重大事件，宁可显式失败也不出假数据），代价是要靠告警及时发现并修数据。
- **密钥丢失 = 数据永久不可读**：应用层密钥必须离线留档（KMS/Vault 之外的兜底），
  `application.yml` 里的 dev key **只供本地**，禁止上生产。
- **JPA 会把解密异常包两层**（`PersistenceException` → Spring Data `JpaSystemException`），
  `OrderQueryService.piiRead(...)` 已沿 cause 链还原为带原因的 9999，避免运维只看到「系统错误」。
- **旧版本回滚**：老版本代码读不懂 `v1:` 密文，回滚前需先 `enabled=false` 停止加密写入，
  或接受老版本对加密行的读取失败（fail-closed）。

## 12. 审查记录

| 时间 | 内容 |
|------|------|
| 2026-10-12 | **审计 P1 遗留项**：① 新增管理端明文手机号专用接口（权限码 + 留痕，见 1.2）；② internal 订单快照收窄为显式字段 DTO（见 1.3），`internalOrder` 不再返回 `OrderMain` 实体；③ 脱敏算法改用公共工具 `common/api/MaskUtil`（本模块 `maskMobile` 保留为薄委托，既有用例零改动）。新增 `OrderAdminReceiverMobileTest`(11) / `OrderInternalSnapshotTest`(7)；`mvn -o -q -pl common,core-order test` 全绿（common 17 / core-order 99），core-payment 12 项回归全绿（未改其任何代码） |
| 2026-10-12 | **审计 P1「收货人手机号落库仍明文」落地**（本次）：`com.order.crypto` 新增 5 个类（`ReceiverMobileCipher` AES-256-GCM + 自描述密文 `v1:keyId:iv:ct` + 多密钥轮换 + 缺密钥 fail-fast、`ReceiverMobileConverter` 持久化边界自动加解密、`ReceiverMobileBackfillService/Runner` 显式开关幂等刷数、`ReceiverMobileColumnGuard` 列宽巡检），见 11；`OrderMain.receiver_mobile` 列 20→255 并挂 Converter，`OrderQueryService` 仅新增 `piiRead` 异常还原层（脱敏/明文接口/契约零改动）。**唯一改动的既有用例**：`OrderPrivacyMaskTest` 改名「脱敏只发生在出参_实体侧读到的是解密后明文」（断言一字未动，只是不再宣称「落库仍为明文」）。新增 `ReceiverMobileCipherTest`(19)/`ReceiverMobileCryptoTest`(14)/`ReceiverMobileBackfillTest`(4)/`ReceiverMobileDisabledTest`(5)/`ReceiverMobileColumnGuardTest`(2)；`mvn -o -pl core-order test` 全绿 **143**（原 99 + 新 44），未改任何其它模块 |
