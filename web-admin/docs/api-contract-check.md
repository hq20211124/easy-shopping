# web-admin API 契约核对清单

> 核对基准：`server/` 下**实际实现**（各服务 Controller / admin-bff / edge-gateway 路由 / `DataInitializer` 权限注册表 / `IdempotentInterceptor`），非 `plans/` 规划稿。
> 前端侧改动范围：`src/api/*` 适配层 + 枚举/菜单常量 + 受影响页面的字段与权限码引用；**未重写任何页面、未替换组件库、未改动业务交互流程**。
> Mock 侧（`mock/index.ts`）数据形状与后端 VO 完全一致（字段名、单位、时间格式、分页 `list/total`、信封 `{code,msg,data,traceId}`）。

> ⚠️ **2026-10-12 全表重做**：本表上一版的 32 项对账表有 **10 处与代码/后端实际不符的标注**（详见文末「审查记录 · 附」），会持续误导后续开发。
> 现改为**以 `src/api/*` 实际导出为准**逐函数重建：行数按真实导出数统计（**30 个**，不是 32），每行标注**复核方式**（后端文件:行号），可一键回验。

---

## 一、核对结论总览（30 个导出函数）

| 域 | 文件 | 导出函数数 | 与后端一致 | 后端未实现（前端降级） |
|----|------|-----------|-----------|----------------------|
| 认证/会话 | `api/auth.ts` | 5 | 5 | 0 |
| 商品 | `api/product.ts` | 4 | 4 | 0 |
| 订单 | `api/order.ts` | 7 | 3 | 4（remark 1 + 售后 3） |
| 用户/角色 | `api/user.ts` | 9 | 9 | 0 |
| 系统/工作台 | `api/system.ts` | 5 | 5 | 0 |
| **合计** | — | **30** | **26** | **4** |

补充口径（不属于上表的 30 个导出函数，单独登记）：

| 类别 | 项 | 结论 |
|------|----|------|
| 后端已就绪、前端未接入 | `POST /api/admin/order/{id}/ship`（发货）、`/api/admin/audit/task/*`（审核任务）、`/api/admin/system/dept/**`、`/api/admin/system/config/**`、`GET /api/admin/dashboard/overview` | 后端 Controller 齐全，`src/api/*` **零调用**，`routes.tsx` 也无对应页面 → 记「后端已就绪 / 前端未接入」，不算契约错误 |
| 前后端一致缺失（记遗留） | `getUserDetail`（后端亦无 `GET /api/admin/system/user/{id}`）、`mark-paid` 端点、售后 `detail/confirm-receive/refund/logistics`（plans 4.2.2 列了但两端都没有） | 不修，记遗留 |

---

## 二、逐函数对拍（以 `src/api/*` 为准）

> **复核方式说明**：`grep -n "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" <后端Controller>` 逐条比对方法+路径；入参名比对 Controller 方法签名；出参比对 record/DTO 定义。

### 2.1 认证/会话 `src/api/auth.ts`

| 前端函数 | 前端路径 | 后端路径（**复核方式**） | 状态 |
|---------|---------|------------------------|------|
| `login` | `POST /admin/auth/login` | `POST /api/admin/auth/login`（`admin-bff/web/AdminAuthController.java:61`） | ✅ 一致 |
| `logout` | `POST /admin/auth/logout` | `POST /api/admin/auth/logout`（`AdminAuthController.java:84`，返回 `{success:true}`） | ✅ 一致 |
| `getAuthInfo` | `GET /admin/auth/info` | `GET /api/admin/auth/info`（`AdminAuthController.java:98`） | ✅ 一致 |
| `getMenus` | `GET /admin/auth/menus` | `GET /api/admin/auth/menus`（`AdminAuthController.java:104`，`RbacService.MenuNode{key,label,path,permission,children}`） | ✅ 一致 |
| `getCaptcha` | `GET /admin/auth/captcha` | `GET /api/admin/auth/captcha`（`AdminAuthController.java:109`，`CaptchaService.Captcha`） | ✅ 一致 |

- **无 `refreshToken` 导出函数**：刷新在 `api/request.ts:refreshAccessToken()` 内联 `POST ${baseURL}/admin/auth/refresh`（`AdminAuthController.java:72`）。
  响应体是 `{accessToken, expiresIn, tokenType}`（`AdminAuthController.java:114-120 toBody`），**不含 `refreshToken`**；
  refresh token 只走 httpOnly Cookie（`AdminAuthController.java:67/79 SET_COOKIE`，`:122-129 extractRefreshToken`）。前端 `LoginResult` 与之一致 ✅。
- **后端没有 `/api/admin/auth/me`**：上一版文档写的 `getCurrentUser → GET /api/admin/auth/me` 是**不存在的端点**，已删除该行。
- 信封：后端统一 `{code,msg,data,traceId}`，`extractMessage` 只读 `msg`（`request.ts`；已删除 plans 旧口径 `message` 的死分支与 `ApiResponse.message` 字段）。
- 401 刷新链：1002/1003 → 单飞刷新 + 排队重放（`request.ts` 模块级 `refreshing`），与后端 `BizErrorCode` 语义一致。

### 2.2 商品 `src/api/product.ts`

| 前端函数 | 前端路径（对齐后） | 后端路径（**复核方式**） | 状态 |
|---------|-----------------|------------------------|------|
| `listProducts` | `GET /admin/product/list`（`page/pageSize/keyword/categoryId/auditStatus/shelfStatus`） | `core-product/web/AdminProductController.java:41-50`，分页入参确为 **`page`**（非 `pageNum`）+ `pageSize`；admin-bff 同名透传（`admin-bff/web/AdminProductController.java:33`） | ✅ 一致（`pageNum`→`page` 换名正确） |
| `getProductDetail` | `GET /admin/product/{id}` + `GET /api/v1/price/skus?ids=` | `core-product .../AdminProductController.java:53`（`toAdminMap`，无 `cost:read` 时 `costPrice` 脱敏为 null）；价格 `core-price/web/PriceController.java:26 GET /api/v1/price/skus` | ✅ 一致 |
| `updateProduct` | `PUT /admin/product/{id}`（适配层重组全量 `ProductInput`） | `core-product .../AdminProductController.java:73`（`@Idempotent`，仅 draft/rejected 可编辑） | ✅ 一致（注意：SKU 全量替换副作用见 §3-6） |
| `batchShelf` | `POST /admin/product/batch-on-shelf` \| `batch-off-shelf`（`{ids}`） | `core-product .../AdminProductController.java:155 / :163`，**同步**返回 `{success:[id], failed:[{id,reason}]}` | ✅ 一致 |

- **无 `batchOffShelf` / `batchShelf` 两个独立导出**：只有 `batchShelf(ids, shelf)` 一个函数按 `ShelfStatus` 拼路径。上一版文档把 `batchShelf`/`batchOffShelf` 列为两个函数并写成 `/spu/batch-shelf`、`/spu/batch-off-shelf`，**函数名与路径都不存在**，已更正。
- 出参适配（`toListItem`/`toDetail`）：列表回 `id/minPrice/marketPrice`、详情回 `spuId/price/originalPrice`，两路兼容；元→分换算（`admin-frontend 9.6`）。

### 2.3 订单 `src/api/order.ts`

| 前端函数 | 前端路径（对齐后） | 后端路径（**复核方式**） | 状态 |
|---------|-----------------|------------------------|------|
| `listOrders` | `GET /admin/order/list`（`pageNum/pageSize/orderNo/userId/orderStatus`） | `core-order/web/AdminOrderController.java:48-60`（入参 `userId/orderStatus/storeId/orderNo/includeChildren/pageNum/pageSize`，`orderNo` **精确匹配**）；admin-bff 透传（`admin-bff/web/AdminOrderController.java:33`） | ✅ 一致 |
| `getOrderDetail` | `GET /admin/order/{id}`（**主键 id**） | `core-order .../AdminOrderController.java:62`（`@PathVariable("orderId") Long orderId`）；admin-bff `:39` | ✅ 一致（上一版写的 `{orderNo}` **参数名错**） |
| `closeOrder` | `POST /admin/order/{id}/close`（body `{reason}`） | `core-order .../AdminOrderController.java:80`；admin-bff `:54` | ✅ 一致（上一版写的 `PUT .../{orderNo}/close` **方法+参数名双错**） |
| `addOrderRemark` | `PUT /admin/order/{id}/remark` | ❌ **后端未实现**（全仓无 `remark` 端点） | ⚠️ `ORDER_REMARK_AVAILABLE` 开关下线（mock 可用 / 真实后端隐藏入口） |
| `listAfterSales` | `GET /admin/after-sale/list` | ❌ **后端未实现** | ⚠️ `AFTER_SALE_AVAILABLE` 开关下线 |
| `approveAfterSale` | `POST /admin/after-sale/{id}/approve` | ❌ **后端未实现** | ⚠️ 同上 |
| `rejectAfterSale` | `POST /admin/after-sale/{id}/reject` | ❌ **后端未实现** | ⚠️ 同上 |

- **无 `shipOrder` 导出**：上一版文档写 `shipOrder → PUT /api/admin/order/{orderNo}/ship ✅ 一致` —— 前端**根本没有这个函数**，后端实际是 `POST /api/admin/order/{id}/ship`（`core-order .../AdminOrderController.java:70`，权限 `order:ship`）。已改为「后端已就绪 / 前端未接入」。
- **无 `listRefunds` / `handleRefund`**：上一版写 `listRefunds GET /api/admin/order/refund/list`、`handleRefund PUT /api/admin/order/refund/{id}/{approve|reject}`「✅ 一致」——**这三个后端端点都不存在**，前端也不存在（实际叫 `listAfterSales/approveAfterSale/rejectAfterSale`，且后端未实现）。已更正。
- **无 `markOrderPaid`**：上一版的「已实现」标注前后端均无，记**一致缺失，遗留**（§1 补充口径）。

### 2.4 用户与角色 `src/api/user.ts`

| 前端函数 | 前端路径（对齐后） | 后端路径（**复核方式**） | 状态 |
|---------|-----------------|------------------------|------|
| `listAdminUsers` | `GET /admin/system/user` | `admin-bff/web/SystemUserController.java:35`（`@RequirePerm system:user`） | ✅ 一致（上一版写的 `/api/admin/system/user/list` **路径不存在**） |
| `createAdminUser` | `POST /admin/system/user` | `SystemUserController.java:44` | ✅ 一致 |
| `updateAdminUser` | `PUT /admin/system/user/{id}` | `SystemUserController.java:53`（`AdminUserUpdateReq` 仅 `nickname/mobile/password/status`，**不含 roles**） | ✅ 一致（前端已收窄入参） |
| `setUserRoles` | `PUT /admin/system/user/{id}/roles` | `SystemUserController.java:68`（`@RequirePerm system:role`） | ✅ 一致（页面保存拆两步） |
| `deleteAdminUser` | `DELETE /admin/system/user/{id}` | `SystemUserController.java:61`（软删） | ✅ 一致 |
| `listRoles` | `GET /admin/system/role` | `admin-bff/web/SystemRoleController.java:35` | ✅ 一致 |
| `getPermissionTree` | `GET /admin/system/permission/tree` | `admin-bff/web/SystemPermissionController.java:42` | ✅ 一致 |
| `getRolePermissions` | `GET /admin/system/role/{id}/permissions` | `SystemRoleController.java:68` | ✅ 一致 |
| `updateRolePermissions` | `PUT /admin/system/role/{id}/permissions` | `SystemRoleController.java:75` | ✅ 一致 |

- **无 `getUserDetail`**：上一版写 `getUserDetail → GET /api/admin/system/user/{id}`「✅ 一致」——后端**没有**该端点（`SystemUserController` 只有 GET 列表），前端也没有。已改为**一致缺失，记遗留**。

### 2.5 系统与工作台 `src/api/system.ts`

| 前端函数 | 前端路径（对齐后） | 后端路径（**复核方式**） | 状态 |
|---------|-----------------|------------------------|------|
| `listDictTypes` | `GET /admin/system/dict` | `admin-bff/web/SystemDictController.java:47`（`@RequirePerm system:dict`） | ✅ 一致 |
| `listDictItems` | `GET /admin/system/dict/items?type=xxx` | `SystemDictController.java:64` | ✅ 一致 |
| `listOperationLogs` | `GET /admin/system/operation-log`（`operator/module/createdAtStart/createdAtEnd/pageNum/pageSize`） | `admin-bff/web/SystemLogController.java:35`（`@RequirePerm system:log`；时间参数 `@DateTimeFormat(ISO.DATE_TIME)`，关键字与时间范围互斥、关键字优先） | ✅ 一致（上一版写的 `/api/admin/system/log/list` **路径不存在**） |
| `getWorkbench` | `GET /admin/data/workbench` | `admin-bff/web/AdminDataController.java:21` —— ⚠️ **P0 stub**：`gmv=0`、`items=List.of()` | ✅ 路径一致（数据恒 0，见 §3-7） |
| `getTodoList` | `GET /admin/data/todo` | `AdminDataController.java:32` —— ⚠️ **P0 stub**：恒空列表 | ✅ 路径一致（同上） |

- **「字典/部门/角色/配置 CRUD ✅ 一致」不成立**：上一版把四类合并成一行并标「一致」。实测 `src/api/` 只有 `listDictTypes/listDictItems/listRoles/getPermissionTree/getRolePermissions/updateRolePermissions` 六项，
  **部门（`SystemDeptController.java:45/56/71/90`）与配置（`SystemConfigController.java:42/57/73/91`）后端 CRUD 齐全但前端零调用**，`routes.tsx` 也无对应页面。已拆开并登记为「后端已就绪 / 前端未接入」。

### 2.6 枚举与权限码

- **枚举 0 基对齐**（`src/config/constants.ts`）：`ORDER_STATUS_OPTIONS` 8 值逐值对齐 `core-order/domain/OrderStatus.java`；
  `auditStatus` 0-draft/1-auditing/2-on_shelf/3-rejected（`core-product AuditStatus`）；`shelfStatus` 0-下架/1-上架。
- **权限码对齐后端 25 码注册表**（`admin-bff/rbac/DataInitializer.java:58-85`，逐条核对通过，共 25 个）：
  - 前端曾自造 → 已收敛：`product:on_shelf`/`product:off_shelf` → `product:edit`；`order:remark` → `order:detail`；`refund:approve`/`refund:reject` → `refund:list`（后端**只有 `refund:list`**）。
  - 其余 25 码（`dashboard/product/product:list/product:create/product:audit/order/order:list/order:detail/order:ship/order:close/order:refund/refund:list/system/user:list/user:roles/system:dict/system:log/system:permission/system:dept/system:config/system:role/system:user/cost:read/report:view`）与前端引用一致 ✅。
  - 复核方式：`grep -n 'save("' server/admin-bff/.../DataInitializer.java`。

### 2.7 信封 / 错误码 / 幂等（`src/api/request.ts`）

| 项 | 后端事实 | 前端处理 | 状态 |
|----|---------|---------|------|
| 响应信封 | `{code,msg,data,traceId}`（`common/api/ApiResponse.java`） | `extractMessage` 只读 `msg`；`ApiResponse` 类型已删除 `message` 别名字段 | ✅ 一致 |
| 业务错误码文案 | `BizErrorCode`：1001/1002/1003/1004/1005/2xxx/3xxx/4xxx/5xxx/9999 | `ERROR_CODE_TEXT` 兜底映射（仅在 `msg` 缺失时使用） | ✅ 一致 |
| 幂等 | `Idempotency-Key` 头（`common/idempotent/IdempotentInterceptor`）；缺失→1001，重复 key→1005 | POST/PUT/PATCH/DELETE 自动补键（排除 `/admin/auth/**`）；`newIdempotencyKey()` 带非安全上下文降级；401 重放时复用原键（`!config.headers['Idempotency-Key']` 守卫） | ✅ 一致 |
| 分页 | `PageResult{list,total,pageNum,pageSize}`（`common/api/PageResult`） | 类型与各 list 函数一致 | ✅ 一致 |

---

## 三、后端未实现 / 缺字段（前端已降级，**不修改 server/**）

| # | 项 | 后端现状 | 前端降级策略 |
|---|----|---------|-------------|
| 1 | `PUT /api/admin/order/{id}/remark` | **未实现** | `ORDER_REMARK_AVAILABLE` 开关：mock 下开启、`VITE_USE_MOCK=false` 时隐藏入口（列表页「备注」按钮 + 详情页「添加备注」+ 空态文案） |
| 2 | `/api/admin/after-sale/**`（list/detail/approve/reject/confirm-receive/refund） | **未实现**（plans 4.2.2 规划稿，服务端无 Controller；P1 随 `admin-*` 拆分演进，`plans/00-架构总纲` 演进阶段 21 个 `admin-*` 中不含售后域） | **`AFTER_SALE_AVAILABLE` 开关诚实下线**：<br>① 三函数路径修正为 `/admin/after-sale/*`（对齐 plans 4.2.2 与 mock 路由，原 `/after-sale/*` 少 `/admin` 段，**mock 与真实后端都取不到数**）；<br>② `menus.ts` 静态菜单 + `BasicLayout` 后端菜单树**双侧**剔除「退款/售后」入口（后端 `DataInitializer.java:63` 仍会下发 `/order/refund`，只改静态菜单不够）；<br>③ `order/refund.tsx` 查询 `enabled:false` 不发请求，直接渲染 `Result`「功能未开放」说明页 —— **不允许"点得进、永远空表"**；<br>④ `order/detail.tsx` 状态 4（退款中）的指引文案按开关切换，避免死链指引。<br>**回归测试**：`src/api/order.contract.test.ts` |
| 3 | `AdminOrderVO` 缺 `receiverAddress`/`payDeadline`/`shippedAt`/`adminRemarks` | 未下发 | 适配层 `RawOrder` 标可选 + `?? ''/null` 兜底，页面显示"后端未下发"占位，不出现 `undefined` |
| 4 | 商品详情缺 `stock`/`categoryName`/`brandName`/`auditRecords`；`sales30d` 无数据源 | 未下发 | 对应展示位显示 `-`；成本价无 `cost:read` 时显示 `****`（后端已脱敏为 null） |
| 5 | `toAdminMap` 缺 SKU 级 `price`/`marketPrice` | 未下发 | 详情页经 `GET /api/v1/price/skus?ids=` 补价 |
| 6 | `PUT /api/admin/product/{id}` 为 **SKU 全量替换**（先删后插） | 后端既有语义 | 适配层「先取详情+现价再重组入参」，`skuCode` 保持不变；**副作用**：skuId 会变更、`costPrice` 未回填时会被清空 —— **需与后端书面确认**后再放开编辑入口 |
| 7 | `AdminDataController`（workbench/todo）为 **P0 stub**，恒 0 / 恒空 | stub | 看板空态已注明「P0 后端为 stub，示例数据仅 mock 环境可见」；⚠️ mock 返回 `gmv=128654300` 等美化数据，**mock 比真实更美化**，联调时勿据 mock 做视觉验收 |

> 以上 7 项为**后端待补**清单，联调时需后端先行实现或确认字段下发口径。

---

## 四、待联调验证项（mock 无法覆盖，需真实后端）

1. 登录 → 401 → 单飞行 refresh → 排队重放的真实时序（含 refresh 自身 401 时登出）。
2. `getMenus` 返回树为空/部分缺失时 `BasicLayout` 回退静态菜单的行为；确认真实后端下**「退款/售后」入口确实被隐藏**。
3. `IdempotentInterceptor` 在网关链路上是否对全部写路由生效（尤其 admin-bff 转发场景 header 是否透传）。
4. `updateProduct` 全量替换语义：未编辑 SKU 的价格/`costPrice` 是否会被覆盖/清空（§3-6）。
5. `listOrders` `orderNo` 精确匹配的用户体验（是否需要后端补 LIKE 查询）。
6. 系统日志 `keywords` 与时间范围互斥逻辑（keywords 优先）是否符合运营预期。
7. `ship`/`close` 在真实订单状态机下的非法流转错误码（4xxx）文案展示。实测：`ship`/`close` 非法流转/终态拦截均回 `4002`（HTTP 200）✅。
8. 工作台/待办真实值为 0（stub），确认页面能区分「真实为 0」与「加载失败」（已加 `QueryError` 错误态 + 重试）。

---

## 五、验证命令与结果

```bash
cd C:\D\agent\easy-shopping\web-admin
pnpm build && pnpm lint && pnpm test
```

- `pnpm build`：`tsc -b && vite build` 通过（无类型错误）。
- `pnpm lint`：通过（exit 0）。
  - 附带修复：`eslint.config.js` 的 Node 全局白名单原只覆盖 `mock/**/*.ts` 与 `vite.config.ts`，**遗漏 `scripts/**/*.mjs`**，
    导致 `scripts/live-check.mjs`（既有文件，Node CLI 脚本）报 18 个 `no-undef`/`no-unused-vars` 错误 —— 这是本表上一版「pnpm lint 通过（exit 0）」失真的原因之一。
- `pnpm test`：4 文件 18 用例全部通过（新增 `src/api/order.contract.test.ts` 3 例：售后三接口 URL + 特性开关开关两侧的菜单可见性）。
  `vite.config.ts` 的 `test` 块保留了 `hookTimeout/testTimeout = 120s` 与 `fileParallelism: false`（低配机器规避 vitest 内部 60s RPC fetch 超时，仅测试配置，不影响业务代码与产物）。

---

## 审查记录

### 2026-10-12 · 全表重建（P0-5 + P1 契约表修正）

- **P0-5 售后路径**：`api/order.ts` 三函数由 `/after-sale/*` 修正为 `/admin/after-sale/*`（原路径既不匹配 mock 路由 `mock/index.ts:645` 的 `/^\/api\/admin\/after-sale\/list$/`，也不匹配任何后端端点）。
- **P0-5 诚实下线**：新增 `AFTER_SALE_AVAILABLE`（口径同 `ORDER_REMARK_AVAILABLE`），菜单/后端菜单树/页面三层同时下线，替换原先文档里"入口已隐藏"的口头承诺（代码事实上并未隐藏）。
- **契约表重建**：以 `src/api/*` 实际导出（30 个）为基准逐函数重写 §2，新增「复核方式」列（后端文件:行号）。
- **静默失败治理**：新增 `src/components/QueryError.tsx`（整页 `Result` + 表格 `emptyText` 两种形态），接入订单/商品/用户/角色/字典/日志/工作台列表页与订单/商品详情页 —— 此前详情页在请求失败后会**永久停在骨架屏**，列表页则会显示**与「无数据」无法区分的空表格**。
- **顺带**：`types/api.ts` 删除 plans 旧口径的 `ApiResponse.message` 死字段，`request.ts` 同步清理两处 `body?.message` 死分支（后端 `ApiResponse` 只有 `msg`）；`roles.tsx` 的 `openDrawer` 补 catch（原先拉取已授权失败会开出一个"全未勾选"的抽屉，一保存即覆盖权限）。

#### 附 · 本表上一版（32 项）的 10 处实证错误 → 本版更正

| # | 上一版写法 | 实际 | 本版位置 |
|---|-----------|------|---------|
| 1 | `refreshToken` 响应 `{accessToken,refreshToken}` | 响应 `{accessToken,expiresIn,tokenType}`；refreshToken 走 httpOnly Cookie；且前端**无该导出**（内联在 `request.ts`） | §2.1 |
| 2 | `getCurrentUser GET /api/admin/auth/me` | 后端**无 `/me`**，只有 `/api/admin/auth/info` | §2.1 |
| 3 | `listProducts GET /api/admin/product/spu/list` | 实际 `/api/admin/product/list` | §2.2 |
| 4 | `getProductDetail GET /api/admin/product/spu/{id}` | 实际 `/api/admin/product/{id}` | §2.2 |
| 5 | `batchShelf .../spu/batch-shelf` + `batchOffShelf .../spu/batch-off-shelf` | 实际 `/admin/product/batch-on-shelf`/`batch-off-shelf`，且**只有一个 `batchShelf(ids, shelf)` 导出** | §2.2 |
| 6 | `getOrderDetail GET /api/admin/order/{orderNo}` | 实际 `{id}`（`@PathVariable orderId`），**代码对、文档错** | §2.3 |
| 7 | `shipOrder PUT .../ship`「✅ 一致」；`closeOrder PUT .../{orderNo}/close` | 前端**无 `shipOrder`**；`closeOrder` 是 `POST` + `{id}` | §2.3 |
| 8 | `listRefunds /api/admin/order/refund/list`；`handleRefund PUT .../{id}/{approve\|reject}`「✅ 一致」 | 两端**都不存在**；实际导出是 `listAfterSales/approveAfterSale/rejectAfterSale`，且后端未实现 | §2.3 |
| 9 | `listUsers GET /api/admin/system/user/list`；`getUserDetail /{id}`「✅ 一致」 | 实际 `/admin/system/user`（无 `/list`）；**无 `getUserDetail`**，后端亦无 `GET /{id}` | §2.4 |
| 10 | `listLogs GET /api/admin/system/log/list` | 实际 `/api/admin/system/operation-log` | §2.5 |

另修正两处统计失真：声明 32 项但 `src/api/*` 实际导出 30 个（§1）；§2.5 声称"字典/部门/角色/配置 CRUD ✅ 一致"，实测部门/配置后端齐全但**前端零调用**（§2.5 末尾 + §1 补充口径）。
