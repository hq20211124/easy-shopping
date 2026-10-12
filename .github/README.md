# GitHub Actions CI

工作流文件：[`workflows/ci.yml`](workflows/ci.yml)（单文件，含 6 个 job）。

## 一、为什么需要它

本项目在 **Windows** 上开发，带来两个结构性验证缺口：

| 缺口 | 本机能不能验证 | 由哪个 CI job 兜底 |
|------|--------------|-------------------|
| **iOS 从未被 Xcode 编译过** | ❌ 无 Xcode / Swift 工具链 | **`ios-macos`（macos-15）** |
| **跨服务契约从没被自动验证过** | ⚠️ 只能手工跑 `server/scripts/smoke.ps1` | **`e2e-smoke`（ubuntu-latest）** |
| 五端各自能 build，但缺回归网 | ⚠️ 只在改动时手工验 | `backend` / `frontend-web` / `miniprogram` / `android` |

`ios-macos` 与 `e2e-smoke` 是这套 CI 存在的**首要理由**。其余 4 个 job 是常规回归网。

## 二、触发方式

| 触发 | 条件 |
|------|------|
| `push` | 推到 `main` 或 `master` |
| `pull_request` | 任意 PR |
| `workflow_dispatch` | 手动（Actions 页点 **Run workflow**） |

手动触发时有一个开关：

- **`skip_ios`**（默认 `false`）——勾上则跳过 `ios-macos`，省 macOS 额度。
  额度紧张时可用，但 iOS 是当前唯一的真实编译验证手段，**不建议长期开着**。
- **`skip_e2e`**（默认 `false`）——勾上则跳过 `e2e-smoke`，省 Linux 分钟。
  它验证的是跨服务契约（见 §三 的第 6 项），**不建议长期开着**。

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

### 6. `e2e-smoke` — `ubuntu-latest`，JDK 17 + PowerShell 7，timeout 30min ⭐

**这是本 job 存在的唯一理由，不要为了"让 CI 变绿"削弱它。**

`AUDIT/` 查出的两个资金级 P0 —— prepay 取了 `totalAmount` 而不是订单快照的 `payAmount`、
`refund` 是空实现 —— **都发生在被 `@MockBean` 掉的跨服务边界上**。
`mvn test` 全绿，但契约是错的。`server/scripts/smoke.ps1` 早就能在本机跑通 13 步 32 断言，
却一直只在本机跑 —— 也就是「跨服务契约」至今没有任何自动化保护。本 job 把它变成**每次 push 都验证**的东西。

#### 流程

| 步 | 动作 |
|----|------|
| 1 | `actions/checkout` + `setup-java`（temurin 17，不带 maven 缓存：这里不跑 `mvn`） |
| 2 | `actions/download-artifact` 取 `backend` job 上传的 `backend-jars`，解到 `server/` |
| 3 | 校验 11 个 jar 都在 `server/<svc>/target/<svc>-0.1.0-SNAPSHOT.jar`，打印 `java -version` / `pwsh --version` |
| 4 | `pwsh scripts/start-all.ps1 -ReadyTimeoutSec 600 -JvmArgs …` 起 11 个服务，`tee` 到 `logs/start-ci.txt` |
| 5 | `pwsh scripts/smoke.ps1` 跑 13 步 / 32 断言，`tee` 到 `logs/smoke-ci.txt` |
| 6 | `pwsh scripts/stop-all.ps1` + `pkill -f 'java -jar'` 兜底（`if: always()`） |
| 7 | 上传 artifact `e2e-service-logs`（`if: always()`） |
| 8 | 写 Job Summary + 按结果判定成败 |

**关键设计点**

- **`needs: backend`**：这是本 workflow 里唯一有依赖的 job。`backend` 失败则本 job 自动跳过
  （没 jar 可跑），不会浪费 runner 时间。前 5 个 job 依旧完全并行、互不影响。
- **jar 还原布局对得上**：`backend` 上传的是 `server/**/target/*.jar`，`upload-artifact` 以
  「所有命中文件的最小公共祖先」为根（即 `server/`），所以下载到 `path: server` 后正好还原成
  `server/<module>/target/<module>-0.1.0-SNAPSHOT.jar` —— 正是 `start-all.ps1` 找 jar 的位置。
- **用 pwsh 而不是 bash 重写一套**：`smoke.ps1` 里的 32 条断言（浮点比较 `Eq`、券的 UTC 时间拼装、
  下单 payload 拼串、响应信封 `{code,msg,data}` 取值）是**唯一实现**。复制一份 bash 等于制造第二份
  迟早会漂移的契约校验，而 ubuntu-latest 自带 PowerShell 7，跑同一份脚本是最强保证。
  （代价就是 `server/scripts/*.ps1` 必须做跨平台改造 —— 见下面的「脚本为跨平台做的改造」）
- **用 `pwsh -NoProfile -Command` 而不是 `shell: pwsh`**：GitHub 的 `shell: pwsh` 是**点加载**临时脚本，
  点加载不会传参，`-ReadyTimeoutSec` / `-JvmArgs` 会被静默丢弃、悄悄退回 90s 默认值。
- **限堆 `-Xmx448m -XX:+UseSerialGC …`**：11 个 JVM 并发冷启动。runner 是 2C7G（私有仓库 Free）
  或 4C16G，11 × 不限堆会直接 OOM；这些服务的数据量极小，448m 绰绰有余。
  本机 Windows 不受影响（`start-all.ps1` 的 `-JvmArgs` 默认是空数组）。
- **`continue-on-error` + 最后统一判定**：起服务与冒烟都 `continue-on-error: true`，
  保证「上传日志 / 写 Job Summary」这两个**最需要日志的时候**永远能跑到；
  成败由最后一步按 `steps.*.outcome` 判定（与 `ios-macos` 同一套路）。

#### 6.1 脚本为跨平台做的改造

`server/scripts/*.ps1` 原本只能在 Windows 跑。改造点全部收敛在新建的
[`server/scripts/platform-lib.ps1`](../server/scripts/platform-lib.ps1)，业务脚本保持跨平台无关：

| 原写法 | Windows | Linux（pwsh 7） |
|--------|---------|------------------|
| `Get-NetTCPConnection`（查端口占用/复核） | ✅ 原样保留 | 解析 `/proc/net/tcp` + `/proc/net/tcp6`（`st == 0A` 即 LISTEN） |
| `Get-CimInstance Win32_Process`（孤儿进程清扫） | ✅ 原样保留 | 遍历 `/proc/<pid>/cmdline` |
| `Join-Path $JAVA_HOME 'bin\java.exe'` | ✅ | 改成 `bin/java` |
| `"target\{0}-0.1.0-SNAPSHOT.jar"`（硬编码反斜杠） | ✅ | 改成嵌套 `Join-Path` |
| `Start-Process -WindowStyle Hidden` | ✅ | 省略（`ProcessWindowStyle` 是 Win32 概念，Linux 上无意义） |
| `netstat -ano \| findstr :8080`（排查提示） | ✅ | 换成 `ss -lntp \| grep ':8080 '` |
| 非 2xx 响应体取值（`smoke.ps1`） | `HttpWebResponse.GetResponseStream()` | `$_.ErrorDetails.Message` → `HttpResponseMessage.Content` |
| `-Headers @{}`（空请求头） | ✅ | 改为「非空才传参」（PS7 对空 Headers 校验更严） |

- **Linux 分支不查「占用者进程」**：`/proc/net/tcp` 里的 inode 要映射回 pid 需要遍历 `/proc/*/fd`，
  收益低。因此 Linux 上只回答「这个端口有没有人在听」，诊断交给 `ss -lntp`。Windows 侧行为逐字未变。
- **控制台编码**：Windows 侧**什么都没做**（本机 GBK 控制台 + UTF-8 BOM 源文件，
  历史上加过 `[Console]::OutputEncoding=UTF8`，反而把中文搞乱，已回滚）；
  Linux 侧显式钉 UTF-8，保证 Actions 日志与 Job Summary 里中文可读。
- **有自测**：[`server/scripts/selftest-platform-lib.ps1`](../server/scripts/selftest-platform-lib.ps1)
  19 条断言，把 `/proc` 解析拆成纯函数后对着样本文件验证 —— Linux 分支没法在 Windows 上跑，
  靠这个保证两端都对。**加跨平台原语时请同步加断言。**

#### 失败了怎么看

1. **Job Summary**（最快）：已自动列出断言统计（通过/失败数）、**每一条 `[FAIL]` 断言原文**、
   启动失败时的 `start-all.ps1` 末尾 60 行、以及各服务日志里前 25 条 `ERROR`。
2. **artifact `e2e-service-logs`**：含 `server/logs/<svc>.log` 与 `<svc>.err.log`（stdout/stderr 分开）、
   `smoke-ci.txt`（完整冒烟输出）、`start-ci.txt`（完整启动输出）。
3. **先分清是哪一类失败**：

| 现象 | 大概率原因 | 处理 |
|------|-----------|------|
| 没有 `smoke-ci.txt`，Summary 说「未生成」 | jar 没还原成功 / 服务没就绪 | 看第 3 步的 `校验 jar` 输出与 `start-ci.txt` |
| 断言全是 PASS 但 job 仍红 | 冒烟在第 0 步就退（`exit 2`，网关 `/health` 不通） | Summary 会自动展开冒烟输出前 40 行 |
| 某服务反复「进程已退出」 | 该服务 JVM OOM 或配置错 | 下 artifact 看该服务 `.err.log` |
| `搜索命中新建商品` 偶发失败 | core-search 5s 全量同步 + runner 慢 | 重跑一次；连续出现则查 search 的同步日志 |
| 全部失败且报连接超时 | 11 个 JVM 在 2 核 runner 上没冷启动完 | 看是不是超时（600s）；必要时重跑 |

> ⚠️ **不要**因为 e2e-smoke 红了就去删 `smoke.ps1` 的断言。它红了说明跨服务契约真的变了，
> 该改的是被测代码或两边不一致的契约，不是断言本身。

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
3. **额度告急时用 `workflow_dispatch` 的 `skip_ios` 开关**，手动跑其余 job，iOS 攒着跑；
4. **仓库转公开** —— iOS 这类验证就不再计费了。

`e2e-smoke` 跑在 Linux 上，**不消耗 macOS 额度**（两者是分开的池子：私有 Free 仓库 Linux 2000 分钟/月、
macOS 200 分钟/月）。它单次正常耗时约 **5～10 分钟**，主要成本是 11 个 JVM 的冷启动。

## 六、通用约定

- **最小权限**：workflow 级 `permissions: contents: read`，所有 job 只读代码仓，不写、不评论、不取 secret。
- **超时**：backend 45min / frontend-web 25min / miniprogram 25min / android 50min /
  ios-macos 45min / e2e-smoke 30min，均留足余量又不会因 runner 卡死而空烧额度
  （`timeout-minutes` 一到就取消 job，不再计费）。
- **依赖关系**：只有 `e2e-smoke` 有 `needs: backend`（要消费 jar）；其余 job 之间无依赖、完全并行，
  任一失败不影响其它 job 产出日志与产物。
- **缓存**：全部用官方 cache action，key 均含依赖锁文件哈希——
  `setup-java`（`~/.m2`，key = 全部 `pom.xml` 哈希）、
  `gradle/actions/setup-gradle`（`~/.gradle`，key = Gradle 配置哈希）、
  `setup-node` 的 `cache: pnpm`（pnpm store，key = `pnpm-lock.yaml` 哈希）、
  `actions/cache`（DerivedData，key = `**/*.swift` + `project.yml` 哈希）。
- **产物体积**：Android APK / iOS xcresult 较大，`retention-days` 已相应设置（7 / 14 / 30 天）。
- **失败现场**：每个「可能失败的执行步骤」后面都跟着 `if: always()` 的「上传日志 / 写 Job Summary」，
  保证红了也能看到为什么红。

## 七、本 CI 不验证什么

> 补充：`ios-smoke`（`.github/workflows/ios-smoke.yml`）在 macOS runner 上真实启动模拟器、装 App、运行并截图，验证的是**"启动不崩 + 根视图渲染 + 网络失败有可见错误态"**。判定用两级强信号（`simctl launch` 退出码、`launchctl` 中进程是否仍在），**fail-closed**——宁可假红也不能假绿。截图只作参考信号，不参与判定。触发为 `workflow_dispatch` + 仅 `ios-client/**` 变更的 push/PR + 每周一 schedule（约 60 分钟/月额度，删掉 `schedule:` 即可停）。

### 7.1 iOS 运行态冒烟的实战教训（2026-10-12，6 轮才绿）

首次启用时连续 6 次运行失败，**但每次 `xcodebuild` 都是 `BUILD SUCCEEDED`**——问题全在编排脚本。已全部修复，记录在此以免重蹈：

| # | 症状 | 真因 |
|---|------|------|
| 1 | 整份 workflow **0 秒失败、不创建任何 job、无日志** | `jobs.<id>.env` 里引用 `inputs.*` / `runner.*`——这两个上下文**只在 step 级可用**，GitHub 在加载期就判无效（本地 YAML 解析完全正常，极具迷惑性） |
| 2 | 14 秒全链失败，`install.rc=99` 等"未执行"默认值 | `$GITHUB_ENV` **只对后续步骤生效**，同一步内 `mkdir "$WORK"` 是空路径 |
| 3 | 中段步骤被整段跳过，现场全丢 | 原文件**零个** `continue-on-error`，与文件头声明的"判定统一收在最后一步"相悖 |
| 4 | `xcodebuild: error: missing value for key 'id' of option 'Destination'` | 编译步骤漏加载 `state/sim.env`（→ `$UDID` 空）+ 缺 `working-directory: ios-client`（`.xcodeproj` 由 xcodegen 现场生成在子目录） |
| 5 | 产物明明在、`BUILD SUCCEEDED`，却报"没有可安装产物" | `WRAPPER_NAME` 本身已含 `.app`，代码又拼一次 → `EasyShopping.app.app`；连锁引发下游 `set -u` 下 unbound variable |
| 6 | install/launch/存活/截图全绿，判定仍失败 | "产物是否就位"被 bundle id 解析**连坐**——bundle id 只影响 launch 目标，不该拖垮主判据 |

**四条通用原则**（均已落到 workflow 注释里）：

1. **本地 YAML 合法 ≠ GitHub 接受**。`inputs` / `runner` / `secrets` 等上下文有严格的可用位置限制。
2. **`$GITHUB_ENV` 只影响后续步骤**，同一步骤内别指望它。
3. **判据必须与 best-effort 的辅助解析解耦**，否则一个辅助步骤失败会让真实结果不可见。
4. **失败时一定要留证据**。第 3 轮补的 `continue-on-error` 是后面 3 轮能精确定位的前提——没有它，每轮都只会得到一句 `Process completed with exit code 1`。

### 7.2 绿灯的正确读法

> 补充：`ios-smoke`（`.github/workflows/ios-smoke.yml`）在 macOS runner 上真实启动模拟器、装 App、运行并截图，验证的是**"启动不崩 + 根视图渲染 + 网络失败有可见错误态"**。判定用两级强信号（`simctl launch` 退出码、`launchctl` 中进程是否仍在），**fail-closed**——宁可假红也不能假绿。截图只作参考信号，不参与判定。触发为 `workflow_dispatch` + 仅 `ios-client/**` 变更的 push/PR + 每周一 schedule（约 60 分钟/月额度，删掉 `schedule:` 即可停）。

避免误读绿灯：

- ❌ iOS **UI 自动化断言**——`ios-smoke` 验证的是"能装、能启动、进程还活着、能截图"，**不是**像素级/可访问性树断言（无 XCUITest target，取不到可访问性树，用颜色阈值假装断言只会制造假红假绿）。
  - 已验证到的程度（2026-10-12 首次绿）：截图显示根视图正常渲染（标题/搜索框/四 Tab），且 CI 无后端时首页呈现**"网络异常：无法连接服务器。"+ 重试按钮**的可见错误态——正是审计要求的"静默失败治理"在运行期的实证。
  - `ios-macos`（编译）与 `ios-smoke`（运行）是两个独立 workflow；后者覆盖不了 iOS 16 特有行为——runner 上是最新 runtime（当前 iOS 26.x），且 Xcode 16 已不自带 iOS 16 模拟器。
- ❌ **真实中间件**：e2e-smoke 跑的仍是 H2 文件库 + 进程内事件分发（`server/` 的 P0 形态），
  **不是** MySQL 16 库 64 分片 + RabbitMQ（生产演进形态，见 `plans/00-架构总纲.md`）。
  所以它验的是「跨服务**代码契约**」，不是「跨中间件的部署契约」。
- ❌ 安卓 release 签名 / iOS 真机归档（Archive）——CI 只出 debug / 模拟器构建，无签名凭据。
- ❌ 小程序在微信开发者工具里的实际运行——只验能编译出 `mp-weixin` 产物。
- ❌ 性能与容量（`plans/00-架构总纲.md` 的千万 DAU 口径）——CI 不做压测。