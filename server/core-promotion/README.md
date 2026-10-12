# core-promotion（:8088）

营销服务 P0 实现：优惠券模板 CRUD（管理端）/ 领取（防超领、限领）/ 试算 / 核销（防重复）/ 关单退回 / 过期翻转。
拼团/砍价 P0 不做（plans 标 P1）。业务设计来源：`plans/admin-backend.md` 1.2.2 营销模块 DDL + 七、营销服务。

## 端点清单

### 买家侧（登录；`/api/v1/coupons/*` 与 `/api/v1/coupon/*` 双注册等价，见"路径口径"）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/coupons/receive`（别名 `/api/v1/coupon/receive`、`/api/v1/coupon/{id}/receive`） | 领券 `{couponId=模板ID}`。**防超领**：库存条件更新 `received < total` 才自增（原子，并发只成功 N 次）+ 每人限领预检 + 时间窗/下线校验，同事务任一步失败整体回滚 |
| POST | `/api/v1/coupons/preview`（别名同上） | 试算 `{couponId=用户券记录ID, amount[]}`：金额列表求和后算优惠（满减/无门槛减面额、折扣券按 value 折，HALF_UP 两位小数）。不满足门槛/不可用 → `eligible=false, discount=0, reason`（不抛异常） |
| POST | `/api/v1/coupons/deduct`（别名同上） | **核销** `{couponId=用户券记录ID, orderId}`：条件更新 `status=0→1 + 绑定 orderId`（防并发重复核销）；**同订单重试幂等成功**，他订单占用/已过期 → 4002。成功后模板 usedCount+1。**强制校验券主归属** |
| POST | `/api/v1/coupons/release`（别名同上） | 关单退回 `{couponId, orderId?}`：`status=1→0` 清订单绑定，usedCount-1；幂等（已可用直接成功）；orderId 传入时校验与核销绑定一致。**强制校验券主归属** |
| GET | `/api/v1/coupons/my`（别名同上） | 我的券列表（`?status=` 0可用/1已使用/2已过期） |

**字段口径**：`receive` 的 `couponId` 是**券模板 ID**；`preview/deduct/release` 的 `couponId` 是**用户券记录 ID**
（即 `receive` 响应里的 `couponId` 字段，模板 ID 在响应的 `templateId`）。

### 服务间（core-order 直连，不走网关；共享密钥鉴权）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/internal/coupons/{id}/deduct` | 服务间核销 `{orderId}`（`{id}` = 用户券记录 ID）。**不校验券归属**，鉴权走请求头 `X-Internal-Token` |
| POST | `/api/v1/internal/coupons/{id}/release` | 服务间退券 `{orderId?}`。同上鉴权 |

**鉴权**（P0-F 修复）：买家接口需登录；`deduct/release` **必须携带 `X-User-Id` 且为券主本人**，
缺身份或他人券一律 **1004/HTTP 403**（原实现「不携带 X-User-Id 即跳过归属校验」= 任意调用方可核销/退回他人券）。
服务端间调用改走上面的 internal 端点，鉴权为共享密钥 `app.internal.token`
（默认留空 = **拒绝所有 internal 调用** fail-closed；生产用环境变量 `APP_INTERNAL_TOKEN` 注入，
调用方 core-order 需配置同一密钥；网关未配置 `/api/v1/internal/**` 路由）。P1 换 mTLS 后删除该机制。

### 管理端（要求角色 `admin`，即 X-User-Roles 含 admin；admin-bff 直连须透传该头）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/coupon`（别名 `/api/admin/coupons`） | 创建模板 |
| PUT | `/api/admin/coupon/{id}` | 更新（整体覆盖；totalCount 不得低于已领数） |
| DELETE | `/api/admin/coupon/{id}` | 删除（已发放的只能下线，4002 拒删） |
| GET | `/api/admin/coupon/{id}` | 详情 |
| GET | `/api/admin/coupon/list?pageNum=&pageSize=` | 分页列表（PageResult） |
| GET | `/api/admin/coupon/{id}/records` | 领取记录（审计） |
| POST | `/api/admin/coupon/{id}/distribute` | 指定用户发放 `{userId}`（尊重总量，不占每人限领） |

路径对齐 `plans/admin-backend.md` 7.2.1；创建的复数别名兼容任务契约。
注：网关把 `/api/admin/**` 路由到 admin-bff，本组接口供 admin-bff（admin-marketing）**服务间直调**
（`service.urls.core-promotion`），P0 不经网关。

## 路径口径（网关联调注意）

网关 `gateway/application.yml` 的 core-promotion 路由只配了 `Path=/api/v1/coupon/**`（单数），
而任务契约与 `plans/pc-client-backend.md` 用复数 `/api/v1/coupons/*`——因此**两套路径全部双注册、完全等价**。
网关路由表补齐 `/api/v1/coupons/**` 后可收敛（改网关属其负责人，本模块不动）。

## 与订单服务（core-order）的集成点

- **被调**（core-order → core-promotion，服务间直调）：
  下单试算 `POST /api/v1/coupons/preview`、下单锁券 `POST /api/v1/coupons/deduct`、
  关单/支付失败退券 `POST /api/v1/coupons/release`。deduct 幂等（同订单重试安全），release 幂等，
  order 侧可放心重试（幂等接口，P1 OpenFeign 可开重试）。
  **P0-H/P0-F 后建议**：核销与退券改走 `/api/v1/internal/coupons/{id}/deduct|release`
  （带 `X-Internal-Token`），避免依赖 core-order 手工转发 `X-User-Id`。
- **本服务回查 core-order**（仅对账兜底用）：`GET /api/v1/internal/orders/{orderId}` 判断核销的订单是否仍存活。
- **不调用**任何其他服务除上述对账回查外（P0 无营销事件——`DomainEventNames` 30 事件注册表中无营销域事件，
  新增事件须先去 `architecture/04-messaging.md` §7.1 登记）。

## 并发控制口径

所有改"券资源"的写操作均为**条件更新（SQL CAS）**，多实例安全（不依赖进程内锁）：

- 领取预占：`UPDATE ... SET received=received+1 WHERE id=? AND status=1 AND received<total AND now BETWEEN start AND end`
- 核销：`UPDATE ... SET status=1, used_order_id=?, used_time=? WHERE id=? AND status=0 AND expire_at>=now`
- 退回：`UPDATE ... SET status=0, used_order_id=NULL WHERE id=? AND status=1`
- 过期翻转：`UPDATE ... SET status=2 WHERE status=0 AND expire_at<now`（幂等，多实例重复跑无害）

## 数据表

- `coupon_template`：雪花主键、type 1满减/2折扣(0<v<10，如8.5=85折)/3无门槛、value/min_amount DECIMAL(10,2)、
  total/received/used_count、per_limit、start/end_time、status、@Version。
- `coupon_record`：雪花主键、coupon_id=模板ID、user_id、status 0可用/1已使用/2已过期、
  used_order_id/used_time、expire_at（领取时取模板 end_time 快照）、received_at。

注：`coupon_template.value` 是 H2/MySQL 保留字 → 本服务开启 `hibernate.globally_quoted_identifiers=true`
（MySQL 侧同样安全，plans DDL 本就用反引号）。P1 上生产前补 Flyway 脚本（占位 TODO）。

## 定时任务

- `CouponExpireJob`：每小时第 20 分翻转过期券（cron 可配 `app.promotion.expire-cron`）。P0 进程内调度；
  P1 换 MQ 延迟消息 + 分布式调度。
- `CouponDeductReconcileJob`（P0-G 兜底）：每 10 分扫描**核销未确认**的券并回收
  （cron 可配 `app.promotion.deduct-reconcile-cron`）。判定口径：
  `status=used` 且 `used_time`（= 核销时刻）早于 `now - app.promotion.deduct-timeout-minutes`（默认 60 分钟）
  **且**回查 core-order 确认订单**不存在/已取消(6)/已关闭(7)** → 条件更新回 `available`（幂等，`used_count-1`）。
  订单仍有效、或 core-order 不可达（UNKNOWN）→ **不回收**（误回收 = 把已消费券白送用户，资损）；
  漏回收 = 人工可查的对账项。P0 进程内调度，多副本重复扫描因 CAS 收敛而安全。

## 测试

`mvn -pl core-promotion test`（mem H2）：① 预占最后 1 张券并发安全（8 线程只 1 人成功）
② 核销并发只成功一次（8 线程不同订单）③ 防超领/每人限领（3002/4002）
④ 核销/退回/同订单重试幂等 ⑤ 试算满减+折扣+门槛不足 ⑥ 过期翻转且过期券不可核销
⑦ 管理端 CRUD + admin 角色校验 ⑧ 越权防线（无身份头 403/1004、他人券 403、internal 端点带密钥可调）
⑨ 核销未确认回收（订单没了→退回；重复跑幂等；订单有效/下游不可达/未超时→不回收）。
