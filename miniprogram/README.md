# 易购商城 · 买家小程序端

uni-app（Vue 3 + TypeScript + Vite）实现的买家购物小程序，覆盖 P0 核心购物闭环：
**浏览 → 加购 → 结算 → 下单 → 支付 → 订单管理**。

## 构建结果

| 命令 | 结果 |
|------|------|
| `pnpm type-check` | ✅ 0 错误 |
| `pnpm build:h5` | ✅ DONE Build complete（`dist/build/h5`） |
| `pnpm build:mp-weixin` | ✅ DONE Build complete（`dist/build/mp-weixin`，主包 141 KB / 限 2 MB） |

## 快速开始

```bash
pnpm install

# H5 开发（默认 mock，无需启动后端）
pnpm dev:h5

# 微信开发者工具：先构建，再导入 dist/build/mp-weixin
pnpm build:mp-weixin
# 或开发模式（监听编译到 dist/dev/mp-weixin）
pnpm dev:mp-weixin
```

### 对接真实后端

`.env.development`：

```ini
VITE_USE_MOCK=false
VITE_API_BASE=http://localhost:8080
```

启动 `server/` 下 gateway（:8080）与 core-* 各服务即可联调。mock 与真实后端响应结构
对齐（信封 `{code,msg,data,traceId}`、0 基订单状态、`pay_deadline` 等口径一致）。

## 目录结构

```
src/
├── App.vue                 # onLaunch：恢复登录态 + 购物车角标
├── main.ts                 # createSSRApp + Pinia
├── manifest.json           # uni-app 配置（mp-weixin appid 占位）
├── pages.json              # 页面注册 / 分包 / tabBar / easycom
├── api/
│   ├── client.ts           # 请求封装：信封解包 / Bearer / Idempotency-Key / 401→登录 / mock 分流
│   ├── types.ts            # 领域类型（对齐 server DTO + web-buyer domain.ts）+ 业务错误码
│   ├── mock.ts             # 内存 mock 后端（商品/SKU 笛卡尔/购物车合并/订单状态机/支付/优惠券）
│   ├── auth.ts product.ts search.ts cart.ts order.ts payment.ts address.ts coupon.ts
├── store/
│   ├── user.ts             # 登录态（本地持久化恢复）
│   ├── cart.ts             # 购物车角标
│   └── checkout.ts         # 结算草稿（lines/address/couponId/remark）
├── utils/
│   ├── format.ts           # 金额两位小数 / 分转换 / 时间格式化
│   ├── countdown.ts        # pay_deadline 倒计时（C7：不硬编码分钟数）
│   └── uuid.ts             # 幂等键生成
├── components/
│   ├── ec-price.vue        # 价格（后端下发为准）
│   ├── ec-stepper.vue      # 数量步进器（v-model + change）
│   ├── ec-empty.vue        # 空状态（icon/text/操作按钮）
│   └── ec-sku-matrix.vue   # 多规格 SKU 矩阵选择器（选中/禁用三态联动）
├── pages/                  # 主包（141 KB，< 2 MB）
│   ├── login/index.vue     # 登录/注册（验证码固定 123456）
│   ├── index/index.vue     # 首页（轮播/分类/推荐流）
│   ├── search/index.vue    # 搜索（热词/历史）
│   ├── search/result.vue   # 搜索结果（关键词+筛选+排序+游标分页）
│   ├── product/detail.vue  # 商品详情（图集/规格矩阵/加购/立即购买）
│   ├── cart/index.vue      # 购物车（有效/失效分区/全选/步进器/结算）
│   ├── checkout/index.vue  # 确认订单（地址/优惠券试算降级/备注/提交）
│   └── user/index.vue      # 我的（登录态/订单入口/地址管理）
└── subpkg/                 # 分包
    ├── order/
    │   ├── list.vue        # 订单列表（0 基 8 值状态页签 + 服务端 actions 驱动按钮）
    │   ├── detail.vue      # 订单详情（子单分组/金额明细/倒计时/timeline）
    │   ├── pay.vue         # 收银台（prepay → mock 付款 → 2s×5 轮询 → 结果页）
    │   └── result.vue      # 支付结果
    └── user/
        ├── address.vue     # 地址列表（select=1 结算选择模式/设默认/删除）
        └── address-edit.vue# 地址新增/编辑
```

## 关键口径（与 architecture 裁决一致）

- **订单状态 0 基 8 值**：0待付款 / 1待发货 / 2待收货 / 3已完成 / 4退款中 / 5已退款 / 6已取消 / 7已关闭
  （core-order README 白名单，列表页签与「我的」入口均按此传参）
- **支付超时不硬编码**：倒计时基准一律取服务端 `pay_deadline`（裁决 C7）
- **金额以后端为准**：本地仅做展示级预估；下单后跳支付页带 `orderId`，实付金额以
  `prepay` 响应为准，创建订单响应的 `payAmount` 为最终权威值
- **写接口幂等**：POST/PUT/DELETE 由 `client.ts` 统一注入 `Idempotency-Key`
- **按钮由服务端下发**：订单操作（pay/cancel/confirm/logistics）按 `actions` 数组驱动，
  前端不自行推断可执行操作
- **优惠券试算降级**：`/coupons/preview` 不可用时降级为「不阻断下单」，最终以创建订单
  计算为准；3002 等业务错误 toast 服务端 `msg`
- **API 路径与 web-buyer 一致**：`/api/v1/*`，路径以 server/core-* README 为真值来源

## Mock 演示路径（VITE_USE_MOCK=true）

1. 任意手机号 + 密码登录（或注册，验证码 `123456`）
2. 首页/搜索 → 商品详情 → 选规格加购 / 立即购买
3. 购物车 → 结算（可选优惠券「满 300 减 30」「无门槛减 5」）→ 提交订单
4. 收银台 → 确认支付（mock 付款）→ 轮询到 `paid` → 支付成功页
5. 订单列表按状态查看，待付款可取消/继续支付，待收货可确认收货
6. 我的 → 收货地址：新增/编辑/设默认/删除
