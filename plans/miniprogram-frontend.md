# 小程序客户端前端开发计划

> **文档版本**：v1.3（v1.1 审查修订 → v1.2 跨文件同步 → v1.3 实施手册修订；变更见文末《审查记录》《实施手册修订记录》）
> **容量口径**：**千万 DAU**（唯一事实来源：`plans/00-架构总纲.md`）

## 一、项目架构与工程化

### 1.1 技术选型（原生/uni-app/Taro）

| 方案 | 适用场景 | 优势 | 劣势 |
|------|----------|------|------|
| **微信小程序原生** | 仅微信平台、追求极致性能 | 包体积小、启动快、API 完整 | 多端需重写、维护成本高 |
| **uni-app (Vue)** | 多端覆盖、团队有 Vue 经验 | 一套代码多端运行、插件丰富 | 复杂场景性能略低、包体积偏大 |
| **Taro (React)** | 多端覆盖、团队有 React 经验 | React 生态、京东主导维护 | 包体积略大、部分原生能力受限 |
| **mpvue (Vue)** | 仅微信、Vue 2 语法 | 迁移成本低、文档完善 | 已停止维护、不推荐新项目 |

**推荐方案：uni-app (Vue 3 + Vite + Composition API)**
- 一套代码覆盖微信/支付宝/百度/抖音/H5/App
- Vue 3 组合式 API + `<script setup>` 语法，适合大型项目
- 插件市场成熟，支付、分享、登录等能力开箱即用
- 支持条件编译，处理平台差异优雅
- TypeScript 支持，类型安全

**开发规范约定**：
- 统一使用 Vue 3 `<script setup>` + Composition API，不再使用 Options API
- 所有组件、页面、composables 必须编写 TypeScript 类型声明
- 样式使用 SCSS + CSS 变量，遵循 BEM 命名规范

### 1.2 目录结构

```
miniprogram-ecommerce/
├── src/
│   ├── api/                    # 接口层（仅定义接口，不含业务逻辑）
│   │   ├── user.ts             # 用户相关接口
│   │   ├── product.ts          # 商品相关接口
│   │   ├── order.ts            # 订单相关接口
│   │   ├── cart.ts             # 购物车接口
│   │   ├── search.ts           # 搜索接口
│   │   ├── address.ts          # 地址接口
│   │   ├── coupon.ts           # 优惠券接口
│   │   ├── message.ts          # 消息接口
│   │   ├── share.ts            # 分享裂变接口
│   │   └── payment.ts          # 支付接口
│   ├── services/               # 业务服务层（封装复杂业务逻辑）
│   │   ├── auth.service.ts     # 认证服务
│   │   ├── order.service.ts    # 订单服务
│   │   └── share.service.ts    # 分享服务
│   ├── components/             # 公共组件
│   │   ├── common/             # 通用组件
│   │   │   ├── EcImage/        # 图片组件（懒加载、占位图、WebP）
│   │   │   ├── EcButton/       # 按钮组件
│   │   │   ├── EcPopup/        # 弹窗组件
│   │   │   ├── EcEmpty/        # 空状态
│   │   │   ├── EcLoading/      # 加载状态
│   │   │   ├── EcCountdown/    # 倒计时
│   │   │   ├── EcPrice/        # 价格展示
│   │   │   ├── EcTag/          # 标签
│   │   │   ├── EcRate/         # 评分
│   │   │   └── EcErrorBoundary/ # 全局错误兜底页（Vue errorHandler + App onError，小程序无 React 式边界）
│   │   ├── product/            # 商品组件
│   │   │   ├── ProductCard/    # 商品卡片
│   │   │   ├── ProductList/    # 商品列表
│   │   │   ├── SpecSelector/   # 规格选择器
│   │   │   ├── PriceDisplay/   # 商品卡片价格区（组合 EcPrice，含促销角标）
│   │   │   └── CommentList/    # 评价列表
│   │   ├── order/              # 订单组件
│   │   │   ├── OrderCard/      # 订单卡片
│   │   │   ├── OrderStatus/    # 订单状态
│   │   │   └── LogisticsInfo/  # 物流信息
│   │   ├── cart/               # 购物车组件
│   │   │   ├── CartBar/        # 购物车底部栏
│   │   │   ├── CartItem/       # 购物车项
│   │   │   └── QuantityInput/  # 数量输入
│   │   └── feedback/           # 反馈组件
│   │       ├── ErrorPage/      # 全局错误页（网络异常/服务器错误）
│   │       └── RetryModule/    # 重试模块
│   ├── composables/            # 组合式函数
│   │   ├── useUser.ts          # 用户状态管理
│   │   ├── useCart.ts          # 购物车逻辑
│   │   ├── useShare.ts         # 分享逻辑
│   │   ├── useLocation.ts      # 定位逻辑
│   │   ├── usePayment.ts       # 支付逻辑
│   │   ├── useRequest.ts       # 请求状态组合式封装（loading/error/重试，底层复用 utils/request）
│   │   └── usePagination.ts    # 分页加载
│   ├── config/                 # 配置文件
│   │   ├── index.ts            # 主配置
│   │   ├── platforms.ts        # 平台差异配置
│   │   └── constants.ts        # 常量定义
│   ├── pages/                  # 页面目录（主包仅保留核心页面）
│   │   ├── index/              # 首页（主包）
│   │   ├── login/              # 登录（主包）
│   │   ├── product/            # 商品列表/详情（分包 package-product，分享落地页可直达）
│   │   ├── cart/               # 购物车（主包）
│   │   ├── checkout/           # 结算页（主包）
│   │   ├── order/              # 订单列表+详情
│   │   ├── user/               # 个人中心
│   │   ├── address/            # 地址管理
│   │   ├── coupon/             # 优惠券
│   │   ├── search/             # 搜索
│   │   ├── message/            # 消息中心
│   │   ├── customer-service/    # 客服
│   │   └── share/              # 分享裂变（拼团/砍价/海报）
│   ├── stores/                 # 状态管理 (Pinia)
│   │   ├── user.ts             # 用户状态
│   │   ├── cart.ts             # 购物车状态
│   │   ├── common.ts           # 全局状态（定位、配置等）
│   │   ├── message.ts          # 消息状态
│   │   ├── order.ts            # 订单状态
│   │   └── product.ts          # 商品状态（浏览历史、收藏）
│   ├── styles/                 # 全局样式
│   │   ├── variables.scss      # 主题变量
│   │   ├── mixins.scss         # 混入
│   │   └── common.scss         # 通用样式
│   ├── utils/                  # 工具函数
│   │   ├── auth.ts             # 授权相关（Token 加密/刷新）
│   │   ├── request.ts          # 请求封装（拦截器、Token 注入）
│   │   ├── format.ts           # 格式化
│   │   ├── validate.ts         # 校验
│   │   ├── storage.ts          # 加密存储
│   │   ├── platform.ts         # 平台判断与适配
│   │   ├── security.ts         # 加密/解密/脱敏
│   │   └── analytics.ts        # 埋点上报
│   ├── plugins/                # 插件（Pinia 持久化、路由守卫等）
│   ├── interceptors/           # 全局拦截器注册（401 静默续期、错误码统一提示、埋点注入）
│   ├── static/                 # 静态资源（仅 tabbar 图标等必要资源）
│   │   ├── icons/
│   │   └── tabbar/
│   ├── App.vue                 # 应用入口
│   ├── main.ts                 # 主入口
│   ├── manifest.json           # 应用配置
│   ├── pages.json              # 页面配置（含分包）
│   └── env.d.ts                # TypeScript 类型声明
├── tests/                      # 测试目录
│   ├── unit/                   # 单元测试
│   ├── integration/            # 集成测试
│   └── e2e/                    # 端到端测试
├── .github/                    # CI/CD 配置
│   └── workflows/
│       ├── ci.yml              # 持续集成
│       └── cd.yml              # 持续部署
├── package.json
├── vite.config.ts
├── tsconfig.json
└── README.md
```

**分层职责与调用方向**（避免 `api/`、`services/`、`composables/`、`utils/request.ts` 职责重叠）：

```
页面 pages → composables（页面级状态编排） → services（业务流程编排）
                                          ↘ api/（纯接口定义，1 函数 = 1 接口）
                utils/request.ts（底层：拦截器、Token 注入、401 续期、重试、错误码转换）
                interceptors/（注册上述拦截器，不承载业务逻辑）
```

- `api/*.ts`：只做接口路径 + 参数/响应类型声明，禁止出现业务判断与 toast
- `services/*.ts`：编排多个 api（如下单 = 预览 + 提交 + 支付），供 composables 调用
- `composables/*.ts`：持有响应式状态（用户、购物车、分页），供页面直接使用
- `utils/request.ts`：唯一出口，所有 api 必须经它发起请求；`composables/useRequest.ts` 仅封装 loading/error/重试等状态，不重复实现拦截器

### 1.3 组件化设计原则

1. **原子设计方法论**：原子 → 分子 → 有机体 → 模板 → 页面
2. **单一职责**：每个组件只做一件事
3. **Props 向下，Events 向上**：严格的数据流向
4. **插槽机制**：提供灵活的扩展能力
5. **平台无关**：业务组件不依赖平台 API
6. **性能优先**：虚拟列表、图片懒加载、组件按需渲染

### 1.4 多平台适配

#### 1.4.1 平台差异对照表

| 功能 | 微信 | 支付宝 | 抖音 | 百度 |
|------|------|--------|------|------|
| 登录 | `wx.login` → code2Session | `my.login` → oauth2 token | `tt.login` → code2Session | `swan.login` → code2Session（以官方文档为准） |
| 唯一标识 | openid + unionid（需绑开放平台） | alipay_user_id / user_id（无跨平台 unionid） | open_id + union_id（抖音系内） | 百度 openid（以官方文档为准） |
| 支付 | 微信支付 JSAPI（需 openid） | 支付宝 tradePay（需 tradeNO） | 抖音支付（需 open_id） | `swan.requestPayment` |
| 分享 | `onShareAppMessage`（+ 朋友圈需 `onShareTimeline`） | `onShareAppMessage`（能力受限，依赖版本） | `onShareAppMessage` | `onShareAppMessage` |
| 订阅消息 | `wx.requestSubscribeMessage` | `my.requestSubscribeMessage`（需申请、模板审核严格，能力有限） | `tt.requestSubscribeMessage` | `swan.requestSubscribeMessage`（以官方文档为准） |
| 模板消息 | 已停止下发（仅存量兼容，见 11.2） | 不支持 | 不支持 | 不支持 |
| 手机号验证 | `getPhoneNumber`（返回 phoneCode） | `my.getPhoneNo`（需企业认证） | `tt.getPhoneCode` | 无（走短信验证码绑定） |
| 小程序码 | `wxacode.getUnlimited`（scene ≤ 32 字符） | 不支持（普通二维码 + H5 兜底） | 不支持（普通二维码 + H5 兜底） | 不支持（普通二维码 + H5 兜底） |
| 图片选择 | `chooseImage` | `chooseImage` | `chooseImage` | `chooseImage` |
| 授权设置查询 | `getSetting` | `getSetting` | `getSetting` | `getSetting` |
| 客服 | `button open-type="contact"` / `openCustomerServiceChat`（需开通微信客服） | `my.openChat` | 官方客服组件（以平台文档为准） | 无 |
| 收货地址 | `chooseAddress` | `chooseAddress` | 不支持（前端用表单填写） | 无 |

> 表格修正说明：本表已与后端计划 11.1 平台差异对比表对齐（登录换取方式、唯一标识、支付调起凭证、分享能力差异、订阅消息能力、模板消息存量口径、手机号验证 API 不同名、小程序码支持度）；支付宝/抖音均支持各自的订阅消息 API，此前标注"无"会导致支付宝/抖音渠道消息能力漏开发；支付宝无 `openCustomerServiceChat` 同名 API；百度端仅保留基础能力，各平台 API 名称与开通条件以官方文档为准，最终降级由后端渠道配置开关（后端 11.4）驱动。
> **iOS 虚拟支付限制**：微信/抖音小程序 iOS 端禁止引导第三方支付购买虚拟商品（实物电商不受影响，本项目为实物电商可正常收款，但若后续上架虚拟商品/充值类目，iOS 端需走 IAP 或隐藏入口）。条件编译：`// #ifdef MP-WEIXIN || MP-TOUTIAO` + `uni.getSystemInfoSync().platform === 'ios'`。

#### 1.4.2 适配策略

```javascript
// utils/platform.ts —— 平台常量用「编译期条件编译」定义，避免运行时分支与无用 API 打包
export const platform = {
  // #ifdef MP-WEIXIN
  code: 'mp-weixin', channelCode: 'miniprogram_wechat',
  // #endif
  // #ifdef MP-ALIPAY
  code: 'mp-alipay', channelCode: 'miniprogram_alipay',
  // #endif
  // #ifdef MP-TOUTIAO
  code: 'mp-toutiao', channelCode: 'miniprogram_douyin',
  // #endif
  // #ifdef MP-BAIDU
  code: 'mp-baidu', channelCode: 'miniprogram_baidu',
  // #endif
}

// 统一登录：uni.login 已抹平各端差异（微信/抖音返回 code，支付宝返回 authCode）
// 不再直接调用 my.login / tt.login / swan.login 全局对象——非对应平台下这些对象不存在
export function getPlatformCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: (res) => res.code ? resolve(res.code) : reject(new Error('未获取到登录凭证')),
      fail: (err) => reject(err) // 必须有 reject 兜底，否则平台异常时 Promise 永远 pending
    })
  })
}

// 统一支付：支付参数由后端 payment/prepay 返回后直接透传，勿在前端拼装/签名
export function platformPayment(payParams): Promise<void> {
  return new Promise((resolve, reject) => {
    uni.requestPayment({
      ...payParams, // 内含 provider/orderId 等平台差异字段，由后端多平台适配层抹平
      success: () => resolve(),
      // 用户主动取消不视为异常，交由上层轮询订单支付状态确认最终结果
      fail: (err) => reject(new Error(String(err?.errMsg || '').includes('cancel') ? 'PAY_CANCEL' : 'PAY_FAILED'))
    })
  })
}
```

---

## 二、启动与初始化

### 2.1 启动页（Splash）

**页面路径**：`pages/index/index`

**功能点**：
- 品牌 Logo 展示
- 加载进度条动画
- 初始化数据预加载
- 版本检查与强制更新
- 隐私协议弹窗（首次启动）

**关键组件**：
- `SplashScreen`：全屏启动页组件
- `PrivacyPopup`：隐私协议弹窗
- `VersionUpdateModal`：版本更新提示

**技术要点**：
```javascript
// App.vue 生命周期
export default {
  onLaunch(options) {
    // 1. 获取窗口/设备信息（getSystemInfoSync 已废弃，按需改用 getWindowInfo/getDeviceInfo）
    const windowInfo = uni.getWindowInfo()
    // 2. 初始化配置
    this.initConfig()
    // 3. 检查更新
    this.checkUpdate()
    // 4. 预加载关键数据（必须先判登录态，游客态跳过 user/cart 接口，见 14.2）
    this.preloadData()
    // 5. 处理启动参数（扫码/分享进入，解析 scene 与分享归因，见 13.1）
    this.handleLaunchOptions(options)
  },
  onShow() {
    // 小程序从后台进入前台
    this.refreshUserData()
  }
}
```

**隐私合规要点（上线前置条件，缺失会被驳回审核）**：
- `manifest.json` 中必须配置用户隐私保护指引，并开启 `__usePrivacyCheck__`；填写的隐私接口清单需覆盖实际调用（`getPhoneNumber`、`chooseAddress`、`getLocation`、`chooseImage` 等）
- 隐私协议弹窗同意前，不得发起任何涉及个人信息的请求；`getPhoneNumber`/`chooseAddress` 等敏感接口必须在用户同意后才可调用
- 获取定位需在 manifest 声明权限并说明用途（首页定位选城市场景建议优先使用 `uni.chooseCity` 或手动选择，避免强制定位引起用户反感）

**数据依赖**：
- 系统信息 API
- 配置接口（全局配置、开关）
- 更新接口

### 2.2 授权登录

**功能点**：
- 静默登录（code2session）
- 微信授权获取用户信息
- 手机号授权绑定
- 支付宝授权登录
- 抖音授权登录
- 登录态维护与刷新

**关键组件**：
- `AuthService`：统一授权服务
- `PhoneAuthModal`：手机号绑定弹窗
- `AuthButton`：平台授权按钮

**技术要点**：
```javascript
// composables/useUser.ts
export function useUser() {
  const isLoggedIn = ref(false)
  const userInfo = ref<UserInfo | null>(null)
  const token = ref('')

  // 静默登录：对应后端 POST /api/v1/miniprogram/auth/wechat/login
  // code 一次性使用（5 分钟有效），登录态未过期时禁止重复调用
  const silentLogin = async (scene?: string) => {
    const code = await getPlatformCode()
    const res = await api.user.silentLogin({
      code,
      channelCode: platform.channelCode,   // 后端必填，由请求拦截器统一注入（见 18.4）
      scene: scene || '',                  // 进入场景（分享/扫码 scene 值）
      shareCode: getPendingShareCode()     // 落地页解析出的分享码，登录时一并上报归因（后端 2.1.2：shareUserId 由服务端根据 shareCode 反查，不接受客户端直接传入，防伪造）
    })
    token.value = res.token
    userInfo.value = res.userInfo
    isLoggedIn.value = true
    setToken(res.token) // 统一走 utils/storage.ts 加密存储，禁止散落 setStorageSync 明文
    if (res.needBindPhone) uni.$emit('auth:need-bind-phone')
    return res
  }

  // 微信手机号快速验证：对应后端 POST /api/v1/miniprogram/auth/phone/wechat/bind
  // 注意：新版 getPhoneNumber 仅返回一次性 code，encryptedData/iv 解密方式已于 2021 年被微信废弃，不得再使用
  // 入参命名对齐后端 2.2.2：{ phoneCode, channelCode }（phoneCode = e.detail.code，服务端调 phonenumber.getuserphonenumber 换取手机号）
  const getPhoneNumber = (e) => {
    if (e.detail.errMsg !== 'getPhoneNumber:ok' || !e.detail.code) {
      return Promise.reject(new Error('用户拒绝授权'))
    }
    return api.user.bindPhone({ phoneCode: e.detail.code, channelCode: platform.channelCode })
  }

  return { isLoggedIn, userInfo, token, silentLogin, getPhoneNumber }
}
```

**要点说明**：
- 微信登录 `code` 与手机号 `phoneCode` 均为一次性凭证，多次并发请求不得复用；需要新 code 时重新调用 `getPlatformCode()` / 重新触发 `getPhoneNumber` 授权
- Token 有效期与刷新遵循后端约定（过期前 1 小时调用 `/auth/token/refresh`），刷新逻辑统一收敛在 `utils/request.ts` 的 401 拦截器中，业务代码不感知

**数据依赖**：
- 静默登录接口
- 手机号绑定接口
- 用户信息接口

### 2.3 获取用户信息

**功能点**：
- 获取微信头像昵称（新接口：头像昵称填写）
- 获取用户基本信息
- 获取用户积分/优惠券概览
- 获取用户未读消息数
- 获取用户购物车数量

**页面/组件**：
- `UserInfoCard`：用户信息卡片
- `UserStats`：用户数据统计（订单数、优惠券、积分）

**技术要点**：
```javascript
// 微信新头像昵称规范
const chooseAvatar = (e) => {
  const { avatarUrl } = e.detail
  updateAvatar(avatarUrl)
}

const chooseNickname = (e) => {
  const { value } = e.detail
  updateNickname(value)
}
```

**数据依赖**：
- 用户信息接口
- 用户统计接口
- 消息未读数接口
- 购物车数量接口

---

## 三、登录与账户

### 3.1 微信授权登录

**功能点**：
- 一键登录按钮（`open-type="getPhoneNumber"`）
- 登录引导页
- 登录流程状态管理
- 登录异常处理

**页面路径**：`pages/login/login`

**关键组件**：
- `LoginButton`：平台登录按钮
- `LoginGuide`：登录引导组件
- `LoginLoading`：登录加载中状态

**技术要点**：
```xml
<!-- 微信手机号快速验证 -->
<button open-type="getPhoneNumber" @getphonenumber="onGetPhoneNumber">
  微信一键登录
</button>

<!-- 头像昵称填写（微信新规范） -->
<button open-type="chooseAvatar" @chooseavatar="onChooseAvatar">
  选择头像
</button>
<input type="nickname" @blur="onNicknameBlur" placeholder="请输入昵称" />
```

**数据依赖**：
- 微信登录接口
- 手机号验证接口

### 3.2 手机号绑定

**功能点**：
- 手机号绑定流程
- 验证码发送与校验
- 第三方登录手机号绑定
- 手机号更换

**页面路径**：`pages/login/bind-phone`

**关键组件**：
- `PhoneInput`：手机号输入
- `VerifyCodeInput`：验证码输入
- `CountdownButton`：倒计时发送按钮

**技术要点**：
```javascript
const sendCode = async (phone) => {
  await api.sms.send({ phone, scene: 'bind' })
  countdown.value = 60
  // 启动倒计时
  if (!countdownTimer.value) {
    countdownTimer.value = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) {
        clearInterval(countdownTimer.value)
        countdownTimer.value = null
        countdown.value = 60
      }
    }, 1000)
  }
}

// 组件卸载时必须清理定时器，否则页面返回后定时器仍在跑（内存泄漏 + 倒计时错乱）
onUnmounted(() => {
  if (countdownTimer.value) clearInterval(countdownTimer.value)
})

// 短信验证码绑定：对应后端 POST /api/v1/miniprogram/auth/phone/sms/bind
// 注意：此处 code 为短信验证码（≠ 微信一键登录的 phoneCode，见 2.2），两者是不同接口、不同凭证
const bindPhone = async () => {
  const res = await api.user.bindPhone({
    phone: phone.value,
    code: code.value, // 短信验证码：5 分钟有效，错 5 次锁定 30 分钟（后端约束）
    channelCode: platform.channelCode
  })
  // 绑定成功，刷新用户信息
  await refreshUserInfo()
}
```

**数据依赖**：
- 短信验证码接口
- 手机号绑定接口

### 3.3 多平台账户体系

**功能点**：
- 微信/支付宝/抖音账户统一
- 手机号作为唯一标识
- 账户关联与合并
- 游客模式（未登录可浏览）

**架构设计**：
```
平台用户ID (openid) ←→ 平台账户表
                              ↓
                        手机号（唯一标识）
                              ↓
                        统一用户ID (user_id)
                              ↓
                    用户中心（积分/等级/权益）
```

**游客模式实现要点**：
- 未登录生成本地匿名 UUID（存 storage），随请求头 `X-Device-Id` 上报，用于游客态购物车/足迹/埋点归因
- 游客可浏览、可加购（本地或服务端匿名购物车）；点击收藏/结算/支付时触发静默登录或跳转登录页
- 登录成功后由服务端执行数据合并（购物车、收藏、优惠券，见后端 2.3.1），前端登录成功回调中刷新购物车/角标，不自行做本地合并

**数据依赖**：
- 平台账户接口
- 用户合并接口
- 统一用户信息接口

---

## 四、首页

### 4.1 首页整体架构

**页面路径**：`pages/index/index`

**功能点**：
- 顶部搜索栏（点击跳转搜索页）
- Banner 轮播（活动/促销）
- 分类导航（金刚区）
- 限时秒杀
- 商品推荐流（瀑布流）
- 个性化推荐

**关键组件**：
- `HomeHeader`：首页头部（定位+搜索）
- `Banner`：轮播图
- `CategoryNav`：分类导航
- `Seckill`：秒杀模块
- `ProductFeed`：商品推荐流
- `FloatWindow`：悬浮窗（客服/活动）

**技术要点**：
```xml
<template>
  <view class="home">
    <HomeHeader :location="location" />
    <Banner :list="bannerList" />
    <CategoryNav :list="categoryList" />
    <Seckill :seckillInfo="seckill" />
    <ProductFeed :list="recommendList" @loadMore="loadMore" />
  </view>
</template>
```

### 4.2 商品推荐

**功能点**：
- 个性化推荐算法
- 瀑布流展示
- 下拉刷新
- 上拉加载更多
- 商品卡片（图片、标题、价格、销量）

**关键组件**：
- `ProductCard`：商品卡片组件
- `ProductFeed`：商品流组件
- `WaterfallList`：瀑布流列表

**技术要点**：
```javascript
// 分页加载：后端契约统一分页参数为 pageNum/pageSize（见后端 3.1.2）
const loadMore = async () => {
  if (loading.value || noMore.value) return
  loading.value = true
  try {
    const res = await api.product.home({
      pageNum: page.value,
      pageSize: 20,
      scene: 'home'
    })
    recommendList.value.push(...res.productList)
    noMore.value = res.hasMore === false
    page.value++
  } finally {
    loading.value = false
  }
}

// 下拉刷新：onPullDownRefresh 是页面级生命周期，需在 pages.json 对首页开启
// { "path": "pages/index/index", "style": { "enablePullDownRefresh": true } }
const onPullDownRefresh = async () => {
  page.value = 1
  recommendList.value = []
  noMore.value = false
  // 首页 Banner/分类/推荐已由后端 GET /product/home 聚合返回（后端 3.1.4），一次请求即可
  await Promise.all([loadRecommend()])
  uni.stopPullDownRefresh()
}
```

**数据依赖**：
- 推荐接口（个性化）
- Banner 接口
- 分类导航接口

### 4.3 活动入口

**功能点**：
- 限时秒杀入口
- 拼团活动入口
- 砍价活动入口
- 优惠券领取入口
- 新人专区入口

**关键组件**：
- `ActivityGrid`：活动入口网格
- `SeckillEntry`：秒杀入口
- `CouponEntry`：优惠券入口

**落地页说明**：秒杀专区为独立页面（建议 `package-marketing` 分包），功能含场次列表、开抢倒计时、库存进度条、限购提示与抢购按钮状态机（未开始/抢购中/已抢光/已结束）；倒计时基准时间必须取服务端下发的 `serverTime`（本地时钟可被篡改），活动数据由共享"营销中心"提供，接口契约待后端补充（见 18.4）。

### 4.4 搜索入口

**功能点**：
- 搜索框（placeholder 热词轮播）
- 扫码搜索
- 拍照搜索

**关键组件**：
- `SearchBar`：搜索栏组件
- `HotWordCarousel`：热词轮播

### 4.5 分类导航

**功能点**：
- 金刚区（8-10 个分类入口）
- 自定义排序
- 运营配置

**关键组件**：
- `CategoryNav`：分类导航组件
- `CategoryItem`：分类项

**数据依赖**：
- 分类导航配置接口

---

## 五、商品浏览

### 5.1 商品列表页

**页面路径**：`pages/product/list`

**功能点**：
- 分类筛选（左侧分类 + 右侧商品）
- 价格排序
- 销量排序
- 综合排序
- 筛选器（价格区间、品牌、标签）
- 列表/网格视图切换

**关键组件**：
- `ProductList`：商品列表
- `FilterBar`：筛选栏
- `FilterPanel`：筛选面板
- `SortBar`：排序栏
- `CategorySidebar`：分类侧边栏

**技术要点**：
```javascript
// 字段命名与枚举对齐后端 GET /api/v1/miniprogram/product/category/list 契约
const filterParams = reactive({
  categoryId: '',
  brandIds: [],   // 后端为逗号分隔字符串，由 api 层序列化
  priceMin: null, // 后端为独立字段，而非 priceRange 数组
  priceMax: null,
  tags: [],
  sortField: 'comprehensive', // comprehensive/sales/price/new（对齐后端枚举，禁止自造值）
  sortOrder: 'desc',          // asc/desc
  pageNum: 1,
  pageSize: 20
})

const fetchProducts = async () => {
  const res = await api.product.categoryList(filterParams)
  productList.value = res.list
  total.value = res.total
}
```

**数据依赖**：
- 商品列表接口
- 品牌接口
- 筛选条件接口

### 5.2 商品详情页

**页面路径**：`pages/product/detail`

**功能点**：
- 商品图片轮播（支持视频）
- 商品标题与卖点
- 价格展示（原价、促销价、会员价）
- 规格选择（SKU）
- 促销信息（满减、优惠券、秒杀）
- 商品详情（图文详情）
- 用户评价
- 售后保障
- 店铺信息
- 底部操作栏（客服、购物车、立即购买）

**关键组件**：
- `ProductGallery`：商品图片/视频轮播
- `PriceSection`：价格区域
- `SpecSelector`：规格选择弹窗
- `PromotionInfo`：促销信息
- `ProductDetail`：图文详情
- `ReviewSection`：评价区域
- `ShopInfo`：店铺信息
- `BottomBar`：底部操作栏

**技术要点**：
```xml
<!-- 字段命名与后端 GET /api/v1/miniprogram/product/detail 响应保持一致（后端 3.2.2） -->
<template>
  <view class="product-detail">
    <ProductGallery :images="product.mainImages" :videos="product.videos" />
    <PriceSection :price="product.price" :original-price="product.originalPrice" />
    <SpecSelector v-model="selectedSku" :sku-list="product.skuList" />
    <PromotionInfo :promotions="product.promotions" />
    <ProductDetail :content="product.detailHtml" />
    <ReviewSection :summary="product.reviewSummary" @more="goReviewList" />
    <ShopInfo :shop="product.shopInfo" />
    <BottomBar @buy="onBuy" @addCart="onAddCart" @service="onService" />
  </view>
</template>
```

- 图文详情 `detailHtml` 为富文本：使用 `mp-html` 等解析组件渲染，服务端已做图片懒加载标记（后端 3.2.3），前端禁止 innerHTML 直插
- 评价区展示后端聚合的 `reviewSummary` 摘要，完整列表/发表评价见 9.6

**数据依赖**：
- 商品详情接口
- SKU 信息接口
- 促销信息接口
- 评价列表接口
- 店铺信息接口

### 5.3 图片浏览

**功能点**：
- 全屏预览
- 手势缩放
- 左右滑动切换
- 保存图片
- 图片预加载

**关键组件**：
- `ImageViewer`：图片浏览器

**技术要点**：
```javascript
const previewImage = (urls, current) => {
  // longPressActions 为微信特有参数，其他平台自动降级为无长按菜单
  // #ifdef MP-WEIXIN
  uni.previewImage({
    urls,
    current,
    longPressActions: {
      itemList: ['发送给朋友', '保存图片', '收藏'],
      success: (data) => {
        if (data.tapIndex === 1) saveImage(urls[current])
      }
    }
  })
  // #endif
  // #ifndef MP-WEIXIN
  uni.previewImage({ urls, current })
  // #endif
}

// 保存图片需处理相册授权：用户拒绝后必须引导去设置页开启，否则无法再次弹窗
const saveImage = async (url) => {
  try {
    await uni.saveImageToPhotosAlbum({ filePath: url })
  } catch (e) {
    const { authSetting } = await uni.getSetting()
    if (authSetting['scope.writePhotosAlbum'] === false) {
      uni.showModal({
        title: '需要相册权限',
        content: '请在设置中允许保存图片到相册',
        success: (res) => res.confirm && uni.openSetting()
      })
    }
  }
}
```

### 5.4 规格选择

**功能点**：
- SKU 选择（规格组合）
- 库存校验
- 价格联动
- 规格图片联动
- 数量选择

**关键组件**：
- `SpecSelector`：规格选择器
- `SkuSelector`：SKU 选择弹窗
- `QuantityInput`：数量输入

**技术要点**：
```javascript
const selectedSpecs = ref({})
const selectedSku = ref(null)

// 问题：Object.values(...).join('_') 依赖对象键序与规格值内容，
// 规格值含 '_' 或顺序变化时会错配 SKU。正确做法：按"规格值 ID 集合"与后端下发的 skuList 匹配
const onSpecSelect = (specId, valueId) => {
  selectedSpecs.value = { ...selectedSpecs.value, [specId]: valueId }
  const selectedValueIds = Object.values(selectedSpecs.value).filter(Boolean).sort()
  selectedSku.value = skuList.value.find(
    (sku) => sku.specValueIds.slice().sort().join(',') === selectedValueIds.join(',')
  ) || null

  // 联动价格/库存（价格与库存以后端下发为准，不自行计算）
  if (selectedSku.value) {
    currentPrice.value = selectedSku.value.price
    currentStock.value = selectedSku.value.stockCount
  }
}
```

- 需要后端 `skuList` 每项返回 `specValueIds`（规格值 ID 数组），前端不得依赖规格名称/顺序拼接
- 规格联动置灰（某规格组合无货/不存在时禁选）使用三态联动算法，基于 skuList 在前端计算可选性

**数据依赖**：
- SKU 列表接口
- 库存接口

### 5.5 收藏功能

**功能点**：
- 商品收藏/取消收藏
- 收藏列表管理
- 收藏提醒（降价通知）

**关键组件**：
- `FavoriteButton`：收藏按钮

**技术要点**：
```javascript
const toggleFavorite = async () => {
  if (isFavorited.value) {
    await api.favorite.delete({ productId: product.value.id })
    isFavorited.value = false
    uni.showToast({ title: '已取消收藏' })
  } else {
    await api.favorite.add({ productId: product.value.id })
    isFavorited.value = true
    uni.showToast({ title: '已收藏' })
  }
}
```

**数据依赖**：
- 收藏接口
- 收藏状态查询接口

### 5.6 分享功能

**功能点**：
- 分享给好友（小程序卡片）
- 分享到朋友圈（需手动）
- 生成分享海报
- 复制链接

**关键组件**：
- `ShareButton`：分享按钮
- `SharePoster`：分享海报生成
- `ShareModal`：分享弹窗

**技术要点**：
```javascript
// 分享给好友：卡片路径参数与后端契约一致（spuId + shareUserId，见后端 3.2.2 shareInfo.path，用于详情页分享场景追踪）
// 注意：卡片分享的 shareUserId 与"分享链接/海报"链路（13.1，统一走 shareCode）是两条链路；
//       落地页登录归因一律以 shareCode 为准，后端 2.1.2 不接受客户端直接上报 shareUserId
// 注意：onShareAppMessage 只能在页面中定义；组件内分享需用 <button open-type="share">
onShareAppMessage(options) {
  const shareUserId = userInfo.value?.id ?? '' // 未登录兜底，避免 path 中出现 'undefined'
  return {
    title: product.value.title,
    path: `/pages/product/detail?spuId=${product.value.spuId}&shareUserId=${shareUserId}`,
    imageUrl: product.value.shareInfo?.image || product.value.mainImages?.[0]
  }
}

// 分享到朋友圈（微信特有，需单独声明 onShareTimeline，path 自动附加 query）
onShareTimeline() {
  return { title: product.value.title, query: `spuId=${product.value.spuId}` }
}

// 生成分享海报：优先后端海报服务（POST /share/poster/generate，服务端渲染 + CDN 缓存）
// 本地 canvas 仅作离线降级；若降级必须使用 canvas 2d（旧版 createCanvasContext 已废弃、高分屏模糊）
const generatePoster = async () => {
  try {
    const { posterUrl } = await api.share.generatePoster({
      shareType: 'product', shareId: product.value.spuId
    })
    await savePosterImage(posterUrl)
  } catch (e) {
    await drawLocalPoster() // canvas 2d 降级绘制
  }
}
```

> 为什么不本地画：后端计划 7.2 已提供服务端海报生成（模板化、CDN 缓存、异步），本地 canvas 代码维护两套绘制逻辑且二维码需另行生成；统一优先后端接口，保证海报风格与小程序码合规（服务端使用 `getwxacodeunlimit`，scene 值 ≤ 32 字符，携带 shareCode 而非明文 userId）。

**数据依赖**：
- 分享接口
- 海报生成接口
- 二维码生成接口

---

## 六、搜索与筛选

### 6.1 搜索页

**页面路径**：`pages/search/search`

**功能点**：
- 搜索输入框
- 搜索历史（本地存储）
- 热门搜索词（服务端下发）
- 搜索联想（输入时实时推荐）
- 扫码搜索
- 拍照搜索

**关键组件**：
- `SearchInput`：搜索输入框
- `SearchHistory`：搜索历史
- `HotSearch`：热门搜索
- `SearchSuggest`：搜索联想
- `SearchEmpty`：无结果状态

**技术要点**：
```javascript
const searchHistory = ref([])
const hotWords = ref([])
const suggestions = ref([])

// 加载搜索历史和热词
onLoad(() => {
  searchHistory.value = uni.getStorageSync('searchHistory') || []
  api.search.hotWords().then(res => hotWords.value = res)
})

// 搜索联想：debounce + 请求序号，防止旧请求后返回覆盖新结果（竞态）
let suggestSeq = 0
const onInput = debounce(async (keyword) => {
  if (!keyword.trim()) {
    suggestions.value = []
    return
  }
  const seq = ++suggestSeq
  const res = await api.search.suggest({ keyword })
  if (seq !== suggestSeq) return // 期间已有更新的输入，丢弃本次结果
  suggestions.value = res
}, 300)

// 执行搜索
const doSearch = (keyword) => {
  if (!keyword.trim()) return
  saveToHistory(keyword)
  uni.navigateTo({
    url: `/pages/search/result?keyword=${encodeURIComponent(keyword)}`
  })
}
```

**数据依赖**：
- 热门搜索接口
- 搜索联想接口
- 搜索历史（本地）

### 6.2 搜索结果页

**页面路径**：`pages/search/result`

**功能点**：
- 搜索结果列表
- 筛选器（分类、价格、品牌、标签）
- 排序（综合、销量、价格、新品）
- 无结果推荐

**关键组件**：
- `SearchResultList`：搜索结果列表
- `SearchFilter`：搜索筛选器
- `SearchSort`：搜索排序
- `NoResult`：无结果组件

### 6.3 筛选器

**功能点**：
- 分类筛选
- 价格区间筛选
- 品牌筛选
- 标签筛选（包邮、新品、促销）
- 多选/单选
- 筛选条件回显

**关键组件**：
- `FilterPanel`：筛选面板
- `PriceRange`：价格区间选择
- `BrandFilter`：品牌筛选
- `TagFilter`：标签筛选

**技术要点**：
```javascript
const filters = reactive({
  category: null,
  brands: [],
  priceMin: null,
  priceMax: null,
  tags: []
})

const applyFilter = () => {
  page.value = 1
  fetchSearchResults()
}

const resetFilter = () => {
  filters.category = null
  filters.brands = []
  filters.priceMin = null
  filters.priceMax = null
  filters.tags = []
  applyFilter()
}
```

**数据依赖**：
- 搜索接口
- 筛选条件接口

### 6.4 搜索历史

**功能点**：
- 搜索历史记录（最多 10 条）
- 历史记录删除
- 历史记录清空
- 本地存储

**技术要点**：
```javascript
const HISTORY_KEY = 'searchHistory'
const MAX_HISTORY = 10

const saveToHistory = (keyword) => {
  let history = uni.getStorageSync(HISTORY_KEY) || []
  history = history.filter(item => item !== keyword)
  history.unshift(keyword)
  history = history.slice(0, MAX_HISTORY)
  uni.setStorageSync(HISTORY_KEY, history)
  searchHistory.value = history
}

const clearHistory = () => {
  uni.removeStorageSync(HISTORY_KEY)
  searchHistory.value = []
}
```

> 本地存储与后端 Redis 搜索历史（后端 3.3.3，保留 10 条）为两份数据：本地优先读写保证离线可用；登录态下在页面 onShow 时与服务端单向同步（服务端覆盖本地），避免多设备历史割裂。

### 6.5 热门搜索

**功能点**：
- 热门搜索词列表
- 热词排名标识
- 热词标签（热/新/荐）
- 点击搜索

**关键组件**：
- `HotSearch`：热门搜索组件
- `HotWordTag`：热词标签

**数据依赖**：
- 热门搜索接口

---

## 七、购物车

### 7.1 购物车页

**页面路径**：`pages/cart/cart`

**功能点**：
- 购物车商品列表
- 商品选择（单选/全选）
- 商品数量修改
- 商品删除
- 失效商品展示
- 购物车汇总（总价、优惠）
- 底部结算栏

**关键组件**：
- `CartList`：购物车列表
- `CartItem`：购物车项
- `CartBar`：底部结算栏
- `QuantityInput`：数量输入
- `InvalidGoods`：失效商品

**技术要点**：
```javascript
const cartList = ref([])
const selectedIds = ref([])
// 仅统计有效商品：失效/下架商品不可勾选，不参与全选判断
const validItems = computed(() => cartList.value.filter(item => item.valid))
const isAllSelected = computed(() => {
  return validItems.value.length > 0 &&
         validItems.value.every(item => selectedIds.value.includes(item.id))
})

const toggleAll = () => {
  selectedIds.value = isAllSelected.value
    ? []
    : validItems.value.map(item => item.id)
  updateCartBar()
}

const updateQuantity = async (item, quantity) => {
  // 数量变更失败（下架/超库存）时以服务端返回为准回滚本地状态
  try {
    await api.cart.update({ id: item.id, quantity })
    item.quantity = quantity
  } catch (e) {
    await reloadCart() // 重新拉取购物车，纠正本地与服务端不一致
  }
  updateCartBar()
}

const updateCartBar = () => {
  const selected = cartList.value.filter(item => selectedIds.value.includes(item.id))
  // 金额一律用「分」为单位整数累加，避免浮点误差（0.1 + 0.2 问题）
  const totalCents = selected.reduce((sum, item) => sum + item.priceCent * item.quantity, 0)
  totalPrice.value = (totalCents / 100).toFixed(2)
  totalCount.value = selected.reduce((sum, item) => sum + item.quantity, 0)
  // 注意：此处仅为底部栏预估展示；最终应付金额（含运费/优惠）以后端「订单预览」接口为准
}
```

**数据依赖**：
- 购物车列表接口
- 购物车更新接口
- 购物车删除接口

### 7.2 批量操作

**功能点**：
- 批量删除
- 批量移动收藏
- 批量修改数量
- 全选/取消全选

**关键组件**：
- `BatchActionBar`：批量操作栏
- `BatchDeleteConfirm`：批量删除确认

### 7.3 优惠券提示

**功能点**：
- 可用优惠券提示
- 优惠券领取入口
- 满减优惠提示
- 最优优惠推荐

**关键组件**：
- `CouponTip`：优惠券提示
- `CouponAvailable`：可用优惠券

**数据依赖**：
- 可用优惠券接口
- 优惠计算接口

### 7.4 库存校验

**功能点**：
- 加入购物车时库存校验
- 购物车商品库存实时校验
- 库存不足提示
- 库存预警

**技术要点**：
```javascript
const addToCart = async (productId, skuId, quantity) => {
  // 前端预检仅为交互优化（商品详情页已有库存展示，通常无需再单独查库存接口）
  // 库存存在并发竞态（TOCTOU）：查到有货 ≠ 加购时有货，绝不能作为强校验
  try {
    await api.cart.add({ productId, skuId, quantity }) // 服务端强校验并预占库存
    uni.showToast({ title: '已加入购物车' })
    updateCartCount()
  } catch (e) {
    if (e.code === ERROR_CODES.INSUFFICIENT_STOCK) {
      uni.showToast({ title: '库存不足', icon: 'none' })
    } else {
      throw e
    }
  }
}
```

**设计原则**：库存最终校验与预占一律由服务端（库存中心）完成，前端只消费错误码做提示；"先查库存再加购"的两步式写法应禁止。

**数据依赖**：
- 库存查询接口
- 加入购物车接口

---

## 八、下单结算

### 8.1 确认订单页

**页面路径**：`pages/checkout/index`

**功能点**：
- 收货地址选择/新增
- 商品清单预览
- 配送方式选择
- 优惠券选择
- 积分抵扣
- 订单备注
- 费用明细（商品总额、运费、优惠）
- 提交订单

**关键组件**：
- `AddressSelector`：地址选择
- `ProductPreview`：商品预览
- `CouponSelector`：优惠券选择
- `PointDeduction`：积分抵扣
- `OrderRemark`：订单备注
- `FeeDetail`：费用明细
- `SubmitBar`：提交栏

**技术要点**：
```xml
<template>
  <view class="checkout">
    <AddressSelector :address="selectedAddress" @add="onAddAddress" />
    <ProductPreview :items="orderItems" />
    <CouponSelector :available="coupons" :selected="selectedCoupon" />
    <PointDeduction :points="userPoints" :max-deduct="maxDeduct" />
    <OrderRemark v-model="remark" />
    <FeeDetail :items="feeItems" :total="totalPrice" />
    <SubmitBar :total="totalPrice" :disabled="!canSubmit" @submit="onSubmit" />
  </view>
</template>
```

**数据依赖**：
- 地址列表接口
- 订单预览接口（`POST /order/preview`：一次性返回商品金额、运费、优惠明细与 `orderToken`，见后端 4.1.2；**无独立运费接口**，运费由预览接口返回）
- 优惠券列表接口
- 积分查询接口

**关键流程**：进入结算页 → 调用预览接口（锁定价格与库存 15 分钟，获得 `orderToken`）→ 用户调整优惠券/积分/备注 → 提交时携带 `orderToken`（见 8.3），避免价格变动期间被篡改或超卖。

### 8.2 地址选择

**功能点**：
- 地址列表选择
- 新增地址
- 编辑地址
- 地址解析（智能识别）
- 地址校验

**页面路径**：`pages/address/select`

**关键组件**：
- `AddressList`：地址列表
- `AddressForm`：地址表单
- `AddressParse`：地址解析

**技术要点**：
```javascript
// 微信收货地址
const chooseWxAddress = () => {
  uni.chooseAddress({
    success: (res) => {
      formData.name = res.userName
      formData.phone = res.telNumber
      formData.address = `${res.provinceName}${res.cityName}${res.countyName}${res.detailInfo}`
    }
  })
}

// 地址解析
const parseAddress = async (text) => {
  const res = await api.address.parse({ text })
  formData.name = res.name
  formData.phone = res.phone
  formData.province = res.province
  formData.city = res.city
  formData.district = res.district
  formData.detail = res.detail
}
```

**数据依赖**：
- 地址列表接口
- 地址解析接口
- 地址保存接口

### 8.3 支付方式

**功能点**：
- 微信支付
- 支付宝支付
- 余额支付
- 货到付款（部分场景）
- 支付密码输入

**关键组件**：
- `PaymentSelector`：支付方式选择
- `PaymentModal`：支付弹窗
- `PasswordInput`：密码输入

**技术要点**：
```javascript
// 防重复提交：点击后立即禁用按钮（小程序端最常见的重复下单来源是连点）
const submitting = ref(false)

const pay = async () => {
  if (submitting.value) return
  submitting.value = true
  try {
    // 1) 提交订单：携带结算页预览返回的 orderToken（价格/库存锁定凭证，后端 4.1.4）
    const orderRes = await api.order.submit({
      orderToken: preview.value.orderToken,
      channelCode: platform.channelCode,
      remark: remark.value
    })

    // 2) 获取平台支付参数：对应后端 POST /api/v1/miniprogram/payment/prepay
    //    （请求只收 orderId，channelCode 由拦截器注入；金额/openId 由服务端从订单与登录态获取，严禁客户端传入——
    //     后端 8.1.2 安全约束：客户端传金额/身份是"改价支付"与越权漏洞的直接来源）
    //    paymentType 同样不传：支付方式由服务端按订单与渠道配置确定，返回值中的 payType 决定调起方式
    const payParams = await api.payment.prepay({ orderId: orderRes.orderId })

    // 3) 调起支付（参数直接透传，勿在前端拼装签名）
    await platformPayment(payParams)

    // 4) 支付成功也不直接信任本地回调：跳转订单详情后由服务端支付状态查询确认
    //    （回调可能延迟、用户可能取消后又支付、客户端 success 不可靠）
    uni.redirectTo({ url: `/pages/order/detail?id=${orderRes.orderId}&payPending=1` })
  } catch (err) {
    if (err.message === 'PAY_CANCEL') {
      // 用户主动取消：留在当前页或引导去待付款订单，不弹错误框
      return
    }
    uni.showModal({
      title: '支付失败',
      content: '支付未成功，是否重试？',
      success: (res) => {
        if (!res.confirm) {
          // 购物车/订单列表是 tabBar 页面，必须用 switchTab（navigateTo 会静默失败）
          uni.switchTab({ url: '/pages/order/list' })
        }
      }
    })
  } finally {
    submitting.value = false
  }
}
```

- 订单详情页收到 `payPending=1` 后轮询订单支付状态接口（间隔 2s，最多 5 次）确认支付结果，再展示成功/失败，杜绝"已扣款却显示未支付"
- 支付方式选择器（PaymentSelector）只展示后端渠道配置允许的支付方式（后端 11.4.1），选择结果**不改变 prepay 入参**（后端只收 orderId），也不参与任何签名计算
- 支付超时按订单 `pay_deadline`（付款截止时间，后端到期自动取消，时长待产品确认，建议 30 分钟）在前端用 `orderToken` 有效期倒计时提示

**数据依赖**：
- 提交订单接口（`POST /order/submit`，幂等，携带 orderToken）
- 创建支付参数接口（`POST /payment/prepay`，只收 orderId，见后端 8.1.2）
- 订单支付状态查询接口（轮询确认）
- 平台支付能力（`uni.requestPayment`）

### 8.4 订单备注

**功能点**：
- 常用备注快捷选择
- 自定义备注输入
- 备注字数限制
- 隐私备注（仅商家可见）

**关键组件**：
- `RemarkInput`：备注输入
- `QuickRemarkTags`：快捷备注标签

**技术要点**：
```javascript
const quickRemarks = ['放快递柜', '送货上门', '电话联系', '工作日送达', '周末送达']
const remark = ref('')
const maxLength = 50

const selectQuickRemark = (text) => {
  remark.value = text
}
```

---

## 九、订单管理

### 9.1 订单列表页

**页面路径**：`pages/order/list`

**功能点**：
- 订单状态Tab（全部/待付款/待发货/待收货/待评价/已完成/已取消/售后）
- 订单卡片展示
- 订单操作（付款/确认收货/取消订单/再次购买）
- 下拉刷新
- 上拉加载

**状态枚举对齐**（与后端 4.2.1 `status` 参数保持一致，前端 Tab ↔ 后端枚举一一映射）：

| 前端 Tab | 后端 status 枚举 |
|----------|-----------------|
| 全部 | `all` |
| 待付款 | `unpaid` |
| 待发货 | `unshipped` |
| 待收货 | `unreceived` |
| 待评价 | `unreviewed` |
| 已完成 | `completed` |
| 已取消 | `cancelled`（后端枚举需补充） |
| 售后 | 独立售后单列表（非订单状态，见 9.5，后端需提供售后单列表接口） |

**关键组件**：
- `OrderTabs`：订单状态Tab
- `OrderList`：订单列表
- `OrderCard`：订单卡片
- `OrderActions`：订单操作按钮

**技术要点**：
```xml
<template>
  <view class="order-list">
    <OrderTabs v-model="activeTab" :tabs="orderTabs" />
    <OrderList :orders="orderList" :loading="loading" @refresh="onRefresh" @loadMore="onLoadMore">
      <OrderCard v-for="order in orderList" :key="order.id" :order="order">
        <OrderActions :order="order" @pay="onPay" @confirm="onConfirm" @cancel="onCancel" />
      </OrderCard>
    </OrderList>
  </view>
</template>
```

**数据依赖**：
- 订单列表接口
- 订单状态接口

### 9.2 订单详情页

**页面路径**：`pages/order/detail`

**功能点**：
- 订单状态展示
- 商品信息
- 物流信息
- 费用明细
- 订单信息（订单号、下单时间、支付方式）
- 订单操作
- 申请售后
- 联系客服

**关键组件**：
- `OrderStatusHeader`：订单状态头部
- `LogisticsInfo`：物流信息
- `OrderFeeDetail`：费用明细
- `OrderInfo`：订单信息
- `OrderDetailActions`：订单操作

**技术要点**：
```xml
<template>
  <view class="order-detail">
    <OrderStatusHeader :order="order" />
    <LogisticsInfo :logistics="order.logistics" />
    <OrderFeeDetail :order="order" />
    <OrderInfo :order="order" />
    <OrderDetailActions :order="order" />
  </view>
</template>
```

**数据依赖**：
- 订单详情接口
- 物流信息接口

### 9.3 物流跟踪

**功能点**：
- 物流轨迹时间线
- 物流地图展示
- 快递员信息
- 物流状态订阅
- 物流异常提醒

**关键组件**：
- `LogisticsTimeline`：物流时间线
- `LogisticsMap`：物流地图
- `CourierInfo`：快递员信息

**技术要点**：
```xml
<template>
  <view class="logistics">
    <!-- 坐标与快递员信息后端 /logistics/track 响应未提供（后端 4.3.2），缺失时必须隐藏地图降级为时间线 -->
    <LogisticsMap v-if="logistics.location" :location="logistics.location" />
    <LogisticsTimeline :tracks="logistics.tracks" />
    <CourierInfo v-if="logistics.courier" :courier="logistics.courier" />
  </view>
</template>
```

> 契约缺口：物流地图需要骑手/运单实时坐标、快递员信息需要联系方式，均需后端物流服务在 track 接口中补充字段（或提供独立接口）；在补充前前端按"无坐标则不渲染地图"降级，禁止地图模块报错影响整页。

**数据依赖**：
- 物流信息接口
- 物流订阅接口

### 9.4 确认收货

**功能点**：
- 确认收货弹窗
- 确认收货后评价入口
- 自动确认收货倒计时
- 确认收货校验

**关键组件**：
- `ConfirmReceiveModal`：确认收货弹窗
- `AutoReceiveCountdown`：自动确认倒计时

**技术要点**：
```javascript
const confirmReceive = async (orderId) => {
  uni.showModal({
    title: '确认收货',
    content: '请确认已收到商品，确认后将无法申请售后（质量问题除外）',
    success: async (res) => {
      if (res.confirm) {
        await api.order.confirmReceive({ orderId })
        // 跳转到评价页
        uni.navigateTo({
          url: `/pages/order/review?orderId=${orderId}`
        })
      }
    }
  })
}
```

**数据依赖**：
- 确认收货接口

### 9.5 售后管理

**功能点**：
- 申请售后（退货/退款/换货）
- 售后进度查询
- 售后详情
- 售后物流
- 售后协商记录

**页面路径**：`pages/order/after-sale`

**关键组件**：
- `AfterSaleApply`：售后申请表单
- `AfterSaleProgress`：售后进度
- `AfterSaleDetail`：售后详情
- `AfterSaleLogistics`：售后物流

**技术要点**：
```javascript
const applyAfterSale = async (params) => {
  const res = await api.afterSale.apply({
    orderId: params.orderId,
    type: params.type, // refund/return/exchange
    reason: params.reason,
    description: params.description,
    images: params.images
  })
  uni.showToast({ title: '申请已提交' })
  uni.navigateTo({
    url: `/pages/order/after-sale-detail?id=${res.id}`
  })
}
```

**数据依赖**：
- 售后申请接口
- 售后详情接口
- 售后进度接口

### 9.6 商品评价（晒单/追评）

**页面路径**：`pages/order/review`（发表评价）、评价列表内嵌于商品详情 5.2 的 `ReviewSection`

**功能点**：
- 确认收货后发表评价（打分 + 文字 + 晒图，最多 9 张）
- 评价列表（商品详情页入口，分页加载、按标签筛选：全部/有图/好评/中评/差评）
- 追评（发表后 90 天内可追评一次）
- 评价点赞/回复（商家回复展示）

**关键组件**：
- `ReviewSection`：详情页评价摘要区（复用 5.2）
- `ReviewForm`：评价表单（评分、文字、`EcUploader` 晒图）
- `ReviewList`：评价列表
- `ReviewTagBar`：评价标签筛选栏

**技术要点**：
```javascript
// 发表评价：图片先走 uni.uploadFile 上传至后端临时凭证换取的 OSS 地址，再提交 URL 列表
const submitReview = async ({ orderId, items }) => {
  const imageUrls = await Promise.all(items.images.map(uploadImage))
  await api.review.create({ orderId, items: items.map((it, i) => ({ ...it, images: imageUrls[i] })) })
  uni.showToast({ title: '评价成功' })
  uni.navigateBack()
}
```

**数据依赖**（后端计划尚未定义小程序端评价接口，需补充，见 18.4）：
- 发表评价接口
- 评价列表接口（含标签计数）
- 追评接口

---

## 十、个人中心

### 10.1 个人中心首页

**页面路径**：`pages/user/index`

**功能点**：
- 用户信息卡片（头像、昵称、会员等级）
- 用户数据统计（订单数、优惠券、积分、收藏）
- 订单快捷入口（待付款/待发货/待收货/待评价/售后）
- 功能菜单（地址管理、优惠券、积分商城、客服、设置）
- 会员中心入口
- 签到入口

**关键组件**：
- `UserInfoCard`：用户信息卡片
- `UserStats`：用户数据统计
- `OrderShortcuts`：订单快捷入口
- `FunctionMenu`：功能菜单
- `MemberCenter`：会员中心入口
- `SignIn`：签到入口

**技术要点**：
```xml
<template>
  <view class="user-center">
    <UserInfoCard :user="userInfo" />
    <UserStats :stats="userStats" />
    <OrderShortcuts :order-counts="orderCounts" />
    <MemberCenter :member="memberInfo" />
    <FunctionMenu :menus="functionMenus" />
  </view>
</template>
```

**数据依赖**：
- 用户信息接口
- 用户统计接口
- 订单数量统计接口
- 会员信息接口

### 10.2 个人信息

**功能点**：
- 头像修改
- 昵称修改
- 性别设置
- 生日设置
- 个性签名
- 实名认证

**页面路径**：`pages/user/profile`

**关键组件**：
- `AvatarUpload`：头像上传
- `ProfileForm`：个人信息表单
- `RealNameAuth`：实名认证

**技术要点**：
```javascript
const updateAvatar = async (avatarUrl) => {
  await api.user.updateProfile({ avatar: avatarUrl })
  userInfo.value.avatar = avatarUrl
  uni.showToast({ title: '头像更新成功' })
}
```

**数据依赖**：
- 用户信息接口
- 更新用户信息接口

### 10.3 收货地址管理

**功能点**：
- 地址列表
- 新增地址
- 编辑地址
- 删除地址
- 设为默认地址
- 地址解析
- 微信地址导入

**页面路径**：`pages/address/list`

**关键组件**：
- `AddressList`：地址列表
- `AddressForm`：地址表单
- `AddressParse`：地址解析
- `DefaultAddressTag`：默认地址标签

**技术要点**：
```javascript
const deleteAddress = async (id) => {
  uni.showModal({
    title: '删除地址',
    content: '确定要删除该地址吗？',
    success: async (res) => {
      if (res.confirm) {
        await api.address.delete({ id })
        addressList.value = addressList.value.filter(item => item.id !== id)
      }
    }
  })
}
```

**数据依赖**：
- 地址列表接口
- 地址增删改接口

### 10.4 我的收藏

**功能点**：
- 收藏商品列表
- 收藏店铺列表
- 取消收藏
- 收藏分类
- 收藏降价提醒

**页面路径**：`pages/user/favorite`

**关键组件**：
- `FavoriteTabs`：收藏分类Tab
- `FavoriteList`：收藏列表
- `FavoriteItem`：收藏项

### 10.5 我的优惠券

**功能点**：
- 优惠券列表（未使用/已使用/已过期）
- 优惠券详情
- 优惠券领取
- 优惠券使用说明
- 优惠券到期提醒

**页面路径**：`pages/coupon/my`

**关键组件**：
- `CouponTabs`：优惠券状态Tab
- `CouponList`：优惠券列表
- `CouponCard`：优惠券卡片
- `CouponDetail`：优惠券详情

**技术要点**：
```xml
<template>
  <view class="coupon-list">
    <CouponTabs v-model="activeTab" />
    <CouponList :coupons="coupons">
      <CouponCard v-for="coupon in coupons" :key="coupon.id" :coupon="coupon">
        <template v-if="coupon.status === 'available'">
          <EcButton @click="useCoupon(coupon)">去使用</EcButton>
        </template>
      </CouponCard>
    </CouponList>
  </view>
</template>
```

**数据依赖**：
- 优惠券列表接口
- 优惠券详情接口

### 10.6 积分中心

**功能点**：
- 积分余额
- 积分明细（收入/支出）
- 积分商城入口
- 积分签到
- 积分兑换记录

**页面路径**：`pages/user/points`

**关键组件**：
- `PointsBalance`：积分余额
- `PointsHistory`：积分明细
- `PointsSignIn`：积分签到
- `PointsMall`：积分商城入口

### 10.7 设置

**功能点**：
- 账号与安全
- 隐私设置
- 消息通知设置
- 清除缓存
- 关于我们
- 退出登录
- 版本更新检查

**页面路径**：`pages/user/settings`

**关键组件**：
- `SettingsList`：设置列表
- `NotificationSettings`：消息通知设置
- `ClearCache`：清除缓存
- `LogoutButton`：退出登录

**技术要点**：
```javascript
const logout = async () => {
  const { confirm } = await uni.showModal({
    title: '退出登录',
    content: '确定要退出登录吗？'
  })
  if (!confirm) return
  try {
    await api.user.logout() // 必须调用服务端登出：JWT 为无状态令牌，仅清本地无法使其失效（后端 Redis 可主动失效）
  } catch (e) {
    // 登出接口失败不阻塞本地清理
  }
  clearToken()            // 统一走 utils/storage.ts 清理（token/userInfo/匿名设备标识）
  resetStores()           // 重置 Pinia：用户、购物车、消息等状态
  uni.reLaunch({ url: '/pages/index/index' })
}
```

---

## 十一、消息通知

### 11.1 消息中心

**页面路径**：`pages/message/index`

**功能点**：
- 消息分类（系统通知/订单消息/优惠消息/互动消息）
- 消息列表
- 消息已读/未读
- 消息删除
- 消息详情

**关键组件**：
- `MessageTabs`：消息分类Tab
- `MessageList`：消息列表
- `MessageItem`：消息项
- `UnreadBadge`：未读角标

**技术要点**：
```xml
<template>
  <view class="message-center">
    <MessageTabs v-model="activeTab" :unread-counts="unreadCounts" />
    <MessageList :messages="messages">
      <MessageItem v-for="msg in messages" :key="msg.id" :message="msg" @read="onRead" @delete="onDelete" />
    </MessageList>
  </view>
</template>
```

**数据依赖**：
- 消息列表接口
- 消息已读接口
- 未读数接口

### 11.2 站内消息与服务端推送

> **重要修正**：微信小程序"模板消息"（formId 机制）已于 2020 年停用，统一升级为**订阅消息**；且模板/订阅消息的**发送只能由服务端调用平台 API**，客户端不得持有发送权限（否则可被恶意刷消息）。前端职责仅为：① 在关键交互时请求订阅授权（见 11.3）；② 消息中心站内信的展示（见 11.1）。
> **与后端口径一致**：与后端计划 6.1"模板消息（历史兼容，逐步下线）"口径保持一致——存量授权在灰度期可由服务端继续下发（前端不新增任何模板消息调用），新功能一律基于订阅消息 + 站内信；平台无授权/授权余量耗尽时，服务端自动降级站内信（跨渠道必达兜底通道，后端 6.4），前端无需感知发送通道差异。

**功能点**：
- 订单状态通知（付款成功/发货/签收）→ 服务端在订单状态机节点触发订阅消息
- 物流更新通知 → 服务端在物流状态变更时触发
- 促销活动通知 / 优惠券到期提醒 → 仅对已授权该模板的用户发送（服务端依据订阅记录）
- 系统公告 → 站内信（消息中心），不依赖订阅授权

**服务端触发节点对照**（由后端消息推送服务执行，前端只在节点前完成授权）：

| 业务节点 | 前端动作 | 服务端动作 |
|---------|---------|-----------|
| 提交订单前 | 点击"提交订单"的同步调用栈中请求"订单状态通知"订阅 | — |
| 支付成功 | — | 触发"支付成功"订阅消息 |
| 商家发货 | — | 触发"发货通知"订阅消息（后端 6.2.3） |
| 优惠券到期前 1 天 | — | 触发"优惠券到期提醒" |

**数据依赖**：
- 消息中心列表/已读接口（站内信）
- 订阅记录接口（服务端发送前校验授权余量）

### 11.3 订阅消息

**功能点**：
- 一次性订阅消息
- 长期订阅消息（需申请）
- 订阅消息授权
- 订阅消息管理

**技术要点**：
```javascript
// 微信/支付宝/抖音订阅消息统一封装（各端 API 名称不同，用条件编译抹平）
const requestSubscribe = async (templateIds) => {
  return new Promise((resolve) => {
    uni.requestSubscribeMessage({
      tmplIds: templateIds,
      success: resolve,
      // fail 也要 resolve（用户拒绝/已达弹窗上限），不得阻断主流程
      fail: resolve
    })
  })
}

// 使用示例：下单前请求订阅（必须在用户点击的「同步调用栈」内发起）
const onSubmitOrder = async () => {
  // 1. 先请求订阅授权（requestSubscribeMessage 由用户点击触发，异步后再调用会弹窗失败）
  const res = await requestSubscribe([TEMPLATE_ORDER_STATUS, TEMPLATE_SHIP_NOTICE])
  // 2. 将授权结果上报服务端记录订阅余量（一次性订阅：授权一次 = 服务端只能发一条）
  const accepts = Object.entries(res).filter(([, v]) => v === 'accept').map(([k]) => k)
  if (accepts.length) await api.message.subscribe({ templateIds: accepts, scene: 'order_submit' })
  // 3. 再继续真正的下单请求
  await doSubmitOrder()
}
```

**调用时机规范**（错过时机将永久丢失该次授权）：
- 必须由用户点击事件的同步调用栈发起（点击"立即购买/提交订单/发起拼团"回调的第一行），不得在 `onLoad`、接口回调等异步链路中调用
- 一次性订阅"一次授权只能发一条"：发货/签收/退款等关键节点各自请求对应模板，不指望一次授权覆盖全部
- 长期订阅消息仅对政务、医疗等特定类目开放，**电商类目通常申请不到**，方案设计不得依赖长期订阅
- 单次调用最多携带 3 个模板 ID；被用户拒绝或达到弹窗频率限制时 `fail` 返回，需静默降级（不打扰用户）
- 支付宝为 `my.requestSubscribeMessage`、抖音为 `tt.requestSubscribeMessage`，参数结构略有差异，统一收敛在 `utils/platform.ts`

**数据依赖**：
- 订阅消息接口

---

## 十二、客服系统

### 12.1 在线客服

**页面路径**：`pages/customer-service/index`

**功能点**：
- 在线聊天窗口
- 文字消息
- 图片消息
- 商品卡片消息
- 常见问题快捷入口
- 人工客服转接
- 客服评价

**关键组件**：
- `ChatWindow`：聊天窗口
- `ChatMessage`：聊天消息
- `QuickQuestions`：常见问题
- `ProductCard`：商品卡片消息
- `ServiceEvaluate`：服务评价

**技术要点**：
```xml
<template>
  <view class="customer-service">
    <ChatWindow :messages="messages" @send="onSend">
      <ChatMessage v-for="msg in messages" :key="msg.id" :message="msg" />
    </ChatWindow>
    <QuickQuestions :questions="faqList" @select="onQuickQuestion" />
  </view>
</template>
```

**通道选型与实现要点**（后端 9.1.4 使用 WebSocket）：
- **优先接入平台官方客服**：微信 `button open-type="contact"`（免开发、用户习惯成本低），支付宝 `my.openChat`；自建 IM 客服作为补充通道（跨平台统一、可沉淀会话数据），两条通道并存，入口按平台配置开关
- 自建 IM 使用 `uni.connectSocket`（对应后端 WebSocket），必须处理：**wss 合法域名配置**（公众平台"服务器域名"中添加 socket 合法域名，且必须 HTTPS/WSS）、心跳保活（30s ping）、断线重连（指数退避，最多 5 次）、页面 `onHide` 断开 `onShow` 重连
- 小程序切后台约 5 分钟后连接会被系统回收，重连后需通过 `GET /cs/message/list` 拉取离线消息补齐，不能依赖本地消息数组
- 消息发送需处理"发送中/已送达/失败重发"三态，图片消息走 `uni.uploadFile` 先传后发

**数据依赖**：
- 客服会话接口
- 消息发送接口（`POST /cs/message/send`）
- 消息列表接口（`GET /cs/message/list`，离线补齐）
- 常见问题接口

### 12.2 工单系统

**功能点**：
- 工单提交
- 工单类型（售前/售后/投诉/建议）
- 工单进度查询
- 工单回复
- 工单关闭

**页面路径**：`pages/customer-service/ticket`

**关键组件**：
- `TicketForm`：工单表单
- `TicketList`：工单列表
- `TicketDetail`：工单详情
- `TicketReply`：工单回复

**技术要点**：
```javascript
const submitTicket = async (params) => {
  const res = await api.ticket.create({
    type: params.type,
    title: params.title,
    description: params.description,
    images: params.images,
    orderId: params.orderId
  })
  uni.showToast({ title: '工单已提交' })
  uni.navigateTo({
    url: `/pages/customer-service/ticket-detail?id=${res.id}`
  })
}
```

**数据依赖**：
- 工单创建接口
- 工单列表接口
- 工单详情接口

### 12.3 消息记录

**功能点**：
- 聊天记录保存
- 聊天记录同步
- 聊天记录搜索
- 聊天记录导出

**关键组件**：
- `ChatHistory`：聊天记录
- `ChatSearch`：聊天搜索

---

## 十三、分享裂变

### 13.1 分享链接

**功能点**：
- 商品分享链接
- 店铺分享链接
- 活动分享链接
- 邀请链接
- 分享链接追踪

**技术要点**：
```javascript
// 生成分享链接：对齐后端 POST /share/link/generate（返回 shareCode），禁止前端明文拼 userId
// —— 明文 inviter 可被篡改/枚举（伪造邀请、爬取用户 ID），shareCode 可控有效期与防刷
const generateShareUrl = async (type, id) => {
  const res = await api.share.generateLink({
    shareType: type,           // product / shop / activity / invite
    shareId: String(id),
    channelCode: platform.channelCode
  })
  // res: { shareCode, expireTime }
  const page = SHARE_PAGE_MAP[type] // product -> /pages/product/detail
  return `${page}?spuId=${id}&shareCode=${res.shareCode}`
}

// 落地页处理（onLoad）：对应后端 GET /share/link/parse?shareCode=xx
onLoad(async (options) => {
  if (options.shareCode) {
    const parsed = await api.share.parseLink({ shareCode: options.shareCode })
    // parsed 用于过期/有效性校验（链接默认 7 天有效，过期降级为常规浏览，见下方要点）
    // 暂存的是 shareCode 本身（而非明文 shareUserId）：登录时随 silentLogin 上报（见 2.2），
    // shareUserId 由服务端根据 shareCode 反查，防止客户端伪造分享人（后端 2.1.2/7.1.2）
    setPendingShareCode(options.shareCode)
    trackShareArrive({ shareCode: options.shareCode, scene: options.scene })
  }
  // 注意：小程序码进入时归因参数在 options.scene（≤32 字符），需 base64/自定义编码解析，
  // 不能依赖 query；解析逻辑收敛在 utils/share.ts 统一处理两种入口
})
```

**要点**：
- 归因关系**不要在落地页立即写入**（用户可能只是点开未注册），应在"注册/首单成功"时由服务端归因，避免刷邀请
- 未登录用户同样记录 pending 归因，登录成功后随静默登录参数上报
- 分享链接默认 7 天有效（后端 7.1.4），过期落地页需降级提示并走常规浏览

**数据依赖**：
- 邀请关系接口
- 分享记录接口

### 13.2 分享海报

**功能点**：
- 商品海报生成
- 店铺海报生成
- 活动海报生成
- 邀请海报生成
- 海报保存与分享

**关键组件**：
- `SharePoster`：分享海报组件
- `PosterCanvas`：海报画布

**技术要点**：
```javascript
// 海报生成：优先后端服务端渲染（POST /share/poster/generate，后端 7.2：模板化 + CDN 缓存 + 异步）
// 本地 canvas 绘制仅作为离线降级方案
const generatePoster = async (data) => {
  try {
    const res = await api.share.generatePoster({
      shareType: data.type, shareId: data.id, channelCode: platform.channelCode
    })
    // 后端为异步生成：status=ready 直接返回 URL；processing 则轮询或稍后重试
    if (res.status === 'ready') {
      await savePosterImage(res.posterUrl)
      return
    }
    uni.showToast({ title: '海报生成中，请稍后在分享中心查看', icon: 'none' })
  } catch (e) {
    await drawLocalPoster(data) // canvas 2d 降级
  }
}

// 降级方案：必须使用 canvas 2d（旧版 createCanvasContext 已废弃，高分屏模糊且不支持同层渲染）
const drawLocalPoster = (data) => {
  return new Promise((resolve, reject) => {
    uni.createSelectorQuery().select('#posterCanvas')
      .fields({ node: true, size: true })
      .exec(([{ node: canvas, width, height }]) => {
        const ctx = canvas.getContext('2d')
        const dpr = uni.getSystemInfoSync().pixelRatio
        canvas.width = width * dpr   // 画布真实像素 = 显示尺寸 × dpr，否则图片模糊
        canvas.height = height * dpr
        ctx.scale(dpr, dpr)
        // 绘制背景/商品图/标题/价格/二维码...
        // 二维码必须使用后端下发的小程序码（getwxacodeunlimit，scene 携带 shareCode），
        // 普通二维码无法跳转小程序，海报裂变链路会断
        drawLayers(ctx, data, width, height)
        uni.canvasToTempFilePath({ canvas, success: (res) => resolve(res.tempFilePath), fail: reject })
      })
  })
}
```

**要点**：
- 后端海报服务已支持模板管理与 CDN 缓存，避免前端维护两套绘制逻辑导致风格不一致
- 保存到相册需处理 `scope.writePhotosAlbum` 授权拒绝场景（同 5.3）

**数据依赖**：
- 海报数据接口
- 二维码生成接口

### 13.3 拼团

**功能点**：
- 拼团活动列表
- 发起拼团
- 参团
- 拼团进度
- 拼团成功/失败通知
- 拼团分享

**页面路径**：`pages/share/group-buy`

**关键组件**：
- `GroupBuyList`：拼团列表
- `GroupBuyCard`：拼团卡片
- `GroupProgress`：拼团进度
- `GroupShareModal`：拼团分享弹窗

**技术要点**：
```javascript
const startGroupBuy = async (activityId) => {
  const res = await api.groupBuy.start({ activityId })
  // 跳转到拼团详情
  uni.navigateTo({
    url: `/pages/share/group-buy-detail?id=${res.groupId}`
  })
}

const joinGroupBuy = async (groupId) => {
  const res = await api.groupBuy.join({ groupId })
  if (res.success) {
    uni.showToast({ title: '参团成功' })
  } else {
    uni.showToast({ title: res.message, icon: 'none' })
  }
}
```

**数据依赖**：
- 拼团活动接口
- 拼团创建接口
- 拼团参与接口

### 13.4 砍价

**功能点**：
- 砍价活动列表
- 发起砍价
- 帮砍
- 砍价进度
- 砍价成功/失败
- 砍价分享

**页面路径**：`pages/share/bargain`

**关键组件**：
- `BargainList`：砍价列表
- `BargainCard`：砍价卡片
- `BargainProgress`：砍价进度
- `BargainShareModal`：砍价分享弹窗

**技术要点**：
```javascript
// 砍价活动列表：对应后端 GET /api/v1/miniprogram/bargain/activity/list（后端 7.5.2 已补充，供 BargainList 组件）
const loadBargainActivities = (pageNum = 1) =>
  api.bargain.activityList({ pageNum, channelCode: platform.channelCode })

const startBargain = async (activityId, skuId) => {
  // 请求参数 activityId + skuId；返回 bargainRecordId（用户砍价单 ID，bargain_record.id）
  const res = await api.bargain.start({ activityId, skuId })
  uni.navigateTo({
    url: `/pages/share/bargain-detail?id=${res.bargainRecordId}`
  })
}

// helpBargain 的入参 bargainRecordId 即"被帮砍的砍价单 ID"（历史命名 bargainId 已废弃，统一改名，后端 7.5.2）
// shareCode 为帮砍人归因参数，来自砍价分享链接（13.1 同款 shareCode 机制），需登录态
const helpBargain = async (bargainRecordId, shareCode) => {
  const res = await api.bargain.help({ bargainRecordId, shareCode })
  if (res.finished) {
    uni.showModal({
      title: '砍价成功',
      content: `您已成功砍掉 ¥${res.cutAmount}`,
      showCancel: false
    })
  }
}
```

**数据依赖**：
- 砍价活动列表接口（`GET /bargain/activity/list`，后端 7.5.2 已补充）
- 砍价创建接口（`POST /bargain/create`，返回 bargainRecordId/shareCode）
- 帮砍接口（`POST /bargain/help`，入参 bargainRecordId + shareCode）
- 砍价进度接口（`GET /bargain/progress?bargainRecordId=xx`，后端 7.5.2）

---

## 十四、性能优化

### 14.1 包体积优化

**功能点**：
- 主包精简（只保留首页、登录等核心页面）
- 分包加载（按模块分包）
- 图片资源压缩与 CDN
- 无用代码删除
- 第三方库按需引入
- 组件懒加载

**技术要点**：
```json
// pages.json 分包配置（主包仅保留 tabBar 页 + 首页 + 登录）
{
  "subpackages": [
    {
      "root": "package-product",
      "pages": [
        { "path": "list", "style": { "navigationBarTitleText": "商品列表" } },
        { "path": "detail", "style": { "navigationBarTitleText": "商品详情" } }
      ]
    },
    {
      "root": "package-order",
      "pages": [
        { "path": "list", "style": { "navigationBarTitleText": "我的订单" } },
        { "path": "detail", "style": { "navigationBarTitleText": "订单详情" } }
      ]
    }
  ],
  // 分包预下载：进入首页后后台静默预下载高频分包，消除分享直达/点击跳转的下载等待
  "preloadRule": {
    "pages/index/index": { "network": "all", "packages": ["package-product"] },
    "pages/product/detail": { "network": "wifi", "packages": ["package-order", "package-marketing"] }
  }
}
```

> 注：分包页面可被分享/扫码直接打开（微信支持直达分包路径），但首次打开需下载该分包，故商品详情所在 `package-product` 必须配置预下载；`tabBar` 页面强制位于主包，不可放入分包。

**优化目标**（各平台包体积硬限制，以官方最新文档为准；微信：主包 ≤ 2MB、所有分包总计 ≤ 20MB；支付宝/抖音另有总包上限）：
- 主包 < 1.5MB（为逼近 2MB 硬限制预留素材缓冲）
- 单个分包 < 2MB（建议值，便于并行加载）
- 总体积 < 20MB（微信硬限制，超出无法上传）
- CI 卡点：构建脚本统计主包/分包体积，超阈值直接失败（对应 `.github/workflows/ci.yml`）

### 14.2 启动速度优化

**功能点**：
- 启动页资源预加载
- 关键数据缓存
- 接口预请求
- 骨架屏
- 首屏数据预取

**技术要点**：
```javascript
// 预加载关键数据：必须先判登录态——游客态调用 user/cart 接口会 401 并触发无谓的登录跳转
const preloadData = async () => {
  const tasks = [api.config.getGlobalConfig()] // 公共配置：游客/登录态都请求
  if (isLoggedIn.value) {
    tasks.push(api.user.getInfo(), api.cart.getCount())
  }
  // 单个接口失败不阻塞启动（Promise.allSettled），超时由 request 层统一控制（2s）
  const results = await Promise.allSettled(tasks)
  results.filter(r => r.status === 'rejected').forEach(r => reportError(r.reason))
}
```

```xml
<!-- 骨架屏：与真实布局等高，避免数据到达后的二次抖动 -->
<template>
  <view v-if="loading" class="skeleton">
    <SkeletonHeader />
    <SkeletonBanner />
    <SkeletonProductList />
  </view>
  <view v-else class="content">
    <!-- 实际内容 -->
  </view>
</template>
```

**优化目标**：
- 冷启动 < 2s
- 热启动 < 1s
- 首屏渲染 < 1s

### 14.3 列表流畅度

**功能点**：
- 虚拟列表
- 图片懒加载
- 列表项复用
- 防抖与节流
- 离屏渲染优化

**技术要点**（虚拟列表仅适用于**等高**列表，如订单列表、消息列表；**不适用于商品瀑布流**——双列不等高卡片的高度无法预计算）：

```javascript
// 等高虚拟列表示例（含上下缓冲区 overscan，避免快速滚动时露白）
<template>
  <scroll-view :scroll-y="true" @scroll="onScroll" :style="{ height: containerHeight + 'px' }">
    <view :style="{ height: totalHeight + 'px', position: 'relative' }">
      <view v-for="item in visibleItems" :key="item.id" :style="{ position: 'absolute', top: item.top + 'px' }">
        <ProductCard :product="item" />
      </view>
    </view>
  </scroll-view>
</template>

<script>
import { ref, computed } from 'vue'

const itemHeight = 200
const OVERSCAN = 5 // 缓冲行数，减少滚动时的空白渲染
const containerHeight = ref(0)
const scrollTop = ref(0)
const list = ref([])

const visibleCount = computed(() => {
  return Math.max(1, Math.ceil(containerHeight.value / itemHeight))
})

const visibleItems = computed(() => {
  const start = Math.max(0, Math.floor(scrollTop.value / itemHeight) - OVERSCAN)
  const end = Math.min(list.value.length, start + visibleCount.value + OVERSCAN * 2)
  return list.value.slice(start, end).map((item, index) => ({
    ...item,
    top: (start + index) * itemHeight
  }))
})
</script>
```

**商品瀑布流的正确方案**：
- 不使用虚拟滚动：双列瀑布流采用"分页加载（每页 10-20 条）+ 图片懒加载 + 首屏骨架"即可满足流畅度，微信列表渲染性能足以支撑数百 DOM 节点
- 若后续确需超长列表优化，优先迁移至 skyline 渲染管线的 `recycle-list`（原生节点复用），而非手写定高虚拟列表

### 14.4 图片优化

**功能点**：
- 图片懒加载
- 图片压缩（WebP）
- 图片 CDN
- 图片占位图
- 渐进式加载

**技术要点**：
```xml
<!-- 图片地址统一带 CDN 裁剪参数：按展示位请求合适规格，禁止下载原图 -->
<!-- 例：商品列表卡片 ?w=345&h=345&format=webp；详情轮播 ?w=750&format=webp -->
<image
  :src="thumbUrl(product.mainImage, 345, 345)"
  lazy-load
  mode="aspectFill"
  @error="onImageError"
/>
```

- `thumbUrl()` 收敛在 `utils/format.ts`，所有组件统一走它拼接 CDN 参数（与后端 10.4.2 动态裁剪约定一致）
- swiper 中的图片 `lazy-load` 不生效：首屏轮播图数量需控制（≤5 张）并使用小尺寸规格
- 首页金刚区/Banner 等首屏图使用 CDN 缓存 + 本地 storage 记录上次内容，实现"先出旧图再换新图"（避免白屏）

### 14.5 内存优化

**功能点**：
- 页面销毁时清理定时器
- 大列表数据释放
- 图片缓存管理
- 事件监听清理

**技术要点**：
```javascript
onUnmounted(() => {
  // 清理定时器
  if (timer) clearInterval(timer)
  // 清理事件监听
  uni.off('someEvent', handler)
  // 释放大对象
  largeData.value = null
})
```

---

## 十五、多平台适配

### 15.1 微信/支付宝/抖音差异处理

#### 15.1.1 登录差异

| 平台 | 登录方式 | 注意事项 |
|------|----------|----------|
| 微信 | `wx.login` + 手机号授权 | 需 `open-type="getPhoneNumber"` |
| 支付宝 | `my.login` | 需授权获取用户信息 |
| 抖音 | `tt.login` | 需配置抖音小程序 |

#### 15.1.2 支付差异

| 平台 | 支付方式 | 调用方式 |
|------|----------|----------|
| 微信 | 微信支付 | `uni.requestPayment({ provider: 'wxpay' })` |
| 支付宝 | 支付宝支付 | `uni.requestPayment({ provider: 'alipay' })` |
| 抖音 | 抖音支付 | `uni.requestPayment({ provider: 'toutiao' })` |

> 三端支付参数（签名字段、字段命名）差异由**后端多平台适配层**统一返回（后端 11.2 `MiniprogramAdapter.prepay`），前端只负责透传给 `uni.requestPayment`，不做任何签名与字段转换；支付能力开关读取后端渠道配置（后端 11.4.1），某渠道关闭支付时前端隐藏对应入口而非报错。

#### 15.1.3 分享差异

| 平台 | 分享方式 | 限制 |
|------|----------|------|
| 微信 | 好友/朋友圈 | 朋友圈需手动 |
| 支付宝 | 好友/生活圈 | 限制较多 |
| 抖音 | 好友/抖音内 | 限制较多 |

### 15.2 条件编译

**技术要点**：
```xml
<!-- #ifdef MP-WEIXIN -->
<button open-type="getPhoneNumber" @getphonenumber="onGetPhoneNumber">
  微信一键登录
</button>
<!-- #endif -->

<!-- #ifdef MP-ALIPAY -->
<button open-type="getAuthorize" scope='userInfo' @getAuthorize="onGetAuthorize">
  支付宝一键登录
</button>
<!-- #endif -->
```

```javascript
// JS 条件编译
// #ifdef MP-WEIXIN
const loginProvider = 'weixin'
// #endif

// #ifdef MP-ALIPAY
const loginProvider = 'alipay'
// #endif
```

### 15.3 统一封装

**技术要点**：
```javascript
// utils/platform.js
class PlatformAdapter {
  constructor() {
    this.platform = this.detectPlatform()
    // 渠道编码与后端 channel_config 表保持一致（后端 1.4.1）
    this.channelCode = {
      weixin: 'miniprogram_wechat',
      alipay: 'miniprogram_alipay',
      toutiao: 'miniprogram_douyin',
      baidu: 'miniprogram_baidu'
    }[this.platform]
  }

  detectPlatform() {
    // #ifdef MP-WEIXIN
    return 'weixin'
    // #endif
    // #ifdef MP-ALIPAY
    return 'alipay'
    // #endif
    // #ifdef MP-TOUTIAO
    return 'toutiao'
    // #endif
  }

  async login(scene) {
    const code = await this.getCode()
    // channelCode 与 1.4.2 的 utils/platform.ts 保持同一来源；platform 参数改用后端契约字段 channelCode
    // shareCode 与 2.2 的 silentLogin 同源（utils/share.ts 暂存的待归因分享码），shareUserId 由服务端反查
    return api.user.silentLogin({
      code, channelCode: this.channelCode, scene: scene || '', shareCode: getPendingShareCode()
    })
  }

  async payment(params) {
    return new Promise((resolve, reject) => {
      uni.requestPayment({
        // 后端 payment/prepay 返回值已含 provider 时优先使用，否则按平台映射兜底
        provider: params.provider || this.getPayProvider(),
        ...params,
        success: () => resolve(),
        fail: (err) => reject(new Error(String(err?.errMsg || '').includes('cancel') ? 'PAY_CANCEL' : 'PAY_FAILED'))
      })
    })
  }

  getPayProvider() {
    const map = {
      weixin: 'wxpay',
      alipay: 'alipay',
      toutiao: 'toutiao',
      baidu: 'baidupay'
    }
    return map[this.platform]
  }
}
```

### 15.4 样式适配

**功能点**：
- 安全区域适配
- 状态栏高度适配
- 导航栏高度适配
- 底部 TabBar 适配

**技术要点**：
```scss
// 安全区域
.safe-area-bottom {
  padding-bottom: constant(safe-area-inset-bottom);
  padding-bottom: env(safe-area-inset-bottom);
}

// 状态栏高度
.status-bar-height {
  height: var(--status-bar-height);
}
```

---

## 十六、开发排期

> **排期前提**：
> - 人力按 **2 名前端**估算（与后端计划 13.2 人员配置一致），各阶段标注的天数为"人天合计"，两人并行时阶段四→阶段一实际日历时间约为人天的一半并预留联调缓冲
> - 后端整体排期 24 周（后端 13.1），前端全部开发约 11 周即可完成——**前端排期以接口契约为准、不等待后端实现**：阶段一产出接口 Mock（基于 18.4 契约对照表与后端已定义的响应结构），阶段二/三与后端对应服务并行开发，后端服务就绪后按模块替换 Mock 联调
> - 关键联调里程碑：认证（后端阶段一完成后）、订单/支付/商品（后端阶段二完成后，约第 10 周）、营销/分享/消息（后端阶段三完成后，约第 16 周）；**P0 一期（微信渠道首发）以核心交易闭环为准、可在第 13 周全量**（前提：营销/分享/消息功能开关关闭），支付宝/抖音多渠道与营销功能全量受后端 24 周排期约束，前端完成不等于功能可开启
> - **容量口径**：前端无独立容量假设；全链路容量（千万 DAU / 网关入口 60 万 QPS / 订单峰值 5 万笔/s）以 `plans/00-架构总纲.md` 为准，本文仅约定前端侧性能预算（见 14 章与 16.6 验收标准）
> - **P0 一期 = 微信渠道首发**：阶段一~四以微信端上线为目标（后端阶段一、二第 10 周就绪支撑核心交易链路提审）；营销/分享/消息模块随版本发布但功能开关关闭，待后端阶段三（约第 16 周）联调后灰度开启（对应后端 11.4 features 配置）

### 16.1 阶段一：基础架构（2周）

| 任务 | 工期 | 负责人 |
|------|------|--------|
| 项目初始化 | 1天 | 前端 |
| 目录结构搭建 | 1天 | 前端 |
| 请求封装 | 1天 | 前端 |
| 状态管理搭建 | 1天 | 前端 |
| 路由配置 | 1天 | 前端 |
| 公共组件开发 | 3天 | 前端 |
| 多平台适配 | 2天 | 前端 |

**验收标准**：三端（微信/支付宝/抖音）条件编译构建通过；`PlatformAdapter`（15.3）与请求封装（含签名 timestamp+nonce、错误码 18.3）联 Mock 自测通过；CI 体积卡点（14.1）生效。
**依赖与前置**：18.4 契约对照表冻结为 Mock 基线；无需后端环境。

### 16.2 阶段二：核心功能（4周）

| 任务 | 工期 | 负责人 |
|------|------|--------|
| 启动与初始化 | 2天 | 前端 |
| 登录与账户 | 3天 | 前端 |
| 首页 | 4天 | 前端 |
| 商品列表与详情 | 5天 | 前端 |
| 搜索与筛选 | 3天 | 前端 |
| 购物车 | 3天 | 前端 |
| 下单结算 | 5天 | 前端 |

**验收标准**：登录（含 `phoneCode` 绑定、`shareCode` 归因）走通微信真机；商品→购物车→下单→`/payment/prepay`→支付回调查询全链路 Mock 自测通过；主包 < 1.5MB。
**依赖与前置**：后端阶段一（W4 认证联调环境）、阶段二（W8~W10 订单/支付）接口就绪后按模块切真；微信支付商户号（后端 W8 前）。

### 16.3 阶段三：扩展功能（3周）

| 任务 | 工期 | 负责人 |
|------|------|--------|
| 订单管理 | 4天 | 前端 |
| 个人中心 | 3天 | 前端 |
| 消息通知 | 2天 | 前端 |
| 客服系统 | 3天 | 前端 |
| 分享裂变 | 4天 | 前端 |
| 地址管理 | 2天 | 前端 |

**验收标准**：订单 8 值状态机各 Tab 映射正确；订阅消息授权链路（11.3）真机验证；分享海报走后端 `getwxacodeunlimit`（scene ≤ 32 字符、携 `shareCode`）；拼团/砍价按 `bargainRecordId` 契约联调。
**依赖与前置**：消息/客服/分享/拼团/砍价模块依赖后端阶段三（约第 16 周）——**先 Mock + 功能开关关闭**，联调通过后灰度开启；客服依赖 wss 合法域名配置。

### 16.4 阶段四：优化与上线（2周）

| 任务 | 工期 | 负责人 |
|------|------|--------|
| 性能优化 | 3天 | 前端 |
| 多平台联调与回归（支付宝/抖音渠道，区别于阶段一的适配层搭建） | 3天 | 前端 |
| 测试与修复 | 3天 | 前端 |
| 提审准备与灰度发布 | 1天 | 前端 |
| 审核等待与全量上线 | 1天+审核周期 | 前端 |

**验收标准**：性能预算达标（16.6）；真机 Top 机型回归通过；体验版提审材料（类目资质、隐私协议、虚拟支付表述自查）齐备；灰度（分阶段发布）→ 全量无 P0/P1 缺陷。
**依赖与前置**：后端阶段二全量就绪 + 提审支撑（审核环境/体验账号，后端 13.3 W10）；微信**类目资质与营业执照**（审核 1~7 天、预留 1~2 周缓冲）；微信支付商户号已开通。

> **审核周期风险**：微信审核通常 1~7 天且可能多次驳回（电商类目需提供营业执照与类目资质、虚拟支付表述、隐私协议完整性），必须预留 1~2 周审核缓冲；建议"提审 → 灰度（分阶段发布）→ 全量"而非压缩为 2 天。

### 16.5 P0 一期（微信渠道首发）按周任务分解

> 前端工期合计约 11 周（两人并行时按阶段推进），下表为**按周可交付任务**（含提审/发布节点），与后端 13.3 按周分解对齐联调节点。

| 周次 | 可交付任务 | 联调/发布节点 | 依赖与前置 |
|------|-----------|--------------|-----------|
| W1 | 工程初始化、目录结构、请求封装（含签名与错误码） | 契约 Mock（18.4）发布到团队 | 18.4 契约 v1 冻结 |
| W2 | 状态管理、路由、公共组件（EcImage/EcButton/EcPopup 等） | 组件库 Demo 页可演示 | 无 |
| W3 | 多平台适配层（`PlatformAdapter`）、启动页与初始化（2.1） | 三端条件编译构建通过 | 无 |
| W4 | 授权登录（2.2/3.1）、手机号 `phoneCode` 绑定（3.2） | **后端 W4 认证就绪 → 登录切真**（后端 13.3 M1） | 后端阶段一；手机号快速验证组件权限 |
| W5 | 首页（4 章）、商品列表（5.1） | 商品接口 Mock 转真（后端 W5） | 后端 core-product 联调环境 |
| W6 | 商品详情（5.2）、图片查看、SKU 选择（5.4）、收藏 | 详情/加购真机回归 | 后端 W5/W7 |
| W7 | 搜索与筛选（6 章）、搜索历史本地/远端同步 | 搜索联想 < 50ms 真机抽测 | 后端 W6（ES） |
| W8 | 购物车（7 章）、优惠券展示与结算校验 | 购物车跨端同步验证 | 后端 W7；游客合并策略确认 |
| W9 | 下单结算（8 章，orderToken 防改价）、支付方式与 `prepay` | **后端 W8 订单就绪 → 下单切真** | 后端 W8；微信支付商户号（后端 W8 前申请） |
| W10 | 订单管理（9 章）、个人中心（10 章）、地址管理 | 订单 8 值状态机全 Tab 回归；售后走通 | 后端 W9/W10（支付/售后） |
| W11 | 性能优化（14 章）、体验版打包、提审材料自查 | **提审节点：提交微信审核**（审核环境+体验账号由后端 13.3 W10 提供） | 类目资质/营业执照、隐私协议终稿 |
| W12 | 审核等待（预留 1~2 周缓冲，含驳回整改）、修复回归 | **灰度节点：分阶段发布 1%→10%→50%** | 审核通过；后端监控告警（12.2）就绪 |
| W13 | 灰度观察（崩溃率/支付成功率）、修复 | **全量上线节点** | 灰度指标达标（16.6） |
| W16+ | 消息/客服/分享/拼团/砍价灰度开启 | 营销功能开关开启（后端 11.4 features） | 后端阶段三联调通过（后端 M3） |

### 16.6 里程碑验收标准

| 里程碑 | 时间 | 功能验收 | 性能/体验验收 | 审核/合规验收 |
|--------|------|---------|--------------|--------------|
| M1 登录可用 | W4 | 微信授权、`phoneCode` 绑定、`shareCode` 归因走通真机 | 冷启动首屏 < 1.5s（中端机） | 隐私授权前置弹窗（2.1）合规 |
| M2 核心购物闭环 | W9 | 商品→购物车→下单→支付→订单查询全链路真机通过 | 下单页 RT P99 < 500ms（客户端埋点） | — |
| M3 功能齐备 | W10 | 订单/售后/个人中心/地址全部可用 | 主包 < 1.5MB、总包 < 20MB、CI 卡点通过 | — |
| M4 提审通过 | W11~W12 | 体验版全量回归无 P0/P1 缺陷 | 列表滑动无白屏（懒加载/骨架屏生效） | **微信审核通过**（类目资质、隐私协议、虚拟支付表述自查） |
| M5 全量上线 | W13 | 灰度期无崩溃级缺陷，回滚预案演练 | 灰度期崩溃率 < 0.1%、支付成功率 ≥ 后端 12.2.1 阈值（95%） | 上线后首周合规巡检 |
| M6 营销灰度 | W16+ | 消息/客服/分享/拼团/砍价联调后灰度开启 | 订阅消息授权转化不因弹窗过频下降 | 诱导分享/砍价合规评审（后端 14.2） |

### 16.7 依赖与前置总表

| 依赖类型 | 依赖项 | 需求时间 | 未就绪时的处理 |
|---------|--------|---------|---------------|
| 后端接口 | 阶段一认证（W4）、阶段二商品/订单/支付（W5~W10）、阶段三营销/消息/分享（W16） | 见 16.5 | 契约 Mock 先行（18.4），联调窗口切真；营销模块功能开关关闭 |
| 微信资质 | 小程序主体、**电商类目资质与营业执照**、隐私协议 | 提审（W11）前 | 无资质不得提审，预留 1~2 周缓冲 |
| 微信支付 | **微信支付商户号 + API 证书** | W9 联调前（后端 W8 前申请） | 沙箱联调，禁止带病提审 |
| 域名配置 | request/uploadFile/socket 合法域名（HTTPS/WSS） | W4 联调前 | 开发者工具"不校验合法域名"过渡，不可用于提审 |
| 订阅消息 | 消息模板申请与类目审核 | W10 前 | 无模板时降级站内信（11.2 存量口径） |
| 支付宝/抖音 | 企业认证、类目、支付通道 | 阶段四多平台联调 | 微信先行，渠道 feature 开关关闭（与后端 11.4 一致） |
| 容量口径 | 无前端侧独立容量依赖 | — | 全链路容量以 `plans/00-架构总纲.md` 为准（千万 DAU，不做单元化/异地多活） |

---

## 十七、风险与应对

### 17.1 技术风险

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 多平台差异 | 开发成本高 | 统一封装、条件编译 |
| 包体积限制 | 功能受限 | 分包加载、资源优化、CI 体积卡点 |
| 性能问题 | 用户体验差 | 等高列表虚拟滚动、懒加载、瀑布流分页 |
| 微信审核 | 上线延迟 | 提前了解审核规范，预留 1~2 周审核缓冲 |
| 隐私合规被驳回/下架 | 无法上线/已上线被下架 | manifest 配齐隐私指引与权限声明，敏感接口前置同意（2.1） |
| 类目与支付资质 | 无法开通支付 | 提前办理营业执照、类目资质与微信支付商户号 |
| 平台 API 变更（getPhoneNumber 等） | 功能失效 | 平台能力收敛在 utils/platform.ts，关注官方公告（与后端 14.1 风险同步） |

### 17.2 业务风险

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 需求变更 | 工期延长 | 敏捷开发、迭代交付 |
| 接口不稳定 | 功能异常 | 接口Mock、异常处理 |
| 第三方服务 | 功能不可用 | 备用方案、降级处理 |

---

## 十八、附录

### 18.1 接口规范

```javascript
// 统一响应格式（与后端所有接口一致）
{
  code: 0,        // 0-成功，非0-失败
  message: 'success',
  data: {},
  timestamp: 1697000000000
}

// 统一错误码
const ERROR_CODES = {
  SUCCESS: 0,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  SERVER_ERROR: 500,
  // 业务错误码
  INSUFFICIENT_STOCK: 10001,
  ORDER_NOT_FOUND: 10002,
  COUPON_EXPIRED: 10003
}
```

**统一请求约定**（由 `utils/request.ts` 统一实现，业务代码不得绕过）：

```javascript
// 请求拦截器统一注入：后端绝大多数接口要求 channelCode（后端 3.1.2/4.1.2 等）
uni.addInterceptor('request', {
  invoke(args) {
    args.header = {
      ...args.header,
      'Authorization': `Bearer ${getToken()}`,        // 未登录时不携带
      'X-Channel-Code': platform.channelCode,          // miniprogram_wechat / miniprogram_alipay / miniprogram_douyin
      'X-Device-Id': getDeviceId(),                    // 游客匿名设备标识（见 3.3）
      'X-Timestamp': Date.now()                        // 为可能的签名/防重放预留
    }
  }
})

// 响应拦截器统一处理
// 1) code === 0       -> 直接返回 data
// 2) code === 401     -> 先调 /auth/token/refresh 静默续期并重放原请求，刷新失败才清态并跳登录页
// 3) 网络错误 / 5xx    -> 仅对幂等 GET 自动重试（指数退避，最多 2 次）；写接口（下单/支付/评价）绝不自动重试
// 4) 业务错误码        -> 抛出 BizError，由页面决定 toast 或模块内展示，禁止全局吞错
```

### 18.2 埋点规范

```javascript
// 页面浏览埋点
const trackPageView = (pageName, params) => {
  analytics.track('page_view', {
    page_name: pageName,
    ...params,
    timestamp: Date.now()
  })
}

// 点击埋点
const trackClick = (elementId, params) => {
  analytics.track('click', {
    element_id: elementId,
    ...params,
    timestamp: Date.now()
  })
}
```

### 18.3 监控告警

```javascript
// 错误监控
uni.onError((error) => {
  monitor.reportError({
    type: 'js_error',
    message: error.message,
    stack: error.stack,
    page: currentPage
  })
})

// 性能监控：uni.getPerformance 仅微信小程序端可用，需条件编译避免其他端报错
// #ifdef MP-WEIXIN
const performanceObserver = uni.getPerformance()
const observer = performanceObserver.createObserver((entryList) => {
  // 上报性能指标（渲染耗时、脚本耗时、页面切换耗时）
})
// entryTypes 取值受渲染层限制：普通 WebView 模式下 'render'/'script' 不保证触发，
// 建议以 navigation（页面切换）+ 自定义埋点（首屏数据到达时间）为主指标
observer.observe({ entryTypes: ['navigation', 'render', 'script'] })
// #endif

// 其他端降级：以 App.onLaunch/onShow 时间差 + 首屏接口耗时埋点估算启动性能
```

### 18.4 接口契约对照表（与《小程序客户端后端开发计划》对齐）

> 用途：前端开发前先按本表与后端确认契约；标注"⚠ 后端待补充"的接口必须在后端对应服务排期内补齐（否则前端以 Mock 先行）。

**已对齐接口**（以下为两端契约一致或已在后端计划中明确定义）：

| 前端模块 | 前端 api 层函数 | 后端契约 | 备注 |
|---------|----------------|---------|------|
| 登录 | `user.silentLogin` | `POST /api/v1/miniprogram/auth/wechat/login` | 需带 channelCode/scene/shareCode（shareUserId 由服务端反查，后端 2.1.2） |
| Token 刷新 | `user.refreshToken` | `POST /api/v1/miniprogram/auth/token/refresh` | request 层 401 时自动调用 |
| 手机号绑定 | `user.bindPhone`（微信） | `POST /api/v1/miniprogram/auth/phone/wechat/bind` | 参数 `{ phoneCode, channelCode }`（后端 2.2.2；`e.detail.code`，不用 encryptedData/iv） |
| 短信验证码 | `sms.send` | `POST /api/v1/miniprogram/auth/phone/sms/send` | |
| 账号合并 | `user.mergeAccount` | `POST /api/v1/miniprogram/auth/account/merge` | |
| 首页聚合 | `product.home` | `GET /api/v1/miniprogram/product/home` | 返回 bannerList/categoryList/productList/hasMore |
| 分类商品列表 | `product.categoryList` | `GET /api/v1/miniprogram/product/category/list` | sortField/priceMin/priceMax |
| 商品搜索 | `product.search` | `GET /api/v1/miniprogram/product/search` | |
| 搜索建议 | `search.suggest` | `GET /api/v1/miniprogram/product/search/suggestion` | |
| 商品详情 | `product.detail` | `GET /api/v1/miniprogram/product/detail` | 参数 spuId/skuId/shareUserId |
| 推荐 | `product.recommend` | `GET /api/v1/miniprogram/product/recommend` | |
| 订单预览 | `order.preview` | `POST /api/v1/miniprogram/order/preview` | 返回费用明细 + orderToken |
| 订单提交 | `order.submit` | `POST /api/v1/miniprogram/order/submit` | 幂等，携带 orderToken |
| 订单列表/详情 | `order.list` / `order.detail` | `GET /order/list`、`GET /order/detail` | status 枚举见 9.1 |
| 取消/确认收货 | `order.cancel` / `order.confirmReceive` | `POST /order/cancel`、`POST /order/confirm` | |
| 物流轨迹 | `logistics.track` | `GET /api/v1/miniprogram/logistics/track` | ⚠ 缺坐标/快递员字段（见 9.3） |
| 个人信息 | `user.getProfile` / `user.updateProfile` | `GET /user/profile`、`POST /user/profile/update` | |
| 地址 | `address.list/add` | `GET /user/address/list`、`POST /user/address/add` | 微信导入另有 `POST /user/address/wechat/import` 已对齐 |
| 收藏 | `favorite.add/list` | `POST /user/favorite/add`、`GET /user/favorite/list` | |
| 足迹 | `footprint.list` | `GET /user/footprint/list` | |
| 积分 | `points.detail` / `points.sign` | `GET /user/points/detail`、`POST /user/points/sign` | |
| 支付 | `payment.prepay` | `POST /api/v1/miniprogram/payment/prepay` | 只收 orderId（channelCode 拦截器注入），金额/openId 严禁客户端传入（后端 8.1.2）；返回各端 payParams，前端透传 |
| 退款 | `payment.refund` | `POST /api/v1/miniprogram/payment/refund` | |
| 分享 | `share.generateLink` / `share.parseLink` | `POST /share/link/generate`、`GET /share/link/parse` | shareCode 机制（见 13.1） |
| 分享海报 | `share.generatePoster` | `POST /api/v1/miniprogram/share/poster/generate` | 异步 + CDN |
| 拼团 | `groupBuy.start/join/detail` | `POST /group/create`、`POST /group/join`、`GET /group/detail` | |
| 砍价 | `bargain.activityList` / `bargain.start/help/progress` | `GET /bargain/activity/list`、`POST /bargain/create`、`POST /bargain/help`、`GET /bargain/progress` | 统一用 `bargainRecordId`（砍价单 ID，后端 7.5.2）；help 需带 shareCode 归因 |
| 客服 | `cs.message.send/list` | `POST /cs/message/send`、`GET /cs/message/list` | WebSocket 见 12.1 |
| 工单 | `ticket.create/detail` | `POST /cs/ticket/create`、`GET /cs/ticket/detail` | |
| 订阅消息授权 | `message.subscribe` | `POST /api/v1/miniprogram/message/subscribe/request` | 客户端仅上报授权结果 |

**⚠ 后端待补充接口**（前端已引用、后端计划中缺失或未定义小程序端契约，需后端在对应服务阶段补齐）：

| 前端模块 | 前端已引用 | 缺口说明 | 建议后端归属 |
|---------|-----------|---------|------------|
| 购物车 | `cart.list/add/update/delete/batchDelete/moveFavorite/count` | 后端计划**无购物车服务章节**（无表结构与 API），为下单链路核心依赖 | 共享购物车服务（或会员中心扩展） |
| 优惠券 | `coupon.list/detail/receive/available` | 后端仅在共享服务表中提及"营销中心"，未定义小程序端 API | 营销中心 |
| 评价 | `review.create/list/append` | 后端仅有共享"评价服务"一句话，无小程序端 API（见 9.6） | 评价服务 |
| 售后 | `afterSale.apply/detail/progress` | 后端仅有退款接口，无售后单状态机 API（见 9.5） | 订单中心扩展 |
| 秒杀 | `seckill.sessions/buy` | 首页入口已设计，后端营销中心未定义秒杀 API（见 4.6 落地页说明） | 营销中心 |
| 热门搜索词 | `search.hotWords` | 后端 3.3.1 提及热门搜索词但未给出接口 | 搜索服务 |
| 取消收藏 | `favorite.delete` | 后端只定义了 add/list | 会员中心 |
| 地址解析 | `address.parse` | 后端 5.2.4 描述了"地址智能识别"能力但未定义 API | 会员中心 |
| 消息中心 | `message.list/read/delete/unreadCount` | 后端仅有客服消息（cs），无站内信（系统/订单/营销消息）接口（见 11.1） | 消息推送服务 |
| 全局配置 | `config.getGlobalConfig` | 启动依赖的全局开关/配置接口未定义 | 配置中心 |
| 登出 | `user.logout` | JWT 主动失效接口未定义（见 10.7） | 认证服务 |
| 支付状态查询 | `order.payStatus` | 支付轮询确认依赖（见 8.3） | 支付中心 |
| 订单状态数量 | `order.countByStatus` | 个人中心订单角标依赖（见 10.1） | 订单中心 |
| 上传凭证 | `common.getUploadToken` | 头像/晒图/售后凭证上传依赖（见 5.1.3 后端"先获取凭证再上传 OSS"） | 基础服务 |

### 18.5 二期范围（本期不做，避免范围蔓延）

电子发票、积分商城完整交易链路、直播带货、优惠券领取中心（独立页）、多语言/国际化、青少年模式、无障碍适老化改造——列入二期规划，本期仅保留入口占位或不露出。

---

## 审查记录

### v1.0 → v1.1 修改记录

**一致性（与《miniprogram-backend.md》接口契约对齐）**：
- [1] 登录接口契约不一致：前端 `silentLogin({ code })` 未传后端必填的 `channelCode/scene/shareUserId` → 2.2 重写 silentLogin，补充渠道与分享归因参数
- [2] 请求层缺 channelCode/设备标识统一注入 → 18.1 新增"统一请求约定"（拦截器注入 `X-Channel-Code`/`X-Device-Id`/`X-Timestamp`）
- [3] 手机号绑定用旧版 `encryptedData/iv`，后端契约为新版 `{ code, channelCode }` → 2.2 改为 `e.detail.code`，并说明 code 一次性语义
- [4] 商品 ID 参数不一致：前端 `id/inviter` vs 后端 `spuId/shareUserId` → 5.2/5.6/13.1 统一为 spuId/shareUserId/shareCode
- [5] 分页参数不一致：`page` vs 后端 `pageNum` → 4.2/5.1 统一为 pageNum
- [6] 排序枚举不一致：`sortBy: default/price/sales/rating` vs 后端 `sortField: comprehensive/sales/price/new` → 5.1 对齐
- [7] 支付接口不一致：前端 `payment.prepay({orderId})` vs 后端 `POST /payment/create`（含 channelCode/paymentType/openId/totalAmount）→ 8.3 重写并对齐
- [8] 详情页字段不一致（images/detail/reviews vs 后端 mainImages/detailHtml/reviewSummary）→ 5.2 模板对齐
- [9] 首页聚合接口未利用：后端 `GET /product/home` 一次返回 banner+分类+商品 → 4.2 下拉刷新改为复用聚合接口
- [10] 结算页"运费计算接口"后端不存在（运费由 `POST /order/preview` 返回）→ 8.1 修正数据依赖，补充 orderToken 关键流程
- [11] 订单状态枚举不对齐（前端 Tab 缺"待评价"，后端缺"已取消/售后"）→ 9.1 增加状态枚举映射表并标注后端待补枚举
- [12] 分享链接用明文 `inviter=userId`，与后端 shareCode 机制不一致且可被伪造刷邀请 → 13.1 改为 `/share/link/generate` + `/share/link/parse`，归因延迟到注册/首单时
- [13] 海报生成与后端重复：后端 7.2 已有服务端渲染+CDN，前端却本地 canvas → 5.6/13.2 改为优先后端接口、本地 canvas 2d 降级
- [14] 前端引用但后端未定义的接口（购物车/优惠券/评价/售后/秒杀/站内信/登出/上传凭证等 15+ 项）→ 新增 18.4 接口契约对照表，逐项标注"已对齐/后端待补充"及归属服务
- [15] 物流地图所需坐标/快递员字段后端 track 响应未提供 → 9.3 增加缺失字段降级说明（v-if 隐藏，不阻断整页）

**内部一致性/缺陷修复**：
- [16] 8.3 支付失败文案乱码"支付未成功ession" → 修正为"支付未成功，是否重试？"
- [17] 8.3 支付失败用 `navigateTo` 跳 tabBar 页（购物车/订单列表）会静默失败 → 改为 `switchTab`
- [18] 支付结果只信任客户端 success/fail 回调不可靠 → 8.3 改为跳订单详情后轮询服务端支付状态（payPending）
- [19] 提交订单无防重复点击（连点重复下单）→ 8.3 增加 submitting 禁用 + orderToken 幂等
- [20] 购物车全选未排除失效商品 → 7.1 引入 validItems 计算属性
- [21] 购物车总价浮点累加精度问题 → 7.1 改为"分"为单位整数计算，并声明预估价仅供展示、以预览接口为准
- [22] 加购前先查库存的 TOCTOU 竞态写法 → 7.4 删除预检，服务端强校验 + 错误码提示
- [23] SKU 匹配用 `Object.values().join('_')` 依赖键序且规格值含 `_` 会错配 → 5.4 改为按 specValueIds 集合匹配
- [24] 退出登录只清本地 token，JWT 未失效 → 10.7 增加服务端登出调用 + store 重置
- [25] 验证码倒计时定时器未在组件卸载时清理（内存泄漏）→ 3.2 增加 onUnmounted 清理
- [26] 启动预加载未判登录态，游客态 user/cart 请求必 401 → 2.1/14.2 改为条件预取 + allSettled 降级
- [27] 目录结构主包/分包矛盾（1.2 标"商品详情（主包）" vs 14.1 分包含商品详情）→ 1.2 统一为分包并说明分享直达
- [28] `useRequest.ts` 与 `utils/request.ts` 职责重叠未界定 → 1.2 新增分层职责与调用方向说明
- [29] `EcPrice` 与 `PriceDisplay` 职责重叠未界定 → 1.2 注释说明分工（原子金额 vs 卡片价格区）
- [30] `EcErrorBoundary` 命名误导（小程序无 React 式错误边界）→ 1.2 改注释为"全局错误兜底页（Vue errorHandler + App onError）"
- [31] 搜索联想 debounce 未处理在途请求竞态（旧响应覆盖新结果）→ 6.1 增加请求序号守卫
- [32] 评价模块整体缺失（9.4 确认收货后跳转 `/pages/order/review` 但无该页面章节）→ 新增 9.6 商品评价章节
- [33] 秒杀仅在首页有入口、无落地页章节 → 4.3 补充落地页说明（分包、服务端时间基准、抢购状态机）
- [34] `vite.config.js` 与 TS 项目不符 → 1.2 改为 `vite.config.ts`

**小程序特性/平台适配**：
- [35] 1.4.2 平台适配代码三处缺陷：运行时 `process.env` 判断（应编译期条件编译）、Promise 无 reject 兜底会永久 pending、直接调用 my/tt/swan 全局对象 → 重写为条件编译 + `uni.login` 统一 API + 取消态识别
- [36] 1.4.2 与 15.3 存在两套互相矛盾的平台封装实现 → 15.3 PlatformAdapter 的 login/payment 与 1.4.2 统一（channelCode 同源、provider 兜底）
- [37] 1.4.1 平台差异表错误：支付宝/抖音订阅消息标"无"（后端 11.1 确认支持，漏开发风险）；支付宝无 openCustomerServiceChat → 修正表格并加 iOS 虚拟支付限制注记
- [38] 模板消息已废弃（微信 2020 年停用）且客户端不应持有发送权限（可被刷消息）→ 11.2 重写为"站内消息 + 服务端触发"，补充触发节点对照表
- [39] 订阅消息调用时机缺失（必须用户点击同步调用栈；一次性订阅一授权一发；电商拿不到长期订阅）→ 11.3 补充时机规范与代码示例
- [40] 客服 WebSocket 兼容性缺失（wss 域名、心跳重连、切后台回收、离线补齐）→ 12.1 补充通道选型与实现要点
- [41] 隐私合规要求缺失（2023 年起强制：manifest 隐私指引、`__usePrivacyCheck__`、敏感接口前置同意）→ 2.1 新增合规要点
- [42] 包体积限制表述不准（微信主包 2MB/总包 20MB 硬限制，且无 CI 卡点、无分包预下载）→ 14.1 修正目标 + 补 preloadRule + CI 体积卡点
- [43] 虚拟列表按固定高度实现且被当作瀑布流方案 → 14.3 限定适用范围（等高列表），瀑布流改分页+懒加载，补充 overscan
- [44] canvas 用旧版 createCanvasContext 且从 context 解构 width/height（永远 undefined）→ 13.2 改 canvas 2d（node + dpr）
- [45] 保存图片/预览长按未处理相册授权拒绝 → 5.3/13.2 补充 openSetting 引导；longPressActions 微信特有条件编译
- [46] `uni.getSystemInfoSync` 已废弃 → 2.1 改用 `getWindowInfo`
- [47] swiper 中 lazy-load 不生效等图片细节 → 14.4 补充 CDN 裁剪参数统一入口（thumbUrl）
- [48] 朋友圈分享需 `onShareTimeline`，5.6 未覆盖 → 5.6 补充
- [49] 分享组件内无法直接 onShareAppMessage → 5.6 注明 button open-type="share"
- [50] `uni.getPerformance` 仅微信端可用、render/script 入口受渲染层限制 → 18.3 条件编译 + 降级埋点
- [51] 游客模式设计缺失（匿名 UUID、登录合并、拦截时机）→ 3.3 补充实现要点

**可实施性/其他**：
- [52] 排期与后端 24 周排期脱节、无联调里程碑、人力假设不明、"多平台适配"在阶段一/四重复 → 16 增加排期前提（2 名前端、Mock 先行、联调里程碑），16.4 改为"多平台联调与回归"并补充审核缓冲（1~2 周，驳回风险）
- [53] 17.1 风险表缺隐私合规/类目支付资质/API 变更风险 → 补充三行
- [54] 6.4 本地搜索历史与后端 Redis 历史双份未说明关系 → 补充同步策略
- [55] 二期范围未声明（易范围蔓延）→ 新增 18.5

**审查结论**：原计划功能覆盖全面、结构清晰，但存在三类系统性问题：① 与后端计划的接口契约大量不一致或缺口（前后端两份计划各自演进，无对照表）；② 部分示例代码存在小程序平台性错误（tabBar 跳转、canvas 版本、订阅消息时机、模板消息废弃）；③ 低估了审核合规与联调依赖对排期的影响。本次修订以"契约对齐 + 平台正确性 + 降级兜底"为主线，新增 18.4 契约对照表作为两端开发的单一事实来源。

## 跨文件同步记录

### v1.1 → v1.2

> 来源：《miniprogram-backend.md》审查遗留待办（后端计划已修订至 v1.2，本文件同步对齐其契约变更）。

- [S1] 来源 `miniprogram-backend.md` 2.2.2（契约说明） → 手机号绑定入参由 `{ code, channelCode }` 改为 `{ phoneCode, channelCode }`（`encryptedData/iv` 解密方式 2021 年已被微信废弃，`phoneCode` 即 `e.detail.code`）→ 修改 2.2 `getPhoneNumber`（改传 `phoneCode` 并更新注释）、2.2 要点说明（一次性凭证表述）、3.2 `bindPhone`（加注：短信验证码 code ≠ phoneCode，走 `/auth/phone/sms/bind`）、18.4 手机号绑定行
- [S2] 来源 `miniprogram-backend.md` 2.1.2/7.1.2（分享归因统一） → 登录归因参数 `shareUserId` → `shareCode`（`shareUserId` 由服务端根据 shareCode 反查，不接受客户端直接传入，防伪造刷邀请；原 `inviter` 明文参数已在 v1.1 清除，本次清理残留的 `shareUserId` 上报）→ 修改 2.2 `silentLogin`（`shareCode: getPendingShareCode()`）、13.1 落地页（暂存 shareCode 本身）、15.3 `PlatformAdapter.login`（补 shareCode）、18.4 登录行备注；5.6 卡片分享路径保留 `shareUserId`（与后端 3.2.2 `shareInfo.path` 契约一致）但加注说明与 shareCode 链路的分工
- [S3] 来源 `miniprogram-backend.md` 6.1/6.4（模板消息存量兼容口径） → 模板消息已停止下发、客户端无发送权限，与后端口径统一为"存量兼容 + 站内信兜底"→ 修改 11.2 修正说明：新增"与后端口径一致"段落（灰度期存量由服务端下发、新功能一律订阅消息 + 站内信、无授权自动降级站内信）
- [S4] 来源 `miniprogram-backend.md` 11.1（平台差异对比表） → 前端 1.4.1 平台差异表与后端对齐（登录换取方式、唯一标识、支付调起凭证、分享能力差异、订阅消息能力、模板消息存量口径、手机号验证 API 不同名、小程序码支持度）→ 重写 1.4.1 表格（新增唯一标识/模板消息/小程序码三行，修正手机号验证与地址导入差异，百度列按"以官方文档为准"标注）并更新表格修正说明
- [S5] 来源 `miniprogram-backend.md` 7.5.2（砍价参数与接口补充） → ① `bargainId` 实为用户砍价单 ID（`bargain_record.id`），统一改名 `bargainRecordId`；② `bargain.start` 补 `skuId` 入参、响应取 `bargainRecordId`；③ `bargain.help` 改传 `{ bargainRecordId, shareCode }` 并按 `finished` 判断成功；④ 补充"砍价活动列表"接口契约（`GET /bargain/activity/list`）供 `BargainList` 组件 → 修改 13.4 技术要点与数据依赖、18.4 砍价行（拆出 activityList/help/progress）
- [S6] 来源 `miniprogram-backend.md` 8.1.2（预支付接口统一） → `/payment/create` 统一为 `/payment/prepay`，请求**只收 orderId**（channelCode 由拦截器注入；金额/openId/paymentType 严禁客户端传入，防"改价支付"与越权）→ 修改 8.3 支付代码与数据依赖、1.2 目录注释、15.1.2 适配层说明（`createPayment` → `prepay`）、15.3 `PlatformAdapter.payment` 注释、18.4 支付行

*文档版本：v1.3*
*审查日期：2026-10-11（v1.1）；跨文件同步：2026-10-11（v1.2，与 miniprogram-backend.md v1.2 对齐）；实施手册修订：2026-10-11（v1.3）*

---

## 实施手册修订记录

### v1.2 → v1.3

> 背景：项目目标由"1 亿在线"**降级为千万 DAU**；`architecture/` 12 份文档删除、压缩为 `plans/00-架构总纲.md`。本次将本文修订为实施手册口径，并清理亿级残留。

- [I1] **容量口径核查**：全文 grep 核查无 `architecture/`、`00-conflict-register`、`RocketMQ`、`OceanBase`、`单元化`、`异地多活`、`1亿/亿级`、`300 万条/s`、`1024` 分片等残留引用；本文本无独立容量假设，16 章排期前提新增容量口径声明——全链路容量（千万 DAU / 峰值在线 200 万 / 网关入口 60 万 QPS / 订单峰值 5 万笔/s）以 `plans/00-架构总纲.md` 为唯一事实来源，不做单元化/异地多活/OceanBase（历史亿级口径见 git 历史 v1）
- [I2] **排期升级为实施手册（16 章）**：
  - 16.1~16.4 每阶段就地补齐**验收标准 + 依赖与前置**（勿重复：阶段任务表保留原样，验收/依赖为增量）
  - 新增 **16.5 P0 一期（微信渠道首发）按周任务分解**：W1~W13 + W16+ 每周可交付任务、联调/发布节点（W11 提审 → W12 灰度 → W13 全量 → W16+ 营销灰度）、依赖与前置，与后端 13.3 按周分解对齐
  - 新增 **16.6 里程碑验收标准**：M1~M6 功能 + 性能/体验 + 审核/合规三维可验证条件（含崩溃率 < 0.1%、主包 < 1.5MB、微信审核通过等）
  - 新增 **16.7 依赖与前置总表**：后端接口依赖状态（阶段一 W4 / 阶段二 W5~W10 / 阶段三 W16）、微信类目资质与营业执照、微信支付商户号 + API 证书、合法域名、订阅消息模板、支付宝/抖音资质、容量口径归属
- [I3] **P0 一期定义**：明确 P0 一期 = 微信渠道首发（阶段一~四），营销/分享/消息模块功能开关关闭、待后端阶段三（约第 16 周）联调后灰度开启（与后端 11.4 features 配置一致），避免 24 周排期被撑爆
- [I4] **保留不改**：`/payment/prepay`、`phoneCode`、`shareCode`、`bargainRecordId`、订单 8 值状态机引用、接口契约对照表（18.4）——本文引用一律保持原契约（`pay_deadline`、缓存 Key 四段式仅在后端文档引用，本次核查前端无相关残留）
- [I5] 文档版本 v1.2 → v1.3


---

*文档版本：v1.3*
*最后更新：2026-10-12（文档一致性终审，见 00-架构总纲.md 文末《文档一致性终审记录》）*
