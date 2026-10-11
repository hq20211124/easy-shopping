# 管理端后端开发计划

> **项目**：大型电商平台管理端后端  
> **规模**：淘宝级别，支撑超级管理员、运营、客服、财务、商家等多角色  
> **技术栈**：Java Spring Boot（核心服务）+ Go（网关/文件服务）+ Node.js（实时看板）+ MySQL + Redis + Elasticsearch + RabbitMQ  
> **架构**：微服务架构，与买家端共享核心服务  

**技术栈选型标准**：
- **Java Spring Boot**：核心业务服务（商品、订单、财务、会员等），生态成熟、事务管理完善
- **Go**：admin-gateway（高并发网关）、admin-file（文件处理），性能敏感场景
- **Node.js**：admin-data（实时看板），适合I/O密集型的WebSocket推送
- **禁止跨语言直接访问数据库**，必须通过服务间通信

---

## 一、架构与基础设施

### 1.1 微服务拆分

#### 1.1.1 服务划分原则

| 服务名称 | 职责 | 核心能力 | 依赖关系 | 语言 |
|---------|------|---------|---------|------|
| `admin-gateway` | 统一网关 | 路由、鉴权、限流、熔断、日志 | 所有服务 | Go |
| `admin-auth` | 认证授权 | 登录、JWT、RBAC、权限校验 | admin-user | Java |
| `admin-user` | 用户管理 | 管理员CRUD、角色分配 | - | Java |
| `admin-product` | 商品服务 | 商品CRUD、审核、类目、属性 | admin-category | Java |
| `admin-category` | 类目服务 | 类目树、属性模板 | - | Java |
| `admin-order` | 订单服务 | 订单CRUD、状态机、拆单、改价 | admin-product, admin-member | Java |
| `admin-inventory` | 库存服务 | 库存CRUD、预警、盘点、调拨 | admin-product, admin-warehouse | Java |
| `admin-member` | 会员服务 | 会员CRUD、等级、积分、标签 | - | Java |
| `admin-marketing` | 营销服务 | 优惠券、满减、秒杀、拼团、价格引擎 | admin-product, admin-order | Java |
| `admin-finance` | 财务服务 | 对账、结算、佣金、提现、发票 | admin-order | Java |
| `admin-content` | 内容服务 | CMS、Banner、公告、专题 | - | Java |
| `admin-data` | 数据服务 | 报表、导出、实时看板 | 所有服务(消费MQ) | Node.js |
| `admin-system` | 系统服务 | 参数配置、操作日志、备份 | - | Java |
| `admin-store` | 店铺服务 | 店铺审核、等级、保证金、DSR | admin-user | Java |
| `admin-message` | 消息服务 | 通知、推送、站内信 | admin-user | Java |
| `admin-cs` | 客服服务 | 工单、会话、智能客服 | admin-member, admin-order | Java |
| `admin-audit` | 审核服务 | 商品/店铺/售后审核 | admin-product, admin-store, admin-order | Java |
| `admin-search` | 搜索服务 | 全文检索、筛选、聚合 | Elasticsearch | Java |
| `admin-file` | 文件服务 | 图片/文件上传、CDN、水印 | OSS/S3 | Go |
| `admin-warehouse` | 仓储服务 | 仓库管理、物流对接 | - | Java |
| `admin-logistics` | 物流服务 | 物流跟踪、运费模板 | - | Java |

> **合并考量**：admin-warehouse 与 admin-product 耦合度高，初期可合并为 `admin-product-warehouse`，后期再拆分。admin-logistics 同理。admin-audit 与 admin-store 的审核能力初期也可合并为一个 `admin-audit-center`，避免审核队列、认领、复审逻辑重复实现两遍。
>
> **服务边界补充（v1.1）**：
> - 店铺（admin-store）、审核（admin-audit）、客服（admin-cs）、消息（admin-message）、数据看板（admin-data）、文件（admin-file）、物流（admin-logistics）在本计划前九章只定义了数据表，**API 与流程统一见第十七章「其余模块服务设计」**，避免出现"有表无接口"的落地空洞。
> - **跨语言读库的例外说明**：admin-data（Node.js）只允许「消费 MQ + 调用各服务查询接口」两种取数方式，聚合结果写入自己的 `admin_data_db`，不得直连 MySQL 业务库（与本节"禁止跨语言直接访问数据库"口径一致）；报表类离线查询在 P1 阶段可由 Java 侧 `admin-data-query` 模块承担，避免为看板单独开放库权限。

#### 1.1.2 服务间通信

```
┌─────────────────────────────────────────────────────────────────┐
│                        API Gateway (Higress/APISIX)               │
│  (Higress/APISIX，支持JWT验证、限流、熔断，能力对齐Kong)           │
└─────────────┬───────────────┬───────────────┬───────────────────┘
              │               │               │
    ┌─────────▼─────┐  ┌─────▼──────┐  ┌────▼────────┐
    │  admin-auth   │  │ admin-user │  │admin-system │
    └─────────┬─────┘  └────────────┘  └─────────────┘
              │ JWT验证
    ┌─────────▼─────────────────────────────────────┐
    │              业务服务层                         │
    │  product / order / member / marketing / ...     │
    └─────────┬─────────────────────────────────────┘
              │
    ┌─────────▼─────┐  ┌────────────┐  ┌────────────┐
    │  admin-data   │  │admin-search│  │ admin-file │
    │  (MQ Consumer)│  │   (ES)     │  │   (OSS)    │
    └───────────────┘  └────────────┘  └────────────┘
```

**通信方式**：
- **同步调用**：OpenFeign（Java内部）/ gRPC（跨语言服务）
- **异步消息**：RabbitMQ（事件驱动）
- **数据共享**：核心数据通过事件同步，不直接跨库查询

**服务发现与调用规范（v1.1 补充，原文缺失）**：
- **注册中心**：Nacos 统一注册（OpenFeign、Seata、Sentinel 均依赖 Nacos，见 8.3.3、11.4.1），Go 侧网关/文件服务通过 Nacos SDK 或 K8s Service + DNS 发现；**若不引入 Nacos，Seata 与 Sentinel 配置中的 `registry.type: nacos` 必须同步改为其他实现，否则章节间自相矛盾**。
- **超时与重试**：Feign 默认超时必须显式配置（连接 1s / 读 3s），网关整体超时 10s；**只有幂等接口（GET/带幂等 Key 的写接口）允许重试**，非幂等写接口禁止自动重试，避免重复下单/重复扣款。
- **降级约定**：查询类接口降级返回空/缓存，资金与状态变更类接口（改价、退款、结算、打款）**禁止降级**，失败必须显式报错并留痕。
- **依赖方向**：admin-order 可同步调用 admin-product、admin-member；反向依赖（product→order）一律改为 MQ 事件，禁止形成同步环。

**网关定名与选型（v1.3 跨架构裁决 C5/C12）**：
- **买家端网关定名 `edge-gateway`**（架构 01 号新增命名，`buyer-gateway` 等旧称作废），管理端侧沿用 `admin-gateway` 称谓；
- `admin-gateway` 的**通用网关职责（路由、协议转换、限流、熔断、日志等）下沉到网关插件 + BFF**，**不再自研通用网关**；网关本体统一选型 **Higress/APISIX（能力对齐 Kong）**，与 11.4.1「规则同源 Nacos、执行器分家」口径一致；
- 1.1.1 服务表中的 `admin-gateway` 行与文首"Go：admin-gateway（高并发网关）"为 **P0/P1 过渡口径**，P2 起仅保留 BFF/适配层代码，通用网关能力一律走 Higress/APISIX 插件。

#### 1.1.3 与买家端共享服务

| 共享服务 | 管理端使用方式 | 说明 |
|---------|--------------|------|
| `core-product` | 调用+扩展 | 商品基础数据共享，管理端扩展审核字段 |
| `core-price` | 调用 | 价格计算引擎共享 |
| `core-promotion` | 调用+配置 | 促销规则共享，管理端负责配置 |
| `core-user` | 调用 | 用户基础数据共享 |

**事件同步机制**：
- 管理端操作（如商品上架、价格变更）通过MQ发送事件
- 买家端消费事件更新本地缓存和ES索引
- 采用**最终一致性**，通过定时对账任务补偿

---

### 1.2 数据库设计

#### 1.2.1 数据库拆分策略

采用**分库分表 + 读写分离**策略：

```
┌─────────────────────────────────────────────────────────┐
│                    MySQL Cluster                          │
├──────────────┬──────────────┬──────────────┬────────────┤
│  admin_db_0  │  admin_db_1  │  admin_db_2  │ admin_db_3 │
│  (用户/权限)  │  (商品/类目)  │  (订单/库存)  │  (财务/营销)│
├──────────────┴──────────────┴──────────────┴────────────┤
│  分库规则：按业务领域垂直拆分                              │
│  分表规则：订单按月份、商品按spu_id、日志按日期               │
│  分片键：订单按user_id，商品按spu_id                  │
│  读写分离：主库写、从库读，关键操作强制走主库                │
└─────────────────────────────────────────────────────────┘
```

**分片策略说明**：
- 用户ID哈希：仅用于用户相关表（admin_user、admin_user_role）
- 订单表：按user_id分库 + 按月份分表（双维度）
- 商品表：按spu_id分片（v1.3 裁决 C13 + 02 §7#8：原"按 category_id 分表"作废，类目维度查询走 Elasticsearch）
- 日志表：按日期分表
- 全局ID：使用Snowflake雪花算法，避免跨库ID冲突

**分片落地约束（v1.1 修正，原文 DDL 与策略互相矛盾）**：
1. **ID 策略矛盾（v1.3 强化，裁决 C13 + 02 §7#14）**：上文写"全局ID用 Snowflake"，但 1.2.2 中所有表仍为 `AUTO_INCREMENT`（v1.0 遗留写法，**该写法已全部作废**）。**分库分表场景禁止自增**：分表清单内的表（admin_product_spu_*、admin_order_*、admin_*_log_*、admin_settlement_*、admin_reconciliation_*、admin_login_log_*）及一切分片表主键一律改为 `BIGINT NOT NULL` + 应用层**雪花算法**；1.2.2 全表 DDL 的批量修订是**上线前必改项**（当前 DDL 尚未落地该口径，见 1.2.2 文首说明）。未分表的字典/配置类表如确需保留自增，必须在本约束下**逐表豁免登记**，禁止默认沿用。
2. **分片键与查询路径冲突**：订单按 `user_id` 分库，但管理端高频查询是 `order_no`、`store_id`、状态+时间范围（运营/商家/财务视角）。方案：
   - `order_no` 内嵌分库分表路由信息，统一采用 **24 位定长编码：时间 14 位（`yyyyMMddHHmmss`）+ 分片槽位 4 位（0000~8191）+ 序列 6 位**（精确规范见 02 §3.2；槽位可反解出物理分区/单元，按 `order_no` 查单无需重算 `crc32(user_id)`，网关/路由层解析 `order_no[14:18]` 定位单片）；**原"`yyyyMMddHHmmss` + 雪花尾段"写法作废**——雪花尾段不携带分片信息，无法满足商家/运营/客服按 `order_no` 直接反解路由查单；
   - `store_id`、状态类列表查询统一走 **Elasticsearch 索引**（admin-search），命中 `order_id` 后再回表点查；
   - 禁止无路由条件的跨片 `SELECT` 进入在线链路，跨片统计一律走离线/报表链路（第十七章数据服务）。
3. **跨片 JOIN 不可行**：`admin_order_item`、`admin_order_log` 必须与订单同库同片（按 `order_no` 路由），否则拆单、售后、导出都会退化为跨片查询；**新增表时必须先确定它跟随哪张主表分片**。
4. **初期可实施性**：v1.0 直接给出 4 库 + 多维分表过于理想化。落地建议：**单库 + 按月逻辑分表（表名后缀）**，读写分离 + ES 承担检索；单表行数稳定超过 5000 万或磁盘出现压力时再启用分库，路由层用 ShardingSphere 透明切换，业务代码不感知。
5. **唯一约束**：分表后 `uk_order_no`、`uk_spu_code` 等唯一索引只在单片内生效，**必须额外以 `order_no`/`spu_code` 的编码规则保证全局唯一**（时间戳+机器号+序列），并在生成器上做并发测试。

#### 1.2.2 核心数据表

> **主键与分片口径（v1.3 跨架构裁决 C13 + 02 §7 必改清单，上线前必改）**：下列 DDL 为 v1.0 结构示意，其中 `id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT` 写法**已全部作废**——① 所有分片表及日志/流水/结算/对账/登录日志表主键一律 `BIGINT NOT NULL` + 应用层**雪花算法**（分库分表场景禁止自增，1.2.1 约束 1）；② 商品 SPU 表分片键为 `spu_id`（非 `category_id`，类目维度查询走 ES）；③ 订单域单号 `order_no` 统一 24 位编码（时间 14 + 槽位 4 + 序列 6，见 1.2.1 约束 2）。**DDL 落地前必须按上述口径批量修订**（02 §7#8/#14），未分表的字典/配置类表保留自增需在 1.2.1 逐表豁免登记。

##### 用户权限模块

```sql
-- 管理员表
CREATE TABLE `admin_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名',
  `password` VARCHAR(255) NOT NULL COMMENT '密码(BCrypt加密)',
  `real_name` VARCHAR(50) DEFAULT NULL COMMENT '真实姓名',
  `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱(加密存储)',
  `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号(加密存储)',
  `avatar` VARCHAR(500) DEFAULT NULL COMMENT '头像',
  `dept_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '部门ID',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态:0-禁用,1-启用',
  `last_login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip` VARCHAR(50) DEFAULT NULL COMMENT '最后登录IP',
  `login_fail_count` INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
  `lock_until` DATETIME DEFAULT NULL COMMENT '账号锁定截止时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` DATETIME DEFAULT NULL COMMENT '软删除时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_status` (`status`),
  KEY `idx_dept_id` (`dept_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员表';

-- 角色表
CREATE TABLE `admin_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL COMMENT '角色名称',
  `code` VARCHAR(50) NOT NULL COMMENT '角色编码',
  `description` VARCHAR(255) DEFAULT NULL COMMENT '描述',
  `data_scope` TINYINT NOT NULL DEFAULT 1 COMMENT '数据范围:1-全部,2-本部门,3-本部门及以下,4-仅本人,5-自定义,6-本店铺(merchant专用,按store_id过滤)',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 用户角色关联表
CREATE TABLE `admin_user_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `role_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 权限表(菜单+按钮+接口)
CREATE TABLE `admin_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父ID',
  `name` VARCHAR(100) NOT NULL COMMENT '权限名称',
  `type` TINYINT NOT NULL COMMENT '类型:1-目录,2-菜单,3-按钮,4-接口',
  `path` VARCHAR(200) DEFAULT NULL COMMENT '路由路径',
  `component` VARCHAR(200) DEFAULT NULL COMMENT '组件路径',
  `perms` VARCHAR(200) DEFAULT NULL COMMENT '权限标识(product:create)',
  `api_method` VARCHAR(10) DEFAULT NULL COMMENT 'API方法',
  `api_path` VARCHAR(200) DEFAULT NULL COMMENT 'API路径',
  `icon` VARCHAR(100) DEFAULT NULL COMMENT '图标',
  `sort` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- 角色权限关联表
CREATE TABLE `admin_role_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id` BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- 部门表
CREATE TABLE `admin_dept` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `name` VARCHAR(100) NOT NULL,
  `leader_id` BIGINT UNSIGNED DEFAULT NULL,
  `sort` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

-- 操作日志表(按月分表)
CREATE TABLE `admin_operation_log_202401` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '操作人ID',
  `username` VARCHAR(50) DEFAULT NULL COMMENT '操作人用户名',
  `module` VARCHAR(50) NOT NULL COMMENT '操作模块',
  `action` VARCHAR(100) NOT NULL COMMENT '操作动作',
  `method` VARCHAR(10) NOT NULL COMMENT '请求方法',
  `path` VARCHAR(500) NOT NULL COMMENT '请求路径',
  `params` TEXT DEFAULT NULL COMMENT '请求参数',
  `before_data` TEXT DEFAULT NULL COMMENT '操作前数据',
  `after_data` TEXT DEFAULT NULL COMMENT '操作后数据',
  `ip` VARCHAR(50) DEFAULT NULL COMMENT 'IP地址',
  `location` VARCHAR(100) DEFAULT NULL COMMENT '地理位置',
  `user_agent` VARCHAR(500) DEFAULT NULL COMMENT 'UA',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态:0-失败,1-成功',
  `error_msg` TEXT DEFAULT NULL COMMENT '错误信息',
  `duration` INT DEFAULT NULL COMMENT '耗时(ms)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_module` (`module`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';
```

##### 商品模块

```sql
-- 类目表
CREATE TABLE `admin_category` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `name` VARCHAR(100) NOT NULL COMMENT '类目名称',
  `level` TINYINT NOT NULL COMMENT '层级:1-3',
  `path` VARCHAR(500) NOT NULL COMMENT '路径(1/2/3)',
  `icon` VARCHAR(500) DEFAULT NULL,
  `sort` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_level` (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类目表';

-- 属性模板表
CREATE TABLE `admin_attr_template` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '类目ID',
  `name` VARCHAR(100) NOT NULL COMMENT '属性名称',
  `type` TINYINT NOT NULL COMMENT '类型:1-单选,2-多选,3-输入',
  `input_type` TINYINT NOT NULL COMMENT '录入方式:1-手工,2-列表选择',
  `options` JSON DEFAULT NULL COMMENT '可选值列表',
  `is_required` TINYINT NOT NULL DEFAULT 0 COMMENT '是否必填',
  `is_search` TINYINT NOT NULL DEFAULT 0 COMMENT '是否可搜索',
  `sort` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category_id` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='属性模板表';

-- 商品SPU表(分片键 spu_id；原"按类目ID分表"作废，类目维度查询走 ES，v1.3 裁决 C13/02 §7#8)
CREATE TABLE `admin_product_spu_0` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `spu_code` VARCHAR(50) NOT NULL COMMENT 'SPU编码',
  `name` VARCHAR(200) NOT NULL COMMENT '商品名称',
  `subtitle` VARCHAR(500) DEFAULT NULL COMMENT '副标题',
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '类目ID',
  `brand_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '品牌ID',
  `main_image` VARCHAR(500) DEFAULT NULL COMMENT '主图',
  `images` JSON DEFAULT NULL COMMENT '图片列表',
  `detail` LONGTEXT DEFAULT NULL COMMENT '详情HTML(需XSS过滤)',
  `price_min` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '最低价',
  `price_max` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '最高价',
  `market_price` DECIMAL(10,2) DEFAULT NULL COMMENT '市场价',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '上下架:0-下架,1-上架(须先审核通过)',
  `audit_status` TINYINT NOT NULL DEFAULT 0 COMMENT '审核状态(与3.3.2一致):0-draft草稿/待提交,1-auditing审核中,2-on_shelf通过已上架,3-rejected审核拒绝',
  `audit_remark` VARCHAR(500) DEFAULT NULL COMMENT '审核备注(拒绝原因/退回意见,必填)',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_code` (`spu_code`),
  KEY `idx_category_id` (`category_id`),
  KEY `idx_status` (`status`),
  KEY `idx_audit_status` (`audit_status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SPU表';

-- 商品SKU表
CREATE TABLE `admin_product_sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `spu_id` BIGINT UNSIGNED NOT NULL,
  `sku_code` VARCHAR(50) NOT NULL COMMENT 'SKU编码',
  `specs` JSON NOT NULL COMMENT '规格组合',
  `price` DECIMAL(10,2) NOT NULL COMMENT '售价',
  `market_price` DECIMAL(10,2) DEFAULT NULL,
  `cost_price` DECIMAL(10,2) DEFAULT NULL COMMENT '成本价',
  `stock` INT NOT NULL DEFAULT 0 COMMENT '库存',
  `warning_stock` INT NOT NULL DEFAULT 10 COMMENT '预警库存',
  `weight` DECIMAL(8,2) DEFAULT NULL COMMENT '重量(kg)',
  `status` TINYINT NOT NULL DEFAULT 1,
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_code` (`sku_code`),
  KEY `idx_spu_id` (`spu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU表';

-- 品牌表
CREATE TABLE `admin_brand` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `logo` VARCHAR(500) DEFAULT NULL,
  `description` VARCHAR(500) DEFAULT NULL,
  `sort` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品牌表';
```

##### 订单模块

```sql
-- 订单主表(按用户ID分库,按月分表)
CREATE TABLE `admin_order_202401_0` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no` VARCHAR(32) NOT NULL COMMENT '订单编号',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `user_name` VARCHAR(50) DEFAULT NULL COMMENT '用户名(冗余)',
  `parent_order_no` VARCHAR(32) DEFAULT NULL COMMENT '父订单号(拆单后子单回填,见4.4.2)',
  `store_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '店铺ID(子单必填;主单为空表示未拆单)',
  `order_status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态(8值,与4.3及买家端一致):0-unpaid待付款,1-paid待发货,2-shipped待收货,3-completed已完成,4-refunding退款中,5-refunded已退款,6-cancelled已取消,7-closed已关闭',
  `pay_status` TINYINT NOT NULL DEFAULT 0,
  `pay_type` TINYINT DEFAULT NULL COMMENT '支付方式:1-支付宝,2-微信,3-银行卡(银联),4-余额(买家端扩展,v1.2同步字典)',
  `pay_time` DATETIME DEFAULT NULL,
  `pay_deadline` DATETIME DEFAULT NULL COMMENT '付款截止时间(超时自动关单,见4.4.4)',
  `close_reason` VARCHAR(255) DEFAULT NULL COMMENT '关闭/取消原因(timeout超时,manual手工,refund退款完成)',
  `total_amount` DECIMAL(12,2) NOT NULL COMMENT '商品总金额',
  `discount_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '优惠金额',
  `freight_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '运费',
  `pay_amount` DECIMAL(12,2) NOT NULL COMMENT '应付金额',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '用户备注',
  `admin_remark` VARCHAR(500) DEFAULT NULL COMMENT '管理员备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_order_status` (`order_status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- 订单商品表
CREATE TABLE `admin_order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_no` VARCHAR(32) NOT NULL,
  `store_id` BIGINT UNSIGNED NOT NULL COMMENT '店铺ID',
  `spu_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '类目ID(冗余,佣金/报表按类目统计用,见8.3.1)',
  `sku_name` VARCHAR(200) NOT NULL COMMENT 'SKU名称(冗余)',
  `sku_image` VARCHAR(500) DEFAULT NULL,
  `sku_specs` JSON DEFAULT NULL,
  `price` DECIMAL(10,2) NOT NULL COMMENT '单价',
  `quantity` INT NOT NULL COMMENT '数量',
  `total_amount` DECIMAL(12,2) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_store_id` (`store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单商品表';

-- 订单状态流水表
CREATE TABLE `admin_order_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_no` VARCHAR(32) NOT NULL,
  `from_status` TINYINT DEFAULT NULL,
  `to_status` TINYINT NOT NULL,
  `operator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '操作人',
  `operator_name` VARCHAR(50) DEFAULT NULL,
  `operator_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1-管理员,2-系统,3-用户',
  `remark` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态流水表';

-- 订单操作日志表(记录所有管理操作)
CREATE TABLE `admin_order_operation_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_no` VARCHAR(32) NOT NULL,
  `operation_type` VARCHAR(50) NOT NULL COMMENT '操作类型',
  `operator_id` BIGINT UNSIGNED NOT NULL,
  `operator_name` VARCHAR(50) DEFAULT NULL,
  `before_data` TEXT DEFAULT NULL,
  `after_data` TEXT DEFAULT NULL,
  `remark` VARCHAR(500) DEFAULT NULL,
  `ip` VARCHAR(50) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单操作日志表';

-- 售后表
CREATE TABLE `admin_after_sale` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `after_sale_no` VARCHAR(32) NOT NULL COMMENT '售后单号',
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_no` VARCHAR(32) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-退款,2-退货退款,3-换货',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待审核,1-待退货,2-待退款,3-已完成,4-已拒绝',
  `reason` VARCHAR(255) NOT NULL COMMENT '原因',
  `description` TEXT DEFAULT NULL,
  `images` JSON DEFAULT NULL COMMENT '凭证图片',
  `refund_amount` DECIMAL(10,2) DEFAULT NULL,
  `audit_remark` VARCHAR(500) DEFAULT NULL,
  `handled_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '处理人',
  `handled_at` DATETIME DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_after_sale_no` (`after_sale_no`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后表';
```

##### 库存模块

```sql
-- 仓库表
CREATE TABLE `admin_warehouse` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(50) NOT NULL COMMENT '仓库编码',
  `name` VARCHAR(100) NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-自营仓,3-第三方仓',
  `province` VARCHAR(50) DEFAULT NULL,
  `city` VARCHAR(50) DEFAULT NULL,
  `address` VARCHAR(255) DEFAULT NULL,
  `contact_name` VARCHAR(50) DEFAULT NULL,
  `contact_phone` VARCHAR(20) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库表';

-- 库存表
CREATE TABLE `admin_inventory` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `warehouse_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `available_stock` INT NOT NULL DEFAULT 0 COMMENT '可用库存',
  `locked_stock` INT NOT NULL DEFAULT 0 COMMENT '锁定库存',
  `frozen_stock` INT NOT NULL DEFAULT 0 COMMENT '冻结库存',
  `warning_stock` INT NOT NULL DEFAULT 10 COMMENT '预警库存',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_warehouse_sku` (`warehouse_id`, `sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存表';

-- 库存流水表(按月分表)
CREATE TABLE `admin_inventory_log_202401` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `warehouse_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `change_type` TINYINT NOT NULL COMMENT '变动类型:1-入库,2-出库,3-盘点,4-调拨入,5-调拨出,6-锁定,7-解锁',
  `quantity` INT NOT NULL COMMENT '变动数量(正数增加,负数减少)',
  `before_stock` INT NOT NULL,
  `after_stock` INT NOT NULL,
  `biz_type` VARCHAR(50) DEFAULT NULL COMMENT '业务类型(order/refund/check)',
  `biz_no` VARCHAR(50) DEFAULT NULL COMMENT '业务单号',
  `operator_id` BIGINT UNSIGNED DEFAULT NULL,
  `operator_name` VARCHAR(50) DEFAULT NULL,
  `remark` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_warehouse_sku` (`warehouse_id`, `sku_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水表';

-- 库存盘点表
CREATE TABLE `admin_inventory_check` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `check_no` VARCHAR(32) NOT NULL COMMENT '盘点单号',
  `warehouse_id` BIGINT UNSIGNED NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-全盘,2-抽盘',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待盘点,1-盘点中,2-已完成',
  `total_count` INT NOT NULL DEFAULT 0 COMMENT '盘点总数',
  `diff_count` INT NOT NULL DEFAULT 0 COMMENT '差异数',
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_warehouse_id` (`warehouse_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存盘点表';

-- 库存调拨表
CREATE TABLE `admin_inventory_transfer` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `transfer_no` VARCHAR(32) NOT NULL COMMENT '调拨单号',
  `from_warehouse_id` BIGINT UNSIGNED NOT NULL,
  `to_warehouse_id` BIGINT UNSIGNED NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待调拨,1-调拨中,2-已完成,3-已取消',
  `total_count` INT NOT NULL DEFAULT 0,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存调拨表';
```

##### 会员模块

```sql
-- 会员等级表
CREATE TABLE `admin_member_level` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  `level` INT NOT NULL COMMENT '等级',
  `min_points` INT NOT NULL DEFAULT 0 COMMENT '最低积分',
  `discount` DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '折扣率',
  `icon` VARCHAR(500) DEFAULT NULL,
  `benefits` JSON DEFAULT NULL COMMENT '权益说明',
  `status` TINYINT NOT NULL DEFAULT 1,
  `sort` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员等级表';

-- 会员积分规则表
CREATE TABLE `admin_points_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `action` VARCHAR(50) NOT NULL COMMENT '动作(login/order/review/share)',
  `points` INT NOT NULL COMMENT '积分数',
  `daily_limit` INT DEFAULT NULL COMMENT '每日上限',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分规则表';

-- 会员标签表
CREATE TABLE `admin_member_tag` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-系统,2-自定义',
  `rule` JSON DEFAULT NULL COMMENT '规则条件',
  `member_count` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员标签表';

-- 会员标签关联表
CREATE TABLE `admin_member_tag_relation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `tag_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tag` (`user_id`, `tag_id`),
  KEY `idx_tag_id` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员标签关联表';
```

##### 营销模块

```sql
-- 优惠券表
CREATE TABLE `admin_coupon` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-满减券,2-折扣券,3-无门槛券',
  `value` DECIMAL(10,2) NOT NULL COMMENT '面额/折扣',
  `min_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '最低消费',
  `total_count` INT NOT NULL DEFAULT 0 COMMENT '发放总量',
  `received_count` INT NOT NULL DEFAULT 0 COMMENT '已领取',
  `used_count` INT NOT NULL DEFAULT 0 COMMENT '已使用',
  `per_limit` INT NOT NULL DEFAULT 1 COMMENT '每人限领',
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`, `start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券表';

-- 优惠券领取记录表
CREATE TABLE `admin_coupon_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coupon_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-未使用,1-已使用,2-已过期',
  `used_time` DATETIME DEFAULT NULL,
  `used_order_no` VARCHAR(32) DEFAULT NULL,
  `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_coupon_user` (`coupon_id`, `user_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券领取记录表';

-- 满减活动表
CREATE TABLE `admin_full_reduction` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-满金额减,2-满件数减',
  `rules` JSON NOT NULL COMMENT '规则[{threshold:100,reduction:20}]',
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='满减活动表';

-- 秒杀活动表
CREATE TABLE `admin_seckill` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `seckill_price` DECIMAL(10,2) NOT NULL,
  `stock` INT NOT NULL,
  `sold_count` INT NOT NULL DEFAULT 0,
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_time_status` (`start_time`, `end_time`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- 拼团活动表（v1.2 跨文件同步：字段与 miniprogram-backend.md 7.4 逐项对齐）
CREATE TABLE `admin_group_buy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '活动ID，即买家端发起拼团入参 activityId',
  `name` VARCHAR(100) NOT NULL,
  `spu_id` BIGINT UNSIGNED NOT NULL COMMENT 'SPU ID（与买家端 group_buying.spu_id 同源）',
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `group_price` DECIMAL(10,2) NOT NULL COMMENT '拼团价（口径以 core-price 试算为准，见7.3）',
  `group_size` INT NOT NULL COMMENT '成团人数',
  `stock` INT NOT NULL COMMENT '活动库存池（独立于普通库存与秒杀池，团失败回补，见miniprogram 7.4.4）',
  `limit_per_user` INT NOT NULL DEFAULT 0 COMMENT '单用户可参团次数上限，0-不限',
  `expire_minutes` INT NOT NULL DEFAULT 1440 COMMENT '成团有效时长(分钟)：买家端 group_buying.expire_time=发起时间+本值',
  `auto_success` TINYINT NOT NULL DEFAULT 0 COMMENT '到期自动成团(虚拟补位)：0-否,1-是（7.4.4 虚拟拼团，补位记审计日志）',
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拼团活动表';

-- 砍价活动配置表（v1.2 跨文件同步：管理端 admin-marketing 为主数据维护方）
-- 字段与 miniprogram-backend.md 7.5.3 `bargain_activity` 逐值一致；买家端 `bargain_record`（用户砍价单）
-- 与 `bargain_help_record`（帮砍记录）为运行时数据，由分享裂变服务写入，管理端只读查询、不另建表。
CREATE TABLE `admin_bargain_activity` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `activity_id` BIGINT UNSIGNED NOT NULL COMMENT '活动ID，即买家端入参 activityId',
  `spu_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(100) NOT NULL COMMENT '活动名称',
  `original_price` DECIMAL(10,2) NOT NULL COMMENT '原价',
  `target_price` DECIMAL(10,2) NOT NULL COMMENT '砍到底价（服务端保护线，不得低于成本价）',
  `min_cut_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.01 COMMENT '单次最小砍价金额（保护下限）',
  `help_limit` INT NOT NULL DEFAULT 20 COMMENT '单个砍价单最多帮砍人数',
  `daily_create_limit` INT NOT NULL DEFAULT 1 COMMENT '单用户每日发起次数上限',
  `stock` INT NOT NULL DEFAULT 0 COMMENT '活动库存池，0-不限',
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_id` (`activity_id`),
  KEY `idx_status_time` (`status`, `start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='砍价活动配置表';
```

##### 财务模块

```sql
-- 结算周期表
CREATE TABLE `admin_settlement_cycle` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-T+1,2-T+7,3-月结',
  `days` INT NOT NULL COMMENT '结算天数',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算周期表';

-- 商家结算表(按月分表)
CREATE TABLE `admin_settlement_202401` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `settlement_no` VARCHAR(32) NOT NULL,
  `store_id` BIGINT UNSIGNED NOT NULL,
  `store_name` VARCHAR(100) DEFAULT NULL,
  `period_start` DATE NOT NULL,
  `period_end` DATE NOT NULL,
  `order_count` INT NOT NULL DEFAULT 0,
  `total_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '订单总额',
  `commission_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '佣金',
  `refund_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '退款金额',
  `settlement_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '结算金额',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待结算,1-结算中,2-已结算,3-已打款',
  `settled_at` DATETIME DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_no` (`settlement_no`),
  KEY `idx_store_id` (`store_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家结算表';

-- 佣金规则表
CREATE TABLE `admin_commission_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `category_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '类目ID(null=默认)',
  `store_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '店铺ID(null=默认)',
  `rate` DECIMAL(5,4) NOT NULL COMMENT '佣金比例(0.0500=5%)',
  `min_commission` DECIMAL(10,2) NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金规则表';

-- 提现申请表
CREATE TABLE `admin_withdraw` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `withdraw_no` VARCHAR(32) NOT NULL,
  `store_id` BIGINT UNSIGNED NOT NULL,
  `store_name` VARCHAR(100) DEFAULT NULL,
  `amount` DECIMAL(12,2) NOT NULL,
  `fee` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '手续费',
  `actual_amount` DECIMAL(12,2) NOT NULL COMMENT '实际到账',
  `type` TINYINT NOT NULL COMMENT '类型:1-银行卡,2-支付宝,3-微信',
  `account_info` JSON NOT NULL COMMENT '账户信息(加密)',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '两级审批(与前端9.4一致):0-待初审,1-初审通过待复审,2-复审通过待打款,3-已打款,4-已拒绝',
  `first_audit_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '初审人',
  `first_audit_at` DATETIME DEFAULT NULL,
  `second_audit_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '复审人',
  `second_audit_at` DATETIME DEFAULT NULL,
  `audit_remark` VARCHAR(500) DEFAULT NULL,
  `handled_by` BIGINT UNSIGNED DEFAULT NULL,
  `handled_at` DATETIME DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_store_id` (`store_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现申请表';

-- 发票申请表
CREATE TABLE `admin_invoice` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `invoice_no` VARCHAR(32) NOT NULL,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_no` VARCHAR(32) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-个人,2-企业',
  `title` VARCHAR(100) NOT NULL COMMENT '发票抬头',
  `tax_no` VARCHAR(50) DEFAULT NULL COMMENT '税号',
  `amount` DECIMAL(10,2) NOT NULL,
  `content` VARCHAR(50) NOT NULL COMMENT '发票内容',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待开票,1-已开票,2-已邮寄',
  `invoice_file` VARCHAR(500) DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票申请表';

-- 对账表(按日期分表)
CREATE TABLE `admin_reconciliation_20240101` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type` TINYINT NOT NULL COMMENT '类型:1-支付对账,2-退款对账',
  `channel` VARCHAR(20) NOT NULL COMMENT '渠道(alipay/wechat)',
  `channel_no` VARCHAR(64) NOT NULL COMMENT '渠道流水号',
  `order_no` VARCHAR(32) DEFAULT NULL,
  `amount` DECIMAL(10,2) NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-未对账,1-已对账,2-异常',
  `reconciled_at` DATETIME DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_channel_no` (`channel_no`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账表';
```

##### 店铺模块

```sql
-- 店铺表
CREATE TABLE `admin_store` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `store_code` VARCHAR(50) NOT NULL,
  `store_name` VARCHAR(100) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '店主用户ID',
  `category_id` BIGINT UNSIGNED NOT NULL COMMENT '经营类目',
  `logo` VARCHAR(500) DEFAULT NULL,
  `description` VARCHAR(500) DEFAULT NULL,
  `type` TINYINT NOT NULL DEFAULT 1 COMMENT '类型:1-个人,2-企业',
  `level` TINYINT NOT NULL DEFAULT 1 COMMENT '店铺等级',
  `dsr_score` DECIMAL(2,1) NOT NULL DEFAULT 5.0 COMMENT 'DSR评分',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待审核,1-正常,2-关闭,3-冻结',
  `audit_remark` VARCHAR(500) DEFAULT NULL,
  `deposit` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '保证金',
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_code` (`store_code`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺表';

-- 店铺等级表
CREATE TABLE `admin_store_level` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  `level` INT NOT NULL,
  `commission_rate` DECIMAL(5,4) NOT NULL COMMENT '佣金比例',
  `deposit` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '保证金要求',
  `benefits` JSON DEFAULT NULL,
  `sort` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺等级表';

-- 保证金记录表
CREATE TABLE `admin_deposit_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `store_id` BIGINT UNSIGNED NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-缴纳,2-扣除,3-退还',
  `amount` DECIMAL(10,2) NOT NULL,
  `before_balance` DECIMAL(10,2) NOT NULL,
  `after_balance` DECIMAL(10,2) NOT NULL,
  `reason` VARCHAR(255) DEFAULT NULL,
  `operator_id` BIGINT UNSIGNED DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_store_id` (`store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保证金记录表';
```

##### 客服模块

```sql
-- 客服工单表
CREATE TABLE `admin_ticket` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_no` VARCHAR(32) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `user_name` VARCHAR(50) DEFAULT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-商品咨询,2-订单问题,3-售后问题,4-投诉建议',
  `priority` TINYINT NOT NULL DEFAULT 2 COMMENT '优先级:1-低,2-中,3-高,4-紧急',
  `title` VARCHAR(200) NOT NULL,
  `content` TEXT NOT NULL,
  `images` JSON DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待处理,1-处理中,2-已解决,3-已关闭',
  `assigned_to` BIGINT UNSIGNED DEFAULT NULL COMMENT '处理人',
  `assigned_name` VARCHAR(50) DEFAULT NULL,
  `resolved_at` DATETIME DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_assigned_to` (`assigned_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单表';

-- 客服会话表
CREATE TABLE `admin_cs_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(32) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `cs_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '客服ID',
  `cs_name` VARCHAR(50) DEFAULT NULL,
  `user_name` VARCHAR(50) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0-已结束,1-进行中',
  `start_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `end_time` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_cs_user_id` (`cs_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话表';

-- 聊天消息表
CREATE TABLE `admin_cs_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(32) NOT NULL,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `sender_type` TINYINT NOT NULL COMMENT '1-用户,2-客服',
  `content` TEXT NOT NULL,
  `content_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1-文本,2-图片,3-商品卡片',
  `is_read` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';

-- 智能客服配置表
CREATE TABLE `admin_smart_cs_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `trigger_type` TINYINT NOT NULL COMMENT '触发类型:1-关键词,2-意图识别',
  `keywords` JSON DEFAULT NULL COMMENT '触发关键词',
  `intent` VARCHAR(100) DEFAULT NULL COMMENT '意图名称',
  `reply_content` TEXT NOT NULL,
  `reply_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1-文本,2-转人工',
  `sort` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能客服配置表';
```

##### 审核模块

```sql
-- 审核任务表
CREATE TABLE `admin_audit_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `audit_no` VARCHAR(32) NOT NULL,
  `biz_type` TINYINT NOT NULL COMMENT '业务类型:1-商品,2-店铺,3-售后',
  `biz_id` BIGINT UNSIGNED NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `submitter_id` BIGINT UNSIGNED NOT NULL,
  `submitter_name` VARCHAR(50) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待审核,1-审核中,2-通过,3-拒绝',
  `priority` TINYINT NOT NULL DEFAULT 2,
  `assigned_to` BIGINT UNSIGNED DEFAULT NULL,
  `assigned_name` VARCHAR(50) DEFAULT NULL,
  `audit_remark` VARCHAR(500) DEFAULT NULL,
  `audit_time` DATETIME DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_status` (`status`),
  KEY `idx_assigned_to` (`assigned_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核任务表';

-- 工作流定义表
CREATE TABLE `admin_workflow_definition` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `code` VARCHAR(50) NOT NULL,
  `biz_type` TINYINT NOT NULL COMMENT '业务类型',
  `nodes` JSON NOT NULL COMMENT '节点配置',
  `edges` JSON NOT NULL COMMENT '流转配置',
  `status` TINYINT NOT NULL DEFAULT 1,
  `version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流定义表';
```

##### 消息模块

```sql
-- 消息模板表
CREATE TABLE `admin_message_template` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `code` VARCHAR(50) NOT NULL,
  `type` TINYINT NOT NULL COMMENT '类型:1-站内信,2-短信,3-邮件,4-推送',
  `title` VARCHAR(200) NOT NULL,
  `content` TEXT NOT NULL,
  `params` JSON DEFAULT NULL COMMENT '参数说明',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息模板表';

-- 站内信表
CREATE TABLE `admin_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `template_id` BIGINT UNSIGNED DEFAULT NULL,
  `sender_id` BIGINT UNSIGNED DEFAULT NULL,
  `sender_name` VARCHAR(50) DEFAULT NULL,
  `receiver_id` BIGINT UNSIGNED NOT NULL,
  `receiver_name` VARCHAR(50) DEFAULT NULL,
  `title` VARCHAR(200) NOT NULL,
  `content` TEXT NOT NULL,
  `type` TINYINT NOT NULL DEFAULT 1,
  `is_read` TINYINT NOT NULL DEFAULT 0,
  `read_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_receiver` (`receiver_id`, `is_read`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内信表';

-- 推送记录表
CREATE TABLE `admin_push_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `platform` VARCHAR(20) NOT NULL COMMENT '平台(android/ios/web)',
  `title` VARCHAR(200) NOT NULL,
  `content` TEXT NOT NULL,
  `biz_type` VARCHAR(50) DEFAULT NULL,
  `biz_id` BIGINT UNSIGNED DEFAULT NULL,
  `push_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待推送,1-已推送,2-失败',
  `read_status` TINYINT NOT NULL DEFAULT 0,
  `pushed_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送记录表';
```

##### 内容模块

```sql
-- Banner表
CREATE TABLE `admin_banner` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `image` VARCHAR(500) NOT NULL,
  `link` VARCHAR(500) DEFAULT NULL,
  `platform` VARCHAR(20) NOT NULL DEFAULT 'all' COMMENT '平台(h5/app/pc)',
  `position` VARCHAR(50) NOT NULL COMMENT '位置(home/category/...)',
  `sort` INT NOT NULL DEFAULT 0,
  `start_time` DATETIME DEFAULT NULL,
  `end_time` DATETIME DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_position` (`position`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Banner表';

-- 公告表
CREATE TABLE `admin_notice` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(200) NOT NULL,
  `content` TEXT NOT NULL,
  `type` TINYINT NOT NULL DEFAULT 1 COMMENT '1-普通,2-重要',
  `is_top` TINYINT NOT NULL DEFAULT 0,
  `publish_time` DATETIME NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告表';

-- 专题页表
CREATE TABLE `admin_topic` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `cover_image` VARCHAR(500) DEFAULT NULL,
  `page_content` LONGTEXT DEFAULT NULL COMMENT '页面JSON配置',
  `start_time` DATETIME DEFAULT NULL,
  `end_time` DATETIME DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专题页表';

-- CMS文章表
CREATE TABLE `admin_cms_article` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(200) NOT NULL,
  `category` VARCHAR(50) DEFAULT NULL,
  `content` LONGTEXT NOT NULL,
  `summary` VARCHAR(500) DEFAULT NULL,
  `cover` VARCHAR(500) DEFAULT NULL,
  `views` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-草稿,1-已发布',
  `version` INT NOT NULL DEFAULT 0,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS文章表';
```

##### 数据模块

```sql
-- 报表配置表
CREATE TABLE `admin_report_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `code` VARCHAR(50) NOT NULL,
  `type` TINYINT NOT NULL COMMENT '1-日报,2-周报,3-月报,4-实时',
  `data_source` VARCHAR(200) NOT NULL COMMENT '数据源SQL/API',
  `params` JSON DEFAULT NULL COMMENT '参数配置',
  `chart_type` VARCHAR(20) DEFAULT NULL COMMENT '图表类型',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表配置表';

-- 导出任务表
CREATE TABLE `admin_export_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no` VARCHAR(32) NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `type` VARCHAR(50) NOT NULL COMMENT '导出类型(order/product/...)',
  `params` JSON DEFAULT NULL COMMENT '查询条件',
  `file_url` VARCHAR(500) DEFAULT NULL,
  `file_size` BIGINT DEFAULT NULL,
  `total_count` INT DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待处理,1-处理中,2-已完成,3-失败',
  `error_msg` VARCHAR(500) DEFAULT NULL,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_created_by` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出任务表';
```

##### 系统模块

```sql
-- 系统参数表
CREATE TABLE `admin_system_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `config_key` VARCHAR(100) NOT NULL COMMENT '配置键',
  `config_value` TEXT DEFAULT NULL COMMENT '配置值',
  `type` VARCHAR(20) NOT NULL DEFAULT 'string' COMMENT '类型(string/int/json)',
  `description` VARCHAR(255) DEFAULT NULL,
  `is_system` TINYINT NOT NULL DEFAULT 0 COMMENT '是否系统级',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数表';

-- 配置变更历史表(支持版本回滚,对应前端12.3"配置版本")
CREATE TABLE `admin_config_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(100) NOT NULL,
  `old_value` TEXT DEFAULT NULL,
  `new_value` TEXT DEFAULT NULL,
  `operator_id` BIGINT UNSIGNED NOT NULL,
  `operator_name` VARCHAR(50) DEFAULT NULL,
  `reason` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置变更历史表';

-- 数据字典表
CREATE TABLE `admin_dict_type` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `code` VARCHAR(50) NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典类型表';

CREATE TABLE `admin_dict_data` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type_id` BIGINT UNSIGNED NOT NULL,
  `label` VARCHAR(100) NOT NULL,
  `value` VARCHAR(100) NOT NULL,
  `sort` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_id` (`type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典数据表';

-- 登录日志表(按月分表)
CREATE TABLE `admin_login_log_202401` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED DEFAULT NULL,
  `username` VARCHAR(50) DEFAULT NULL,
  `type` TINYINT NOT NULL COMMENT '1-登录,2-登出',
  `ip` VARCHAR(50) DEFAULT NULL,
  `location` VARCHAR(100) DEFAULT NULL,
  `user_agent` VARCHAR(500) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0-失败,1-成功',
  `error_msg` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志表';

-- 定时任务表
CREATE TABLE `admin_scheduled_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `cron` VARCHAR(50) NOT NULL,
  `class_name` VARCHAR(200) NOT NULL,
  `method_name` VARCHAR(100) NOT NULL,
  `params` VARCHAR(500) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0-停止,1-运行',
  `last_execute_time` DATETIME DEFAULT NULL,
  `next_execute_time` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务表';
```

---

### 1.3 缓存策略

#### 1.3.1 缓存层级

```
┌─────────────────────────────────────────────────────────┐
│                    客户端缓存                            │
│  (浏览器LocalStorage / APP本地缓存)                      │
└─────────────────────────┬───────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────┐
│                   CDN缓存                                │
│  (静态资源 / 商品详情页)                                 │
└─────────────────────────┬───────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────┐
│                 Nginx缓存                                │
│  (页面片段 / API响应)                                    │
└─────────────────────────┬───────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────┐
│              Redis集群 (核心缓存)                        │
│  ┌─────────────┐ ┌─────────────┐ ┌────────────────────┐ │
│  │ 本地缓存    │ │ Redis主从   │ │ Redis Cluster      │ │
│  │ (Caffeine)  │ │ (Sentinel)  │ │ (分布式缓存)       │ │
│  └─────────────┘ └─────────────┘ └────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

**Redis部署方案**：
- 开发环境：单节点 + Sentinel
- 生产环境：Redis Cluster（6节点，3主3从）
- 本地缓存：Caffeine（仅用于热点数据，如类目树）
- **实例隔离（v1.1 修正）**：缓存与"结构化数据"必须分实例/分库，禁止混用：
  - **缓存实例**：`maxmemory-policy = allkeys-lru`，只放可丢失的数据（商品、类目、字典、首页）；
  - **数据实例**：`maxmemory-policy = noeviction`（或 volatile-lru），存放**不可丢失数据**——分布式锁、幂等去重表、Token 黑名单/Refresh Token、限流计数、会话、队列消费位点。
  - 原因：docker-compose 中 `allkeys-lru` 会让 Redis 主动淘汰**锁与黑名单**，直接导致幂等失效、已注销 Token 复活、重复导出/重复扣减等资金级事故。

#### 1.3.2 缓存使用规范

| 数据类型 | 缓存策略 | 过期时间 | 说明 |
|---------|---------|---------|------|
| 用户会话 | Redis Hash | 30分钟 | 滑动过期 |
| 权限数据 | Redis Hash | 10分钟 | 角色权限变更时主动刷新 |
| 系统配置 | Redis String | 永久 | 变更时更新 |
| 商品详情 | Redis Hash | 30分钟 | 商品变更时删除 |
| 类目树 | Redis String | 1小时 | 定时刷新 |
| 订单详情 | Redis Hash | 10分钟 | 订单状态变更时删除 |
| 会员信息 | Redis Hash | 30分钟 | 会员信息变更时删除 |
| 营销活动 | Redis String | 活动结束 | 活动变更时删除 |
| 数据字典 | Redis Hash | 永久 | 变更时更新 |
| 首页数据 | Redis String | 5分钟 | 定时刷新 |

#### 1.3.3 缓存问题与解决方案

| 问题 | 解决方案 | 实现 |
|------|---------|------|
| 缓存穿透 | 空值缓存 + 布隆过滤器 | Redis + BloomFilter |
| 缓存击穿 | 互斥锁 + 过期时间随机 | Redis分布式锁 |
| 缓存雪崩 | 过期时间随机 + 多级缓存 | 本地缓存 + Redis |
| 缓存一致性 | 延迟双删 + Canal订阅 | Canal + RabbitMQ |

**缓存一致性保障**：
- 采用**延迟双删**策略：先删缓存 → 更新DB → 延迟500ms → 再删缓存
- Canal监听binlog，异步更新缓存
- 关键数据（如库存）采用**先更新DB，再删缓存**策略

**三种机制的分工（v1.1 补充，避免"三套全上"过度设计）**：
| 机制 | 适用对象 | 说明 |
|------|---------|------|
| 延迟双删 | 默认方案 | 无 Canal 依赖时的兜底，覆盖 95% 写路径 |
| Canal 订阅 binlog | 只对**热点只读**数据启用 | 商品详情、类目树、字典；P1 阶段再引入，需评估运维成本 |
| 定时对账 | 资金/库存等关键数据 | 见 13.2.2，与缓存无关，修复的是 DB 级不一致 |

- 上表"永久"过期时间的键（系统配置、数据字典）必须存放于 **noeviction 数据实例**（见 1.3.1 实例隔离），否则会被 LRU 淘汰，出现"配置偶发回滚到旧值"的诡异故障。
- 缓存更新统一走"**先更新DB → 删缓存 → 发MQ广播失效**"，本地 Caffeine 通过广播失效，禁止本地缓存自行设长过期。

---

### 1.4 消息队列

#### 1.4.1 RabbitMQ 队列设计

| 交换机 | 路由Key | 队列 | 消费者 | 用途 |
|--------|---------|------|--------|------|
| `admin.order.exchange` | `order.created` | `order.created.queue` | admin-data, admin-finance | 订单创建事件 |
| `admin.order.exchange` | `order.paid` | `order.paid.queue` | admin-inventory, admin-finance | 订单支付事件 |
| `admin.order.exchange` | `order.refunded` | `order.refunded.queue` | admin-inventory, admin-finance | 订单退款事件 |
| `admin.product.exchange` | `product.created` | `product.created.queue` | admin-search, admin-data | 商品创建事件 |
| `admin.product.exchange` | `product.updated` | `product.updated.queue` | admin-search | 商品更新事件 |
| `admin.product.exchange` | `product.deleted` | `product.deleted.queue` | admin-search | 商品删除事件 |
| `admin.member.exchange` | `member.registered` | `member.registered.queue` | admin-marketing, admin-data | 会员注册事件 |
| `admin.member.exchange` | `user.deleted` | `user.deleted.queue` | core-user, admin-data | 用户注销跨端删除（**v1.2 跨文件同步，来源 ios-backend.md 12.1.2**：生产者=买家端账户注销，冷静期结束后广播；`core-user` 删除会员主数据与第三方账号绑定，`admin-data` 清理画像/标签/看板缓存中的个人数据，法定必须留存的交易、结算与发票数据除外——否则存在"注销后管理端仍保留个人数据"的 PIPL 合规缺口） |
| `admin.audit.exchange` | `audit.submitted` | `audit.submitted.queue` | admin-message | 审核提交事件 |
| `admin.audit.exchange` | `audit.completed` | `audit.completed.queue` | admin-message | 审核完成事件 |
| `admin.export.exchange` | `export.request` | `export.request.queue` | admin-data | 导出任务事件 |
| `admin.message.exchange` | `message.send` | `message.send.queue` | admin-message | 消息发送事件 |
| `admin.order.exchange` | `order.cancelled` | `order.cancelled.queue` | admin-inventory, admin-data | 订单取消（**v1.1 补充：释放锁定库存**，见4.4.4） |
| `admin.order.exchange` | `order.refund.applied` | `order.refund.applied.queue` | admin-audit, admin-message, admin-data | 售后申请提交 |
| `admin.audit.exchange` | `audit.returned` | `audit.returned.queue` | admin-message, admin-search | 审核"退回修改"（v1.1 补充，与前端4.3对齐） |
| `admin.store.exchange` | `store.audited` | `store.audited.queue` | admin-message, admin-finance | 店铺审核结果（v1.1 补充） |

> **交换机类型**：所有交换机统一为 `topic`，路由Key 形如 `order.created`、避免使用 fanout（需要按事件选择性消费）。**死信队列（DLX）**统一挂 `admin.dlx.exchange`，队列声明时设置 `x-dead-letter-exchange`；消费失败按 `1s / 5s / 30s` 三次重试后进 DLQ，DLQ 消息必须有监控告警（12.2）与人工重投接口，**禁止无限重试造成毒消息循环**。
>
> **消费端要求**：每个队列必须①手动 ACK、②用 1.4.3 去重表幂等、③消费逻辑幂等可重入、④单条消息处理超时 30s 断开重投；消费者宕机导致的堆积由 12.1.1 的"RabbitMQ 堆积"告警覆盖。
>
> **合规事件要求（v1.2 跨文件同步）**：`user.deleted` 为强合规事件，注销冷静期结束后 **30 分钟内**必须消费完成；重试 3 次仍失败要单独告警并转人工处置（不允许静默滞留 DLQ），删除结果回执写入 `admin-data` 审计表作为 PIPL"注销即删"举证，并纳入第十六章上线计划的验收项（见 16.1，与 ios-backend.md 13.5 Checklist 联调）。消费时按 1.4.3 去重表幂等，重复投递不得产生二次删除副作用。

> **MQ 演进（v1.3 跨架构裁决 C2）**：P0/P1 沿用 RabbitMQ 现设计（上表交换机/队列拓扑不变）；**P2 阶段一次性迁移 RocketMQ 5.x**，队列/交换机映射为 Topic/ConsumerGroup（事件名一个不改，交换机名降级为 Tag，队列名映射为消费组），30 个事件清单与顺序/事务消息约束见 `architecture/04-messaging.md` §7；上文的幂等（1.4.3）、死信与重试约定在 RocketMQ 侧继续生效。

#### 1.4.2 消息格式

```json
{
  "messageId": "msg_20240101120000_abc123",
  "exchange": "admin.order.exchange",
  "routingKey": "order.created",
  "timestamp": 1704076800000,
  "data": {
    "orderNo": "202401011200001",
    "userId": 10001,
    "payAmount": 299.00,
    "items": [
      {"skuId": 1001, "quantity": 2}
    ]
  }
}
```

#### 1.4.3 幂等性保证

- 消息消费端使用**Redis分布式锁** + **消息去重表**
- 每条消息包含唯一 `messageId`，消费前检查是否已处理
- 业务操作使用**乐观锁**保证幂等
- 消息去重表结构：

```sql
CREATE TABLE `admin_message_dedup` (
  `message_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(50) NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-处理中,1-已完成',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`message_id`, `consumer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息去重表';
```

#### 1.4.4 消息可靠性保障

- **消息持久化**：所有消息设置为持久化
- ** Publisher Confirms**：生产者确认机制
- **Consumer Acknowledges**：消费者手动ACK
- **死信队列**：处理失败的消息进入死信队列，人工介入
- **消息补偿**：定时任务扫描未处理消息，重新投递

---

### 1.5 部署方案

#### 1.5.1 容器化部署

```yaml
# docker-compose.yml (开发环境)
version: '3.8'
services:
  admin-gateway:
    image: admin-gateway:latest
    ports:
      - "8080:8080"
    environment:
      # 网关为 Go 服务，不使用 Spring 配置（v1.1 修正）
      - GATEWAY_ENV=dev
      - AUTH_SERVICE_URL=admin-auth:8081
      - NACOS_ADDR=nacos:8848
      - REDIS_HOST=redis
      - REDIS_DB=1
    depends_on:
      - redis
      - nacos
      - rabbitmq

  admin-auth:
    image: admin-auth:latest
    # ... 其他服务配置

  nacos:
    image: nacos/nacos-server:v2.3.2
    ports:
      - "8848:8848"
      - "9848:9848"
    environment:
      - MODE=standalone
      - NACOS_AUTH_ENABLE=false

  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
    volumes:
      - mysql_data:/var/lib/mysql

  redis:
    image: redis:7
    ports:
      - "6379:6379"
    command: redis-server --requirepass ${REDIS_PASSWORD} --maxmemory 2gb --maxmemory-policy allkeys-lru
    # 注意：此实例仅作缓存；锁/黑名单/幂等键使用第二个实例（--maxmemory-policy noeviction），
    # 生产环境对应 1.3.1 的"缓存实例 + 数据实例"隔离要求。

  redis-data:
    image: redis:7
    ports:
      - "6380:6379"
    command: redis-server --requirepass ${REDIS_PASSWORD} --maxmemory 2gb --maxmemory-policy noeviction

  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      - RABBITMQ_DEFAULT_USER=${RABBITMQ_USER}
      - RABBITMQ_DEFAULT_PASS=${RABBITMQ_PASSWORD}

  elasticsearch:
    image: elasticsearch:8.15.0
    ports:
      - "9200:9200"
    environment:
      - discovery.type=single-node
      - ES_JAVA_OPTS=-Xms1g -Xmx1g

volumes:
  mysql_data:
```

#### 1.5.2 Kubernetes部署

```
┌─────────────────────────────────────────────────────────────┐
│                      K8s Cluster                             │
│  ┌─────────────┐ ┌─────────────┐ ┌────────────────────────┐ │
│  │ Namespace   │ │ Namespace   │ │ Namespace              │ │
│  │ admin-dev   │ │ admin-prod  │ │ infra                  │ │
│  │             │ │             │ │ (mysql/redis/rabbitmq) │ │
│  │ Deployment  │ │ Deployment  │ │                        │ │
│  │ - gateway   │ │ - gateway   │ │ StatefulSet            │ │
│  │ - auth      │ │ - auth      │ │ - mysql                │ │
│  │ - product   │ │ - product   │ │ - redis-cluster        │ │
│  │ - order     │ │ - order     │ │                        │ │
│  │ - ...       │ │ - ...       │ │                        │ │
│  │             │ │             │ │                        │ │
│  │ HPA:        │ │ HPA:        │ │                        │ │
│  │ min:2 max:10│ │ min:3 max:20│ │                        │ │
│  └─────────────┘ └─────────────┘ └────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

#### 1.5.3 CI/CD流程

```
代码提交 → GitLab → Jenkins/GitHub Actions
                          │
                          ▼
              ┌─────────────────────┐
              │   单元测试 + 代码扫描  │
              │   (SonarQube/JaCoCo) │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │   构建Docker镜像     │
              │   推送到Harbor      │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │   部署到测试环境      │
              │   自动化测试          │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │   人工验收           │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │   灰度发布(5%→50%)  │
              │   全量发布           │
              └─────────────────────┘
```

---

## 二、认证与权限

### 2.1 认证方案

#### 2.1.1 JWT + Refresh Token 双Token机制

```
┌──────────┐    登录请求      ┌──────────┐
│  前端     │ ──────────────► │ 认证服务  │
│          │                 │          │
│          │ ◄────────────── │          │
│          │  Access Token   │          │
│          │  Refresh Token  │          │
└──────────┘                 └──────────┘
     │
     │  业务请求(携带Access Token)
     ▼
┌──────────┐
│  API网关  │ ──► 验证Token ──► 放行/拒绝
└──────────┘
     │
     │  Token过期
     ▼
┌──────────┐    刷新Token      ┌──────────┐
│  前端     │ ──────────────► │ 认证服务  │
│          │                 │          │
│          │ ◄────────────── │          │
│          │  新Access Token │          │
└──────────┘                 └──────────┘
```

#### 2.1.2 Token规格

| Token | 有效期 | 客户端载体 | 服务端存储 | 说明 |
|-------|--------|-----------|-----------|------|
| Access Token | 2小时 | **内存变量**，经 `Authorization: Bearer` 携带（不落 localStorage/sessionStorage） | 无需存储，签名自校验 + 黑名单反查 | 包含用户ID、角色、权限，见 2.1.3 |
| Refresh Token | 7天 | **httpOnly Cookie**（`Secure; SameSite=Strict`，仅刷新接口可用） | Redis（Hash：`auth:refresh:{userId}`，含 deviceId、签发时间、轮换后的旧值） | 每次刷新**旋转**（rotation）并写入旧值黑名单，泄露后可被吊销 |

> **与前端约定（admin-frontend 2.1 对齐）**：
> 1. 刷新接口 `POST /api/admin/auth/refresh` 从 Cookie 取 Refresh Token，校验后返回新 Access Token + 旋转新的 Refresh Token；失败（过期/已吊销/被踢）返回 401，前端清空状态跳登录页。
> 2. **并发刷新**：多个 401 同时到达时，只允许一个请求刷新，其余排队等待同一结果（由前端实现，后端保证刷新接口幂等——同一 refreshToken 二次使用直接判定失效并吊销全链路）。
> 3. **单端登录（默认）**：新登录写入 `auth:refresh:{userId}` 覆盖旧 deviceId，旧端下次刷新即 401；可按系统参数切换为多端。
> 4. Refresh Token 只能存放于 httpOnly Cookie，**禁止写入 JS 可读存储**；若改为纯 Cookie 携带业务请求，必须配套 CSRF 双提交令牌（`X-XSRF-TOKEN`）并校验 Origin（与前端 2.1 约定一致）。

#### 2.1.3 JWT Payload设计

```json
{
  "userId": 10001,
  "username": "admin",
  "roles": ["super_admin", "operator"],
  "permissions": ["product:create", "product:edit", "order:export", "cost:read"],
  "dataScope": 1,
  "scopeStores": [],
  "deptId": 1,
  "permVer": 12,
  "sessionId": "sess_9f2c...",
  "iat": 1704076800,
  "exp": 1704084000
}
```

- `permVer`：权限版本号，权限变更时 +1，网关/服务端比对 Redis 中的最新版本，**版本不匹配即判定 Token 过期**（配合 2.1.4，避免"改了权限仍可用旧 Token 操作"）。
- `sessionId` / `deviceId`：支撑单端登录与主动踢出。
- **权限码规范（与前端 2.4 对齐）**：统一 `模块:操作`，**统一使用 `product:*`，禁止 `goods:*` 另起一套**；字段级读取权限同样走权限码（`cost:read`、`shop:idcard:read`），详见 2.2.3。

#### 2.1.4 权限实时性保障

- **权限变更时**：提升用户 `permVer` 并**拉黑该用户当前 sessionId 的 Access Token**（只踢该会话，不要求重新输密码登录），用户下次请求 401 → 自动刷新仍失败 → 引导重新登录；同时删除 Redis 权限缓存。
- **JWT黑名单**：登出、被踢、权限变更时将 `jti/sessionId` 加入 Redis 黑名单（TTL = Token 剩余有效期），网关每次请求反查（本地缓存 10s，避免每请求一次 Redis）。
- **权限刷新**：前端每 5 分钟静默刷新权限列表（与 admin-frontend 2.4 约定）；服务端返回权限时同时返回 `permVer`，与 JWT 中不一致则提示重新登录。
- **关键操作**：敏感操作（删除、改价、退款、打款、权限变更、证照查看）在**业务服务端二次校验**权限与数据范围，不信任网关的单一校验。
- **异常兜底**：黑名单服务不可用时降级为"只校验签名与有效期"，并对资金类接口保持拒绝（宁可失败，不可放行）。

---

### 2.2 RBAC权限模型

#### 2.2.1 模型架构

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  用户    │────►│  角色    │────►│  权限    │
│ (User)   │     │ (Role)   │     │(Permission)│
└──────────┘     └──────────┘     └──────────┘
     │                │                │
     │                │                │
     ▼                ▼                ▼
  管理员表         角色表           权限表
                              (菜单+按钮+接口)
```

#### 2.2.2 预置角色

| 角色 | 编码 | 权限范围 | 说明 |
|------|------|---------|------|
| 超级管理员 | `super_admin` | 全部数据 | 系统最高权限 |
| 运营主管 | `operation_manager` | 本部门及以下 | 管理运营团队 |
| 运营专员 | `operator` | 仅本人 | 日常运营操作 |
| 客服主管 | `cs_manager` | 全部工单 | 管理客服团队 |
| 客服专员 | `cs_agent` | 分配工单 | 处理分配工单 |
| 财务主管 | `finance_manager` | 全部财务 | 财务管理权限 |
| 财务专员 | `finance_clerk` | 仅本人处理 | 日常财务操作 |
| 商家 | `merchant` | 仅自己店铺 | 店铺管理权限，数据范围=6（本店铺，见 2.3.1） |

> **防提权约束（与前端 12.1 对齐）**：分配角色时，**不可分配数据范围大于操作者自身的角色**（如 `operator` 不能给他人授予"全部数据"）；`super_admin` 角色不可编辑、不可删除；角色删除前校验是否仍绑定用户，绑定则拒绝删除；所有角色/权限变更写入 `admin_operation_log` 并触发敏感操作告警。

#### 2.2.3 权限点注册表（v1.1 新增，前后端唯一权威）

权限码统一 `模块:操作`，模块前缀与路由模块一一对应。**前端只读取本表下发的权限码，禁止自造（历史上的 `goods:*` 全部作废）**：

| 模块 | 权限码前缀 | 典型操作码 | 说明 |
|------|-----------|-----------|------|
| 商品 | `product:` | `product:list/create/edit/delete/on_shelf/off_shelf/export/import/audit` | 上架、审核独立成码 |
| 类目/品牌 | `category:` / `brand:` | `category:create/edit/delete/sort` | |
| 订单 | `order:` | `order:list/detail/remark/price/close/address/ship/split/merge/export/print/remind` | 改价、合单独立成码 |
| 售后 | `refund:` | `refund:list/approve/reject/confirm_receive/execute` | |
| 库存 | `inventory:` | `inventory:list/inbound/outbound/adjust/lock/check/transfer/export` | |
| 会员 | `member:` | `member:list/detail/edit/points_adjust/disable/export` | |
| 营销 | `marketing:` | `coupon:create/edit/distribute`、`seckill:*`、`group_buy:*` | |
| 财务 | `finance:` | `finance:settlement/withdraw/invoice/reconciliation/` + **`finance:export`** | 资金操作必须独立码 |
| 店铺 | `shop:` | `shop:list/audit/freeze/close/deposit` + **`shop:idcard:read`** | 证照查看独立码，查看留痕告警 |
| 内容 | `content:` | `content:banner/notice/topic/cms/help` | |
| 客服 | `cs:` | `cs:ticket/session/export` | 会话导出需主管权限 |
| 数据 | `report:` | `report:view/export` | 受数据权限（2.3）约束 |
| 系统 | `system:` | `system:user/role/permission/dept/dict/config/log/monitor` | 含 `system:user` 等 |
| 字段级 | — | **`cost:read`**（成本价/利润率）、`shop:idcard:read`（证照）、`finance:read`（结算金额） | **后端脱敏为主**：无权限时接口返回 `****`，前端隐藏列为辅（与前端 2.2 一致） |

- 权限点在 `admin_permission` 表中注册（`type=4` 为接口权限，`perms` 存上表编码，`api_method`+`api_path` 用于网关粗粒度拦截）。
- **两层校验**：网关按 `api_method + api_path` 粗校验 → 业务服务按 `perms` 细校验（防止网关规则漏配）。
- 权限码变更视为**接口契约破坏性变更**，须同步 admin-frontend.md 并在数据字典中心发布。

---

### 2.3 数据权限

#### 2.3.1 数据范围定义

| 范围 | 编码 | 说明 | 实现方式 |
|------|------|------|---------|
| 全部数据 | 1 | 可查看所有数据 | 无过滤 |
| 本部门 | 2 | 仅本部门数据 | `dept_id = user.dept_id` |
| 本部门及以下 | 3 | 本部门及子部门 | `dept_id IN (user.dept_id, child_depts)` |
| 仅本人 | 4 | 仅自己创建的数据 | `created_by = user.id` |
| 自定义 | 5 | 自定义部门范围 | `dept_id IN (custom_depts)` |
| 本店铺 | 6 | 仅本店铺数据（merchant 专用，**v1.1 补充**） | `store_id IN (user.storeIds)` |

> **与前端约定（admin-frontend 2.2）**：前端只控制默认筛选项与入口可见性，**数据隔离一律由后端 SQL 拦截保证**；商家角色（merchant）走第 6 档，工作台/报表聚合数据同样按档位过滤（非"全部数据"角色只看可见范围内的聚合值）。
>
> **实施前提**：受数据权限约束的实体必须带 `created_by` 与 `dept_id`（或通过 `store_id`）字段。`admin_order`、`admin_product_spu` 等按 2.3.1 档位过滤时，**部门维度取该实体的"归属人 → 归属人部门"**，不可直接假设表上有 `dept_id`；不具备的表（如订单）需通过 `created_by`/`store_id` 关联实现，具体映射在各服务的 `DataScope` 注解上显式声明，禁止"一刀切"。

#### 2.3.2 数据权限SQL拦截

```java
@Aspect
@Component
public class DataScopeAspect {

    @Before("@annotation(dataScope)")
    public void doBefore(DataScope dataScope) {
        LoginUser user = SecurityUtils.getLoginUser();
        // 注解声明"该查询支持哪些档位"，实际生效档位取自用户角色（super_admin/data_scope=1 直接放行）
        DataScopeEnum scope = dataScope.scope();

        // 条件与参数分离：SQL 片段 + 参数列表一起交给 MyBatis 拦截器拼接，
        // 占位符由 PreparedStatement 绑定，禁止把值拼进 SQL（防注入）。
        switch (scope) {
            case ALL -> DataScopeContext.clear();
            // 2-本部门：按部门过滤（v1.1 修正：原文误写为 created_by = ?，与 2.3.1 定义矛盾）
            case DEPT_ONLY -> DataScopeContext.set("dept_id = ?", List.of(user.getDeptId()));
            // 3-本部门及以下：子部门ID集合（带缓存），IN 占位符按元素个数展开
            case DEPT_AND_CHILD -> DataScopeContext.set(
                inCondition("dept_id", childDeptIds.size()), childDeptIds);
            // 4-仅本人
            case SELF -> DataScopeContext.set("created_by = ?", List.of(user.getUserId()));
            // 5-自定义：取角色上配置的部门集合（非字符串 IN (?)，原文写法无法展开列表）
            case CUSTOM -> DataScopeContext.set(
                inCondition("dept_id", customDepts.size()), customDepts);
            // 6-本店铺（merchant 专用，v1.1 新增，与前端 2.2"商家仅本店铺"对齐）
            case STORE_ONLY -> DataScopeContext.set(
                inCondition("store_id", user.getStoreIds().size()), user.getStoreIds());
        }
    }
}
```

**实施要求（v1.1 补充）**：
1. `inCondition()` 负责生成 `store_id IN (?,?,?)` 形式的占位符串，参数由拦截器统一绑定；**`IN (?)` 直接写死在原文中不可运行**。
2. 数据权限片段必须在**分页 count 之前**拼接，否则分页总数越界。
3. 列表/导出/看板聚合接口走 SQL 拦截；详情类点查接口必须**另行校验该实体是否落在本人数据范围内**，防止越权横向读取（IDOR）。
4. 档位变更后清空权限缓存并提升 `permVer`（见 2.1.3、2.1.4）。

---

### 2.4 操作日志

#### 2.4.1 日志记录策略

| 操作类型 | 记录方式 | 存储位置 |
|---------|---------|---------|
| 登录/登出 | AOP拦截 | MySQL(login_log) |
| CRUD操作 | AOP拦截 | MySQL(operation_log) |
| 导出操作 | 异步记录 | MySQL(operation_log) |
| 审核操作 | 业务记录 | MySQL(operation_log) |
| 定时任务 | 日志框架 | Elasticsearch |

#### 2.4.2 日志切面实现

```java
@Aspect
@Component
public class OperationLogAspect {
    
    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint point, OperationLog operationLog) {
        long startTime = System.currentTimeMillis();
        OperationLogEntity log = new OperationLogEntity();
        
        try {
            // 记录操作前数据
            log.setBeforeData(getBeforeData(point));
            
            Object result = point.proceed();
            
            log.setStatus(1);
            log.setOperationResult("成功");
            return result;
        } catch (Exception e) {
            log.setStatus(0);
            log.setErrorMsg(e.getMessage());
            throw e;
        } finally {
            log.setDuration((int)(System.currentTimeMillis() - startTime));
            log.setOperator(SecurityUtils.getLoginUser());
            log.setOperationTime(new Date());
            // 异步保存日志
            operationLogService.saveAsync(log);
        }
    }
}
```

### 2.5 认证与系统管理接口（v1.1 新增，前端 2.1/12.x 依赖）

#### 2.5.1 认证接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 登录 | POST | `/api/admin/auth/login` | 账号密码/短信验证码；返回 Access Token + 设置 Refresh Token Cookie |
| 登出 | POST | `/api/admin/auth/logout` | 黑名单当前 Token，清除 Refresh Token |
| 刷新 | POST | `/api/admin/auth/refresh` | 见 2.1.2，旋转 Refresh Token |
| 当前用户信息 | GET | `/api/admin/auth/info` | 返回用户、角色、权限码列表、`dataScope`、`permVer` |
| 菜单树 | GET | `/api/admin/auth/menus` | 按权限过滤的菜单树（type=1/2），前端动态注册路由 |
| 图形验证码 | GET | `/api/admin/auth/captcha` | 连续失败 3 次后强制校验（见下） |
| 在线用户 | GET | `/api/admin/system/online-user` | 会话列表，支持强制踢出（写黑名单） |

**登录安全策略（与前端 2.1 对齐）**：
- 连续失败 **3 次** → 后续请求需图形验证码；连续失败 **5 次** → 锁定 15 分钟（`admin_user.login_fail_count` + `lock_until`，锁定状态同时写 Redis 便于快速判停）。
- 登录、锁定、踢出均写 `admin_login_log`（失败也记，用于暴力破解分析）。
- 支持系统参数配置"是否允许单端登录"（默认单端，见 2.1.2 第 3 条）。
- 短信验证码、扫码登录（轮询/推送）为 P1，接口与买家端扫码协议复用 `core-user`。

#### 2.5.2 系统管理接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 管理员列表/详情/新增/编辑/删除 | GET/POST/PUT/DELETE | `/api/admin/system/user`... | 删除为软删；重置密码需二次验证 |
| 分配角色 | PUT | `/api/admin/system/user/{id}/roles` | 防提权校验（2.2.2） |
| 角色列表/新增/编辑/删除 | GET/POST/PUT/DELETE | `/api/admin/system/role`... | 删除前校验绑定用户 |
| 角色授权 | PUT | `/api/admin/system/role/{id}/permissions` | 写入 `admin_role_permission` 并刷新相关用户 `permVer` |
| 权限点树 | GET | `/api/admin/system/permission/tree` | 菜单+按钮+接口（2.2.3 注册表） |
| 部门树/CRUD | GET/POST/PUT/DELETE | `/api/admin/system/dept`... | 禁止删除有子节点/有用户的部门 |
| 操作日志列表/详情 | GET | `/api/admin/system/operation-log`... | 按 2.4 落库，支持前后值对比 |
| 登录日志 | GET | `/api/admin/system/login-log` | |
| 系统参数 CRUD/回滚 | GET/POST/PUT | `/api/admin/system/config`... | 变更写 `admin_config_history`，支持一键回滚 |
| 数据字典 CRUD | GET/POST/PUT/DELETE | `/api/admin/system/dict`... | 订单状态、支付方式等枚举统一从此下发；**v1.2：`pay_type` 字典须含 `4-余额`（买家端扩展），否则订单按支付方式筛选/导出取不到该值** |
| 定时任务管理 | GET/PUT | `/api/admin/system/scheduled-task`... | 启停、执行记录、失败告警 |
| 服务健康/接口监控 | GET | `/api/admin/system/monitor` | 聚合各服务 Actuator + 指标（对应前端 12.5） |
| 导出任务列表/下载 | GET | `/api/admin/system/export-task`... | 状态轮询 + **临时签名 URL（24h 有效）**，见 4.4.3 |

> **接口通用规范**：分页参数统一 `pageNum`/`pageSize`（`pageSize ≤ 200`）；返回结构统一 `{code, message, data, traceId}`；`401`=未认证/Token 失效，`403`=无权限（前端据此触发刷新或跳 403 页）；写接口必须携带幂等 Key（见 10.1.1）。

---

## 三、商品服务

### 3.1 功能点

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 商品CRUD | 创建、编辑、删除、查看商品 | P0 |
| 批量操作 | 批量上架、下架、删除、改价 | P0 |
| 商品审核 | 新商品审核、修改审核 | P0 |
| 类目管理 | 类目树CRUD、排序、启用/禁用 | P0 |
| 属性管理 | 属性模板CRUD、属性值管理 | P0 |
| 品牌管理 | 品牌CRUD、Logo上传 | P1 |
| 商品回收站 | 已删除商品恢复、彻底删除 | P1 |
| 商品草稿 | 保存草稿、草稿发布 | P1 |
| 审核中心 | 待审核队列、认领/分配、通过/驳回/退回修改、可配置复审（见 3.4） | P0 |
| 字段权限 | 成本价等敏感字段按 `cost:read` 脱敏（后端脱敏为主，见 2.2.3） | P0 |

### 3.2 API设计

#### 3.2.1 商品接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 商品列表 | GET | `/api/admin/product/list` | 分页查询商品 |
| 商品详情 | GET | `/api/admin/product/{id}` | 商品详情 |
| 创建商品 | POST | `/api/admin/product` | 创建新商品 |
| 更新商品 | PUT | `/api/admin/product/{id}` | 更新商品 |
| 删除商品 | DELETE | `/api/admin/product/{id}` | 软删除商品 |
| 批量删除 | POST | `/api/admin/product/batch-delete` | 批量删除 |
| 批量上架 | POST | `/api/admin/product/batch-on-shelf` | 批量上架 |
| 批量下架 | POST | `/api/admin/product/batch-off-shelf` | 批量下架 |
| 批量改价 | POST | `/api/admin/product/batch-update-price` | 批量改价 |
| 商品导出 | GET | `/api/admin/product/export` | 导出商品Excel |
| 商品导入 | POST | `/api/admin/product/import` | 导入商品Excel |

#### 3.2.2 类目接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 类目树 | GET | `/api/admin/category/tree` | 获取类目树 |
| 类目列表 | GET | `/api/admin/category/list` | 分页查询类目 |
| 创建类目 | POST | `/api/admin/category` | 创建类目 |
| 更新类目 | PUT | `/api/admin/category/{id}` | 更新类目 |
| 删除类目 | DELETE | `/api/admin/category/{id}` | 删除类目 |
| 排序 | PUT | `/api/admin/category/sort` | 类目排序 |
| 启用/禁用 | PUT | `/api/admin/category/{id}/status` | 状态变更 |

#### 3.2.3 属性接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 属性模板列表 | GET | `/api/admin/attr-template/list` | 按类目查询 |
| 创建属性模板 | POST | `/api/admin/attr-template` | 创建属性模板 |
| 更新属性模板 | PUT | `/api/admin/attr-template/{id}` | 更新属性模板 |
| 删除属性模板 | DELETE | `/api/admin/attr-template/{id}` | 删除属性模板 |
| 属性值列表 | GET | `/api/admin/attr-template/{id}/values` | 属性值列表 |

#### 3.2.4 品牌接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 品牌列表 | GET | `/api/admin/brand/list` | 分页查询 |
| 品牌详情 | GET | `/api/admin/brand/{id}` | 品牌详情 |
| 创建品牌 | POST | `/api/admin/brand` | 创建品牌 |
| 更新品牌 | PUT | `/api/admin/brand/{id}` | 更新品牌 |
| 删除品牌 | DELETE | `/api/admin/brand/{id}` | 删除品牌 |
| 上传Logo | POST | `/api/admin/brand/{id}/logo` | 上传Logo |

### 3.3 技术要点

#### 3.3.1 商品搜索

```java
@Service
public class ProductSearchService {
    
    @Autowired
    private ElasticsearchRestTemplate elasticsearchTemplate;
    
    public Page<ProductSearchVO> search(ProductSearchDTO dto) {
        // 构建ES查询条件
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        
        if (StringUtils.hasText(dto.getKeyword())) {
            boolQuery.must(QueryBuilders.multiMatchQuery(dto.getKeyword(), 
                "name^3", "subtitle^2", "spuCode"));
        }
        
        if (dto.getCategoryId() != null) {
            boolQuery.filter(QueryBuilders.termQuery("categoryId", dto.getCategoryId()));
        }
        
        if (dto.getStatus() != null) {
            boolQuery.filter(QueryBuilders.termQuery("status", dto.getStatus()));
        }
        
        if (dto.getPriceMin() != null || dto.getPriceMax() != null) {
            RangeQueryBuilder range = QueryBuilders.rangeQuery("priceMin");
            if (dto.getPriceMin() != null) range.gte(dto.getPriceMin());
            if (dto.getPriceMax() != null) range.lte(dto.getPriceMax());
            boolQuery.filter(range);
        }
        
        // 排序
        SortBuilder<?> sort = buildSort(dto.getSortField(), dto.getSortOrder());
        
        // 高亮
        HighlightBuilder highlight = new HighlightBuilder()
            .field("name")
            .preTags("<em>")
            .postTags("</em>");
        
        NativeSearchQuery query = new NativeSearchQueryBuilder()
            .withQuery(boolQuery)
            .withSort(sort)
            .withHighlightBuilder(highlight)
            .withPageable(PageRequest.of(dto.getPageNum() - 1, dto.getPageSize()))
            .build();
        
        SearchHits<ProductSearchPO> searchHits = elasticsearchTemplate.search(
            query, ProductSearchPO.class);
        
        return convertToPage(searchHits, dto);
    }
}
```

#### 3.3.2 商品审核流程

```
                        提交审核                审核通过
┌──────────┐ ───────────────────► ┌──────────┐ ───────────► ┌──────────┐
│ 草稿/退回 │                     │ 审核中    │              │ 已上架    │
│ (draft)   │ ◄────────────────── │(auditing)│              │(on_shelf)│
└──────────┘   退回修改(带意见)     └────┬─────┘              └──────────┘
                                       │ 驳回(必填原因)
                                       ▼
                                 ┌──────────┐
                                 │ 审核拒绝  │ ──修改后重新提交──► auditing
                                 │(rejected)│
                                 └──────────┘
```

**对外状态枚举（与前端 4.3 严格一致，前端禁止自造状态）**：`draft` → `auditing` → `on_shelf` / `rejected`。

**三张表的状态映射（v1.1 补充，消除 v1.0 三套枚举打架）**：

| 实体 | 字段 | 取值与含义 |
|------|------|-----------|
| `admin_product_spu.audit_status` | 对外唯一真相 | 0-draft、1-auditing、2-on_shelf、3-rejected（与 DDL 注释同步修改） |
| `admin_product_spu.status` | 仅上下架 | 0-下架、1-上架；**上架前置条件 = audit_status=2**；下架不回退审核状态 |
| `admin_audit_task.status` | 审核任务内部流转 | 0-待审核、1-审核中（含复审子节点）、2-通过、3-拒绝、4-退回修改 |
| 前端展示 | 由 audit_status 直接映射 | 复审仅是 `auditing` 的内部环节，**不产生新状态** |

**流转规则**：
1. 商家/运营提交 → 生成 `admin_audit_task`（biz_type=1）+ 置 `audit_status=1`，并发 `audit.submitted` 事件（站内信通知审核人）。
2. 审核人认领（`assigned_to`，乐观锁防重复认领）→ 审核中；开启复审开关时，初审通过后进入复审节点（仍在 `auditing` 内）。
3. **通过**：`audit_status=2` + `status=1`（自动上架）→ 发 `product.updated` + `product.on_shelf` 事件，买家端更新缓存/ES（见 14.1.1）。
4. **驳回**：`audit_status=3`，`audit_remark` **必填**，发 `audit.completed` 通知提交人。
5. **退回修改**（v1.1 补充，前端 4.3 有此操作、原文缺失）：回到 `audit_status=0`（draft）并带退回意见，提交人可编辑后重新提交；任务记录保留全链路审计。
6. **并发控制**：审核动作使用 `version` 乐观锁；同一商品的并发审核只有一人成功，另一人收到"该商品已被处理"。
7. **失败补偿**：任务状态与 SPU 状态必须在**同一事务/同一服务边界**内更新；若拆在 admin-audit 与 admin-product 两个服务，则通过 MQ `audit.completed` 最终一致 + 13.2.2 定时对账校验"任务已通过但 SPU 仍为 auditing"的悬挂记录。

#### 3.3.3 批量操作限流

> **口径澄清（v1.1 修正）**：`BATCH_SIZE` 是**内部分批大小**（每批 200），接口**单次提交上限为 500 条**（与前端 4.1/4.6 约定一致）；超过 500 前端直接提示分批，服务端同样硬校验拒绝。批量操作为**异步任务**：接口立即返回 `taskId`，前端轮询任务结果接口拿成功/失败明细，**不存在同步返回 BatchResult 的用法**（原文 `@Async` 方法返回非 Future 值，调用方拿不到结果，不可实施）。

```java
@Service
public class ProductBatchService {

    private static final int API_MAX_IDS = 500;        // 接口单次上限（与前端约定）
    private static final int CHUNK_SIZE = 200;         // 内部分批大小
    private static final Semaphore CHUNK_SEMAPHORE = new Semaphore(3); // 全局并发上限：最多3个分批在跑

    /** 提交批量任务：校验上限 -> 落任务 -> MQ 异步执行 */
    public String submitBatch(ProductBatchDTO dto) {
        if (dto.getIds() == null || dto.getIds().isEmpty()
            || dto.getIds().size() > API_MAX_IDS) {
            throw new BizException("批量操作单次上限" + API_MAX_IDS + "条，请分批提交");
        }
        String taskId = IdUtil.nextTaskId();
        batchTaskMapper.insert(new BatchTask(taskId, dto, dto.getOperatorId())); // status=0
        rabbitTemplate.convertAndSend("admin.product.exchange", "product.batch.request",
            new BatchMessage(taskId, dto));
        return taskId;
    }

    @RabbitListener(queues = "product.batch.request.queue")
    public void executeBatch(BatchMessage message) {
        ProductBatchDTO dto = message.getDto();
        CHUNK_SEMAPHORE.acquire(); // 全局限流，防止批量任务打爆DB
        try {
            int success = 0, fail = 0;
            for (List<Long> batch : Lists.partition(dto.getIds(), CHUNK_SIZE)) {
                try {
                    // 按 id + version 条件更新，未命中的记为失败（版本冲突/已删除）
                    int rows = productMapper.batchUpdateStatus(batch, dto.getStatus(), dto.getVersion());
                    success += rows;
                    fail += (batch.size() - rows);
                } catch (Exception e) {
                    fail += batch.size();
                    log.error("批量更新失败, task={}", message.getTaskId(), e);
                }
            }
            batchTaskMapper.finish(message.getTaskId(), success, fail); // 同时落成功/失败明细
            messageService.sendBatchCompleteNotice(dto.getOperatorId(), message.getTaskId(), success, fail);
        } finally {
            CHUNK_SEMAPHORE.release();
        }
    }
}
```

**补充约束**：
- 涉及价格的批量改价走审批流（7.x/审批服务），**不允许**在本接口直接生效大范围改价；
- 批量上/下架会触发 MQ 事件扇出，>100 条时按 200/批串行发 MQ，避免买家端缓存击穿；
- 任务结果保留 7 天，明细含失败原因（乐观锁冲突/已删除/无权限），前端支持按原因筛选重试（对齐前端 4.1）。

### 3.4 审核中心接口（v1.1 新增，admin-audit 服务，前端 4.3 依赖）

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 审核队列 | GET | `/api/admin/audit/task/list` | 待审核/已审核 Tab，筛选 biz_type、提交人、时间、状态 |
| 审核详情 | GET | `/api/admin/audit/task/{id}` | 业务快照 + 审核记录 + 当前节点（含复审进度） |
| 认领任务 | POST | `/api/admin/audit/task/{id}/claim` | 乐观锁防重复认领 |
| 通过 | POST | `/api/admin/audit/task/{id}/approve` | 可附备注；联动业务状态（3.3.2 第3条） |
| 驳回 | POST | `/api/admin/audit/task/{id}/reject` | `reason` 必填 |
| 退回修改 | POST | `/api/admin/audit/task/{id}/return` | 带意见退回 draft，可重新提交 |
| 批量审核 | POST | `/api/admin/audit/task/batch` | 上限与失败明细同 3.3.3 |
| 复审开关 | PUT | `/api/admin/system/config/audit-recheck` | 系统参数：是否开启复审（仅 auditing 内部节点） |
| 审核记录查询 | GET | `/api/admin/audit/record` | 按业务单号查全链路审计（含操作人/时间/前后值） |

> **覆盖范围**：商品（biz_type=1）、店铺（biz_type=2，见第十七章）、售后（biz_type=3，见 4.2.2）、资金类审批（改价审批、提现初审/复审、保证金扣减、结算调整，biz_type=4~8）。**共用一套审核队列与审计留痕，禁止各业务各写一套审批逻辑**（对应前端 5.5、9.4、9.6、13.4 的"强制审批流"要求）。

---

## 四、订单服务

### 4.1 功能点

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 订单查询 | 多条件筛选、详情查看 | P0 |
| 订单状态管理 | 状态流转、状态机控制 | P0 |
| 拆单 | 按店铺自动/手动拆单 | P0 |
| 合单 | 同买家待付款订单合并支付（重算差异阈值人工确认，见 4.4.2） | P1 |
| 改价 | 订单价格调整（改价原因必填 + 阈值审批，见 4.2.1） | P0 |
| 关闭订单 | 手动关闭 + 超时自动关闭（联动释放锁定库存，见 4.4.4） | P0 |
| 修改收货地址 | 发货前修改，乐观锁 + 操作日志（前后值脱敏） | P0 |
| 催付 | 短信/站内信提醒买家付款 | P1 |
| 备注 | 管理员备注、用户备注 | P0 |
| 订单导出 | 异步导出Excel | P1 |
| 售后管理 | 退款、退货、换货处理 | P0 |
| 物流跟踪 | 物流信息查询 | P1 |
| 订单打印 | 发货单、快递单打印 | P2 |

### 4.2 API设计

#### 4.2.1 订单接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 订单列表 | GET | `/api/admin/order/list` | 分页查询订单（数据权限见 2.3） |
| 订单详情 | GET | `/api/admin/order/{id}` | 订单详情（含子单/父单号、金额明细、优惠明细） |
| 订单备注 | PUT | `/api/admin/order/{id}/remark` | 添加备注 |
| 订单改价 | PUT | `/api/admin/order/{id}/price` | 修改价格：原因必填、校验不低于成本价/底价、超阈值（20% 或 500 元）自动转审批流、**支付中的订单拒绝改价** |
| 关闭订单 | POST | `/api/admin/order/{id}/close` | 手动关闭（待付款单直接关，已发货单需先走售后）；联动 4.4.4 释放库存 |
| 修改收货地址 | PUT | `/api/admin/order/{id}/address` | 仅 `paid`(待发货) 可改，乐观锁 + 操作日志，前后值脱敏 |
| 催付 | POST | `/api/admin/order/{id}/remind` | 发送短信/站内信，按系统参数限频 |
| 确认收款 | POST | `/api/admin/order/{id}/confirm-pay` | 确认收款（线下汇款等场景，幂等） |
| 发货 | POST | `/api/admin/order/{id}/ship` | 订单发货（幂等键，重复发货拒绝） |
| 导出订单 | GET | `/api/admin/order/export` | 异步导出（见 4.4.3） |
| 拆单 | POST | `/api/admin/order/{id}/split` | 手动拆单（幂等，已拆分拒绝，见 4.4.2） |
| 合单 | POST | `/api/admin/order/merge` | 同买家待付款订单合并；优惠重算差异超阈值需人工确认（见 4.4.2） |
| 子单列表 | GET | `/api/admin/order/{orderNo}/children` | 按父单号查子单（父子关系可追溯） |

> **订单列表筛选参数（v1.2 补充）**：`orderStatus`（8 值，见 4.3）、`payType`（**1-支付宝 / 2-微信 / 3-银行卡(银联) / 4-余额**，与 1.2.2 `admin_order.pay_type`、2.5.2 数据字典同源，买家端 `android-backend.md` 1.4.1 扩展了 `4-余额`）、`payStatus`、`storeId`、`orderNo`、`userId`、`createdAtStart/End`；筛选项与导出列的可选值一律取数据字典，**禁止前端或后端硬编码枚举**，新增支付方式必须同时更新 DDL 注释、字典与导出模板。

#### 4.2.2 售后接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 售后列表 | GET | `/api/admin/after-sale/list` | 售后单列表 |
| 售后详情 | GET | `/api/admin/after-sale/{id}` | 售后详情 |
| 审核通过 | POST | `/api/admin/after-sale/{id}/approve` | 审核通过 |
| 审核拒绝 | POST | `/api/admin/after-sale/{id}/reject` | 审核拒绝 |
| 确认收货 | POST | `/api/admin/after-sale/{id}/confirm-receive` | 确认退货收货 |
| 退款 | POST | `/api/admin/after-sale/{id}/refund` | 执行退款 |

### 4.3 订单状态机

**对外 8 状态（与 DDL 注释、前端 5.3、买家端数据字典严格一致，任何一端禁止自造状态）**：

```
                          支付成功
 ┌──────────┐ ─────────────────────────► ┌──────────┐        ┌──────────┐
 │ 待付款    │                            │ 待发货    │ ─发货─►│ 待收货    │
 │(unpaid)  │                            │ (paid)   │        │(shipped) │
 └────┬─────┘                            └────┬─────┘        └────┬─────┘
      │                                       │ 申请退款          │ 确认收货/自动确认
      │ 超时未付款/手动关闭                    ▼                   ▼
      │                                  ┌──────────┐       ┌──────────┐
      ▼                                  │ 退款中    │ ◄─────│ 已完成    │
 ┌──────────┐                            │(refunding)│       │(completed)│
 │ 已取消    │ (终态)                     └────┬─────┘       └────┬─────┘
 │(cancelled)│                                │ 售后完成         │ 售后完成
 └──────────┘                    退款成功     ▼                  ▼
                            ┌──────────┐              ┌──────────┐
                            │ 已退款    │ ─归档关闭─►  │ 已关闭    │ (终态)
                            │ (refunded)│   (v1.2)     │ (closed) │
                            └──────────┘              └──────────┘
```

> **v1.2 跨文件同步（与 `android-backend.md` 4.3.4 对齐）**：`refunded` 为**业务终态**——退款成功后禁止再进入发货/收货/售后等任何交易流转，仅保留一条**归档边 `refunded → closed`**（财务对账完成后由系统归档任务触发，禁止人工调用）；真正不可再流转的**终态只有 `cancelled`、`closed` 两个**。买家端 4.3.4 定义 `refunded(5) → closed(7)`，4.4.1 代码已同步补齐该条目与终态声明。

**流转白名单**（4.4.1 代码直接引用此表，全系统只能有这一份定义）：

| 当前状态 | 允许到达 | 触发 |
|---------|---------|------|
| unpaid | paid、cancelled | 支付成功 / 超时未付款、手动关闭 |
| paid | shipped、refunding、cancelled、closed | 发货 / 申请退款 / 后台关闭（仅限未发货） |
| shipped | completed、refunding | 确认收货（或超时自动确认）/ 申请退款 |
| completed | refunding、closed | 申请售后 / 售后完成关闭 |
| refunding | refunded、completed、closed | 退款成功 / 退款失败恢复原状态 / 部分退款后售后关闭 |
| refunded | closed | 退款完成、财务对账后系统归档关闭（**v1.2 跨文件同步**，与买家端 4.3.4「5→7」一致；仅系统任务触发，禁止人工） |
| cancelled、closed | — | **终态，禁止再流转** |

**规则补充（v1.1 修正）**：
1. 退款可从 `paid / shipped / completed` 进入（v1.0 图只画了"已完成 → 售后中"，与 4.4.1 代码 `PAID→REFUNDING`、`SHIPPED→REFUNDING` 互相矛盾，以本节为准）；退款成功后订单置 `refunded`，发货/收货事实保留在流水中。
2. `cancelled` 与 `closed` 严格区分：**cancelled = 未支付即取消（无资金流）**；**closed = 已支付后因售后完成或后台关闭而结束（必须有退款或资金处置记录）**，两者禁止混用（前端 5.3 已指出该混用问题）。
3. 每次流转写 `admin_order_log`（from/to/操作人/原因），并按 14.1.2 发 MQ 事件推送买家端。
4. 状态变更必须 `version` 乐观锁 + 幂等键 + 二次权限校验（资金敏感操作）。
5. **（v1.2 跨文件同步）** 8 值枚举 `0-unpaid/1-paid/2-shipped/3-completed/4-refunding/5-refunded/6-cancelled/7-closed` 与 1.2.2 DDL、买家端 `android-backend.md` 4.3.4 逐值一致；`refunded → closed` 仅由财务对账归档任务触发（幂等键 `orderId`），`cancelled`、`closed` 为真正终态，由 4.4.1 的 `TERMINAL_STATES` 显式拦截。

### 4.4 技术要点

#### 4.4.1 订单状态机实现

```java
@Component
public class OrderStateMachine {

    // 状态流转规则：与 4.3 流转白名单一一对应（8 状态；v1.2 跨文件同步补齐）
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
        OrderStatus.UNPAID, Set.of(OrderStatus.PAID, OrderStatus.CANCELLED),
        OrderStatus.PAID, Set.of(OrderStatus.SHIPPED, OrderStatus.REFUNDING,
                                  OrderStatus.CANCELLED, OrderStatus.CLOSED),
        OrderStatus.SHIPPED, Set.of(OrderStatus.COMPLETED, OrderStatus.REFUNDING),
        OrderStatus.COMPLETED, Set.of(OrderStatus.REFUNDING, OrderStatus.CLOSED),
        OrderStatus.REFUNDING, Set.of(OrderStatus.REFUNDED, OrderStatus.COMPLETED, OrderStatus.CLOSED),
        // v1.2 补：退款完成 → 系统归档关闭（与买家端 android-backend.md 4.3.4「5→7」一致）
        OrderStatus.REFUNDED, Set.of(OrderStatus.CLOSED)
    );

    // v1.2 补：终态显式声明（4.3 白名单「cancelled、closed 终态，禁止再流转」）
    // 这两个状态不作为 TRANSITIONS 的 key 出现，命中即拒绝一切流出
    private static final Set<OrderStatus> TERMINAL_STATES =
            Set.of(OrderStatus.CANCELLED, OrderStatus.CLOSED);

    @Transactional
    public void transition(Long orderId, OrderStatus targetStatus, String operator, String reason) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException("订单不存在");
        }

        // 1. 终态与白名单校验（v1.1 修正：原文 Map.get() 对终态返回 null 会 NPE；v1.2 显式终态拦截）
        if (TERMINAL_STATES.contains(order.getOrderStatus())) {
            throw new BizException("订单已处于终态 " + order.getOrderStatus() + "，禁止再流转");
        }
        Set<OrderStatus> allowed = TRANSITIONS.get(order.getOrderStatus());
        if (allowed == null || !allowed.contains(targetStatus)) {
            throw new BizException("订单状态不允许从 " + order.getOrderStatus() + " 变更为 " + targetStatus);
        }

        // 2. 幂等：同一目标状态重复请求直接返回成功（避免 MQ/回调重投导致重复处理）
        if (order.getOrderStatus() == targetStatus) {
            return;
        }

        // 3. 更新状态，使用乐观锁（version）
        int rows = orderMapper.updateStatus(orderId, targetStatus, order.getVersion());
        if (rows == 0) {
            throw new BizException("订单状态已变更，请刷新后重试");
        }

        // 4. 记录状态流水
        OrderLog log = new OrderLog();
        log.setOrderId(orderId);
        log.setFromStatus(order.getOrderStatus());
        log.setToStatus(targetStatus);
        log.setOperator(operator);
        log.setRemark(reason);
        orderLogMapper.insert(log);

        // 5. 发送MQ事件（14.1.2：推买家端缓存/消息）
        eventPublisher.publishEvent(new OrderStatusChangedEvent(orderId, targetStatus));
    }
}
```

**使用约束（v1.1）**：
- 所有状态变更必须经过 `transition()`，**禁止在业务代码里直接 `update order set order_status=...`**（可在代码评审中用 ArchUnit 规则强制）。
- `cancelled`、`closed` 的进入需额外校验资金条件：已支付订单关闭必须存在退款或资金处置记录（见 4.3 规则 2）。
- 定时任务（超时关单）与支付回调并发时，靠乐观锁 + 幂等保证只成功一次；定时任务侧需重查订单再判断，禁止拿缓存状态直接流转。
- **（v1.2 跨文件同步，补齐项）**：`COMPLETED → REFUNDING`（已完成订单发起售后）由售后服务在用户申请时触发；`REFUNDED → CLOSED` 仅允许财务对账归档任务调用（操作人固定 `system:archive`，幂等键 `orderId`），**禁止在后台页面暴露该流转按钮**；`targetStatus` 命中 `TERMINAL_STATES` 一律拒绝。两条边补齐后，4.4.1 与 4.3 白名单、买家端 4.3.4 三方一致，`android-backend.md` 审查记录 L1 遗留项可关闭。

#### 4.4.2 订单拆单与合单逻辑

**拆单时机（v1.1 补充）**：支付成功后按店铺自动拆单（`order.paid` 事件触发），运营也可对**待发货**订单手动拆单。未支付订单不拆单（拆了也无法独立支付，v1.0 代码默认拆为 `PAID` 属于严重错误）。

**拆单规则（与前端 5.4 对齐）**：
1. **分组键**：`store_id`；仓库维度不在拆单处理，留到发货环节（前端 5.4 已按此展示）。
2. **优惠与运费按比例分摊**：子单优惠 = 父单优惠 ×（子单商品金额 / 父单商品金额），**尾差归入金额最大的子单**；运费按店铺运费模板分别计算，未发货前运费为 0。**禁止把父单整额优惠扣在每个子单上**（v1.0 每个子单都减全额优惠，N 个店铺会多减 N-1 次优惠）。
3. **父子关系可追溯**：子单 `parent_order_no` 回填父单号、`store_id` 必填；父单保留原始金额，仅标记"已拆分"，**父单不参与发货/售后**，其 `order_status` 保持 `paid` 并加 `split_flag`，避免出现 DDL 中不存在的 `SPLIT` 状态。
4. **幂等与并发**：拆单以 `parent_order_no` 做唯一索引防重复拆；同一父单并发拆单时，唯一键冲突的一方直接读取已生成的子单返回。
5. **金额校验**：Σ子单实付 = 父单实付（分摊尾差已处理），不等则拒绝拆单并告警。

```java
@Service
public class OrderSplitService {

    @Transactional
    public List<Order> split(Long parentOrderNo, String operator) {
        Order parent = orderMapper.selectByOrderNo(parentOrderNo);

        // 幂等：已拆分直接返回现有子单（唯一索引兜底）
        List<Order> existings = orderMapper.selectChildren(parentOrderNo);
        if (!existings.isEmpty()) {
            return existings;
        }
        // 前置校验：只有已支付、未发货的父单可拆
        if (parent.getOrderStatus() != OrderStatus.PAID) {
            throw new BizException("仅待发货订单可拆单");
        }

        List<OrderItem> items = orderItemMapper.selectByOrderNo(parentOrderNo);

        // 按店铺分组
        Map<Long, List<OrderItem>> storeGroup = items.stream()
            .collect(Collectors.groupingBy(OrderItem::getStoreId));

        BigDecimal parentGoodsAmount = parent.getTotalAmount();
        List<Order> subOrders = new ArrayList<>();
        BigDecimal allocatedDiscount = BigDecimal.ZERO;
        Order maxOrder = null;

        for (Map.Entry<Long, List<OrderItem>> entry : storeGroup.entrySet()) {
            Long storeId = entry.getKey();
            List<OrderItem> storeItems = entry.getValue();

            BigDecimal subGoodsAmount = storeItems.stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 优惠按比例分摊（2位小数，HALF_UP）
            BigDecimal subDiscount = parentGoodsAmount.signum() == 0 ? BigDecimal.ZERO
                : subGoodsAmount.divide(parentGoodsAmount, 12, RoundingMode.HALF_UP)
                    .multiply(parent.getDiscountAmount())
                    .setScale(2, RoundingMode.HALF_UP);

            // 运费：按店铺运费模板计算（未发货前一般为0）
            BigDecimal subFreight = freightService.calculate(storeId, storeItems);

            Order subOrder = new Order();
            subOrder.setOrderNo(IdUtil.nextOrderNo(storeId));
            subOrder.setParentOrderNo(parent.getOrderNo());
            subOrder.setStoreId(storeId);
            subOrder.setUserId(parent.getUserId());
            subOrder.setTotalAmount(subGoodsAmount);
            subOrder.setDiscountAmount(subDiscount);
            subOrder.setFreightAmount(subFreight);
            subOrder.setPayAmount(subGoodsAmount.subtract(subDiscount).add(subFreight));
            subOrder.setOrderStatus(OrderStatus.PAID);   // 继承父单的"已支付"状态
            subOrder.setPayStatus(parent.getPayStatus());
            subOrder.setPayDeadline(parent.getPayDeadline());
            orderMapper.insert(subOrder);

            // 订单明细跟随子单（order_id/order_no 一起更新，v1.0 漏改 order_id 会导致明细归属错乱）
            for (OrderItem item : storeItems) {
                item.setOrderId(subOrder.getId());
                item.setOrderNo(subOrder.getOrderNo());
                orderItemMapper.updateById(item);
            }

            allocatedDiscount = allocatedDiscount.add(subDiscount);
            subOrders.add(subOrder);
        }

        // 尾差归入金额最大的子单，保证 Σ子单实付 == 父单实付
        BigDecimal diff = parent.getDiscountAmount().subtract(allocatedDiscount);
        if (diff.signum() != 0) {
            Order biggest = subOrders.stream()
                .max(Comparator.comparing(Order::getTotalAmount)).orElseThrow();
            biggest.setDiscountAmount(biggest.getDiscountAmount().add(diff));
            biggest.setPayAmount(biggest.getPayAmount().subtract(diff));
            orderMapper.updateById(biggest);
        }

        // 父单标记已拆分（不引入新状态，仅加拆分标记与备注）
        orderMapper.markSplit(parent.getOrderNo(), operator);

        return subOrders;
    }
}
```

**合单（v1.1 补充，前端 5.4 有该场景）**：
- 接口 `POST /api/admin/order/merge`，前置条件：同一买家、全部 `unpaid`、无进行中的支付单、未核销不可叠加优惠（已核销券/积分先回退）。
- 合并后用 `core-price` 引擎重算优惠，**重算后实付差异超过阈值（默认 1%）需人工确认**再继续；合并记录原订单号（父子关系），全程写操作日志。
- 合并/拆分都是资金敏感操作，须 `order:merge` / `order:split` 权限 + 二次校验当前状态。

#### 4.4.3 订单导出(异步)

```java
@Service
public class OrderExportService {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @Autowired
    private RedissonClient redissonClient;
    
    public String export(OrderExportDTO dto) {
        // 生成任务编号
    public String export(OrderExportDTO dto) {
        // 1) 幂等：客户端携带幂等Key，重复提交返回同一任务号
        String idemKey = dto.getIdempotencyKey();
        String cached = redisTemplate.opsForValue().get("export:idem:" + idemKey);
        if (cached != null) {
            return cached;
        }

        // 2) 任务号：雪花ID，避免原文 System.currentTimeMillis() 并发碰撞
        String taskNo = "EXPORT_" + IdUtil.nextTaskNo();

        // 3) 用户级并发限制：只允许 1 个"进行中"任务（v1.1 修正：原文拿到锁后
        //    立即在 finally 释放，防不住重复提交；这里用"查状态+唯一约束+缓存"组合）
        int running = exportTaskMapper.countRunningByUser(dto.getUserId());
        if (running >= 1) {
            throw new BizException("您有导出任务正在进行中，请稍后再试");
        }

        // 创建导出任务（task_no 唯一索引兜底并发插入）
        ExportTask task = new ExportTask();
        task.setTaskNo(taskNo);
        task.setType("order");
        task.setParams(JsonUtil.toJson(dto));
        task.setCreatedBy(dto.getUserId());
        task.setStatus(0);
        exportTaskMapper.insert(task);

        // 发送MQ异步处理（本地消息表兜底：同事务落 message，见 13.2.1）
        rabbitTemplate.convertAndSend(
            "admin.export.exchange",
            "export.request",
            new ExportMessage(taskNo, dto)
        );

        redisTemplate.opsForValue().set("export:idem:" + idemKey, taskNo, 24, TimeUnit.HOURS);
        return taskNo;
    }
    
    @RabbitListener(queues = "export.request.queue")
    public void handleExport(ExportMessage message) {
        // 幂等：状态机 0->1 条件更新，重复投递直接跳过
        int updated = exportTaskMapper.markProcessing(message.getTaskNo());
        if (updated == 0) {
            return;
        }
        ExportTask task = exportTaskMapper.selectByTaskNo(message.getTaskNo());

        try {
            // 查询数据（流式游标，避免大结果集 OOM）
            List<OrderExportVO> data = orderMapper.selectForExport(message.getDto());
            
            // 使用EasyExcel写入临时文件（容器内/tmp目录）
            String fileName = "order_export_" + message.getTaskNo() + ".xlsx";
            String filePath = System.getProperty("java.io.tmpdir") + "/" + fileName;
            EasyExcel.write(filePath, OrderExportVO.class)
                .sheet("订单数据")
                .doWrite(data);
            
            // 上传到OSS（私有Bucket，不生成公开直链）
            String fileUrl = ossService.uploadPrivate(filePath, fileName);
            
            // 更新任务状态（条件更新 1->2，防并发覆盖）
            exportTaskMapper.finish(message.getTaskNo(), fileUrl, data.size());
            
            // 发送通知：站内信只带任务号，下载走后端接口（见下）
            messageService.sendExportCompleteNotice(task.getCreatedBy(), message.getTaskNo());
            
        } catch (Exception e) {
            exportTaskMapper.fail(message.getTaskNo(), e.getMessage());
        } finally {
            // 清理容器临时文件，防止磁盘写满（对应 12.2 P3 告警）
            FileUtil.del(filePath);
        }
    }
}
```

**导出安全要求（与前端 4.1、9.6 对齐，v1.1 补充）**：
1. 文件存**私有 Bucket**，下载必须走 `GET /api/admin/system/export-task/{taskNo}/download`：校验登录态 + `task.created_by == 当前用户` + 任务状态=已完成 → 返回**临时签名 URL（有效期 24h）**，禁止公开直链。
2. 导出需对应模块 `xxx:export` 权限（财务还需 `finance:export` + 二次确认），导出行为写 `admin_operation_log`，文件加操作人水印。
3. 导出条件在落库时**固化为快照**（params），避免处理时条件变化导致数据与提交不一致；单次导出条数上限（如 10 万行）超限提示收窄条件。
4. 敏感列（手机号、地址、成本价）按当前用户权限**脱敏后再写文件**，不是下载时脱敏。

### 4.4.4 超时关单与锁定库存释放（v1.1 新增，前端 5.2/6.1 依赖）

**问题**：v1.0 状态机有"超时未付款"边，但没有任何实现；库存表有 `locked_stock`，也没有释放路径，最终必然出现"库存被幽灵锁死、越卖越少"。

**实现（MQ 延迟队列为主，定时扫描兜底）**：
1. **下单/支付成功时**：订单落库同时发送延迟消息（RabbitMQ TTL + DLX 延迟队列，或延迟交换机），到期路由到 `order.timeout.check`；`pay_deadline` 写入订单表作为可查询的事实来源。
2. **到期消费**：重查订单 → 若仍为 `unpaid` 且无支付流水 → 走 4.4.1 `transition(unpaid→cancelled, reason=timeout)` → 发 `order.cancelled` 事件 → admin-inventory 释放锁定库存（`locked_stock -= n`，写库存流水 `change_type=7 解锁`，`biz_no=order_no`）→ 营销侧回退占用的优惠券/秒杀名额。
3. **兜底定时任务**（每 5 分钟）：扫描 `order_status=unpaid AND pay_deadline < now()` 的漏网订单（延迟消息丢失时补齐），与支付回调并发时靠乐观锁 + 幂等保证只关一次。
4. **幂等**：关单动作复用 4.4.1 幂等判断；库存释放以 `biz_no + change_type` 唯一索引防重复释放（**释放比扣减更危险**：重复释放等于白送库存）。
5. **人工兜底**：`POST /api/admin/order/{id}/close` 手动关单走同一链路；异常场景（支付成功但回调丢失）由对账任务（13.2.2）反向补偿，禁止手工直接改库存。

---

## 五、库存服务

### 5.1 功能点

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 库存查询 | 多维度库存查询 | P0 |
| 入库 | 采购入库、退货入库 | P0 |
| 出库 | 销售出库、调拨出库 | P0 |
| 库存预警 | 低库存预警通知 | P0 |
| 库存盘点 | 全盘、抽盘、差异处理 | P0 |
| 库存调拨 | 仓间调拨 | P1 |
| 多渠道同步 | 多渠道库存同步 | P1 |
| 库存流水 | 库存变动记录 | P0 |

### 5.2 API设计

#### 5.2.1 库存接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 库存列表 | GET | `/api/admin/inventory/list` | 分页查询库存 |
| 库存详情 | GET | `/api/admin/inventory/{warehouseId}/{skuId}` | 库存详情 |
| 入库 | POST | `/api/admin/inventory/inbound` | 库存入库 |
| 出库 | POST | `/api/admin/inventory/outbound` | 库存出库 |
| 调整 | POST | `/api/admin/inventory/adjust` | 库存调整 |
| 锁定 | POST | `/api/admin/inventory/lock` | 锁定库存 |
| 解锁 | POST | `/api/admin/inventory/unlock` | 解锁库存 |

#### 5.2.2 预警接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 预警列表 | GET | `/api/admin/inventory/warning/list` | 低库存预警列表 |
| 设置预警 | PUT | `/api/admin/inventory/{skuId}/warning` | 设置预警库存 |
| 预警统计 | GET | `/api/admin/inventory/warning/stats` | 预警统计 |

#### 5.2.3 盘点接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 创建盘点单 | POST | `/api/admin/inventory-check` | 创建盘点任务 |
| 盘点列表 | GET | `/api/admin/inventory-check/list` | 盘点单列表 |
| 盘点详情 | GET | `/api/admin/inventory-check/{id}` | 盘点详情 |
| 提交盘点 | POST | `/api/admin/inventory-check/{id}/submit` | 提交盘点结果 |
| 确认盘点 | POST | `/api/admin/inventory-check/{id}/confirm` | 确认盘点结果 |
| 取消盘点 | POST | `/api/admin/inventory-check/{id}/cancel` | 取消盘点 |

#### 5.2.4 调拨接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 创建调拨单 | POST | `/api/admin/inventory-transfer` | 创建调拨 |
| 调拨列表 | GET | `/api/admin/inventory-transfer/list` | 调拨单列表 |
| 调拨详情 | GET | `/api/admin/inventory-transfer/{id}` | 调拨详情 |
| 确认调拨 | POST | `/api/admin/inventory-transfer/{id}/confirm` | 确认调拨 |
| 取消调拨 | POST | `/api/admin/inventory-transfer/{id}/cancel` | 取消调拨 |

### 5.3 技术要点

#### 5.3.1 库存扣减(防止超卖)

> **v1.1 修正的四个硬伤**（原文不可实施）：
> 1. `inventory:pre:{skuId}` **key 从未初始化**，Redis 对不存在的 key 执行 DECR 会直接变成负数 → 永远"库存不足"；必须以 DB 为准做**懒加载回填**（`setnx` + DB 值 + TTL）。
> 2. key **不含 warehouse_id**，与 `admin_inventory` 的 `uk_warehouse_sku`（按仓+SKU）模型矛盾 → 多仓场景会互相打架。
> 3. Redis 预扣与 DB 扣减之间**没有对账/补偿**，Redis 挂掉或进程崩溃会泄漏占用量 → Redis 侧只是**限流/抗热点闸门**，**DB 才是账本**，且 Redis 计数必须带 TTL。
> 4. `available_stock / locked_stock` 两段式没有体现：下单应"锁库"，支付才"出库"，取消/超时"解锁"（见 4.4.4），原文直接出库会导致未付款订单占死库存。

```java
@Service
public class InventoryService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 预扣（闸门）：下单时调用，只做"锁定"，不做出库。
     * Redis 仅作抗热点的快速失败闸门，最终以 DB 的 locked_stock 为准。
     */
    public boolean preLock(Long warehouseId, Long skuId, Integer quantity, String orderNo) {
        String key = "inventory:lock:" + warehouseId + ":" + skuId;

        // 1) 懒加载回填：以 DB 可用库存初始化计数（原子 setIfAbsent，带 TTL）
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            Inventory inv = inventoryMapper.selectByWarehouseAndSku(warehouseId, skuId);
            if (inv == null || inv.getAvailableStock() < quantity) {
                return false;
            }
            redisTemplate.opsForValue().setIfAbsent(key,
                String.valueOf(inv.getAvailableStock()), 30, TimeUnit.MINUTES);
        }

        // 2) 原子预扣
        Long remaining = redisTemplate.opsForValue().decrement(key, quantity);
        if (remaining < 0) {
            redisTemplate.opsForValue().increment(key, quantity); // 回滚闸门
            return false;
        }

        // 3) 落 DB：锁库（乐观锁 + 条件更新，available->locked）
        int rows = inventoryMapper.lock(warehouseId, skuId, quantity);
        if (rows == 0) {
            redisTemplate.opsForValue().increment(key, quantity); // DB 不足则回滚闸门
            return false;
        }
        writeLog(warehouseId, skuId, 6 /*锁定*/, -quantity, "order", orderNo);
        return true;
    }

    /**
     * 确认出库：支付成功后调用，把锁定库存真正扣掉（幂等）。
     */
    @Transactional
    public void deduct(Long warehouseId, Long skuId, Integer quantity, String orderNo) {
        // 幂等：同一 bizNo + change_type 只允许一条流水（唯一索引 uk_biz_no_type）
        if (inventoryLogMapper.exists(orderNo, 2)) {
            return;
        }
        int rows = inventoryMapper.deductLocked(warehouseId, skuId, quantity);
        if (rows == 0) {
            throw new BizException("库存扣减失败（锁定库存不足或已扣减），请人工核对单号：" + orderNo);
        }
        writeLog(warehouseId, skuId, 2 /*出库*/, -quantity, "order", orderNo);
        eventPublisher.publishEvent(new InventoryChangedEvent(skuId, -quantity));
        syncToChannels(skuId, currentTotalStock(skuId)); // 见 5.3.3
    }

    /**
     * 释放预扣（解锁）：订单取消/超时关单时调用（4.4.4）。
     * 释放比扣减更危险：重复释放 = 白送库存，必须幂等。
     */
    @Transactional
    public void unlock(Long warehouseId, Long skuId, Integer quantity, String orderNo) {
        if (inventoryLogMapper.exists(orderNo, 7 /*解锁*/)) {
            return; // 已释放过
        }
        int rows = inventoryMapper.unlock(warehouseId, skuId, quantity);
        if (rows == 0) {
            // 锁定库存已被出库，说明订单已支付，拒绝回滚并告警人工介入
            throw new BizException("锁定库存已出库，无法释放：" + orderNo);
        }
        writeLog(warehouseId, skuId, 7, quantity, "order", orderNo);
        String key = "inventory:lock:" + warehouseId + ":" + skuId;
        redisTemplate.opsForValue().increment(key, quantity); // 回补闸门
    }
}
```

**补充说明**：
- `admin_inventory_log` 需新增 `UNIQUE KEY uk_biz_no_type (biz_no, change_type)`，作为扣减/解锁幂等的最终防线。
- 秒杀等极端高并发场景可在 Redis 闸门之上再加**库存分桶/ LUA 原子扣减**，但下单落库仍走本方法，不允许绕过 DB 账本。
- Redis 闸门计数带 TTL，重启/丢失后自动以 DB 回填，**不依赖 Redis 记忆**。
- 库存不足、并发冲突必须给用户明确错误与刷新提示（前端 6.1 已按此交互），不得静默失败。

#### 5.3.2 库存预警

```java
@Scheduled(cron = "0 0 8 * * ?") // 每天早上8点
public void checkInventoryWarning() {
    // 查询低库存商品
    List<InventoryWarning> warnings = inventoryMapper.selectWarningList();
    
    for (InventoryWarning warning : warnings) {
        // 发送通知
        MessageDTO message = new MessageDTO();
        message.setTitle("库存预警");
        message.setContent(String.format("商品[%s]库存不足，当前库存：%d，预警库存：%d",
            warning.getSkuName(), warning.getAvailableStock(), warning.getWarningStock()));
        message.setReceivers(getInventoryManagers());
        
        messageService.send(message);
    }
}
```

#### 5.3.3 多渠道库存同步

```java
@Service
public class ChannelInventorySyncService {
    
    @Autowired
    private Map<String, ChannelService> channelServices;
    
    /**
     * 同步库存到多渠道
     */
    public void syncToChannels(Long skuId, Integer stock) {
        for (Map.Entry<String, ChannelService> entry : channelServices.entrySet()) {
            String channel = entry.getKey();
            ChannelService service = entry.getValue();
            
            try {
                service.updateStock(skuId, stock);
                log.info("渠道{}库存同步成功，skuId={}", channel, skuId);
            } catch (Exception e) {
                log.error("渠道{}库存同步失败，skuId={}", channel, skuId, e);
                // 记录同步失败，后续重试
                syncFailLogMapper.insert(new SyncFailLog(channel, skuId, stock, e.getMessage()));
            }
        }
    }
}
```

---

## 六、会员服务

### 6.1 功能点

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 会员查询 | 多条件筛选、详情查看 | P0 |
| 会员等级管理 | 等级CRUD、升降级规则 | P0 |
| 积分管理 | 积分明细、调整 | P0 |
| 会员标签 | 标签管理、批量打标 | P1 |
| 会员画像 | 消费分析、行为分析 | P1 |
| 会员导出 | 导出会员数据 | P2 |

### 6.2 API设计

#### 6.2.1 会员接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 会员列表 | GET | `/api/admin/member/list` | 分页查询会员 |
| 会员详情 | GET | `/api/admin/member/{id}` | 会员详情 |
| 会员等级修改 | PUT | `/api/admin/member/{id}/level` | 修改会员等级 |
| 会员积分调整 | POST | `/api/admin/member/{id}/points/adjust` | 调整积分 |
| 会员禁用/启用 | PUT | `/api/admin/member/{id}/status` | 状态变更 |
| 会员标签 | GET | `/api/admin/member/{id}/tags` | 会员标签列表 |
| 会员积分明细 | GET | `/api/admin/member/{id}/points/log` | 积分流水 |

#### 6.2.2 等级接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 等级列表 | GET | `/api/admin/member-level/list` | 等级列表 |
| 创建等级 | POST | `/api/admin/member-level` | 创建等级 |
| 更新等级 | PUT | `/api/admin/member-level/{id}` | 更新等级 |
| 删除等级 | DELETE | `/api/admin/member-level/{id}` | 删除等级 |

#### 6.2.3 标签接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 标签列表 | GET | `/api/admin/member-tag/list` | 标签列表 |
| 创建标签 | POST | `/api/admin/member-tag` | 创建标签 |
| 更新标签 | PUT | `/api/admin/member-tag/{id}` | 更新标签 |
| 删除标签 | DELETE | `/api/admin/member-tag/{id}` | 删除标签 |
| 批量打标 | POST | `/api/admin/member-tag/batch-tag` | 批量打标 |

### 6.3 技术要点

#### 6.3.1 会员画像(ES聚合)

```java
@Service
public class MemberProfileService {
    
    public MemberProfileVO getProfile(Long userId) {
        // 消费统计
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(QueryBuilders.termQuery("userId", userId));
        
        // 聚合统计
        SumAggregationBuilder totalAmount = AggregationBuilders.sum("totalAmount")
            .field("payAmount");
        TermsAggregationBuilder categoryAgg = AggregationBuilders.categories("categories")
            .field("categoryId");
        
        searchSourceBuilder.aggregation(totalAmount);
        searchSourceBuilder.aggregation(categoryAgg);
        
        // 执行查询...
        
        // 构建画像数据
        MemberProfileVO profile = new MemberProfileVO();
        profile.setUserId(userId);
        profile.setTotalConsumption(aggregate.getTotalAmount());
        profile.setCategoryPreference(aggregate.getCategories());
        // ...
        
        return profile;
    }
}
```

---

## 七、营销服务

### 7.1 功能点

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 优惠券管理 | 创建、发放、核销 | P0 |
| 满减活动 | 创建、管理满减规则 | P0 |
| 秒杀活动 | 创建、管理秒杀 | P0 |
| 拼团活动 | 创建、管理拼团 | P1 |
| 砍价活动 | 创建、管理砍价（**v1.2 跨文件同步**：miniprogram-backend.md 7.5 依赖） | P1 |
| 价格计算引擎 | 多优惠叠加计算 | P0 |
| 活动管理 | 活动创建、上下线 | P1 |

### 7.2 API设计

#### 7.2.1 优惠券接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 优惠券列表 | GET | `/api/admin/coupon/list` | 分页查询 |
| 创建优惠券 | POST | `/api/admin/coupon` | 创建优惠券 |
| 更新优惠券 | PUT | `/api/admin/coupon/{id}` | 更新优惠券 |
| 删除优惠券 | DELETE | `/api/admin/coupon/{id}` | 删除优惠券 |
| 优惠券详情 | GET | `/api/admin/coupon/{id}` | 优惠券详情 |
| 领取记录 | GET | `/api/admin/coupon/{id}/records` | 领取记录 |
| 优惠券发放 | POST | `/api/admin/coupon/{id}/distribute` | 指定用户发放 |

#### 7.2.2 满减接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 满减列表 | GET | `/api/admin/full-reduction/list` | 满减活动列表 |
| 创建满减 | POST | `/api/admin/full-reduction` | 创建满减活动 |
| 更新满减 | PUT | `/api/admin/full-reduction/{id}` | 更新满减 |
| 删除满减 | DELETE | `/api/admin/full-reduction/{id}` | 删除满减 |
| 上下线 | PUT | `/api/admin/full-reduction/{id}/status` | 上下线 |

#### 7.2.3 秒杀接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 秒杀列表 | GET | `/api/admin/seckill/list` | 秒杀活动列表 |
| 创建秒杀 | POST | `/api/admin/seckill` | 创建秒杀 |
| 更新秒杀 | PUT | `/api/admin/seckill/{id}` | 更新秒杀 |
| 删除秒杀 | DELETE | `/api/admin/seckill/{id}` | 删除秒杀 |
| 上下线 | PUT | `/api/admin/seckill/{id}/status` | 上下线 |

#### 7.2.4 拼团接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 拼团列表 | GET | `/api/admin/group-buy/list` | 拼团活动列表 |
| 创建拼团 | POST | `/api/admin/group-buy` | 创建拼团（字段见 1.2.2 `admin_group_buy`） |
| 更新拼团 | PUT | `/api/admin/group-buy/{id}` | 更新拼团 |
| 删除拼团 | DELETE | `/api/admin/group-buy/{id}` | 删除拼团（进行中的团不允许删，只能下线） |
| 上下线 | PUT | `/api/admin/group-buy/{id}/status` | 上下线（对齐 7.2.2/7.2.3 约定） |
| 拼团记录 | GET | `/api/admin/group-buy/{id}/records` | 只读买家端 `group_buying`：团状态、成员数、剩余时间、超时退款结果 |
| 拼团详情 | GET | `/api/admin/group-buy/{id}` | 活动详情 + 成团/失败/进行中统计 |

> **字段对齐（v1.2 跨文件同步，来源 miniprogram-backend.md 7.4）**：`id` 即买家端发起拼团入参 `activityId`；`spu_id`/`sku_id`/`group_price`/`group_size`/`stock`/`start_time`/`end_time`/`status` 为买家端 `group_create`/`group/activity/list` 的消费字段，新增 `expire_minutes`（对应 `group_buying.expire_time`）、`auto_success`（7.4.4 虚拟拼团）、`limit_per_user`（参团限制）三项管理端配置，**任一端增删字段必须双端同步发版**。

#### 7.2.5 砍价接口（v1.2 跨文件同步，来源 miniprogram-backend.md 7.5）

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 砍价活动列表 | GET | `/api/admin/bargain/activity/list` | 分页查询（买家端 `bargain/activity/list` 的配置来源） |
| 创建砍价活动 | POST | `/api/admin/bargain/activity` | 创建，校验 `target_price ≥ 成本价`、`target_price < original_price` |
| 更新砍价活动 | PUT | `/api/admin/bargain/activity/{id}` | 更新（进行中的砍价单按原快照继续，改价只对新发起生效） |
| 删除砍价活动 | DELETE | `/api/admin/bargain/activity/{id}` | 删除，存在 `active` 砍价单时拒绝删除（进行中活动先下线） |
| 上下线 | PUT | `/api/admin/bargain/activity/{id}/status` | 上下线 |
| 砍价单列表 | GET | `/api/admin/bargain/record/list` | 只读买家端 `bargain_record`：current_price、status、帮砍进度 |
| 帮砍记录 | GET | `/api/admin/bargain/record/{id}/helps` | 只读 `bargain_help_record`：含 `risk_score` 命中的记录（审计用） |

> **三表职责（与 miniprogram-backend.md 7.5.3 对齐）**：`admin_bargain_activity`（活动配置，管理端写）/ `bargain_record`（用户砍价单，买家端写）/ `bargain_help_record`（帮砍记录，买家端写，`uk_record_helpuser` 一单一人）。管理端只对活动配置有写权限，砍价单与帮砍记录**只读**；底价保护、防刷与并发控制由买家端 7.5.4 执行，管理端不得绕过活动配置直接改 `current_price`。

### 7.3 价格计算引擎

> **职责边界（v1.1 修正，消除 1.1.3 与本节的重复定义）**：**价格计算的唯一实现是买家端共享的 `core-price` 引擎**（见 1.1.3），管理端（admin-marketing）只做两件事：①活动/规则的配置与生效管理；②调用 `core-price` 做**试算与展示**（改价前试算、审核详情展示优惠明细、结算明细重算）。**禁止在 admin-marketing 再维护第二套算价逻辑**——两套算法必然出现"管理端看到的价 ≠ 买家下单的价"。

```java
@Service
public class PriceEngineService {

    @Autowired
    private PriceClient corePriceClient; // 调用共享的 core-price，而非自行查库组装

    /**
     * 试算（管理端预览/改价联动用），全部数据经服务间通信获取
     */
    public PriceResult calculate(Long skuId, Long userId, Integer quantity) {
        // 直接委托 core-price，保证与买家端下单价完全一致
        PriceResult result = corePriceClient.calc(skuId, userId, quantity);

        // 通用兜底规则（core-price 内同样实现，此处仅为契约说明）：
        // 1) 金额统一 2 位小数、RoundingMode.HALF_UP，禁止 double 浮点；
        // 2) 优惠合计不得超过商品原价总额，最终价 floor 到 0，禁止出现负价；
        // 3) 会员折扣在优惠券/满减之后计算（叠加顺序写入规则配置，可运营调整）；
        // 4) 运费不参与"优惠"计算，单独按运费模板结算（拆单时同理，见 4.4.2）。
        return result;
    }
}
```

**试算与实付一致性校验（v1.1 补充）**：
- 下单、改价、拆单、退款各环节的重算都必须走 `core-price`，并在服务端保留 `calc_snapshot`（参数+结果+引擎版本号）；
- 任何环节出现"实付金额 ≠ 引擎计算结果"必须**拒绝提交并告警**（对账类缺陷的最后防线）；
- 价格变更通过 MQ 事件同步买家端缓存/ES（14.1.1），管理端展示"已提交同步"状态而非假装即时生效。

```java
@Service
public class PriceEngineService {
    
    @Autowired
    private ProductService productService;
    
    @Autowired
    private PromotionService promotionService;
    
    @Autowired
    private MemberService memberService;
    
    /**
     * 计算商品最终价格
     * 注意：所有数据通过服务间通信获取，不直接查库
     */
    public PriceResult calculate(Long skuId, Long userId, Integer quantity) {
        PriceResult result = new PriceResult();
        
        // 1. 获取商品原价（通过服务调用）
        ProductSku sku = productService.getSkuById(skuId);
        BigDecimal originalPrice = sku.getPrice();
        result.setOriginalPrice(originalPrice);
        
        BigDecimal finalPrice = originalPrice;
        List<PromotionDetail> promotions = new ArrayList<>();
        
        // 2. 计算单品优惠（秒杀、直降）
        PromotionDetail singlePromotion = promotionService.calculateSinglePromotion(skuId, quantity);
        if (singlePromotion != null) {
            finalPrice = singlePromotion.getPromotionPrice();
            promotions.add(singlePromotion);
        }
        
        // 3. 计算满减优惠
        BigDecimal totalAmount = finalPrice.multiply(new BigDecimal(quantity));
        PromotionDetail fullReduction = promotionService.calculateFullReduction(sku.getCategoryId(), totalAmount);
        if (fullReduction != null) {
            totalAmount = totalAmount.subtract(fullReduction.getDiscountAmount());
            promotions.add(fullReduction);
        }
        
        // 4. 计算优惠券优惠
        PromotionDetail coupon = promotionService.calculateCoupon(userId, skuId, totalAmount);
        if (coupon != null) {
            totalAmount = totalAmount.subtract(coupon.getDiscountAmount());
            promotions.add(coupon);
        }
        
        // 5. 计算会员折扣
        MemberLevel level = memberService.getMemberLevel(userId);
        if (level != null && level.getDiscount().compareTo(BigDecimal.ONE) < 0) {
            BigDecimal discountAmount = totalAmount.multiply(
                BigDecimal.ONE.subtract(level.getDiscount()));
            totalAmount = totalAmount.multiply(level.getDiscount());
            
            PromotionDetail memberDiscount = new PromotionDetail();
            memberDiscount.setType("member");
            memberDiscount.setName("会员折扣");
            memberDiscount.setDiscountAmount(discountAmount);
            promotions.add(memberDiscount);
        }
        
        result.setFinalPrice(totalAmount);
        result.setTotalDiscount(originalPrice.multiply(new BigDecimal(quantity)).subtract(totalAmount));
        result.setPromotions(promotions);
        
        return result;
    }
}
```

---

## 八、财务服务

### 8.1 功能点

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 对账管理 | 支付对账、退款对账 | P0 |
| 结算管理 | 商家结算、结算单管理 | P0 |
| 佣金管理 | 佣金规则、佣金计算 | P0 |
| 提现管理 | 提现审核、打款 | P0 |
| 发票管理 | 发票申请、开票 | P1 |
| 财务报表 | 收入、支出、利润报表 | P1 |

### 8.2 API设计

#### 8.2.1 对账接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 对账列表 | GET | `/api/admin/reconciliation/list` | 对账记录列表 |
| 对账详情 | GET | `/api/admin/reconciliation/{id}` | 对账详情 |
| 手动对账 | POST | `/api/admin/reconciliation/manual` | 手动对账 |
| 对账差异处理 | POST | `/api/admin/reconciliation/{id}/handle` | 处理对账差异 |
| 对账报表 | GET | `/api/admin/reconciliation/report` | 对账统计报表 |

#### 8.2.2 结算接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 结算列表 | GET | `/api/admin/settlement/list` | 结算单列表 |
| 结算详情 | GET | `/api/admin/settlement/{id}` | 结算单详情 |
| 确认结算 | POST | `/api/admin/settlement/{id}/confirm` | 确认结算 |
| 打款 | POST | `/api/admin/settlement/{id}/pay` | 确认打款 |
| 结算明细 | GET | `/api/admin/settlement/{id}/items` | 结算明细 |

#### 8.2.3 提现接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 提现列表 | GET | `/api/admin/withdraw/list` | 提现申请列表 |
| 提现详情 | GET | `/api/admin/withdraw/{id}` | 提现详情（含审批链路、风控标记） |
| 初审 | POST | `/api/admin/withdraw/{id}/first-audit` | 财务专员初审（通过→待复审 / 拒绝→已拒绝） |
| 复审 | POST | `/api/admin/withdraw/{id}/second-audit` | 财务主管复审（通过→待打款），**初审与复审人不得为同一人** |
| 确认打款 | POST | `/api/admin/withdraw/{id}/paid` | 对接支付平台打款，幂等（防重复打款），记录渠道流水号 |

> **两级审批（v1.1 修正，与前端 9.4/9.6 对齐）**：v1.0 只有 `approve/reject/paid` 单级审批，不满足"财务初审 → 财务复审 → 打款"与审批留痕要求。状态机：`0待初审 → 1待复审 → 2待打款 → 3已打款`，任一步可 `4已拒绝`；审批链路（初审/复审人、时间、意见）落 `admin_withdraw` 字段并同步写审核服务的审批记录（3.4），详情页展示且不可篡改。
> **风控（前端 9.4）**：大额（≥系统参数阈值）、频繁（7 日 ≥ N 次）、账户信息变更后首次提现，自动打"需人工复核"标记并强制走复审；打款失败进入可重试状态并告警，禁止静默吞错。

#### 8.2.4 发票接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 发票列表 | GET | `/api/admin/invoice/list` | 发票申请列表 |
| 发票详情 | GET | `/api/admin/invoice/{id}` | 发票详情 |
| 开票 | POST | `/api/admin/invoice/{id}/issue` | 确认开票 |
| 邮寄 | POST | `/api/admin/invoice/{id}/mail` | 确认邮寄 |

### 8.3 技术要点

#### 8.3.1 佣金计算

> **v1.1 修正**：原文 `item.getCategoryId()` 在 `admin_order_item` 表中**不存在**（调用即运行时报错）。已在 1.2.2 订单商品表补充 `category_id` 冗余字段（下单时从 SPU 快照），佣金计算不再跨服务回查商品。另注意：①佣金在**订单完成后**计提，退款需按比例**冲抵**已计提佣金（对账差异主要来源之一）；②规则命中优先级：店铺专属规则 > 类目规则 > 默认规则，同一优先级取生效时间最新。

```java
@Service
public class CommissionService {
    
    @Autowired
    private CommissionRuleService commissionRuleService;
    
    /**
     * 计算订单佣金
     */
    public BigDecimal calculateCommission(Order order) {
        List<OrderItem> items = orderItemMapper.selectByOrderId(order.getId());
        BigDecimal totalCommission = BigDecimal.ZERO;
        
        for (OrderItem item : items) {
            // 获取佣金规则（通过服务调用）
            CommissionRule rule = commissionRuleService.getRule(
                item.getCategoryId(), item.getStoreId());
            
            // 计算佣金
            BigDecimal itemAmount = item.getPrice()
                .multiply(new BigDecimal(item.getQuantity()));
            BigDecimal commission = itemAmount.multiply(rule.getRate());
            
            // 最低佣金
            if (commission.compareTo(rule.getMinCommission()) < 0) {
                commission = rule.getMinCommission();
            }
            
            totalCommission = totalCommission.add(commission);
        }
        
        return totalCommission;
    }
}
```

#### 8.3.2 对账流程

```
┌──────────┐    拉取对账文件    ┌──────────┐
│ 支付渠道  │ ──────────────► │ 对账服务  │
│ (支付宝等)│                 │          │
└──────────┘                 └────┬─────┘
                                  │
                    ┌─────────────┼─────────────┐
                    ▼             │             ▼
              ┌──────────┐       │       ┌──────────┐
              │ 平台订单  │       │       │ 渠道流水  │
              └──────────┘       │       └──────────┘
                    │             │             │
                    └─────────────┼─────────────┘
                                  ▼
                           ┌──────────┐
                           │ 比对结果  │
                           │ 1. 平台有  │
                           │    渠道无  │
                           │ 2. 平台无  │
                           │    渠道有  │
                           │ 3. 金额不符│
                           └──────────┘
```

#### 8.3.3 分布式事务方案

**方案选择**：
- **Seata AT模式**：适用于跨服务数据一致性要求高的场景（如支付+库存）
- **本地消息表**：适用于最终一致性场景（如订单创建+积分增加）
- **TCC模式**：适用于需要预留资源的场景（如库存扣减）

**Seata配置示例**：
```yaml
seata:
  enabled: true
  application-id: admin-finance
  tx-service-group: admin_finance_tx_group
  service:
    vgroup-mapping:
      admin_finance_tx_group: default
  registry:
    type: nacos
    nacos:
      server-addr: ${NACOS_ADDR}
      namespace: ${NACOS_NAMESPACE}
```

### 8.4 财务通用规范（v1.1 新增，与前端 9.6 逐条对齐）

| 项 | 约定 |
|----|------|
| 金额精度 | DB 用 `DECIMAL`，接口传输**统一字符串或"分"整数**，禁止浮点；服务端计算统一 `RoundingMode.HALF_UP`，与前端 `formatAmount` 口径一致 |
| 审批留痕 | 结算调整、提现审核（初审/复审）、保证金扣减、订单改价超阈值，**强制走审核中心审批流**（3.4），审批链路详情可见且不可篡改 |
| 导出管控 | 财务导出需 `finance:export` + 二次确认，文件加操作人水印，下载链接 24h 有效，全部写操作日志（见 4.4.3） |
| 权限隔离 | 结算金额、佣金、成本对非财务角色脱敏（`finance:read` / `cost:read`，见 2.2.3） |
| 资金操作幂等 | 结算打款、提现打款、退款执行均需幂等键 + 渠道流水号唯一索引，**同一笔资金操作只允许成功一次** |
| 对账差异 | 差异必须逐笔下钻到订单，并人工标记原因（渠道手续费/退款时间差/跨账期等），未标记的差异不得关闭对账单 |
| 账户与流水 | 新增商家/平台**资金账户余额表 + 资金流水表**（v1.1 补充：v1.0 有结算/提现/保证金，但没有账户余额与流水，无法解释"钱从哪来、到哪去"），所有余额变动必须伴随一条流水，余额 = Σ流水，定时校验 |

---

## 九、内容服务

### 9.1 功能点

| 功能 | 说明 | 优先级 |
|------|------|--------|
| Banner管理 | Banner CRUD、排序、上下线 | P0 |
| 公告管理 | 公告发布、编辑、删除 | P0 |
| 专题页管理 | 专题页创建、编辑、发布 | P1 |
| CMS文章 | 文章管理、分类管理 | P1 |

### 9.2 API设计

#### 9.2.1 Banner接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| Banner列表 | GET | `/api/admin/banner/list` | Banner列表 |
| 创建Banner | POST | `/api/admin/banner` | 创建Banner |
| 更新Banner | PUT | `/api/admin/banner/{id}` | 更新Banner |
| 删除Banner | DELETE | `/api/admin/banner/{id}` | 删除Banner |
| 排序 | PUT | `/api/admin/banner/sort` | Banner排序 |
| 启用/禁用 | PUT | `/api/admin/banner/{id}/status` | 状态变更 |

#### 9.2.2 公告接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 公告列表 | GET | `/api/admin/notice/list` | 公告列表 |
| 创建公告 | POST | `/api/admin/notice` | 创建公告 |
| 更新公告 | PUT | `/api/admin/notice/{id}` | 更新公告 |
| 删除公告 | DELETE | `/api/admin/notice/{id}` | 删除公告 |
| 发布 | POST | `/api/admin/notice/{id}/publish` | 发布公告 |

#### 9.2.3 专题页接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 专题列表 | GET | `/api/admin/topic/list` | 专题列表 |
| 创建专题 | POST | `/api/admin/topic` | 创建专题 |
| 更新专题 | PUT | `/api/admin/topic/{id}` | 更新专题 |
| 删除专题 | DELETE | `/api/admin/topic/{id}` | 删除专题 |
| 发布 | POST | `/api/admin/topic/{id}/publish` | 发布专题 |

#### 9.2.4 CMS文章接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 文章列表 | GET | `/api/admin/cms/list` | 文章列表 |
| 创建文章 | POST | `/api/admin/cms` | 创建文章 |
| 更新文章 | PUT | `/api/admin/cms/{id}` | 更新文章 |
| 删除文章 | DELETE | `/api/admin/cms/{id}` | 删除文章 |
| 发布 | POST | `/api/admin/cms/{id}/publish` | 发布文章 |

---

## 十、安全设计

### 10.1 接口安全

#### 10.1.1 接口幂等性

- **幂等Key机制**：客户端生成唯一幂等Key，服务端Redis去重
- **数据库唯一约束**：关键业务表添加唯一索引
- **乐观锁**：更新操作使用version字段

#### 10.1.2 防重放攻击

- **时间戳校验**：请求时间与服务器时间差不超过5分钟
- **Nonce随机数**：每次请求携带随机数，服务端校验
- **签名机制**：关键接口使用HMAC-SHA256签名

### 10.2 数据安全

#### 10.2.1 敏感数据加密

| 数据类型 | 加密方式 | 说明 |
|---------|---------|------|
| 手机号 | AES加密 | 存储加密，展示脱敏 |
| 邮箱 | AES加密 | 存储加密，展示脱敏 |
| 身份证号 | AES加密 | 存储加密，展示脱敏 |
| 银行卡号 | AES加密 | 存储加密，展示脱敏 |
| 密码 | BCrypt | 单向哈希 |

#### 10.2.2 XSS防护

- **输入过滤**：所有用户输入进行HTML实体编码
- **输出编码**：前端展示时使用`v-html`需配合XSS过滤库
- **CSP策略**：配置Content-Security-Policy响应头

#### 10.2.3 SQL注入防护

- **参数化查询**：全部使用MyBatis参数绑定
- **ORM框架**：禁止拼接SQL
- **权限最小化**：数据库用户权限最小化

### 10.3 文件上传安全

- **文件类型白名单**：仅允许jpg/png/pdf等指定类型
- **文件大小限制**：单文件不超过10MB
- **文件内容检测**：使用magic bytes检测文件真实类型
- **病毒扫描**：上传文件经过ClamAV扫描
- **存储隔离**：上传文件存储在非Web根目录

---

## 十一、性能优化

### 11.1 数据库优化

#### 11.1.1 连接池配置（HikariCP）

```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 10
      maximum-pool-size: 50
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      connection-test-query: SELECT 1
```

#### 11.1.2 读写分离

- **主库**：处理写操作和关键读操作
- **从库**：处理非关键读操作
- **强制走主库**：关键业务（如库存扣减、支付）强制走主库
- **主从延迟处理**：写后读场景，延迟等待或强制走主库

#### 11.1.3 索引优化

- **慢查询监控**：开启MySQL慢查询日志，定期分析
- **索引覆盖**：高频查询使用覆盖索引
- **联合索引**：遵循最左前缀原则
- **避免索引失效**：不在索引列上使用函数或表达式

### 11.2 缓存优化

#### 11.2.1 多级缓存

```
请求 → 本地缓存(Caffeine) → Redis集群 → 数据库
```

#### 11.2.2 缓存预热

- **启动预热**：应用启动时加载热点数据
- **定时预热**：定时任务刷新缓存
- **懒加载**：首次访问时加载

### 11.3 Elasticsearch优化

#### 11.3.1 索引设计

- **分片数**：根据数据量设置，单分片不超过50GB
- **副本数**：生产环境至少1个副本
- **Mapping设计**：明确字段类型，避免动态映射
- **分词器**：中文使用IK分词器

#### 11.3.2 查询优化

- **避免深度分页**：使用search_after替代from+size
- **过滤优先**：使用filter上下文，不计算评分
- **聚合优化**：使用composite聚合分页

### 11.4 限流熔断

#### 11.4.1 网关层限流

> **执行器口径（v1.3 跨架构裁决 C5/C6）**：**网关侧限流执行器为 APISIX/Higress 原生插件（本地令牌桶 + Redis 集群限流），规则与 Sentinel 同源 Nacos，执行器分家**（Sentinel 官方无 Go SDK，只管 Java 服务内熔断/自适应限流/集群流控）。下述配置为 **Java 服务侧**的规则消费示例：规则文件统一存 Nacos（`{应用名}-flow-rules`），网关插件与 Sentinel 读取同一份规则数据，**禁止在网关进程内嵌 Sentinel Agent**。

```yaml
# 规则配置示例（Java 服务侧 Sentinel 消费；网关侧由 APISIX/Higress 限流插件读取同源 Nacos 规则）
spring:
  cloud:
    sentinel:
      transport:
        dashboard: sentinel-dashboard:8080
      datasource:
        flow:
          nacos:
            server-addr: ${NACOS_ADDR}
            data-id: ${spring.application.name}-flow-rules
            rule-type: flow
```

#### 11.4.2 服务层熔断

```java
@SentinelResource(value = "productService", 
    blockHandler = "handleBlock",
    fallback = "handleFallback")
public Product getProduct(Long id) {
    return productMapper.selectById(id);
}
```

---

## 十二、监控与运维

### 12.1 监控体系

#### 12.1.1 指标监控（Prometheus + Grafana）

- **应用指标**：QPS、RT、错误率、JVM指标
- **中间件指标**：MySQL连接数、Redis命中率、RabbitMQ堆积
- **业务指标**：订单量、支付金额、库存预警数

#### 12.1.2 链路追踪（SkyWalking）

- **TraceID**：全链路追踪
- **Span**：服务间调用耗时
- **拓扑图**：服务依赖关系可视化

#### 12.1.3 日志收集（ELK）

- **Filebeat**：日志采集
- **Logstash**：日志处理
- **Elasticsearch**：日志存储
- **Kibana**：日志查询

### 12.2 告警策略

| 告警级别 | 触发条件 | 通知方式 |
|---------|---------|---------|
| P0紧急 | 服务不可用、支付异常 | 电话+短信+钉钉 |
| P1严重 | 错误率>5%、RT>1s | 短信+钉钉 |
| P2警告 | 错误率>1%、RT>500ms | 钉钉 |
| P3提示 | 磁盘使用>80%、内存使用>80% | 钉钉 |

### 12.3 数据归档

#### 12.3.1 归档策略

| 数据类型 | 归档周期 | 归档存储 |
|---------|---------|---------|
| 订单数据 | 在线保留 12 个月，之后转历史库 | OceanBase 历史库（13~36 个月）；> 36 个月转 OSS Parquet（富文本/大对象外置 OSS，库内只存 url/md5/size） |
| 操作日志 | 6个月后 | Elasticsearch冷索引 |
| 登录日志 | 3个月后 | Elasticsearch冷索引 |
| 库存流水 | 1年后 | OceanBase 历史库 / OSS Parquet |

> **v1.3 修订（跨架构裁决 C13 + 02 §4.1/§7#12）**：原"订单/库存流水 → HBase"口径**作废**，改为 **OceanBase 分区归档 + 大对象外置 OSS**。理由：① 引入 HBase 等于多一套集群与运维（RowKey 设计、Region 热点、GC）；② OB 历史库与在线库同 SQL、同连接，业务查询代码零分叉；③ 冷数据 Parquet 存储成本约为 HBase 的 1/5。

#### 12.3.2 归档实现（v1.3 修订：OB 分区归档 + 大对象外置 OSS）

> **原实现作废（跨架构裁决 C13 + 02 §7#12）**：`selectBeforeDate` 为无分页全表扫描、`deleteBeforeDate` 为单事务大删除，**100 亿行规模下必崩**（锁表 + binlog 爆炸 + OOM），HBase 写入口径一并作废。

**改为 OceanBase 分区归档 + 大对象外置 OSS，按分区批量迁移**（完整规范见 `architecture/02-data-storage.md` §4.2）：

1. **水位表**：`archive_watermark(table, slot, month, last_id, status)` 记录断点，支持断点续传与幂等重跑；
2. **选批**：`WHERE created_at < :cutoff AND id > :last_id ORDER BY id LIMIT 5000`（走主键，不扫二级索引）；
3. **按分区批量迁移**：写入 OB 历史库（同 slot 分区，1000 行/事务，单事务 ≤ 500ms）；**富文本/大对象（商品 detail、支付报文等）外置 OSS**，库内只存 `url/md5/size`；
4. **校验**：行数 + `SUM(pay_amount)` + `MAX(id)` 三元守恒，不一致即重试/告警；
5. **下线在线数据**：分区方案走 `EXCHANGE PARTITION` 后 `DROP`（秒级）；行方案 `DELETE ... WHERE id BETWEEN ? AND ?` 限速 2 万行/s，禁止大事务删除；
6. **限速与错峰**：全局 ≤ 2 万行/s，仅 02:00~06:00 运行，超限自动挂起；单 slot 失败不影响其他 slot，连续 3 天失败转 P2 告警 + 人工介入。

```java
@Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点，低峰窗口
public void archiveOrders() {
    // 按水位 + 分区分批迁移，禁止 selectBeforeDate 全表扫描
    for (ArchiveSlot slot : watermarkService.pendingSlots("order_main")) {
        List<Order> batch = orderMapper.selectBatchBeforeDate(
                slot.getCutoff(), slot.getLastId(), 5000);   // 主键分批，不扫全表
        if (batch.isEmpty()) { watermarkService.finish(slot); continue; }
        historyDb.batchInsert(batch);                        // 写 OB 历史库（1000 行/事务）
        ossArchiver.dumpLob(batch);                          // 大对象外置 OSS，库内只留 url/md5/size
        VerifyTriple v = verifier.triple(batch);             // 行数 + 金额守恒 + MAX(id) 三元校验
        if (!v.consistent()) { alertService.p2("archive verify failed: " + slot); return; }
        partitionAdmin.exchangeAndDrop(slot.getPartition()); // 分区秒级下线，替代 deleteBeforeDate
        watermarkService.save(slot.advance());               // 落水位，断点续传
    }
}
```

---

## 十三、分布式事务与数据一致性

### 13.1 分布式事务方案

| 方案 | 适用场景 | 一致性 | 性能 |
|------|---------|--------|------|
| 2PC/XA | 单数据库跨库 | 强一致性 | 低 |
| TCC | 跨服务资源预留 | 最终一致性 | 中 |
| Saga | 长事务 | 最终一致性 | 高 |
| 本地消息表 | 跨服务异步 | 最终一致性 | 高 |
| Seata AT | 跨服务同步 | 最终一致性 | 中 |

**落地优先级（v1.1 补充，避免"三个方案同时全量上线"）**：
1. **默认方案：本地消息表 + MQ（13.2.1）+ 定时对账（13.2.2）**，覆盖订单-库存-营销-数据同步等绝大多数链路；成本最低、故障可人工介入。
2. **Seata AT 仅在"必须同步回滚且无法拆成补偿"的少数链路启用**（如结算单生成 + 账户余额变动），且需 Nacos/Seata Server 随部署交付（1.5 已补 Nacos）；启用前先评估锁竞争与脏回滚风险，长事务链路禁用。
3. **TCC 只保留接口约定**，v1.1 阶段不实现（库存已用"锁库→出库→解锁"两段式 + 幂等补偿等效实现，见 5.3.1、4.4.4）。
4. **2PC/XA 不作为业务方案**：仅在特殊迁移场景临时使用，写入性能代价在本平台不可接受。
5. 所有跨服务方案必须回答三个问题：**谁补偿、补偿失败怎么办、人工介入入口在哪**；补偿失败的任务进死信队列 + 告警（12.2），不允许无限重试。

### 13.2 最终一致性保障

#### 13.2.1 本地消息表

```java
@Service
public class OrderService {
    
    @Transactional
    public void createOrder(Order order) {
        // 1. 插入订单
        orderMapper.insert(order);
        
        // 2. 插入消息表（同一事务）
        Message msg = new Message();
        msg.setExchange("admin.order.exchange");
        msg.setRoutingKey("order.created");
        msg.setData(JsonUtil.toJson(order));
        msg.setStatus(0); // 待发送
        messageMapper.insert(msg);
    }
}

@Scheduled(cron = "0 */5 * * * ?") // 每5分钟
public void sendPendingMessages() {
    List<Message> messages = messageMapper.selectPending();
    for (Message msg : messages) {
        try {
            rabbitTemplate.convertAndSend(msg.getExchange(), msg.getRoutingKey(), msg.getData());
            msg.setStatus(1); // 已发送
            messageMapper.updateById(msg);
        } catch (Exception e) {
            log.error("消息发送失败", e);
        }
    }
}
```

#### 13.2.2 定时对账

```java
@Scheduled(cron = "0 0 */1 * * ?") // 每小时
public void reconcileOrders() {
    // 对账逻辑：检查订单状态与库存扣减是否一致
    List<Order> orders = orderMapper.selectUnreconciled();
    for (Order order : orders) {
        // 检查库存是否已扣减
        boolean deducted = inventoryService.checkDeducted(order.getOrderNo());
        if (!deducted) {
            // 补偿扣减
            inventoryService.deductByOrder(order);
        }
    }
}
```

---

## 十四、与买家端数据交互

### 14.1 事件同步机制

#### 14.1.1 商品变更同步

```
管理端操作 → 发送MQ事件 → 买家端消费 → 更新本地缓存/ES索引
```

| 事件类型 | 触发时机 | 买家端处理 |
|---------|---------|-----------|
| 商品上架 | 审核通过 | 更新缓存，加入ES索引 |
| 商品下架 | 手动下架 | 删除缓存，从ES移除 |
| 价格变更 | 修改价格 | 更新缓存，更新ES |
| 库存变更 | 库存变动 | 更新缓存 |

#### 14.1.2 订单状态同步

| 事件类型 | 触发时机 | 买家端处理 |
|---------|---------|-----------|
| 订单创建 | 用户下单 | 刷新订单列表缓存 |
| 订单支付 | 支付成功 | 刷新订单详情缓存 |
| 订单发货 | 管理员发货 | 更新物流信息 |
| 订单完成 | 确认收货 | 更新订单状态 |

### 14.2 缓存一致性方案

- **管理端更新时**：主动删除买家端缓存（通过MQ通知）
- **买家端读取时**：先读缓存，缓存未命中读DB并回填
- **定时对账**：每5分钟对账一次，修复不一致

---

## 十五、测试策略

### 15.1 单元测试

- **覆盖率要求**：核心业务代码覆盖率≥80%
- **框架**：JUnit5 + Mockito
- **命名规范**：`should_xxx_when_xxx`

### 15.2 集成测试

- **测试环境**：Docker Compose一键启动
- **数据准备**：使用Flyway管理测试数据
- **接口测试**：使用RestAssured

### 15.3 性能测试

- **工具**：JMeter / Gatling
- **场景**：模拟双11峰值流量
- **指标**：RT<200ms，错误率<0.1%

---

## 十六、上线计划

### 16.1 灰度发布

```
第1周：内部测试 → 第2周：1%流量 → 第3周：5%流量 → 第4周：20%流量 → 第5周：50%流量 → 第6周：100%流量
```

- **上线验收项（v1.2 跨文件同步）**：`user.deleted` 端到端联调通过（买家端注销 → 冷静期结束 → `core-user`/`admin-data` 删除并回执审计表），与 ios-backend.md 13.5 Checklist 同项关闭，**未通过不得放量至 100%**。

### 16.2 回滚方案

- **自动回滚**：错误率>5%自动回滚
- **手动回滚**：保留前3个版本镜像，支持快速回滚
- **数据回滚**：数据库变更使用Flyway，支持版本回退

---

## 十七、其余模块服务设计（v1.1 新增）

> v1.0 只给这些模块建了表（1.2.2），没有功能点、API 与流程，导致前端对应页面（admin-frontend 十三/十四/十五/十一/十/三）全部落空。本章统一补齐契约与关键约束。

### 17.1 店铺服务（admin-store）

**功能点**：店铺列表/详情、入驻审核、等级管理、保证金、评分（DSR）、冻结/解冻/关闭。

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 店铺列表 | GET | `/api/admin/store/list` | 按名称/类型/类目/状态/入驻时间筛选 |
| 店铺详情 | GET | `/api/admin/store/{id}` | 含资质、保证金余额、DSR 明细 |
| 入驻审核-通过 | POST | `/api/admin/store/{id}/approve` | 生成审核任务记录（3.4，biz_type=2） |
| 入驻审核-驳回 | POST | `/api/admin/store/{id}/reject` | 原因必填 |
| 补充材料 | POST | `/api/admin/store/{id}/supplement` | 退回商家补件，留痕 |
| 等级 CRUD | GET/POST/PUT/DELETE | `/api/admin/store-level`... | 升降级规则（GMV、评分、时长）+ 有效期 |
| 保证金列表/扣减/退还 | GET/POST | `/api/admin/deposit`... | 扣减需审批（3.4），流水见 `admin_deposit_log` |
| DSR 评分统计 | GET | `/api/admin/store/{id}/dsr` | 描述相符/服务态度/物流速度三维度 + 趋势 |
| 冻结/解冻/关闭 | POST | `/api/admin/store/{id}/freeze`... | 关闭前校验未完成订单与未结算款项 |

**关键流程与约束**：
1. **入驻流程**（与前端 13.2 一致）：商家提交 → 资质审核 → 缴纳保证金 → 开店成功；`admin_store.status`：0-待审核、1-正常、2-关闭、3-冻结，非法流转由状态机校验（同 4.4.1 思路）。
2. **资质与隐私**：营业执照、法人身份证等证照存 OSS 私有 Bucket，展示走**临时签名 URL**；查看完整信息需 `shop:idcard:read` 权限，**每次查看写审计日志并触发敏感操作告警**（与前端 13.2 隐私合规一致）；OCR 结果仅供参考，以人工审核为准。
3. **DSR 维度**：`admin_store` 只有综合分 `dsr_score`，v1.1 补充 `admin_store_dsr`（store_id、维度、分数、统计周期、样本量），否则前端 13.5 的三维度展示无数据来源。
4. **冻结/关闭**：冻结后买家不可下单、商品强制下架（发 MQ `product.off_shelf_by_store`）；关闭走"处理未完成订单 → 结算尾款 → 保证金退还"的退出清单，未清完不允许关闭。
5. **数据权限**：merchant 角色按 2.3.1 第 6 档（本店铺）过滤，店铺相关列表接口一律带 `store_id` 过滤断言。

### 17.2 客服服务（admin-cs）

**功能点**：工单管理、会话管理、智能客服配置、绩效与排班。

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 工单列表/详情 | GET | `/api/admin/cs/ticket`... | 筛选：类型、优先级、状态、处理人 |
| 工单分配 | POST | `/api/admin/cs/ticket/{id}/assign` | 主管分配，乐观锁防抢 |
| 工单流转 | POST | `/api/admin/cs/ticket/{id}/transition` | 待处理→处理中→已解决→已关闭 |
| 会话列表 | GET | `/api/admin/cs/session/list` | 买家、店铺、等待时长、状态 |
| 接入/转接 | POST | `/api/admin/cs/session/{id}/accept|transfer` | 自动分配（轮询/技能组） |
| 历史消息 | GET | `/api/admin/cs/session/{id}/messages` | 分页拉取，导出需 `cs:export` + 脱敏 |
| 智能客服规则 CRUD | GET/POST/PUT/DELETE | `/api/admin/cs/smart-config`... | 关键词/意图 → 回复/转人工 |
| 绩效统计 | GET | `/api/admin/cs/performance` | 接待量、响应时长、解决率、满意度 |
| 排班 CRUD | GET/POST/PUT/DELETE | `/api/admin/cs/schedule`... | 技能组 + 工作时间 + 在线状态 |

**关键约束**：
1. **WebSocket 鉴权**（与前端 15.2 一致）：握手即校验 Access Token（query 或 subprotocol），未授权直接断开；心跳 30s，断线指数退避重连；重连后同步会话列表与未读消息。
2. **消息协议统一**：文本/图片/表情/订单卡片/商品卡片的消息结构与买家端各端统一定义（复用 1.7 的共享协议），禁止各端各定义一套。
3. **超时与升级**：紧急工单超时未处理自动升级并告警；会话等待超时自动转留言 → 生成工单，不允许"无限等待"。
4. **敏感词与风控**：会话内容过敏感词库，频繁发送限流；会话导出脱敏（手机号、地址）且需主管权限。
5. **待补表**：客服绩效、排班、在线状态的表在 v1.0 未定义，P1 阶段补 `admin_cs_performance`、`admin_cs_schedule`、`admin_cs_online_status`。

### 17.3 消息服务（admin-message）

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 通知创建/列表 | GET/POST | `/api/admin/message/notice`... | 定时发送、发送范围（全部/等级/标签/指定用户） |
| 模板 CRUD | GET/POST/PUT/DELETE | `/api/admin/message/template`... | 变量配置、预览 |
| 模板审核 | POST | `/api/admin/message/template/{id}/audit` | 短信/微信模板需平台审核（对应前端 14.4） |
| 发送记录 | GET | `/api/admin/message/record` | 接收人、渠道、内容、成功/失败、送达率 |
| 失败重发 | POST | `/api/admin/message/record/{id}/resend` | 幂等，防止重复轰炸（按 bizNo 去重） |
| 推送配置 | GET/PUT | `/api/admin/message/push-config` | 物流提醒、营销推送的渠道与策略 |

**约束**：
1. 站内信/短信/邮件/推送统一走 `message.send` 事件，**模板变量渲染在消费端完成**，发送失败进重试（3 次）→ DLQ → 人工重发。
2. **营销推送防骚扰**：同一用户 24h 内营销类推送次数受系统参数限制；退订用户永不推送。
3. 所有对外发送记录必须可查（合规要求），保留 180 天。

### 17.4 数据服务与看板（admin-data）

**定位**：报表、导出任务处理、实时看板。**取数方式受限**（见 1.1.1 补充）：只消费 MQ + 调用各服务查询 API，聚合结果写 `admin_data_db`，禁止直连业务库。

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 工作台概览 | GET | `/api/admin/data/workbench` | 今日 GMV/订单数/新增用户/待办计数 |
| 待办聚合 | GET | `/api/admin/data/todo` | 待审核商品、待发货订单、待处理退款、待回复工单 |
| 销售趋势 | GET | `/api/admin/data/sales-trend` | 日/周/月，环比同比 |
| 品类/地区/店铺报表 | GET | `/api/admin/data/report/*` | 维度下钻（品类→子品类→商品） |
| 会员报表 | GET | `/api/admin/data/member/*` | 新增趋势、等级分布、留存、RFM |
| 商品报表 | GET | `/api/admin/data/product/*` | Top100、转化漏斗、库存周转 |
| 营销效果 | GET | `/api/admin/data/marketing/*` | 券 ROI、活动效果归因 |
| 自定义报表 | GET/POST | `/api/admin/data/custom-report`... | 设计器、保存（个人/团队）、定时推送 |
| 导出任务 | GET | `/api/admin/data/export-task`... | 见 4.4.3（任务号、状态、下载） |

**关键约束**：
1. **数据权限联动**（前端 3.1）：非"全部数据"角色的看板/报表必须按 2.3 档位过滤聚合口径；无权限的指标卡片后端直接不返回。
2. **实时性分层**：P0 用"MQ 消费 + Redis 增量计数"支撑分钟级看板（今日 GMV 等）；日/周/月报表走 T+1 离线任务；**Flink/Spark 仅在离线口径无法满足且量级证明必要时引入**（v1.0 修改点 1 声称已补充 Flink 但正文缺失，此处为正式口径，避免过度设计）。
3. WebSocket 推送实时订单流时，需鉴权 + 心跳（同 17.2），断线重连后**补拉最近数据**保证最终一致（前端 3.1 已按此实现）。
4. 大盘查询必须走预聚合表/物化视图，**禁止在线 API 直接对大表做 group by**。

### 17.5 文件服务（admin-file）

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 上传 | POST | `/api/admin/file/upload` | 类型白名单 + 大小限制 + magic bytes 检测（10.3） |
| 私有文件签名 URL | GET | `/api/admin/file/sign` | 临时 URL，权限校验 + 有效期 |
| 证照上传 | POST | `/api/admin/file/credential` | 仅入驻/资质场景，强制私有 Bucket + 查看留痕 |

**约束**：与买家端共用对象存储但**路径隔离**（`admin/` 前缀），防止越权遍历；上传走 ClamAV 扫描；生成缩略图与水印（导出件、证照）；禁止公开直链（含商品图之外的一切资质文件）。

### 17.6 物流服务（admin-logistics）

**功能点**：运费模板、物流轨迹、面单打印（P1/P2）。

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 运费模板 CRUD | GET/POST/PUT/DELETE | `/api/admin/freight-template`... | 按地区/重量/件数计费 |
| 物流公司 CRUD | GET/POST/PUT/DELETE | `/api/admin/logistics/company`... | 编码、对接配置 |
| 轨迹查询 | GET | `/api/admin/logistics/trace` | 按运单号查询，缓存 5 分钟，异常件标记 |
| 面单打印 | POST | `/api/admin/logistics/waybill/print` | P2，对接打印服务，需仓库权限 |

**约束**：运费模板是拆单（4.4.2）与下单运费计算的唯一来源；轨迹查询对接第三方有频率限制，必须缓存 + 重试 + 失败降级（展示"轨迹暂不可用"而非报错）。

### 17.7 帮助中心与内容补充（admin-content）

- v1.0 内容模块缺**帮助中心**（前端 10.5），v1.1 补 `admin_help_category`、`admin_help_article` 两张表（分类、标题、富文本、排序、状态），API 同 CMS 文章风格（`/api/admin/help/...`）。
- 富文本一律后端白名单过滤（10.2.2），专题页 `page_content` 的 JSON 配置需做 schema 校验，防止注入任意组件。

## 审查记录

### v1.0 自查记录（修改点汇总）

| 序号 | 问题类型 | 问题描述 | 修改内容 |
|------|---------|---------|---------|
| 1 | 完整性 | 缺少数据看板实时计算方案 | 补充Flink/Spark实时计算说明 |
| 2 | 完整性 | 缺少工作流引擎 | 新增工作流定义表`admin_workflow_definition` |
| 3 | 完整性 | 缺少订单操作日志表 | 新增`admin_order_operation_log`表 |
| 4 | 完整性 | 缺少发票审批流程 | 补充发票审批流程说明 |
| 5 | 合理性 | 技术栈混合无边界 | 明确各语言选型标准和服务对应关系 |
| 6 | 合理性 | 分库分表策略过于复杂 | 简化为垂直拆分+水平分表 |
| 7 | 合理性 | Redis缓存层级矛盾 | 明确Sentinel和Cluster的使用场景 |
| 8 | 一致性 | 命名风格不统一 | 统一使用下划线命名 |
| 9 | 一致性 | 系统配置表字段名冲突 | `key`改为`config_key` |
| 10 | 一致性 | 商品表缺少版本控制 | 所有表增加`version`字段 |
| 11 | 可实施性 | 价格引擎跨库查询 | 改为通过服务间通信获取数据 |
| 12 | 可实施性 | 导出文件写本地路径 | 改为写入容器tmp目录 |
| 13 | 可实施性 | ES版本过旧 | 升级为8.15.0 |
| 14 | 边界情况 | 缺少分布式事务方案 | 新增第十三章，详细说明Seata/TCC/Saga方案 |
| 15 | 边界情况 | 缺少超时关单/库存释放与导出幂等边界 | v1.1 补充 4.4.4、4.4.3 幂等与签名下载 |

> 注：v1.0 收尾时本行曾被截断（缺失第 15 条完整内容），v1.1 已修复并补齐。

### v1.0 → v1.1 修改记录

- [1] 文档收尾截断：`修改点汇总` 第 15 条残缺（以 `缺少</longcat_think>` 结尾）→ 修复该行并补齐第 15 条，注明截断原因
- [2] 完整性：店铺/审核/客服/消息/数据/文件/物流 7 个模块"有表无接口"（前端十三/十四/十五/十一/十 依赖） → 新增「十七、其余模块服务设计」，逐模块补功能点、API、流程与隐私/风控约束
- [3] 完整性：缺 RBAC 系统管理与认证接口（登录/刷新/菜单/用户/角色/权限/日志/配置/字典） → 新增 2.5，含登录安全策略（3 次验证码、5 次锁定 15 分钟、单端登录）
- [4] 完整性：缺审核中心 API 与"退回修改"流转（前端 4.3 有该操作） → 新增 3.4 审核接口；3.3.2 补退回修改流转与并发/补偿规则
- [5] 完整性：缺订单超时关单与锁定库存释放实现（状态机有该边、库存表有 locked_stock 但无释放路径） → 新增 4.4.4（MQ 延迟队列 + 定时兜底 + 释放幂等）
- [6] 完整性：缺关闭订单/修改地址/催付/合单接口（前端 5.2、5.4 依赖） → 4.1 功能点、4.2.1 接口表补齐，并标注权限与风控前置条件
- [7] 完整性：缺权限点注册表，前后端权限码无权威口径（前端曾出现 `goods:*`） → 新增 2.2.3，统一 `模块:操作`，含字段级 `cost:read`/`shop:idcard:read`/`finance:export`，明确两层校验
- [8] 完整性：缺财务通用规范（金额精度、审批留痕、导出管控、账户流水） → 新增 8.4，补充商家/平台资金账户余额与流水表要求
- [9] 一致性：Token 存储口径矛盾（后端写"Refresh Token 存 Redis"、前端写 httpOnly Cookie） → 2.1.2 重写为"客户端载体 + 服务端存储"双列，补刷新接口、旋转、并发刷新、单端登录语义
- [10] 一致性：订单状态机三处打架（DDL 7 值枚举、4.3 图含 after_sale、4.4.1 代码另一套且终态 NPE） → 4.3 重画 8 状态图 + 流转白名单；DDL 注释同步；4.4.1 代码修正（getOrDefault、幂等、终态拦截）；明确 cancelled/closed 语义区分
- [11] 一致性：商品审核三套状态枚举（SPU status/audit_status、audit_task.status、前端 draft/auditing/on_shelf/rejected） → DDL 改为 8 值对齐；3.3.2 增加三表映射表与"复审仅为 auditing 内部节点"约定
- [12] 一致性：数据权限五档 vs 前端"商家仅本店铺"缺档位 → 2.3.1 增加第 6 档「本店铺」；`admin_role.data_scope` 注释同步；2.2.2 补防提权约束
- [13] 一致性：拆单实现与前端 5.4 契约冲突（每个子单都扣全额优惠、漏改 order_id、引入不存在的 SPLIT 状态、无幂等） → 4.4.2 重写：按比例分摊 + 尾差归集、父子单号可追溯、幂等唯一键、拆单时机改为支付后；补合单规则（重算差异阈值人工确认）
- [14] 一致性：导出链接公开直链、无时效/水印/权限校验（前端 4.1、9.6 要求 24h 有效 + 登录态） → 4.4.3 改为私有 Bucket + 临时签名 URL + 归属校验 + 操作人水印 + 敏感列脱敏后写文件
- [15] 一致性：提现审批只有单级（前端 9.4 要求初审→复审→打款，且初复审不得同一人） → 8.2.3、`admin_withdraw` DDL 改为两级审批状态机并补风控标记
- [16] 合理性：`admin-data`(Node.js) 消费 MQ 出报表与"禁止跨语言直连数据库"矛盾 → 1.1.1 补例外口径（只允许 MQ + 服务查询 API，结果写自有库），并给出 P1 的 Java 离线查询替代路径
- [17] 合理性：`allkeys-lru` 淘汰会导致分布式锁/幂等键/Token 黑名单丢失（资金级风险） → 1.3.1 增加"缓存实例 + 数据实例(noeviction)"隔离；docker-compose 补 `redis-data`；1.3.2 永久键指向数据实例
- [18] 合理性：价格引擎重复定义（1.1.3 声称复用 core-price，7.3 又在 admin-marketing 自建一套） → 7.3 明确唯一实现为 core-price，管理端只做配置与试算；补金额精度、负价兜底、试算与实付一致性校验
- [19] 合理性：分库分表策略与 DDL 互相矛盾（写 Snowflake 却全表 AUTO_INCREMENT；user_id 分库与 store_id/order_no 查询冲突；跨片 JOIN） → 1.2.1 增加 5 条落地约束（ID 策略、查询路径走 ES、明细同片、初期单库逻辑分表、全局唯一编码）
- [20] 合理性：部署缺 Nacos，但 OpenFeign/Seata/Sentinel 配置均依赖 Nacos → 1.1.2 补服务发现与超时/重试/降级规范；docker-compose 补 nacos 服务；修正 Go 网关误用 `SPRING_PROFILES_ACTIVE`
- [21] 合理性：分布式事务三方案并存无优先级（Seata AT/TCC/Saga/本地消息表全都"要上"） → 13.1 增加落地优先级：默认本地消息表+对账，Seata AT 少数链路，TCC 仅保留接口约定，2PC 不作业务方案
- [22] 合理性：缓存一致性"延迟双删 + Canal + 定时对账"三套机制无分工 → 1.3.3 增加分工表，明确 Canal 仅 P1 对热点只读数据启用
- [23] 合理性：数据看板声称 Flink/Spark 实时计算但正文无实现（v1.0 记录虚述） → 17.4 给出正式口径：P0 用 MQ+Redis 增量计数，离线走 T+1 预聚合，Flink 仅在必要时引入
- [24] 可实施性：商品批量操作 `@Async` 返回非 Future 值（调用方拿不到结果）、500 既当上限又当分批大小 → 3.3.3 重写为"提交返回 taskId + 结果查询"，明确接口上限 500 / 内部分批 200 / 全局并发信号量
- [25] 可实施性：库存预扣 Redis key 从未初始化（DECR 直接负数→永远库存不足）、key 不含 warehouse_id、Redis 与 DB 无补偿 → 5.3.1 重写：DB 为账本 + Redis 闸门懒加载回填、两段式锁库/出库/解锁、`uk_biz_no_type` 幂等防线
- [26] 可实施性：导出"拿锁后立即释放"防不住重复提交、任务号用 `currentTimeMillis` 会碰撞、消费端无幂等、临时文件不清理 → 4.4.3 重写：幂等 Key + 进行中任务数限制 + 雪花任务号 + 状态机条件更新 + finally 清理临时文件
- [27] 可实施性：数据权限切面代码不可运行（`IN (?)` 无法展开列表、本部门误写成 created_by） → 2.3.2 重写为占位符展开 + 拦截器绑定参数，并补分页 count、点查防 IDOR、档位变更后失效缓存等 4 条实施要求
- [28] 边界情况：MQ 缺交换机类型、死信队列、毒消息处理；缺 `order.cancelled` 等关键事件 → 1.4.1 补 topic 交换机约定、DLX 拓扑、三次重试后进 DLQ + 人工重投；补 4 个缺失事件（取消释放库存、售后申请、审核退回、店铺审核）
- [29] 边界情况：价格计算缺舍入与"优惠不得超过原价"兜底，退款无幂等 → 7.3、5.3.1、4.4.3 分别补 HALF_UP、floor 到 0、`bizNo` 幂等判断
- [30] 安全：登录暴力破解仅有表字段无策略、刷新接口缺旋转机制 → 2.5.1 补 3 次验证码/5 次锁定 15 分钟/锁定写 Redis；2.1.2 补 Refresh Token 旋转与二次使用判失效
- [31] 安全：敏感字段（成本价、证照）只写"加密存储"无读取控制 → 2.2.3 补字段级权限码，明确"后端脱敏为主、前端隐藏为辅"，证照查看留痕告警（对齐前端 2.2、13.2）
- [32] 性能：在线 API 直接对大表 group by 出报表、无预聚合约定 → 17.4 补预聚合表/物化视图要求，大盘查询禁止裸聚合
- [33] 完整性：系统参数无变更历史（前端 12.3 要求配置版本回滚） → 新增 `admin_config_history` 表，2.5.2 配置接口补回滚能力
- [34] 完整性：佣金计算调用 `item.getCategoryId()` 但订单商品表无该字段 → `admin_order_item` 补 `category_id` 冗余字段；8.3.1 补计提时点与退款冲抵、规则命中优先级

## 跨文件同步记录

### v1.1 → v1.2

- [S1] `android-backend.md`（4.3.4 状态机映射 + 文末审查记录遗留项 L1） → 订单状态机补齐：复核确认 `COMPLETED→REFUNDING` 已存在，**新增 `REFUNDED→CLOSED` 归档边**与 `TERMINAL_STATES`（`CANCELLED`/`CLOSED`）**两个终态显式声明**，并同步 4.3 白名单与状态图，保证 8 值状态机（`0-unpaid/1-paid/2-shipped/3-completed/4-refunding/5-refunded/6-cancelled/7-closed`，即待付款/待发货/待收货/已完成/退款中/已退款/已取消/已关闭）在 DDL、4.3 图、4.3 白名单、4.4.1 代码、买家端 4.3.4 五处逐值一致 → 修改位置：4.3 状态图（`refunded` 标注归档边 + 图后同步说明）、4.3 流转白名单（拆出 `refunded→closed` 行、终态行改为 `cancelled、closed`、规则补充第 5 条）、4.4.1 `TRANSITIONS` 与 `TERMINAL_STATES` 常量、`transition()` 终态拦截、使用约束 v1.2 条目
- [S2] `ios-backend.md`（12.1.2 `user.deleted` 跨端待办、13.5 上线 Checklist、审查记录 [19]） → 登记 `user.deleted` MQ 事件：交换机 `admin.member.exchange`、队列 `user.deleted.queue`，**生产者=买家端账户注销（冷静期结束广播），消费者=`core-user`（会员主数据/三方绑定删除）与 `admin-data`（画像/标签/看板个人数据清理，法定留存交易与发票除外）**；并补合规消费要求（30 分钟内消费完成、重试 3 次失败单独告警转人工、删除回执写审计表作为 PIPL 举证、纳入 16.1 上线验收）→ 修改位置：1.4.1 队列设计表新增 1 行、1.4.1 交换机说明块新增「合规事件要求（v1.2）」段、16.1 灰度发布新增上线验收项
- [S3] `android-backend.md`（1.4.1 `admin_order.pay_type` 注释、审查记录 [3]） → 支付方式字典补齐 `4=余额支付`（买家端扩展），管理端 DDL 注释、数据字典下发与订单查询/导出口径同步该枚举 → 修改位置：1.2.2 `admin_order.pay_type` 注释（`1-支付宝,2-微信,3-银行卡(银联),4-余额`）、2.5.2 数据字典接口说明（字典必须含 `4-余额`）、4.2.1 订单列表筛选参数说明（`payType` 取值 + 禁止硬编码枚举）
- [S4] `miniprogram-backend.md`（7.4 拼团、7.5 砍价三表 activity/record/help_record、文末跨端待办第 2 条） → 拼团活动表补 `spu_id`、`expire_minutes`（对应 `group_buying.expire_time`）、`limit_per_user`、`auto_success`（虚拟成团）并标注 `id` 即买家端 `activityId`；**新增砍价活动配置表 `admin_bargain_activity`**（与 `bargain_activity` 字段逐值一致：`activity_id/spu_id/sku_id/original_price/target_price/min_cut_amount/help_limit/daily_create_limit/start_time/end_time/status`），`bargain_record`、`bargain_help_record` 由买家端写入、管理端只读；营销服务补砍价功能点与 7.2.5 砍价接口（活动 CRUD + 砍价单/帮砍记录只读查询），7.2.4 补拼团上下线/详情/拼团记录接口与字段对齐说明 → 修改位置：1.2.2 营销模块 DDL（`admin_group_buy` 扩字段、新增 `admin_bargain_activity`）、7.1 功能点、7.2.4 拼团接口、新增 7.2.5 砍价接口

## 跨架构裁决回写记录

### v1.2 → v1.3

- [R1] 裁决C5/C6 → 11.4.1 新增执行器口径说明：**网关侧限流执行器为 APISIX/Higress 原生插件（本地令牌桶 + Redis 集群限流），规则与 Sentinel 同源 Nacos，执行器分家**（Sentinel 官方无 Go SDK，只管 Java 服务内熔断/自适应限流/集群流控）；原代码块注释由"# Sentinel配置"改为"Java 服务侧规则消费示例（网关插件读取同源 Nacos 规则）"，并明确禁止在网关进程内嵌 Sentinel Agent
- [R2] 裁决C5/C12 → 1.1.2 网关图更新（"Kong / Go自研" → "Higress/APISIX，能力对齐 Kong"）并新增「网关定名与选型」段：**买家端网关定名 `edge-gateway`**（架构 01 号新增命名，`buyer-gateway` 等旧称作废）；`admin-gateway` 通用网关职责下沉到网关插件 + BFF，**不再自研通用网关**，统一选型 Higress/APISIX；1.1.1 服务表与文首"Go：admin-gateway（高并发网关）"标注为 P0/P1 过渡口径（就地说明，未改表行）
- [R3] 裁决C13 + 02 §7 待回写清单 → ① 1.2.1 约束 2：`order_no` 改 **24 位定长编码（时间 14 + 槽位 4 + 序列 6，槽位可反解分区/单元）**，原"yyyyMMddHHmmss + 雪花尾段"写法作废（不带分片信息，无法满足商家按 order_no 查单）；② 1.2.1 分片策略图与说明、1.2.2 SPU 表注释：商品域分片键补 `spu_id`（原 `category_id` 口径作废，类目查询走 ES）；③ 1.2.1 约束 1 强化 + 1.2.2 文首新增主键/分片口径说明：全表 `AUTO_INCREMENT` 主键写法作废，分片表一律 `BIGINT NOT NULL` + 雪花算法（分库分表禁止自增），DDL 批量修订列为上线前必改项；④ 12.3：原 `selectBeforeDate`/`deleteBeforeDate` 全表扫描实现作废（100 亿行必崩），改为 **OceanBase 分区归档 + 大对象外置 OSS，按水位/分区批量迁移**（水位表 + 5000 行分批 + 三元校验 + `EXCHANGE PARTITION` 后 DROP），12.3.1 存储口径 HBase → OB 历史库 + OSS Parquet
- [R4] 裁决C2 → 1.4.1 新增「MQ 演进」注：P0/P1 沿用 RabbitMQ 现设计；**P2 阶段一次性迁移 RocketMQ 5.x**，队列/交换机映射为 Topic/ConsumerGroup（事件名不改、交换机名降级为 Tag），30 个事件清单与顺序/事务消息约束见 `architecture/04-messaging.md` §7
