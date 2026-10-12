# core-payment（:8087）

支付服务 P0 实现：预支付（防改价）/ mock 支付 / 回调幂等三步 / 查单 / 退款 / 对账兜底。
业务设计来源：`plans/admin-backend.md`（4.4 订单状态机协同、13.2.2 定时对账）、
`plans/miniprogram-backend.md` 8.1（prepay 契约 + payment_order DDL）、`plans/ios-backend.md` 七。

## 端点清单

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/payment/prepay` | 预支付。**只收 `{orderId, channel}`**（channel：1支付宝/2微信/3银行卡/4余额）；金额/用户由服务端反查 core-order 定额（裁决 **C7 防改价**，客户端传 `amount` 会被忽略）。同一订单仅一个进行中支付单，重复请求返回原 paymentId；已支付/退款中拒绝 | 登录（X-User-Id） |
| POST | `/api/v1/payment/mock/pay` | P0 mock 付款 `{paymentId}`：模拟用户付款成功，**走与真实回调完全相同的处理链**（幂等键 `mock:{paymentId}`，重复调用不重复入账） | 登录 + 归属校验 |
| POST | `/api/v1/payment/notify` | 支付回调 `{notifyId?, paymentId, amount, thirdPartyNo}`。**回调幂等三步**：① notify_log 唯一键去重（重复通知返回成功）② 状态 CAS（仅 pending→paid）③ 金额校验（不符 → 告警日志 + code 5001 + 事务回滚）。入账后同步 core-order 状态机并发 `payment.success` 事件 | 内网（P0）；真实渠道回调需网关白名单，见下 |
| GET | `/api/v1/payment/status/{orderId}` | 查单轮询，返回 `pending/paid/refunding/refunded` | 登录 |
| POST | `/api/v1/payment/refund` | 退款 `{orderId, reason}`：paid→refunding→refunded，**幂等**（重复退款返回终态、不重复发事件）；供订单关单/售后服务调用 | 内网（P0） |

错误码：5001 支付失败（金额不符/终态拒绝/支付单不存在）、4001 支付单不存在、4002 订单状态不允许（已支付重复预支付/未支付退款）、1004 无权操作他人支付单、3002 未使用。

## 事件

| 事件名（`DomainEventNames` 注册表） | 时机 |
|------|------|
| `payment.success` | 回调入账成功后（事务提交后发布；任务书中的 "payment.paid" 以注册表 `payment.success` 为准，见 DEV.md 4.4） |
| `payment.refund` | 退款完成后（首次；幂等重放不重复发） |

## 与订单服务（core-order）的集成点

统一走 `com.payment.client.OrderClient`（RestClient + `service.urls.core-order`，连接 1s/读 3s）：

| 调用 | 方向 | 时机 | 失败处理 |
|------|------|------|----------|
| `GET /api/v1/orders/{orderId}` | payment → order 反查 | prepay（取金额/用户/pay_deadline，防改价）；对账任务 | prepay 失败直接报错（资金接口禁止降级）；对账下轮重试 |
| `POST /api/v1/orders/{orderId}/paid` | payment → order 状态机通知 | 回调入账成功后；对账任务补偿 | **失败不回滚支付单**（支付事实已成立），留 `order_paid_notified=false`，由对账任务每小时补偿。订单侧须幂等（同状态重复通知直接成功） |

**TODO(core-order 契约)**：订单详情响应字段做了兜底解析（orderId/id、totalAmount/payAmount/amount），
`/paid` 路径为任务书指定的临时通道——core-order 定稿后收敛（若其状态机由 `order.paid` MQ 事件驱动，P1 改纯事件）。
订单状态按 **0 基 8 值**解析（0待付款 … 6已取消/7已关闭，见 AGENTS.md）。

## 对账兜底

`PaymentReconcileJob`（每小时第 15 分，cron 可配 `app.payment.reconcile-cron`）：
扫 `status=paid AND order_paid_notified=false` → 反查订单：
仍待付款 → 补发 `/paid` 并置位；已取消/关闭（6/7）→ **告警**（P1 自动退款）；订单已推进后续状态 → 只置位。
P0 进程内 `@Scheduled` 单实例（补偿幂等，多副本无资金风险）；P1 换 MQ 定时消息 + 分布式调度。

## 幂等口径（重要）

本模块写接口**不用** common 的 `@Idempotent`（进程内 Map，多实例失效），全部落 DB 级幂等：
prepay 按 `uk_payment_order(order_id)` 复用支付单；notify 按 `uk_notify_id` 去重 + 状态 CAS；
refund 按状态 CAS。因此**不要求**客户端携带 `Idempotency-Key` 头。

## 数据表

- `payment_order`：雪花主键、`uk_payment_order(order_id)`、金额 DECIMAL(10,2)、状态 1pending/2paid/3refunding/4refunded、`order_paid_notified` 对账标记、@Version。
- `payment_notify_log`：雪花主键、`uk_notify_id` 回调去重账本。

P1 上生产前补 Flyway 脚本（占位 TODO，见 DEV.md 4.3.2）。

## 测试

`mvn -pl core-payment test`（mem H2）：① 同一 notify 重复 10 次只生效 1 次 ② 金额不符拒绝且可再入账
③ mock 支付同链路幂等 ④ 退款幂等（事件只发一次）⑤ prepay 忽略客户端金额字段（防改价）
⑥ 对账补偿（订单不可用→恢复后补偿 / 订单已 paid→只置位）。
