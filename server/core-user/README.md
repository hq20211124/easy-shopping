# core-user（:8081）

用户中心服务（账号/认证/资料/地址 + 内部管理端点）。买家域 10 个 core-* 微服务之一。

## P0 实现范围

| 域 | 端点 | 说明 |
|----|------|------|
| 认证 | POST `/api/v1/auth/login` | 用户名（手机号/邮箱）+ 密码登录；密码 SHA-256+盐（`s256$salt$hash`，常量时间比对；TODO(P1) 迁 BCrypt）。连续失败 5 次锁定 15 分钟（失败计数在独立事务落库，不受登录失败回滚影响） |
| | POST `/api/v1/auth/register` | 手机号 + 短信验证码注册（P0 固定码 `123456`，TODO(P1) 接短信服务）；成功发 `member.registered` 领域事件（registry 名 `member.registered`，见 architecture/04-messaging §7.1） |
| | POST `/api/v1/auth/refresh` | Refresh Token 轮换；旧 token 60s 宽限（并发刷新竞态，P0 进程内存储，TODO(P1) Redis） |
| | POST `/api/v1/auth/logout` | 吊销 Refresh Token |
| | GET `/api/v1/auth/me` | 当前登录用户摘要 |
| 资料 | GET `/api/v1/user/profile` | 个人资料（mobile 脱敏 `138****1234`、email 脱敏 `z***@`，后端脱敏为主） |
| | PUT `/api/v1/user/profile` | 修改昵称/性别/生日/邮箱等（不含手机号换绑，P1） |
| 地址 | GET/POST `/api/v1/user/addresses` | 收货地址列表/新增（唯一默认地址约束） |
| | PUT/DELETE `/api/v1/user/addresses/{id}` | 编辑/软删（删默认地址自动提升次新地址为默认） |
| | POST `/api/v1/user/addresses/{id}/default` | 设为默认 |
| 内部 | POST `/api/v1/internal/auth/verify` | 凭据校验（admin-bff 登录用；错误码 2001/2002 透传）；**无网关路由，仅内网可达** |
| | GET/POST `/api/v1/internal/users` | 内部用户管理（分页/按 role 过滤/创建） |
| | GET/PUT/DELETE `/api/v1/internal/users/{id}` | 查询/更新/软删（软删发 `user.deleted` 事件，PIPL，消费者 P1） |
| | PUT `/api/v1/internal/users/{id}/roles` | 更新角色 CSV（`ADMIN`/`USER`） |
| | POST `/api/v1/internal/users/{id}/login-fail-reset` | 解锁（失败计数清零） |
| 健康 | GET `/health` | 健康检查 |

## 关键口径

- **错误码**：0 成功 / 1001 参数 / 1002 未登录 / 1003 Token 过期 / 1004 无权限 / 2001 用户不存在 / 2002 密码错误 / 2003 验证码错误 / 404 不存在 / 9999 系统。统一信封 `{code, msg, data, traceId}`。
- **密码**：永不落库明文、永不入日志（登录请求体的 password 字段不打印）。
- **写接口幂等**：POST/PUT/DELETE 须携带 `Idempotency-Key`（或 `X-Idempotency-Key`）头，缺失返回 1001，重复返回 1005（P0 进程内去重，TODO(P1) Redis SETNX）。
- **网关信任**：`/api/v1/**` 由网关 JWT 过滤器注入 `X-User-Id`/`X-User-Roles`，本服务直接读头（P0 内网直连信任）。
- **事件**：`member.registered`（注册）、`user.deleted`（软删）经 common `DomainEventPublisher` 发布，P0 内存实现。

## 依赖

| 依赖方 | 说明 |
|--------|------|
| admin-bff → 本服务 | 登录凭据校验 + 管理员账号 CRUD（HTTP `/api/v1/internal/**`，见 admin-bff README） |
| 本服务 → common | ApiResponse/BizException/JwtService/DomainEventPublisher/SnowflakeIdGenerator（自动装配，无额外配置） |
| 本服务 → 短信服务 | TODO(P1)：注册/换绑手机验证码下发（P0 固定 123456） |

## 测试

`mvn -pl core-user test`（mem H2）：25 用例覆盖 登录成功/失败/锁定、refresh 轮换+宽限、脱敏、地址默认唯一、internal 端点。
