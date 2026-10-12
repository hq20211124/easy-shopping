# web-admin · 电商平台管理端前端

对齐 `plans/admin-frontend.md`（实施手册）与 `plans/admin-backend.md`（接口契约）的 P0 可运行骨架。

## 技术栈

Vite 6 + React 19 + TypeScript（strict）+ Ant Design 5（`@ant-design/v5-patch-for-react-19`）+ React Router v7（data router）
+ TanStack Query（服务端态）+ zustand（全局轻状态）+ axios（统一拦截）+ Vitest

## 快速开始

```bash
pnpm install
pnpm dev        # 开发：默认挂载 Vite mock 中间件（.env.development VITE_USE_MOCK=true）
pnpm build      # 生产构建（tsc -b 零错误 + vite build）
pnpm lint       # ESLint
pnpm test       # Vitest 单测（工具/权限纯函数）
```

### 环境切换

| 场景 | 配置 | 说明 |
|------|------|------|
| 纯前端开发（后端未就绪） | `.env.development`：`VITE_USE_MOCK=true` | Vite 中间件在 `mock/index.ts` 提供 `/api/admin/*` mock，响应结构与 admin-backend 2.5.2 一致 |
| 开发联调（后端就绪） | `.env.development`：`VITE_USE_MOCK=false` | vite dev proxy `/api` → `http://localhost:8080`（管理端网关） |
| 生产 | `.env.production`：`VITE_USE_MOCK=false` | 同源 `/api` 由 Nginx 反代；**history 路由必须 fallback 到 index.html** |

### Token 存储口径（admin-frontend 2.1 / admin-backend 2.1.2）

- 正式口径：Access Token **仅内存**，刷新走 httpOnly Cookie（`VITE_ACCESS_TOKEN_STORAGE=memory`）。
- P0 兜底：后端若仅提供 bearer 方言，`.env.development` 设为 `session`（sessionStorage 维持刷新后登录态），见 `src/utils/token.ts` 中 TODO。
- 401 并发刷新等待队列（只触发一次刷新，成功统一重放）在 `src/api/request.ts`。

## 目录

```
mock/                 Vite mock 中间件（仅开发期，生产构建不包含）
src/
  api/                接口层（auth/product/order/user/system），每函数标注 plans 章节出处
  components/         BasicLayout(菜单/面包屑)、RouteGuard、AuthButton、StatusTag、ErrorBoundary、PageLoading
  config/             routes.tsx（懒加载+权限守卫+403/404/500）、menus.ts（按权限码过滤）、constants.ts（枚举，来源=数据字典）
  hooks/              usePermission（2.4 按钮级权限）
  pages/              login / dashboard / product / order / user / system / error
  stores/             auth（zustand：用户信息与权限码；Token 不入 store）
  types/              ApiResponse{code,message,data,traceId}、PageResult{list,total}
  utils/              token(内存 Token)、format(金额「分」)、permission(纯函数+单测)、query(pageSize≤100)
```

## P0 页面清单

登录 · 工作台（统计卡片+待办） · 商品列表/详情（上下架、审核流状态） · 订单列表（8 值状态 Tab）/详情（备注/关闭） · 退款售后（同意/拒绝） · 用户管理 · 角色权限（权限点树） · 数据字典 · 操作日志 · 403/404/500 + ErrorBoundary

## 关键口径（勿在页面内另造）

- 权限码：`模块:操作`，以 admin-backend 2.2.3 注册表为准（`product:*`，禁止 `goods:*`）
- 订单状态：8 值 `0待付款…7已关闭`（admin-backend 4.3）；商品审核：`draft/auditing/on_shelf/rejected`（3.3.2）
- 金额：传输以「分」为整数，展示统一 `formatAmount`；分页 `pageSize ≤ 100`、时间范围 ≤ 90 天（16.4）
- 上下架/改价经 MQ 同步买家端为**最终一致性**，操作成功提示"最长数分钟生效"（1.7）
