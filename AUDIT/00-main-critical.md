# AUDIT/00 — 主线程深审：资金与数据关键路径

> 审计者：主会话。范围：订单状态机、拆单分摊、支付回调、对账任务、库存/券并发入口、幂等拦截器、雪花ID。
> 方法：逐行读服务层关键实现 + 与 `plans/admin-backend.md` 4.3/4.4.1 交叉核对 + 跨切面 grep 口径扫描。
> 性质：与 `01-backend.md` / `02-frontend.md`（子代理广度审计）互补——此处只记录**亲自验证过的**结论，每条附证据行号。

## 一、P0（阻断上线级）

**无。** 未发现会造成资金错账、状态错乱或数据不一致的确定性缺陷。

## 二、P1（高：需在生产前修复或明确接受风险）

### P1-1 支付回调金额校验失败后，合法重试被去重日志永久拦截，且对账任务不覆盖该场景

- 位置：`server/core-payment/.../service/PaymentNotifyService.java:69-92`（第①步 `notifyLogRepo.saveAndFlush` 独立事务提交，第②③步在 `transactionTemplate` 内）
- 事实：`handleNotify` 第①步把 `notify_log` 单独提交（注释明说"避免与②③同事务回滚后失去去重能力"）。第③步金额校验失败抛 `BizException` → `transactionTemplate` 回滚 → ②的 `pending→paid` 翻转被撤销，**支付单停留在 `pending`**；但 `notify_log` 行已落库且不会删除。
- 后果链：渠道按标准语义用**同一 notifyId** 重试 → 第①步 `existsByNotifyId` 命中 → 返回 `repeated()` → **永远不会入账**。`PaymentReconcileJob:50` 只扫 `findByStatusAndOrderPaidNotifiedFalse(STATUS_PAID)`，本单是 `pending`，**对账兜底也不覆盖** → 钱已收、订单永久待付款，只能人工介入（且日志有 `[支付告警]`，至少可发现）。
- 理由定为 P1 而非 P0：触发前提是通知金额与支付单金额不一致（金额在 prepay 时服务端已定额，正常渠道不会发生），属异常路径；但一旦发生后果是资金链断裂且**无自动恢复路径**。
- 建议：给 `notify_log` 增加处理状态列（`RECEIVED`/`PROCESSED`/`REJECTED`），第①步只对 `PROCESSED`（或 `RECEIVED` 且在处理中）判重；③失败时把该行标 `REJECTED` 或删除，使修正后的重试可被处理。并让对账任务额外扫「`pending` 且创建超过 N 分钟 + 有渠道成功查询结果」的单。

### P1-2 幂等拦截器不区分「处理中」与「已成功」，业务失败不释放占位

- 位置：`server/common/.../idempotent/IdempotentInterceptor.java:47-64`
- 事实：占位在 `preHandle` 阶段写入（`putIfAbsent`），若后续 handler 抛业务异常，占位**不会释放**，TTL 内同键重试一律返回 `IDEMPOTENT_CONFLICT`(409/1005)。
- 后果：客户端"同一次提交的网络重试复用同 key"（安卓端已如此实现）语义正确；但**首次提交因库存不足(3002)/券失效失败后，用户用同一提交意图重试**会拿到"重复提交"而非真实业务错误，掩盖问题、误导用户与客服排障。跨实例不生效的局限已在类注释如实标注（P1 换 Redis），这点无问题。
- 建议：值语义改为 `PROCESSING`/`SUCCESS` 两态——仅 `SUCCESS` 判重；handler 抛业务异常时删除占位（系统异常保留占位以防重复提交的真实副作用）。Redis 版用 `SET NX` + 状态位 + 比对请求体摘要。

## 三、P2（改进建议）

| 编号 | 位置 | 问题 | 理由 |
|------|------|------|------|
| P2-1 | `core-order/.../support/DiscountAllocator.java:52-54` | `bases` 为空且 `totalDiscount≠0` 时 `result.get(0)` 抛 `IndexOutOfBoundsException` | 调用方始终传店铺分组（非空），当前不可触发；但作为公共工具类应有防御。负数 `base`（退款场景）亦未校验，会产生负比例分摊 |
| P2-2 | 同上 `:50-54` | 尾差归集到「首单（店铺ID升序）」，plans 4.4.2 原文是「金额最大的子单」 | 已在类注释与模块 README 明示为 P0 口径差异。总金额不变量 `Σ分摊==总优惠` 成立，仅"哪个子单承担这一分钱"不同，财务总额无影响；建议把 plans 表述改为"最大子单优先，实现暂取首单"以消除文档-代码歧义 |
| P2-3 | `core-order/.../service/OrderStateMachine.java:104-106` | 幂等早退只看**父单**状态：`from==target` 直接 `return false`，不再级联子单 | 正常路径下父子同事务更新不会漂移；但历史数据/人工改库造成的父子漂移不会被自愈。可改为：父单已 target 时仍扫一遍子单，补齐不一致的（记流水） |
| P2-4 | `core-payment/.../job/PaymentReconcileJob.java:39` | `@Scheduled` 进程内调度，多副本会重复扫描 | 补偿动作本身幂等无资金风险，类注释已声明 P1 换分布式调度；仅浪费资源 |
| P2-5 | `common/.../id/SnowflakeIdGenerator.java:42-45` | 时钟回拨时 `now = last` 强制走等待路径 | 无告警、无发号拒绝，极端回拨会阻塞发号线程。P0 可接受，但生产建议加回拨幅度阈值告警 |

## 四、✅ 已验证无问题的关键设计（留证）

### 4.1 订单状态机白名单与 plans 4.3 逐边一致

plans `admin-backend.md:2223-2231` 六行白名单 vs `OrderStateMachine.TRANSITIONS`（`:48-56`）：

| plans 当前状态 | plans 允许到达 | 实现 | 一致 |
|---|---|---|---|
| unpaid | paid、cancelled | UNPAID→{PAID, CANCELLED} | ✅ |
| paid | shipped、refunding、cancelled、closed | PAID→{SHIPPED, REFUNDING, CANCELLED, CLOSED} | ✅ |
| shipped | completed、refunding | SHIPPED→{COMPLETED, REFUNDING} | ✅ |
| completed | refunding、closed | COMPLETED→{REFUNDING, CLOSED} | ✅ |
| refunding | refunded、completed、closed | REFUNDING→{REFUNDED, COMPLETED, CLOSED} | ✅ |
| refunded | closed（仅系统） | REFUNDED→{CLOSED} + `validate()` 校验 `operator.type==3` | ✅ |
| cancelled、closed | 终态禁止流出 | `TERMINAL_STATES={CANCELLED,CLOSED}`（`:59,:148`） | ✅ |

另验证：状态变更为唯一入口且禁止直写 SQL（类注释约定）、幂等检查先于白名单（`:102-106`，避免 `from==target` 被误判非法）、`version` 乐观锁 CAS + 输方重读裁决（`:117-126`）、父子级联同事务（`:111-133`）、流水父子各一条、事件经 `AfterCommitEvents` 提交后发布（`:136`）。

### 4.2 支付回调三步与订单联动设计正确

`PaymentNotifyService`：①去重独立提交以保住去重能力（取舍合理，代价见 P1-1）→ ②`pending→paid` 条件更新（并发只成功一次）→ ③金额 `compareTo` 校验，失败抛异常连带回滚②（**不会出现"金额不符但已入账"**）→ 入账提交后才通知订单、订单通知失败不回滚支付单而落 `order_paid_notified=false` 交由对账补偿（`:140-149`）。终态（退款中/已退款）收到成功回调 → 告警并拒绝（`:124-129`），符合资金安全。mock 支付与真实回调共用同一处理链（类注释），杜绝测试通道绕过校验。

### 4.3 雪花偶数序列：当前日期下确属必需且实现正确

- 布局：41bit 时间戳（纪元 2024-01-01）<< 17 | workerId << 12 | sequence << 1（bit0 留白）。
- 验算：`2^53 / 2^17 = 2^36 ms ≈ 795 天`，即**自纪元起约 2.18 年后（2026 年年中）ID 开始越过 2^53**；当前（2026-10）ID ≈ 1.15e16 > 9.007e15，JS `Number` 只能精确表示偶数 → 偶数约束**当前是必需的**，联调修复（`Live-Integration` 报告 3004/4001）判断正确。
- 注释中「约 2028-05 越过 2^54」验算一致（2^54/2^17 ≈ 1584 天）。
- 实现：`sequence << 1` 写入不溢出 workerId 位段；单实例每毫秒 2048 个号；`synchronized` 保证线程安全；时钟回拨走等待（见 P2-5）。

### 4.4 跨切面口径扫描（全仓 grep）

| 扫描项 | 结论 |
|--------|------|
| plans 中 `99.99`/`1 亿`/`亿级`/`OceanBase`/`RocketMQ`/`单元化`/`异地多活` | 全部位于「已作废/差异对照表/不迁移决策」语境，无活口径残留 |
| 五端支付超时 | 无硬编码分钟数，一律读 `pay_deadline`/`payDeadline`（web-buyer `useCountdown.ts`、miniprogram `utils/countdown.ts`、ios `Components.swift:150`、android `PayScreen.kt:217`）——C7 落地；`web-buyer/mock/store.ts` 的 `PAY_TIMEOUT_MS` 仅用于 mock 造数且已注释声明 |
| 前端响应信封 | 全仓无 `message` 字段误用（统一 `msg`） |
| 订单状态基 | 无 1 基残留（无 `status===1→待付款` 类写法） |

## 五、结论

**后端资金与数据关键路径具备上线条件**，无 P0。两项 P1（P1-1 回调失败重试被拦截、P1-2 幂等占位不释放）建议在生产前修复或由项目组书面接受风险——两者均有 `[支付告警]`/`log.warn` 可发现，不构成静默错账。5 项 P2 可排入 P1 迭代。

**复审入口（已核对测试证据）**：`server/core-payment/src/test/.../PaymentIntegrationTest.java:147-163` 的「金额不符拒绝」用例，其恢复路径用的是**新通知号**（`:163` 注释"金额正确的通知（新通知号）仍可正常入账"）——恰好绕开了渠道标准的**同 notifyId 重试**语义，因此现有测试通过并不能证明该路径可恢复，反而印证了 P1-1 的缺口。修复后应补一条测试：「金额不符回调 → **同 notifyId** 修正金额重试 → 成功入账」。