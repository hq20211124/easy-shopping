# 后端代码审计报告（server/ 13 模块）

- **审计对象**：`C:\D\agent\easy-shopping\server` — common / gateway / core-user / core-product / core-price / core-inventory / core-cart / core-order / core-payment / core-promotion / core-search / admin-bff（12 个 Maven 模块 + 共享 common）
- **审计方式**：只读静态审计（未执行构建/测试，未修改任何代码）
- **审计基准**：`plans/admin-backend.md` 4.3/4.4、`plans/00-架构总纲.md`、`DEV.md` 3/4/6、AGENTS.md 关键约定
- **审计日期**：2026-10-12
- **优先级定义**：P0 = 逻辑错误 / 资金或数据不一致 / 安全漏洞；P1 = 完备性缺口 / 质量隐患；P2 = 改进建议

---

## 摘要

| 级别 | 数量 |
|------|------|
| P0 | 8 |
| P1 | 12 |
| P2 | 9 |

审计范围内**状态机、拆单分摊、支付回调三步幂等、库存两段式、优惠券条件更新**这几条资金主链路的实现质量明显高于平均：状态机有白名单 + 终态拦截 + CAS + 流水的四件套；`DiscountAllocator` 用 `RATIO_SCALE=12` 中间精度 + `setScale(2, HALF_UP)` + 尾差归集，并在 `OrderCreateService.persist` 末尾做了 Σ子单实付 == 父单实付的硬校验；支付回调三步（notify_log 唯一键 → 状态 CAS → 金额校验回滚）逐行可验证，且有 `PaymentIntegrationTest` 覆盖重复 10 次只生效 1 次。这些**不是 P0 问题所在**。

P0 集中在三个地方：
1. **预支付取错金额字段**（`totalAmount` 而非 `payAmount`）——有券订单会多收钱；
2. **`@Idempotent` 在 cart/order 上是"先占位后失败"语义**——失败的请求也吃掉幂等键，用户无法重试；
3. **退款通道是空实现**（`RestPaymentClient.refund` 只打日志）+ **关单不退券**——两条竞态补偿路径都是假的。

---

## A. 资金与数据正确性

### A1. 订单状态机

#### ✅ 做得正确的部分（作为对照基线）

- `OrderStateMachine.TRANSITIONS`（`core-order/.../service/OrderStateMachine.java:48-56`）逐边对照 `plans/admin-backend.md:2225-2230` 白名单表，**7 行完全一致**，包含 v1.2 补的 `REFUNDED → CLOSED` 归档边。
- `TERMINAL_STATES = {CANCELLED, CLOSED}`（:59）显式声明，且这两个状态**不作为 `TRANSITIONS` 的 key**，`validate()` 先查终态再查白名单（:148-156），不会出现 plans 4.4.1 注释里提到的"终态 `Map.get()` 返回 null"的老 NPE。
- 归档边额外加了操作人校验（:157-160）：`REFUNDED → CLOSED` 仅 `operator.type()==3`（系统）可触发，对应 plans "禁止人工调用"。
- 唯一入口：`OrderMainRepository` 只有 `casUpdateStatus` 一个写状态的 `@Modifying` 方法，其余全是 `fill*` 回填方法（不改 `order_status`），结构上保证了 plans 4.4.1 使用约束"禁止直接 update order_status"。
- 状态流水：每次 CAS 成功写一条 `OrderStatusLog`（:128-130），父子单各一条（group 循环），满足 plans 4.3 规则 3。
- 事件在 `AfterCommitEvents.publish`（事务同步器 afterCommit）发布，**未发现"事务内发事件"的违规**。

**但有 3 个问题：**

---

#### [P0] `core-order/.../service/OrderStateMachine.java:104-107` — 幂等短路发生在终态校验之前，导致终态订单的"同状态重复请求"被误判为成功

```java
// 幂等优先（4.4.1 第 2 步）：已处于目标状态直接返回 false
if (from == target) {
    return false;
}
validate(from, target, operator);
```

**理由**：`from == target` 的短路在 `validate()` 之前。考虑 `markPaid` 的调用方 `PaymentNotifyService`：`casAndVerify` 把支付单 CAS 成 `PAID` 后调 `orderClient.markPaid()`。如果这时订单**已经是 `CANCELLED`（终态）**，`from(CANCELLED) != target(PAID)`，所以这个具体路径没问题。

真正的漏洞在**子单级联循环内**（:111-116）：父单先 CAS 成功（`from` 从数据库读的是 UNPAID），随后轮到某个子单时该子单可能**已被并发的另一条路径推到了 `target`**。此时 `currentFrom == target` → `continue`（:113-115），**跳过了 `validate`**。这条还算安全。

反过来的漏洞更实际：调用方拿到 `false` 后无法区分"已幂等命中"与"被拒绝"。`OrderLifecycleService.markPaid` 的循环（`OrderLifecycleService.java:137`）收到 `changed==false` 时只打 WARN 然后 `continue`，**最多重试 3 次后抛 4002**。而 `OrderStateMachine.transition` 在 `from==target` 时返回 false 是一种**完全成功**的语义。两者混淆的后果：一个已经 PAID 的订单（`markPaid` 里其实在循环顶部 `status == PAID` 就 return ALREADY_PAID 了，:93-96，所以这条不触发）——但 `ship()`（`OrderLifecycleService.java:188-190`）自己做了前置短路，说明作者也意识到语义混乱。

**实际可触发的失败场景**：并发两个 admin 发货请求同时到达，两个都通过 `ship()` 的 `!= SHIPPED` 前置检查，同时进入 `transition`。A 先 CAS 成功（group 内父+子全部），B 的父单 CAS 返回 0 → 重读发现 `== SHIPPED` → `continue`（:125）→ `changed` 仍为 false → B 返回 `false` → `ship()` 拿到 false 但**没有检查返回值**（:191 直接忽略），继续执行 `fillDeliveryTime`（:193-197）**重复写发货时间**。结果：流水里只有 A 的一条，但 `delivery_time` 被 B 覆盖成更晚的时间，且 B 的 `trackingNo`（不同值时）被 A 的覆盖丢失。

**建议修复**：
1. 把 `from == target` 短路移到 `validate()` 之后（终态 + 白名单先过），并在 javadoc 说明；
2. `ship()` 检查 `transition()` 返回值，`false` 时直接 return 不执行 `fillDeliveryTime`；
3. 或让 `transition` 返回三态枚举（`CHANGED` / `ALREADY_IN_STATE` / `REJECTED`）而非 boolean。

---

#### [P0] `core-order/.../service/OrderStateMachine.java:117-126` — CAS 败方在"另一方已推到目标态"时静默 `continue`，父单成功 + 全部子单跳过的组合会返回 `changed=true` 并发事件，但订单组内状态可能不一致

```java
int rows = orderMainRepository.casUpdateStatus(
        current.getId(), currentFrom.getValue(), target.getValue(), current.getVersion(), now);
if (rows == 0) {
    OrderMain fresh = load(current.getId());
    if (OrderStatus.of(fresh.getOrderStatus()) != target) {
        throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "订单状态已变更，请刷新后重试");
    }
    continue;
}
```

**理由**：类注释（:39-40）声称"整组在一个事务内，任一 CAS 失败整体回滚"。但 `continue` 分支**不抛异常也不回滚**，事务正常提交。考虑：父单 CAS 成功（`changed=true`），子单 A CAS 成功（`changed=true`），子单 B 的 CAS 返回 0 且重读发现 `fresh.status != target` → 抛 BizException → `@Transactional` 回滚 → **父单和子单 A 的 CAS 都回滚**。这条是对的。

**真正的问题在异常路径的回滚后 `markPaid` 的重试**：`OrderLifecycleService.markPaid`（:112-120）捕获 `ORDER_STATUS_ERROR` 后 `continue` 重读，最多 3 次。若 3 次都撞上"子单被人推到别的状态"，最终抛 `BizException(4002)` 给 core-payment。core-payment 的 `notifyOrderAndPublish`（`PaymentNotifyService.java:142-149`）捕获后只打 ERROR 日志，**支付单已 paid 但订单永远是 UNPAID**，只能等 `PaymentReconcileJob` 每小时补偿。而 `PaymentReconcileJob.compensate`（:70-75）看到 `order.unpaid()` 就再调一次 `markPaid`，**如果还是失败就无限循环每小时重试**。这是一个无自愈的死循环告警态。

**建议修复**：`markPaid` 的重试上限耗尽时，不能只抛 4002，应落一条 `payment_reconcile` 待处理记录（或直接标记支付单 `order_paid_notified=false` + ERROR 级别告警接入值班），并在 `PaymentReconcileJob` 加**重试次数上限 + 指数退避**，超过阈值升级为 P1 人工工单，避免每小时无限重打。

---

#### [P1] `core-order/.../service/OrderStateMachine.java:107` vs `OrderLifecycleService` 各前置校验重复且不一致

`OrderStateMachine.validate()` 已经做了终态 + 白名单校验，但 `OrderLifecycleService` 的 `cancelByUser`（:148-150）、`confirmReceive`（:166-169）、`closeTimedOut`（:248-252）、`closeByAdmin`（:217-234）各自又写了一遍状态前置判断。这些**功能上冗余**（`:164` 注释自己说"而非依赖状态机抛错"），但两套判断在未来演进时会漂移。`closeTimedOut` 的 `status != UNPAID → SKIPPED` 与 `cancelByUser` 的 `status != UNPAID → 抛 4002` 语义不同（前者静默、后者报错），是有意的，但没有任何注释或测试固化这个差异的意图。

**建议**：抽 `OrderStateMachine.assertCanTransition(from, target, operator)` 为 public，或在 `OrderLifecycleService` 的每个前置校验旁加注释指向状态机的哪一条边。

---

### A2. 拆单与金额

#### ✅ 做对的部分

- 按 `store_id` 分组（`OrderCreateService.java:198-202`），分组键来自 core-product 的 `SkuView.storeId()`，缺失时显式抛错（:119-121）而不是静默归到 0。
- `storeIds` 按升序排序（:202）保证"首单"在多次重试间稳定，这是尾差归集能幂等的前提。
- `DiscountAllocator.allocate`（`support/DiscountAllocator.java:32-56`）算法正确：按比例分摊 → `setScale(2, HALF_UP)` → 尾差 `totalDiscount - allocated` 归入 `result[0]`，**恒等式 `Σ结果 == totalDiscount` 在数学上成立**。
- 行级二次分摊（`OrderCreateService.java:233-243`）保证 `Σ行优惠 == 子单优惠`，形成两级守恒。
- **金额硬校验**（:253-257）：`sumChildrenPay.compareTo(parent.getPayAmount()) != 0` → 抛 `SYSTEM_ERROR` 并 ERROR 日志。这是防资损的最后一道闸，设计正确。
- `order_no` 24 位（`support/OrderNoGenerator.java:47`）：`String.format("%s%04d%06d", time, slot, seq)` = 14+4+6 = 24，时间用 `Clock.system(ZoneId.of("Asia/Shanghai"))` 固定时区（DEV.md 4.3 口径），槽位 `floorMod(userId, 10000)` 负数安全。`@UniqueConstraint(name="uk_order_no")`（`OrderMain.java:26`）兜底。
- `OrderCreateSplitTest.拆单_三店铺优惠比例分摊_尾差归集首单且总额平衡` 精确验证了 200/401、100/401、101/401 三个分摊值与 Σ 平衡。

---

#### [P0] `core-order/.../service/OrderCreateService.java:208` + `support/DiscountAllocator.java:51-53` — 尾差归集到"首单"而非 plans 要求的"金额最大的子单"，分摊基数极不均衡时会产生错误的子单实付

plans `admin-backend.md:2319` 明确写："子单优惠 = 父单优惠 ×（子单商品金额 / 父单商品金额），**尾差归入金额最大的子单**"。代码实现（`DiscountAllocator.java:12-14` 注释）承认了这个差异："plans 原文为「金额最大的子单」，本 P0 按任务口径取首单"。

**理由**：这不是单纯的"实现与文档不一致"。尾差归集到哪一单，会直接改变**各子单的实付金额**，而子单实付是**商家结算的依据**。举例：3 个店铺，商品额分别为 0.01 / 0.01 / 100.00（合计 100.02），优惠券 50.00。按比例分摊：0.005 / 0.005 / 49.99 → HALF_UP 两位 → 0.01 / 0.01 / 49.99，Σ=50.01，尾差 -0.01 归到首单（0.01 的那单）→ 首单优惠变 0.00，两个 0.01 的店铺**完全没享受到优惠**，反而是最大单承担了 0.01 的误差。

plans 选"金额最大的子单"正是因为它能保证**小数尾差落在金额基数最大的分母上，对相对误差的影响最小**。归到"首单"（此处是 `storeId` 最小的那单，可能金额极小）会让分摊误差相对放大 N 倍，在店铺数多、金额分布极不均时可能产生显著的结算偏差——这是**真实的商家资金分配错误**，不只是文档偏差。

**建议修复**：`DiscountAllocator.allocate` 接受一个"尾差归属索引"参数，`OrderCreateService.persist` 传入 `storeGoods` 最大值的索引（并列时取最小索引保证确定性）。同时更新类注释，移除"按任务口径取首单"的自我豁免表述，改为与 plans 对齐。行级分摊（`lineShares`）同样处理（plans 未规定行级归属，取首行影响小，可保留但需注释说明）。

---

#### [P1] `core-order/.../service/OrderCreateService.java:185` + `:226` — 运费 `freightAmount` 在父单和所有子单上恒为 0，`payAmount` 计算式遗漏运费

```java
parent.setFreightAmount(BigDecimal.ZERO); // TODO(P1): 运费模板按店铺计算，未发货前为 0
parent.setPayAmount(goodsTotal.subtract(discount));
...
child.setFreightAmount(BigDecimal.ZERO);
child.setPayAmount(subGoods.subtract(subDiscount));
```

**理由**：plans 4.4.2 规则 2 要求"运费按店铺运费模板分别计算"，`OrderMain.payAmount` 的定义注释（`OrderMain.java:94`）是"应付金额 = 商品总额 - 优惠 + **运费**"。当前实现写死 0 并把运费从公式里省略了。

风险在于：**这是一条"看起来能跑"的省略**。一旦 P1 接入运费模板，只改 `setFreightAmount` 而忘了改 `setPayAmount`（或者反过来），就会立刻产生资损，而且 `persist:253` 的 Σ 校验**只在子单间做平衡校验，不校验 `payAmount == total - discount + freight` 这个恒等式**，所以校验网也拦不住。

**建议修复**：现在就按定义写 `payAmount = totalAmount.subtract(discountAmount).add(freightAmount)`，运费传 0 但公式完整；并在 `persist` 的金额校验段追加一条"父单/子单自身恒等式"断言（`pay == total - discount + freight`），让 P1 接运费时自动被测试拦住。

---

#### [P1] `core-order/.../service/OrderCreateService.java:165-167` + `:260-267` — 下单即拆单，与 plans "支付成功后拆单"相反；且父单在 `UNPAID` 状态下已生成带 `store_id` 的子单

plans `admin-backend.md:2315`："**拆单时机**：支付成功后按店铺自动拆单（`order.paid` 事件触发）……未支付订单不拆单（拆了也无法独立支付）"。

代码在 `create()` 的 `persist()` 内（未支付）就完成了全量拆单。`OrderMain.java:20-21` 的注释承认了这个差异并指向 README。

**理由**：P0 形态下"拆了但只有一个支付单、支付父单金额"在功能上自洽（Σ校验保证一致），不算资损。但它带来两个具体后果：

1. **子单语义错误**：plans 规则 3 要求"父单不参与发货/售后"，但现在子单在未支付时就存在且 `order_status=0`。若运营或对账脚本按 `order_main` 全表扫"待发货子单"，会把一堆未支付子单算进待发货口径。
2. **关单级联成本**：超时关单要 CAS 更新 1 父 + N 子（`OrderStateMachine.transition:111-133`）。多店铺订单的关单事务行数是 N+1，大促时 N 可能很大（跨 20 个店铺），10s 扫描周期下批量关单会产生大量行锁。

**建议修复**：短期不改架构，但应在 `OrderMain` 上增加 `split_flag` 的语义注释并让所有统计/报表口径过滤 `split_flag=true AND parent_order_no IS NOT NULL`；P1 按 plans 改为 `order.paid` 事件触发拆单。

---

#### [P2] `core-order/.../service/OrderCreateService.java:109-110` — SKU 映射用 `(a, b) -> a` 吞掉重复，未告警

```java
.collect(Collectors.toMap(SkuView::skuId, sku -> sku, (a, b) -> a));
```

同理 `:125-127` 的 `priceMap`。若 core-product 返回了重复 skuId（其 `internalSkuViews` 按 `findByIdInAndDeletedFalse` 返回理论上无重复，但**跨服务契约没有唯一性保证**），这里静默取第一条。价格取错就是资损。**建议**：改为 `(a,b) -> { log.error("下游返回重复 skuId={}", ...); return a; }`，让契约违规可观测。

---

### A3. 支付回调幂等三步

#### ✅ 三步实现完整

`PaymentNotifyService.handleNotify`（`core-payment/.../service/PaymentNotifyService.java:69-92`）：

1. **① notify_log 唯一键去重**（:71-81）：先 `existsByNotifyId` 预检，再 `saveAndFlush` 捕获 `DataIntegrityViolationException` 处理并发。**去重日志用独立事务提交**（注释 :66-67 说明"避免与 ②③ 同事务回滚后失去去重能力"）——这个设计正确且关键。
2. **② 状态 CAS**（:115-117）：`casMarkPaid` 只允许 `PENDING → PAID`（`PaymentOrderRepository.java:27-36` 的 WHERE 带 `p.status = :fromStatus`）。`rows == 0` 时细分：已 paid → 幂等返回；其他终态 → 告警 + 抛 5001。
3. **③ 金额校验与回滚**（:131-136）：`cmd.amount().compareTo(payment.getAmount()) != 0` → ERROR 日志 + 抛 `BizException`。

**关于"金额不符是否真的回滚 ②"**：这是审计要求里明确点名的一条，我逐行确认了 —— `casAndVerify` 整体在 `transactionTemplate.execute(s -> casAndVerify(cmd))`（:84）内，方法声明 `@Transactional` 的仓储方法 `casMarkPaid`（`@Modifying` + `PaymentOrderRepository` 默认 `@Transactional`）**参与外层事务**（Spring 默认 `PROPAGATION_REQUIRED`，`@Modifying` 不改变传播行为）。因此抛 `BizException`（RuntimeException）→ 事务回滚 → ② 的 UPDATE 一并撤销。**回滚语义正确**。

测试 `PaymentIntegrationTest.notifyAmountMismatchRejected`（:150-171）验证了：金额不符后 `status` 仍为 `PENDING`，且**金额正确的后续通知（新 notifyId）仍能正常入账**——这正是"③ 回滚了 ② 但没污染去重表"的强证据。

---

#### [P0] `core-payment/.../service/PaymentService.java:83` + `core-order/.../web/InternalOrderController.java:50-51` — 预支付取 `totalAmount` 而非 `payAmount`，有优惠券的订单会多收钱

```java
// PaymentService.java:83
payment.setAmount(order.totalAmount());
```

```java
// InternalOrderController.java:50-51
return ApiResponse.ok(new InternalOrderSnapshot(order.getId(), order.getUserId(),
        order.getTotalAmount(), order.getOrderStatus(), order.getPayDeadline()));
```

**理由**：这是本次审计发现的最严重的资金错误。

`OrderMain` 明确区分三个金额字段（`OrderMain.java:80-94`）：
- `totalAmount` = 商品总额（**优惠前**），:81
- `discountAmount` = 优惠金额，:85
- `payAmount` = 应付金额 = 商品总额 - 优惠 + 运费，:94

预支付的语义是"用户实际要付的钱"，必须取 `payAmount`。当前取的是 `totalAmount`（优惠前）。

**完整数据流**：
1. 用户下单，商品 100 元，优惠券减 10 元 → `totalAmount=100.00`、`discountAmount=10.00`、**`payAmount=90.00`**（`OrderCreateService.java:182-186`）
2. 用户调 `POST /api/v1/payment/prepay`，core-payment 反查订单，拿到 `totalAmount=100.00`（`InternalOrderController:51` 传的是 `order.getTotalAmount()`）
3. `PaymentService.java:83` 落 `payment_order.amount = 100.00`
4. `PrepayResponse.amount` = 100.00 返回给前端 → **前端拉起收银台显示应付 100 元**
5. 用户付款 100 元，渠道回调 `amount=100.00`
6. `PaymentNotifyService.casAndVerify` 第③步：`cmd.amount()(100.00).compareTo(payment.getAmount()(100.00)) == 0` → **校验通过**（因为支付单本身也被写成了 100）
7. `orderClient.markPaid(orderId, paymentId, 100.00)` → core-order `markPaid` 置订单 PAID
8. 结果：**用户被多收 10 元，订单实付字段是 90，但支付流水是 100，差 10 元永久沉淀**

**为什么现有测试没发现**：`PaymentIntegrationTest.prepayIgnoresClientAmount`（:95-115）里 `stubOrder(100L, "199.00", 0)` 打桩的 `OrderSnapshot` 只有**一个金额字段** `totalAmount`，测试构造不出"totalAmount ≠ payAmount"的场景。防改价测试（客户端传 0.01 被忽略）通过，但**服务端取错字段这个 bug 被测试盲区完全掩盖**。

**后果量化**：只要平台发券（这是营销常态），每一笔用券订单都多收一个券面额。用户投诉 + 需批量退款 + 财务对账不平。

**建议修复**：
1. `InternalOrderController:51` 改为 `order.getPayAmount()`；
2. `OrderSnapshot` 记录拆为 `totalAmount` / `discountAmount` / **`payAmount`** 三个字段，`PaymentService:83` 用 `payAmount`；
3. **同时增加第二道防线**：`PaymentNotifyService.notifyOrderAndPublish` 调 `orderClient.markPaid` 前，先 `orderClient.getOrder(payment.getOrderId())` 反查订单 `payAmount`，与 `payment.amount` 不一致则拒绝入账并 ERROR 告警（防止任何上游字段漂移再次静默通过）；
4. 新增测试 `prepayUsesPayAmountNotTotalAmount`：打桩 `OrderSnapshot(total=100, pay=90)`，断言 `payment_order.amount == 90`。

---

#### [P0] `core-order/.../client/RestPaymentClient.java:30-38` — 退款是空实现，只打 WARN 日志，两条竞态补偿路径全部失效

```java
@Override
public void refund(Long orderId, BigDecimal amount, String reason) {
    // TODO(core-payment)：契约就绪后放开真实调用：
    log.warn("[payment-refund-todo] 需原路退款（core-payment 契约未定稿，P0 仅登记）: orderId={} amount={} reason={}",
            orderId, amount, reason);
}
```

**理由**：这个 `refund` 被两处调用，且都是**真金白银的场景**：

1. **`OrderLifecycleService.markPaid` 的竞态败方补偿**（`OrderLifecycleService.java:99`）：用户已付款，但订单被超时关单/取消。plans `android-backend.md 4.2.4` 要求"超时关单赢 → 迟到的成功回调触发原路退款"。当前只打日志 → **钱收了货不发也不退**。
2. **`OrderLifecycleService.closeByAdmin` 的 PAID 分支**（:230）：管理员关闭已支付订单 → plans 4.3 规则 2 要求 `closed` 必须有资金处置记录。当前只打日志 → **订单关闭了但钱没退**。

关键矛盾：`OrderLifecycleService.triggerRefund`（:285-287）在 `paymentClient.refund` 之后**无条件发 `ORDER_REFUND_APPLIED` 事件**。事件语义是"退款已应用"，但实际什么都没发生。任何下游消费方（财务对账、通知推送）收到这个事件都会认为钱已退。

**测试为什么没发现**：`OrderRaceTest` 用的是 `@MockBean PaymentClient`（`verify(paymentClient, times(1)).refund(...)`），验证的是"调用发生了"，而**真实实现是 no-op**。`PaymentReconcileJob.compensate`（`core-payment/.../job/PaymentReconcileJob.java:77-81`）同样只 `log.error` + `return 0`，注释写"P1 自动退款，P0 告警人工介入"——**人工介入在代码里没有任何工单/告警通道**。

**建议修复**：
1. `RestPaymentClient.refund` 改为真实调用 `POST /api/v1/payments/refund`（该接口**已存在**：`PaymentService.refund:173` + `PaymentController`，且 `refundIsIdempotent` 测试全绿）。契约已经通了，只是 client 没接；
2. 修复后必须把 `triggerRefund` 的事件发布移到"退款成功后"，失败时不发 `ORDER_REFUND_APPLIED`；
3. `PaymentReconcileJob.compensate` 的 `cancelledOrClosed` 分支改为调用真实退款 API；
4. 新增集成测试：mock `RestPaymentClient` 指向真实 core-payment，验证"关单后支付成功 → 支付单变 REFUNDED"。

---

#### [P1] `core-payment/.../service/PaymentNotifyService.java:84-87` — ②③ 失败时（返回 `repeated`）不区分"真重复"与"金额不符被拒"，且金额不符的通知号已被永久去重

```java
NotifyOutcome outcome = transactionTemplate.execute(s -> casAndVerify(cmd));
if (outcome == null || !outcome.paid()) {
    return NotifyOutcome.repeated();
}
```

**理由**：① 的去重日志在**独立事务**中先提交了（:77）。若 ③ 金额校验抛异常回滚 ②③，**① 的去重记录依然在库里**。渠道若用**同一个 notifyId** 重投正确金额的通知（渠道在金额异常时重试是常见行为），会被 ① 直接判为 `repeated` → 返回成功给渠道 → **渠道以为处理完成，但订单永远收不到 paid 通知**，且 `payment.success` 事件永不发。

对比 `notifyAmountMismatchRejected` 测试（:164-170）用的是**新 notifyId** `N-OK` 才成功——恰好绕过了这个场景。

**建议修复**：把 ① 的去重记录改为"待确认"状态，`casAndVerify` 成功后（事务提交）再标记"已生效"；或者对 ③ 失败的通知**删除去重记录**允许重投。最简方案：`handleNotify` 捕获 `BizException` 后 `notifyLogRepo.deleteByNotifyId(cmd.notifyId())`。

---

#### [P1] `core-payment/.../job/PaymentReconcileJob.java:39` / `core-order/.../scheduler/OrderTimeoutCloseScheduler.java:40` / `core-inventory/.../service/InventoryExpireScheduler.java:28` — 三个 `@Scheduled` 均为进程内单实例调度，多副本部署会重复扫描

`PaymentReconcileJob` 的类注释（:22-23）诚实标注了这一点（"P0 局限：进程内 @Scheduled 单实例调度，多副本部署会重复扫描"），另两个**没有标注**。

**理由**：`OrderTimeoutCloseScheduler` 重复扫描**不会**造成资损（CAS 保证只成功一次），但会导致 N-1 个副本每 10s 做一次全表扫描 `findByOrderStatusAndPayDeadlineBeforeAndParentOrderNoIsNull`——在千万级订单表上这是**显著的无效 DB 负载**（该查询走 `idx_status_deadline` 索引，但每秒 100 次 × 副本数）。`InventoryExpireScheduler` 每 30s 同理。

**建议修复**：三个任务统一加分布式锁（Redis `SET NX EX` 或 xxl-job 分片），或至少在类注释中**如实标注**多副本局限（与 `PaymentReconcileJob` 保持一致的标准）。

---

### A4. 防改价

#### ✅ 结论：达标

`PaymentService.prepay`（`core-payment/.../service/PaymentService.java:63-95`）签名只收 `userId, orderId, channel`，**方法签名层面就不接受客户端金额**。`PrepayRequest` DTO 虽有 `amount`/`totalAmount`/`userId` 字段（测试 :100 证明客户端传了会被接受并忽略），但服务端：
- 金额来自 `orderClient.getOrder(orderId)`（:68）
- 用户归属来自订单反查，`order.userId() != userId` 抛 `NO_PERMISSION`（:69-71）

`PaymentIntegrationTest.prepayIgnoresClientAmount` 明确验证：客户端传 `amount=0.01, totalAmount=0.01, userId=12345`，返回 `data.amount=199.00`，且 `saved.getUserId() == 99`（服务端反查的用户）。**防改价逻辑与测试双双到位。**

（注：字段取错的问题见 A3 的 P0，与防改价是两回事——防改价防的是"客户端改"，这里坏的是"服务端自己取错"。）

---

#### [P1] `core-payment/.../service/PaymentService.java:98-107` — `reuse()` 对 `REFUNDING/REFUNDED` 状态复用同一支付单，与 `refund` 的两段式状态机冲突

```java
case PaymentOrder.STATUS_PENDING -> return toPrepayResponse(payment, payDeadline);
case PaymentOrder.STATUS_PAID -> throw ... ORDER_STATUS_ERROR, "订单已支付，请勿重复支付";
default -> throw ... PAYMENT_FAILED, "订单退款中/已退款，不能再次支付";
```

**理由**：`doRefund`（:190-213）在 `paid → refunding → refunded` 之间如果**中途崩溃**（进程被杀、DB 连接断），支付单会**永久停在 `REFUNDING`**。此后用户重新下单支付时，`prepay` 因为 `uk_payment_order(order_id)` 唯一约束（`PaymentOrder.java:24`）找不到新支付单，只能 `reuse` 这张卡在 `REFUNDING` 的单 → 抛 5001 "不能再次支付"。**该订单永远无法再支付**。

`doRefund:197-201` 有"续跑"分支（`REFUNDING → refunded`），但**只有 `refund()` 被再次调用时才会走到**，没有任何定时任务扫描 `REFUNDING` 状态的单子。

**建议修复**：`PaymentReconcileJob` 增加"扫描 `status=REFUNDING` 且 `refund_at` 超过 N 分钟"的补偿分支，续跑或告警；或为支付单增加 `retry_count` 字段并限制重试次数。

---

### A5. 库存

#### ✅ 做对的部分

- **TOCTOU 闭合**（`InventoryService.java:114-137`）：`reserve` 把"读 available → 校验 → 改 → `saveAndFlush`"全部放在**同一个 `txTemplate.execute`** 内，且 `Stock` 带 `@Version`（`Stock.java:46`）。`saveAndFlush` 会在事务内立即触发 UPDATE，乐观锁冲突抛 `OptimisticLockingFailureException`。
- **重试机制**（:258-270）：整单元重试最多 5 次，每次都是**全新事务**（重新读最新 version），重试耗尽抛 `OUT_OF_STOCK`（3002），**绝不静默超卖**。
- **release/deduct 幂等**：`StockReservation` 带 `@Version`（`StockReservation.java:51-53`），`moveTo` 单向流转，重复调用时 `findByOrderIdAndStatus(ACTIVE)` 返回空 → `changed=0`。
- **预占幂等**（:115-118）：`existsByOrderIdAndStatus(ACTIVE)` 命中则跳过。
- **超时释放**：`InventoryExpireScheduler` 每 30s 扫 `status=ACTIVE AND expire_at < now`，`sweepExpiredReservations`（:204-236）逐条**重新读取 fresh 并二次校验 status**（:212-214）后才释放，且单条失败 `catch (BizException)` 不阻塞整轮（:230-233）。

---

#### [P0] `core-inventory/.../service/InventoryService.java:148-167` — `release` 逐条处理时，第 N 条失败会导致前 N-1 条的释放被**整体回滚**，形成库存"卡在 locked"的幽灵锁

```java
public OperationResult release(Long orderId) {
    int changed = withOptimisticRetry("release", orderId, () -> {
        List<StockReservation> actives = reservationRepository.findByOrderIdAndStatus(orderId, ACTIVE);
        for (StockReservation reservation : actives) {
            Stock stock = stockRepository.findBySkuId(reservation.getSkuId())
                    .orElseThrow(() -> new BizException(SYSTEM_ERROR, "库存记录丢失，需人工核对: ..."));
            stock.release(reservation.getQuantity());
            stockRepository.saveAndFlush(stock);
            ...
        }
        return actives.size();
    });
```

**理由**：循环内 `orElseThrow`（:153-155）——如果某个 SKU 的 `sku_stock` 记录被删除（管理端删档、DB 清理脚本、或数据迁移遗漏），抛出的 `BizException` 会让 `txTemplate.execute` **回滚整个事务**，前面已经成功释放的 SKU 全部回滚。结果：该订单的所有预占都停在 `ACTIVE`，`locked_stock` 永久占用。

`deduct`（:180-183）和 `sweepExpiredReservations`（:215-217）有**完全相同的问题**。

调用方 `OrderLifecycleService.releaseQuietly`（:291-297）捕获异常后只打 ERROR 日志，注释说"留给对账补偿"——但**代码里不存在任何库存对账补偿任务**。`InventoryExpireScheduler` 虽然会扫，但已 `expire_at` 的预占在下一轮仍会因为同样的 `sku_stock` 缺失而失败，**永久死循环**。

这正是 plans `admin-backend.md 4.4.4` 点名的"v1.0 硬伤：库存被幽灵锁死"。当前实现虽然有释放路径，但**存在一条让释放路径永久失效的路径**。

**建议修复**：
1. 循环内单条异常不中断整批——改为收集 `failures` 列表，成功的正常提交，失败的记 ERROR 并**把该 reservation 单独标记为 `MANUAL_REVIEW` 状态**（新增枚举值），使其不再被重复扫描；
2. 或：把 `orElseThrow` 改为"记录缺失并跳过"，同时发出 P0 告警（缺失 sku_stock 本身就是数据损坏信号）；
3. 新增测试：`release` 一个含 3 个 SKU（其中一个 sku_stock 被删）的订单，断言另外 2 个成功释放。

---

#### [P1] `core-inventory/.../service/InventoryService.java:262` — `withOptimisticRetry` 只捕获 `OptimisticLockingFailureException`，但 `@Version` 冲突在 H2/JPA 下也可能表现为 `DataIntegrityViolationException` 或 `ObjectOptimisticLockingFailureException` 的父类不匹配

`ObjectOptimisticLockingFailureException extends OptimisticLockingFailureException`，所以子类是覆盖的。**但 `saveAndFlush` 抛出的实际类型依赖 Hibernate 版本**。当前依赖的 Spring Boot 版本下 `@Version` 冲突确实抛 `ObjectOptimisticLockingFailureException`，被 `catch (OptimisticLockingFailureException e)` 捕获。**这条实际是 OK 的**，但代码没有对 `ObjectOptimisticLockingFailureException` 显式处理，也没有测试断言重试路径真的被走到。

**建议**：新增一个测试直接 mock 两个线程同时 `reserve` 同一 SKU（库存刚好只剩 1），断言一个成功一个返回 3002。当前 `InventoryServiceTest` 未覆盖这个并发场景。

---

#### [P1] `core-inventory/.../domain/Stock.java:29-30` — `sku_stock` 主键用 `GenerationType.IDENTITY` 自增，违反裁决 C13

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
```

类注释（:21）诚实标注了"P0 非分片小表，主键自增仅为 H2 建表便利，P1 迁移分片时改雪花（裁决 C13）"。`StockReservation` 同样（`StockReservation.java:30-31`）。

**理由**：AGENTS.md 和 plans 裁决 C13 明确"分库分表禁止 AUTO_INCREMENT，主键/单号禁止自增"。这是**已知并被文档标注的 P0 例外**，不算隐藏缺陷，但应登记为上线前的强制项：一旦 P1 接入 MySQL 分片，这两张表必须先改雪花，否则分片路由无法实施。

**建议修复**：登记到上线检查清单；或 P0 阶段就改用雪花（`Stock`/`StockReservation` 都注入 `SnowflakeIdGenerator`，成本很低）。

---

#### [P2] `core-inventory/.../domain/Stock.java:66-83` — `reserve`/`release`/`deduct` 无下界断言，可被调成负数库存

```java
public void reserve(int quantity) {
    this.availableStock -= quantity;   // 无 if (availableStock >= quantity) 检查
    this.lockedStock += quantity;
}
```

调用方 `InventoryService.reserve:123-128` 有前置校验，但 `release`（:156）和 `deduct`（:184）**没有校验 `lockedStock >= quantity`**。当前靠预占单数据一致性保证，但领域对象自身无防御。**建议**：在 `Stock` 的三个方法里加 `IllegalStateException` 断言（`available < quantity` / `locked < quantity` 时抛），把不变量封装进领域对象。

---

### A6. 优惠券

#### ✅ 做对的部分

- **防超领**（`CouponService.receive:62-70`）：三重防护——① `countByCouponIdAndUserId` 每人限领预检（:62-66）；② `templateRepo.reserveStock` 条件更新（`CouponTemplateRepository.java:19-26`，WHERE 带 `receivedCount < totalCount AND :now BETWEEN startTime AND endTime AND status=1`）原子预占，**并发只成功 N 次**；③ 领取记录落库同事务（`@Transactional`），任一步失败整体回滚不会泄漏预占数。
- **核销并发**（`CouponService.deduct:123` + `CouponRecordRepository.casUse:28-38`）：条件更新 `WHERE id=? AND status=AVAILABLE AND expireAt >= :now`，**过期校验也在 SQL WHERE 里**（不是 Java 侧先查再改），消除了 TOCTOU。
- **同订单重试幂等**（:127-128）：`updated == 0` 后重读，若 `status==USED && orderId.equals(current.getUsedOrderId())` 返回成功。
- **关单退回**（`release:142-162`）：`casRelease` 条件更新 + `orderId` 一致性校验（:150-154，防止退错单的券）+ `decreaseUsedCount` 带 `usedCount > 0` 保护防负数。
- **过期翻转**（`expireDue:174-176` + `CouponExpireJob`）：条件更新 `WHERE status=AVAILABLE AND expireAt < :now`。
- **试算精度**（`discountOf:273-286`）：折扣券 `total × (10 - value) / 10`，`divide(TEN, 2, HALF_UP)`；满减直接减面额；最后 `if (discount > total) discount = total` 封顶。

---

#### [P0] `core-promotion/.../service/CouponService.java:135` — 核销成功但订单最终失败/取消时，优惠券**永久停留在 `used` 状态**，用户既拿不到券、订单也没有

**完整数据流**：
1. `OrderCreateService.create:153` 调 `promotionClient.deductCoupon(couponId, userId, parentId, goodsTotal)` → preview 算出 discount → 调 `POST /api/v1/coupons/deduct` → `CouponService.deduct` 把券置 `USED` 并绑定 `used_order_id = parentId`
2. 随后 `txTemplate.execute(status -> persist(...))`（:165）执行落库
3. **若 persist 失败**（如 :253 的 Σ 金额校验失败、或 DB 约束冲突、或进程崩溃）：`catch (RuntimeException e) { compensateRelease(parentId, e); throw e; }`（:168-171）**只释放了库存**（`inventoryClient.release`），**没有退券**

`RestPromotionClient` 的类注释（`core-order/.../client/RestPromotionClient.java:25-26`）承认了这个 TODO：
> **TODO(P1)**：核销成功后若订单持久化失败，券停留在已核销状态——P1 需补 `/api/v1/coupons/release` 补偿

**更严重的是第二段**：即使订单成功创建，后续**超时关单 / 用户取消 / 后台关闭**时，`OrderLifecycleService` 的 `closeTimedOut`（:245-273）、`cancelByUser`（:145-159）、`closeByAdmin`（:213-236）**都调用了 `releaseQuietly(parent, ...)` 释放库存，但没有任何一处退券**。而 `core-promotion` 的 `CouponUserController.release`（:70-73）**接口是现成的**。

**后果**：
- 场景 A：下单中途失败 → 用户券被吞，订单不存在，用户申诉无门
- 场景 B：下单成功但超时未付款（30 分钟后自动关单）→ 库存正确释放了，但**优惠券永远回不来**。用户只是没付款，却损失了一张券。这是**用户可感知的权益损失**，在电商场景下会直接引发投诉。

**建议修复**（P0 必须，因为这是权益损失不是体验问题）：
1. `OrderCreateService.create` 的 catch 块中增加 `promotionClient.releaseCoupon(couponId, parentId)` 补偿（接口已存在，需在 `PromotionClient` 接口加方法 + `RestPromotionClient` 实现）；
2. `OrderLifecycleService` 的三条关单路径（`closeTimedOut` / `cancelByUser` / `closeByAdmin`）在 `releaseQuietly` 旁增加 `releaseCouponQuietly(parent, ...)`——需要 `OrderMain` 记录 `coupon_id`（**当前 `OrderMain` 没有这个字段**，见 A6-P1）；
3. 新增测试：下单用券 → 超时关单 → 断言 `coupon_record.status == AVAILABLE`。

---

#### [P1] `core-order/.../domain/OrderMain.java` — `OrderMain` 未持久化 `coupon_id`，导致关单时**无法知道该退哪张券**

`OrderMain` 的字段清单（:34-126）包含 `discountAmount` 但**没有 `couponId`**。`OrderItem` 也只存 `discountAmount`（`OrderCreateService.java:243`）。

**理由**：这是 A6-P0 的**结构性根因**。即使实现了关单退券，关单时也没有数据可以定位券记录。`CouponRecord.usedOrderId` 反查也不可行——那是券侧的索引，且需要遍历。

**建议修复**：`OrderMain` 增加 `coupon_id`（`Long`，父单填写、子单继承或留空），`OrderCreateService.persist` 写入，在 `OrderQueryService` 的详情 VO 中透出（管理端需要看"这单用了什么券"）。DDL 同步（`admin-backend 1.2.2`）。

---

#### [P1] `core-promotion/.../service/CouponService.java:62-66` — 每人限领预检是"先查后写"，并发下可突破限领

```java
long owned = recordRepo.countByCouponIdAndUserId(templateId, userId);
if (owned >= template.getPerLimit()) {
    throw ...;
}
int reserved = templateRepo.reserveStock(templateId, now);
```

**理由**：`reserveStock` 的条件更新保证了**总量**不超发（这部分是对的），但 `countByCouponIdAndUserId` 是**普通 SELECT**，与随后的 `recordRepo.save(record)`（:77）之间没有原子性保证。同一用户并发 10 个领券请求：10 个请求都读到 `owned=0 < perLimit=1`，10 个都通过预检，只要模板总量够（`totalCount >= 10`），**10 个都成功**——用户拿到了 10 张券，限领 1 的规则被完全突破。

这是一个**可被脚本稳定复现的超发漏洞**（并发领券是薅羊毛的经典手法）。

**建议修复**：新增 `coupon_record` 上的条件更新，或在 `CouponTemplate` 上增加按 userId 维度的计数列；最简方案是创建 `uk_user_coupon_template(coupon_id, user_id)` 之类的辅助计数表 + `INSERT ... SELECT COUNT(*) WHERE count < perLimit` 的单语句原子领取。新增并发测试：同一 userId 并发调 `receive` 10 次，断言 `recordRepo.countByCouponIdAndUserId == perLimit`。

---

#### [P2] `core-promotion/.../service/CouponService.java:76` — 券有效期取模板 `endTime` 快照，若模板被改期，已领券的有效期不跟随

`record.setExpireAt(template.getEndTime())`。运营延长活动时，已发出的券不会延长。这是产品决策而非缺陷，但**代码和 `CouponTemplateRequest` DTO 都没有注释说明这个口径**，容易在后续迭代中被误认为 bug 而"修错"。**建议**：加注释明确"券有效期为领取时快照，模板改期不影响存量券"。

---

### A7. pay_deadline 超时关单与支付回调竞态

#### ✅ 裁决设计正确

`OrderLifecycleService` 类注释（:30-32）完整描述了裁决语义，代码逐条落实：

| 场景 | 代码位置 | 行为 |
|------|---------|------|
| 关单赢 | `closeTimedOut:245-273` | CAS 成功 → CANCELLED → 释放库存 → 发 `order.cancelled` |
| 回调赢 | `closeTimedOut:248-252` | 重读发现非 UNPAID → `SKIPPED`，**不释放库存、不发事件** |
| 迟到回调 | `markPaid:97-102` | 发现 CANCELLED/CLOSED → `triggerRefund` → `REFUND_TRIGGERED`，**不回写 PAID** |
| CAS 冲突 | `markPaid:112-120` | 捕获 4002 → 重读裁决，最多 3 次 |
| CAS 未生效 | `markPaid:136-137` | 重读裁决 |

`OrderRaceTest` 三个测试覆盖了：确定性两种交错 + 真并发（`CountDownLatch` 同时放行），真并发测试的断言是**分支式**的（根据最终状态判断哪方赢），不强求特定赢家，设计合理。

---

#### [P0] `core-order/.../service/OrderLifecycleService.java:285-287` — `triggerRefund` 无条件发 `ORDER_REFUND_APPLIED` 事件，与 A3-P0 的空实现叠加

```java
private void triggerRefund(OrderMain order, String reason) {
    try {
        paymentClient.refund(order.getId(), order.getPayAmount(), reason);
    } catch (Exception e) {
        log.error("退款调用失败，需对账补偿: ...", e);
    }
    AfterCommitEvents.publish(eventPublisher, DomainEventNames.ORDER_REFUND_APPLIED, Map.of(...));
}
```

**理由**：与 A3-P0 联动。当前 `paymentClient.refund` 是 no-op（不抛异常），所以 catch 分支不触发，事件**必定被发出**——即"订单已取消/关闭 + 支付成功"的场景下，系统对外宣告"退款已应用"，但**一分钱没退**。

即使修好了 A3-P0（接入真实退款），这个结构仍然有缺陷：**catch 分支打日志后事件照发**。退款 API 抛异常时，下游（财务对账、用户通知）仍会收到"退款成功"事件。

**建议修复**：把 `publish` 移入 try 块内且仅在 `refund` 返回成功后执行；失败时发一个独立的 `order.refund.failed` 告警事件（若事件表无此名，退而求其次用 ERROR 日志 + 告警系统接入）。

---

#### [P1] `core-order/.../scheduler/OrderTimeoutCloseScheduler.java:52-53` — 扫描无分页、无 limit，订单表增长后单轮扫描会 OOM 或超时

```java
List<OrderMain> expired = orderMainRepository.findByOrderStatusAndPayDeadlineBeforeAndParentOrderNoIsNull(
        OrderStatus.UNPAID.getValue(), Instant.now());
```

**理由**：返回**全部**待关单记录到内存。正常情况每 10s 只有少量超时单，但**异常场景**（服务停机 1 小时后重启、DB 故障恢复、大促积压）会一次捞出数万甚至数十万条 `OrderMain` 实体（含所有字段），直接打满堆内存。`closed++` 的循环也会因为每单要发多次 DB 调用（transition 的父子 CAS + 流水 + fillCloseReason + release HTTP）而远超 10s 周期，导致 `fixedDelay` 堆积。

**建议修复**：`findBy...` 改为分页（`Pageable.ofSize(500)`）循环拉取，或加 `List<OrderMain>` 改 `List<Long>` 只取 ID（降低内存占用），并对单轮处理数设上限。

---

#### [P1] `core-order/.../service/OrderLifecycleService.java:123-126` — `markPaid` 中 `fillPayInfo` 在 `transition` 事务**之外**执行，两步非原子

```java
changed = stateMachine.transition(parent.getId(), OrderStatus.PAID, ...);   // @Transactional，独立事务
...
if (changed) {
    txTemplate.executeWithoutResult(s -> {                                     // 又一个独立事务
        orderMainRepository.fillPayInfo(parent.getId(), payType, now, now);
        orderMainRepository.fillPayInfoByParent(parent.getOrderNo(), payType, now, now);
    });
```

**理由**：`stateMachine.transition` 是 `@Transactional`（`OrderStateMachine.java:89`），`markPaid` 自身**没有** `@Transactional`，所以两者是**两个独立事务**。若状态流转已提交但 `fillPayInfo` 失败（DB 抖动），订单处于 `PAID` 但 `pay_type`/`pay_time` 为 null。后果：用户看到"已支付"但订单详情不显示支付方式和支付时间；对账报表缺列；`AdminOrderController` 的导出模板会缺这两列。

**建议修复**：把 `markPaid` 加 `@Transactional`，让状态流转与 payInfo 回填在同一事务内；或把 `fillPayInfo` 移入 `OrderStateMachine` 的事务边界（作为流转后置动作）。注意 A1-P0 提到的 `changed` 短路逻辑需相应调整。

---

### A8. 幂等拦截器

#### ✅ 做得好的

- **缺失行为明确**（`common/.../idempotent/IdempotentInterceptor.java:43-46`）：缺少 `Idempotency-Key` 头 → 抛 `PARAM_ERROR`（1001），**不放行**。符合 plans 10.1.1 "写接口必须携带幂等键"。
- **重复行为明确**（:55-64）：TTL 内重复 → `IDEMPOTENT_CONFLICT`（1005 → HTTP 409）。过期占位的 CAS 替换（`seen.replace`）处理了并发抢注。
- **key 作用域正确**（:49）：`method + ":" + URI + ":" + key`，避免跨接口撞键。
- **多副本局限如实标注**（类注释 :18-21）："内存 Map 仅单实例有效，服务多副本部署时幂等会失效；P1 必须替换为 Redis SET key NX EX ttl"。**这是本次审计中标注质量最好的一处**。

---

#### [P0] `common/.../idempotent/IdempotentInterceptor.java:51-53` — **在业务执行前**就写入 `seen`，业务失败后幂等键**永久占用**，用户无法用同一 key 重试

```java
Long prev = seen.putIfAbsent(scopedKey, expireAt);
if (prev == null) {
    return true; // 首次请求，占位成功
}
```

**理由**：`preHandle` 在 Controller 方法**执行之前**运行。占位一旦成功，无论后续业务是成功还是抛异常，这个 key 都会在 `seen` 里停留 `ttlSeconds`（`@Idempotent` 默认值需确认，但通常是秒级到分钟级）。

**失败场景**（以 `core-cart` 为例，`CartController` 有 6 个写接口挂 `@Idempotent`）：
1. 用户 A 点"加入购物车"，客户端生成 key=`abc-123`
2. `preHandle` 占位成功
3. Controller 执行 → `CartService.add` → `requireOnShelf` 抛 `PRODUCT_OFF_SHELF`（商品刚下架）
4. 客户端收到错误，用户刷新页面重试 —— **用的是同一个 key=`abc-123`**（正常的客户端实现就是重试同一个操作，key 不变）
5. `preHandle` 发现 `prev > now` → 抛 `IDEMPOTENT_CONFLICT`（1005 / HTTP 409）
6. **用户永远无法把商品加入购物车**，直到 TTL 过期。商品可能长期下架，用户就只能反复看到 409

**order 模块同样受影响**：`OrderController.create:53-58`。下单遇到"库存不足 3002"（这是**极高频**的业务失败——秒杀、爆款场景）后，同一 key 重试必然 409，用户必须等 TTL。

**根本问题**：`@Idempotent` 的语义应该是"**成功后**记幂等，失败不记"。当前实现是"**尝试即记**"。

**建议修复**：
1. 在 `preHandle` 中改为"预检（peek）"，Controller 成功后由 `afterCompletion`（`ex == null`）才写入 `seen`；
2. 或至少在 `afterCompletion` 中，**若 `ex != null` 则 `seen.remove(scopedKey)`** 释放占位（并发场景下需用 `seen.remove(key, expireAt)` 带值删除，避免删掉别人新占的位）；
3. 新增测试：同一 key 连续两次调用，第一次业务失败，断言第二次**能正常进入业务逻辑**（而非 409）。

---

#### [P1] `common/.../idempotent/IdempotentInterceptor.java:67-71` — `cleanupIfTooLarge` 清理策略导致内存可能无界增长

```java
private void cleanupIfTooLarge(long now) {
    if (seen.size() > 100_000) {
        seen.entrySet().removeIf(e -> e.getValue() <= now);
    }
}
```

**理由**：只在 `size > 100_000` 时触发清理，且**只删除已过期的**。如果攻击者（或误配置的客户端）持续用**随机 key** 打写接口，TTL 内产生的条目全部"未过期"，`removeIf` 不会删除任何东西，`size` 持续增长直到超过 10w 阈值后**每次请求都做一次全表 `removeIf` 扫描**（O(n) × QPS）。

**建议修复**：改用 `ScheduledExecutorService` 定时清理（每 60s 一次），或改用 Caffeine/带 TTL 的缓存（`expireAfterWrite` 自动淘汰），并设置 `maximumSize`。P1 换 Redis 时一并解决。

---

#### [P1] `core-order/.../web/OrderController.java:53` — `@Idempotent` + `RestPromotionClient` 的降级语义冲突：券服务不可用时下单成功，但 key 已消耗

`OrderCreateService.create` 对券服务做了**静默降级**（`RestPromotionClient.java:58-63`：核销失败 → `discount = 0`，下单继续按原价成交）。这在业务上是对的（不让券服务故障阻塞交易），但配合 A8-P0 的"尝试即占位"，用户会遇到：券服务抖动 → 下单成功但没优惠 → 用户认为是 bug，用同一 key 重试想"再来一次" → 409。**建议**：在 `OrderCreateService` 响应中显式返回 `couponApplied=false` + `note`（说明降级原因），让前端能给出明确提示而非让用户盲目重试。

---

### A9. 雪花 ID

#### ✅ 偶数序列实现正确

`SnowflakeIdGenerator.nextId`（`common/.../id/SnowflakeIdGenerator.java:39-60`）：

```java
long step = sequence.incrementAndGet() & MAX_SEQUENCE_STEP;  // MAX_SEQUENCE_STEP = 2^11-1 = 2047
if (step == 0) { now = waitNextMillis(last); }
sequence.set(step);
...
return ((now - EPOCH) << 17) | (workerId << 12) | (sequence.get() << 1);  // ← 序列左移 1bit
```

**验证**：
- 布局：1(符号,隐含) + 41(时间戳) + 5(workerId) + 12(序列)，共 59 bit ✓ 与注释一致
- **序列左移 1 bit 写入**（:59 `sequence.get() << 1`）→ bit0 恒为 0 → **ID 恒为偶数** ✓
- 步进上限 `2^11-1 = 2047`（:26）而非 `2^12-1 = 4095` → 步进左移后最大 `2047 << 1 = 4094 < 4096`，**不溢出 12 bit 序列段，也不侵入 workerId 位段** ✓ 这正是注释 :25 所说的设计意图
- **并发安全**：`synchronized` 方法（:39）+ `AtomicLong` 双重保障；`synchronized` 是必需的，因为"读 lastTimestamp → 判定 → 写 sequence → 写 lastTimestamp"是复合操作，单靠 AtomicLong 不够 ✓
- **时钟回拨处理**（:42-45）：`now < last` 时 `now = last`，即**暂停发号**（不发重复号），注释标注"P0 简化处理，生产可拒绝发号并告警"。这个选择是安全的（宁可阻塞也不发重复 ID），且如实标注 ✓
- **溢出等待**（:48-50）：`step == 0` 表示本毫秒 2048 个号用尽 → `waitNextMillis` 忙等到下一毫秒 ✓

`SnowflakeIdGeneratorTest` 存在，测试文件在 `common/src/test/`。

---

#### [P2] `common/.../id/SnowflakeIdGenerator.java:42-45` — 时钟回拨静默阻塞无告警，且 `waitNextMillis` 是忙等

```java
if (now < last) {
    now = last;  // 时钟回拨：等待追平（P0 简化处理，生产可拒绝发号并告警）
}
```

**理由**：NTP 校时导致的毫秒级回拨会让所有发号线程在该毫秒内发号 2048 次后全部进入 `waitNextMillis` 忙等自旋。回拨幅度大（比如虚拟机迁移、宿主机时钟跳变）时会**长时间阻塞所有下单请求**。注释已标注 P1 改进，但当前**没有任何日志或指标**能观测到这个状态。

**建议修复**：加 `log.warn` + Micrometer 指标（回拨次数、回拨幅度），回拨幅度超过阈值（比如 100ms）时抛异常拒绝发号（fail-fast 比静默等待好）。

---

#### [P2] 全部 12 个模块共用 `APP_JWT_SECRET` 环境变量但各自有**相同的硬编码默认值**

所有 `application.yml` 都是 `secret: ${APP_JWT_SECRET:dev-only-secret-change-me-in-prod-0123456789abcdef}`。若生产环境漏配环境变量，**所有服务会用同一个公开在代码仓库里的密钥**。`JwtService:40-42` 有长度校验（>= 32 字节）但**没有校验"是否仍是默认值"**。

**建议修复**：`JwtService` 构造时若 secret 等于默认常量则抛异常（生产 profile 下），或在 `application-prod.yml` 中显式写 `${APP_JWT_SECRET}`（不带默认值，缺失即启动失败）。

---

## B. 安全

### B1. SQL 注入

#### ✅ 结论：未发现注入点

- 所有查询走 JPA 参数绑定（`:param`）或 `Specification` + Criteria API（`cb.equal`/`cb.like`），**无字符串拼接 SQL**。
- `LIKE` 通配符转义在两个模块都做了，且**转义符一致**：
  - `core-search/.../service/SearchService.java:166-168`：`raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")`
  - `core-product/.../service/ProductService.java:597-599`：同款
  - 两者都在 `cb.like(expr, pattern, '\\')` 中把 `'\\'` 作为 escape char 传入 ✓ 转义与转义符匹配，无绕过。
- `SearchEscapingTest` 存在（`core-search/src/test/`）。

---

#### [P2] `core-search/.../service/SearchService.java:242-243` — cursor 参数解析有异常吞噬

```java
String[] parts = raw.split("\\|", 2);
return new Cursor(new BigDecimal(parts[0]), Long.parseLong(parts[1]));
```

被 `catch (Exception e)` 捕获转 `PARAM_ERROR`（:244-246）✓ 行为正确。但 `parts[0]` 若为空串（cursor 为 `"|123"`），`new BigDecimal("")` 抛 `NumberFormatException` → 被 catch → 1001 ✓。**实际安全**，只是错误信息不够精确。**建议**：区分 `NumberFormatException` 与其他异常，返回更精确的提示。

---

### B2. JWT

#### ✅ 做对的部分

- **算法固定**（`common/.../auth/JwtService.java:96`）：header 硬编码 `put("alg", "HS256")`，**`parse()` 时不读取也不信任 header 的 alg**（:62-92 直接用 `HmacSHA256` 验签）→ **天然免疫 alg=none 和 alg 混淆攻击** ✓ 这是自实现 JWT 最容易踩的坑，此处避开了。
- **常量时间比较**（:70）：`MessageDigest.isEqual(expected, actual)` ✓ 防时序侧信道。
- **exp 校验**（:78-81）：`exp <= now` 抛过期。注意用的是 `<=` 而非 `<`，边界略严，无害。
- **typ 校验**（:74-77）：`access`/`refresh` 白名单，其余拒绝 ✓。
- **jti 存在**（:102）：UUID，refresh 轮换依赖它。
- **密钥长度校验**（:40-42）：< 32 字节启动即失败 ✓。

---

#### [P1] `common/.../auth/JwtService.java:64` — `parse()` 不校验 `alg` 头，虽然当前实现免疫混淆，但缺少显式的算法白名单断言

```java
String[] parts = token.split("\\.");
if (parts.length != 3) { throw ... }
byte[] expected = sign(parts[0] + "." + parts[1]);
```

**理由**：当前实现**实际上不读 header 内容**（只把它当不透明字节参与签名计算），所以 `alg=none` 的攻击会因签名不匹配而失败——**这是安全的**。但这是"因为没读所以安全"而非"因为校验了所以安全"，属于**依赖实现细节的安全性**。若后续有人为了"兼容不同 alg"而加上读取 header 的逻辑，就会立刻引入漏洞。

**建议**：加一行显式断言——解析 header 的 `alg`，若 `!= "HS256"` 直接抛 `InvalidTokenException`。这是纵深防御，成本 3 行。

---

#### [P1] `core-user/.../service/AuthService.java:77-79` — 用户不存在时返回 2001（用户不存在），密码错误返回 2002 —— **用户名枚举漏洞**

```java
UserAccount user = userRepo.findByUsernameAndDeletedFalse(username)
        .orElseThrow(() -> new BizException(BizErrorCode.USER_NOT_FOUND));  // 2001
...
if (!PasswordHasher.matches(password, user.getPasswordHash())) {
    throw new BizException(BizErrorCode.PASSWORD_ERROR, "密码错误");       // 2002
```

**理由**：攻击者可通过响应码差异（2001 vs 2002）**枚举出系统中存在哪些用户名/手机号**。这是登录接口的经典漏洞，配合社工库撞库风险显著。

另外还有**时序侧信道**：用户不存在时直接抛异常（无 SHA-256 计算），用户存在但密码错时执行了 SHA-256 + Base64 + `MessageDigest.isEqual`。响应时间可区分。

**建议修复**：统一返回 `PASSWORD_ERROR`（"用户名或密码错误"），并在用户不存在时执行一次**哑哈希**（dummy hash）填充时间差。

---

#### [P1] `gateway/.../filter/JwtAuthGlobalFilter.java:73-76` — 解析失败统一返回 `TOKEN_EXPIRED`（1003），与"签名无效"语义混淆

```java
} catch (Exception e) {
    log.warn("jwt auth failed path={} reason={}", path, e.getMessage());
    return writeUnauthorized(exchange, BizErrorCode.TOKEN_EXPIRED);
}
```

**理由**：`e` 可能是"签名不匹配"、"格式非法"、"token 类型非法"（refresh 误用）、"已过期"四种，全部返回 1003 TOKEN_EXPIRED。客户端无法区分"该重新登录"和"这个 token 根本不该用在这"。且 `!claims.isAccessToken()` 的情况（:70-72）也返回 `TOKEN_EXPIRED` —— 用户拿 refresh token 调业务接口被告知"已过期"，语义错误。

**建议**：区分返回码：签名/格式非法 → 1002 NOT_LOGIN；类型错误 → 1002；仅真正过期 → 1003。

---

### B3. 越权（IDOR）

#### ✅ 做得好的

| 资源 | 防护 | 位置 |
|------|------|------|
| 订单列表/详情 | `findByUserIdAndParentOrderNoIsNull(userId, ...)` + `ownedParent` 校验 | `OrderQueryService.java:69-72, 83` |
| 订单取消/确认 | `ownedParent(orderId, userId)` 抛 4001 | `OrderLifecycleService.java:320-326` |
| 购物车 | `findByUserIdAndCartItemId` + 归属集合校验 | `CartService.java:238-240, 270-276` |
| 地址 | `findByIdAndUserIdAndDeletedFalse` | `AddressService.java:96-99` |
| 券 preview | `requireOwnedRecord` 校验归属 | `CouponService.java:259-265` |
| 券 deduct/release | 显式 userId 比对 | `CouponService.java:119-121, 145-147` |
| 支付单 | prepay/status/mockPay 均校验 userId | `PaymentService.java:69-71, 161-163, 144-146` |
| 网关头伪造 | **先 remove 再 set** | `JwtAuthGlobalFilter.java:82-85` |

`CartService.remove`（:270-276）用"全量查本人 cartItemId 集合再比对"，虽然 O(n)，但**不泄露他人行的存在性**（统一报 3004），注释明确说明 ✓。

`JwtAuthGlobalFilter:82-83` 先 `headers.remove(USER_ID)` 再 `set`，**彻底防客户端伪造 X-User-Id** ✓ 这是关键防线。

---

#### [P0] `core-promotion/.../web/CouponUserController.java:64-67` + `core-promotion/.../service/CouponService.java:119` — `deduct`/`release` 在**无 X-User-Id 时完全跳过归属校验**，任意人可核销/退回任意用户的券

```java
// CouponUserController.java:66
return ApiResponse.ok(couponService.deduct(request.couponId(), request.orderId(), UserContext.userId()));
```
```java
// CouponService.java:119-121
if (userId != null && !record.getUserId().equals(userId)) {
    throw new BizException(BizExceptionCode.NO_PERMISSION, "无权操作他人优惠券");
}
```

**理由**：`UserContext.userId()` 在请求无 `X-User-Id` 头时返回 **null**，而 `deduct` 的校验是 `if (userId != null && ...)` —— **null 时直接跳过校验**。

Controller 注释（:30-31）说明了这个设计意图：
> deduct/release 允许服务端间调用（core-order 下单/关单链路）——携带 X-User-Id 时校验归属，**不携带时按内网信任放行（P0，对齐 DEV.md 4.5）**

**问题在于网关路由**：gateway 的 `application.yml` 里有一条
```yaml
- id: core-promotion
  predicates:
    - Path=/api/v1/coupon/**,/api/v1/coupons/**,...
```
而 `JwtAuthGlobalFilter` 的白名单**不包含** `/api/v1/coupons/**`，所以走网关需要 token → 有 token 就有 `X-User-Id` → 校验生效。

**但攻击面在于**：`/api/v1/coupons/deduct` 和 `/api/v1/coupons/release` 是**注册在买家端路径下的写接口**。只要攻击者能构造一个**不带 X-User-Id** 的请求到达 core-promotion（端口 8088 在 P0 静态配置下 `uri: http://localhost:8088`，**所有服务都监听 localhost**，但 WSL/容器网络下 8088 可能对外可达），就能：

1. `POST /api/v1/coupons/deduct {"couponId": <他人的券记录ID>, "orderId": <任意>}` → **把他人的券核销掉**
2. `POST /api/v1/coupons/release {"couponId": <他人的券记录ID>, "orderId": <任意>}` → 把已核销的券退回（且 `release` 只在 `orderId != usedOrderId` 时报错，攻击者传 null 时 `:150-154` 的条件不成立 → **任意退回**）

**券记录 ID 是雪花 ID**（`CouponRecord.java:30-32`，偶数序列），不是严格不可猜测——雪花的时间戳段是公开可推算的，攻击者可以枚举附近的 ID。

**建议修复**：
1. 把 internal 调用与买家调用**分离路径**：`/api/v1/internal/coupons/deduct`（要求内网 + 服务间鉴权，如 mTLS 或内部 token）供 core-order 使用；`/api/v1/coupons/deduct` **强制要求 X-User-Id**（`UserContext.requireUserId()`），彻底去掉 `if (userId != null)` 的空档；
2. `release` 接口目前**没有任何 Controller 暴露给买家端**（`CouponUserController:70-73` 暴露了），建议直接下线该买家端路径，只保留 internal 路径。

---

#### [P1] `core-order/.../web/AdminOrderController.java:94-101` — 管理端角色校验"角色头为空 = 内部直连放行"

```java
private Long requireAdminRole() {
    List<String> roles = UserContext.roles();
    if (!roles.isEmpty() && !UserContext.isAdmin()) {
        throw new BizException(BizErrorCode.NO_PERMISSION, "需要管理员角色");
    }
    return UserContext.userId();
}
```

**理由**：`roles.isEmpty()` 时**放行**。虽然 gateway 的白名单不含 `/api/admin/**`（除 login/refresh/captcha），走网关一定有角色头，但 `admin-bff` 通过 `RemoteSupport.forwardHeaders`（:55-58）**只在 `!roles.isEmpty()` 时才转发 `X-User-Roles`**。如果某个调用路径导致 roles 为空（例如从定时任务或内部服务调 admin 接口），就会**静默获得管理员权限**。

对比 `admin-bff` 的 `AdminGuardInterceptor.java:34`（`if (!UserContext.roles().contains(ROLE_ADMIN)) throw`）——**默认拒绝**，方向相反。两处口径不一致，`core-order` 这处是**默认放行**，风险更高。

**建议修复**：改为默认拒绝（与 admin-bff 对齐）——`if (!UserContext.isAdmin()) throw NO_PERMISSION`。若确有内部直连需要，用独立路径前缀区分。

---

#### [P1] `core-user/.../service/AuthService.java:103-104` — 注册验证码硬编码 `123456`，任何人可用任意手机号注册

```java
static final String P0_FIXED_SMS_CODE = "123456";
...
if (!P0_FIXED_SMS_CODE.equals(request.code())) {
    throw new BizException(BizErrorCode.CAPTCHA_ERROR);
}
```

**理由**：注释明确标注了 "P0 短信通道未接入：注册验证码固定 123456。TODO(P1) 接短信服务商"。这是**已知的 P0 简化**，代码诚实标注。

但风险是实在的：攻击者可以用**任意手机号**注册账号。若手机号是后续账号找回/实名认证的依据，会造成账号劫持。且 `register` 用了 `@Transactional` 但**没有 `@Idempotent`**（`AuthController.java:42` 有 `@Idempotent`，覆盖了 register —— 确认一下）。

**建议修复**：上线前必须接入真实短信通道；或至少加"环境开关"（`app.sms.mock-enabled`，`prod` profile 强制 `false` 并在启动时校验），防止误部署。

---

#### [P1] `core-user/.../service/AuthService.java:100-121` — `register` 无频率限制，可被刷注册

`AuthService` 有 `SMS_TOO_FREQUENT`（2005）错误码定义（`BizErrorCode.java:30`）但**代码中无任何使用**。**建议**：P1 接入短信通道时一并实现频控。

---

### B4. 密码存储

#### ✅ 做对的部分

- `PasswordHasher`（`core-user/.../security/PasswordHasher.java`）：16 字节 `SecureRandom` 盐（:29-30）+ SHA-256（:31）+ Base64 存储（:33），格式 `s256$salt$hash`。
- **常量时间比较**（:50）：`MessageDigest.isEqual` ✓。
- **格式非法返回 false 不抛异常**（:42-44, :51-53）：避免被用于探测 ✓。
- **不落日志**：`AuthService` 全程不打印 password / passwordHash，只有 `userId` 和 `failCount`（:68, :90-91）✓。
- **TODO(P1) 标注诚实**（:11-14）："迁移到 BCrypt（$2a$，cost=10）。迁移时采用双写校验" ✓。

---

#### [P0] `core-user/.../security/PasswordHasher.java:31` — 单轮 SHA-256 + 盐**无任何密钥 stretching**，抗爆破能力极弱

```java
byte[] digest = sha256(concat(salt, rawPassword.getBytes(StandardCharsets.UTF_8)));
```

**理由**：SHA-256 是**为速度设计**的哈希，在现代 GPU 上可达 **每秒数十亿次**。即使有 16 字节随机盐（防彩虹表），攻击者拿到 `user_account` 表后：
- 无需字典表，每条记录独立爆破
- 常见密码（`123456`、`password`、`13800138000` 等）在 GPU 集群上**秒级**完成

**对比**：BCrypt cost=10 约 100ms/次，慢 5~6 个数量级；Argon2 更好。

这是**唯一一个 P0 的密码存储问题**。TODO(P1) 的标注是诚实的，但"知道要用 BCrypt"和"现在就用"是两回事——P0 系统如果有任何真实用户或真实密码（联调、测试环境、灰度），风险已经存在。

**建议修复**：
1. **立即**：把 `PasswordHasher` 换成 PBKDF2-HMAC-SHA256（**JDK 原生 `SecretKeyFactory`，零三方依赖**，与"零额外依赖"的设计目标兼容），迭代 ≥ 600,000 次，输出格式 `pbkdf2$iterations$salt$hash`；
2. `matches()` 保留对 `s256$` 旧格式的识别（读存量），登录成功后自动升级为新格式（**双写迁移**，TODO 注释已规划好这个方案）；
3. P1 换 BCrypt/Argon2。

---

#### [P1] `core-user/.../service/AuthService.java:86-94` — 登录失败计数的持久化依赖异常回滚的副作用，逻辑脆弱

```java
if (!PasswordHasher.matches(password, user.getPasswordHash())) {
    // 内存实例仅用于生成响应文案；计数/锁定经独立事务提交（否则随本方法异常回滚丢失）
    user.recordLoginFailure(MAX_LOGIN_FAILS, LOCK_MINUTES);
    loginAudit.recordLoginFailure(user.getId(), MAX_LOGIN_FAILS, LOCK_MINUTES);
    ...
    throw new BizException(...);
}
```

**理由**：`verifyCredentials` 是 `@Transactional`，方法末尾抛 `BizException` → **整个事务回滚**。`user.recordLoginFailure` 对 `user` 实体的修改被回滚，**失败计数根本没落库**。代码靠 `loginAudit.recordLoginFailure`（:89）走独立事务来兜底——这个注释说得很清楚，但**用户表上的 `loginFailCount` / `lockedUntil` 字段实际上从未被更新**。

后果：`user.isLocked()`（:80）永远返回 false → **5 次失败锁 15 分钟的机制完全失效**。攻击者可以无限次爆破。

`LoginAuditService` 需要确认它是否独立提交了用户表的更新。从命名和注释推断它只写审计表（`userId` + 失败次数 + 时间），**不回写 `user_account.login_fail_count`**。

**建议修复**：把失败计数的更新移到**非事务方法**中（如 `TransactionTemplate` 包裹的独立调用），或用 `@Transactional(propagation = REQUIRES_NEW)` 的专用 bean；或直接在一条 `@Modifying` UPDATE 里 `SET login_fail_count = login_fail_count + 1 WHERE id = ?`。

**建议同时新增测试**：连续 5 次错误密码 → 断言第 6 次正确密码也被拒（锁定生效）。

---

### B5. 手机号脱敏

#### ✅ `core-user` 侧达标

`AddressService.toResponse`（`AddressService.java:101-104`）对 `mobile` 走 `MaskUtil.maskMobile`，保留前 3 后 4。

#### [P0] `core-order/.../service/OrderQueryService.java:112, 211` — 订单的收货人手机号**完全未脱敏**，原样返回给买家端和管理端

```java
// buyerDetail:112
parent.getReceiverName(), parent.getReceiverMobile(), parent.getReceiverAddress(),
// toAdminVO:211
order.getReceiverName(), order.getReceiverMobile(), ...
```

**理由**：`core-user` 花了力气做 `MaskUtil`，但**订单链路完全没有复用**。`OrderMain.receiverMobile`（`OrderMain.java:100-101`）明文存储、明文返回。

后果：
1. **买家端**：用户 A 的订单列表如果有任何越权（虽然 `ownedParent` 挡住了，但 `AdminOrderVO` 路径不受此限），会泄露他人手机号；
2. **管理端**：`AdminOrderVO` 返回明文手机号给所有有 `order:detail` 权限的管理员。plans `admin-backend.md 2.2.3` 对敏感字段有脱敏要求，且订单导出（4.4.3）明确要求"敏感列脱敏后写文件"。运营/客服/导出文件里出现完整手机号是**个人信息合规（PIPL）问题**。
3. `OrderMain.java:96` 的注释已经意识到这一点："P1 落库加密 + 输出脱敏"——但**输出脱敏可以在 P0 就做**，成本极低（`core-user` 已有 `MaskUtil`，只需把它提到 `common` 模块或复制一份）。

**建议修复**：`OrderQueryService.toBuyerListVO` / `buyerDetail` / `toAdminVO` 中的 `receiverMobile` 一律走 `MaskUtil.maskMobile`（完整手机号在发货场景由专门的有权限接口提供）。

---

#### [P1] `core-user/.../service/AuthService.java:68, 115-118` — 登录成功日志与注册事件中包含手机号明文

```java
log.info("[auth] login success userId={} roles={}", user.getId(), user.getRoles());  // :68 —— 无手机号，OK

eventPublisher.publish(DomainEventNames.MEMBER_REGISTERED, Map.of(
        "userId", user.getId(),
        "mobile", user.getMobile(),        // :117 —— 事件载荷含明文手机号
```

**理由**：`register` 事件的 payload 含明文手机号。事件走 `InMemoryDomainEventPublisher`（P0 进程内日志分发），P1 接 RabbitMQ 后会**持久化到 MQ 并被多个消费者复制**。一个明文手机号在消息中间件里流动，泄露面急剧扩大，且难以撤回。

**建议修复**：事件载荷中手机号改用脱敏值或改用 `userId` 单独传递（消费者需要完整手机号时应回调 core-user）。

---

### B6. 敏感信息落日志

#### ✅ 做对的部分

扫描了全部 `log.*` 调用：
- **无 token 落日志** ✓
- **无密码落日志** ✓（`AuthService` 只打 userId）
- `GatewayLoggingFilter` / `GlobalExceptionHandler` 都不打印请求体 ✓
- `GlobalExceptionHandler.handleOther`（:70）打印完整堆栈但不打印请求参数 ✓
- `RestClientResponseException` 的 `getResponseBodyAsString()` 被打印（`RestOrderClient.java:63, 75`）—— 这是**下游服务的错误响应体**，含错误码和消息，不含敏感数据，可接受。

---

#### [P1] `core-order/.../service/OrderCreateService.java:305` — 下单失败补偿日志打印了原始异常的完整消息，可能含用户输入

```java
log.warn("下单失败已补偿释放库存: parentId={} cause={}", parentId, cause.getMessage());
```

以及 `:307` 的 `log.error(..., releaseError)` 打印完整堆栈。

**理由**：`cause.getMessage()` 对于参数校验异常（`BizException` 的 msg 包含用户输入，如 `"商品不存在: skuId=" + skuId`、`"非法订单状态: " + status`）会**回显用户输入**。用户可能构造超长/含特殊字符的 `status` 参数制造日志注入（log forging）。当前 `parseStatus`（`OrderQueryService.java:249`）的 msg 直接拼接了 `status` 入参。

**建议修复**：`OrderQueryService.parseStatus` 的错误消息**不要回显原始入参**（改为"参数非法，请参考枚举"）；或对所有回显入参的日志做长度截断 + 控制字符过滤。

---

#### [P2] `common/.../api/GlobalExceptionHandler.java:32` — 业务异常消息记 WARN 且**原样打印 msg**，同样有回显入参的问题

同 B6-P1，建议统一在异常消息构造处治理，而非在日志层。

---

### B7. 限流与网关白名单

#### ✅ 做对的部分

- `GatewayRateLimitFilter`（`gateway/.../filter/GatewayRateLimitFilter.java`）：令牌桶按 IP，100 QPS（`application.yml` 配置），**限流在鉴权之前**（`getOrder()` 返回 `HIGHEST_PRECEDENCE`，:101-103），避免未认证流量打满 JWT 验签开销 ✓ 设计正确。
- **返回 429 + 业务码 1001**（:86, :91）符合 plans 16.2 约定 ✓。
- 白名单用 `AntPathMatcher`（:92-97），配置驱动 ✓。

---

#### [P1] `gateway/.../filter/GatewayRateLimitFilter.java:62` — `synchronized` 方法是**全局单锁**，所有 IP 的请求串行排队

```java
private synchronized boolean tryAcquire(String clientIp, double permitsPerSecond) {
```

**理由**：`synchronized` 锁的是 `this`（整个 filter 实例），意味着**任意 IP 的任意请求都要获取同一把锁**。虽然临界区很短（几个纳秒级的算术），但在 plans 口径 **60 万 QPS 网关入口**下，单锁会成为明确的瓶颈。代码注释（:60）承认"P0 用粗粒度同步（单机开发/小流量够用）"，且给了 P1 方案（Sentinel）。

但 plans 目标量级（60 万 QPS）与当前实现的差距是 **3 个数量级**，不是"小流量够用"的范畴。

**建议修复**：P1 上 Sentinel（plans 11.4 已规划）；或短期改为 `Striped<LongAdder>` / `AtomicReference` 免锁方案（把 Bucket 换成 `DoubleAdder` + CAS），去掉 `synchronized`。

---

#### [P1] `gateway/.../filter/GatewayRateLimitFilter.java:76-79` — `X-Forwarded-For` 可被客户端伪造，限流可被绕过

```java
String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
if (xff != null && !xff.isBlank()) {
    return xff.split(",")[0].trim();
}
```

**理由**：网关**直接信任客户端发送的 `X-Forwarded-For` 头的第一个值**。攻击者每次请求携带不同的 `X-Forwarded-For: 1.2.3.4`，即可让每个请求都命中一个新的令牌桶 → **限流完全失效**。

**建议修复**：只在**网关是可信代理链的一环**时才读 XFF，且取**最右侧**（最后一个非内网 IP）或由 Spring Cloud Gateway 的 `ForwardedHeaderFilter` / `XForwardedHeadersFilter` 统一处理；在直连场景下忽略 XFF 使用 `remoteAddress`。

---

#### [P1] `gateway/.../config/GatewayProperties.java`（`JwtAuthGlobalFilter.java:130-135`）— 白名单默认值与 `application.yml` 配置不一致

代码里的 `DEFAULT_WHITELIST` 缺了 4 项：`/api/v1/brand/**`、`/api/admin/auth/captcha`（yml 里有），且**多了** `/api/v1/auth/sms-code`（yml 里也有，一致）。

**理由**：两处定义**独立维护**，没有单一事实来源。当前靠 yml 覆盖代码默认值，所以实际行为正确，但一旦 yml 缺失某项就会静默回退到代码默认值（更宽松或更严格，方向不定）。

**建议修复**：删除 `DEFAULT_WHITELIST` 常量，白名单**只允许 yml 配置**（`properties.auth().whitelist() == null` 时应当**默认拒绝全部**而非放行——当前 `isWhitelisted` 返回 `false`，这个方向是安全的 ✓，但代码常量仍应删除以免误导）。

---

## C. 架构与口径一致性

### C1. 响应信封与错误码

#### ✅ 结论：全局一致

- 所有 Controller 返回 `ApiResponse<T>`（`common/.../api/ApiResponse.java`），由 `GlobalExceptionHandler`（:24 `@RestControllerAdvice`）统一收敛异常。
- `PageResult`（:23 `PageResult.of`）统一分页 `list/total` 口径。
- `traceId` 由 `TraceIdInterceptor`（:16-21）注入并回写响应头，`ApiResponse` 携带 ✓。
- 网关的 401/429 响应也用 `ApiResponse.error`（`JwtAuthGlobalFilter:105`、`GatewayRateLimitFilter:90`）✓ 全局统一。
- `BizErrorCode`（`common/.../api/BizErrorCode.java`）覆盖 0/1xxx/2xxx/3xxx/4xxx/5xxx/9xxx，无重复数值（`fromCode` 线性查找，无冲突）。
- HTTP 状态映射（`GlobalExceptionHandler.httpStatus:75-84`）与 `BizErrorCode` 类注释声明的映射一致 ✓。

#### [P2] `gateway/.../filter/GatewayRateLimitFilter.java:91` — 限流返回 1001（PARAM_ERROR 参数错误）语义冲突

限流不是参数错误。`BizErrorCode` 无专门的限流码，`plans 16.2` 约定"1001 含限流"，代码注释也这么说。但从可观测性和客户端处理角度，客户端无法区分"参数错了"和"被限流了"。

**建议**：P1 在 `BizErrorCode` 增加 `RATE_LIMITED(1006)`，与 plans 同步。

---

### C2. 跨服务调用

#### ✅ 做对的部分

- **只调不查库** ✓：所有跨服务聚合走 HTTP（`RemoteSupport`、`ClientSupport`、`RestOrderClient` 等），无跨服务直查 DB。
- **超时统一** ✓：`ClientSupport.client:26-27`（连接 1s / 读 3s）、`RemoteSupport.restClient:38-39`（同）、`RestOrderClient:35-36`（可配，默认同）。符合 DEV.md 3 口径。
- **统一错误翻译** ✓：`ClientSupport.unwrap:31-39` + `mapCode:42-51`；`RemoteSupport.unwrap:91-104` + `translate:107-124`。下游业务码尽量透传（`RemoteSupport.unwrap:97-100` 用 `BizErrorCode.fromCode` 反查），不粗暴掩码成 9999 ✓。

---

#### [P0] `core-order/.../client/RestInventoryClient.java:23-54` — 三个库存写接口**全部无降级**，任一失败直接向上抛 `SYSTEM_ERROR`

```java
public void reserve(Long orderId, List<InventoryItem> items, long ttlSeconds) {
    ApiResponse<Map<String, Object>> response = restClient.post()
            .uri("/api/v1/internal/inventory/reserve")
            .body(...)
            .retrieve()
            .body(...);
    ClientSupport.unwrap(response, "core-inventory");
}
```

**理由**：审计要求明确"是否所有跨服务调用都有降级，失败时是否会导致资金/库存不一致"。这三个是**无降级的**，且它们的失败后果各不相同：

| 调用点 | 无降级的后果 |
|--------|-------------|
| `OrderCreateService:147` reserve | 抛 3002/9999 → **下单失败**（这个是正确的：不能无库存下单） |
| `OrderCreateService:304` release（补偿） | 已被 `catch (Exception releaseError)` 包裹并记 ERROR（:306-308）—— 正确 |
| `OrderLifecycleService:293` release（关单） | 已被 `releaseQuietly` 包裹（:291-297）—— 正确 |
| `OrderLifecycleService:176` **deduct** | **未被包裹**！见下 |

**关键问题在 `confirmReceive`（`OrderLifecycleService.java:170-176`）**：
```java
stateMachine.transition(parent.getId(), OrderStatus.COMPLETED, ...);  // 订单已流转（独立事务提交）
txTemplate.executeWithoutResult(s -> { fillReceiveTime(...); });      // 已提交
inventoryClient.deduct(parent.getId());   // ← 裸调用，core-inventory 不可用时抛 9999
```

订单已流转为 `COMPLETED` 并已提交，但扣减库存失败 → **用户看到"已确认收货"（货已发），但库存 `locked` 没有扣减，`available` 也没回补**。该 SKU 的这批库存**永久处于锁定态**，既不可售（available 减了）也不计入已售（locked 没消）。

`releaseQuietly` 的模式在这行**没有被复用**，是一个明显的遗漏。

**建议修复**：`deduct` 同样用 `deductQuietly` 包裹，失败时 ERROR 告警 + 登记待补偿；P1 增加库存对账任务扫描"订单已 COMPLETED 但 reservation 仍 ACTIVE"的记录并补扣。

---

#### [P1] `core-order/.../client/RestPromotionClient.java:58-63, 82-86` — 券服务的**静默降级**虽业务合理，但缺少可观测性

```java
} catch (Exception e) {
    log.warn("[promotion-degrade] 优惠券核销失败，本单不折扣: couponId={} orderId={} err={}", ...);
    return BigDecimal.ZERO;
}
```

**理由**：降级本身是对的（不让非核心服务阻塞交易）。但：
1. 只有 WARN 日志，**没有指标**（降级次数、比例），无法在监控上发现"券服务已经挂了 30%"这类问题；
2. 响应中不告知用户（见 A8-P1），用户以为用了券实际没优惠。

**建议**：加 Micrometer 计数器（`coupon_deduct_degraded_total`）+ 在 `CreateOrderResponse` 中返回 `couponApplied` 与降级原因。

---

#### [P1] `core-product/.../service/ProductService.java:502-521` — `syncPricesToPriceService` 在商品事务内发起 HTTP 调用，形成长事务

```java
@Transactional
public Spu create(ProductInput in) {
    ...
    spuRepo.save(spu);
    saveSkus(spu, in.skus());
    syncPricesToPriceService(spu, in.skus());   // ← 事务内 HTTP 调用 core-price（读超时 3s）
    ...
}
```

**理由**：core-price 不可用时，HTTP 调用会阻塞最多 3s（`ClientSupport` 读超时），**期间数据库事务和连接池连接被占用**。批量创建商品（每个 SKU 一次同步，总共一次 HTTP 调用但 body 很大）时更明显。

这是 plans 明确点名的风险（"跨服务调用的长事务风险"）。P1 演进到 MySQL + 连接池后，高并发商品写入时会耗尽连接池。

**建议修复**：把 `syncPricesToPriceService` 移到事务提交后（`TransactionSynchronization.afterCommit`，与事件发布同一模式），或改为发事件由消费者异步同步。

---

#### [P1] `core-search/.../service/SearchSyncService.java`（定时拉 core-product 全量）+ `application.yml` 的 `sync-interval-ms: 60000` — 每分钟全量拉取商品

**理由**：`core-search` 用 HTTP 轮询而非 MQ，60s 一次全量（分页 500/页）。商品数增长后这是持续的大流量拉取，且**拉取期间 core-product 的负载与 core-search 的 DB 写入都是同步的**。plans 11.x 的事件驱动方案（P1）在注释里提到了。**建议**：P1 改订阅 `product.created/updated/deleted` 事件增量同步。

---

### C3. 事务边界

#### ✅ 做对的部分

- **事件全部在事务提交后发布** ✓：`AfterCommitEvents`（`core-order/.../support/AfterCommitEvents.java:17-28`）、`InventoryService` 的同名工具、`ProductService.publishAfterCommit:548-559`、`PriceService.publishPriceChanged:167-181`——全部用 `TransactionSynchronizationManager.registerSynchronization` 的 `afterCommit` ✓。**无一处事务内发事件**。
- **`PaymentService.prepay` 刻意不开外层事务**（:61 注释）："订单服务远程调用不占用 DB 连接；写库各自成小事务" ✓ 这是正确的考虑。
- **库存/订单的"读-改-写"都在单事务内** ✓（A5 已述）。

---

#### [P1] `core-payment/.../service/PaymentNotifyService.java:77` — 去重日志的"独立事务提交"依赖 `@Modifying` 方法的默认传播，语义脆弱

```java
// 仓库 save 自带事务：去重日志先独立提交
notifyLogRepo.saveAndFlush(newNotifyLog(cmd));
```

`saveAndFlush` 在 `SimpleJpaRepository` 上标注了 `@Transactional`（默认 `REQUIRED`）。`handleNotify` 自身**没有** `@Transactional`，所以 `saveAndFlush` 会开新事务并提交 ✓ 当前正确。

但这依赖"调用方恰好没有活动事务"这一**隐式前提**。若未来有人在 `handleNotify` 上加 `@Transactional`，去重日志就会与后续 ②③ 同事务，**③ 失败回滚时去重记录也消失** → 渠道用同一 notifyId 重投会被当成新通知重新处理（可能造成重复入账，虽然 ② 的 CAS 能挡住）。

**建议修复**：显式标注 `@Transactional(propagation = Propagation.REQUIRES_NEW)`（在一个独立的 bean 上），让意图不依赖调用上下文。

---

#### [P1] `core-cart/.../service/CartService.java:108-132` — 加购的 `for(;;)` 重试循环内每次迭代都可能产生 DB 事务，且第一轮 `findByUserIdAndSkuId` 在事务外

```java
for (int attempt = 1; ; attempt++) {
    var existing = cartRepository.findByUserIdAndSkuId(userId, request.skuId());  // 无事务（repository 自带）
    ...
    Long cartItemId = txTemplate.execute(status -> { ... });
    ...
}
```

**理由**：`attempt` 递增但 `if (attempt >= 2) throw`（:127-129）——即**只有第 1、2 轮有效，第 3 轮直接抛**。如果第 2 轮又撞唯一键冲突，抛的是 `SYSTEM_ERROR`（9999）而不是"加购成功"。并发加购同一 SKU 的 3 个请求同时到达时，第 3 个可能失败。

**建议修复**：把重试上限提到 3~5 次，或改用 `INSERT ... ON DUPLICATE KEY UPDATE` 单语句（MySQL 原生，H2 兼容语法可测）。

---

### C4. plans 要求但未实现/降级的功能

区分「P0 简化」与「缺陷」：

#### 属于 P0 简化（代码有注释标注，文档有登记，不算缺陷）

| 功能 | 状态 | 标注位置 |
|------|------|---------|
| 运费模板 | 恒 0 | `OrderCreateService.java:185` TODO(P1) |
| 物流单号/承运商 | 未落 logistics 服务 | `OrderLifecycleService.java:198` TODO(P1) |
| `order.completed` / `order.shipped` 事件 | 注册表无此常量，用 `order.status.changed` 承载 | `OrderLifecycleService.java:177-179, 199-200` |
| 短信通道 | 固定验证码 123456 | `AuthService.java:37` TODO(P1) |
| BCrypt | SHA-256+salt | `PasswordHasher.java:11` TODO(P1) |
| Redis 幂等 | 内存 Map | `IdempotentInterceptor.java:18` P1 |
| 收银地址加密 | 明文存储 | `OrderMain.java:96` P1 |
| 数据权限（RBAC 2.3 档位） | 未注入过滤 | `admin-bff/.../web/AdminOrderController.java:21` TODO(P1) |
| MQ 事件 | 进程内分发 | 多处 |
| 单店铺假设 | `storeId` 固定 0 | `ProductService.java:413` |

#### 属于**缺陷**（未在 plans 中登记为简化，或简化方向错误）

| # | 问题 | 依据 |
|---|------|------|
| 1 | **预支付取 `totalAmount` 而非 `payAmount`** | A3-P0。plans 裁决 C7 是"服务端定额"，plans 的语义明确是应付金额 |
| 2 | **退款空实现** | A3-P0。plans 4.4.4 / android-backend 4.2.4 明确要求原路退款，且 core-payment 的退款接口**已经实现并测试通过** |
| 3 | **关单不退券** | A6-P0。plans 的优惠券流程含"关单退回"，core-promotion 的 `release` 接口**已实现** |
| 4 | **拆单时机**（下单即拆 vs 支付后拆） | A2-P1。plans 4.4.2 明确"未支付订单不拆单"，代码注释承认差异但 plans 未更新 |
| 5 | **尾差归集到首单 vs 最大子单** | A2-P0。plans 4.4.2 规则 2 明确"尾差归入金额最大的子单" |
| 6 | **`confirmReceive` 的扣减库存无异常保护** | C2-P0。`releaseQuietly` 的模式未复用 |

---

### C5. 测试覆盖度

#### ✅ 覆盖到的关键路径

| 路径 | 测试 | 质量评价 |
|------|------|---------|
| 支付回调重复 10 次只生效 1 次 | `PaymentIntegrationTest.duplicateNotifyTenTimesEffectiveOnce` | ★★★★★ 强：验证 notify_log 只 1 条、markPaid 只调 1 次、事件只发 1 次 |
| 支付金额不符拒绝 | `PaymentIntegrationTest.notifyAmountMismatchRejected` | ★★★★☆ 强：验证状态不翻转 + 新 notifyId 仍能入账 |
| 防改价 | `PaymentIntegrationTest.prepayIgnoresClientAmount` | ★★★★☆ 强，但**盲区**：打桩只有单一金额字段，测不出取错字段 |
| 退款幂等 | `PaymentIntegrationTest.refundIsIdempotent` | ★★★★☆ 验证 3 次只发 1 次事件 |
| 状态机白名单 | `OrderStateMachineTest` | ★★★★☆ |
| 关单 vs 回调竞态 | `OrderRaceTest`（3 个，含真并发） | ★★★★★ 很强，分支式断言设计合理 |
| 拆单分摊 + Σ 平衡 + 24 位单号 | `OrderCreateSplitTest` | ★★★★★ 强，精确验证到分 |
| 幂等冲突 | `OrderIdempotencyTest` / `CartIdempotencyTest` | ★★★☆☆ |
| SQL 转义 | `SearchEscapingTest` | ★★★☆☆ |

---

#### ❌ **缺失的关键测试**（按风险排序）

| # | 缺失的测试 | 对应缺陷 | 风险 |
|---|-----------|---------|------|
| 1 | **有券订单的 prepay 金额 = payAmount** | A3-P0 | 直接漏掉了最严重的资金 bug |
| 2 | **关单/取消后优惠券退回** | A6-P0 | 权益损失，无测试守护 |
| 3 | **关单竞态败方真正触发 core-payment 退款** | A3-P0 | 当前 `OrderRaceTest` 用 `@MockBean`，验证的是"调用了"不是"退款了" |
| 4 | **同一用户并发领券不突破 perLimit** | A6-P1 | 超发漏洞无测试 |
| 5 | **幂等键在业务失败后可重用** | A8-P0 | 用户无法重试，无测试 |
| 6 | **`release` 遇到 sku_stock 缺失时部分成功** | A5-P0 | 幽灵锁无测试 |
| 7 | **并发扣减同一 SKU 库存为 1** | A5-P1 | `withOptimisticRetry` 重试路径从未被测试执行 |
| 8 | **5 次密码错误后账号锁定生效** | B4-P1 | 锁定机制疑似完全失效（B4-P1） |
| 9 | **用户名枚举（不存在的用户 vs 密码错）返回相同码** | B2-P1 | 枚举漏洞无测试 |
| 10 | **`confirmReceive` 时 core-inventory 不可用** | C2-P0 | 库存泄漏无测试 |
| 11 | **`DiscountAllocator` 的边界输入**（全 0、单元素、负数尾差、大额） | A2-P0 | 分摊器是资金核心但只有 1 个测试 |
| 12 | **雪花 ID 时钟回拨** | A9-P2 | 并发安全声明无测试 |

**测试策略的系统性问题**：资金路径的测试大量使用 `@MockBean` 替换被测的**下游服务**（`OrderRaceTest` mock 掉 `inventoryClient`/`paymentClient`/`promotionClient`，`PaymentIntegrationTest` mock 掉 `OrderClient`）。这保证了**测试跑得快且稳定**，但也意味着**跨服务的契约一致性完全未被验证**——而本次审计发现的两个 P0（A3 取错字段、A3 退款空实现）**恰恰都是 mock 掩盖掉的**。

**建议**：建立一组 `@SpringBootTest` 的**契约测试**（或 Testcontainers 起的真实依赖），覆盖"下单 → 用券 → prepay → 支付 → 退款"完整闭环。现有 `LIVE-INTEGRATION-REPORT.md` 的 46 项真实链路验证是好的开始，但那些是**手工/脚本验证**，应固化为自动化回归。

---

## 修复优先级清单

### P0（必须修复后才能上线，8 项）

| # | 问题 | 文件 | 工作量 |
|---|------|------|--------|
| 1 | **prepay 取 `payAmount` 而非 `totalAmount`** + markPaid 前二次金额校验 + 补测试 | `PaymentService.java:83`、`InternalOrderController.java:51`、`OrderSnapshot.java` | **4h** |
| 2 | **接入真实退款 API**（接口已存在，只是 client 没接）+ 事件仅在退款成功后发 | `RestPaymentClient.java:30-38`、`OrderLifecycleService.java:278-288` | **4h** |
| 3 | **关单/取消/下单失败三条路径退券** + `OrderMain` 加 `coupon_id` | `OrderLifecycleService.java`（3 处）、`OrderCreateService.java:168-171`、`OrderMain.java`、`PromotionClient.java` | **8h** |
| 4 | **`confirmReceive` 的扣减库存加异常保护** | `OrderLifecycleService.java:176` | **1h** |
| 5 | **尾差归集改为"金额最大的子单"**（对齐 plans） | `DiscountAllocator.java:32-56`、`OrderCreateService.java:208` | **2h** |
| 6 | **幂等键改为"成功后记录"**（或在异常时释放占位） | `IdempotentInterceptor.java:51-53` + `afterCompletion` | **4h** |
| 7 | **库存 `release`/`deduct` 单条失败不中断整批** + 标记人工复核 | `InventoryService.java:148-195, 204-236` | **6h** |
| 8 | **密码哈希换 PBKDF2**（JDK 原生，零三方依赖）+ 双写迁移 | `PasswordHasher.java:28-54` | **4h** |
| 9 | **订单收货手机号脱敏** | `OrderQueryService.java:112, 211` | **1h** |
| 10 | **券 deduct/release 去掉 `userId != null` 空档** | `CouponService.java:119, 145` + 路径分离 | **3h** |
| 11 | **状态机幂等短路移到 validate 之后 + ship 检查返回值** | `OrderStateMachine.java:104`、`OrderLifecycleService.java:191` | **2h** |

**P0 小计：约 39 人时（约 5 个工作日）**

---

### P1（上线前应完成，12 项）

| # | 问题 | 工作量 |
|---|------|--------|
| 1 | `markPaid` 重试耗尽后的告警/工单闭环 + 对账重试上限 | 4h |
| 2 | 金额不符通知的去重记录可重投（③ 失败删 ①） | 2h |
| 3 | 支付单卡在 `REFUNDING` 的定时补偿 | 4h |
| 4 | 每人限领的原子化（防并发超领） | 6h |
| 5 | 登录失败计数独立事务落库 + 补锁定测试 | 4h |
| 6 | 用户名枚举防护（统一码 + 哑哈希） | 2h |
| 7 | JWT `alg` 白名单显式断言 | 1h |
| 8 | 网关 401 区分 NOT_LOGIN / TOKEN_EXPIRED | 2h |
| 9 | 限流绕过（XFF 信任）与 `synchronized` 全局锁 | 4h |
| 10 | `AdminOrderController.requireAdminRole` 改默认拒绝 | 1h |
| 11 | 超时关单扫描分页 + 内存优化 | 3h |
| 12 | `payAmount` 公式补运费项 + 恒等式断言 | 2h |
| 13 | 三个 `@Scheduled` 加分布式锁或统一标注多副本局限 | 3h |
| 14 | `markPaid` 的 `fillPayInfo` 并入状态流转事务 | 2h |
| 15 | `syncPricesToPriceService` 移出事务 | 3h |
| 16 | 事件载荷去手机号明文 + 日志回显入参治理 | 3h |
| 17 | 券服务降级加指标 + 响应告知用户 | 3h |
| 18 | `sku_stock` / `stock_reservation` 主键改雪花 | 3h |

**P1 小计：约 52 人时（约 6.5 个工作日）**

---

### P2（改进建议，9 项）

| # | 问题 | 工作量 |
|---|------|--------|
| 1 | 雪花 ID 时钟回拨加告警 + 指标 | 2h |
| 2 | JWT 密钥默认值生产环境启动即失败 | 1h |
| 3 | `Stock` 领域对象补不变量断言 | 2h |
| 4 | `IdempotentInterceptor` 清理策略改定时/Caffeine | 3h |
| 5 | SKU 映射重复时告警而非静默取第一条 | 1h |
| 6 | 券有效期快照口径加注释 | 0.5h |
| 7 | `parseStatus`/`Cursor.decode` 错误信息精确化 | 1h |
| 8 | `BizErrorCode` 增加 `RATE_LIMITED(1006)` | 1h |
| 9 | 删除 `JwtAuthGlobalFilter.DEFAULT_WHITELIST` 重复定义 | 0.5h |
| 10 | 幂等内存 Map 换 Caffeine（带 maximumSize） | 3h |

**P2 小计：约 15 人时（约 2 个工作日）**

---

### 建议新增的自动化测试（与 P0 修复同步进行，约 40h）

见 C5 表格 12 项。重点是第 1、2、3、5 项——它们直接守护本次发现的 P0。

---

## 总体结论

### **有条件上线（灰度 / 内测），不建议直接生产上线**

**理由：**

**支持"可以进内测"的依据：**
1. 核心状态机、拆单分摊、库存两段式、支付回调三步幂等这四条资金主链路的**设计是正确的**，实现质量高于一般 P0 骨架。白名单逐边对齐、终态显式拦截、CAS + 重读裁决、Σ 金额硬校验、SQL 层条件更新防超发——这些不是靠"看起来对"蒙混，是有可验证逻辑的。
2. 关键缺陷**大多有诚实标注**（TODO/P1/局限说明遍布代码），说明开发者清楚边界在哪。
3. 测试对已有路径的覆盖质量不错（拆单分摊测试精确到分、竞态测试有真并发）。

**不支持"直接上线"的依据（4 条硬理由）：**
1. **有券订单会多收钱**（A3-P0）——这不是边缘场景，是营销常态。只要平台发券，每一笔用券订单都多收一个券面额，且**永久沉淀、无法自愈**（支付流水与订单实付字段不一致，对账不平）。
2. **两条退款路径都是假的**（A3-P0）——用户付款后订单被关单，钱不退；管理员关闭已支付订单，钱不退。而系统对外**发 `ORDER_REFUND_APPLIED` 事件宣称已退**。这是"用户投诉 + 平台失信 + 事件驱动的下游全被污染"三重问题。
3. **订单取消/超时会让用户损失优惠券**（A6-P0）——库存正确释放了，券回不来。平台运营会立刻发现"为什么我的券没了"，且接口已实现只是没调用。
4. **幂等键"尝试即占位"会让高频失败场景无法重试**（A8-P0）——"库存不足"是秒杀场景的常态，用户点了重试就吃 409，要等 TTL。这会在故障时把局部问题放大成全局不可用。

外加两个安全项（密码哈希、券的归属校验空档）虽然都有 TODO 标注，但**TODO 标注不等于风险不存在**。

### 上线门槛建议

**分三步走：**

**第一步（灰度/内测，1 周内）**
- 完成全部 P0 修复（39 人时，约 5 个工作日）
- 补 C5 的第 1、2、3、5 项测试（守护本次的 P0）
- 加"注册验证码 mock 开关"，`prod` profile 强制关闭（防误部署）
- **限流、支付、退款相关配置全部显式设置**，不依赖默认值

**第二步（生产公测，1–2 周后）**
- 完成 P1 前 8 项（用户枚举、券超领、密码锁定、JWT）
- 建立库存/资金/优惠券的**对账任务**（当前只有支付对账，库存和券的对账缺失是多个 P0 无法自愈的根本原因）
- 接入监控告警：支付降级率、库存对账差异、券核销失败率、幂等冲突率

**第三步（全量，1 个月后）**
- 全部 P1 + P2
- MySQL 替换 H2（`ddl-auto: update` → Flyway `validate`）
- `sku_stock` / `stock_reservation` 主键改雪花（分片前置）
- 建立跨服务**契约测试**（Testcontainers），避免再次出现"mock 掩盖契约 bug"

### 最需要补的架构能力（当前完全缺失）

审计过程中反复出现同一个根因：**多个 P0 之所以无法自愈，是因为缺少对账/补偿闭环**。

| 数据 | 现有对账 | 缺失的 |
|------|---------|--------|
| 支付 | ✅ `PaymentReconcileJob`（每小时） | 重试上限 + 告警升级 |
| 库存 | ❌ 无 | "订单 COMPLETED 但 reservation 仍 ACTIVE"扫描补扣 |
| 优惠券 | ❌ 无 | "订单已取消但券仍 USED"扫描退回 |
| 退款 | ❌ 无 | "订单 CLOSED 但 payment 仍 PAID"扫描退款 |
| 幂等 | ❌ 无 | 无需（业务幂等键已在 DB 层） |

**建议在 P1 建立一张统一的 `reconcile_task` 表 + 通用对账扫描器**，这是让上述多个 P0 从"永久资损"降级为"最终一致"的唯一系统性方案。

---

*本报告为只读审计产物，未修改 `server/` 下任何文件。*