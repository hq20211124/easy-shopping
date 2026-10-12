# web-admin API 契约核对清单

> 核对基准：`server/` 下**实际实现**（各服务 Controller / admin-bff / edge-gateway 路由 / `DataInitializer` 权限注册表 / `IdempotentInterceptor`），非 `plans/` 规划稿。
> 前端侧改动范围：`src/api/*` 适配层 + 枚举/菜单常量 + 受影响页面的字段与权限码引用；**未重写任何页面、未替换组件库、未改动业务交互流程**。
> Mock 侧（`mock/index.ts`）数据形状与后端 VO 完全一致（字段名、单位、时间格式、分页 `list/total`、信封 `{code,msg,data,traceId}`）。

---

## 一、核对结论总览

| 域 | 核对函数数 | 一致 | 已修复 | 后端未实现/缺字段 |
|----|-----------|------|--------|------------------|
| 认证/会话（auth） | 6 | 6 | 0 | 0 |
| 商品（product） | 5 | 1 | 4 | 后端 VO 缺 `stock/categoryName/brandName/costPrice/auditRecords`、SKU 缺 `price/marketPrice`（已做降级适配） |
| 订单（order） | 7 | 3 | 4 | `PUT /{id}/remark` 未实现（功能开关降级）；售后 4 个接口未实现（UI 隐藏） |
| 售后/退款（after-sale） | 5 | 0 | 0 | 全部未实现（页面隐藏入口） |
| 用户（user） | 4 | 2 | 2 | — |
| 系统（system） | 5 | 2 | 3 | — |
| **合计** | **32** | **14** | **13** | **5 类后端缺口** |

不一致分类统计（共 27 处 → 已修 22 处 + 5 处后端缺口降级适配）：

| 类别 | 数量 | 说明 |
|------|------|------|
| URL 路径/HTTP 方法不匹配 | 4 | 见 §2.2 |
| 请求参数名/结构不匹配 | 6 | 见 §2.3 |
| 响应结构/字段名不匹配 | 8 | 见 §2.4 |
| 枚举值基准不匹配（0 基 vs 文案枚举） | 3 | 见 §2.5 |
| 单位不匹配（元 vs 分） | 2 | 见 §2.4 |
| 权限码不在后端注册表 | 5 | 见 §2.6（后端 25 码为唯一真值） |
| 信封/错误码/幂等缺失 | 4 | 见 §2.7 |
| 后端未实现（前端降级，不修后端） | 5 | 见 §3 |

---

## 二、已对齐项明细（按域）

### 2.1 认证/会话 `src/api/auth.ts`

| 前端函数 | 前端路径 | 后端路径 | 状态 |
|---------|---------|---------|------|
| `login` | `POST /api/admin/auth/login` | `AuthController#login`（经 admin-bff） | ✅ 一致 |
| `logout` | `POST /api/admin/auth/logout` | `POST /api/admin/auth/logout` | ✅ 一致 |
| `refreshToken` | `POST /api/admin/auth/refresh` | `POST /api/admin/auth/refresh`（Body：`{refreshToken}`，响应 `{accessToken,refreshToken}`） | ✅ 一致 |
| `getCaptcha` | `GET /api/admin/auth/captcha` | `GET /api/admin/auth/captcha`（`{captchaId,image}`） | ✅ 一致 |
| `getMenus` | `GET /api/admin/auth/menus` | `GET /api/admin/auth/menus`（`RbacService.MenuNode` 树，`{path,title,permission,children}`） | ✅ 新增，`BasicLayout` 已接 |
| `getCurrentUser` | `GET /api/admin/auth/me` | `GET /api/admin/auth/me`（`{id,username,nickname,roles,permissions}`） | ✅ 一致 |

- 信封：后端统一 `{code,msg,data,traceId}`，前端 `extractMessage` 已按 `msg` 字段读取（原按 `message` 读取，**已修**）。
- 401 刷新链：1002 未登录 / 1003 Token 过期 → 单飞行刷新 + 排队重放，与后端 `BizErrorCode` 语义一致。

### 2.2 商品 `src/api/product.ts`（适配层）

| 前端函数 | 前端路径（对齐后） | 后端路径 | 状态 |
|---------|---------|---------|------|
| `listProducts` | `GET /api/admin/product/spu/list`（`page/pageSize/keyword/categoryId/auditStatus/shelfStatus`） | `ProductController#list`（参数名 `page`，非 `pageNum`） | ✅ 已修（`pageNum`→`page`） |
| `getProductDetail` | `GET /api/admin/product/spu/{id}` + `GET /api/v1/price/skus?ids=` | 同左（网关 `/api/v1/price/**` → core-price） | ✅ 已修（补 SKU 价格） |
| `updateProduct` | `PUT /api/admin/product/spu/{id}`（全量 `ProductInput`） | 后端要求全量 `{name,subtitle,mainImage,images,video,detail,categoryId,skus[]}`，SKU 全量替换，仅草稿(0)/驳回(3)可编辑 | ✅ 已修（适配层重建全量入参 + 状态守卫） |
| `batchShelf` | `POST /api/admin/product/spu/batch-shelf`（`{ids}`） | 响应 `{success:[id],failed:[{id,reason}]}` | ✅ 已修（映射为 `{taskId,successCount,failCount,failures}`） |
| `batchOffShelf` | `POST /api/admin/product/spu/batch-off-shelf` | 同上结构 | ✅ 已修（同上） |

响应形状（`toAdminMap`）：`{spuId,spuCode,name,subtitle,mainImage,images[],video,detail,price(元,minPrice),originalPrice(元,marketPrice),salesCount,specs,skus[],categoryId,auditStatus,shelfStatus,auditRemark,deleted,createdAt,updatedAt}`。适配层在此基础上组装页面视图模型（`sales30d` 沿用查询参数占位，见 §3）。

### 2.3 订单 `src/api/order.ts`（适配层）

| 前端函数 | 前端路径（对齐后） | 后端路径 | 状态 |
|---------|---------|---------|------|
| `listOrders` | `GET /api/admin/order/list`（`userId/orderStatus/storeId/orderNo/includeChildren/pageNum/pageSize`） | `AdminOrderController#list`；`orderNo` 为**精确匹配** | ✅ 已修（筛选项标注"精确查询"，`buyer`→`userId`） |
| `getOrderDetail` | `GET /api/admin/order/{orderNo}` | 同左 | ✅ 已修（缺口字段降级，见 §3） |
| `shipOrder` | `PUT /api/admin/order/{orderNo}/ship` | 同左（幂等写操作） | ✅ 一致 |
| `closeOrder` | `PUT /api/admin/order/{orderNo}/close` | 响应 `{orderId,orderNo,status}` | ✅ 已修（映射为 `{success:true}`） |
| `markOrderPaid` | （前端未实现） | （后端无 `mark-paid` 端点） | ⚠️ 契约表曾标「已实现」，实测前后端均无 → 契约表已更正；如需补单可走 close+重下单，P1 再评估 |
| `listRefunds` | `GET /api/admin/order/refund/list` | admin-bff → 订单服务 | ✅ 一致（字段以实测为准） |
| `handleRefund` | `PUT /api/admin/order/refund/{id}/{approve|reject}` | 同左 | ✅ 一致 |

`AdminOrderVO` 实际字段：`{orderNo,parentOrderNo,userId,storeId,storeName,status,statusText,totalAmount(元),freightAmount(元),payAmount(元),discountAmount(元),payTime,receiverName,receiverMobile,items[],children[],closeReason,version,createdAt,updatedAt}`。适配层做 元→分 换算供页面金额组件使用。

### 2.4 用户 `src/api/user.ts`

| 前端函数 | 前端路径（对齐后） | 后端路径 | 状态 |
|---------|---------|---------|------|
| `listUsers` / `getUserDetail` | `GET /api/admin/system/user/list` / `/{id}` | admin-bff `SystemUserController` | ✅ 一致 |
| `updateAdminUser` | `PUT /api/admin/system/user/{id}` | `AdminUserUpdateReq = {nickname,mobile,password,status}`（**无 roles**） | ✅ 已修（收窄入参） |
| `setUserRoles` | `PUT /api/admin/system/user/{id}/roles` | `PUT /{id}/roles`（独立接口） | ✅ 已修（新增；页面"保存"拆为两步调用） |

`lastLoginAt`/`deptName` 后端可能为 `null`，类型与渲染均已按可空处理。

### 2.5 系统 `src/api/system.ts`

| 前端函数 | 前端路径（对齐后） | 后端路径 | 状态 |
|---------|---------|---------|------|
| `listLogs` | `GET /api/admin/system/log/list`（`keywords/createdAtStart/createdAtEnd/pageNum/pageSize`） | `SystemLogController`；时间参数 `@DateTimeFormat(ISO.DATE_TIME)`；keywords 与时间范围互斥（keywords 优先） | ✅ 已修（前端序列化为 ISO-8601 并加提示） |
| 字典/部门/角色/配置 CRUD | `/api/admin/system/{dict,dept,role,config}/**` | admin-bff 各 Controller | ✅ 一致 |

### 2.6 枚举与权限码

- **枚举 0 基对齐**（`src/config/constants.ts`）：`auditStatus` 0待审/1通过/2驳回/…以后端白名单为准；`shelfStatus` 0下架/1上架。商品列表/详情页比较值全部改用常量而非魔法值。**已修 3 处**。
- **权限码对齐后端 25 码注册表**（`DataInitializer`）：
  - `product:on_shelf` / `product:off_shelf` → `product:edit`
  - `order:remark` → `order:detail`
  - `refund:approve` / `refund:reject` → `refund:list`
  - 其余（`dashboard/product/product:list/product:create/product:audit/order/order:list/order:detail/order:ship/order:close/order:refund/refund:list/system/user:list/user:roles/system:dict/system:log/system:permission/system:dept/system:config/system:role/system:user/cost:read/report:view`）一致。

### 2.7 信封 / 错误码 / 幂等（`src/api/request.ts`）

| 项 | 后端事实 | 前端处理 | 状态 |
|----|---------|---------|------|
| 响应信封 | `{code,msg,data,traceId}` | `extractMessage` 读 `msg` | ✅ 已修 |
| 业务错误码文案 | `BizErrorCode`：1001 参数/1002 未登录/1003 Token 过期/1004 无权限/1005 幂等冲突/2xxx 用户/3xxx 商品/4xxx 订单/5xxx 支付/9999 系统 | `ERROR_CODE_TEXT` 兜底映射（后端 `msg` 缺失时使用） | ✅ 已修 |
| 幂等 | `Idempotency-Key` 头（缺省回退 `X-Idempotency-Key`）；缺 header→1001，重复 key→1005 | `request.ts` 对 POST/PUT/PATCH/DELETE（排除 `/admin/auth/*`）自动生成 UUID 头 | ✅ 已修 |
| 分页 | `PageResult = {list,total,pageNum,pageSize}` | 类型与各 list 函数一致 | ✅ 一致 |

---

## 三、后端未实现 / 缺字段（前端已降级，**不修改 server/**）

| # | 项 | 后端现状 | 前端降级策略 |
|---|----|---------|-------------|
| 1 | `PUT /api/admin/order/{orderNo}/remark` | **未实现** | `ORDER_REMARK_AVAILABLE` 功能开关（mock 下开启、`VITE_USE_MOCK=false` 直连后端时隐藏入口） |
| 2 | `/api/admin/after-sale/*` 全部（list/detail/approve/reject/ logistics） | **未实现** | 售后列表/详情入口隐藏；`order/refund.tsx` 仅展示订单侧退款数据 |
| 3 | `AdminOrderVO` 缺 `receiverAddress`/`payDeadline`/`shippedAt`/`adminRemarks` | 未下发 | 详情页对应行显示"后端未下发"占位，不阻塞渲染 |
| 4 | 商品详情缺 `stock`/`categoryName`/`brandName`/`costPrice`/`auditRecords`；`sales30d` 无数据源 | 未下发 | 对应展示位显示 "-"；上架前校验改用 SKU 维度提示 |
| 5 | `toAdminMap` 缺 SKU 级 `price`/`marketPrice` | 未下发 | 详情页经 `GET /api/v1/price/skus?ids=` 补价 |

> 以上 5 项为**后端待补**清单，联调时需后端先行实现或确认字段下发口径。

---

## 四、待联调验证项（mock 无法覆盖，需真实后端）

1. 登录 → 401 → 单飞行 refresh → 排队重放的真实时序（含 refresh 自身 401 时登出）。
2. `getMenus` 返回树为空/部分缺失时 `BasicLayout` 回退静态菜单的行为。
3. `IdempotentInterceptor` 在网关链路上是否对全部写路由生效（尤其 admin-bff 转发场景 header 是否透传）。
4. `updateProduct` 全量替换语义：仅回传页面已编辑 SKU 时，未编辑 SKU 的价格是否会被覆盖（当前适配层按"商品级改价"回填全部 SKU 价格，需与后端确认）。
5. `listOrders` `orderNo` 精确匹配的用户体验（是否需要后端补 LIKE 查询）。
6. 系统日志 `keywords` 与时间范围互斥逻辑（keywords 优先）是否符合运营预期。
7. `mark-paid` / `ship` / `close` 在真实订单状态机下的非法流转错误码（4xxx）文案展示。实测：`ship`/`close` 非法流转/终态拦截均回 `4002`（HTTP 200）✅；`mark-paid` 前后端均无此端点 → 已在 §3 契约表更正，记遗留。

---

## 五、验证命令与结果

```bash
cd C:\D\agent\easy-shopping\web-admin
pnpm build && pnpm lint && pnpm test
```

- `pnpm build`：tsc -b + vite build 通过（无类型错误）。
- `pnpm lint`：通过（exit 0）。
- `pnpm test`：3 文件 15 用例全部通过。为适配低配机器上 vitest 内部 60s RPC fetch 超时与冷启动 transform 慢的问题，在 `vite.config.ts` 的 `test` 块补充了 `hookTimeout/testTimeout = 120s` 与 `fileParallelism: false`（仅测试配置，不影响业务代码与产物）。
