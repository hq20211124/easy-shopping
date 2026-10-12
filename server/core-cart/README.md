# core-cart 购物车服务

服务端购物车：加购合并、修改/勾选/删除、三源聚合列表（商品 + 价格 + 库存）、
失效标记回写。对应 `plans/android-backend.md` 第七章与 16.4 P0 缺口清单、
`plans/ios-backend.md` 4.2.1（接口）+ 4.3（`shopping_cart` DDL）。

- 端口 `8085`，P0 H2 文件库 `./data/core-cart`
- 雪花 `app.worker-id: 5`
- P0 **无购物车域事件**（`DomainEventNames` 无 `cart.*` 常量，不自造事件名，DEV.md 4.4）

## 1. 接口契约（双路径，同一 handler）

| 动作 | POST 动作式（android 1.6） | RESTful（ios 4.2.1） | 幂等 |
|------|---------------------------|----------------------|------|
| 列表 | `GET /api/v1/cart/list` | `GET /api/v1/cart` | GET，无幂等头 |
| 加入 | `POST /api/v1/cart/add` | `POST /api/v1/cart`、`POST /api/v1/cart/items` | `@Idempotent` |
| 修改 | `POST /api/v1/cart/update`（body 带 `cartItemId`） | `PUT /api/v1/cart/{cartItemId}`、`PUT /api/v1/cart/items/{cartItemId}` | `@Idempotent` |
| 删除 | `POST /api/v1/cart/remove`（`cartItemIds[]` 批量） | `DELETE /api/v1/cart/{cartItemId}`、`DELETE /api/v1/cart/items/{cartItemId}` | `@Idempotent` |
| 勾选 | `POST /api/v1/cart/select` | –（同上） | `@Idempotent` |
| 健康 | `GET /health` | – | – |

- 写接口必带 `Idempotency-Key`（缺 → `1001`，重复 → `1005`/HTTP 409；键作用域 `METHOD:URI:key`）；
- 需登录：`X-User-Id` 缺失 → `1002`（HTTP 401）；
- **以 `cartItemId` 定位行，禁止用 `skuId`**（android-backend 16.4：同 SKU 多规格需区分），
  跨用户访问一律 `3004`（不泄露他人行是否存在）。

### 响应要点

```jsonc
// GET /api/v1/cart/list
{ "code": 0, "data": {
    "items": [{"cartItemId":..., "productId":..., "skuId":..., "title":..., "mainImage":...,
                "price":..., "quantity":..., "stock":..., "inStock":...,
                "selected":..., "invalid":..., "discountTags": []}],
    "totalQuantity": 5,      // 车内总件数（含失效行）
    "selectedQuantity": 3,   // 已勾选且未失效
    "maxItems": 200,         // 服务端下发，前端禁止硬编码（ios-frontend 7.2）
    "totalAmount": ..., "discountAmount": 0, "payableAmount": ...
}}
```

## 2. 数据模型（`shopping_cart`）

- 雪花主键 `id` + 对外 `cart_item_id`（双 ID，裁决 C13 禁 AUTO_INCREMENT）；
- `uk_user_sku` 唯一约束：**同用户同 SKU 只有一行**；
- `selected` 默认 1、`invalid` 默认 0；数量 `Integer`、金额不落车（结算以 core-price 权威计价，客户端不可自证）。

## 3. 关键行为

**加购合并 + 唯一键兜底**：同 SKU 先查后累加（`incrementQuantityCapped` 带上限条件的条件 UPDATE）；
并发首次插入撞 `uk_user_sku` → 捕获 `DataIntegrityViolationException` 转累加（重试一次）→
**幂等重放 / 并发重复加购 0 重复行**。

**失效标记（回写式）**：列表链路按 core-product 状态判定
- SKU 查不到（3001 语义）或 `onShelf=false`（3003 语义）→ `invalid=true` 并**落库**（下次直读）；
- 失效行**仍返回**（用户可见可删），不计入 `selectedQuantity` 与金额，但计入 `totalQuantity`；
- 商品恢复上架 → 自动回写 `invalid=false`（可恢复）；
- 加购时**只校验在架**（3001/3003 拒绝），**不查库存**（TOCTOU 禁止：列表取库存仅展示，
  强校验在下单时由 core-inventory reserve 返回 3002 裁决）。

**容量上限**（服务端下发/校验）：`app.cart.max-items`（默认 200，行数上限，已存在的 SKU 合并不占新行）、
`app.cart.max-quantity`（默认 999，单行上限，累加超限返回 `1001` 而非静默截断）。

**勾选**：`cartItemIds` 缺省/空 = 全选或取消全选，否则只改指定行（单选/多选同接口）。

**金额**：`price × quantity` 汇总，**P0 促销未接入 → `discountAmount` 恒 0**
（与 core-order 下单同口径，TODO 待 core-promotion 就绪）。

## 4. 跨服务依赖（测试中全部 `@MockBean`）

| 客户端 | 契约 | 用途 |
|--------|------|------|
| `ProductClient` | `GET /api/v1/internal/skus?ids=` | 标题/主图/在架状态/SPU 解析 |
| `PriceClient` | `GET /api/v1/price/skus?ids=` | 展示价（结算以权威计价为准） |
| `InventoryClient` | `GET /api/v1/inventory/stocks?skuIds=` | 展示库存与 `inStock` |

## 5. 配置

```yaml
app:
  worker-id: 5
  cart:
    max-items: 200
    max-quantity: 999
```

## 6. 测试

`C:\D\tools\maven\bin\mvn.cmd -q -pl core-cart test`

| 类 | 覆盖 |
|----|------|
| `CartCrudTest`（9） | 加购合并 0 重复行、列表三源聚合与金额、POST/PUT 双路径改数量与勾选、全选/单选、DELETE 与批量删除、**跨用户 3004 IDOR 防护**、max-items 上限、单行数量上限、未登录 1002 |
| `CartIdempotencyTest`（4） | **幂等键去重（要求④）** 同键重复加购 409/1005 只写一次、缺键 1001（POST/DELETE）、键作用域按 URI 隔离、update/remove 同样受保护 |
| `CartInvalidMarkingTest`（6） | 下架/删品 → `invalid=true` 回写、不计金额与勾选、恢复上架自动恢复、加购 3001/3003 拒绝、失效行可删除、空车汇总 |
| `web/HealthControllerTest`（1） | 健康检查信封 |

测试基类 `AbstractCartTest`：mem H2 唯一库名 + `ddl-auto=create-drop`；
下游三客户端以可变目录 Map 打桩（便于用例中途「下架 / 删品」）；
`@BeforeEach` 反射清空 `IdempotentInterceptor.seen`（幂等 Map 跨用例存续，不清会撞键）。

## 7. 与 plans DDL 的差异 / TODO

- **主键雪花**（裁决 C13：分库分表禁止 `AUTO_INCREMENT`），覆盖 plans/ios-backend 4.3 的 `id BIGINT AUTO_INCREMENT`；
- 唯一键收窄为 `uk_user_sku(user_id, sku_id)`：plans 原为 `uk_user_product_sku(user_id, product_id, sku_id)`，
  `product_id` 由 SKU 派生、冗余存储即可，不必进唯一键；
- **新增 `invalid` 列**（plans 无）：失效标记需落库才能「下次直读不重复判定」；
- 金额不落车（车表本就不存价格；结算以 core-price 权威计价，客户端不可自证金额）；
- 促销 `discountTags` / `discountAmount` P0 恒空、0（待 core-promotion）；
- 购物车域事件未发（事件注册表无 `cart.*` 常量，登记后补）；
- 批量勾选/批量加购（`POST /cart/batch`）与跨端同步推送未在 P0 范围。
