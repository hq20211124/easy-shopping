# core-inventory 库存服务

交易域核心服务之一：**预占（reserve）/ 释放（release）/ 扣减（deduct）/ 校验（preview）**，
保证高并发下不超卖。对应 `plans/admin-backend.md` 第 5 章（5.3.1 库存并发模型）、
`plans/ios-backend.md` 4.4.4（预占与超时释放）。

- 端口 `8084`，P0 H2 文件库 `./data/core-inventory`（MySQL 兼容模式，`./data/` 已 gitignore）
- 雪花 `app.worker-id: 4`（分库分表禁止 AUTO_INCREMENT，裁决 C13）
- 领域事件：`DomainEventNames.INVENTORY_CHANGE`（仅事务提交后发布）

## 1. 接口契约

### 1.1 内部跨服务（core-order 调用）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/internal/inventory/preview` | 下单前只读校验，不占库，返回 `allAvailable` + 每 SKU 明细 |
| POST | `/api/v1/internal/inventory/reserve` | 预占 `available → locked`，入参 `{orderId, items[{skuId, quantity}], ttlSeconds}`，全 SKU 原子成功（任一不足整体失败，无部分预占） |
| POST | `/api/v1/internal/inventory/release` | 释放 `locked → available`（订单取消/超时关单），入参 `{orderId}` |
| POST | `/api/v1/internal/inventory/deduct` | 出库扣减（确认收货），`locked` 减少且**不回补 available**，入参 `{orderId}` |
| POST | `/api/v1/internal/inventory/stocks` | 批量写入查询（内部批处理） |

### 1.2 对外查询

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/inventory/stocks?skuIds=1,2,3` | 库存视图 `{skuId, availableStock, lockedStock, totalStock}`；core-cart 列表展示也走此契约 |
| POST | `/api/v1/internal/inventory/stocks` | P0 联调初始化 SKU 库存（已存在则拒绝，避免覆盖账本；管理端出入库属 admin-inventory 域） |
| GET | `/health` | 统一信封健康检查 |

## 2. 数据模型

- `stock`：`sku_id`、`available_stock`、`locked_stock`、`@Version version`
- `stock_reservation`：`order_id`、`sku_id`、`quantity`、`expire_at`、`status(ACTIVE/RELEASED/DEDUCTED/EXPIRED)`、`@Version version`
  （`order_id` 是 release/deduct 的业务幂等键）

## 3. 并发与竞态处理（核心）

**模型**：admin-backend 5.3.1 v1.1「Redis 仅是闸门、DB 是账本」。P0 无 Redis，
直接以 DB `@Version` 乐观锁 + 同事务内「读-校验-改-写」闭合 TOCTOU：

- 每次变更跑在独立 `TransactionTemplate` 单元里；`OptimisticLockingFailureException` 整单元重试，
  最多 `MAX_OPTIMISTIC_RETRIES = 5` 次；
- 重试耗尽仍冲突 → `BizException(OUT_OF_STOCK=3002)`「库存紧张，请重试」，**绝不静默超卖**；
  可用不足同样 3002；
- **幂等**：reserve 以「同 orderId 已有 ACTIVE 预占单」直接返回（下单重试安全）；
  release/deduct 靠预占单状态机 `ACTIVE → RELEASED/DEDUCTED` 单向流转 + `@Version`
  （重复释放等于白送库存，是比重复扣减更危险的一侧，plans 4.4.4 第 4 条）；
- **超时释放**：`InventoryExpireScheduler` 每 `app.inventory.expire-scan-ms`（默认 30000）
  扫 `expire_at < now` 且 ACTIVE 的预占单逐条释放，单条失败不阻塞整轮；
  P1 改 RabbitMQ 延迟队列，`deliveryTimestamp` 直取 `pay_deadline`。

**并发证明**：`InventoryServiceTest` 含 TOCTOU 用例——两线程争抢最后 1 件，
恰一线程成功、另一线程 3002，最终 `available=0` 不为负。

## 4. 领域事件

事务提交后经 `AfterCommitEvents` 发布 `DomainEventNames.INVENTORY_CHANGE`，
负载含 `{skuId, delta, available, locked, reason(reserve|release|deduct|expire-release), orderId}`。
P0 用进程内 `InMemoryDomainEventPublisher`；P1 换 RabbitMQ（拓扑见 `architecture/04-messaging.md`）。

## 5. 配置

```yaml
app:
  worker-id: 4
  inventory:
    expire-scan-ms: 30000   # 预占超时扫描间隔，P0 内存扫，P1 换 MQ 延迟
```

预占默认 TTL `DEFAULT_RESERVE_TTL_SECONDS = 30*60`，与订单 `pay_deadline`（`app.order.pay-timeout-minutes=30`）对齐。

## 6. 测试

`C:\D\tools\maven\bin\mvn.cmd -q -pl core-inventory test`（**不要** `-am`，common 已安装到 .m2）

| 类 | 覆盖 |
|----|------|
| `InventoryServiceTest` | 预占幂等、库存不足 3002、全量原子性、release/deduct 幂等与状态机、超时扫描释放、**并发抢最后 1 件不超卖** |
| `web/HealthControllerTest` | 健康检查信封 |

测试用 `jdbc:h2:mem:` + `ddl-auto=create-drop`（唯一库名），不污染 `./data` 开发库。

## 7. P0 与 plans 的差异 / TODO

- Redis 闸门未接（P0 单机 DB 乐观锁已足够；P1 引入 `inventory:lock:{skuId}` 原子闸门）；
- 超时释放是**内存扫描**兜底（P1 → MQ 延迟队列，plans 4.4.4）；
- 运费/赠品出库、批次先进先出未在 P0 范围。
