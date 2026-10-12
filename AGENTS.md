# AGENTS.md

## 项目概览

电商平台项目规划仓库。当前处于**规划阶段**，尚无可执行代码，全部交付物为设计文档。

五个终端：管理端（仅浏览器 BS 架构）、PC 客户端、安卓客户端、iOS 客户端、小程序客户端。管理端用于商家/平台管理，其余四个为买家购物客户端。后端统一微服务架构，各端共享核心服务。

## 目录结构

```
plans/          10 个业务计划文档（每端拆分为前端 + 后端）
architecture/   12 个亿级架构文档（00 索引/冲突登记 + 01~10 专题）
AGENTS.md       本文件
```

### plans/（业务计划，10 份）

| 文件 | 内容 |
|------|------|
| admin-frontend.md / admin-backend.md | 管理端（React + Ant Design Pro / 微服务） |
| pc-client-frontend.md / pc-client-backend.md | PC 客户端（Electron/Tauri） |
| android-frontend.md / android-backend.md | 安卓客户端（Kotlin + Compose + MVI） |
| ios-frontend.md / ios-backend.md | iOS 客户端（Swift + SwiftUI，最低 iOS 16） |
| miniprogram-frontend.md / miniprogram-backend.md | 小程序（uni-app，多平台） |

每份文档包含：模块拆分、API 设计、数据模型、技术要点，文末附「审查记录」「跨文件同步记录」「跨架构裁决回写记录」。

### architecture/（亿级架构，12 份）

目标 **1 亿同时在线**（≈3 亿 DAU）。阅读顺序：`00-index.md` → `01-overview-unitization.md` → `09-performance-testing.md` → `00-conflict-register.md`。

- **00-index.md** — 总索引：统一口径、技术上限汇总、结构性缺口
- **00-conflict-register.md** — **口径裁决唯一入口**，14 项冲突（C1~C14）+ 22 层容量口径总表；plans/ 侧回写清单在第四节
- 01 总纲与单元化 · 02 数据存储 · 03 缓存 · 04 消息 · 05 流量接入 · 06 搜索推荐 · 07 服务治理 · 08 高可用多活 · 09 性能压测 · 10 运维说明书

## 关键约定（修改文档前必读）

### 唯一事实来源

| 事实 | 真值位置 |
|------|----------|
| 服务命名 | `core-product`/`core-payment`（**不是** core-item/core-pay），10 个 `core-*` + 21 个 `admin-*`；`edge-gateway`（非 buyer-gateway） |
| 订单状态机 | **0基** 8 值（0待付款/1待发货/2待收货/3已完成/4退款中/5已退款/6已取消/7已关闭），见 admin-backend 4.3 与 L404 DDL 注释，**不是 1 基** |
| `order_no` 编码 | 24 位 = 时间14 + 槽位4 + 序列6，见 02-data-storage §3.2 |
| 支付超时 | 订单表 `pay_deadline` 字段，各端不自定义分钟数 |
| 缓存 Key | ios-backend 四段式 `{业务域}:{模块}:{标识}:{版本}`，见 03-cache §6.3 |
| 签名窗口 | ±5 分钟 / Nonce TTL 10 分钟（android-backend 13.1） |
| 容量口径 | `architecture/00-conflict-register.md` 第二节 22 层总表 |
| MQ 事件 | 30 个事件名以 plans/ 原事件名为准 |

### 陷阱

- `android-frontend.md` 中的 `core-network`/`core-ui` 等是**安卓客户端模块名**，与后端 `core-*` 同名不同物，禁止混淆。
- `admin-backend.md` 实际章节：安全=十、性能=十一、监控运维=十二、其余模块=十七（**无第 18 章**）。
- 技术栈分阶段迁移：plans/ 现写 MySQL+RabbitMQ+ShardingSphere，architecture/ 基线为 OceanBase+RocketMQ 5.x，**P0/P1 不迁移、P2 一次性迁**（不要中途混改）。
- 文件均为 **UTF-8 编码**；PowerShell `Get-Content` 默认按 ANSI 读取会导致中文乱码与匹配失败，读取时须显式指定 `[System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)`。

## 工作流程约定

- 修改任何文档的口径/契约前，先查 `00-conflict-register.md` 是否已有裁决，避免重新引入冲突。
- 跨文档同步后，需在目标文件文末追加对应记录章节（审查记录 / 跨文件同步记录 / 跨架构裁决回写记录），并在登记表更新状态。
- 大规模文档生成与审查使用并行 subagent 分工（编写 → critic 反向找茬 → 跨文件同步），每份文档独立闭环。

## 待决策项（需项目组书面确认，文档层面已无未闭环项）

- 支付超时分钟数（建议 30 分钟）
- iOS App Clip 是否在阶段一提供
- 三方支付/登录资质审核
- 安卓热修复 Tinker × AGP 8.x PoC
- 运维编制：现有计划写 2 人，实际需 SRE 8~10 人（见 09 号文档 §8）
