# PC客户端前端开发计划

> 项目：大型电商平台 PC 客户端前端
> 技术栈：Electron + React + TypeScript + Vite + Zustand
> 文档版本：v1.2（实施手册版）
> 更新日期：2026-10-11
> 关联文档：pc-client-backend.md（接口契约以 后端计划 为准，API 统一前缀 `/api/v1`，统一响应 `{code, message, data}`）；`plans/00-架构总纲.md`（容量与可用性口径：千万 DAU / 峰值在线 200 万 / 网关入口 60 万 QPS / MySQL 16 库 64 表 / RabbitMQ 不迁移；原亿级口径见 git 历史 v1 中 architecture/）

## 一、项目架构与工程化

### 1.1 技术选型（Electron vs Tauri）

| 维度 | Electron | Tauri | 推荐 |
|------|----------|-------|------|
| 打包体积 | 约80-120MB（内置Chromium） | 约3-10MB（系统WebView） | **Tauri** |
| 内存占用 | 较高（150-300MB基线） | 较低（50-100MB基线） | **Tauri** |
| 启动速度 | 较慢（冷启动2-4s） | 快（冷启动<1s） | **Tauri** |
| 生态成熟度 | 非常成熟，社区庞大 | 快速增长，生态完善中 | Electron |
| 跨平台一致性 | 完美（自带浏览器内核） | 依赖系统WebView，Windows WebView2/macOS WebKit/Linux WebKitGTK差异显著 | Electron |
| 安全性 | 需显式加固（CSP、contextIsolation、导航拦截），Chromium本身为多进程隔离架构 | 默认更安全（Rust后端最小攻击面，需配置项更少） | Tauri |
| 与Web端代码复用 | 高（直接复用浏览器代码） | 高（但需注意系统WebView差异） | 平手 |
| 开发体验 | 成熟稳定，调试工具完善 | 优秀（Rust工具链），但调试工具仍在完善 | Electron |
| 原生API覆盖 | 非常全面 | 主要场景覆盖，部分API需自行实现 | Electron |
| 社区解决方案 | 丰富，常见问题均有案例 | 增长中，部分问题需自行解决 | Electron |

**推荐方案**：对于淘宝级别的复杂电商应用，选择 **Electron + React + TypeScript**，理由：
- 业务复杂度高，需要Chromium内核保证跨平台一致性，避免系统WebView差异带来的兼容性问题
- Electron生态成熟，社区解决方案丰富，遇到问题容易找到答案
- 与浏览器端共享代码更容易，无需处理WebView差异
- 原生API覆盖全面，支付、生物识别、文件系统等桌面能力开箱即用
- 团队学习成本相对较低，调试工具（Chrome DevTools）成熟稳定
- 需承诺跟进 Electron/Chromium 安全更新（高危CVE 72小时内热修复发布，见16.1）
- 体积和内存可通过 electron-builder 的优化配置（见1.2节和13.1节）在可接受范围内

**备选方案**：如果对体积极其敏感且团队有Rust经验，可选Tauri + React + TypeScript，但需针对系统WebView做充分测试（特别是Windows WebView2版本差异、macOS WebKit行为差异），且部分原生功能可能需要自行实现桥接。关于Electron体积优化的具体配置请参考1.2节和13.1节。

**技术选型备注**：若未来需将应用轻量化或拓展到资源受限设备，建议采用渐进式迁移策略：先通过code splitting和资源压缩降低Electron体积，必要时再考虑向Tauri迁移，届时需进行系统WebView兼容性适配工作。

### 1.2 目录结构

```
pc-client/
├── src/                          # 渲染进程代码
│   ├── app/                      # 应用入口
│   │   ├── App.tsx               # 根组件
│   │   ├── router.tsx            # 路由配置
│   │   └── store/                # 全局状态管理
│   │       ├── auth.ts           # 认证状态
│   │       ├── cart.ts           # 购物车状态
│   │       └── ...
│   ├── pages/                    # 页面级组件
│   │   ├── login/                # 登录页
│   │   ├── home/                 # 首页
│   │   ├── product/              # 商品相关
│   │   ├── search/               # 搜索相关
│   │   ├── cart/                 # 购物车
│   │   ├── checkout/             # 下单结算
│   │   ├── order/                # 订单管理
│   │   ├── profile/              # 个人中心
│   │   ├── message/              # 消息通知
│   │   └── customer-service/     # 客服系统
│   ├── components/               # 通用组件
│   │   ├── layout/               # 布局组件
│   │   ├── common/               # 基础组件
│   │   ├── business/             # 业务组件
│   │   └── feedback/             # 反馈组件
│   ├── hooks/                    # 自定义Hooks
│   ├── services/                 # API服务层
│   ├── utils/                    # 工具函数
│   ├── types/                    # TypeScript类型定义
│   ├── assets/                   # 静态资源
│   ├── styles/                   # 全局样式
│   └── constants/                # 常量定义
├── main/                         # 主进程代码
│   ├── index.ts                  # 主进程入口（含单实例锁）
│   ├── window/                   # 窗口管理
│   ├── updater/                  # 自动更新（对接后端更新服务）
│   ├── tray/                     # 系统托盘
│   ├── menu/                     # 菜单管理
│   ├── ipc/                      # IPC通信（含Zod通道参数校验）
│   ├── storage/                  # 本地存储（safeStorage封装）
│   ├── logger/                   # 日志与崩溃上报（对接后端crash服务）
│   ├── protocol/                 # deep link协议注册与第二实例唤醒
│   └── security/                 # 安全配置（CSP、导航拦截）
├── preload/                      # 预加载脚本
│   └── index.ts                  # 桥接主进程与渲染进程
├── native/                       # 原生模块（生物识别等，按需引入）
├── shared/                       # 主进程与渲染进程共享代码
│   ├── types/                    # 共享类型（统一API响应类型、枚举常量、IPC通道契约）
│   ├── constants/                # 共享常量（API前缀、平台常量）
│   └── utils/                    # 共享工具函数（格式化、校验、请求签名）
├── build/                        # 构建配置
│   ├── electron/                 # Electron构建配置
│   └── icons/                    # 应用图标
├── dist/                         # 构建输出
├── resources/                    # 额外资源
├── tests/                        # 测试文件（unit/ + e2e/，e2e用Playwright Electron）
├── package.json
├── tsconfig.json
├── vite.config.ts
├── electron-builder.yml          # 打包配置
└── README.md
```

### 1.3 主进程与渲染进程

#### 主进程职责
- 窗口生命周期管理（创建、销毁、多窗口）
- 系统托盘图标与上下文菜单
- 应用菜单栏（原生菜单）
- 自动更新服务（检查、下载、安装）
- 本地文件读写（缓存、日志、用户数据）
- 全局快捷键注册
- 系统级通知（调用OS原生通知）
- IPC消息路由（接收渲染进程请求，分发处理，通道级Zod参数校验）
- 协议处理（deep link，如 `ecommerce://product/123`；setAsDefaultProtocolClient注册 + 第二实例argv透传）
- 单实例锁（requestSingleInstanceLock；第二实例启动时唤醒已有窗口并透传深链参数）
- 日志与崩溃上报（日志落盘轮转，minidump与JS异常上报后端crash服务，见1.6节）

#### 渲染进程职责
- UI渲染与用户交互
- 路由管理与页面切换
- 状态管理（Zustand/Redux）
- API请求（通过HTTP/HTTPS与后端通信）
- 本地缓存操作（通过IndexedDB/localStorage，或调用主进程）

#### 进程间通信（IPC）设计
```typescript
// 通道命名规范：invoke 为渲染进程→主进程请求（返回 Promise）；event 为主进程→渲染进程推送
// 所有 invoke 通道在主进程 handler 侧使用 Zod 校验入参
interface IPCInvokeChannels {
  // 窗口控制
  'window:minimize': () => void;
  'window:maximize': () => void;
  'window:close': () => void;
  'window:hide-to-tray': () => void;
  'window:get-state': () => Promise<'maximized' | 'minimized' | 'normal' | 'fullscreen'>;

  // 自动更新
  'updater:check': () => Promise<{ hasUpdate: boolean; version?: string; force: boolean }>;
  'updater:download': () => Promise<void>;
  'updater:install': () => Promise<void>;

  // 本地存储（键名限定命名空间 settings:*/cache:*/token:*，值序列化后 ≤ 1MB）
  // 敏感数据（token）不经此通道，由主进程持有，渲染进程只触发"登录/登出"语义
  'storage:get': (ns: string, key: string) => Promise<unknown>;
  'storage:set': (ns: string, key: string, value: unknown) => Promise<void>;
  'storage:remove': (ns: string, key: string) => Promise<void>;

  // 系统能力
  'system:get-platform': () => Promise<'win32' | 'darwin' | 'linux'>;
  'system:get-version': () => Promise<string>;
  'system:get-device-info': () => Promise<DeviceInfo>;    // 登录上报device_info用（见2.1）
  'system:open-external': (url: string) => Promise<boolean>; // 仅http/https + 域名白名单
  'system:sign-request': (body: string) => Promise<string>;  // HMAC-SHA256请求签名（密钥存主进程）
  'system:set-auto-launch': (enabled: boolean) => Promise<boolean>; // 开机自启（14.5）

  // 对话框与文件
  'dialog:select-file': (options: FileDialogOptions) => Promise<string[]>; // 头像/评价图片
  'dialog:save-image': (url: string) => Promise<string | null>;            // 拖拽/另存为

  // 剪贴板
  'clipboard:write-text': (text: string) => Promise<void>;
  'clipboard:read-text': () => Promise<string>;
}

// 主进程 → 渲染进程单向事件
interface IPCEventChannels {
  'updater:update-available': (info: { version: string; force: boolean }) => void;
  'updater:download-progress': (p: { percent: number }) => void;
  'updater:update-downloaded': (info: { version: string }) => void;
  'updater:error': (err: { message: string }) => void;
  'tray:badge-update': (count: number) => void;  // 未读角标（平台差异见14.1）
  'notification:click': (route: string) => void;  // 桌面通知点击跳转
  'deep-link:open': (route: string) => void;      // ecommerce://product/123
  'theme:changed': (isDark: boolean) => void;     // nativeTheme变化
  'second-instance': (argv: string[]) => void;    // 第二实例唤醒
  'window:focus-changed': (focused: boolean) => void;
}
```

#### preload暴露约定（contextBridge）
```typescript
// preload/index.ts：仅暴露白名单方法，不暴露 ipcRenderer 本体
import { contextBridge, ipcRenderer } from 'electron';

const allowedEvents = [
  'updater:update-available', 'updater:download-progress', 'updater:update-downloaded',
  'updater:error', 'tray:badge-update', 'notification:click', 'deep-link:open',
  'theme:changed', 'second-instance', 'window:focus-changed',
];

contextBridge.exposeInMainWorld('desktop', {
  invoke: (channel: string, ...args: unknown[]) => ipcRenderer.invoke(channel, ...args),
  on: (channel: string, listener: (...args: unknown[]) => void) => {
    if (!allowedEvents.includes(channel)) return () => {};
    const cb = (_e: unknown, ...a: unknown[]) => listener(...a);
    ipcRenderer.on(channel, cb);
    return () => ipcRenderer.removeListener(channel, cb); // 返回解绑函数，防泄漏
  },
});
```

#### 安全策略
- 启用 `contextIsolation: true`（上下文隔离）、`sandbox: true`、`webviewTag: false`；禁用 `nodeIntegration`/`nodeIntegrationInWorker`/`enableRemoteModule`
- 通过 preload 脚本暴露有限的API给渲染进程
- 使用 `contextBridge` 仅暴露必需方法，禁止直接暴露 `ipcRenderer` 本体
- 对所有IPC通道做入参校验（主进程 handler 侧统一使用 Zod 校验）
- CSP示例（域名按实际部署填写白名单，必须包含 API 域与 CDN 图片域，否则商品图片与接口请求会被拦截）：
  `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https://cdn.example.com; connect-src 'self' https://api.example.com wss://ws.example.com; font-src 'self' data:; object-src 'none'; frame-ancestors 'none'`
- `will-navigate` 阻止非白名单跳转；`setWindowOpenHandler` 统一拦截 `window.open`（非白名单一律 `shell.openExternal`）；`permission-request-handler` 拒绝定位/摄像头等非必要权限
- 对外部链接使用系统浏览器打开，仅允许 http/https 协议 + 域名白名单（拦截 `file://`、`javascript:`）
- 渲染进程不持有 API 密钥：请求签名密钥（HMAC-SHA256，见后端2.3.4）存主进程，经 `system:sign-request` 代签，防止 XSS 窃取
- Token 与敏感数据使用 `safeStorage`（Windows DPAPI / macOS Keychain）加密落盘，不写入明文 localStorage/IndexedDB
- 维护对外暴露能力清单，安全评审定期审查 IPC 通道与 preload API

### 1.4 自动更新机制

#### 更新流程
```
应用启动 → 后台静默检查更新 → 发现新版本 → 提示用户 → 下载更新包 → 下载完成 → 提示安装 → 用户确认 → 退出并安装
```

#### 更新类型
| 类型 | 说明 | 用户感知 |
|------|------|----------|
| 强制更新 | 重大Bug修复、安全漏洞 | 强制弹窗，必须更新才能使用 |
| 推荐更新 | 新功能、体验优化 | 提示更新，可稍后 |
| 静默更新 | 热修复（资源文件） | 无感知，后台完成 |

#### 实现方案
- 使用 `electron-updater`（基于 Squirrel.Windows / Squirrel.Mac）作为客户端更新基线
- 与后端更新服务（见 pc-client-backend.md 第八章）对接：版本检查/灰度命中/强制标记以服务端 `GET /api/v1/client/version/check` 为准，下载与安装由 electron-updater 承担
- **协议适配（v1.1 修正）**：electron-updater 默认消费 `latest.yml`（Windows）/`latest-mac.yml`（macOS）元数据，后端契约为自定义 JSON，需二选一：
  - 方案A（推荐）：后端更新服务同时产出 electron-updater 元数据文件（latest.yml/blockmap + 安装包）托管 CDN，客户端 feedURL 指向 `https://cdn.example.com/updates/{channel}/`；灰度与强制标记通过元数据或启动时拉取 client_config 组合判断
  - 方案B：自研 Updater 直接调用 `version/check`，自行实现断点续传、bsdiff 差量合并（后端8.2）与安装器调用；工作量大，仅在方案A无法满足灰度粒度时采用
  - 差量机制必须与后端约定唯一方案：electron-updater 原生 blockmap 差量 与 后端 bsdiff 增量包 **二选一**，禁止双套并存
- 更新包完整性与来源校验：下载完成后校验 SHA-256（后端 `file_hash`）+ 验证代码签名（Windows Authenticode / macOS 公证），校验失败立即删除并回滚
- 强制更新：`is_force_update=true` 时启动即拦截主界面（全屏遮罩无关闭按钮），完成更新前禁止业务操作，倒计时 60s 后自动 `quitAndInstall`
- 支持多通道（stable/beta/alpha），通道与后端 `gray_config` 对齐；灰度命中判定由服务端返回，客户端不自建灰度逻辑
- 更新失败：保留上一版本完整包（与后端8.4一致），自动回滚；连续失败3次引导用户手动下载

#### 关键代码结构
```typescript
// main/updater/index.ts
class AutoUpdater {
  private feedURL: string;
  private channel: 'stable' | 'beta' | 'alpha';

  async checkForUpdates(): Promise<{ hasUpdate: boolean; force: boolean }>; // 对接latest.yml或/client/version/check
  downloadUpdate(): void;        // 断点续传，进度经 IPC 事件推送渲染进程
  verifyAndInstall(): Promise<void>;  // SHA-256 + 代码签名校验通过后 quitAndInstall
  on(event: 'update-available' | 'download-progress' | 'update-downloaded' | 'error', cb): void;
}
```

#### Electron打包优化配置（v1.1修正：原文为错误的JSON结构，此处为正确YAML）
```yaml
# electron-builder.yml
appId: com.example.easyshopping
productName: EasyShopping
compression: maximum          # 最大压缩
asar: true
files:                        # 白名单精简，等效prune
  - dist/**
  - "!**/*.map"               # 排除生产环境sourcemap
win:
  artifactName: "${productName}-${version}-win-${arch}.${ext}"
  target: [nsis, zip]         # zip供electron-updater差量使用
  verifyUpdateCodeSignature: true
nsis:
  oneClick: false
  allowToChangeInstallationDirectory: true
mac:
  artifactName: "${productName}-${version}-mac-${arch}.${ext}"
  target: [dmg, zip]          # Squirrel.Mac需要zip
  category: public.app-category.shopping
  hardenedRuntime: true
  entitlements: build/electron/entitlements.mac.plist
  notarize: true              # 公证，避免Gatekeeper拦截
linux:
  artifactName: "${productName}-${version}-linux-${arch}.${ext}"
  target: [AppImage]
directories:
  output: ./dist/releases
  buildResources: build/electron
publish:
  provider: generic
  url: https://cdn.example.com/updates/${channel}/   # 与方案A对齐
```

> 代码签名证书（Windows EV/OV、Apple Developer ID + 公证）为发布前置条件：缺失会导致 SmartScreen/Gatekeeper 拦截，并使 electron-updater 校验失败导致无法自动更新。

### 1.5 API请求层设计（v1.1新增）

#### 统一请求客户端
- 所有业务 API 走渲染进程 `services/` 层（axios 实例），不走 IPC；仅系统能力走 IPC（见1.3）
- baseURL 按环境切换：dev（vite proxy 代理规避CORS）/ staging / production（`https://api.example.com/api/v1`）
- 请求拦截器统一注入：`Authorization: Bearer <access_token>`、`device_id`、`client_version`、`os_type`、`timestamp`
- **请求签名（对接后端2.3.4）**：`HMAC-SHA256(device_id + timestamp + body_hash)`，签名密钥存主进程 safeStorage，渲染进程经 `system:sign-request` IPC 代签（避免密钥进渲染进程被 XSS 窃取）；时间戳偏差 > 5 分钟被服务端拒绝，客户端需在本地时钟漂移时校准重试

#### 统一响应处理
- 服务端统一格式 `{code, message, data}`（后端16.1），响应拦截器解析：
  - `code == 0` → 返回 `data`
  - `code == 1002`（未登录）→ 触发 token 刷新后重放原请求；**并发请求只触发一次刷新（single-flight）**，其余请求挂起等待；刷新失败（refresh token 过期/被吊销）→ 清理会话跳登录页
  - `code == 1004/1005`（签名错误/重放拒绝）→ 校准时间戳后重试一次
  - `code == 5003`（需二次验证）→ 弹出二次验证流程（见2.1）
  - 其他业务错误 → 抛业务异常，由页面层展示 `message`，同时上报日志
- 幂等敏感操作（下单、支付、领券）客户端生成 `request_id`，重试复用同一 id（见7.1）
- 超时与重试：普通 GET 超时 10s、可重试2次（指数退避）；POST 支付/下单 **不自动重试**，由用户确认

#### WebSocket统一客户端（对接后端第七章推送服务）
- 单一 WSS 连接（`wss://ws.example.com`）+ topic 订阅模式（messages / order-updates / logistics / customer-service），**不建立多条独立 WS 连接**
- 连接鉴权：连接建立后首帧携带 access_token；token 刷新后需重连
- 心跳：30s ping/pong，60s 无 pong 判定断连
- 重连：指数退避（1s→2s→4s→…最大60s），网络恢复（powerMonitor resume）立即重连
- 消息可靠性：每条消息带全局递增 `seq`，客户端缓存已处理 seq 去重；重连后按 seq 补偿拉取遗漏消息（调用相应 REST 接口兜底，如 `GET /api/v1/notifications`）
- 消息仅做"事件通知"，数据本体以 REST 拉取为准（推送只带 id/类型，不带大 payload）

### 1.6 日志与崩溃上报（v1.1新增，对接后端第十一章）

#### 日志
- 主进程：`electron-log` 落盘轮转（单文件 10MB × 保留 5 份），路径 `{userData}/logs/`
- 渲染进程：console 代理转发主进程统一收集；业务错误（接口失败、未捕获异常）必须打日志
- 脱敏：日志中手机号、token、密码一律脱敏（与后端16.4规范一致）

#### 崩溃上报
- JS 异常：渲染进程 `window.onerror` / `unhandledrejection` → 主进程 → 批量上报 `POST /api/v1/crash/upload`（对接后端11.1）
- 原生崩溃：`crashReporter.start({ uploadToServer: false })` 收集 minidump，本地暂存后按需上传；同时监听 `render-process-gone` 重建窗口并上报
- 附加上下文：os_type、os_version、client_version、device_id、页面路由、自定义面包屑（最近50步操作）
- 上报策略：本地暂存队列（SQLite），网络恢复后批量补传；同一 crash_hash 本地聚合去重，避免风暴
- 用户可在设置中关闭崩溃上报（隐私开关，默认开启匿名上报）
- 符号表（Windows pdb / macOS dSYM）随 CI 发布归档，供后端符号化解析（后端11.2）

### 1.7 配置下发与功能开关（v1.1新增，对接后端 client_config 服务）

- 启动时拉取客户端配置（对接后端1.2配置下发服务，`client_config` 表）：功能开关、促销推送开关、更新通道、灰度实验分组
- **本地缓存兜底**：最近一次配置持久化（30分钟TTL），启动先用缓存再后台刷新；接口失败不阻塞启动
- 关键开关示例：`promo_push_enabled`、`customer_service_enabled`、`update_channel`、`ab_home_feed_v2`
- 配置变更通过 WS 推送即时生效，无需重启的配置热更新；影响主进程行为的（如更新通道）下次启动生效

---

## 二、登录与账户

### 2.1 登录页

#### 页面布局
- 左侧：品牌展示区（轮播图、活动广告）
- 右侧：登录表单区

#### 登录方式
| 方式 | 说明 | 优先级 |
|------|------|--------|
| 手机号+密码 | 基础方式，必选 | 1 |
| 手机号+验证码 | 免密登录，适合移动端用户 | 2 |
| 扫码登录 | 使用手机APP扫码，桌面端推荐 | 3 |
| 第三方登录 | 微信/支付宝/Apple ID | 4 |

#### 关键组件
```
LoginPage
├── BrandCarousel          # 品牌轮播
├── LoginForm              # 登录表单
│   ├── PhoneInput         # 手机号输入
│   ├── PasswordInput      # 密码输入（支持显示/隐藏）
│   ├── VerifyCodeInput    # 短信验证码输入
│   ├── CaptchaInput       # 图形验证码（对接后端2.1.2要求，滑块/点选）
│   ├── LoginButton        # 登录按钮（loading状态）
│   └── ForgotPasswordLink # 忘记密码
├── QRCodeLogin            # 扫码登录
│   ├── QRCodeCanvas       # 二维码画布
│   └── RefreshButton      # 刷新二维码
├── ThirdPartyLogin        # 第三方登录
│   ├── WeChatButton       # 微信登录
│   ├── AlipayButton       # 支付宝登录
│   └── AppleButton        # Apple ID登录
└── Agreement              # 用户协议勾选
```

#### 交互细节
- 手机号输入：自动格式化（3-4-4分隔），实时校验格式
- 密码输入：支持显示/隐藏切换，密码强度提示
- 验证码：60s倒计时，防重复发送
- 扫码登录：每2s轮询一次登录状态，二维码有效期2分钟
- 登录失败：错误提示具体原因（密码错误/账号不存在/图形验证码错误/网络异常），错误码映射对齐后端16.2（如 1001 参数错误、5003 需二次验证）
- 新设备二次验证：登录响应 `require_verify=true`（后端2.1.2）时弹出短信/邮箱验证码流程，验证通过后进入主界面
- 登录请求必须携带 `device_info`（device_id、device_name、os_type、os_version、client_version，经 `system:get-device-info` IPC 获取）与图形验证码 `captcha_id`/`captcha_code`

#### 数据依赖（v1.1对齐：登录收敛为单一入口接口，`login_type` 区分方式）
- `POST /api/v1/auth/login` — 统一登录入口（后端2.1.2）
  - `login_type=password`（账号密码；username 支持手机号/邮箱）
  - `login_type=sms`（手机验证码；服务端限频 60s/条、10条/天/手机号）
  - `login_type=qrcode`（扫码登录，需手机APP已登录态授权）
  - `login_type=third_party`（第三方 OAuth：微信/QQ/微博，与后端2.1.1一致）
- **待后端补充的接口**（桌面端需要，后端契约暂缺）：
  - 短信验证码发送接口（登录前置步骤）
  - 扫码登录二维码生成与状态轮询接口
  - 第三方 OAuth 桌面端回调（建议自定义协议 `ecommerce://auth/callback` 落地）

### 2.2 记住密码与自动登录

#### 功能设计
- 登录页提供"记住账号"（默认勾选，仅存手机号/用户名明文可接受）与"自动登录"（存 refresh token）复选项
- **v1.1 修正：不在本地保存密码（明文或密文）**。桌面端自动登录的标准做法是 refresh token 续期（后端2.2已支持30天有效期与轮换），本地保存密码即使加密也风险收益比极差（终端被攻破时加密文件+可派生路径等同于交出密码）
- 下次启动使用存储的 refresh token 静默换取 access token，跳过登录页直接进入主界面
- 退出登录时清除本账户 token 存储；token 被吊销（黑名单/设备解绑）时降级回登录页并提示

#### 安全方案（v1.1重写，与后端2.2.3一致）
- Access Token：仅存内存（渲染进程 store），不落盘
- Refresh Token：主进程经 Electron `safeStorage.encryptString()` 加密后写入 `{userData}/tokens.enc`（Windows 底层为 DPAPI、macOS 为 Keychain、Linux 为 kwallet/gnome-libsecret 或口令兜底），文件权限 600
- 多账户时按 userId 命名空间隔离存储（见2.3）
- 定期轮换：每次续期使用后端返回的新 refresh token 覆盖旧值（后端2.2.2轮换机制）

#### v1.0 方案废弃说明（原"AES-256 + PBKDF2 派生密码加密存储"）
原方案存在以下缺陷，已整体废弃：
1. `deriveKey` 的 salt 每次使用 `getRandomValues` 随机生成 → 每次派生密钥不同，**永远无法解密**（致命错误）
2. 机器指纹（CPU/主板/MAC）无可靠稳定来源（虚拟机、多网卡、权限受限环境不可得），指纹漂移即密钥失效
3. 代码中 `extractable: true` 与注释 `only extractable: false` 自相矛盾，且可导出密钥违背存储加密初衷
4. refresh token 方案泄露面更小：可单独吊销、有有效期、服务端可风控

### 2.3 多账户切换

#### 功能设计
- 主界面顶部显示当前账户头像和昵称
- 点击下拉菜单显示"切换账户"选项
- 弹出账户切换弹窗，列出最近登录过的3-5个账户
- 选择账户后快速切换（无需重新输入密码，使用refresh token）
- 支持"添加账户"进入登录页

#### 关键组件
```
AccountSwitcher
├── AccountTrigger         # 当前账户展示（头像+昵称）
├── AccountDropdown        # 下拉菜单
│   ├── AccountList        # 历史账户列表
│   │   ├── AccountItem    # 单个账户（头像+手机号+最后登录时间）
│   │   └── AddAccount     # 添加新账户
│   └── Logout             # 退出登录
```

#### 数据依赖与切换流程（v1.1对齐：后端无独立"切换账户"接口，复用 refresh 机制）
- 本地存储：最近登录账户列表（头像/昵称/脱敏手机号/token存储索引，不上传服务器）
- `POST /api/v1/auth/refresh` — 切换账户时使用该账户本地保存的 refresh token 换发新 token 对（后端2.2.2支持轮换，旧 token 立即失效）
- `GET /api/v1/devices` — 设备列表（后端2.3.2）；切换时校验设备绑定状态，设备数超限（5台）时引导解绑
- 并发冲突处理：切换前确认无进行中的支付/下单流程；收到登录互踢信号（后端2.3.3可配置）时自动回登录页并提示"该账户已在其他设备登录"

#### 切换流程
1. 用户选择目标账户 → 主进程读取该账户命名空间下加密的 refresh token（safeStorage 解密）
2. 调用 `POST /api/v1/auth/refresh` 换发新 token 对
3. 清空渲染进程全局状态（Zustand store 按 userId 重建）→ 重建 WSS 连接（新 token 鉴权）→ 刷新首页
4. refresh 失败（过期/已吊销）→ 该账户标记"需重新登录"，引导至登录页

### 2.4 生物识别登录（可选）

#### 功能设计
- Windows: Windows Hello（指纹/面部识别）
- macOS: Touch ID
- 首次登录后启用，后续可通过生物识别快速解锁
- 生物识别仅用于本地验证，验证通过后从本地存储读取凭证完成登录

#### 技术方案
- macOS Touch ID：通过原生模块（`native/` 目录）调用 LocalAuthentication 框架，需编译 darwin 专用 native addon
- Windows Hello：Electron 无法直接调用 `Windows.Security.Credentials.UI`（UWP API），可行路径为原生 addon 走 Win32 `VerifyForUser`（Kerberos/Negotiate CNG）或封装 UWP 桥接；**实现成本高，标注为 P2 可选项**，一期降级为"PIN 码/密码快速填充提示"
- 生物识别仅用于本地验证，验证通过后由主进程从 safeStorage 读取凭证完成 refresh token 换发登录
- 生物识别失败计数连续3次后暂时锁定30秒，避免暴力破解
- 生物识别失败时降级为密码输入，并记录失败次数

---

## 三、首页

### 3.1 页面布局

```
HomePage
├── TopNavigation          # 顶部导航栏
│   ├── Logo               # 应用Logo
│   ├── SearchBar          # 搜索框
│   ├── MessageIcon        # 消息中心入口（带未读数角标）
│   └── UserAvatar         # 用户头像（下拉菜单）
├── CategoryNav            # 分类导航栏
│   ├── CategoryItem       # 分类项（图标+文字）
│   └── MoreCategories     # 全部分类（悬浮展开）
├── BannerCarousel         # 轮播图
├── FlashSale              # 限时秒杀模块
│   ├── CountdownTimer     # 倒计时
│   └── ProductGrid        # 商品网格
├── RecommendationFeed     # 个性化推荐
│   ├── FeedTabs           # 推荐/关注/附近
│   └── ProductCard        # 商品卡片（瀑布流）
├── ActivityEntrance       # 活动入口
│   ├── ActivityCard       # 活动卡片
│   └── ActivityList       # 活动列表
└── Footer                 # 底部信息
```

### 3.2 商品推荐

#### 功能设计
- 基于用户浏览/购买历史的个性化推荐
- 支持"猜你喜欢"、"看了又看"、"买了还买"等推荐场景
- 瀑布流布局，无限滚动加载
- 下拉刷新获取最新推荐

#### 关键组件
```
RecommendationFeed
├── FeedTabs              # 推荐切换Tab
├── WaterfallList         # 瀑布流列表
│   └── ProductCard       # 商品卡片
│       ├── ProductImage  # 商品图片（懒加载）
│       ├── ProductTitle  # 商品标题
│       ├── ProductPrice  # 商品价格
│       ├── SalesCount    # 销量
│       └── ShopInfo      # 店铺信息
└── LoadMoreIndicator     # 加载更多指示器
```

#### 交互细节
- 图片懒加载，进入视口才加载
- 无限滚动：距底部200px时触发加载
- 商品卡片悬浮：显示"加入购物车"、"收藏"快捷按钮
- 下拉刷新：获取最新推荐数据

#### 数据依赖
- `POST /api/v1/bff/home` — 首页聚合接口（对接后端12.1.1，一次返回 user/banners/categories/recommendations/unread_count/cart count，减少请求数）
- `GET /api/v1/recommendations?scene=home&page=1&size=20` — 推荐列表分页加载（无限滚动增量拉取）
- 推荐理由：后端暂无 `GET /api/recommendations/reason` 独立接口，一期以 BFF 聚合结果内联字段为准，缺失时隐藏理由文案

### 3.3 活动入口

#### 功能设计
- 首页固定位置展示平台活动（如"双11"、"618"）
- 活动卡片支持配置化（图片、链接、时间）
- 点击跳转到活动页或商品列表
- 活动倒计时显示

#### 数据依赖
- `GET /api/v1/activities?position=home` — 获取首页活动入口（**待后端/运营后台补充**：后端计划未定义活动接口，需营销服务扩展或由 client_config 下发活动位配置）
- `GET /api/v1/activities/:id` — 获取活动详情（同上，待后端补充）

### 3.4 搜索入口

#### 功能设计
- 顶部搜索框，点击跳转到搜索页
- 搜索框下方显示热门搜索词（点击直接搜索）
- 搜索框支持语音输入（使用 Web Speech API `webkitSpeechRecognition`；Chromium 在 Windows/macOS 可用，**Linux 无支持时自动隐藏入口**；识别需联网，失败降级为键盘输入）

#### 关键组件
```
SearchBar
├── Input                 # 搜索输入框
├── VoiceButton           # 语音输入
├── SearchButton          # 搜索按钮
└── HotSearch             # 热门搜索（悬浮下拉）
    ├── HotSearchItem     # 热门搜索词
    └── HistorySearch     # 历史搜索
```

---

## 四、商品浏览

### 4.1 商品列表页

#### 页面布局
```
ProductListPage
├── Breadcrumb            # 面包屑导航
├── FilterBar             # 筛选栏
│   ├── CategoryFilter     # 分类筛选
│   ├── BrandFilter        # 品牌筛选
│   ├── PriceRangeFilter   # 价格区间
│   ├── AttributeFilter    # 属性筛选（如颜色、尺寸）
│   └── MoreFilters        # 更多筛选（展开面板）
├── SortBar               # 排序栏
│   ├── DefaultSort       # 综合排序
│   ├── SalesSort         # 销量优先
│   ├── PriceSort         # 价格排序（升序/降序）
│   └── NewSort           # 新品优先
├── ProductGrid           # 商品网格
│   └── ProductCard       # 商品卡片
├── Pagination            # 分页器
└── BackToTop             # 回到顶部按钮
```

#### 关键组件
```
ProductCard
├── ProductImage          # 商品图片（多图预览）
│   └── ImagePreview      # 悬浮预览
├── ProductTitle          # 商品标题（最多两行，悬浮显示完整）
├── ProductTags           # 商品标签（包邮、正品等）
├── ProductPrice          # 商品价格
│   ├── CurrentPrice      # 现价
│   ├── OriginalPrice     # 原价（划线价）
│   └── DiscountTag       # 折扣标签
├── ProductInfo           # 商品信息
│   ├── SalesCount        # 销量
│   ├── ReviewCount       # 评价数
│   └── ShopInfo          # 店铺信息
└── ProductActions        # 商品操作
    ├── AddToCart         # 加入购物车
    └── Favorite          # 收藏
```

#### 交互细节
- 列表支持网格视图/列表视图切换
- 商品图片支持悬浮放大预览
- 筛选条件可组合，支持多选
- 排序支持切换，记忆用户选择
- 列表滚动时筛选栏吸顶

#### 数据依赖
- `GET /api/v1/products?category_id=1&brand_ids=1&price_min=100&price_max=500&sort=sales_desc&page=1&size=20` — 商品列表（参数与后端3.1.2一致；size 最大50）
- `GET /api/v1/products/category/{id}` / `GET /api/v1/products/brand/{id}` / `GET /api/v1/products/hot` / `GET /api/v1/products/new` — 按类目/品牌/热销/新品筛选（后端3.1.1）
- 筛选条件聚合（`/api/products/filters?category_id=1`）**后端暂无独立接口**：一期从列表响应聚合出可选项，或由 BFF `POST /api/v1/bff/product-detail` 扩展筛选维度，待后端补充
- `POST /api/v1/cart` — 加入购物车（后端五章）
- 收藏：`POST /api/v1/user/favorites` / `DELETE /api/v1/user/favorites/{id}`（后端6.3；v1.0 的 `POST /api/favorites/toggle` 后端不存在，改为显式 add/remove + `GET /api/v1/user/favorites/check` 批量校验已收藏状态）

### 4.2 商品详情页

#### 页面布局
```
ProductDetailPage
├── Breadcrumb            # 面包crumb
├── ProductMain           # 商品主信息区
│   ├── ImageGallery      # 图片画廊
│   │   ├── MainImage     # 主图（支持缩放）
│   │   ├── ThumbnailList # 缩略图列表
│   │   └── ImageZoom     # 图片放大镜
│   ├── ProductInfo       # 商品信息
│   │   ├── Title         # 商品标题
│   │   ├── Subtitle      # 副标题
│   │   ├── Price         # 价格区
│   │   │   ├── CurrentPrice    # 现价
│   │   │   ├── OriginalPrice   # 原价
│   │   │   ├── DiscountTag     # 折扣标签
│   │   │   └── PromotionTags   # 促销标签
│   │   ├── SalesInfo      # 销量信息
│   │   ├── RatingInfo     # 评价信息
│   │   └── DeliveryInfo   # 配送信息
│   └── SkuSelector       # 规格选择器
│       ├── SkuOptions    # 规格选项（颜色、尺寸等）
│       ├── Quantity      # 数量选择器
│       └── SkuInfo       # 选中SKU信息（价格、库存）
├── ProductTabs           # 商品详情Tab
│   ├── DetailTab         # 商品详情（图文描述）
│   ├── SpecificationsTab # 规格参数
│   ├── ReviewsTab        # 用户评价
│   └── AfterSalesTab     # 售后保障
├── RelatedProducts       # 相关推荐
│   ├── SameShopProducts  # 同店推荐
│   └── SimilarProducts   # 相似推荐
└── BottomActionBar       # 底部操作栏
    ├── AddToCart         # 加入购物车
    ├── BuyNow            # 立即购买
    ├── Favorite          # 收藏
    └── Share             # 分享
```

#### 关键组件
```
ImageGallery
├── MainImageContainer    # 主图容器（支持拖拽、缩放）
│   ├── MainImage         # 主图
│   ├── ZoomOverlay       # 放大镜覆盖层
│   └── NavigationArrows  # 左右切换箭头
├── ThumbnailStrip        # 缩略图条
│   └── ThumbnailItem     # 缩略图项
└── VideoPlayer           # 视频预览（如有）
```

```
SkuSelector
├── SkuGroup              # 规格组
│   ├── SkuGroupName      # 规格名（如"颜色"）
│   └── SkuOptions        # 规格选项
│       ├── OptionButton  # 选项按钮（选中/未选中/不可选）
│       └── OptionImage   # 图片选项（如颜色选择）
├── QuantitySelector      # 数量选择器
│   ├── DecreaseButton    # 减少
│   ├── QuantityInput     # 数量输入
│   └── IncreaseButton    # 增加
└── SkuSummary            # SKU摘要
    ├── SelectedSku       # 已选规格
    ├── SkuPrice          # SKU价格
    └── SkuStock          # SKU库存
```

#### 交互细节
- 图片画廊：支持左右切换、缩略图导航、鼠标滚轮缩放、双击放大
- 规格选择：选择不同规格时联动价格、库存、图片
- 数量选择：最小1，最大为库存上限，支持直接输入
- 详情Tab：切换时懒加载内容
- 底部操作栏：滚动时吸底
- 分享：支持复制链接、生成海报、分享到社交平台

#### 数据依赖（v1.1对齐：SKU随详情返回，相关推荐走推荐服务统一场景接口）
- `GET /api/v1/products/{id}` — 商品详情（后端3.2，**已内含 SKU 列表**：规格/价格/库存，无需独立 skus 接口）
- `GET /api/v1/products/{id}/reviews?page=1&size=20` — 评价列表（后端3.3.1，另有 `/reviews/summary` 评分统计）
- `GET /api/v1/recommendations?scene=product_detail&page=1&size=20` — 相关/相似推荐（后端3.5，v1.0 的 `/related`、`/recommendations` 独立接口不存在）
- `GET /api/v1/user/favorites/check?product_ids=1,2` — 批量校验收藏状态（后端6.3）

### 4.3 图片放大

#### 功能设计
- 主图支持鼠标悬浮放大镜效果
- 双击进入全屏看图模式
- 全屏模式下支持左右切换、滚轮缩放、拖拽平移
- 支持键盘操作（方向键切换，ESC退出）

#### 关键组件
```
ImageZoom
├── ZoomContainer         # 缩放容器
│   ├── Image             # 图片（transform: scale + translate）
│   └── ZoomLens          # 放大镜（可选）
├── FullscreenViewer      # 全屏查看器
│   ├── Image             # 当前图片
│   ├── PrevButton        # 上一张
│   ├── NextButton        # 下一张
│   ├── ZoomInButton      # 放大
│   ├── ZoomOutButton     # 缩小
│   └── CloseButton       # 关闭
└── ThumbnailNav          # 缩略图导航
```

### 4.4 收藏功能

#### 功能设计
- 商品卡片和详情页均可收藏
- 收藏时提示选择收藏夹（默认收藏夹 + 自定义收藏夹）
- 已收藏商品显示收藏状态
- 支持批量收藏

#### 数据依赖（v1.1对齐）
- `GET /api/v1/user/favorites?keyword=xxx&order=recent` — 收藏列表（后端6.3；`user_favorite` 表**无收藏夹字段**）
- `POST /api/v1/user/favorites` — 添加收藏（body: product_id）
- `DELETE /api/v1/user/favorites/{id}` — 取消收藏（{id} 为收藏记录ID，非商品ID）
- **收藏夹（folder）为后端缺口**：v1.0 计划的"默认收藏夹+自定义收藏夹、收藏夹分组"需要后端扩展 `user_favorite.folder_id` 字段与收藏夹接口；一期降级为单一收藏列表 + 标签筛选，收藏夹功能与后端排期对齐后开放

---

## 五、搜索与筛选

### 5.1 搜索页

#### 页面布局
```
SearchPage
├── SearchHeader          # 搜索头部
│   ├── SearchInput       # 搜索输入框（自动聚焦）
│   ├── SearchButton      # 搜索按钮
│   └── VoiceSearch       # 语音搜索
├── SearchSuggestions     # 搜索建议（输入时展示）
│   ├── SuggestionItem    # 建议项
│   └── HistoryTags       # 历史搜索标签
├── SearchResults         # 搜索结果
│   ├── ResultCount       # 结果数量
│   ├── FilterBar         # 筛选栏（同商品列表页）
│   ├── SortBar           # 排序栏
│   └── ProductGrid       # 商品网格
└── NoResults             # 无结果
    ├── NoResultTips      # 无结果提示
    └── RecommendProducts # 推荐商品
```

#### 关键组件
```
SearchSuggestions
├── SuggestionList        # 搜索建议列表
│   ├── SuggestionItem    # 建议项（高亮匹配文字）
│   └── SuggestionIcon    # 建议图标
├── HistorySearch         # 历史搜索
│   ├── HistoryTitle      # 标题 + 清空按钮
│   └── HistoryTags       # 历史标签（最多10个）
└── HotSearch             # 热门搜索
    ├── HotTitle          # 标题
    └── HotTags           # 热门标签（带排名）
```

#### 交互细节
- 输入时实时展示搜索建议（防抖300ms）
- 搜索建议支持上下键选择、回车确认
- 搜索历史本地存储，最多10条
- 热门搜索每日更新
- 无结果时展示"您是不是要找"推荐词

#### 数据依赖（v1.1对齐后端3.4）
- `GET /api/v1/search/products?keyword=xxx&page=1&size=20&sort=&category_id=` — 搜索商品（ES全文检索，支持筛选/排序/聚合）
- `GET /api/v1/search/suggest?keyword=xxx` — 搜索建议（**响应内已含 `hot_keywords` 热词**，无独立 hot 接口，热门搜索从同一响应取）
- 搜索历史不走独立接口：本地存储为主（见5.4），开启跨端同步时经 `POST /api/v1/sync/push`（data_types 含 history）上传，服务端去重合并保留50条（后端9.1.1）

### 5.2 筛选器

#### 功能设计
- 支持多维度筛选：分类、品牌、价格、属性、服务
- 筛选条件可组合，支持多选
- 已选条件展示为标签，可单独删除
- 筛选面板支持展开/收起
- 筛选结果实时更新

#### 关键组件
```
FilterPanel
├── FilterGroup            # 筛选组
│   ├── FilterTitle        # 筛选标题
│   ├── FilterOptions      # 筛选选项
│   │   ├── OptionItem     # 选项项（复选框/单选按钮）
│   │   └── OptionLabel    # 选项标签
│   └── ExpandButton       # 展开/收起
├── PriceRange             # 价格区间
│   ├── MinInput           # 最低价
│   ├── MaxInput           # 最高价
│   └── ConfirmButton      # 确认
├── SelectedFilters        # 已选条件
│   └── FilterTag          # 条件标签（可删除）
└── FilterActions          # 筛选操作
    ├── ResetButton        # 重置
    └── ConfirmButton      # 确认
```

### 5.3 排序

#### 排序选项
| 排序方式 | 说明 |
|----------|------|
| 综合排序 | 默认，基于相关性+销量+评价加权 |
| 销量优先 | 按销量降序 |
| 价格升序 | 按价格从低到高 |
| 价格降序 | 按价格从高到低 |
| 新品优先 | 按上架时间降序 |
| 评价优先 | 按好评率降序 |

#### 交互细节
- 排序切换时保持筛选条件
- 排序切换时列表平滑滚动到顶部
- 价格排序支持点击切换升序/降序

### 5.4 搜索历史

#### 功能设计
- 本地存储搜索历史（localStorage/IndexedDB），仅存本地默认关闭上传（隐私保护，与12.1缓存表一致）
- 本地最多保存 **20 条**，超出自动删除最早记录；开启跨端同步后服务端保留 50 条（后端9.1.1），本地仍只展示最近20条
- 支持单条删除和一键清空
- v1.0 的"可选同步 `GET /api/search/history/sync`"接口后端不存在，同步统一走 `POST /api/v1/sync/push` / `POST /api/v1/sync/pull`（后端9.1.2）

#### 数据依赖
- 本地存储：`localStorage.getItem('search_history')`
- 可选同步：`POST /api/v1/sync/push`（data_types=["history"]）— 跨设备同步搜索历史（用户在设置中开启时）

---

## 六、购物车

### 6.1 购物车页

#### 页面布局
```
CartPage
├── CartHeader            # 购物车头部
│   ├── Title             # 标题（含商品数量）
│   └── ManageButton      # 管理按钮
├── CartToolbar           # 工具栏
│   ├── SelectAll         # 全选
│   ├── DeleteSelected    # 删除选中
│   └── MoveToFavorites   # 移入收藏
├── ShopGroupList         # 店铺分组列表
│   └── ShopGroup         # 店铺分组
│       ├── ShopHeader    # 店铺信息
│       │   ├── ShopCheckbox  # 店铺全选
│       │   ├── ShopName      # 店铺名称
│       │   └── ShopCoupon    # 店铺优惠券
│       └── ProductList   # 商品列表
│           └── CartItem  # 购物车项
│               ├── ItemCheckbox    # 商品选择
│               ├── ProductImage    # 商品图片
│               ├── ProductInfo     # 商品信息
│               │   ├── Title       # 商品标题
│               │   ├── SkuInfo     # 规格信息
│               │   └── Price       # 单价
│               ├── QuantitySelector # 数量选择器
│               ├── ItemTotal       # 小计
│               └── ItemActions     # 操作（删除、移入收藏）
├── CartSummary           # 购物车汇总（吸底）
│   ├── SelectAll         # 全选
│   ├── TotalItems        # 已选商品数
│   ├── TotalPrice        # 合计金额
│   └── CheckoutButton    # 结算按钮
└── EmptyCart             # 空购物车
    ├── EmptyImage        # 空状态图片
    ├── EmptyText         # 空状态文案
    └── RecommendProducts # 推荐商品
```

#### 关键组件
```
CartItem
├── ItemCheckbox          # 商品选择框
├── ProductImage          # 商品图片
├── ProductInfo           # 商品信息
│   ├── Title             # 商品标题
│   ├── SkuInfo           # 规格信息（颜色、尺寸等）
│   └── StockStatus       # 库存状态（有货/缺货/预售）
├── PriceInfo             # 价格信息
│   ├── UnitPrice         # 单价
│   └── TotalPrice        # 小计
├── QuantitySelector      # 数量选择器
│   ├── DecreaseButton    # 减少
│   ├── QuantityInput     # 数量输入
│   └── IncreaseButton    # 增加
└── ItemActions           # 商品操作
    ├── DeleteButton      # 删除
    └── FavoriteButton    # 移入收藏
```

#### 交互细节
- 店铺分组展示，支持店铺级全选
- 商品数量修改实时更新小计和合计
- 库存不足时数量选择器显示最大可购数量
- 失效商品（下架/无货）单独分组展示
- 已选商品数量和金额实时更新
- 管理模式下支持批量操作（删除、移入收藏）

#### 数据依赖（v1.1对齐后端五章，REST风格）
- `GET /api/v1/cart` — 购物车列表（响应含按店铺分组所需的 shop 信息；**注：后端 `user_cart` 表无 shop_id/价格快照字段，店铺分组与店铺优惠券依赖后端购物车服务扩展，一期可按 SKU 归属店铺前端分组**）
- `POST /api/v1/cart` — 添加商品（body: sku_id, quantity；加购时校验库存）
- `PUT /api/v1/cart/{sku_id}` — 修改数量（v1.0 的 `POST /api/cart/update-quantity` 后端不存在）
- `DELETE /api/v1/cart/{sku_id}` — 删除商品
- `PUT /api/v1/cart/select` — 选中/取消选中（body: sku_ids + is_selected）
- `DELETE /api/v1/cart/clear` — 清空已购买/失效商品
- **后端缺口**：`select-all` 与独立 `summary` 接口不存在；全选为前端批量调用 select（或后端扩展 body 支持 all=true），汇总金额由前端勾选状态本地计算，结算页以 `POST /api/v1/bff/order-confirm` 服务端金额为准（不信任客户端价格，后端4.1）
- 未登录本地购物车：未登录时购物车存本地（IndexedDB），登录后调用批量加购接口合并到服务端（后端5.2），冲突时数量累加、以服务端为准

### 6.2 批量操作

#### 功能设计
- 管理模式下支持批量选择商品
- 批量删除：删除选中商品，需二次确认
- 批量移入收藏：将选中商品移入收藏夹
- 批量加入购物车（从收藏夹/常购清单）

#### 关键组件
```
BatchActions
├── SelectAllCheckbox     # 全选框
├── SelectedCount         # 已选数量
├── DeleteButton          # 批量删除
├── FavoriteButton        # 批量收藏
└── MoveButton            # 批量移动
```

### 6.3 优惠券提示

#### 功能设计
- 店铺分组显示可用优惠券
- 点击"领券"弹窗展示所有可领优惠券
- 结算时自动匹配最优优惠券
- 显示优惠券使用条件（满减门槛）

#### 关键组件
```
CouponBanner
├── CouponTag             # 优惠券标签（满100减20）
├── CouponButton          # 领券按钮
└── CouponModal           # 优惠券弹窗
    ├── AvailableCoupons  # 可领优惠券
    └── ReceivedCoupons   # 已领优惠券
```

#### 数据依赖
- `GET /api/coupons/available?shop_id=xxx` — 获取可领优惠券
- `POST /api/coupons/receive` — 领取优惠券
- `GET /api/coupons/usable?items=xxx` — 获取可用优惠券

### 6.4 库存校验

#### 功能设计
- 加入购物车时校验库存
- 购物车加载时校验所有商品库存状态
- 库存不足商品标记"库存不足"，不可选择
- 商品下架标记"已下架"，不可选择
- 库存变化时实时更新（通过WebSocket推送）

#### 交互细节
- 库存不足时数量选择器禁用增加按钮
- 已选商品失去库存时，合计金额自动扣减
- 结算时再次校验库存，失败则提示具体商品

#### 数据依赖
- 批量操作复用购物车 REST 接口（`PUT/DELETE /api/v1/cart/{sku_id}`、`PUT /api/v1/cart/select`），批量场景由 BFF 或后端扩展批量端点以减少请求数
- **库存推送（v1.1修正）**：后端推送服务未定义 `stock-updates` 独立 WS 通道（后端七章仅定义消息/客服/物流类推送），一期降级为：打开购物车/结算页时 `GET /api/v1/products/{id}` 批量校验 + 每60s 轮询一次价格与库存；`stock-updates` 推送通道作为 P2 需求与后端协商后开放

---

## 七、下单结算

### 7.1 确认订单页

#### 页面布局
```
CheckoutPage
├── AddressSection        # 收货地址
│   ├── AddressCard       # 地址卡片（默认地址/最近使用）
│   │   ├── ReceiverName  # 收件人
│   │   ├── ReceiverPhone # 联系电话
│   │   └── FullAddress   # 完整地址
│   ├── ChangeAddress     # 更换地址
│   └── AddAddress        # 添加地址
├── ProductSection        # 商品信息
│   ├── ShopInfo          # 店铺信息
│   └── ProductList       # 商品列表
│       └── OrderItem     # 订单商品
├── DeliverySection       # 配送方式
│   ├── DeliveryOptions   # 配送选项
│   └── DeliveryTime      # 送达时间
├── PaymentSection        # 支付方式
│   ├── PaymentMethods    # 支付方式列表
│   │   ├── Alipay        # 支付宝
│   │   ├── WeChatPay     # 微信支付
│   │   ├── CreditCard    # 信用卡
│   │   └── Balance       # 余额支付
│   └── PaymentPassword   # 支付密码输入
├── CouponSection         # 优惠券
│   ├── AvailableCoupon   # 可用优惠券
│   └── SelectedCoupon    # 已选优惠券
├── InvoiceSection        # 发票（v1.1新增，对接后端invoice表与下单invoice_info）
│   ├── InvoiceType       # 个人/企业
│   ├── InvoiceTitle      # 发票抬头
│   └── TaxNoInput        # 企业税号
├── OrderSummary          # 订单汇总
│   ├── GoodsTotal        # 商品总额
│   ├── DiscountAmount    # 优惠金额
│   ├── ShippingFee       # 运费
│   └── PayableAmount     # 应付金额
├── RemarkSection         # 订单备注
│   └── RemarkInput       # 备注输入框
└── SubmitBar             # 提交栏（吸底）
    ├── TotalAmount       # 合计金额
    └── SubmitButton      # 提交订单
```

#### 关键组件
```
AddressCard
├── DefaultTag           # 默认地址标签
├── ReceiverInfo         # 收件人信息
│   ├── Name             # 姓名
│   └── Phone            # 电话（部分隐藏）
├── AddressDetail        # 地址详情
├── EditButton           # 编辑
└── DeleteButton         # 删除
```

```
PaymentMethods
├── PaymentMethodList    # 支付方式列表
│   └── PaymentMethodItem # 支付方式项
│       ├── MethodIcon   # 支付方式图标
│       ├── MethodName   # 支付方式名称
│       ├── MethodDesc   # 支付方式描述
│       └── SelectRadio  # 选择按钮
└── PaymentPasswordInput # 支付密码输入
```

#### 交互细节
- 地址选择弹窗：展示所有地址，支持搜索、新增、编辑、删除
- 支付方式选择：单选，切换时更新支付信息
- 优惠券选择弹窗：展示可用优惠券，自动推荐最优券
- 订单备注：可选，最多 **200字**（服务端 remark 字段 VARCHAR(512)，与7.4一致）
- 提交订单：防重复提交（按钮loading + 生成幂等 `request_id`），网络超时重试复用同一 `request_id`（后端4.1幂等校验，5分钟内重复请求返回首次结果）
- 库存二次校验：提交时再次校验库存，失败提示具体商品（错误码2002/2003）

#### 数据依赖（v1.1对齐后端四/六章）
- `GET /api/v1/user/addresses` — 收货地址列表（后端6.2）
- `POST /api/v1/bff/order-confirm` — 结算页聚合（后端12.1.1：商品、优惠、运费、应付金额一次返回）
- `POST /api/v1/orders` — 创建订单（后端4.1，body 必带 `request_id` 幂等键 + `invoice_info`）
- `POST /api/v1/orders/{order_no}/pay` — 发起支付（后端4.2，body: pay_type / payment_password / return_url）
- 支付方式：由支付接口 `pay_type` 枚举下发（后端4.2：支付宝/微信/银联/余额），**无独立 `GET /api/payment-methods` 接口**；前端维护枚举映射表，未知值降级展示
- `GET /api/v1/coupons/usable?order_amount=xxx` — 可用优惠券（**待后端营销服务补充**：后端计划仅在下单接口含 coupon_id 字段，未定义优惠券查询/领取接口，需营销服务扩展）

### 7.2 地址选择

#### 功能设计
- 展示用户所有收货地址
- 默认地址高亮显示
- 支持新增、编辑、删除地址
- 地址搜索（按收件人、电话、地址）
- 地址簿支持设置默认地址

#### 关键组件
```
AddressModal
├── SearchInput          # 地址搜索
├── AddressList          # 地址列表
│   └── AddressItem      # 地址项
│       ├── ReceiverInfo # 收件人信息
│       ├── AddressInfo  # 地址信息
│       ├── DefaultTag   # 默认标签
│       ├── SelectButton # 选择按钮
│       ├── EditButton   # 编辑按钮
│       └── DeleteButton # 删除按钮
└── AddAddressButton     # 添加地址
```

#### 数据依赖（v1.1对齐后端6.2，统一 `/api/v1/user/addresses` 前缀）
- `GET /api/v1/user/addresses` — 获取地址列表
- `POST /api/v1/user/addresses` — 新增地址
- `PUT /api/v1/user/addresses/{id}` — 编辑地址
- `DELETE /api/v1/user/addresses/{id}` — 删除地址
- `PUT /api/v1/user/addresses/{id}/default` — 设置默认地址
- 地图选点：需第三方地图SDK（高德/腾讯），选点回填经纬度 —— **后端 `user_address` 表无 lat/lng 字段**，地图选点为 P2 依赖后端扩展；一期用省市区三级 + 文本详细地址

### 7.3 支付方式

#### 支付方式（v1.1对齐后端4.2）
| 支付方式 | pay_type | 说明 | 限额 |
|----------|----------|------|------|
| 支付宝 | alipay | PC扫码/手机辅助支付 | 单笔5万 |
| 微信支付 | wechat | PC扫码 | 单笔5万 |
| 银联云闪付 | unionpay | 跳转云闪付/扫码 | 单笔10万 |
| 余额支付 | balance | 需支付密码 | 余额上限 |
| 信用卡 | credit_card | 输入卡号支付 | 单笔10万（**P2：后端支付渠道未支持，需支付服务扩展**） |
| 组合支付 | combined | 余额+其他方式 | —（**P2：后端未支持，需支付服务扩展**） |

#### 支付流程
```
选择支付方式 → 点击提交订单 → 创建订单（POST /api/v1/orders, request_id幂等） → 发起支付（POST /api/v1/orders/{order_no}/pay）
      ↓                                                                        ↓
  支付宝/微信/银联：展示二维码，轮询订单支付状态（每3s，直至 pay_deadline 前停止）      支付成功 → 订单详情
      ↓                                                                        ↓
  余额支付：本地弹支付密码框（RSA加密）→ 直接返回结果              支付失败 → 提示原因 → 重新支付/取消订单
```
- PC 扫码支付结果确认：客户端轮询 `GET /api/v1/orders/{order_no}` 直至 `status=已支付` 或超时；页面隐藏时暂停轮询，WS `order-updates` 事件到达时立即停止轮询
- 支付超时按订单 `pay_deadline`（下单时下发的付款截止时间，唯一事实来源，客户端不自定义分钟数；具体时长待产品确认，建议 30 分钟）到期后端自动释放库存（后端4.1延迟队列），客户端收到订单取消事件后提示

#### 安全设计
- 支付密码使用RSA加密传输
- 支付请求签名防篡改
- 支付超时自动取消（按 `pay_deadline`，不写死固定分钟数）
- 支付失败不扣款

### 7.4 订单备注

#### 功能设计
- 每个商品可单独添加备注
- 备注支持常用短语快速选择
- 备注内容仅卖家可见
- 备注字数限制 **200字**（与7.1结算页一致；v1.0 的50字为内部矛盾，已统一）

#### 关键组件
```
RemarkInput
├── Input                 # 备注输入框
├── QuickPhrases          # 常用短语
│   └── PhraseTag         # 短语标签
└── CharCount             # 字数统计
```

---

## 八、订单管理

### 8.1 订单列表页

#### 页面布局
```
OrderListPage
├── OrderTabs             # 订单状态Tab
│   ├── AllOrders         # 全部订单
│   ├── PendingPayment    # 待付款
│   ├── PendingShipment   # 待发货
│   ├── PendingReceipt    # 待收货
│   ├── PendingReview    # 待评价
│   └── AfterSales       # 售后/退款
├── OrderList            # 订单列表
│   └── OrderCard        # 订单卡片
│       ├── OrderHeader  # 订单头部
│       │   ├── ShopName # 店铺名称
│       │   └── OrderStatus # 订单状态
│       ├── ProductList  # 商品列表
│       │   └── OrderItem # 订单商品
│       ├── OrderSummary # 订单汇总
│       │   ├── TotalItems # 商品总数
│       │   └── TotalAmount # 订单总额
│       └── OrderActions # 订单操作
│           ├── PayButton      # 去支付
│           ├── CancelButton   # 取消订单
│           ├── ConfirmButton  # 确认收货
│           ├── ReviewButton   # 去评价
│           └── MoreButton     # 更多操作
└── EmptyOrders          # 空订单
    ├── EmptyImage       # 空状态图片
    ├── EmptyText        # 空状态文案
    └── BrowseButton     # 去逛逛
```

#### 关键组件
```
OrderCard
├── OrderHeader           # 订单头部
│   ├── OrderNumber       # 订单编号（可复制）
│   ├── ShopName          # 店铺名称
│   ├── OrderStatus       # 订单状态（带颜色标签）
│   └── OrderTime         # 下单时间
├── ProductList           # 商品列表
│   └── OrderItem         # 订单商品
│       ├── ProductImage  # 商品图片
│       ├── ProductTitle  # 商品标题
│       ├── SkuInfo       # 规格信息
│       ├── Price         # 单价
│       └── Quantity      # 数量
├── OrderSummary          # 订单汇总
│   ├── GoodsTotal        # 商品总额
│   ├── DiscountAmount    # 优惠金额
│   ├── ShippingFee       # 运费
│   └── TotalAmount       # 实付金额
└── OrderActions          # 订单操作按钮组
```

#### 交互细节
- Tab切换时筛选对应状态订单；Tab与后端状态枚举映射（后端4.3状态机）：
  | 前端Tab | 后端 status |
  |---------|------------|
  | 待付款 | 待支付 |
  | 待发货 | 已支付（未发货） |
  | 待收货 | 已发货 |
  | 待评价 | 已收货（未评价） |
  | 售后/退款 | 退款/退货中 |
  | 全部 | 任意（含已取消/已完成） |
- 订单列表支持下拉刷新、上拉加载更多
- 订单状态实时更新（WS `order-updates` 事件通知，数据以 REST 拉取为准，见1.5）
- 操作按钮根据订单状态动态显示
- 右键订单卡片显示快捷操作菜单（复制订单号、查看详情、再次购买）

#### 数据依赖（v1.1对齐后端4.3，资源键为 order_no 而非数字id）
- `GET /api/v1/orders?status=pending_payment&page=1&size=20` — 订单列表
- `GET /api/v1/orders/{order_no}` — 订单详情
- `POST /api/v1/orders/{order_no}/cancel` — 取消订单
- `POST /api/v1/orders/{order_no}/confirm` — 确认收货
- `DELETE /api/v1/orders/{order_no}` — 删除订单（软删除，仅已完成/已取消订单可删；v1.0 遗漏，v1.1 补充）
- `POST /api/v1/orders/{order_no}/refund` — 申请退款（后端4.3）
- WS: `order-updates` topic — 订单状态变更事件通知

### 8.2 订单详情页

#### 页面布局
```
OrderDetailPage
├── OrderStatus           # 订单状态
│   ├── StatusIcon        # 状态图标
│   ├── StatusText        # 状态文字
│   └── StatusDesc        # 状态描述
├── LogisticsInfo         # 物流信息
│   ├── LogisticsCompany  # 物流公司
│   ├── TrackingNumber    # 运单号
│   └── TrackingTimeline  # 物流轨迹
├── AddressInfo           # 收货地址
│   ├── ReceiverName      # 收件人
│   ├── ReceiverPhone     # 联系电话
│   └── FullAddress       # 完整地址
├── ProductInfo           # 商品信息
│   └── OrderItem         # 订单商品
├── PaymentInfo           # 支付信息
│   ├── PaymentMethod     # 支付方式
│   ├── PaymentTime       # 支付时间
│   └── PaymentAmount     # 支付金额
├── OrderInfo             # 订单信息
│   ├── OrderNumber       # 订单编号
│   ├── OrderTime         # 下单时间
│   ├── PaymentTime       # 支付时间
│   └── Remark            # 订单备注
├── OrderSummary          # 订单汇总
│   ├── GoodsTotal        # 商品总额
│   ├── DiscountAmount    # 优惠金额
│   ├── ShippingFee       # 运费
│   └── TotalAmount       # 实付金额
└── OrderActions          # 订单操作
```

#### 关键组件
```
TrackingTimeline
├── TimelineItem          # 轨迹项
│   ├── TimelineDot       # 时间轴点
│   ├── TimelineContent   # 轨迹内容
│   └── TimelineTime      # 轨迹时间
└── MoreTracking          # 查看更多
```

#### 数据依赖（v1.1对齐后端4.3/4.4）
- `GET /api/v1/orders/{order_no}` — 订单详情（含发票信息 invoice，对接后端invoice表）
- `GET /api/v1/orders/{order_no}/logistics` — 物流信息（后端4.4，响应含 logistics_no/company/status/estimated_time/traces）
- 物流轨迹按运单号公开查询（v1.0 的 `GET /api/logistics/:tracking_number`）后端无此接口，统一走订单维度接口

### 8.3 物流跟踪

#### 功能设计
- 实时物流轨迹展示
- 物流状态节点：已下单 → 已揽收 → 运输中 → 派送中 → 已签收
- 物流异常提醒（包裹超时、地址错误等）
- 预计送达时间计算
- 物流信息支持刷新

#### 交互细节
- 物流轨迹按时间倒序展示
- 最新物流节点高亮显示
- 物流信息自动更新（每30分钟刷新）
- 支持手动刷新物流信息
- 物流异常时显示提醒横幅

#### 数据依赖
- `GET /api/v1/orders/{order_no}/logistics` — 查询物流轨迹（后端4.4）
- `POST /api/v1/logistics/subscribe` — 订阅物流提醒（后端7.3；发货时自动订阅，签收后退订）
- WS: `logistics` topic — 物流状态变更事件通知（**v1.0 的独立 `logistics-updates` 通道后端未定义**，统一收敛为推送服务 topic，见1.5）

### 8.4 确认收货

#### 功能设计
- 待收货订单显示"确认收货"按钮
- 点击后弹窗二次确认
- 确认后订单状态变为"已完成"
- 确认收货后触发评价入口
- 支持"收货后自动确认"设置（如签收后7天自动确认）

#### 关键组件
```
ConfirmReceiptDialog
├── DialogTitle           # 标题
├── DialogContent         # 内容（确认提示）
├── CountdownTip          # 倒计时提示（如"7天后自动确认"）
└── DialogActions         # 操作按钮
    ├── CancelButton      # 取消
    └── ConfirmButton     # 确认收货
```

### 8.5 售后/退款

#### 功能设计
- 已收货订单可申请售后（7天无理由退货、15天质量问题换货）
- 售后类型：退货退款、换货、维修
- 售后流程：申请 → 审核 → 退货 → 退款
- 售后进度实时跟踪
- 支持撤销售后申请

#### 页面布局
```
AfterSalesPage
├── AfterSalesTabs        # 售后Tab
│   ├── AllAfterSales     # 全部售后
│   ├── PendingProcess    # 处理中
│   └── Completed         # 已完成
├── AfterSalesList        # 售后列表
│   └── AfterSalesCard    # 售后卡片
│       ├── ProductInfo   # 商品信息
│       ├── AfterSalesType # 售后类型
│       ├── AfterSalesStatus # 售后状态
│       └── AfterSalesActions # 售后操作
└── ApplyAfterSales       # 申请售后
    ├── ProductSelect     # 选择商品
    ├── ReasonSelect      # 选择原因
    ├── Description       # 问题描述
    ├── ImageUpload       # 上传图片
    └── SubmitButton      # 提交申请
```

#### 数据依赖（v1.1契约差距说明）
- 后端仅有退款：`POST /api/v1/orders/{order_no}/refund` + `refund` 表（状态：申请中/已退款/已拒绝），退款走订单维度接口
- **v1.0 计划的 `/api/after-sales` 系列接口（列表/详情/撤回/回填退货物流）后端契约缺失**，且后端 refund 表不支持"换货、维修"类型 —— 售后完整流程需要后端扩展 after-sales 服务（售后单、退货物流、换货）；一期范围收敛为"仅退款"，对齐后端 refund 接口，换货/维修列入二期与后端排期联动
- 待后端补充：售后列表/详情/撤销售后/回填退货物流接口、换货与维修类型、退货物流跟踪

### 8.6 订单评价（v1.1新增，v1.0 有"去评价"入口但缺评价页面）

#### 功能设计
- 仅"已收货/已完成"订单可评价（后端3.3.2规则：每个订单商品仅一次，7天内可修改）
- 评分（1-5星）+ 文字内容 + 图片/视频上传 + 匿名评价开关
- 评价标签快捷选择（如"物流快"、"质量好"）
- 评价成功得20积分提示（后端6.4积分规则）

#### 关键组件
```
ReviewPage
├── ProductReviewItem    # 按订单商品逐项评价
│   ├── RatingStars      # 星级评分
│   ├── ReviewInput      # 文字输入（500字上限）
│   ├── TagSelector      # 评价标签
│   ├── ImageUploader    # 图片上传（最多9张，复用9.2头像上传组件）
│   └── AnonymousToggle  # 匿名评价
└── SubmitButton         # 提交（幂等request_id）
```

#### 数据依赖
- `POST /api/v1/products/{id}/reviews` — 发表评价（后端3.3.1；body: sku_id、order_id、rating、content、images、is_anonymous）
- `GET /api/v1/products/{id}/reviews/summary` — 评价统计（后端3.3.1）
- `DELETE /api/v1/reviews/{review_id}` — 删除评价（后端3.3.1）

---

## 九、个人中心

### 9.1 页面布局

```
ProfilePage
├── UserCard              # 用户信息卡片
│   ├── Avatar            # 用户头像
│   ├── Nickname          # 用户昵称
│   ├── UserLevel         # 用户等级
│   └── UserStats         # 用户统计（关注/粉丝/获赞）
├── QuickActions          # 快捷操作
│   ├── PendingPayment    # 待付款
│   ├── PendingShipment   # 待发货
│   ├── PendingReceipt    # 待收货
│   ├── PendingReview    # 待评价
│   └── AfterSales        # 售后/退款
├── OrderEntrance         # 订单入口
│   ├── AllOrders         # 全部订单
│   └── OrderSettings     # 订单设置
├── MenuList              # 功能菜单
│   ├── AddressManagement # 收货地址
│   ├── MyFavorites       # 我的收藏
│   ├── MyCoupons         # 我的优惠券
│   ├── MyPoints          # 我的积分
│   ├── BrowsingHistory   # 浏览历史
│   ├── AccountSecurity   # 账户安全
│   └── Settings          # 设置
└── RecommendSection      # 推荐区
    └── ProductGrid       # 推荐商品
```

### 9.2 个人信息

#### 功能设计
- 查看和编辑个人信息：头像、昵称、性别、生日、个性签名
- 头像上传：支持裁剪
- 用户等级展示：等级图标、成长值、等级权益
- 实名认证：身份证上传、人脸识别

#### 关键组件
```
UserInfoEditor
├── AvatarEditor          # 头像编辑
│   ├── CurrentAvatar     # 当前头像
│   ├── UploadButton      # 上传按钮
│   └── CropModal         # 裁剪弹窗
├── FormFields            # 表单字段
│   ├── NicknameInput     # 昵称输入
│   ├── GenderSelect      # 性别选择
│   ├── BirthdayPicker    # 生日选择
│   └── SignatureInput    # 个性签名
└── SaveButton            # 保存按钮
```

#### 数据依赖（v1.1对齐后端6.1）
- `GET /api/v1/user/profile` — 获取个人信息
- `PUT /api/v1/user/profile` — 更新个人信息
- `POST /api/v1/user/avatar` — 上传头像
- `GET /api/v1/user/security` / `PUT /api/v1/user/password|email|phone` — 账户安全（改密/换绑，后端6.1）
- **后端缺口**：`GET /api/user/level`（用户等级）与实名认证（身份证/人脸识别）后端契约均未定义 —— 等级展示与实名认证标记为 **P2**，依赖会员中心扩展；一期隐藏入口

### 9.3 收货地址

#### 功能设计
- 地址列表展示（收货人、电话、地址、默认标签）
- 新增地址：填写表单，地图选点
- 编辑地址：修改已有地址
- 删除地址：二次确认
- 设置默认地址
- 地址数量限制（最多20个）

#### 页面布局
```
AddressManagement
├── AddressList           # 地址列表
│   └── AddressItem       # 地址项
│       ├── ReceiverInfo  # 收件人信息
│       ├── AddressInfo   # 地址信息
│       ├── DefaultTag    # 默认标签
│       ├── SetDefaultButton # 设为默认
│       ├── EditButton    # 编辑
│       └── DeleteButton  # 删除
└── AddAddressButton      # 添加地址
```

#### 关键组件
```
AddressForm
├── ReceiverNameInput     # 收件人姓名
├── PhoneInput            # 联系电话
├── RegionSelect          # 省市区选择
│   ├── ProvinceSelect    # 省份选择
│   ├── CitySelect        # 城市选择
│   └── DistrictSelect    # 区县选择
├── DetailAddressInput    # 详细地址
├── MapPicker             # 地图选点
└── DefaultCheckbox       # 设为默认地址
```

#### 数据依赖（v1.1对齐后端6.2）
- `GET /api/v1/user/addresses` — 获取地址列表
- `POST /api/v1/user/addresses` — 新增地址
- `PUT /api/v1/user/addresses/{id}` — 编辑地址
- `DELETE /api/v1/user/addresses/{id}` — 删除地址
- `PUT /api/v1/user/addresses/{id}/default` — 设为默认
- 省市区数据：本地打包静态 JSON（国标行政区划，随版本更新），不走接口；地图选点为 P2（需后端 lat/lng 字段，见7.2）

### 9.4 我的收藏

#### 功能设计
- 收藏列表按收藏夹分组
- 支持创建、编辑、删除收藏夹
- 收藏夹内商品支持移动、删除
- 收藏商品降价提醒
- 收藏商品推荐相关商品

#### 页面布局
```
FavoritesPage
├── FolderTabs            # 收藏夹Tab
│   └── FolderTab         # 收藏夹项
├── FavoritesList         # 收藏列表
│   └── FavoriteItem      # 收藏项
│       ├── ProductImage  # 商品图片
│       ├── ProductInfo   # 商品信息
│       ├── PriceInfo     # 价格信息
│       ├── PriceDropTag  # 降价标签
│       └── Actions       # 操作（加购、删除）
└── EmptyFavorites        # 空收藏
```

#### 数据依赖（v1.1对齐后端6.3）
- `GET /api/v1/user/favorites?keyword=&order=recent` — 收藏列表（含降价标记字段依赖后端扩展，一期由商品价格缓存对比本地生成）
- `POST /api/v1/user/favorites` — 添加收藏
- `DELETE /api/v1/user/favorites/{id}` — 取消收藏
- `GET /api/v1/user/favorites/check?product_ids=` — 批量校验收藏状态
- 收藏夹（folder）创建/移动接口后端缺失，见4.4说明，一期不提供分组管理

### 9.5 我的优惠券

#### 功能设计
- 优惠券列表按状态分组：未使用、已使用、已过期
- 优惠券详情：使用条件、有效期、适用范围
- 支持"去使用"跳转商品列表
- 优惠券到期提醒

#### 页面布局
```
CouponsPage
├── CouponTabs            # 优惠券Tab
│   ├── AvailableCoupons  # 未使用
│   ├── UsedCoupons       # 已使用
│   └── ExpiredCoupons    # 已过期
├── CouponList            # 优惠券列表
│   └── CouponCard        # 优惠券卡片
│       ├── CouponAmount  # 优惠金额
│       ├── CouponCondition # 使用条件
│       ├── CouponValidity # 有效期
│       └── CouponStatus  # 优惠券状态
└── EmptyCoupons          # 空优惠券
```

#### 关键组件
```
CouponCard
├── CouponLeft           # 左侧金额区
│   ├── Amount           # 优惠金额
│   └── Condition        # 使用条件
├── CouponRight          # 右侧信息区
│   ├── CouponName       # 优惠券名称
│   ├── ValidityPeriod   # 有效期
│   ├── Scope            # 适用范围
│   └── UseButton        # 去使用
└── CouponStatus         # 优惠券状态（已使用/已过期）
```

#### 数据依赖（v1.1契约缺口说明）
- `GET /api/v1/coupons?status=available` / `POST /api/v1/coupons/receive` — **待后端营销服务补充**：后端计划在下单接口含 `coupon_id` 字段，但未定义优惠券列表/领取/详情接口；"我的优惠券"页面与6.3领券弹窗共同依赖该组接口，需与后端排期对齐，一期可用 mock 数据先行开发 UI

### 9.6 积分

#### 功能设计
- 积分余额展示
- 积分明细（获取/使用记录）
- 积分兑换：优惠券、商品、服务
- 积分签到：每日签到获取积分
- 积分规则说明

#### 页面布局
```
PointsPage
├── PointsCard            # 积分卡片
│   ├── PointsBalance     # 积分余额
│   ├── TotalEarned       # 累计获取
│   ├── TotalUsed         # 累计使用
│   └── SignInButton      # 每日签到
├── PointsExchange        # 积分兑换
│   └── ExchangeItem      # 兑换项
│       ├── ExchangeImage # 兑换商品图片
│       ├── ExchangeName  # 兑换商品名称
│       ├── ExchangePoints # 所需积分
│       └── ExchangeButton # 兑换按钮
├── PointsHistory         # 积分明细
│   └── HistoryItem       # 明细项
│       ├── HistoryDesc   # 明细描述
│       ├── HistoryPoints # 积分变动
│       └── HistoryTime   # 变动时间
└── PointsRules           # 积分规则
```

#### 数据依赖（v1.1对齐后端6.4）
- `GET /api/v1/user/points` — 积分概览（余额/累计获取/累计使用）
- `GET /api/v1/user/points/logs?type=&page=` — 积分明细（对接后端 points_log 表）
- 积分规则对齐后端6.4：消费1元=1积分、每日签到=5积分、评价=20积分
- **待后端补充**：签到（`POST /api/v1/user/points/sign-in`）与积分兑换（`POST /api/v1/user/points/exchange`）接口未在后端契约中定义；积分规则中已含签到/兑换规则，接口需营销/会员服务扩展，一期展示余额与明细、签到置灰

### 9.7 设备管理与隐私设置（v1.1新增，对接后端2.3设备管理）

#### 功能设计
- 已绑定设备列表：设备名、os_type、client_version、最后活跃时间、当前设备标记
- 解绑其他设备（解绑后该设备 refresh token 进黑名单，需重新登录）
- 修改设备名称
- 隐私开关：崩溃上报、促销推送、搜索历史跨端同步、个性化推荐

#### 数据依赖
- `GET /api/v1/devices` — 设备列表（后端2.3.2）
- `DELETE /api/v1/devices/{device_id}` — 解绑设备
- `PUT /api/v1/devices/{device_id}/name` — 修改设备名称
- 设备上限提示：最多5台（后端2.3.3），超限时引导先解绑
- `POST /api/v1/push/register` — 推送 token 注册/注销（后端7.1.2）

---

## 十、消息通知

### 10.1 消息中心

#### 页面布局
```
MessageCenter
├── MessageTabs           # 消息Tab
│   ├── SystemMessages    # 系统通知
│   ├── LogisticsMessages # 物流提醒
│   ├── PromoMessages     # 促销推送
│   └── CustomerService   # 客服消息
├── MessageList           # 消息列表
│   └── MessageItem       # 消息项
│       ├── MessageIcon   # 消息图标
│       ├── MessageTitle  # 消息标题
│       ├── MessageDesc   # 消息描述
│       ├── MessageTime   # 消息时间
│       └── UnreadDot     # 未读标记
└── MessageActions        # 消息操作
    ├── MarkAllRead       # 全部已读
    └── DeleteRead        # 删除已读
```

#### 关键组件
```
MessageItem
├── MessageIcon           # 消息图标（按类型）
├── MessageContent        # 消息内容
│   ├── Title             # 消息标题
│   ├── Description       # 消息描述
│   └── Time              # 消息时间
├── UnreadDot             # 未读红点
└── SwipeActions          # 滑动操作（删除/标记已读）
```

#### 交互细节
- 未读消息显示红点标记
- 点击消息跳转到对应页面（如物流消息跳转到订单详情）
- 支持右键删除/标记已读（v1.0 的"左滑删除"为移动端交互，桌面端改为右键菜单与批量勾选）
- 消息按时间倒序排列
- 消息列表支持下拉刷新
- Tab 分类与后端 `notification.type` 枚举映射（后端7.2：order/logistics/coupon/system/activity）：

  | 前端Tab | 后端 type |
  |---------|-----------|
  | 系统通知 | system、activity |
  | 物流提醒 | logistics、order |
  | 促销推送 | coupon |
  | 客服消息 | 独立来源（客服会话，见十一章），不与 notification 混存 |

#### 数据依赖（v1.1对齐后端7.2，资源名为 notifications）
- `GET /api/v1/notifications?type=system&page=1&size=20` — 通知列表
- `GET /api/v1/notifications/unread` — 未读通知（含未读数角标）
- `PUT /api/v1/notifications/{id}/read` — 标记已读
- `PUT /api/v1/notifications/read-all` — 全部已读
- `DELETE /api/v1/notifications/{id}` — 删除通知
- WS: `messages` topic — 新消息事件通知（仅通知有新消息，数据经 REST 拉取，见1.5）

### 10.2 系统通知

#### 通知类型
| 类型 | 说明 | 触发条件 |
|------|------|----------|
| 系统公告 | 平台公告、活动通知 | 平台发布 |
| 账户安全 | 登录提醒、密码修改 | 账户操作 |
| 订单状态 | 订单状态变更 | 订单状态变化 |
| 系统维护 | 维护通知 | 平台维护 |

#### 交互细节
- 系统通知置顶显示
- 重要通知弹窗提醒
- 通知支持已读/未读状态
- 通知详情页展示完整内容

### 10.3 物流提醒

#### 通知类型
| 类型 | 说明 | 触发条件 |
|------|------|----------|
| 已发货 | 包裹已发出 | 商家发货 |
| 运输中 | 包裹运输中 | 物流状态更新 |
| 派送中 | 包裹正在派送 | 快递员派送 |
| 已签收 | 包裹已签收 | 用户签收 |
| 异常提醒 | 物流异常 | 物流异常 |

#### 交互细节
- 物流提醒显示运单号（可复制）
- 点击跳转到物流详情页
- 物流状态变更时桌面通知
- 物流异常时高亮提醒

### 10.4 促销推送

#### 功能设计
- 个性化促销推荐（基于用户偏好）
- 促销活动开始提醒
- 优惠券到账提醒
- 收藏商品降价提醒
- 购物车商品促销提醒

#### 推送策略
- 推送频率控制：每日不超过3条促销推送
- 用户可在设置中关闭促销推送
- 推送时间避开深夜（22:00-8:00）
- 推送内容个性化，避免骚扰

#### 数据依赖（v1.1契约缺口说明）
- 促销推送由后端推送服务（第七章）经 WS `messages` topic 下发 + `notification` 表持久化（type=coupon/activity），**v1.0 的 `GET /api/promotions/push`、`POST /api/promotions/push/read` 接口后端不存在**
- 推送频率控制（每日≤3条、避开22:00-8:00）由服务端策略负责，客户端开关经 1.7 配置下发（`promo_push_enabled`）与 9.7 隐私设置控制

### 10.5 桌面通知

#### 功能设计
- 使用系统原生通知（Windows Toast / macOS Notification Center / Linux Notify）
- 通知内容：标题、正文、图标、点击动作
- 点击通知跳转到对应页面
- 通知分组：按类型分组显示
- 通知历史：本地保存最近100条通知

#### 技术实现
```typescript
// 主进程调用系统通知
import { Notification } from 'electron';

function showDesktopNotification(options: {
  title: string;
  body: string;
  icon?: string;
  silent?: boolean;
  onClick?: () => void;
}) {
  const notification = new Notification({
    title: options.title,
    body: options.body,
    icon: options.icon,
    silent: options.silent,
  });
  notification.on('click', options.onClick);
  notification.show();
}
```

#### 交互细节
- 通知显示时长由系统控制：Windows/Linux 由通知中心管理，**macOS 不支持自定义超时**（v1.0"5秒后自动消失"不可跨平台实现，仅 Windows 可用 `toastScenario` 近似控制）；点击通知均支持聚焦窗口并跳转
- 通知点击：聚焦应用窗口并跳转到对应页面（经 `notification:click` IPC 事件，见1.3）
- 通知权限：macOS 首次调用时系统弹窗授权，被拒绝时引导用户到系统设置开启；Windows 需设置 appUserModelID（electron-builder 自动配置）并依赖快捷方式存在，否则通知不弹
- 免打扰时段：22:00-8:00不显示桌面通知（仅应用内消息），由客户端本地判断（与后端10.4推送时间策略双保险）
- 通知角标：任务栏/托盘未读数（Windows 需替换托盘图标绘制数字，macOS 用 Dock badge，见14.1）

#### 数据依赖
- 主进程 API：`new Notification()`
- IPC 通信：渲染进程 → 主进程触发通知

---

## 十一、客服系统

### 11.1 在线客服

#### 页面布局
```
CustomerServicePage
├── ServiceTabs           # 服务Tab
│   ├── OnlineService     # 在线客服
│   ├── ServiceTickets    # 服务工单
│   └── MessageHistory    # 消息记录
├── OnlineService         # 在线客服
│   ├── ServiceQueue      # 排队信息
│   ├── ChatWindow        # 聊天窗口
│   │   ├── MessageList   # 消息列表
│   │   │   └── ChatMessage # 聊天消息
│   │   ├── QuickReplies  # 快捷回复
│   │   └── InputArea     # 输入区
│   └── ServiceInfo       # 客服信息
│       ├── AgentAvatar   # 客服头像
│       ├── AgentName     # 客服姓名
│       └── AgentRating   # 客服评分
├── ServiceTickets        # 服务工单
│   └── TicketList        # 工单列表
│       └── TicketItem    # 工单项
└── MessageHistory        # 消息记录
    └── HistoryList       # 历史消息列表
        └── HistoryItem   # 历史消息项
```

#### 关键组件
```
ChatWindow
├── MessageList           # 消息列表
│   └── ChatMessage       # 聊天消息
│       ├── MessageAvatar # 消息头像
│       ├── MessageContent # 消息内容
│       │   ├── TextMessage  # 文本消息
│       │   ├── ImageMessage # 图片消息
│       │   ├── ProductMessage # 商品卡片消息
│       │   └── OrderMessage # 订单卡片消息
│       └── MessageTime   # 消息时间
├── QuickReplies          # 快捷回复
│   └── QuickReplyTag     # 快捷回复标签
└── InputArea             # 输入区
    ├── EmojiButton       # 表情按钮
    ├── ImageButton       # 图片按钮
    ├── FileButton        # 文件按钮
    ├── Input             # 文本输入
    └── SendButton        # 发送按钮
```

#### 交互细节
- 消息实时收发（WSS `customer-service` topic，连接复用统一WS客户端，见1.5；断线重连后经 REST 补拉 `session/{id}/messages` 补偿遗漏消息并按 seq 去重）
- 支持发送文本、图片、文件、表情（文件类型/大小限制由后端约定，客户端预校验）
- 支持发送商品卡片、订单卡片
- 消息状态显示：发送中、已发送、已读、发送失败（可重试）
- 消息撤回（2分钟内可撤回，**撤回接口待后端补充**）
- 聊天窗口支持拖拽调整大小
- 聊天窗口可最小化为悬浮窗

#### 数据依赖（v1.1对齐后端10.1）
- `POST /api/v1/customer-service/session` — 创建会话（排队/分配结果在响应中；**v1.0 的 `GET /api/customer-service/queue` 排队查询接口后端未定义**，排队信息由会话创建响应与 WS 事件下发）
- `GET /api/v1/customer-service/session/{id}` — 会话信息
- `POST /api/v1/customer-service/session/{id}/close` — 关闭会话
- `GET /api/v1/customer-service/session/{id}/messages` — 历史消息（分页）
- `POST /api/v1/customer-service/message` — 发送消息（单数，后端10.1.2；v1.0 的复数 `/messages` 为POST路径不对齐）
- WS: `customer-service` topic — 实时消息（消息格式见后端10.1.2）
- **待后端补充**：消息撤回、消息导出、排队状态查询

### 11.2 服务工单

#### 功能设计
- 创建工单：选择问题类型、填写描述、上传截图
- 工单状态：待处理、处理中、已解决、已关闭
- 工单进度实时更新
- 工单评价：解决后对工单进行评价
- 工单历史查询

#### 页面布局
```
ServiceTicketsPage
├── CreateTicketButton    # 创建工单
├── TicketList            # 工单列表
│   └── TicketItem        # 工单项
│       ├── TicketType    # 工单类型
│       ├── TicketStatus  # 工单状态
│       ├── TicketDesc    # 工单描述
│       └── TicketTime    # 创建时间
└── TicketDetail          # 工单详情
    ├── TicketInfo        # 工单信息
    ├── TicketProgress    # 工单进度
    ├── TicketMessages    # 工单消息
    └── TicketActions     # 工单操作
        ├── ReplyButton   # 回复
        └── CloseButton   # 关闭工单
```

#### 数据依赖（v1.1对齐后端10.2，统一customer-service前缀）
- `POST /api/v1/customer-service/tickets` — 创建工单
- `GET /api/v1/customer-service/tickets?status=&page=` — 工单列表
- `GET /api/v1/customer-service/tickets/{id}` — 工单详情
- `PUT /api/v1/customer-service/tickets/{id}` — 更新工单（v1.0 的 `PUT /api/tickets/:id/close` 关闭动作并入此接口：body 传 status=closed）
- `POST /api/v1/customer-service/tickets/{id}/reply` — 回复工单
- **待后端补充**：工单评价接口（v1.0 的 `POST /api/tickets/:id/rate` 后端契约缺失），一期展示入口置灰

### 11.3 消息记录

#### 功能设计
- 查看所有客服消息记录
- 消息按时间倒序排列
- 支持按时间、类型筛选
- 消息记录本地保存（最近30天）
- 支持消息记录导出

#### 数据依赖（v1.1对齐）
- `GET /api/v1/customer-service/session/{id}/messages` — 消息记录按会话分页拉取（后端10.1.1；**无独立 history/export 接口**）
- 本地保存：会话消息缓存至 IndexedDB（最近30天），支持本地全文搜索
- 消息记录导出（v1.0 的 `GET /api/customer-service/export`）**后端缺口**，一期用客户端本地导出实现（读本地缓存生成 Markdown/HTML 文件，经 `dialog:save-image` 同款保存对话框落盘）

### 11.4 悬浮客服窗

#### 功能设计
- 应用内悬浮客服入口（可拖拽）
- 悬浮窗显示未读消息数
- 点击悬浮窗展开聊天窗口
- 聊天窗口可最小化回悬浮窗
- 悬浮窗位置记忆（下次启动恢复位置）

#### 关键组件（v1.1修正：原代码块缺失起始标记）
```
FloatingServiceEntry
├── FloatingButton         # 悬浮按钮（应用内可拖拽，位置记忆存 IndexedDB）
│   └── UnreadBadge       # 未读数角标
└── MiniChatWindow         # 迷你聊天窗（同窗口内子组件实现，避免独立 BrowserWindow 的父子联动复杂度）
    ├── MessagePreview    # 消息预览
    └── CloseButton       # 关闭
```

---

## 十二、离线能力

### 12.1 本地缓存

#### 缓存策略
| 数据类型 | 存储位置 | 缓存策略 | 过期时间 | 备注 |
|----------|----------|----------|----------|------|
| 用户信息 | IndexedDB | 长期缓存 | 登录期间有效 | 登出时自动清理 |
| 浏览历史 | IndexedDB | 最近100条 | 永久 | 登出时保留或清理（由用户在设置中决定） |
| 搜索历史 | localStorage | 最近20条 | 永久 | 登出时清理 |
| 商品信息 | IndexedDB | 热门商品缓存 | 1小时 | LRU策略，超出自动淘汰 |
| 首页数据 | IndexedDB | 离线可读 | 30分钟 | 关键数据优先缓存 |
| 购物车数据 | IndexedDB | 实时同步 | 永久 | 冲突解决：以服务器为准，本地变更优先提交 |
| 订单列表 | IndexedDB | 离线可读 | 10分钟 | 过期后自动标记"数据已过期"，提示用户刷新 |
| 静态资源 | Cache Service Worker | 长期缓存 | 版本更新时失效 | 配合hash版本号策略 |

#### 技术方案
```typescript
// 使用 localForage 或 idb-keyval 操作 IndexedDB
import localforage from 'localforage';

// 缓存商品信息
await localforage.setItem('product:12345', productData);

// 读取缓存
const cached = await localforage.getItem('product:12345');

// 检查缓存是否有效
function isCacheValid(timestamp: number, maxAge: number): boolean {
  return Date.now() - timestamp < maxAge;
}
```

### 12.2 离线浏览

#### 功能设计
- 首页数据缓存，离线时可浏览
- 商品详情缓存，离线时可查看
- 订单列表缓存，离线时可查看
- 浏览历史本地记录，联网后同步
- 搜索历史本地存储
- 图片离线查看（已加载的图片）

#### 离线提示
- 网络断开时显示离线提示横幅
- 离线时禁用需要网络的操作（如支付）
- 离线时显示"离线模式"标识
- 网络恢复后自动同步数据

#### 关键组件
```
OfflineBanner
├── OfflineIcon           # 离线图标
├── OfflineText           # 离线提示文字
└── RetryButton           # 重试按钮
```

### 12.3 网络状态检测

#### 功能设计
- 实时检测网络连接状态
- 网络状态变化时通知所有组件
- 网络恢复时自动同步离线数据
- 网络断开时切换到离线模式
- 支持手动刷新网络状态

#### 技术实现
```typescript
// 渲染进程监听网络状态（浏览器标准API，Chromium支持）
window.addEventListener('online', handleOnline);
window.addEventListener('offline', handleOffline);

// 主进程辅助探测（v1.1修正：Electron net 模块无 isOnline() 方法）
// 方案：powerMonitor 监听 resume/suspend 事件 + 定时对 API 域名发起 HEAD 探测请求（204/超时判定）
import { net, powerMonitor } from 'electron';

function probeNetwork(): Promise<boolean> {
  return new Promise((resolve) => {
    const req = net.request({ method: 'HEAD', url: 'https://api.example.com/api/v1/ping' });
    req.on('response', () => resolve(true));
    req.on('error', () => resolve(false));
    req.setTimeout(5000, () => { req.abort(); resolve(false); });
    req.end();
  });
}
powerMonitor.on('resume', () => { void handleOnline(); }); // 休眠唤醒后强制探测+同步
```

#### 数据同步策略（v1.1对齐后端第九章同步服务）
- 同步接口以 `POST /api/v1/sync/pull`（增量拉取）、`POST /api/v1/sync/push`（推送本地变更）、`POST /api/v1/sync/resolve`（冲突解决）、`POST /api/v1/sync/full`（首次安装全量）、`GET /api/v1/sync/status`（同步状态）为准（后端9.1.2/9.3.2），v1.0 的 `/api/sync/offline-actions` 接口不存在
- 触发时机（后端9.3.1）：应用启动增量拉取、每5分钟定时、网络恢复、用户手动、离线队列上传
- 离线时操作加入待同步队列，网络恢复后自动执行同步队列
- 同步冲突处理：收藏/配置时间戳优先，购物车数量以服务端为准（后端9.1.3）

#### 关键组件
```
NetworkStatusIndicator
├── OnlineStatus          # 在线状态
│   └── OnlineIcon        # 在线图标
└── OfflineStatus         # 离线状态
    ├── OfflineIcon       # 离线图标
    └── SyncPending       # 待同步数据量
```

### 12.4 离线操作队列

#### 功能设计
- 离线时支持的操作：加购物车、收藏、浏览（下单/支付必须联网，离线时禁用并提示）
- 离线操作加入待执行队列，每条操作携带：`op_id`（客户端生成UUID，服务端幂等去重）、`op_type`、`payload`、`created_at`、`retry_count`
- 网络恢复后按原顺序执行队列（保序），单条失败指数退避重试（1s/4s/16s），超过5次进入"待处理失败"列表由用户手动重试/丢弃
- 队列持久化到 **IndexedDB**（v1.0 用 localStorage 不可靠：清缓存即丢失，且容量小）；队列项在服务端确认后删除
- 执行结果经 WS/轮询校验，冲突交由同步服务解决（后端9.1.3）

#### 数据依赖
- 本地存储：IndexedDB（key: `offline_queue`）
- 同步接口：`POST /api/v1/sync/offline/upload`（上传离线操作，后端9.2.1）、`GET /api/v1/sync/offline/status`（查询执行状态）

---

## 十三、性能优化

### 13.1 启动速度优化

#### 冷启动优化
| 优化项 | 方案 | 预期效果 |
|--------|------|----------|
| 主进程优化 | 延迟加载非核心模块 | 减少200ms |
| 渲染进程优化 | 使用Vite按需加载 | 减少300ms |
| 资源预加载 | 预加载关键资源 | 减少100ms |
| 骨架屏 | 首屏骨架屏 | 感知提升50% |
| 代码分割 | 路由级别代码分割 | 减少bundle体积 |

#### 启动流程优化
```
应用启动
  ├── 主进程初始化（最小化）
  ├── 创建窗口（显示骨架屏）
  ├── 加载核心模块（路由、状态管理）
  ├── 并行加载：用户信息 + 首页数据
  └── 渲染首屏
```

#### 关键指标
- 冷启动时间：< 2s（目标，优设备），< 3s（中配置），< 4s（低配置）
- 热启动时间：< 500ms
- 首屏渲染时间：< 1s（优设备），< 1.5s（中配置），< 2s（低配置）

### 13.2 内存占用优化

#### 优化策略
| 策略 | 说明 |
|------|------|
| 路由懒加载 | 按需加载页面组件 |
| 图片懒加载 | 进入视口才加载图片 |
| 虚拟列表 | 长列表使用虚拟滚动 |
| 内存泄漏检测 | 定期检查组件卸载清理 |
| Worker线程 | 计算密集型任务放入Worker |
| 缓存控制 | LRU缓存策略，限制缓存大小 |

#### 虚拟列表实现
```typescript
// 商品瀑布流为不等高布局，FixedSizeList（等高）不适用，需按列实现或使用支持变高的库
// 方案A：react-virtuoso（支持变高item的Masonry布局）
import { VirtuosoMasonry } from 'react-virtuoso';

<VirtuosoMasonry
  data={products}
  renderItem={(product) => <ProductCard product={product} />}
  columnCount={4}   // 桌面端按窗口宽度动态分列
/>

// 方案B：列表页（等高卡片网格）可用 react-window FixedSizeList，配合无限滚动分页窗口
// 注意：不得一次性渲染全量数据（itemCount 应为已加载页数×pageSize，滚动触底再拉下一页）
```

#### 内存监控
- 主进程内存监控
- 渲染进程内存监控
- 内存超限自动清理缓存
- 内存泄漏告警

### 13.3 渲染性能优化

#### 优化策略
| 策略 | 说明 |
|------|------|
| React.memo | 避免不必要的重渲染 |
| useMemo/useCallback | 缓存计算结果和函数 |
| 列表key优化 | 使用稳定唯一的key |
| 避免内联函数 | 减少子组件重渲染 |
| CSS优化 | 避免复杂选择器，使用GPU加速 |
| 防抖/scroll | 滚动事件节流 |

#### 图片优化
- 使用WebP格式（体积减少30%）
- 响应式图片（根据屏幕尺寸加载不同分辨率）
- 图片懒加载（IntersectionObserver）
- 图片预加载（关键图片）
- 图片占位符（骨架屏）

#### 打包优化
- Tree Shaking：移除未使用代码
- Code Splitting：按需加载
- 压缩：Terser压缩JS，CSSNano压缩CSS
- 资源内联：小图片内联为Base64
- Gzip/Brotli：服务器端压缩

### 13.4 桌面特有性能优化

#### 多窗口管理
- 使用 `BrowserWindow` 的 `webPreferences` 优化渲染进程
- 窗口不可见（occlusion/minimize）时依赖 Chromium 原生 `backgroundThrottling` 节流定时器/动画（不可手动降低渲染帧率，v1.0 表述不可实现已修正）；对 WS 心跳等不受节流影响的定时任务在主进程侧驱动
- 窗口最小化时暂停非必要动画和轮询（如支付状态轮询保留）
- 窗口销毁时释放资源（移除监听、清理缓存引用）

#### 主进程优化
- 避免在主进程执行耗时操作
- 使用 `ipcMain.handle` 异步处理IPC请求
- 大量数据处理放入 Worker 线程（`worker_threads`）
- 定期清理无用缓存

#### 渲染进程优化
- 使用 `will-navigate` 阻止不必要的页面跳转，`setWindowOpenHandler` 控制新窗口（安全与性能双收益，见1.3）
- 限制同时打开的窗口数量
- 监控渲染进程CPU/内存占用（`app.getAppMetrics()`，见13.5）

### 13.5 性能监控

#### 监控指标
| 指标 | 说明 | 目标 |
|------|------|------|
| 首屏渲染时间 | 首屏内容渲染完成时间 | < 1s |
| 交互响应时间 | 用户操作到界面响应时间 | < 100ms |
| 帧率 | 动画/滚动帧率 | > 55fps |
| 内存占用 | 渲染进程内存占用 | < 200MB |
| CPU占用 | 空闲时CPU占用 | < 1% |
| 打包体积 | 应用安装包体积 | < 100MB |

#### 监控方案
- 使用 `Performance API`（PerformanceObserver 采集 LCP/INP）采集渲染性能指标
- 主进程用 `app.getAppMetrics()` 采集各进程 CPU/内存（v1.0 引用的 `electron-process-manager` 已停止维护，废弃）
- 与1.6节崩溃上报共用上报通道，性能数据批量上报分析平台
- 性能异常时自动告警（帧率持续<30fps、内存>阈值时打点上报）
- 灰度阶段（后端8.3）以崩溃率+性能数据作为放量依据

---

## 十四、桌面特有功能

### 14.1 系统托盘

#### 功能设计
- 托盘图标显示应用状态
- 右键菜单：显示主界面、检查更新、设置、退出应用
- 托盘图标显示未读消息数（角标）：**平台差异** —— Windows 托盘不支持原生角标，需替换带数字的图标位图（预渲染0-99+）；macOS 优先用 Dock badge（`app.dock.setBadge`），托盘仅作辅助
- 托盘图标动画（新消息时闪烁/切换图标，仅 Windows 与部分 Linux 托盘支持）
- 点击托盘图标显示/隐藏主窗口（macOS 无左键 click 事件，需菜单项或双击处理，平台分支处理）
- **关闭行为设置项**（桌面端高频诉求）：点击关闭按钮时"最小化到托盘"或"退出应用"，默认最小化到托盘；退出需经托盘菜单或快捷键；首次最小化到托盘时气泡提示告知用户

#### 关键代码
```typescript
// main/tray/index.ts
import { Tray, Menu } from 'electron';

const tray = new Tray(iconPath);

const contextMenu = Menu.buildFromTemplate([
  { label: '显示主界面', click: () => showMainWindow() },
  { label: '退出', click: () => app.quit() },
]);
tray.setContextMenu(contextMenu);
tray.on('click', () => toggleMainWindow());
```

### 14.2 快捷键

#### 快捷键配置（v1.1修正：区分应用内快捷键与全局快捷键）
| 快捷键 | 类型 | 功能 |
|--------|------|------|
| Ctrl/Cmd+F | 应用内 | 页面内搜索/聚焦搜索框 |
| Ctrl/Cmd+K | 应用内 | 命令面板（快速跳转） |
| Ctrl/Cmd+, | 应用内 | 打开设置 |
| Ctrl/Cmd+Shift+F | 全局（可选，默认关） | 快速搜索悬浮窗 |
| Ctrl/Cmd+Shift+M | 全局（可选，默认开） | 打开消息中心 |
| Ctrl/Cmd+Shift+S | 应用内（截图 P2） | 截图 |
| Ctrl/Cmd+Q | 系统标准 | 退出应用（macOS 系统保留 Cmd+Q，走关闭行为设置） |

修正说明：
- v1.0 的 Ctrl+Shift+Q 全局退出易与其他应用/系统冲突且误触风险高，已移除；退出统一走托盘菜单/应用菜单
- v1.0 的 Ctrl+Shift+S/F/C/O/M 全局注册会抢占系统组合键（与输入法、其他软件冲突），**改为默认仅应用内生效**（menu accelerator），全局版作为设置项开放且默认关闭
- 全局快捷键注册失败（被占用）时给出提示并允许用户在设置中重新绑定（自定义 + 冲突检测）
- 截图功能依赖 `desktopCapturer` + 全屏选区实现，成本较高，标记 P2

#### 技术实现
```typescript
// main/shortcuts/index.ts
import { globalShortcut, app } from 'electron';

app.whenReady().then(() => {
  // 全局快捷键：仅注册用户已开启的项，注册失败需捕获并上报
  try {
    const ok = globalShortcut.register('CommandOrControl+Shift+M', () => showMainWindow('messages'));
    if (!ok) logger.warn('全局快捷键被占用: Cmd/Ctrl+Shift+M');
  } catch (e) { logger.error('快捷键注册失败', e); }
});

app.on('will-quit', () => globalShortcut.unregisterAll()); // 退出时释放，避免系统级残留
```

### 14.3 应用菜单

#### 菜单结构
```
文件（Windows/Linux；macOS 首项为应用菜单"EasyShopping"）
├── 新建窗口
├── 关闭窗口
└── 退出

编辑（使用 role，自动适配 macOS 系统快捷键）
├── 撤销 / 重做
├── 剪切 / 复制 / 粘贴
└── 全选

视图
├── 刷新（role: reload）
├── 全屏（role: togglefullscreen）
├── 开发者工具（生产包隐藏或限制）
└── 缩放（role: zoomIn/zoomOut/resetZoom）

窗口（macOS 需追加角色菜单：最小化、前置、窗口列表）
├── 最小化
├── 最大化
└── 关闭

帮助
├── 帮助文档
├── 检查更新
└── 关于
```

#### 平台差异（v1.1补充）
- macOS 必须有独立应用菜单（App Menu，含"关于/偏好设置/隐藏/退出"角色项），且菜单栏常驻；Windows/Linux 菜单嵌在窗口标题栏
- 快捷键用 `CommandOrControl` 修饰符，禁止硬编码 Ctrl（macOS 应为 Cmd）
- 生产构建移除"开发者工具"菜单项或绑定到非公开快捷键
- 用户偏好（如字体大小、主题）入口放"偏好设置"，对应页面 14.5

### 14.4 拖拽功能

#### 功能设计
- 支持文件拖拽上传（如评价时上传图片）
- 支持拖拽排序（如收藏夹排序）
- 支持拖拽到桌面（如保存图片）
- 支持从桌面拖入文件（如上传头像）

### 14.5 系统级集成

#### 功能设计
- 深度链接（deep link）：注册自定义协议 `ecommerce://`，浏览器/其它应用点击链接拉起客户端并路由到目标页（商品/订单/活动落地页）
- 系统通知：调用OS原生通知（见10.5）
- 剪贴板：读取/写入剪贴板内容（口令复制10秒后自动清空）
- 系统主题：跟随系统深色/浅色模式
- 开机启动：支持设置开机自启动（默认关闭，用户显式开启）

#### Deep Link 实现（v1.1补充，对接1.3协议处理职责）
```typescript
// main/protocol/index.ts
if (process.defaultApp) {
  if (process.argv.length >= 2) app.setAsDefaultProtocolClient('ecommerce', process.execPath, [path.resolve(process.argv[1])]);
} else {
  app.setAsDefaultProtocolClient('ecommerce');
}

// macOS：open-url 事件接收链接
app.on('open-url', (event, url) => { event.preventDefault(); routeDeepLink(url); });

// Windows/Linux：第二实例的 argv 携带链接；单实例锁激活已有实例后处理
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) { app.quit(); } else {
  app.on('second-instance', (_e, argv) => {
    const url = argv.find(a => a.startsWith('ecommerce://'));
    if (url) routeDeepLink(url);
    showMainWindow();
  });
}
```
- 链接解析后经 `deep-link:open` IPC 事件通知渲染进程路由（见1.3事件通道）
- electron-builder.yml 增加 `protocols` 配置（`{ name: 'EasyShopping', schemes: [ecommerce] }`）以完成 macOS 注册
- 未登录时深链先落地登录页，登录成功后补路由

#### 开机自启（v1.1补充）
```typescript
import { app } from 'electron';
app.setLoginItemSettings({
  openAtLogin: userSetting.enabled,       // 默认关闭
  path: process.execPath,
  args: ['--autolaunch'],                 // 自启时静默启动到托盘，不弹主窗口
});
```

#### 深色模式适配
```typescript
// 主进程监听系统主题变化，经 theme:changed 事件通知渲染进程切换主题（见1.3）
import { nativeTheme } from 'electron';
nativeTheme.on('updated', () => {
  mainWindow?.webContents.send('theme:changed', nativeTheme.shouldUseDarkColors);
});
```
- 用户可在设置中强制指定 浅色/深色/跟随系统（默认跟随系统），覆盖值经 `nativeTheme.themeSource` 设置
- Windows 10 以下与部分 Linux 发行版不支持深色 API，需应用内自带主题兜底

---

## 十五、开发排期建议

### 15.1 里程碑

| 阶段 | 时间 | 交付物 |
|------|------|--------|
| M1 架构搭建 | 2周 | 项目骨架、基础配置、CI/CD |
| M2 核心功能 | 6周 | 登录、首页、商品、搜索、购物车 |
| M3 交易流程 | 4周 | 下单、支付、订单、售后（一期仅退款） |
| M4 用户中心 | 3周 | 个人中心、地址、收藏、优惠券（UI先行） |
| M5 消息客服 | 3周 | 消息中心、在线客服、通知 |
| M6 桌面特性 | 2周 | 自动更新、托盘、快捷键、桌面通知、深链 |
| M7 离线能力 | 2周 | 本地缓存、离线浏览、网络检测、同步 |
| M8 性能优化 | 2周 | 启动优化、内存优化、渲染优化 |
| M9 测试上线 | 2周 | 集成测试、安全渗透测试、代码签名与公证、正式发布 |

> 各里程碑的**可验证验收条件见 15.5**，按周任务分解与联调节点见 **15.4**，前置与外部依赖见 **15.6**。

### 15.2 后端联调依赖（v1.1新增）

前端总周期 26 周，后端 20 周（见 pc-client-backend.md 14.1），联调按后端阶段解锁：

| 前端里程碑 | 依赖后端阶段 | 联调内容 |
|-----------|-------------|---------|
| M2 登录/商品 | 第二阶段（4-7周，认证/商品/订单/购物车） | 登录单接口、device_info、请求签名、商品详情 |
| M3 交易 | 第二阶段末（下单幂等、支付） | request_id 幂等、支付轮询、order_no 契约 |
| M5 消息客服 | 第三阶段（8-11周，会员/消息/客服） | WSS 推送、notifications、客服会话 |
| M6 自动更新 | 第四阶段（12-15周，更新服务/同步/崩溃） | 更新协议适配（方案A/B）、crash 上传、sync 接口 |
| M9 上线 | 第六阶段（19-20周，灰度/全量） | 双端灰度通道对齐、回滚演练 |

**接口缺口需在 M2 开始前与后端确认排期**：优惠券接口、签到/兑换、收藏夹、售后（换货/维修）、用户等级/实名认证、活动位接口（详见各章节"待后端补充"标注）。

### 15.3 人员配置建议

| 角色 | 人数 | 职责 |
|------|------|------|
| 前端架构师 | 1 | 技术选型、IPC/更新架构设计、Code Review |
| Electron开发 | 2 | 主进程、preload、自动更新、托盘/快捷键、打包签名 |
| 前端开发 | 3-4 | 页面开发、组件开发、业务逻辑 |
| UI设计师 | 1 | 视觉设计、交互设计、设计规范 |
| 测试工程师 | 1-2 | 功能测试、性能测试、自动化测试、多平台兼容测试 |

> 注：后端 API 开发由后端团队负责（见 pc-client-backend.md 14.2），本表仅列客户端团队角色，前端团队需指定 1 名接口对接人负责契约对齐与联调。

### 15.4 P0 一期按周任务分解（v1.2新增）

> 26 周总周期；**加粗周次为前后端联调节点**（对应后端 L1~L4 窗口，见 pc-client-backend.md 14.1/14.3）。后端 20 周先交付，前端按里程碑接入，故 L1~L3 落在后端开发期内、L4 与前端 M9 同步。

| 周次 | 里程碑 | 可交付任务 | 联调/外部节点 |
|------|--------|-----------|----------------|
| W1 | M1 | 工程骨架与目录结构（1.2）、IPC 通道表 + preload 白名单（1.3）、安全策略 CSP（1.3） | **外部前置启动**：Windows 代码签名证书、Apple Developer + 公证账号申请（阻塞 M9，见15.6） |
| W2 | M1 | API 请求层（1.5：主进程代签、1002 single-flight、幂等 request_id）、WSS 统一客户端、日志崩溃（1.6）、配置下发（1.7）、electron-builder 正确配置（1.4） | **联调准备**：与后端确认契约 v1.0（后端 W1-W3），mock 服务与契约缺口清单签字（15.6 A） |
| W3 | M2 | 登录页与四种 login_type（2.1）、记住密码/safeStorage（2.2）、多账户切换（2.3） | mock 数据先行 |
| W4 | M2 | 首页 BFF 聚合（第三章）、商品列表/详情/收藏（第四章） | mock 数据先行，接口契约复核 |
| **W5** | M2 | 登录/商品接真接口：device_info 上报、HMAC 签名联调、require_verify 二次验证 | **L1 联调开始**（后端 W5：登录单接口、商品详情） |
| **W6** | M2 | 搜索/筛选/历史（第五章）、购物车 REST 契约（第六章）、未登录购物车合并 | L1：购物车 `/cart/items/*`、search/suggest |
| **W7** | M2 | 结算页与发票（7.1）、扫码支付 + 支付轮询（7.3）、request_id 幂等验证 | L1：下单/支付、BFF order-confirm、order_no 契约 |
| W8 | M2 收口 | 剩余用例回归、接口缺口二次确认 | **L1 收口**，缺口未闭项转 15.6 A 跟踪 |
| W9 | M3 | 订单列表/详情/Tab 映射（8.1/8.2）、取消/确认收货 | 后端订单服务已交付，接真接口 |
| W10 | M3 | 物流跟踪与订阅（8.2）、订单评价（8.6） | 后端 W10 物流提醒（L2 前置） |
| W11 | M3 | 售后一期收敛为「仅退款」（8.5）、退款状态回显 | 依赖后端 4.6 契约；换货/维修标记二期 |
| W12 | M3 收口 | 交易链路 e2e、幂等/超时关单（`pay_deadline`）用例 | **交易联调收口**，支付压测用例交后端 W18 |
| W13 | M4 | 个人中心、账户安全、地址管理（9.1/9.2） | 后端会员服务（W8）已交付 |
| W14 | M4 | 收藏夹与标签（9.4）、设备管理与隐私设置（9.7） | 收藏夹契约对齐（后端 6.3） |
| W15 | M4 | 优惠券（9.5，mock→真接口）、积分签到/兑换（9.6）、用户等级入口隐藏 | **优惠券接口状态确认点**（后端 14.5 A：营销服务契约 W8 已出） |
| W16 | M5 | 消息中心与 Tab→type 映射（第十章）、未读数 | **L2 已就绪**（后端 W9-W11），接 WSS topic |
| W17 | M5 | 在线客服悬浮窗、工单、断线重连补偿（第十一章） | L2：客服会话、消息状态（失败重试） |
| W18 | M5 | 通知策略、推送开关、免打扰、深链路由 | **L2 收口** |
| **W19** | M6 | 自动更新（1.4：方案A/B、blockmap 差量、强制更新、签名校验） | **L3 联调**（后端 L3 窗口 W13-15 已就绪）：更新协议方案A/B **须在后端 W12 前定稿**（阻塞项，见15.6 A） |
| W20 | M6 | 托盘、快捷键、桌面通知、单实例/深链、开机自启（第十四章） | 更新成功率与崩溃上报（1.6）对齐后端 8.5 |
| W21 | M7 | 本地缓存与网络检测、离线浏览（12.1~12.3） | 后端 sync（W14）已交付 |
| **W22** | M7 | 离线队列 IndexedDB、冲突解决 UI、cursor 同步 | **L3 侧收口**（后端 sync 为 W14）：sync 不重不漏、离线上传幂等 |
| W23 | M8 | 启动优化（13.1）、内存优化（13.2）、渲染优化（13.3/13.4） | 性能数据接入 13.5，为灰度放量供数 |
| W24 | M8 | 性能监控（13.5）、弱网与低配机型适配、无障碍检查 | **L4 准备**：与后端 W17 联调收口同步 |
| **W25** | M9 | 集成/e2e 测试、多平台兼容矩阵、安全渗透（CSP/safeStorage/IPC）、代码签名与公证入 CI、三平台安装包与升级回滚演练 | **L4 灰度联调**（后端 W19）：灰度通道、强制更新、回滚对齐 |
| W26 | M9 | 灰度放量（崩溃率+性能达标）、正式发布、发布 SOP 与监控看板 | 全量（后端 W20），7 天观察期 |

### 15.5 里程碑验收标准（v1.2新增）

> 每条验收必须**可验证**（有用例记录/有产物/有数据看板），未达标不进入下一里程碑。性能指标真值以 13.5 监控表为准，容量口径以 `plans/00-架构总纲.md`（千万 DAU）为准。

| 里程碑 | 功能验收 | 打包/安装验收 | 更新验收 | 性能验收 |
|--------|---------|---------------|----------|----------|
| M1（W1-2） | 骨架可运行，IPC 白名单与 CSP 生效（越权 invoke 被拒） | 三平台 CI 构建成功，dev 安装包可安装启动 | updater 空跑：feedURL 可达、`checkForUpdate` 返回当前版本 | 记录冷启动基线（目标 < 4s 低配） |
| M2（W3-8） | 登录/首页/商品/搜索/购物车 e2e 通过率 100%；签名与 1002 刷新重放用例全绿 | nsis/dmg/AppImage 三平台安装包生成并**签名通过**，安装后首启正常 | — | 首屏 < 1s、交互响应 < 100ms（13.5），商品列表滚动 > 55fps |
| M3（W9-12） | 下单→支付→订单→仅退款 全链路 e2e；request_id 幂等重放不重复下单；`pay_deadline` 超时关单前端状态一致 | 安装包 < 100MB，增量包（blockmap）命中 | 支付轮询与 WS 终止事件不重复触发 | 结算聚合接口本地渲染 < 500ms，内存 < 200MB |
| M4（W13-15） | 个人中心/地址/收藏/优惠券 用例通过率 100%；mock→真接口切换无回归 | 同上，包体无回退 | — | 页面切换 60fps，无内存增长泄漏（30min 操作监测） |
| M5（W16-18） | WSS 单连接多 topic、断线重连 + seq 去重补偿验证；客服消息失败重试可恢复 | 同上 | — | 未读数接口本地渲染 < 100ms，24h 长连接无异常断连 |
| M6（W19-20） | 自动更新全流程：检查→差量下载→签名校验→安装；强制更新可阻断主界面 | 三平台安装包/升级包签名与公证全部通过，**安装包 < 100MB** | **自动更新成功率 ≥ 99%（灰度内），校验失败自动回滚，版本黑名单生效** | 更新下载不阻塞 UI，后台限速可配 |
| M7（W21-22） | 断网可浏览缓存内容、离线队列重放幂等、冲突提示可人工解决 | 同上 | 更新包在断网后恢复可续传 | 冷启动 < 2s（优）/ < 3s（中）、热启动 < 500ms |
| M8（W23-24） | 性能监控埋点全量上报、异常告警可触发 | 同上 | — | **冷启动 < 2s/< 3s/< 4s（优/中/低配）、热启动 < 500ms、内存 < 200MB、空闲 CPU < 1%、帧率 > 55fps** |
| M9（W25-26） | 安全渗透高危 0 项；灰度放量阈值（崩溃率、更新成功率）达标 | 三平台正式签名 + 公证全部通过，安装/升级/回滚演练各 1 次成功 | **灰度崩溃率 < 0.5%、更新成功率 ≥ 95%（后端 8.3.3 熔断阈值）方可放量** | 全量后 7 天性能指标不低于 M8 基线；对齐后端 P0 可用性 99.9%（千万 DAU 口径） |

### 15.6 依赖与前置（v1.2新增）

**A. 后端接口依赖清单（状态以 pc-client-backend.md 14.5 A 为唯一清单，本表为前端视角摘要，M2 开始前须书面确认）**

| 依赖项 | 状态 | 就绪/阻塞时间 |
|--------|------|---------------|
| 认证、商品、搜索、下单、支付、购物车接口 | ✅ 契约已定义 | 后端 W4-W7，**L1（W5）联调** |
| 会员、收藏夹、积分、WSS topic、客服、通知 | ✅ 契约已定义 | 后端 W8-W11，W16 起联调 |
| 更新协议方案 A/B、sync cursor、离线上传、崩溃上报 | ⏳ 方案定稿 + 接口已定义 | **方案须在后端 W12 前定稿，阻塞 M6（W19）** |
| 优惠券列表/领取/详情 | ⏳ 共享营销服务契约确认 | 一期 UI 可 mock 先行，M4（W15）前确认 |
| 短信/扫码/第三方登录补充接口 | ⏳ 认证服务确认是否一期提供 | **M2 开始前（W2）确认**，否则一期仅密码登录 |
| `stock-updates` WS 推送、批量库存 | ⛔ 一期降级为详情校验 + 60s 轮询 | P2 |
| 地址 lat/lng 地图选点、用户等级/实名认证、售后换货/维修、Windows Hello、信用卡/组合支付 | ⛔ 一期不实现 | P2 / 二期，见各章节标注 |

**B. 外部依赖与前置条件**

| 依赖 | 类型 | 需就绪时间 | 阻塞对象 |
|------|------|-----------|----------|
| Windows 代码签名证书（OV/EV）、Apple Developer Program + 公证、macOS 签名密钥 | 外部账号/证书 | **W1 启动申请，M1（W2）打通入 CI** | 全部分发（M9），审核周期不可控 |
| 公证被拒缓冲期、三平台打包白名单 | 流程 | M1 | M9 上线 |
| 更新 CDN 与 feedURL（`https://cdn.example.com/updates/{channel}/`） | 三方服务 | M6 前（W19） | 自动更新 |
| 后端契约 v1.0 冻结与 mock 服务 | 内部协作 | **W2** | M2 全部开发 |
| `plans/00-架构总纲.md`（千万 DAU 口径）与后端 13.4 容量基线 | 文档口径 | W1 | 性能验收阈值取值 |
| 微信/支付宝/银联商户资质与回调证书（后端持有） | 三方资质 | 后端 W4 前 | 扫码支付联调（W7） |
| 域名、ICP 备案、TLS/wss 证书 | 合规 | W2 前 | 全部联调环境 |
| 安全渗透测试授权与排期 | 外部/内部 | W24 前锁定 | M9 验收 |
| Electron LTS 版本与 Chromium CVE 订阅 | 供应链 | 持续（高危 72h 内热修复） | 安全基线（16.1） |
| UI 设计稿与设计规范交付 | 内部 | M2 前（W2） | 页面开发 |

---

## 十六、风险与应对

### 16.1 技术风险

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| Electron打包体积大 | 用户体验差 | files白名单精简、排除sourcemap、7z最大压缩；安装包目标 < 100MB |
| Chromium高危CVE | 安全漏洞 | 固定Electron LTS版本，订阅安全公告，高危72小时内热修复发布 |
| 内存泄漏 | 应用卡顿 | 定期内存监控（app.getAppMetrics），Chrome DevTools排查，WS/定时器统一生命周期管理 |
| 跨平台兼容性问题 | 部分功能不可用 | 平台差异代码隔离（platform/目录），CI 三平台构建冒烟，兼容矩阵测试 |
| 自动更新失败 | 用户无法更新 | 更新协议方案A/B提前定稿，多更新源CDN，校验失败回滚，手动下载兜底 |
| 代码签名/公证被拒 | 无法分发安装 | 证书与公证流程在M1阶段打通并纳入CI，预留审核缓冲期 |
| IPC通信性能 | 响应延迟 | 大数据传输使用结构化克隆/文件传递，避免高频大payload IPC |
| WSS协议未定义 | 推送功能延期 | 1.5节统一WS客户端先行，REST轮询兜底，推送通道与后端协商topic规范 |
| 渲染进程XSS窃取Token | 账户泄露 | CSP+签名密钥不进渲染进程+safeStorage，安全渗透测试（M9） |

### 16.2 业务风险

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 高并发下单 | 系统崩溃 | 限流、排队、库存预扣 |
| 支付超时 | 订单状态不一致 | 定时任务补偿，支付结果轮询 |
| 图片加载慢 | 用户体验差 | CDN加速、图片压缩、懒加载 |
| 搜索无结果 | 用户流失 | 搜索建议、推荐商品、热门搜索 |

### 16.3 应对策略

- 灰度发布：新版本先向5%用户推送，观察稳定性
- 回滚机制：发现问题可快速回滚到上一版本
- 监控告警：关键指标异常时自动告警
- 用户反馈：内置反馈入口，收集用户问题

---

## 十七、总结

本计划覆盖了PC客户端前端开发的全部核心模块，从架构设计到具体功能实现，从桌面特有功能到性能优化，为开发团队提供了清晰的技术路线图。

**关键成功因素**：
1. 选择Electron保证跨平台一致性，降低维护成本
2. 接口契约与 pc-client-backend.md 全面对齐（v1.1），缺口接口显式标注并纳入排期管理
3. 模块化开发，各模块独立交付，降低风险
4. 优先保证核心交易流程稳定性
5. 充分利用桌面特性提升用户体验（更新/托盘/深链/通知）
6. 安全基线前置（safeStorage、请求签名、CSP、代码签名），持续性能监控和优化

---

## 审查记录

### v1.0 → v1.1 修改记录（2026-10-11）

| 编号 | 类型 | 问题描述 → 修改位置与内容 |
|------|------|--------------------------|
| 1 | 契约对齐 | 全文 API 缺 `/v1` 前缀（后端统一 `/api/v1`）→ 二至十二章所有数据依赖路径统一加 `/v1`，文件头补充关联文档与统一响应格式说明 |
| 2 | 契约对齐 | 登录接口前后端不一致（前端拆6个接口，后端为单一 `login_type` 接口）→ 2.1 数据依赖重写为 `POST /api/v1/auth/login` 单入口，四种 login_type 列表，缺失的短信发送/扫码/第三方回调接口标注"待后端补充" |
| 3 | 完整性 | 登录缺图形验证码与 device_info 上报、新设备二次验证流程 → 2.1 关键组件新增 CaptchaInput，交互细节补 device_info 获取方式与 `require_verify` 二次验证流程 |
| 4 | 安全性 | 2.2 记住密码方案存在致命缺陷（PBKDF2 salt 每次随机导致永远无法解密、机器指纹不稳定、extractable 自相矛盾），且与后端 DPAPI/Keychain 契约冲突 → 2.2 整体重写为"记住账号 + safeStorage 加密 refresh token 自动登录"，附 v1.0 方案废弃说明 |
| 5 | 契约对齐 | 2.3 切换账户接口后端不存在（`/api/accounts/recent`、`/auth/switch-account`）→ 改为复用 `POST /api/v1/auth/refresh` + 本地账户列表 + `GET /api/v1/devices`，补充4步切换流程；删除残留的空"关键组件"标题 |
| 6 | 可行性 | 2.4 Windows Hello 方案不可行（Electron 无法直接调用 UWP API）、条目重复 → 修正为原生 addon 路径说明，标注 P2 可选、一期降级 PIN；删除重复 bullet |
| 7 | 性能 | 首页未用后端 BFF 聚合接口（12.1.1）→ 3.2 数据依赖改为 `POST /api/v1/bff/home` + 推荐分页；推荐理由接口后端无，标注隐藏兜底 |
| 8 | 契约缺口 | 3.3 活动接口后端未定义 → 标注"待后端/运营后台补充"，提示 client_config 下发兜底方案 |
| 9 | 可行性 | 3.4 "调用系统语音识别API"不可行 → 改为 Web Speech API + Linux 降级隐藏 |
| 10 | 契约对齐 | 4.1/4.2 商品接口不一致（`/skus`、`/related`、`/filters`、`/favorites/toggle` 后端不存在）→ 对齐后端：SKU 随详情返回、推荐走 `scene=product_detail`、收藏改 add/remove + check、筛选聚合标注缺口 |
| 11 | 契约缺口 | 收藏夹（folder）后端无字段与接口 → 4.4/9.4 标注缺口，一期降级为单一列表 + 标签 |
| 12 | 契约对齐 | 5.1 搜索接口不一致（`/api/search`、`/suggestions`、`/hot`、history 接口）→ 对齐 `GET /api/v1/search/products` 与 `suggest`（热词在响应内），历史经 sync 通道 |
| 13 | 一致性 | 5.4 搜索历史自相矛盾（"仅本地不上传" vs "可选同步"）与条数混乱（20/50）、冗余编辑残留句 → 统一为"本地20条默认不同步，开启同步后服务端50条"，删除冗余句 |
| 14 | 契约对齐 | 6.1 购物车接口动词/路径不一致（POST update-quantity 等）→ 对齐 REST 风格 `PUT/DELETE /api/v1/cart/{sku_id}`；select-all/summary 标注为前端模拟或后端扩展；补未登录本地购物车登录合并（后端5.2）；店铺分组标注后端表结构缺口 |
| 15 | 契约缺口 | 6.4 库存 WS 通道与批量库存接口后端未定义 → 降级为详情校验 + 60s 轮询，推送标注 P2 |
| 16 | 契约对齐 | 7.x 支付/结算接口不一致（`/api/payment/pay`、`/api/checkout`、`/api/payment-methods` 不存在）→ 对齐 `POST /api/v1/orders/{order_no}/pay` 与 BFF order-confirm；支付方式表增加 pay_type 列并标注信用卡/组合支付为 P2 |
| 17 | 完整性 | 结算缺发票模块（后端下单含 invoice_info、有 invoice 表）→ 7.1 新增 InvoiceSection（个人/企业/税号） |
| 18 | 一致性 | 订单备注 200字 vs 50字 矛盾 → 统一为 200 字（7.1/7.4） |
| 19 | 契约对齐 | 7.2 地址接口前缀不一致 → 统一 `/api/v1/user/addresses`；地图选点标注后端 lat/lng 字段缺口（P2） |
| 20 | 完整性 | PC 扫码支付缺结果确认机制 → 7.3 补支付轮询流程（轮询订单状态 + WS 事件终止），补幂等 request_id 与超时释放库存说明 |
| 21 | 契约对齐 | 8.1 订单接口用 `:id` 而后端用 `{order_no}` → 全部替换为 order_no；补后端已有的删除订单接口；新增 Tab 与后端状态枚举映射表；WS 通道统一 |
| 22 | 契约缺口 | 8.5 售后 `/api/after-sales` 系列后端不存在且 refund 表不支持换货/维修 → 标注契约差距，一期收敛为仅退款 |
| 23 | 完整性 | 有"去评价"入口但全文无评价模块（后端有完整 reviews 接口）→ 新增 8.6 订单评价（组件树 + 接口对齐） |
| 24 | 契约对齐 | 8.2/8.3 物流接口（按运单号公开查询、logistics-updates 通道）后端无 → 统一走 `GET /api/v1/orders/{order_no}/logistics` + 物流订阅接口 + WSS topic |
| 25 | 契约缺口 | 9.2 用户等级、实名认证后端无 → 标注 P2，一期隐藏入口；补账户安全接口（后端6.1） |
| 26 | 契约对齐 | 9.3/9.5/9.6 地址/优惠券/积分接口不一致或缺失 → 地址对齐 user 前缀；优惠券整组标注"待后端营销服务补充"（一期 mock 先行）；积分对齐 `/user/points`，签到/兑换标注缺口 |
| 27 | 完整性 | 后端有设备管理接口（devices/bind/unbind/push）前端无对应功能 → 新增 9.7 设备管理与隐私设置 |
| 28 | 契约对齐 | 10.1 消息资源名不一致（messages vs notifications）、Tab 分类与后端 type 枚举不匹配、左滑为移动端交互 → 对齐 notifications 接口组，新增 Tab→type 映射表，改为右键/勾选交互 |
| 29 | 契约缺口 | 10.4 促销推送接口后端不存在 → 改为 WS + notification 持久化，频率控制归服务端策略 |
| 30 | 平台差异 | 10.5 "通知5秒自动消失"跨平台不可实现、Windows Toast 需 appUserModelID → 修正为平台差异描述 |
| 31 | 契约对齐 | 11.x 客服接口不一致（复数/单数、`/api/tickets` 前缀、queue/history/export/rate 接口缺失）→ 全面对齐后端 10.1/10.2，缺失项标注；补 WS 断线重连补偿与消息状态（失败重试） |
| 32 | 格式错误 | 11.4 关键组件代码块缺起始 ``` 标记 → 修复，明确悬浮窗为同窗口子组件实现 |
| 33 | 契约对齐 | 12.4 离线队列接口不存在（`/api/sync/offline-actions`）→ 对齐 `POST /api/v1/sync/offline/upload` + status 查询；补 op_id 幂等、保序、退避重试、死信处理；localStorage 改 IndexedDB |
| 34 | 错误API | 12.3 `net.isOnline()` 不存在 → 改为 net.request HEAD 探测 + powerMonitor resume 事件 |
| 35 | 一致性 | 12.1 "进程刷新"错字、同步接口未引用后端 sync/pull|push|resolve|full|status → 修正错字，12.3 数据同步策略对接后端第九章 |
| 36 | 技术错误 | 1.4 electron-builder 配置为错误 JSON 结构（文件名 yml、product/build 嵌套错乱、`build: null`）→ 重写为正确 YAML（nsis/dmg targets、公证、publish generic、签名开关） |
| 37 | 架构矛盾 | electron-updater 元数据协议与后端自定义 version/check JSON 不匹配，差量机制 blockmap vs bsdiff 双轨 → 1.4 新增"协议适配"方案A（推荐后端产出 latest.yml）/方案B（自研），明确差量二选一；补强制更新阻断实现、代码签名校验、公证前置条件 |
| 38 | 完整性 | 后端有 crash 服务与日志规范，前端无日志/崩溃上报模块 → 新增 1.6 日志与崩溃上报（electron-log 轮转、minidump、面包屑、符号表归档、隐私开关） |
| 39 | 完整性 | 后端有 client_config 配置下发服务，前端无对应逻辑 → 新增 1.7 配置下发与功能开关（缓存兜底、WS 热更新） |
| 40 | 完整性 | 后端要求 HMAC 请求签名与统一错误码，前端 services 层未设计 → 新增 1.5 API请求层（签名经主进程代签、1002 single-flight 刷新重放、5003 二次验证、幂等 request_id、支付不自动重试、统一 WSS 客户端：单连接多 topic、心跳、指数退避、seq 去重补偿） |
| 41 | IPC安全 | 1.3 通道表混淆 invoke/event、storage 无边界、缺 preload 示例与导航/窗口拦截 → 通道表重写（方向标注、命名空间限制、补 updater 事件/深链/主题/第二实例/dialog/sign-request），新增 contextBridge 白名单示例，安全策略补 sandbox/webviewTag/setWindowOpenHandler/permission handler/CSP 补全 connect-src 与 CDN 域（原 CSP 会拦截全部图片与接口） |
| 42 | 完整性 | 缺单实例锁与深链实现细节 → 1.3 主进程职责补充；14.5 新增 deep link 代码（setAsDefaultProtocolClient、open-url、second-instance argv 透传、protocols 配置、未登录补路由） |
| 43 | 桌面特性 | 托盘角标/点击事件平台差异、缺关闭行为设置 → 14.1 补 Windows 换图标绘制数字、macOS Dock badge、关闭最小化到托盘设置项与首次气泡提示 |
| 44 | 桌面特性 | 14.2 全局快捷键危险且与系统冲突（Ctrl+Shift+Q 退出、抢占组合键）→ 重写为应用内/全局分层表，全局默认仅消息中心开启、可自定义+冲突检测，补 unregisterAll 与截图 P2 说明 |
| 45 | 平台差异 | 14.3 菜单为 Windows 风格，macOS 需 App Menu/角色菜单 → 补平台差异说明与生产环境移除 devtools |
| 46 | 桌面特性 | 开机自启无实现 → 14.5 补 setLoginItemSettings 代码（默认关闭、静默启动到托盘） |
| 47 | 性能 | 13.2 FixedSizeList 误用于瀑布流（不等高）→ 改为 VirtuosoMasonry + 分页窗口说明 |
| 48 | 过时方案 | 13.4 "非活跃窗口降低渲染帧率"不可实现、"禁用Flash"过时；13.5 electron-process-manager 已停维护 → 改为 backgroundThrottling/AppMetrics/PerformanceObserver，补灰度放量以崩溃率+性能为依据 |
| 49 | 排期 | 前端26周与后端20周无联调依赖说明、人员表与后端团队重复（都写了后端开发）→ 新增 15.2 后端联调依赖矩阵与接口缺口确认节点；15.3 人员表改为客户端团队角色并注明接口对接人；M9 补安全渗透与代码签名公证 |
| 50 | 风险 | 16.1 风险表缺失关键项 → 补 Chromium CVE 跟进、签名/公证被拒、WSS 协议未定义、XSS 窃取 Token 等风险与应对 |
| 51 | 一致性 | 1.1 安全性对比表述不准确（Electron 亦为多进程隔离）→ 修正为攻击面与配置成本维度对比；推荐理由补 Electron 安全更新跟进承诺 |
| 52 | 完整性 | 1.2 目录结构缺 native/logger/protocol 等目录 → 补充原生模块、日志崩溃、协议处理目录及 e2e 测试说明 |
| 53 | 文档规范 | 文档缺版本/日期/关联文档元信息 → 头部补充（对齐后端文档格式） |

---

## 实施手册修订记录

### v1.1 → v1.2（2026-10-11，实施手册修订）

| 编号 | 类型 | 修改内容 |
|------|------|---------|
| 1 | 口径降级 | 目标由「1 亿在线」降级为**千万 DAU**：文档头「关联文档」补 `plans/00-架构总纲.md`（DAU 1000 万 / 峰值在线 200 万 / 网关入口 60 万 QPS / 应用读 50 万写 8 万 / 订单峰值 5 万笔/s / MySQL 16 库 64 表 / RabbitMQ 不迁移 / Redis 40～60 节点 / 可用性 P0 99.9%→P1 同城双活 99.95%，**不做单元化、异地多活、OceanBase**）；验收阈值（15.5）按该口径取值 |
| 2 | 亿级残留清理 | 全文 grep `architecture/`、`00-conflict-register`、`RocketMQ`、`OceanBase`、`单元化`、`1亿`、`亿级`、`异地多活`：正文无亿级容量数字与失效引用，仅头部补口径说明并指向 `plans/00-架构总纲.md`（历史亿级表述见 git 历史 v1 中 architecture/）；**保留不改**：`pay_deadline`、`edge-gateway`、缓存 Key 四段式、订单 8 值状态机、`order_no` 24 位编码 |
| 3 | 实施手册化 | 15.1 里程碑补验收/分解/依赖指引；新增 **15.4 P0 一期按周任务分解（W1~W26，标粗联调节点 L1~L4，含证书申请与协议定稿等外部节点）**、**15.5 里程碑验收标准（M1~M9 × 功能/打包安装/更新/性能四维，性能值引自 13.5）**、**15.6 依赖与前置**（后端接口依赖清单 7 类状态 + 外部依赖 10 项就绪时间与阻塞对象，清单以 pc-client-backend.md 14.5 A 为准） |
| 4 | 版本对齐 | 文档头版本 v1.1 → **v1.2（实施手册版）** |

---


---

*文档版本：v1.2*
*最后更新：2026-10-12（文档一致性终审，见 00-架构总纲.md 文末《文档一致性终审记录》）*
