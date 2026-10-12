<#
.SYNOPSIS
  platform-lib.ps1 的自测（不改业务断言，只测跨平台原语）。

.DESCRIPTION
  为什么需要它：Linux 分支（/proc/net/tcp{,6} 解析、/proc/<pid>/cmdline 扫描）在 Windows
  开发机上永远走不到，靠「本地跑一遍」是测不出来的。这里把解析逻辑拆成纯函数
  （ConvertFrom-ProcNetTcp），对固定样本文件做断言，于是 Windows 与 Linux 都能验证。

  覆盖：
    1. ConvertFrom-HexIPv4 / ConvertFrom-HexIPv6 —— 十六进制地址还原
    2. ConvertFrom-ProcNetTcp —— 只取 LISTEN、端口匹配、忽略非 0A 状态
    3. Get-PortOwnerHint —— 平台相关提示命令
    4. Test-IsWindows —— 与 $IsWindows 语义一致
    5. Get-JavaCommandLine —— 在本机真实返回（Windows 走 CIM / Linux 走 /proc）

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File server\scripts\selftest-platform-lib.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'platform-lib.ps1')

$script:Pass = 0
$script:Fail = 0

function Assert-Eq {
    param([string]$Name, $Expected, $Actual)
    $ok = ([string]$Expected -eq [string]$Actual)
    if ($ok) {
        $script:Pass++
        Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green
    } else {
        $script:Fail++
        Write-Host ("  [FAIL] {0}   <<< 期望={1} 实际={2}" -f $Name, $Expected, $Actual) -ForegroundColor Red
    }
}

Write-Host '== 1. 十六进制地址还原 ==' -ForegroundColor Cyan
Assert-Eq 'IPv4 0100007F -> 127.0.0.1' '127.0.0.1' (ConvertFrom-HexIPv4 '0100007F')
Assert-Eq 'IPv4 00000000 -> 0.0.0.0' '0.0.0.0' (ConvertFrom-HexIPv4 '00000000')
Assert-Eq 'IPv4 0101A8C0 -> 192.168.1.1' '192.168.1.1' (ConvertFrom-HexIPv4 '0101A8C0')
Assert-Eq 'IPv6 ::1' '::1' (ConvertFrom-HexIPv6 '00000000000000000000000001000000')
Assert-Eq 'IPv6 ::' '::' (ConvertFrom-HexIPv6 '00000000000000000000000000000000')
# 内核按 4 字节一组、组内小端打印：::ffff:127.0.0.1 的文件形态是
#   s6_addr32 = 00000000 00000000 FFFF0000 7F000001
Assert-Eq 'IPv6 ::ffff:127.0.0.1 归一' '[ipv6:00000000000000000000ffff0100007f]' (ConvertFrom-HexIPv6 '0000000000000000FFFF00007F000001')

Write-Host ''
Write-Host '== 2. /proc/net/tcp 解析（样本文件）==' -ForegroundColor Cyan
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ('procnettcp-' + [guid]::NewGuid().ToString('N') + '.txt')
# 列：sl local_address rem_address st ...
# 端口十六进制：8080 = 1F90，8081 = 1F91，8082 = 1F92
@(
    '  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode',
    '   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 4242 1 0000000000000000 100 0 0 10 0',
    '   1: 0100007F:1F91 0100007F:C350 01 00000000:00000000 00:00000000 00000000     0        0 4243 1 0000000000000000 100 0 0 10 0',
    '   2: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 4244 1 0000000000000000 100 0 0 10 0',
    '   3: 0100007F:1F92 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 4245 1 0000000000000000 100 0 0 10 0',
    '   4: 0100007F:1F91 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 4246 1 0000000000000000 100 0 0 10 0'
) | Set-Content -Path $fixture -Encoding ASCII

$r = @(ConvertFrom-ProcNetTcp -Path $fixture -Port 8080 -Family 4)
Assert-Eq '8080 命中 2 条' '2' $r.Count
Assert-Eq '第 1 条地址 = 127.0.0.1' '127.0.0.1' $(if ($r.Count -ge 1) { $r[0].LocalAddress } else { '<none>' })
Assert-Eq '第 2 条地址 = 0.0.0.0' '0.0.0.0' $(if ($r.Count -ge 2) { $r[1].LocalAddress } else { '<none>' })
Assert-Eq '占用者 pid 未知（Linux 分支不解析 inode->pid）' '' $(if ($r.Count -ge 1 -and $null -eq $r[0].OwningProcess) { '' } else { 'not-null' })

$r8081 = @(ConvertFrom-ProcNetTcp -Path $fixture -Port 8081 -Family 4)
Assert-Eq '8081 只取到 LISTEN 那条（ESTABLISHED 被忽略）' '1' $r8081.Count
Assert-Eq '8081 命中的那条确实是 0A 行' '127.0.0.1' $(if ($r8081.Count -ge 1) { $r8081[0].LocalAddress } else { '<none>' })

$rNone = @(ConvertFrom-ProcNetTcp -Path $fixture -Port 9999 -Family 4)
Assert-Eq '9999 无监听 -> 0 条' '0' $rNone.Count

$rMissing = @(ConvertFrom-ProcNetTcp -Path (Join-Path ([System.IO.Path]::GetTempPath()) 'no-such-file-xyz') -Port 8080 -Family 4)
Assert-Eq '文件不存在 -> 0 条且不抛异常' '0' $rMissing.Count
Remove-Item -Path $fixture -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '== 3. 平台相关提示 ==' -ForegroundColor Cyan
$hint = Get-PortOwnerHint -Port 8080
if (Test-IsWindows) {
    Assert-Eq 'Windows 提示用 netstat -ano' 'True' ($hint -like '*netstat -ano*')
} else {
    Assert-Eq 'Linux 提示用 ss -lntp' 'True' ($hint -like '*ss -lntp*')
}

Write-Host ''
Write-Host '== 4. 平台判定 ==' -ForegroundColor Cyan
$expectedIsWin = if ($PSVersionTable.PSEdition -eq 'Desktop') { $true } else { [bool]$IsWindows }
Assert-Eq 'Test-IsWindows 与宿主一致' ([string]$expectedIsWin) ([string](Test-IsWindows))
Assert-Eq '当前平台' $(if (Test-IsWindows) { 'Windows' } else { 'Linux' }) $(if ($expectedIsWin) { 'Windows' } else { 'Linux' })

Write-Host ''
Write-Host '== 5. java 进程命令行查询（真实调用本平台实现）==' -ForegroundColor Cyan
# 传一个绝不可能命中的路径：期望返回 0 条，但走的是真实平台代码路径
$javaHits = @(Get-JavaCommandLine -MustContainPath '/__selftest_no_such_dir__' -MustContainMarker '--server.address=127.0.0.1')
Assert-Eq '不存在的目录 -> 0 条' '0' $javaHits.Count
# 传一个一定命中的路径（当前 pwsh/powershell 自己的 exe 不含 java，但 /proc 扫描分支要能跑完不抛）
$javaSelf = @(Get-JavaCommandLine -MustContainPath ([System.IO.Path]::GetTempPath().TrimEnd('/','\')) -MustContainMarker '--__selftest_no_such_marker__')
Assert-Eq '不存在的标记 -> 0 条' '0' $javaSelf.Count

Write-Host ''
Write-Host '==================================================' -ForegroundColor Cyan
Write-Host ("  自测结果：通过 {0} / 失败 {1}" -f $script:Pass, $script:Fail) -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Red' })
Write-Host '==================================================' -ForegroundColor Cyan
if ($script:Fail -eq 0) { exit 0 } else { exit 1 }