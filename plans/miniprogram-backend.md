# 小程序客户端后端开发计划

> 项目：大型电商平台（淘宝级别）小程序客户端后端  
> 技术栈：Java Spring Boot / Go / Node.js + MySQL + Redis + Elasticsearch + RabbitMQ  
> 目标：支撑千万级 DAU 小程序电商场景，与浏览器端共享核心服务，扩展小程序特有能力

---

## 一、架构与基础设施

### 1.1 与浏览器端共享的服务

小程序后端并非独立系统，而是在现有电商平台核心服务之上构建的**渠道适配层**。以下服务与浏览器端完全共享：

| 共享服务 | 职责 | 技术选型 |
|---------|------|---------|
| 商品中心 | 商品信息、类目、品牌、SKU/SPU 管理 | Java Spring Boot + MySQL + ES |
| 订单中心 | 订单生命周期管理、状态机 | Java Spring Boot + MySQL + RabbitMQ |
| 支付中心 | 支付路由、对账、退款 | Java Spring Boot + MySQL |
| 会员中心 | 用户基础信息、等级、积分 | Java Spring Boot + MySQL + Redis |
| 营销中心 | 优惠券、满减、秒杀、拼团 | Java Spring Boot + Redis + RabbitMQ |
| 库存中心 | 库存扣减、预占、回补 | Go + Redis + MySQL |
| 搜索服务 | 商品搜索、筛选、排序 | Elasticsearch + Go |
| 推荐服务 | 个性化推荐、猜你喜欢 | Python + TensorFlow + Redis |
| 物流服务 | 物流轨迹、配送管理 | Java Spring Boot + MySQL |
| 评价服务 | 商品评价、晒单 | Java Spring Boot + MySQL + ES |
| 购物车服务 | 购物车项管理、失效标记、跨端同步 | Java Spring Boot + Redis + MySQL |
| 售后中心 | 售后单创建/审核/退款退货换货流转 | Java Spring Boot + MySQL + RabbitMQ |
| 优惠券服务 | 券模板、发放、核销、过期回收（属营销中心） | Java Spring Boot + Redis + MySQL |

**共享原则**：
- 核心业务逻辑（商品、订单、支付、库存）完全复用，不因渠道不同产生分叉
- 通过 `channel` 字段区分流量来源（`miniprogram_wechat` / `miniprogram_alipay` / `miniprogram_douyin` / `browser` / `app`）
- 共享服务对渠道无感知，仅通过统一网关接收标准化请求

> **命名对齐**：本表"商品中心/订单中心/支付中心/会员中心/营销中心/库存中心"分别对应 `admin-backend.md` 1.1.3 中的 `core-product` / `core-order` / `core-payment` / `core-user` / `core-promotion` / `core-inventory`，是同一批共享服务在不同文档中的称呼，实现时以统一的 `core-*` 服务名为准，避免重复建设两套"商品/订单"服务。支付渠道对账（与微信/支付宝/抖音）归支付中心，商家结算与财务对账归管理端 `admin-finance`，二者不重叠。

### 1.2 小程序特有服务

以下服务为小程序端独有或需要深度定制：

| 特有服务 | 职责 | 技术选型 |
|---------|------|---------|
| 小程序网关 | 渠道识别、限流、鉴权、路由（不做业务与协议适配） | Go + Redis |
| 渠道认证服务 | 微信/支付宝/抖音登录、手机号解密、UnionID 管理 | Node.js + Redis |
| 订阅消息推送服务 | 订阅消息、模板消息兼容、客服消息通道 | Node.js + RabbitMQ |
| 分享裂变服务 | 分享链接、海报生成、渠道追踪、拼团/砍价 | Go + Redis + MySQL |
| 小程序码服务 | 小程序码生成、二维码管理、scene 规则 | Go + Redis |
| 多平台适配层 | 微信/支付宝/抖音差异抹平、协议适配 | Java Spring Boot |
| 客服渠道接入 | 微信客服消息回调、WebSocket 接入（会话/工单逻辑复用共享客服中心） | Node.js + WebSocket |
| 站内消息服务 | 消息中心（系统/订单/优惠/互动）、未读数、已读回执 | Java Spring Boot + Redis |

### 1.3 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        客户端层                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ 微信小程序 │  │ 支付宝小程序│  │ 抖音小程序 │  │  H5/APP  │       │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘       │
└───────┼──────────────┼──────────────┼──────────────┼─────────────┘
        │              │              │              │
        ▼              ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      API 网关层（小程序网关）                      │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  渠道识别 → 限流 → 鉴权 → 路由（协议适配下沉至适配层）      │    │
│  └─────────────────────────────────────────────────────────┘    │
└───────────────────────────────┬─────────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
┌───────────────┐    ┌───────────────────┐    ┌───────────────────┐
│  小程序特有服务  │    │   共享核心服务      │    │   基础设施服务      │
│  - 渠道认证     │    │   - 商品中心       │    │   - 配置中心       │
│  - 订阅消息推送  │    │   - 订单中心       │    │   - 注册中心       │
│  - 分享裂变     │    │   - 支付中心       │    │   - 监控告警       │
│  - 小程序码     │    │   - 会员中心       │    │   - 链路追踪       │
│  - 多平台适配   │    │   - 营销中心       │    │   - 日志系统       │
│  - 客服渠道接入  │    │   - 库存中心       │    │                   │
│  - 站内消息     │    │   - 购物车/售后     │    │                   │
└───────────────┘    │   - 搜索/推荐      │    └───────────────────┘
                     │   - 物流服务       │
                     │   - 评价服务       │
                     └───────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
┌───────────────┐    ┌───────────────────┐    ┌───────────────────┐
│   数据存储层    │    │    缓存层          │    │    消息队列层      │
│  - MySQL      │    │   - Redis Cluster │    │   - RabbitMQ      │
│  - ES         │    │   - 本地缓存       │    │   - Kafka         │
│  - MongoDB    │    │   - CDN            │    │                   │
└───────────────┘    └───────────────────┘    └───────────────────┘
```

### 1.4 数据库设计

#### 1.4.1 小程序渠道配置表

```sql
CREATE TABLE `channel_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `channel_code` varchar(32) NOT NULL COMMENT '渠道编码：miniprogram_wechat/alipay/douyin',
  `channel_name` varchar(64) NOT NULL COMMENT '渠道名称',
  `app_id` varchar(64) NOT NULL COMMENT '小程序AppID',
  `app_secret` varchar(128) DEFAULT NULL COMMENT '小程序AppSecret（加密存储）',
  `platform_type` varchar(16) NOT NULL COMMENT '平台类型：wechat/alipay/douyin',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-启用',
  `config_json` text COMMENT '渠道配置JSON（含支付、分享等配置）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_code` (`channel_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序渠道配置表';
```

#### 1.4.2 小程序用户映射表

```sql
CREATE TABLE `miniprogram_user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '平台用户ID（关联会员中心）',
  `channel_code` varchar(32) NOT NULL COMMENT '渠道编码',
  `open_id` varchar(64) NOT NULL COMMENT '小程序OpenID',
  `union_id` varchar(64) DEFAULT NULL COMMENT '微信UnionID（冗余字段，用于快速查找，权威映射见 user_unionid_mapping）',
  `session_key` varchar(128) DEFAULT NULL COMMENT '微信SessionKey（加密存储）',
  `auth_type` varchar(16) NOT NULL DEFAULT 'wechat' COMMENT '授权类型：wechat/alipay/douyin',
  `auth_time` datetime DEFAULT NULL COMMENT '授权时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_openid` (`channel_code`, `open_id`),
  KEY `idx_union_id` (`union_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序用户映射表';
```

#### 1.4.3 小程序访问令牌表

```sql
CREATE TABLE `miniprogram_token` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '平台用户ID',
  `channel_code` varchar(32) NOT NULL COMMENT '渠道编码',
  `access_token` varchar(512) NOT NULL COMMENT '访问令牌（JWT，仅存摘要/指纹，原文下发客户端）',
  `refresh_token` varchar(512) DEFAULT NULL COMMENT '刷新令牌（随机串，服务端存储原文）',
  `token_version` int(11) NOT NULL DEFAULT '1' COMMENT '令牌版本号，用于主动失效（改密/解绑时 version+1）',
  `expires_at` datetime NOT NULL COMMENT '过期时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_channel` (`user_id`, `channel_code`),
  UNIQUE KEY `uk_refresh_token` (`refresh_token`),
  KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序访问令牌表';

-- 说明：Token 方案以本节为准（消除 2.1.3 与旧描述的歧义）
-- 1. access_token 为短时效 JWT（2小时），携带 userId / channelCode / tokenVersion；
-- 2. 鉴权时校验 JWT 签名 + Redis 中该用户 tokenVersion 是否一致（Redis key: mp:token:ver:{userId}:{channelCode}）；
-- 3. refresh_token 为随机串，仅服务端存储（MySQL + Redis 热数据），用于换取新 JWT；
-- 4. 本表不存储 session_key，session_key 仅存于 miniprogram_user（AES-256-GCM 加密，KMS 管密钥）。
```

### 1.5 缓存策略

| 缓存对象 | 缓存位置 | 过期策略 | 说明 |
|---------|---------|---------|------|
| 小程序 Session | Redis | 30天滑动过期 | key: `mp:session:{token}` |
| 微信 AccessToken | Redis | 微信有效期 7200 秒，缓存 7000 秒（提前 200 秒过期），刷新加分布式锁 | key: `mp:wx:accesstoken:{appId}`，同一 appId 全局仅一个持有者刷新，防止并发调用微信接口被封 |
| 小程序码 | Redis + CDN | 永久（业务控制） | key: `mp:qrcode:{scene}` |
| 商品详情 | Redis + 本地缓存 | 5分钟 + 1分钟 | 多级缓存 |
| 用户信息 | Redis | 30分钟 | key: `mp:user:{userId}` |
| 渠道配置 | 本地缓存 + Redis | 10分钟 | 配置变更主动刷新 |
| 分享链接映射 | Redis | 7天 | key: `mp:share:{shareCode}` |

> **接口防重放**：小程序网关对写接口统一校验 `timestamp + nonce + signature`（nonce 在 Redis 中去重，窗口 5 分钟），防止请求重放；时间戳偏差超过 5 分钟直接拒绝。

### 1.6 技术要点

1. **服务拆分原则**：共享服务保持稳定，小程序特有服务独立部署，通过内部 RPC（Dubbo/gRPC）调用共享服务
2. **网关层职责**：小程序网关是流量入口，负责渠道识别、限流熔断、鉴权、路由，不承载业务逻辑，也不做协议适配（协议适配在多平台适配层，数据聚合在 BFF/聚合层，见 10.1.2）
3. **多环境隔离**：开发/测试/预发/生产环境完全隔离，小程序 AppID 按环境配置
4. **灰度发布**：支持按渠道、按用户比例灰度发布
5. **监控告警**：接入 Prometheus + Grafana，关键指标（QPS、RT、错误率、支付成功率）实时告警

---

## 二、认证与授权

### 2.1 微信登录

#### 2.1.1 功能点

- 小程序端调用 `wx.login()` 获取临时登录凭证 `code`
- 后端通过 `code` 向微信服务器换取 `openid` 和 `session_key`
- 首次登录自动创建平台用户账号，建立 `openid` 与平台 `userId` 的映射
- 生成平台自有 Token（JWT），后续请求携带 Token 鉴权
- 支持 SessionKey 续期机制

#### 2.1.2 API 设计

**接口：微信登录**

```
POST /api/v1/miniprogram/auth/wechat/login
```

请求参数：
```json
{
  "code": "081H50000a1b2c3d4e5f6g",
  "channelCode": "miniprogram_wechat",
  "scene": "1001",
  "shareCode": "SH202610110001",
  "deviceId": "device-fingerprint-xxx"
}
```

> **契约说明（与前端对齐）**：
> 1. 微信 `getUserInfo` 返回 `encryptedData/iv` 的能力已于 2021 年废弃，登录请求**不再接收** `encryptedData/iv/rawData/signature`；头像昵称通过"头像昵称填写"能力（`open-type="chooseAvatar"` + `type="nickname"`）走资料更新接口。
> 2. 分享归因统一使用 `shareCode`（与 7.1 分享服务一致）。`shareUserId` 由服务端根据 shareCode 反查，**不接受客户端直接传入**，防止伪造分享人刷取分享奖励。（原前端 13.1 的 `inviter` 参数应改为 `shareCode`）
> 3. `scene` 为小程序码/分享场景值，用于渠道归因与风控。

响应结果：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenExpireTime": 1697000000,
    "isNewUser": true,
    "userInfo": {
      "userId": 100001,
      "nickName": "微信用户",
      "avatarUrl": "https://...",
      "gender": 1,
      "country": "中国",
      "province": "广东",
      "city": "深圳"
    },
    "needBindPhone": true
  }
}
```

**接口：刷新 Token**

```
POST /api/v1/miniprogram/auth/token/refresh
```

#### 2.1.3 技术要点

1. **Code 一次性使用**：微信 `code` 只能使用一次，5分钟内有效，后端需做幂等处理
2. **SessionKey 安全存储**：`session_key` 是解密用户敏感数据的关键，必须加密存储（AES-256），访问需审计日志
3. **Token 设计**：采用 1.4.3 统一方案——短时效 JWT（2小时）+ `tokenVersion` 主动失效（Redis 校验），refresh_token 随机串存服务端；禁止三套方案并存
4. **登录态续期**：Token 过期前 1 小时，前端可调用刷新接口获取新 Token；刷新失败（版本失效/过期）返回 401，前端走静默登录
5. **防刷限制**：同一 IP/设备登录频率限制，防止恶意注册；`deviceId`（设备指纹）与 IP、微信号共同作为风控特征，同一设备大量新注册账号触发风控拦截
6. **游客模式**：未登录可浏览商品/搜索/详情；下单、加购、收藏、领券需登录。游客态生成临时 `anonymousId`，登录后行为数据（足迹、购物车）按 2.3 合并策略归并

### 2.2 手机号绑定

#### 2.2.1 功能点

- 支持微信手机号快速验证（`getPhoneNumber` 接口）
- 支持短信验证码绑定
- 手机号绑定后可用于账号找回、订单通知等场景

#### 2.2.2 API 设计

**接口：微信手机号快速验证**

```
POST /api/v1/miniprogram/auth/phone/wechat/bind
```

请求参数：
```json
{
  "phoneCode": "081H50000a1b2c3d4e5f6g",
  "channelCode": "miniprogram_wechat"
}
```

> **契约说明（与前端对齐）**：`phoneCode` 为小程序端 `open-type="getPhoneNumber"` 回调中 `e.detail.code`（新能力），服务端调用微信 `phonenumber.getuserphonenumber` 接口换取手机号。**不再使用 `encryptedData/iv` 解密方式**（老能力已废弃），前端 3.2 中 `bindPhone({ encryptedData, iv, code })` 的入参应同步改为 `{ phoneCode, channelCode }`。

**接口：短信验证码绑定**

```
POST /api/v1/miniprogram/auth/phone/sms/bind
```

**接口：发送短信验证码**

```
POST /api/v1/miniprogram/auth/phone/sms/send
```

#### 2.2.3 数据模型

```sql
CREATE TABLE `user_phone_bind` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '平台用户ID',
  `channel_code` varchar(32) NOT NULL COMMENT '渠道编码',
  `phone_cipher` varchar(128) NOT NULL COMMENT '手机号密文（AES-256-GCM，随机IV，仅存储不建索引）',
  `phone_hash` char(64) NOT NULL COMMENT '手机号确定性哈希（HMAC-SHA256 + 盐），用于唯一性校验与等值查询',
  `bind_type` varchar(16) NOT NULL COMMENT '绑定类型：wechat/sms',
  `bind_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态：0-解绑 1-已绑定',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone_hash` (`phone_hash`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户手机号绑定表';

-- 设计说明：
-- 1. 加密列不可直接建唯一键/索引：AES-GCM 使用随机 IV，同一手机号多次加密得到不同密文，
--    原设计 UNIQUE(user_id, channel_code, phone) 在密文列上会失效（同手机号可重复绑定/绕过查重）。
-- 2. 唯一性与"一机一号"风控查重放在 phone_hash 上（确定性哈希，同手机号同哈希）；
--    phone_hash 唯一键同时保证一个手机号只对应一个 userId（多渠道账号合并的兜底约束）。
-- 3. 解密仅在必要的内部服务发生（订单发货、客服联系），密钥走 KMS，访问留审计日志。
```

#### 2.2.4 技术要点

1. **手机号加密存储**：使用 AES-256-GCM 加密手机号，密钥由 KMS 管理
2. **微信手机号验证**：`getPhoneNumber` 返回的 `code` 直接换取手机号，无需短信验证
3. **验证码安全**：短信验证码 5 分钟有效，错误 5 次锁定 30 分钟；发送频控（同手机号 1 分钟 1 条、单设备/单 IP 每小时上限），接入短信防刷风控
4. **一码一用**：微信 `phoneCode` 和短信验证码都是一次性使用
5. **换绑与解绑**：换绑/解绑需二次验证（已绑定手机号短信验证码），解绑后 `phone_hash` 唯一约束释放需延迟归档，防止并发重复绑定

### 2.3 多平台账户体系

#### 2.3.1 功能点

- 支持微信、支付宝、抖音等多平台小程序登录
- 同一用户在不同平台的账号可关联到同一平台用户
- 支持账号合并（如用户先微信登录，后支付宝登录，可合并账号）
- 支持游客模式浏览，登录后合并购物车、收藏等数据

#### 2.3.2 API 设计

**接口：账号关联**

```
POST /api/v1/miniprogram/auth/account/merge
```

**接口：获取用户绑定状态**

```
GET /api/v1/miniprogram/auth/account/bindStatus
```

#### 2.3.3 技术要点

1. **UnionID 关联**：同一微信开放平台下的多个小程序/公众号，UnionID 相同，可自动关联（仅限微信生态内部）
2. **跨平台打通唯一链路**：微信/支付宝/抖音之间**没有**跨平台统一标识（支付宝 uid 仅限同一主体应用、抖音无跨平台账号体系），跨平台账号合并的唯一可靠链路是**手机号绑定**：先在 A 平台绑定手机号，再到 B 平台登录并绑定同一手机号，系统自动归并到同一 `userId`
3. **账号合并策略**：主账号判定优先级为"已绑定手机号 > 最早注册的平台账号"；未绑定手机号的两个平台账号不允许自动合并，必须走手机号或人工客服验证，防止小号蹭合并窃取优惠券/积分
4. **数据合并**：账号合并时合并购物车、收藏、足迹；优惠券、积分按营销规则处理（已核销券不迁移、积分取上限合并），合并操作不可逆且需留审计记录
5. **安全校验**：账号合并需要二次验证（短信验证码或人脸识别）；合并接口限频，同一用户每日合并尝试不超过 3 次

### 2.4 UnionID 机制

#### 2.4.1 功能点

- 通过微信开放平台 UnionID 实现跨应用用户识别
- 支持同一用户在小程序、公众号、APP 间的身份打通
- UnionID 获取需在微信开放平台完成应用绑定

#### 2.4.2 数据模型

```sql
CREATE TABLE `user_unionid_mapping` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `union_id` varchar(64) NOT NULL COMMENT '微信UnionID',
  `user_id` bigint(20) NOT NULL COMMENT '平台用户ID',
  `app_id` varchar(64) NOT NULL COMMENT '获取UnionID的应用AppID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_union_id_app` (`union_id`, `app_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='UnionID用户映射表';
```

#### 2.4.3 技术要点

1. **UnionID 获取条件**：小程序必须绑定到微信开放平台账号下，否则 `code2Session` 不返回 UnionID（响应中无 unionid 字段）
2. **UnionID 稳定性**：同一开放平台下，UnionID 永久不变，是跨应用用户识别的最佳标识
3. **获取方式**：`code2Session`（推荐，静默）；`wx.getUserInfo` / 客服消息等场景也可获得，以 code2Session 为准
4. **缺失降级**：未绑定开放平台时 unionid 为空，系统降级为"openid + 手机号"识别；`user_unionid_mapping` 记录 UnionID 与来源应用（`app_id`）的多对一关系，用于审计与跨应用归因；`miniprogram_user.union_id` 为冗余快查字段，权威数据以本表为准
5. **隐私合规**：UnionID 属于用户敏感信息，需加密存储，访问需审计；涉及用户信息展示、评价等功能前需完成微信隐私协议（Privacy）配置与用户授权弹窗，遵守《个人信息保护法》最小必要原则

---

## 三、商品服务

### 3.1 商品列表

#### 3.1.1 功能点

- 支持首页商品推荐、分类商品列表、搜索结果列表
- 支持多种排序（综合、销量、价格、上新）
- 支持筛选（品牌、价格区间、属性）
- 支持分页加载（下拉刷新、上拉加载更多）
- 小程序端商品卡片适配（图片尺寸、信息密度）

#### 3.1.2 API 设计

**接口：首页商品推荐**

```
GET /api/v1/miniprogram/product/home
```

请求参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| channelCode | String | 是 | 渠道编码 |
| pageNum | Integer | 否 | 页码，默认1 |
| pageSize | Integer | 否 | 每页数量，默认20 |
| scene | String | 否 | 场景：index/new/hot/sale |

响应结果：
```json
{
  "code": 0,
  "data": {
    "bannerList": [
      {
        "id": 1,
        "imageUrl": "https://cdn.example.com/banner/1.jpg",
        "linkType": "product",
        "linkValue": "100001"
      }
    ],
    "categoryList": [],
    "productList": [
      {
        "spuId": 100001,
        "title": "Apple iPhone 15 Pro 256GB",
        "subtitle": "钛金属设计，A17 Pro芯片",
        "price": 8999.00,
        "originalPrice": 9999.00,
        "salesCount": 12580,
        "mainImage": "https://cdn.example.com/product/100001/thumb.jpg",
        "tags": ["新品", "包邮"],
        "promotionInfo": {
          "type": "flashSale",
          "label": "限时秒杀",
          "endTime": 1697000000
        }
      }
    ],
    "hasMore": true
  }
}
```

**接口：分类商品列表**

```
GET /api/v1/miniprogram/product/category/list
```

请求参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| categoryId | Long | 是 | 类目ID |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页数量 |
| sortField | String | 否 | 排序字段：comprehensive/sales/price/new |
| sortOrder | String | 否 | 排序方向：asc/desc |
| brandIds | String | 否 | 品牌ID列表，逗号分隔 |
| priceMin | BigDecimal | 否 | 最低价格 |
| priceMax | BigDecimal | 否 | 最高价格 |

**接口：商品搜索**

```
GET /api/v1/miniprogram/product/search
```

请求参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | String | 是 | 搜索关键词 |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页数量 |
| sortField | String | 否 | 排序字段 |
| categoryId | Long | 否 | 类目筛选 |

#### 3.1.3 数据模型

```sql
CREATE TABLE `miniprogram_product_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `spu_id` bigint(20) NOT NULL COMMENT 'SPU ID',
  `channel_code` varchar(32) NOT NULL COMMENT '渠道编码',
  `is_visible` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否可见',
  `sort_order` int(11) NOT NULL DEFAULT '0' COMMENT '排序权重',
  `custom_title` varchar(256) DEFAULT NULL COMMENT '渠道自定义标题',
  `custom_image` varchar(512) DEFAULT NULL COMMENT '渠道自定义图片',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_channel` (`spu_id`, `channel_code`),
  KEY `idx_channel_sort` (`channel_code`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序商品配置表';
```

#### 3.1.4 技术要点

1. **接口聚合**：首页接口聚合 Banner、分类、商品列表、推荐位等多个数据源，一次请求返回
2. **图片适配**：小程序端图片使用 CDN 动态裁剪，根据小程序卡片尺寸返回特定规格
3. **缓存策略**：商品列表页缓存 5 分钟，首页推荐缓存 1 分钟
4. **搜索优化**：接入 Elasticsearch，支持拼音搜索、错别字纠错、搜索建议

### 3.2 商品详情

#### 3.2.1 功能点

- 商品基本信息（标题、价格、库存、销量）
- 商品图片/视频轮播
- 商品详情富文本（图文详情）
- SKU 选择器（规格、价格、库存联动）
- 商品参数展示
- 评价摘要
- 推荐搭配
- 店铺信息

#### 3.2.2 API 设计

**接口：商品详情**

```
GET /api/v1/miniprogram/product/detail
```

请求参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| spuId | Long | 是 | SPU ID |
| skuId | Long | 否 | SKU ID |
| channelCode | String | 是 | 渠道编码 |
| shareUserId | Long | 否 | 分享者ID |

响应结果：
```json
{
  "code": 0,
  "data": {
    "spuId": 100001,
    "title": "Apple iPhone 15 Pro 256GB",
    "price": 8999.00,
    "originalPrice": 9999.00,
    "salesCount": 12580,
    "stockCount": 500,
    "mainImages": ["https://cdn.example.com/product/100001/1.jpg"],
    "skuList": [
      {
        "skuId": 200001,
        "specs": {"颜色": "原色钛金属", "容量": "256GB"},
        "price": 8999.00,
        "stockCount": 200
      }
    ],
    "detailHtml": "<div>...</div>",
    "shopInfo": {
      "shopId": 300001,
      "shopName": "Apple官方旗舰店"
    },
    "reviewSummary": {
      "totalCount": 5280,
      "goodRate": 98
    },
    "shareInfo": {
      "title": "Apple iPhone 15 Pro 256GB",
      "image": "https://cdn.example.com/product/100001/share.jpg",
      "path": "/pages/product/detail?spuId=100001&shareUserId=100001"
    }
  }
}
```

#### 3.2.3 技术要点

1. **详情页聚合**：商品详情接口聚合商品信息、SKU、评价摘要、推荐、店铺信息
2. **富文本优化**：商品详情富文本在服务端做图片懒加载标记
3. **价格实时性**：商品价格缓存 1 分钟，秒杀/促销价格实时计算
4. **分享追踪**：通过 `shareUserId` 和 `shareScene` 追踪分享来源

### 3.3 搜索与推荐

#### 3.3.1 功能点

- 搜索关键词建议（Search Suggestion）
- 热门搜索词
- 搜索历史
- 个性化推荐（猜你喜欢）
- 相关搜索

#### 3.3.2 API 设计

**接口：搜索建议**

```
GET /api/v1/miniprogram/product/search/suggestion
```

**接口：个性化推荐**

```
GET /api/v1/miniprogram/product/recommend
```

#### 3.3.3 技术要点

1. **搜索建议**：使用 Elasticsearch Completion Suggester，响应时间 < 50ms
2. **个性化推荐**：基于用户行为的协同过滤 + 深度学习模型
3. **搜索历史**：存储在 Redis，保留最近 10 条
4. **热门搜索**：基于实时搜索日志统计，每小时更新

### 3.4 评价与晒单

#### 3.4.1 功能点

- 商品评价列表（全部/有图/好评/中评/差评、按时间/热度排序）
- 评价标签聚合（与商品详情 `reviewSummary` 联动）
- 评价详情与晒单图片/视频
- 提交评价（文字、图片、匿名评价）
- 评价追评、商家回复展示

#### 3.4.2 API 设计

**接口：评价列表**

```
GET /api/v1/miniprogram/review/list
```

请求参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| spuId | Long | 是 | SPU ID |
| skuId | Long | 否 | 按 SKU 筛选 |
| type | String | 否 | all/image/good/mid/bad，默认 all |
| sortField | String | 否 | time/like |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页数量 |

**接口：提交评价**

```
POST /api/v1/miniprogram/review/submit
```

请求参数：
```json
{
  "orderId": 600001,
  "items": [{"skuId": 200001, "scores": {"desc": 5, "logistics": 5, "service": 5}}],
  "content": "商品很好，物流很快",
  "images": ["https://cdn.example.com/review/1.jpg"],
  "anonymous": true
}
```

#### 3.4.3 技术要点

1. **服务归属**：评价数据与写入复用共享评价服务（core-review），小程序侧仅做接口适配与图片 CDN 规范
2. **幂等与防刷**：同一订单同一 SKU 只允许评价一次（唯一约束 order_id + sku_id）；评价图片走统一文件服务并做内容安全审核（机审 + 人审）
3. **敏感词过滤**：评价内容接入敏感词库与文本审核，命中后转人工审核后才对外可见
4. **激励联动**：首次评价奖励积分（走 5.4 积分体系，bizId 幂等），带图评价额外奖励

### 3.5 限时秒杀

#### 3.5.1 功能点

- 秒杀场次列表与倒计时
- 秒杀商品详情（活动价、剩余库存、限购数）
- 秒杀抢购下单（独立库存通道）
- 用户参与资格查询（是否已购、是否达限购）

#### 3.5.2 API 设计

**接口：秒杀场次列表**

```
GET /api/v1/miniprogram/seckill/session/list
```

**接口：秒杀资格与库存**

```
GET /api/v1/miniprogram/seckill/preview?activityId=800001&skuId=200001
```

响应包含：`canBuy`、`boughtCount`、`limitCount`、`stockVisible`、`startTime/endTime`。

#### 3.5.3 技术要点

1. **归属**：秒杀活动配置由管理端 `admin-marketing` 维护，共享营销中心提供读接口与库存通道，小程序侧不自建秒杀库存
2. **库存展示**：商品详情/列表接口中 `stockCount` 为缓存值并做展示策略（超过阈值只显示"有货/无货"，不暴露真实余量，秒杀场景展示 `stockVisible` 由服务端按阈值裁剪），防止被脚本探测库存节奏
3. **热点防护**：秒杀接口独立限流（令牌桶 + 用户级限购校验前置），库存扣减走 Redis 预扣 + 异步落库，接口层面禁止查询型流量打穿到 DB
4. **下单联动**：秒杀下单走 4.1 提交流程，营销类型 `seckill`，价格以服务端活动价为准，客户端传入价格一律不信任

---

## 四、订单服务

### 4.1 下单流程

#### 4.1.1 功能点

- 购物车结算下单
- 立即购买下单
- 订单预览（价格计算、优惠券选择、地址选择）
- 订单提交（库存预占、价格锁定）
- 订单支付（调起微信支付）

#### 4.1.2 API 设计

**接口：订单预览**

```
POST /api/v1/miniprogram/order/preview
```

请求参数：
```json
{
  "channelCode": "miniprogram_wechat",
  "items": [{"skuId": 200001, "quantity": 1, "shopId": 300001}],
  "addressId": 400001,
  "couponId": 500001,
  "usePoints": 100
}
```

**接口：提交订单**

```
POST /api/v1/miniprogram/order/submit
```

请求参数：
```json
{
  "orderToken": "order_token_xxx",
  "channelCode": "miniprogram_wechat",
  "remark": "尽快发货"
}
```

#### 4.1.3 数据模型

```sql
CREATE TABLE `miniprogram_order_extra` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `order_id` bigint(20) NOT NULL COMMENT '订单ID',
  `channel_code` varchar(32) NOT NULL COMMENT '渠道编码',
  `share_user_id` bigint(20) DEFAULT NULL COMMENT '分享者用户ID',
  `share_scene` varchar(64) DEFAULT NULL COMMENT '分享场景',
  `group_id` bigint(20) DEFAULT NULL COMMENT '拼团ID',
  `bargain_id` bigint(20) DEFAULT NULL COMMENT '砍价ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序订单扩展表';
```

#### 4.1.4 技术要点

1. **订单预览与提交分离**：预览阶段返回 `orderToken` 并锁定价格和库存（15 分钟），提交阶段正式创建订单；预览响应需包含金额明细（商品总额、运费、优惠、积分抵扣、应付金额）、可用优惠券列表、默认地址，供结算页直接渲染
2. **库存预占**：使用 Redis 预占库存（带过期时间与回补任务），订单创建成功后落 MySQL，取消/超时自动回补
3. **价格锁定**：预览时生成 `orderToken`（Redis，15 分钟 TTL），提交时校验 Token 有效性与价格快照一致性，价格不一致返回"价格已变动，请重新结算"
4. **幂等处理**：`orderToken` 一次性消费（Redis `SET NX` + 消费标记），同一 Token 重复提交返回同一 `orderId`；同一用户同一商品 3 秒内重复提交直接拒绝，防止网络重试导致重复下单
5. **分布式事务**：订单创建涉及多个服务（订单、库存、营销、积分），使用 RabbitMQ 最终一致性 + 定时对账补偿；关键动作（扣库存、冻结券）必须可回补、可对账
6. **客户端金额不可信**：提交接口只接受 `orderToken`，不接受任何金额/优惠字段；库存与价格一律以服务端计算为准

### 4.2 订单管理

#### 4.2.1 功能点

- 订单列表（全部/待付款/待发货/待收货/待评价/已完成）
- 订单详情
- 取消订单
- 确认收货
- 删除订单
- 订单状态跟踪

#### 4.2.2 API 设计

**接口：订单列表**

```
GET /api/v1/miniprogram/order/list
```

请求参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | 订单状态：all/unpaid/unshipped/unreceived/unreviewed/completed |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页数量 |

**接口：订单详情**

```
GET /api/v1/miniprogram/order/detail?orderId=600001
```

**接口：取消订单**

```
POST /api/v1/miniprogram/order/cancel
```

请求参数：`orderId`、`reason`；仅 `unpaid` 状态可取消（已支付订单须走退款/售后）。

**接口：确认收货**

```
POST /api/v1/miniprogram/order/confirm
```

**接口：删除订单**

```
POST /api/v1/miniprogram/order/delete
```

> 仅 `completed`/`cancelled` 订单可删除（软删除 `deleted=1`），删除不影响售后与发票链路。

**订单状态映射（与管理端状态机对齐，见 `admin-backend.md` 4.3）**

| 小程序列表 Tab | 统一订单状态 | 说明 |
|---------------|------------|------|
| 待付款 | `unpaid` | 30 分钟未支付自动关闭 |
| 待发货 | `paid` | 支付成功待商家发货 |
| 待收货 | `shipped` | 已发货 |
| 待评价 | `completed`（未评价） | 确认收货后 |
| 已完成 | `completed`（已评价） | - |
| 已取消 | `cancelled` | 超时/主动取消 |
| 售后 | `after_sale` / `refunding` / `refunded` | 售后单状态另见 4.5 |

> 客户端 Tab 是统一状态机的**视图**，禁止在小程序侧自定义一套独立状态枚举（原 `unpaid/unshipped/unreceived/unreviewed/completed` 表述作废）。

#### 4.2.3 技术要点

1. **订单状态机**：订单状态流转使用状态机模式
2. **订单列表缓存**：订单列表缓存 1 分钟，状态变更时主动失效
3. **订单详情聚合**：订单详情接口聚合订单信息、商品信息、物流信息、售后信息

### 4.3 物流跟踪

#### 4.3.1 功能点

- 物流轨迹查询
- 物流状态订阅（微信/支付宝消息通知）
- 预计送达时间
- 物流地图展示

#### 4.3.2 API 设计

**接口：物流轨迹**

```
GET /api/v1/miniprogram/logistics/track?orderId=600001
```

响应结果：
```json
{
  "code": 0,
  "data": {
    "logisticsNo": "SF1234567890",
    "companyName": "顺丰速运",
    "status": "in_transit",
    "statusText": "运输中",
    "estimatedTime": "2024-10-12 18:00:00",
    "tracks": [
      {"time": "2024-10-11 10:00:00", "status": "已揽收", "location": "深圳市"}
    ]
  }
}
```

#### 4.3.3 技术要点

1. **物流数据聚合**：对接多家快递公司，统一物流数据格式
2. **物流状态订阅**：通过微信"物流助手"小程序消息 / 支付宝物流消息订阅能力推送（需在平台开通并完成服务商配置），未开通时降级为用户主动查询 + 到货订阅消息
3. **物流缓存**：物流轨迹缓存 30 分钟

### 4.4 购物车服务

#### 4.4.1 功能点

- 加入购物车（含 SKU、数量、来源活动）
- 购物车列表（含失效商品、库存预警标记）
- 修改数量、勾选状态、删除、批量操作
- 购物车汇总（选中商品总价、优惠预估、件数角标）
- 跨端同步（浏览器端 / 小程序 / APP 共享购物车）
- 游客购物车与登录购物车合并

#### 4.4.2 API 设计

**接口：加入购物车**

```
POST /api/v1/miniprogram/cart/add
```

请求参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| skuId | Long | 是 | SKU ID |
| quantity | Integer | 是 | 数量（上限按单品限购规则） |
| channelCode | String | 是 | 渠道编码 |
| sourceType | String | 否 | 来源：detail/cart/seckill/group/bargain |

**接口：购物车列表**

```
GET /api/v1/miniprogram/cart/list
```

**接口：更新购物车项**

```
POST /api/v1/miniprogram/cart/update
```

参数：`cartItemId`、`quantity`、`checked`。

**接口：删除购物车项 / 批量删除**

```
POST /api/v1/miniprogram/cart/delete
```

参数：`cartItemIds`（数组，批量）。

**接口：购物车数量角标**

```
GET /api/v1/miniprogram/cart/count
```

#### 4.4.3 数据模型

```sql
CREATE TABLE `cart_item` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID（游客态为匿名ID，合并后回填）',
  `anonymous_id` varchar(64) DEFAULT NULL COMMENT '游客匿名ID',
  `sku_id` bigint(20) NOT NULL,
  `spu_id` bigint(20) NOT NULL,
  `quantity` int(11) NOT NULL DEFAULT '1',
  `checked` tinyint(4) NOT NULL DEFAULT '1',
  `source_type` varchar(16) DEFAULT 'detail' COMMENT '来源：detail/seckill/group/bargain',
  `activity_id` bigint(20) DEFAULT NULL COMMENT '活动ID（秒杀/拼团/砍价）',
  `invalid` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否失效（下架/售罄）',
  `channel_code` varchar(32) NOT NULL,
  `added_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_sku` (`user_id`, `sku_id`),
  KEY `idx_user_time` (`user_id`, `added_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';
```

> 实现建议：购物车读多写少且跨端同步，热数据放 Redis Hash（`cart:{userId}`），MySQL 作持久化；单用户上限 200 条，超出提示清理。

#### 4.4.4 技术要点

1. **跨端一致**：购物车属共享服务（core-cart），浏览器端/小程序共用同一份数据，`channel_code` 仅记录最近一次操作来源
2. **游客合并**：游客以 `anonymous_id` 暂存，登录成功后（或账号合并时）触发合并任务：同 SKU 数量累加（不超过限购），冲突以服务端计算为准，合并操作幂等（`merge_flag`）
3. **库存校验**：列表接口异步批量校验库存与上架状态并标记 `invalid`；不单独暴露"库存查询"接口（防刷），前端加购前的库存预校验复用商品详情接口返回的 `stockCount`
4. **失效策略**：下架/售罄商品保留 7 天后自动清理，失效商品不计入总价
5. **结算联动**：结算页商品以购物车勾选项 + `orderToken` 快照为准，防止结算期间购物车被篡改导致前后端金额不一致

### 4.5 售后服务（客户端侧）

#### 4.5.1 功能点

- 申请售后（仅退款 / 退货退款 / 换货）
- 售后列表与进度跟踪
- 售后详情（含协商记录、寄回物流、退款进度）
- 撤销售后申请
- 售后寄回物流录入
- 售后结果通知（订阅消息 + 站内信）

#### 4.5.2 API 设计

**接口：申请售后**

```
POST /api/v1/miniprogram/after-sale/apply
```

请求参数：
```json
{
  "orderId": 600001,
  "orderItemId": 60000101,
  "type": "refund",
  "reason": "不想要了",
  "description": "描述",
  "images": ["https://..."],
  "refundAmount": 8199.00
}
```

**接口：售后列表**

```
GET /api/v1/miniprogram/after-sale/list
```

**接口：售后详情**

```
GET /api/v1/miniprogram/after-sale/detail?afterSaleNo=AS202610110001
```

**接口：撤销售后**

```
POST /api/v1/miniprogram/after-sale/cancel
```

**接口：填写寄回物流**

```
POST /api/v1/miniprogram/after-sale/logistics
```

#### 4.5.3 技术要点

1. **归属**：售后单据、审核流转复用共享售后中心（数据表见 `admin-backend.md` 1.2 `after_sale` 表），小程序侧只做申请入口、进度查询与物流回填
2. **状态联动**：售后状态（`appraising/returning/refunding/completed/rejected`）变更时，主订单进入 `after_sale` 视图（见 4.2.2 映射），退款成功回写订单为 `refunded`
3. **退款金额校验**：`refundAmount` 由服务端按订单实付金额与可退范围校验（含优惠分摊），客户端传入值仅作展示参考，超出可退范围直接拒绝
4. **幂等与并发**：同一 `orderItemId` 同时只允许一个进行中的售后单（唯一约束 + 状态机校验）；撤销后重新申请需风控校验次数
5. **通知触达**：审核通过/退款到账等关键节点，走订阅消息（6.2）+ 站内信（6.4），发送失败进入重试队列

---

## 五、会员服务

### 5.1 个人信息

#### 5.1.1 功能点

- 个人信息展示与编辑
- 头像上传（微信头像/自定义上传）
- 昵称修改
- 性别、生日设置
- 实名认证

#### 5.1.2 API 设计

**接口：获取个人信息**

```
GET /api/v1/miniprogram/user/profile
```

**接口：更新个人信息**

```
POST /api/v1/miniprogram/user/profile/update
```

#### 5.1.3 技术要点

1. **头像上传**：小程序端先调用上传接口获取临时凭证，再上传到 OSS/CDN
2. **信息缓存**：用户基本信息缓存 30 分钟，变更时主动失效
3. **隐私保护**：手机号等敏感信息脱敏展示

### 5.2 收货地址

#### 5.2.1 功能点

- 地址列表
- 新增/编辑/删除地址
- 设置默认地址
- 微信地址导入（`chooseAddress` 接口）
- 地址智能识别（粘贴文本自动解析）

#### 5.2.2 API 设计

**接口：地址列表**

```
GET /api/v1/miniprogram/user/address/list
```

**接口：新增地址**

```
POST /api/v1/miniprogram/user/address/add
```

**接口：微信地址导入**

```
POST /api/v1/miniprogram/user/address/wechat/import
```

#### 5.2.3 数据模型

```sql
CREATE TABLE `user_address` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `name` varchar(64) NOT NULL COMMENT '收货人姓名',
  `phone` varchar(128) NOT NULL COMMENT '手机号密文（AES-256-GCM，随机IV）',
  `phone_hash` char(64) NOT NULL COMMENT '手机号哈希（HMAC-SHA256），用于查询与去重',
  `province` varchar(32) NOT NULL COMMENT '省份',
  `city` varchar(32) NOT NULL COMMENT '城市',
  `district` varchar(32) NOT NULL COMMENT '区县',
  `detail` varchar(256) NOT NULL COMMENT '详细地址',
  `longitude` decimal(10,7) DEFAULT NULL COMMENT '经度',
  `latitude` decimal(10,7) DEFAULT NULL COMMENT '纬度',
  `is_default` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否默认',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收货地址表';
```

#### 5.2.4 技术要点

1. **微信地址导入**：通过 `wx.chooseAddress` 获取用户微信地址簿中的地址
2. **地址智能识别**：使用 NLP 技术解析用户粘贴的地址文本
3. **地址去重**：基于经纬度 + 详细地址的相似度去重

### 5.3 收藏与足迹

#### 5.3.1 功能点

- 商品收藏/取消收藏
- 收藏列表
- 浏览足迹
- 足迹清除

#### 5.3.2 API 设计

**接口：收藏商品**

```
POST /api/v1/miniprogram/user/favorite/add
```

**接口：收藏列表**

```
GET /api/v1/miniprogram/user/favorite/list
```

**接口：浏览足迹**

```
GET /api/v1/miniprogram/user/footprint/list
```

#### 5.3.3 数据模型

```sql
CREATE TABLE `user_favorite` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `spu_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_spu_channel` (`user_id`, `spu_id`, `channel_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏表';

CREATE TABLE `user_footprint` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `spu_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `view_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户浏览足迹表';
```

#### 5.3.4 技术要点

1. **收藏缓存**：用户收藏列表缓存 5 分钟，使用 Redis Set 结构存储
2. **足迹异步写入**：浏览足迹通过 RabbitMQ 异步写入
3. **足迹去重**：同一商品多次浏览只保留最新记录

### 5.4 积分体系

#### 5.4.1 功能点

- 积分获取（签到、购物、评价、分享）
- 积分使用（抵扣、兑换）
- 积分明细
- 积分商城

#### 5.4.2 API 设计

**接口：积分明细**

```
GET /api/v1/miniprogram/user/points/detail
```

**接口：每日签到**

```
POST /api/v1/miniprogram/user/points/sign
```

**接口：积分兑换（积分商城）**

```
POST /api/v1/miniprogram/user/points/exchange
```

请求参数：`goodsId`（兑换商品）、`quantity`；服务端校验积分余额与库存，幂等键 `bizId`。

**接口：积分兑换记录**

```
GET /api/v1/miniprogram/user/points/exchange/list
```

> 积分余额、签到配置、兑换商品由共享会员中心/营销中心维护，小程序侧不自建积分账户（防止与浏览器端积分双写）。

#### 5.4.3 数据模型

```sql
CREATE TABLE `user_points_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `points` int(11) NOT NULL COMMENT '变动积分',
  `balance` int(11) NOT NULL COMMENT '变动后余额',
  `type` varchar(32) NOT NULL COMMENT '类型：sign/order/review/share/exchange',
  `description` varchar(256) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户积分明细表';
```

#### 5.4.4 技术要点

1. **积分幂等**：积分变动需保证幂等，防止重复发放
2. **积分过期**：积分设置有效期（如1年），过期自动清零
3. **积分锁**：积分扣减使用 Redis 分布式锁，防止并发超扣
4. **积分账务**：`user_points_log` 记录每笔变动与变动后余额，与用户余额表做定期对账；所有发放/扣减以 `bizId`（订单号/签到日期/活动ID）幂等

### 5.5 优惠券（客户端侧）

#### 5.5.1 功能点

- 可领券列表（店铺券/商品券/平台券）
- 领券
- 我的优惠券（未使用/已使用/已过期）
- 结算页可用券试算（含最优组合推荐）
- 券到期提醒

#### 5.5.2 API 设计

**接口：可领取优惠券**

```
GET /api/v1/miniprogram/coupon/available?spuId=100001
```

**接口：领取优惠券**

```
POST /api/v1/miniprogram/coupon/receive
```

参数：`couponTemplateId`；幂等（同一用户同一模板重复领取返回已领取）。

**接口：我的优惠券**

```
GET /api/v1/miniprogram/coupon/my?status=unused&spuId=100001
```

**接口：结算可用券试算**

```
POST /api/v1/miniprogram/coupon/selectable
```

参数：`items`（skuId + 数量）、`channelCode`；返回每张券的可用性与不可用原因。

#### 5.5.3 技术要点

1. **归属**：券模板、发放、核销复用共享优惠券服务（`admin-backend.md` 7.2 定义模板管理），客户端仅提供领取与查询入口
2. **防超发**：领券走 Redis 原子扣减库存（Lua），落库异步补偿；单用户领取上限、同设备/同手机号领取次数风控
3. **试算一致性**：结算页券试算结果与订单预览（4.1.2）返回的 `availableCoupons` 必须同源计算（同一次价格引擎调用），避免"显示可用、提交不可用"
4. **过期治理**：到期前 3 天通过订阅消息/站内信提醒；过期券异步核销为 `expired`

---

## 六、消息服务

### 6.1 模板消息（历史兼容，逐步下线）

> **重要澄清**：微信小程序侧的"模板消息"能力已于 2020 年停止下发（模板消息仅服务号保留），小程序统一使用**订阅消息**（6.2）。本节仅作存量兼容说明：老版本小程序/存量授权可在灰度期继续调用，**新功能一律基于订阅消息设计**。11.1 平台差异表中"模板消息：支持（逐步淘汰）"同义，指存量兼容而非新增能力；支付宝、抖音无模板消息。

#### 6.1.1 功能点（存量）

- 订单状态通知（下单、发货、收货）——迁移至订阅消息
- 物流状态通知——迁移至订阅消息
- 促销活动通知——仅站内信 + 订阅消息（营销类消息受平台限制，见 6.4）
- 库存不足提醒——站内信

#### 6.1.2 API 设计（存量兼容，内部调用）

**接口：发送模板消息（内部调用，存量）**

```
POST /api/v1/miniprogram/message/template/send
```

请求参数：
```json
{
  "channelCode": "miniprogram_wechat",
  "openId": "oXXX",
  "templateId": "template_id_xxx",
  "page": "pages/order/detail?orderId=600001",
  "data": {
    "thing1": {"value": "iPhone 15 Pro"},
    "phrase2": {"value": "已发货"},
    "time3": {"value": "2024-10-11 10:00:00"}
  }
}
```

#### 6.1.3 技术要点

1. **模板消息限制**：微信小程序模板消息已停止下发，本接口仅服务存量调用方；新场景一律使用订阅消息（6.2），并保证同一业务事件只通过一个通道下发，避免重复打扰
2. **消息队列**：模板消息发送通过 RabbitMQ 异步处理，失败重试 3 次
3. **发送频率控制**：同一用户同一模板消息发送频率限制
4. **模板管理**：模板 ID 存储在配置中心，支持动态更新

### 6.2 订阅消息（微信）

#### 6.2.1 功能点

- 一次性订阅消息（用户授权一次，发送一条）
- 长期订阅消息（需微信认证服务号）
- 订阅消息模板管理
- 订阅消息发送

#### 6.2.2 API 设计

**接口：获取订阅消息模板列表**

```
GET /api/v1/miniprogram/message/subscribe/templates
```

**接口：请求订阅授权**

```
POST /api/v1/miniprogram/message/subscribe/request
```

**接口：发送订阅消息（内部调用）**

```
POST /api/v1/miniprogram/message/subscribe/send
```

#### 6.2.3 数据模型

```sql
CREATE TABLE `user_subscribe_auth` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `open_id` varchar(64) NOT NULL,
  `template_id` varchar(64) NOT NULL COMMENT '平台侧模板ID',
  `scene` varchar(32) NOT NULL COMMENT '业务场景：order_ship/order_pay/logistics/coupon_expire',
  `rest_count` int(11) NOT NULL DEFAULT '0' COMMENT '剩余可发送次数（一次性订阅=1，长期订阅=9999）',
  `expire_at` datetime DEFAULT NULL COMMENT '授权过期时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tpl_scene` (`user_id`, `template_id`, `scene`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订阅消息授权记录表';
```

#### 6.2.4 技术要点

1. **订阅授权**：小程序端调用 `wx.requestSubscribeMessage` 获取用户授权（用户点"允许"一次，服务端可发一条），支付宝/抖音同理但模板能力与审核规则不同（见 11.1）
2. **授权管理**：`user_subscribe_auth` 记录每个用户每个模板每个场景的**剩余发送次数**；发送成功后原子扣减（`rest_count = rest_count - 1 WHERE rest_count > 0`），扣减失败即放弃发送
3. **消息队列**：订阅消息发送通过 RabbitMQ 异步处理，失败重试 3 次；同一业务事件按 `bizId + templateId` 去重，防止重复下发
4. **发送时机**：在订单状态变更、物流状态变更、拼团成团、砍价成功、退款到账等关键节点触发发送；营销类推送需用户订阅对应场景模板，不得滥发
5. **降级**：无剩余授权次数时降级为站内信（6.4），不阻断主流程

### 6.3 客服消息（微信/支付宝/抖音官方客服通道）

> **边界说明**：本节只负责**平台官方客服通道**的接入（微信"客服消息"回调、48 小时会话窗口、支付宝/抖音客服消息），属"客服渠道接入"；会话分配、坐席管理、工单流转等业务逻辑复用共享客服中心（管理端 `admin-cs`），小程序侧第九章的 WebSocket 仅是自建在线客服的实时传输通道，两者的会话记录统一落库，避免两套客服数据割裂。

#### 6.3.1 功能点

- 接收平台官方客服通道的用户消息（文本/图片/小程序卡片）
- 48 小时会话窗口内的被动回复
- 自动回复与转人工（业务逻辑在共享客服中心）
- 消息记录统一存储

#### 6.3.2 API 设计

**接口：接收客服消息回调（微信回调）**

```
POST /api/v1/miniprogram/message/cs/callback
```

**接口：发送客服消息**

```
POST /api/v1/miniprogram/message/cs/send
```

#### 6.3.3 数据模型

```sql
CREATE TABLE `customer_service_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `msg_type` varchar(16) NOT NULL COMMENT '消息类型：text/image/link',
  `direction` varchar(8) NOT NULL COMMENT '方向：in/out',
  `content` text,
  `is_auto_reply` tinyint(4) NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服消息记录表';
```

#### 6.3.4 技术要点

1. **消息回调**：微信客服消息通过回调方式接收；**回调必须验签（消息签名校验）并校验来源 IP 白名单**，防止伪造回调灌入脏数据；微信回调需在 5 秒内返回成功，耗时处理转 MQ 异步
2. **自动回复**：基于关键词匹配的自动回复（逻辑在共享客服中心，本侧只做通道转发）
3. **消息存储**：客服消息存储 90 天，涉及用户隐私字段脱敏，导出走审计
4. **客服接入**：当用户发送"人工"或自动回复无法解决时，转接人工客服

### 6.4 站内消息中心

#### 6.4.1 功能点

- 消息分类：系统通知 / 订单消息 / 优惠消息 / 互动消息
- 消息列表、详情、已读/未读、批量已读、删除
- 未读角标数（个人中心、消息 Tab）
- 消息点击跳转（订单详情、商品详情、活动页）

#### 6.4.2 API 设计

**接口：消息列表**

```
GET /api/v1/miniprogram/message/list?category=order&status=unread&pageNum=1&pageSize=20
```

**接口：消息已读**

```
POST /api/v1/miniprogram/message/read
```

参数：`messageIds`（数组）或 `all=true`。

**接口：未读数**

```
GET /api/v1/miniprogram/message/unread/count
```

响应：`total` 与各分类 `unreadCounts`（供前端 `MessageTabs` 角标）。

#### 6.4.3 数据模型

```sql
CREATE TABLE `user_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) DEFAULT NULL COMMENT '产生该消息的渠道（可空=平台级）',
  `category` varchar(16) NOT NULL COMMENT '分类：system/order/promotion/interaction',
  `title` varchar(128) NOT NULL,
  `content` varchar(512) DEFAULT NULL,
  `jump_type` varchar(32) DEFAULT NULL COMMENT '跳转类型：url/order/product/activity',
  `jump_value` varchar(256) DEFAULT NULL,
  `biz_type` varchar(32) DEFAULT NULL COMMENT '业务类型：order/aftersale/group/bargain/coupon',
  `biz_id` varchar(64) DEFAULT NULL COMMENT '业务ID（幂等去重键组成部分）',
  `read_flag` tinyint(4) NOT NULL DEFAULT '0',
  `read_time` datetime DEFAULT NULL,
  `expire_at` datetime DEFAULT NULL COMMENT '消息过期时间（过期异步清理）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_biz` (`user_id`, `biz_type`, `biz_id`),
  KEY `idx_user_read` (`user_id`, `read_flag`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户站内消息表';
```

> 千万级用户下消息表按 `user_id` 分库分表（与订单同规则），未读数用 Redis Hash（`msg:unread:{userId}`）维护，读库仅在角标丢失时重建。

#### 6.4.4 技术要点

1. **归属**：站内信是**跨渠道必达通道**（平台消息不依赖微信/支付宝能力），与订阅消息互补：能推送的走订阅消息，推送失败/无授权的兜底写站内信
2. **幂等**：`uk_user_biz` 保证同一业务事件（如同一订单发货）只产生一条消息
3. **写入异步**：业务事件 → MQ → 消息服务落库 + 更新未读数，主流程不阻塞
4. **营销合规**：优惠类消息仅向已订阅/已授权营销通知的用户下发，遵守各平台消息规范

---

## 七、分享服务

### 7.1 分享链接

#### 7.1.1 功能点

- 生成商品分享链接
- 生成活动分享链接
- 分享链接追踪（扫码/点击统计）
- 分享链接防刷

#### 7.1.2 API 设计

**接口：生成分享链接**

```
POST /api/v1/miniprogram/share/link/generate
```

请求参数：
```json
{
  "channelCode": "miniprogram_wechat",
  "shareType": "product",
  "shareId": "100001",
  "scene": "1001"
}
```

> `shareUserId` **由服务端从登录态获取**，不接受客户端传入（防伪造分享人刷奖励）。返回值中的 `shareCode` 即对外分享参数（小程序路径 query、海报二维码 scene 均使用它），命名与 2.1.2 登录归因参数保持一致。

**接口：解析分享链接**

```
GET /api/v1/miniprogram/share/link/parse?shareCode=SH202410110001
```

#### 7.1.3 数据模型

```sql
CREATE TABLE `share_link` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `share_code` varchar(32) NOT NULL,
  `share_type` varchar(16) NOT NULL,
  `share_id` varchar(64) NOT NULL,
  `share_user_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `click_count` int(11) NOT NULL DEFAULT '0',
  `convert_count` int(11) NOT NULL DEFAULT '0',
  `expire_time` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_share_code` (`share_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享链接表';
```

#### 7.1.4 技术要点

1. **分享码生成**：使用分布式 ID 生成器（雪花算法）+ 短码 Base62 编码，二维码 scene 需控制在 32 字符内（微信小程序码限制）
2. **链接追踪**：通过 `shareCode` 追踪分享来源，点击/曝光埋点走 Kafka 异步
3. **防刷机制**：同一用户多次点击同一分享链接只计一次（Redis Set 去重）；同一设备/同一 IP 聚集的点击不计入有效转化
4. **反薅羊毛**：分享归因（分享人）存在自邀识别（被分享人 == 分享人直接拒绝）、新注册账号批量领券/砍价的设备指纹聚集识别、奖励发放延迟 + 风控复核，命中风控的奖励冻结不发放
5. **链接有效期**：分享链接默认 7 天有效，过期返回默认落地页；分享人注销/风控封禁后其链接立即失效

### 7.2 分享海报

#### 7.2.1 功能点

- 生成商品分享海报
- 生成活动分享海报
- 海报模板管理
- 海报图片缓存

#### 7.2.2 API 设计

**接口：生成分享海报**

```
POST /api/v1/miniprogram/share/poster/generate
```

#### 7.2.3 技术要点

1. **海报生成**：使用 Java Canvas / Node.js Canvas 服务端生成海报图片
2. **海报缓存**：生成的海报图片缓存到 CDN
3. **模板管理**：海报模板存储在配置中心
4. **异步生成**：海报生成耗时较长，使用 RabbitMQ 异步处理

### 7.3 渠道追踪

#### 7.3.1 功能点

- 分享渠道统计（微信好友、朋友圈、微信群）
- 分享效果分析（点击、转化、成交）
- 渠道 ROI 计算
- 渠道排行榜

#### 7.3.2 API 设计

**接口：记录分享行为**

```
POST /api/v1/miniprogram/share/track
```

**接口：渠道统计**

```
GET /api/v1/miniprogram/share/statistics
```

#### 7.3.3 技术要点

1. **数据埋点**：分享行为通过埋点方式记录，使用 Kafka 实时处理
2. **渠道识别**：通过 `scene` 参数识别分享渠道
3. **效果分析**：基于分享链接的点击和转化数据，计算渠道 ROI

### 7.4 拼团

#### 7.4.1 功能点

- 发起拼团
- 参与拼团
- 拼团进度查询
- 拼团成功/失败处理
- 拼团分享

#### 7.4.2 API 设计

**接口：拼团活动列表**

```
GET /api/v1/miniprogram/group/activity/list
```

**接口：发起拼团**

```
POST /api/v1/miniprogram/group/create
```

请求参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| activityId | Long | 是 | 拼团活动ID（管理端 admin-marketing 配置） |
| skuId | Long | 是 | SKU ID |
| quantity | Integer | 否 | 数量，默认 1 |

响应：`groupId`、`groupNo`、`expireTime`、`payParams`（发起人需立即支付，走 8.1 预支付）。

**接口：参与拼团**

```
POST /api/v1/miniprogram/group/join
```

请求参数：`groupId`（或 `groupNo`）、`skuId`、`quantity`；校验：团未满、未过期、未达人数上限、非本人重复参团（`uk_group_user` 兜底）。

**接口：拼团详情**

```
GET /api/v1/miniprogram/group/detail?groupId=700001
```

响应：团状态、成员列表（头像昵称脱敏）、剩余时间、成团进度。

**接口：拼团退款结果查询**

```
GET /api/v1/miniprogram/group/refund/status?groupId=700001
```

> 与前端契约对齐：前端 13.3 `api.groupBuy.start({ activityId })` / `join({ groupId })` 与本组接口一一对应，需补充"拼团活动列表"接口给前端的 `GroupBuyList` 组件。

#### 7.4.3 数据模型

```sql
CREATE TABLE `group_buying` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `group_id` bigint(20) NOT NULL,
  `group_no` varchar(32) NOT NULL,
  `spu_id` bigint(20) NOT NULL,
  `sku_id` bigint(20) NOT NULL,
  `group_size` int(11) NOT NULL,
  `current_size` int(11) NOT NULL DEFAULT '1',
  `group_price` decimal(10,2) NOT NULL,
  `creator_id` bigint(20) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'pending',
  `expire_time` datetime NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_no` (`group_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拼团表';

CREATE TABLE `group_buying_user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `group_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `join_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `order_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_user` (`group_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拼团用户表';
```

#### 7.4.4 技术要点

1. **拼团状态机**：`pending → success / failed / closed`，只有 `pending` 可参团；状态变更使用乐观锁（`status = 'pending'` 条件更新）保证只流转一次
2. **成团并发**：最后两个名额同时支付的场景，参团人数更新用 **Redis Lua 原子脚本**（校验 `current_size < group_size` 后自增并写入成员），超员直接返回"团已满，请加入其他团"；人数落库用 MQ 异步对账，Redis 为唯一计数真源直到团关闭
3. **成团结算**：成团瞬间触发结算事件——按团价重算订单金额（多退少补不找零，参团价即最终价，避免事后加价）、扣减真实库存、团内订单进入 `paid` 待发货；结算事件按 `groupId` 幂等
4. **超时退款**：定时扫描任务（每 30 秒）扫描 `expire_time < now() && status = 'pending'` 的团，置为 `failed` 并对已支付成员**原路退款**（走 8.1 退款，幂等键 `groupId + userId`）；退款结果落 `group_buying_user.refund_status`，失败进入重试队列并对账
5. **库存与回补**：参团时预占库存（活动库存池独立于普通库存），团失败自动回补；活动库存池与秒杀库存池互不占用，防止同一库存被双重占用
6. **虚拟拼团**：支持商家配置"到期自动成团"（模拟成员补位），补位成员不发通知、不计佣金，补位动作记审计日志
7. **取消与售后**：成团后单个成员取消订单按普通订单取消/退款流程处理，不影响团内其他成员；团长注销/封禁不影响已成团的订单履约
8. **通知**：成团/失败通过订阅消息（6.2）+ 站内信（6.4）双通道触达，无授权次数时只发站内信

### 7.5 砍价

#### 7.5.1 功能点

- 发起砍价
- 帮砍
- 砍价进度查询
- 砍价成功/失败处理

#### 7.5.2 API 设计

**接口：砍价活动列表**

```
GET /api/v1/miniprogram/bargain/activity/list
```

**接口：发起砍价**

```
POST /api/v1/miniprogram/bargain/create
```

请求参数：`activityId`、`skuId`；响应：`bargainRecordId`、`currentPrice`、`targetPrice`、`expireTime`、`shareCode`。

**接口：帮砍**

```
POST /api/v1/miniprogram/bargain/help
```

请求参数：`bargainRecordId`（被帮砍的砍价单）、`shareCode`（帮砍人归因）；**需登录态**，响应：`cutAmount`（本次砍掉金额）、`currentPrice`、`finished`。

**接口：砍价进度**

```
GET /api/v1/miniprogram/bargain/progress?bargainRecordId=900001
```

> 与前端契约对齐：前端 13.4 `bargain.start({ activityId })` / `bargain.help({ bargainId })` 中的 `bargainId` 实为**用户砍价单 ID**（`bargain_record.id`），后端参数统一命名为 `bargainRecordId`，需同步前端命名；需补充"砍价活动列表"接口给 `BargainList` 组件。

#### 7.5.3 数据模型

```sql
-- 砍价活动配置表（管理端 admin-marketing 维护）
CREATE TABLE `bargain_activity` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `activity_id` bigint(20) NOT NULL COMMENT '活动ID',
  `spu_id` bigint(20) NOT NULL,
  `sku_id` bigint(20) NOT NULL,
  `original_price` decimal(10,2) NOT NULL COMMENT '原价',
  `target_price` decimal(10,2) NOT NULL COMMENT '砍到底价',
  `min_cut_amount` decimal(10,2) NOT NULL DEFAULT '0.01' COMMENT '单次最小砍价金额（保护下限）',
  `help_limit` int(11) NOT NULL DEFAULT '20' COMMENT '单个砍价单最多帮砍人数',
  `daily_create_limit` int(11) NOT NULL DEFAULT '1' COMMENT '单用户每日发起次数上限',
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `status` tinyint(4) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_id` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='砍价活动配置表';

-- 用户砍价单表（一次发起=一条记录，与活动配置分离）
CREATE TABLE `bargain_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `activity_id` bigint(20) NOT NULL,
  `spu_id` bigint(20) NOT NULL,
  `sku_id` bigint(20) NOT NULL,
  `creator_id` bigint(20) NOT NULL COMMENT '发起人',
  `original_price` decimal(10,2) NOT NULL,
  `target_price` decimal(10,2) NOT NULL,
  `current_price` decimal(10,2) NOT NULL COMMENT '当前价（随帮砍递减，Redis 为热源，本表异步对账）',
  `helped_count` int(11) NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'active' COMMENT 'active/success/failed/expired',
  `expire_time` datetime NOT NULL COMMENT '砍价单有效期',
  `order_id` bigint(20) DEFAULT NULL COMMENT '砍价成功后下单ID',
  `channel_code` varchar(32) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_creator_time` (`creator_id`, `created_at`),
  KEY `idx_activity` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户砍价单表';

-- 帮砍记录表（防刷核心：一个帮砍人对一个砍价单只能砍一次）
CREATE TABLE `bargain_help_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `bargain_record_id` bigint(20) NOT NULL,
  `help_user_id` bigint(20) NOT NULL COMMENT '帮砍人（可为新用户）',
  `help_device_id` varchar(64) DEFAULT NULL COMMENT '帮砍人设备指纹',
  `cut_amount` decimal(10,2) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `risk_score` decimal(5,2) DEFAULT NULL COMMENT '风控评分（黑产命中则不计入）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_record_helpuser` (`bargain_record_id`, `help_user_id`),
  KEY `idx_device` (`help_device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帮砍记录表';
```

#### 7.5.4 技术要点

1. **砍价金额算法**：服务端随机 + 递减策略（前几次砍大额、接近底价时砍小额），单次砍价金额区间与概率由活动配置下发；**砍价后价格不得低于 `target_price`，也不得低于成本价保护线**，服务端计算，客户端不参与
2. **防刷机制（核心）**：
   - 同一帮砍人对同一砍价单只能砍一次（`uk_record_helpuser` 唯一约束兜底）
   - 帮砍人需完成登录 + 手机号绑定（未绑手机号的帮砍不计入有效金额），提高小号成本
   - 设备指纹/IP 聚集识别：同一设备指纹、同 IP 段批量新注册账号帮砍，命中风控直接标记 `risk_score` 高危且不计入金额（记录保留用于审计）
   - 帮砍次数上限（`help_limit`）、发起次数限制（`daily_create_limit`）、同一用户 24 小时内帮砍总次数限制
   - 砍价成功后的下单支付环节做二次风控，异常订单不发货并冻结
3. **砍价缓存**：砍价进度（当前价、剩余帮砍数）存 Redis Hash（`bargain:{recordId}`），MySQL 异步对账落库；Redis 挂掉时以 DB 重建
4. **并发控制**：帮砍操作使用 Redis Lua 脚本原子执行（校验状态/次数/底价 → 扣减金额 → 记录帮砍人），避免分布式锁的性能瓶颈与死锁
5. **有效期与清理**：砍价单过期未达底价自动置 `expired`，过期后发起人仍可按原价或活动价下单（不做惩罚）；帮砍金额不退不补
6. **成单联动**：砍价成功后限定时间内（如 24 小时）下单有效，超时优惠失效；下单走 4.1 流程，价格以砍价单 `current_price` 服务端快照为准

### 7.6 小程序码与二维码

#### 7.6.1 功能点

- 生成小程序码（商品/活动/海报/客服，带 scene 参数）
- 生成普通二维码（H5 兜底、跨端扫码）
- 码的批量生成与缓存
- 码的访问归因统计

#### 7.6.2 API 设计

**接口：生成小程序码**

```
POST /api/v1/miniprogram/qrcode/miniprogram
```

请求参数：`page`、`scene`（如 `shareCode=SH001`）、`width`、`isHyaline`；响应：图片 URL（CDN）。

**接口：生成普通二维码（H5 兜底）**

```
POST /api/v1/miniprogram/qrcode/normal
```

#### 7.6.3 数据模型

```sql
CREATE TABLE `miniprogram_qrcode` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `scene` varchar(64) NOT NULL COMMENT '场景值（含 shareCode 等）',
  `page` varchar(128) NOT NULL,
  `qrcode_type` varchar(16) NOT NULL COMMENT 'wxacode/normal',
  `cdn_url` varchar(512) NOT NULL,
  `hit_count` int(11) NOT NULL DEFAULT '0',
  `expire_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scene_type` (`scene`, `qrcode_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序码表';
```

#### 7.6.4 技术要点

1. **平台限制**：`wxacode.getUnlimited` 的 scene 长度 ≤ 32 字符、单个小程序码生成量有上限（约 10 万/天），因此 scene 只放短码（如 `shareCode`），不放长参数；批量海报场景提前生成并缓存 CDN，命中缓存直接复用（`uk_scene_type`）
2. **兜底策略**：支付宝/抖音不支持小程序码（见 11.1），统一生成**普通二维码 + H5 中转页**，H5 中转页按 UA 引导跳转对应小程序或下载 App
3. **归因**：扫码进入后由 7.1 的 `shareCode` 解析完成归因；同一用户扫码去重计数
4. **安全**：生成接口限频（单用户每分钟上限），scene 由服务端拼装，客户端只能传业务 ID，防止注入任意 page 跳转

---

## 八、支付服务

### 8.1 多平台支付（微信/支付宝/抖音）

#### 8.1.1 功能点

- 小程序支付：微信 JSAPI / 支付宝当面付（小程序 tradePay）/ 抖音支付
- 预支付参数生成（服务端组装，客户端只负责调起）
- 支付回调处理（验签、幂等、金额校验）
- 退款（原路退回）
- 支付结果通知（订阅消息 + 站内信）
- 渠道对账（与微信/支付宝/抖音）

#### 8.1.2 API 设计

**接口：预支付（创建支付单）**

```
POST /api/v1/miniprogram/payment/prepay
```

请求参数：
```json
{
  "orderId": 600001,
  "channelCode": "miniprogram_wechat"
}
```

> **安全约束（重要）**：请求**只接受 `orderId`**，不接受 `totalAmount`（金额）与 `openId`（身份）——金额由服务端按订单实付金额计算，`openId/userId` 从登录态获取。客户端传入金额/身份的旧设计是越权与"改价支付"漏洞的直接来源，一律废弃。

响应结果（按渠道返回对应调起参数，前端 `platformPayment(payParams)` 依赖此结构）：
```json
{
  "code": 0,
  "data": {
    "paymentId": 900001,
    "channelCode": "miniprogram_wechat",
    "payType": "wechat_jsapi",
    "payParams": {
      "timeStamp": "1697000000",
      "nonceStr": "xxx",
      "package": "prepay_id=wx20261011xxx",
      "signType": "RSA",
      "paySign": "xxx"
    },
    "expireAt": 1697001800
  }
}
```

> 支付宝渠道 `payParams` 为 `{ tradeNO }`（调 `my.tradePay`），抖音渠道为 `{ orderStr }`（调 `tt.requestPayment`）。前端 8.3 的 `api.payment.prepay({ orderId })` 与本接口命名一致。

**接口：支付回调（微信回调）**

```
POST /api/v1/miniprogram/payment/callback/wechat
```

**接口：申请退款**

```
POST /api/v1/miniprogram/payment/refund
```

#### 8.1.3 数据模型

```sql
CREATE TABLE `payment_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `payment_id` bigint(20) NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `payment_type` varchar(16) NOT NULL,
  `total_amount` decimal(10,2) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'pending',
  `third_party_no` varchar(64) DEFAULT NULL,
  `paid_time` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单表';

CREATE TABLE `refund_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `refund_id` bigint(20) NOT NULL COMMENT '退款单号（幂等键）',
  `payment_id` bigint(20) NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `refund_amount` decimal(10,2) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'pending',
  `third_party_no` varchar(64) DEFAULT NULL COMMENT '渠道退款单号',
  `refund_time` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_id` (`refund_id`),
  KEY `idx_payment_id` (`payment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款订单表';

-- 支付回调幂等日志表：原始回调落库后再处理，天然去重
CREATE TABLE `payment_notify_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `channel_code` varchar(32) NOT NULL,
  `notify_type` varchar(32) NOT NULL COMMENT 'pay/refund',
  `third_party_no` varchar(64) NOT NULL COMMENT '渠道交易号（微信 transaction_id / 支付宝 trade_no / 抖音 order_id）',
  `out_trade_no` varchar(64) DEFAULT NULL COMMENT '商户订单号',
  `notify_body` text COMMENT '原始报文（验签后落库）',
  `verify_result` tinyint(4) NOT NULL DEFAULT '0' COMMENT '验签结果',
  `process_status` varchar(16) NOT NULL DEFAULT 'received' COMMENT 'received/processing/finished/failed',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_txn` (`channel_code`, `notify_type`, `third_party_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付回调幂等日志表';
```

#### 8.1.4 技术要点

1. **支付安全**：预支付签名使用 RSA2（微信/支付宝）/ 抖音对应签名算法，商户私钥由 KMS 管理并支持轮换；回调接口**必须验签**（微信回调需校验消息签名 + 可选 API 证书，支付宝/抖音校验平台证书），并对回调来源 IP 做白名单校验
2. **回调幂等（详细方案）**：
   - 第一步：原始回调先落 `payment_notify_log`，以 `(channel_code, notify_type, third_party_no)` 唯一键去重——重复通知直接插入失败，返回成功响应，不再重复处理
   - 第二步：状态机推进用 CAS 条件更新（`UPDATE payment_order SET status='paid' WHERE order_id=? AND status='pending'`），受影响行数为 0 说明已处理，直接返回成功
   - 第三步：**金额校验**——回调金额必须与订单应付金额一致，不一致告警并拒绝置成功，转人工对账
   - 处理中抛异常返回失败让渠道重试（微信会按 15s/15s/30s/3m/.../6h 重试），避免"收到但没处理成功"丢单
   - 兜底：定时对账任务拉取渠道账单，发现"已支付未回调"订单主动补单，发现"已回调未支付"异常告警
3. **支付超时**：支付订单 30 分钟未支付自动取消（订单状态机 `unpaid → cancelled`），取消时释放库存预占、回补券与积分；取消与回调并发时以状态机 CAS 结果为准，"取消成功但回调到达"场景触发退款而非直接拒绝
4. **退款原路返回**：退款原路返回用户支付账户；退款单以 `refund_id` 幂等，退款回调同样走 `payment_notify_log` 去重；退款失败自动重试并对账
5. **对账机制**：渠道对账（与微信/支付宝/抖音账单核对，归支付中心，每日 T+1 下载账单 + 差异处理）与财务对账（平台与商家结算，归管理端 `admin-finance`）分工明确，不重复建设
6. **支付结果通知**：支付成功/退款到账通过订阅消息（6.2）下发，客户端拉起支付后**不能以拉起成功作为支付成功依据**，必须以服务端订单状态为准（轮询订单状态或订阅回调）

### 8.2 支付安全

#### 8.2.1 功能点

- 余额支付与支付密码（仅余额支付场景）
- 支付风控（异常支付检测）
- 支付限额
- 支付日志审计

#### 8.2.2 技术要点

1. **支付密码边界**：微信/支付宝/抖音渠道支付由平台完成身份验证，平台侧**不存在自有支付密码**；支付密码仅用于"余额支付"场景（前端 8.3 的余额支付），密码加盐哈希存储，连续错误锁定 + 二次验证解锁
2. **余额支付**：余额支付走共享支付中心账户体系（充值-消费-退款回余额），余额变更需幂等（bizId）+ 账务流水；小程序侧仅提供接口适配
3. **支付风控**：基于用户行为、设备信息、支付金额、收款方异常等多维度风控，命中高危拦截或延迟发货；风控规则配置化，支持渠道维度开关
4. **支付限额**：单笔支付限额、日累计支付限额（区分渠道与用户等级），超限前端置灰并提示
5. **支付日志**：所有支付/退款操作记录审计日志，保留 5 年，含操作者、渠道、金额、IP、设备指纹

---

## 九、客服服务

> **服务边界**：客服的**会话调度、坐席管理、工单流转、智能客服**属共享客服中心（管理端 `admin-cs` 定义会话/工单/知识库模型），本章是小程序侧的**用户接入层**（WebSocket 实时通道、小程序客服入口、工单提交接口）。微信官方客服消息通道的接入见 6.3，两处会话统一落到共享客服中心，禁止小程序侧另建一套坐席与工单库。

### 9.1 在线客服

#### 9.1.1 功能点

- 用户发起客服会话
- 客服自动回复
- 人工客服接入
- 会话记录存储
- 客服评价

#### 9.1.2 API 设计

**接口：发起客服会话**

```
POST /api/v1/miniprogram/cs/session/create
```

**接口：发送消息**

```
POST /api/v1/miniprogram/cs/message/send
```

**接口：获取消息列表**

```
GET /api/v1/miniprogram/cs/message/list
```

#### 9.1.3 数据模型

```sql
CREATE TABLE `cs_session` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `session_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `cs_user_id` bigint(20) DEFAULT NULL,
  `channel_code` varchar(32) NOT NULL,
  `type` varchar(16) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'active',
  `rating` tinyint(4) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话表';
```

#### 9.1.4 技术要点

1. **WebSocket 通信**：客服消息使用 WebSocket 实时通信
2. **自动回复**：基于关键词匹配 + 意图识别的自动回复
3. **客服分配**：基于客服负载、技能组、优先级智能分配
4. **会话超时**：用户 30 分钟无消息自动关闭会话

### 9.2 工单系统

#### 9.2.1 功能点

- 用户提交工单
- 工单分类（售前/售后/投诉/建议）
- 工单处理流程
- 工单状态跟踪
- 工单评价

#### 9.2.2 API 设计

**接口：提交工单**

```
POST /api/v1/miniprogram/cs/ticket/create
```

**接口：工单详情**

```
GET /api/v1/miniprogram/cs/ticket/detail?ticketId=300001
```

#### 9.2.3 数据模型

```sql
CREATE TABLE `cs_ticket` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `ticket_id` bigint(20) NOT NULL,
  `ticket_no` varchar(32) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `channel_code` varchar(32) NOT NULL,
  `type` varchar(16) NOT NULL,
  `title` varchar(256) NOT NULL,
  `description` text,
  `images` text,
  `status` varchar(16) NOT NULL DEFAULT 'pending',
  `priority` varchar(8) NOT NULL DEFAULT 'normal',
  `reply_content` text,
  `reply_time` datetime DEFAULT NULL,
  `rating` tinyint(4) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单表';
```

#### 9.2.4 技术要点

1. **工单分类**：基于工单类型自动分类
2. **SLA 管理**：不同类型工单设置不同的 SLA
3. **工单模板**：常见问题的工单模板
4. **满意度评价**：工单解决后邀请用户评价

---

## 十、性能优化

### 10.1 接口聚合

#### 10.1.1 功能点

- 首页接口聚合（Banner + 分类 + 商品列表 + 推荐位）
- 商品详情接口聚合（商品信息 + SKU + 评价 + 推荐 + 店铺）
- 订单详情接口聚合（订单信息 + 商品 + 物流 + 售后）
- 个人中心接口聚合（用户信息 + 订单统计 + 优惠券 + 积分）

#### 10.1.2 技术要点

1. **BFF 层聚合**：聚合由**独立的 BFF/聚合服务**承担（部署在网关之后的业务聚合层），**不在网关内做聚合**——网关只做路由与鉴权（见 1.6），避免网关因业务编排变重而无法水平扩展
2. **并行调用**：聚合接口内部使用 CompletableFuture / goroutine 并行调用
3. **数据裁剪**：根据小程序端需要返回字段，减少数据传输量
4. **降级策略**：某个服务不可用时，返回缓存数据或默认值

### 10.2 缓存策略

#### 10.2.1 多级缓存架构

```
小程序端 → CDN 缓存 → Redis 缓存 → 本地缓存 → 数据库/ES
```

#### 10.2.2 缓存策略

| 数据类型 | 缓存位置 | 过期时间 | 更新策略 |
|---------|---------|---------|---------|
| 首页推荐 | CDN + Redis | 1分钟 | 定时刷新 |
| 商品列表 | Redis | 5分钟 | 主动失效 |
| 商品详情 | Redis + 本地 | 5分钟 + 1分钟 | 主动失效 |
| 用户信息 | Redis | 30分钟 | 主动失效 |
| 订单列表 | Redis | 1分钟 | 主动失效 |
| 搜索结果 | Redis | 3分钟 | 定时刷新 |
| 配置信息 | 本地 + Redis | 10分钟 | 配置变更推送 |

> 本表与 1.5 缓存策略为同一套规范的两个视角（1.5 侧重小程序特有对象，本表侧重通用数据），实现时以统一的缓存配置中心为准，避免两处配置漂移。

#### 10.2.3 技术要点

1. **缓存穿透**：使用布隆过滤器防止缓存穿透
2. **缓存击穿**：热点数据使用分布式锁
3. **缓存雪崩**：缓存过期时间加随机值
4. **缓存一致性**：数据变更时主动失效缓存

### 10.3 CDN 加速

#### 10.3.1 功能点

- 静态资源 CDN 加速（图片、JS、CSS）
- 动态接口 CDN 缓存（商品列表、搜索结果）
- 图片动态裁剪（根据小程序端尺寸返回最优图片）
- WebP 格式自动转换

#### 10.3.2 技术要点

1. **图片优化**：CDN 支持图片动态裁剪、格式转换（WebP/AVIF）、质量压缩
2. **缓存策略**：静态资源缓存 30 天，动态接口缓存 1-5 分钟
3. **HTTPS**：全站 HTTPS，使用 TLS 1.3
4. **HTTP/2**：启用 HTTP/2 多路复用

### 10.4 图片优化

#### 10.4.1 功能点

- 图片懒加载
- 图片渐进式加载
- 图片尺寸适配
- 图片格式优化（WebP/AVIF）

#### 10.4.2 技术要点

1. **图片裁剪**：CDN 支持 `?w=750&h=360&format=webp` 参数动态裁剪
2. **懒加载**：小程序端使用 `lazy-load` 属性
3. **渐进式加载**：先加载低质量图片占位，再加载高清图片
4. **图片压缩**：上传图片时自动压缩

### 10.5 其他优化

#### 10.5.1 功能点

- 接口 Gzip 压缩
- 数据库读写分离
- 数据库分库分表
- 异步处理（非核心逻辑异步化）
- 限流熔断

#### 10.5.2 技术要点

1. **Gzip 压缩**：接口响应 Gzip 压缩
2. **读写分离**：MySQL 主从复制
3. **分库分表**：订单表按用户 ID 分 16 库 64 表；**配套约束**：`miniprogram_order_extra`、`payment_order`、`refund_order`、`cart_item`、`user_message` 等扩展表必须与主表**同库同分片键**（user_id 或 order_id），或按 order_id/user_id 独立分片，禁止跨库 join（如订单扩展表按 order_id 查询前需先由订单表定位分片）
4. **异步处理**：日志记录、消息推送、数据统计等异步处理
5. **限流熔断**：使用 Sentinel / Hystrix 实现限流熔断

---

## 十一、多平台适配

### 11.1 平台差异对比

| 功能 | 微信小程序 | 支付宝小程序 | 抖音小程序 |
|------|-----------|-------------|-----------|
| 登录 | `wx.login` → code2Session | `my.login` → oauth2 token | `tt.login` → code2Session |
| 唯一标识 | openid + unionid（需绑开放平台） | alipay_user_id / user_id（无跨平台 unionid） | open_id + union_id（抖音系内） |
| 支付 | 微信支付 JSAPI（需 openid） | 支付宝 tradePay（需 tradeNO） | 抖音支付（需 open_id） |
| 分享 | `onShareAppMessage` | `onShareAppMessage`（能力受限，依赖版本） | `onShareAppMessage` |
| 订阅消息 | `wx.requestSubscribeMessage` | `my.requestSubscribeMessage`（需申请、模板审核严格，能力有限） | `tt.requestSubscribeMessage` |
| 模板消息 | 已停止下发（仅存量兼容，见 6.1） | 不支持 | 不支持 |
| 手机号验证 | `getPhoneNumber`（返回 phoneCode） | `my.getPhoneNo`（需企业认证） | `tt.getPhoneCode` |
| 地址导入 | `chooseAddress` | `chooseAddress` | 不支持（前端用表单填写） |
| 小程序码 | `wxacode.getUnlimited` | 不支持（用普通二维码 + H5 兜底） | 不支持（用普通二维码 + H5 兜底） |
| 客服通道 | 官方客服消息（48h 窗口） | 官方在线客服 | 官方客服（能力以平台文档为准） |

> **说明**：本表为**能力概览**，各平台 API 名称、开通条件、审核规则以官方文档为准并随版本变化，落地时以 11.4 配置驱动的 feature 开关控制降级，前端 `miniprogram-frontend.md` 1.4.1 的平台差异表应以本表为准同步修订（尤其"支付宝/抖音订阅消息：无"与"手机号验证 API 同名"两处与实际不符）。

### 11.2 多平台适配层设计

#### 11.2.1 架构设计

```
小程序端 → 多平台适配层（Adapter Layer） → 统一接口层（Unified API）
```

#### 11.2.2 适配器接口定义

```java
public interface MiniprogramAdapter {
    PlatformType getPlatformType();

    /** 平台登录：换取平台侧唯一标识（微信 openid/unionid、支付宝 alipay_user_id、抖音 open_id/union_id） */
    AuthResult login(String code, String channelCode);

    /** 手机号验证：入参为各平台回调 code（微信 phoneCode / 支付宝 authCode / 抖音 phoneCode），内部差异由实现抹平 */
    PhoneResult getPhoneNumber(String code, String channelCode);

    /** 预支付：入参只带 orderId 与登录态，金额/身份由服务端补齐；返回对应平台调起参数 */
    PaymentResult prepay(PaymentRequest request);

    /** 订阅消息下发（无授权时返回 NoAuth，由上层降级站内信） */
    MessageResult sendSubscribeMessage(MessageRequest request);

    /** 分享路径生成（统一放入 shareCode，不放长参数） */
    String generateSharePath(ShareRequest request);

    /** 小程序码生成；不支持的平台返回 null，上层走普通二维码 + H5 兜底（见 7.6） */
    byte[] generateMiniProgramCode(MiniProgramCodeRequest request);

    /** 平台配置能力开关查询（对应 11.4 features 配置） */
    boolean isFeatureSupported(FeatureType feature);
}
```

#### 11.2.3 微信适配器实现

```java
@Component
public class WechatAdapter implements MiniprogramAdapter {
    @Override
    public PlatformType getPlatformType() {
        return PlatformType.WECHAT;
    }

    @Override
    public AuthResult login(String code, String channelCode) {
        WechatSession session = wechatClient.code2Session(code);
        Long userId = userService.getOrCreateByOpenid(
            session.getOpenId(), session.getUnionId(), channelCode);
        String token = tokenService.generate(userId, channelCode);
        return AuthResult.builder().userId(userId).token(token).build();
    }
}
```

### 11.3 平台差异处理

#### 11.3.1 登录差异

| 平台 | 登录方式 | 平台侧标识 | 特殊处理 |
|------|---------|-----------|---------|
| 微信 | `wx.login` → code → code2Session | openid；绑定开放平台后返回 unionid | UnionID 仅打通微信系应用；未绑定时降级 openid + 手机号 |
| 支付宝 | `my.login` → authCode → 换 token | alipay_user_id（无跨平台 unionid） | 需企业认证才能获取手机号；uid 打通仅限同一主体应用 |
| 抖音 | `tt.login` → code → code2Session | open_id；返回 union_id（抖音系内） | union_id 仅打通抖音系应用，跨平台仍靠手机号 |

> **跨平台结论**：三平台之间不存在统一账号标识，跨平台账号合并**唯一可靠链路是手机号**（见 2.3.3），UnionID/union_id 只在各自生态内有效。

#### 11.3.2 支付差异

| 平台 | 支付方式 | 特殊处理 |
|------|---------|---------|
| 微信 | 微信支付（JSAPI） | 需要 openid |
| 支付宝 | 支付宝支付 | 需要 authCode |
| 抖音 | 抖音支付 | 需要 openid |

#### 11.3.3 消息差异

| 平台 | 消息类型 | 特殊处理 |
|------|---------|---------|
| 微信 | 订阅消息（模板消息已停止下发，仅存量兼容） | 一次性订阅"授权一次发一条"，长期订阅仅限特定行业；无授权时降级站内信 |
| 支付宝 | 订阅消息 / 生活号 | 模板需审核，能力与频次受限，营销类慎用 |
| 抖音 | 订阅消息 | 需用户主动订阅，模板需审核 |

> 三平台均无"无条件推送"能力，**站内信（6.4）是跨渠道必达兜底通道**，订阅消息是增强通道，二者配合使用。

### 11.4 配置管理

#### 11.4.1 平台配置

```json
{
  "miniprogram_wechat": {
    "appId": "wx1234567890",
    "platform": "wechat",
    "features": {
      "login": true, "payment": true, "subscribeMessage": true,
      "templateMessage": false, "share": true, "miniProgramCode": true,
      "chooseAddress": true, "customerService": true
    }
  },
  "miniprogram_alipay": {
    "appId": "2021001234567890",
    "platform": "alipay",
    "features": {
      "login": true, "payment": true, "subscribeMessage": true,
      "templateMessage": false, "share": true, "miniProgramCode": false,
      "chooseAddress": true, "customerService": true, "phoneVerify": true
    }
  },
  "miniprogram_douyin": {
    "appId": "tt1234567890",
    "platform": "douyin",
    "features": {
      "login": true, "payment": true, "subscribeMessage": true,
      "templateMessage": false, "share": true, "miniProgramCode": false,
      "chooseAddress": false, "customerService": false, "phoneVerify": true
    }
  }
}
```

#### 11.4.2 技术要点

1. **适配器模式**：使用适配器模式抹平平台差异
2. **配置驱动**：平台差异通过配置管理
3. **功能开关**：通过配置控制各平台功能开关
4. **自动识别**：小程序端自动识别平台

---

## 十二、部署与运维

### 12.1 部署架构

```
                    ┌─────────┐
                    │   CDN   │
                    └────┬────┘
                         │
                    ┌────┴────┐
                    │   WAF   │
                    └────┬────┘
                         │
                    ┌────┴────┐
                    │   SLB   │
                    └────┬────┘
                         │
              ┌──────────┴──────────┐
              │   API 网关集群       │
              │  (小程序网关)        │
              └──────────┬──────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
    ┌────┴────┐    ┌────┴────┐    ┌────┴────┐
    │ 微信认证 │    │ 消息推送 │    │ 分享裂变 │
    └────┬────┘    └────┬────┘    └────┬────┘
         │               │               │
         └───────────────┼───────────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
    ┌────┴────┐    ┌────┴────┐    ┌────┴────┐
    │  MySQL  │    │  Redis  │    │RabbitMQ │
    │ 主从集群 │    │ Cluster │    │ Cluster │
    └─────────┘    └─────────┘    └─────────┘
```

### 12.2 监控告警

#### 12.2.1 监控指标

| 指标类型 | 指标名称 | 告警阈值 |
|---------|---------|---------|
| 接口指标 | QPS 环比突变 | 较昨日同期 ±40%（突增防刷、突降防故障） |
| 接口指标 | RT（P99） | > 500ms |
| 接口指标 | 错误率 | > 1% |
| 业务指标 | 支付成功率 | < 95%（分渠道监控，单一渠道异常单独告警） |
| 业务指标 | 支付回调处理积压 | 待处理回调 > 100 或最老回调 > 5 分钟 |
| 业务指标 | 下单转化率 | 较 7 日均值下降 > 30%（绝对值阈值无意义） |
| 业务指标 | 订单状态滞后 | 已支付未发货超 24 小时订单数 > 阈值 |
| 系统指标 | CPU 使用率 | > 80% |
| 系统指标 | 内存使用率 | > 85% |
| 系统指标 | 磁盘使用率 | > 80% |
| 依赖风险 | 微信/支付宝/抖音接口失败率 | > 5%（平台侧故障快速发现，触发降级） |
| 依赖风险 | Redis/MQ 积压 | 队列堆积 > 阈值 |

#### 12.2.2 技术要点

1. **Prometheus + Grafana**：指标采集和可视化
2. **链路追踪**：接入 SkyWalking / Jaeger
3. **日志系统**：ELK（Elasticsearch + Logstash + Kibana）
4. **告警通知**：告警通过钉钉/企业微信/短信通知

### 12.3 容灾备份

#### 12.3.1 功能点

- 数据库主从切换
- 服务多机房部署
- 数据定期备份
- 故障自动转移

#### 12.3.2 技术要点

1. **多机房部署**：服务部署在多个机房
2. **数据库主从**：MySQL 主从复制，故障自动切换
3. **数据备份**：每日全量备份 + 实时增量备份，保留 30 天
4. **故障演练**：定期进行故障演练

---

## 十三、开发排期

### 13.1 阶段划分

| 阶段 | 时间 | 里程碑 |
|------|------|--------|
| 第一阶段：基础架构 | 第1-4周 | 架构与基础设施、认证与授权（含 UnionID/账号合并、Token 体系） |
| 第二阶段：核心功能 | 第5-10周 | 商品服务（含评价、秒杀）、购物车服务、订单服务（含售后）、支付服务（含回调幂等与对账） |
| 第三阶段：扩展功能 | 第11-16周 | 会员服务（含优惠券、积分商城）、消息服务（订阅消息+站内信）、分享服务（拼团/砍价/小程序码） |
| 第四阶段：优化与适配 | 第17-20周 | 性能优化、多平台适配（微信先上，支付宝/抖音按配置灰度） |
| 第五阶段：测试与上线 | 第21-24周 | 测试（含支付回调/拼团并发/砍价风控专项压测与攻防演练）、灰度发布、全量上线 |

> **范围说明**：第二、三阶段已覆盖本次评审补充的购物车、售后、优惠券、评价、秒杀、站内信、小程序码模块；若人力不变，可将"支付宝/抖音多平台适配"整体后移一个迭代（微信渠道先行），避免 24 周排期被撑爆。

### 13.2 人员配置

| 角色 | 人数 | 职责 |
|------|------|------|
| 架构师 | 1 | 架构设计、技术选型、技术评审 |
| 后端开发 | 6 | 服务开发、接口实现、单元测试 |
| 前端开发 | 2 | 小程序端开发、联调测试 |
| 测试工程师 | 2 | 功能测试、性能测试、自动化测试 |
| 运维工程师 | 1 | 部署、监控、告警 |
| 产品经理 | 1 | 需求管理、项目管理 |

---

## 十四、风险与应对

### 14.1 技术风险

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| 微信 API 变更 | 功能不可用 | 关注微信官方公告，及时适配；平台差异以 11.4 配置驱动，变更只改适配层 |
| 高并发性能瓶颈 | 服务不可用 | 提前压测，准备扩容方案 |
| 数据不一致 | 订单/支付异常 | 分布式事务 + 对账机制 |
| 安全漏洞 | 数据泄露 | 安全审计 + 渗透测试 |
| 平台审核不通过 | 功能延期 | 涉审功能（手机号、订阅消息、支付）提前申请资质，预留降级路径（站内信/短信） |
| 平台侧故障（登录/支付不可用） | 交易中断 | 多渠道降级开关（如微信故障引导其他渠道），健康检查 + 自动切换 |

### 14.2 业务风险

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| 需求变更 | 项目延期 | 敏捷开发，迭代交付 |
| 第三方服务不可用 | 功能受限 | 多服务商备份 + 降级方案 |
| 用户体验差 | 用户流失 | 用户反馈 + 持续优化 |
| 营销合规风险（诱导分享/砍价拉新） | 活动被微信封禁、小程序处罚 | 活动设计遵守平台规范（不得强制分享解锁、不得虚假砍价），上线前合规评审 |
| 薅羊毛/黑产刷单 | 营销资金损失 | 设备指纹 + 风控规则（见 7.1.4/7.5.4），奖励延迟发放 + 人工复核，单活动预算熔断 |
| 个人信息保护（PIPL） | 监管处罚、下架 | 隐私协议、最小必要采集、手机号/UnionID 加密存储、注销与数据删除通道 |

---

## 十五、总结

本开发计划覆盖了小程序客户端后端的全部核心模块，从架构设计到具体实现，从功能开发到性能优化，从单平台支持到多平台适配，为大型电商平台小程序后端开发提供了完整的指导方案。

**核心设计原则**：
1. **共享复用**：核心业务逻辑与浏览器端共享，避免重复建设
2. **渠道适配**：通过适配层抹平平台差异，统一接口
3. **高性能**：多级缓存 + 接口聚合 + 异步处理，支撑高并发
4. **高可用**：多机房部署 + 容灾备份 + 监控告警，保障服务稳定
5. **可扩展**：微服务架构 + 配置驱动，支持快速迭代

---

*文档版本：v1.1*
*最后更新：2026-10-11*

---

## 审查记录

### v1.0 → v1.1 修改记录

| 编号 | 维度 | 问题描述 | 修改位置与内容 |
|------|------|---------|---------------|
| 1 | 完整性 | 缺少购物车服务（前端有完整购物车模块，后端无接口与数据模型） | 新增 4.4 购物车服务：API（add/list/update/delete/count）、`cart_item` 表、游客购物车合并、跨端同步 |
| 2 | 完整性 | 缺少售后服务（前端 9.5 与 admin 售后表均有，小程序侧无接口） | 新增 4.5 售后服务：申请/列表/详情/撤销/寄回物流接口与幂等、退款金额校验要点 |
| 3 | 完整性 | 缺少优惠券客户端接口（列表/领取/试算） | 新增 5.5 优惠券（客户端侧）：可领券、领取、我的券、结算试算 + 防超发/一致性要点 |
| 4 | 完整性 | 缺少评价/晒单接口 | 新增 3.4 评价与晒单：列表/提交接口 + 敏感词过滤、积分激励联动 |
| 5 | 完整性 | 缺少秒杀活动接口（前端首页有 Seckill 模块） | 新增 3.5 限时秒杀：场次列表/资格库存接口 + 库存展示策略、热点防护 |
| 6 | 完整性 | 缺少站内消息中心（前端 11.1 消息中心无后端支撑） | 新增 6.4 站内消息中心：列表/已读/未读数接口、`user_message` 表、作为跨渠道必达兜底通道 |
| 7 | 完整性 | 小程序码服务无接口与数据模型 | 新增 7.6 小程序码与二维码：生成接口、`miniprogram_qrcode` 表、scene 32 字符与 10 万/天限制、H5 兜底 |
| 8 | 完整性 | 积分商城有功能点无接口 | 5.4 补充积分兑换/兑换记录接口与幂等、对账要点 |
| 9 | 合理性 | 客服双轨重复建设（6.3 客服消息、第九章客服服务、admin-cs 边界不清） | 6.3 改为"平台官方客服通道接入"并加边界说明；第九章开头新增服务边界声明（接入层 vs 共享客服中心）；1.2 服务名改为"客服渠道接入" |
| 10 | 一致性 | "微信认证服务"命名与实际三平台登录不符 | 1.2/1.3 改为"渠道认证服务"；1.3 架构图同步更新 |
| 11 | 一致性 | 网关"参数适配/不承载业务"与"BFF 聚合在网关"自相矛盾 | 1.3/1.6/10.1.2 统一：网关只做识别/限流/鉴权/路由，协议适配在适配层，聚合在独立 BFF 层 |
| 12 | 一致性 | 登录/手机号接口与前端契约不一致（encryptedData/iv 已废弃；bindPhone 参数混用） | 2.1.2 移除 encryptedData/iv/rawData/signature 并加契约说明；2.2.2 改为 `phoneCode` 并注明前端需同步修订 |
| 13 | 一致性 | 支付接口命名与参数不一致（后端 create vs 前端 prepay；客户端传金额/openId） | 8.1.2 改为 `/payment/prepay`，请求只收 orderId，补三平台 payParams 响应结构 |
| 14 | 一致性 | 分享归因参数命名不一致（shareUserId vs inviter）且可伪造 | 2.1.2/7.1.2 统一为 `shareCode` 归因，shareUserId 由服务端反查，禁止客户端传入 |
| 15 | 一致性 | 拼团/砍价接口无参数定义，与前端 activityId 模型不一致 | 7.4.2/7.5.2 补活动列表接口与参数/响应表，统一 `bargainRecordId` 命名并标注前端同步点 |
| 16 | 一致性 | 订单状态枚举与 admin-backend 状态机不一致 | 4.2.2 新增 Tab↔统一状态映射表，废弃自定义枚举表述 |
| 17 | 一致性 | 共享服务命名与 admin-backend core-* 不一致 | 1.1 新增命名对齐说明（core-product 等）与共享服务补充（购物车/售后/优惠券） |
| 18 | 一致性 | 模板消息已下线但 6.1 与 11.1 表述矛盾 | 6.1 改为"历史兼容，逐步下线"；11.1 表同步修正 |
| 19 | 一致性 | 平台差异表错误：支付宝/抖音订阅消息标注、手机号验证 API 同名、缺唯一标识行 | 11.1 表重写（含唯一标识行与开通条件），并要求前端 1.4.1 同步修订 |
| 20 | 可实施性 | UnionID 机制落地条件与降级缺失、跨平台打通链路不清 | 2.3.3/2.4.3 补充绑定条件、缺失降级、跨平台唯一可靠链路为手机号；11.3.1 补三平台标识对照 |
| 21 | 一致性 | miniprogram_user.union_id 与 user_unionid_mapping 职责冗余不清 | 1.4.2 字段注释标注冗余快查，2.4.3 明确权威表为 user_unionid_mapping |
| 22 | 一致性 | Token 方案三种混杂（JWT+Redis 双校验 vs MySQL token 表 vs Redis session） | 1.4.3 表注释 + 说明统一为 JWT(2h)+tokenVersion 主动失效+服务端 refresh_token；2.1.3 同步 |
| 23 | 可实施性 | 手机号密文列建唯一键/索引失效（随机 IV 密文不相等） | 2.2.3 `user_phone_bind` 拆 phone_cipher/phone_hash，唯一键建在 phone_hash；5.2.3 地址表同步 |
| 24 | 可实施性 | 微信 AccessToken 未处理并发刷新 | 1.5 补充分布式锁与提前过期说明 |
| 25 | 可实施性 | 小程序码 scene 32 字符与生成量限制未考虑 | 7.6.4 补限制说明与 CDN 缓存复用、普通二维码兜底 |
| 26 | 边界情况 | 支付回调幂等方案过于笼统 | 8.1.4 展开三步幂等（notify_log 唯一键去重 → CAS 状态机 → 金额校验）+ 失败重试语义 + 对账兜底；8.1.3 新增 `payment_notify_log` 表 |
| 27 | 边界情况 | 拼团成团并发/结算/超时退款/库存回补细节缺失 | 7.4.4 扩展为 8 条：Lua 原子计数、成团结算、扫描退款幂等、活动库存池隔离、虚拟成团审计、取消售后联动 |
| 28 | 边界情况 | 砍价防刷不足且活动表与砍价单混淆 | 7.5.3 拆为 activity/record/help_record 三表（含设备指纹、风控分）；7.5.4 扩展防刷细则与底价保护 |
| 29 | 边界情况 | orderToken 幂等未给方案、订单删除/取消接口缺失 | 4.1.4 补一次性消费幂等；4.2.2 补 cancel/delete 接口与可取消/可删除状态约束 |
| 30 | 边界情况 | 订阅授权次数无数据表支撑 | 6.2.3 新增 `user_subscribe_auth` 表与原子扣减、降级站内信 |
| 31 | 安全 | 回调验签与 IP 白名单缺失 | 8.1.4/8.2/6.3.4 补验签、IP 白名单、5 秒响应异步化 |
| 32 | 安全 | 分享防薅不足（自邀、小号聚集） | 7.1.4 补自邀拒绝、设备聚集识别、奖励延迟+冻结 |
| 33 | 安全 | 隐私合规（PIPL、微信隐私协议、注销）缺失 | 2.4.3/14.2 补隐私授权、加密存储、注销与数据删除通道 |
| 34 | 性能 | 秒杀场景库存泄露、热点防护缺失 | 3.5.3/3.2.3 补库存展示阈值裁剪与独立限流 |
| 35 | 性能 | 分库分表下扩展表分片键未说明（跨库 join 风险） | 10.5.2 补充扩展表与主表同库同分片键约束 |
| 36 | 性能 | 监控阈值不合理（QPS 绝对值告警、转化率绝对阈值） | 12.2.1 改为环比突变告警，新增回调积压、依赖失败率、订单滞后指标 |
| 37 | 一致性 | 支付中心职责与支付单表归属、渠道对账 vs 财务对账边界不清 | 1.1 命名对齐说明 + 8.1.4 第 5 条明确分工 |
| 38 | 可实施性 | 排期未覆盖新增模块（购物车/售后/优惠券/站内信/秒杀） | 13.1 阶段表补充模块并给出"多平台适配后移一个迭代"的范围取舍建议 |

> **遗留待办（建议同步到前端与管理端文档）**：
> 1. `miniprogram-frontend.md` 需同步修订：手机号绑定入参（encryptedData→phoneCode）、分享参数（inviter→shareCode）、模板消息章节（11.2）、平台差异表（1.4.1）。
> 2. `admin-backend.md` 需确认：拼团活动配置字段与 7.4 `activityId` 对齐、砍价活动配置（v1.1 新增 `bargain_activity`）由管理端营销服务承载、售后状态枚举与 4.5 对齐。
