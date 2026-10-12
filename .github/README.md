# GitHub Actions CI

工作流文件：[`workflows/ci.yml`](workflows/ci.yml)（单文件，含 5 个 job）。

## 一、为什么需要它

本项目在 **Windows** 上开发，带来两个结构性验证缺口：

| 缺口 | 本机能不能验证 | 由哪个 CI job 兜底 |
|------|--------------|-------------------|
| **iOS 从未被 Xcode 编译过** | ❌ 无 Xcode / Swift 工具链 | **`ios-macos`（macos-15）** |
| 五端各自能 build，但缺回归网 | ⚠️ 只在改动时手工验 | `backend` / `frontend-web` / `miniprogram` / `android` |

`ios-macos` 是这套 CI 存在的**首要理由**。其余 4 个 job 是常规回归网。

## 二、触发方式

| 触发 | 条件 |
|------|------|
| `push` | 推到 `main` 或 `master` |
| `pull_request` | 任意 PR |
| `workflow_dispatch` | 手动（Actions 页点 **Run workflow**） |

手动触发时有一个开关：

- **`skip_ios`**（默认 `false`）——勾上则跳过 `ios-macos`，省 macOS 额度。
  额度紧张时可用，但 iOS 是当前唯一的真实编译验证手段，**不建议长期开着**。

### 并发与取消

- PR 上有新 push → **自动取消**旧的同分支 run（省额度）。
- `main`/`master` 与手动触发 → **不取消**。首次 iOS 验证很贵，绝不能被后来的 push 掐断。

## 三、各 job 验证什么

### 1. `backend` — `ubuntu-latest`，JDK 17，timeout 45min

`server/` 13 个 Maven 模块：

1. `mvn -B -ntp test` —— 全量单测（53 个测试文件）
2. `mvn -B -ntp -DskipTests package` —— 打出可执行 jar
3. 产物：`backend-jars`（`server/**/target/*.jar`，即各服务启动包 + `common`）、
   `backend-surefire-reports`（Surefire XML，`if: always()`，红了也能下载看）

说明：

- **没有 Maven wrapper**，直接用 runner 预装的 Maven 3.9.x（DEV.md 里「必须用全路径 `mvn.cmd`」是 Windows PATH 问题，与 CI 无关）。
- `MAVEN_OPTS` 钉了 `-Dfile.encoding=UTF-8`：Windows 上是 GBK，Linux 上是「跟随 locale、可能退化成 ASCII」，坑的形态不同但同样会让中文日志/测试输出乱码。
- 用 `-B -ntp` 而不是 DEV.md 里的 `-q`：`-q` 会把模块级 INFO 一起吞掉，CI 上看不出「挂在哪个模块」。
- **Maven 镜像可选**：GitHub 托管 runner 在美国机房，直连 Central 正常，默认不配。
  需要时在仓库 **Settings → Secrets and variables → Actions → Variables** 新增
  `MAVEN_MIRROR_URL = https://maven.aliyun.com/repository/public`，workflow 会自动写入 `~/.m2/settings.xml`，**不需要改本文件**。

### 2. `frontend-web` — `ubuntu-latest`，Node 22 + pnpm 12.6.0，timeout 25min

`web-admin` / `web-buyer` **并行 matrix**（`fail-fast: false`，一端挂了仍要看另一端结果）。

每个目录依次跑：`pnpm install --frozen-lockfile` → `pnpm build`（`tsc -b && vite build`）→ `pnpm lint`（ESLint）→ `pnpm test`（vitest run）。
产物：`<app>-dist`。

> **为什么锁 pnpm 12.6.0**（和本机一致）：`pnpm-workspace.yaml` 用了 pnpm 12 的 `allowBuilds` 字段
> 放行 esbuild 的构建脚本。pnpm 11 及更早不认这个字段名 → esbuild 的 postinstall 被拦 →
> Vite 构建报错。pnpm 版本错了红的是这里，跟业务代码无关。

### 3. `miniprogram` — `ubuntu-latest`，Node 22 + pnpm 12.6.0，timeout 25min

`pnpm install` → `pnpm type-check`（`vue-tsc --noEmit`）→ `pnpm build:h5` → `pnpm build:mp-weixin`。
产物：`miniprogram-dist`（`dist/build/{h5,mp-weixin}`，mp-weixin 可直接导入微信开发者工具）。

> 这里用普通 `pnpm install` 而**不是** `--frozen-lockfile`：uni-app 的 peer 依赖树很脆，
> 本 job 目的是验证「H5 + mp-weixin 两份产物都能编译出来」，不承担 lockfile 门禁职责。

### 4. `android` — `ubuntu-latest`，JDK 17 + Gradle 8.4，timeout 50min

`gradle :app:assembleDebug --no-daemon`，产物 `app-debug.apk`。

关键点（都读自 `android-client/` 的真实配置）：

- **Gradle 8.4 显式安装**：仓库**没有 gradle wrapper**（`gradlew` 与 `gradle/wrapper/` 都不入库），
  必须靠 `gradle/actions/setup-gradle` 带 `gradle-version: 8.4`。依据是 `README.md`「Gradle 8.4+（AGP 8.2.2）」，
  且本机验证过的就是 8.4（`.gradle/` 下有 `8.4` 目录）。锁死版本避免 Gradle 大版本升级带来的无谓差异。
- **SDK 版本**：`platforms;android-34` + `build-tools;34.0.0`，取自 `app/build.gradle.kts` 的
  `compileSdk = 34` / `buildToolsVersion = "34.0.0"`。
- **`local.properties` 必须现写**：该文件被 `.gitignore` 忽略（不入库），CI 用
  `ANDROID_HOME` 环境变量现写 `sdk.dir`，并补 `baseUrl=http://10.0.2.2:8080/`（编译期无影响，只影响 `BuildConfig.BASE_URL`）。

### 5. `ios-macos` — `macos-15`，timeout 45min ⭐

`brew install xcodegen` → `xcodegen generate` → `xcodebuild` → **无论成败都上传日志与 xcresult**。

**配置选择的依据**（都核对自 `ios-client/project.yml`）：

| 项 | 值 | 依据 |
|----|----|------|
| scheme | `EasyShopping` | `project.yml` 中 `name: EasyShopping`、target 名 `EasyShopping`、且 `scheme:` 段显式声明。XcodeGen 生成的 scheme 与 target 同名 |
| project | `EasyShopping.xcodeproj` | 同上，由 `xcodegen generate --spec project.yml` 现场生成（`.xcodeproj` 已 gitignore） |
| destination | `generic/platform=iOS Simulator` | **不挑具体机型/系统版本**。`project.yml` 的 `deploymentTarget.iOS = 16.0` 只是编译下限，选 `generic` 就不受 runner 上模拟器 runtime 版本漂移影响，也不需要启动模拟器。CI 只验「能不能编译过」，不验运行 |
| `-configuration Debug` | | 与 `project.yml` `scheme.run.config: Debug` 对齐 |
| 签名 | `CODE_SIGNING_ALLOWED=NO` `CODE_SIGNING_REQUIRED=NO` `CODE_SIGN_IDENTITY=""` | 无开发者账号的 CI 上必须显式关掉；`project.yml` 里 `CODE_SIGN_STYLE: Automatic` 是给本地真机调试留的 Team 选择位 |
| 日志格式 | `2>&1 | tee`，**不接 xcpretty** | xcpretty 会重排/截断原始 `文件:行:列: error: 描述` 行，正好破坏「抓前 50 条 error」这个目的 |

`-derivedDataPath` 指向 `$RUNNER_TEMP/DerivedData` 并用 `actions/cache` 缓存，key 含
`hashFiles('ios-client/**/*.swift', 'ios-client/project.yml')` —— 任一源码或工程规格改动即失效。

## 四、iOS 首次运行预期（重要）

> ### ⚠️ `ios-macos` 第一次跑**大概率是红的。这是预期结果，不是 CI 配错了。**

`ios-client/README.md` 文首已经写明：**「⚠️ 编译状态声明：未经 Xcode 编译验证。」**
43 个 Swift 文件此前只做过「括号配对 + 符号引用闭环」这类结构化静态自查，**一行都没被编译器看过**。
真实的首批编译错误几乎一定会出现（典型类别：SwiftUI 属性名/可用性标注、闭包签名推断、`Decimal` 运算、
`@MainActor` 隔离标注、协议 witness 不匹配……）。

**这个 job 的价值正是把这些错误一次性抓出来，而不是把 CI 弄绿。**

### 失败了怎么把日志喂回来

1. 打开 run → `ios-macos` job → 底部 **Job Summary** 已自动列出：
   - `error:` 行总数
   - **前 50 条** `文件:行:列: error: 描述`（可折叠）
2. 需要更完整的信息（warning、整个模块的上下文、xcresult 的 issue navigator）时，
   下载 artifact **`ios-build-log`**，其中含：
   - `xcodebuild.log` —— 完整原始构建日志
   - `EasyShopping.xcresult` —— 可用 Xcode 打开（`File ▸ Open`）看结构化 issue 列表
3. 把 error 摘要贴回给实现方修，**修的是 `ios-client/EasyShopping/**.swift` 源码**。
   修完再 push，CI 重跑，直到 `ios-macos` 变绿。
4. 修的时候别只改到「不报错」为止——注意别为了过 CI 去改 `project.yml` 的
   `SWIFT_STRICT_CONCURRENCY: minimal` 或 `SWIFT_VERSION: "5.0"`（那是刻意留的迁移决策锚点，见文件内注释）。

### 少量常见非源码原因（先排掉这些再怀疑代码）

| 现象 | 原因 | 处理 |
|------|------|------|
| `Multiple commands produce ... Info.plist` | 用了旧工程或 `Resources/Info.plist` 被当成资源重复拷贝 | 本仓库 `project.yml` 已 `excludes` + `GENERATE_INFOPLIST_FILE: NO`，若出现说明 `xcodegen generate` 没生效，确认跑在 `ios-client/` 目录下 |
| `xcodebuild: error: The workspace named ... does not contain a scheme` | scheme 名对不上 | 看同 job 内「列出工程 target / scheme」步骤的 `xcodebuild -list` 输出 |
| 依赖下载超时 | runner 网络偶发 | 直接 re-run；`.xcodeproj` 是零三方依赖（无 SPM/CocoaPods），不该出现依赖问题 |

## 五、macOS 额度（务必留意）

GitHub 的 macOS runner 是**按倍数系数计费**的：

| 仓库类型 | macOS 额度 |
|---------|-----------|
| **公开仓库** | **免费无限** |
| **私有仓库 · Free 计划** | **约 200 分钟/月**（10× 倍率；Linux 2000 分钟、macOS 200 分钟是分开的池子） |

`ios-macos` 单次正常耗时量级：**5～15 分钟**（首跑含 brew 装 XcodeGen + 全量编译会更久；DerivedData 缓存命中后更快）。

也就是说，私有 Free 仓库一个月大概能跑 **15～30 次** iOS 构建。省额度的做法，按推荐顺序：

1. **别在每个 push 上跑** —— 当前 `concurrency` 已经在 PR 上自动取消旧 run；
2. **合并前才跑** —— 必要时把触发条件收窄到只对 PR / `master` 生效（默认已是如此）；
3. **额度告急时用 `workflow_dispatch` 的 `skip_ios` 开关**，手动跑其余 4 个 job，iOS 攒着跑；
4. **仓库转公开** —— iOS 这类验证就不再计费了。

## 六、通用约定

- **最小权限**：workflow 级 `permissions: contents: read`，所有 job 只读代码仓，不写、不评论、不取 secret。
- **超时**：backend 45min / frontend-web 25min / miniprogram 25min / android 50min / ios-macos 45min，均留足余量又不会因 runner 卡死而空烧额度（`timeout-minutes` 一到就取消 job，不再计费）。
- **缓存**：全部用官方 cache action，key 均含依赖锁文件哈希——
  `setup-java`（`~/.m2`，key = 全部 `pom.xml` 哈希）、
  `gradle/actions/setup-gradle`（`~/.gradle`，key = Gradle 配置哈希）、
  `setup-node` 的 `cache: pnpm`（pnpm store，key = `pnpm-lock.yaml` 哈希）、
  `actions/cache`（DerivedData，key = `**/*.swift` + `project.yml` 哈希）。
- **产物体积**：Android APK / iOS xcresult 较大，`retention-days` 已相应设置（7 / 14 / 30 天）。

## 七、本 CI 不验证什么

避免误读绿灯：

- ❌ iOS **运行**（UI 自动化、真机/模拟器交互、性能）——只验编译。
- ❌ 后端**集成**（多服务同时起、跨服务链路）——`mvn test` 里已有的集成测试除外；
  跨服务链路目前靠 `server/scripts/smoke.ps1` 在本机跑（DEV.md §1）。
- ❌ 安卓 release 签名 / iOS 真机归档（Archive）——CI 只出 debug / 模拟器构建，无签名凭据。
- ❌ 小程序在微信开发者工具里的实际运行——只验能编译出 `mp-weixin` 产物。
- ❌ 性能与容量（`plans/00-架构总纲.md` 的千万 DAU 口径）——CI 不做压测。