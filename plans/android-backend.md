# 安卓客户端后端开发计划

> 项目：大型电商平台（淘宝级别）安卓客户端后端  
> 技术栈：Java Spring Boot / Go / Node.js + MySQL + Redis + Elasticsearch + RabbitMQ  
> 文档版本：v1.1（v1.0 经跨端架构评审后修订，修订明细见文末「审查记录」）  
> 更新日期：2026-10-11

---

## 一、架构与基础设施

### 1.1 与浏览器端共享的服务

| 服务 | 职责 | 技术选型 |
|------|------|----------|
| 商品中心 | 商品信息、类目、品牌、SKU/SPU | Java Spring Boot + MySQL + ES |
| 店铺中心 | 店铺信息、店铺评分、店铺分类 | Java Spring Boot + MySQL + ES |
| 购物车 | 购物车商品管理、数量校验、价格计算 | Java Spring Boot + Redis + MySQL |
| 订单中心 | 订单生命周期管理 | Java Spring Boot + MySQL + RabbitMQ |
| 支付中心 | 支付路由、对账、退款 | Java Spring Boot + MySQL |
| 会员中心 | 用户基础信息、等级、积分 | Java Spring Boot + MySQL + Redis |
| 营销中心 | 优惠券、促销、秒杀 | Java Spring Boot + Redis + RabbitMQ |
| 搜索服务 | 商品搜索、筛选、排序 | Go + Elasticsearch |
| 推荐服务 | 个性化推荐、猜你喜欢 | Python/Go + Redis + 特征平台 |
| 评价服务 | 商品评价、晒单 | Java Spring Boot + MySQL |
| 库存服务 | 库存扣减、预占、释放 | Go + Redis + MySQL |
| 物流服务 | 物流轨迹、配送管理 | Java Spring Boot + MySQL |
| 内容服务 | Banner、金刚区分类、公告、专题页、闪屏/引导页配置 | Java Spring Boot + MySQL + Redis |
| 售后服务 | 仅退款/退货退款/换货、退款进度、退货物流 | Java Spring Boot + MySQL + RabbitMQ |

> **边界说明**：共享服务同时服务浏览器端（H5/PC）、小程序与安卓/iOS 客户端；本文档只描述「移动端访问共享服务的接口与移动端特有逻辑」，不重复定义共享服务内部实现。共享服务与管理端的对应关系见 `admin-backend.md` 1.1.3（core-product / core-price / core-promotion / core-user），两端不得各自建一套商品/订单数据。
>
> **与管理端读模型的关系（v1.1 补充）**：`admin-backend.md` 1.1.3 只把 core-product / core-price / core-promotion / core-user 列为「调用式共享」，管理端的订单、售后是 `admin_order_*` 系列**读模型**，由订单中心经 MQ 事件同步 + 定时对账补偿（见管理端 1.1.3 事件同步机制）。由此三条硬约束：
> 1. **订单事实来源只有一份**（订单中心），管理端不得回写订单状态，只能发起状态变更意图，最终状态由状态机服务落库；
> 2. **状态机、事件名、金额字段口径必须同源**：买家端 `order.status` 与管理端 `admin_order.order_status` 均为 8 值枚举，逐值对应关系见本文 1.4.1 订单表注释与 4.3.4，任何一端新增状态必须双端同步发版；
> 3. **商品/价格/促销变更以事件为准**：买家端消费事件刷新本地缓存与 ES 索引，允许最终一致，缓存 TTL 内的读差异可接受，但下单计价一律实时回源 `core-price`，不读缓存价格。

### 1.2 移动端特有服务

| 服务 | 职责 | 技术选型 |
|------|------|----------|
| 推送服务 | FCM/厂商通道推送、推送策略、离线消息补推 | Go + RabbitMQ + 厂商SDK |
| 消息中心 | 站内信、系统/订单/物流/促销通知列表、已读/未读 | Java Spring Boot + MySQL + Redis |
| 定位服务 | 附近门店、配送范围、地址解析 | Go + Redis GEO + 高德API |
| 分享服务 | 分享链接、海报生成、渠道追踪 | Node.js + MySQL + CDN |
| 扫码服务 | 扫码识物、扫码登录 | Go + Redis + 商品中心 |
| 更新服务 | 版本管理、灰度发布、热修复 | Node.js + MySQL + Redis |
| 客服服务 | 在线客服、工单、智能问答 | Node.js + WebSocket + MySQL |
| 设备管理 | 设备注册、绑定、风控 | Go + MySQL + Redis |
| 文件服务 | 图片上传、裁剪、CDN分发 | Node.js + OSS + CDN |

> **推送 vs 消息边界**：消息中心（站内信）是「事实来源」，落库、可查询、可回溯；推送服务只是消息中心的**一条投递通道**。一次业务事件（如订单发货）先写消息中心，再由 MQ 异步触发推送，禁止业务方直接调推送服务绕过消息中心，否则会出现「推送已发出但站内查不到」的不一致。

### 1.3 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        安卓客户端                                │
└───────────────────────────┬─────────────────────────────────────┘
                            │ HTTPS / HTTP2
┌───────────────────────────▼─────────────────────────────────────┐
│                      API Gateway (Kong/Spring Cloud Gateway)     │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐   │
│  │ 限流    │ │ 鉴权    │ │ 路由    │ │ 日志    │ │ 熔断    │   │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
┌───────▼───────┐   ┌───────▼───────┐   ┌───────▼───────┐
│  共享服务集群  │   │ 移动端服务集群 │   │  基础服务集群  │
│  (Spring Boot)│   │  (Go/Node.js) │   │  (中间件)      │
└───────┬───────┘   └───────┬───────┘   └───────┬───────┘
        │                   │                   │
        └───────────────────┼───────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│                    数据层                                        │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐   │
│  │ MySQL   │ │ Redis   │ │   ES    │ │RabbitMQ │ │  OSS    │   │
│  │ 主从集群 │ │ 集群    │ │  集群   │ │  集群   │ │  CDN    │   │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 1.4 数据库设计

#### 1.4.1 共享核心表（与浏览器端共用）

```sql
-- 用户表
CREATE TABLE `user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` VARCHAR(64) NOT NULL COMMENT '用户名',
  `mobile` VARCHAR(20) NOT NULL COMMENT '手机号',
  `email` VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
  `password_hash` VARCHAR(256) NOT NULL COMMENT '密码哈希',
  `nickname` VARCHAR(64) DEFAULT NULL COMMENT '昵称',
  `avatar` VARCHAR(512) DEFAULT NULL COMMENT '头像URL',
  `gender` TINYINT DEFAULT 0 COMMENT '性别 0-未知 1-男 2-女',
  `birthday` DATE DEFAULT NULL COMMENT '生日',
  `status` TINYINT DEFAULT 1 COMMENT '状态 0-禁用 1-正常 2-已注销(注销为逻辑注销:昵称头像清空、mobile 写回脱敏值以释放唯一键供手机号重新注册,积分/优惠券余额按规则清零)',
  `level` TINYINT DEFAULT 1 COMMENT '会员等级',
  `points` INT DEFAULT 0 COMMENT '积分余额',
  `growth_value` INT DEFAULT 0 COMMENT '成长值',
  `verified` TINYINT DEFAULT 0 COMMENT '实名认证 0-未认证 1-已认证',
  `register_source` VARCHAR(32) DEFAULT NULL COMMENT '注册来源',
  `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mobile` (`mobile`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_level` (`level`),
  KEY `idx_register_source` (`register_source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 商品SPU表
CREATE TABLE `product_spu` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(256) NOT NULL COMMENT '商品名称',
  `category_id` BIGINT NOT NULL COMMENT '类目ID',
  `brand_id` BIGINT DEFAULT NULL COMMENT '品牌ID',
  `shop_id` BIGINT NOT NULL COMMENT '店铺ID',
  `main_image` VARCHAR(512) NOT NULL COMMENT '主图',
  `images` JSON COMMENT '图片列表',
  `detail` LONGTEXT COMMENT '商品详情(富文本)',
  `sales_count` INT DEFAULT 0 COMMENT '销量',
  `rating` DECIMAL(2,1) DEFAULT 5.0 COMMENT '评分',
  `review_count` INT DEFAULT 0 COMMENT '评价数量',
  `weight` DECIMAL(10,2) DEFAULT 0.00 COMMENT '重量(kg)',
  `volume` DECIMAL(10,2) DEFAULT 0.00 COMMENT '体积(m³)',
  `status` TINYINT DEFAULT 1 COMMENT '状态 0-下架 1-上架',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_brand` (`brand_id`),
  KEY `idx_shop` (`shop_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SPU表';

-- 商品SKU表
CREATE TABLE `product_sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `spu_id` BIGINT UNSIGNED NOT NULL COMMENT 'SPU ID',
  `specs` JSON NOT NULL COMMENT '规格属性 {颜色:红色,尺码:XL}',
  `price` DECIMAL(10,2) NOT NULL COMMENT '售价',
  `original_price` DECIMAL(10,2) DEFAULT NULL COMMENT '原价',
  `stock` INT NOT NULL DEFAULT 0 COMMENT '库存',
  `sku_code` VARCHAR(64) NOT NULL COMMENT 'SKU编码',
  `status` TINYINT DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_code` (`sku_code`),
  KEY `idx_spu` (`spu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU表';

-- 订单表
-- 注意1：订单状态枚举必须与 admin-backend.md 4.3 订单状态机、1.2.2 admin_order.order_status(8值) 逐值一致
--        （同一状态机、同一套事件）；管理端订单是由订单中心 MQ 事件同步的读模型，禁止两端各自定义状态，映射见 4.3.4
-- 注意2：主键与 order_no 的全局唯一性遵循 admin-backend.md 1.2.1 分片落地约束(第2/5条)：
--        启用分表后主键改用雪花ID，order_no 按 yyyyMMddHHmmss+机器号+序列 编码保证全局唯一，
--        分表后 uk_order_no 仅在单片内生效，不能依赖唯一索引兜底
CREATE TABLE `order` (
  `id` BIGINT UNSIGNED NOT NULL COMMENT '主键(启用分表后为雪花ID,见admin 1.2.1约束2/5,初期单库可保留自增)',
  `order_no` VARCHAR(32) NOT NULL COMMENT '订单编号(全局唯一编码规则)',
  `parent_order_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '父订单ID(拆单场景,为空表示父单,对齐管理端parent_order_no)',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `shop_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '店铺ID(子单必填;父单为空表示多店铺合并单,与管理端store_id语义一致)',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
  `discount_amount` DECIMAL(10,2) DEFAULT 0.00 COMMENT '优惠金额',
  `freight_amount` DECIMAL(10,2) DEFAULT 0.00 COMMENT '运费',
  `points_used` INT DEFAULT 0 COMMENT '使用积分',
  `coupon_id` BIGINT DEFAULT NULL COMMENT '优惠券ID',
  `coupon_record_id` BIGINT DEFAULT NULL COMMENT '优惠券领取记录ID(核销幂等依据)',
  `pay_amount` DECIMAL(10,2) NOT NULL COMMENT '实付金额',
  `payment_id` BIGINT DEFAULT NULL COMMENT '支付单ID',
  `pay_type` TINYINT DEFAULT NULL COMMENT '支付方式 1-支付宝 2-微信 3-银联(管理端字典为银行卡,同一枚举) 4-余额(买家端扩展,须在管理端数据字典同步补录,否则管理端按支付方式筛选/导出取不到该值)',
  `pay_status` TINYINT NOT NULL DEFAULT 0 COMMENT '支付状态 0-未支付 1-部分支付 2-已支付 3-已退款',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态(8值,与admin 1.2.2/4.3逐值一致):0-待付款 1-待发货(已付款) 2-待收货(已发货) 3-已完成 4-退款中 5-已退款 6-已取消 7-已关闭',
  `idempotent_key` VARCHAR(64) NOT NULL COMMENT '下单幂等键(客户端生成UUID,防重复提交)',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号(状态变更防并发)',
  `address_id` BIGINT NOT NULL COMMENT '地址ID',
  `address_snapshot` JSON DEFAULT NULL COMMENT '下单时地址快照(防止用户改地址影响历史订单)',
  `remark` VARCHAR(512) DEFAULT NULL COMMENT '订单备注',
  `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间',
  `ship_time` DATETIME DEFAULT NULL COMMENT '发货时间',
  `receive_time` DATETIME DEFAULT NULL COMMENT '签收时间',
  `close_time` DATETIME DEFAULT NULL COMMENT '关闭时间(超时未付/取消)',
  `pay_deadline` DATETIME DEFAULT NULL COMMENT '付款截止时间(超时自动关单的唯一事实来源,原名auto_close_at,按裁决C7统一为订单表pay_deadline;延迟消息deliveryTimestamp直取此字段,与管理端同一调度语义,见4.1.4;时长待产品联调前书面确认,原"30分钟"为建议值)',
  `cancel_reason` VARCHAR(256) DEFAULT NULL COMMENT '取消原因',
  `deleted_at` DATETIME DEFAULT NULL COMMENT '用户删除时间(逻辑删除:交易流水不可物理删除,删除后默认列表不展示,售后/退款/发票仍可查)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_idempotent_key` (`idempotent_key`),
  KEY `idx_user` (`user_id`, `status`, `created_at`),
  KEY `idx_shop` (`shop_id`),
  KEY `idx_status` (`status`, `pay_deadline`),
  KEY `idx_payment` (`payment_id`),
  KEY `idx_parent` (`parent_order_id`),
  KEY `idx_user_deleted` (`user_id`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 订单商品表
CREATE TABLE `order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `sku_id` BIGINT UNSIGNED NOT NULL,
  `spu_id` BIGINT UNSIGNED NOT NULL,
  `sku_name` VARCHAR(256) NOT NULL COMMENT 'SKU名称(快照)',
  `sku_image` VARCHAR(512) DEFAULT NULL COMMENT 'SKU图片(快照)',
  `price` DECIMAL(10,2) NOT NULL COMMENT '单价(快照)',
  `quantity` INT NOT NULL COMMENT '数量',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '小计',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单商品表';
```

#### 1.4.2 移动端特有表

```sql
-- 设备表
CREATE TABLE `device` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `device_id` VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  `device_type` VARCHAR(32) NOT NULL COMMENT '设备类型 android/ios',
  `device_model` VARCHAR(64) DEFAULT NULL COMMENT '设备型号',
  `os_version` VARCHAR(32) DEFAULT NULL COMMENT '系统版本',
  `app_version` VARCHAR(32) DEFAULT NULL COMMENT 'APP版本',
  `push_token` VARCHAR(256) DEFAULT NULL COMMENT '推送Token(冗余字段,仅记录默认/最近一次通道,权威存储以device_push_token为准,更新必须同事务双写)',
  `push_channel` VARCHAR(32) DEFAULT NULL COMMENT '推送通道 fcm/xiaomi/huawei/oppo/vivo',
  `status` TINYINT DEFAULT 1 COMMENT '状态 0-解绑 1-绑定',
  `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_id` (`device_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备表';

-- 设备推送Token表（一台设备可同时持有多个通道Token：厂商通道 + FCM）
-- 说明：厂商通道（小米/华为/OPPO/vivo）与 FCM 的 Token 互不通用，必须分通道存储，
--       否则无法实现「厂商通道优先、FCM 兜底」的降级路由（见 6.1.5）
CREATE TABLE `device_push_token` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `device_pk` BIGINT UNSIGNED NOT NULL COMMENT '设备表主键ID(即device.id)',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '当前绑定用户ID',
  `channel` VARCHAR(32) NOT NULL COMMENT '通道 fcm/xiaomi/huawei/oppo/vivo',
  `token` VARCHAR(256) NOT NULL COMMENT '该通道的Token',
  `token_status` TINYINT DEFAULT 1 COMMENT '状态 0-失效(厂商回调/上报) 1-有效 2-过期清理',
  `last_active_at` DATETIME DEFAULT NULL COMMENT '最近活跃时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_token` (`channel`, `token`),
  KEY `idx_device` (`device_pk`),
  KEY `idx_user_channel` (`user_id`, `channel`, `token_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备推送Token表';

-- 用户地址表
CREATE TABLE `user_address` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(64) NOT NULL COMMENT '收货人姓名',
  `mobile` VARCHAR(20) NOT NULL COMMENT '收货人手机号',
  `province` VARCHAR(32) NOT NULL COMMENT '省',
  `city` VARCHAR(32) NOT NULL COMMENT '市',
  `district` VARCHAR(32) NOT NULL COMMENT '区',
  `detail` VARCHAR(256) NOT NULL COMMENT '详细地址',
  `longitude` DECIMAL(10,7) DEFAULT NULL COMMENT '经度',
  `latitude` DECIMAL(10,7) DEFAULT NULL COMMENT '纬度',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否默认 0-否 1-是',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户地址表';

-- 收藏表
CREATE TABLE `user_favorite` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `spu_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_spu` (`user_id`, `spu_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏表';

-- 积分记录表
CREATE TABLE `points_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `points` INT NOT NULL COMMENT '积分变动(正数增加,负数减少)',
  `type` VARCHAR(32) NOT NULL COMMENT '类型 sign/order/refund/exchange',
  `description` VARCHAR(256) DEFAULT NULL COMMENT '描述',
  `related_id` BIGINT DEFAULT NULL COMMENT '关联ID(订单ID等)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分记录表';

-- 推送记录表（字段与 admin_push_log 对齐：统一带 biz_type/biz_id，便于双端统计同一业务事件）
-- 说明：扇出粒度是「一台设备一行」，同一 msg_id 可在多台设备/多通道各落一行，
--       因此幂等唯一键必须含 device_pk（同通道也可能存在两台同厂商设备，用 (msg_id,channel) 会挡住第二台）；
--       保留 channel 是为了支持「首选通道失败→新增一行换下一通道补发」，同一设备同通道只允许一行
CREATE TABLE `push_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `msg_id` VARCHAR(64) NOT NULL COMMENT '消息唯一ID(业务方生成,用于幂等去重)',
  `user_id` BIGINT UNSIGNED NOT NULL,
  `device_pk` BIGINT UNSIGNED NOT NULL COMMENT '目标设备主键ID(扇出后一行一设备,必填)',
  `channel` VARCHAR(32) NOT NULL COMMENT '推送通道 fcm/xiaomi/huawei/oppo/vivo',
  `biz_type` VARCHAR(32) NOT NULL COMMENT '业务类型 system/order/logistics/promotion/recommend',
  `biz_id` BIGINT DEFAULT NULL COMMENT '业务ID(订单ID等)',
  `title` VARCHAR(128) NOT NULL COMMENT '推送标题',
  `content` VARCHAR(512) NOT NULL COMMENT '推送内容',
  `deeplink` VARCHAR(512) DEFAULT NULL COMMENT '点击跳转deeplink',
  `status` TINYINT DEFAULT 0 COMMENT '状态 0-待发送 1-已发送 2-发送失败 3-已点击 4-降级兜底(短信/站内信) 5-已丢弃(免打扰/超频)',
  `retry_count` TINYINT DEFAULT 0 COMMENT '重试次数(单通道失败换下一通道)',
  `fail_reason` VARCHAR(256) DEFAULT NULL COMMENT '失败原因',
  `sent_at` DATETIME DEFAULT NULL COMMENT '发送时间',
  `clicked_at` DATETIME DEFAULT NULL COMMENT '点击时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_id_device_channel` (`msg_id`, `device_pk`, `channel`),
  KEY `idx_user` (`user_id`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送记录表';

-- 分享记录表
CREATE TABLE `share_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `share_type` VARCHAR(32) NOT NULL COMMENT '类型 product/store/activity',
  `target_id` BIGINT NOT NULL COMMENT '目标ID',
  `channel` VARCHAR(32) NOT NULL COMMENT '渠道 wechat/moments/qq/weibo/copy',
  `share_code` VARCHAR(32) NOT NULL COMMENT '分享码(用于追踪)',
  `click_count` INT DEFAULT 0 COMMENT '点击次数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_share_code` (`share_code`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享记录表';

-- 扫码记录表
CREATE TABLE `scan_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '用户ID(扫码登录时)',
  `scan_type` VARCHAR(32) NOT NULL COMMENT '类型 product/login/payment',
  `content` VARCHAR(512) NOT NULL COMMENT '扫码内容',
  `result` JSON COMMENT '扫码结果',
  `device_id` VARCHAR(64) DEFAULT NULL COMMENT '扫码设备',
  `ip` VARCHAR(64) DEFAULT NULL COMMENT 'IP地址',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_type` (`scan_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫码记录表';

-- 版本更新表
CREATE TABLE `app_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `platform` VARCHAR(16) NOT NULL COMMENT '平台 android/ios',
  `version_code` INT NOT NULL COMMENT '版本号',
  `version_name` VARCHAR(32) NOT NULL COMMENT '版本名称',
  `update_type` TINYINT NOT NULL COMMENT '更新类型 0-普通 1-强制 2-灰度(客户端上报字段含义见10.1.3,服务端返回时按0-无更新1-普通2-强制3-灰度映射)',
  `download_url` VARCHAR(512) NOT NULL COMMENT '下载URL',
  `file_size` BIGINT DEFAULT NULL COMMENT '安装包字节数(客户端展示进度条)',
  `md5` VARCHAR(64) DEFAULT NULL COMMENT '安装包MD5(下载完整性校验)',
  `sign_sha256` VARCHAR(128) DEFAULT NULL COMMENT 'APK签名SHA256(防篡改,客户端校验)',
  `update_desc` TEXT COMMENT '更新说明',
  `gray_ratio` INT DEFAULT 100 COMMENT '灰度比例(%)',
  `gray_rule` JSON DEFAULT NULL COMMENT '灰度规则 {userIds:[],deviceIds:[],provinces:[],channels:[]}',
  `status` TINYINT DEFAULT 1 COMMENT '状态 0-下线 1-上线',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_platform` (`platform`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='APP版本表';

-- 客服会话表
CREATE TABLE `cs_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
  `type` VARCHAR(32) NOT NULL COMMENT '类型 human/robot',
  `status` TINYINT DEFAULT 0 COMMENT '状态 0-等待 1-进行中 2-已结束',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`session_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话表';

-- 客服消息表
CREATE TABLE `cs_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL,
  `sender_type` VARCHAR(16) NOT NULL COMMENT '发送者 user/staff/robot',
  `content_type` VARCHAR(16) DEFAULT 'text' COMMENT '内容类型 text/image/file',
  `content` TEXT NOT NULL COMMENT '消息内容',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服消息表';

-- 工单表
CREATE TABLE `cs_ticket` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_no` VARCHAR(32) NOT NULL COMMENT '工单编号',
  `user_id` BIGINT UNSIGNED NOT NULL,
  `category` VARCHAR(32) NOT NULL COMMENT '分类 refund/exchange/complaint/other',
  `title` VARCHAR(256) NOT NULL COMMENT '标题',
  `description` TEXT NOT NULL COMMENT '描述',
  `images` JSON COMMENT '图片附件',
  `status` TINYINT DEFAULT 0 COMMENT '状态 0-待处理 1-处理中 2-已解决 3-已关闭',
  `priority` TINYINT DEFAULT 1 COMMENT '优先级 0-低 1-中 2-高 3-紧急',
  `handler_id` BIGINT DEFAULT NULL COMMENT '处理人ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单表';
```

### 1.5 缓存策略

> **Key 规范（裁决 C11）**：统一四段式 `{业务域}:{模块}:{标识}[:{子标识}]:v{N}`，以 `ios-backend.md` 1.4.4 四段式为**唯一真值**，下表按 `architecture/03-cache.md` §6.1/§6.3 统一表落地（修正本文原三段式 Key 与既有文件同一数据两个 Key 的冲突）；变更 Key 语义必须升版本段，禁止原地改 value 结构。
> **前缀说明**：Redis Cluster **不支持逻辑 DB**，Key 前缀仅用于 `CacheFacade` 在客户端做**多集群路由**（按前缀白名单选实例组），未登记前缀启动即抛 `IllegalCacheKeyException`（见 03-cache §6.2/§6.4）。

| 缓存对象 | 缓存Key（四段式） | 过期时间 | 更新策略 | 实例组 |
|----------|---------|----------|----------|--------|
| 用户信息 | `user:profile:{userId}:v3` | 30分钟 | 登录/更新时刷新 + 广播 | A（LRU） |
| 商品详情 | `product:detail:{spuId}:v2` | 30分钟+随机抖动 | 商品变更 → 删 L2 + 广播 L1 + 延迟 500ms 二次删 | A（热点进 hotCache） |
| 商品库存 | `stock:sku:{skuId}:v1` | 无 TTL（实时） | Lua 原子增减，异步落库 | C（秒杀独立，noeviction） |
| 用户地址 | `user:addresses:{userId}:v1` | 10分钟 | 增删改时删除 | A（LRU） |
| 用户收藏 | `user:favorites:{userId}:v1` | 10分钟 | 增删时删除 | A（LRU） |
| 积分余额 | `user:points:{userId}:v1` | 实时 | 变动时更新 | D（计数榜单） |
| 推送Token | `push:token:{deviceId}:v2` | 7天 | 登录时更新；若设备注销或Token作废，提前清除 | A（LRU） |
| 版本信息 | `version:latest:{platform}:v1` | 5分钟 | 发布时刷新 | A（LRU） |
| 分享配置 | `share:config:{type}:v1` | 1小时 | 更新时刷新 | A（LRU） |
| 热门商品 | `hot:products:{categoryId}:v1` | 5分钟 | 定时刷新；若Redis误删，通过ES重建热度排行 | A（LRU） |
| 价格缓存 | `price:sku:{skuId}:v1` | 1分钟 | 秒杀/促销结束后立即删除，确保价格实时性 | A（**禁止进 L1**） |
| 浏览历史 | `user:browse:{userId}:v1` | 30分钟 | 用户退出登录或清空历史时删除 | A（LRU） |

> 其余 Key（订单域 `order:detail:*`、基础设施类 `lock:`/`idem:`/`rl:` 等）一律按 03-cache §6.3 统一表实现，不另起写法。

### 1.6 技术要点

- **服务注册与发现**：Nacos / Consul
- **配置中心**：Nacos Config / Apollo
- **链路追踪**：SkyWalking / Jaeger
- **日志收集**：ELK (Elasticsearch + Logstash + Kibana)
- **监控告警**：Prometheus + Grafana
- **容器化部署**：Docker + Kubernetes
- **CI/CD**：GitLab CI / Jenkins

---

## 二、认证与授权

### 2.1 移动端登录

#### 2.1.1 功能点
- 手机号 + 验证码登录
- 手机号 + 密码登录
- 第三方登录（微信、QQ、微博、支付宝）
- 一键登录（运营商网关认证）
- 扫码登录（APP扫码确认）

#### 2.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 发送验证码 | POST | `/api/v1/auth/sms-code` | 发送短信验证码 |
| 验证码登录 | POST | `/api/v1/auth/login/sms` | 手机号+验证码登录 |
| 密码登录 | POST | `/api/v1/auth/login/password` | 手机号+密码登录 |
| 一键登录 | POST | `/api/v1/auth/login/one-click` | 运营商一键登录 |
| 第三方登录 | POST | `/api/v1/auth/login/oauth` | 第三方授权登录 |
| 扫码登录确认 | POST | `/api/v1/scan-login/confirm` | APP扫码确认（已废弃别名 `/api/v1/auth/login/qr-confirm`，流程与安全约束见 9.2，v1.2 移除别名） |
| 刷新Token | POST | `/api/v1/auth/token/refresh` | 刷新访问令牌 |
| 登出 | POST | `/api/v1/auth/logout` | 用户登出 |

#### 2.1.3 关键接口详情

**发送验证码**
```
POST /api/v1/auth/sms-code
Content-Type: application/json

{
  "mobile": "13800138000",
  "type": "login",        // login/register/reset
  "captchaToken": "xxx"   // 图形验证码token(防刷)
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "expireIn": 300,      // 有效期(秒)
    "cooldown": 60        // 冷却时间(秒)
  }
}
```

**验证码登录**
```
POST /api/v1/auth/login/sms
Content-Type: application/json

{
  "mobile": "13800138000",
  "code": "123456",
  "deviceId": "device-uuid-xxx",
  "deviceInfo": {
    "model": "Pixel 7",
    "osVersion": "14",
    "appVersion": "10.0.0"
  }
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "userId": 10001,
    "accessToken": "eyJhbGciOiJIUzI1NiIs...",
    "refreshToken": "dGhpcyBpcyBh...",
    "expiresIn": 7200,
    "isNewUser": false
  }
}
```

**一键登录**
```
POST /api/v1/auth/login/one-click
Content-Type: application/json

{
  "token": "运营商网关返回的token",
  "operator": "china_mobile",  // china_mobile/china_unicom/china_telecom
  "deviceId": "device-uuid-xxx"
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "mobile": "13800138000",
    "userId": 10001,
    "accessToken": "eyJhbGciOiJIUzI1NiIs...",
    "refreshToken": "dGhpcyBpcyBh...",
    "expiresIn": 7200
  }
}
```

### 2.2 Token管理

#### 2.2.1 功能点
- Access Token + Refresh Token 双令牌机制
- Token自动续期
- Token黑名单（登出/踢出）
- 多端登录管理

#### 2.2.2 技术要点

| 项目 | 方案 |
|------|------|
| Token类型 | JWT (JSON Web Token) |
| Access Token有效期 | 2小时 |
| Refresh Token有效期 | 30天 |
| Token存储 | Redis (Refresh Token) |
| 签名算法 | RS256 (非对称加密)，公钥下发给网关验签、私钥仅认证服务持有并放 KMS |
| 刷新策略 | Refresh Token轮换，旧Token加入黑名单 |
| 签发域 `iss` / 受众 `aud` | `iss=easy-shopping`，买家端 `aud=easy-app`（管理端为 `easy-admin`，见 `admin-backend.md` 2.1） |
| 并发刷新 | 401 触发的刷新必须**单飞**（同一进程/同一设备只发一次 refresh，其余请求等待复用结果），对齐前端 1.6.4 |
| 多端策略 | 买家端默认多端在线（上限 5 台），新设备超限或被踢时旧端收到 1002 + 黑名单命中，前端提示「账号在其他设备登录」 |

> **与管理端 Token 的隔离（对齐 `admin-backend.md` 2.1，v1.1 补充）**：管理端是 Access Token 2 小时 + Refresh Token 7 天（httpOnly Cookie、单端登录），买家端是 Access Token 2 小时 + Refresh Token 30 天（本地安全存储、多端），**两者是不同受众的两套令牌，不可互认**。落地要求：
> 1. 签发时写入不同 `aud` 与**独立签名密钥对**（密钥轮换互不牵连），网关同时校验签名、有效期与 `aud`；
> 2. 管理端 Token 调用买家端接口（或反向）一律 `403 + 1004`，严禁因为「同一套 JWT 密钥」造成越权；
> 3. 两端各自的 Refresh Token 轮换规则语义一致：**同一 refreshToken 二次使用即判定失效并吊销全链路**，并记录 `did`/`sessionId` 供踢出与审计；
> 4. 黑名单均以 `jti/sessionId` 为键写 Redis，TTL = Token 剩余有效期，网关反查并做本地缓存（≤10s），黑名单服务故障时资金类接口保持拒绝。

#### 2.2.3 Token数据结构

```json
{
  "sub": "10001",           // 用户ID
  "did": "device-uuid-xxx", // 设备ID
  "type": "access",         // token类型
  "iat": 1697000000,        // 签发时间
  "exp": 1697007200,        // 过期时间
  "jti": "unique-token-id"  // Token唯一标识
}
```

### 2.3 设备绑定

#### 2.3.1 功能点
- 设备注册与绑定
- 设备解绑
- 多设备管理
- 设备风控（异常设备检测）

#### 2.3.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 注册设备 | POST | `/api/v1/device/register` | 注册新设备 |
| 绑定设备 | POST | `/api/v1/device/bind` | 绑定设备到用户 |
| 解绑设备 | POST | `/api/v1/device/unbind` | 解绑设备 |
| 设备列表 | GET | `/api/v1/device/list` | 获取用户设备列表 |
| 设备详情 | GET | `/api/v1/device/{deviceId}` | 获取设备详情 |

#### 2.3.3 设备注册详情

```
POST /api/v1/device/register
Content-Type: application/json

{
  "deviceId": "device-uuid-xxx",
  "deviceType": "android",
  "deviceModel": "Pixel 7",
  "osVersion": "14",
  "appVersion": "10.0.0",
  "pushToken": "fcm-token-xxx",
  "pushChannel": "fcm",
  "screenResolution": "1080x2400",
  "language": "zh-CN"
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "deviceId": "device-uuid-xxx",
    "bound": false,
    "createdAt": "2026-10-11T10:00:00Z"
  }
}
```

### 2.4 生物识别

#### 2.4.1 功能点
- 指纹识别登录
- 人脸识别登录
- 生物识别支付确认
- 生物识别安全设置

#### 2.4.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 开启指纹登录 | POST | `/api/v1/auth/biometric/enable` | 开启指纹登录 |
| 关闭指纹登录 | POST | `/api/v1/auth/biometric/disable` | 关闭指纹登录 |
| 指纹登录验证 | POST | `/api/v1/auth/biometric/verify` | 指纹验证 |
| 人脸登录验证 | POST | `/api/v1/auth/face/verify` | 人脸验证 |

#### 2.4.3 技术要点

- 生物识别数据**不存储在服务端**，仅存储在设备本地（KeyStore/Keychain）
- 服务端只验证生物识别成功后返回的加密签名
- 使用设备端生物识别API（Android BiometricPrompt）
- 服务端验证签名后颁发Token

---

## 三、商品服务

### 3.1 商品列表

#### 3.1.1 功能点
- 分类商品列表
- 品牌商品列表
- 活动商品列表
- 新品列表
- 热销榜单

#### 3.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 分类商品列表 | GET | `/api/v1/products/category/{categoryId}` | 按分类获取商品 |
| 品牌商品列表 | GET | `/api/v1/products/brand/{brandId}` | 按品牌获取商品 |
| 新品列表 | GET | `/api/v1/products/new` | 新品列表 |
| 热销榜单 | GET | `/api/v1/products/hot` | 热销商品榜单 |
| 商品筛选 | GET | `/api/v1/products/filter` | 多维度筛选 |

#### 3.1.3 商品列表详情

```
GET /api/v1/products/category/{categoryId}?page=1&pageSize=20&sort=price_asc&priceMin=100&priceMax=500

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 1000,
    "page": 1,
    "pageSize": 20,
    "list": [
      {
        "spuId": 10001,
        "name": "Apple iPhone 15 Pro",
        "mainImage": "https://cdn.example.com/iphone15.jpg",
        "price": 7999.00,
        "originalPrice": 8999.00,
        "salesCount": 50000,
        "rating": 4.9,
        "tags": ["新品", "包邮"],
        "skuCount": 5
      }
    ]
  }
}
```

### 3.2 商品详情

#### 3.2.1 功能点
- 商品基本信息
- SKU规格选择
- 商品图片/视频
- 商品详情（富文本）
- 用户评价摘要
- 推荐搭配
- 门店库存

#### 3.2.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 商品详情 | GET | `/api/v1/products/{spuId}` | 获取商品详情 |
| SKU详情 | GET | `/api/v1/products/{spuId}/sku/{skuId}` | 获取SKU详情 |
| 商品评价 | GET | `/api/v1/products/{spuId}/reviews` | 获取商品评价 |
| 推荐搭配 | GET | `/api/v1/products/{spuId}/recommendations` | 推荐搭配商品 |

#### 3.2.3 商品详情详情

```
GET /api/v1/products/{spuId}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "spuId": 10001,
    "name": "Apple iPhone 15 Pro",
    "subtitle": "钛金属设计，A17 Pro芯片",
    "mainImage": "https://cdn.example.com/iphone15.jpg",
    "images": ["url1", "url2", "url3"],
    "video": "https://cdn.example.com/iphone15-video.mp4",
    "price": 7999.00,
    "originalPrice": 8999.00,
    "salesCount": 50000,
    "rating": 4.9,
    "reviewCount": 12000,
    "detail": "<p>富文本详情...</p>",
    "specs": [
      {
        "name": "颜色",
        "values": ["原色钛金属", "蓝色钛金属", "白色钛金属"]
      },
      {
        "name": "存储容量",
        "values": ["128GB", "256GB", "512GB", "1TB"]
      }
    ],
    "skus": [
      {
        "skuId": 20001,
        "specs": {"颜色": "原色钛金属", "存储容量": "256GB"},
        "price": 7999.00,
        "stock": 100,
        "skuCode": "IP15P-256-NAT"
      }
    ],
    "services": ["7天无理由退货", "正品保证", "极速退款"],
    "shop": {
      "shopId": 30001,
      "shopName": "Apple官方旗舰店",
      "shopRating": 4.9
    }
  }
}
```

### 3.3 商品搜索

#### 3.3.1 功能点
- 关键词搜索
- 语音搜索
- 图片搜索（拍照识物）
- 搜索建议/自动补全
- 搜索历史
- 热门搜索

#### 3.3.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 关键词搜索 | GET | `/api/v1/search/keyword` | 关键词搜索商品 |
| 语音搜索 | POST | `/api/v1/search/voice` | 语音搜索 |
| 图片搜索 | POST | `/api/v1/search/image` | 拍照/相册搜索 |
| 搜索建议 | GET | `/api/v1/search/suggest` | 搜索自动补全 |
| 搜索历史 | GET | `/api/v1/search/history` | 用户搜索历史 |
| 热门搜索 | GET | `/api/v1/search/hot` | 热门搜索词 |

#### 3.3.3 搜索详情

```
GET /api/v1/search/keyword?q=手机&page=1&pageSize=20&sort=default&brandIds=1,2,3&priceMin=1000&priceMax=5000

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 5000,
    "page": 1,
    "pageSize": 20,
    "list": [...],
    "filters": {
      "brands": [
        {"id": 1, "name": "Apple", "count": 1200},
        {"id": 2, "name": "华为", "count": 800}
      ],
      "priceRanges": [
        {"min": 0, "max": 1000, "count": 500},
        {"min": 1000, "max": 3000, "count": 2000}
      ]
    },
    "relatedQueries": ["手机壳", "手机膜", "充电器"]
  }
}
```

### 3.4 商品推荐

#### 3.4.1 功能点
- 个性化推荐（首页猜你喜欢）
- 相似商品推荐
- 搭配推荐
- 浏览历史
- 购买过的人还买了

#### 3.4.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 首页推荐 | GET | `/api/v1/recommendations/home` | 首页个性化推荐 |
| 相似商品 | GET | `/api/v1/recommendations/similar/{spuId}` | 相似商品推荐 |
| 搭配推荐 | GET | `/api/v1/recommendations/match/{spuId}` | 搭配推荐 |
| 浏览历史 | GET | `/api/v1/recommendations/history` | 浏览历史记录 |
| 购买推荐 | GET | `/api/v1/recommendations/also-bought/{spuId}` | 买了还买了 |

#### 3.4.3 技术要点

- 推荐算法：协同过滤 + 内容推荐 + 深度学习模型
- 实时特征：用户最近点击、搜索、加购行为
- 离线特征：用户画像、购买偏好
- 推荐服务独立部署，通过Redis缓存结果
- 首页推荐结果缓存5分钟，减少计算压力

---

## 四、订单服务

### 4.1 下单

#### 4.1.1 功能点
- 购物车下单
- 立即购买下单
- 预售下单
- 拼团下单
- 订单预览（确认订单页）

#### 4.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 订单预览 | POST | `/api/v1/order/preview` | 确认订单页数据 |
| 创建订单 | POST | `/api/v1/order/create` | 创建订单 |
| 购物车结算 | POST | `/api/v1/order/cart-checkout` | 购物车批量结算 |

> **复用说明**：4.1.1 中的预售、拼团、秒杀下单**不新增接口**，统一走 `POST /api/v1/order/create`，通过 `activityId` + `activityType`（presale/groupbuy/seckill）标识，活动资格、限购与优惠由 `core-promotion` 实时校验，校验不通过返回 1001/3003；营销中心未开放前，这些字段为可选，未传即普通下单，避免出现「功能点有、接口没有」的断层。

#### 4.1.3 订单预览详情

```
POST /api/v1/order/preview
Content-Type: application/json

{
  "items": [
    {
      "skuId": 20001,
      "quantity": 1,
      "shopId": 30001
    }
  ],
  "addressId": 50001,
  "couponId": 60001,
  "usePoints": 1000
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [
      {
        "skuId": 20001,
        "spuId": 10001,
        "name": "Apple iPhone 15 Pro",
        "image": "https://cdn.example.com/iphone15.jpg",
        "specs": "原色钛金属 256GB",
        "price": 7999.00,
        "quantity": 1,
        "subtotal": 7999.00
      }
    ],
    "totalAmount": 7999.00,
    "discountAmount": 200.00,
    "couponDiscount": 100.00,
    "pointsDiscount": 10.00,
    "freight": 0.00,
    "payAmount": 7789.00,
    "address": {
      "id": 50001,
      "name": "张三",
      "mobile": "138****8000",
      "fullAddress": "北京市朝阳区xxx路xxx号"
    },
    "availableCoupons": [...],
    "availablePoints": 5000
  }
}
```

#### 4.1.4 下单幂等、并发与拆单要点

| 关注点 | 方案 |
|--------|------|
| 防重复提交 | 客户端生成 `idempotent_key`（UUID），同时随请求头 `X-Idempotency-Key` 与 body 提交；服务端以 `uk_idempotent_key` 唯一索引兜底，**命中后直接返回已创建订单**，不报错、不重复建单 |
| 幂等键归属 | 命中幂等键时必须校验 `user_id` 归属，不一致按 4001/1004 处理，防止跨用户探测或覆盖他人下单结果；幂等结果缓存 24 小时 |
| 金额权威 | 请求体中的金额一律忽略，由服务端 `core-price` 实时计价（叠加 `coupon_record_id`、`points_used`）；预览与创建两次计价结果可不同，以创建时为准 |
| 库存预占 | 下单成功即预占库存（Redis 原子预占 + DB 扣减），预占有效期与订单表 `pay_deadline` 对齐；关单/取消/支付失败自动释放，已支付订单不回滚 |
| 超卖防护 | Redis Lua 原子扣减 + DB `UPDATE ... SET stock = stock - ? WHERE stock >= ?` 双保险；秒杀/拼团走营销中心独立库存池，不与常规库存混用 |
| 超时关单 | **以订单表 `pay_deadline` 字段为唯一事实来源**：下单事务内写入付款截止时间，延迟队列（RabbitMQ TTL + 死信 或 延迟插件）的 `deliveryTimestamp` 直取该字段到期触发，与管理端使用同一调度语义与同一关单服务，避免「两端同时关单 / 两端都不关」；本端不自定义分钟数，原文「30分钟」为待产品联调前书面确认的参数（iOS/PC 原写 15 分钟，冲突由裁决 C7 消除）；延迟消息丢失时由每 5 分钟扫库任务按 `pay_deadline < now()` 兜底（对齐 admin-backend 4.4.4） |
| 拆单 | `items` 跨店铺时服务端按 `shop_id` 拆为父单 + 子单（`parent_order_id`），父单 `shop_id` 为空、子单必填；优惠与运费按子单金额比例分摊，分摊规则复用 `core-price`，保证管理端 4.4.2 拆单结果与买家端展示一致 |
| 并发一致 | 状态与金额变更一律带 `version` 乐观锁条件更新，`rows == 0` 返回 4002 订单状态错误；同一订单的取消与支付成功并发时先到者胜 |
| 快照固化 | `address_snapshot`、`coupon_record_id`、`order_item` 的价格与规格在下单事务内固化，后续用户改地址、改价、优惠券失效均不影响历史订单 |
| 事务边界 | 建单 + 订单项 + 库存预占 + 优惠券核销在同一本地事务/ Saga 内完成，任一步失败整体回滚；扣库存、核券的下游调用必须可重入 |

### 4.2 支付

#### 4.2.1 功能点
- 支付宝支付
- 微信支付
- 银联支付
- 余额支付
- 组合支付
- 支付密码验证
- 生物识别支付

#### 4.2.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 发起支付 | POST | `/api/v1/payment/create` | 创建支付订单 |
| 支付结果查询 | GET | `/api/v1/payment/{paymentId}` | 查询支付状态 |
| 支付密码验证 | POST | `/api/v1/payment/verify-password` | 验证支付密码 |
| 生物识别支付 | POST | `/api/v1/payment/biometric-pay` | 生物识别支付 |

#### 4.2.3 发起支付详情

```
POST /api/v1/payment/create
Content-Type: application/json

{
  "orderId": 80001,
  "payChannel": "alipay",     // alipay/wechat/unionpay/balance
  "payPassword": "encrypted",  // 支付密码(加密)
  "useBalance": 100.00        // 使用余额金额
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "paymentId": 90001,
    "payAmount": 7689.00,
    "payParams": {
      "alipay": "alipay_sdk_params...",
      "wechat": {
        "appId": "wx123456",
        "timeStamp": "1697000000",
        "nonceStr": "xxx",
        "package": "prepay_id=xxx",
        "signType": "RSA",
        "paySign": "xxx"
      }
    }
  }
}
```

#### 4.2.4 支付幂等与安全要点

| 关注点 | 方案 |
|--------|------|
| 支付单幂等 | `POST /api/v1/payment/create` 必须携带 `X-Idempotency-Key`（同一次支付意图复用同一键）；同一 `orderId` 仅允许存在一个进行中支付单，重复请求返回原 `paymentId`，避免重复拉起收银台或重复下单 |
| 金额以服务端为准 | 请求体中的 `useBalance`、`payAmount` 仅为意图声明，实际金额由服务端按订单 `pay_amount` 与 `core-price` 计算，禁止信任客户端金额 |
| 支付回调 | 渠道异步回调按 `paymentId + 渠道流水号` 幂等，重复回调直接应答成功；回调处理与订单状态变更同事务，失败进重试队列并告警 |
| 支付密码 | 仅密文上送（前端 RSA 加密），服务端经 KMS/AES-256 专用密钥解密比对；禁止明文落库、禁止写入日志与链路追踪 |
| 生物识别支付 | `/api/v1/payment/biometric-pay` 依赖设备 Keystore 密钥对签名 + 服务端公钥验签，服务端**不接收、不存储**任何生物特征；验签通过等价于支付密码通过 |
| 支付结果确认 | 拉起收银台后由 `GET /api/v1/payment/{paymentId}` 退避轮询（1s/2s/5s，上限 30s）+ 服务端主动查单兜底；**禁止客户端"支付成功"自证** |
| 关单与回调竞争 | 超时关单与支付成功回调并发时以订单 `version` 乐观锁裁决；已关单后收到成功回调走原路退款，不得回写为已支付 |
| 限频风控 | 支付密码连续错误 5 次锁定 30 分钟（返回 1004），单用户单日发起支付次数限频，大额支付强制二次验证（对齐 13.3） |

### 4.3 订单管理

#### 4.3.1 功能点
- 订单列表（按状态筛选）
- 订单详情
- 取消订单
- 确认收货
- 删除订单
- 订单状态跟踪

#### 4.3.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 订单列表 | GET | `/api/v1/orders` | 获取订单列表 |
| 订单详情 | GET | `/api/v1/orders/{orderId}` | 获取订单详情 |
| 取消订单 | POST | `/api/v1/orders/{orderId}/cancel` | 取消订单 |
| 确认收货 | POST | `/api/v1/orders/{orderId}/confirm` | 确认收货 |
| 删除订单 | DELETE | `/api/v1/orders/{orderId}` | 删除订单 |
| 再次购买 | POST | `/api/v1/orders/{orderId}/rebuy` | 再次购买 |

#### 4.3.3 订单列表详情

```
GET /api/v1/orders?status=unpaid&page=1&pageSize=20

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 50,
    "page": 1,
    "pageSize": 20,
    "list": [
      {
        "orderId": 80001,
        "orderNo": "OS202610110001",
        "status": 0,
        "statusText": "待付款",
        "totalAmount": 7789.00,
        "itemCount": 2,
        "items": [
          {
            "spuId": 10001,
            "name": "Apple iPhone 15 Pro",
            "image": "https://cdn.example.com/iphone15.jpg",
            "specs": "原色钛金属 256GB",
            "price": 7999.00,
            "quantity": 1
          }
        ],
        "createdAt": "2026-10-11T10:00:00Z",
        "actions": [
          {"type": "pay", "text": "去支付"},
          {"type": "cancel", "text": "取消订单"}
        ]
      }
    ]
  }
}
```

#### 4.3.4 订单状态机（与管理端同源）

状态取值以 `admin-backend.md` 1.2.2 `admin_order.order_status` 与 4.3 状态机为**唯一来源**，买家端只消费、不定义：

| status | 枚举 | 含义 | 允许的下一状态 | 前端 Tab 映射 |
|--------|------|------|----------------|----------------|
| 0 | unpaid | 待付款 | 1、6 | 待付款 |
| 1 | paid | 待发货(已付款) | 2、4 | 待发货 |
| 2 | shipped | 待收货(已发货) | 3、4 | 待收货 |
| 3 | completed | 已完成 | 4（申请售后） | 已完成 / 待评价 |
| 4 | refunding | 退款中 | 5、3 | —（列表并入售后） |
| 5 | refunded | 已退款 | 7 | —（列表并入售后） |
| 6 | cancelled | 已取消 | 终态 | 已取消 |
| 7 | closed | 已关闭 | 终态 | 已取消（按需合并展示） |

落地约定：

1. **查询参数枚举化**：`GET /api/v1/orders?status=` 只接受 `all|unpaid|paid|shipped|completed|cancelled|refunding|refunded|closed`（枚举名，与上表一一对应），**不接受中文或 `pending` 等自造值**；非法值按 1001 参数错误返回。
2. **「待评价」不是订单状态**：由 `status=completed&reviewed=false` 表达（评价属于评价服务维度，管理端 8 值状态机中同样没有该状态），前端不得把「待评价」映射成独立 status 值，否则会与管理端筛选结果对不上。
3. **删除语义**：`DELETE /api/v1/orders/{orderId}` 仅允许终态（3/6/7）且为**逻辑删除**（写 `deleted_at`），交易流水不可物理删除；默认列表过滤 `deleted_at IS NULL`，显式带 `includeDeleted=true` 才回查，售后/退款/发票链路不受删除影响。
4. **流转唯一入口**：所有状态变更（买家端取消/确认收货、管理端发货/退款、系统超时关单）都走同一状态机实现——乐观锁 `version` + 状态流水 + `OrderStatusChangedEvent`，禁止在买家端旁路改状态。
5. **文案与按钮由后端下发**：`statusText`、`actions` 由服务端按状态机产出，前端只渲染，保证买家端、浏览器端、管理端三处文案一致（前端不自建中文状态文案表）。
6. **遗留依赖**：管理端 4.4.1 的 `TRANSITIONS` 映射目前只覆盖 `UNPAID/PAID/SHIPPED/REFUNDING`，缺少 `COMPLETED→REFUNDING`、`REFUNDED→CLOSED` 与两个终态声明，需在 `admin-backend.md` 同步补齐后本表才可视为端到端生效（见文末审查记录遗留项）。

### 4.4 物流跟踪

#### 4.4.1 功能点
- 物流轨迹查询
- 物流详情
- 配送员信息
- 预计送达时间
- 物流订阅通知

#### 4.4.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 物流轨迹 | GET | `/api/v1/logistics/{orderId}` | 获取物流轨迹 |
| 物流详情 | GET | `/api/v1/logistics/{orderId}/detail` | 获取物流详情 |
| 配送员信息 | GET | `/api/v1/logistics/{orderId}/courier` | 获取配送员信息 |

#### 4.4.3 物流轨迹详情

```
GET /api/v1/logistics/{orderId}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "orderId": 80001,
    "logisticsNo": "SF1234567890",
    "logisticsCompany": "顺丰速运",
    "status": 2,
    "statusText": "运输中",
    "estimatedDelivery": "2026-10-12T18:00:00Z",
    "courier": {
      "name": "李师傅",
      "mobile": "139****9999",
      "avatar": "https://cdn.example.com/courier.jpg"
    },
    "timeline": [
      {
        "time": "2026-10-11T14:00:00Z",
        "status": "已发货",
        "description": "包裹已到达深圳分拣中心"
      },
      {
        "time": "2026-10-11T10:00:00Z",
        "status": "已发货",
        "description": "包裹已从商家仓库发出"
      }
    ]
  }
}
```

---

## 五、会员服务

### 5.1 个人信息

#### 5.1.1 功能点
- 个人信息查看/编辑
- 头像上传/裁剪
- 昵称修改
- 性别/生日设置
- 实名认证

#### 5.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 个人信息 | GET | `/api/v1/user/profile` | 获取个人信息 |
| 更新个人信息 | PUT | `/api/v1/user/profile` | 更新个人信息 |
| 上传头像 | POST | `/api/v1/user/avatar` | 上传头像 |
| 实名认证 | POST | `/api/v1/user/verify` | 实名认证 |

#### 5.1.3 个人信息详情

```
GET /api/v1/user/profile

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "userId": 10001,
    "nickname": "张三",
    "avatar": "https://cdn.example.com/avatar.jpg",
    "mobile": "138****8000",
    "email": "z***@example.com",
    "gender": 1,
    "birthday": "1990-01-01",
    "level": 5,
    "levelName": "钻石会员",
    "points": 5000,
    "growthValue": 12000,
    "verified": true,
    "verifiedName": "张*"
  }
}
```

### 5.2 地址管理

#### 5.2.1 功能点
- 地址列表
- 新增地址
- 编辑地址
- 删除地址
- 设置默认地址
- 地址解析（经纬度转地址）

#### 5.2.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 地址列表 | GET | `/api/v1/user/addresses` | 获取地址列表 |
| 新增地址 | POST | `/api/v1/user/addresses` | 新增地址 |
| 编辑地址 | PUT | `/api/v1/user/addresses/{addressId}` | 编辑地址 |
| 删除地址 | DELETE | `/api/v1/user/addresses/{addressId}` | 删除地址 |
| 设置默认地址 | POST | `/api/v1/user/addresses/{addressId}/default` | 设置默认地址 |
| 地址解析 | GET | `/api/v1/location/regeo` | 经纬度解析地址（统一由定位服务提供，见 7.3.2；原 `/api/v1/user/address/resolve` 已废弃，服务端兼容转发至本接口，v1.2 移除） |

### 5.3 收藏管理

#### 5.3.1 功能点
- 商品收藏
- 店铺收藏
- 收藏列表
- 取消收藏
- 收藏分类

#### 5.3.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 收藏列表 | GET | `/api/v1/user/favorites` | 获取收藏列表 |
| 添加收藏 | POST | `/api/v1/user/favorites` | 添加收藏 |
| 取消收藏 | DELETE | `/api/v1/user/favorites/{spuId}` | 取消收藏 |
| 批量取消收藏 | POST | `/api/v1/user/favorites/batch-delete` | 批量取消收藏 |

### 5.4 积分系统

#### 5.4.1 功能点
- 积分余额查询
- 积分明细
- 积分签到
- 积分兑换
- 积分商城

#### 5.4.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 积分余额 | GET | `/api/v1/user/points` | 获取积分余额 |
| 积分明细 | GET | `/api/v1/user/points/records` | 获取积分明细 |
| 每日签到 | POST | `/api/v1/user/points/sign` | 每日签到 |
| 积分兑换 | POST | `/api/v1/user/points/exchange` | 积分兑换商品 |

#### 5.4.3 积分明细详情

```
GET /api/v1/user/points/records?page=1&pageSize=20

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 100,
    "page": 1,
    "pageSize": 20,
    "list": [
      {
        "id": 1,
        "points": 100,
        "type": "order",
        "description": "订单消费获得积分",
        "relatedId": 80001,
        "createdAt": "2026-10-11T10:00:00Z"
      },
      {
        "id": 2,
        "points": -500,
        "type": "exchange",
        "description": "兑换优惠券",
        "createdAt": "2026-10-10T15:00:00Z"
      }
    ]
  }
}
```

---

## 六、消息服务

### 6.1 推送通知

#### 6.1.1 功能点
- FCM推送
- 厂商通道推送（小米、华为、OPPO、vivo）
- 推送策略（免打扰时段、频率控制）
- 推送统计（送达率、点击率）
- 富媒体推送（图片、按钮、 deeplink）

#### 6.1.2 推送通道架构

```
┌─────────────────────────────────────────────────────────────┐
│                      推送服务 (Push Service)                  │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐        │
│  │ 推送路由 │  │ 推送策略 │  │ 推送统计 │  │ 推送队列 │        │
│  └────┬────┘  └─────────┘  └─────────┘  └────┬────┘        │
│       │                                        │              │
│  ┌────▼────────────────────────────────────────▼────┐        │
│  │              通道适配器 (Channel Adapter)          │        │
│  ├─────────┬─────────┬─────────┬─────────┬─────────┤        │
│  │   FCM   │  小米   │  华为   │  OPPO   │  vivo   │        │
│  └─────────┴─────────┴─────────┴─────────┴─────────┘        │
└─────────────────────────────────────────────────────────────┘
```

#### 6.1.3 推送优先级策略

| 场景 | 通道优先级 | 说明 |
|------|------------|------|
| 订单状态变更 | 厂商通道 > FCM | 高优先级，确保送达 |
| 物流提醒 | 厂商通道 > FCM | 高优先级 |
| 促销活动 | FCM > 厂商通道 | 普通优先级 |
| 系统通知 | FCM > 厂商通道 | 普通优先级 |
| 个性化推荐 | FCM | 低优先级，可延迟 |

#### 6.1.4 技术要点

- **厂商通道集成**：小米Push、华为Push、OPPO Push、vivo Push SDK
- **Token管理**：设备Token与用户绑定，支持多设备
- **推送去重**：同一消息不重复推送
- **免打扰时段**：夜间22:00-08:00不推送促销类消息
- **频率控制**：单用户单类消息每天不超过5条
- **离线消息**：离线消息保留7天，上线后补推

### 6.2 系统通知

#### 6.2.1 功能点
- 系统公告
- 订单通知
- 物流通知
- 活动通知
- 通知已读/未读
- 通知设置

#### 6.2.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 通知列表 | GET | `/api/v1/notifications` | 获取通知列表 |
| 通知详情 | GET | `/api/v1/notifications/{id}` | 获取通知详情 |
| 标记已读 | POST | `/api/v1/notifications/{id}/read` | 标记通知已读 |
| 全部已读 | POST | `/api/v1/notifications/read-all` | 全部标记已读 |
| 删除通知 | DELETE | `/api/v1/notifications/{id}` | 删除通知 |
| 未读数 | GET | `/api/v1/notifications/unread-count` | 获取未读数量 |
| 通知设置 | GET/PUT | `/api/v1/notifications/settings` | 通知设置 |

#### 6.2.3 通知列表详情

```
GET /api/v1/notifications?type=order&page=1&pageSize=20

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 50,
    "unreadCount": 5,
    "page": 1,
    "pageSize": 20,
    "list": [
      {
        "id": 1,
        "type": "order",
        "title": "订单已发货",
        "content": "您的订单OS202610110001已发货",
        "image": "https://cdn.example.com/iphone15.jpg",
        "relatedId": 80001,
        "isRead": false,
        "createdAt": "2026-10-11T14:00:00Z",
        "deeplink": "easy-shopping://order/80001"
      }
    ]
  }
}
```

### 6.3 物流提醒

#### 6.3.1 功能点
- 发货提醒
- 派送提醒
- 签收提醒
- 物流异常提醒
- 自提提醒

#### 6.3.2 技术要点

- 物流状态变更通过RabbitMQ消息队列触发推送
- 推送内容包含物流单号、物流公司、当前状态
- 支持 deeplink 跳转到物流详情页
- 派送提醒包含配送员联系方式

---

## 七、定位服务

### 7.1 附近门店

#### 7.1.1 功能点
- 附近门店查询
- 门店详情
- 门店商品库存
- 门店导航
- 门店筛选

#### 7.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 附近门店 | GET | `/api/v1/location/nearby-stores` | 查询附近门店 |
| 门店详情 | GET | `/api/v1/location/stores/{storeId}` | 获取门店详情 |
| 门店商品 | GET | `/api/v1/location/stores/{storeId}/products` | 获取门店商品 |

#### 7.1.3 附近门店详情

```
GET /api/v1/location/nearby-stores?longitude=116.404&latitude=39.915&radius=5000&categoryId=1

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 10,
    "list": [
      {
        "storeId": 40001,
        "storeName": "Apple官方旗舰店(三里屯)",
        "address": "北京市朝阳区三里屯路19号",
        "distance": 1200,
        "distanceText": "1.2km",
        "longitude": 116.410,
        "latitude": 39.920,
        "businessHours": "10:00-22:00",
        "phone": "010-12345678",
        "services": ["自提", "维修", "以旧换新"],
        "stockAvailable": true
      }
    ]
  }
}
```

> **分页口径**：本接口按距离升序返回，固定 `page`/`pageSize`（默认 1/20，单次上限 50），`total` 为半径内命中总数，响应结构仍遵循 16.1 统一分页信封，前端不需为该接口单独适配。

### 7.2 配送范围

#### 7.2.1 功能点
- 配送范围查询
- 配送费计算
- 预计送达时间
- 配送区域校验

#### 7.2.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 配送范围查询 | GET | `/api/v1/location/delivery-range` | 查询配送范围 |
| 配送费计算 | POST | `/api/v1/location/delivery-fee` | 计算配送费 |
| 配送时间预估 | GET | `/api/v1/location/delivery-time` | 预估送达时间 |

### 7.3 地址解析

#### 7.3.1 功能点
- 经纬度转地址（逆地理编码）
- 地址转经纬度（地理编码）
- 地址智能提示
- 地址纠错

#### 7.3.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 逆地理编码 | GET | `/api/v1/location/regeo` | 经纬度转地址 |
| 地理编码 | GET | `/api/v1/location/geocode` | 地址转经纬度 |
| 地址提示 | GET | `/api/v1/location/address-tips` | 地址智能提示 |

#### 7.3.3 技术要点

- 使用高德地图API进行地理编码/逆地理编码
- Redis GEO存储门店位置信息，支持附近查询
- 地址提示使用ES进行模糊匹配
- 定位精度：GPS > 基站 > IP定位

---

## 八、分享服务

### 8.1 分享链接

#### 8.1.1 功能点
- 商品分享
- 店铺分享
- 活动分享
- 邀请好友
- 分享渠道（微信、朋友圈、QQ、微博、复制链接）
- 分享追踪（点击统计）

#### 8.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 生成分享链接 | POST | `/api/v1/share/create` | 生成分享链接 |
| 获取分享内容 | GET | `/api/v1/share/{shareCode}` | 获取分享内容 |
| 分享点击追踪 | POST | `/api/v1/share/{shareCode}/click` | 追踪分享点击 |
| 分享统计 | GET | `/api/v1/share/statistics` | 获取分享统计 |

#### 8.1.3 生成分享链接详情

```
POST /api/v1/share/create
Content-Type: application/json
Headers: Authorization: Bearer <access-token>   // 分享人身份取自Token，body不接收userId

{
  "shareType": "product",
  "targetId": 10001,
  "channel": "wechat"
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "shareCode": "SH202610110001",
    "shareUrl": "https://m.example.com/s/SH202610110001",
    "shareTitle": "Apple iPhone 15 Pro 仅售7999元",
    "shareDesc": "钛金属设计，A17 Pro芯片，快来抢购！",
    "shareImage": "https://cdn.example.com/iphone15-share.jpg",
    "miniProgramPath": "/pages/product/detail?spuId=10001&shareCode=SH202610110001"
  }
}
```

### 8.2 分享海报

#### 8.2.1 功能点
- 商品海报生成
- 活动海报生成
- 邀请海报生成
- 海报模板管理
- 海报保存/分享

#### 8.2.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 生成海报 | POST | `/api/v1/share/poster` | 生成分享海报 |
| 海报模板 | GET | `/api/v1/share/poster-templates` | 获取海报模板列表 |

#### 8.2.3 生成海报详情

```
POST /api/v1/share/poster
Content-Type: application/json
Headers: Authorization: Bearer <access-token>   // 海报归属人取自Token，body不接收userId

{
  "shareType": "product",
  "targetId": 10001,
  "templateId": 1,
  "elements": {
    "showPrice": true,
    "showQrCode": true,
    "showAvatar": true
  }
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "posterUrl": "https://cdn.example.com/posters/SH202610110001.jpg",
    "posterWidth": 750,
    "posterHeight": 1334
  }
}
```

### 8.3 渠道追踪

#### 8.3.1 功能点
- 分享渠道统计
- 分享转化漏斗
- 渠道效果分析
- 邀请奖励

#### 8.3.2 技术要点

- 每个分享链接生成唯一shareCode
- 用户点击分享链接时记录渠道、时间、设备信息
- 通过shareCode追踪转化（注册、下单）
- 邀请奖励：被邀请人首单后，邀请人获得奖励

---

## 九、扫码服务

### 9.1 扫码识物

#### 9.1.1 功能点
- 条形码识别
- 二维码识别
- 商品匹配
- 扫码历史
- 扫码结果展示

#### 9.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 扫码识物 | POST | `/api/v1/scan/product` | 扫码识别商品 |
| 扫码历史 | GET | `/api/v1/scan/history` | 获取扫码历史 |
| 清空扫码历史 | DELETE | `/api/v1/scan/history` | 清空扫码历史 |

#### 9.1.3 扫码识物详情

```
POST /api/v1/scan/product
Content-Type: application/json

{
  "scanContent": "6901234567890",  // 条形码/二维码内容
  "scanType": "barcode",           // barcode/qrcode
  "deviceId": "device-uuid-xxx"
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "scanType": "barcode",
    "scanContent": "6901234567890",
    "matchType": "product",         // product/store/none
    "product": {
      "spuId": 10001,
      "name": "Apple iPhone 15 Pro",
      "image": "https://cdn.example.com/iphone15.jpg",
      "price": 7999.00,
      "salesCount": 50000
    }
  }
}
```

### 9.2 扫码登录

#### 9.2.1 功能点
- PC端展示二维码
- APP扫码确认登录
- 二维码刷新
- 登录状态同步

#### 9.2.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 生成扫码登录二维码 | POST | `/api/v1/scan-login/qrcode` | 生成二维码 |
| 扫码确认 | POST | `/api/v1/scan-login/confirm` | APP扫码确认 |
| 查询登录状态 | GET | `/api/v1/scan-login/status/{qrCodeId}` | 查询登录状态（只回 ticket，不回 AccessToken） |
| 换取登录态 | POST | `/api/v1/auth/login/qr-ticket/exchange` | PC端用一次性 ticket 换取 AccessToken |
| 取消登录 | POST | `/api/v1/scan-login/cancel/{qrCodeId}` | 取消登录 |

#### 9.2.3 扫码登录流程

```
1. PC端请求生成二维码
   POST /api/v1/scan-login/qrcode
   Response: { "qrCodeId": "QR202610110001", "expireIn": 300 }

2. PC端展示二维码（内容为 qrCodeId）

3. APP扫码后请求确认（身份取自 APP 端 AccessToken，**不接收 body 中的 userId**）
   POST /api/v1/scan-login/confirm
   Headers: Authorization: Bearer <app-access-token>
   {
     "qrCodeId": "QR202610110001",
     "deviceId": "device-uuid-xxx",
     "confirm": true    // true确认 false取消
   }

4. PC端轮询登录状态（只返回一次性 ticket，不直接下发 AccessToken）
   GET /api/v1/scan-login/status/QR202610110001
   Response: { "status": "confirmed", "ticket": "tk-9f2c...", "expireIn": 60 }

5. PC端用 ticket 换取登录态（一次性、单次有效，换发即作废）
   POST /api/v1/auth/login/qr-ticket/exchange
   { "qrCodeId": "QR202610110001", "ticket": "tk-9f2c..." }
   Response: { "userId": 10001, "accessToken": "xxx", "refreshToken": "yyy", "expiresIn": 7200 }
```

#### 9.2.4 技术要点

- 二维码有效期5分钟，过期自动刷新
- 二维码内容加密，防止伪造
- 扫码确认需要APP端已登录
- 支持扫码登录PC端、iPad端
- **不随状态接口下发长效凭证**：`status` 只返回 60 秒、绑定 `qrCodeId`、单次有效的 ticket，PC 端再换取 AccessToken，避免二维码被截图/中间人截获后直接盗号；ticket 兑换后立即作废，二次兑换返回 1003
- **伪造与重放防护**：`qrCodeId` 为 128bit 以上随机值且不可枚举；确认接口按「设备 + qrCodeId」限一设备一确认；轮询与兑换接口按 IP + qrCodeId 限频（如 5 次/秒），超限返回 1001
- **会话绑定**：换发的 AccessToken `did` 写入 PC 端会话标识，PC 端后续刷新 Token 必须携带同一 session，APP 端「退出扫码登录」可即时吊销

---

## 十、更新服务

### 10.1 版本管理

#### 10.1.1 功能点
- 版本信息查询
- 版本更新检测
- 强制更新
- 灰度发布
- 版本回滚

#### 10.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 版本检测 | GET | `/api/v1/update/check` | 检测版本更新 |
| 版本列表 | GET | `/api/v1/update/versions` | 获取版本列表 |
| 灰度配置 | GET | `/api/v1/update/gray-config` | 获取灰度配置 |

#### 10.1.3 版本检测详情

```
GET /api/v1/update/check?platform=android&versionCode=100&channel=official&deviceId=xxx

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "hasUpdate": true,
    "updateType": 1,              // 0-无需更新 1-普通更新 2-强制更新 3-灰度更新
    "versionCode": 105,
    "versionName": "10.5.0",
    "downloadUrl": "https://cdn.example.com/app-10.5.0.apk",
    "fileSize": 52428800,
    "md5": "a1b2c3d4e5f6...",
    "updateDesc": "1. 优化首页加载速度\n2. 修复已知问题\n3. 新增扫码功能",
    "isGray": false,
    "grayRatio": 100
  }
}
```

### 10.2 灰度发布

#### 10.2.1 功能点
- 按用户ID灰度
- 按设备ID灰度
- 按地区灰度
- 按渠道灰度
- 灰度比例控制

#### 10.2.2 灰度策略

| 灰度维度 | 说明 | 示例 |
|----------|------|------|
| 用户ID | 指定用户ID列表或取模 | userId % 100 < 10 |
| 设备ID | 指定设备ID列表 | deviceId in [...] |
| 地区 | 按省份/城市 | 北京、上海 |
| 渠道 | 按下载渠道 | 应用商店、官网 |
| 版本 | 按当前版本 | versionCode = 100 |

### 10.3 热修复

#### 10.3.1 功能点
- 热修复包管理
- 热修复下发
- 热修复回滚
- 修复状态追踪

#### 10.3.2 技术要点

- 使用Tinker/Sophix等热修复框架
- 热修复包按版本下发
- 修复失败自动回滚
- 修复成功率监控

---

## 十一、客服服务

### 11.1 在线客服

#### 11.1.1 功能点
- 人工客服接入
- 智能机器人
- 会话管理
- 消息收发（文字、图片、语音）
- 客服评价
- 会话转接

#### 11.1.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 创建会话 | POST | `/api/v1/cs/session` | 创建客服会话 |
| 发送消息 | POST | `/api/v1/cs/session/{sessionId}/message` | 发送消息 |
| 消息列表 | GET | `/api/v1/cs/session/{sessionId}/messages` | 获取消息列表 |
| 结束会话 | POST | `/api/v1/cs/session/{sessionId}/close` | 结束会话 |
| 评价客服 | POST | `/api/v1/cs/session/{sessionId}/rate` | 评价客服 |
| 客服状态 | GET | `/api/v1/cs/status` | 查询客服在线状态 |

#### 11.1.3 技术要点

- 使用WebSocket实现实时消息
- 智能机器人使用NLP自动回复
- 会话超时自动关闭（30分钟无消息）
- 客服分配：轮询 + 技能组匹配
- 消息持久化到MySQL
- **WebSocket 鉴权**：握手 URL 携带 AccessToken（`wss://...?ticket=`），网关按 13.1 同规则校验；心跳 30s，超时断连；重连时携带 `lastSeq` 增量补齐消息，避免弱网丢消息
- **消息幂等与有序**：上行消息带 `clientMsgId`（去重）+ 服务端下发全局递增 `seq`（排序），重复 `clientMsgId` 直接返回原 `msgId`，客户端按 `seq` 去重排序
- **内容安全**：文字/图片/语音入库前后接入内容安全审核，命中即撤回并标记；客服侧同样受审计，敏感操作留痕
- **背压与限频**：单会话消息限频（如 20 条/10s），服务端积压超阈值降级为「排队提示」，防止刷消息打爆会话与审核服务

### 11.2 工单系统

#### 11.2.1 功能点
- 工单创建
- 工单列表
- 工单详情
- 工单处理
- 工单评价
- 工单统计

#### 11.2.2 API设计

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 创建工单 | POST | `/api/v1/cs/ticket` | 创建工单 |
| 工单列表 | GET | `/api/v1/cs/tickets` | 获取工单列表 |
| 工单详情 | GET | `/api/v1/cs/tickets/{ticketId}` | 获取工单详情 |
| 工单回复 | POST | `/api/v1/cs/tickets/{ticketId}/reply` | 工单回复 |
| 关闭工单 | POST | `/api/v1/cs/tickets/{ticketId}/close` | 关闭工单 |
| 评价工单 | POST | `/api/v1/cs/tickets/{ticketId}/rate` | 评价工单 |

#### 11.2.3 工单创建详情

```
POST /api/v1/cs/ticket
Content-Type: application/json

{
  "category": "refund",
  "title": "申请退款",
  "description": "商品有质量问题，申请退款",
  "images": ["https://cdn.example.com/1.jpg"],
  "orderId": 80001,
  "priority": 1
}

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "ticketId": 70001,
    "ticketNo": "TK202610110001",
    "status": 0,
    "statusText": "待处理",
    "createdAt": "2026-10-11T10:00:00Z"
  }
}
```

---

## 十二、性能优化

### 12.1 接口聚合

#### 12.1.1 功能点
- 首页接口聚合
- 订单预览接口聚合
- 商品详情接口聚合
- 个人中心接口聚合

#### 12.1.2 技术要点

- 使用GraphQL或自定义聚合层
- 减少客户端请求次数
- 并行调用多个下游服务
- 聚合结果缓存

#### 12.1.3 首页聚合接口

```
GET /api/v1/aggregation/home
Headers: Authorization: Bearer <access-token>   // 身份取自Token，禁止query传userId（见13.1）

Response:
{
  "code": 0,
  "message": "success",
  "data": {
    "userInfo": { ... },
    "banners": [ ... ],
    "categories": [ ... ],
    "hotProducts": [ ... ],
    "recommendations": [ ... ],
    "unreadCount": 5,               // 顶层 unreadCount，与前端 1.6.1 聚合契约字段名一致
    "notifications": { "unreadCount": 5 },  // 分组结构，与顶层同源同值，二者不可各算各的
    "cartInfo": { "count": 3 }
  }
}
```

> **聚合层约束**：① `userInfo`/`unreadCount`/`cartInfo`/`recommendations` 为个性化数据，缓存必须**按 userId 分片**且 TTL ≤ 5s（或不缓存），禁止全量共享缓存导致串号；② `banners`/`categories`/`hotProducts` 可公共缓存 5 分钟；③ 下游任一域超时（1s）即**降级返回空数组**并标记 `degraded: [域]`，不得整体 500——首页可用性优先于数据完整（对齐前端 8s 超时与降级预期）；④ 聚合接口不参与 CDN 缓存（见 12.3.2「API 响应不缓存」）。

### 12.2 缓存策略

#### 12.2.1 多级缓存架构

```
┌─────────────────────────────────────────────────────────────┐
│                      客户端缓存                              │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐                     │
│  │ 内存缓存 │  │ 磁盘缓存 │  │ 图片缓存 │                     │
│  └─────────┘  └─────────┘  └─────────┘                     │
└────────────────────────┬────────────────────────────────────┘
                         │ 缓存未命中
┌────────────────────────▼────────────────────────────────────┐
│                   CDN缓存 (静态资源)                         │
└────────────────────────┬────────────────────────────────────┘
                         │ 缓存未命中
┌────────────────────────▼────────────────────────────────────┐
│                   Redis缓存 (热点数据)                       │
└────────────────────────┬────────────────────────────────────┘
                         │ 缓存未命中
┌────────────────────────▼────────────────────────────────────┐
│                   MySQL (持久化数据)                         │
└─────────────────────────────────────────────────────────────┘
```

#### 12.2.2 缓存更新策略

| 策略 | 适用场景 | 说明 |
|------|----------|------|
| Cache-Aside | 通用场景 | 先读缓存，未命中读DB，再写缓存 |
| Write-Through | 写多读多 | 写DB同时写缓存 |
| Write-Behind | 写密集 | 先写缓存，异步写DB |
| 过期自动刷新 | 热点数据 | 缓存过期前主动刷新 |

#### 12.2.3 缓存可靠性（穿透 / 击穿 / 雪崩）

| 问题 | 场景 | 方案 |
|------|------|------|
| 穿透 | 恶意请求不存在的 `spuId`/`orderId`，绕过缓存打穿到 DB | 空值缓存（TTL 30~60s）+ 布隆过滤器（商品/类目维度）+ 参数合法性校验（ID 段、雪花结构） |
| 击穿 | 热点 Key 过期瞬间大量并发回源 | 互斥锁重建（`SET NX` 只允许一个请求回源）+ 本地缓存毫秒级兜底 + 热点 Key 永不过期、异步刷新 |
| 雪崩 | 批量 Key 同时过期 / Redis 节点故障 | 过期时间加随机抖动（基础 TTL ± 10%）+ Redis 集群哨兵与从库读 + 多级缓存降级（本地 → Redis → DB）+ 熔断限流（对齐 12.5.3） |
| 缓存与 DB 不一致 | 商品改价/改状态后读到旧值 | Cache-Aside：先更 DB 再删缓存 + 延迟双删；下单计价、扣库存等强一致场景**不读缓存**，直接回源（见 1.1 边界说明第 3 条） |
| 大 Key / 热 Key | 单个聚合对象过大、单 Key 命中集中 | 大 Key 拆分（首页聚合按域拆分后并行读取）、热 Key 本地缓存 + 名称打散（副本 Key） |

### 12.3 CDN加速

#### 12.3.1 功能点
- 静态资源CDN加速
- 图片CDN加速
- 视频CDN加速
- API响应CDN缓存
- CDN预热

#### 12.3.2 CDN配置

| 资源类型 | CDN缓存时间 | 说明 |
|----------|-------------|------|
| JS/CSS | 7天 | 带版本号，可长期缓存 |
| 图片 | 30天 | 带版本号或hash |
| 视频 | 30天 | 大文件分片缓存 |
| API响应 | 不缓存 | 动态内容不缓存 |
| 首页HTML | 不缓存 | 动态内容不缓存 |

### 12.4 图片优化

#### 12.4.1 功能点
- 图片压缩
- 图片裁剪
- WebP格式适配
- 图片懒加载
- 渐进式加载
- 图片CDN

#### 12.4.2 图片处理服务

```
原图上传 → 图片处理服务 → 生成多尺寸图片 → CDN分发
                              │
                              ├── 缩略图 (200x200)
                              ├── 列表图 (400x400)
                              ├── 详情图 (800x800)
                              ├── 大图 (1200x1200)
                              └── WebP格式
```

#### 12.4.3 图片URL规则

```
https://cdn.example.com/{width}x{height}/{format}/{imagePath}

示例:
https://cdn.example.com/400x400/webp/products/iphone15.jpg
https://cdn.example.com/800x800/webp/products/iphone15.jpg
https://cdn.example.com/200x200/webp/avatar/user10001.jpg
```

### 12.5 其他优化

#### 12.5.1 网络优化

| 优化项 | 方案 |
|--------|------|
| 协议 | HTTP/2 或 HTTP/3 |
| 压缩 | Gzip / Brotli |
| 连接复用 | Keep-Alive |
| 请求合并 | 接口聚合 |
| 数据压缩 | Protocol Buffers |

#### 12.5.2 数据库优化

| 优化项 | 方案 |
|--------|------|
| 读写分离 | 主库写，从库读 |
| 分库分表 | 按用户ID分片 |
| 索引优化 | 覆盖索引、联合索引 |
| 慢查询优化 | 定期分析慢查询日志 |
| 连接池 | HikariCP |

#### 12.5.3 服务端优化

| 优化项 | 方案 |
|--------|------|
| 异步处理 | RabbitMQ消息队列 |
| 线程池 | 合理配置线程池参数 |
| JVM调优 | G1GC，合理堆内存 |
| 限流熔断 | Sentinel / Hystrix |
| 服务降级 | 非核心服务降级 |

---

## 十三、安全设计

### 13.1 接口安全

| 安全措施 | 说明 |
|----------|------|
| HTTPS | 全站 HTTPS（TLS 1.3），App 侧证书锁定 + `usesCleartextTraffic=false`，证书轮换需双证书并行过渡 |
| 签名验证 | **HMAC-SHA256**，待签串 `method\npath\ntimestamp\nnonce\nsortedQuery\nbodySha256`；secret 随登录响应下发并存 Android Keystore，支持登录后轮换 |
| 时间戳 | 请求头 `X-Timestamp`（毫秒），**容差 ±5 分钟**，超出按 1001 拒绝（与前端 1.6.4 约定一致） |
| 防重放 | 请求头 `X-Nonce`，Redis `SET NX` 去重（TTL = 2 × 时间戳容差），命中重复直接拒绝；签名覆盖 nonce，无法改包重放 |
| 身份标识来源 | **用户身份一律取自网关校验后的 AccessToken（或 `X-User-Id` 内部透传头）**，任何接口禁止信任 query/body 中的 `userId`、`uid` 等字段；设备维度标识取 `X-Device-Id` 请求头，不从 body 读取 |
| 幂等 | 写接口统一 `X-Idempotency-Key`（下单、支付、加购、签到等），网关 + 服务端双层幂等表，重复请求返回首次结果 |
| 限流 | 网关按「用户 + 接口」令牌桶限流，登录/短信/验证码/领券等敏感接口单独更严阈值，超限返回 429 且业务码 1001 |
| 风控 | 异常行为检测（见 13.3），命中降级为验证码/二次验证 |

**HTTP 状态码与业务码映射（与前端 1.6.3 约定一致）**：

| HTTP | 业务码 | 场景 |
|------|--------|------|
| 200 | 0 / 其他业务码 | 业务成功或业务失败，均在 body 中携带 `code` |
| 401 | 1002 / 1003 | 网关未认证或 Token 过期（黑名单命中同样返回 401 + 1002，前端据此提示「账号在其他设备登录」） |
| 403 | 1004 | 已登录但无权限 / audience 不匹配（管理端 Token 调买家端接口） |
| 429 | 1001 | 限流触发 |
| 500 | 9999 | 未捕获异常，`requestId` 必填用于排障 |

### 13.2 数据安全

| 安全措施 | 说明 |
|----------|------|
| 密码加密 | BCrypt/Argon2 |
| 敏感数据加密 | AES-256加密存储 |
| 传输加密 | TLS 1.3 |
| 数据脱敏 | 手机号、身份证号脱敏 |
| SQL注入防护 | 参数化查询 |

### 13.3 风控策略

| 场景 | 风控措施 |
|------|----------|
| 登录 | 异地登录提醒、设备验证 |
| 下单 | 异常订单拦截、地址验证 |
| 支付 | 大额支付验证、生物识别 |
| 领券 | 防刷券、频率限制 |
| 分享 | 防羊毛、异常分享检测 |

---

## 十四、监控与运维

### 14.1 监控体系

| 监控类型 | 工具 | 监控指标 |
|----------|------|----------|
| 应用监控 | Prometheus + Grafana | QPS、RT、错误率 |
| 链路追踪 | SkyWalking | 调用链、耗时分布 |
| 日志监控 | ELK | 错误日志、业务日志 |
| 基础设施 | Prometheus | CPU、内存、磁盘、网络 |
| 业务监控 | 自定义 | 订单量、GMV、转化率 |

### 14.2 告警策略

| 告警级别 | 触发条件 | 通知方式 |
|----------|----------|----------|
| P0-紧急 | 服务不可用、核心接口错误率>5% | 电话 + 短信 + 企业微信 |
| P1-严重 | 接口RT>3s、错误率>1% | 短信 + 企业微信 |
| P2-警告 | 接口RT>1s、错误率>0.5% | 企业微信 |
| P3-提示 | 磁盘使用>80%、内存使用>80% | 企业微信 |

### 14.3 运维工具

| 工具 | 用途 |
|------|------|
| Kubernetes | 容器编排 |
| Docker | 容器化 |
| Jenkins / GitLab CI | CI/CD |
| Ansible | 自动化运维 |
| Terraform | 基础设施即代码 |

---

## 十五、开发排期

### 15.1 阶段划分

| 阶段 | 时间 | 里程碑 |
|------|------|--------|
| 第一阶段：基础架构 | 第1-4周 | 架构搭建、数据库设计、基础服务 |
| 第二阶段：核心功能 | 第5-12周 | 认证、商品、订单、支付 |
| 第三阶段：移动端特有 | 第13-18周 | 推送、定位、分享、扫码 |
| 第四阶段：完善优化 | 第19-22周 | 客服、更新、性能优化 |
| 第五阶段：测试上线 | 第23-26周 | 测试、灰度发布、全量上线 |

### 15.2 团队配置

| 角色 | 人数 | 职责 |
|------|------|------|
| 架构师 | 1 | 架构设计、技术选型、技术评审 |
| 后端开发 | 6 | 服务开发、API实现 |
| 前端开发 | 2 | 管理后台、H5页面 |
| 测试工程师 | 2 | 功能测试、性能测试 |
| 运维工程师 | 1 | 部署、监控、运维 |
| 产品经理 | 1 | 需求管理、项目管理 |

---

## 十六、附录

### 16.1 API统一响应格式

```json
{
  "code": 0,           // 0-成功，非0-失败
  "message": "success", // 响应消息
  "data": { },         // 响应数据
  "timestamp": 1697000000000,
  "requestId": "req-xxx"
}
```

**分页契约**：所有列表接口 `data` 统一为 `{ total, page, pageSize, list }`（与 `android-frontend.md` 1.6.3 一致）；不返回分页字段的接口必须在本文档显式标注「非分页」。

- `page` 从 1 开始，`pageSize` 默认 20、上限 100，超限按 1001 参数错误返回；
- 深翻页（`page > 100`）不保证性能与结果稳定性，feed 类接口的 cursor 分页**列入待契约**（见 16.4），上线前不承诺该场景；
- 排序字段由服务端白名单校验，禁止把前端传入的字段名直接拼进 SQL/DSL。

**错误响应示例**：

```json
{
  "code": 4002,
  "message": "订单状态不允许该操作",
  "data": null,
  "timestamp": 1697000000000,
  "requestId": "req-xxx"
}
```

### 16.2 错误码定义

| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 1001 | 参数错误（含签名/时间戳/nonce 校验失败、限流 429） |
| 1002 | 未登录（或 Token 已被拉黑：多端互踢、登出后使用） |
| 1003 | Token过期（含 Refresh Token 失效/二次使用） |
| 1004 | 无权限（含受众不匹配：管理端 Token 调买家端接口） |
| 2001 | 用户不存在 |
| 2002 | 密码错误 |
| 2003 | 验证码错误 |
| 2004 | 验证码已过期 |
| 2005 | 短信发送过于频繁 |
| 3001 | 商品不存在 |
| 3002 | 库存不足 |
| 3003 | 商品已下架/失效 |
| 4001 | 订单不存在 |
| 4002 | 订单状态错误（状态机不允许该流转） |
| 5001 | 支付失败 |
| 5002 | 余额不足 |
| 9999 | 系统错误 |

> **使用约定**：① 该错误码空间为**买家端专属**，与管理端字典（`admin-backend.md`）分属不同下发通道，两端不共用同一张码表；② 前端已依赖的 `0/1001/1002/1003/1004/3002/4002/9999` 语义**不可变更**（见 `android-frontend.md` 1.6.3）；③ HTTP 401/403/429/500 与业务码的映射见 13.1；④ 新增错误码须先更新本表并同步前端 `core-network` 白名单，未知码一律按 9999 通用兜底展示 `message`。

### 16.3 技术选型总结

| 类别 | 技术选型 |
|------|----------|
| 开发语言 | Java / Go / Node.js / Python |
| 框架 | Spring Boot / Gin / Express |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7.0 |
| 搜索引擎 | Elasticsearch 8.x |
| 消息队列 | RabbitMQ |
| 对象存储 | 阿里云OSS / 腾讯云COS |
| CDN | 阿里云CDN / 腾讯云CDN |
| 容器化 | Docker + Kubernetes |
| 监控 | Prometheus + Grafana + SkyWalking |
| 日志 | ELK (Elasticsearch + Logstash + Kibana) |

### 16.4 待契约接口清单（与 `android-frontend.md` 1.6.2 双向对齐）

> 本表是后端**已知但尚未定义**的接口，与前端 1.6.2 及各模块 `⏳待契约` 标注一一对应。规则：**未出现在本文档其余章节、也未列入本表的路径，一律视为"未评估"**，前端不得据此开发；本表接口一旦定稿即补写进对应章节并同步前端，P0 项必须在第一阶段（第 1-4 周）完成契约闭环。

| 优先级 | 接口（拟定） | 归属服务 | 阻塞的前端模块 | 计划交付 |
|--------|--------------|----------|----------------|----------|
| P0 | 购物车全套：`GET /api/v1/cart/list`、`POST /cart/add`、`/cart/update`、`/cart/remove`、`/cart/select`、`/cart/batch-remove`、`/cart/batch-favorite`、`/cart/stock-check`（写接口带 `clientRequestId` 幂等；**用 cartItemId 而非 skuId 定位行**） | 购物车（共享服务） | 第七章购物车、第八章结算（仅有 `POST /api/v1/order/cart-checkout` 远不够） | 第 1-4 周出契约 / 第 5-8 周实现 |
| P0 | 通用文件上传 `POST /api/v1/file/upload`（multipart → `{url, width, height, hash}`；单文件 ≤10MB，白名单 jpg/png/webp/mp4，按用户限频） | 文件服务 | 头像、售后图片、工单图片、客服图片、图片搜索 | 第 1-4 周 |
| P0 | 评价发表 `POST /api/v1/orders/{orderId}/review` + 晒图（订单状态机 `completed` 且 `reviewed=false`） | 评价服务 | 订单列表「去评价」、待评价 Tab | 第 1-4 周出契约 / 第 9-12 周实现 |
| P0 | 注册 `POST /api/v1/auth/register`，**或**明确「验证码登录即自动注册」并关闭独立注册入口 | 会员中心（认证） | 3.2 注册页 | 第 1-4 周（必须二选一，不能两处都没有） |
| P1 | 售后 `/api/v1/aftersale/{create,list,{id},cancel,return-logistics}`，状态需与 4.3.4 状态机的 4/5/7 联动 | 售后服务（共享） | 订单详情「售后」、退款进度 | 第 9-12 周 |
| P1 | 优惠券 `GET /api/v1/coupon/available`、`POST /coupon/{id}/receive` | 营销中心 | 结算页优惠选择 | 第 9-12 周 |
| P1 | 首页活动位：`GET /api/v1/seckill/list`、`GET /api/v1/splash/config`、`GET /api/v1/onboarding/config` | 营销中心 / 内容服务 | 启动页、引导页、首页秒杀（可降级隐藏） | 第 13-18 周 |
| P1 | 分类树 `GET /api/v1/category/tree` | 商品中心（类目） | 首页金刚区、分类页 | 第 5-8 周 |
| P1 | 埋点上报 `POST /api/v1/track/batch`（批量、去标识、失败本地重试） | 数据服务 | 曝光/漏斗统计 | 第 13-18 周 |
| P1 | 收藏单查 `GET /api/v1/user/favorites/{spuId}` | 会员中心 | 商品详情「是否已收藏」 | 第 5-8 周 |
| P1 | 账号安全：`POST /api/v1/user/password/change`、`POST /api/v1/user/phone/bind`、`GET/PUT /api/v1/push/config`、`GET /api/v1/user/stats` | 会员中心 / 推送服务 | 账户安全页、推送设置 | 第 13-18 周 |
| P2 | 协议与配置：`GET /api/v1/agreement/{privacy,user}`、`GET /api/v1/permission/config`、`GET /api/v1/region/list` | 内容服务 | 启动/注册/地址选择（可内置兜底） | 第 19-22 周 |
| P2 | 搜索增强：`GET /api/v1/search/correct`、`GET /api/v1/search/discovery` | 搜索服务 | 搜索纠错、搜索发现（缺失可降级） | 第 19-22 周 |
| P2 | 扫码支付/被扫：`POST /api/v1/scan/payment`（`scan_record.scan_type` 已预留 `payment`，但 9.1 未定义接口） | 扫码服务 + 支付中心 | 扫一扫付款码场景 | 第 19-22 周（若不做须删除 DDL 中的 `payment` 类型） |
| P2 | feed 游标分页 `cursor` 参数（`page>100` 深翻页） | 搜索/推荐服务 | 首页推荐流深翻页 | 第 19-22 周 |

**闭环流程**：① 契约先在本表登记（含请求/响应示例），评审通过后写入正文对应章节并从本表移除；② 前端 1.6.2 的每一行必须能在本表或正文找到对应项，出现「两边都没有」的接口即为评审失败项；③ P0 未闭环前，相关前端模块不得进入联调。

---

> 本文档为安卓客户端后端开发计划，后续根据实际开发情况持续更新。

---

## 审查记录

> 评审方式：与 `android-frontend.md` 1.6.1 接口契约对照表、1.6.2 待契约清单、1.6.3 响应/错误码、1.6.4 Token 约定，以及 `admin-backend.md` 1.1.3 共享服务、1.2.1 分片约束、1.2.2 `admin_order` 表、2.1 认证方案、4.3 订单状态机逐项比对，并复核微服务边界、幂等、并发、安全、性能与完整性。
> 评审结论：发现 22 项问题，均已在本文修复；另有 4 项遗留依赖需其他文档同步（见末尾「遗留项」）。

### v1.0 → v1.1 修改记录

- [1] **P0｜订单状态枚举与管理端冲突**：本文 `order.status` 为 7 值且 4=已取消/5=退款中/6=已退款，与 `admin-backend.md` 1.2.2 `admin_order.order_status`（8 值：4=退款中、5=已退款、6=已取消、7=已关闭）逐值错位 → 1.4.1 订单表注释改为 8 值并注明「与 4.3 及管理端逐值一致」；新增 4.3.4 状态机映射表、查询参数枚举与前端 Tab 映射。
- [2] **P0｜订单列表示例与状态自相矛盾**：`?status=pending`（非枚举值）+ `status:1` 却写 `statusText:"已付款"`（1 应为待发货），且 `actions` 是待付款按钮 → 4.3.3 改为 `?status=unpaid`、`status:0`、`statusText:"待付款"`，并在 4.3.4 约定 `statusText/actions` 由后端下发、参数只接受枚举名。
- [3] **P0｜`pay_type` 假称「与 admin 一致」**：管理端字典为 1-支付宝 2-微信 3-银行卡，无「4-余额」→ 1.4.1 注释改为「3-银联（管理端字典为银行卡，同一枚举）4-余额（买家端扩展，须同步管理端字典，否则管理端筛选/导出取不到值）」。
- [4] **订单主键与 order_no 全局唯一策略同 admin 1.2.1 分片约束矛盾**（本文 `AUTO_INCREMENT`，管理端要求分表后改雪花 + 编码规则兜底）→ 1.4.1 订单表新增「注意2」，主键与 `order_no` 注释补充分表落地口径。
- [5] **父单 `shop_id NOT NULL` 与拆单冲突**：跨店铺下单时父单无归属店铺 → `shop_id` 改为可空并标注「子单必填、父单为空=多店铺合并单」，对齐管理端 `store_id` 语义；拆单与分摊规则写入 4.1.4。
- [6] **`DELETE /api/v1/orders/{orderId}` 缺少软删字段**，存在物理删除交易流水风险 → `order` 表新增 `deleted_at` 与 `idx_user_deleted`，4.3.4 约定「仅终态可删、逻辑删除、默认列表过滤、售后/退款/发票不受影响」。
- [7] **推送记录唯一键 `(msg_id, channel)` 会挡住同厂商第二台设备**，且 `device_pk` 可空使唯一约束失效 → `device_pk` 改 `NOT NULL`，唯一键改为 `uk_msg_id_device_channel (msg_id, device_pk, channel)`，表头补充扇出粒度说明。
- [8] **`device.push_token` 与 `device_push_token` 双写无权威声明**，易出现通道 Token 不一致 → 字段注释标明冗余、权威存储与同事务双写要求。
- [9] **微服务边界未讲清订单在管理端的形态**（管理端 1.1.3 只共享 product/price/promotion/user，却有 `admin_order_*` 表）→ 1.1 边界说明新增「与管理端读模型的关系」三条硬约束：订单事实来源唯一、状态机/事件/金额口径同源、变更以事件为准且下单计价实时回源。
- [10] **P0｜越权风险：多处由客户端传 `userId`**（`/aggregation/home?userId=`、`share/create`、`share/poster`、`scan-login/confirm` body）→ 四处全部改为取自 AccessToken（含请求头示例），并在 13.1 新增「身份标识来源：禁止信任 query/body 中的 userId，设备标识取 `X-Device-Id`」。
- [11] **首页聚合响应字段与前端契约不一致**（前端 1.6.1 要求 `unread`，本文只有 `notifications.unreadCount`）→ 12.1.3 新增顶层 `unreadCount` 并注明与分组结构同源同值；补充个性化数据按 userId 分片、TTL≤5s、下游降级返回空数组、不进 CDN 的约束。
- [12] **下单链路缺幂等/并发/库存闭环**：`idempotent_key`、`auto_close_at` 只有字段没有规则 → 新增 4.1.4：幂等键 + `X-Idempotency-Key`、幂等键归属校验、金额以 `core-price` 为准、库存预占与释放、Lua+条件更新防超卖、关单与管理端 `pay_deadline` 同源、乐观锁、快照固化、事务边界。
- [13] **支付链路缺幂等与安全约束**（重复拉起收银台、回调重复、支付密码可被日志泄露、客户端可自证支付成功）→ 新增 4.2.4：支付单幂等、金额权威、回调按 `paymentId+渠道流水号` 幂等、密码 KMS 解密不落日志、生物识别仅验签、退避轮询查单、关单与回调竞争裁决、失败限频。
- [14] **P0｜扫码登录直接下发 AccessToken 且接收 body `userId`**（二维码被截获即等同盗号）→ 9.2 改为「状态接口只回 60s 一次性 ticket → 新增 `POST /api/v1/auth/login/qr-ticket/exchange` 换发」，确认接口身份取自 APP Token；9.2.4 补伪造/重放/限频/会话绑定约束。
- [15] **同一功能两条路径**：2.1.2 `/api/v1/auth/login/qr-confirm` 与 9.2 `/api/v1/scan-login/confirm` 重复 → 9.2 为准，2.1.2 标记旧路径废弃；同类问题「地址解析」（`/user/address/resolve` vs `/location/regeo`）→ 5.2 统一到 7.3 定位服务路径并标记旧路径废弃（v1.2 移除）。
- [16] **13.1 安全措施过于笼统，与前端 1.6.4 的可执行约定对不上** → 补 HMAC-SHA256 待签串定义、Keystore 存 secret、±5min 容差、nonce Redis 去重、`X-Idempotency-Key`、限流返回值，并新增 HTTP 状态码 ↔ 业务码映射表（401/403/429/500）。
- [17] **Token 方案与管理端未声明隔离**（同一 JWT 密钥会让管理端 Token 在买家端接口越权）→ 2.2.2 新增 `iss/aud`（`easy-app` vs `easy-admin`）、独立签名密钥对、并发刷新单飞、多端上限与 1002 互踢语义；补充与 `admin-backend.md` 2.1（7 天 Cookie Refresh、单端登录）的差异及四条隔离要求。
- [18] **功能点有「预售/拼团下单」但无接口**，属「功能点有、接口没有」的断层 → 4.1.2 补充复用 `POST /api/v1/order/create` + `activityId/activityType`，活动资格由 `core-promotion` 实时校验。
- [19] **P0｜完整性缺口：前端 1.6.2 的 P0/P1/P2 待契约项在后端无任何承接**（购物车、文件上传、评价、注册、售后、优惠券、闪屏/引导、埋点、协议、region、搜索纠错、cursor 分页等）→ 新增 16.4「待契约接口清单」，逐项列拟定路径、归属服务、阻塞模块与交付阶段，并给出双向对账与闭环流程。
- [20] **错误码表缺常用码且无使用约定** → 16.2 补 2004 验证码过期、2005 短信发送频繁、3003 商品已下架；新增使用约定：码空间买家端专属、前端已依赖的 8 个码语义不可变更、HTTP 映射见 13.1、未知码按 9999 兜底。
- [21] **分页契约不统一**：附近门店只返回 `{total,list}`、深翻页无策略 → 16.1 明确统一分页信封、`page` 从 1 起、`pageSize` 上限 100、`page>100` 不承诺且 cursor 列入待契约；7.1.3 补分页口径说明。
- [22] **性能与可用性补强**：缓存缺穿透/击穿/雪崩方案 → 新增 12.2.3（空值+布隆、互斥重建、过期抖动、延迟双删、大 Key/热 Key）；客服 WebSocket 缺鉴权、消息幂等与顺序、内容安全、背压限频 → 11.1.3 补充；`user.status` 无法表达账号注销与手机号唯一键释放 → 1.4.1 增加 `2-已注销` 语义。

### 遗留项（需其他文档同步，本轮无法在本文件闭环）

| 编号 | 遗留问题 | 需同步的文档与位置 |
|------|----------|--------------------|
| L1 | 订单状态机 `TRANSITIONS` 只覆盖 `UNPAID/PAID/SHIPPED/REFUNDING`，缺 `COMPLETED→REFUNDING`、`REFUNDED→CLOSED` 源状态条目，`CANCELLED/CLOSED` 未显式声明终态 | `admin-backend.md` 4.4.1（状态图 4.3 已含售后→关闭，代码未跟上） |
| L2 | 1.1.3 未声明订单/售后是「由订单事件同步的读模型」，易被理解为两端各存一份订单 | `admin-backend.md` 1.1.3，与本文 1.1 边界说明互为镜像 |
| L3 | 支付方式字典缺「4-余额」，管理端按支付方式筛选/导出会漏数据 | `admin-backend.md` 数据字典（16.x 系统管理） |
| L4 | 16.4 中 P0 项（购物车、文件上传、评价、注册）尚未定稿，前端对应模块仍为 `⏳待契约` | `android-frontend.md` 1.6.2，须在第 1-4 周完成契约评审 |

---

## 跨架构裁决回写记录

> 依据：`architecture/00-conflict-register.md` 第四节收敛裁决，将已裁决结论回写至本计划。

### v1.1 → v1.2

- [R1] **裁决C7｜支付超时关单** → 1.4.1 订单表：字段 `auto_close_at` 统一更名为 **`pay_deadline`**（付款截止时间为超时关单唯一事实来源），注释标注原名与裁决出处；索引 `idx_status(status, auto_close_at)` 同步改为 `idx_status(status, pay_deadline)`；原「待付款30分钟」表述改为**时长待产品联调前书面确认**（原 30 分钟为建议值）。iOS/PC 原写 15 分钟、本文原写 30 分钟的口径冲突即由本裁决消除。
- [R2] **裁决C7｜支付超时关单** → 4.1.4「超时关单」「库存预占」两行：改为以订单表 `pay_deadline` 为唯一事实来源——延迟队列（RabbitMQ TTL + 死信 或 延迟插件）`deliveryTimestamp` 直取该字段到期触发，与管理端同一调度语义与同一关单服务；本端不自定义分钟数，「30分钟」标注为待产品联调前书面确认的参数；补充延迟消息丢失时每 5 分钟扫 `pay_deadline < now()` 兜底（对齐 admin-backend 4.4.4）；库存预占有效期同步与 `pay_deadline` 对齐。
- [R3] **裁决C11｜缓存 Key 三段式 → 四段式** → 1.5 缓存策略：按 `architecture/03-cache.md` §6.1/§6.3 统一表，将本文 12 条三段式 Key 全部改为 **ios-backend 四段式为唯一真值**的写法（如 `user:{userId}` → `user:profile:{userId}:v3`、`product:spu:{spuId}` → `product:detail:{spuId}:v2`、`address/user/favorite/points/browse` → `user:addresses/favorites/points/browse:{userId}:v1`、`push:device:` → `push:token:{deviceId}:v2`、`version:` → `version:latest:{platform}:v1`），并补充实例组归属列与两处 TTL 差异修正（商品详情 10 分钟 → 30 分钟+随机抖动、用户地址 30 分钟 → 10 分钟）；同时注明 **Redis Cluster 无逻辑 DB，Key 前缀仅用于 `CacheFacade` 客户端多集群路由**，未登记前缀禁止上线。
