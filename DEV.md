# DEV.md — 后端骨架开发指南（server/）

> 面向对象：后续接手各域的开发代理/工程师。本文描述 **P0 骨架** 的构建方式与开发规范；
> 业务设计的唯一事实来源是 `plans/*-backend.md`，容量与部署口径查 `plans/00-架构总纲.md`；
> 历史裁决（原 `architecture/00-conflict-register.md` 等 12 份亿级文档）已删除，见 git 历史 v1。

## 1. 环境与构建

| 项 | 值 |
|----|----|
| JDK | 17（`JAVA_HOME=C:\Android\jdk-17`） |
| Maven | **必须用全路径** `C:\D\tools\maven\bin\mvn.cmd`（PATH 里没有 `mvn`） |
| Maven 镜像 | 本机直连 Maven Central 超时，已在 `C:\Users\72492\.m2\settings.xml` 配置阿里云镜像（`mirrorOf=central`），勿删 |
| 平台编码 | **GBK**（所有源码/配置均为 UTF-8；pom 已强制 `project.build.sourceEncoding=UTF-8`） |

```powershell
# 构建（跳过测试）
$env:JAVA_HOME='C:\Android\jdk-17'
cd C:\D\agent\easy-shopping\server
C:\D\tools\maven\bin\mvn.cmd -q -DskipTests package

# 构建 + 全部测试
C:\D\tools\maven\bin\mvn.cmd -q test

# 只跑一个模块的测试
C:\D\tools\maven\bin\mvn.cmd -q -pl core-user -am test
```

**编码陷阱**：PowerShell 5.1 读取无 BOM 的 UTF-8 文件会按 GBK 解析，导致中文乱码/脚本报错。
写 `.ps1` 脚本或用 `Get-Content` 读源码时，要么给文件加 UTF-8 BOM，要么用
`[System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)`。Java 源文件保持 **无 BOM**（javac 由 `-encoding UTF-8` 保证）。

## 2. 模块与端口

```
server/
  common/        公共库（无 DB、无启动类）：响应信封/错误码/全局异常/JWT/幂等/领域事件/雪花ID
  gateway/       :8080  统一网关（Spring Cloud Gateway，静态路由）
  core-user/     :8081  认证/用户/地址
  core-product/  :8082  SPU/SKU/类目/上下架
  core-price/    :8083  价格查询/计算
  core-inventory/:8084  库存预占/释放/扣减（乐观锁）
  core-cart/     :8085  购物车
  core-order/    :8086  下单/拆单/8值状态机/pay_deadline 超时关单/查询
  core-payment/  :8087  支付 mock/回调幂等/退款
  core-promotion/:8088  优惠券领取/核销
  core-search/   :8089  商品搜索（P0 DB like，P1 ES）
  admin-bff/     :8090  管理端 API 聚合（21 个 admin-* 细分 P1 前的聚合承载，见其 README）
```

**启动顺序**：先各业务服务（顺序无所谓），最后 gateway。
每个服务独立可启动（`mvn -pl core-order -am spring-boot:run` 或直接 `java -jar core-order/target/core-order-0.1.0-SNAPSHOT.jar`）。

**手工联调链路示例**（无前端时验证全链路）：

```powershell
# 1. 登录拿 Token（免登录白名单）
curl http://localhost:8080/api/v1/auth/login -X POST
# 2. 带 Token 访问需登录接口（网关注入 X-User-Id/X-User-Roles）
curl http://localhost:8080/api/v1/cart/list -H "Authorization: Bearer <accessToken>"
```

## 3. P0 技术口径（与 plans 的差异点）

| 项 | P0 骨架 | plans 目标态 / P1+ |
|----|---------|--------------------|
| 注册中心 | 无 Nacos；网关 `application.yml` 静态路由，服务地址 `service.urls.*` | Nacos（admin-backend 1.1.2） |
| 服务间调用 | Spring `RestClient` + 静态地址 | OpenFeign（超时 连接1s/读3s，只有幂等接口可重试） |
| DB | 每服务独立 H2 文件库（`MODE=MySQL`，`./data/` 目录，已 gitignore）；`ddl-auto=update` | MySQL 分库分表；P1 引入 Flyway 后 `ddl-auto=validate`（16.4.1）；`application-prod.yml` 已留 MySQL 配置位 |
| MQ | 无 RabbitMQ；`DomainEventNames` 常量（30 个）+ 进程内日志分发 | RabbitMQ，事件名见下节；本地消息表/事务消息（git 历史 v1 `architecture/04-messaging.md`） |
| 网关 | Spring Cloud Gateway + JWT 过滤器 + 进程内令牌桶限流 | 生产 Higress/APISIX 插件（1.1.2 v1.3 裁决 C5/C12），路由/JWT/限流逻辑下沉插件 |
| 幂等 | `@Idempotent` + 内存 Map（单实例有效） | Redis SETNX + TTL（多实例必须） |
| 全链路追踪 | `X-Trace-Id` 头透传 + `TraceIds` ThreadLocal | Micrometer Tracing / SkyWalking |

**响应信封差异**：骨架字段为 `{code, msg, data, traceId}`（任务约定）；
plans 前端文档写的是 `{code, message, data, ...}`（admin 侧带 traceId、android 侧带 timestamp/requestId）。
若要与前端对齐，只改 `common` 的 `ApiResponse`（给 `msg` 加 `@JsonProperty("message")`），**禁止各服务自行包装**。

## 4. 给后续代理的开发规范

### 4.1 目录归属（防冲突）

- 只在自己负责的模块目录内创建文件；跨模块共享的东西进 `common`（改动需谨慎，其它模块同时在用）。
- 业务包名：`com.<域>.<子域>`（如 `com.order.web` / `com.order.domain` / `com.order.service` / `com.order.repo`），
  **不要**用 `com.easyshopping.*`（common/gateway 除外），与已生成骨架保持一致。
- 网关路由、白名单改动集中在 `gateway/src/main/resources/application.yml`，改前先 grep 是否有他人未提交改动。

### 4.2 加一个接口的标准步骤

1. Controller（`web` 包）：`@RestController`，路径写**全前缀**（`/api/v1/...` 或 `/api/admin/...`，网关不剥前缀）。
2. 入参校验：`@Valid @RequestBody` + DTO `jakarta.validation` 注解；参数错误自动返回 1001（全局异常处理器已兜底）。
3. 返回统一 `ApiResponse.ok(data)`；分页用 `PageResult.of(list, total, pageNum, pageSize)`。
4. 错误只抛 `BizException(BizErrorCode.XXX)`：401/403/404/409 由全局处理器映射 HTTP 状态，其余 HTTP 200 + 业务码。
5. 写接口必须加 `@Idempotent`（客户端传 `Idempotency-Key` 或 `X-Idempotency-Key`），并让客户端携带。
6. 当前登录用户：`UserContext.requireUserId()`（网关已注入 `X-User-Id`，不要自己解析 Token）。
7. 加测试：`@WebMvcTest`（MVC 切片，不起 JPA）或 `@SpringBootTest`（需要 DB 时用 mem H2 覆盖，参考 `core-user` 的 `CoreUserApplicationTests`）。

### 4.3 加实体/表的标准步骤

1. 实体放 `domain` 包：`@Entity`，主键 **`Long id` + `@GeneratedValue` 不用**——分片/流水表一律用
   `SnowflakeIdGenerator.nextId()`（裁决 C13，禁止自增）；P0 非分片小表如用 H2 自增需在代码注释标注「P1 迁移时改雪花」。
2. P0 靠 `ddl-auto=update` 自动建表；**上生产前必须补 Flyway 迁移脚本**（占位 TODO）。
3. 金额一律 `BigDecimal`（DECIMAL(10,2)），**禁止 Double**；时间用 `Instant`/`LocalDateTime` + 服务端时区 Asia/Shanghai。
4. 需要乐观锁的表（库存、券余量）：`@Version` 字段 + 更新失败按业务错误码处理（如 3002）。

### 4.4 领域事件规范

- 事件名**只能**用 `common` 的 `DomainEventNames` 常量（30 个；权威注册表见 git 历史 v1 `architecture/04-messaging.md` §7.1）；新增事件先去该文件登记。
- 发布：`DomainEventPublisher.publish(DomainEventNames.XXX, payload)`；**必须在本地事务提交后**（P0 无本地消息表，P1 补）。
- 跨服务解耦优先事件，禁止 A 服务直连 B 服务数据库；同步调用只允许 admin-order→core-product/member 这类白名单方向，反向一律 MQ（admin-backend 1.1.2 依赖方向）。

### 4.5 JWT / 鉴权

- 网关校验 `Authorization: Bearer <accessToken>`，透传 `X-User-Id` / `X-User-Roles`；白名单在 `gateway/application.yml`。
- 业务服务**不重复校验 Token**（P0 信任内网），只读头；需要角色的接口自行判断 `UserContext.roles()`，缺权抛 `NO_PERMISSION`（1004）。
- 密钥 `app.jwt.secret`（默认值仅开发用，生产用环境变量 `APP_JWT_SECRET` 覆盖，≥32 字节）。

### 4.6 错误码

`BizErrorCode` 以 `plans/android-backend.md` 16.2 为基准（前端已依赖的 0/1001/1002/1003/1004/3002/4002/9999 语义不可变更）。
新增码先更新该表并同步前端 `core-network` 白名单；未知码前端按 9999 兜底。

## 5. 常见问题

- **启动端口冲突**：`netstat -ano | findstr 8081` 后结束进程；每个服务端口见上表。
  **本机注意**：开发机的 WSL（wslrelay）占用了 `[::1]:8081`（IPv6 回环），会导致 core-user 默认端口启动失败
  （"Port 8081 was already in use"）。临时用 `--server.port=18081` 覆盖并在 gateway 用
  `--service.urls.core-user=http://localhost:18081` 同步覆盖；或关闭占用 8081 的 WSL 进程。
- **H2 文件锁**：同一服务只允许启动一个实例（P0 无 AUTO_SERVER）；删 `server/data/` 可重置开发库。
- **测试污染开发库**：测试必须用 `jdbc:h2:mem:`（参考 `CoreUserApplicationTests` 的 properties 覆盖）。
- **中文乱码**：先确认文件是 UTF-8 无 BOM，再确认 pom 有 `project.build.sourceEncoding=UTF-8`（parent pom 已统一）。
- **gateway 报 503**：对应业务服务没启动；报 404：路径没进任何 route，先核对 `application.yml` 路由表。
