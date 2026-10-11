# 04 消息与异步化架构（RabbitMQ → RocketMQ 5.x）

> **目标规模**：1 亿同时在线；写 QPS 50 万（日常峰值）~ 100 万（大促峰值）；大促峰值消息量 300 万条/秒
> **基线**：RocketMQ 5.x（替换 10 个计划文件中的 RabbitMQ），Java（核心业务）/ Go（网关、推送）/ Node.js（消息中心）消费者
> **一致性**：交易链路用 RocketMQ 事务消息（半消息 + 回查），全链路保留定时对账兜底（`admin-backend.md` 13.2.2）
> **事件命名**：**100% 沿用现有 routingKey**（`order.created` / `payment.success` / `user.deleted` 等），零改名迁移
> **关联文档**：`plans/admin-backend.md` 1.3（缓存）、1.4（队列/DLX/幂等）、4.4.4（超时关单）、13.2（本地消息表 + 对账）、14.1（跨端事件同步）；`plans/ios-backend.md` 1.5.2；`plans/android-backend.md` 1.4/4.3.4；`plans/miniprogram-backend.md` 7.4/8.x；`plans/pc-client-backend.md` 7.1/8.x

---

## 一、消息量测算

### 1.1 测算前提与假设

| 参数 | 取值 | 依据 |
|------|------|------|
| 同时在线 | 1 亿 | 统一口径 |
| 写 QPS | 日常峰值 50 万 / 大促峰值 100 万 | 统一口径（全站写接口：下单、支付、库存、购物车、地址、评价、后台管理等合计） |
| 大促峰值消息量 | 300 万条/秒 | 统一口径（本文用两条独立路径交叉验证，见 1.2 / 1.3） |
| 大促峰值系数 | ×50（相对日常峰值） | 统一口径 |
| 平均消息体 | ≈ 1 KB（加权：订单类 1.5 KB、支付类 800 B、库存类 600 B、通知类 400 B、埋点类 300 B） | 现有 `admin-backend.md` 1.4.2 消息格式（含 items 数组）实测口径推算 |
| 消息放大系数 | 每 1 次写操作平均产生 3 条消息 | 1.2 交叉验证：写路径含"业务事件 + 扇出（缓存失效/ES 索引/看板/通知）"，见 1.4 分解 |
| 大促当日日峰谷比 | 5（当日均值 = 当日峰值 / 5） | 大促流量集中在 0 点抢购 + 20 点两段，取保守值 |

### 1.2 路径 A：自上而下（写 QPS × 放大系数）

```
大促峰值消息量 = 大促峰值写 QPS × 放大系数
              = 100 万/秒 × 3.0 = 300 万条/秒   ← 与统一口径一致

日常峰值消息量 = 大促峰值消息量 ÷ 峰值系数
              = 300 万/秒 ÷ 50   =   6 万条/秒
```

### 1.3 路径 B：自下而上（按业务域、以订单为锚点）

**大促峰值锚点**：峰值下单 TPS = **30 万/秒**（占写 QPS 100 万的 30%；参考 2020 年天猫双 11 峰值订单创建 50.6 万笔/秒、支付峰值 56.7 万笔/秒，本平台取同量级偏保守值）。

> **跨文档口径对齐（00-conflict-register C10-g）**：`01` 号 §1.3 取"下单占写 50%"= **50 万/秒**（与给定口径"订单创建峰值 50 万笔/s"对齐），本文路径 B 取 30 万/秒，`08` 号漏斗取 3.5 万/秒——三者不一致，**裁决：下单峰值规划取 50 万/秒（最保守）**。对本文的影响：路径 A（写 100 万 × 放大系数 3 = 300 万条/s）**与下单占比无关，总量结论不变**；但分域表"订单域 50 万条/s"需按 50 万单/s 上修至 ≈97 万条/s，全站分域合计将上浮至 ≈380 万条/s——**§1.4 的设计值 350 万条/s（含 20% 冗余）可能不足，集群规划（4~6 个域集群）留出升到 400 万条/s 的余量，最终以全链路压测回填为准**。另：`09` 号 §0.1 的"50 万笔/s 含/不含试算口径"P0 待办仍然有效，业务书面确认前按 50 万/秒上限采购。

每笔订单全生命周期消息数（大促期）：

| 环节 | 条数 | 说明 |
|------|------|------|
| 下单 | 2 | `order.created`（含本地消息表兜底）+ 超时关单延迟消息 `order.timeout.check` |
| 库存 | 2 | 预扣确认 + 释放/回补（取消或超时场景约 30% 订单） |
| 支付 | 2.5 | `payment.success`（≈ 支付率 70%）+ `payment.failed` + 回调幂等重投 |
| 拆单 | 1 | `order.paid` 后按店铺拆单（`admin-backend.md` 4.4.2，1.5 店/单） |
| 状态流转 | 1.5 | `OrderStatusChangedEvent`（发货/收货/完成，`pc-client-backend.md` 8.x） |
| 物流 | 3 | `logistics.update`（揽收/中转/签收，`ios-backend.md` 1.5.2） |
| 通知 | 5 | 站内信 + 推送 + 短信 + 订阅消息 + 角标，扇出（`android-backend.md` 1.3"消息中心为事实来源、推送为通道"） |
| 营销 | 1.5 | 券核销/积分发放/满赠（大促期秒杀与拼团另计，见下表） |
| 财务/数据/搜索/缓存失效 | 3 | 对账流水、看板计数、ES 索引、缓存失效广播（1.3.2 广播失效） |
| **合计** | **≈ 19.5** | 取整 18~20 条/单 |

> 说明：19.5 条/单是"全链路每单摊销"（含非订单事件摊到订单上的比例），不是每单必然 19.5 条业务事件。下表按域拆分时以此为总量约束反推。

**大促峰值分域测算**（合计对齐 300 万条/秒）：

| 业务域 | 日常峰值（条/秒） | 大促峰值（条/秒） | 占比 | 主要事件 |
|--------|-----------------|-----------------|------|---------|
| 订单 | 1.0 万 | 50 万 | 16.7% | `order.created` / `order.paid` / `order.cancelled` / `order.refunded` / `order.refund.applied` / 拆单 / `OrderStatusChangedEvent` |
| 支付 | 0.7 万 | 35 万 | 11.7% | `payment.success` / `payment.failed` / 退款回调 / 渠道对账流水 |
| 库存 | 1.1 万 | 55 万 | 18.3% | 预扣确认 / 释放 / 回补 / 库存流水 / 预警 / 秒杀池扣减 |
| 营销 | 0.7 万 | 35 万 | 11.7% | 券领取/核销/回退、秒杀、拼团（`miniprogram-backend.md` 7.4）、砍价（7.5） |
| 通知（站内信/推送/短信/订阅消息） | 1.7 万 | 85 万 | 28.3% | `message.send` → `push.send` / `push.retry` / `cs.message`，**扇出最大域** |
| 物流/售后 | 0.4 万 | 20 万 | 6.7% | `logistics.update` / 售后流转 |
| 商品/搜索/缓存失效/埋点/看板 | 0.4 万 | 20 万 | 6.6% | `product.created/updated/deleted` / `product.off_shelf_by_store` / 足迹 / 看板增量 |
| **合计** | **6.0 万** | **300 万** | 100% | — |

两条路径结果一致（300 万条/秒、日常 6 万条/秒），测算成立。

**日量级（用于磁盘/成本估算）**：

```
日常日消息量 = 6 万/秒 × 86400 ÷ 日峰谷比 4 ≈ 13 亿条/日 ≈ 13 TB/日（1 KB，不含副本）
大促当日量  = 当日曲线积分 ≈ 500 亿 ~ 1000 亿条 ≈ 500 TB ~ 1 PB/日（不含副本）
             （简化算式：300 万/秒峰值段 30 分钟 + >100 万/秒段 2 小时 + >50 万/秒段 5 小时 + 其余按日常 6 万/秒计）
```

### 1.4 总吞吐目标（工程设计值，非业务量）

| 指标 | 目标 | 计算 |
|------|------|------|
| 峰值发送 TPS | **350 万条/秒** | 300 万业务 + 重试放大 3%（9 万）+ 事务回查/重投 1%（3 万）+ 20% 冗余 |
| 峰值消费 TPS | **350 万条/秒** | 消费端与发送同量级（广播类事件每个消费组独立计一次） |
| 集群写入带宽 | 28 Gbps 应用层 | 350 万 × 1 KB × 8 bit |
| 副本复制带宽 | **56 ~ 84 Gbps**（2~3 副本） | 主从异步复制 2 份；DLedger/同步双写场景 3 份 |
| 7×24h 磁盘写入 | 平日 13 TB/日、大促 500 TB~1 PB/日 | 1.3 |
| 堆积容忍（消费端全挂 1 小时） | 350 万 × 3600 ≈ **126 亿条 ≈ 12.6 TB** | 消费者全停 1 小时不丢消息、恢复后可追平 |
| 单消息大小上限 | 4 MB（默认），常规 < 4 KB | 大文件/图片禁止走 MQ，走 OSS 引用 |

> **诚实结论**：350 万条/秒 + 56~84 Gbps 复制带宽，**单个 RocketMQ 物理集群无法稳妥承载**（见第八章），必须按业务域拆分为 4~6 个集群。

---

## 二、RabbitMQ → RocketMQ 5.x 升级理由与迁移路径

### 2.1 按亿级电商维度逐项对比

| 维度 | RabbitMQ（现状） | RocketMQ 5.x | 亿级结论 |
|------|-----------------|--------------|---------|
| **吞吐** | 经典队列单队列 1~2 万 TPS；quorum queue 更低（Raft 日志 + 磁盘 fsync 语义）。Erlang VM 单消息调度开销大。300 万/秒需要 1500+ 个队列分片 + 巨量连接，镜像复制会二次放大 | 单 Broker 3~10 万 TPS（1 KB 消息、异步刷盘 + 主从异步复制、SSD）；集群 20~40 Broker 可稳定 50 万~100 万 TPS；5.x 批量 + 长轮询 + Proxy 进一步提升 | RocketMQ 高 **5~10 倍**，300 万/秒属于"多集群可解"而非"无解" |
| **堆积能力** | 队列是单条链式文件，堆积导致内存水位升高、消费者重连时从头读、quorum queue Leader 变更极慢且可能触发流控；官方经验单队列堆积应控制在数十万内 | CommitLog 顺序写 + ConsumeQueue 索引分离，堆积与收发完全解耦，官方口径"海量堆积不影响正常收发"；单集群 TB~PB 级堆积是常规能力 | 大促"消费端挂 1 小时"场景：RabbitMQ 会雪崩，RocketMQ 只是磁盘变多 |
| **事务消息** | **无原生支持**。现状靠"本地消息表 + MQ"（`admin-backend.md` 13.2.1）+ 定时补偿（13.2.2），事务语义靠业务自查 | 原生**半消息 + 本地事务 + 事务回查**（`TransactionListener#checkLocalTransaction`），Broker 主动回查，业务只需提供回查接口 | 交易链路（支付→订单→库存）从"补偿型最终一致"升级为"发送侧精确一次 + 回查兜底" |
| **顺序消息** | 单队列天然 FIFO，但一个队列只能被一个消费者竞争消费，顺序与吞吐互斥；且无法与镜像/重试组合保证端到端有序 | `MessageQueueSelector` 按 `orderId` Hash 选队列，队列内严格有序 + 消费端单线程；5.x 支持顺序消息失败跳过或挂起 | 库存流水、订单状态事件可获得"按单串行"的低成本保证 |
| **延迟消息** | 现方案用 **TTL + DLX 延迟队列**（`admin-backend.md` 4.4.4）：**存在队头阻塞**——同一队列中先入队的短 TTL 消息未到期时，后面的长 TTL 消息无法被消费，导致关单时间不准；`rabbitmq_delayed_message_exchange` 是社区插件（非官方支持、内存索引、单点） | 原生定时消息：1.0 兼容 18 个固定延迟级别（最大 2h）；**5.x 支持任意时间戳的定时消息**，覆盖"发货后 7 天自动确认收货""拼团 24h 超时"等长周期场景 | 现 TTL+DLX 方案在大促（同时存在 30 分钟关单 + 3 天确认收货的延迟消息）下必然出现关单漂移，RocketMQ 原生解决 |
| **消息重放** | 无原生能力。补偿重放需业务重新发布或从备份恢复镜像队列 | 按时间戳重置消费位点（`resetOffsetByTimestamp`），可精确回放到"大促 00:00:00"重放全量事件；配合 `Keys`（业务单号）可定位单条 | 对账修复、数据回填、ES 重建索引的关键能力 |
| **消息轨迹** | 依赖 Firehose/Monitor 插件，生产环境基本不用，故障时"消息去哪了"靠人肉查日志 | 内置 Message Trace（生产/消费轨迹落 RDB），Dashboard 可按 `messageId`/`Keys` 查询"谁发的、谁消费的、何时 ACK" | 大促故障定位从小时级降到分钟级 |
| **消费模型** | 队列与消费者 1:1 绑定（fanout 时每消费者一个队列副本），消费者数量变化 = 拓扑变更；扇出靠交换机复制 | **消费组共享队列**：队列数 = 并行度（与消费者数解耦），加消费者只需扩实例；广播模式每消费组独立订阅 | 1.3 说的"缓存失效广播"与 14.1 的跨端同步在 300 万/秒下不再需要 N 份队列副本 |
| **多语言（Go/Node）** | Go 官方客户端弱（社区 `amqp091-go` 可用但无封装框架）；Node.js 生态一般 | 5.x 走 **gRPC + Proxy**，Java/Go/C++ 官方客户端统一协议，`rocketmq-clients/go` 成熟；Node.js 走 Proxy gRPC | 与"Java 核心 + Go 网关/推送 + Node.js 消息中心"的技术栈匹配 |
| **DLQ/重试** | DLX 成熟（现状 1s/5s/30s 三档重试进 DLQ） | 原生 `%RETRY%<group>` 重试 Topic + `%DLQ%<group>` 死信 Topic，支持按消费组配置最大重投次数与延迟级别 | 能力持平，RocketMQ 运维入口更统一（Dashboard） |
| **运维** | 单机起步简单；镜像/quorum 队列脑裂、`ha-mode` 全量镜像在大促扩容时的流量风暴是经典事故源；集群扩容需配合 Federation/Shovel 手工拆拓扑 | NameServer 无状态轻量；Broker 主从 + Dledger 自动选主；Dashboard + Exporter(Prometheus) 完整；缺点是 Topic/队列数量大时路由推送与内存需要规划 | 300 万/秒规模下：RabbitMQ 是"拓扑复杂度爆炸"，RocketMQ 是"机器数量规划"——后者可自动化 |
| **成本** | Erlang 单机资源利用率低，需要更多队列分片 | Broker 磁盘/内存配比可调（堆内 4~8 G + 堆外 direct memory），顺序写对 SSD 友好 | 同等吞吐下 RocketMQ 机器数约 1/3~1/2 |

### 2.2 结论

1. **必须替换**。RabbitMQ 在 300 万条/秒、TB 级堆积、事务消息、长周期延迟消息四个硬指标上均不达标，其中"TTL+DLX 延迟队列队头阻塞导致关单漂移"和"堆积后 quorum queue 变更雪崩"是**现有方案已埋的生产事故级隐患**，不是容量问题而是模型问题。
2. **RocketMQ 5.x 而非 4.x**：必须 5.x 的理由只有两条但很硬——① 任意时间戳定时消息（4.x 的 18 级延迟最大 2h，覆盖不了 7 天自动确认收货与 24h 拼团超时）；② gRPC Proxy 统一多语言客户端（Go 网关/推送、Node.js 消息中心）。
3. **RabbitMQ 不立即下线**：迁移期作为"影子/回滚源"保留一个完整大促周期。

### 2.3 Topic / 队列映射规则（沿用事件名，零改名）

| RabbitMQ 概念 | RocketMQ 概念 | 映射规则 | 示例 |
|--------------|--------------|---------|------|
| 交换机 `admin.order.exchange` | **不映射为 Topic**，降级为 Topic 的**业务域前缀/Tag** | 域 → Tag：`admin.order` / `admin.payment` / `admin.inventory` | — |
| 路由 Key `order.created` | **Topic = 路由 Key 原文** | 10 个计划文件里的事件名一个不改 | Topic `order.created` |
| 队列 `order.created.queue`（消费者组） | **ConsumerGroup** | 一个队列对应一个消费组；同队列多消费者竞争消费 → 同一 Group | Group `og-order-created`（admin-inventory、admin-finance 同组竞争） |
| 同事件多服务各要一份 | **不同 ConsumerGroup** | 语义为"每服务独立消费"时必须拆组（见下表 `order.paid`：admin-inventory 与 admin-finance 需各收一份时） | `og-order-paid-inv` / `og-order-paid-fin` |
| fanout 交换机 / 缓存失效广播 | 广播消费模式（Clustering→Broadcasting） | 例：`product.updated` 的缓存失效广播，各端各自一个 Group + Broadcasting | — |
| `admin.dlx.exchange` + `*.dlq` | `%DLQ%<ConsumerGroup>`（系统自动） | 不再手工声明 DLX 拓扑，统一走 RocketMQ DLQ + 人工干预台（见第五章） | `%DLQ%og-order-created` |
| TTL+DLX 延迟队列 `order.timeout.check` | Topic `order.timeout.check` + **5.x 定时消息（时间戳）** | 发送时设置 `setDeliveryTimestamp(payDeadline)`，到期才可见 | — |
| `x-death` 头 | RocketMQ 内置属性（`RECONSUME_TIMES`、原 Topic/QueueId） | 人工干预台展示这两个属性 | — |

**Topic 命名与容量约束**：Topic 总数 = 现有 16 个事件（`admin-backend.md` 1.4.1）+ ios 侧 8 个（`ios-backend.md` 1.5.2）+ 本文新增（第七章）≈ **40 个业务 Topic** + 系统 Topic（`%RETRY%` / `%DLQ%` / `RMQ_SYS_*`）。单集群 Topic 上限按数千规划、队列数按 16（日常）/ 64（大促核心 Topic）预分配，**队列数一次配足、消费者数弹性伸缩**（避免大促当天扩队列触发 rebalance 风暴）。

### 2.4 迁移策略：双写灰度（四阶段，全程可回滚）

```
阶段 0  建设期（T-90d）
  · 建 4~6 个 RocketMQ 集群（按域，见第八章 8.3），Topic/Group 由配置中心统一下发
  · 定义统一 SDK：mq-spring-boot-starter（Java）/ mq-go-sdk / mq-node-sdk
    └ 内含：事件信封（沿用 1.4.2 的 messageId/timestamp/data 结构）、
       发送重试、事务消息模板、幂等拦截器（复用 admin_message_dedup 表结构）
  · 建"消息对账工具"：按 messageId 比对 RabbitMQ 与 RocketMQ 两侧条数与内容

阶段 1  双写（T-60d）
  · 生产端 SDK 同时写 RabbitMQ（主）与 RocketMQ（影子），同一 payload、同一 messageId
  · 消费端只消费 RabbitMQ；RocketMQ 侧只做"影子消费"打点不落业务（或不消费）
  · 每日对账：两侧消息条数差异 < 0.01%，payload 哈希一致
  · 顺序消息、事务消息在 RocketMQ 侧单独验证（RabbitMQ 侧保持现状本地消息表方案）

阶段 2  灰度切读（T-30d，按业务域逐个切，非按流量百分比）
  · 切换顺序：商品/搜索 → 通知 → 营销 → 库存 → 订单/支付（风险从低到高）
  · 切换方法：消费者同时订阅 RabbitMQ 与 RocketMQ 两个源，**依赖 1.4.3 去重表
    （messageId + consumer）保证同一条消息只处理一次** —— 去重表是双读安全的前提，
    必须先于任何消费者切换完成部署并验证去重命中率
  · 每域观察 3~7 天：消费 lag、失败率、DLQ 进入率、业务对账差异

阶段 3  主次互换（T-14d，跳过一个大促周期则 T+6 个月）
  · RocketMQ 转为主，停止业务端消费 RabbitMQ，RabbitMQ 侧转为影子
  · 大促前 7 天冻结切换，大促后复盘再切剩余域

阶段 4  下线（T+1 个大促周期）
  · 停止双写 → 观察 72h（覆盖事务回查与长周期延迟消息）→ 删除 RabbitMQ 拓扑
  · 清理项：TTL+DLX 延迟队列、DLX 交换机、镜像策略、监控告警改指 RocketMQ
```

**回滚保障**：阶段 1~3 期间 RabbitMQ 始终是完整可用的生产链路，任一域出问题只需把消费组指向回 RabbitMQ（配置中心热切换，分钟级），不需要回滚数据——因为两侧 messageId 相同，去重表保证不会重复处理。

**兼容旧事件语义**：`ios-backend.md` 1.2.2 约定"买家端消费绑定管理端交换机"——迁移后改为"买家端与管理端订阅**同一个 Topic、不同 ConsumerGroup**"，语义等价（各自独立消费一份），且解决了原文"同名不同机导致消息丢失"的问题（`payment.success` 现在是全平台唯一 Topic）。

---

## 三、削峰填谷设计

### 3.1 下单链路异步化拆分

**原则**：同步链路只做"钱和库存的强一致动作"，其余全部异步或延迟；同步 P99 目标 < 500 ms（下单）/ < 200 ms（支付回调应答渠道）。

| 步骤 | 同步/异步 | 原因 |
|------|----------|------|
| 参数校验 + 风控前置（Redis 规则） | 同步 | 必须拦截非法请求 |
| Redis 预扣库存（Lua 原子）+ 优惠券冻结 | 同步 | 库存超卖是资损 |
| 订单落库 + 支付单创建（本地事务） | 同步 | 资金事实来源 |
| 发超时关单**定时消息**（`setDeliveryTimestamp(pay_deadline)`） | 同步发送（事务消息半消息） | 关单必须可靠 |
| 风控异步复核 | **异步**（`risk.review` 延迟 3s） | 复核慢但要求最终拦截 |
| 券核销确认、积分发放 | **异步**（消费 `order.created`） | 可回补、可对账 |
| 拆单 | **异步**（消费 `order.paid`，`admin-backend.md` 4.4.2） | 原文已约定 |
| ES 索引、看板增量、缓存失效广播、足迹/埋点 | **异步**（合并批量消费） | 允许秒级延迟 |
| 站内信/推送/短信 | **异步 + 合并**（见 3.4） | 扇出最大域 |
| 发票、财务入账、对账流水 | **异步**（消费 `payment.success`） | T+1 可容忍 |
| 自动确认收货、售后超时 | **延迟消息** | 长周期 |

### 3.2 队列积压监控

| 指标 | 采集 | 告警阈值（分级） |
|------|------|-----------------|
| 积压量 `堆积 = 最新 Offset − 已提交 Offset` | RocketMQ Exporter → Prometheus | P3：> 100 万条；P2：> 1000 万条；P1：> 1 亿条或增长速率 > 消费速率 × 2 持续 5 分钟 |
| 消费耗时 P99 | 消费端埋点 | P2：> 3s；P1：> 10s |
| DLQ 进入速率 | `%DLQ%*` Topic 消费计数 | P1：任何非零且持续 5 分钟 |
| 队列负载倾斜 | 各队列堆积分布 | 最大/最小 > 5 倍（Hash Key 分布不均，需检查 Key 设计） |
| 事务回查积压 | `RMQ_SYS_TRANS_HALF_STORE` | P2：半消息超 10 分钟未决 |

**扩容手段（按顺序）**：
1. 加消费者实例（不改拓扑，秒级生效；受限于队列数上限——所以队列数按大促峰值预分配 64）
2. 提高单消费者并发线程数 / `pullBatchSize`
3. 消费端限流下调（保护下游 DB 的令牌桶放宽）
4. 仍不够 → 该 Topic 新增队列（会触发 rebalance，需低峰执行 + 消费端幂等已保证安全）
5. 极端情况：非关键域（埋点、足迹、推荐特征）**降级丢弃**，交易域永不丢

### 3.3 消费限流与背压

| 层 | 措施 |
|----|------|
| Broker→消费者 | `pullBatchSize`（默认 32，交易域 8 降低重投粒度）、长轮询 15s |
| 消费线程池 | 每 Group 独立线程池 + 队列；拒绝策略：CallerRuns（背压给拉取线程） |
| 全局保护 | Sentinel 令牌桶包住"消费逻辑中的下游调用"（DB/Redis/gRPC），保护下游而非保护 MQ |
| 分级限流 | **交易域（订单/支付/库存）不限流**；通知域限流到下游短信/推送通道容量；埋点域可丢弃 |
| 重投退避 | 失败消息进入 `%RETRY%` Topic，延迟级别见第五章，避免同批消息"秒级重投风暴" |
| 生产端 | 单实例 in-flight 窗口上限 + 发送超时 3s + 本地阻塞队列满则快速失败（不允许在应用侧无限缓冲，否则 OOM 转移到应用） |

### 3.4 通知域削峰（最大扇出域的专项设计）

通知占 28%（85 万条/秒），是唯一可以主动削的域：
- **合并**：同一用户 1 秒内多事件合并为一条推送（Redis 计数 + 1s 窗口聚合），预计削 30~40%
- **降级通道**：大促期关闭低价值推送（营销类），只留交易类
- **通道隔离**：短信/推送/站内信各自独立 Group + 独立限流，短信通道满不影响站内信

### 3.5 大促预扩容（T-7 → T-0 检查单）

| 时点 | 动作 |
|------|------|
| T-14d | 全链路压测（影子流量打标），验证 350 万条/秒发送 + 消费 + 堆积追平能力 |
| T-7d | Broker 磁盘扩容（按 1.3 大促当日量 + 72h 保留）；Topic 队列数冻结；网卡升级核查 |
| T-3d | 消费者副本按预估峰值 ×1.5 预扩容；事务回查线程池扩容；DLQ 告警值班表 |
| T-1d | 关闭一切非必要定时任务（对账、报表）；延迟消息积压检查（`order.timeout.check` 不得有历史欠账） |
| T-0 | 值班看板：分域发送/消费 TPS、堆积、DLQ、事务回查积压、下游 DB QPS |
| T+1d | 堆积追平确认；对账任务恢复；复盘消息量与测算偏差（回填 1.1 的放大系数） |

---

## 四、事务消息：支付回调 → 订单状态 → 库存扣减

### 4.1 为什么这条链路必须上事务消息

现状（`admin-backend.md` 4.4.1、`android-backend.md` 10.32）：回调处理与订单状态变更同事务，失败进重试队列 + 告警。缺口：**"本地事务成功"与"MQ 消息发出"之间没有原子性**——本地事务提交后进程崩溃/网络抖动，订单已 paid 但 `payment.success` 事件丢失，库存扣减、财务入账、通知全部丢失，只能等每小时对账（13.2.2）发现，用户侧表现为"付了钱订单还是待付款"。

### 4.2 事务消息方案

```
支付渠道回调（5s 内必须应答，android 1032）
  │
  ├─ 1. 验签 + 幂等（paymentId + 渠道流水号，已支付直接应答成功）
  │
  ├─ 2. 【半消息】producer 发送事务消息到 Topic payment.success
  │      （半消息对消费者不可见，Broker 落 RMQ_SYS_TRANS_HALF_STORE）
  │
  ├─ 3. 【本地事务】（同一 DB 事务）
  │      · 写支付流水（已支付）
  │      · 更新订单 unpaid → paid（乐观锁 version + 状态机校验，admin 4.3）
  │      · 写事务日志表 mq_transaction_log(tx_id, biz_no=order_no, status=BEGIN)
  │      · 提交后立即应答渠道 SUCCESS
  │
  ├─ 4. 返回 Broker：COMMIT（消息可见）/ ROLLBACK（丢弃）/ UNKNOWN（等回查）
  │
  └─ 5. 消费者（各自独立 Group，幂等）
         og-payment-success-inv   → 库存扣减（锁库→出库→解锁，5.3.1 两段式）
         og-payment-success-fin   → 财务入账 / 对账流水
         og-payment-success-data  → 看板 / ES / 通知
         og-payment-success-split → 拆单（admin 4.4.2）
```

**事务回查**：Broker 定时（默认 6s 间隔起，指数退避）对未决半消息回调 `checkLocalTransaction(txId)`：

```
回查实现：
  SELECT status FROM mq_transaction_log WHERE tx_id = ?
  · status = COMMITTED     → 返回 COMMIT
  · 无记录 且 订单已 paid    → 补写 COMMITTED 后返回 COMMIT（处理"事务已提交但日志未刷盘"窗口）
  · 无记录 且 订单不存在     → 返回 ROLLBACK
  · 无法判定（DB 只读/超时）→ 返回 UNKNOWN（Broker 继续回查，超过 15 次转人工干预通道）
```

`mq_transaction_log` 与订单表同库同片（按 `order_no` 路由，遵循 `admin-backend.md` 1.2.1 分片约束），**禁止跨库回查**。

### 4.3 与对账兜底的关系（三层防线，各管一段）

| 防线 | 保证什么 | 不保证什么 | 对应机制 |
|------|---------|-----------|---------|
| 第 1 层：事务消息 | "本地事务成功 ⟺ 消息必达消费者"（发送侧精确一次） | 消费端处理是否成功；渠道回调是否丢失 | 半消息 + 回查（本文 4.2） |
| 第 2 层：消费重试 + 幂等 | 消息处理成功（重试到成功或进 DLQ） | 无 | `%RETRY%` + `admin_message_dedup`（`admin-backend.md` 1.4.3） |
| 第 3 层：定时对账 | 前两层全部失效时兜底（回调丢失、回查失败、Broker 故障、人工改库） | 实时性（延迟小时级） | 13.2.2 订单-库存对账 + 渠道账单对账（T+1 拉渠道账单核对 `payment.success` 与实际打款） |

**必须诚实说明**：事务消息**不能**替代对账。以下场景仍靠对账：① 渠道侧扣款成功但回调丢失（需主动查询渠道/账单核对）；② 事务回查期间 DB 主从切换导致误判；③ Broker 半消息存储损坏（极端）。因此 13.2.2 定时对账 + T+1 渠道对账**必须保留**，事务消息的价值是把对账从"主要一致手段"降级为"兜底手段"，把发现问题的时间从小时级降到秒级。

**回查与对账的联动**：回查连续 UNKNOWN 超阈值的交易单，自动进入对账任务的高优扫描队列，而不是等 T+1。

### 4.4 支付回调的竞争场景

- **超时关单 vs 支付回调同时到达**：不依赖 MQ 顺序。订单状态机 + DB 行锁裁决：回调先拿到锁则 `unpaid→paid` 成功，延迟消息到期后查到已 paid 则丢弃；关单先拿到锁则 `unpaid→cancelled`，回调发现已 cancelled → 触发**自动退款**流程（异步，走 `payment.refund`）。
- **重复回调**：`paymentId + 渠道流水号` 幂等键（android 1032），第二次直接应答成功，不产生第二条 `payment.success`。

---

## 五、死信与重试

### 5.1 重试策略（沿用 admin 1.4.1 的"1s / 5s / 30s 三次后进 DLQ"）

RocketMQ 原生重试是 `1s 5s 10s 30s 1m 2m ...` 递增（默认 16 次），**显式配置 `maxReconsumeTimes = 3` + 延迟级别（1, 2, 4）= 1s / 5s / 30s**，与现有口径逐值对齐，避免迁移后重试语义漂移。

| 失败类型 | 处理 | 说明 |
|---------|------|------|
| 反序列化失败 / 必填字段缺失 | **立即进 DLQ，不重试** | 重试必然失败，浪费资源且掩盖问题 |
| 业务异常（如订单已终态） | 直接 ACK + 记录告警 | 幂等重入的一种：合法的"无需处理"不是错误 |
| 系统异常（DB 超时、RPC 超时） | 1s → 5s → 30s 三次 → DLQ | 与 1.4.1 一致 |
| 未知异常 | 三次 → DLQ | |
| 下游限流（429） | 退避重试，**不计入三次**（独立计数，上限 30 分钟） | 限流不是"毒" |

### 5.2 死信队列与毒消息

- RocketMQ 自动按消费组生成 `%DLQ%<ConsumerGroup>`，DLQ 中的消息不再自动投递（消费位点默认不动）。
- **毒消息判定**：同一 `messageId` 进入 DLQ ≥ 2 次（重投后再次失败）→ 该 `messageId` 进入"隔离名单"（Redis Set + TTL 24h），自动重投通道对该 ID 永久关闭，只能人工处置——**彻底杜绝无限重试循环**（1.4.1 明确禁止的场景）。
- DLQ 消息保留原 Topic、原 QueueId、重投次数、异常堆栈（RocketMQ 属性 + 业务自定义 `ERR_CODE`/`ERR_MSG` 头）。

### 5.3 人工干预通道（管理端"消息治理台"）

在 admin-backend 管理端新增模块（复用现有 admin-system 权限体系）：

| 能力 | 说明 |
|------|------|
| 死信列表 | 按 Topic/Group/时间/业务单号（`Keys`）检索，展示原 Topic、重投次数、错误堆栈 |
| 消息轨迹 | 按 `messageId` 查 RocketMQ 内置轨迹：谁发的、何时到 Broker、哪个消费者 ACK |
| 重投 | 单条/批量重投回原 Topic；重投前强制展示幂等键；所有操作写审计日志（复用 `admin_operation_log`） |
| 隔离名单管理 | 查看/移除隔离名单中的 messageId（移除 = 允许再次自动重投） |
| 手动补偿 | 对"已知丢单"场景触发业务补偿接口（如重新触发库存扣减），**禁止直接改库存表**（admin 4.4.4 已有此约束） |
| 告警联动 | DLQ 非零 5 分钟 → P1 告警 + 值班群；`user.deleted` 的 DLQ → **合规告警专线**（见下） |

### 5.4 特殊事件的差异化策略

| 事件 | 策略 | 依据 |
|------|------|------|
| `payment.success` / `order.paid` | **不允许进 DLQ 静默滞留**：3 次重试失败后直接触发业务补偿流程 + P1 告警 + 自动进入对账高优扫描 | 资金事件，滞留即资损 |
| `user.deleted` | 30 分钟 SLA：重试间隔压缩为 30s/1m/2m（30 分钟内完成三次），失败单独告警转人工，删除回执写审计表作为 PIPL 举证 | admin 1.4.1 合规事件要求 |
| `order.timeout.check`（延迟） | 到期消费失败 → 3 次重试；仍失败由定时扫描兜底（`pay_deadline` 字段 + 每 5 分钟扫库，admin 4.4.4） | 双保险 |
| `export.request` / 海报生成 | 允许进 DLQ 长时间等待人工（低价值），不告警只记账 | 避免噪音淹没真告警 |

---

## 六、顺序与延迟

### 6.1 顺序消息：诚实结论——绝大多数"顺序需求"不该由 MQ 承担

**先说结论**：本方案**只有 2 个 Topic 使用顺序消息**，其余全部并行 + 业务幂等。理由：现有文档已用更强的机制解决了乱序问题，MQ 顺序是优化项而非正确性来源；而顺序消息的代价在 300 万条/秒下不可接受（单队列单线程，吞吐降至数千~1 万 TPS，且一条毒消息阻塞整个队列）。

| 场景 | 需要顺序吗 | 现有正确性保证 | 本方案 |
|------|-----------|---------------|--------|
| 订单状态机流转（`order.created→paid→shipped→completed`） | 逻辑上需要"同一订单串行" | **乐观锁 `version` + 状态机白名单 + 终态拒绝**（admin 4.3、android 4.3.4）：乱序消息会被状态机拒绝，不会写坏数据 | 消费端并行 + 幂等；`order.status.changed` 作为**顺序 Topic（按 orderId Hash）**仅用于对外通知/推送（避免"先推送已发货、后推送待发货"的用户可见乱序） |
| 库存扣减/释放/回补（同一 SKU） | 需要"变更流水有序" | Redis Lua 原子 + DB 乐观锁（pc-client 5.3.1） | `inventory.change` **顺序 Topic（按 skuId Hash）**，仅保证流水表顺序，防止同一 SKU 的释放先于扣减被执行 |
| 支付回调 vs 超时关单竞争 | 需要"仲裁有序" | 状态机 + 行锁（本文 4.4） | 不用顺序消息 |
| 拆单 | 否 | `order.paid` 消费 + 幂等（拆单任务表唯一键） | 并行 |
| 优惠券核销 | 否 | 券记录乐观锁 + 幂等键 | 并行 |
| 缓存失效广播 | 否 | 失效本身幂等（删除/覆盖） | 并行广播 |

**顺序 Topic 的运维约束**：消费者失败挂起策略用 `SUSPEND_CURRENT_QUEUE_A_MOMENT` + 3 次后转 DLQ 并**告警阻塞**（顺序队列阻塞 = 该 SKU/订单的下游全部延迟，必须有人工介入入口）；队列数 = 8~16（顺序消息队列不能多，否则乱序风险与 rebalance 风险上升）。

### 6.2 延迟消息：场景与 RocketMQ 能力映射

| 场景 | 延迟 | 触发事件 | RocketMQ 实现 | 兜底 |
|------|------|---------|---------------|------|
| 超时未支付关单 | 下单后 N 分钟（见下方口径冲突） | 下单/支付单创建时发送 | 5.x **时间戳定时消息** `setDeliveryTimestamp(pay_deadline)` | `pay_deadline` 字段扫库（admin 4.4.4，每 5 分钟） |
| 锁定库存释放 | 同上（同一事件驱动） | 消费 `order.cancelled` | 普通消息 | 库存对账（13.2.2） |
| 优惠券/秒杀名额回退 | 同上 | 消费 `order.cancelled` | 普通消息 | 营销对账 |
| 风控异步复核 | 3 秒 | 下单 | 18 级延迟级别 level=2（5s，取最接近档） | 风控对账 |
| 拼团超时未成团 | 发起后 `expire_minutes`（默认 1440 分钟 = 24h） | 发起拼团时发送 | 时间戳定时消息（4.x 最大 2h，**覆盖不了，必须 5.x**） | miniprogram 7.4 的 30s 扫描任务保留 |
| 自动确认收货 | 发货后 7~15 天 | 发货时发送 | 时间戳定时消息（同上，超 2h） | `auto_close_at` 字段扫库 |
| 售后超时（待退货/待退款） | 3~7 天 | 售后单创建 | 时间戳定时消息 | 售后表扫库 |
| 赠送积分过期 | 30~365 天 | 积分发放 | 时间戳定时消息（超长周期，需验证 Broker `message.delayLevelTable` 与磁盘占用：长周期消息在 CommitLog 中保留更久） | 积分对账 |
| 重试退避 | 1s/5s/30s | 消费失败 | 18 级 level 1/2/4 | — |

**18 级延迟级别速查**（兼容用）：`1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h`（level 1~18）。**level 18 = 2h 是硬上限**，超过 2h 的延迟一律用 5.x 时间戳定时消息。

**⚠ 跨文件口径冲突（需联调统一，本方案不擅改其他文件）**：支付超时时长在三个文件中不一致——`ios-backend.md` 2240 说 15 分钟、`pc-client-backend.md` 830 说 15 分钟、`android-backend.md` 191/965 说 30 分钟，而 `admin-backend.md` 只定义了 `pay_deadline`/`auto_close_at` 字段而未定死分钟数。**本方案约定：以订单表 `pay_deadline` 字段为唯一事实来源**，延迟消息的 `deliveryTimestamp` 直接取该字段值，各端展示同一字段；具体分钟数（建议统一 30 分钟）由产品在联调前确认，否则会出现"iOS 端显示 15 分钟关单、实际 30 分钟关单"的客诉。**收敛裁决（00-conflict-register C7）：`pay_deadline` 字段为唯一事实来源，各端不得自定义分钟数；分钟数取值（30 分钟建议值）由产品在联调前书面确认后回写三端计划文件，架构侧无异议。**

**延迟消息的容量账（诚实量化）**：以日常日均 13 亿条消息、其中延迟消息占比 ~8%（关单 + 确认收货 + 售后超时）≈ 1 亿条/日；平均延迟时长按 2 小时计 → Broker 需同时持有 ≈ 830 万条未到期延迟消息 ≈ 8 GB CommitLog 占用，可接受。大促当日峰值 300 万条/秒 × 8% = 24 万条/秒延迟消息写入，属正常量级。但**超长周期消息（365 天积分过期）会拉长 CommitLog 保留期**，建议积分过期改为"DB 扫描 + 时间轮"而非 MQ 定时消息，避免全局保留期被单业务绑架。

---

## 七、与现有业务计划对接：全量事件清单与承载方式

**原则**：10 个计划文件已定义的事件名**一个不改**（多文件已就"以 admin-backend.md 1.4 为唯一来源"达成一致，`ios-backend.md` 248、`android-backend.md` 35）；交换机名降级为 Tag；RabbitMQ 队列名映射为消费组。

### 7.1 事件清单（来源逐项标注）

| # | 事件名（沿用） | Topic = 事件名 | 来源文件 | 建议生产者 | 消费者（Group） | 消息模型 | 集群 |
|---|--------------|---------------|---------|-----------|----------------|---------|------|
| 1 | `order.created` | `order.created` | admin 1.4.1、ios 1.5.2 | admin-order / 订单中心 | `og-order-created-inv`（库存）、`-fin`（财务）、`-data`（看板）、`-risk`（风控） | 普通 | A |
| 2 | `order.paid` | `order.paid` | admin 1.4.1、ios 1.5.2 | 订单中心 | `og-order-paid-inv`、`-fin`、`-split`（拆单）、`-search` | 普通 | A |
| 3 | `order.refunded` | `order.refunded` | admin 1.4.1、ios 1.5.2 | 订单中心 | `og-order-refunded-inv`（回补）、`-fin`、`-mkt`（退券） | 普通 | A |
| 4 | `order.cancelled` | `order.cancelled` | admin 1.4.1、ios 1.5.2 | 订单中心（含超时关单） | `og-order-cancelled-inv`（释放库存）、`-mkt`（退券）、`-data` | 普通 | A |
| 5 | `order.refund.applied` | `order.refund.applied` | admin 1.4.1、ios 1.5.2 | 订单中心 | `og-after-sale`（admin-audit）、`-msg`、`-data` | 普通 | A |
| 6 | `product.created` | `product.created` | admin 1.4.1 | admin-product | `og-product-search`、`og-product-data` | 普通 | D |
| 7 | `product.updated` | `product.updated` | admin 1.4.1、ios 1.5.2、pc 14.1 | admin-product | `og-product-search` + **广播组**（各端缓存失效，1.3.2） | 广播 | D |
| 8 | `product.deleted` | `product.deleted` | admin 1.4.1、ios 1.5.2 | admin-product | `og-product-search` + 广播组 | 广播 | D |
| 9 | `product.off_shelf_by_store` | `product.off_shelf_by_store` | admin 3610（店铺冻结强制下架） | admin-store | 同 `product.updated` 消费者 | 普通 | D |
| 10 | `member.registered` | `member.registered` | admin 1.4.1、ios 1.5.2 | core-user | `og-member-mkt`（新人礼）、`og-member-data` | 普通 | D |
| 11 | `user.deleted` | `user.deleted` | admin 1.4.1（v1.2）、ios 12.1.2 | 买家端（注销冷静期结束） | `og-user-deleted-core`、`og-user-deleted-data` | 普通 + **30min SLA** | A |
| 12 | `audit.submitted` | `audit.submitted` | admin 1.4.1 | admin-audit | `og-audit-msg` | 普通 | D |
| 13 | `audit.completed` | `audit.completed` | admin 1.4.1 | admin-audit | `og-audit-msg`、`og-audit-product`（admin 2028 悬挂补偿） | 普通 | D |
| 14 | `audit.returned` | `audit.returned` | admin 1.4.1 | admin-audit | `og-audit-msg`、`og-audit-search` | 普通 | D |
| 15 | `export.request` | `export.request` | admin 1.4.1 | admin-* | `og-export-data`（admin-data）、`og-export-order`（admin 2435） | 普通 | D |
| 16 | `message.send` | `message.send` | admin 1.4.1、3604 | admin-* | `og-msg-center`（admin-message 落库→再触发各通道） | 普通 | C |
| 17 | `store.audited` | `store.audited` | admin 1.4.1 | admin-audit | `og-store-msg`、`og-store-fin` | 普通 | D |
| 18 | `payment.success` | `payment.success` | ios 1.5.2、android 1032 | 支付回调 | `og-pay-inv`、`og-pay-fin`、`og-pay-data`、`og-pay-split` | **事务消息** | A |
| 19 | `payment.failed` | `payment.failed` | ios 1.5.2 | 支付回调 | `og-pay-msg`（通知用户） | 普通 | A |
| 20 | `payment.refund` | `payment.refund`（新增补齐） | 由 admin 退款流程与 miniprogram 8.1 引用 | 支付中心 | `og-refund-fin`、`og-refund-inv` | 事务消息 | A |
| 21 | `iap.purchased` | `iap.purchased` | ios 1.5.2 | iOS 端 | `og-iap-member`（开通会员）、`og-iap-points` | 事务消息 | A |
| 22 | `iap.refunded` | `iap.refunded` | ios 1.5.2 | iOS 端 | `og-iap-member`（回收会员） | 普通 | A |
| 23 | `logistics.update` | `logistics.update` | ios 1.5.2、android 1434 | admin-logistics | `og-logistics-msg`、`og-logistics-order` | 普通 | A |
| 24 | `push.send` | `push.send` | ios 1.5.2 | admin-message | `og-push-apns` / `og-push-fcm` / `og-push-hw`（通道分组） | 普通 + 失败转 `push.retry` | C |
| 25 | `push.retry` | `push.retry` | ios 1.5.2 | 推送服务 | `og-push-apns` 等 | 延迟重试 | C |
| 26 | `cs.message` | `cs.message` | ios 1.5.2 | 客服服务 | `og-cs-ws`（WebSocket 网关，跨实例投递，pc 7.1.2） | 普通（低延迟优先） | C |
| 27 | `order.timeout.check` | `order.timeout.check` | admin 4.4.4 | 下单/支付链路 | `og-order-timeout`（唯一关单入口，admin 4.4.4） | **定时消息（时间戳）** | A |
| 28 | `OrderStatusChangedEvent` | 建议定名 `order.status.changed` | pc 891、android 1122 | 订单状态机（唯一入口） | `og-order-status-push`、`og-order-status-cache` | **顺序（orderId Hash）** | A |
| 29 | `inventory.change`（新增补齐） | `inventory.change` | 由 5.3.1 库存流水链路补齐 | core-inventory | `og-inv-log`（流水）、`og-inv-warn`（预警） | **顺序（skuId Hash）** | A |
| 30 | `risk.review`（新增补齐） | `risk.review` | 3.1 削峰拆分 | 订单中心 | `og-risk` | 延迟 5s | A |

> 现有文件共定义 **28 个具名事件**（含 `OrderStatusChangedEvent` 内部事件）；本方案沿用全部名称，新增 2 个补齐事件（`payment.refund`、`inventory.change`、`risk.review` 实为 3 个新增，用于闭合退款、库存流水、风控三个已在文档中描述但没有登记为 MQ 事件的链路）。**本清单是迁移后的唯一权威事件注册表**，新增事件必须在此登记（沿用 admin 1.4.1 的登记纪律）。

### 7.2 承载原则

1. **事件信封沿用 1.4.2 结构**：`messageId` / `timestamp` / `data` 保留；`exchange`/`routingKey` 字段保留但填入 Tag 与 Topic 原文（历史消息可解析）；新增 `Keys = 业务单号`（RocketMQ 索引查询，替代原来靠 body 检索）、`Tag = admin.order` 等域标识。
2. **幂等沿用 1.4.3**：`message_id + consumer` 去重表（Redis Set NX 快速判重 + DB 表持久化），这是双读灰度（2.4 阶段 2）与"跨端重复投递"的共同安全网。300 万条/秒下 DB 去重表单表写入是瓶颈——**去重表按 `message_id` Hash 分 64 表 + Redis 先行判重（命中率 >99% 时 DB 只承担写入）**。
3. **缓存失效广播沿用 1.3.2**："先更新 DB → 删缓存 → 发 MQ 广播失效"，广播组用 RocketMQ Broadcasting 模式（每端一个 Group），替代 fanout 交换机的 N 份队列副本。
4. **本地消息表（13.2.1）与事务消息的关系**：交易域（支付/退款/IAP）用**事务消息替代本地消息表**（能力超集，回查替代每 5 分钟扫描）；非交易域（订单创建后的通知、导出任务）**保留本地消息表**（成本低、无需回查接口），两者并存但分工明确，避免"两套全上"。

---

## 八、技术上限：单集群能到多少，超出怎么办

### 8.1 RocketMQ 单集群物理上限（诚实区间，含计算过程）

以下为 1 KB 消息、SSD、异步刷盘 + 主从异步复制（2 副本）条件下的经验值与公开 benchmark 的折算，**不是承诺值**，实际需压测：

| 指标 | 单 Broker | 单集群（16~24 Broker） | 计算依据 |
|------|----------|----------------------|---------|
| 发送 TPS | 3 万 ~ 10 万 | **50 万 ~ 150 万**（稳妥生产值按 50 万规划） | 单 Broker：commitLog 顺序写 SSD（500 MB/s+）÷ 批量提交；10 万 TPS × 1 KB = 100 MB/s，接近单盘写入上限，故稳妥取 3~5 万/台 |
| 消费 TPS | 受队列数与线程数限制 | 100 万+（可高于发送侧，多消费组并行） | 消费不落盘（只提交位点），瓶颈在下游业务 |
| 顺序消费 | 单队列 0.3 万 ~ 1 万 | 与队列数线性 | 单队列单线程串行 |
| 堆积能力 | 2 TB SSD ≈ **20 亿条** | **PB 级**（磁盘总量决定） | 顺序写，堆积不影响收发；fileReservedTime 默认 72h |
| 集群写入带宽 | 100~300 MB/s（1 万~3 万 TPS@1KB） | 2~4 GB/s = 16~32 Gbps | 24 台 × 1.5 Gbps 均摊 |
| Topic / 队列上限 | — | 数千 Topic / 数万队列（路由推送内存与 NameServer 压力限制） | 本方案 40 Topic × 64 队列 = 2560 队列，安全 |
| 消息回溯 | — | `fileReservedTime`（默认 72h）内可按时间回放 | 超期无法回放，需冷存或 DLedger 归档 |

**本方案目标 350 万条/秒 ÷ 单集群稳妥 50 万条/秒 ≈ 7 倍**，且集群写入带宽需求 28 Gbps（应用层）+ 复制 56~84 Gbps，单集群 24 台机器的网卡总和虽够，但**故障域、运维复杂度、脑裂风险都不可接受**。

### 8.2 超出单集群的解决路径（递进）

| 阶段 | 手段 | 覆盖量级 | 代价 |
|------|------|---------|------|
| **P0（本方案）** | **按业务域拆 4~6 个物理集群**（见 8.3），客户端/Proxy 路由 Topic→集群 | ≤ 500 万条/秒 | 机器数 ×N，但每集群独立故障域、可独立扩容；跨集群无事务与顺序（本方案已确保交易链路不跨集群） |
| **P1** | 集群内水平加 Broker（单集群 32~48 台）+ 消息压缩 + 批量 | ≤ 200 万条/秒/集群 | 磁盘与网络成本线性上升 |
| **P2** | **消息体瘦身**：大 payload（订单 items、导出数据）改为"事件只带单号 + 拉取详情"或 OSS 引用 | 降 30~50% 带宽 | 增加一次 RPC 拉取 |
| **P3** | 通知/埋点/日志域迁移到 **Kafka/Pulsar**（吞吐密度更高、存储成本更低，且该域不要求事务与低延迟强一致） | 把 105 万条/秒（通知+数据域）从 RocketMQ 剥离 | 双 MQ 技术栈，运维复杂度上升 |
| **P4（超过 ~1000 万条/秒）** | **自研消息平台**（阿里 Notify → 商业版 Heron 的路线）：多集群统一路由层、全局消息 ID 与轨迹、与内部基础设施（单元化、资源管理、对账平台）深度耦合 | 千万级 TPS | 数十人年级团队，非本平台当前阶段合理投入 |

### 8.3 集群拓扑规划（本方案采用）

| 集群 | 承载域 | 峰值目标 | Broker 规模（4 万 TPS/台 × 50% 利用率） | 特殊要求 |
|------|--------|---------|----------------------------------------|---------|
| **MQ-A 交易核心** | `order.*`、`payment.*`、`inventory.*`、`logistics.*`、`user.deleted`、`order.timeout.check`、事务/顺序消息 | 160 万条/秒 | 80 台（4 组 × 20 主从）→ 拆为 A1（下单/支付）+ A2（履约/库存/物流）各 40 台 | 同步双写（金融级）、SSD、72h 保留、跨机房容灾 |
| **MQ-B 营销** | 券/秒杀/拼团/砍价 | 40 万条/秒 | 20 台 | 大促可临时扩容；秒杀 Topic 独立 |
| **MQ-C 触达** | `message.send`、`push.*`、`cs.message`、`iap.*` 通知部分 | 90 万条/秒 | 45 台（可合并 B 或用较低配机器） | 允许 24h 保留；降级优先级最高 |
| **MQ-D 数据/商品** | `product.*`、`member.*`、`audit.*`、`store.*`、`export.request`、埋点/看板/足迹 | 60 万条/秒 | 30 台 | 允许 24h 保留；可作为 P3 迁 Kafka 的候选 |
| 合计 | — | 350 万条/秒 | **~175 台**（含主从） | — |

> **诚实说明**：175 台 Broker 是 350 万条/秒 + 1 KB 消息 + 2 副本 + 50% 利用率水位下的工程估算，是"阿里双 11 量级"的真实代价（阿里内部 MQ 峰值千万级 TPS 时 Broker 数以百计）。若实际压测单 Broker 达到 8 万 TPS（批量优化后可能），机器数可降至 ~90 台；若消息平均瘦身到 500 B，带宽与磁盘再降一半。**本平台若最终量级只有测算的 1/5（日常 1.2 万条/秒、大促 60 万条/秒），只需 1 个集群 24~32 台即可**——集群拆分方案应随真实量级缩放，不应一步到位铺 175 台。

### 8.4 何时需要自研（对照阿里 Notify/Heron 的触发条件）

满足**任意两条**才值得自研，否则用开源 RocketMQ + 多集群足够：

1. 单平台消息峰值持续 > 1000 万条/秒（多集群路由的运维成本超过自研成本）
2. 需要**跨集群**的全局事务/全局顺序/全局消息轨迹（单元化架构下的跨单元消息路由）
3. 消息平台需要与公司内部基础设施深度耦合（资源隔离与配额、单元化流量调度、统一资损防控平台）
4. 开源方案的 License/可控性风险不可接受

本平台当前 300 万条/秒、4 个业务域、无单元化诉求 → **不自研**，采用"RocketMQ 5.x 多集群 + 域隔离 + 业务侧幂等/对账"的组合，并把"通知/数据域可能迁 Kafka"（8.3 P3）作为中长期成本优化项保留在路线图上。

---

## 九、落地前必须闭环的三件事（跨文件待办）

1. **支付超时时长统一**（ios 15min vs android 30min vs pc 15min，见 6.2）——产品定稿后更新各端文件，延迟消息只认 `pay_deadline` 字段。**架构侧裁决已完成（00-conflict-register C7：`pay_deadline` 唯一事实来源），仅剩产品确认分钟数与 plans/ 三端回写。**
2. **事件注册表回写**：本文 7.1 的 30 个事件清单需要同步回 `admin-backend.md` 1.4.1（把"RabbitMQ 队列设计"改为"Topic/ConsumerGroup 设计"），避免两个文档各自演进。
3. **去重表分片改造**：`admin_message_dedup` 单表在 300 万条/秒下不可用，需按 `message_id` Hash 分表 + Redis 前置（7.2 第 2 条），该改造必须先于阶段 2（灰度切读）完成。

---

## 跨文档一致性修正（2026-10-11 收敛，详见 `00-conflict-register.md`）

| # | 修正 | 位置 | 依据 |
|---|------|------|------|
| 1 | 消息峰值 **300 万条/s（设计 350 万）** 确认为全架构唯一口径；`07` 号 30~50 万 msg/s、`08` 号 5.2 万 msg/s 均为各自漏斗低值，已在其文档加注回指 | §一 | C10-h |
| 2 | 下单锚点 30 万/s 与 `01` 号 50 万/s 矛盾 → 规划取 50 万/s，分域表订单域需上修，集群容量留 400 万条/s 余量 | §1.3 | C10-g |
| 3 | RabbitMQ→RocketMQ 5.x 迁移与 `01` 号"P0/P1 不迁、P2 一次性迁"裁决一致；`09` 号"必须换 Kafka"的建议按基线修订为 RocketMQ（已在 09 号加注） | §二 | C2 |
| 4 | 支付超时关单：`pay_deadline` 唯一事实来源，架构侧闭环，plans/ 三端修订在登记表跟踪 | §6.2、§九 | C7 |
