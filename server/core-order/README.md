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

### 1.3 内部跨服务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/internal/orders/{orderId}/paid` | core-payment 支付成功回调；幂等（重复回调 `ALREADY_PAID`），与超时关单并发时 version CAS 裁决 |

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

- `OrderStateMachine.transition`：**幂等判断先于白名单/终态校验**（`from == target` → no-op `false`，不抛 4002）；
- 白名单外流转抛 `ORDER_STATUS_ERROR(4002)`；
- 每次流转写 `order_status_log`（含建单 `-1 → 0` 初始条目），`orderId` 落 NOT NULL 列（修复过的隐性缺陷）；
- 所有流转走 `OrderMainRepository` 的 CAS：`WHERE id=? AND version=? AND order_status=?`，
  同时 `version+1`，并发只有一方成功，输的一方重读裁决。

## 3. 下单与拆单（P0 口径）

流程：入参校验 → core-product/price 取商品与价 → `core-inventory preview/reserve` 预占
→ core-promotion 扣券 → 按 `store_id` 拆子单 → 写 `order_main`（父+子）/`order_item` → 发 `order.created`。

- **创建时拆单**（与 `plans/*` 中「先建父单、支付后再拆」的描述不同，README 特此标注）；
- 优惠按子单金额比例分摊，**尾差归集到首单**（按店铺 ID 升序取第一单；plans 原文为「金额最大的子单」，
  实现与原文差异见 `DiscountAllocator` 注释），保证 `Σ子单优惠 = 父单优惠`；
  行级优惠同理 `Σ行 = 子单优惠`，尾差归首行；
- 金额 BigDecimal，行金额/优惠不落浮点（DEV.md 4）；
- **P0 促销降级**：core-promotion 核销契约未就绪 → 优惠按 0 计 + WARN 日志 + TODO
  （`RestPromotionClient`：P1 必须删除降级分支，失败要抛错回滚，禁止静默按原价成交）；
- 运费 P0 恒 0（TODO：运费模板按店铺计算）。

## 4. 超时关单与竞态裁决（测试要求②⑥）

- `pay_deadline = 建单时间 + pay-timeout-minutes`（默认 30 分钟，产品待确认）；
- `OrderTimeoutCloseScheduler` 每 `app.order.timeout-scan-ms`（默认 10000ms）扫 `now > pay_deadline`
  且状态 0 的父单 → CAS 置 6 已取消 → 释放库存（`core-inventory release`）→ 发 `order.cancelled`；
- **关单 vs 支付回调竞态**：双方都用 `version + order_status` CAS，先到者胜——
  - 关单赢：支付回调重读后发现 `CLOSED/REFUNDING` → 调 `paymentClient.refund()` 原路退款
    （P0 `RestPaymentClient` 为日志桩，TODO 待 core-payment 契约），返回 `REFUND_TRIGGERED`；
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
```

## 8. 测试

`C:\D\tools\maven\bin\mvn.cmd -q -pl core-order test`

| 类 | 覆盖 |
|----|------|
| `OrderStateMachineTest`（12） | 8 值状态机全迁移矩阵、非法迁移 4002、幂等重放 no-op、流水写入 |
| `OrderCreateSplitTest`（4） | 多店铺拆单、优惠比例分摊 + 尾差归集（Σ恒等）、`order_no` 24 位、预占联动 |
| `OrderIdempotencyTest`（3） | **场景④** 同键 409/1005 只建一笔、缺键 1001、键作用域按 METHOD:URI 隔离 |
| `OrderTimeoutCloseTest`（4） | **场景②** 超时扫描 → 6 已取消 + 释放库存 + 事件；未到期不关 |
| `OrderRaceTest`（3） | **场景⑥** 关单 vs 支付回调：确定性两向 + 并发 latch 终态裁决 |
| `OrderLifecycleTest`（10） | 支付/发货/确认收货/取消/后台关单、事件与流水、幂等空操作 |
| `web/HealthControllerTest`（1） | 健康检查信封 |

测试为 mem H2 唯一库名 + `ddl-auto=create-drop` + 把扫描间隔调大以禁用调度器；
下游 5 个客户端全部 `@MockBean`（不依赖外部进程）。

## 9. P0 与 plans 的差异 / TODO

- **创建时拆单**（plans 描述为支付后拆）——本模块口径，需在计划文档回写；
- 尾差归集到「首单（店铺 ID 升序）」而非 plans 的「金额最大子单」；
- 促销降级 0 折扣、运费 0、支付退款为日志桩（`RestPaymentClient` TODO）；
- 物流单号未落 logistics 服务（TODO P1）；
- `order.shipped` / `order.completed` 事件名待注册表登记。
