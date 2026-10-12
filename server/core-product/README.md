# core-product :8082 —— 商品域 P0 实现说明

> 实现范围：类目树 CRUD（管理端）、SPU/SKU 实体与 CRUD、审核状态机、上下架、回收站/草稿（P0 简化）、
> 买家端商品查询、服务间批量查询。设计出处：plans/admin-backend.md 3.x、plans/android-backend.md 三、
> plans/ios-backend.md 三。构建/测试：`mvn -q -pl core-product test`（mem H2）。

## 1. 端点清单（集成代理核对用）

### 管理端 `/api/admin/**`（需网关注入 `X-User-Id` + `X-User-Roles` 含 `ROLE_ADMIN`，否则 403/1004）

| 方法 | 路径 | 说明 | 幂等键 |
|------|------|------|--------|
| GET | `/api/admin/category/tree` | 类目树 | — |
| GET | `/api/admin/category/list?page=&pageSize=` | 类目分页 | — |
| POST | `/api/admin/category` | 创建类目 `{name, parentId?, sort?}` | ✅ |
| PUT | `/api/admin/category/{id}` | 更新类目 `{name?, sort?}` | ✅ |
| DELETE | `/api/admin/category/{id}` | 删除类目（软删；有子类目/商品引用 → 1001） | ✅ |
| PUT | `/api/admin/category/sort` | 批量排序 `[{id, sort}]` | ✅ |
| PUT | `/api/admin/category/{id}/status` | 启用/禁用 `{status: 0/1}` | ✅ |
| GET | `/api/admin/product/list?keyword=&categoryId=&auditStatus=&shelfStatus=&page=&pageSize=` | 商品分页 | — |
| GET | `/api/admin/product/{id}` | 详情（SPU+SKU，`costPrice` 仅 `cost:read` 角色可见，否则 null） | — |
| POST | `/api/admin/product` | 创建商品（**P0：创建即草稿**）| ✅ |
| PUT | `/api/admin/product/{id}` | 编辑（仅 draft/rejected；SKU 全量替换） | ✅ |
| DELETE | `/api/admin/product/{id}` | 软删除→回收站（auditing 禁删 → 1001） | ✅ |
| POST | `/api/admin/product/{id}/restore` | 回收站恢复（恢复后强制下架） | ✅ |
| DELETE | `/api/admin/product/{id}/permanent` | 彻底删除（SPU+SKU 物理删除） | ✅ |
| POST | `/api/admin/product/{id}/submit-audit` | 提交审核 draft/rejected → auditing | ✅ |
| POST | `/api/admin/product/{id}/approve` | 审核通过 auditing → on_shelf **+ 自动上架** | ✅ |
| POST | `/api/admin/product/{id}/reject` | 驳回 auditing → rejected `{reason}` 必填 | ✅ |
| POST | `/api/admin/product/{id}/return` | 退回修改 auditing → draft `{reason}` 必填 | ✅ |
| POST | `/api/admin/product/{id}/on-shelf` | 上架（**前置条件 auditStatus=2**） | ✅ |
| POST | `/api/admin/product/{id}/off-shelf` | 下架（**不回退审核状态**） | ✅ |
| POST | `/api/admin/product/batch-on-shelf` | 批量上架 `{ids:[]}` ≤500（P0 同步） | ✅ |
| POST | `/api/admin/product/batch-off-shelf` | 批量下架 `{ids:[]}` ≤500 | ✅ |

**创建/编辑商品入参**（`ProductInput`）：
```json
{ "name": "...", "subtitle": "...", "categoryId": 123, "mainImage": "url", "images": "url1,url2",
  "video": "url", "detail": "<p>富文本</p>",
  "skus": [ { "skuCode": null, "title": null, "spec": {"颜色":"黑","容量":"256GB"},
              "images": "url", "price": "7999.00", "marketPrice": "8999.00", "costPrice": "7000.00" } ] }
```
- `price/marketPrice` 在创建/编辑时经 HTTP 同步到 core-price（`POST /api/v1/internal/price/skus`，容错降级）。
- SPU 上的 `minPrice/marketPrice` 仅为**展示冗余**（min=SKU 最低价，market=SKU 划线价最大值）。

### 买家端（无鉴权；响应统一信封 `{code,msg,data,traceId}`，字段 camelCase）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/products?categoryId=&keyword=&sort=&priceMin=&priceMax=&page=&pageSize=` | 列表分页筛选；sort: default/price_asc/price_desc/sales/newest；data=`{total,pageNum,pageSize,list:[{spuId,spuCode,name,subtitle,mainImage,price,originalPrice,salesCount,skuCount}]}` |
| GET | `/api/v1/products/category/{categoryId}?...` | 按类目列表（android 3.1.2，参数同上） |
| GET | `/api/v1/products/{spuId}` | 详情：`{spuId,spuCode,name,subtitle,mainImage,images[],video,detail,price,originalPrice,salesCount,specs:[{name,values[]}],skus:[{skuId,skuCode,title,specs{},images[],status,price}]}`；不存在→**3001**，未上架/已删→**3003** |

> 详情中 SKU `price` 由本服务调 core-price `GET /api/v1/price/skus` 回填；价格服务不可用时为 null（降级）。

### 服务间内部契约（不对外，无鉴权；P0 信任内网）

| 方法 | 路径 | 响应 data |
|------|------|-----------|
| GET | `/api/v1/internal/skus?ids=1,2` | `[{skuId,spuId,title,spec,images,status}]`（spec 为 JSON 字符串；缺失 id 不返回） |
| GET | `/api/v1/internal/products?page=&pageSize=` | `{total,pageNum,pageSize,list:[{spuId,name,mainImage,categoryId,minPrice,marketPrice,salesCount,shelfStatus}]}`（core-search 同步源；含下架行，消费者自行过滤 shelfStatus=1） |

## 2. 审核状态机（唯一定义：`com.product.domain.AuditStateMachine`）

```
draft(0) ──提交──► auditing(1) ──通过──► on_shelf(2)（审核终态，下架只改 shelfStatus）
   ▲                  ├─驳回(reason 必填)──► rejected(3) ──重提──► auditing(1)
   └──退回修改(reason 必填)──┘
```
- 非法流转（含 draft 直接上架、on_shelf 再提交）→ **BizException(1001)** + 明确文案。
- 并发：SPU `@Version` 乐观锁；冲突 → **1005**「该商品已被处理，请刷新后重试」。
- 上架前置 = `auditStatus=2`；下架不回退审核状态（plans/admin-backend.md 3.3.2）。

## 3. 事件（DomainEventNames 注册表；事务提交后发布）

| 时机 | 事件 | payload |
|------|------|---------|
| 创建 | `product.created` | `{spuId, categoryId, changeType:"create"}` |
| 编辑 | `product.updated` | `{spuId, changeType:"update"}` |
| 审核通过 | `product.updated` + `audit.completed` | `{changeType:"audit_approved", shelfStatus:1}` / `{bizType:1, result:"approved"}` |
| 驳回 | `audit.completed` | `{bizType:1, result:"rejected", reason}` |
| 退回 | `audit.returned` | `{bizType:1, reason}` |
| 提交审核 | `audit.submitted` | `{bizType:1, operator}` |
| 上/下架 | `product.updated` | `{changeType:"on_shelf"/"off_shelf", shelfStatus}` |
| 删除 | `product.deleted` | `{spuId[, changeType:"permanent"]}` |

> ⚠️ plans 3.3.2 提到的 `product.on_shelf` 事件**未在 DomainEventNames 注册表登记**，P0 由
> `product.updated(changeType=audit_approved/on_shelf)` 承载；如需专用事件须先登记 04-messaging §7.1。

## 4. 给集成代理的注意事项

1. **幂等键**：所有写接口必带 `Idempotency-Key`（或 `X-Idempotency-Key`），缺失 → 1001（common 拦截器强制）。
2. **管理端角色**：`/api/admin/**` 校验 `X-User-Roles` 含 `ROLE_ADMIN`（admin-bff 登录占位即发该角色）；
   `costPrice` 脱敏需额外 `cost:read` 角色。
3. **错误码映射**：3001 商品不存在、3003 未上架/失效、1001 参数/非法流转、1005 并发冲突、1004 无权限。
4. 网关若走 `/api/admin/**` 需确认 gateway 路由表已覆盖（本模块未改 gateway）。
5. 买家端响应字段为 **camelCase**（骨架信封口径）；ios 侧 snake_case 由 BFF/前端映射。
6. 配置开关：`app.product.price-client.enabled`（默认 true）——false 时不同步/回填价格（测试用）。
