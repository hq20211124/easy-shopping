# PC客户端后端开发计划

> 项目：大型电商平台 PC 客户端后端  
> 技术栈：Java Spring Boot（核心服务） + Go（高性能网关/推送） + MySQL + Redis + Elasticsearch + RabbitMQ  
> 文档版本：v1.2  
> 更新日期：2026-10-11

---

## 一、架构与基础设施

### 1.1 与浏览器端共享的服务

PC客户端后端与浏览器端共享核心业务服务，通过统一的 API Gateway 进行路由和协议适配。

| 共享服务 | 职责 | 协议 |
|---------|------|------|
| 商品中心 | 商品信息、类目、品牌、库存、商品评价 | REST/gRPC |
| 订单中心 | 下单、支付、退款、订单状态、发票 | REST/gRPC |
| 会员中心 | 用户信息、地址、收藏、积分、用户等级 | REST/gRPC |
| 搜索服务 | 商品搜索、筛选、排序 | REST |
| 推荐服务 | 个性化推荐、猜你喜欢 | REST/gRPC |
| 营销服务 | 优惠券、促销、秒杀 | REST/gRPC |
| 支付服务 | 支付渠道、对账、退款 | REST/gRPC |
| 物流服务 | 物流跟踪、运费计算 | REST/gRPC |
| 购物车服务 | 购物车管理、数量校验、价格计算 | REST/gRPC |
| 内容服务 | Banner、活动入口、公告、专题（首页数据源） | REST/gRPC |
| 营销服务 | 优惠券领取/可用/核销、满减、秒杀、拼团 | REST/gRPC |
| 售后服务 | 仅退款/退货退款/换货全流程 | REST/gRPC |
| 客服服务 | 在线客服会话、工单系统（管理端 admin-cs 对接） | REST/gRPC + WebSocket |
| 消息通知服务 | 站内信、系统通知、未读数（管理端 admin-message 对接） | REST/gRPC + MQ |
| 文件服务 | 头像/评价图/客服附件上传，OSS 直传凭证 | REST |
| 风控服务 | 登录/下单/支付/营销风控，设备指纹校验 | gRPC |

**架构原则：**
- 核心服务无状态化，支持水平扩展
- 通过 API Gateway 统一鉴权、限流、日志
- 浏览器端与桌面端共享同一套核心服务，桌面端通过 JWT claim `client_type=pc` 区分；`client_type` 仅写入 token 与请求上下文/日志/风控表，**不在核心业务表增加列**（避免为客户端类型污染共享模型，需要按端统计时走埋点与日志）
- 服务间通信用 gRPC（内部），对外暴露 RESTful API
- 共享服务不包含桌面端特有逻辑（推送通道、增量更新、数据同步），确保浏览器端与桌面端核心业务逻辑完全复用

### 1.2 桌面端特有服务

| 服务 | 职责 | 技术选型 |
|------|------|---------|
| 推送服务 | 桌面通知、系统托盘消息、离线推送 | WebSocket + RabbitMQ |
| 更新服务 | 版本管理、增量更新、灰度发布、回滚机制 | REST + 文件服务 |
| 数据同步服务 | 本地缓存同步、离线数据上传、全量/增量同步 | REST + 冲突解决 |
| 设备管理服务 | 设备注册、绑定、多端同步 | REST |
| 崩溃上报服务 | 客户端崩溃日志收集、聚合分析、符号化解析 | REST + 对象存储 |
| 配置下发服务 | 客户端动态配置、功能开关、AB实验 | REST + 本地缓存 |

> **边界说明**：客服服务与消息/通知服务属于**买家端共享服务**（浏览器端同样需要在线客服与站内信），不属于桌面端特有服务，见 1.1 节共享服务表；桌面端只是在共享客服服务基础上增加了托盘集成与系统通知能力。

### 1.2.1 共享服务边界补充（与 admin-backend.md 对齐）

除 1.1 节列出的共享服务外，PC 客户端与浏览器端还共同依赖以下服务，**不应在本端重复实现**：

| 共享服务 | 对应 admin-backend 服务 | 本端使用方式 | 说明 |
|---------|----------------------|------------|------|
| 内容服务 | `admin-content` | 调用 | 首页 Banner、活动入口、公告、专题页数据源，见前端 `/api/activities` |
| 文件服务 | `admin-file` | 调用（直传凭证） | 头像、评价图片、客服附件、崩溃日志统一走 OSS/S3 直传，本端只签发上传凭证并做内容安全审核 |
| 营销/优惠券服务 | `admin-marketing` | 调用 | 优惠券领取/可用/核销、满减、秒杀、拼团，本端不得自建优惠计算 |
| 售后服务 | `admin-order`（售后模块） | 调用 | 仅退款/退货退款/换货全流程，见 4.6 节 |
| 区域服务 | `admin-system` | 调用（只读） | 省市区数据（`/api/v1/regions`），本地缓存一年 |
| 风控服务 | 独立 `core-risk` | 调用 | 登录风控、下单风控、支付风控、营销防刷（羊毛党识别） |

**架构原则补充：**
- 价格计算统一调用 `core-price` 价格引擎（与 `admin-marketing` 共享），本端下单只做参数校验与编排，**禁止在客户端或 BFF 层实现优惠叠加/试算逻辑**，避免买卖两端算价不一致
- 库存扣减统一走共享库存服务（`core-inventory`），复用 admin-backend 5.3.1 的「Redis 预扣 + DB 乐观锁 + 库存流水 + MQ 事件」方案；本端不得另写一套库存扣减
- 管理端变更（上/下架、改价、库存变动、订单状态）通过 RabbitMQ 事件同步，本端消费后更新本地缓存与 ES 索引，采用最终一致性 + 定时对账（每 5 分钟），见 admin-backend 14.1
- 数据库表结构变更使用 Flyway 管理，支持版本回退（与 admin-backend 16.2 对齐）
- 订单表分片策略与 admin-backend 1.2.1 对齐：按 `user_id` 分库 + 按月份分表；`order_no` 查询必须携带 `user_id` 作为分片键

### 1.3 数据库设计

#### 1.3.1 核心表结构（共享）

```sql
-- 用户表
CREATE TABLE `user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL,
  `password_hash` VARCHAR(128) NOT NULL COMMENT 'BCrypt加密存储',
  `email` VARCHAR(128),
  `phone` VARCHAR(20),
  `avatar_url` VARCHAR(512),
  `nickname` VARCHAR(64),
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 商品表（SPU维度）
CREATE TABLE `product` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'SPU ID',
  `title` VARCHAR(256) NOT NULL,
  `description` TEXT,
  `category_id` BIGINT UNSIGNED NOT NULL,
  `brand_id` BIGINT UNSIGNED,
  `main_image` VARCHAR(512),
  `images` JSON,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_brand` (`brand_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- SKU表（规格维度）
CREATE TABLE `product_sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `spu_id` BIGINT UNSIGNED NOT NULL COMMENT '关联product.id',
  `sku_code` VARCHAR(64) NOT NULL,
  `spec_json` JSON NOT NULL COMMENT '规格组合，如{"颜色":"黑色","尺寸":"XL"}',
  `price` DECIMAL(10,2) NOT NULL,
  `original_price` DECIMAL(10,2),
  `stock` INT NOT NULL DEFAULT 0,
  `sales_count` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_code` (`sku_code`),
  KEY `idx_spu` (`spu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单表
CREATE TABLE `order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no` VARCHAR(32) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `shop_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '所属店铺',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '商品总金额',
  `discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '优惠金额',
  `freight_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '运费',
  `pay_amount` DECIMAL(10,2) NOT NULL COMMENT '实付金额',
  `status` TINYINT NOT NULL DEFAULT 0,
  `pay_type` TINYINT,
  `pay_time` DATETIME,
  `pay_deadline` DATETIME DEFAULT NULL COMMENT '付款截止时间(超时自动关单的唯一事实来源,延迟消息deliveryTimestamp直取此字段;时长待产品联调前书面确认,见4.1,对齐admin-backend pay_deadline)',
  `delivery_address` JSON NOT NULL,
  `logistics_no` VARCHAR(64) COMMENT '物流单号',
  `logistics_company` VARCHAR(32) COMMENT '物流公司',
  `remark` VARCHAR(512),
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_pay_deadline` (`status`, `pay_deadline`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单商品表
CREATE TABLE `order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL COMMENT 'SPU ID',
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `title` VARCHAR(256) NOT NULL,
  `price` DECIMAL(10,2) NOT NULL,
  `quantity` INT NOT NULL,
  `total_price` DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 退款表
CREATE TABLE `refund` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `refund_no` VARCHAR(32) NOT NULL,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `order_no` VARCHAR(32) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `amount` DECIMAL(10,2) NOT NULL,
  `reason` VARCHAR(256),
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-申请中 1-已退款 2-已拒绝',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  KEY `idx_order` (`order_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 商品评价表
CREATE TABLE `product_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `rating` TINYINT NOT NULL COMMENT '1-5星',
  `content` TEXT,
  `images` JSON,
  `tags` JSON,
  `is_anonymous` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 收藏表
CREATE TABLE `user_favorite` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 购物车表
CREATE TABLE `user_cart` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `quantity` INT NOT NULL DEFAULT 1,
  `is_selected` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_sku` (`user_id`, `sku_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 积分明细表
CREATE TABLE `points_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `points` INT NOT NULL COMMENT '正数-获得，负数-消费',
  `type` VARCHAR(32) NOT NULL COMMENT 'consume/signin/review/exchange',
  `description` VARCHAR(256),
  `related_id` BIGINT UNSIGNED COMMENT '关联订单ID等',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 发票表
CREATE TABLE `invoice` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `invoice_no` VARCHAR(32) NOT NULL,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `type` VARCHAR(16) NOT NULL COMMENT 'personal/company',
  `title` VARCHAR(128) NOT NULL,
  `tax_no` VARCHAR(32),
  `amount` DECIMAL(10,2) NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invoice_no` (`invoice_no`),
  KEY `idx_order` (`order_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 1.3.2 桌面端特有表

```sql
-- 设备表
CREATE TABLE `device` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `device_id` VARCHAR(64) NOT NULL,
  `device_name` VARCHAR(128),
  `device_type` VARCHAR(32) NOT NULL DEFAULT 'pc',
  `os_type` VARCHAR(32) NOT NULL,
  `os_version` VARCHAR(32),
  `client_version` VARCHAR(32) NOT NULL,
  `push_token` VARCHAR(256),
  `last_active_at` DATETIME,
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_id` (`device_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 客户端版本表
CREATE TABLE `client_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `version` VARCHAR(32) NOT NULL,
  `version_code` INT NOT NULL,
  `os_type` VARCHAR(32) NOT NULL,
  `os_arch` VARCHAR(16) NOT NULL DEFAULT 'x64',
  `channel` VARCHAR(16) NOT NULL DEFAULT 'stable' COMMENT 'stable/beta/alpha',
  `download_url` VARCHAR(512) NOT NULL,
  `file_size` BIGINT NOT NULL,
  `file_hash` VARCHAR(64) NOT NULL,
  `sign_status` TINYINT NOT NULL DEFAULT 1 COMMENT '1-已签名 0-待签名',
  `is_force_update` TINYINT NOT NULL DEFAULT 0,
  `is_blacklist` TINYINT NOT NULL DEFAULT 0 COMMENT '版本黑名单，命中则跳过本版本',
  `update_log` TEXT,
  `gray_scale` TINYINT NOT NULL DEFAULT 0,
  `gray_config` JSON,
  `status` TINYINT NOT NULL DEFAULT 1,
  `published_at` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version_os_arch_channel` (`version`, `os_type`, `os_arch`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 数据同步记录表（游标按「用户 × 数据类型」维护；设备维度仅用于设备级私有数据，如本机浏览历史）
CREATE TABLE `sync_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `device_id` VARCHAR(64) NOT NULL,
  `data_type` VARCHAR(32) NOT NULL,
  `sync_type` TINYINT NOT NULL,
  `last_sync_time` DATETIME NOT NULL,
  `sync_data_hash` VARCHAR(64),
  `conflict_count` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device_type` (`user_id`, `device_id`, `data_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 离线队列表（已废弃：与 9.2.2 offline_data 表职责重复，v1.2 起统一使用 offline_data，
-- 本表保留仅为兼容历史实现，新功能禁止使用；见 9.2 节说明）
CREATE TABLE `offline_queue` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `device_id` VARCHAR(64) NOT NULL,
  `operation_type` VARCHAR(32) NOT NULL,
  `operation_data` JSON NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0,
  `retry_count` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `processed_at` DATETIME,
  PRIMARY KEY (`id`),
  KEY `idx_user_device` (`user_id`, `device_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 系统通知表
CREATE TABLE `notification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `type` VARCHAR(32) NOT NULL COMMENT 'order/logistics/coupon/system/activity',
  `title` VARCHAR(128) NOT NULL,
  `content` TEXT,
  `related_id` BIGINT UNSIGNED COMMENT '关联业务ID',
  `is_read` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 崩溃日志表
CREATE TABLE `crash_report` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `device_id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT UNSIGNED,
  `os_type` VARCHAR(32) NOT NULL,
  `os_version` VARCHAR(32),
  `client_version` VARCHAR(32) NOT NULL,
  `crash_hash` VARCHAR(64) NOT NULL COMMENT '崩溃堆栈哈希',
  `crash_log` MEDIUMTEXT NOT NULL COMMENT '已脱敏，不含token/手机号/地址',
  `stack_trace` TEXT,
  `count` INT NOT NULL DEFAULT 1,
  `first_time` DATETIME NOT NULL,
  `last_time` DATETIME NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-未处理 1-已确认 2-已修复 3-已忽略',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_crash_agg` (`crash_hash`, `client_version`, `os_type`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 客户端配置表
CREATE TABLE `client_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(64) NOT NULL,
  `config_value` JSON NOT NULL,
  `config_type` VARCHAR(32) NOT NULL DEFAULT 'json',
  `description` VARCHAR(256),
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 1.4 缓存策略

| 数据类型 | 缓存层 | 过期策略 | 说明 |
|---------|--------|---------|------|
| 用户会话 | Redis | 2小时滑动过期 | Token黑名单机制 |
| 商品详情 | Redis + 本地缓存 | 10分钟 | 热点数据预加载，布隆过滤器防穿透 |
| 商品列表 | Redis | 5分钟 | 分页缓存，空值缓存30秒防穿透 |
| 搜索结果 | Redis | 3分钟 | 搜索词缓存 |
| 库存数据 | Redis | 实时 | 库存扣减用Lua保证原子性，预扣+异步落库 |
| 配置数据 | Redis + 本地缓存 | 30分钟 | 灰度配置、开关，推送更新 |
| 推荐结果 | Redis | 15分钟 | 用户维度缓存 |

**缓存更新策略：**
- 商品信息：Cache-Aside + 消息队列异步更新
- 库存数据：Write-Through + Redis Lua原子操作
- 配置数据：定时拉取 + 推送更新

**缓存防护策略：**
- **缓存穿透**：布隆过滤器拦截不存在的商品ID，空结果缓存30秒
- **缓存击穿**：热点Key使用Redis分布式锁，只允许一个请求回源
- **缓存雪崩**：过期时间增加随机偏移（基础时间 ± 10%），避免同时失效

---

## 二、认证与授权

### 2.1 桌面端登录

PC客户端支持多种登录方式，针对不同场景提供最佳体验。

#### 2.1.1 登录方式

| 登录方式 | 场景 | 流程 |
|---------|------|------|
| 账号密码登录 | 首次登录、安全验证 | 用户名+密码+图形验证码 |
| 手机验证码登录 | 快速登录 | 手机号+短信验证码（60秒间隔，24小时最多10条） |
| 扫码登录 | 已登录手机APP用户 | 手机端确认授权 |
| 第三方登录 | 快捷登录 | 微信/QQ/微博 OAuth |
| 自动登录 | 已记住登录态 | Refresh Token 续期 |

#### 2.1.2 登录接口

```
POST /api/v1/auth/login
```

> **账号标识说明**：`username` 字段同时接受「用户名 / 手机号 / 邮箱」三种标识，服务端自动识别；与前端「手机号+密码」的交互保持一致，避免前后端登录标识不统一。

**请求参数：**
```json
{
  "login_type": "password",
  "username": "user@example.com",
  "password": "RSA加密后的密码",
  "captcha_id": "uuid",
  "captcha_code": "a3f8",
  "device_info": {
    "device_id": "dev_abc123",
    "device_name": "Windows-PC",
    "os_type": "windows",
    "os_version": "10.0.19041",
    "client_version": "3.2.1"
  }
}
```

> **密码传输加密**：登录前调用 `GET /api/v1/auth/public-key` 获取服务端 RSA 公钥（带 `key_id` 与过期时间），前端用 RSA-OAEP 加密密码；公钥轮换期间旧公钥保留 24 小时可用。

**响应：**
```json
{
  "code": 0,
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIs...",
    "refresh_token": "dGhpcyBpcyBh...",
    "expires_in": 7200,
    "token_type": "Bearer",
    "user": {
      "id": 10001,
      "username": "user",
      "avatar_url": "https://...",
      "nickname": "用户昵称"
    },
    "device_bound": true,
    "require_verify": false
  }
}
```

> 当检测到新设备登录时，`require_verify=true`，需二次验证（短信/邮箱）完成后方可登录。

#### 2.1.3 其余登录方式接口

以下接口对应 2.1.1 登录方式表，前端 `pc-client-frontend.md` 已依赖，后端必须提供：

```
GET  /api/v1/auth/public-key              # 获取RSA公钥（含key_id、过期时间）
POST /api/v1/auth/sms-code                # 发送短信验证码（60s间隔、10条/天）
POST /api/v1/auth/login-sms              # 手机号+验证码登录
GET  /api/v1/auth/qrcode-token            # 生成扫码登录token（二维码有效期2分钟）
GET  /api/v1/auth/qrcode-status           # 轮询扫码状态（pending/scanned/confirmed/expired）
POST /api/v1/auth/qrcode/confirm          # 手机端确认授权（已登录APP调用）
POST /api/v1/auth/third-party             # 第三方登录（微信/支付宝/Apple）
POST /api/v1/auth/verify                  # 新设备二次验证（短信/邮箱验证码）
POST /api/v1/auth/switch-account          # 多账户切换（各账户refresh token独立存储互不踢出）
GET  /api/v1/accounts/recent              # 最近登录账户列表（仅返回脱敏信息）
POST /api/v1/auth/logout                  # 登出（服务端吊销当前设备token）
```

**扫码登录安全要求：**
- 二维码内容为一次性随机 `qrcode_token`，绑定生成时的 `device_id`（桌面端），扫码后由手机端确认，确认时校验手机端与二维码绑定设备不同
- `qrcode_token` 单次有效、2 分钟过期，确认后立即失效，防止二维码被截获重放
- 扫码登录成功后签发的 token 与桌面端 `device_id` 绑定，计入设备上限

**登录风控：**
- 单账号连续 5 次密码错误锁定 30 分钟；单 IP 5 次/分钟触发验证码升级（图形 → 短信）
- 异地/异常设备登录触发 `require_verify`，并调用风控服务评估风险
- 短信验证码 60 秒间隔、24 小时 10 条/手机号，且同一 IP 每小时上限 20 条

### 2.2 Token管理

#### 2.2.1 Token策略

| Token类型 | 有效期 | 用途 | 存储位置 |
|-----------|--------|------|---------|
| Access Token | 2小时 | 接口认证 | 内存 + DPAPI/Keychain加密存储 |
| Refresh Token | 30天 | 续期Access Token | 系统凭证管理器/DPAPI加密 |
| Device Token | 90天（滑动续期） | 设备标识与推送凭证 | 本地安全存储 |

> **安全说明**：Device Token 不设为永久有效——永久凭证一旦泄露无法自动失效，构成持续风险；采用 90 天滑动续期，每次启动/联网时静默续期，过期后需重新注册设备。

#### 2.2.2 Token续期

```
POST /api/v1/auth/refresh
```

**请求参数：**
```json
{
  "refresh_token": "dGhpcyBpcyBh...",
  "device_id": "dev_abc123"
}
```

**响应：**
```json
{
  "code": 0,
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIs...",
    "refresh_token": "dGhpcyBpcyBh...",
    "expires_in": 7200
  }
}
```

#### 2.2.3 Token安全

- Access Token 使用 JWT 格式，包含 user_id、device_id、client_type，使用 RS256 签名
- Refresh Token 使用随机字符串，服务端存储 SHA-256 哈希值
- 支持 Token 黑名单（用户登出、设备解绑、异常检测）
- 同一设备 Refresh Token 轮换机制（每次续期生成新 Refresh Token，旧 Token 设置 **60 秒宽限期** 后失效，避免客户端并发刷新时合法请求被误判为失效）
- Refresh Token 复用检测：若检测到已失效的旧 Token 被再次使用，判定为 Token 泄露，立即吊销该用户在该设备下的整条 Token 家族并强制重新登录
- 多账户切换场景下，各账户的 Refresh Token 按 `(user_id, device_id)` 独立存储与轮换，切换账户不吊销其他账户的 Token
- 客户端本地存储使用 Windows DPAPI / macOS Keychain 加密，不使用明文存储
- 所有 Token 通过 HTTPS 传输，禁止 URL 参数传递

### 2.3 设备绑定

#### 2.3.1 设备绑定流程

```
1. 客户端生成唯一 device_id（基于硬件指纹：CPU+主板+MAC地址哈希，加盐后仅上传哈希值，不上传原始硬件信息）
2. 登录时携带 device_info
3. 服务端验证设备合法性（检查设备指纹签名，密钥由服务端下发并定期轮换）
4. 绑定 user_id 与 device_id
5. 返回设备绑定状态
6. 若为新设备且已有设备在线 → 发送二次验证通知
```

> **合规说明**：硬件指纹属于个人信息，需在隐私政策中明示用途并取得用户同意；提供「重置设备标识」入口；更换网卡/主板导致指纹变化时，走「重新绑定」而非直接判为异常设备。

#### 2.3.2 设备管理接口

```
GET    /api/v1/devices                    # 获取用户设备列表
POST   /api/v1/devices/bind               # 绑定设备
DELETE /api/v1/devices/{device_id}        # 解绑设备
PUT    /api/v1/devices/{device_id}/name   # 修改设备名称
GET    /api/v1/devices/{device_id}/sync   # 获取设备同步状态
```

#### 2.3.3 设备限制

| 限制项 | 值 | 说明 |
|--------|-----|------|
| 最大设备数 | 5台 | 同一用户最多绑定5台设备 |
| 登录互踢 | 可配置 | 同类型设备是否互踢 |
| 异常检测 | 开启 | 异地登录提醒+二次验证 |
| 设备指纹签名 | 必选 | 防止 device_id 伪造 |

#### 2.3.4 API安全

- 所有非登录接口需携带 Access Token + 请求签名
- 请求签名算法：HMAC-SHA256(device_id + timestamp + body_hash)，防重放攻击
- 时间戳偏差超过5分钟的请求直接拒绝
- 所有API接口限流：单设备 100次/分钟，单IP 1000次/分钟

**免鉴权白名单**（与「所有接口需鉴权」的默认规则相冲突，以下接口必须放行，否则未登录浏览/自动更新场景会失败）：

| 接口 | 说明 |
|------|------|
| `/api/v1/auth/*` | 登录、验证码、公钥获取 |
| 商品/搜索/推荐的读接口 | 未登录可浏览，仅做 IP/设备限流 |
| `/api/v1/client/version/check` | 自动更新检查（应用启动时可能未登录） |
| `/api/v1/crash/upload` | 崩溃上报（崩溃时可能无登录态），按 device_id 限流并脱敏 |
| `/api/v1/pay/callback/*` | 支付回调（渠道方调用，验签而非 Token 鉴权） |

**单IP限流补充**：NAT/企业出口下大量用户共享同一 IP，单 IP 阈值需支持按「IP + 设备指纹」二次放行，避免误伤正常用户。

---

## 三、商品服务

### 3.1 商品列表

#### 3.1.1 接口设计

```
GET /api/v1/products                    # 商品列表（分页）
GET /api/v1/products/category/{id}      # 按类目获取
GET /api/v1/products/brand/{id}         # 按品牌获取
GET /api/v1/products/hot                # 热销商品
GET /api/v1/products/new                # 新品上架
GET /api/v1/products/recommend          # 推荐商品
GET /api/v1/products/filters            # 类目筛选条件聚合（价格区间/品牌/属性）
GET /api/v1/products/stock              # 批量库存查询（sku_ids，用于购物车/结算页）
GET /api/v1/products/{id}/skus          # SKU列表（含规格、价格、库存、图片）
GET /api/v1/products/{id}/related       # 相关推荐（看了又看/买了还买）
```

#### 3.1.2 请求参数

```
GET /api/v1/products?page=1&size=20&sort=sales_desc&category_id=100&price_min=100&price_max=500&brand_ids=1,2,3
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认1 |
| size | int | 否 | 每页数量，默认20，最大50 |
| sort | string | 否 | 排序方式 |
| category_id | long | 否 | 类目ID |
| price_min | decimal | 否 | 最低价格 |
| price_max | decimal | 否 | 最高价格 |
| brand_ids | string | 否 | 品牌ID列表 |
| keyword | string | 否 | 搜索关键词 |

#### 3.1.3 响应结构

```json
{
  "code": 0,
  "data": {
    "total": 10000,
    "page": 1,
    "size": 20,
    "items": [
      {
        "product_id": 10001,
        "title": "Apple iPhone 15 Pro",
        "price": 7999.00,
        "original_price": 8999.00,
        "main_image": "https://cdn.example.com/...",
        "sales_count": 50000,
        "rating": 4.9,
        "tags": ["新品", "包邮"],
        "stock_status": "in_stock"
      }
    ]
  }
}
```

### 3.2 商品详情

```
GET /api/v1/products/{id}
```

**响应包含：**
- 基本信息：标题、描述、图片、视频
- SKU信息：规格、价格、库存
- 物流信息：运费、发货地、预计到达
- 售后服务：退换货政策
- 推荐搭配：相关商品
- 用户评价：评分、标签、精选评价（支持分页）

### 3.3 商品评价

#### 3.3.1 评价接口

```
GET    /api/v1/products/{id}/reviews          # 评价列表
POST   /api/v1/products/{id}/reviews          # 发表评价（需购买）
DELETE /api/v1/reviews/{review_id}            # 删除评价
GET    /api/v1/products/{id}/reviews/summary  # 评价统计
```

#### 3.3.2 评价规则
- 仅已收货订单用户可评价
- 每个订单商品仅可评价一次
- 支持修改评价（7天内）

### 3.4 搜索

#### 3.4.1 搜索接口

```
GET /api/v1/search/products
```

**技术要点：**
- 使用 Elasticsearch 实现全文搜索
- 支持中文分词（IK分词器）
- 支持筛选、排序、聚合
- 搜索建议（Search Suggestion）
- 搜索历史记录（支持桌面端同步）

#### 3.4.2 搜索建议

```
GET /api/v1/search/suggest?keyword=iphone
```

**响应：**
```json
{
  "code": 0,
  "data": {
    "suggestions": ["iphone 15", "iphone 15 pro", "iphone 14"],
    "hot_keywords": ["iphone 15", "华为Mate60", "小米14"]
  }
}
```

#### 3.4.3 搜索历史与热搜

```
GET    /api/v1/search/history       # 搜索历史（最近50条）
POST   /api/v1/search/history       # 记录搜索历史（item_idempotency_key 幂等）
DELETE /api/v1/search/history       # 清空搜索历史
GET    /api/v1/search/hot           # 热搜榜
```

- 搜索历史属于「用户级共享数据」，多端同步（见 9.1 节），受用户隐私开关控制；用户可单独清空或关闭同步
- 深分页（page > 50）改用 `search_after` 游标分页，`page/size` 仅用于浅分页，避免 ES 大偏移性能塌陷

### 3.5 推荐

```
GET /api/v1/recommendations?scene=home&page=1&size=20
```

| 场景 | 说明 |
|------|------|
| home | 首页推荐 |
| cart | 购物车推荐 |
| product_detail | 详情页推荐 |
| order_complete | 下单完成推荐 |

**推荐策略：**
- 协同过滤（用户行为相似度）
- 内容推荐（商品属性相似度）
- 热门推荐（实时销量）
- 个性化推荐（深度学习模型，第一阶段先用规则+热门兜底）

---

## 四、订单服务

### 4.1 下单流程

#### 4.1.1 结算页聚合接口

```
GET /api/v1/checkout                    # 结算信息（商品、可用优惠券、运费、应付金额）
GET /api/v1/payment-methods             # 支付方式列表（按用户/渠道/地区动态下发）
GET /api/v1/regions                     # 省市区数据（只读，本地缓存一年）
```

> 结算页金额必须由服务端 `core-price` 价格引擎计算并返回，客户端仅展示，不得本地试算。

#### 4.1.2 创建订单

```
POST /api/v1/orders
```

> **幂等性保证**：客户端生成唯一 `request_id`，服务端基于 request_id 做幂等校验，5分钟内重复请求返回首次结果。

**请求参数：**
```json
{
  "request_id": "req_uuid_12345",
  "items": [
    {
      "sku_id": 10001,
      "quantity": 2,
      "shop_id": 100
    }
  ],
  "address_id": 10086,
  "coupon_id": 5001,
  "remark": "工作日送货",
  "invoice_info": {
    "type": "personal",
    "title": "个人"
  }
}
```

**下单流程：**
1. 参数校验（商品上架状态、库存、购买限制）
2. 库存预扣（调用共享库存服务 `core-inventory`：Redis 预扣 + DB 乐观锁 + 库存流水，不自建扣减逻辑）
3. 价格计算（调用 `core-price` 价格引擎：商品金额、优惠叠加、运费，服务端重新计算不信任客户端价格）
4. 创建订单（状态：待支付），事务保证订单+订单项一致性
5. 多店铺订单按 `shop_id` 拆单（复用 admin-backend 4.4.2 拆单逻辑），返回主订单号 + 子订单列表，子订单独立发货/售后/退款
6. 锁定库存与超时关单（**以订单表 `pay_deadline` 字段为唯一事实来源**：下单事务内写入付款截止时间，延迟队列（RocketMQ/RabbitMQ 延迟消息）的 `deliveryTimestamp` 直取 `pay_deadline`，到期自动关单并释放库存，PC/管理端/各买家端展示同一字段，本端不自定义分钟数；原文「15分钟」为待产品联调前书面确认的参数，仅作占位、不得硬编码）
7. 返回支付参数

> **幂等存储**：`request_id` 幂等记录写入 Redis（`SETNX` + 结果快照，TTL 24小时）与 `order_idempotent` 表双落地，避免 Redis 故障后重复下单。

### 4.2 支付

```
POST /api/v1/orders/{order_no}/pay
```

**请求参数：**
```json
{
  "pay_type": "alipay",
  "return_url": "https://...",
  "payment_password": "encrypted_password"
}
```

**支付方式：**
- 支付宝（PC扫码/手机支付）
- 微信支付（PC扫码）
- 银联支付
- 余额支付（需支付密码）

**支付安全：**
- 大额支付（>500元）需输入支付密码；支付密码独立加密存储（SM3/BCrypt 加盐哈希），与登录密码分离，5次错误锁定24小时
- 服务端记录支付风控日志
- 支付回调验签（RSA-SHA256），防伪造回调

**支付回调：**
```
POST /api/v1/pay/callback/{channel}
```

> **回调幂等**：以 `(channel, out_trade_no, transaction_id)` 为幂等键，重复回调直接返回成功应答，不重复更新订单状态/不重复加积分/不重复发通知。

> **回调与取消竞态**：订单已取消后收到支付成功回调，不得直接置为「已支付」——触发自动原路退款流程（调用共享退款服务），并告警人工复核，退款状态机见 4.6 节。

> **回调与超时关单竞态**：超时关单由延迟消息在 `pay_deadline` 到期触发，与支付成功回调并发时以订单 `version` 乐观锁裁决、只生效一方；已关单后收到成功回调同样走上述自动退款流程。

### 4.3 订单管理

```
GET    /api/v1/orders                    # 订单列表（支持 status 筛选，见状态枚举）
GET    /api/v1/orders/{order_no}         # 订单详情
POST   /api/v1/orders/{order_no}/cancel  # 取消订单
POST   /api/v1/orders/{order_no}/confirm # 确认收货
DELETE /api/v1/orders/{order_no}         # 删除订单（软删除）
POST   /api/v1/orders/{order_no}/refund  # 申请退款（仅退款场景，完整售后见 4.6）
```

> **标识符约定**：对外一律使用业务单号 `order_no`（退款用 `refund_no`，售后用 `after_sale_no`），不暴露自增主键，避免越权遍历；前端订单列表/详情接口的 `:id` 统一替换为 `order_no`。

**订单状态流转（与 admin-backend.md 4.3 状态机保持一致，统一枚举，避免买卖两端状态语义错位）：**
```
unpaid(待支付) → paid(待发货) → shipped(待收货) → completed(已完成)
     ↓              ↓               ↓
cancelled(已取消)  └──────→ after_sale(售后中) → refunded(已退款) / closed(已关闭)
```
- 状态变更使用乐观锁（`version` 字段）+ 状态流水表 `order_status_log`，非法流转直接拒绝
- 状态枚举全端统一：`unpaid / paid / shipped / completed / cancelled / after_sale / refunded / closed`
- 每次状态变更通过 MQ 发布 `OrderStatusChangedEvent`，驱动缓存刷新、推送通知、买家端状态同步

### 4.4 物流跟踪

```
GET /api/v1/orders/{order_no}/logistics
```

**响应：**
```json
{
  "code": 0,
  "data": {
    "logistics_no": "SF1234567890",
    "company": "顺丰速运",
    "status": "in_transit",
    "estimated_time": "2026-10-12",
    "traces": [
      {
        "time": "2026-10-11 10:00:00",
        "desc": "快件已到达北京转运中心",
        "location": "北京"
      }
    ]
  }
}
```

### 4.6 售后服务

> 售后能力由共享售后模块（admin-backend `admin-order` 售后域）提供，本端只做接口聚合与状态展示；前端 `after-sales` 页面依赖以下接口，缺一则退货退款/换货流程不可用。

```
GET  /api/v1/after-sales                      # 售后列表
POST /api/v1/after-sales                      # 申请售后（仅退款/退货退款/换货）
GET  /api/v1/after-sales/{after_sale_no}      # 售后详情
POST /api/v1/after-sales/{after_sale_no}/cancel  # 撤销售后
POST /api/v1/after-sales/{after_sale_no}/ship    # 回填退货物流单号
```

**售后状态机：**
```
apply(申请) → review(审核) → shipping(待寄回) → received(已收货) → refunding(退款中) → done(已完成)
                  ↓                                                  ↓
              rejected(已拒绝)                                    closed(已关闭/协商一致)
```

**售后规则：**
- 一个订单可按 `order_item` 维度分别申请售后（部分退款），退款金额累计不得超过实付金额
- 退款资金流复用共享退款服务与 admin-finance 对账，买家端不直接操作资金
- 售后状态变更同样发布 MQ 事件，驱动推送通知与订单状态同步（`after_sale` ↔ `refunded`/`closed`）

---

## 五、购物车服务

### 5.1 购物车接口

> **路由设计说明**：商品操作统一收在 `/cart/items/*` 子路径下，避免 `PUT /cart/select` 与 `PUT /cart/{sku_id}` 的路由歧义（"select" 会被误匹配为 sku_id）；与前端 `pc-client-frontend.md` 的 `/api/cart/*` 命名对齐。

```
GET    /api/v1/cart                      # 购物车列表（含 price_changed / invalid / max_quantity 标记）
POST   /api/v1/cart/items                # 添加商品
PUT    /api/v1/cart/items/{sku_id}       # 修改数量
DELETE /api/v1/cart/items/{sku_id}       # 删除商品
POST   /api/v1/cart/items/select         # 选中/取消选中（body: sku_ids[] + selected）
POST   /api/v1/cart/items/select-all     # 全选/取消全选
DELETE /api/v1/cart/items/cleared        # 清空已勾选商品
GET    /api/v1/cart/summary              # 购物车汇总（件数、金额、优惠预估）
POST   /api/v1/cart/merge                # 登录后本地购物车合并（request_id 幂等）
```

### 5.2 购物车规则
- 未登录时购物车存本地，登录后调用 `/cart/merge` 合并到服务端；合并幂等（`request_id`），同一 SKU 数量取「本地与服务端较大值」而非累加，避免重复合并导致数量翻倍
- 添加商品时校验库存，超库存时提示最大可购买数量
- 购物车商品价格变动时，客户端定时刷新价格；服务端返回 `price_changed=true` 与最新价格，客户端不自动下单，需用户确认
- 下架/失效商品标记 `invalid=true`，保留在列表中供用户手动清理，不参与结算
- 购物车并发修改以服务端为最终一致，客户端乐观 UI 后以服务端返回为准覆盖本地状态

---

## 六、会员服务

### 6.1 个人信息

```
GET   /api/v1/user/profile              # 获取个人信息
PUT   /api/v1/user/profile              # 更新个人信息
POST  /api/v1/user/avatar               # 上传头像
GET   /api/v1/user/security             # 安全设置
PUT   /api/v1/user/password             # 修改密码
PUT   /api/v1/user/email                # 修改邮箱
PUT   /api/v1/user/phone                # 修改手机号
```

### 6.2 地址管理

```
GET    /api/v1/user/addresses           # 地址列表
POST   /api/v1/user/addresses           # 新增地址
PUT    /api/v1/user/addresses/{id}      # 更新地址
DELETE /api/v1/user/addresses/{id}      # 删除地址
PUT    /api/v1/user/addresses/{id}/default  # 设为默认
```

**地址表：**
```sql
CREATE TABLE `user_address` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(64) NOT NULL,
  `phone` VARCHAR(20) NOT NULL,
  `province` VARCHAR(32) NOT NULL,
  `city` VARCHAR(32) NOT NULL,
  `district` VARCHAR(32) NOT NULL,
  `detail` VARCHAR(256) NOT NULL,
  `zip_code` VARCHAR(10),
  `is_default` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 6.3 收藏

> **收藏夹**：支持多收藏夹（与前端 `/api/favorites/folders` 对齐），`user_favorite` 表增加 `folder_id` 字段，`NULL` 表示默认收藏夹。

```
GET    /api/v1/user/favorites           # 收藏列表（支持 folder_id 筛选）
POST   /api/v1/user/favorites           # 添加收藏（body: product_id, folder_id?）
POST   /api/v1/user/favorites/toggle    # 收藏/取消收藏切换（幂等）
DELETE /api/v1/user/favorites/{favorite_id}  # 取消收藏（favorite_id=收藏记录ID，非product_id）
GET    /api/v1/user/favorites/check     # 批量检查收藏状态（body: product_ids[]）
GET    /api/v1/user/favorites/folders   # 收藏夹列表
POST   /api/v1/user/favorites/folders   # 新建收藏夹
```

### 6.4 积分

```
GET  /api/v1/user/points                 # 积分概览（余额 + 等级）
GET  /api/v1/user/points/logs            # 积分明细
POST /api/v1/user/points/sign-in         # 每日签到（幂等，重复签到返回当日已签）
POST /api/v1/user/points/exchange        # 积分兑换优惠券
GET  /api/v1/user/level                  # 用户等级信息（等级、成长值、下一级门槛）
```

**积分规则：**
- 消费1元 = 1积分
- 每日签到 = 5积分（连续签到额外奖励，断签重置）
- 评价商品 = 20积分
- 积分兑换优惠券
- 积分变动通过 `points_log` 流水记录，`related_id` 关联订单/活动，支持对账回滚；并发扣减使用 Redis 预扣 + DB 乐观锁，防止超扣

### 6.5 文件上传

> 头像、评价图片、客服附件统一走共享文件服务（`admin-file`），本端不自建上传通道。

```
POST /api/v1/file/upload-token           # 获取OSS/S3直传凭证（含大小/类型/数量限制）
POST /api/v1/file/report                 # 上传完成回调（登记审核状态）
```

**上传安全：**
- 限制：单文件 ≤ 10MB，图片仅 jpg/png/webp，头像 ≤ 2MB
- 服务端二次校验文件头（魔数），防止改扩展名绕过；同步进入内容安全审核（鉴黄/鉴政），审核不通过自动替换占位图
- 崩溃日志、客服附件单独限制大小与频次，防止存储滥用

---

## 七、消息服务

### 7.1 桌面推送

#### 7.1.1 推送通道

| 通道 | 协议 | 用途 |
|------|------|------|
| WebSocket | WSS | 实时消息、客服会话 |
| 系统通知 | Windows Toast / macOS Notification | 订单状态变更、物流提醒 |
| 托盘消息 | 客户端托盘 | 促销信息、系统公告 |

#### 7.1.2 推送通道与 WebSocket 协议

**WebSocket 接入（桌面端唯一实时通道）：**
```
GET /api/v1/push/ws                     # WebSocket 升级点（生产环境强制 wss://，前端计划中的 ws:// 仅开发环境）
```

**连接生命周期：**
1. 建立连接后 5 秒内发送首帧 `{"type":"auth","access_token":"..."}`，超时或鉴权失败服务端主动断开
2. 心跳：客户端每 30 秒发送 `ping`，服务端回 `pong`；60 秒无心跳服务端清理连接
3. 断线重连采用指数退避（1s→2s→4s→…最大 60s）+ 抖动，重连成功后按 `last_msg_id` 补拉离线消息
4. 每条下行消息携带自增 `msg_id` 与业务 `topic`，客户端对已处理 `msg_id` 幂等去重

**Topic 订阅模型**（与前端 `stock-updates / order-updates / logistics-updates / messages / customer-service` 五个频道对齐）：

| Topic | 推送内容 | 订阅条件 |
|-------|---------|---------|
| `order` | 订单状态变更 | 登录后自动订阅 |
| `logistics` | 物流状态提醒 | 订阅的订单发货后 |
| `message` | 站内信、系统通知 | 登录后自动订阅 |
| `stock` | 购物车商品库存/价格变动 | 进入购物车页面时订阅 |
| `customer-service` | 客服会话消息 | 创建客服会话后订阅 |

```
POST   /api/v1/push/subscriptions        # 订阅topic
DELETE /api/v1/push/subscriptions/{topic} # 退订topic
POST   /api/v1/push/register             # 注册设备推送凭证
DELETE /api/v1/push/devices/{device_id}  # 注销设备推送
POST   /api/v1/push/test                 # 测试推送（内部/管理端）
```

**水平扩展：** 推送服务多实例部署时，连接与路由解耦——用户在线状态与 `instance_id` 存 Redis；跨实例投递通过 RabbitMQ 广播交换机或 Redis Pub/Sub 转发，避免粘性会话带来的扩缩容限制。

**频控与合规：** 营销类推送受用户开关与免打扰时段控制（22:00–8:00 不推送营销），单用户营销推送 ≤ 3 条/天；订单/物流/安全类通知不受免打扰限制。

#### 7.1.3 离线推送
- 用户离线时，推送消息持久化到 notification 表
- 用户上线后通过 WebSocket 拉取未读消息
- 离线消息保留7天，过期自动清理
- 离线推送通知通过系统通知通道发送

### 7.2 系统通知

```
GET    /api/v1/notifications             # 通知列表
GET    /api/v1/notifications/unread      # 未读通知
PUT    /api/v1/notifications/{id}/read   # 标记已读
PUT    /api/v1/notifications/read-all    # 全部已读
DELETE /api/v1/notifications/{id}        # 删除通知
```

**通知类型：**
- 订单状态变更
- 物流状态更新
- 优惠券到期提醒
- 系统公告
- 活动通知

**未读数性能**：角标未读数不走 `SELECT COUNT(*)`，用 Redis 计数器（`INCR/DECR`）维护，读取 O(1)；计数与 DB 定时对账修复漂移。通知数据由共享消息服务（`admin-message` 事件）产生，本端只做查询与已读状态管理。

### 7.3 物流提醒

```
POST /api/v1/logistics/subscribe         # 订阅物流提醒
DELETE /api/v1/logistics/subscribe/{id}  # 取消订阅
```

**提醒时机：**
- 订单发货
- 物流状态变更（到达转运中心、派送中、已签收）
- 异常提醒（物流超时、地址问题）

---

## 八、更新服务

### 8.1 版本管理

```
GET /api/v1/client/version/check         # 检查更新
GET /api/v1/client/version/latest        # 最新版本
GET /api/v1/client/version/list          # 版本列表
```

**检查更新请求：**
```json
{
  "current_version": "3.2.1",
  "current_version_code": 321,
  "os_type": "windows",
  "os_arch": "x64",
  "channel": "stable"
}
```

**响应：**
```json
{
  "code": 0,
  "data": {
    "has_update": true,
    "version": "3.3.0",
    "version_code": 330,
    "is_force_update": false,
    "update_log": "1. 优化性能\n2. 修复已知问题",
    "download_url": "https://cdn.example.com/...",
    "file_size": 52428800,
    "file_hash": "sha256_hash",
    "is_delta": true,
    "delta_download_url": "https://cdn.example.com/...",
    "delta_file_size": 10485760
  }
}
```

### 8.2 增量更新

#### 8.2.1 增量更新流程

> **技术选型说明**：前端已选型 `electron-updater`（NSIS/Squirrel），其自带基于 `blockmap` 的差量下载（differential download），无需自研 bsdiff。bsdiff 对大安装包内存消耗高（需同时加载新旧全量在内存），且需为每个 `from→to` 版本组合预生成增量包，会造成增量包数量随版本数平方级膨胀，维护成本高。

**采用方案（与 electron-updater 对齐）：**
```
1. 客户端上报 current_version / os_type / os_arch / channel
2. 服务端匹配增量包：有 blockmap 差量包返回 delta 信息，无则降级返回全量包 URL
3. 客户端下载差量包，在本地 staging 目录合并，不直接覆盖安装目录
4. 校验完整性（SHA-256 + 安装包签名校验，防止被篡改的增量包）
5. 校验通过后原子替换（退出应用 → 替换 → 启动自检）
6. 启动自检失败（崩溃/无法启动）自动回滚到上一版本
7. 上报更新结果（success/failed/rolled_back）用于灰度熔断与成功率统计
```

**原子性与回滚要求：**
- 更新安装在 staging 目录完成，通过「重命名替换 + 保留上一版本完整包」实现准原子切换，避免 Windows 文件占用导致半安装状态
- 客户端磁盘需预留约 2 倍安装包体积；空间不足时降级为「下载完成后提示用户」而非静默更新
- 服务端版本黑名单：命中黑名单的版本，`version/check` 直接返回下一个安全版本，客户端不会拉到问题版本

#### 8.2.2 增量包管理

```sql
CREATE TABLE `delta_package` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `from_version` VARCHAR(32) NOT NULL,
  `to_version` VARCHAR(32) NOT NULL,
  `os_type` VARCHAR(32) NOT NULL,
  `os_arch` VARCHAR(16) NOT NULL DEFAULT 'x64',
  `channel` VARCHAR(16) NOT NULL DEFAULT 'stable',
  `package_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1-全量 2-差量(blockmap)',
  `download_url` VARCHAR(512) NOT NULL,
  `file_size` BIGINT NOT NULL,
  `file_hash` VARCHAR(64) NOT NULL,
  `sign_status` TINYINT NOT NULL DEFAULT 1 COMMENT '1-已签名 0-待签名',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_from_to_os_arch_channel` (`from_version`, `to_version`, `os_type`, `os_arch`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **字段对齐说明**：检查更新请求中包含 `os_arch` 与 `channel`，增量包唯一键必须包含这两个维度，否则 x64/arm64 或 stable/beta 通道之间会互相覆盖。差量包由 CI 流水线在构建产物发布时自动生成并登记，无需人工维护。

### 8.3 灰度发布

#### 8.3.1 灰度策略

| 策略 | 说明 |
|------|------|
| 白名单 | 指定用户ID列表 |
| 百分比 | 按用户ID哈希取模 |
| 设备类型 | 指定操作系统 |
| 地区 | 指定区域 |

#### 8.3.2 灰度配置

```json
{
  "version": "3.3.0",
  "gray_scale": 10,
  "strategy": "percentage",
  "whitelist": [10001, 10002],
  "blacklist": [],
  "os_types": ["windows", "macos"],
  "start_time": "2026-10-11T10:00:00Z",
  "end_time": "2026-10-18T10:00:00Z"
}
```

#### 8.3.3 灰度发布流程
1. 内部测试（1-2天）→ 白名单用户（2-3天）→ 1%灰度（1天）→ 5%灰度（1天）→ 20%灰度（1天）→ 50%灰度（1天）→ 全量发布
2. 每阶段监控崩溃率、更新成功率，**触发以下任一阈值自动暂停灰度并告警**（暂停后停止放量，由发布负责人人工确认后恢复）：
   - 新版本崩溃率 > 0.5% 且高于上一版本 2 倍
   - 更新成功率 < 95%（success / total，含 rolled_back）
   - 回滚率 > 2%
   - 强制更新后客服工单量环比上涨 50%
3. 全量发布前强制灰度阶段无P0/P1级别崩溃
4. 百分比分桶算法固定为 `hash(user_id) % 100`（MurmurHash，固定种子），保证同一用户在整个灰度周期内稳定落在同一分桶，不会在多个百分比之间跳动

### 8.4 更新回滚机制

- 客户端保留上一版本完整包，更新失败/启动自检失败自动回滚
- 服务端支持版本黑名单（`client_version.is_blacklist`），命中黑名单的版本在 `version/check` 中被跳过，直接下发下一安全版本
- 强制更新仅用于安全修复或重大Bug修复，且必须先经过至少 5% 灰度阶段
- 客户端本地最多保留当前版本 + 上一版本两份安装包，更早版本定期清理

### 8.5 更新统计

```
POST /api/v1/client/update/report        # 客户端上报更新结果（start/downloaded/installed/rolled_back）
GET  /api/v1/client/version/stats        # 版本分布统计
GET  /api/v1/client/update/stats         # 更新成功率统计（按版本/渠道/OS维度）
```

> 更新成功率与崩溃率是灰度熔断（8.3.3）的数据源，`update/report` 必须在客户端更新流程的每个关键节点上报，上报失败不影响更新流程本身（本地重试队列，最多 3 次）。

---

## 九、数据同步

### 9.1 本地缓存同步

#### 9.1.1 同步策略

| 数据类型 | 同步方式 | 冲突解决 |
|---------|---------|---------|
| 用户信息 | 服务端优先 | 以服务端为准 |
| 收藏列表 | 双向合并 | 时间戳优先（服务端时钟为准，客户端时间仅作参考） |
| 购物车 | 双向合并 | 同一 SKU 取「本地/服务端较大值」，不累加（见下方说明） |
| 浏览历史 | 客户端上传 | 去重合并，保留最近1000条 |
| 搜索历史 | 客户端上传 | 去重合并，保留最近50条 |
| 配置设置 | 服务端优先 + 时间戳 | 以服务端为准，本地仅缓存 |

> **购物车数量冲突说明**：早期草稿中的「数量累加」策略会导致重复同步时数量翻倍，已废弃；统一采用「取较大值 + 服务端最终一致」，且购物车操作走操作日志合并（9.1.3），每个操作携带幂等 `op_id`，重复上报不会重复生效。

#### 9.1.2 同步接口

```
POST /api/v1/sync/pull                  # 拉取服务端数据（增量，基于版本号游标）
POST /api/v1/sync/push                   # 推送客户端数据（op_id 幂等）
POST /api/v1/sync/resolve                # 冲突解决
POST /api/v1/sync/full                   # 全量同步（首次安装/数据异常时）
GET  /api/v1/sync/settings               # 同步隐私开关（浏览/搜索历史是否上传）
```

> **为什么不用时间戳做增量游标**：服务端并发写入时，时间戳存在精度与时钟偏移问题——拉取窗口边界上的写入可能被漏掉（`updated_at` 相同或回拨）。改为**每类数据维护单调递增的版本号（version/cursor）**，客户端携带上次同步拿到的 cursor，服务端返回 `version > cursor` 的变更与新 cursor，保证不重不漏。

**拉取数据请求：**
```json
{
  "device_id": "dev_abc123",
  "data_types": ["favorites", "cart", "history"],
  "cursors": {
    "favorites": 10086,
    "cart": 2048,
    "history": 55012
  }
}
```

**拉取数据响应：**
```json
{
  "code": 0,
  "data": {
    "changes": {
      "favorites": [ { "product_id": 10001, "action": "add", "version": 10087 } ],
      "cart": [ { "sku_id": 10001, "quantity": 2, "action": "update", "version": 2049 } ]
    },
    "cursors": { "favorites": 10087, "cart": 2049, "history": 55012 },
    "has_more": false
  }
}
```

> 响应含 `has_more=true` 时客户端继续用新 cursor 增量拉取，单次返回条数上限 500，避免全量拉取大数据集（如 1000 条浏览历史）超时。

**推送数据请求：**
```json
{
  "device_id": "dev_abc123",
  "ops": [
    {
      "op_id": "op_uuid_001",
      "data_type": "favorites",
      "data_id": "10001",
      "action": "add",
      "timestamp": "2026-10-11T12:00:00Z"
    },
    {
      "op_id": "op_uuid_002",
      "data_type": "cart",
      "data_id": "10001",
      "action": "update",
      "quantity": 2,
      "timestamp": "2026-10-11T12:00:00Z"
    }
  ]
}
```

> **推送幂等**：每条操作携带客户端生成的唯一 `op_id`，服务端以 `uk_op_id` 唯一索引去重，重复上报（网络重试/离线队列重放）不会重复执行。

#### 9.1.3 冲突解决策略
- **时间戳优先**：以最后修改时间为准；客户端时间不可信时以服务端接收时间兜底
- **操作日志合并**：收藏/购物车按操作序列合并（add/delete/update），每个操作以 `op_id` 幂等
- **数量冲突**：购物车数量不累加，取本地与服务端较大值，服务端为准（与 9.1.1 表格一致）
- **删除与修改冲突**：以「删除优先」处理，被删除的条目不再接受迟到的 update
- **无法自动合并的冲突**：写入冲突暂存区，客户端弹窗让用户手动选择，通过 `/sync/resolve` 提交结果

### 9.2 离线数据上传

#### 9.2.1 离线操作队列

```
POST /api/v1/sync/offline/upload         # 上传离线操作（op_id 幂等，批量≤100条）
GET  /api/v1/sync/offline/status         # 查询离线操作状态
```

**离线操作类型（仅限幂等、可补偿的操作）：**
- 添加收藏
- 修改购物车（数量增减）
- 添加/修改收货地址草稿
- 浏览/搜索历史上报

> **禁止离线下单**：早期草稿将「提交订单」列入离线队列，已废弃——下单依赖实时的价格、库存、优惠与风控校验，离线期间这些数据可能已变化，离线提交必然产生脏订单与售后纠纷。客户端离线时只保存**结算草稿**（商品、地址、优惠券选择），联网后重新拉取结算信息、由用户确认后提交。

#### 9.2.2 离线数据表

```sql
CREATE TABLE `offline_data` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `device_id` VARCHAR(64) NOT NULL,
  `data_type` VARCHAR(32) NOT NULL,
  `data_id` VARCHAR(64) NOT NULL,
  `op_id` VARCHAR(64) NOT NULL COMMENT '客户端操作唯一ID，幂等去重键',
  `operation` VARCHAR(16) NOT NULL,
  `data` JSON NOT NULL,
  `local_created_at` DATETIME NOT NULL,
  `sync_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-待处理 1-成功 2-失败 3-冲突待解决',
  `retry_count` INT NOT NULL DEFAULT 0,
  `max_retry` INT NOT NULL DEFAULT 5,
  `server_created_at` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_op_id` (`op_id`),
  KEY `idx_user_device` (`user_id`, `device_id`),
  KEY `idx_sync_status` (`sync_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **重试与死信**：超过 `max_retry` 仍失败的操作标记为死信（`sync_status=3`），进入人工/告警通道，不无限重试；客户端本地队列同样有容量上限与淘汰策略（按时间 FIFO 淘汰最旧的浏览/搜索历史类操作）。
> **表职责划分**：`offline_data`（本节）为客户端上报的操作明细表；1.3.2 中的 `offline_queue` 表已标记废弃，历史实现迁移完成后下线。

### 9.3 多端同步

#### 9.3.1 同步触发时机

| 触发条件 | 同步内容 |
|---------|---------|
| 应用启动 | 增量拉取 |
| 首次安装/数据异常 | 全量拉取 |
| 用户操作 | 实时推送 |
| 定时同步 | 每5分钟 |
| 网络恢复 | 离线队列上传 |
| 手动同步 | 用户触发 |

#### 9.3.2 同步状态

```
GET /api/v1/sync/status
```

**响应：**
```json
{
  "code": 0,
  "data": {
    "last_sync_time": "2026-10-11T12:00:00Z",
    "pending_count": 5,
    "sync_status": "syncing",
    "data_types": {
      "favorites": {"last_sync": "2026-10-11T12:00:00Z", "count": 100},
      "cart": {"last_sync": "2026-10-11T12:00:00Z", "count": 10}
    }
  }
}
```

---

## 十、客服服务

### 10.1 在线客服

#### 10.1.1 会话管理

> **与前端契约对齐**：前端 `pc-client-frontend.md` 使用 `/api/customer-service/queue`、`/api/customer-service/messages`（复数）；本计划统一为 `/api/v1/customer-service/*` 前缀与复数命名，前端同步修订。

```
POST /api/v1/customer-service/session            # 创建会话
GET  /api/v1/customer-service/session/{id}       # 获取会话信息
POST /api/v1/customer-service/session/{id}/close # 关闭会话
GET  /api/v1/customer-service/session/{id}/messages  # 获取消息列表（分页，历史漫游）
GET  /api/v1/customer-service/queue              # 排队信息（队列位置、预计等待）
GET  /api/v1/customer-service/history            # 历史会话记录
```

#### 10.1.2 消息发送

```
POST /api/v1/customer-service/messages           # 发送消息（msg_id 幂等）
```

**WebSocket 消息格式：**
```json
{
  "type": "message",
  "session_id": "cs_123456",
  "msg_id": "msg_789",
  "sender_type": "customer",
  "content_type": "text",
  "content": "你好，我想咨询一下订单问题",
  "timestamp": "2026-10-11T12:00:00Z"
}
```

**客服会话 WebSocket 要求：**
- 连接鉴权：复用 7.1.2 的 `auth` 首帧 + topic `customer-service` 订阅模型
- 消息去重：客户端生成 `msg_id`，服务端与对端按 `msg_id` 幂等，重连后按 `last_msg_id` 拉取漏掉的消息
- 历史漫游：消息持久化到会话消息表，离线期间的消息在重连后分页拉取，不依赖内存
- 附件消息：`content_type` 支持 `image/file/order_card`，文件走共享文件服务直传凭证
- 会话内快捷操作：发送订单卡片（带订单快照，避免客服看到的订单状态与买家不一致）

#### 10.1.3 客服分配策略

| 策略 | 说明 |
|------|------|
| 轮询分配 | 按顺序分配给空闲客服 |
| 技能组分配 | 根据问题类型分配 |
| 优先级分配 | VIP客户优先 |
| 负载均衡 | 按当前会话数分配 |

### 10.2 工单系统

#### 10.2.1 工单接口

```
POST /api/v1/customer-service/tickets          # 创建工单
GET  /api/v1/customer-service/tickets          # 工单列表
GET  /api/v1/customer-service/tickets/{id}     # 工单详情
PUT  /api/v1/customer-service/tickets/{id}     # 更新工单
POST /api/v1/customer-service/tickets/{id}/reply  # 工单回复
POST /api/v1/customer-service/tickets/{id}/rate   # 工单满意度评价（对齐前端）
```

#### 10.2.2 工单表

```sql
CREATE TABLE `service_ticket` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_no` VARCHAR(32) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `order_id` BIGINT UNSIGNED,
  `type` VARCHAR(32) NOT NULL,
  `title` VARCHAR(256) NOT NULL,
  `description` TEXT,
  `status` TINYINT NOT NULL DEFAULT 0,
  `priority` TINYINT NOT NULL DEFAULT 1,
  `assigned_to` BIGINT UNSIGNED,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `resolved_at` DATETIME,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 10.2.3 工单状态

```
待处理 → 处理中 → 已解决 → 已关闭
           ↓
         已转派
```

---

## 十一、崩溃上报服务

### 11.1 崩溃接口

```
POST /api/v1/crash/upload                 # 上传崩溃日志（未登录也可用，脱敏+限流）
GET  /api/v1/crash/list                   # 崩溃列表（管理端，走admin网关鉴权）
GET  /api/v1/crash/{id}                   # 崩溃详情
PUT  /api/v1/crash/{id}/status            # 更新崩溃状态
POST /api/v1/crash/symbols                # 上传符号文件（PDB/dSYM，管理端）
```

### 11.2 崩溃分析
- 自动聚合相同 crash_hash 的崩溃（`uk_crash_hash (crash_hash, client_version, os_type)` 唯一键 + `ON DUPLICATE KEY UPDATE count=count+1`，避免并发上报产生重复聚合行）
- 支持符号化解析：Windows 用 PDB，macOS 用 dSYM；符号文件按版本归档，崩溃查询时自动匹配对应版本符号
- 崩溃率告警：新版本崩溃率超过上一版本2倍时自动告警，且与 8.3.3 灰度熔断指标联动
- 隐私脱敏：上传前客户端脱敏（去除 token、手机号、地址等），服务端二次脱敏兜底；崩溃日志仅用于稳定性分析，保留 90 天后自动清理

---

## 十二、性能优化

### 12.1 接口聚合

#### 12.1.1 BFF层（Backend For Frontend）

PC客户端通过独立 BFF 层（Go语言实现）聚合多个微服务接口，减少客户端请求次数。BFF层独立于API Gateway部署，负责桌面端特有的接口聚合。

```
GET /api/v1/bff/home                      # 首页聚合接口（GET，可缓存）
GET /api/v1/bff/product-detail            # 商品详情聚合
GET /api/v1/bff/order-confirm             # 订单确认聚合
```

> BFF 聚合接口均为**只读查询**，使用 GET 以保留 HTTP 缓存语义（配合 CDN/网关缓存）；涉及写操作（下单、支付）不经过 BFF 聚合，直接调用对应服务。

**首页聚合响应：**
```json
{
  "code": 0,
  "data": {
    "user": {...},
    "banners": [...],
    "categories": [...],
    "recommendations": [...],
    "notifications": {"unread_count": 3},
    "cart": {"count": 5}
  }
}
```

#### 12.1.2 聚合策略

| 策略 | 说明 |
|------|------|
| 并行调用 | 多个服务并行请求，总超时500ms |
| 数据裁剪 | 只返回客户端需要的字段 |
| 缓存聚合结果 | 短时间缓存聚合结果（首页30秒） |
| 降级策略 | 非核心服务失败时降级，不影响主流程 |
| 超时控制 | 单服务超时分级：核心链路（商品/价格/库存）200ms、非核心（推荐/通知/促销）100ms，聚合总超时800ms；超时即降级返回默认值，不阻塞主流程 |

### 12.2 CDN

#### 12.2.1 CDN策略

| 资源类型 | CDN策略 | 缓存时间 |
|---------|---------|---------|
| 商品图片 | 图片CDN | 1天 |
| JS/CSS | 静态资源CDN | 7天 |
| 安装包 | 下载CDN | 永久 |
| API响应 | 不缓存 | - |

#### 12.2.2 图片优化

- 支持 WebP 格式
- 按需裁剪尺寸
- 渐进式加载
- 懒加载

### 12.3 其他优化

#### 12.3.1 数据库优化

- 读写分离（主库写，从库读）
- 分库分表（订单表按用户ID分片）
- 索引优化，慢查询监控告警（>1秒）
- 连接池：HikariCP，最大连接数200

#### 12.3.2 接口优化

- 分页查询
- 字段筛选
- 批量操作
- 异步处理

#### 12.3.3 限流方案

| 限流维度 | 阈值 | 策略 |
|---------|------|------|
| 单设备 | 100次/分钟 | 令牌桶 |
| 单IP | 1000次/分钟 | 令牌桶 |
| 单用户 | 200次/分钟 | 滑动窗口 |
| 全局API | 10000次/秒 | 网关层限流 |
| 登录接口 | 5次/分钟/IP | 固定窗口 |
| 短信验证码 | 1条/60秒，10条/天/手机号 | 固定窗口 |

#### 12.3.4 监控告警

| 监控项 | 阈值 | 告警方式 |
|--------|------|---------|
| 接口响应时间 | > 1s | 邮件+短信 |
| 错误率 | > 1% | 邮件+短信 |
| CPU使用率 | > 80% | 邮件 |
| 内存使用率 | > 85% | 邮件 |
| 磁盘使用率 | > 90% | 邮件+短信 |
| 崩溃率 | > 0.5% | 邮件+短信 |
| 缓存命中率 | < 80% | 邮件 |
| 下单/支付成功率 | < 99% | 邮件+短信 |
| 更新成功率 | < 95% | 邮件+短信 |
| 同步冲突率 | > 5% | 邮件 |

---

## 十三、部署架构

### 13.1 服务部署

```
                     ┌─────────────┐
                     │   CDN       │
                     └──────┬──────┘
                            │
                     ┌──────┴──────┐
                     │  API Gateway │
                     └──────┬──────┘
                            │
              ┌─────────────┼─────────────┐
              │             │             │
        ┌─────┴─────┐ ┌────┴────┐ ┌─────┴─────┐
        │  BFF层    │ │ 推送服务 │ │ 其他服务  │
        │  (Go)    │ │ (Go)   │ │  (Java)  │
        └─────┬─────┘ └────┬────┘ └─────┬─────┘
              │             │             │
        ┌─────┴─────────────┴─────────────┴─────┐
        │              数据层                    │
        │  MySQL  │  Redis  │  ES  │  RabbitMQ  │
        └──────────────────────────────────────┘
```

### 13.2 容器化部署

- 使用 Docker 容器化部署
- Kubernetes 编排管理
- 自动扩缩容（HPA基于CPU/内存/QPS）
- 滚动更新，零停机部署

### 13.3 环境划分

| 环境 | 用途 | 数据 |
|------|------|------|
| dev | 开发测试 | 模拟数据 |
| staging | 预发布 | 脱敏数据 |
| production | 生产 | 真实数据 |

---

## 十四、开发排期

### 14.1 阶段划分

| 阶段 | 时间 | 里程碑 |
|------|------|--------|
| 第一阶段 | 1-3周 | 架构设计、基础设施搭建、CI/CD流水线 |
| 第二阶段 | 4-7周 | 认证、商品、订单、购物车核心服务 |
| 第三阶段 | 8-11周 | 会员、消息、客服服务 |
| 第四阶段 | 12-15周 | 更新服务、数据同步、崩溃上报 |
| 第五阶段 | 16-18周 | 性能优化、联调测试、安全渗透测试 |
| 第六阶段 | 19-20周 | 灰度发布、全量上线 |

### 14.2 人员配置

| 角色 | 人数 | 职责 |
|------|------|------|
| 架构师 | 1 | 架构设计、技术选型 |
| 后端开发 | 5 | 服务开发（Java 4人 + Go 1人） |
| 前端开发 | 2 | 客户端开发 |
| 测试工程师 | 2 | 功能测试、性能测试 |
| 运维工程师 | 1 | 部署、监控 |

---

## 十五、风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| 高并发 | 系统崩溃 | 限流、降级、扩容、多级缓存 |
| 数据不一致 | 用户体验差 | 分布式事务（Saga）、最终一致性 |
| 安全漏洞 | 数据泄露 | 加密、审计、安全渗透测试、代码审计 |
| 第三方服务不可用 | 功能异常 | 降级、重试、熔断（Sentinel） |
| 更新失败 | 客户端不可用 | 回滚机制、灰度发布、版本黑名单 |
| 缓存雪崩 | 数据库压力骤增 | 过期时间随机偏移、多级缓存 |
| 数据同步冲突 | 用户数据丢失 | 操作日志合并、冲突检测、用户手动解决 |
| 排期延期 | 上线推迟 | 预留缓冲时间、核心功能优先 |

---

## 十六、附录

### 16.1 API规范

- 统一返回格式：`{ "code": 0, "message": "success", "data": {} }`
- **路径前缀**：本文档统一使用 `/api/v1/`；前端 `pc-client-frontend.md` 中出现的 `/api/xxx` 一律映射为 `/api/v1/xxx`，以前端契约对齐表（见下）为准
- **资源命名**：复数、蛇形命名统一（`/notifications`、`/messages`、`/tickets`），避免单复数混用
- 错误码规范：4位数字，前2位模块，后2位错误类型
- 分页参数：page（页码，从1开始）、size（每页数量，最大50）；深分页场景改用游标（cursor/search_after）
- 时间格式：ISO 8601（`2026-10-11T12:00:00Z`）
- 金额单位：元，保留两位小数
- 所有接口需鉴权（**例外白名单**见 2.3.4：登录、商品/搜索/推荐读接口、更新检查、崩溃上报、支付回调）

### 16.2 前端契约对齐表（pc-client-frontend.md）

> 前端计划中的以下接口已在本文档补齐或统一命名，前后端需按本表联调：

| 前端接口 | 后端接口（本计划） | 说明 |
|---------|------------------|------|
| `POST /api/auth/login` | `POST /api/v1/auth/login` | 前缀对齐 |
| `/api/auth/sms-code`、`login-sms`、`qrcode-token`、`qrcode-status`、`third-party` | `/api/v1/auth/*` | 见 2.1.3 |
| `/api/auth/switch-account`、`/api/accounts/recent` | `/api/v1/auth/switch-account`、`/api/v1/accounts/recent` | 见 2.1.3 |
| `/api/cart/add`、`update-quantity`、`remove`、`select`、`select-all` | `/api/v1/cart/items/*` | 路由统一，消除歧义，见 5.1 |
| `/api/favorites/add`、`remove`、`toggle`、`folders` | `/api/v1/user/favorites/*` | 见 6.3 |
| `/api/addresses`、`/api/regions` | `/api/v1/user/addresses`、`/api/v1/regions` | 见 6.2、4.1.1 |
| `/api/orders/:id`、`/api/orders/:id/cancel` | `/api/v1/orders/{order_no}/*` | 标识符统一为 order_no |
| `/api/payment/pay`、`/api/payment-methods` | `/api/v1/orders/{order_no}/pay`、`/api/v1/payment-methods` | 见 4.1.1、4.2 |
| `/api/checkout` | `/api/v1/checkout` | 见 4.1.1 |
| `/api/after-sales/*` | `/api/v1/after-sales/*` | 新增，见 4.6 |
| `/api/coupons/*` | `/api/v1/coupons/*` | 共享营销服务，见 1.2.1 |
| `/api/points/balance`、`history`、`sign-in`、`exchange` | `/api/v1/user/points*` | 见 6.4 |
| `/api/user/level` | `/api/v1/user/level` | 见 6.4 |
| `/api/messages` | `/api/v1/notifications` | 命名统一为 notifications |
| `/api/tickets` | `/api/v1/customer-service/tickets` | 见 10.2 |
| `/api/search`、`/api/search/suggestions` | `/api/v1/search/products`、`/api/v1/search/suggest` | 见 3.4 |
| `/api/products/:id/skus`、`/related`、`/filters`、`/stock` | `/api/v1/products/*` | 见 3.1.1 |
| `/api/activities` | `/api/v1/activities`（内容服务） | 见 1.2.1 |
| `/api/sync/offline-actions` | `/api/v1/sync/offline/upload` | 见 9.2.1 |
| `ws://.../stock-updates` 等 5 个频道 | `wss://…/api/v1/push/ws` + topic 订阅 | 见 7.1.2，生产强制 wss |

### 16.2 错误码

| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 1001 | 参数错误 |
| 1002 | 未登录 |
| 1003 | 无权限 |
| 1004 | 请求签名错误 |
| 1005 | 请求超时（重放攻击） |
| 2001 | 商品不存在 |
| 2002 | 库存不足 |
| 2003 | 商品已下架 |
| 3001 | 订单不存在 |
| 3002 | 订单状态错误 |
| 3003 | 订单已取消 |
| 4001 | 支付失败 |
| 4002 | 支付超时 |
| 4003 | 支付密码错误 |
| 4004 | 订单已取消，收到支付回调（触发自动退款） |
| 5001 | 设备未绑定 |
| 5002 | 设备数量超限 |
| 5003 | 需要二次验证 |
| 5004 | 设备指纹校验失败 |
| 6001 | 售后单不存在 |
| 6002 | 售后状态错误 |
| 6003 | 退款金额超过实付金额 |
| 7001 | 同步游标无效（需全量同步） |
| 7002 | 离线操作超出重试上限（死信） |
| 7003 | 同步冲突待人工解决 |
| 8001 | 版本不存在 |
| 8002 | 增量包校验失败（回退全量下载） |
| 8003 | 版本在黑名单中 |
| 9001 | 客服会话不存在 |
| 9002 | 客服排队超时 |

### 16.3 技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| 核心框架 | Java 17 + Spring Boot 3.x | 核心业务服务 |
| 高性能框架 | Go 1.22 + Gin | API Gateway、BFF层、推送服务 |
| 数据库 | MySQL 8.0 | 关系型数据 |
| 缓存 | Redis 7.0 | 缓存、会话、分布式锁 |
| 搜索 | Elasticsearch 8.x | 全文搜索 |
| 消息队列 | RabbitMQ | 异步处理、事件驱动 |
| 容器 | Docker + Kubernetes | 部署编排 |
| 监控 | Prometheus + Grafana | 监控告警 |
| 日志 | ELK Stack | 日志收集分析 |
| 限流熔断 | Sentinel | 流量控制 |

### 16.4 日志规范

- 统一JSON格式：`{"timestamp":"2026-10-11T12:00:00Z","level":"INFO","module":"order","trace_id":"uuid","message":"..."}`
- 日志级别：ERROR（需告警）、WARN（需关注）、INFO（关键业务节点）、DEBUG（开发调试）
- 关键业务操作必须打印日志（登录、下单、支付、退款、设备绑定）
- 敏感信息脱敏（密码、手机号、身份证号）

### 16.5 数据备份策略

- MySQL：每日全量备份 + binlog实时同步，备份保留30天
- Redis：RDB每日 + AOF实时，备份保留7天
- Elasticsearch：每日快照，备份保留14天
- 备份数据加密存储，异地容灾

---

## 审查记录

### v1.0 → v1.1 修改记录（2026-10-11）

| 编号 | 修改类型 | 修改内容 |
|------|---------|---------|
| 1 | 技术栈明确 | 明确Java用于核心服务、Go用于高性能网关/推送，避免三种语言混杂 |
| 2 | 新增购物车服务 | 在共享服务列表中补充购物车服务，新增购物车接口章节 |
| 3 | 新增商品评价 | 新增评价表、评价接口、评价规则，商品详情响应补充评价分页 |
| 4 | 新增发票服务 | 发票纳入订单中心职责，新增发票表设计 |
| 5 | 新增退款表 | 补充退款表设计，订单管理新增退款接口 |
| 6 | 新增收藏表 | 补充收藏表设计 |
| 7 | 新增积分明细表 | 补充积分明细表设计 |
| 8 | 重构商品表 | 拆分为SPU表+SKU表，明确spu_id/sku_id关系 |
| 9 | 订单表补充字段 | 新增shop_id、freight_amount、logistics_no、logistics_company、is_deleted字段 |
| 10 | 新增桌面端服务 | 补充崩溃上报服务、配置下发服务，新增crash_report、client_config表 |
| 11 | 新增通知表 | 补充notification表设计 |
| 12 | 完善缓存策略 | 新增缓存穿透/击穿/雪崩防护策略 |
| 13 | 密码加密明确 | 明确使用BCrypt存储密码，登录密码使用RSA加密传输 |
| 14 | 短信防刷 | 新增短信验证码防刷机制（60秒间隔，24小时最多10条） |
| 15 | 设备指纹签名 | 新增设备指纹签名验证，防止device_id伪造 |
| 16 | 二次验证 | 新设备登录增加二次验证流程 |
| 17 | API签名机制 | 新增HMAC-SHA256请求签名，防重放攻击 |
| 18 | Token存储安全 | 明确使用DPAPI/Keychain加密存储Token |
| 19 | 下单幂等性 | 新增request_id幂等性保证 |
| 20 | 支付安全 | 新增大额支付密码验证、支付回调验签 |
| 21 | 限流方案 | 新增详细限流方案（设备/IP/用户/全局/登录接口/短信接口） |
| 22 | 离线推送 | 补充离线推送设计（消息持久化、上线拉取、7天保留） |
| 23 | 更新回滚机制 | 补充客户端自动回滚、服务端版本黑名单 |
| 24 | 灰度发布流程 | 补充详细灰度发布流程（内部→白名单→1%→5%→20%→50%→全量） |
| 25 | 全量同步接口 | 新增sync/full全量同步接口 |
| 26 | 冲突解决优化 | 补充操作日志合并、数量冲突解决策略 |
| 27 | BFF层定位 | 明确BFF层独立部署，使用Go实现，补充超时控制 |
| 28 | 监控告警补充 | 新增崩溃率、缓存命中率监控 |
| 29 | 排期调整 | 排期从12周调整为20周，后端开发从4人增加到5人 |

### v1.1 → v1.2 修改记录（2026-10-11）

本次审查交叉比对了 `pc-client-frontend.md`（前端契约）、`admin-backend.md`（管理端共享服务与状态机）。

| 编号 | 审查维度 | 问题描述 → 修改位置与内容 |
|------|---------|--------------------------|
| 1 | 完整性 | 客服服务误列为桌面端特有（浏览器端同样需要）→ 1.2 节移除客服服务，1.1 节共享服务表补充客服服务，并加边界说明 |
| 2 | 完整性 | 共享服务缺内容/文件/营销/售后/区域/风控 → 1.2.1 新增「共享服务边界补充」表，说明本端只编排不自建 |
| 3 | 合理性 | 与 admin-backend 共享服务命名不一致 → 1.1 架构原则增加 core-product/core-price/core-promotion/core-user 对齐说明；价格计算统一走 core-price，禁止双端算价 |
| 4 | 一致性 | 「关键业务表增加 client_type 字段」与无状态共享原则矛盾 → 1.1 架构原则改为 JWT claim + 日志埋点，不污染业务表 |
| 5 | 完整性 | 前端依赖的筛选/SKU/批量库存/相关推荐接口缺失 → 3.1.1 补充 filters、stock、skus、related 接口 |
| 6 | 完整性 | 搜索历史/热搜接口缺失（前端已定义）→ 新增 3.4.3 节 |
| 7 | 合理性 | 结算页/支付方式/省市区接口缺失 → 新增 4.1.1 结算聚合接口节 |
| 8 | 完整性 | 售后服务整体缺失（前端有 after-sales 五接口，后端只有 refund）→ 新增 4.6 售后服务节，含状态机、部分退款、资金流复用 |
| 9 | 一致性 | 订单状态枚举与 admin-backend 4.3 状态机不一致（中英文混用、终态不同）→ 4.3 统一为 unpaid/paid/shipped/completed/cancelled/after_sale/refunded/closed |
| 10 | 可实施性 | 多店铺拆单未定义（admin 有拆单逻辑，pc 有 shop_id 却无拆单说明）→ 4.1 下单流程第 5 步补充拆单说明，返回主/子订单 |
| 11 | 边界情况 | 支付回调无幂等、无取消竞态处理 → 4.2 补充回调幂等键与「已取消收到回调→自动退款」流程 |
| 12 | 边界情况 | 支付密码存储未定义、错误锁定缺失 → 4.2 支付安全补充独立加密与锁定策略 |
| 13 | 一致性 | 购物车路由歧义（PUT /cart/select 会被匹配为 {sku_id}）→ 5.1 改为 /cart/items/* 子路径，补 select-all、summary、merge 接口，与前端对齐 |
| 14 | 边界情况 | 购物车本地合并无幂等、可能数量翻倍 → 5.2 明确 merge 幂等、数量取较大值、价格变动需用户确认 |
| 15 | 完整性 | 收藏夹功能前端有 folders，后端完全缺失 → 6.3 补充收藏夹接口与 toggle 语义，明确 favorite_id 非 product_id |
| 16 | 完整性 | 积分签到/兑换、用户等级接口缺失（前端已定义）→ 6.4 补充 sign-in/exchange/level 接口与并发扣减说明 |
| 17 | 完整性 | 文件上传服务缺失（头像/评价图/客服附件）→ 新增 6.5 文件上传节，OSS 直传 + 魔数校验 + 内容审核 |
| 18 | 完整性 | WebSocket 未定义连接协议（前端有 5 个 ws 频道，后端只字未提）→ 7.1.2 重写为 topic 订阅模型，含鉴权首帧、心跳、重连退避、msg_id 去重 |
| 19 | 合理性 | 推送 register 混淆 Token 与 device_id → 7.1.2 拆分 subscriptions/register/devices 接口 |
| 20 | 可实施性 | 推送服务多实例水平扩展未定义（无 sticky session 说明）→ 7.1.2 补充 Redis 在线状态 + MQ/Redis Pub/Sub 跨实例投递 |
| 21 | 合规 | 营销推送无免打扰与频控 → 7.1.2 补充 22:00–8:00 免打扰与每日上限 |
| 22 | 性能 | 通知未读数走 COUNT(*) → 7.2 补充 Redis 计数器 + 定时对账 |
| 23 | 可实施性 | bsdiff 自研增量不可行（内存消耗、组合爆炸），且与前端 electron-updater 选型冲突 → 8.2.1 改为 blockmap 差量下载，补 staging 目录原子替换与磁盘预留说明 |
| 24 | 一致性 | delta_package/client_version 表缺 os_arch、channel（请求体有但表无）→ 两表补充字段并更新唯一键 |
| 25 | 边界情况 | 灰度「异常自动暂停」无阈值、无分桶算法定义 → 8.3.3 补充 4 项熔断阈值与 MurmurHash 固定种子分桶 |
| 26 | 可实施性 | 更新回滚未考虑 Windows 文件占用与磁盘空间 → 8.4 补充 staging 替换、双版本保留、黑名单命中跳过逻辑 |
| 27 | 完整性 | 更新结果上报接口缺失（灰度熔断的数据源）→ 8.5 新增 POST /client/update/report |
| 28 | 边界情况 | 同步用时间戳做游标会漏数据（时钟偏移/并发写）→ 9.1.2 改为版本号 cursor，补 has_more 分页 |
| 29 | 一致性 | 9.1.1「购物车数量累加」与 9.1.3「服务端为准」自相矛盾 → 统一为取较大值不累加，说明累加会导致重复同步翻倍 |
| 30 | 边界情况 | sync push 无幂等键（离线重放会重复执行）→ 9.1.2 推送请求改为扁平 ops[]，每条携带 op_id 并加唯一索引 |
| 31 | 可实施性 | 离线队列允许「提交订单」必然产生脏订单 → 9.2.1 改为只允许幂等可补偿操作，下单降级为「结算草稿 + 联网确认」 |
| 32 | 一致性 | offline_queue 与 offline_data 两张表职责重复 → 1.3.2 标注 offline_queue 废弃，9.2.2 补充职责划分说明 |
| 33 | 边界情况 | 离线操作无重试上限与死信 → 9.2.2 offline_data 补充 retry_count/max_retry/sync_status 状态机 |
| 34 | 一致性 | 同步游标按 device 维度但购物车/收藏是用户级数据 → sync_record 表注释明确 user×type 为主、device 仅用于本机数据 |
| 35 | 合理性 | 客服接口与前端命名不一致（单复数混用、缺 queue/history/rate）→ 10.1.1、10.2.1 补充并对齐 |
| 36 | 边界情况 | 客服 WebSocket 缺鉴权/去重/历史漫游/附件 → 10.1.2 补充五项要求，含订单卡片快照 |
| 37 | 边界情况 | 崩溃聚合无唯一键（并发上报产生重复行）、符号文件未定义（PC 用 PDB 非 mapping）→ 11.1/11.2 补充唯一键 upsert、PDB/dSYM、隐私脱敏与 90 天清理 |
| 38 | 一致性 | BFF 聚合接口用 POST 但语义是只读查询（无法走缓存）→ 12.1.1 改为 GET 并说明写操作不走 BFF |
| 39 | 性能 | BFF 单服务 100ms 超时过紧 → 12.1.2 改为分级超时（核心 200ms / 非核心 100ms / 总 800ms） |
| 40 | 性能 | 监控缺业务指标 → 12.3.4 补充下单/支付成功率、更新成功率、同步冲突率 |
| 41 | 一致性 | API 前缀 /api 与 /api/v1 混用、鉴权白名单未定义 → 16.1 明确前缀规则、资源命名规范与免鉴权白名单 |
| 42 | 完整性 | 错误码缺售后/同步/更新/客服/设备模块 → 16.2 补充 4004、5004、6xxx、7xxx、8xxx、9xxx 错误码 |
| 43 | 一致性 | 前后端 20+ 接口路径不一致未集中说明 → 新增 16.2 前端契约对齐表 |
| 44 | 合规 | 设备硬件指纹涉及个人信息、Device Token 永久有效 → 2.3.1 补充合规说明与重置入口；2.2.1 改为 90 天滑动续期 |
| 45 | 边界情况 | Refresh Token 轮换「旧 Token 立即失效」导致并发刷新失败、无泄露检测 → 2.2.3 补充 60 秒宽限期、Token 家族复用检测、多账户隔离 |
| 46 | 完整性 | 登录方式表列了扫码/第三方/短信登录但无接口 → 新增 2.1.3 节，含扫码登录安全要求、登录风控、密码错误锁定 |
| 47 | 可实施性 | RSA 加密密码未定义公钥下发 → 2.1.2 补充 public-key 接口与 key_id 轮换 |
| 48 | 边界情况 | 单 IP 限流在 NAT 下误伤 → 2.3.4 补充 IP+设备指纹二次放行 |
| 49 | 一致性 | 「所有接口需鉴权」与未登录浏览/自动更新场景矛盾 → 2.3.4 定义免鉴权白名单，16.1 引用 |

---

## 跨架构裁决回写记录

> 依据：`architecture/00-conflict-register.md` 第四节收敛裁决，将已裁决结论回写至本计划。

### v1.2 → v1.3

- [R1] 裁决C7（支付超时关单）→ 4.1 下单流程第 6 步：由「15分钟未支付自动释放」改为**以订单表 `pay_deadline` 字段为唯一事实来源**——下单时写入付款截止时间，延迟队列（RocketMQ/RabbitMQ 延迟消息）`deliveryTimestamp` 直取该字段，到期关单并释放库存，PC/管理端/各买家端展示同一字段，本端不自定义分钟数；原文「15分钟」标注为待产品联调前书面确认的参数（占位值，不得硬编码）。
- [R2] 裁决C7（实际内容与描述不符处的就地修正）→ 1.3 `order` 表：原文订单表**缺少** `pay_deadline` 字段，无法作为「唯一事实来源」→ 按裁决意图新增 `pay_deadline DATETIME`（付款截止时间）及辅助索引 `idx_pay_deadline(status, pay_deadline)`，支撑「每 5 分钟扫 `pay_deadline < now()`」的漏网兜底任务（对齐 `admin-backend.md` 4.4.4），字段注释对齐 `admin-backend.md` 的 `pay_deadline` 语义。
- [R3] 裁决C7（配套强化）→ 4.2 支付回调：新增「回调与超时关单竞态」说明——`pay_deadline` 延迟关单与支付成功回调并发时以 `version` 乐观锁裁决、只生效一方，已关单后收到成功回调走自动退款，与既有「回调与取消竞态」条款并列。

---

*文档结束*