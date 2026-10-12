# 活体联调验证报告（web-admin / web-buyer ↔ 真实后端）

> 时间：2026-10-12 · 环境：本机 12 服务（gateway :8080 + 10 个 core/admin 服务）
> 方式：`start-all.ps1` 起真实后端，两前端 `VITE_USE_MOCK=false`，请求经 vite dev proxy（:5173 / :5174）→ 网关 :8080，模拟前端真实请求头（Authorization / Idempotency-Key / Cookie）。
> 验证手段：HTTP 直调断言脚本 + 前端源码断言（浏览器工具未连接桌面应用，页面渲染改用源码断言 + HTTP 层验证，见 §4）。

## 0. 结论速览

| 端 | 项目 | 结果 |
|----|------|------|
| 后端基线 | `smoke.ps1` 冒烟 | ✅ 32 / 32 全绿 |
| 后端回归 | `mvn test` 全量 | ✅ `TEST_EXIT=0`（含新增雪花偶数 ID 断言） |
| web-buyer | 真实全链路 46 断言 | ✅ 46 / 46 全绿 |
| web-admin | 7 项待联调 40 断言 | ✅ 40 / 40 全绿（修复后） |

## 1. web-admin 7 项待联调逐项结果

脚本：`web-admin/scripts/live-check.mjs`（走 :5173 代理）。修复前基线 **29 通过 / 11 失败**，逐项修复后 **40 / 40 全绿**。

| 项 | 内容 | 修复前 | 修复后 | 说明 |
|----|------|--------|--------|------|
| ① | 401 → 单飞 refresh → 重放时序 | ✅ | ✅ | 无效 token 401/1003；ADMIN_RT Cookie 换新 token；轮换 Cookie 下发；重放 200；并发 refresh 不 5xx；refresh 自身 401/1003 |
| ② | getMenus 空树回退静态菜单 | ✅ | ✅ | 真实菜单树 len=4；`remoteMenus?.length ? … : MENUS` 回退；`retry:false` 防死循环 |
| ③ | 幂等头经网关透传（审核/发货） | ✅ | ✅ | 同键重复审核/发货 → 409/1005；写接口缺幂等键 → 1001 |
| ④ | updateProduct SKU 全量替换不覆盖价格 | ❌ 4 | ✅ | ④-a/b/c/e 转 PASS（见修复 #4/#5） |
| ⑤ | listOrders orderNo 精确匹配 | ✅ | ✅ | 精确命中 1、前缀/改末位 0；前端标注「精确查询」（⑤-c 首轮失败为脚本数据碰撞，已修） |
| ⑥ | 操作日志 关键字与时间互斥 | ❌ 3 | ✅ | ⑥-a/b/f 转 PASS（见修复 #2/#3） |
| ⑦ | 订单状态机非法流转错误码展示 | ❌ 4 | ✅ | ⑦-a/d/e 转 PASS（见修复 #1）；⑦-f 改为「前后端一致缺失」契约适配 |

### ⑦ 状态机细节（修复后）
- 待付款(0) 直接发货 → **4002**（HTTP 200）✅
- 终态(已关闭 7) 再关单 → **4002**（终态拦截）✅
- 已发货(2) 关单 → **4002**（白名单外流转）✅
- 待付款(0) 关闭合法 → 0（对照组）✅
- 前端 `ERROR_CODE_TEXT[4002]='订单状态错误'` + `extractMessage` 后端 msg 优先 ✅

## 2. web-buyer 真实全链路结果

脚本：`web-buyer/scripts/real-chain-check.mjs`（走 :5174 代理）。**46 / 46 全绿**，覆盖：

注册 → 登录 → 搜索 → 详情 → 加购 → 幂等（重复加购 1005/409）→ 下单（status=0 + `pay_deadline` + 金额自洽）→ 重复下单 409/1005 不产生第二单 → prepay → mock pay → status=1 → 领券 → 试算（eligible）→ 用券下单（discount=10、实付=总额+运费−减免）→ 库存不足 3002 → 地址/购物车/订单列表。

雪花偶数 ID 修复已在新数据上生效（orderId / couponRecordId 均为偶数，JS `JSON.parse` 无损）。

## 3. 修复清单（最小化，不动业务语义）

### 后端 `server/`
1. **admin-bff 掩码下游业务码**（`admin-bff/client/RemoteSupport.java#unwrap`）：原将下游非 0 码统一包成 `SYSTEM_ERROR(9999)`，致 core-order 的 4002 变 9999/HTTP 500，违反契约「非法流转回 4002/HTTP 200」。改为 `BizErrorCode.fromCode()` 保留下游原码，未知码仍兜底 9999。
   - 配套：`common/api/BizErrorCode.java` 新增 `fromCode(int)` 反查（纯新增，不影响现有路径）。
2. （前置）`common/id/SnowflakeIdGenerator.java` 序列左移 1bit → ID 恒偶数，规避 JS `JSON.parse` 对 2^53~2^54 奇数精度丢失（cart update 3004 / order detail 4001 实测缺陷）。`SnowflakeIdGeneratorTest` 增 `id % 2 == 0` 断言。

### 前端 `web-admin/`
3. **商品列表字段映射**（`src/api/product.ts#toListItem`）：列表接口返回 `id/minPrice/marketPrice`、详情返回 `spuId/price/originalPrice`，原 `toListItem` 只读后者 → 真实模式列表 `id=undefined`、价格 0。改为两路兼容（`spuId ?? id`、`price ?? minPrice`、`originalPrice ?? marketPrice`）。
4. **商品级改价抬 marketPrice**（`src/api/product.ts#updateProduct`）：core-price 约束 `marketPrice ≥ price`，否则整批同步被拒且 `syncSkuPrices` fail-safe 仅告警（静默丢价）。改高价时 `marketPrice: Math.max(current?.marketPrice ?? price, price)`。
5. **日志时间序列化补时区**（`src/api/system.ts#toIsoDateTime`）：后端 `@DateTimeFormat(ISO.DATE_TIME) Instant` 要求带时区，原产出 `YYYY-MM-DDTHH:mm:ss`（无时区）→ 1001，时间筛选真实模式失效。补本地时区偏移（`+08:00` 等）。
6. **日志页互斥提示**（`src/pages/system/log.tsx`）：补「关键字与时间范围互斥（关键字优先）」文案，对齐后端 P0 二选一限制。

### 契约/脚本
7. **契约表更正**（`web-admin/docs/api-contract-check.md`）：`markOrderPaid` 原标「✅ 一致」，实测前后端均无 `mark-paid` 端点/调用 → 改为「前后端一致缺失，记遗留」。
8. **联调脚本修正**（`web-admin/scripts/live-check.mjs`）：④ 价格接口补 `/api` 前缀、⑥ 时间补时区、⑤-c 改末位避免与原末位碰撞、④-e marketPrice 与前端一致、⑦-f 改为「前后端一致缺失」断言。
9. **vite 配置**（两前端 `vite.config.ts`）：`process.env` 读不到 `.env.development`，改用 `loadEnv(mode, cwd, '')`，否则 `VITE_USE_MOCK=false` 时 mock 插件不卸载、proxy 不挂载（实测缺陷）。

## 4. 渲染验证说明

浏览器工具未连接桌面应用（`browser.disconnected`），无法截图。改用：
- **HTTP 层**：所有业务断言经 :5173 / :5174 dev proxy，请求头与浏览器一致（Bearer / Idempotency-Key / Cookie），等价验证前端 API 层与后端契约。
- **源码断言**：② 菜单回退表达式、⑤ 「精确查询」标注、⑥ `toIsoDateTime` + 互斥提示、⑦ `ERROR_CODE_TEXT[4002]` + `extractMessage`，均直接断言前端源码，覆盖「渲染所需逻辑」。

## 5. 遗留项

| 项 | 说明 | 建议 |
|----|------|------|
| `mark-paid` 端点 | 契约表曾标已实现，实测前后端均无。已更正契约表为「一致缺失」，联调脚本⑦-f 改为校验「无孤儿端点/调用」 | P1 评估是否补「补单支付」能力；P0 可用 close + 重下单替代 |
| 页面视觉渲染 | 浏览器工具未连接，未截图确认像素级渲染 | 接入桌面应用后复核登录页/工作台/订单列表视觉 |

## 6. 用户切换真实模式的操作步骤

> 联调收尾已将两前端 `.env.development` 的 `VITE_USE_MOCK` 恢复为 `true`（默认走 mock）。切真实模式步骤如下。

1. **起后端**（12 服务，含 gateway）：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\server\scripts\start-all.ps1 -ReadyTimeoutSec 240
   ```
   > 注：11 个 Spring Boot 服务冷启约 2~4 分钟；脚本会等每个服务 `/health` 就绪。若 shell 有超时，建议用脱离进程方式启动（`Start-Process powershell …`）再轮询端口。
2. **冒烟确认**：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\server\scripts\smoke.ps1
   ```
3. **前端切真实模式**：把 `web-admin/.env.development` 与 `web-buyer/.env.development` 的 `VITE_USE_MOCK=false`（vite 会自动重启并挂 proxy、卸 mock）。
4. **访问**：web-admin `http://localhost:5173`、web-buyer `http://localhost:5174`。
5. **联调断言脚本**（可选）：
   ```powershell
   node web-admin\scripts\live-check.mjs
   node web-buyer\scripts\real-chain-check.mjs
   ```
6. **收尾停服务**：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\server\scripts\stop-all.ps1
   ```
7. **恢复 mock**：把两前端 `VITE_USE_MOCK` 改回 `true`。

### 测试账号（数据重置后需重建）
- 管理员：直连 `http://127.0.0.1:8081/api/v1/internal/users` 建 `roles=ADMIN` 账号（无对外注册口）。
- 买家：`http://127.0.0.1:8080/api/v1/auth/register`，短信码固定 `123456`。
- 库存账本：直连 `http://127.0.0.1:8084/api/v1/internal/inventory/stocks`。
