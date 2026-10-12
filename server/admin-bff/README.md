# admin-bff（:8090）

管理端 API 聚合 BFF：认证/RBAC/系统管理本地实现，商品/审核/订单聚合代理 core-product / core-order。

## 演进口径（重要）

`plans/admin-backend.md` 1.1.1 定义了 21 个管理端服务（admin-auth / admin-user / admin-product /
admin-category / admin-order / admin-inventory / admin-member / admin-marketing / admin-finance /
admin-content / admin-data / admin-system / admin-store / admin-message / admin-cs / admin-audit /
admin-search / admin-file / admin-warehouse / admin-logistics / admin-gateway）。

**P0/P1 口径**：这 21 个 admin-* 细分服务在阶段一不单独部署，由本模块（admin-bff）聚合承载：
按 `plans/admin-backend.md` 的 API 路径前缀（`/api/admin/**`）在本模块内分 package 实现，
共享一个 H2 库 `admin-bff`。

**P2 拆分条件**（满足其一再拆）：某域 QPS 或团队并行度成为瓶颈；或该域需要独立数据边界
（admin-data 只消费 MQ 的例外口径见 1.1.2）。拆分时按域名平移到独立服务，API 路径不变，
网关在 `gateway/application.yml` 增加对应路由即可，前端零改动。

另注：按 1.1.2 v1.3 裁决（C5/C12），通用网关能力生产走 Higress/APISIX 插件，本 BFF 只承载
管理端业务聚合与适配，不自研通用网关逻辑。

## 端点清单

### 认证（`com.admin.auth`；web-admin src/api/auth.ts 契约）

| 接口 | 权限 | 说明 |
|------|------|------|
| POST `/api/admin/auth/login` | 白名单 | 经 HTTP 调 core-user internal verify 校验凭据（禁止直查其 DB）；ADMIN 角色校验；access 进 body，refresh 进 httpOnly Cookie `ADMIN_RT`（path=/api/admin, SameSite=Strict）；失败 3 次后前端强制验证码（P0 服务端不强制，TODO P1） |
| POST `/api/admin/auth/refresh` | 白名单 | 从 Cookie 读 refresh，轮换（旧 token 60s 宽限）+ 重签 access |
| POST `/api/admin/auth/logout` | ADMIN | 吊销 refresh + 清 Cookie |
| GET `/api/admin/auth/info` | ADMIN | AuthProfile：userId/username/nickname/roles/permissions/dataScope/permVer/deptId/deptName |
| GET `/api/admin/auth/menus` | ADMIN | 按权限过滤的菜单树（key/label/path/permission/children；type=permission 节点不下发；super_admin 全量） |
| GET `/api/admin/auth/captcha` | ADMIN* | SVG 验证码（*网关白名单未含，联调需加白，见下方依赖清单） |

### RBAC/系统管理（`com.admin.system`；web-admin src/api/user.ts / system.ts 契约）

| 接口 | 权限点 | 说明 |
|------|--------|------|
| GET/POST `/api/admin/system/user` | system:user | 管理员列表（代理 core-user internal users）/ 新增（core-user 建账号 + 本地绑角色） |
| PUT/DELETE `/api/admin/system/user/{id}` | system:user | 编辑 / 软删（core-user 侧发 user.deleted） |
| PUT `/api/admin/system/user/{id}/roles` | system:role | 分配角色（防提权：角色必须存在于注册表） |
| GET/POST `/api/admin/system/role` | system:role | 角色列表 / 新增（code 唯一；data_scope 1~6） |
| PUT/DELETE `/api/admin/system/role/{id}` | system:role | 编辑 / 删除（**内置角色防提权**：builtin 不可编辑/删除；有绑定用户不可删） |
| GET/PUT `/api/admin/system/role/{id}/permissions` | system:role | 角色授权查询 / 全量替换（写入后相关用户 permVer+1） |
| GET `/api/admin/system/permission/tree` | system:permission | 权限点树（菜单+按钮+接口，type=menu\|permission） |
| POST/DELETE `/api/admin/system/permission/{id}` | system:permission | 新增（code 唯一，product:* 命名，禁 goods:*）/ 删除（有子节点不可删） |
| GET/POST/PUT/DELETE `/api/admin/system/dept`…`/{id}` | system:dept | 部门树/CRUD（有子部门/有用户不可删） |
| GET `/api/admin/system/dict`、GET `/api/admin/system/dict/items?type=` | system:dict | 字典类型/字典项（订单状态、支付方式等枚举统一从此下发；**pay_type 须含 4-余额**，v1.2 裁决） |
| POST/PUT/DELETE `/api/admin/system/dict`…`/{id}` | system:dict | 字典项 CRUD（type+value 唯一） |
| GET/POST/PUT/DELETE `/api/admin/system/config`…`/{id}` | system:config | 系统参数（key 唯一 upsert；TODO P1 变更历史+一键回滚） |
| GET `/api/admin/system/operation-log` | system:log | 操作日志（AOP 落库；operator/module 关键字 + 时间范围；beforeData P0 恒 null） |

### 商品/审核代理（`com.admin.web.AdminProductController` → core-product）

| 接口 | 权限点 |
|------|--------|
| GET `/api/admin/product/list` | product:list |
| GET `/api/admin/product/{id}` | product:list |
| GET `/api/admin/audit/task/list` | product:audit |
| POST `/api/admin/audit/task/{id}/approve` \| `/reject` | product:audit（幂等+操作日志） |

### 订单代理（`com.admin.web.AdminOrderController` → core-order）

| 接口 | 权限点 |
|------|--------|
| GET `/api/admin/order/list` | order:list |
| GET `/api/admin/order/{id}` | order:detail |
| POST `/api/admin/order/{id}/ship` | order:ship（幂等+操作日志） |
| POST `/api/admin/order/{id}/close` | order:close（幂等+操作日志） |

### 工作台/其他

| 接口 | 说明 |
|------|------|
| GET `/api/admin/data/workbench` | WorkbenchData stub（gmv/orderCount/newMember/pendingCount/trend）；TODO P1 admin-data 服务消费 MQ 聚合，禁止直连业务库（1.1.2） |
| GET `/api/admin/data/todo` | 待办聚合 stub |
| GET `/api/admin/dashboard/overview` | 看板 stub |
| GET `/health` | 健康检查 |
| 客服/消息（17.2/17.3） | **未实现**，TODO(P1) 补 com.admin.cs / com.admin.message |

## 安全模型

- **网关信任**：网关 JWT 过滤器注入 `X-User-Id`/`X-User-Roles`；`AdminGuardInterceptor` 强制 `/api/admin/**`（login/refresh 白名单除外）携带 ADMIN 角色，买家 Token（USER）一律 403+1004。
- **权限点**：`@RequirePerm("模块:资源")` 切面查本地 RBAC（P0 每请求查库；TODO P1 Redis+permVer 失效）；super_admin 绕过。缺权 → HTTP 403 + code 1004。
- **写接口幂等**：`IdempotentInterceptor` 强制 `Idempotency-Key` 头（缺失 1001，重复 1005）。
- **操作日志**：`@OperationLog` AOP 落 `admin_operation_log`（耗时/结果/IP/入出参，password 等敏感字段黑名单剥离）。
- **数据权限五档**（2.3.1）：P0 生效 1-全部/2-本部门/6-本店铺；3/4/5 存表但查询改写 TODO（`DataScope`/`DataScopeFilter`）。

## 权限点注册表（DataInitializer 种子，与 web-admin src/config/menus.ts 对齐）

菜单：dashboard / product / product:list / order / order:list / order:refund / system / user:list / user:roles / system:dict / system:log
权限点：product:create / product:edit / product:audit / order:detail / order:ship / order:close / refund:list / system:permission / system:dept / system:config / **system:user / system:role** / cost:read / report:view
内置角色：super_admin（builtin，全量权限，不可编辑/删除）

## 依赖清单（联调前置）

| 依赖 | 端点 | 状态 |
|------|------|------|
| core-user | POST `/api/v1/internal/auth/verify`、GET/POST/PUT/DELETE `/api/v1/internal/users*`、PUT `/api/v1/internal/users/{id}/roles`、POST `/api/v1/internal/users/{id}/login-fail-reset` | ✅ 已实现（core-user README） |
| core-product | GET `/api/admin/product/list\|{id}`、GET `/api/admin/audit/task/list`、POST `/api/admin/audit/task/{id}/approve\|reject` | ⚠️ 依赖集成（未就绪时 9999「core-product 暂不可用」） |
| core-order | GET `/api/admin/order/list\|{id}`、POST `/api/admin/order/{id}/ship\|close` | ⚠️ 依赖集成（同上） |
| gateway | 白名单加 `/api/admin/auth/captcha`（登录页无 token 场景）；路由 `/api/admin/**` → admin-bff | ⚠️ 需网关负责人配置（本模块无权改 gateway） |

## 测试

`mvn -pl admin-bff test`（mem H2 + mock 三个 core 客户端）：17 用例覆盖
① 登录成功/密码错误 2002/非管理员 403+1004 ② refresh 轮换+60s 宽限+logout 吊销
③ 权限点缺失 403+1004/super_admin 绕过/买家 Token 拒绝 ⑥ 菜单树结构+AuthProfile 契约
另：内置角色防提权、有用户角色不可删、角色授权+permVer 刷新、幂等键强制（1001/1005）、商品/订单代理透传。
