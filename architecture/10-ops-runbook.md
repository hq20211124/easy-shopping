# 10. 运维说明书（Ops Runbook）

> 读者：SRE / 运维团队 / 值班工程师
> 定位：**可照着执行的操作手册**，不展开理论
> 基线：`plans/admin-backend.md` §1.5（部署）、§10（安全）、§11（性能）、§12（监控运维）、§16（灰度回滚）
> 口径来源：本仓库统一口径（1亿在线 ≈ 3亿DAU，应用层 300万~500万读QPS，四层LB+七层网关 → 单元化 RZone×N + GZone 微服务(K8s) → OceanBase/MySQL + Redis Cluster + RocketMQ + Elasticsearch）

## 0. 口径对齐（先看这张表，避免文档间打架）

| 主题 | 本文采用口径 | 与既有文档的关系 |
|------|------------|----------------|
| 规模 | 1亿在线 ≈ 3亿DAU；应用层 300万~500万读QPS（峰值，大促日） | 统一口径 |
| 接入 | 四层LB（LVS/云SLB，双AZ）→ 七层网关（**Higress/APISIX，能力对齐 Kong**，K8s 内，插件可用 Go/WASM 实现） | 选型以 `05-traffic.md` §0.2 裁决为准（原"Go/Envoy"表述作废）；`admin-gateway` 通用职责下沉网关插件 + BFF；买家端网关定名 `edge-gateway`（01 号总纲，原 07 号 `buyer-gateway` 为同义词，已废弃） |
| 服务形态 | 单元化：RZone×N（按 uid 路由的读写单元）+ GZone（全局单元：库存扣减、优惠券/积分账户、支付路由、雪花ID发号） | 统一口径；管理端后台不参与单元化，单独 `admin-prod` |
| 微服务框架 | Spring Cloud Alibaba（Nacos + OpenFeign + Sentinel + Seata） | admin-backend §1.1.2 |
| 交易主库 | OceanBase（买家端/交易链路，三副本）；MySQL（管理端 `admin_db_*`、报表、日志类库） | admin-backend §1.2 为 MySQL 分库分表方案，本文按基线把**交易主库**归 OceanBase，MySQL 保留给管理端/日志/对账离线链路；分片键仍为 `user_id`/`order_no`（admin-backend §1.2.1 约束不变） |
| 消息队列 | 交易链路 RocketMQ（基线）；管理端 admin-* 链路沿用 RabbitMQ（`admin.*.exchange` + DLX，见 admin-backend §1.4.1） | **差异点**：运维上按两套中间件分别巡检/告警；新增服务统一上 RocketMQ，RabbitMQ 只维护不新增 Topic，中期下线 |
| 可观测 | Prometheus/Grafana(+Thanos) + SkyWalking + ELK | 与 admin-backend §12.1 一致 |
| 服务命名 | 买家端/核心 `core-*`（core-product/core-price/core-promotion/core-user 等）；管理端 `admin-*` | admin-backend §1.1.3 |
| 归档 | 订单/库存流水 1 年后归档、操作/登录日志冷索引 | 直接沿用 admin-backend §12.3，本文只补备份与演练要求 |

---

## 1. 硬件/资源规格总表（量级估算，仅此章涉及硬件）

### 1.1 估算前提（调整本章数字时只改这几个参数）

| 参数 | 日常 | 大促 | 说明 |
|------|------|------|------|
| 应用层读 QPS（峰值） | 120万 | 500万 | 统一口径 300~500万取上限做容量冗余 |
| 应用层写 TPS（峰值） | 3万 | 25万 | 下单/扣库存/支付/积分，含异步落地 |
| 单应用 Pod 规格 | 4C8G（交易核心 8C16G） | 同左 | Java + SkyWalking agent |
| 单 Pod 稳态承载 | ~1000 QPS | ~1200 QPS | 缓存命中后口径，大促调优后略高 |
| 单节点（64C/512G）可调度密度 | 12~14 Pod | 14~16 Pod | 剔除系统预留与 DaemonSet |
| 多AZ冗余 | 每层 N+1 AZ | 每层 N+1 AZ | 任一 AZ 挂掉剩余容量 ≥ 60% 峰值 |
| 容量水位红线 | CPU P95 < 60% | CPU P95 < 70% | 触发扩容，见 §7.3 |

### 1.2 资源总表

> 估算方式：QPS ÷ 单实例承载 × 冗余系数(1.3~1.5)，跨 AZ 均摊。**所有数量为量级估算，实际按云环境机型与预算调整，调参见 1.1。**

#### 1.2.1 接入层

| 组件 | 节点规格 | 日常 | 大促 | 备注 |
|------|---------|------|------|------|
| 四层 LB（LVS/DPDK 或云 SLB） | 32C/64G，双万兆/百兆口 | 6（双AZ×3） | 12（双AZ×6） | 单实例 80~120Gbps，按带宽扩容 |
| 七层网关 Pod（K8s Ingress/Gateway，Go） | 4C8G | 40 | 160 | 单 Pod 1.5万~2万 QPS，含 TLS 卸载 |
| WAF / DDoS 清洗 | 云服务 | 按带宽计费 | 同左 | 峰值带宽预留 1.5 倍 |
| CDN | 云服务 | 带宽 200Gbps | 800Gbps | 静态资源+商品详情页，回源率 < 5%（**静态资源口径**；全站综合回源 QPS 占比 25%、回源带宽 ~485 Gbps，以 `05-traffic.md` §1.2/§1.3.2 为准；CDN 边缘总带宽按 12~15 Tbps 规划，见 00-conflict-register 容量总表） |

#### 1.2.2 K8s 集群与应用层（单元化）

| 组件 | 节点规格 | 日常 | 大促 | 备注 |
|------|---------|------|------|------|
| K8s 集群数 | — | 4 套 | 5 套 | 3 业务单元集群(每 AZ 一套) + 1 基础设施集群(监控/日志/中间件) + 1 异地灾备集群 |
| K8s Master/etcd | 16C/64G + 2T NVMe | 3×4 = 12 | 3×5 = 15 | etcd 独立盘，写 QPS 预警见 §4.2 |
| 业务工作节点 | 64C/512G，NVMe 3.2T | ~180 | ~750 | 应用 Pod 1.6万核 ≈ 250 节点 ×3 单元冗余口径 |
| 交易核心节点（高内存池） | 32C/256G + 3.2T NVMe | 24 | 96 | 库存/支付/订单，独占池 + 细粒度压测 |
| 管理端 `admin-prod` | 64C/512G | 12 | 30 | 管理端读写 QPS 低（约 2万），但要与交易隔离 |
| 弹性/Spot 节点池 | 64C/512G | 0 | 120 | 只跑可水平扩展的读服务，禁跑有状态 |

#### 1.2.3 数据层

| 组件 | 节点规格 | 日常 | 大促 | 备注 |
|------|---------|------|------|------|
| OceanBase 集群（交易主库） | 32C/256G + 4T NVMe | 3AZ × 6 = 18 | 3AZ × 12 = 36 | 三副本，OBServer 单机 2000~4000 TPS 稳态；每租户独立 Unit |
| OBProxy | 8C/16G | 6 | 12 | 无状态，双AZ |
| MySQL（管理端 + 对账/报表库） | 16C/128G + 2T SSD | 主从×3库组 = 6 | 6 + 只读从库 6 | 沿用 admin-backend §1.2.1 垂直 4 库口径 |
| Redis Cluster（缓存实例，allkeys-lru） | 64G/实例 | 96 节点（32G×96 ≈ 3T 有效） | 320 节点（≈ 10T 有效） | 3主3从起步，按 15万 QPS/主分片估算 |
| Redis（数据实例，noeviction：锁/幂等/Token/位点） | 32G/实例，主从 | 12 | 36 | 与缓存实例物理隔离（admin-backend §1.3.1 硬性要求） |
| RocketMQ 集群 | 16C/64G + 4T SSD | 12 Broker（3组） | 48 Broker（12组） | 主从同步双写；单 Broker 稳态 3万~5万 TPS |
| RocketMQ 事务/延时集群（支付回调、超时关单） | 16C/64G + 2T SSD | 6 | 18 | 与普通集群隔离，避免大促挤压 |
| RabbitMQ（仅管理端遗留链路） | 8C/16G | 3（镜像队列） | 3 | 只维护不扩容，见 §0 |
| Elasticsearch 集群 A（搜索/检索） | 16C/64G + 4T NVMe | 24（单分片 ≤ 50GB，副本≥1） | 80 | 商品/订单检索，JVM 堆 31G |
| Elasticsearch 集群 B（日志 ELK） | 16C/64G + 8T NVMe | 30 | 100 | 与搜索隔离；日志热 7 天/温 30 天/冷 6 个月 |

#### 1.2.4 中间件与支撑

| 组件 | 节点规格 | 日常 | 大促 | 备注 |
|------|---------|------|------|------|
| Nacos（注册+配置） | 8C/32G | 5（3+2 跨AZ） | 5 | 配置变更走 §3.3 流程 |
| Sentinel Dashboard | 4C8G Pod | 4 | 8 | 规则存 Nacos（§11.4.1） |
| Seata Server（AT/TCC） | 8C/32G | 3 | 6 | 只在资金/库存一致性场景 |
| SkyWalking OAP + UI | 8C/32G + 2T | OAP 12 / UI 2 | OAP 24 | 存储复用 ES 集群 A 的独立索引或独立集群 |
| Prometheus + Thanos | 16C/64G + 4T | 6 | 18 | 按单元分片抓取，对象存储存长期 |
| Grafana / Alertmanager | 4C8G | 2 / 3 | 4 / 6 | Alertmanager 必须多副本+去重 |
| Pushgateway / Exporters | 2C4G Pod | ~200 | ~600 | 中间件与云资源 exporter |
| Filebeat/Vector DaemonSet | 1C2G Pod（每节点1个） | 随节点 | 随节点 | 日志采集 |
| Logstash / Kafka（日志管道） | 8C32G | 12 | 40 | 日志洪峰缓冲 |
| Harbor 镜像仓库 | 16C/64G + 20T | 3（主从+异地复制） | 3 | 大促前完成预热拉取 |
| Jenkins/GitLab CI Runner | 8C32G | 6 | 6 | 大促冻结期停发版 |
| 备份服务器 + 异地对象存储 | 8C32G + 备份盘 | 3 + 对象存储 | 3 + 对象存储 | 见 §6 |

### 1.3 调整原则

1. **先水平后垂直**：应用层只做水平扩展；数据层优先加 OBServer/分片，其次升配。
2. **水位红线**：任何组件日常水位 ≥ 70%（大促 ≥ 80%）即为"需要扩容"，不等告警。
3. **每 AZ 容量 ≥ 峰值 60%**：机房级故障切换的硬性前提（§5.6）。
4. **预留 30% 冗余**：大促规格 = 预估峰值 × 1.3，且提前 14 天完成扩容并验证。
5. 硬件细节（机型/云规格族/磁盘 IOPS）由运维按实际云环境落地，本文不约束。

---

## 2. 部署拓扑

### 2.1 机房与单元划分

```
                        ┌──────────────┐
                        │   CDN + WAF  │
                        └──────┬───────┘
                               ▼
                 ┌─────────────────────────────┐
                 │  四层LB（双AZ Anycast VIP）  │
                 │  ECMP → 按五元组哈希         │
                 └───────┬───────────────┬─────┘
              AZ-A(主)   ▼               ▼   AZ-B(热备/分担)
   ┌──────────────────────────┐  ┌──────────────────────────┐
   │  七层网关集群 (K8s)        │  │  七层网关集群 (K8s)        │
   │  鉴权/限流/单元路由         │  │  同构                     │
   │  路由规则: uid % N → RZone │  │                          │
   └───────┬──────────────────┘  └──────────┬───────────────┘
           │                                │
   ┌───────▼────────────────────────────────▼───────┐
   │              单元化业务集群（每 AZ 一套）          │
   │  ┌─────────┐ ┌─────────┐      ┌─────────┐      │
   │  │ RZone-0 │ │ RZone-1 │ ...  │ RZone-N │  N=4 │
   │  │ core-*  │ │ core-*  │      │ core-*  │      │
   │  └────┬────┘ └────┬────┘      └────┬────┘      │
   │       └───────────┴───────┬────────┘           │
   │                    ┌──────▼──────┐             │
   │                    │   GZone     │  全局单元    │
   │                    │ 库存扣减/券池│             │
   │                    │ 支付路由/ID发号│            │
   │                    └──────┬──────┘             │
   └───────────────────────────┼────────────────────┘
                               ▼
     OceanBase(3副本, AZ-A主) / Redis Cluster / RocketMQ / ES / Nacos
                               │
                    ┌──────────┴──────────┐
                    ▼                     ▼
              AZ-C（同城，OB第三副本+只读流量）  异地灾备机房（备份+冷单元，RPO>0）
```

- **RZone（路由单元）**：N=4（日常），按 `uid % N` 划分用户，读写同单元内闭环；无登录态流量按 `device_id`/`ip` 哈希。
- **GZone（全局单元）**：库存扣减中心、优惠券/积分账户、支付渠道路由、雪花ID发号器（admin-backend §1.2.1 的全局ID要求）。
- **单元隔离原则**：RZone 之间禁止同步调用；跨单元只允许 GZone 与 MQ 事件；单单元故障不影响其他单元（§5.5）。
- **机房角色**：AZ-A/AZ-B 双活，AZ-C 承载 OB 第三副本 + 只读副本 + 备份归档；异地灾备为冷备，季度演练切换（§6.3）。

### 2.2 流量与调用路径（文字规范，强制）

| 请求类型 | 路径 | 说明 |
|---------|------|------|
| C端读（商品/首页） | CDN → 网关 → 本地缓存/Caffeine → Redis 缓存实例 → RZone Pod →（穿透）OB 从库 | 缓存命中率 ≥ 90%（§4.2） |
| C端写（下单/支付） | 网关 → RZone（uid 定位） → GZone（库存） → OB 主库 → RocketMQ 事件 | 同步链路禁止跨 RZone |
| 管理端操作 | 管理端四层LB → admin-gateway(Go) → admin-* Pod → MySQL `admin_db_*` | 与交易隔离，不占单元容量 |
| 管理端→C端数据 | admin-* → RocketMQ 事件 → core-* 消费更新缓存/ES | 最终一致性 + 定时对账（admin-backend §1.1.3） |
| 服务间同步 | OpenFeign（Java）/ gRPC（跨语言）；Nacos 发现 | 超时：连 1s/读 3s，网关总超时 10s；仅幂等接口可重试（admin-backend §1.1.2） |

### 2.3 K8s 命名空间规划

| Namespace（每集群） | 用途 | 关键策略 |
|--------------------|------|---------|
| `core-prod` | 买家端核心微服务（单元内） | NetworkPolicy 默认拒绝 + 白名单；禁止 cluster-admin 直接操作 |
| `core-gzone` | 全局单元服务 | 独占节点池，优先级 Guaranteed QoS |
| `admin-prod` | 管理端 admin-* 服务 | 与 core 逻辑隔离，出口走独立 SNAT |
| `infra-mw` | Nacos/Sentinel/Seata/网关 | 独立节点池 |
| `infra-db-proxy` | OBProxy/Redis 代理/MQ 客户端 sidecar | 仅暴露必要端口 |
| `monitoring` | Prometheus/Thanos/Grafana/Alertmanager/SkyWalking | 只读挂载 cAdvisor |
| `logging` | Logstash/Kafka Connect/Filebeat 控制端 | 与 ES B 集群绑定 |
| `staging` / `gray` | 预发与灰度 | 灰度单元流量 1%→5%→20%→50%→100%（§3.1） |
| `dr`（异地灾备集群） | 冷备单元 | 副本数=1，定时同步 |

**通用约束**：每个 Namespace 必须配 ResourceQuota + LimitRange + PodDisruptionBudget(PDB minAvailable=2 或 50%) + 默认 NetworkPolicy；禁止 `hostNetwork/hostPath`（DaemonSet 类除外）。

### 2.4 网络与端口规范

| 类别 | 规范 |
|------|------|
| 网段 | Pod CIDR `172.20.0.0/16`，Service CIDR `172.21.0.0/16`，节点网段 `10.10.0.0/16`，VIP `10.0.0.0/24`；跨机房 Underlay MPLS/专线，单元间带宽单独隔离 VLAN |
| 入口端口 | 仅 80/443 对公网（HTTP 强制 301→HTTPS）；管理端 VPN+443，源IP白名单 |
| 服务端口约定 | 业务端口统一 `8080`；健康检查 `/actuator/health`（Java）或 `/healthz`（Go）走 `8081`，与业务端口分离 |
| 中间件默认端口 | Nacos 8848/9848，RocketMQ NameSrv 9876 / Broker 10911,10909,10912，Redis 6379，MySQL 3306，OceanBase 2881(OBProxy 2883)，ES 9200，RabbitMQ 5672/15672，SkyWalking OAP 11800/12800，Prometheus 9090，Alertmanager 9093，Grafana 3000，Kibana 5601 |
| 端口纪律 | 新增端口必须登记在端口台账（运维 Wiki）；跨单元只允许经网关/GZone 端口，禁止直连 Pod IP |
| MTU / 长连接 | 网关→Pod 保持 HTTP/2 长连接，连接池 ≥ 500/实例；单 Pod 对外连接数上限 2000（防止雪崩扩散） |
| 证书 | 证书统一由内部 CA 签发，存储在 KMS/Vault，K8s 内通过 ExternalSecret 注入；禁止明文进 Git |

---

## 3. 日常运维手册

### 3.1 发布流程（灰度 → 全量 → 回滚）

```
代码合入 → CI(单测+SonarQube门禁) → 镜像构建+推Harbor(含SBOM) → 镜像扫描(阻断高危)
   → 部署 staging(自动化回归) → 人工验收
   → 灰度: 1单元/5%流量(30min观察) → 20%(1h) → 50%(4h) → 100%(全单元)
   → 每阶段观察: 错误率/RT/饱和度/业务大盘，任一恶化立即回滚
```

**发布窗口**：工作日 10:00–17:00；周五/大促前 72h/法定节假日禁止发布（紧急修复走 §3.1.4 特批）。

#### 3.1.1 发布前检查（Checklist，逐项打勾）

| # | 检查项 | 标准 |
|---|--------|------|
| 1 | 变更单 | 已审批，含回滚方案与影响面 |
| 2 | DB 变更 | Flyway 脚本已评审，且**只允许向前兼容**（先加列/双写，后删旧列，间隔≥7天） |
| 3 | 配置变更 | Nacos 配置与镜像版本绑定发布，禁止"先发代码后改配置" |
| 4 | 镜像 | 已通过扫描（无 Critical/High），SHA256 固定，禁止 `latest` |
| 5 | 资源 | 目标 Namespace 可调度资源 ≥ 当前用量 ×1.3 |
| 6 | 依赖 | 上下游服务已兼容（Feign 接口/消息体变更需双向确认） |
| 7 | 监控 | 新增/变更指标与告警规则已就位；SkyWalking 拓扑预期无新环路 |
| 8 | 灰度面 | 已指定灰度单元/分组，业务验证账号可用 |

#### 3.1.2 发布操作（K8s + Argo Rollouts，手动兜底）

```bash
# 1) 灰度 5%（单副本组，单 AZ-A 的 RZone-0）
kubectl -n core-prod apply -f argo/rollout-order-service-canary.yaml
kubectl -n argocd argocd app set order-service --helm-set image.tag=v2.13.1

# 2) 观察（30min，四项全绿才推进）
kubectl -n core-prod get pods -l app=order-service -o wide   # 无重启/OOM
kubectl -n monitoring exec -it prometheus-0 -- \
  promtool query instant 'rate(http_server_errors_total{service="core-order"}[5m])'
# SkyWalking: 观察 /dashboard 的 p99 与拓扑 RT，对比灰度前基线

# 3) 逐步推进
kubectl -n argocd argocd app set order-service --helm-set canary.weight=20   # 1h后 50 → 100

# 4) 手动兜底回滚（不依赖 Argo 时）
kubectl -n core-prod rollout undo deploy/core-order
kubectl -n core-prod rollout status deploy/core-order --timeout=180s
```

#### 3.1.3 自动回滚触发条件（任一命中，Argo/流水线自动 undo）

| 指标 | 阈值 | 观察窗 |
|------|------|--------|
| 灰度实例 5xx 率 | > 1% 且高于基线 2 倍 | 5min |
| P99 RT | > 基线 1.5 倍 | 5min |
| Pod 重启/OOM 次数 | ≥ 2 | 10min |
| 业务大盘：下单成功率 | 下降 > 0.5pp | 10min |
| 支付成功率 | 下降 > 0.2pp | 5min（直接 P0，见 §5.1） |

#### 3.1.4 紧急发布（Hotfix）

1. 仅限 P0/P1 故障修复；由值班 SRE + 服务 Owner 双人确认，钉钉/电话报备值班经理。
2. 跳过 staging 回归，但**不跳过**镜像扫描与 DB 兼容性检查。
3. 灰度阶段压缩为：5%（15min）→ 100%；发布后 24h 内补写变更单与复盘记录。

#### 3.1.5 回滚后动作

- DB 已执行过 Flyway 迁移的，禁止直接回滚镜像到不兼容版本，必须先回滚迁移脚本（`flyway undo`，要求所有迁移写 undo）。
- 通知相关业务方与客服口径；在故障群同步"已回滚 + 影响时间窗"。

### 3.2 扩缩容

#### 3.2.1 HPA 标准配置（随服务模板下发）

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: core-order, namespace: core-prod }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: core-order }
  minReplicas: 12            # 单元内基线
  maxReplicas: 120           # 大促前由运维统一上调（见 3.2.3）
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 60 } }
    - type: Pods
      pods:
        metric: { name: http_requests_per_second }
        target: { type: AverageValue, averageValue: "1000" }
  behavior:
    scaleUp:   { stabilizationWindowSeconds: 60,  policies: [{ type: Percent, value: 100, periodSeconds: 60 }] }
    scaleDown: { stabilizationWindowSeconds: 300, policies: [{ type: Pods,    value: 10,   periodSeconds: 60 }] }
```

**要求**：所有业务 Deployment 必须同时有 CPU 与 QPS 指标（仅 CPU 会在 GC/慢查询场景误判）；`scaleDown` 稳定期 ≥ 5min 防抖。

#### 3.2.2 手动扩缩容

```bash
# 扩容（例：大促前 core-order 12 → 60）
kubectl -n core-prod scale deploy/core-order --replicas=60
kubectl -n core-prod rollout status deploy/core-order --timeout=600s

# 临时封板：扩容前先确认节点池余量
kubectl get nodes -l pool=trade --field-selector spec.unschedulable=false | wc -l
```

#### 3.2.3 扩缩容决策表

| 触发信号 | 动作 | 责任人 |
|---------|------|--------|
| CPU P95 > 60% 持续 10min | HPA 已自动；若已达 max，手动提 max 并检查节点池 | 值班 SRE |
| P99 RT > 基线 1.3 倍且非下游引起 | 扩容 50%，同时排查 GC/慢查询 | 服务 Owner |
| 单元流量倾斜（某 RZone QPS 偏差 > 20%） | 调整网关哈希权重/重平衡分片 | 平台组 |
| 大促 T-14 | 按预测值整体上调 minReplicas 与节点池 | 容量负责人 |
| 大促结束 T+2 | 分批缩容（每批 30%，间隔 2h，保留 24h 观察期） | 值班 SRE |

**禁止**：缩容有状态服务（OB/Redis/MQ/ES）；在发布过程中扩缩容同一 Deployment。

### 3.3 配置变更（Nacos）

| 步骤 | 操作 | 要点 |
|------|------|------|
| 1 | 提配置变更单 | 附：Data-ID、Group、变更前后值、影响服务、回滚值 |
| 2 | 评审 | 涉及限流/熔断阈值、线程池、连接池、开关的，需服务 Owner 批准 |
| 3 | 灰度发布 | Nacos **灰度发布**（指定实例标签），观察 15min；无灰度能力的配置先在 gray 命名空间验证 |
| 4 | 全量 | 推送全量实例，确认所有客户端 `config gray=False` 且监听已生效 |
| 5 | 记录 | 变更记录同步到配置中心审计表与 Grafana 注释 |

**红线**：
- 一次性推送影响实例数 > 50% 的配置，必须错峰并分批（每批 ≤ 20% 实例，间隔 10min）。
- 支付、库存、幂等相关配置变更必须在"低峰期"（01:00–06:00）进行。
- 应急开关（降级开关、限流开关）只允许通过配置中心 + 双人复核操作，禁止改代码。
- 配置项命名：`{服务名}-{用途}-{环境}`；禁止在配置中存放明文密钥（用 KMS 引用 `${kms:xxx}`）。

### 3.4 证书 / 密钥轮换

| 对象 | 周期 | 操作方式 | 验证 |
|------|------|---------|------|
| 公网 TLS 证书（网关/WAF） | 90 天（ACME 自动续期 30 天起轮换） | cert-manager 自动；人工仅处理异常 | `openssl s_client` 检查链路 + 站点监控无过期告警 |
| 内部服务 mTLS 证书 | 180 天 | Vault/CA 批量签发 → ExternalSecret 更新 → 滚动重启（灰度→全量） | 拓扑无握手失败、SkyWalking 无 TLS 错误 |
| 数据库/中间件账号密码 | 90 天 | 新建凭证 → 应用双写读取 → 切换 → 吊销旧凭证 | 连接池无认证失败 |
| JWT 签名密钥 / HMAC 密钥 | 180 天（支持双密钥在线轮换） | 新密钥先"签发"，旧密钥仅"校验"，7 天后下线旧密钥 | 登录/鉴权错误率无上升 |
| KMS 主密钥（PIPL 敏感字段 AES） | 每年 + 算法升级时 | 走信封加密重加密（`re-encrypt` 批任务，分片分批） | 抽样解密校验 + 脱敏展示正常 |
| SSH/堡垒机密钥 | 180 天，人员离职即时 | 统一由堡垒机平台签发 | 旧密钥失效验证 |

**统一要求**：任何轮换必须"先加后删"，双凭证在线期 ≥ 24h；轮换窗口在 01:00–06:00；每季度做一次"证书到期前 30 天"的巡检演练。

---

## 4. 监控告警

### 4.1 指标分层

| 层 | 采集对象 | 核心指标 | 工具 |
|----|---------|---------|------|
| L1 基础设施 | 物理机/虚机/云主机、K8s Node、网络 | CPU/内存/磁盘使用率与 IOPS、网卡带宽与丢包、K8s Node Ready、Pod 重启/OOM、etcd fsync 延迟 | node_exporter、cadvisor、cloud-exporter → Prometheus/Thanos |
| L2 中间件 | OB、MySQL、Redis、RocketMQ、RabbitMQ、ES、Nacos、网关 | 连接数/活跃连接、QPS/TPS、P99、命中率、主从延迟、分片倾斜、堆积量、集群状态、GC | exporter + SkyWalking(java agent) |
| L3 应用 | core-* / admin-* Pod | QPS、P50/P95/P99 RT、5xx/业务错误率、线程池水位、JVM GC/堆、Feign 成功率、Sentinel 熔断/限流次数、下游依赖 RT（SkyWalking 拓扑） | micrometer + SkyWalking + Prometheus |
| L4 业务大盘 | 全链路 | DAU/在线人数、访问成功率、**下单成功率、支付成功率、GMV、库存扣减成功率、退款成功率、搜索无结果率、缓存命中率** | 业务埋点 → Kafka → 实时计算 → Grafana 大盘 |

**大盘要求**：`业务大盘（L4）` 永远放在 Grafana 首页最上方；L4 恶化而 L1-L3 正常 = 业务/配置/第三方问题，按 §5.1 处理。

### 4.2 关键指标与阈值（默认基线，按服务可微调）

| 指标 | 告警阈值 | 说明 |
|------|---------|------|
| 服务 5xx 率 | > 1%（P2）/ > 5%（P1）/ > 10%（P0） | 持续 3~5min |
| 服务 P99 RT | > 500ms（P2）/ > 1s（P1） | 基线的 1.5 倍同级 |
| 支付成功率 | < 95%（P1）/ < 90%（P0） | 5min 窗口 |
| 下单成功率 | < 97%（P1） | 5min 窗口 |
| DB 活跃连接 / 上限 | > 80%（P1）/ 拒绝连接（P0） | HikariCP `maximum-pool-size=50`（admin-backend §11.1.1） |
| DB P99 | > 200ms（P2）/ > 500ms（P1） | |
| MySQL 慢查询 | > 20 条/min（P2） | `long_query_time=1s` |
| Redis 命中率 | < 90%（P2）/ < 85%（P1） | 缓存实例 |
| Redis 内存使用率 | > 70%（P2）/ > 80%（P1）/ evict 发生于数据实例（P0） | 数据实例必须 noeviction，出现淘汰即 P0 |
| Redis 分片倾斜 | 单分片内存 > 均值 1.5 倍（P2） | |
| RocketMQ 堆积 | > 50 万（P2）/ > 200 万（P1）/ > 500 万（P0） | 交易 Topic 提一级 |
| RocketMQ Broker 存活 | 同步双写组内任一 Broker 掉线（P1） | |
| ES 集群状态 | yellow（P2）/ red（P1） | |
| ES JVM 堆使用 | > 75%（P2） | GC 频繁 |
| ES 磁盘 | > 80%（P1） | 触发只读 |
| Nacos 注册实例数骤降 | 1min 内 > 10%（P1） | 网关路由表空洞 |
| 网关连接数/带宽 | > 70%（P2）/ > 85%（P1） | |
| Pod 重启（10min） | ≥ 2（P2） | CrashLoop 为 P1 |
| 备份任务 | 连续 2 次失败（P1） | 见 §6 |
| 证书剩余有效期 | < 30 天（P2）/ < 7 天（P1） | |
| K8s 可调度节点 | < 总量 20%（P2）/ < 10%（P1） | |

### 4.3 告警分级与响应时限

| 级别 | 定义 | 触发示例 | 通知方式 | 响应 | 恢复目标 | 升级 |
|------|------|---------|---------|------|---------|------|
| **P0** | 全站/核心交易不可用，或资金数据风险 | 全站 5xx>10%；支付成功率<90%；单元或机房故障；数据不一致/超卖/重复扣款 | 电话 + 短信 + IM + 大屏 | **5min** 内认领，15min 未闭环自动升级 | 30min | 30min → SRE经理；60min → 技术总监 + 启动战时机制 |
| **P1** | 核心功能严重劣化 | 单服务 5xx>5%、RT>1s；DB 连接>80%；MQ 堆积>200万；Broker/节点批量掉线；备份连败 | 短信 + IM | 15min | 2h | 2h → SRE经理 |
| **P2** | 局部劣化，可延后处理 | 错误率 1~5%；RT>500ms；缓存命中<90%；磁盘>80%；ES yellow；证书<30天 | IM + 工单 | 1h（工作时段） | 24h | 24h 未处理自动升 P1 |
| **P3** | 提示性 | 磁盘>70%；版本可升级；容量水位接近阈值；巡检项异常 | IM 日报 | 下个工作日 | 1 周 | 周巡检汇总跟进 |

### 4.4 值班排班

| 项目 | 日常 | 大促/战时 |
|------|------|----------|
| 班次 | 7×24 两班（08:00–20:00 / 20:00–08:00） | 三班（8h）+ 现场战时指挥部 |
| 编制 | 每班 1 主值班(SRE) + 1 备值班(开发 Owner) | 每班 2 SRE + 每核心域 1 开发 + 1 DBA + 1 网络 |
| 轮换 | 每周一轮换，周五 16:00 交接会（书面交接单） | 战时名单 T-7 公布，含手机号与备份联系人 |
| 联系渠道 | IM 值班群 + 电话树 | 电话树 + 战时会议室 + 大屏 |
| 升级链 | 主值班 → 备值班 → SRE经理 → 技术总监 | 同左 + 业务负责人 |
| 免打扰保护 | 非 P0/P1 禁止电话；P2/P3 走 IM | 战时例外 |

**纪律**：值班期间禁止执行未走变更单的操作；值班手机 24h 开机；交接必须包含：未闭环告警、进行中变更、容量水位、待跟进工单。

### 4.5 告警抑制与收敛规则

| 规则 | 实现 |
|------|------|
| **基础设施故障抑制应用告警** | `AZDown`（critical）触发时，抑制同 `dc` 标签的 warning/info（Alertmanager `inhibit_rules`） |
| **依赖抑制** | `DBDown` 触发时抑制其依赖服务的 5xx/RT 告警 10min（避免风暴），但保留 DB 自身告警 |
| **告警风暴聚合** | 同 `alertname + service` 5min 内多实例触发 → 聚合为一条，附实例数；禁止 100 个 Pod 各发一条 |
| **重复收敛** | 同一告警 30min 内不重复通知，除非 severity 升级 |
| **维护期静默** | 变更窗口按变更单自动创建 Silence（Scope=受影响服务/单元，时长=变更时长+30min） |
| **灰度抑制** | gray 命名空间告警不直接电话，仅 IM，且只通知该服务 Owner |
| **阈值防抖** | 所有告警必须持续 N 分钟（P0=1min，P1=3min，P2=10min）才发出，禁止瞬时值告警 |
| **告警有效性月审** | 每月统计：误报率>30% 的规则必须优化或降级；连续 30 天未触发的规则评估保留或删除 |

**示例（Alertmanager 抑制片段）**：

```yaml
inhibit_rules:
  - source_matchers: ['severity="critical"', 'alertname=~"AZDown|LBDown"']
    target_matchers: ['severity=~"warning|info"', 'dc=~"$matchdc"']
  - source_matchers: ['alertname="DBConnectionsSaturated"', 'severity="critical"']
    target_matchers: ['alertname=~"Service5xx|ServiceRTHigh"']
```

---

## 5. 故障处置手册（Top 10 Runbook）

> 统一动作：**① 认领告警（5min）→ ② 判定影响面与级别 → ③ 按下表处置 → ④ 每 15min 在故障群同步 → ⑤ 24h 内复盘（模板见附录 B）**
> **止血优先于定位**：先用开关/限流/回滚/切流量止损，再查根因。

### 5.1 支付失败率上升（P0）

| 项目 | 内容 |
|------|------|
| **现象** | 大盘支付成功率 < 95%（P1）/ < 90%（P0）；支付服务 5xx 或超时上升；SkyWalking 拓扑显示 `core-payment` → 第三方渠道 RT 飙高 |
| **定位** | ① 大盘区分：是**全部渠道**还是**单一渠道**（按 channel 维度下钻）；② 查 `core-payment` 日志与 TraceID（SkyWalking 慢调用）；③ 查 OB `pay_order` 写入是否正常（排除自身 DB）；④ 查渠道方公告/回调地址是否可用 |
| **处置** | 单渠道故障：**立即在配置中心将该渠道权重调 0**（降级开关），流量切至备用渠道；全渠道失败：检查自身出网（NAT/网关/证书过期），恢复网络；若为重复扣款/账务不一致：**冻结对账任务并保留现场**，通知财务与资金组，禁止手工改库；同时对支付接口按渠道限流，保护下游 |
| **复盘** | 渠道 SLA、超时与重试策略、熔断阈值是否合理、是否需要新增第三渠道；资金差错必须出具对账差异报告 |

### 5.2 DB 连接打满 / OceanBase 过载（P1→P0）

| 项目 | 内容 |
|------|------|
| **现象** | `DBConnectionsSaturated`（活跃连接/上限 > 80%）；应用大量 `HikariPool-1 - Connection is not available`；RT 整体抬升 |
| **定位** | ① 连接数分布：按服务/单元看谁在占（`information_schema.processlist` / OB 等待事件）；② 找 Top 慢 SQL（慢查询日志/监控 Top SQL）；③ 查是否有未走分片键的全表扫/跨片查询（admin-backend §1.2.1 禁止项）；④ 查是否有长事务/未提交事务 |
| **处置** | 立即：**对触发服务限流**（Sentinel 阈值下调）并关闭非核心消费/报表任务；杀掉长事务与异常会话（保留 PID 清单）；必要时开启只读模式/切读流量至从库；扩容：临时调大 Hikari `maximum-pool-size`（需 DBA 确认 DB 侧 `max_connections` 余量，盲目调大会加剧雪崩）；根因：补索引/改写 SQL/补分片键，走变更流程 |
| **复盘** | 慢 SQL 台账、连接池容量模型、是否缺少 SQL 审核门禁（上线前 EXPLAIN 检查） |

### 5.3 缓存穿透 / 雪崩（P1）

| 项目 | 内容 |
|------|------|
| **现象** | 缓存命中率骤降至 < 80%；DB QPS 数倍上涨；Redis QPS 打满或连接数暴涨；商品详情/搜索接口 RT 抬升 |
| **定位** | ① 看命中率与 Redis `keyspace_misses`、按 key 前缀统计；② 判断是**穿透**（大量不存在的 key，DB 空查）还是**雪崩**（大批 key 同时过期）还是**热点 Key**（单 key QPS 异常高）；③ 查是否有活动开始/下架、爬虫或恶意刷接口 |
| **处置** | 穿透：开启/确认**空值缓存 + 布隆过滤器**（admin-backend §1.3.3）；对不存在 key 的查询做网关限流；雪崩：批量重新预热 + 过期时间加随机值；热点 Key：本地 Caffeine/网关缓存兜底 + 该 key 复制多份（hot-key 拆分）；严重时对该接口限流并降级返回缓存副本 |
| **复盘** | 布隆过滤器覆盖率、预热脚本是否覆盖全部热点、限流策略是否生效 |

### 5.4 RocketMQ 消息积压（P1）

| 项目 | 内容 |
|------|------|
| **现象** | `MQBacklog` 告警（堆积 > 50万/200万/500万）；消费延迟增长；下游业务（发货/积分/对账/通知）滞后 |
| **定位** | ① 看是哪个 Topic/Group（交易类提级为 P0/P1）；② 看消费 TPS 曲线：是**生产突增**还是**消费变慢/停止**；③ 检查消费者 Pod 是否健康、是否触发限流/熔断、是否进了 DLQ；④ 检查 RocketMQ Broker 磁盘与 IO |
| **处置** | 消费变慢：**扩容消费者**（注意 ≥ 队列数，避免空转）、调大消费线程数；若因单条毒消息：跳过该条（按 `messageId` 记录后 ACK），DLQ 消息走人工重投接口；生产突增：对上游生产端限流；Broker 磁盘不足：清理过期文件/扩容；**禁止**直接删 Topic 或清空队列（会丢业务数据） |
| **复盘** | 积压期间是否造成业务延迟（发货超时/对账延迟）、DLQ 处理 SLA、消费幂等是否正确 |

### 5.5 单元（RZone）故障（P0）

| 项目 | 内容 |
|------|------|
| **现象** | 某 RZone 的 Pod 大面积 NotReady/CrashLoop；网关路由该单元 5xx 激增；其他单元正常；单元维度大盘异常而全局大盘部分劣化 |
| **定位** | ① 确认是**单元级**还是**服务级**（该单元内多服务同时异常 → 单元级）；② 检查该 AZ 网络/存储/K8s 节点；③ 检查是否有该单元专属的配置或发布 |
| **处置** | **在网关摘除该单元**（路由权重调 0），流量由剩余单元与弹性池承接（前提：剩余容量 ≥ 60%，见 §1.3）；保留现场（Pod 不要立刻删除，先 `kubectl debug`/抓取日志与堆）；若有状态写入该单元，检查 GZone 与 OB 是否受影响；恢复后**小流量灰度回归**，不要一次性全量回切 |
| **复盘** | 单元隔离是否达标（是否存在跨单元同步调用）、容量是否满足 N-1 准则 |

### 5.6 机房 / AZ 级故障（P0，战时机制）

| 项目 | 内容 |
|------|------|
| **现象** | 整 AZ 网络不通/大面积超时；四层 LB 某侧全红；云厂商故障公告；监控该 `dc` 标签全线失联 |
| **定位** | ① 与云厂商/运营商确认；② 用剩余 AZ 的探活验证（LB 健康检查、DNS 探测、跨 AZ 调用）；③ 确认 OB 主副本所在 AZ |
| **处置** | ① 四层 LB：把故障侧 ECMP 权重调 0（或 DNS 切换），全部流量导向健康 AZ；② OceanBase：确认是否已自动切主（三副本仲裁），必要时按 DBA 手册强制切主并验证 `primary_zone`；③ 检查 GZone（库存/支付路由）是否可用，必要时降级为"库存异步扣减 + 事后补偿"（需业务负责人确认口径）；④ 单元服务按 AZ 整体摘除；⑤ 通知客服/公关口径；⑥ 故障 AZ 恢复后：先同步数据一致性（OB 副本状态、Redis 主从、MQ 位点），再分批灰度回切，禁止一键全量 |
| **复盘** | 演练覆盖度（§6.3 季度切换演练）、切换决策耗时、N-1 容量是否真实、对外沟通流程 |

### 5.7 Elasticsearch 搜索不可用 / 索引异常（P1）

| 项目 | 内容 |
|------|------|
| **现象** | 商品搜索/订单检索 5xx 或超时；ES 集群状态 yellow/red；分片未分配告警 |
| **定位** | ① `_cluster/allocation/explain` 看未分配原因；② 磁盘水位（>85% 触发只读）；③ JVM GC / 堆；④ 最近是否有 Mapping 变更或大批量写入（MQ 积压导致索引风暴） |
| **处置** | 磁盘满：清理冷索引/扩容数据节点/触发手动 rollover；red：定位丢失分片，用副本恢复；Mapping 冲突：回滚索引并重建（先建新索引 → 双写 → 切别名 → 删旧）；搜索降级：网关对搜索接口降级返回**缓存兜底结果**或静态热门榜（查询类可降级，资金类禁止降级，admin-backend §1.1.2） |
| **复盘** | 单分片 ≤ 50GB 是否达标、副本数 ≥1、写入风暴限流 |

### 5.8 网关 / 四层 LB 故障（P0，全站性）

| 项目 | 内容 |
|------|------|
| **现象** | 全站 5xx/超时，各服务自身指标正常（L4 正常但入口失败）；LB 健康检查失败；网关 Pod CPU 打满或 OOM |
| **定位** | ① 区分 LB 层还是网关层（LB 直接 curl 后端探针）；② 网关日志（限流/鉴权/JWT 校验失败突增）；③ 是否 TLS 证书过期或 Nacos 路由表空；④ 是否遭 DDoS/CC |
| **处置** | 网关过载：**先扩容网关 Pod**，再对非核心接口限流（营销/推荐先降级）；鉴权故障：临时开启降级模式（只校验 Token 有效性，跳过细粒度权限，故障后补审计）；LB 侧：切备用 LB/机房；DDoS：联系云商清洗 + WAF 策略收紧；证书过期：走 §3.4 双证书在线轮换流程 |
| **复盘** | 网关容量模型（单 Pod 1.5万~2万 QPS 是否准确）、限流规则覆盖面、熔断是否在网关层生效 |

### 5.9 K8s 集群 / 控制面或镜像仓库故障（P1）

| 项目 | 内容 |
|------|------|
| **现象** | `kubectl` 超时、apiserver 报错、etcd 延迟高；或 Harbor 不可用导致无法发布/扩容拉不到镜像；节点批量 NotReady |
| **定位** | ① apiserver/etcd 健康端点与 metrics；② 节点 kubelet/容器运行时日志；③ Harbor 与镜像同步任务 |
| **处置** | 控制面故障：**已运行 Pod 不受影响**，禁止无谓重启；按手册恢复 etcd（从快照）或切换到备用控制面；发布/扩容暂停，改为使用已缓存镜像的节点池；Harbor 故障：启用镜像同步副本仓库，或手动 `ctr images pull`；节点批量故障：驱逐 + 在健康 AZ 扩容；若影响面扩大到 P0，按 §5.6 处理 |
| **复盘** | etcd 备份有效性、控制面 HA 配置、镜像仓库异地复制 |

### 5.10 热点 Key / 秒杀打爆 Redis（P0/P1）

| 项目 | 内容 |
|------|------|
| **现象** | 单个/少数 Redis 分片 CPU 100%、连接数暴涨、超时；对应业务（商品详情/秒杀/券池）RT 抬升；其他分片正常 |
| **定位** | ① Redis 侧 `--hotkeys` / 监控按分片 QPS 定位热点 key；② 确认是否秒杀/大 V 商品/爆款券；③ 查是否本地缓存未生效或被击穿 |
| **处置** | 本地 Caffeine + 网关缓存先扛（要求已配置）；热点 key 复制打散（key + 随机后缀 × N）；对秒杀入口**单独限流 + 排队页**；券池/库存走 GZone 的原子扣减（Lua/原子命令），必要时降级为异步预扣；若数据实例被写爆（noeviction 满），优先扩容该实例而非加 maxmemory 淘汰策略 |
| **复盘** | 秒杀方案是否满足"只读走缓存、扣减走原子命令、不打库"；热点探测自动化 |

---

## 6. 备份与恢复

### 6.1 备份策略

| 对象 | 方式 | 频率 | 保留 | 存放 | 校验 |
|------|------|------|------|------|------|
| OceanBase（交易主库） | 全量备份 + 增量备份 + 归档日志持续备份 | 全量每日 02:00；日志持续 | 全量 30 天，日志 30 天；月全量 12 个月 | 异地对象存储（跨 region） | 每日自动校验 + 每周恢复抽检 |
| MySQL（管理端/对账库） | XtraBackup 物理全量 + binlog 增量 | 全量每日；binlog 实时归档（15min 一批） | 全量 30 天，binlog 30 天，月备 12 个月 | 异地对象存储 | 每周恢复抽检 + `CHECKSUM TABLE` |
| Redis 数据实例（锁/幂等/Token/位点） | RDB（每小时）+ AOF everysec | 每小时 | 7 天 | 同机房 + 异地 | 每周抽检 1 实例恢复 |
| Redis 缓存实例 | **不备份**（可丢失，靠预热脚本重建） | — | — | — | 预热脚本版本入 Git |
| RocketMQ | Broker 同步双写 + 消息轨迹文件 | 实时 | 7 天（轨迹 30 天） | 同机房 | 月末抽检重放 |
| Elasticsearch 搜索索引 | 快照 | 每日 | 14 天 | 对象存储 | 每月抽检 1 索引恢复 |
| ES 日志索引 | **不备份**（可重建，日志按 §12.3 归档策略转冷） | — | — | — | — |
| K8s 资源清单 / Helm Values | GitOps（Argo） | 每次变更 | Git 全历史 | 异地 Git | 每周 `argocd app history` 核对 |
| Nacos 配置 | 导出至 Git | 每日 + 每次变更后 | Git 全历史 | 异地 Git | 每周 diff 核对 |
| 证书 / 密钥 | KMS/Vault + 离线密封副本 | 轮换时 + 每年 | 按合规要求 | KMS + 离线 | 每年可用性演练 |
| 备份脚本与 Runbook 本身 | Git | 每次变更 | 全历史 | 异地 | — |

### 6.2 RPO / RTO 对照表

| 场景 | RPO | RTO | 保障方式 |
|------|-----|-----|---------|
| 单 Pod/单节点故障 | 0 | < 1min | 副本数≥2 + PDB + HPA |
| 单 AZ 故障 | 0（同城三副本） | < 5min | LB 摘除 + 单元摘除 + OB 自动切主（§5.6） |
| 单元（RZone）故障 | 0 | < 5min | 网关摘单元 + 弹性池承接 |
| OceanBase 副本级故障 | 0 | < 30s | Paxos 仲裁自动切主 |
| 机房级整体损毁（同城） | 0 | < 15min | 同城双活 + 手动切流 |
| 异地灾备（同城双活全失） | ≤ 5s（日志异步/半同步） | < 60min | 异地冷单元 + 备份恢复 |
| 逻辑误删/数据损坏（PITR） | 0 | < 4h | OceanBase/MySQL 时间点恢复 |
| 整站重建（极端） | ≤ 5s | < 8h | 全量备份 + 配置 Git + 镜像仓库副本 |

### 6.3 恢复演练

| 频率 | 内容 | 通过标准 |
|------|------|---------|
| **每日** | 自动校验：备份任务成功率 100%、binlog/归档日志连续性 | 失败连续 2 次告警 P1 |
| **每周** | 抽检恢复：随机选 1 个 DB + 1 个 Redis 数据实例，恢复到隔离环境 | 数据行数与校验和一致，应用只读连接可查询 |
| **每月** | PITR 演练：恢复到指定时间点（制造一个已知数据点并验证能否回到该点） | 时间点精确到分钟级，数据一致 |
| **每季度** | 机房级容灾切换演练（含 OB 主备切换、单元摘除、LB 切流、DNS 切换）+ 备份异地拉取恢复 | 实测 RTO ≤ §6.2 值；出具演练报告 |
| **每半年** | 全链路重建演练（新集群从 0 拉起：K8s → 中间件 → 应用 → 数据恢复） | 8h 内恢复核心交易链路 |
| **大促前（T-14）** | 强制：至少一次单元切换 + 一次 OB 切主演练 | 演练报告归档，未通过不得上线大促规格 |

---

## 7. 容量巡检

### 7.1 周巡检清单（每周一上午，值班 SRE 执行，结果填巡检表）

| # | 项 | 检查方法 | 通过标准 |
|---|----|---------|---------|
| 1 | 各集群节点 CPU/内存/磁盘 P95 | Grafana「容量周报」大盘 | 磁盘 < 75%，CPU P95 < 60% |
| 2 | 可调度节点余量 | `kubectl get nodes --no-headers \| grep -c Ready` | 余量 ≥ 20% |
| 3 | OceanBase 磁盘/Unit 分布/慢查询 | OB 控制台 + Top SQL 报告 | 磁盘 < 60%，无 Top 慢 SQL 未治理 |
| 4 | Redis 内存与分片倾斜 | Redis exporter 大盘 | 内存 < 70%，倾斜 < 1.5 倍 |
| 5 | RocketMQ 堆积与磁盘 | 控制台 | 工作日高峰堆积 < 5 万 |
| 6 | ES 集群状态与磁盘 | `_cluster/health` | green；磁盘 < 70% |
| 7 | 备份成功率与抽检 | 备份系统报表 | 100% 成功 |
| 8 | 证书剩余有效期 | 证书扫描脚本 | 全部 > 30 天 |
| 9 | 告警有效性 | Alertmanager 周报 | 误报率 < 30%，无静默遗忘 |
| 10 | 镜像仓库容量与垃圾回收 | Harbor 报表 | 剩余 > 30% |
| 11 | HPA 触顶统计 | HPA 历史 | 记录触顶服务，纳入扩容候选 |
| 12 | 安全补丁与漏洞扫描 | 扫描平台 | 无未处理 Critical |

### 7.2 月 / 季度巡检

| 周期 | 项 |
|------|-----|
| 月 | ① 月度容量报告（DAU/QPS 增长 vs 容量增长）；② 备份恢复抽检（§6.3）；③ 权限审计（离职/过期账号清理）；④ 日志与索引归档执行核对（对齐 admin-backend §12.3）；⑤ 告警阈值回顾 |
| 季度 | ① 容灾切换演练；② 证书/密钥轮换审计；③ 混沌工程（注入 Pod 驱逐、网络延迟、依赖故障）；④ 等保/PIPL 合规自查；⑤ 硬件与云资源账单优化（rightsizing） |

### 7.3 扩容触发指标（任一命中即扩容，不等告警）

| 组件 | 触发阈值 | 目标水位 |
|------|---------|---------|
| 应用 Pod | CPU P95 > 60% 持续 30min，或 P99 RT > 基线 1.3 倍 | CPU < 55% |
| 节点池 | 可调度 < 20% | ≥ 30% |
| OceanBase | 单 Unit CPU > 50% 或 磁盘 > 60% 或 日增 > 预测 1.2 倍 | CPU < 45%，磁盘 < 50% |
| Redis 缓存实例 | 内存 > 70% 或 命中率 < 90% | 内存 < 60%，命中 ≥ 93% |
| Redis 数据实例 | 内存 > 60% | < 50%（noeviction，无缓冲） |
| RocketMQ | 磁盘 > 60% 或 堆积常态 > 5 万 | 磁盘 < 50% |
| ES | 磁盘 > 70% 或 JVM > 70% | 磁盘 < 60% |
| 四层 LB | 带宽 > 峰值 60% | < 50% |
| 日志管道 | Kafka Lag > 30min | < 10min |

### 7.4 大促保障 Checklist（倒排）

| 时间点 | 动作 | 产出 |
|--------|------|------|
| T-45 天 | 容量预测（历史 × 增长 × 1.3），确定扩容规格与预算 | 容量评审纪要 |
| T-30 天 | 完成全部扩容（Pod/节点/OB Unit/Redis 分片/MQ Broker/ES 节点/LB 带宽） | 扩容完成单 |
| T-21 天 | 全链路压测（单单元 → 跨单元 → 全量），找出瓶颈 Top 10 | 压测报告 + 修复项 |
| T-14 天 | 故障演练：单元切换 + OB 切主 + 回滚演练（§6.3） | 演练报告 |
| T-10 天 | 缓存预热脚本验证、限流/熔断阈值按大促值调整并评审 | 配置快照 |
| T-7 天 | 第三方渠道（支付/物流/短信）扩容与降级预案确认；客服/公关口径确认 | 预案文档 |
| T-3 天 | **变更冻结**（除安全修复外禁止发布）；战时值班表公布；战时会议室/大屏就绪 | 冻结公告 |
| T-1 天 | 全量预热（CDN/Redis/本地缓存/热点商品）；灰度单元回归一遍 | 预热完成确认 |
| T-0（活动日） | 战时值班：实时盯大盘；每 15min 汇报水位；预设降级开关清单待命 | 战时记录 |
| T+1 天 | 复盘 + 分批缩容（保留 24h 观察期） | 复盘报告 |
| T+7 天 | 容量回收完成，恢复正常巡检 | 回收确认单 |

---

## 8. 安全运维

### 8.1 漏洞响应

| 等级 | 定义 | 缓解时限 | 修复时限 | 升级 |
|------|------|---------|---------|------|
| 紧急（0day / CVSS ≥ 9.0） | 可远程利用且已出现 PoC | **4h 内**（WAF 规则/下线/网络隔离） | **7 天** | 超 24h 未缓解 → CISO |
| 高危（7.0–8.9） | 核心组件可利用 | 24h | 14 天 | 超 7 天 → 安全周会跟进 |
| 中危（4.0–6.9） | 需特定条件利用 | — | 30 天 | — |
| 低危（< 4.0） | 影响有限 | — | 下个迭代 | — |

**流程**：扫描（每日镜像扫描 + 每周主机扫描 + 每季度渗透测试，对齐 admin-backend §10）→ 建工单（带影响面与修复建议）→ 修复 → 复扫关闭 → 未修复项必须有补偿控制措施并登记风险台账。

### 8.2 权限最小化

| 面 | 措施 |
|----|------|
| K8s | 默认无权限；开发用 `view/edit`（Namespace 级）；`cluster-admin` 仅 2 名平台管理员，且日常使用 break-glass 账号（使用即告警）；所有 `exec`/`delete pod` 走审计代理 |
| 主机 | 禁止直连 SSH，统一堡垒机（录像+命令审计）；跳板账号按项目授权，TTL ≤ 8h |
| 数据库 | 应用账号仅授予最小库/表/列权限；只读账号独立；DDL/DML 超 1000 行需 DBA 双人执行；禁止应用账号互相访问 |
| 云平台 | RAM 按角色分权；生产与测试账号隔离；AK 存 KMS，禁止明文 |
| 变更 | 生产变更走工单 + 双人复核（4-eyes）；紧急变更事后 24h 内补单 |
| 密钥 | 统一 Vault/KMS；轮换见 §3.4；开发者不可导出明文 |
| 审计 | 审计日志（堡垒机、K8s Audit、DB 审操作、配置中心）保留 ≥ 6 个月，只增不删，独立存储防篡改 |

### 8.3 数据合规（PIPL 对齐）

> 与 admin-backend §10.2、§12.3、§1.4.1 `user.deleted` 事件的要求保持一致。

| 要求 | 运维落地 |
|------|---------|
| 加密存储 | 手机号/邮箱/身份证/银行卡 AES 加密，密码 BCrypt；KMS 主密钥每年轮换（§3.4） |
| 展示脱敏 | 日志/日志平台/Kibana 默认脱敏；**禁止**明文手机号/身份证进入日志（Filebeat 侧做正则脱敏） |
| 最小留存 | 订单/结算/发票按法定要求留存（1 年+），到期自动归档或删除；操作日志 6 个月、登录日志 3 个月（沿用 admin-backend §12.3） |
| 注销即删 | 消费 `user.deleted` 事件（30 分钟内完成，失败 3 次转人工）；运维需保证该 Topic **不积压、不静默进 DLQ**（纳入 §4.2 告警）；删除回执审计表作为举证 |
| 用户权利 | 提供查询/更正/删除/导出通道；工单 SLA：15 个工作日内响应 |
| 数据出境 | 生产数据默认不出境；如需跨境，必须通过安全评估并登记；备份异地存放位置需登记在数据地图 |
| 第三方共享 | 与支付/物流/短信等共享数据须有协议与最小化字段清单；运维负责接口调用审计 |
| 应急与上报 | 发生个人信息泄露：2 小时内上报 CISO 与法务，按监管要求通知个人与主管部门；保留取证现场（禁止"先清日志"） |
| 等保/测评 | 按三级要求保留测评与整改记录；每年一次测评 |

---

## 附录 A：常用排障命令速查

```bash
# 单元/服务健康
kubectl -n core-prod get pods -l app=core-order -o wide | grep -v Running
kubectl -n core-prod describe pod <pod> | tail -30
kubectl -n core-prod logs <pod> --since=15m --tail=2000 | grep -iE "error|exception"

# 发布与回滚
kubectl -n core-prod rollout history deploy/core-order
kubectl -n core-prod rollout undo deploy/core-order
kubectl -n core-prod rollout status deploy/core-order --timeout=180s

# 扩缩容
kubectl -n core-prod scale deploy/core-order --replicas=60
kubectl top pods -n core-prod --sort-by=cpu | head -20
kubectl top nodes | sort -k3 -hr | head -20

# 配置（Nacos）
curl -s "http://nacos:8848/nacos/v1/cs/configs?dataId=core-order.yaml&group=DEFAULT_GROUP" | head

# OceanBase / MySQL
mysql -h obproxy -P 2883 -u app_ro -p -e "show processlist;"
mysql -h obproxy -P 2883 -u app_ro -p -e "select * from oceanbase.gv\$ob_transaction_participants order by 1 desc limit 20;"

# Redis
redis-cli -h redis-cache info memory | grep used_memory_human
redis-cli -h redis-cache --hotkeys        # 需开启 LFU
redis-cli -h redis-data info keyspace     # 数据实例必须无 evicted_keys 增长

# RocketMQ
sh mqadmin clusterList -n nameserver:9876
sh mqadmin consumerProgress -n nameserver:9876 -g core-order-consumer
sh mqadmin topicList -n nameserver:9876

# Elasticsearch
curl -s "es-a:9200/_cluster/health?pretty"
curl -s "es-a:9200/_cat/shards?v" | grep -v STARTED | head
curl -s "es-a:9200/_nodes/stats/jvm?pretty" | grep heap_used_percent

# 网络与入口
curl -sI https://www.example.com/healthz -o /dev/null -w "%{http_code} %{time_total}\n"
ss -s && ss -ant | awk '{print $1}' | sort | uniq -c | sort -nr
```

## 附录 B：复盘模板（故障后 24h 内提交）

```markdown
# 故障复盘报告
- 故障标题：
- 级别（P0-P3）：
- 影响时间：开始 / 止血 / 恢复（各写时刻）
- 影响面：受影响用户数、订单数、GMV 损失、是否涉及资金/个人信息
- 时间线（分钟级）：告警 → 认领 → 判定 → 止血 → 恢复
- 根因（5 Whys）：
- 止血措施：
- 改进项（每项必须有 Owner + 截止日期 + 可验证标准）：
  | # | 改进项 | Owner | 截止 | 验收标准 |
- 是否需要更新 Runbook/告警阈值/容量模型：
- 复盘会时间与参会人：
```

## 附录 C：Runbook 维护约定

1. 本文件由 SRE 团队维护，**每次故障复盘后必须回写**（新增场景或修订处置步骤）。
2. 每季度全量评审一次，删除失效内容，核对与 admin-backend.md §12、§16 及架构文档的口径一致性。
3. 变更记录：

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| v1.0 | 2026-10-11 | 首版：硬件估算、拓扑、日常运维、监控告警、Top10 Runbook、备份恢复、容量巡检、安全运维 | SRE |

---

## 跨文档一致性修正（2026-10-11 收敛，详见 `00-conflict-register.md`）

| # | 修正 | 位置 | 依据 |
|---|------|------|------|
| 1 | `core-pay` → **`core-payment`**（任务书误写名，仓库无此服务） | §5.3 支付故障 Runbook | 01 号 §0.2 命名确认（C1） |
| 2 | 七层网关选型统一为 **Higress/APISIX**（能力对齐 Kong），买家端网关定名 `edge-gateway` | §0 口径对齐表 | 05 号 §0.2 裁决（C5/C12） |
| 3 | CDN"回源率 <5%"标注为静态资源口径，全站综合回源以 05 号为准 | §1.2.1 | 00-conflict-register 容量总表（C10） |
| 4 | 本文 MQ 口径（交易 RocketMQ / 管理端遗留 RabbitMQ 只维护）与 01/04 号一致；**消息峰值统一以 04 号 300 万条/s 为准**，本文 §1.2.4 的 Broker 数按该值复核 | §0、§1.2.3 | C2/C10 |
