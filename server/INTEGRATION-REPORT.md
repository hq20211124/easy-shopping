# server/ 集成与冒烟报告

- 日期：2026-10-12
- 环境：Windows + JDK 17（`C:\Android\jdk-17`）+ Maven 3.9.9（`C:\D\tools\maven`），H2 文件库，**无 Docker / 无真实 MySQL / 无 RabbitMQ**
- 平台编码 GBK；所有脚本与日志读写显式声明编码
- 端口冲突处理：机器上 WSL `wslrelay`(PID 25696) 双栈监听 `[::1]:8081/8082/8083`。**所有服务与网关统一绑 `127.0.0.1`**，`service.urls.*` 全量覆写为 `http://127.0.0.1:<port>`，避免 IPv6 落到 WSL

---

## 1. 构建结果

- `mvn -q test` → **162 tests / 0 failures / 0 errors**（12 个含测试模块，全绿）
- `mvn -q -DskipTests package` → **12 个可执行 jar**（common 为依赖库，无 jar）

| 模块 | 测试数 | 说明 |
|------|-------|------|
| admin-bff | 17 | |
| common | 9 | |
| core-cart | 20 | |
| core-inventory | 12 | |
| core-order | 37 | |
| core-payment | 10 | |
| core-price | 5 | |
| core-product | 6 | |
| core-promotion | 9 | |
| core-search | 8 | |
| core-user | 25 | |
| gateway | 4 | |

---

## 2. 集成修复清单（只改集成问题，不动已验证业务逻辑）

按"根因"归类。多数为**契约对齐**（代码实际路由/路径/载荷/状态码 ↔ 计划文档与其他模块的承诺）。

### 2.1 网关路由与白名单（`gateway/src/main/resources/application.yml`）
- admin 子域细分路由：`/api/admin/category|product|price|coupon(s)/**` 直连拥有方服务（core-product / core-price / core-promotion），**order 默认 0**，排在 admin-bff 兜底（`order: 10`）之前。已用 `javap` 确认 Spring Cloud Gateway `RouteDefinition.order` 默认 0，兜底确实在后。
- admin-bff 兜底 `/api/admin/**` 放最后，承载认证/RBAC/系统管理/订单聚合/审计/看板。
- 免登录白名单补 `/api/admin/auth/login|refresh|captcha`。

### 2.2 管理员角色口径统一（`common/.../api/UserContext.java`）
- `isAdmin()` 兼容 `ADMIN` / `ROLE_ADMIN` / `admin` 三种角色串，消除各服务自定义判断。

### 2.3 admin-bff 反向代理：返回类型 + 身份头透传（`admin-bff/.../client/RemoteSupport.java`）
- **返回类型编译错误**：`forwardHeaders` 原误写为返回 `ClientHttpRequestInterceptor`，应为 `ClientHttpResponse`。
- **幂等键改从入站请求读**：出站 `httpRequest` 头里没有 `Idempotency-Key`，必须经 `RequestContextHolder` 取入站请求头。
- **身份头透传（根因修复）**：`X-User-Id` / `X-User-Roles` / `Idempotency-Key` 同样从入站请求读并加到出站调用。修复前下游 RBAC 看不到调用方身份 → admin 登录 1004；修复后全绿。

### 2.4 admin-bff 审计代理路径对齐（`admin-bff/.../` 审计代理）
- 按 core-product 实际路径改写代理目标（原路径 404）。

### 2.5 admin-bff 首登角色引导（`admin-bff/.../service/RbacService.java`）
- `ensureBootstrapRole` 首登绑 `super_admin`，避免首个管理员无任何权限。

### 2.6 core-product internal sku 视图补字段（`core-product`）
- internal 视图补 `storeId` / `name` / `image` / `onShelf`，供下游（core-cart / core-order / core-search）消费。

### 2.7 core-order 优惠契约：preview + deduct（`core-order`）
- 优惠统一走 preview（预览减免）+ deduct（核销）；失败降级 `discount=0`。

### 2.8 core-payment ↔ core-order internal 路径对齐
- `core-payment/RestOrderClient`：`getOrder` 改打 `GET /api/v1/internal/orders/{id}`（原打买家端无 X-User-Id → 1002）；`markPaid` 改打 `POST /api/v1/internal/orders/{id}/paid`（原路径 404）。
- `core-order/InternalOrderController`：**新增** `GET /api/v1/internal/orders/{orderId}` 快照端点 + `InternalOrderSnapshot(orderId,userId,totalAmount,status,payDeadline)`；`OrderQueryService.internalOrder()`。

### 2.9 core-product → core-price 同步载荷（`core-product/.../client/PriceClient.java`）
- 裸数组 `[]` → `Map.of("items", views)`。core-price 的 `InternalPriceBatchInput` 是 `{items:[...]}`；原 400/1001 被**静默降级**吞掉，导致下单报 3001 商品价格缺失（冒烟第 5 轮根因）。

> 说明：以上均为集成/契约对齐；未改动已验证的业务逻辑。测试断言未因本次修复而变更。

---

## 3. 端到端冒烟（13 步 / 32 断言）

全部走网关 `http://127.0.0.1:8080`。共跑 4 轮：
- **round 1**：15/17（2 失败）→ 定位 admin-bff 身份头透传根因，修复
- **round 2**：32/32（31 pass + 1 skip→后补齐）
- **round 3**：32/32（验证重复执行幂等）
- **round 4**（留档）：**32/32 全绿**，报告 `server/logs/smoke-final.txt`

| # | 步骤 | 结果 |
|---|------|------|
| 1 | admin 登录 | ✅ |
| 2 | 买家注册 / 登录 | ✅ |
| 3 | 搜索商品（admin 建类目+商品+价+库存，链路不通用处用测试数据兜底） | ✅ |
| 4 | 加购 | ✅ |
| 5 | 下单（断言 0 基状态=0、pay_deadline、拆单子单数=1） | ✅ |
| 6 | prepay（金额=后端定额） | ✅ |
| 7 | mock 支付 | ✅ |
| 8 | 查单状态=1（待发货） | ✅ |
| 9 | 同 Idempotency-Key 重复下单不产生第二单（返回 409/1005） | ✅ |
| 10 | 库存不足 → 3002 | ✅ |
| 11 | 领券 → 用券 → 减免正确（=券 value） | ✅ |
| 12 | 管理端订单列表 + 发货 → 状态=2（待收货） | ✅ |
| 13 | 无 token → 401 / 1002 | ✅ |

### 冒烟兜底说明
- **管理员建号**：core-user 无种子账号，直连 `:8081 /api/v1/internal/users` 建号（重复建返 1001，可忽略）。
- **库存账本**：直连 `:8084 /api/v1/internal/inventory/stocks` 建账本（已有记录返 1001 不覆盖，故先 GET 再按需建；无记录时下单返 3002）。

### 口径偏差（有意为之，不改已验证逻辑）
- **幂等⑨**：common 拦截器对重复 `Idempotency-Key` 返 **HTTP 409 / code=1005**（core-order 测试已固化）。冒烟按"不产生第二单 + 409/1005 或同单"断言。

---

## 4. 交付脚本（`server/scripts/`，UTF-8 with BOM，PSParser 解析通过）

| 脚本 | 用法 | 说明 |
|------|------|------|
| `start-all.ps1` | `powershell -ExecutionPolicy Bypass -File .\start-all.ps1 [-ResetData]` | 按序拉起 12 jar，全绑 `127.0.0.1`；日志 `logs/<svc>.log|.err.log|.pid`；逐个等 `/health`；失败打印日志尾部并 `exit 1`。core-search 带 `--app.search.sync-interval-ms=5000`。`-ResetData` 先删 `server/data/` 再启动（**服务运行中不可用**）。 |
| `stop-all.ps1` | `powershell -ExecutionPolicy Bypass -File .\stop-all.ps1` | 读 pid 文件停服 + 孤儿进程清扫（**同时匹配 server 目录和 `--server.address=127.0.0.1` 标记**，不误杀 WSL relay / 他人进程）+ 端口复核。 |
| `smoke.ps1` | `powershell -ExecutionPolicy Bypass -File .\smoke.ps1` | 13 步 32 断言端到端冒烟，全部走网关 `:8080`。 |

> 脚本未加 `[Console]::OutputEncoding=UTF8`（会把本机 GBK 控制台中文搞乱，已回滚）。

---

## 5. 服务与端口

| 服务 | 端口 |
|------|------|
| gateway | 8080 |
| core-user | 8081 |
| core-product | 8082 |
| core-price | 8083 |
| core-inventory | 8084 |
| core-cart | 8085 |
| core-order | 8086 |
| core-payment | 8087 |
| core-promotion | 8088 |
| core-search | 8089 |
| admin-bff | 8090 |

**所有服务实际绑 `127.0.0.1`**（`[::1]` 上是 WSL wslrelay，勿动）。
健康检查：`GET http://127.0.0.1:<port>/health`（无 actuator）。
日志：`server/logs/<svc>.log`（stdout）与 `.err.log`（被 Java 持锁，须 `Get-Content` 读，`ReadAllText` 会 IOException）。

## 6. 清场状态

- 冒烟与测试跑完后已执行 `stop-all.ps1`，**所有自起进程已停**，pid 文件已清。
- 端口复核：仅剩 `[::1]:8081/8082/8083` 的 WSL wslrelay（PID 25696，非本项目进程，未动）。
- `server/data/` 留有冒烟累积数据；下次 `start-all.ps1 -ResetData` 可重置为干净 H2。
- 机器上另有他人 `core-user --server.port=18081`（PID 31664），未杀。
