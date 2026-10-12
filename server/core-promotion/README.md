# core-promotion（:8088）

营销服务 P0 实现：优惠券模板 CRUD（管理端）/ 领取（防超领、限领）/ 试算 / 核销（防重复）/ 关单退回 / 过期翻转。
拼团/砍价 P0 不做（plans 标 P1）。业务设计来源：`plans/admin-backend.md` 1.2.2 营销模块 DDL + 七、营销服务。

## 端点清单

### 买家侧（登录；`/api/v1/coupons/*` 与 `/api/v1/coupon/*` 双注册等价，见"路径口径"）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/coupons/receive`（别名 `/api/v1/coupon/receive`、`/api/v1/coupon/{id}/receive`） | 领券 `{couponId=模板ID}`。**防超领**：库存条件更新 `received < total` 才自增（原子，并发只成功 N 次）；**防限领超发**：同一模板的并发领取在 `SELECT ... FOR UPDATE` 临界区内串行判限领（`READ_COMMITTED` 下计数必读已提交值，见"并发控制口径"）+ 时间窗/下线校验，同事务任一步失败整体回滚 |
| POST | `/api/v1/coupons/preview`（别名同上） | 试算 `{couponId=用户券记录ID, amount[]}`：金额列表求和后算优惠（满减/无门槛减面额、折扣券按 value 折，HALF_UP 两位小数）。不满足门槛/不可用 → `eligible=false, discount=0, reason`（不抛异常）。**入参非法（空数组/缺省/null 元素/负数）→ 1001，HTTP 层为 400**（`BizException` 的 1001 走信封口径是 200，故空数组额外用 `@NotEmpty` 在入参层拦住，避免客户端把畸形请求误读成"券不可用"而静默按原价下单） |
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

改"券资源"的写操作全部走**数据库层的条件更新或互斥临界区**（多实例安全，不依赖进程内锁）：

| 操作 | 机制 | 并发保证 |
|------|------|---------|
| 领取预占（总量） | `UPDATE ... SET received=received+1 WHERE id=? AND status=1 AND received<total AND now BETWEEN start AND end` | 并发只成功 N 次（N=剩余库存） |
| **每人限领** | `SELECT ... FOR UPDATE` 领取临界区 + `READ_COMMITTED` 隔离级别下重查持券数（`CouponTemplateRepository.findByIdForReceive`） | 同一模板的并发领取在**模板行锁**上串行，计数必然读到前序事务已提交的记录 → 恰 `perLimit` 个成功 |
| 核销 | `UPDATE ... SET status=1, used_order_id=?, used_time=? WHERE id=? AND status=0 AND expire_at>=now` | 同券并发只成功一次；过期校验也在 SQL 内（无 TOCTOU） |
| 退回 | `UPDATE ... SET status=0, used_order_id=NULL WHERE id=? AND status=1` | 幂等，重复执行只有第一次生效 |
| 过期翻转 | `UPDATE ... SET status=2 WHERE status=0 AND expire_at<now` | 幂等，多实例重复跑无害 |

**每人限领为什么必须加锁（审计 A6-P1）**：限领是「该用户在该模板下已有几张券」的**跨行计数**，
原先的「先 SELECT 计数、再 INSERT」之间没有原子性——同一用户并发 N 次领取全部读到 `owned=0 < perLimit`，
限领 1 的券会被同一用户领走 N 张。现已移进 `SELECT ... FOR UPDATE` 临界区，且显式声明
`READ_COMMITTED`：**RR（MySQL 默认）下事务快照在加锁前就固定了，拿到锁后计数仍会读到旧值**。

**已验证不可行的替代方案**（勿再尝试）：
`UPDATE coupon_template SET received=received+1 WHERE ... AND (SELECT COUNT(*) FROM coupon_record WHERE ...) < per_limit`
看似单语句原子，实测不成立——子查询按**语句快照**求值（求值发生在等待行锁之前），
16 并发下有 10 个请求同时读到 `COUNT=0` 全部放行（H2 实测）。
另一条路是引入计数表（`coupon_id+user_id` 唯一键 + 条件自增），代价是 plans DDL 未登记该表 + 计数漂移风险，列为 P1。

## 数据表

- `coupon_template`：雪花主键、type 1满减/2折扣(0<v<10，如8.5=85折)/3无门槛、value/min_amount DECIMAL(10,2)、
  total/received/used_count、per_limit、start/end_time、status、@Version。
- `coupon_record`：雪花主键、coupon_id=模板ID、user_id、status 0可用/1已使用/2已过期、
  used_order_id/used_time、expire_at（领取时取模板 end_time 快照，模板改期**不**追溯存量券）、received_at。
  索引 `idx_coupon_user(coupon_id,user_id)`（对齐 plans DDL）：既服务「我的券/领取记录」，
  也是限领计数走索引的前提。

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

**fail-safe 判定矩阵（回查 core-order `GET /api/v1/internal/orders/{id}`）**——一切不确定都不回收：

| 回查结果 | 结论 | 是否回收 |
|---|---|---|
| `code=4001`（订单不存在） | ORDER_GONE | ✅ 回收 |
| `code=0` 且 `status ∈ {6,7}` | ORDER_GONE | ✅ 回收 |
| `code=0` 且 `status ∈ 0..5`（AGENTS.md 0 基 8 值） | ORDER_ALIVE | ❌ |
| `code=0` 但 `status` **越界**（如 99/8/-1，枚举漂移） | UNKNOWN | ❌ |
| `status` 缺失/非数字（契约漂移） | UNKNOWN | ❌ |
| `code≠0 且 ≠4001` / 连接失败 / 超时(300ms 连接,1s 读) | UNKNOWN | ❌ |

超时/异常**只影响单条记录**（逐条 catch 后按 UNKNOWN 处理，不中断本轮扫描），调度任务整体再兜一层
`catch` 只记日志不抛——大促抖动最多造成"回收延迟到下一轮"，不会误退券、也不会打挂调度线程。

## 测试

`mvn -o -q -pl core-promotion test`（mem H2，共 53 例）：① 预占最后 1 张券并发安全（8 线程只 1 人成功）
② 核销并发只成功一次（8 线程不同订单）③ 防超领/每人限领（3002/4002）
④ 核销/退回/同订单重试幂等 ⑤ 试算满减+折扣+门槛不足 ⑥ 过期翻转且过期券不可核销
⑦ 管理端 CRUD + admin 角色校验 ⑧ 越权防线（无身份头 403/1004、他人券 403、internal 端点带密钥可调）
⑨ 核销未确认回收（订单没了→退回；重复跑幂等；订单有效/下游不可达/未超时→不回收）
⑩ **每人限领并发正确性**（`CouponPerLimitConcurrencyTest`：16 线程 × 12 轮抢 1/3 个名额，
成功数恰为 perLimit、失败全为 4002、无重复记录、receivedCount 无泄漏；另有按用户隔离、限领+总量双上限、
定向发券计入持券数的用例）
⑪ **兜底回收稳健性**（`CouponReclaimRobustnessTest`：回查返回越界状态码/契约漂移 → 绝不回收（真实
`RestDeductedOrderVerifier` 打桩 core-order 端到端）、0 基 8 值映射未被误伤、单条超时不影响同轮其他记录、
脏数据跳过、任务吞异常、测试环境 cron 禁用）
⑫ **试算/核销边界**（`CouponBoundaryTest`：空数组/缺省/null 元素/负数入参、门槛差 1 分、
折扣 HALF_UP 进位、折扣券 value 越界、面额>订单额封顶、过期/已用券 ineligible、
订单已支付后重复核销幂等、错单核销/错单退回被拒、过期券核销被拒）。

## 变更记录

**2026-10-12 营销域 P1 遗留项补齐**（审计 A6-P1 + P0-G 后续 + 试算/核销边界，仅动 core-promotion）：

1. **每人限领并发超发（审计 A6-P1，行为变更）**：`CouponService.receive` 的限领判定移入
   `SELECT ... FOR UPDATE` 临界区（`CouponTemplateRepository.findByIdForReceive`），
   事务隔离级别显式声明 `READ_COMMITTED`。错误码不变（超限 4002 / 领完 3002）。
   对外接口、参数、响应结构均未变；变的只是并发下不再超发。
   连带：`coupon_record` 增 `idx_coupon_user(coupon_id,user_id)`（对齐 plans DDL）。
2. **`RestDeductedOrderVerifier` 越界状态码加固**：`status` 不在 0..7（AGENTS.md 0 基 8 值）时判
   `UNKNOWN` 不回收（原实现把任何非 6/7 的值都当 `ORDER_ALIVE`，回收结论虽同为"不退"，但语义失真、
   枚举漂移不可观测）。0~7 的既有映射不变。
3. **兜底回收可观测性**：`used_order_id` 为空的脏数据此前静默 `continue`，改为 WARN 留痕。
4. **`preview` 空数组入参**：HTTP 层由 `200 + code 1001` 改为 `400 + code 1001`（`PreviewRequest.amount`
   增 `@NotEmpty`）；服务层参数校验前移到券状态判定之前（非法入参不再伪装成"券不可用"）。
5. 新增 3 个测试类 / 31 例（并发限领、回收稳健性、试算核销边界），既有 22 例断言**未删改**。
