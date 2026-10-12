<#
.SYNOPSIS
  start-all.ps1 / stop-all.ps1 的跨平台公共库（被两者 dot-source，**不单独执行**）。

.DESCRIPTION
  存在的唯一理由：GitHub Actions 的 ubuntu runner 上跑同一个 PowerShell 脚本时，
  有三组 Windows 专有 API 在 Linux 上不存在或语义不同。本文件把「平台相关」收敛到一处，
  业务脚本（start-all / stop-all / smoke）保持跨平台无关。

  1) 监听端口查询
     - Windows：Get-NetTCPConnection（NetTCPIP 模块，PowerShell 7 on Windows 也有）
     - Linux：不存在该 cmdlet，改解析内核接口 /proc/net/tcp 与 /proc/net/tcp6（state 0A = LISTEN）。
       无需 ss/lsof/root，任何最小容器里都能跑。
     - Linux 分支**不查占用者进程**（inode → pid 需要遍历 /proc/*/fd 且需同 UID），
     只回答「这个端口有没有被监听」，诊断信息交给 `ss -lntp` 手工看。
     跨平台语义保持一致：Get-BlockingListeners 一律返回 Port 有监听即非空（Windows 侧
     额外排除 [::1]，那是本机 WSL wslrelay 的历史包袱，Linux 无此问题，见 start-all 注释）。

  2) 主机平台判定
     $IsWindows / $IsLinux 是 PowerShell 6+ 的自动变量，本机 Windows 用的是
     PowerShell 5.1（Desktop edition），读它只会拿到 $null，因此统一走 Test-IsWindows。

  3) 控制台编码
     本机控制台是 GBK，脚本源文件是 UTF-8 **with BOM**：5.1 靠 BOM 认出 UTF-8，
     所以 Windows 侧什么都不用做（历史上试过 [Console]::OutputEncoding=UTF8，
     反而把本机 GBK 控制台的中文搞乱，已回滚——见 INTEGRATION-REPORT.md §4 尾注）。
     Linux 侧相反：默认就是 UTF-8，但 runner locale 可能是 POSIX/C，
     显式钉 UTF-8 保证中文日志在 Actions 日志与 Job Summary 里可读。
#>

# 注意：本文件被 dot-source，语句会在**调用方作用域**执行。
# 因此这里不写 Set-StrictMode / $ErrorActionPreference 之类的全局副作用设置，
# 只定义函数，全部逻辑对 StrictMode Off（PowerShell 默认）安全。

# ---- 1. 平台判定 ------------------------------------------------------------
# PS 5.1（Desktop）没有 $IsWindows；PS 6+（Core）有。放在脚本级变量里供调用方复用。
$script:IsWindowsHost = $true
if ($PSVersionTable.PSEdition -ne 'Desktop' -and (Get-Variable -Name 'IsWindows' -ErrorAction SilentlyContinue)) {
    $script:IsWindowsHost = [bool]$IsWindows
}

function Test-IsWindows {
    [OutputType([bool])]
    param()
    return $script:IsWindowsHost
}

# ---- 2. 控制台编码 ----------------------------------------------------------
function Initialize-ConsoleEncoding {
    param([switch]$Force)
    if (Test-IsWindows -and -not $Force) { return }   # Windows 侧保持原样，见文件头第 3 条
    try {
        [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
        $global:OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    } catch {
        # 无控制台（如 -NonInteractive / 重定向到文件）时可能抛，忽略即可：不影响断言
        Write-Verbose ("设置控制台 UTF-8 失败（可忽略）：" + $_.Exception.Message)
    }
}

# ---- 3. 监听端口 ------------------------------------------------------------
function ConvertFrom-HexIPv4 {
    # /proc/net/tcp 的地址是 4 字节小端存放的十六进制：0100007F == 127.0.0.1
    param([string]$Hex)
    $oct = @()
    # 字节对起始下标是 6/4/2/0（不是 7/5/3/1 —— 后者会让最后一次 Substring 越界）
    for ($i = 6; $i -ge 0; $i -= 2) { $oct += [Convert]::ToByte($Hex.Substring($i, 2), 16) }
    return ($oct -join '.')
}

function ConvertFrom-HexIPv6 {
    # /proc/net/tcp6 的地址是 16 字节，按 4 字节一组、组内小端存放。
    # 例：::1  -> "00000000000000000000000001000000"；:: -> 全 0
    param([string]$Hex)
    $b = New-Object 'System.Byte[]' 16
    for ($g = 0; $g -lt 4; $g++) {
        $chunk = $Hex.Substring($g * 8, 8)
        for ($i = 0; $i -lt 4; $i++) {
            $b[$g * 4 + $i] = [Convert]::ToByte($chunk.Substring(6 - $i * 2, 2), 16)
        }
    }
    $isLoopback = $true
    for ($i = 0; $i -lt 15; $i++) { if ($b[$i] -ne 0) { $isLoopback = $false; break } }
    if ($isLoopback -and $b[15] -eq 1) { return '::1' }
    $isAny = $true
    foreach ($x in $b) { if ($x -ne 0) { $isAny = $false; break } }
    if ($isAny) { return '::' }
    return ('[ipv6:{0}]' -f (($b | ForEach-Object { $_.ToString('x2') }) -join ''))
}

function ConvertFrom-ProcNetTcp {
<#
  解析一个 /proc/net/tcp{,6} 格式的文件，只保留 LISTEN（st == 0A）且端口匹配的条目。
  拆成独立函数是为了能在任意平台对着「样本文件」自测（Linux 分支无法在本机跑）。
  文件格式（空白分隔，列序固定）：
      sl  local_address rem_address st tx:rx tr:when retrnsmt uid timeout inode
  例：  0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1234 1 ...
#>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][int]$Family   # 4 或 6
    )
    $found = @()
    if (-not (Test-Path -LiteralPath $Path)) { return $found }
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $parts = $line.Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)
        if ($parts.Length -lt 4) { continue }          # 首行是表头 "sl local_address ..."
        if ($parts[3] -ne '0A') { continue }           # 只认 LISTEN，跳过 ESTABLISHED/TIME_WAIT
        $local = $parts[1]
        $sep = $local.IndexOf(':')
        if ($sep -lt 0) { continue }
        $hexPort = $local.Substring($sep + 1)
        # 端口也是十六进制（8080 -> 1F90），必须显式按 16 进制转，直接 [int] 会抛 InvalidCast
        if ([Convert]::ToInt32($hexPort, 16) -ne $Port) { continue }
        $hexAddr = $local.Substring(0, $sep)
        $addr = if ($Family -eq 4) { ConvertFrom-HexIPv4 $hexAddr } else { ConvertFrom-HexIPv6 $hexAddr }
        $found += [PSCustomObject]@{ LocalAddress = $addr; OwningProcess = $null; LocalPort = $Port }
    }
    return $found
}

function Get-LinuxListeningSocket {
    param([int]$Port)
    $found = @()
    $found += @(ConvertFrom-ProcNetTcp -Path '/proc/net/tcp' -Port $Port -Family 4)
    $found += @(ConvertFrom-ProcNetTcp -Path '/proc/net/tcp6' -Port $Port -Family 6)
    return $found
}

function Get-WindowsListeningSocket {
    param([int]$Port)
    return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

<#
  统一入口：返回该端口上的监听对象数组。
  Windows 分支返回 Get-NetTCPConnection 原对象（含 OwningProcess）；
  Linux 分支返回 LocalAddress / OwningProcess($null) / LocalPort。
  调用方对 OwningProcess 为空的处理：显示 '?'。
#>
function Get-ListeningSocket {
    [OutputType([object[]])]
    param([Parameter(Mandatory = $true)][int]$Port)
    if (Test-IsWindows) { return (Get-WindowsListeningSocket -Port $Port) }
    return (Get-LinuxListeningSocket -Port $Port)
}

# 端口占用时的排查提示，按平台给不同的命令
function Get-PortOwnerHint {
    param([int]$Port)
    if (Test-IsWindows) { return ("netstat -ano | findstr :{0}" -f $Port) }
    return ("ss -lntp | grep ':{0} '" -f $Port)
}

# 查「本目录下、且带启动标记」的 java 进程命令行（stop-all 的孤儿清扫用）
# Windows：Get-CimInstance Win32_Process；Linux：遍历 /proc/<pid>/cmdline
function Get-JavaCommandLine {
    param([string]$MustContainPath, [string]$MustContainMarker)
    $result = @()
    if (Test-IsWindows) {
        try {
            $result = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop |
                Where-Object { $_.CommandLine -and $_.CommandLine -like ('*' + $MustContainPath + '*') -and $_.CommandLine -like ('*' + $MustContainMarker + '*') } |
                ForEach-Object { [PSCustomObject]@{ ProcessId = $_.ProcessId; CommandLine = $_.CommandLine } })
        } catch {
            $result = @()   # Get-CimInstance 不可用时忽略，pid 文件那步已覆盖主要场景
        }
        return $result
    }

    foreach ($d in [System.IO.Directory]::GetDirectories('/proc')) {
        $name = [System.IO.Path]::GetFileName($d)
        $procId = 0
        if (-not [int]::TryParse($name, [ref]$procId)) { continue }   # /proc 下有非数字目录
        $cmdPath = Join-Path $d 'cmdline'
        if (-not [System.IO.File]::Exists($cmdPath)) { continue }      # 权限不足或已退出
        try {
            # cmdline 以 NUL 分隔参数，转成空格拼成可读字符串。
            # 用 [char]0 而不是反引号转义 "`0"：后者会在源文件里落下一个真实 NUL 字节。
            $raw = [System.IO.File]::ReadAllText($cmdPath)
            $cmd = $raw.Replace([char]0, ' ').Trim()
        } catch { continue }
        if (-not $cmd) { continue }
        if ($cmd -notlike '*java*') { continue }
        if ($cmd -notlike ('*' + $MustContainPath + '*')) { continue }
        if ($cmd -notlike ('*' + $MustContainMarker + '*')) { continue }
        $result += [PSCustomObject]@{ ProcessId = $procId; CommandLine = $cmd }
    }
    return $result
}