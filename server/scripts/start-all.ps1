<#
.SYNOPSIS
  一键启动 easy-shopping 后端：server/ 下 11 个业务服务 + gateway（共 12 个可执行 jar）。

.DESCRIPTION
  启动约定（与 DEV.md 2 对齐，另加两条本机适配）：
  1) 全部服务统一绑 127.0.0.1 —— 开发机 WSL 的 wslrelay 占着 [::1]:8081~8083（DEV.md 5），
     Spring Boot 默认双栈绑定会直接报 "Port 8081 was already in use"，绑 IPv4 回环可绕开；
  2) 同时把所有 service.urls.* 覆写成 http://127.0.0.1:<port> —— 否则 localhost 可能被解析成
     ::1，请求会打进 WSL 而不是本机服务；
  3) 启动顺序：业务服务（顺序无关）→ gateway 最后；每个服务等 /health 就绪才算启动成功；
  4) 日志：server/logs/<service>.log（stdout）、server/logs/<service>.err.log（stderr）、
     server/logs/<service>.pid（进程号，stop-all.ps1 据此停止）。

  5) 跨平台（PowerShell 7 on Linux / GitHub Actions ubuntu runner）：
     平台相关部分全部收敛到同目录的 platform-lib.ps1（Windows 侧行为与改造前逐字一致）。
     本机仍用 Windows PowerShell 5.1（GBK 控制台 + UTF-8 BOM 源文件）执行，未变。

.PARAMETER ReadyTimeoutSec
  全部服务就绪的最长等待时间（秒），默认 90。

.PARAMETER ResetData
  启动前删除 server/data/（各服务 H2 文件库），重置为干净库。H2 同一服务只允许一个实例，
  服务运行中不要用这个参数。

.PARAMETER JvmArgs
  追加给每个 JVM 的参数（放在 -jar 之前）。本机默认为空（保持原行为）；
  CI 的 ubuntu runner 规格有限（2C7G 起），11 个 JVM 同时冷启动时需要限堆，
  e2e-smoke job 实际用的是这一组：
    -JvmArgs '-Xmx448m','-Xms96m','-XX:MaxMetaspaceSize=192m','-XX:+UseSerialGC','-Xss512k'

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File server\scripts\start-all.ps1
  powershell -ExecutionPolicy Bypass -File server\scripts\start-all.ps1 -ResetData -ReadyTimeoutSec 120
  pwsh -NoProfile -Command "& './scripts/start-all.ps1' -ReadyTimeoutSec 600 -JvmArgs @('-Xmx448m','-Xms96m','-XX:+UseSerialGC')"   # Linux / CI
#>
[CmdletBinding()]
param(
    [int]$ReadyTimeoutSec = 90,
    [switch]$ResetData,
    [string[]]$JvmArgs = @()
)

$ErrorActionPreference = 'Stop'

# 跨平台公共库：平台判定 / 监听端口查询 / 控制台编码（Windows 侧行为保持原样）
. (Join-Path $PSScriptRoot 'platform-lib.ps1')
Initialize-ConsoleEncoding

$ServerRoot = Split-Path -Parent $PSScriptRoot
$Logs = Join-Path $ServerRoot 'logs'
$Data = Join-Path $ServerRoot 'data'
if (-not (Test-Path $Logs)) { New-Item -ItemType Directory -Path $Logs | Out-Null }

# ---- Java ----
# Windows 是 bin\java.exe，Linux 是 bin/java —— 用 Join-Path 拼，别再硬编码反斜杠。
$JavaExe = $null
$javaNames = if (Test-IsWindows) { @('java.exe') } else { @('java') }
if ($env:JAVA_HOME) {
    foreach ($jn in $javaNames) {
        $candidate = Join-Path (Join-Path $env:JAVA_HOME 'bin') $jn
        if (Test-Path $candidate) { $JavaExe = $candidate; break }
    }
}
if (-not $JavaExe) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $JavaExe = $cmd.Source }
}
if (-not $JavaExe) {
    Write-Host '[FAIL] 找不到 java 可执行文件，请设置 JAVA_HOME（本机为 C:\Android\jdk-17）' -ForegroundColor Red
    exit 1
}

# ---- 服务清单：数组顺序 = 启动顺序（gateway 最后）----
$Services = @(
    @{ Name = 'core-user';      Port = 8081 },
    @{ Name = 'core-product';   Port = 8082 },
    @{ Name = 'core-price';     Port = 8083 },
    @{ Name = 'core-inventory'; Port = 8084 },
    @{ Name = 'core-cart';      Port = 8085 },
    @{ Name = 'core-order';     Port = 8086 },
    @{ Name = 'core-payment';   Port = 8087 },
    @{ Name = 'core-promotion'; Port = 8088 },
    @{ Name = 'core-search';    Port = 8089; Extra = @('--app.search.sync-interval-ms=5000') },
    @{ Name = 'admin-bff';      Port = 8090 },
    @{ Name = 'gateway';        Port = 8080 }
)

# 全部 service.urls 统一指向 127.0.0.1（见文件头第 2 条）
$UrlOverrides = @(
    '--service.urls.core-user=http://127.0.0.1:8081',
    '--service.urls.core-product=http://127.0.0.1:8082',
    '--service.urls.core-price=http://127.0.0.1:8083',
    '--service.urls.core-inventory=http://127.0.0.1:8084',
    '--service.urls.core-cart=http://127.0.0.1:8085',
    '--service.urls.core-order=http://127.0.0.1:8086',
    '--service.urls.core-payment=http://127.0.0.1:8087',
    '--service.urls.core-promotion=http://127.0.0.1:8088',
    '--service.urls.core-search=http://127.0.0.1:8089',
    '--service.urls.admin-bff=http://127.0.0.1:8090'
)

function Test-ServiceHealth {
    param([int]$Port)
    try {
        # 10s 而非 3s：11 个 JVM 同时冷启动会吃满 CPU（Spring Boot 启动 30~40s），
        # 过短的探测超时会把"还在启动"误判为"起不来"。
        $null = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/health" -f $Port) -UseBasicParsing -TimeoutSec 10
        return $true
    } catch {
        return $false
    }
}

# 除 [::1]（WSL relay 占的 IPv6 回环，与 127.0.0.1 绑定不冲突）外，其余监听都算占用
# 跨平台版：Windows 走 Get-NetTCPConnection（含占用者 pid），Linux 走 /proc/net/tcp{,6}
# （该分支不查占用者 pid，OwningProcess 为 $null，只回答「有没有人在听」）。
function Get-BlockingListeners {
    param([int]$Port)
    $found = @()
    foreach ($c in @(Get-ListeningSocket -Port $Port)) {
        if ($c.LocalAddress -eq '::1') { continue }
        $found += $c
    }
    return $found
}

function Show-LogTail {
    param([string]$Name, [int]$Lines = 40)
    foreach ($suffix in @('.err.log', '.log')) {
        $path = Join-Path $Logs ($Name + $suffix)
        if ((Test-Path $path) -and ((Get-Item $path).Length -gt 0)) {
            Write-Host ("---- {0}{1} ----" -f $Name, $suffix) -ForegroundColor DarkGray
            Get-Content -Path $path -Tail $Lines -Encoding UTF8 | ForEach-Object { Write-Host ("    " + $_) -ForegroundColor DarkGray }
        }
    }
}

function Stop-StartedServices {
    param([array]$Started)
    foreach ($s in $Started) {
        $pidPath = Join-Path $Logs ($s.Name + '.pid')
        if (Test-Path $pidPath) {
            $procId = 0
            if ([int]::TryParse((Get-Content -Path $pidPath -ErrorAction SilentlyContinue | Select-Object -First 1), [ref]$procId)) {
                $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
                if ($p -and $p.ProcessName -eq 'java') {
                    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
                    Write-Host ("[stop] {0} (pid {1})" -f $s.Name, $procId) -ForegroundColor Yellow
                }
            }
            Remove-Item $pidPath -Force -ErrorAction SilentlyContinue
        }
    }
}

# jar 路径必须用嵌套 Join-Path 拼：
# 原写法 "target\{0}-0.1.0-SNAPSHOT.jar" 在 Linux 上是单文件名，反斜杠不是路径分隔符，Test-Path 必挂。
function Get-ServiceJarPath {
    param([string]$Name)
    return (Join-Path (Join-Path (Join-Path $ServerRoot $Name) 'target') ("{0}-0.1.0-SNAPSHOT.jar" -f $Name))
}

# ---- 前置检查 ----
if ($ResetData) {
    if (Test-Path $Data) {
        Remove-Item -Path $Data -Recurse -Force
        Write-Host "[data] 已删除 server/data（H2 重置）" -ForegroundColor Yellow
    }
}

$missing = @()
foreach ($svc in $Services) {
    $jar = Get-ServiceJarPath -Name $svc.Name
    if (-not (Test-Path $jar)) { $missing += $jar }
}
if ($missing.Count -gt 0) {
    Write-Host '[FAIL] 缺少可执行 jar：' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host ("       " + $_) -ForegroundColor Red }
    if (Test-IsWindows) {
        Write-Host '       先执行：$env:JAVA_HOME="C:\Android\jdk-17"; cd server; C:\D\tools\maven\bin\mvn.cmd -q -DskipTests package' -ForegroundColor Yellow
    } else {
        Write-Host '       先执行：cd server && mvn -B -ntp -DskipTests package' -ForegroundColor Yellow
    }
    exit 1
}

# ---- 启动 ----
$started = @()
$failed = $false

foreach ($svc in $Services) {
    $name = $svc.Name
    $port = [int]$svc.Port

    if (Test-ServiceHealth -Port $port) {
        Write-Host ("[skip] {0} 已在 127.0.0.1:{1} 就绪" -f $name, $port) -ForegroundColor Yellow
        continue
    }

    $blocking = @(Get-BlockingListeners -Port $port)
    if ($blocking.Count -gt 0) {
        $owners = ($blocking | ForEach-Object {
            '{0}:{1}' -f $_.LocalAddress, $(if ($null -eq $_.OwningProcess) { '?' } else { $_.OwningProcess })
        }) -join ', '
        Write-Host ("[FAIL] 端口 {0} 被非本脚本进程占用（{1}），请先处理：" -f $port, $owners) -ForegroundColor Red
        Write-Host ("       " + (Get-PortOwnerHint -Port $port)) -ForegroundColor Yellow
        $failed = $true
        break
    }

    $jar = Get-ServiceJarPath -Name $name
    $log = Join-Path $Logs ($name + '.log')
    $err = Join-Path $Logs ($name + '.err.log')
    foreach ($f in @($log, $err)) { if (Test-Path $f) { Remove-Item $f -Force -ErrorAction SilentlyContinue } }

    $argList = @()
    $argList += $JvmArgs
    $argList += @('-jar', ('"{0}"' -f $jar), '--server.address=127.0.0.1')
    $argList += $UrlOverrides
    if ($svc.ContainsKey('Extra')) { $argList += $svc.Extra }

    # -WindowStyle Hidden 只在 Windows 上有意义（.NET 的 ProcessWindowStyle 是 Win32 概念），
    # Linux 上虽然 Start-Process 接受该参数，但没有任何效果，省掉以免不同 pwsh 版本行为差异。
    $startParams = @{
        FilePath               = $JavaExe
        ArgumentList           = $argList
        WorkingDirectory       = $ServerRoot
        RedirectStandardOutput = $log
        RedirectStandardError  = $err
        PassThru               = $true
    }
    if (Test-IsWindows) { $startParams.WindowStyle = 'Hidden' }

    try {
        $proc = Start-Process @startParams
    } catch {
        Write-Host ("[FAIL] {0} 启动失败：{1}" -f $name, $_.Exception.Message) -ForegroundColor Red
        $failed = $true
        break
    }

    Set-Content -Path (Join-Path $Logs ($name + '.pid')) -Value $proc.Id -Encoding ASCII
    $started += @{ Name = $name; Port = $port; ProcId = $proc.Id }
    Write-Host ("[start] {0} pid={1} port={2}" -f $name, $proc.Id, $port) -ForegroundColor Cyan
}

if ($failed) {
    Stop-StartedServices -Started $started
    exit 1
}

# ---- 等待就绪 ----
if ($started.Count -eq 0) {
    Write-Host ("[done] {0} 个服务均已在运行" -f $Services.Count) -ForegroundColor Green
    exit 0
}

Write-Host ("[wait] 等待 {0} 个服务就绪（超时 {1}s）..." -f $started.Count, $ReadyTimeoutSec) -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds($ReadyTimeoutSec)
$pending = @($started)
while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
    $still = @()
    foreach ($s in $pending) {
        if (Test-ServiceHealth -Port $s.Port) {
            Write-Host ("[ok] {0} :{1}" -f $s.Name, $s.Port) -ForegroundColor Green
        } else {
            $alive = Get-Process -Id $s.ProcId -ErrorAction SilentlyContinue
            if (-not $alive) {
                Write-Host ("[FAIL] {0} 进程已退出" -f $s.Name) -ForegroundColor Red
                Show-LogTail -Name $s.Name
                $failed = $true
            } else {
                $still += $s
            }
        }
    }
    $pending = $still
    if ($pending.Count -gt 0) { Start-Sleep -Milliseconds 1500 }
}

if ($pending.Count -gt 0) {
    foreach ($s in $pending) {
        Write-Host ("[FAIL] {0} 在 {1}s 内未就绪（127.0.0.1:{2}）" -f $s.Name, $ReadyTimeoutSec, $s.Port) -ForegroundColor Red
        Show-LogTail -Name $s.Name
    }
    $failed = $true
}

if ($failed) {
    Write-Host '[FAIL] 启动失败，已回收本次拉起的进程；日志见 server/logs/' -ForegroundColor Red
    Stop-StartedServices -Started $started
    exit 1
}

Write-Host ''
Write-Host '[done] 全部服务就绪：' -ForegroundColor Green
foreach ($s in $started) { Write-Host ("       {0,-15} http://127.0.0.1:{1}" -f $s.Name, $s.Port) }
Write-Host ('       gateway 统一入口：http://127.0.0.1:8080')
if (Test-IsWindows) {
    Write-Host ('  下一步：powershell -ExecutionPolicy Bypass -File server\scripts\smoke.ps1')
} else {
    Write-Host ('  下一步：pwsh -File server/scripts/smoke.ps1')
}
exit 0
