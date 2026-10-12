<#
.SYNOPSIS
  停止 start-all.ps1 拉起的全部服务（只杀本 server 目录下的 java 进程）。

.DESCRIPTION
  1) 优先按 server/logs/<service>.pid 停（并校验进程名是 java，避免误杀）；
  2) 再兜底扫一遍：命令行里带本 server 目录的 java.exe（pid 文件丢失时用）；
  3) 最后校验各业务端口是否已释放（[::1] 上 WSL wslrelay 的监听不属于我们，忽略）。
  绝不按"端口 = 8081"这种条件杀进程，避免误杀 WSL relay 等非本项目进程。

  跨平台：进程命令行查询 Windows 走 Get-CimInstance，Linux 走 /proc/<pid>/cmdline；
  端口复核 Windows 走 Get-NetTCPConnection，Linux 走 /proc/net/tcp{,6}。详见 platform-lib.ps1。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File server\scripts\stop-all.ps1
  pwsh -File server/scripts/stop-all.ps1      # Linux / CI
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

# 跨平台公共库：平台判定 / 监听端口查询 / 控制台编码（Windows 侧行为保持原样）
. (Join-Path $PSScriptRoot 'platform-lib.ps1')
Initialize-ConsoleEncoding

$ServerRoot = Split-Path -Parent $PSScriptRoot
$Logs = Join-Path $ServerRoot 'logs'

$Names = @('gateway', 'admin-bff', 'core-search', 'core-promotion', 'core-payment',
           'core-order', 'core-cart', 'core-inventory', 'core-price', 'core-product', 'core-user')

$Ports = @(8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 8089, 8090)

# ---- 1. 按 pid 文件停 ----
$stopped = 0
foreach ($n in $Names) {
    $pidPath = Join-Path $Logs ($n + '.pid')
    if (-not (Test-Path $pidPath)) { continue }

    $raw = Get-Content -Path $pidPath -ErrorAction SilentlyContinue | Select-Object -First 1
    $procId = 0
    if ([int]::TryParse([string]$raw, [ref]$procId)) {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($p -and $p.ProcessName -eq 'java') {
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Write-Host ("[stop] {0} (pid {1})" -f $n, $procId) -ForegroundColor Yellow
            $stopped++
        } elseif ($p) {
            Write-Host ("[warn] {0} 的 pid {1} 不是 java（{2}），已跳过" -f $n, $procId, $p.ProcessName) -ForegroundColor DarkYellow
        } else {
            Write-Host ("[gone] {0} (pid {1} 已不存在)" -f $n, $procId) -ForegroundColor DarkGray
        }
    }
    Remove-Item -Path $pidPath -Force -ErrorAction SilentlyContinue
}

# ---- 2. 兜底：只认「带本脚本启动标记 + 在本 server 目录下」的 java 进程 ----
# 标记 = start-all.ps1 注入的 --server.address=127.0.0.1；缺一不可，
# 避免误杀同目录下其它代理/同事手工拉起的实例（如 --server.port=18081 的调试进程）。
$marker = '--server.address=127.0.0.1'
$orphan = @(Get-JavaCommandLine -MustContainPath $ServerRoot -MustContainMarker $marker)
foreach ($p in $orphan) {
    if ($p.ProcessId) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Host ("[stop] orphan java pid={0}" -f $p.ProcessId) -ForegroundColor Yellow
        $stopped++
    }
}

if ($stopped -eq 0) {
    Write-Host '[done] 没有发现本脚本拉起的服务' -ForegroundColor Green
} else {
    Start-Sleep -Milliseconds 800
    Write-Host ("[done] 已停止 {0} 个进程" -f $stopped) -ForegroundColor Green
}

# ---- 3. 端口复核 ----
$busy = @()
foreach ($port in $Ports) {
    foreach ($c in @(Get-ListeningSocket -Port $port)) {
        if ($c.LocalAddress -eq '::1') { continue }   # WSL relay，非本项目
        $owner = $null
        if ($null -ne $c.OwningProcess) { $owner = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue }
        $ownerName = if ($owner) { $owner.ProcessName } else { '?' }
        $ownerPid = if ($null -eq $c.OwningProcess) { '?' } else { $c.OwningProcess }
        $busy += ("{0}({1}:{2})" -f $port, $ownerName, $ownerPid)
    }
}
if ($busy.Count -gt 0) {
    Write-Host ("[warn] 仍有端口被占用（非本脚本进程，未处理）：" + ($busy -join ', ')) -ForegroundColor DarkYellow
}
exit 0
