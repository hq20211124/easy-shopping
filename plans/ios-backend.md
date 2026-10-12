# iOS客户端后端开发计划

> **项目**：大型电商平台 iOS 客户端后端  
> **版本**：v1.4（v1.0→v1.1 评审修订 + v1.2/v1.3 架构裁决回写 + v1.4 实施手册修订，详见文末「审查记录」「跨架构裁决回写记录」「实施手册修订记录」）  
> **日期**：2026-10-11  
> **技术栈**：Java Spring Boot / Go / Node.js + MySQL + Redis + Elasticsearch + RabbitMQ + Kubernetes  
> **容量口径**：**千万 DAU**（DAU 1000 万 / 峰值在线 200 万 / 网关入口 60 万 QPS / 压测目标 75 万 QPS / 应用读 50 万写 8 万 / 订单峰值 5 万笔/s / MySQL 16 库 64 表 / RabbitMQ 不迁移 / Redis 40~60 节点），唯一真值见 `plans/00-架构总纲.md`（原 `architecture/` 12 份亿级文档已删除，历史值见 git 历史 v1）

---

## 目录

- [一、架构与基础设施](#一架构与基础设施)
- [二、认证与授权](#二认证与授权)
- [三、商品服务](#三商品服务)
- [四、订单服务](#四订单服务)
- [五、会员服务](#五会员服务)
- [六、消息服务](#六消息服务)
- [七、支付服务](#七支付服务)
- [八、分享服务](#八分享服务)
- [九、更新服务](#九更新服务)
- [十、客服服务](#十客服服务)
- [十一、性能优化](#十一性能优化)
- [十二、合规服务](#十二合规服务)
- [十三、项目里程碑与排期](#十三项目里程碑与排期)
- [审查记录](#审查记录)
- [跨架构裁决回写记录](#跨架构裁决回写记录)
- [实施手册修订记录](#实施手册修订记录)

---

## 一、架构与基础设施

### 1.1 整体架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                        iOS Client / App Clips                        │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTPS / HTTP/2
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      API Gateway (Kong / APISIX)                    │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌──────────┐ │
│  │ 限流熔断 │ │ 身份认证  │ │ 路由转发  │ │ 灰度路由   │ │ 日志追踪  │ │
│  └─────────┘ └──────────┘ └──────────┘ └───────────┘ └──────────┘ │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
┌───────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  共享核心服务  │    │  移动端特有服务  │    │  支撑服务        │
│  (与浏览器端)  │    │  (iOS Only)     │    │                 │
├───────────────┤    ├─────────────────┤    ├─────────────────┤
│ • 商品服务     │    │ • 设备管理服务   │    │ • 用户服务       │
│ • 订单服务     │    │ • 推送网关       │    │ • 搜索服务       │
│ • 支付服务     │    │ • 版本更新服务   │    │ • 推荐服务       │
│ • 会员服务     │    │ • 分享服务       │    │ • 消息中心       │
│ • 库存服务     │    │ • 深度链接服务   │    │ • 图片服务       │
│ • 物流服务     │    │ • Apple Pay服务  │    │ • 风控服务       │
│ • 促销服务     │    │ • 埋点服务       │    │ • 客服服务       │
│ • 评价服务     │    │ • 合规服务       │    │ • 配置中心       │
└───────┬───────┘    └────────┬────────┘    └────────┬────────┘
        │                     │                      │
        └─────────────────────┼──────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       基础设施层                                     │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────────────┐   │
│  │ MySQL  │ │ Redis  │ │RabbitMQ│ │  ES    │ │ MinIO / OSS    │   │
│  │ 主从集群│ │ Cluster│ │ 集群   │ │ Cluster│ │ 对象存储        │   │
│  └────────┘ └────────┘ └────────┘ └────────┘ └────────────────┘   │
│  ┌────────────────┐ ┌────────────────┐ ┌───────────────────────┐   │
│  │ Kubernetes     │ │ Jaeger/SkyWalking│ │ Prometheus + Grafana │   │
│  │ 容器编排        │ │ 链路追踪         │ │ 监控告警             │   │
│  └────────────────┘ └────────────────┘ └───────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 与浏览器端共享的服务

以下服务为 iOS 客户端和浏览器端（Web/App）完全共享，通过统一的 API Gateway 对外提供服务：

| 服务 | 职责 | 接口协议 | 技术栈 |
|------|------|----------|--------|
| 商品服务 | 商品列表、详情、分类、品牌 | REST / gRPC | Java Spring Boot |
| 订单服务 | 下单、订单查询、订单状态管理 | REST / gRPC | Java Spring Boot |
| 库存服务 | 库存扣减、回补、预占 | gRPC | Go |
| 支付服务 | 支付下单、支付回调、退款 | REST | Java Spring Boot |
| 物流服务 | 物流查询、物流轨迹 | REST | Go |
| 促销服务 | 优惠券、满减、秒杀 | REST / gRPC | Java Spring Boot |
| 评价服务 | 评价展示、评价提交 | REST | Java Spring Boot |
| 搜索服务 | 商品搜索、搜索建议 | REST / gRPC | Go + Elasticsearch |
| 推荐服务 | 个性化推荐、猜你喜欢 | gRPC | Go + TensorFlow Serving |
| 用户服务 | 用户注册、基本信息 | REST / gRPC | Java Spring Boot |
| 地址服务 | 收货地址 CRUD | REST | Java Spring Boot |
| 文件服务 | 图片/文件上传、CDN分发（头像、评价图、客服附件、海报素材） | REST | Go（与管理端 `admin-file` 同源） |

> **边界说明**：1.1 架构图中的「支撑服务」（用户、搜索、推荐、消息中心、图片、风控、客服、配置中心）与本表同属**共享服务**，只是按职责再分类；真正 iOS 专属的服务见 [1.3](#13-移动端特有服务)。上表任一服务均不区分终端，Web / Android / 小程序 / 管理端复用同一套 API 与数据。

#### 1.2.1 共享服务核心数据表（MySQL）

```sql
-- 商品表
CREATE TABLE `product` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  `spu_code` VARCHAR(64) NOT NULL COMMENT 'SPU编码',
  `title` VARCHAR(256) NOT NULL COMMENT '商品标题',
  `subtitle` VARCHAR(512) DEFAULT '' COMMENT '副标题',
  `category_id` BIGINT NOT NULL COMMENT '分类ID',
  `brand_id` BIGINT NOT NULL DEFAULT 0 COMMENT '品牌ID',
  `main_image` VARCHAR(512) NOT NULL COMMENT '主图URL',
  `images` JSON NOT NULL COMMENT '图片列表',
  `price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '售价',
  `original_price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '原价',
  `stock` INT NOT NULL DEFAULT 0 COMMENT '库存（展示冗余字段，以库存服务为准，由库存事件异步回写）',
  `sales` INT NOT NULL DEFAULT 0 COMMENT '销量',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0下架 1上架 2删除',
  `detail` TEXT COMMENT '详情HTML',
  `spec_json` JSON COMMENT '规格JSON',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_code` (`spu_code`),
  KEY `idx_category` (`category_id`),
  KEY `idx_brand` (`brand_id`),
  KEY `idx_status_sales` (`status`, `sales` DESC),
  FULLTEXT KEY `ft_title` (`title`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- SKU表
CREATE TABLE `product_sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `sku_code` VARCHAR(64) NOT NULL COMMENT 'SKU编码',
  `specs` JSON NOT NULL COMMENT '规格属性',
  `price` DECIMAL(10,2) NOT NULL COMMENT '价格',
  `stock` INT NOT NULL DEFAULT 0,
  `image` VARCHAR(512) DEFAULT '' COMMENT 'SKU图片',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_code` (`sku_code`),
  KEY `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU表';

-- 订单主表（16 库 64 分片，分片键 user_id，终态「分片即实例」横向扩容，见 admin-backend 1.2.1 与 00-架构总纲 4.3.1 裁决①；
-- 月度维度由归档分区承担、不按月分表，见 11.4；主键使用雪花算法生成，分库分表场景下禁止 AUTO_INCREMENT）
CREATE TABLE `order_main` (
  `id` BIGINT UNSIGNED NOT NULL COMMENT '订单ID（雪花算法）',
  `order_no` VARCHAR(32) NOT NULL COMMENT '订单编号（24 位定长编码：时间 14 + 分片槽位 4 + 序列 6，槽位可反解分区，见 00-架构总纲 4.3.1；对外展示）',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `receiver_name` VARCHAR(64) DEFAULT '' COMMENT '收货人姓名快照',
  `receiver_mobile` VARCHAR(20) DEFAULT '' COMMENT '收货人手机号快照',
  `receiver_address` VARCHAR(512) DEFAULT '' COMMENT '收货地址快照（省市区+详细地址）',
  `order_status` TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态机 8 值，与 admin-backend.md 4.3 完全一致：0待付款 1待发货 2待收货 3已完成 4退款中 5已退款 6已取消 7已关闭（禁止两端各自另造枚举）',
  `total_amount` DECIMAL(12,2) NOT NULL COMMENT '订单总金额',
  `pay_amount` DECIMAL(12,2) NOT NULL COMMENT '实付金额',
  `discount_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '优惠金额',
  `freight_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '运费',
  `pay_type` TINYINT DEFAULT 0 COMMENT '支付方式 1支付宝 2微信 3ApplePay 4余额',
  `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间',
  `delivery_time` DATETIME DEFAULT NULL COMMENT '发货时间',
  `receive_time` DATETIME DEFAULT NULL COMMENT '收货时间',
  `cancel_reason` VARCHAR(256) DEFAULT '' COMMENT '取消原因',
  `remark` VARCHAR(512) DEFAULT '' COMMENT '订单备注',
  `source` TINYINT NOT NULL DEFAULT 1 COMMENT '来源 1-iOS 2-Android 3-Web 4-MiniApp 5-AppClip',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_status` (`user_id`, `order_status`),
  KEY `idx_status_paytime` (`order_status`, `pay_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- 订单明细表
CREATE TABLE `order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `product_id` BIGINT NOT NULL,
  `sku_id` BIGINT NOT NULL,
  `sku_name` VARCHAR(256) NOT NULL COMMENT 'SKU名称',
  `sku_image` VARCHAR(512) NOT NULL COMMENT 'SKU图片',
  `sku_specs` JSON COMMENT '规格快照',
  `price` DECIMAL(10,2) NOT NULL COMMENT '下单时单价',
  `quantity` INT NOT NULL COMMENT '数量',
  `total_amount` DECIMAL(12,2) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 用户表
CREATE TABLE `user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL COMMENT '用户名（第三方登录用户生成唯一占位，如 apple_{sub}）',
  `mobile` VARCHAR(64) DEFAULT NULL COMMENT '手机号密文（AES-256-GCM，禁止明文落库）',
  `mobile_hash` CHAR(64) DEFAULT NULL COMMENT '手机号 HMAC-SHA256，用于唯一索引与等值查询；无手机号（如 Apple 登录）为 NULL',
  `email` VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
  `password_hash` VARCHAR(128) DEFAULT '' COMMENT '密码哈希',
  `nickname` VARCHAR(64) DEFAULT '' COMMENT '昵称',
  `avatar` VARCHAR(512) DEFAULT '' COMMENT '头像URL',
  `gender` TINYINT DEFAULT 0 COMMENT '性别 0未知 1男 2女',
  `birthday` DATE DEFAULT NULL,
  `level` TINYINT NOT NULL DEFAULT 1 COMMENT '会员等级',
  `points` INT NOT NULL DEFAULT 0 COMMENT '积分',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0禁用 1正常',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_mobile_hash` (`mobile_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 注：user_address.mobile / order_main.receiver_mobile 等收货信息同为个人敏感信息，
--     需按 12.2 策略以 AES-256-GCM 密文存储，接口输出统一按 12.5 规则脱敏。

-- 收货地址表
CREATE TABLE `user_address` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `name` VARCHAR(64) NOT NULL COMMENT '收货人姓名',
  `mobile` VARCHAR(20) NOT NULL COMMENT '收货人手机号',
  `province` VARCHAR(32) NOT NULL COMMENT '省',
  `city` VARCHAR(32) NOT NULL COMMENT '市',
  `district` VARCHAR(32) NOT NULL COMMENT '区/县',
  `detail` VARCHAR(256) NOT NULL COMMENT '详细地址',
  `zip_code` VARCHAR(10) DEFAULT '' COMMENT '邮编',
  `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认 0否 1是',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址表';
```

#### 1.2.2 与管理端（admin-backend）的共享服务对齐

iOS 后端不重复实现管理端已有的核心能力，统一按 `admin-backend.md` 的服务划分对齐：

| 管理端服务 | 买家端（本文档）对应服务 | 对齐要求 |
|-----------|------------------------|----------|
| `admin-product` / `admin-category` | 商品服务 | 同一份 `product` / `product_sku` 数据，管理端写、买家端读，通过 MQ 事件同步 |
| `core-price`（价格计算引擎） | 促销服务（优惠计算） | 下单金额必须调用共享价格引擎计算，禁止客户端或 iOS 后端自行算价 |
| `core-promotion`（促销规则） | 促销服务 | 优惠券/满减/秒杀规则由管理端配置，买家端只负责领取与核销 |
| `core-user` | 用户服务 | 用户主数据以管理端为准，买家端补充移动端扩展（设备、推送） |
| `admin-inventory` | 库存服务（gRPC） | 库存扣减/回补走库存服务，商品表 `stock` 仅为冗余展示字段 |
| `admin-message` / `admin-cs` | 消息服务 / 客服服务 | 管理端下发营销推送与工单处理，买家端负责 C 端触达与会话 |

**跨端一致性约定**：

1. **事件命名统一**：买家端消费管理端事件时以 `admin-backend.md` 1.4 的路由Key为准（`order.created` / `order.paid` / `order.refunded` / `product.updated` / `product.deleted` / `member.registered`），本文件1.5 的 `order.refund` 统一更名为 `order.refunded`，避免同名不同义。
2. **消息格式统一**：所有事件消息携带 `messageId`，消费端使用 Redis 分布式锁 + 去重表保证幂等（与 `admin_message_dedup` 方案一致）。
3. **数据所有权**：商品/类目/促销/库存配置数据管理端独占写权限；买家端仅允许写业务流水（订单、收藏、评价、领取记录），确保对账与审计可行。
4. **分库分表对齐**：订单表分片键统一为 `user_id`，**16 库 64 分片（分片即实例，可横向扩容）**，全局ID统一使用雪花算法生成（与 `admin-backend.md` 1.2.1 及 `00-架构总纲.md` 4.3.1 终审裁决①一致：**P0 上线即固化 16 库 64 分片的分片规则，逻辑分片先行，终态 64 主分片各占 1 物理实例、加片即扩容**；**原「初期单库 + 按月逻辑分表，5000 万行再分库」旧口径作废**），路由由 ShardingSphere 透明切换，业务代码不感知，见 [11.4](#114-数据库优化)。
5. **品牌标识唯一真值**：BundleID = `com.easyshopping`、URL Scheme = `easyshopping://`、域名 = `easyshopping.com`、Apple Pay Merchant ID = `merchant.com.easyshopping`、AASA appID = `{TEAM_ID}.com.easyshopping`。本文档全部示例以该真值为准（与 `ios-frontend.md` 1.4 `BrandConfig`、16.4 一一对应）；图片类占位域名统一为 `cdn.easyshopping.com`。任何一侧变更必须双侧同步并重新执行 8.2.1 自检。
6. **事件生产/消费关系**：`order.*` / `product.*` / `member.registered` 由管理端在 `admin.order.exchange` / `admin.product.exchange` / `admin.member.exchange` 生产，买家端消费时**绑定管理端交换机**；买家端自有事件（`iap.*`、`cs.*` 等）使用无前缀交换机，避免"同名不同机"导致消息丢失。

### 1.3 移动端特有服务

以下服务为 iOS 客户端专属或移动端优先（其中设备管理、APNs 推送、Apple Pay/IAP、深度链接、版本更新、App Clip 为 iOS 真正专属；客服、埋点、合规为「一套实现多端复用」，同时服务 Web / Android / 小程序，实现归属移动端服务集群维护）：

| 服务 | 职责 | 技术要点 |
|------|------|----------|
| 设备管理服务 | 设备注册、设备信息、设备绑定 | 设备指纹、APNs Token关联 |
| 推送网关 | APNs推送、推送策略、推送统计 | HTTP/2 APNs Provider API、JWT认证 |
| 版本更新服务 | 版本检查、灰度发布、强制更新 | 版本号比对、灰度规则引擎 |
| 分享服务 | 分享链接生成、渠道追踪、海报生成 | 短链、二维码、Universal Link |
| 深度链接服务 | Universal Link处理、App Clip跳转 | apple-app-site-association |
| Apple Pay / IAP服务 | Apple Pay支付下单、验证、回调；App 内购凭证校验 | Payment Session、App Store Server API、服务器端验证 |
| App Clip服务 | App Clip 元数据下发、App Clip→完整App 会话升级 | App Clip Instance API、限时升级令牌 |
| 客服服务 | 在线客服、工单系统（多端复用，与管理端 `admin-cs` 打通） | WebSocket长连接、消息队列 |
| 合规服务 | 隐私政策、数据加密、ATS检查 | 数据脱敏、证书管理 |
| 埋点服务 | 客户端行为数据采集（与 consent 授权联动） | Kafka + Flink实时处理 |

#### 1.3.1 移动端核心数据表

```sql
-- 设备表
CREATE TABLE `device` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `device_id` VARCHAR(64) NOT NULL COMMENT '设备唯一标识（iOS: identifierForVendor）',
  `user_id` BIGINT DEFAULT NULL COMMENT '关联用户ID',
  `platform` VARCHAR(16) NOT NULL DEFAULT 'ios' COMMENT '平台：ios（本服务仅iOS）',
  `device_model` VARCHAR(64) DEFAULT '' COMMENT '设备型号（如iPhone15,2）',
  `os_version` VARCHAR(32) DEFAULT '' COMMENT 'iOS版本',
  `app_version` VARCHAR(32) DEFAULT '' COMMENT 'App版本',
  `push_token` VARCHAR(256) DEFAULT '' COMMENT 'APNs Device Token',
  `push_env` VARCHAR(16) DEFAULT 'production' COMMENT '推送环境 production/development',
  `device_name` VARCHAR(64) DEFAULT '' COMMENT '设备名称',
  `screen_resolution` VARCHAR(32) DEFAULT '' COMMENT '屏幕分辨率',
  `language` VARCHAR(16) DEFAULT 'zh-CN' COMMENT '语言',
  `timezone` VARCHAR(32) DEFAULT 'Asia/Shanghai' COMMENT '时区',
  `locale` VARCHAR(16) DEFAULT 'zh_CN' COMMENT '区域',
  `idfa` VARCHAR(64) DEFAULT '' COMMENT '广告标识符（仅 ATT 授权后采集，未授权上报全零 UUID）',
  `idfv` VARCHAR(64) DEFAULT '' COMMENT 'Vendor标识符',
  `push_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许推送',
  `biometric_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用生物识别',
  `last_active_at` DATETIME DEFAULT NULL COMMENT '最后活跃时间',
  `bound_at` DATETIME DEFAULT NULL COMMENT '绑定时间',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1正常 0解绑',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_id` (`device_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_push_token` (`push_token`(64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备表';

-- 推送记录表
CREATE TABLE `push_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `batch_id` VARCHAR(64) NOT NULL COMMENT '批次ID',
  `device_id` BIGINT NOT NULL COMMENT '设备ID（外键指向 device.id 自增主键，非 device.device_id 字符串）',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `push_type` TINYINT NOT NULL COMMENT '1营销推送 2通知推送 3物流提醒 4订单状态',
  `title` VARCHAR(128) NOT NULL COMMENT '推送标题',
  `content` VARCHAR(512) NOT NULL COMMENT '推送内容',
  `payload` JSON COMMENT '自定义数据',
  `apns_id` VARCHAR(64) DEFAULT '' COMMENT 'APNs返回的ID',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2成功 3失败 4过期',
  `error_code` VARCHAR(32) DEFAULT '' COMMENT 'APNs错误码',
  `error_msg` VARCHAR(256) DEFAULT '' COMMENT '错误信息',
  `sent_at` DATETIME DEFAULT NULL,
  `delivered_at` DATETIME DEFAULT NULL,
  `clicked_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_device` (`device_id`),
  KEY `idx_batch` (`batch_id`),
  KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送记录表';

-- 分享记录表
CREATE TABLE `share_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `share_code` VARCHAR(16) NOT NULL COMMENT '分享码（短链标识）',
  `user_id` BIGINT NOT NULL COMMENT '分享人ID',
  `share_type` TINYINT NOT NULL COMMENT '1商品 2店铺 3活动 4邀请',
  `target_id` VARCHAR(64) NOT NULL COMMENT '目标ID',
  `channel` VARCHAR(32) NOT NULL COMMENT '渠道：wechat/weibo/qq/copy/poster',
  `share_url` VARCHAR(512) NOT NULL COMMENT '分享链接',
  `poster_url` VARCHAR(512) DEFAULT '' COMMENT '海报URL',
  `short_url` VARCHAR(128) DEFAULT '' COMMENT '短链',
  `click_count` INT NOT NULL DEFAULT 0 COMMENT '点击次数',
  `convert_count` INT NOT NULL DEFAULT 0 COMMENT '转化次数',
  `commission_amount` DECIMAL(10,2) DEFAULT 0.00 COMMENT '佣金金额',
  `expire_at` DATETIME DEFAULT NULL COMMENT '过期时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_share_code` (`share_code`),
  KEY `idx_user` (`user_id`),
  KEY `idx_target` (`target_id`),
  KEY `idx_short_url` (`short_url`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享记录表';

-- 版本管理表
CREATE TABLE `app_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `version_code` INT NOT NULL COMMENT '版本号（数字）',
  `version_name` VARCHAR(32) NOT NULL COMMENT '版本名（如3.2.1）',
  `platform` VARCHAR(16) NOT NULL DEFAULT 'ios',
  `min_os_version` VARCHAR(16) DEFAULT '16.0' COMMENT '最低iOS版本要求（与 ios-frontend.md 附录 A.4 决策一致：iOS 16.0+）',
  `update_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1普通更新 2推荐更新 3强制更新',
  `update_log` TEXT COMMENT '更新日志',
  `download_url` VARCHAR(512) NOT NULL COMMENT '下载/跳转链接',
  `app_store_url` VARCHAR(512) DEFAULT '' COMMENT 'App Store链接',
  `force_update` TINYINT NOT NULL DEFAULT 0 COMMENT '是否强制更新',
  `force_below_version` INT DEFAULT 0 COMMENT '低于此版本强制更新',
  `gray_percent` INT NOT NULL DEFAULT 100 COMMENT '灰度比例 0-100',
  `gray_rule` JSON COMMENT '灰度规则（设备/用户/地域）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0待发布 1已发布 2已下线',
  `published_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_platform_version` (`platform`, `version_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App版本管理表';

-- Apple Pay支付记录表
CREATE TABLE `apple_pay_transaction` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `transaction_id` VARCHAR(64) NOT NULL COMMENT 'Apple Pay交易ID',
  `order_id` BIGINT NOT NULL COMMENT '内部订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '金额',
  `currency` VARCHAR(8) NOT NULL DEFAULT 'CNY' COMMENT '货币',
  `payment_data` TEXT COMMENT '支付token（加密存储）',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待验证 1验证中 2成功 3失败 4已退款',
  `verified_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transaction_id` (`transaction_id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Apple Pay交易表';

-- App Clip体验记录表
CREATE TABLE `app_clip_invocation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `invocation_id` VARCHAR(64) NOT NULL COMMENT '调用唯一ID',
  `app_clip_bundle_id` VARCHAR(128) NOT NULL COMMENT 'App Clip Bundle ID',
  `user_id` BIGINT DEFAULT NULL,
  `action` VARCHAR(64) NOT NULL COMMENT '动作类型',
  `payload` JSON COMMENT '携带数据',
  `device_id` VARCHAR(64) DEFAULT '',
  `ip` VARCHAR(64) DEFAULT '',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invocation_id` (`invocation_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App Clip调用记录表';

-- 客服工单表
CREATE TABLE `cs_ticket` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_no` VARCHAR(32) NOT NULL COMMENT '工单编号',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `order_id` BIGINT DEFAULT NULL COMMENT '关联订单ID',
  `category` VARCHAR(32) NOT NULL COMMENT '问题分类',
  `title` VARCHAR(256) NOT NULL COMMENT '标题',
  `content` TEXT NOT NULL COMMENT '内容',
  `images` JSON COMMENT '图片附件',
  `priority` TINYINT NOT NULL DEFAULT 1 COMMENT '优先级 1低 2中 3高 4紧急',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2已回复 3已解决 4已关闭',
  `assigned_to` BIGINT DEFAULT NULL COMMENT '分配给客服ID',
  `satisfaction` TINYINT DEFAULT NULL COMMENT '满意度 1-5',
  `resolved_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单表';

-- 工单回复表
CREATE TABLE `cs_ticket_reply` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_no` VARCHAR(32) NOT NULL,
  `reply_id` VARCHAR(64) NOT NULL COMMENT '回复ID',
  `sender_type` TINYINT NOT NULL COMMENT '1用户 2客服 3系统',
  `sender_id` BIGINT NOT NULL,
  `content` TEXT NOT NULL,
  `images` JSON,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reply_id` (`reply_id`),
  KEY `idx_ticket` (`ticket_no`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单回复表';

-- 客服会话表
CREATE TABLE `cs_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `agent_id` BIGINT DEFAULT NULL COMMENT '客服ID',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1进行中 2已结束',
  `last_message_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `ended_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`session_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话表';

-- 客服消息表
CREATE TABLE `cs_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `msg_id` VARCHAR(64) NOT NULL COMMENT '消息ID（10.2.1 协议层 msg_id，用于去重/重发/已读回执）',
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
  `sender_type` TINYINT NOT NULL COMMENT '1用户 2客服 3系统',
  `sender_id` BIGINT NOT NULL,
  `message_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1文本 2图片 3语音 4商品卡片 5订单卡片',
  `content` TEXT NOT NULL COMMENT '消息内容',
  `extra` JSON COMMENT '扩展数据',
  `read` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已读',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_id` (`msg_id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服消息表';
```

### 1.4 缓存策略

#### 1.4.1 缓存架构

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────┐
│   iOS Client │────▶│  CDN (静态资源)   │     │  API Gateway │
└──────────────┘     └──────────────────┘     └──────┬───────┘
                                                     │
                    ┌────────────────────────────────┘
                    │
          ┌─────────┴─────────┐
          │                   │
          ▼                   ▼
┌─────────────────┐  ┌─────────────────┐
│ L1: 本地缓存     │  │ L2: Redis Cluster │
│ (NSCache/数据库) │  │ (分布式缓存)       │
└─────────────────┘  └────────┬────────┘
                              │
                    ┌─────────┴─────────┐
                    │                   │
                    ▼                   ▼
          ┌─────────────────┐  ┌─────────────────┐
          │  L3: MySQL      │  │  L4: ES         │
          │  (持久化存储)     │  │  (搜索引擎)       │
          └─────────────────┘  └─────────────────┘
```

#### 1.4.2 缓存策略详细设计

| 数据类型 | 缓存位置 | 过期时间 | 更新策略 | 说明 |
|----------|----------|----------|----------|------|
| 商品详情 | Redis + CDN | 30分钟 | 主动失效 + 版本号 | 高频访问，缓存命中率>95% |
| 商品列表 | Redis | 5分钟 | 分页缓存 + 标签失效 | 分类/搜索结果缓存 |
| 用户信息 | Redis | 1小时 | 主动失效 | 用户资料、积分、等级 |
| 购物车 | Redis | 7天 | 实时更新 | 基于用户ID的Hash结构 |
| 库存 | Redis + 本地 | 实时 | Lua原子操作 | 预扣 + 异步落库 |
| 搜索结果 | Redis + ES | 10分钟 | 索引重建时失效 | ES聚合 + Redis缓存结果 |
| 推荐结果 | Redis | 15分钟 | 定时刷新 | 用户画像 + 协同过滤 |
| 配置数据 | Redis + 本地 | 5分钟 | 推送更新 | 开关、AB实验、配置 |
| 推送Token | Redis | 永久 | 更新时覆盖 | 设备→Token映射 |
| 会话数据 | Redis | 30分钟 | 续期 | Token Session管理 |
| 版本信息 | Redis + CDN | 5分钟 | 发布时刷新 | App版本检查 |
| 地址列表 | Redis | 10分钟 | 变更时失效 | 用户地址缓存 |

#### 1.4.3 Redis集群架构

```
                    ┌─────────────┐
                    │ Redis Proxy  │
                    │ (Twemproxy)  │
                    └──────┬──────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │ Master 1 │    │ Master 2 │    │ Master 3 │
    │ +Slave 1 │    │ +Slave 2 │    │ +Slave 3 │
    │ +Slave 1'│    │ +Slave 2'│    │ +Slave 3'│
    └──────────┘    └──────────┘    └──────────┘
    
分片策略：
- Slot 0-5460:   Master 1
- Slot 5461-10922: Master 2  
- Slot 10923-16383: Master 3

> **选型说明（二选一，避免架构冗余）**：
> - 方案A（推荐）：直接使用 **Redis Cluster 原生模式**，客户端（Spring Data Redis / go-redis）开启 Cluster 能力，无需代理层；
> - 方案B：客户端不支持 Cluster 时，使用代理层（Twemproxy / Codis / Redis Cluster Proxy）做分片路由，此时后端节点用单机/主从而非 Cluster。
> - 两种方案不可混用；**Redis Cluster 仅支持 DB 0，原"逻辑 DB 前缀"方案不可行**（架构裁决 C11）→ 改为 "**Key 前缀 + 客户端多集群路由**"（按业务域路由到不同集群/独立实例实现隔离）；Key 规范以 1.4.4 四段式为唯一真值（`plans/00-架构总纲.md` 缓存 Key 统一表——原 `architecture/03-cache.md` §6.3 已删除、见 git 历史 v1，各端不得再自定义 Key 段数），并为秒杀库存等场景保留独立 Redis 实例，避免大促拖垮通用缓存。
```

#### 1.4.4 缓存Key设计规范

```
格式：{业务域}:{模块}:{标识}:{版本}

示例：
- product:detail:{product_id}:v2
- product:category_list:{category_id}:page{page}:v1
- user:profile:{user_id}:v3
- user:addresses:{user_id}:v1
- order:detail:{order_no}:v1
- push:token:{device_id}:v2
- version:latest:{platform}:v1
- search:hot:{keyword}:v1
```

> **唯一真值（架构裁决 C11）**：上述四段式 `{业务域}:{模块}:{标识}:{版本}` 为各端**唯一**缓存 Key 规范（与 `plans/00-架构总纲.md` 缓存 Key 统一表一致——原 `architecture/03-cache.md` §6.3 已删除、见 git 历史 v1；`android-backend.md` 1.5 的三段式作废）；隔离方式为 Key 前缀 + 客户端多集群路由（1.4.3），不得使用逻辑 DB 区分业务。

#### 1.4.5 缓存更新策略

| 场景 | 策略 | 实现 |
|------|------|------|
| 商品更新 | Cache-Aside + 消息通知 | 更新DB后发MQ消息，消费端删除缓存 |
| 库存变动 | Write-Through + Lua | Redis原子操作，异步批量落库 |
| 用户登录 | 主动失效 + 续期 | 删除旧Token缓存，写入新Session |
| 配置变更 | 推送刷新 | 配置中心推送 + Redis Pub/Sub |

#### 1.4.6 缓存防护（穿透 / 击穿 / 雪崩）

| 风险 | 场景 | 防护方案 |
|------|------|----------|
| 缓存穿透 | 恶意请求不存在的商品ID/分享码 | 布隆过滤器（商品ID）+ 空值缓存（TTL 60s）+ 网关按设备限流 |
| 缓存击穿 | 热点商品（秒杀/大促）过期瞬间大量回源 | 互斥锁重建（Redis SETNX）+ 逻辑过期 + 热点key永不过期、后台异步刷新 |
| 缓存雪崩 | 大量key同一时间过期、Redis节点故障 | 过期时间加随机抖动（±10%）+ Redis Cluster 哨兵/副本自动切换 + 多级缓存兜底 |
| 大key/热key | 单个商品详情、首页配置被高频访问 | 生产环境定期大key扫描；热key本地缓存（进程内 LRU，TTL ≤ 5s） |

### 1.5 消息队列设计

#### 1.5.1 RabbitMQ集群架构

```
┌─────────────────────────────────────────────────────┐
│                  RabbitMQ Cluster                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │  Node 1  │──│  Node 2  │──│  Node 3  │  (镜像队列)│
│  │  Disk    │  │  Disk    │  │  RAM     │          │
│  └──────────┘  └──────────┘  └──────────┘          │
└─────────────────────────────────────────────────────┘
```

#### 1.5.2 消息队列Topic设计

| Exchange | 类型 | 路由Key | 用途 | 消费者 |
|----------|------|---------|------|--------|
| `order.exchange` | topic | `order.created` | 订单创建 | 库存扣减、推送通知 |
| `order.exchange` | topic | `order.paid` | 订单支付成功 | 发货触发、积分发放 |
| `order.exchange` | topic | `order.refunded` | 订单退款 | 库存回补、优惠券退回 |
| `order.exchange` | topic | `order.cancelled` | 订单取消（用户取消 / 超时未支付） | 释放锁定库存、退回优惠券/秒杀名额（**与 admin-backend.md 1.4 一致**，v1.1 补齐） |
| `order.exchange` | topic | `order.refund.applied` | 售后申请提交 | 售后工单创建、审核流转、消息通知（**与 admin-backend.md 1.4 一致**） |
| `payment.exchange` | topic | `payment.success` | 支付成功 | 订单状态更新、发票 |
| `payment.exchange` | topic | `payment.failed` | 支付失败 | 通知用户 |
| `push.exchange` | direct | `push.send` | 推送发送 | APNs网关 |
| `push.exchange` | direct | `push.retry` | 推送重试 | APNs重试队列 |
| `logistics.exchange` | topic | `logistics.update` | 物流更新 | 推送通知、消息中心 |
| `product.exchange` | topic | `product.updated` | 商品更新 | 缓存失效、索引更新 |
| `product.exchange` | topic | `product.deleted` | 商品下架/删除 | 缓存失效、索引删除 |
| `user.exchange` | topic | `member.registered` | 用户注册 | 欢迎推送、新人优惠、埋点 |
| `iap.exchange` | topic | `iap.purchased` | IAP购买成功 | 会员开通、积分发放 |
| `iap.exchange` | topic | `iap.refunded` | IAP退款/退款申诉 | 会员回收 |
| `cs.exchange` | direct | `cs.message` | 客服消息 | WebSocket推送 |
| `dead.exchange` | fanout | - | 死信队列 | 人工处理 |

**跨端事件约定**：`order.*` / `product.*` / `member.registered` 的路由Key与 `admin-backend.md` 1.4 完全一致（含 v1.1 新增的 `order.cancelled`、`order.refund.applied`），两端互为生产者/消费者；所有消息统一携带 `messageId`，消费端通过 Redis 分布式锁 + 去重表实现幂等（与 `admin_message_dedup` 同构），失败进入重试队列（指数退避）后转死信；跨端消费绑定管理端交换机（见 [1.2.2](#122-与管理端admin-backend的共享服务对齐) 约定5/6）。

---

## 二、认证与授权

### 2.1 移动端登录

#### 2.1.1 登录方式概览

| 登录方式 | 场景 | 优先级 | 说明 |
|----------|------|--------|------|
| 手机号+验证码 | 首次登录/注册 | 高 | 短信验证码登录，支持一键登录（运营商取号） |
| 账号密码 | 老用户 | 中 | 用户名/手机号+密码 |
| 第三方登录 | 快捷登录 | 中 | 微信、Apple Sign In、QQ、微博 |
| Face ID/Touch ID | 二次验证 | 高 | 生物识别快速登录 |
| 一键登录 | 运营商取号 | 高 | 中国移动/联通/电信一键登录 |
| Token刷新 | 会话续期 | 高 | Refresh Token自动续期 |

#### 2.1.2 手机号验证码登录

**流程：**

```
┌──────┐                              ┌──────────┐         ┌──────────┐
│ iOS  │                              │ Backend  │         │  SMS     │
│Client│                              │          │         │ Provider │
└──┬───┘                              └────┬─────┘         └────┬─────┘
   │                                       │                     │
   │  1. POST /api/v1/sms/send             │                     │
   │  { mobile, scene, captcha_token }    │                     │
   │──────────────────────────────────────▶│                     │
   │                                       │                     │
   │                                       │  2. 发送短信验证码     │
   │                                       │────────────────────▶│
   │                                       │                     │
   │  3. { code_id, expires_in }           │                     │
   │◀──────────────────────────────────────│                     │
   │                                       │                     │
   │  4. POST /auth/login/mobile           │                     │
   │  { mobile, code, device_info }        │                     │
   │──────────────────────────────────────▶│                     │
   │                                       │                     │
   │                                       │  5. 校验验证码        │
   │                                       │  6. 生成Token        │
   │                                       │  7. 绑定设备         │
   │                                       │                     │
   │  8. { access_token, refresh_token,    │                     │
   │      user_info, device_id }           │                     │
   │◀──────────────────────────────────────│                     │
   │                                       │                     │
```

**API设计：**

```
POST /api/v1/sms/send            // 前端契约路径（与 ios-frontend.md 3.1 对齐），兼容别名 /api/v1/auth/send-code
Content-Type: application/json

Request:
{
    "mobile": "13800138000",
    "scene": "login",
    "captcha_token": "xxx",
    "captcha_code": "a3f8"
}

Response (200):
{
    "code": 0,
    "data": {
        "code_id": "sms_20261011_abc123",
        "expires_in": 300,
        "resend_interval": 60
    }
}

---
POST /api/v1/auth/login/phone      // 前端契约路径（兼容别名 /api/v1/auth/login/mobile）
Content-Type: application/json

Request:
{
    "mobile": "13800138000",
    "code": "123456",
    "code_id": "sms_20261011_abc123",
    "device_info": {
        "device_id": "ios-uuid-xxxx",
        "device_model": "iPhone15,2",
        "os_version": "18.0",
        "app_version": "3.2.1",
        "push_token": "apns_token_xxx",
        "idfv": "vendor-id-xxx"
    }
}

Response (200):
{
    "code": 0,
    "data": {
        "user_id": 10001,
        "username": "user_10001",
        "nickname": "电商达人",
        "avatar": "https://cdn.example.com/avatar/10001.jpg",
        "mobile": "138****8000",
        "gender": 1,
        "level": 3,
        "points": 2580,
        "access_token": "eyJhbGciOiJSUzI1NiIs...",
        "refresh_token": "def50200...",
        "expires_in": 7200,
        "refresh_expires_in": 604800,
        "device_id": "ios-uuid-xxxx",
        "is_new_user": false
    }
}
```

**验证码与登录限流策略（网关 + 业务双层）：**

| 场景 | 限制 | 说明 |
|------|------|------|
| 发送验证码 | 同一手机号 1条/60s、5条/小时、10条/天 | 超限返回 `42901`，引导图形验证码 |
| 发送验证码 | 同一设备/IP 20条/小时 | 防短信轰炸与批量注册 |
| 验证码校验 | 同一 `code_id` 连续错误 5次即作废 | 防验证码枚举爆破 |
| 密码登录 | 同一账号 5次失败锁定15分钟 | 锁定期间仅允许验证码登录 |
| 登录类接口 | 单设备 60次/分钟 | 网关级限流 + 风控评分 |

#### 2.1.3 密码登录

```
POST /api/v1/auth/login/password
Content-Type: application/json

Request:
{
    "username": "user_10001",   // 用户名或手机号
    "password": "xxx",
    "device_info": { ... }
}

Response (200): 结构同 2.1.2；对未设置密码的账号返回 40301「请使用验证码登录」
```

> 说明：`password_hash` 仅服务端保存（Argon2id/bcrypt，见 12.2）；验证码注册用户默认无密码，需在「账户安全-设置密码」中主动设置后才能使用密码登录。

#### 2.1.4 第三方登录（微信 / QQ / 微博）

```
POST /api/v1/auth/login/{provider}      // provider ∈ wechat | qq | weibo | apple
Content-Type: application/json

Request:
{
    "provider": "wechat",
    "code": "wx_oauth_code",            // 第三方SDK授权码
    "device_info": { ... }
}

Response (200): 结构同 2.1.2
```

> 说明：服务端必须用授权码在第三方服务端换取 openid（禁止信任客户端上报的身份标识）；首次登录返回的微信昵称/头像需用户确认后入库；解绑第三方账号前须保证账号至少保留一种可用登录方式。

#### 2.1.5 Apple Sign In登录

```
POST /api/v1/auth/login/apple
Content-Type: application/json

Request:
{
    "authorization_code": "c6f8e...",
    "identity_token": "eyJraWQ...",
    "user_identifier": "001234.abc123...",
    "device_info": { ... }
}

Response (200):
{
    "code": 0,
    "data": {
        "user_id": 10001,
        "access_token": "eyJhbGciOiJSUzI1NiIs...",
        "refresh_token": "def50200...",
        "expires_in": 7200,
        "is_new_user": true
    }
}
```

**Apple Sign In 服务端校验要点（必须全部满足才允许签发 Token）：**

| 校验项 | 说明 |
|--------|------|
| `identity_token` 验签 | 使用 Apple JWKS（`https://appleid.apple.com/auth/keys`）按 `kid` 取公钥验签 RS256，本地缓存公钥并定期刷新 |
| `aud` 校验 | 必须等于 App 的 Bundle ID，防止跨 App 重放 |
| `iss` 校验 | 必须等于 `https://appleid.apple.com` |
| `nonce` 绑定 | 客户端 SHA256 后的 nonce 必须与登录时下发值一致，防止令牌重放（见 2.2.4） |
| `authorization_code` 一次性 | 与 App Store Server API 校验后即失效；`sub` 为稳定用户标识，用于账号唯一关联 |
| 隐私说明 | 首次授权才返回 name/email（且可能是 Apple 中转邮箱），后续仅返回 `sub`，资料变更以用户主动更新为准 |

> 依据 App Review Guidelines 4.8：只要 App 提供第三方登录，就必须同时提供 Sign in with Apple。

#### 2.1.6 一键登录（运营商取号）

```
POST /api/v1/auth/login/one-tap
Content-Type: application/json

Request:
{
    "operator": "china_mobile",
    "token": "operator_token_xxx",
    "device_info": { ... }
}

Response (200):
{
    "code": 0,
    "data": {
        "mobile": "13800138000",
        "access_token": "eyJhbGciOiJSUzI1NiIs...",
        "refresh_token": "def50200...",
        "expires_in": 7200,
        "is_new_user": true
    }
}
```

### 2.2 Token管理

#### 2.2.1 Token架构

```
┌─────────────────────────────────────────────────────┐
│                    Token 体系                         │
│                                                     │
│  ┌─────────────┐    ┌──────────────┐               │
│  │ Access Token │    │ Refresh Token │               │
│  │ JWT (2小时)  │    │ Opaque (7天)   │               │
│  └──────┬──────┘    └──────┬───────┘               │
│         │                   │                        │
│  ┌──────┴──────┐    ┌──────┴───────┐               │
│  │ 存储：内存   │    │ 存储：Redis   │               │
│  │ 无状态验证   │    │ 主动管理/吊销  │               │
│  └─────────────┘    └──────────────┘               │
└─────────────────────────────────────────────────────┘
```

#### 2.2.2 JWT Token设计

```json
// Header
{
    "alg": "RS256",
    "typ": "JWT",
    "kid": "key_20261011"
}

// Payload
{
    "sub": "10001",
    "did": "ios-uuid-xxxx",
    "scope": ["read", "write"],
    "platform": "ios",
    "version": 321,
    "iat": 1728633600,
    "exp": 1728640800,
    "jti": "token-unique-id-xxx"
}
```

> **算法与密钥说明**：统一使用 RS256（非 HS256），网关与各服务仅持有公钥即可验签，私钥仅签发服务持有并存放于 KMS；`kid` 支持密钥轮换，网关同时信任新旧两把公钥（重叠期 ≥ access_token 有效期），所有节点接入 NTP 校时，`exp` 校验容忍 ≤ 30s 时钟偏移。
> **与管理端 Token 隔离**：管理端（`admin-backend.md` 2.1）与买家端**各自独立签发**，JWT 强制携带 `aud`（受众）与 `platform` claim（ios / android / web / admin），网关按 `aud` 白名单验签，**严禁跨端复用**（管理端 Token 调用买家端接口一律 401）；两端 Refresh Token 均采用旋转（rotation）+ 旧值黑名单，移动端载体为 Keychain（非 httpOnly Cookie，见 2.2.4），语义与管理端 2.1.2 保持一致。

#### 2.2.3 Token管理API

```
POST /api/v1/auth/token/refresh
Content-Type: application/json

Request:   // Refresh Token 仅放请求体，不使用 Authorization 头，避免与 Access Token 混用
{
    "refresh_token": "def50200...",
    "device_id": "ios-uuid-xxxx"
}

Response (200):
{
    "code": 0,
    "data": {
        "access_token": "eyJhbGciOiJSUzI1NiIs...",
        "refresh_token": "def50200...",
        "expires_in": 7200
    }
}

---
POST /api/v1/auth/token/revoke
Authorization: Bearer {access_token}

Request:
{
    "device_id": "ios-uuid-xxxx",
    "revoke_all": false
}

Response (200):
{
    "code": 0,
    "data": {
        "revoked_devices": 1
    }
}

---
GET /api/v1/auth/token/devices
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "devices": [
            {
                "device_id": "ios-uuid-xxxx",
                "device_model": "iPhone15,2",
                "device_name": "iPhone 15 Pro",
                "os_version": "18.0",
                "app_version": "3.2.1",
                "last_active_at": "2026-10-11T10:30:00Z",
                "bound_at": "2026-01-15T08:00:00Z",
                "is_current": true
            },
            {
                "device_id": "ios-uuid-yyyy",
                "device_model": "iPad13,1",
                "device_name": "iPad Pro",
                "os_version": "17.5",
                "app_version": "3.2.0",
                "last_active_at": "2026-10-10T15:20:00Z",
                "bound_at": "2025-06-20T10:00:00Z",
                "is_current": false
            }
        ]
    }
}
```

#### 2.2.4 Token安全策略

| 策略 | 说明 |
|------|------|
| Token轮换 | Refresh Token每次刷新后立即失效，签发新Token |
| 设备绑定 | Token与设备ID绑定，跨设备使用需重新验证 |
| 异常检测 | 检测到异地登录/多设备异常时要求重新验证 |
| 主动吊销 | 用户改密、账号异常时立即吊销所有Token |
| Token黑名单 | Redis存储已吊销的JWT jti，设TTL=access_token剩余有效期 |
| 传输安全 | 仅HTTPS传输，禁止HTTP携带Token |
| 存储安全 | iOS端Keychain存储（kSecAttrAccessibleAfterFirstUnlock），禁止UserDefaults明文存储 |
| 并发刷新 | 多端并发刷新时，旧 Refresh Token 保留 30s 宽限期并返回同一新 Token，避免互相踢下线 |
| 强下线 | 账号封禁/改密通过 jti 黑名单 + `user_token_version` 版本号双通道实现（Redis 故障时版本号兜底） |
| 验签性能 | RS256 公钥本地缓存验签，jti 黑名单使用本地 LRU + Redis 二级缓存，避免每个请求回源 Redis |

### 2.3 设备绑定

#### 2.3.1 设备绑定流程

```
用户登录 ──▶ 提交device_info ──▶ 后端检查device表
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
              新设备首次登录    已有设备登录      设备数超限
                    │               │               │
                    ▼               ▼               ▼
              创建device记录   更新最后活跃    按策略处理
              绑定到用户       返回成功        (踢出最旧/拒绝)
```

#### 2.3.2 设备管理API

```
POST /api/v1/device/bind
Authorization: Bearer {access_token}
Content-Type: application/json

Request:
{
    "device_id": "ios-uuid-xxxx",
    "device_model": "iPhone15,2",
    "device_name": "iPhone 15 Pro",
    "os_version": "18.0",
    "app_version": "3.2.1",
    "push_token": "apns_token_xxx",
    "push_env": "production",
    "idfv": "vendor-id-xxx",
    "language": "zh-CN",
    "timezone": "Asia/Shanghai",
    "screen_resolution": "2556x1179"
}

Response (200):
{
    "code": 0,
    "data": {
        "device_id": "ios-uuid-xxxx",
        "bound": true,
        "push_enabled": true,
        "biometric_supported": true
    }
}

---
PUT /api/v1/device/push-token
Authorization: Bearer {access_token}

Request:
{
    "device_id": "ios-uuid-xxxx",
    "push_token": "new_apns_token_xxx",
    "push_env": "production"
}

Response (200):
{
    "code": 0,
    "data": { "updated": true }
}

---
DELETE /api/v1/device/{device_id}
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": { "unbound": true }
}

---
# 推送偏好（分类开关、免打扰）统一入口见 6.3.2：PUT /api/v1/user/notification-settings，
# 本服务仅维护设备级总开关 push_enabled（随设备绑定/解绑、Token失效自动置为0），不再单独暴露设备级偏好接口，
# 避免「同一配置两个入口」导致的数据不一致。
```

### 2.4 生物识别（Face ID / Touch ID）

#### 2.4.1 生物识别架构

```
┌─────────────────────────────────────────────────────┐
│                iOS Client (Local Authentication)     │
│                                                     │
│  1. 用户触发Face ID验证                              │
│  2. LocalAuthentication框架验证                      │
│  3. 验证成功后，使用Keychain中的密钥签名挑战码        │
│  4. 将签名结果发送到后端验证                          │
└──────────────────────┬──────────────────────────────┘
                       │ HTTPS
                       ▼
┌─────────────────────────────────────────────────────┐
│                   Backend                            │
│                                                     │
│  1. 生成随机挑战码（nonce）                          │
│  2. 客户端签名后提交                                 │
│  3. 使用预注册的公钥验证签名                          │
│  4. 验证通过 → 返回access_token                      │
└─────────────────────────────────────────────────────┘
```

#### 2.4.2 生物识别API

```
POST /api/v1/auth/biometric/register
Authorization: Bearer {access_token}
Content-Type: application/json

Request:
{
    "device_id": "ios-uuid-xxxx",
    "public_key": "-----BEGIN PUBLIC KEY-----\n...",
    "key_tag": "com.example.biometric.device_xxx",
    "biometric_type": "face_id",
    "device_model": "iPhone15,2"
}

Response (200):
{
    "code": 0,
    "data": {
        "registered": true,
        "credential_id": "bio_xxx"
    }
}

---
POST /api/v1/auth/biometric/challenge
Authorization: Bearer {refresh_token}   // 生物识别多用于会话恢复场景，此时可能无有效 access_token，允许携带 Refresh Token

Request:
{
    "device_id": "ios-uuid-xxxx"
}

Response (200):
{
    "code": 0,
    "data": {
        "challenge": "random-nonce-base64-xxx",
        "expires_in": 60
    }
}

---
POST /api/v1/auth/login/biometric      // 前端契约路径（兼容别名 /api/v1/auth/biometric/verify）
Content-Type: application/json         // 免登录接口：依赖一次性 challenge + 设备公钥凭证，网关按设备限流（10次/分钟）

Request:
{
    "device_id": "ios-uuid-xxxx",
    "credential_id": "bio_xxx",
    "challenge": "random-nonce-base64-xxx",
    "signature": "base64-signature-xxx"
}

Response (200):
{
    "code": 0,
    "data": {
        "verified": true,
        "access_token": "eyJhbGciOiJSUzI1NiIs...",
        "refresh_token": "def50200...",
        "expires_in": 7200
    }
}

Error Response (401):
{
    "code": 40101,
    "message": "Biometric verification failed"
}
```

#### 2.4.3 生物识别安全要点

| 要点 | 说明 |
|------|------|
| 密钥存储 | 私钥存储在Secure Enclave中，永不导出 |
| 挑战-响应 | 每次验证使用一次性nonce，防重放攻击 |
| 公钥注册 | 仅注册公钥到后端，私钥不出设备 |
| 降级策略 | 生物识别失败时降级到密码验证 |
| Keychain保护 | kSecAttrAccessibleWhenUnlockedThisDeviceOnly |
| 生物识别变更 | 检测biometric数据库变更时要求重新注册 |

---

## 三、商品服务

### 3.1 功能点

- 商品列表（分类、品牌、筛选、排序）
- 商品详情（富文本、规格、评价摘要、推荐）
- 商品搜索（关键词、搜索建议、搜索历史、热搜词）
- 商品推荐（猜你喜欢、看了又看、相似商品）
- 商品评价（评分分布、标签筛选、图片评价）
- 商品收藏（收藏、取消收藏、收藏列表）
- 商品分享（生成分享链接/海报）

### 3.2 API设计

#### 3.2.1 商品列表

```
GET /api/v1/products
Authorization: Bearer {access_token}

Parameters:
- category_id: 分类ID (可选)
- brand_id: 品牌ID (可选)
- keyword: 关键词 (可选)
- sort: 排序 (default/price_asc/price_desc/sales/newest)
- price_min: 最低价格 (可选)
- price_max: 最高价格 (可选)
- filters: 筛选属性JSON (可选)
- page: 页码 (default=1)
- page_size: 每页数量 (default=20, max=50)

Response (200):
{
    "code": 0,
    "data": {
        "total": 1520,
        "page": 1,
        "page_size": 20,
        "products": [
            {
                "product_id": 10086,
                "spu_code": "SP20261001",
                "title": "Apple iPhone 15 Pro 256GB 黑色钛金属",
                "main_image": "https://cdn.example.com/img/10086/main.jpg",
                "price": 8999.00,
                "original_price": 9999.00,
                "discount": 9.0,
                "sales": 50000,
                "tags": ["自营", "分期免息"],
                "score": 4.9,
                "review_count": 12800,
                "in_stock": true,
                "promotion_tags": ["限时特惠", "满1000减50"],
                "share_info": {
                    "share_url": "https://easyshopping.com/s/abc123",
                    "poster_url": "https://cdn.example.com/poster/10086.jpg"
                }
            }
        ],
        "facets": {
            "brands": [
                {"id": 1, "name": "Apple", "count": 350}
            ],
            "price_ranges": [
                {"min": 0, "max": 1000, "count": 120},
                {"min": 1000, "max": 5000, "count": 580}
            ],
            "specs": [
                {
                    "name": "颜色",
                    "values": ["黑色钛金属", "白色钛金属", "蓝色钛金属"]
                }
            ]
        }
    }
}
```

#### 3.2.2 商品详情

```
GET /api/v1/products/{product_id}
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "product_id": 10086,
        "spu_code": "SP20261001",
        "title": "Apple iPhone 15 Pro 256GB 黑色钛金属",
        "subtitle": "A17 Pro芯片，钛金属设计，USB-C接口",
        "main_image": "https://cdn.example.com/img/10086/main.jpg",
        "images": [
            "https://cdn.example.com/img/10086/1.jpg",
            "https://cdn.example.com/img/10086/2.jpg"
        ],
        "video": {
            "url": "https://cdn.example.com/video/10086.mp4",
            "cover": "https://cdn.example.com/img/10086/video_cover.jpg",
            "duration": 60
        },
        "price": 8999.00,
        "original_price": 9999.00,
        "category": {
            "id": 100,
            "name": "手机通讯",
            "path": "数码电子>手机通讯>手机"
        },
        "brand": {"id": 1, "name": "Apple"},
        "skus": [
            {
                "sku_id": 20001,
                "specs": {"颜色": "黑色钛金属", "容量": "256GB"},
                "price": 8999.00,
                "stock": 500,
                "image": "https://cdn.example.com/img/10086/black.jpg"
            },
            {
                "sku_id": 20002,
                "specs": {"颜色": "白色钛金属", "容量": "256GB"},
                "price": 8999.00,
                "stock": 320,
                "image": "https://cdn.example.com/img/10086/white.jpg"
            }
        ],
        "detail": "<div>HTML富文本详情...</div>",
        "spec_groups": [
            {
                "name": "颜色",
                "type": "image",
                "values": ["黑色钛金属", "白色钛金属", "蓝色钛金属", "原色钛金属"]
            },
            {
                "name": "容量",
                "type": "text",
                "values": ["128GB", "256GB", "512GB", "1TB"]
            }
        ],
        "promotions": [
            {"type": "full_reduction", "text": "满1000减50"},
            {"type": "installment", "text": "12期免息"}
        ],
        "delivery": {
            "from": "上海",
            "to": "北京",
            "fee": 0.00,
            "estimated": "次日达"
        },
        "review_summary": {
            "score": 4.9,
            "total_count": 12800,
            "tags": [
                {"name": "做工精致", "count": 3500},
                {"name": "运行流畅", "count": 2800},
                {"name": "拍照清晰", "count": 2100}
            ],
            "has_image_count": 5600,
            "has_video_count": 1200
        },
        "recommendations": {
            "similar_products": [],
            "accessories": [],
            "frequently_bought_together": []
        },
        "favored": false,
        "share_url": "https://easyshopping.com/s/abc123",
        "updated_at": "2026-10-10T12:00:00Z"
    }
}
```

#### 3.2.3 商品搜索

```
GET /api/v1/search
Authorization: Bearer {access_token}

Parameters:
- q: 搜索关键词 (必填)
- type: 搜索类型 (product/shop/brand)
- sort: 排序 (default/price_asc/price_desc/sales/newest)
- category_id: 分类筛选 (可选)
- filters: 筛选属性JSON (可选)
- price_min: 最低价格 (可选)
- price_max: 最高价格 (可选)
- page: 页码（兼容参数）
- page_size: 每页数量
- cursor: 游标（推荐，深分页场景传上一页返回的 next_cursor）

Response (200):
{
    "code": 0,
    "data": {
        "query": "iphone 15",
        "corrected_query": "iPhone 15",
        "total": 156,
        "page": 1,
        "next_cursor": "p2k9xQ",   // 末页为 null
        "products": [
            {
                "product_id": 10086,
                "title": "Apple iPhone 15 Pro 256GB",
                "title_highlight": "Apple <em>iPhone 15</em> Pro 256GB",
                "price": 8999.00,
                "main_image": "...",
                "score": 4.9,
                "sales": 50000
            }
        ],
        "search_suggestions": [
            "iphone 15 pro",
            "iphone 15 pro max",
            "iphone 15 手机壳"
        ],
        "related_categories": [
            {"id": 100, "name": "手机通讯", "count": 89}
        ],
        "related_brands": [
            {"id": 1, "name": "Apple", "count": 120}
        ]
    }
}

---
GET /api/v1/search/suggestions
Authorization: Bearer {access_token}

Parameters:
- q: 输入关键词 (必填)
- limit: 返回数量 (default=10)

Response (200):
{
    "code": 0,
    "data": {
        "query": "iph",
        "suggestions": [
            {"text": "iphone 15", "type": "keyword", "highlight": "<em>iph</em>one 15"},
            {"text": "iphone 15 pro", "type": "keyword"},
            {"text": "iphone 14", "type": "keyword"},
            {"text": "iphone 手机壳", "type": "keyword"},
            {"text": "iphonex", "type": "product", "product_id": 10086}
        ]
    }
}

---
GET /api/v1/search/hot
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "hot_keywords": [
            {"keyword": "iPhone 16", "heat": 98, "is_new": true},
            {"keyword": "双十一", "heat": 95, "is_hot": true},
            {"keyword": "戴森吹风机", "heat": 88}
        ],
        "history": [
            {"keyword": "iPhone 15", "searched_at": "2026-10-11T09:00:00Z"},
            {"keyword": "手机壳", "searched_at": "2026-10-10T15:30:00Z"}
        ],
        "recommendations": [
            "笔记本电脑", "耳机", "智能手表"
        ]
    }
}
```

> **与 `ios-frontend.md` 6.3 的待补接口约定**：`GET /api/v1/search/filters`（筛选项聚合）**本期不单独暴露接口**，由本节 `GET /api/v1/search` 响应中的 `facets`（brands / price_ranges / specs）降级实现，前端按需二次请求即可；`GET /api/v1/search/discovery`（搜索发现）改由 `GET /api/v1/config?scene=search_discovery` 的运营配置下发（见 9.5）。若二期确有独立接口需求，须先在两端契约对照表（`ios-frontend.md` 附录 A）登记再实现。

#### 3.2.4 商品推荐

```
GET /api/v1/recommendations
Authorization: Bearer {access_token}

Parameters:
- scene: 推荐场景 (home/you_may_like/look_again/similar/accessory/frequently_bought)
- product_id: 关联商品ID (similar/frequent_bought场景必填)
- page: 页码
- page_size: 每页数量

Response (200):
{
    "code": 0,
    "data": {
        "scene": "home",
        "sections": [
            {
                "title": "猜你喜欢",
                "type": "product_grid",
                "products": [
                    {
                        "product_id": 10086,
                        "title": "...",
                        "main_image": "...",
                        "price": 8999.00,
                        "reason": "根据你的浏览记录推荐",
                        "sales": 50000,
                        "score": 4.9
                    }
                ]
            },
            {
                "title": "看了又看",
                "type": "product_list",
                "products": []
            }
        ],
        "request_id": "rec_req_xxx"
    }
}
```

#### 3.2.5 收藏、评价与秒杀

```
POST /api/v1/favorites                 // 收藏/取消收藏（幂等开关）
Authorization: Bearer {access_token}

Request:
{ "product_id": 10086, "sku_id": 20001 }

Response (200):
{ "code": 0, "data": { "favored": true } }

---
GET /api/v1/favorites                  // 收藏列表（前端契约路径）
Authorization: Bearer {access_token}

Parameters: page / page_size / cursor

Response (200):
{
    "code": 0,
    "data": {
        "total": 12,
        "next_cursor": null,
        "products": [
            { "product_id": 10086, "title": "...", "price": 8999.00,
              "main_image": "...", "in_stock": true, "price_dropped": true,
              "price_at_favor": 9999.00, "favorited_at": "2026-10-01T10:00:00Z" }
        ]
    }
}

---
GET /api/v1/products/{product_id}/reviews      // 评价列表（前端契约路径）
Authorization: Bearer {access_token}

Parameters:
- score: 评分筛选 (all/5/4/3/2/1)
- tag: 标签筛选（如"做工精致"）
- has_image: 仅看有图 (true/false)
- cursor / page_size: 分页（游标优先）

Response (200):
{
    "code": 0,
    "data": {
        "summary": { "score": 4.9, "total_count": 12800, "has_image_count": 5600 },
        "next_cursor": null,
        "reviews": [
            {
                "review_id": "rv_xxx",
                "user_nickname": "张**",          // 匿名评价展示"匿名用户"
                "user_avatar": "...",
                "score": 5,
                "content": "运行流畅，拍照清晰",
                "images": ["https://cdn.example.com/review/1.jpg"],
                "specs": {"颜色": "黑色钛金属", "容量": "256GB"},
                "merchant_reply": "感谢支持",
                "helpful_count": 32,
                "created_at": "2026-10-05T08:00:00Z"
            }
        ]
    }
}

---
POST /api/v1/reviews                   // 提交评价（订单完成后 15 天内可评，一个订单项仅可评价一次）
Authorization: Bearer {access_token}

Request:
{
    "order_id": 90001,
    "sku_id": 20001,
    "score": 5,
    "content": "…",
    "images": ["https://cdn.example.com/review/…"],
    "is_anonymous": false,
    "tags": ["做工精致"]
}

Response (200):
{ "code": 0, "data": { "review_id": "rv_xxx", "points_earned": 20 } }

---
POST /api/v1/orders/{order_id}/items/{item_id}/reviews   // 资源级别名（ios-frontend.md 9.6 契约路径），等价于 POST /api/v1/reviews（item_id = order_item.id）
Authorization: Bearer {access_token}                      // 服务端同一实现，仅路径形态不同；未登录/越权一律按 12.6.2 校验归属

---
GET /api/v1/user/reviews                   // 我的评价列表（ios-frontend.md 9.6 契约路径；含"待评价"订单入口数据）
Authorization: Bearer {access_token}

Parameters:
- status: pending（待评价）/ published / reviewing（机审中）
- page / page_size

Response (200):
{
    "code": 0,
    "data": {
        "pending_count": 2,
        "reviews": [
            {
                "review_id": "rv_xxx",
                "order_id": 90001,
                "product_id": 10086,
                "sku_id": 20001,
                "score": 5,
                "content": "运行流畅，拍照清晰",
                "status": "published",          // reviewing 期间不外显（内容安全，见 12.4）
                "created_at": "2026-10-05T08:00:00Z"
            }
        ]
    }
}

---
GET /api/v1/seckill                    // 秒杀/限时购列表（首页 BFF flash_sale 数据源）
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "activity_id": 501,
        "title": "限时秒杀",
        "start_time": "2026-10-11T10:00:00Z",
        "end_time": "2026-10-11T12:00:00Z",
        "server_time": "2026-10-11T10:32:10Z",   // 客户端倒计时必须以服务端时间为准
        "products": [
            { "product_id": 10086, "sku_id": 20001, "seckill_price": 7999.00,
              "origin_price": 8999.00, "total_stock": 1000, "available_stock": 137,
              "limit_per_user": 1 }
        ]
    }
}
```

> 说明：秒杀库存独立于普通库存服务（独立 Redis 实例 + Lua 原子扣减 + 异步落库），下单时校验 `limit_per_user` 限购；秒杀价不得由客户端计算，服务端二次校验。

### 3.3 数据模型

已在 [1.2.1](#121-共享服务核心数据表mysql) 中定义了 `product` 和 `product_sku` 表。以下是搜索相关表：

```sql
-- 搜索历史表
CREATE TABLE `search_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `keyword` VARCHAR(128) NOT NULL,
  `search_count` INT NOT NULL DEFAULT 1,
  `last_searched_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_keyword` (`user_id`, `keyword`),
  KEY `idx_user_time` (`user_id`, `last_searched_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='搜索历史表';

-- 热搜词表
CREATE TABLE `hot_keyword` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `keyword` VARCHAR(128) NOT NULL,
  `heat` INT NOT NULL DEFAULT 0 COMMENT '热度值',
  `category_id` BIGINT DEFAULT NULL,
  `is_hot` TINYINT NOT NULL DEFAULT 0,
  `is_new` TINYINT NOT NULL DEFAULT 0,
  `sort_order` INT NOT NULL DEFAULT 0,
  `date` DATE NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_keyword_date` (`keyword`, `date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='热搜词表';

-- 收藏表
CREATE TABLE `user_favorite` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `product_id` BIGINT NOT NULL,
  `sku_id` BIGINT DEFAULT NULL,
  `price_at_favor` DECIMAL(10,2) DEFAULT NULL COMMENT '收藏时价格',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
  KEY `idx_user_time` (`user_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收藏表';

-- 商品评价表
CREATE TABLE `product_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `review_id` VARCHAR(32) NOT NULL,
  `product_id` BIGINT NOT NULL,
  `sku_id` BIGINT DEFAULT NULL,
  `order_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `user_nickname` VARCHAR(64) DEFAULT '',
  `user_avatar` VARCHAR(512) DEFAULT '',
  `score` TINYINT NOT NULL COMMENT '评分 1-5',
  `content` TEXT NOT NULL,
  `images` JSON COMMENT '评价图片',
  `video` JSON DEFAULT NULL COMMENT '评价视频',
  `tags` JSON COMMENT '评价标签',
  `is_anonymous` TINYINT NOT NULL DEFAULT 0,
  `is_recommended` TINYINT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待机审 1已发布 2审核未通过（不外显）3人工复审通过',
  `merchant_reply` TEXT DEFAULT NULL,
  `merchant_replied_at` DATETIME DEFAULT NULL,
  `helpful_count` INT NOT NULL DEFAULT 0 COMMENT '有用数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_review_id` (`review_id`),
  KEY `idx_product_score` (`product_id`, `score`),
  KEY `idx_user` (`user_id`),
  KEY `idx_created` (`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品评价表';
```

### 3.4 技术要点

| 要点 | 说明 |
|------|------|
| 搜索方案 | Elasticsearch集群，支持中文分词（IK）、拼音搜索、同义词扩展 |
| 推荐系统 | TensorFlow Serving + 用户画像，实时+离线双路推荐 |
| 缓存策略 | 商品详情Redis缓存30分钟，列表缓存5分钟，搜索结果缓存10分钟 |
| CDN加速 | 图片/视频CDN分发，支持WebP/HEIC格式自适应 |
| 分页优化 | 列表/搜索/消息类接口统一游标分页（`cursor` 入参 + `next_cursor` 出参），`page/page_size` 作为兼容参数保留，避免深度 OFFSET 分页 |
| 富文本安全 | 商品详情XSS过滤，图片懒加载协议 |
| 搜索防抖 | 建议词接口前端防抖300ms，后端结果缓存 |
| 个性化排序 | 推荐结果根据用户画像重排序，千人千面 |

---

## 四、订单服务

### 4.1 功能点

- 购物车管理（添加、修改数量、删除、选择）
- 订单预览（确认订单页：商品、地址、优惠券、金额）
- 下单（库存预占、价格锁定、优惠计算）
- 订单支付（调起支付、支付回调）
- 订单列表（按状态筛选）
- 订单详情（商品、金额、物流、操作按钮）
- 订单操作（取消、确认收货、删除、申请退款）
- 物流跟踪（物流轨迹、地图展示）

### 4.2 API设计

#### 4.2.1 购物车

```
GET /api/v1/cart
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "items": [
            {
                "cart_item_id": 50001,
                "product_id": 10086,
                "sku_id": 20001,
                "title": "Apple iPhone 15 Pro 256GB",
                "sku_specs": {"颜色": "黑色钛金属", "容量": "256GB"},
                "main_image": "https://cdn.example.com/img/10086/main.jpg",
                "price": 8999.00,
                "original_price": 9999.00,
                "quantity": 1,
                "stock": 500,
                "in_stock": true,
                "selected": true,
                "discount_tags": ["满1000减50"]
            }
        ],
        "total_quantity": 3,
        "selected_quantity": 2,
        "max_items": 200,                     // 购物车容量上限由服务端下发（前端禁止硬编码，ios-frontend.md 7.2 约定）
        "total_amount": 17998.00,
        "discount_amount": 100.00,
        "payable_amount": 17898.00
    }
}

---
POST /api/v1/cart                           // 加入购物车（契约路径，ios-frontend.md 7.3 修订后为 POST /api/v1/cart）；兼容别名 POST /api/v1/cart/items
Authorization: Bearer {access_token}

Request:
{
    "product_id": 10086,
    "sku_id": 20001,
    "quantity": 1
}

Response (200):
{
    "code": 0,
    "data": {
        "cart_item_id": 50002,
        "cart_total_quantity": 3
    }
}

---
PUT /api/v1/cart/{cart_item_id}               // 修改数量/选中（契约路径）；兼容别名 PUT /api/v1/cart/items/{cart_item_id}
Authorization: Bearer {access_token}

Request:
{
    "quantity": 2,
    "selected": true
}

Response (200):
{
    "code": 0,
    "data": {
        "cart_item_id": 50001,
        "cart_total_quantity": 4,
        "selected_quantity": 3
    }
}

---
DELETE /api/v1/cart/{cart_item_id}             // 删除购物车项（契约路径）；兼容别名 DELETE /api/v1/cart/items/{cart_item_id}
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "cart_total_quantity": 2
    }
}

---
POST /api/v1/cart/sync                  // 多端购物车合并（登录后/前后台切换时调用；离线期间本地变更全量上报，服务端按 updated_at 合并）
Authorization: Bearer {access_token}

Request:
{
    "client": "ios",
    "client_version": "3.2.1",
    "items": [
        { "product_id": 10086, "sku_id": 20001, "quantity": 1, "updated_at": "2026-10-11T10:00:00Z", "deleted": false }
    ]
}

Response (200):
{
    "code": 0,
    "data": {
        "merged": 3,
        "server_items": [ { "cart_item_id": 50001, "product_id": 10086, "sku_id": 20001, "quantity": 2, "selected": true } ]
    }
}
```

#### 4.2.2 下单

```
POST /api/v1/orders/preview
Authorization: Bearer {access_token}

Request:
{
    "cart_items": [
        {"cart_item_id": 50001, "quantity": 1},
        {"cart_item_id": 50002, "quantity": 2}
    ],
    "address_id": 30001,
    "coupon_id": 20001,
    "remark": "请尽快发货",
    "source": 1
}

Response (200):
{
    "code": 0,
    "data": {
        "items": [
            {
                "product_id": 10086,
                "sku_id": 20001,
                "title": "...",
                "main_image": "...",
                "sku_specs": {},
                "price": 8999.00,
                "quantity": 1,
                "subtotal": 8999.00
            }
        ],
        "address": {
            "id": 30001,
            "name": "张三",
            "mobile": "138****8000",
            "province": "北京市",
            "city": "北京市",
            "district": "朝阳区",
            "detail": "望京SOHO T1"
        },
        "amount_detail": {
            "goods_amount": 17998.00,
            "freight_amount": 0.00,
            "discount_amount": 100.00,
            "coupon_discount": 100.00,
            "payable_amount": 17898.00
        },
        "coupons": [
            {"id": 20001, "title": "满1000减50", "discount": 50.00, "selected": true},
            {"id": 20002, "title": "满2000减200", "discount": 200.00, "selected": false}
        ],
        "delivery": {
            "method": "快递配送",
            "estimated": "明日达"
        },
        "expire_time": 900
    }
}

---
POST /api/v1/orders
Authorization: Bearer {access_token}

Request:
{
    "cart_items": [
        {"cart_item_id": 50001, "quantity": 1}
    ],
    "address_id": 30001,
    "coupon_id": 20001,
    "remark": "请尽快发货",
    "source": 1,
    "biometric_verified": true,
    "payment_method": "apple_pay"
}

Response (200):
{
    "code": 0,
    "data": {
        "order_id": 90001,
        "order_no": "202610110001",
        "pay_amount": 8949.00,
        "expire_time": 900,
        "payment_methods": [
            { "channel": "apple_pay", "enabled": true },
            { "channel": "alipay", "enabled": true },
            { "channel": "wechat", "enabled": true },
            { "channel": "balance", "enabled": false, "reason": "余额不足" }
        ]
    }
}
```

> **支付参数下发约定**：下单接口只返回订单信息与可用支付方式；Apple Pay 的 `paymentRequest`、支付宝/微信的支付参数统一由 `POST /api/v1/payments/{channel}/create`（见 [7.3.1](#731-统一支付入口)）返回，避免同一金额在两处下发导致不一致。
> `biometric_verified` 仅作为客户端展示状态回传，**服务端不得据此跳过风控校验**；是否要求生物识别由服务端风控按金额/设备风险等级决定。

#### 4.2.3 订单列表与详情

```
GET /api/v1/orders
Authorization: Bearer {access_token}

Parameters:
- status: 订单状态（**8 值，与 admin-backend.md 4.3 状态机一致**：0待付款 1待发货 2待收货 3已完成 4退款中 5已退款 6已取消 7已关闭；前端 Tab 枚举必须按此编码一一映射）
- page: 页码
- page_size: 每页数量

Response (200):
{
    "code": 0,
    "data": {
        "total": 50,
        "orders": [
            {
                "order_id": 90001,
                "order_no": "202610110001",
                "status": 2,
                "status_text": "待收货",
                "items": [
                    {
                        "product_id": 10086,
                        "title": "...",
                        "main_image": "...",
                        "sku_specs": {},
                        "quantity": 1,
                        "price": 8999.00
                    }
                ],
                "total_amount": 8949.00,
                "item_count": 1,
                "created_at": "2026-10-11T10:00:00Z",
                "actions": [
                    {"type": "pay", "text": "去付款"},
                    {"type": "logistics", "text": "查看物流"},
                    {"type": "confirm", "text": "确认收货"}
                ]
            }
        ]
    }
}

---
GET /api/v1/orders/{order_id}
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "order_id": 90001,
        "order_no": "202610110001",
        "status": 2,
        "status_text": "待收货",
        "items": [],
        "amount_detail": {
            "goods_amount": 8999.00,
            "freight_amount": 0.00,
            "discount_amount": 50.00,
            "payable_amount": 8949.00,
            "pay_amount": 8949.00
        },
        "address": {},
        "delivery": {
            "company": "顺丰速运",
            "tracking_no": "SF1234567890",
            "status": "已发出"
        },
        "timeline": [
            {"status": "已下单", "time": "2026-10-11T10:00:00Z"},
            {"status": "已支付", "time": "2026-10-11T10:01:00Z"},
            {"status": "已发货", "time": "2026-10-11T14:00:00Z"}
        ],
        "payment_status": "paid",        // pending_payment / paid / refunding / refunded / closed；供支付结果页轮询（ios-frontend.md 8.4 PayStatusPoller）判断，前端不得用数字 status 二次推断支付态
        "actions": [
            {"type": "logistics", "text": "查看物流"},
            {"type": "confirm", "text": "确认收货"}
        ]
    }
}
```

#### 4.2.4 物流跟踪

```
GET /api/v1/orders/{order_id}/logistics
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "delivery_company": "顺丰速运",
        "tracking_no": "SF1234567890",
        "status": "运输中",
        "estimated_delivery": "2026-10-12",
        "map_url": "https://map.example.com/track/SF1234567890",
        "timeline": [
            {
                "time": "2026-10-11T18:00:00Z",
                "status": "已揽收",
                "location": "上海",
                "description": "快件已到达上海转运中心"
            },
            {
                "time": "2026-10-11T22:00:00Z",
                "status": "运输中",
                "location": "上海",
                "description": "快件离开上海转运中心，发往北京"
            },
            {
                "time": "2026-10-12T06:00:00Z",
                "status": "运输中",
                "location": "北京",
                "description": "快件已到达北京分拣中心"
            }
        ]
    }
}
```

---

#### 4.2.5 订单操作与售后

```
POST /api/v1/orders/{order_id}/confirm     // 确认收货（前端契约路径）
Authorization: Bearer {access_token}

Request: {}                                  // 服务端校验订单处于「待收货」状态且非自动确认超时

Response (200):
{
    "code": 0,
    "data": { "order_id": 90001, "status": 3, "status_text": "已完成", "points_earned": 89 }
}

---
POST /api/v1/orders/{order_id}/cancel      // 取消订单（待付款可直接取消；已付款走自动退款流程）
Authorization: Bearer {access_token}

Request: { "reason": "不想要了", "remark": "" }

Response (200):
{
    "code": 0,
    "data": { "order_id": 90001, "status": 6, "status_text": "已取消", "refund_no": "RF2026110002" }   // status=6 已取消（8 值枚举见 4.2.3）；已付款时返回 refund_no
}

---
POST /api/v1/refunds                        // 申请退款/退货（前端契约路径）
Authorization: Bearer {access_token}

Request:
{
    "order_id": 90001,
    "item_ids": [50001],                    // 为空表示整单退款
    "type": "refund_only",                  // refund_only 仅退款 / return_goods 退货退款
    "reason": "商品破损",
    "description": "外包装破损，商品划痕",
    "images": ["https://cdn.example.com/refund/1.jpg"],
    "return_address": null                  // type=return_goods 时由服务端返回商家寄回地址
}

Response (200):
{
    "code": 0,
    "data": {
        "refund_no": "RF2026110001",
        "status": "pending_review",
        "return_address": { "name": "售后仓", "mobile": "021****1234", "address": "上海市青浦区****" },
        "estimated_days": 3
    }
}

---
GET /api/v1/refunds/{refund_no}             // 售后进度（前端契约路径 GET /api/v1/refunds/{id}）
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "refund_no": "RF2026110001",
        "status": "returning",              // pending_review / approved / returning / refunding / completed / rejected
        "timeline": [
            { "status": "已提交申请", "time": "2026-10-12T10:00:00Z" },
            { "status": "商家已同意", "time": "2026-10-12T14:00:00Z" }
        ],
        "tracking_no": "SF0987654321"
    }
}
```

### 4.3 数据模型

订单核心表已在 [1.2.1](#121-共享服务核心数据表mysql) 中定义。补充购物车和物流表：

```sql
-- 购物车表
CREATE TABLE `shopping_cart` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `cart_item_id` BIGINT NOT NULL COMMENT '购物车项ID（对外展示）',
  `user_id` BIGINT NOT NULL,
  `product_id` BIGINT NOT NULL,
  `sku_id` BIGINT NOT NULL,
  `quantity` INT NOT NULL DEFAULT 1,
  `selected` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product_sku` (`user_id`, `product_id`, `sku_id`),
  UNIQUE KEY `uk_cart_item_id` (`cart_item_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';

-- 物流轨迹表
CREATE TABLE `logistics_trace` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL,
  `tracking_no` VARCHAR(64) NOT NULL,
  `company_code` VARCHAR(16) NOT NULL,
  `company_name` VARCHAR(32) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `description` VARCHAR(256) NOT NULL,
  `location` VARCHAR(128) DEFAULT '',
  `trace_time` DATETIME NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_tracking_no` (`tracking_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹表';

-- 退款记录表
CREATE TABLE `refund_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `refund_no` VARCHAR(32) NOT NULL,
  `order_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `item_id` BIGINT DEFAULT NULL COMMENT '指定退款商品，NULL为整单退款',
  `refund_type` TINYINT NOT NULL COMMENT '1全额退款 2部分退款',
  `amount` DECIMAL(12,2) NOT NULL,
  `reason` VARCHAR(256) NOT NULL,
  `description` TEXT,
  `images` JSON,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待审核 1审核通过 2审核拒绝 3已退款',
  `audit_remark` VARCHAR(256) DEFAULT '',
  `audited_at` DATETIME DEFAULT NULL,
  `refunded_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款记录表';

-- 订单状态流转日志表（订单详情 timeline 数据源，同时用于对账与客服排查）
CREATE TABLE `order_status_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL,
  `order_no` VARCHAR(32) NOT NULL,
  `from_status` TINYINT NOT NULL DEFAULT 0,
  `to_status` TINYINT NOT NULL,
  `operator_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1用户 2商家 3系统 4客服',
  `operator_id` BIGINT DEFAULT NULL,
  `reason` VARCHAR(256) DEFAULT '',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态流转日志表';
```

### 4.4 技术要点

| 要点 | 说明 |
|------|------|
| 库存扣减 | Redis Lua脚本原子预扣 + 异步落库，定时任务处理超时未支付订单回补 |
| 订单号生成 | 雪花算法（Snowflake），保证全局唯一、趋势递增 |
| 分布式事务 | 下单流程使用Saga模式，各服务间通过MQ保证最终一致性 |
| 幂等设计 | 下单接口幂等（基于用户ID+设备ID+商品ID+SKU ID+时间窗口生成幂等键），防止同一用户重复提交 |
| 价格锁定 | 预览时锁定价格，锁定时长与 `pay_deadline` 同源（**不得早于支付截止时间**，避免支付中途价格失效）；支付时二次校验（具体分钟数同为待确认的产品参数，见下方「支付超时」行） |
| 订单状态机 | 状态流转使用状态机模式，防止非法状态变更 |
| 物流对接 | 快递鸟/快递100等第三方物流API，统一封装适配 |
| 支付超时 | 延迟消息（原 RabbitMQ TTL 方案）的 `deliveryTimestamp` **直取订单表 `pay_deadline` 字段（唯一事实来源）**，到期自动取消并回补库存/退券；各端倒计时与支付页展示读同一字段，**不自定义分钟数**（原文"15 分钟"标注为**待确认的产品参数**——分钟数建议 30 分钟，由产品联调前书面确认后回写三端计划，架构裁决 C7）；订单预览/下单响应中的 `expire_time` 由 `pay_deadline` 派生（返回剩余秒数，示例值 900 仅为占位），与关单任务同源，避免"展示 15 分钟、关单另一套时间"的不一致 |
| 统一算价 | 预览/下单金额一律调用共享价格引擎（`core-price`）计算，客户端只展示不参与计算；支付回调时按支付渠道实付金额二次校验，金额不符直接挂起并告警 |
| 状态日志 | 每次状态变更写入 `order_status_log`，详情页 timeline 只读日志，不做状态推算 |
| 越权防护 | 订单/售后/物流查询一律校验资源归属 `user_id`，禁止仅凭 `order_no` / `order_id` 跨用户访问（防 IDOR） |
| 乐观锁 | 订单状态更新使用 `version` 乐观锁 + 状态机合法流转校验，防止并发双写（与管理端一致） |
| 状态机取值 | 订单状态统一为 8 值枚举（0待付款 1待发货 2待收货 3已完成 4退款中 5已退款 6已取消 7已关闭），与 `admin-backend.md` 4.3 状态机一一对应；所有状态变更必须经状态机 `transition()` 并写 `order_status_log`，买家端不得直接改库 |
| 幂等键 | 写接口（下单/领券/退款申请）统一携带 `Idempotency-Key` 请求头，与 `admin-backend.md` 10.1.1 幂等规范一致；服务端以 `user_id + idempotency_key` 唯一索引去重，超时重试可安全重放；未携带时按上行业务幂等键兜底 |

---

## 五、会员服务

### 5.1 功能点

- 个人信息管理（昵称、头像、性别、生日）
- 收货地址管理（CRUD、默认地址、地址解析）
- 收藏管理（商品收藏、店铺收藏、收藏列表）
- 积分体系（积分获取、积分明细、积分兑换）
- 会员等级（等级权益、成长值、等级历史）
- 优惠券（领取、使用、过期提醒）
- 账户安全（修改密码、绑定手机、登录设备管理）

### 5.2 API设计

#### 5.2.1 个人信息

```
GET /api/v1/user/profile
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "user_id": 10001,
        "username": "user_10001",
        "nickname": "电商达人",
        "avatar": "https://cdn.example.com/avatar/10001.jpg",
        "mobile": "138****8000",
        "email": "user@example.com",
        "gender": 1,
        "birthday": "1990-05-20",
        "level": 3,
        "level_name": "金牌会员",
        "points": 2580,
        "growth_value": 3580,
        "next_level_need": 5000,
        "register_date": "2023-01-15",
        "biometric_enabled": true
    }
}

---
PUT /api/v1/user/profile
Authorization: Bearer {access_token}

Request:
{
    "nickname": "新昵称",
    "gender": 1,
    "birthday": "1990-05-20"
}

Response (200):
{
    "code": 0,
    "data": {
        "updated": true
    }
}

---
POST /api/v1/user/avatar
Authorization: Bearer {access_token}
Content-Type: multipart/form-data

Request:
- file: 图片文件

Response (200):
{
    "code": 0,
    "data": {
        "avatar_url": "https://cdn.example.com/avatar/10001_new.jpg"
    }
}
```

#### 5.2.2 地址管理

```
GET /api/v1/user/addresses              // 契约路径（ios-frontend.md 10.2 已修正为 /user/addresses）；兼容别名 GET /api/v1/addresses
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "addresses": [
            {
                "id": 30001,
                "name": "张三",
                "mobile": "138****8000",
                "province": "北京市",
                "city": "北京市",
                "district": "朝阳区",
                "detail": "望京SOHO T1 1201室",
                "zip_code": "100102",
                "is_default": true,
                "latitude": 39.9990,
                "longitude": 116.4810
            }
        ]
    }
}

---
POST /api/v1/user/addresses              // 契约路径（ios-frontend.md 10.2）；兼容别名 POST /api/v1/addresses
Authorization: Bearer {access_token}

Request:
{
    "name": "李四",
    "mobile": "13900139000",
    "province": "上海市",
    "city": "上海市",
    "district": "浦东新区",
    "detail": "张江高科技园区博云路2号",
    "zip_code": "201203",
    "is_default": false,
    "latitude": 31.2030,
    "longitude": 121.6000
}

Response (200):
{
    "code": 0,
    "data": {
        "address_id": 30002
    }
}

---
PUT /api/v1/user/addresses/{address_id}      // 契约路径（ios-frontend.md 10.2）；兼容别名 PUT /api/v1/addresses/{address_id}
Authorization: Bearer {access_token}

Request: 同上

---
DELETE /api/v1/user/addresses/{address_id}   // 契约路径（ios-frontend.md 10.2）；兼容别名 DELETE /api/v1/addresses/{address_id}
Authorization: Bearer {access_token}

---
POST /api/v1/user/addresses/parse            // 契约路径（ios-frontend.md 10.2 地址粘贴识别）；兼容别名 POST /api/v1/addresses/parse
Authorization: Bearer {access_token}

Request:
{
    "text": "张三 13800138000 北京市朝阳区望京SOHO T1 1201室"
}

Response (200):
{
    "code": 0,
    "data": {
        "name": "张三",
        "mobile": "13800138000",
        "province": "北京市",
        "city": "北京市",
        "district": "朝阳区",
        "detail": "望京SOHO T1 1201室"
    }
}
```

#### 5.2.3 积分体系

```
GET /api/v1/user/points                      // 积分余额（契约路径，ios-frontend.md 10.x）；兼容别名 GET /api/v1/points/balance
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "points": 2580,
        "total_earned": 5000,
        "total_spent": 2420,
        "expiring_soon": 300,
        "expiring_date": "2026-12-31"
    }
}

---
GET /api/v1/user/points/history              // 积分明细（契约路径，ios-frontend.md 10.x）；兼容别名 GET /api/v1/points/transactions
Authorization: Bearer {access_token}

Parameters:
- type: 类型 (all/earn/spend)
- page: 页码

Response (200):
{
    "code": 0,
    "data": {
        "total": 100,
        "records": [
            {
                "id": 1,
                "type": "earn",
                "points": 100,
                "description": "每日签到",
                "balance_after": 2580,
                "created_at": "2026-10-11T08:00:00Z"
            },
            {
                "id": 2,
                "type": "spend",
                "points": -500,
                "description": "兑换优惠券",
                "balance_after": 2480,
                "created_at": "2026-10-10T15:00:00Z"
            }
        ]
    }
}

---
POST /api/v1/user/points/check-in
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "checked_in": true,
        "points_earned": 10,
        "consecutive_days": 7,
        "bonus_earned": 50,
        "total_points": 2640
    }
}
```

#### 5.2.4 优惠券

```
GET /api/v1/user/coupons                     // 契约路径（ios-frontend.md 10.x 已修正为 /user/coupons）；兼容别名 GET /api/v1/coupons
Authorization: Bearer {access_token}

Parameters:
- status: 状态 (unused/used/expired/available；available 结合 order_amount/product_ids 做价格引擎试算)
- order_amount / product_ids: 仅 status=available 时传入（结算页可用券，即 5.2.4 第三段接口的查询式写法）
- page: 页码

Response (200):
{
    "code": 0,
    "data": {
        "coupons": [
            {
                "coupon_id": 20001,
                "title": "满1000减50",
                "type": "full_reduction",
                "threshold": 1000.00,
                "discount": 50.00,
                "status": "unused",
                "valid_from": "2026-10-01",
                "valid_to": "2026-10-31",
                "scope": "全品类",
                "received_at": "2026-10-01T00:00:00Z"
            }
        ]
    }
}

---
POST /api/v1/user/coupons/{coupon_id}/claim  // 领券（契约路径，兼容别名 POST /api/v1/coupons/{coupon_id}/claim）
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "claimed": true
    }
}

---
GET /api/v1/user/coupons/available           // 结算页可用优惠券（契约路径，兼容别名 GET /api/v1/coupons/available；由价格引擎 core-price 按订单金额试算）
Authorization: Bearer {access_token}

Parameters:
- order_amount: 17898.00
- product_ids: 10086,10087

Response (200):
{
    "code": 0,
    "data": {
        "coupons": [
            { "coupon_id": 20002, "title": "满2000减200", "discount": 200.00, "best": true },
            { "coupon_id": 20001, "title": "满1000减50", "discount": 50.00, "best": false }
        ],
        "best_coupon_id": 20002,
        "unavailable": [
            { "coupon_id": 20003, "reason": "未达使用门槛" }
        ]
    }
}
```

#### 5.2.5 账户安全

```
POST /api/v1/user/security/set-password        // 设置/修改密码（已设置过密码需校验旧密码）
Authorization: Bearer {access_token}

Request: { "old_password": "xxx", "new_password": "xxx" }   // new_password 满足复杂度策略

Response (200):
{ "code": 0, "data": { "updated": true, "revoked_all_tokens": true } }   // 修改成功后强制全端重新登录

---
POST /api/v1/user/security/bind-mobile         // 绑定/换绑手机号（换绑需新号码短信验证码 + 旧方式二次验证）
Authorization: Bearer {access_token}

Request: { "mobile": "13900139000", "code": "123456", "code_id": "sms_xxx" }

Response (200):
{ "code": 0, "data": { "bound": true } }

---
GET /api/v1/user/security/devices             // 登录设备列表（与 2.2.3 /auth/token/devices 同源，前端入口别名）
Authorization: Bearer {access_token}

Response (200): 同 2.2.3

---
POST /api/v1/user/account/cancel              // 账号注销（与 12.1.2 注销流程同源；提交后进入 7 天冷静期，期间登录可撤销）
Authorization: Bearer {access_token}

Request: { "reason": "不再使用", "verification_code": "123456" }

Response (200):
{ "code": 0, "data": { "deletion_id": "del_xxx", "status": "cooling_off", "completed_at": "2026-10-18T00:00:00Z" } }
```

> 说明：设置密码、换绑手机号、注销账户均需二次验证（短信验证码 / 生物识别）并触发账户安全推送（6.2.2）；注销冷静期内产生的订单/售后数据按合规要求保留，其余数据按 12.1.2 异步删除。

### 5.3 数据模型

```sql
-- 积分明细表
CREATE TABLE `points_transaction` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `type` TINYINT NOT NULL COMMENT '1获得 2消费',
  `points` INT NOT NULL COMMENT '变动积分（获得为正，消费为负）',
  `balance_after` INT NOT NULL COMMENT '变动后余额',
  `description` VARCHAR(256) NOT NULL,
  `source_type` VARCHAR(32) DEFAULT '' COMMENT '来源：order/checkin/exchange',
  `source_id` BIGINT DEFAULT NULL COMMENT '来源ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_created` (`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分明细表';

-- 优惠券模板表
CREATE TABLE `coupon_template` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(128) NOT NULL,
  `type` TINYINT NOT NULL COMMENT '1满减 2折扣 3无门槛',
  `threshold` DECIMAL(10,2) DEFAULT 0.00,
  `discount` DECIMAL(10,2) NOT NULL,
  `max_discount` DECIMAL(10,2) DEFAULT NULL COMMENT '折扣券最大优惠',
  `scope` TINYINT NOT NULL DEFAULT 1 COMMENT '1全品类 2指定分类 3指定商品',
  `scope_ids` JSON,
  `total_count` INT NOT NULL,
  `claimed_count` INT NOT NULL DEFAULT 0,
  `per_user_limit` INT NOT NULL DEFAULT 1,
  `valid_days` INT DEFAULT 30 COMMENT '领取后有效天数',
  `status` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板表';

-- 用户优惠券表
CREATE TABLE `user_coupon` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coupon_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `template_id` BIGINT NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未使用 1已使用 2已过期',
  `order_id` BIGINT DEFAULT NULL,
  `valid_from` DATETIME NOT NULL,
  `valid_to` DATETIME NOT NULL,
  `used_at` DATETIME DEFAULT NULL,
  `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_id` (`coupon_id`),
  KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券表';

-- 会员等级表
CREATE TABLE `member_level` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `level` TINYINT NOT NULL,
  `name` VARCHAR(32) NOT NULL,
  `min_growth` INT NOT NULL,
  `max_growth` INT DEFAULT NULL,
  `discount_rate` DECIMAL(3,2) DEFAULT 1.00,
  `benefits` JSON COMMENT '权益列表',
  `icon` VARCHAR(256) DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_level` (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员等级表';
```

### 5.4 技术要点

| 要点 | 说明 |
|------|------|
| 地址解析 | 集成NLP地址解析服务，从文本中提取省市区和详细地址 |
| 积分一致性 | 积分变动使用Redis事务 + DB双写，定时对账，差异自动告警 |
| 签到防刷 | 基于设备ID+IP+行为特征多维限流，防脚本刷签到 |
| 等级计算 | 异步计算成长值变更，等级变更触发推送通知，降级时保留原等级 |
| 优惠券防超发 | Redis原子计数器 + DB乐观锁双重保障；过期优惠券由定时任务批量扫描置为已过期，并触发推送提醒 |

---

## 六、消息服务

### 6.1 功能点

- APNs推送（订单状态变更、物流提醒、促销活动、客服回复）
- 系统通知（站内信、公告、系统消息）
- 消息中心（消息列表、未读计数、消息已读）
- 推送管理（推送偏好、免打扰时段、推送开关）
- 推送统计（送达率、点击率、转化漏斗）

### 6.2 APNs推送架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        APNs 推送架构                                  │
│                                                                     │
│  ┌──────────┐    ┌──────────┐    ┌───────────┐    ┌────────────┐ │
│  │ Business │───▶│ RabbitMQ │───▶│ Push      │───▶│ Apple APNs │ │
│  │ Service  │    │ Queue    │    │ Gateway   │    │ Server     │ │
│  └──────────┘    └──────────┘    └───────────┘    └────────────┘ │
│                       │                │                          │
│                       │                ▼                          │
│                       │         ┌──────────┐                      │
│                       │         │ Redis    │                      │
│                       │         │ Token    │                      │
│                       │         │ Cache    │                      │
│                       │         └──────────┘                      │
│                       │                                           │
│                       ▼                                           │
│                ┌──────────┐                                       │
│                │ Push     │                                       │
│                │ Record   │                                       │
│                │ (MySQL)  │                                       │
│                └──────────┘                                       │
└─────────────────────────────────────────────────────────────────────┘
```

#### 6.2.1 APNs推送网关设计

```go
// APNs推送网关核心逻辑（Go）

// APNs HTTP/2 Provider API
type APNsConfig struct {
    BundleID     string        // com.easyshopping（品牌唯一真值，见 1.2.2 约定5）
    TeamID       string        // {TEAM_ID}（App Store Connect 实际 Team ID）
    KeyID        string        // DEF123GHIJ
    Topic        string        // com.easyshopping，APNs 推送目标（与 BundleID 一致；App Clip 若启用则为 com.easyshopping.Clip）
    KeyPath      string        // AuthKey_xxx.p8
    Production   bool          // 生产/开发环境
    Timeout      time.Duration // HTTP超时
    MaxConn      int           // HTTP/2最大连接数
}

// 推送载荷
type PushPayload struct {
    // 注意：APNs 对所有 iOS 版本的普通推送 payload 上限均为 4KB（4096 字节，含自定义字段），
    // VoIP Push（仅限 CallKit 类用途）为 5KB；超限 APNs 返回 413 PayloadTooLarge，整条消息不会送达。
    APS APS `json:"aps"`
}

type APS struct {
    Alert            Alert  `json:"alert,omitempty"`
    Badge            int    `json:"badge,omitempty"`
    Sound            string `json:"sound,omitempty"`
    ContentAvailable int    `json:"content-available,omitempty"`
    Category         string `json:"category,omitempty"`
    ThreadID         string `json:"thread-id,omitempty"`
}

type Alert struct {
    Title    string   `json:"title,omitempty"`
    Subtitle string   `json:"subtitle,omitempty"`
    Body     string   `json:"body,omitempty"`
    LaunchImage string `json:"launch-image,omitempty"`
}
```

#### 6.2.2 推送类型与场景

| 推送类型 | 触发场景 | 优先级 | 展示方式 | 说明 |
|----------|----------|--------|----------|------|
| 订单状态推送 | 支付成功/发货/确认收货 | 高 | 通知栏+应用内 | 实时推送 |
| 物流提醒 | 物流状态变更 | 中 | 通知栏 | 揽收/派送/签收 |
| 促销活动 | 优惠券到期/大促活动 | 中 | 通知栏+应用内 | 定时推送 |
| 客服回复 | 客服消息回复 | 高 | 通知栏+应用内 | WebSocket+APNs |
| 账户安全 | 异地登录/密码修改 | 高 | 通知栏 | 实时推送 |
| 系统通知 | 公告/维护通知 | 低 | 应用内 | 静默推送 |
| 个性化推荐 | 降价提醒/补货提醒 | 低 | 通知栏 | 定时推送 |

#### 6.2.3 推送载荷示例

```json
// 订单状态推送
{
    "aps": {
        "alert": {
            "title": "订单已发货",
            "body": "您的订单 SF1234567890 已发出，请注意查收"
        },
        "badge": 3,
        "sound": "default",
        "category": "ORDER_STATUS"
    },
    "order_id": "90001",
    "order_no": "202610110001",
    "status": 2,
    "deep_link": "easyshopping://order/detail/90001"
}

// 静默推送（应用内消息同步）
{
    "aps": {
        "content-available": 1,
        "sound": ""
    },
    "type": "message_sync",
    "payload": {
        "action": "sync_messages"
    }
}

// 物流提醒
{
    "aps": {
        "alert": {
            "title": "您的包裹正在派送中",
            "body": "快递员张三 138****8000 正在为您派送"
        },
        "badge": 1,
        "sound": "delivery.caf",
        "category": "LOGISTICS_UPDATE",
        "thread-id": "logistics_90001"
    },
    "order_id": "90001",
    "tracking_no": "SF1234567890",
    "status": "delivering",
    "deep_link": "easyshopping://logistics/track/90001"
}
```

### 6.3 API设计

#### 6.3.1 消息中心

```
GET /api/v1/messages
Authorization: Bearer {access_token}

Parameters:
- type: 类型 (all/order/promotion/system/logistics/customer_service)
- page: 页码
- page_size: 每页数量

Response (200):
{
    "code": 0,
    "data": {
        "unread_count": 5,
        "messages": [
            {
                "message_id": 80001,
                "type": "order",
                "title": "订单已发货",
                "content": "您的订单 202610110001 已发出",
                "image": "https://cdn.example.com/img/10086/main.jpg",
                "related_id": "90001",
                "related_type": "order",
                "is_read": false,
                "created_at": "2026-10-11T14:00:00Z",
                "deep_link": "easyshopping://order/detail/90001"
            },
            {
                "message_id": 80002,
                "type": "promotion",
                "title": "优惠券即将过期",
                "content": "您有1张优惠券将于明天过期",
                "is_read": false,
                "created_at": "2026-10-10T10:00:00Z"
            }
        ]
    }
}

---
PUT /api/v1/messages/{message_id}/read
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": { "read": true, "unread_count": 4 }
}

---
PUT /api/v1/messages/read-all
Authorization: Bearer {access_token}

Request:
{
    "type": "order"
}

Response (200):
{
    "code": 0,
    "data": { "unread_count": 0 }
}

---
DELETE /api/v1/messages/{message_id}
Authorization: Bearer {access_token}
```

#### 6.3.2 推送设置

```
GET /api/v1/user/notification-settings   // 前端契约路径（ios-frontend.md 11.2），兼容别名 /api/v1/user/push-settings
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "push_enabled": true,
        "categories": {
            "order_status": {"enabled": true, "sound": true, "vibrate": true},
            "logistics": {"enabled": true, "sound": true, "vibrate": true},
            "promotion": {"enabled": false, "sound": false, "vibrate": false},
            "customer_service": {"enabled": true, "sound": true, "vibrate": true},
            "account_security": {"enabled": true, "sound": true, "vibrate": true}
        },
        "quiet_hours": {
            "enabled": true,
            "start": "22:00",
            "end": "08:00",
            "timezone": "Asia/Shanghai"
        }
    }
}

---
PUT /api/v1/user/notification-settings   // 前端契约路径，兼容别名 /api/v1/user/push-settings
Authorization: Bearer {access_token}

Request:
{
    "push_enabled": true,
    "categories": {
        "order_status": {"enabled": true, "sound": true, "vibrate": true},
        "promotion": {"enabled": true, "sound": false, "vibrate": false}
    },
    "quiet_hours": {
        "enabled": true,
        "start": "23:00",
        "end": "07:00"
    }
}

Response (200):
{
    "code": 0,
    "data": { "updated": true }
}
```

#### 6.3.3 消息增量拉取（推送不可达兜底）

```
GET /api/v1/messages/sync                 // 增量拉取：App 回前台/网络恢复时调用，弥补静默推送被系统限流导致的漏同步
Authorization: Bearer {access_token}

Parameters:
- since: 上次同步时间戳（服务端时间，ISO8601）
- limit: default=50, max=200

Response (200):
{
    "code": 0,
    "data": {
        "server_time": "2026-10-11T10:35:00Z",
        "has_more": false,
        "unread_count": 5,
        "messages": [ { "message_id": 80003, "type": "logistics", "title": "包裹派送中", "is_read": false, "created_at": "2026-10-11T10:30:00Z" } ]
    }
}
```

### 6.4 推送数据模型

```sql
-- 消息中心表
CREATE TABLE `user_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `message_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `type` VARCHAR(16) NOT NULL COMMENT 'order/promotion/system/logistics/cs',
  `title` VARCHAR(128) NOT NULL,
  `content` VARCHAR(512) NOT NULL,
  `image` VARCHAR(512) DEFAULT '',
  `related_id` VARCHAR(64) DEFAULT NULL,
  `related_type` VARCHAR(32) DEFAULT NULL,
  `extra` JSON,
  `is_read` TINYINT NOT NULL DEFAULT 0,
  `read_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_user_read` (`user_id`, `is_read`),
  KEY `idx_created` (`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户消息中心表';

-- 推送偏好设置表
CREATE TABLE `push_preference` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `category` VARCHAR(32) NOT NULL COMMENT '推送分类',
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `sound` TINYINT NOT NULL DEFAULT 1,
  `vibrate` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_category` (`user_id`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送偏好设置表';
```

### 6.5 技术要点

| 要点 | 说明 |
|------|------|
| APNs认证 | JWT Token认证（.p8私钥存于 KMS，禁止进代码仓库/镜像），令牌 20 分钟~1 小时有效，**每 50 分钟提前刷新**，避免临界失效 |
| HTTP/2多路复用 | 单连接复用多个推送请求，减少连接开销 |
| 推送重试 | 失败推送指数退避重试（1s/2s/4s/8s/16s），最多5次；APNs 返回 400/410 的不重试 |
| 批量推送 | 营销推送分批发送并限速（每批 1000 设备、批间隔可配），APNs 未公开固定限流阈值，按自适应并发+错误率自动降速 |
| Token管理 | 设备Token变更时自动更新，失效Token自动清理 |
| 送达追踪 | 监听 HTTP/2 响应码：**410 Unregistered 立即删除 Token**；429 TooManyRequests 降速重试；500/503 退避重试。（苹果已退役 Feedback Service，勿依赖） |
| 免打扰 | 免打扰时段内推送进入队列，恢复后发送；免打扰只作用于营销/推荐类，订单与账户安全类不受限 |
| 推送频控 | 单用户单分类推送频率限制（如营销类每天≤3条），并按设备维度二次限流，超限丢弃并记审计 |
| 富媒体推送 | 支持图片/视频/可变内容（Notification Service Extension），附件需服务端下发 HTTPS URL 且限 5MB，由扩展进程下载 |
| 静默推送 | content-available 仅用于低频数据同步提示；**iOS 对静默推送有系统级调度与频控（约每小时数次），不可作为可靠同步通道**，客户端须保留增量拉取兜底接口（见 6.3.3） |
| 权限状态同步 | 客户端授权状态变化（关闭通知）通过 `/api/v1/user/notification-settings` 回传；连续 3 次不可达的设备自动置 `push_enabled=0` |

---

## 七、支付服务

### 7.1 功能点

- Apple Pay支付（实物商品，应用内支付、App Clip支付）
- Apple 内购 IAP（虚拟商品：会员/积分/礼卡，凭证校验、恢复购买、App Store Server Notifications V2）
- 支付宝/微信支付（跳转支付、小程序支付）
- 余额支付
- 统一支付入口 `POST /api/v1/payments/{channel}/create`（前端契约路径）
- 支付回调处理（渠道验签 + 幂等）
- 退款（普通退款 / IAP 退款申诉处理）
- 支付密码管理
- 支付安全（风控验证、设备指纹）

### 7.2 Apple Pay集成

#### 7.2.1 Apple Pay架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Apple Pay 支付流程                               │
│                                                                     │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────────┐ │
│  │ iOS      │    │ Backend  │    │ Apple    │    │ Payment      │ │
│  │ Client   │    │ Server   │    │ Server   │    │ Processor    │ │
│  │ (PassKit)│    │          │    │          │    │ (银联/支付宝) │ │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘    └──────┬───────┘ │
│       │               │               │                  │         │
│  1. 请求支付参数       │               │                  │         │
│       │──────────────▶│               │                  │         │
│       │               │ 2. 创建Payment│                  │         │
│       │               │   Session     │                  │         │
│       │               │──────────────▶│                  │         │
│       │               │               │                  │         │
│       │               │ 3. session    │                  │         │
│       │               │◀──────────────│                  │         │
│       │ 4. paymentRequest             │                  │         │
│       │◀──────────────│               │                  │         │
│       │               │               │                  │         │
│  5. 用户Touch ID/Face ID确认          │                  │         │
│       │               │               │                  │         │
│  6. paymentToken      │               │                  │         │
│       │──────────────▶│               │                  │         │
│       │               │ 7. 验证支付   │                  │         │
│       │               │──────────────▶│                  │         │
│       │               │               │                  │         │
│       │               │ 8. 验证结果   │                  │         │
│       │               │◀──────────────│                  │         │
│       │               │               │                  │         │
│       │               │ 9. 调用支付网关扣款               │         │
│       │               │─────────────────────────────────▶│         │
│       │               │               │                  │         │
│       │               │ 10. 扣款结果   │                  │         │
│       │               │◀─────────────────────────────────│         │
│       │               │               │                  │         │
│       │ 11. 支付结果   │               │                  │         │
│       │◀──────────────│               │                  │         │
│       │               │               │                  │         │
└─────────────────────────────────────────────────────────────────────┘
```

#### 7.2.2 Apple Pay API

```
POST /api/v1/payments/apple-pay/session
Authorization: Bearer {access_token}

Request:
{
    "order_id": 90001,
    "merchant_id": "merchant.com.easyshopping"
}

Response (200):
{
    "code": 0,
    "data": {
        "payment_request": {
            "countryCode": "CN",
            "currencyCode": "CNY",
            "merchantCapabilities": ["supports3DS", "supportsDebit", "supportsCredit"],
            "supportedNetworks": ["visa", "masterCard", "chinaUnionPay", "jcb"],
            "paymentMethods": [],
            "total": {
                "label": "EasyShopping",
                "amount": "8949.00"
            },
            "applicationData": "base64_order_data",
            "shippingMethods": [],
            "shippingContact": {
                "emailAddress": "user@example.com"
            }
        },
        "merchant_session_url": "https://apple-pay-gateway.apple.com/paymentSession"
    }
}

---
POST /api/v1/payments/apple-pay/complete
Authorization: Bearer {access_token}

Request:
{
    "order_id": 90001,
    "payment_token": {
        "paymentData": "base64_encrypted_data...",
        "transactionId": "apple_pay_transaction_xxx",
        "paymentMethod": {
            "displayName": "银联借记卡(1234)",
            "network": "ChinaUnionPay",
            "type": "debit"
        }
    },
    "shipping_contact": {
        "name": "张三",
        "phone": "138****8000",
        "address": {
            "province": "北京市",
            "city": "北京市",
            "district": "朝阳区",
            "detail": "望京SOHO T1"
        }
    }
}

Response (200):
{
    "code": 0,
    "data": {
        "success": true,
        "transaction_id": "apple_txn_xxx",
        "order_id": 90001,
        "paid_amount": 8949.00,
        "paid_at": "2026-10-11T10:01:00Z"
    }
}
```

### 7.3 统一支付入口与 Apple 内购（IAP）

#### 7.3.1 统一支付入口

```
POST /api/v1/payments/{channel}/create     // 前端契约路径；channel ∈ apple_pay | iap | alipay | wechat | balance
                                            // 别名：POST /api/v1/payments/wechat/create、POST /api/v1/payments/alipay/create（ios-frontend.md 8.3 标注的待补接口，统一走本入口实现，不另立路由）
Authorization: Bearer {access_token}

Request:
{
    "order_id": 90001,
    "return_url": "easyshopping://payment/result"   // 跳转类渠道（支付宝/微信）回跳用
}

Response (200) - apple_pay：
{
    "code": 0,
    "data": {
        "channel": "apple_pay",
        "merchant_id": "merchant.com.easyshopping",
        "merchant_session_url": "https://apple-pay-gateway.apple.com/paymentSession",
        "payment_request": { "...同 7.2.2，服务端已向 Apple 创建 Merchant Session": "" }
    }
}

Response (200) - alipay / wechat：
{
    "code": 0,
    "data": { "channel": "alipay", "pay_params": "渠道加密参数串", "expired_in": 900 }
}

Error Response (409) - 订单金额与支付参数不一致 / 订单已关闭时返回，客户端必须重新拉起订单页
```

#### 7.3.2 Apple 内购（IAP）服务端校验

| 环节 | 方案 |
|------|------|
| 凭证校验 | 客户端 StoreKit 2 返回 Signed Transaction（JWS），服务端使用 App Store Server Library 验签（Apple 根证书链），校验 `bundle_id`、`product_id`、`transaction_id` 唯一性 |
| 用户绑定 | 校验 `appAccountToken` ↔ `user_id` 绑定关系（首次 verify 时落库），同一 token 关联多个账号、或账号无绑定记录的交易一律挂起告警，防止换号盗刷与丢单 |
| 幂等入账 | 以 `transaction_id` 唯一索引去重，重复提交直接返回首次结果，杜绝重复发货 |
| 丢单恢复 | 客户端提供「恢复购买」入口（Guideline 3.1.1 系列对 IAP 应用的必备要求），服务端按用户重放未完成交易；`GET .../iap/transactions` 提供跨设备交易同步 |
| 权益查询 | `GET .../iap/entitlements` 为会员/权益的唯一事实来源（服务端按 `iap_transaction` + Server Notifications 推导），客户端不得本地推断会员状态 |
| Server Notifications | 接收 **App Store Server Notifications V2**（REFUND / REVOKE / RENEWAL / CONSUMPTION_REQUEST / DID_CHANGE_RENEWAL_PREF），验签后驱动会员回收、补单与对账 |
| 沙箱环境 | 使用 sandbox 验收；沙箱与生产凭证不互通，服务端按 `environment` 字段区分并隔离数据 |
| 审核合规 | 会员、积分、礼卡等**虚拟商品必须走 IAP**，App 内不得出现引导外部支付的入口（Guideline 3.1.1）；实物商品可使用 Apple Pay / 支付宝 / 微信 |

```
POST /api/v1/payments/iap/verify           // IAP 凭证校验入账（契约路径；兼容别名 POST /api/v1/iap/verify，ios-frontend.md 16.1 阻塞项①）
Authorization: Bearer {access_token}

Request:
{
    "signed_transaction_info": "eyJhbGciOiJFUzI1Ni...(JWS)",
    "app_account_token": "A1B2C3D4-....-....-....-............",   // StoreKit2 appAccountToken；服务端校验其与当前 user_id 的绑定，防止换号盗刷
    "order_id": null                        // 关联平台订单（可选，如购买会员礼包）
}

Response (200):
{
    "code": 0,
    "data": {
        "transaction_id": "2000000123456789",
        "product_id": "com.easyshopping.vip.monthly",
        "expires_at": "2026-11-11T00:00:00Z",
        "environment": "Production"
    }
}

---
POST /api/v1/payments/iap/restore           // 恢复购买（兼容别名 POST /api/v1/iap/restore）
Authorization: Bearer {access_token}

Response (200):
{ "code": 0, "data": { "restored": 3 } }

---
GET /api/v1/payments/iap/transactions      // 未完成交易查询与跨设备同步（ios-frontend.md 16.1 阻塞项②；兼容别名 GET /api/v1/iap/transactions）
Authorization: Bearer {access_token}

Parameters:
- only_active: true（默认，仅返回未 finish 的交易）

Response (200):
{
    "code": 0,
    "data": {
        "transactions": [
            {
                "transaction_id": "2000000123456789",
                "original_transaction_id": "2000000123456789",
                "product_id": "com.easyshopping.vip.monthly",
                "purchase_at": "2026-10-11T10:00:00Z",
                "expires_at": "2026-11-11T00:00:00Z",
                "revoked": false,
                "environment": "Production"
            }
        ]
    }
}

---
GET /api/v1/payments/iap/entitlements      // 已购权益查询（会员状态；ios-frontend.md 16.1 阻塞项④；兼容别名 GET /api/v1/iap/entitlements）
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "vip": { "active": true, "level": "monthly", "expires_at": "2026-11-11T00:00:00Z" },
        "points_pack": [],
        "grace_period": false
    }
}

---
POST /api/v1/payments/iap/server-notification   // App Store Server Notifications V2 回调（公网入口：仅接受 Apple 官方网段 + 严格验签 + 幂等）
Content-Type: application/json

Request: { "signedPayload": "eyJ..." }

Response (200): { "code": 0 }
```

**IAP 数据模型：**

```sql
-- IAP交易表
CREATE TABLE `iap_transaction` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `transaction_id` VARCHAR(64) NOT NULL COMMENT 'Apple Transaction ID',
  `original_transaction_id` VARCHAR(64) DEFAULT NULL COMMENT '原始交易ID（订阅续费关联）',
  `user_id` BIGINT NOT NULL,
  `product_id` VARCHAR(128) NOT NULL COMMENT '商品ID（如 com.easyshopping.vip.monthly）',
  `environment` VARCHAR(16) NOT NULL DEFAULT 'Production' COMMENT 'Sandbox/Production',
  `quantity` INT NOT NULL DEFAULT 1,
  `expires_at` DATETIME DEFAULT NULL COMMENT '订阅到期时间',
  `revocation` TINYINT NOT NULL DEFAULT 0 COMMENT '是否被Apple退款撤销',
  `raw_payload` JSON COMMENT '原始签名交易信息（审计/申诉用）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 2已过期 3已撤销',
  `purchased_at` DATETIME NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transaction_id` (`transaction_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_original` (`original_transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAP交易表';
```

> **边界说明**：Apple Pay 与 IAP 互斥使用——**实物商品用 Apple Pay，虚拟商品用 IAP**；两者都必须服务端验签后才允许发货，客户端回传的任何「支付成功」标志均不可信。

### 7.4 支付安全

| 安全措施 | 说明 |
|----------|------|
| 设备指纹 | 采集设备信息生成唯一指纹，识别异常设备 |
| 交易风控 | 实时风控引擎，检测异常交易（金额/频率/设备） |
| 生物识别 | 大额支付要求Face ID/Touch ID验证 |
| 支付密码 | 设置支付密码，修改需验证旧密码 |
| 证书校验 | Apple Pay支付Token服务端验证（向收单机构/APNS验证X.509证书链与加密 nonce） |
| 防重放 | 支付Token一次性使用，拒绝重复提交 |
| 限额控制 | 单笔/单日/单月交易限额 |
| 回调验签 | 支付宝/微信异步通知必须验签（RSA-SM2）+ 校验金额与订单一致 + 幂等处理，验签失败直接拒绝并告警 |
| 回调幂等 | 以渠道交易号建立唯一索引，重复通知直接返回成功，不重复发货 |
| 金额校验 | 回调实付金额 ≠ 订单应付金额时挂起订单进入人工核对，严禁按客户端上报金额入账 |
| 风控联动 | 风控服务（支撑服务）按设备/行为/关系网络实时评分，高风险订单强制生物识别或延迟发货 |

### 7.5 退款API

```
POST /api/v1/payments/refund
Authorization: Bearer {access_token}

Request:
{
    "order_id": 90001,
    "refund_type": "full",
    "amount": 8949.00,
    "reason": "不想要了",
    "description": "商品无问题，个人原因"
}

Response (200):
{
    "code": 0,
    "data": {
        "refund_no": "RF2026110001",
        "order_id": 90001,
        "amount": 8949.00,
        "status": "processing",
        "estimated_refund_time": "Apple Pay: 1-3个工作日；支付宝/微信: 即时到账",
        "refund_channel": "original"
    }
}

---
POST /api/v1/payments/apple-pay/refund
Authorization: Bearer {access_token}
Content-Type: application/json

Request:
{
    "order_id": 90001,
    "refund_type": "full",
    "amount": 8949.00,
    "reason": "不想要了",
    "apple_pay_transaction_id": "apple_txn_xxx"
}

Response (200):
{
    "code": 0,
    "data": {
        "refund_no": "RF2026110001",
        "transaction_id": "apple_txn_xxx",
        "amount": 8949.00,
        "status": "processing",
        "estimated_refund_time": "1-3个工作日"
    }
}
```

---

## 八、分享服务

### 8.1 功能点

- 分享链接生成（短链、二维码、Universal Link）
- 分享海报生成（动态海报、带二维码）
- 分享渠道管理（微信好友、朋友圈、QQ、微博、复制链接）
- 渠道追踪（分享来源追踪、点击追踪、转化追踪）
- 邀请奖励（邀请注册奖励、首单奖励）
- 分销佣金（分销员管理、佣金计算、佣金提现）

### 8.2 Universal Links设计

#### 8.2.1 apple-app-site-association (AASA)

```json
{
    "applinks": {
        "details": [
            {
                "appIDs": [
                    "{TEAM_ID}.com.easyshopping",
                    "{TEAM_ID}.com.easyshopping.Clip"
                ],
                "components": [
                    {
                        "/": "/product/*",
                        "comment": "商品详情页"
                    },
                    {
                        "/": "/order/*",
                        "comment": "订单详情页"
                    },
                    {
                        "/": "/category/*",
                        "comment": "分类页"
                    },
                    {
                        "/": "/search",
                        "comment": "搜索页"
                    },
                    {
                        "/": "/promotion/*",
                        "comment": "活动页"
                    },
                    {
                        "/": "/invite/*",
                        "comment": "邀请页"
                    },
                    {
                        "/": "/s/*",
                        "comment": "分享短链（8.3 分享链接落地页，二维码/聊天卡片点击唤起 App 的关键路径）"
                    }
                ]
            }
        ]
    },
    "webcredentials": {
        "apps": ["{TEAM_ID}.com.easyshopping"]
    },
    "appclips": {
        "apps": [
            "{TEAM_ID}.com.easyshopping.Clip"
        ]
    }
}
```

> **AASA 托管与一致性要求**：
> 1. 文件必须由**域名根路径** `https://{domain}/.well-known/apple-app-site-association` 直接返回（无 302 跳转、无 Cookie、`Content-Type: application/json`、HTTPS 证书受信任）；
> 2. 苹果会对 AASA 做 CDN 缓存（最长约 3 天），修改后可通过带版本参数的 URL 触发重新抓取；上线前用 [AASA Validator](https://devpages.io/aasa/) 或 `curl` 自检；
> 3. **分享短链域名必须与 AASA 托管域名一致**（全文已统一为 `ios-frontend.md` 16.4 约定的 `easyshopping.com`，托管地址 `https://easyshopping.com/.well-known/apple-app-site-association`），否则短链落地页无法唤起 App，分享转化链路直接断裂；
> 4. Bundle ID / Team ID 以 App Store Connect 实际值为准（占位符 `{TEAM_ID}`），主 App 与 App Clip 的 appID 都要列入 `appIDs`；**App Clip 是否提供必须在阶段一决策**（`ios-frontend.md` 16.4 与附录 A.4 同为待决项）：提供则前端需同步开发 Clip Target，不提供则本文件 AASA 的 `appclips` 段与 `.Clip` 条目一并删除，避免苹果校验与实际能力不符；
> 5. 与 App 内路由表保持同步：AASA 新增路径时，`ios-frontend.md` 的 `DeepLink` 解析器必须同步支持，否则苹果校验通过但 App 内会 404。

#### 8.2.2 Universal Link路由

```
URL Pattern                                  →  App Route
─────────────────────────────────────────────────────────
https://easyshopping.com/product/{id}        →  商品详情页
https://easyshopping.com/order/{id}          →  订单详情页
https://easyshopping.com/category/{id}       →  分类页
https://easyshopping.com/search?q={kw}       →  搜索结果页
https://easyshopping.com/promotion/{id}      →  活动详情页
https://easyshopping.com/invite/{code}       →  邀请注册页
https://easyshopping.com/coupon/{id}         →  优惠券领取页
https://easyshopping.com/clip/{action}       →  App Clip入口
https://easyshopping.com/s/{share_code}      →  分享落地页（服务端 302 跳目标详情并携带 share_code 归因）
```

> 说明：域名已按 1.2.2 约定5 与 `ios-frontend.md` 16.4 统一为 `easyshopping.com`（不再使用占位域名）；App 内自定义 Scheme 统一为 `easyshopping://`，但**通知/分享点击跳转优先使用 Universal Link（https）**，自定义 Scheme 仅作降级兜底——自定义 Scheme 可被恶意 App 抢注劫持。

#### 8.2.3 App Clip 专用接口

```
GET /api/v1/appclip/metadata             // App Clip 元数据（免登录、轻载荷，由 App Clip Code / NFC / 地图 / 网页唤起）
Authorization: 无

Parameters:
- invocation_id: 唯一唤起ID（对应 app_clip_invocation 表）
- scene: product | order | store

Response (200):
{
    "code": 0,
    "data": {
        "type": "product",
        "product": { "product_id": 10086, "title": "...", "main_image": "...", "price": 8999.00, "in_stock": true },
        "merchant": { "name": "Example Shop", "logo": "https://cdn.example.com/logo.png" },
        "pickup": { "store": "望京店", "distance_km": 1.2 },
        "supports_apple_pay": true,
        "app_upgrade_hint": "查看订单与售后请安装主 App"
    }
}

---
POST /api/v1/appclip/session-upgrade     // App Clip → 完整 App 会话升级（App Clip Instance API）
Authorization: Bearer {access_token}     // 用户已在主 App 完成登录

Request:
{ "app_clip_token": "xxxxx" }            // App Clip 端经 App Clip Session 生成，TTL 内有效

Response (200):
{ "code": 0, "data": { "upgraded": true, "cart_migrated": true, "expire_hours": 6 } }
```

> 说明：
> - App Clip 体积上限 15MB，**不承载完整注册登录**，仅提供「浏览 + Apple Pay 快速下单」最短链路；
> - 用户安装主 App 后，通过 App Clip Instance API（TTL 6 小时/48 小时两级令牌）迁移购物车与归因，避免重复下单；
> - 每次唤起写入 `app_clip_invocation` 表（见 1.3.1）用于归因与转化统计（埋点服务消费）；
> - AASA 的 `appclips` 段与 App Clip Bundle ID 必须配置正确（见 8.2.1），且 App Clip 的推送/沙盒环境与主 App 分离。

### 8.3 分享API

```
POST /api/v1/share
Authorization: Bearer {access_token}

Request:
{
    "share_type": "product",
    "target_id": "10086",
    "channel": "wechat",
    "custom_text": "超值好物推荐",
    "generate_poster": true
}

Response (200):
{
    "code": 0,
    "data": {
        "share_code": "sAbC12",
        "share_url": "https://easyshopping.com/s/sAbC12",
        "short_url": "https://u.easyshopping.com/sAbC12",
        "universal_link": "https://easyshopping.com/product/10086?share_code=sAbC12",
        "qr_code": "https://cdn.example.com/qrcode/sAbC12.png",
        "poster_url": "https://cdn.example.com/poster/share_10086_sAbC12.png",
        "expire_at": "2026-11-11T00:00:00Z",
        "track_params": {
            "utm_source": "app_share",
            "utm_medium": "wechat",
            "utm_campaign": "product_share",
            "share_code": "sAbC12",
            "inviter_id": "10001"
        }
    }
}

---
GET /api/v1/share/{share_code}/track    // 注意：该接口由「被分享者」在未登录状态下访问（扫码/点链接落地），不携带 Token，
                                        // 网关按 IP+设备指纹限流；inviter 信息仅返回昵称与头像，不返回手机号等隐私数据

Response (200):
{
    "code": 0,
    "data": {
        "share_code": "sAbC12",
        "share_type": "product",
        "target": {
            "product_id": 10086,
            "title": "Apple iPhone 15 Pro",
            "main_image": "...",
            "price": 8999.00
        },
        "inviter": {
            "user_id": 10001,
            "nickname": "电商达人",
            "avatar": "..."
        },
        "stats": {
            "click_count": 128,
            "convert_count": 15,
            "commission_earned": 150.00
        }
    }
}

---
GET /api/v1/user/share/stats
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "total_shares": 45,
        "total_clicks": 3200,
        "total_converts": 280,
        "total_commission": 2800.00,
        "pending_commission": 500.00,
        "withdrawn_commission": 2300.00,
        "recent_shares": [
            {
                "share_code": "sAbC12",
                "product_id": 10086,
                "product_title": "Apple iPhone 15 Pro",
                "click_count": 128,
                "convert_count": 15,
                "commission": 150.00,
                "shared_at": "2026-10-01T10:00:00Z"
            }
        ]
    }
}
```

### 8.4 分享海报设计

```
┌─────────────────────────────────────────┐
│  ┌─────┐                                │
│  │     │  Apple iPhone 15 Pro           │
│  │ 商  │  256GB 黑色钛金属               │
│  │ 品  │                                │
│  │ 图  │  价格: ¥8,999.00               │
│  │ 片  │  原价: ¥9,999.00               │
│  │     │                                │
│  └─────┘                                │
│                                         │
│  ┌─────────────────┐                    │
│  │  二维码 (QR)     |  扫码查看详情      │
│  │   ████████████   |  或打开App         │
│  │   ████████████   |                    │
│  │   ████████████   |  App Store下载     │
│  └─────────────────┘                    │
│                                         │
│  分享人: 电商达人                       │
│  邀请码: sAbC12                         │
│  下载App → https://app.example.com      │
└─────────────────────────────────────────┘
```

### 8.5 技术要点

| 要点 | 说明 |
|------|------|
| 短链生成 | 雪花算法 + Base62编码，6-8位短码 |
| 海报生成 | 后端模板引擎生成，支持动态数据填充 |
| 二维码 | 生成带Logo的中间嵌图二维码 |
| 渠道追踪 | 分享链接携带UTM参数 + 邀请人标识 |
| 防刷机制 | 同一设备多次点击只计一次有效点击 |
| 分销佣金 | 基于规则引擎计算佣金，T+7可提现 |
| Universal Link | 服务端返回AASA文件，客户端配置Associated Domains |

---

## 九、更新服务

### 9.1 功能点

- 版本检查（启动检查、手动检查）
- 灰度发布（按稳定哈希分桶：设备/用户/地域/用户群）
- 强制更新（低于指定版本强制更新，仅跳转 App Store）
- 配置热更新（功能开关、AB 实验、文案/图片、路由规则；**仅数据级，不含可执行代码**）
- 配置回滚（发现问题紧急回滚配置版本）
- 更新渠道（App Store 正式发布 / TestFlight 内测；**iOS 不存在也不得设计任何「审核绕过」的安装通道**）

> **更新渠道红线**：面向公众的消费级 App 只允许 **App Store + TestFlight** 两种分发方式。**不得使用 Enterprise 企业证书向公众分发**（企业证书仅限内部员工工具，违反将被吊销账号，Guideline 2.5.2 / Apple Developer Enterprise Program 许可协议）；MDM 分发同理仅限企业自用场景。本文档示例中的"内测包校验字段"仅在 TestFlight 渠道生效。

> **iOS 合规红线（务必遵守）**：依据 App Store Review Guidelines **2.5.2**，App 不得下载、安装或执行引入可执行代码的代码（含 OC/Swift 代码补丁、JSPatch、RN/Flutter 逻辑热修复）。本服务只允许下发**配置与资源**（开关、AB 参数、文案、图片、路由），资源更新需签名校验且不得改变核心业务逻辑；任何代码缺陷只能通过发版修复（借助 TestFlight + 加急审核缩短时效）。Android 端的代码级热修复（Tinker/Sophix）方案不得复用到 iOS。

### 9.2 版本检查API

```
GET /api/v1/version/check
Authorization: 无（**免登录可调用**：游客态也要能检查更新；灰度分桶键未登录时回退 `device_id`，见 9.3）

Parameters:
- version_code: 当前版本号（数字，如321）
- version_name: 当前版本名（如3.2.1）
- platform: ios
- build_number: 构建号
- device_model: iPhone15,2
- os_version: 18.0
- channel: app_store

Response (200) - 有新版本（灰度命中）:
{
    "code": 0,
    "data": {
        "has_update": true,
        "version_code": 322,
        "version_name": "3.2.2",
        "update_type": "recommended",
        "force_update": false,
        "force_below_version": 0,
        "update_log": "1. 修复已知问题\n2. 优化启动速度",
        "download_url": "itms-apps://itunes.apple.com/app/id123456",   // iOS 跳转 App Store 的标准 scheme
        "app_store_url": "https://apps.apple.com/cn/app/id123456",
        "release_date": "2026-10-10",
        "gray_percent": 30,
        "in_gray": true,
        "phased_release": true,          // App Store 分阶段发布中，客户端不得重复弹更新框
        "min_supported_version": 300     // 低于此版本将出现功能不可用（用于判断是否强更）
    }
}

Response (200) - 无更新:
{
    "code": 0,
    "data": {
        "has_update": false,
        "latest_version_code": 321,
        "latest_version_name": "3.2.1"
    }
}

Response (200) - 强制更新:
{
    "code": 0,
    "data": {
        "has_update": true,
        "version_code": 322,
        "version_name": "3.2.2",
        "update_type": "force",
        "force_update": true,
        "force_below_version": 321,
        "update_log": "1. 紧急修复支付漏洞\n2. 升级安全组件",
        "download_url": "itms-apps://itunes.apple.com/app/id123456",
        "app_store_url": "https://apps.apple.com/cn/app/id123456",
        "release_date": "2026-10-10",
        "can_skip": false,
        "min_supported_version": 320
    }
}
```

> **说明**：
> - `md5` / `file_size` 仅在 **TestFlight 内测渠道**返回（用于校验内测包完整性），App Store 渠道一律不下发，避免暗示支持站外 IPA 安装——iOS 上除 App Store / TestFlight 外不存在面向公众的合法安装通道（MDM / 企业证书仅限企业自用内部工具，见 9.1 更新渠道红线）；
> - **强制更新必须跳转 App Store**（`itms-apps://`），且只在「继续使用会造成资损/安全/合规风险」时启用；仅为体验问题的更新必须可跳过，否则易触发 Guideline 2.5.1 / 4.0 拒审；
> - 若 App Store 使用了 Phased Release（分阶段发布），服务端灰度结果需与 Phased Release 叠加判断，客户端已从 App Store 拿到新版本时不得再弹更新提示，避免重复打扰。

### 9.3 灰度发布策略

```json
{
    "version_code": 322,
    "bucket_key": "user_id",                 // 分桶键：优先 user_id，未登录回退 device_id
    "rules": [                               // rules 定义「人群」，按顺序匹配，命中即停止
        {
            "name": "内部员工",
            "type": "user_list",
            "values": [10001, 10002, 10003],
            "percent": 100
        },
        {
            "name": "iOS 18+用户",
            "type": "os_version",
            "operator": ">=",
            "value": "18.0",
            "percent": 20
        },
        {
            "name": "常规灰度",
            "type": "consistent_hash",       // 基于 bucket_key 的一致性哈希分桶，禁止真随机
            "percent": 5                     // 由下方 schedule 按时间推进
        }
    ],
    "schedule": [                            // schedule 定义「节奏」，与 rules 正交：当前生效比例 = min(schedule(percent), rules(percent))
        {"time": "2026-10-11T10:00:00Z", "percent": 1},
        {"time": "2026-10-11T14:00:00Z", "percent": 5},
        {"time": "2026-10-12T10:00:00Z", "percent": 20},
        {"time": "2026-10-13T10:00:00Z", "percent": 50},
        {"time": "2026-10-15T10:00:00Z", "percent": 100}
    ],
    "rollback": {                            // 紧急回滚：把 percent 置 0 即停止向旧版本用户推荐新版本
        "enabled": true,
        "rollback_percent": 0,
        "reason_required": true
    }
}
```

**灰度机制要求：**

1. **分桶必须稳定**：`bucket = crc32(bucket_key) % 100`，同一用户/设备在整个灰度周期内始终落在同一桶，避免"今天收到更新提示、明天又消失"的体验抖动；禁止使用 `random`。
2. **可观测**：每个灰度阶段必须监控崩溃率、启动耗时、支付成功率、订单转化率，任一核心指标劣化超过阈值（如崩溃率 +0.2%）自动暂停推进并告警。
3. **可回滚**：回滚只需把 `percent` 置 0，历史版本用户立即不再收到更新提示；已更新用户无法降级（iOS 无降级通道），因此灰度节奏宁慢勿快。
4. **与 App Store Phased Release 叠加**：服务端灰度 + App Store 7 天分阶段发布同时生效时，以二者交集为准，避免重复打扰。
5. **强制更新豁免**：安全类强更不参与灰度，直接按 `force_below_version` 全量生效。

### 9.4 配置级热更新机制（仅数据级，不含可执行代码）

```
┌─────────────────────────────────────────────────────┐
│                  热更新机制                           │
│                                                     │
│  ┌──────────┐         ┌──────────┐                  │
│  │ Config   │────────▶│ Client   │                  │
│  │ Center   │  Push   │ SDK      │                  │
│  └──────────┘         └──────────┘                  │
│       │                      │                      │
│  ┌────┴────┐           ┌─────┴─────┐               │
│  │ Feature │           │ AB Test   │               │
│  │ Flag    │           │ Engine    │               │
│  └─────────┘           └───────────┘               │
│                                                     │
│  支持的热更新内容：                                   │
│  • 功能开关（Feature Toggle）                        │
│  • AB实验参数                                       │
│  • 首页布局配置                                      │
│  • 运营位配置                                       │
│  • 文案/图片替换                                    │
│  • 路由规则更新                                     │
└─────────────────────────────────────────────────────┘
```

**热更新合规边界（红线）：**

| 类别 | 允许 | 禁止 |
|------|------|------|
| 代码 | 无 | 任何可执行代码下发：OC/Swift 代码补丁、JSPatch/OSSPatch、RN/JS 业务逻辑热修复、脚本引擎 |
| 配置 | 功能开关、AB 实验参数、排序权重、阈值、文案、图片 URL、Banner 与运营位、路由开关 | 通过配置拼接出新的业务逻辑分支（等价于下发代码） |
| 资源 | 图片/主题/字体等**非可执行资源**，附带签名校验与完整性校验（防止 CDN/中间人篡改） | 资源替换导致核心功能行为变化（如替换支付页资源） |
| 生效方式 | 配置随启动/回前台拉取后生效；资源下次启动生效 | 任何「立即改变代码行为」的机制 |

**工程约束：**

1. 客户端配置 SDK **只允许读取声明式数据**（JSON），并对 schema 做白名单校验，未知字段忽略；
2. 所有配置项在客户端必须有**本地默认值**，远程配置拉取失败（3s 超时）时静默降级，不影响启动与核心链路（与 `ios-frontend.md` 2.4 的 `RemoteConfigManager` 契约一致）；
3. 配置变更记录审计日志（谁在什么时间改了什么、影响范围），支持**按版本号一键回滚**（见 9.1 配置回滚）；
4. 关键交易链路（下单/支付/退款）不允许被远程开关关闭到"不可用"，只能降级提示，防止配置错误造成全量资损；
5. 图片/文案资源走 CDN 时，URL 必须带内容版本号，客户端按 URL 缓存，避免更新不生效。

### 9.5 热更新API

```
GET /api/v1/config
Authorization: 无（**免登录可调用**：启动早期即需拉取；未登录时按 device 维度下发，登录后按 user 维度合并）

Parameters:
- version_code: 321
- platform: ios
- scene: home_page/config_flags/ab_tests/search_discovery

Response (200):
{
    "code": 0,
    "data": {
        "version": 42,
        "features": {
            "new_home_page": true,
            "ai_search": true,
            "dark_mode": false,
            "apple_pay_enabled": true
        },
        "ab_tests": {
            "checkout_flow": "variant_b",
            "search_algorithm": "v3"
        },
        "banners": [
            {
                "id": 1,
                "image": "https://cdn.example.com/banner/sale.jpg",
                "link": "easyshopping://promotion/1001",
                "position": 1
            }
        ],
        "updated_at": "2026-10-11T08:00:00Z",
        "config_hash": "a1b2c3d4"
    }
}
```

> **拉取约定**：客户端携带本地 `config_version`，服务端未变更时返回 304，避免每次启动全量拉取；下发的运营位图片 URL 带内容版本号并附资源签名（HMAC），客户端校验后落盘。

**配置数据模型：**

```sql
-- 远程配置表
CREATE TABLE `app_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_version` INT NOT NULL COMMENT '配置版本号（单调递增）',
  `platform` VARCHAR(16) NOT NULL DEFAULT 'ios',
  `min_version_code` INT DEFAULT 0 COMMENT '生效的最低客户端版本',
  `max_version_code` INT DEFAULT 0 COMMENT '生效的最高客户端版本，0表示不限',
  `config_key` VARCHAR(64) NOT NULL,
  `config_value` JSON NOT NULL,
  `scope` JSON COMMENT '灰度范围（用户/设备/地域）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0草稿 1生效 2已回滚',
  `operator` BIGINT DEFAULT NULL,
  `effective_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_key_version` (`config_key`, `config_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='远程配置表（配置级热更新）';

-- AB实验表
CREATE TABLE `ab_experiment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `experiment_key` VARCHAR(64) NOT NULL,
  `platform` VARCHAR(16) NOT NULL DEFAULT 'ios',
  `variants` JSON NOT NULL COMMENT '实验分组及流量比例',
  `bucket_key` VARCHAR(32) NOT NULL DEFAULT 'user_id',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未开始 1运行中 2已结束',
  `start_at` DATETIME DEFAULT NULL,
  `end_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_experiment` (`experiment_key`, `platform`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AB实验表（一致性哈希分桶，分桶键与 9.3 灰度一致）';
```

---

## 十、客服服务

### 10.1 功能点

- 在线客服（文字、图片、语音、商品卡片、订单卡片）
- 智能客服（机器人自动回复、意图识别、知识库匹配）
- 工单系统（问题提交、工单流转、满意度评价）
- 常见问题（FAQ分类浏览、搜索）
- 客服评价（满意度评分、评价标签）

### 10.2 WebSocket实时通信

```
┌─────────────────────────────────────────────────────────────────────┐
│                    客服 WebSocket 架构                               │
│                                                                     │
│  ┌──────────┐    ┌──────────┐    ┌───────────┐    ┌────────────┐ │
│  │ iOS      │    │ Gateway  │    │ RabbitMQ  │    │ CS Agent   │ │
│  │ Client   │◀──▶│ (WS)     │◀──▶│ Cluster   │◀──▶│ Backend    │ │
│  │ (SRWebSocket)│ └──────────┘    └───────────┘    └────────────┘ │
│  └──────────┘                                                      │
│       │                                                             │
│       ▼                                                             │
│  ┌──────────┐                                                      │
│  │ Redis    │  会话状态、未读计数、路由信息                           │
│  │ Cluster  │                                                      │
│  └──────────┘                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### 10.2.1 WebSocket消息协议

```json
// 连接（优先走 Sec-WebSocket-Protocol 携带一次性票据，避免首帧明文 Token）
{
    "type": "connect",
    "auth_ticket": "one_time_ws_ticket_xxx",   // 10.3 创建会话时下发，60s 有效、一次性
    "device_id": "ios-uuid-xxxx",
    "timestamp": 1728633600000,
    "signature": "HMAC-SHA256(ticket+timestamp, device_secret)"   // device_secret 在登录时随响应下发并存入 Keychain
}

// 客户端发送消息
{
    "type": "message",
    "session_id": "cs_session_xxx",
    "msg_id": "msg_xxx",
    "message_type": "text",
    "content": "你好，我想咨询一下订单问题",
    "timestamp": 1728633600000
}

// 服务端推送消息
{
    "type": "message",
    "session_id": "cs_session_xxx",
    "msg_id": "msg_yyy",
    "sender_type": "agent",
    "sender_id": 20001,
    "message_type": "text",
    "content": "您好，请问有什么可以帮您？",
    "timestamp": 1728633660000
}

// 心跳
{
    "type": "ping",
    "timestamp": 1728633600000
}

// 心跳响应
{
    "type": "pong",
    "timestamp": 1728633600500
}

// 正在输入
{
    "type": "typing",
    "session_id": "cs_session_xxx",
    "sender_type": "agent"
}

// 消息已读回执
{
    "type": "read_receipt",
    "session_id": "cs_session_xxx",
    "msg_ids": ["msg_xxx", "msg_yyy"],
    "reader_type": "user"
}

// 会话状态变更
{
    "type": "session_update",
    "session_id": "cs_session_xxx",
    "status": "queued",
    "queue_position": 3,
    "estimated_wait": 60
}
```

### 10.3 客服API

```
POST /api/v1/cs/sessions
Authorization: Bearer {access_token}

Request:
{
    "type": "online",
    "order_id": 90001,
    "initial_message": "我想咨询订单发货问题"
}

Response (200):
{
    "code": 0,
    "data": {
        "session_id": "cs_session_xxx",
        "status": "waiting",
        "agent_id": null,
        "agent_name": null,
        "queue_position": 3,
        "estimated_wait": 120,
        "websocket_url": "wss://ws.example.com/cs",
        "auth_ticket": "one_time_ws_ticket_xxx"   // 一次性 WS 鉴权票据（60s 有效），禁止把长期 Token 拼进 URL——URL 会进入网关访问日志，造成凭据泄漏
    }
}

---
GET /api/v1/cs/sessions/{session_id}/messages
Authorization: Bearer {access_token}

Parameters:
- before_msg_id: 向前翻页（可选）
- limit: 每页数量 (default=20)

Response (200):
{
    "code": 0,
    "data": {
        "messages": [
            {
                "msg_id": "msg_xxx",
                "sender_type": "user",
                "sender_id": 10001,
                "message_type": "text",
                "content": "你好，我想咨询一下订单问题",
                "timestamp": 1728633600000,
                "read": true
            },
            {
                "msg_id": "msg_yyy",
                "sender_type": "agent",
                "sender_id": 20001,
                "sender_name": "小李",
                "message_type": "text",
                "content": "您好，请问有什么可以帮您？",
                "timestamp": 1728633660000,
                "read": true
            }
        ],
        "has_more": false
    }
}

---
GET /api/v1/cs/tickets                        // 工单列表（契约路径，ios-frontend.md 12.2 统一 /cs/ 前缀）；兼容别名 GET /api/v1/tickets
Authorization: Bearer {access_token}

Parameters:
- status: pending / processing / resolved / closed
- page / page_size / cursor

Response (200):
{
    "code": 0,
    "data": {
        "total": 3,
        "tickets": [
            { "ticket_no": "TK2026110001", "title": "订单未收到货", "status": "pending",
              "category": "order_issue", "created_at": "2026-10-11T10:00:00Z" }
        ]
    }
}

---
POST /api/v1/cs/tickets                      // 创建工单（契约路径）；兼容别名 POST /api/v1/tickets
Authorization: Bearer {access_token}

Request:
{
    "category": "order_issue",
    "order_id": 90001,
    "title": "订单未收到货",
    "content": "订单号202610110001，显示已签收但未收到",
    "images": [
        "https://cdn.example.com/ticket/img1.jpg"
    ],
    "priority": 2
}

Response (200):
{
    "code": 0,
    "data": {
        "ticket_no": "TK2026110001",
        "status": "pending",
        "estimated_response": "24小时内"
    }
}

---
GET /api/v1/cs/tickets/{ticket_no}           // 工单详情（含回复列表，契约路径）；兼容别名 GET /api/v1/tickets/{ticket_no}
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "ticket_no": "TK2026110001",
        "title": "订单未收到货",
        "status": "processing",
        "priority": 2,
        "replies": [
            { "reply_id": "rp_xxx", "sender_type": "agent", "content": "已联系快递核实", "created_at": "2026-10-11T11:00:00Z" }
        ],
        "created_at": "2026-10-11T10:00:00Z"
    }
}

---
POST /api/v1/cs/tickets/{ticket_no}/reply    // 追加回复（契约路径，前端 12.2 标注的待补接口）；兼容别名 POST /api/v1/tickets/{ticket_no}/reply
Authorization: Bearer {access_token}

Request: { "content": "补充一张照片", "images": ["https://cdn.example.com/ticket/2.jpg"] }

Response (200):
{ "code": 0, "data": { "reply_id": "rp_yyy" } }

---
POST /api/v1/cs/tickets/{ticket_no}/rate     // 满意度评价（契约路径）；兼容别名 POST /api/v1/tickets/{ticket_no}/rate
Authorization: Bearer {access_token}

Request:
{
    "satisfaction": 5,
    "tags": ["响应及时", "解答专业"],
    "comment": "客服很耐心，问题已解决"
}

Response (200):
{
    "code": 0,
    "data": { "rated": true }
}
```

### 10.4 智能客服

```
┌─────────────────────────────────────────────────────┐
│                  智能客服架构                         │
│                                                     │
│  用户消息 ──▶ 意图识别 ──▶ 知识库匹配 ──▶ 自动回复  │
│                 │                                   │
│                 ▼                                   │
│           意图置信度 < 0.8 ?                        │
│                 │                                   │
│           ┌─────┴─────┐                            │
│           ▼           ▼                            │
│       转人工客服    追问澄清                        │
│                                                     │
│  意图分类：                                         │
│  • 订单查询 (order_query)                          │
│  • 物流查询 (logistics_query)                       │
│  • 退款退货 (refund_request)                        │
│  • 商品咨询 (product_inquiry)                       │
│  • 账户问题 (account_issue)                         │
│  • 投诉建议 (complaint)                             │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 10.5 技术要点

| 要点 | 说明 |
|------|------|
| WebSocket网关 | 基于Go + Gorilla WebSocket，支持百万级长连接 |
| 消息可靠性 | 消息ID去重 + 发送确认 + 离线消息存储 |
| 智能路由 | 根据问题分类、客服技能、负载均衡分配客服 |
| 会话恢复 | WebSocket断线重连，恢复历史消息；重连采用指数退避+抖动，回前台时先调用 6.3.3 增量拉取对齐消息 |
| 后台限制 | iOS 应用退后台后长连接会被系统挂起或断开，实时性最终依赖 APNs；服务端必须容忍"长期离线→重连→补偿拉取"的模式 |
| 消息顺序 | 会话内消息按服务端 `seq` 单调递增，客户端按 `seq` 去重与补拉，避免乱序/重复展示 |
| 敏感词过滤 | 实时检测敏感词，自动拦截/替换 |
| 服务质量 | 会话满意度评价 + 响应时间统计 |

---

## 十一、性能优化

### 11.1 接口聚合（BFF层）

#### 11.1.1 BFF (Backend For Frontend) 架构

```
┌─────────────────────────────────────────────────────┐
│                   BFF 聚合层                          │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │ iOS BFF  │  │ Web BFF  │  │ MiniApp  │         │
│  │ Service  │  │ Service  │  │ BFF      │         │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘         │
│       │              │              │               │
│       └──────────────┼──────────────┘               │
│                      │                              │
│              ┌───────┴───────┐                      │
│              │  GraphQL      │                      │
│              │  Federation   │                      │
│              └───────┬───────┘                      │
│                      │                              │
│         ┌────────────┼────────────┐                 │
│         ▼            ▼            ▼                 │
│    ┌────────┐  ┌────────┐  ┌────────┐             │
│    │ Product│  │ Order  │  │ User   │             │
│    │Service │  │Service │  │Service │             │
│    └────────┘  └────────┘  └────────┘             │
└─────────────────────────────────────────────────────┘
```

#### 11.1.2 首页聚合接口

```
GET /api/v1/bff/ios/home
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "user_info": {
            "nickname": "电商达人",
            "avatar": "...",
            "level": 3,
            "unread_messages": 5,
            "cart_count": 3
        },
        "banners": [
            {"id": 1, "image": "...", "link": "easyshopping://promotion/1001"}
        ],
        "quick_entries": [
            {"name": "订单", "icon": "...", "link": "easyshopping://orders"},
            {"name": "优惠券", "icon": "...", "link": "easyshopping://coupons"},
            {"name": "客服", "icon": "...", "link": "easyshopping://cs"}
        ],
        "flash_sale": {
            "title": "限时秒杀",
            "end_time": "2026-10-11T12:00:00Z",
            "products": [
                {"product_id": 10086, "title": "...", "price": 8999.00, "image": "..."}
            ]
        },
        "recommendations": {
            "title": "猜你喜欢",
            "products": [
                {"product_id": 10086, "title": "...", "price": 8999.00, "image": "..."}
            ]
        },
        "notification": {
            "show": true,
            "title": "双11大促开启",
            "content": "全场满300减50"
        }
    }
}
```

> **与前端契约对齐**：`ios-frontend.md` 4.2 列出的 `GET /api/v1/home/config`、`/home/banners`、`/home/categories`、`/home/seckill`、`/home/recommend` 为 BFF 聚合的数据来源接口（可单独调试与降级）；客户端默认调用 `/api/v1/bff/ios/home` 一次性获取，弱网/降级场景可回退到分接口加载。两套接口返回的字段命名必须保持一致（banner/link、product/price 等），由 BFF 层统一转换，禁止出现同一字段两套命名。

#### 11.1.3 商品详情聚合接口

```
GET /api/v1/bff/ios/product/{product_id}
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "product": {
            "product_id": 10086,
            "title": "Apple iPhone 15 Pro",
            "main_image": "...",
            "images": [],
            "price": 8999.00,
            "original_price": 9999.00,
            "skus": []
        },
        "review_summary": {
            "score": 4.9,
            "total_count": 12800,
            "tags": []
        },
        "recommendations": {
            "similar": [],
            "accessories": []
        },
        "user_info": {
            "favored": false,
            "cart_count": 3
        },
        "delivery": {
            "estimated": "次日达"
        },
        "share": {
            "share_url": "...",
            "poster_url": "..."
        }
    }
}
```

### 11.2 CDN与图片优化

#### 11.2.1 CDN架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CDN 架构                                      │
│                                                                     │
│  ┌──────────┐                                                      │
│  │  iOS     │ ──▶ ┌──────────┐ ──▶ ┌──────────┐ ──▶ ┌─────────┐ │
│  │  Client  │      │ CDN Edge │      │ CDN Mid  │      │ Origin  │ │
│  └──────────┘      │ (就近)   │      │ (区域)   │      │ (源站)  │ │
│                    └──────────┘      └──────────┘      └─────────┘ │
│                                                                     │
│  图片格式自适应：                                                    │
│  • iOS 15+: WebP / HEIC                                             │
│  • iOS 14+: WebP                                                    │
│  • 其他: JPEG                                                       │
│                                                                     │
│  图片质量梯度：                                                      │
│  • 列表页: 质量75, 宽度750px                                        │
│  • 详情页: 质量85, 宽度1080px                                       │
│  • 查看大图: 质量95, 原始尺寸                                        │
└─────────────────────────────────────────────────────────────────────┘
```

#### 11.2.2 图片优化策略

| 策略 | 说明 |
|------|------|
| 格式选择 | 根据iOS版本自动选择WebP/HEIC/JPEG |
| 尺寸适配 | 根据屏幕尺寸请求对应分辨率图片 |
| 质量压缩 | 列表页质量75，详情页质量85 |
| 懒加载 | 图片URL携带懒加载参数，CDN端处理 |
| 渐进加载 | JPEG渐进式加载，提升感知速度 |
| 图片合并 | 小图标合并为雪碧图/WebP Sprite |
| 缓存策略 | CDN边缘缓存30天，客户端本地缓存7天 |

### 11.3 网络优化

| 优化项 | 方案 | 效果 |
|--------|------|------|
| HTTP/2 | 全链路HTTP/2，头部压缩，多路复用 | 减少连接数，提升并发 |
| 连接复用 | 连接池管理，Keep-Alive | 减少TCP握手开销 |
| 请求合并 | BFF层聚合多个微服务调用 | 减少请求数量 |
| 数据压缩 | Gzip/Brotli压缩传输数据 | 减少传输体积 |
| 预连接 | DNS预解析、TCP预连接、TLS预握手 | 减少首次请求延迟 |
| 离线包 | 静态资源（H5 页面、图片、字体）离线包，随 App 版本内置 + 配置热更新增量刷新 | 弱网体验优化；**只允许非可执行静态资源，包体需 HMAC 签名校验，禁止夹带 JS 业务逻辑补丁（Guideline 2.5.2）** |
| 差分更新 | 仅对 H5/静态资源做 bsdiff 差分，差异包带完整性校验 | 减少资源下载量；**iOS 二进制/代码不存在合法的差分热更通道**，App 本体更新一律走 App Store |
| 接口降级 | BFF 聚合接口设超时预算（单接口 P99 200ms、聚合 400ms），子调用超时即用缓存/降级数据 | 保证首屏可用性 |

### 11.4 数据库优化

| 优化项 | 方案 |
|--------|------|
| 读写分离 | MySQL主从复制，读请求走从库；下单/支付回调/库存等强一致场景强制走主库 |
| 分库分表 | 订单表分片键 `user_id`，**16 库 64 分片（终审裁决①「分片即实例」：P0 上线即固化分片规则，逻辑分片先行，终态 64 主分片各占 1 物理实例、加片即扩容）**；**月度维度由归档分区承担、不按月分表**（原「初期单库 + 按月逻辑分表、5000 万行再分库」旧口径作废）；读写分离 + 承载检索由 ES 兜底，与 `plans/00-架构总纲.md` 一致，路由层用 ShardingSphere 透明切换、业务代码不感知；主键使用雪花算法，禁止 AUTO_INCREMENT；分片键字段在所有订单相关表冗余，禁止跨库 JOIN |
| 索引优化 | 覆盖索引、联合索引、前缀索引 |
| SQL优化 | 慢查询监控，EXPLAIN分析，避免全表扫描 |
| 连接池 | HikariCP连接池，合理配置最大连接数 |
| 归档策略 | 3个月前订单归档到历史表 |

### 11.5 监控与告警

```
┌─────────────────────────────────────────────────────────────────────┐
│                       监控告警体系                                   │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │ Prometheus   │  │ Grafana      │  │ AlertManager │             │
│  │ 指标采集      │  │ 可视化面板    │  │ 告警通知      │             │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘             │
│         │                 │                  │                      │
│         └─────────────────┼──────────────────┘                      │
│                           │                                         │
│                    ┌──────┴───────┐                                 │
│                    │  Jaeger      │                                 │
│                    │  链路追踪      │                                 │
│                    └──────────────┘                                 │
│                                                                     │
│  监控指标：                                                          │
│  • QPS / P99延迟 / 错误率                                           │
│  • JVM内存 / GC频率 / 线程数                                         │
│  • Redis命中率 / 连接数 / 慢查询                                     │
│  • MySQL QPS / 慢查询 / 主从延迟                                    │
│  • RabbitMQ队列深度 / 消费速率                                       │
│  • APNs推送成功率 / 延迟                                             │
└─────────────────────────────────────────────────────────────────────┘
```

**SLA 分级（避免"一刀切"指标无法落地）：**

| 等级 | 接口范围 | 目标 |
|------|----------|------|
| P0 | 登录、下单、支付、支付回调 | P99 < 200ms，可用性 **99.9%**（千万 DAU 一期口径） |
| P1 | 商品列表/详情、搜索、订单列表 | P99 < 300ms，可用性 99.9% |
| P2 | BFF 聚合、推荐、消息、客服 | P99 < 400ms，可用性 99.5% |

> **可用性口径（v1.4 千万 DAU 新口径，覆盖原架构裁决 09 §8 #4）**：
> - **P0 一期 = 99.9%**（年故障 ≤ 8.76 小时），与整体目标一致；
> - **P1 阶段完成同城双活后 P0 升 99.95%**（年 ≤ 4.38 小时）；**若业务方书面要求 99.95%，则必须先完成 P1 同城双活再对外承诺**；
> - **99.99%（年 52.56 分钟）为亿级方案（1 亿在线）口径，已随 `architecture/` 12 份文档删除而废弃**，不再作为任何验收依据；
> - P1/P2 的预算归属与"是否计入全局可用性"（P2 明确不计入）以 `plans/00-架构总纲.md` 可用性章节为唯一 SLO 表（原 `architecture/09-performance-testing.md` §3.2，见 git 历史 v1）；
> - 容量口径同步降级：**网关入口 60 万 QPS / 压测目标 75 万 QPS / 应用读 50 万写 8 万 / 订单峰值 5 万笔/s**（原 630 万 / 756 万 QPS 为亿级口径，已作废）。

**业务与客户端质量指标（与 11.6 埋点服务联动）：**

- 业务：支付成功率、下单转化率、推送点击率、更新升级率、分享转化率；
- 客户端：崩溃率（目标 < 0.05%）、启动耗时 P90、请求成功率、弱网失败率、卡顿率；
- 告警：核心指标突变（如支付成功率下跌 > 1%）5 分钟内触达值班，配套一键暂停灰度 / 配置回滚预案。

### 11.6 埋点与数据服务

#### 11.6.1 采集与处理链路

```
iOS SDK（批量上报、本地缓冲、断电续传）
   │  事件：曝光 / 点击 / 停留 / 搜索 / 下单漏斗 / 崩溃 / 性能
   ▼
API Gateway（鉴权、去重、限流、脱敏前置）
   ▼
Kafka ──▶ Flink（实时：漏斗、实时大屏、风控特征）
      └─▶ 数仓（离线：画像、报表，供管理端 admin-data 复用）
```

#### 11.6.2 埋点合规要求

| 要求 | 说明 |
|------|------|
| 授权前置 | 未获得用户授权（隐私弹窗 / ATT）前，仅采集必要行为事件，不得采集 IDFA / 精确位置 |
| 最小化 | 事件字段白名单化，禁止采集密码、支付信息、聊天内容等敏感数据 |
| 可关闭 | 用户可在隐私设置（12.1.2）关闭个性化推荐与行为分析，关闭后停止上报非必要事件 |
| 匿名化 | 分析侧使用匿名 ID，与账号 ID 的映射单独受控保存，保留期到期自动清理（与 12.1.1 的 90 天策略一致） |
| 一致性 | 事件命名与 `ios-frontend.md` 埋点 SDK 约定一致，schema 版本化管理，新增字段需评审 |

---

## 十二、合规服务

### 12.1 隐私政策合规

#### 12.1.1 数据分类与保护

| 数据类别 | 示例 | 保护级别 | 处理方式 |
|----------|------|----------|----------|
| 个人身份信息 | 姓名、手机号、身份证号 | 高 | 加密存储，脱敏展示 |
| 设备信息 | IDFA、IDFV、设备型号 | 中 | 需用户授权，可重置 |
| 交易信息 | 订单号、支付金额、收货地址 | 高 | 加密存储，访问审计 |
| 行为数据 | 浏览记录、搜索记录、点击行为 | 中 | 匿名化存储，90天过期 |
| 生物识别 | Face ID/Touch ID密钥 | 极高 | 仅存储公钥，私钥不出设备 |
| 位置信息 | GPS定位、IP地址 | 高 | 需用户授权，可随时关闭 |
| 广告标识 | IDFA | 高 | 仅 ATT（App Tracking Transparency）授权后采集，未授权上报全零 UUID |
| 第三方SDK数据 | 微信/支付宝/地图/统计 SDK | 中 | 在隐私政策与隐私清单（PrivacyInfo.xcprivacy）中逐项披露 |

**落地要求**：数据分类中的「保留期限」必须由可执行的清理任务兑现——行为数据 90 天匿名化、日志 180 天、注销账号数据 15 天内删除（法定留存的交易/发票数据除外），清理任务需有执行记录与对账告警，避免"策略写了、库还在"。

#### 12.1.2 隐私合规API

```
GET /api/v1/privacy/policy
Authorization: 无（**免登录白名单接口**，见 12.6.1；首次启动隐私弹窗与未登录态必须可访问，登录后仅追加返回该账号的同意版本）

Response (200):
{
    "code": 0,
    "data": {
        "version": "2026-10-01",
        "title": "隐私政策",
        "content": "HTML内容...",
        "effective_date": "2026-10-01",
        "sections": [
            {
                "title": "我们收集的信息",
                "content": "..."
            },
            {
                "title": "信息使用方式",
                "content": "..."
            },
            {
                "title": "信息共享",
                "content": "..."
            },
            {
                "title": "您的权利",
                "content": "..."
            }
        ]
    }
}

---
GET /api/v1/privacy/data-export
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "export_id": "exp_xxx",
        "status": "processing",
        "estimated_ready": "2026-10-11T12:00:00Z",
        "download_url": null
    }
}

---
DELETE /api/v1/privacy/account-data
Authorization: Bearer {access_token}

Request:
{
    "reason": "用户注销",
    "verification_code": "123456"
}

Response (200):
{
    "code": 0,
    "data": {
        "deletion_id": "del_xxx",
        "status": "scheduled",
        "completed_at": "2026-10-18T00:00:00Z"
    }
}

---
GET /api/v1/privacy/consent-settings
Authorization: Bearer {access_token}

Response (200):
{
    "code": 0,
    "data": {
        "advertising_tracking": false,
        "location_tracking": false,
        "personalized_recommendation": true,
        "push_notification": true,
        "data_sharing_third_party": false,
        "updated_at": "2026-10-01T10:00:00Z"
    }
}

---
PUT /api/v1/privacy/consent-settings
Authorization: Bearer {access_token}

Request:
{
    "advertising_tracking": true,
    "personalized_recommendation": true
}

Response (200):
{
    "code": 0,
    "data": { "updated": true }
}
```

**隐私合规数据模型：**

```sql
-- 用户同意记录表（合规审计：证明"何时、以何版本的隐私政策/用户协议获得了用户同意"）
CREATE TABLE `privacy_consent_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `device_id` VARCHAR(64) DEFAULT '',
  `consent_type` VARCHAR(32) NOT NULL COMMENT 'privacy_policy/user_agreement/att_push/location/personalization',
  `policy_version` VARCHAR(32) NOT NULL,
  `granted` TINYINT NOT NULL DEFAULT 1,
  `source` VARCHAR(32) DEFAULT '' COMMENT 'first_launch/settings/share',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_type` (`user_id`, `consent_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隐私同意记录表';

-- 数据导出/删除任务表
CREATE TABLE `privacy_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `task_type` TINYINT NOT NULL COMMENT '1数据导出 2账户注销/数据删除',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0处理中 1已完成 2已取消 3失败',
  `verify_channel` VARCHAR(32) DEFAULT '' COMMENT '短信/生物识别二次验证方式',
  `result_url` VARCHAR(512) DEFAULT NULL COMMENT '导出文件下载链接（签名URL、24小时过期、仅本人可下载）',
  `error_msg` VARCHAR(256) DEFAULT '',
  `scheduled_at` DATETIME DEFAULT NULL,
  `completed_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隐私任务表（导出/删除）';
```

> 说明：数据导出文件必须加密打包、签名 URL、24 小时过期且仅账号本人可下载；账户注销执行前需校验无进行中的订单/售后/提现，冷静期内可撤销，冷静期结束触发跨服务删除（含 MQ 广播 `user.deleted`），并保留法定必须留存的交易与发票数据。
> **跨端待办**：`user.deleted` 事件当前仅在本文件定义，需在 `admin-backend.md` 1.4 同步登记（买家端为生产者、管理端 `core-user`/`admin-data` 为消费者），否则管理端主数据与审计库会出现"注销后仍保留个人数据"的合规缺口——该项列入 13.5 上线 Checklist。

### 12.2 数据加密

#### 12.2.1 加密策略

| 数据 | 加密方式 | 密钥管理 |
|------|----------|----------|
| 传输数据 | TLS 1.3 + 证书固定 | 自动证书轮换 |
| 密码 | bcrypt/Argon2id | 无（单向哈希） |
| 手机号 | AES-256-GCM | KMS密钥管理 |
| 身份证号 | AES-256-GCM + 令牌化 | KMS密钥管理 |
| 支付数据 | Apple Pay Token + 端到端加密 | Apple管理 |
| 日志数据 | 敏感字段脱敏后存储 | - |
| 备份数据 | AES-256加密 | 异地密钥存储 |

#### 12.2.2 密钥管理架构

```
┌─────────────────────────────────────────────────────┐
│                  密钥管理系统 (KMS)                    │
│                                                     │
│  ┌──────────────┐    ┌──────────────┐              │
│  │ 应用层密钥    │    │ 数据层密钥    │              │
│  │ (每应用独立)  │    │ (每表独立)    │              │
│  └──────┬───────┘    └──────┬───────┘              │
│         │                   │                       │
│         └─────────┬─────────┘                       │
│                   │                                 │
│           ┌───────┴───────┐                         │
│           │  Root Key     │                         │
│           │  (HSM保护)    │                         │
│           └───────────────┘                         │
│                                                     │
│  密钥轮换策略：                                      │
│  • 应用层密钥：每90天自动轮换                        │
│  • 数据层密钥：每180天自动轮换                       │
│  • Root Key：每年手动轮换                            │
└─────────────────────────────────────────────────────┘
```

### 12.3 App Transport Security (ATS)

#### 12.3.1 ATS配置

```xml
<!-- Info.plist ATS 配置：全站启用 ATS，不配置任何例外域名 -->
<key>NSAppTransportSecurity</key>
<dict>
    <!-- 默认强制：HTTPS + TLS 1.2+ + 前向保密加密套件；iOS 13+ 可显式声明仅允许 TLS 1.3 -->
    <key>NSAllowsArbitraryLoads</key>
    <false/>
    <key>NSAllowsLocalNetworking</key>
    <false/>
</dict>
```

> **配置原则**：
> 1. **不配置 `NSExceptionDomains` 例外**——所有业务域名（`easyshopping.com`）、CDN、第三方 SDK 域名（支付回调、客服 WebSocket、埋点、地图）都必须 HTTPS + TLS 1.2 以上 + 前向保密。**当前配置零例外**；若未来确需引入历史遗留域名例外，须单独申请豁免说明、最小粒度（`NSExceptionMinimumTLSVersion=TLSv1.2`）并写入审核备注与下版本整改计划；
> 2. 若某个第三方 SDK 必须使用例外（如老图片域名），例外粒度最小化、仅 `NSExceptionMinimumTLSVersion=TLSv1.2`，并在下个版本推动供应商整改；`NSAllowsArbitraryLoads=true` 会导致审核问询（Guideline 2.5.x / ITMS-90809），需要在提交时填写用途说明；
> 3. WebSocket 全部使用 `wss://`（见 10.2），APNs/Apple Pay 回调域名不做例外；
> 4. 证书固定（SSL Pinning）与 ATS 配合使用：Pin 证书链（leaf + backup key），校验失败时**允许降级到系统校验并上报告警**（fail-open），避免证书轮换/劫持场景导致全量用户无法联网；备份公钥随 App 内置，轮换时新旧并存。
> 5. App 内嵌 WKWebView 同样受 ATS 约束，H5 兜底页面（**仅指 9.5 远程配置下发的活动页/运营页 URL**，与代码热修复无关）必须全 HTTPS，否则会白屏。

#### 12.3.2 ATS安全检查清单

| 检查项 | 要求 | 状态 |
|--------|------|------|
| TLS版本 | 最低TLS 1.2，推荐TLS 1.3 | 合规 |
| 证书 | 受信任CA签发，有效期验证 | 合规 |
| 加密套件 | 前向保密（Forward Secrecy） | 合规 |
| HTTP禁止 | 禁止明文HTTP请求 | 合规 |
| 证书固定 | 关键API证书固定（SSL Pinning） | 合规 |
| WebSocket | WSS加密连接 | 合规 |

### 12.4 App Store审核合规

| 合规项 | 要求 | 实现方案 |
|--------|------|----------|
| 登录方式 | App 提供第三方登录（微信/QQ等）时必须同时提供 Sign in with Apple（Guideline 4.8） | 已实现（2.1.5） |
| 隐私标签 | App Store Connect 隐私标签与隐私清单（PrivacyInfo.xcprivacy）必须与实际收集一致 | 与 12.1 数据分类联动维护 |
| 数据使用 | 明确告知数据用途，获取用户同意 | 首次启动弹窗 + 同意记录表 |
| 账户删除 | 提供应用内删除账户功能（Guideline 5.1.1(v)） | 我的-设置-账户安全-注销账户（5.2.5），冷静期后执行 |
| 评分引导 | 使用 SKStoreReviewController，不强制引导、不打断流程 | 订单完成后可选评价 |
| 内购规范 | 虚拟商品必须使用 Apple IAP，且不得引导外部支付（3.1.1） | 会员/积分走 IAP（7.3.2），实物走 Apple Pay/支付宝/微信 |
| 内购必备入口 | 必须提供"恢复购买" | `/api/v1/payments/iap/restore` |
| 热更新 | 禁止下载可执行代码（2.5.2） | 仅配置级热更新（9.4），代码缺陷走发版 |
| 强制更新 | 不得以强更阻碍用户使用已付费功能 | 仅安全/资损场景强更，其余可跳过（9.2） |
| 深链与 AASA | Universal Link 配置正确，避免审核时无法演示 | 8.2 托管与自检清单 |
| 内容安全 | UGC（评价/客服/晒图）内容审核机制 | 机器审核（敏感词+图片鉴黄）+ 人工复审 |
| 审核账号 | 提供可登录的演示账号 | 内测账号 + 审核备注说明 |

### 12.5 数据脱敏规则

```sql
-- 手机号脱敏
138****8000

-- 身份证号脱敏
110***********1234

-- 银行卡号脱敏
6222 **** **** 1234

-- 邮箱脱敏
u***@example.com

-- 地址脱敏
北京市朝阳区****号

-- 姓名脱敏
张**
```

### 12.6 接口安全设计

#### 12.6.1 接口签名与防重放

| 措施 | 说明 |
|------|------|
| 请求签名 | 私有业务接口统一带 `X-App-Key` / `X-Timestamp` / `X-Nonce` / `X-Sign = HMAC-SHA256(sortedParams, device_secret)`，网关校验时间窗口（**±5 分钟**）与签名 |
| 防重放 | `X-Nonce` 在 Redis 中做短窗口去重（**TTL 10 分钟**，必须 ≥ 签名时间窗），同一 Nonce 直接拒绝 |
| 设备绑定 | `device_secret` 登录时下发并存 Keychain；换设备需重新登录，禁止在多个设备复用 |
| 敏感接口 | 支付、改绑、注销、领券等接口额外要求重签 + 风控校验 + 二次验证 |
| 白名单 | AASA、隐私政策、App Clip 元数据等公开接口无需签名，但必须限流并禁止越权返回他人数据 |

> **时间窗口口径（架构裁决 C3）**：签名窗 **±5 分钟** / Nonce TTL **10 分钟**，与 `android-backend.md` 13.1 一致（本文件原为 ±60s / TTL 120s）；网关按 `X-App-Key` **可配窗口**，兼容老版本客户端（90 天过渡期后统一切换到 ±5 分钟）。Nonce TTL 不得小于签名时间窗，否则窗口内重放可绕过去重。

#### 12.6.2 越权与数据保护

1. 所有按 ID 访问的资源（订单、地址、优惠券、工单、售后、分享）必须校验 `user_id` 归属，禁止仅凭 ID 直接读取（防横向越权/IDOR）；
2. 接口返回遵循最小化原则：手机号、地址、身份证在列表类接口一律脱敏（12.5），明文仅在必要场景按需返回；
3. 服务间调用走内网 + mTLS，禁止公网暴露 gRPC；管理端与买家端共用服务时按 API Key + 租户隔离；
4. 文件上传（头像、评价图、客服附件）必须：类型白名单（jpg/png/heic/webp）、大小限制（≤10MB）、内容检测（EXIF 清除 + 鉴黄 + 图片二次渲染防马牛）、私有桶 + 签名 URL 访问；
5. 日志与审计：登录、支付、改绑、注销、导出等敏感操作全量审计（操作者、IP、设备、结果），日志中敏感字段脱敏；审计日志只增不改。

#### 12.6.3 密钥与凭据管理

| 凭据 | 存放 | 轮换与审计 |
|------|------|-----------|
| APNs .p8 签名密钥 | KMS/Secrets Manager | 按 Apple 轮换流程双密钥过渡，禁止提交代码仓库与镜像 |
| Apple Pay Merchant 证书 | KMS | 到期前 30 天告警，按收单机构流程轮换 |
| 支付渠道密钥（支付宝/微信） | KMS | 双人复核变更，定期审计 |
| 数据加密主密钥（12.2） | HSM/KMS | 90/180 天轮换，支持历史密文解密 |
| JWT 签名私钥 | KMS | 与 2.2.2 `kid` 轮换机制配合 |

---

## 十三、项目里程碑与排期

### 13.1 总体时间线

> 总工期 **36 周（约 9 个月）**，与 13.2 各阶段周次（W1-W36）一致；iOS 客户端（`ios-frontend.md`，约 19 周）与本后端计划并行推进，依赖接口的部分由后端先出契约文档 + Mock 服务解耦。
>
> **实施手册三要素（v1.4 补齐）**：① **阶段任务分解**——13.2 各阶段已按周（W1-W36）列出可交付任务，13.6 给出 P0 一期门禁与**审核提审节点**；② **验收标准**——13.2 每张表新增「验收标准」列（功能 + 性能 + 审核三类可验证条件），上线总验收见 13.5；③ **依赖与前置**——13.2 新增「依赖与前置」列，外部 Apple 证书/账号/资质与跨端接口依赖汇总于 13.6。

```
月份    M1(11月) M2(12月) M3(1月)  M4(2月)  M5(3月)  M6(4月)  M7(5月)  M8(6月)  M9(7月)
       ┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
阶段1  │ 架构设计 │ 基础设施 │         │         │         │         │         │         │         │
       │ 环境搭建 │ 数据库   │         │         │         │         │         │         │         │
       ├─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┤
阶段2  │         │         │ 核心服务 │ 商品/搜索│ 订单/支付│         │         │         │         │
       │         │         │ 认证授权 │ 推荐服务 │ 物流服务 │         │         │         │         │
       ├─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┤
阶段3  │         │         │         │         │         │ 会员/消息│ 客服/分享│         │         │
       │         │         │         │         │         │ APNs推送 │ 更新服务 │         │         │
       ├─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┤
阶段4  │         │         │         │         │         │         │         │ 性能优化 │ 合规审计 │
       │         │         │         │         │         │         │         │ 压测调优 │ 上线准备 │
       ├─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┼─────────┤
里程碑 │   M1    │   M2    │   M3    │   M4    │   M5    │   M6    │   M7    │   M8    │   M9    │
       │ 框架完成│ 核心完成│ 商品完成│ 交易完成│ 服务完成│ 会员消息│ 客服分享│ 优化完成│  上线   │
       └─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
```

### 13.2 详细排期

#### 阶段一：架构设计与基础设施（M1-M2，8周）

| 周次 | 任务 | 产出 | 人力 | 依赖与前置 | 验收标准 |
|------|------|------|------|------------|----------|
| W1-W2 | 架构设计、技术选型、环境搭建 | 架构文档、K8s集群、CI/CD流水线 | 架构师2人 | 容量口径以 `plans/00-架构总纲.md` 为准并冻结；服务器/配额就位 | 架构评审通过（含 16 库 64 表、60 万 QPS 入口分配）；CI/CD 首次构建 + 部署成功 |
| W3-W4 | 数据库设计与搭建、Redis集群 | MySQL主从、Redis Cluster、ES集群 | DBA 1人 + 后端2人 | 分片方案与 Redis 40~60 节点资源申请审批通过 | DDL 迁移脚本可重复执行；主从切换演练通过；Redis 命中率基线 ≥ 90% |
| W5-W6 | API Gateway、认证授权服务 | Gateway部署、认证服务上线 | 后端2人 | `edge-gateway` 统一接入与限流策略口径；签名窗口 ±5min / Nonce TTL 10min 规范（12.6） | 签名/防重放用例全绿；登录链路 P99 < 200ms；网关按 60 万 QPS 口径完成容量分配 |
| W7-W8 | 用户服务、设备管理服务 | 用户CRUD、设备绑定API | 后端2人 | `ios-frontend.md` 附录 A 契约冻结（3.x / 11.2） | 契约测试通过；`/device/*` 与前端接口清单逐条对齐；**M1 里程碑评审通过** |

#### 阶段二：核心服务开发（M3-M5，10周，**P0 一期核心链路**）

| 周次 | 任务 | 产出 | 人力 | 依赖与前置 | 验收标准 |
|------|------|------|------|------------|----------|
| W9-W10 | 商品服务、搜索服务 | 商品列表/详情/搜索API | 后端3人 | M1 通过；商品中台/类目数据初始化 | 契约测试通过；列表 P99 < 300ms（11.5 P1 档） |
| W11-W12 | 推荐服务 | 首页推荐、相似商品API | 后端2人 + 算法1人 | 埋点数据链路（11.6）可产出样本 | BFF 首页聚合接口 P99 < 400ms；降级开关生效 |
| W13-W14 | 订单服务、库存服务 | 下单/订单列表/购物车API | 后端3人 | 订单 8 值状态机与 `admin-backend.md` 4.3 一致；`pay_deadline` 字段口径（4.4）；幂等键规范 | 下单链路 P99 < 200ms；状态机回归用例全绿；**W14 交易链路提测（前端联调门禁）** |
| W15-W16 | 支付服务 | 支付宝/微信/Apple Pay对接 | 后端2人 | **Apple Developer 账号、Merchant ID、支付资质、沙箱环境**；三方支付商户资质审核通过 | 回调验签 + 金额二次校验用例通过；支付成功率看板上线；IAP 四接口联调通过 |
| W17-W18 | 物流服务 | 物流查询/轨迹API | 后端1人 | 三方物流接口契约与密钥 | 轨迹查询可用性 99.9%；**M2/M3-M5 核心交易链路全量提测通过** |

#### 阶段三：支撑服务开发（M6-M7，8周）

| 周次 | 任务 | 产出 | 人力 | 依赖与前置 | 验收标准 |
|------|------|------|------|------------|----------|
| W19-W20 | 会员服务、积分体系 | 个人中心/积分/优惠券API | 后端2人 | IAP 四接口（verify/transactions/entitlements/Server Notifications V2）就绪 | 会员等级与积分账务对账 0 差异；沙箱购买链路通过 |
| W21-W22 | 消息服务、APNs推送 | 消息中心/APNs推送网关 | 后端2人 | **APNs .p8 密钥 / 生产证书入库**；设备绑定接口（W7-W8）已上线 | 推送成功率 ≥ 95%、payload ≤ 4KB、410 清理与 429 降速用例通过 |
| W23-W24 | 客服服务 | WebSocket客服/工单系统 | 后端2人 | 长连接网关容量按 200 万峰值在线分配 | 心跳/重连/去重用例通过；单连接 P99 消息延迟 < 1s |
| W25-W26 | 分享服务、更新服务、埋点服务 | 分享链接/海报/版本检查与配置热更新/埋点采集链路 | 后端2人 | **AASA 域名托管与证书**（16.4 前端部署清单）、`user.deleted` 在 admin-backend 1.4 登记 | AASA 自检通过；仅配置级热更新（9.4 合规红线）；**M6/M7 支撑服务提测通过** |

#### 阶段四：优化与上线（M8-M9，10周）

| 周次 | 任务 | 产出 | 人力 | 依赖与前置 | 验收标准 |
|------|------|------|------|------------|----------|
| W27-W28 | 性能优化、BFF层 | 首页聚合/详情聚合接口 | 后端2人 | 阶段二/三全部接口冻结 | 聚合接口 P99 < 400ms；接口数与字段口径与前端附录 A 一致 |
| W29-W30 | 全链路压测、性能调优 | 压测报告、优化方案 | 全员 | 预发环境与压测数据构造；口径见 13.5（60 万 / 75 万 QPS） | 达成网关入口 60 万 QPS、压测目标 75 万 QPS、订单峰值 5 万笔/s；P0 P99 < 200ms |
| W31-W32 | 合规服务、安全审计 | 隐私政策/数据加密/ATS | 安全1人 + 后端1人 | 隐私清单、隐私标签、备案与资质材料 | 渗透测试通过（12.6 用例）；隐私清单与隐私标签一致 |
| W33-W34 | Bug修复、灰度发布 | 灰度方案、回滚预案 | 全员 | 客户端灰度分桶（2.5）与监控看板就绪 | 灰度期间崩溃率 < 0.1%、下单成功率环比跌幅 < 1%；回滚演练通过 |
| W35-W36 | 全量上线、监控完善 | 上线checklist、监控面板 | 全员 | **App Store 审核通过（提审节点见 13.6）**、13.5 Checklist 全部勾选 | 13.5 全项通过；监控告警配置完成、值班就绪；**M9 上线评审通过** |

### 13.3 团队配置

| 角色 | 人数 | 职责 |
|------|------|------|
| 技术负责人 | 1 | 架构设计、技术决策、进度把控 |
| Apple开发者 | 1 | 开发者账号管理、证书/描述文件维护、App Store Connect操作 |
| 后端开发 | 8 | 各服务开发 |
| 算法工程师 | 2 | 推荐算法、搜索排序 |
| 前端开发 | 3 | iOS客户端开发 |
| 测试工程师 | 3 | 功能测试、性能测试、自动化测试 |
| 运维工程师 | 2 | K8s运维、监控、CI/CD |
| 产品经理 | 2 | 需求管理、验收 |
| UI设计师 | 1 | 交互设计、视觉设计 |
| 安全工程师 | 1 | 安全审计、合规检查 |
| **合计** | **24** | - |

### 13.4 风险与应对

| 风险 | 概率 | 影响 | 应对措施 |
|------|------|------|----------|
| Apple Pay审核延迟 | 中 | 高 | 提前准备备用支付方案 |
| APNs证书过期 | 低 | 高 | 证书到期自动提醒+续期 |
| 性能不达标 | 中 | 高 | 提前压测，预留优化时间 |
| 人员变动 | 中 | 中 | 代码审查+文档完善+知识共享 |
| 需求变更 | 高 | 中 | 敏捷迭代，MVP优先 |
| 第三方服务故障 | 低 | 高 | 多供应商备份+降级方案 |
| App Store审核被拒 | 中 | 中 | 提前审核 guidelines，预留修改时间 |
| 热更新合规风险 | 中 | 高 | 严格限定配置级热更新（9.4），杜绝任何可执行代码下发；上线前合规评审 checklist |
| AASA/Universal Links 配置错误 | 中 | 中 | 上线前用校验工具自检；AASA 与路由表同源生成，避免不一致 |
| IAP 审核/沙箱问题 | 中 | 中 | 提前在沙箱环境联调恢复购买与 Server Notifications，准备审核说明视频 |
| 强更策略引发拒审 | 中 | 高 | 强更仅用于安全/资损场景，其余可跳过；文案说明原因 |
| 数据合规（个保法/隐私标签） | 低 | 高 | 隐私清单与实际收集联动评审，SDK 变更需合规检查 |
| APNs 推送大面积失败 | 低 | 中 | 推送依赖降级为站内信 + 增量拉取兜底（6.3.3），监控推送成功率 |

### 13.5 上线Checklist

- [ ] 所有API通过Postman/Apifox自动化测试（契约测试与 `ios-frontend.md` 接口清单逐条对齐，含兼容别名）
- [ ] 全链路压测通过（`10000+` 为**单服务实例口径**，保留；**平台级口径按千万 DAU：网关入口 60 万 QPS、压测目标 75 万 QPS（大促预测峰值 ×125%，取 120% 下限向上取整）**，应用读 50 万 / 写 8 万 QPS、订单峰值 5 万笔/s，口径唯一真值见 `plans/00-架构总纲.md`；原「630 万 / 756 万 QPS」为亿级方案口径，已随 `architecture/00-conflict-register.md` 删除而作废；按 11.5 SLA 分级验收）
- [ ] 核心接口 P99延迟 < 200ms（聚合接口 < 400ms）
- [ ] 错误率 < 0.01%
- [ ] 安全渗透测试通过（含越权/重放/回调验签用例，见 12.6）
- [ ] 隐私政策合规审查通过（隐私清单 PrivacyInfo.xcprivacy 与 App Store 隐私标签一致）
- [ ] ATT 权限弹窗与 IDFA 采集逻辑验收（未授权不采集）
- [ ] 热更新合规评审通过（确认无任何可执行代码下发通道，见 9.4）
- [ ] 更新渠道复核：仅 App Store / TestFlight，无任何面向公众的企业证书分发（见 9.1）
- [ ] 与 `ios-frontend.md` 附录 A（A.1 一致 / A.2 已改 / A.3 待补 / A.4 全局不一致）逐条核销，契约测试全绿
- [ ] 订单状态机 8 值枚举与 `admin-backend.md` 4.3 一致，前端 Tab 映射回归用例通过
- [ ] IAP 四接口（verify / transactions / entitlements / Server Notifications V2）联调通过，会员/积分功能方可上线
- [ ] 品牌标识逐字一致：BundleID / 域名 / URL Scheme / AASA appID / merchantId 与 `ios-frontend.md` `BrandConfig` 比对
- [ ] App Clip 提供与否已决策，AASA `appclips` 段与实际能力一致
- [ ] `user.deleted` 事件已在 `admin-backend.md` 1.4 登记并联调（注销数据跨端删除）
- [ ] AASA 文件已托管并通过校验工具自检（含 `/s/*` 分享短链路径）
- [ ] APNs 生产证书 / .p8 密钥已入库并配置告警
- [ ] IAP 沙箱验收完成（购买、恢复购买、Server Notifications、退款回收）
- [ ] 支付回调验签与金额校验用例通过
- [ ] 账号注销（删除）与数据导出流程验收通过
- [ ] App Store审核材料准备完成（含强更/热更新说明）
- [ ] 灰度发布方案就绪（一致性哈希分桶 + 指标看板）
- [ ] 回滚预案就绪（配置回滚、灰度回滚、MQ 消费回滚）
- [ ] 监控告警配置完成（含客户端崩溃率/启动耗时）
- [ ] 值班安排就绪
- [ ] 用户文档/FAQ更新完成

### 13.6 实施手册：P0 一期门禁、提审节点与依赖前置（v1.4 新增）

#### 13.6.1 阶段门禁与审核提审节点

> 每个门禁（G*）为**可验证的放行条件**：功能 + 性能 + 审核三类同时满足才允许进入下一阶段；任一不达标回滚到上一阶段补课，不顺延后续门禁。

| 门禁 | 周次 | 可交付 | 验收标准（功能 / 性能 / 审核） | 依赖与前置 |
|------|------|--------|-------------------------------|------------|
| G0 契约冻结 | W2 | OpenAPI 契约 + Mock 服务 | `ios-frontend.md` 附录 A 逐条核销、双侧签字冻结 | `plans/00-架构总纲.md` 容量与技术口径冻结 |
| G1 基础设施就绪 | W8 | M1：网关/认证/用户/设备 | 功能：`/device/*` 契约测试全绿；性能：登录 P99 < 200ms、网关完成 60 万 QPS 容量分配；审核：无 | K8s/MySQL 16 库 64 表/Redis 40~60 节点资源就位 |
| G2 交易链路提测 | W14 | 下单/订单/库存接口提测 | 功能：8 值状态机回归全绿、幂等用例通过；性能：下单 P99 < 200ms；审核：无 | `pay_deadline` 口径（4.4）、前端 8.x/9.x 契约冻结 |
| G3 核心链路联调通过 | W18 | M2/M3-M5：商品+交易全量 | 功能：支付回调验签/金额校验通过；性能：核心接口错误率 < 0.01%；审核：Apple Pay 资质与沙箱可用 | 三方支付商户资质审核通过、Apple Merchant ID |
| G4 支撑服务齐备 | W26 | M6/M7：会员/推送/客服/分享/更新 | 功能：IAP 四接口 + APNs 推送 + AASA 自检通过；性能：推送成功率 ≥ 95%；审核：热更新合规评审通过（9.4） | APNs .p8、AASA 域名托管、IAP 付费协议签署 |
| G5 压测验收 | W30 | 全链路压测报告 | 功能：压测场景覆盖主链路；性能：**网关入口 60 万 QPS / 压测目标 75 万 QPS / 订单峰值 5 万笔/s**、P0 可用性 99.9% 口径达标；审核：无 | 预发环境、压测数据构造、容量口径以 `plans/00-架构总纲.md` 为准 |
| G6 安全合规 | W32 | 渗透报告 + 隐私材料 | 功能：越权/重放/回调验签用例通过；性能：无；审核：隐私清单=隐私标签、ATS/脱敏/日志合规 | 备案与资质材料、安全评审排期 |
| G7 灰度放量 | W34 | 灰度 5%→20%→50% | 功能：核心漏斗无回归；性能：崩溃率 < 0.1%、下单成功率环比跌幅 < 1%；审核：无 | 前端分桶（`ios-frontend.md` 2.5）、监控看板、回滚预案 |
| **G8 提交 App Store 审核** | **W34 末** | **提审包 + 审核说明** | 功能：13.5 Checklist 除"审核通过"外全项通过；性能：审核机网络劣化下首屏可接受；审核：**审核材料齐备（16.3）、IAP 沙箱演示、强更/热更新说明、隐私问卷与实际采集一致** | 前端提审；后端需保证**审核期账号资质、沙箱环境、AASA 生产可达**；预留 1-2 周审核时长 |
| G9 审核通过与全量 | W35-W36 | M9 全量上线 | 功能：13.5 全项勾选；性能：线上监控 P0 P99/可用性达标；审核：**App Store 审核通过并完成首次发版** | 值班安排、回滚预案、用户 FAQ |

#### 13.6.2 依赖与前置清单

**（a）Apple 证书 / 账号 / 资质依赖（外部、不可压缩，最早期启动）**

| 依赖项 | 用途 | 需到位时间 | 责任人 | 缺失影响 |
|--------|------|------------|--------|----------|
| Apple Developer Program 账号 + Team ID | 签名、TestFlight、提审 | W1（立即） | Apple开发者1人 | 无法构建/分发，全部里程碑阻塞 |
| 证书 / 描述文件 / Push 签名（APNs .p8） | 推送、CI 自动签名 | W1（证书类）、W21（.p8 入库+告警） | Apple开发者1人 | 推送不可用、CI 分发失败 |
| Merchant ID + Payment Processing 证书 | Apple Pay | W15 前 | 后端支付负责人 | 支付服务（W15-W16）无法提测 |
| IAP 协议（付费协议）+ 商品 ID 配置 | 会员/积分内购 | W19 前 | Apple开发者1人 + 产品 | IAP 四接口联调与沙箱验收阻塞 |
| AASA / UL 域名托管与生效（`/s/*`、appclips 段） | 分享直达、App Clip | W25 前、上线前复检 | 运维 + 后端 | 分享回跳失败、App Clip 决策无法闭环 |
| App 备案 + 隐私标签 + 隐私清单 | 中国区上架 | W31 前 | 安全/合规1人 | 无法上架或被下架 |
| **App Clip 是否提供** | 双端阶段一决策项 | **W2 门前必须书面决策** | 产品 + 技术负责人 | AASA `appclips` 段与 Target 无法冻结 |

**（b）后端接口依赖状态（对 `ios-frontend.md` 的就绪承诺）**

| 接口域 | 契约就绪 | 联调就绪 | 支撑的前端阶段 | 状态 |
|--------|----------|----------|----------------|------|
| 认证/设备/隐私（2.x、11.2、12.1） | W2 | W8（G1） | 前端阶段一 | W1 出契约即冻结 |
| 首页 BFF / 商品 / 搜索（3.x、11.1） | W2（Mock） | W12 | 前端阶段二 | 未开始 |
| 订单 / 购物车 / 支付（4.x、7.x、8.x） | W2（Mock） | W14 提测、W16 联调 | 前端阶段二 | 未开始；**G2/G3 为前端联调门禁** |
| 会员 / 消息 / 推送（5.x、6.x） | W19 | W22 | 前端阶段三 | 未开始 |
| 客服 / 分享 / 更新（10.x、8.x、9.x） | W23 | W26（G4） | 前端阶段三 | 未开始 |
| 收藏 / 评价 / 工单回复 / IAP 验签等 **⚠ 缺口接口** | 按需求评审逐项定 | 最迟 W19 | 前端阶段三 | **未补齐的一律前端隐藏入口**（`ios-frontend.md` 第二十章） |

**（c）基础设施前置**：MySQL **16 库 64 表**分片方案与 ShardingSphere 路由（W3-W4）、Redis **40~60 节点**集群与秒杀独立实例（W3-W4）、**RabbitMQ 集群（P0/P1 不迁移，保持 RabbitMQ）**、Kafka+Flink 埋点链路（W25-W26）；容量口径唯一真值见 `plans/00-架构总纲.md`。

---

> **文档版本**：v1.4（v1.0→v1.1 评审修订版 + v1.2/v1.3 架构裁决回写 + v1.4 千万 DAU 降级与实施手册修订，修改明细见下方「审查记录」「跨架构裁决回写记录」「实施手册修订记录」）  
> **最后更新**：2026-10-11  
> **负责人**：技术架构组  
> **评审状态**：v1.1 已完成二轮交叉审查（与 `ios-frontend.md` / `admin-backend.md` 契约对齐）；v1.3 已完成架构裁决回写（C3 / C7 / C11 / 09 §8 #2/#4）；**v1.4 已完成千万 DAU 口径降级清理与实施手册化（章节 13）**

---

## 审查记录

### v1.0 → v1.1 修改记录

> 审查范围：iOS 热更新合规性、与 `ios-frontend.md` 的接口契约一致性、与 `admin-backend.md` 的核心服务冲突、APNs / Apple Pay / IAP 接口完整性、隐私合规 / ATS / 数据加密 / 幂等 / 安全 / 性能。编号与下文一一对应。

- [1] **热更新合规残留**：ATS 说明仍引用「2.4 热修复方案」，与 9.4「仅配置级热更新」红线自相矛盾（且 2.4 实为生物识别章节，引用失效） → 12.3.1 第 5 条改写为「9.5 远程配置下发的活动页/运营页 URL，与代码热修复无关」
- [2] **更新渠道合规**：9.1 含「企业内部分发」，面向公众的消费级 App 用企业证书分发将被吊销账号（Guideline 2.5.2 / Enterprise 许可协议） → 9.1 删除企业分发并新增「更新渠道红线」（仅 App Store + TestFlight）；9.2 `md5/file_size` 下发说明同步收窄为仅 TestFlight
- [3] **订单状态机冲突（重大）**：本文件为 0-6 七值（4=已取消），`admin-backend.md` 4.3 为 0-7 八值（4=退款中、6=已取消、7=已关闭），而 `order_main` 是两端同一份共享表 → 1.2.1 DDL 注释、4.2.3 `status` 参数、4.2.5 cancel 响应（status 4→6）、4.4 新增「状态机取值」行，统一为 8 值枚举并要求经 `transition()` + `order_status_log`；**前端订单 Tab 枚举映射需按新编码同步**（登记于 `ios-frontend.md` 附录 A）
- [4] **契约路径方向标反**：地址 / 优惠券 / 积分 / 购物车 / 工单 5 组接口把「兼容别名」标成了「前端契约路径」，与 `ios-frontend.md` 的修正结论相反（如 `/api/v1/addresses`、`/api/v1/tickets` 均被前端判定为不存在） → 5.2.2、5.2.3、5.2.4、4.2.1、10.3 全部翻转：契约路径以前端最终版为准（`/user/addresses`、`/user/coupons`、`/user/points`、`/cart`、`/cs/tickets`），原路径降级为兼容别名
- [5] **优惠券接口契约不符**：前端用 `GET /api/v1/user/coupons?status=available` 表达结算可用券，本文档另设 `/coupons/available`（前端标注路径不存在） → 5.2.4 列表接口支持 `status=available` + `order_amount/product_ids` 试算；`/user/coupons/available` 定为契约路径，原路径保留为别名
- [6] **前端待补项未闭环**：购物车容量上限前端硬编码、支付结果轮询缺字段 → 4.2.1 `GET /cart` 响应补 `max_items`（服务端下发，前端禁止硬编码）；4.2.3 订单详情补 `payment_status`（供 `PayStatusPoller` 轮询，避免用数字 status 二次推断支付态）
- [7] **评价接口缺失**：前端 9.6 需 `POST /orders/{id}/items/{id}/reviews`、`GET /user/reviews`，本文档只有 `POST /reviews` → 3.2.5 补资源级别名与「我的评价」接口；`product_review` 表补 `status` 审核态字段（机审后置审核、`reviewing` 不外显，满足 12.4 内容安全）
- [8] **搜索接口缺口未声明**：前端 6.3 标注 `search/filters`、`search/discovery` 后端未定义 → 3.2.3 新增约定：filters 由 `facets` 降级实现、discovery 走 `GET /config?scene=search_discovery`；9.5 `scene` 枚举补 `search_discovery`
- [9] **IAP 必补接口不全（阻塞项）**：前端 16.1 列出 4 个必补接口（verify / transactions / Server Notifications V2 / entitlements），本文档仅覆盖 verify 与回调，且路径前缀 `/payments/iap/*` 与前端 `/iap/*` 不一致 → 7.3.2 补 `GET /payments/iap/transactions`、`GET /payments/iap/entitlements`（均给出 `/iap/*` 别名），verify / restore 标注别名与前端阻塞项编号；新增 `appAccountToken` ↔ `user_id` 绑定校验行与「权益查询唯一事实来源」行
- [10] **微信/支付宝支付被判「未定义」**：统一支付入口已存在但未声明别名，前端 8.3 仍标注需补 `/payments/{wechat,alipay}/create` → 7.3.1 声明二者为 `POST /payments/{channel}/create` 的别名，不另立路由
- [11] **品牌标识不一致**：APNs `BundleID/Topic`、AASA `appIDs`、Apple Pay `merchantId` 仍为 `com.example.shop` / `ABCDE12345`，与前端 `com.easyshopping` / `easyshopping.com` / `merchant.com.easyshopping` 冲突 → 1.2.2 新增约定 5「品牌标识唯一真值」；6.2.1 APNsConfig、8.2.1 AASA（appIDs / webcredentials / appclips）、8.2.2 UL 路由表、8.3 与 3.2.x 分享链接、7.2.2 / 7.3.1 merchantId、Apple Pay payment sheet label 全部统一
- [12] **App Clip 决策缺失**：AASA 已含 clip 条目，但前端 16.4 / 附录 A.4 将其列为阶段一待决项 → 8.2.1 注 4 写明二选一决策与不提供时的删除动作；13.5 Checklist 增加该项
- [13] **最低系统版本冲突**：前端附录 A.4 已定 iOS 16+，本文档 `min_os_version` 默认 14.0 → 1.3.1 DDL 默认值改 `16.0` 并注明依据
- [14] **MQ 事件缺失**：`admin-backend.md` 1.4 已有 `order.cancelled`、`order.refund.applied`，本文档 1.5.2 未登记，买家端取消订单后管理端无法释放库存/退券 → 1.5.2 补两行；1.2.2 新增约定 6（跨端消费绑定管理端交换机，避免同名不同机）
- [15] **分库分表口径冲突**：本文档直书「分 16 库」，`admin-backend.md` 1.2.1 v1.1 结论为「初期单库 + 按月逻辑分表，行数超 5000 万再分库」 → 1.2.2 约定 4、11.4 统一为分阶段方案（ShardingSphere 透明切换，业务无感）　**〔本条记录已作废：v1.1「单库起步」口径已被终审裁决①取代，现行口径为 16 库 64 分片、分片即实例，见 1.2.2 约定 4〕**
- [16] **Token 跨端复用风险**：管理端与买家端各自签发 JWT，但未声明隔离规则 → 2.2.2 增加「与管理端 Token 隔离」：`aud` / `platform` claim 白名单验签，跨端调用一律 401；Refresh Token 旋转语义对齐、移动端载体为 Keychain
- [17] **免登录场景误标鉴权**：隐私政策（首次启动弹窗）、版本检查、远程配置在游客态必须可用，本文档却标 `Authorization: Bearer` → 12.1.2 / 9.2 / 9.5 改为免登录（纳入 12.6.1 白名单），并说明 device / user 维度合并策略
- [18] **ATS 说明与配置不符**：正文声称存在「历史遗留图片域名例外」，而 12.3.1 plist 为零例外 → 配置原则第 1 条改为「当前零例外」，例外须单独审批 + 写入审核备注 + 版本整改
- [19] **注销跨端删除不闭环**：`user.deleted` 仅本文档定义，`admin-backend.md` 1.4 未登记，存在「注销后管理端仍留存个人数据」的合规缺口 → 12.1.2 增加「跨端待办」说明并列入 13.5 Checklist
- [20] **幂等口径不一致**：前端要求写请求携带幂等键，`admin-backend.md` 10.1.1 有统一幂等规范，本文档仅写业务幂等键 → 4.4 新增「幂等键」行：统一 `Idempotency-Key` 请求头 + `user_id` 唯一索引去重，业务键兜底
- [21] **文档结构缺陷**：头部 v1.1、尾部 v1.0 版本不一致，且缺少「审查记录」章节 → 文末版本信息改为 v1.1 并指向本章节；新增本《审查记录》

### 本轮复核确认无问题项

- 热更新正文（9.4 合规边界表、9.5 拉取约定、11.3 离线包/差分更新、12.4 审核合规表、13.4 风险表）已符合 Guideline 2.5.2：**无任何 OC/Swift 补丁、JSPatch、RN/JS 逻辑热修复通道**，仅下发配置与非可执行资源，并以「强制更新跳转 App Store」兜底代码级缺陷——本轮仅清理了 2 处残留表述（见 [1][2]）
- APNs：payload 4KB 上限、410 Unregistered 清理、429 降速、Feedback Service 已退役、静默推送频控与 6.3.3 增量拉取兜底，均准确
- Apple Pay：Merchant Session 服务端创建、payment token 证书链验证、金额二次校验、原路退款接口完整
- AASA：托管路径 / Content-Type / 无跳转 / CDN 缓存与自检清单、`/s/*` 分享短链路径齐备
- 数据加密与脱敏：AES-256-GCM 落库、HMAC 等值查询、KMS 轮换、12.5 脱敏规则与 12.1.1 数据分类一致
- 越权与日志：IDOR 归属校验、审计日志只增不改、文件上传二次渲染防护均已覆盖

---

## 跨架构裁决回写记录

### v1.2 → v1.3

> 批次说明：本次回写依据 `architecture/00-conflict-register.md` 第四节「plans/ 侧待修订清单」执行（**该文档已随 architecture/ 目录删除，见 git 历史 v1**），将架构收敛裁决（C3 / C7 / C11 / 09 §8 #2、#4）落到本文件。文件此前版本停留在 v1.1，按回写批次统一升版为 v1.3（v1.2 为架构裁决收敛批次预留号，本文件无 v1.2 实体版本）。

- [R1] 裁决 C3 → 12.6.1「接口签名与防重放」：签名时间窗 ±60s → **±5 分钟**，Nonce TTL 120s → **10 分钟**（与 `android-backend.md` 13.1 一致），并加注「网关按 `X-App-Key` 可配窗口，兼容老版本客户端（90 天过渡）」「Nonce TTL ≥ 签名窗」
- [R2] 裁决 09 §8 #2/#4 → ① 13.5 上线 Checklist：「目标 QPS 10000+」标注为**单服务实例口径**，并注明平台级入口目标 **630 万 QPS**、压测目标 756 万 QPS（见 `architecture/00-conflict-register.md` 容量总表⑤，**该文档已删除、见 git 历史 v1**），原值与平台口径差约 **550 倍**；② 11.5 SLA 分级表：P0 可用性 99.95% → **≥99.99%**（原单项 4.38h/年即击穿全局 52.56min 误差预算），P1/P2 预算归属以 `09-performance-testing.md` §3.2 为唯一 SLO 表。**（v1.4 注：本条记录的 630 万 / 756 万 QPS 与 99.99% 均为亿级方案口径，已随目标降级为千万 DAU 而作废，现行口径以 11.5 / 13.5 与 `plans/00-架构总纲.md` 为准；本条仅作历史留档）**
- [R3] 裁决 C7 → 4.4「支付超时」：改为延迟消息 `deliveryTimestamp` **直取订单表 `pay_deadline` 字段为唯一事实来源**，各端展示同一字段，**不自定义分钟数**；原文"15 分钟"标注为**待确认的产品参数**（建议 30 分钟，产品联调前书面确认后回写三端计划）；`expire_time` 由 `pay_deadline` 派生；同步将 4.4「价格锁定」行改为与 `pay_deadline` 同源（锁定不得早于支付截止，避免支付中途价格失效）
- [R4] 裁决 C11 → 1.4.3：「逻辑 DB 前缀」方案不可行（Redis Cluster 仅支持 DB 0）→ 改为「**Key 前缀 + 客户端多集群路由**」，并在 1.4.4 加注四段式 `{业务域}:{模块}:{标识}:{版本}` 为跨端唯一 Key 真值（`architecture/03-cache.md` §6.3 统一表，**该文档已删除、统一表现由 `plans/00-架构总纲.md` 承载，见 git 历史 v1**；`android-backend.md` 三段式作废）

**与执行指令描述的差异说明**：文件实际为 4944 行（非约 4294 行）；文件原版本标记为 v1.1（非 v1.2），已按回写批次升版为 v1.3；4 项修订的落点与裁决意图一致，无偏离。

---

## 实施手册修订记录

### v1.3 → v1.4

> 修订背景：项目目标由「1 亿同时在线」**降级为千万 DAU**；`architecture/` 12 份文档删除、收敛为 `plans/00-架构总纲.md`。本次将本文件从"设计计划"补强为**可执行实施手册**，并清理亿级残留口径。**保留不改项**：签名窗口 ±5min / Nonce TTL 10min（12.6）、`pay_deadline` 唯一事实源（4.4）、缓存 Key 四段式（1.4.4）、订单 8 值状态机（4.2）、`edge-gateway` 统一接入、AASA/IAP 契约、RabbitMQ 不迁移。

**一、口径清理（亿级 → 千万 DAU）**

- [I1] 文首新增**容量口径行**：DAU 1000 万 / 峰值在线 200 万 / 网关入口 60 万 QPS / 压测目标 75 万 QPS / 应用读 50 万写 8 万 / 订单峰值 5 万笔/s / MySQL 16 库 64 表 / Redis 40~60 节点 / RabbitMQ 不迁移；版本号 v1.1（头部旧值）→ **v1.4**
- [I2] **11.5 SLA 分级表**：P0 可用性 `≥99.99%` → **`99.9%`（P0 一期）**，并重写口径注：**P1 同城双活后 99.95%**（业务若书面要求 99.95%，须先完成 P1 双活）、**99.99% 为亿级方案口径已废弃**；P1/P2 预算归属指向 `plans/00-架构总纲.md`
- [I3] **13.5 上线 Checklist**：保留 `10000+` 单服务实例口径；平台级由「630 万 / 756 万 QPS」改为 **网关入口 60 万 QPS / 压测目标 75 万 QPS**，删除对 `architecture/00-conflict-register.md` 的引用 → 改指 `plans/00-架构总纲.md`
- [I4] **11.4 分库分表 / 1.2.2 约定 4**：补充**目标态 16 库 × 64 表**（千万 DAU 口径），分阶段方案（初期单库 + 按月逻辑分表 + ShardingSphere 透明切换）保留不变　**〔本条中「初期单库 + 按月逻辑分表」已作废：现行口径见 1.2.2 约定 4（16 库 64 分片、分片即实例、归档分区替代按月分表）〕**
- [I5] **1.4.3 / 1.4.4 缓存 Key 引用**：`architecture/03-cache.md` §6.3 → `plans/00-架构总纲.md`（原文件已删除，见 git 历史 v1）；四段式真值与「Key 前缀 + 客户端多集群路由」不变
- [I6] 历史记录（跨架构裁决回写 R2 / R4 与批次说明）中指向 `architecture/` 的引用统一加注「已删除 / 见 git 历史 v1」，并对 R2 标注「630 万、756 万、99.99% 已作废」——**历史留档不改写，仅标注失效**

**二、升级为实施手册（第十三章三要素补齐）**

- [I7] **13.1** 新增「实施手册三要素」导语：任务分解 / 验收标准 / 依赖与前置的落点索引
- [I8] **13.2 四张排期表就地强化**：每行新增 **「依赖与前置」**（含分片与节点资源、Apple Merchant ID / APNs .p8 / AASA 域名、支付资质、跨端契约冻结状态）与 **「验收标准」**（功能 + 性能 + 审核，含具体阈值与 M1-M9 里程碑评审点）
- [I9] **新增 13.6 实施手册**：
  - 13.6.1 **门禁与审核提审节点表（G0-G9）**：按周可交付、验收标准、依赖前置，其中 **G8 = W34 末提交 App Store 审核**（预留 1-2 周审核时长）、G9 = 审核通过后全量
  - 13.6.2 **依赖与前置清单**：(a) Apple 证书/账号/资质（含 App Clip 决策 W2 门前必须书面决策）；(b) 后端接口对 `ios-frontend.md` 的就绪承诺表（契约就绪周 / 联调就绪周 / 支撑的前端阶段，⚠ 缺口接口未补齐则前端隐藏入口）；(c) 基础设施前置（16 库 64 表、Redis 40~60 节点、RabbitMQ 不迁移）

**三、未改动项复核**：签名窗口/Nonce TTL、`pay_deadline`、四段式 Key、8 值状态机、AASA/IAP、9.4 热更新合规红线、13.5 其余 Checklist 条目，本轮均逐条复核保持原样。

---

*文档版本：v1.4（千万 DAU 降级 + 实施手册修订版）*  
*最后更新：2026-10-11*  
*本次审查：21 项问题（v1.1），涉及 1.2 / 1.3 / 1.5 / 2.2 / 3.2 / 4.2 / 4.4 / 5.2 / 7.2 / 7.3 / 8.2 / 8.3 / 9.1 / 9.2 / 9.5 / 10.3 / 11.4 / 12.1 / 12.3 / 13.5 / 文末版本；回写：4 项架构裁决（C3 / C7 / C11 / 09 §8）；**v1.4 实施手册修订：9 项（I1-I9）**，涉及 文首 / 1.2.2 / 1.4.3 / 1.4.4 / 11.4 / 11.5 / 13.1 / 13.2 / 13.5 / 新增 13.6 / 文末版本*
