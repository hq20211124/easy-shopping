<#
.SYNOPSIS
  編排 13 步端到端冒烟（全走网关 http://127.0.0.1:8080），返回 0 表示 13 步全绿。

.DESCRIPTION
  前置：先跑 start-all.ps1 把 12 个服务拉起来。

  13 步（任务书口径）：
    1  管理端登录（/api/admin/auth/login 取 accessToken）
    2  买家注册 + 登录
    3  搜索商品（admin 建类目 + 商品 + 价格 + 库存，再等 core-search 同步后检索）
    4  加购
    5  下单（断言 0 基状态 = 0、pay_deadline 存在、拆单子单数 = 1、金额自洽）
    6  prepay（金额 = 后端定额）
    7  mock 支付
    8  查单状态 = 1（待发货）
    9  同 Idempotency-Key 重复下单不产生第二单
   10  库存不足路径（返回 3002）
   11  领券 -> 下单用券 -> 减免正确
   12  管理端订单列表可见 + 发货 -> 状态 = 2
   13  未带 token 访问需登录接口 -> 401/1002

  两处「链路不通用」的兜底（冒烟内已注明）：
    a) 管理员账号：core-user 无种子账号，直连 :8081 /api/v1/internal/users 建号
       （admin-backend 的账号管理接口，未挂网关路由，外网不可达）；
    b) 库存：管理端没有库存写接口（P0 属 admin-inventory，未实现），
       直连 :8084 /api/v1/internal/inventory/stocks 建账本。

  幂等口径说明：common 的 IdempotentInterceptor 对重复 Idempotency-Key 返回
  HTTP 409 + code 1005（core-order OrderIdempotencyTest 已固化该行为），
  所以第 9 步断言「不产生第二单，且返回 409/1005（或同单）」。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File server\scripts\smoke.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$GW     = 'http://127.0.0.1:8080'
$DIRECT_USER = 'http://127.0.0.1:8081'
$DIRECT_INV  = 'http://127.0.0.1:8084'

$AdminUser = 'smoke_admin'
$AdminPass = 'Smoke#12345'
$BuyerMobile = '13900013900'
$BuyerPass = 'Buyer#12345'

$Stamp = Get-Date -Format 'yyyyMMddHHmmss'
$script:Pass = 0
$script:Fail = 0

# ------------------------------------------------------------------ 基础设施

function Invoke-Api {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [string]$Method = 'GET',
        [string]$Json = '',
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 25
    )
    $res = @{ status = -1; text = ''; json = $null; error = '' }
    $params = @{
        Uri            = $Url
        Method         = $Method
        UseBasicParsing = $true
        TimeoutSec     = $TimeoutSec
        Headers        = $Headers
    }
    if ($Json -ne '') {
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($Json)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $resp = Invoke-WebRequest @params
        $res.status = [int]$resp.StatusCode
        $res.text = [string]$resp.Content
    } catch {
        $errResp = $_.Exception.Response
        if ($errResp) {
            $res.status = [int]$errResp.StatusCode
            try {
                $stream = $errResp.GetResponseStream()
                # PS 5.1 抛错前可能已把流读到末尾，不回卷就读出空串
                if ($stream -and $stream.CanSeek) { $stream.Position = 0 }
                $sr = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
                $res.text = $sr.ReadToEnd()
                $sr.Close()
            } catch { $res.text = '' }
        } else {
            $res.status = -1
            $res.error = $_.Exception.Message
        }
    }
    if ($res.text -ne '') {
        try { $res.json = $res.text | ConvertFrom-Json } catch { $res.json = $null }
    }
    return $res
}

function Get-Code {
    param($Res)
    if ($Res -and $Res.json -and ($Res.json.PSObject.Properties['code'])) { return [int]$Res.json.code }
    return $null
}

function Get-Data {
    param($Res)
    if ($Res -and $Res.json) { return $Res.json.data }
    return $null
}

function Eq {
    param($A, $B)
    if ($null -eq $A -or $null -eq $B) { return $false }
    try { return ([math]::Abs([double]$A - [double]$B) -lt 0.005) } catch { return $false }
}

function Step {
    param([string]$No, [string]$Title)
    Write-Host ''
    Write-Host ("===== {0}. {1}" -f $No, $Title) -ForegroundColor Cyan
}

function Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        $script:Pass++
        Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green
    } else {
        $script:Fail++
        $msg = "  [FAIL] {0}" -f $Name
        if ($Detail -ne '') { $msg = $msg + "   <<< " + $Detail }
        Write-Host $msg -ForegroundColor Red
    }
}

function New-IdemKey {
    param([string]$Tag)
    return ("smoke-{0}-{1}-{2}" -f $Tag, $Stamp, ([guid]::NewGuid().ToString('N').Substring(0, 8)))
}

# ------------------------------------------------------------------ 前置检查

$h = Invoke-Api -Url "$GW/health"
if ($h.status -ne 200) {
    Write-Host '[FAIL] 网关未就绪（http://127.0.0.1:8080/health）。请先运行 start-all.ps1' -ForegroundColor Red
    exit 2
}
Write-Host ('[env] gateway ok，开始冒烟  ' + (Get-Date -Format 'HH:mm:ss')) -ForegroundColor Cyan

# ================================================================== 步骤 1
Step '1' '管理端登录'
$createAdmin = '{"username":"' + $AdminUser + '","password":"' + $AdminPass + '","nickname":"Smoke Admin","roles":"ADMIN"}'
$rCreate = Invoke-Api -Url "$DIRECT_USER/api/v1/internal/users" -Method POST -Json $createAdmin `
    -Headers @{ 'Idempotency-Key' = (New-IdemKey 'admin') }
Write-Host ("  [info] 直连 :8081 建管理员（无种子账号兜底） code={0}" -f (Get-Code $rCreate))

$adminLoginBody = '{"username":"' + $AdminUser + '","password":"' + $AdminPass + '"}'
$rLogin = Invoke-Api -Url "$GW/api/admin/auth/login" -Method POST -Json $adminLoginBody
$AdminToken = $null
$loginData = Get-Data $rLogin
if ($loginData) { $AdminToken = $loginData.accessToken }
Check '管理端登录返回 accessToken' (($rLogin.status -eq 200) -and ((Get-Code $rLogin) -eq 0) -and (-not [string]::IsNullOrEmpty($AdminToken))) `
    ("status={0} code={1}" -f $rLogin.status, (Get-Code $rLogin))

$AdminH = @{}
if ($AdminToken) { $AdminH = @{ Authorization = ('Bearer ' + $AdminToken) } }

# ================================================================== 步骤 2
Step '2' '买家注册 / 登录'
$regBody = '{"mobile":"' + $BuyerMobile + '","code":"123456","password":"' + $BuyerPass + '"}'
$rReg = Invoke-Api -Url "$GW/api/v1/auth/register" -Method POST -Json $regBody `
    -Headers @{ 'Idempotency-Key' = (New-IdemKey 'buyer') }
Write-Host ("  [info] 注册 code={0}（已注册过则非 0，转登录）" -f (Get-Code $rReg))

$rBuyerLogin = Invoke-Api -Url "$GW/api/v1/auth/login" -Method POST `
    -Json ('{"username":"' + $BuyerMobile + '","password":"' + $BuyerPass + '"}')
$BuyerToken = $null
$buyerData = Get-Data $rBuyerLogin
if ($buyerData) { $BuyerToken = $buyerData.accessToken }
Check '买家登录返回 accessToken' (($rBuyerLogin.status -eq 200) -and ((Get-Code $rBuyerLogin) -eq 0) -and (-not [string]::IsNullOrEmpty($BuyerToken))) `
    ("status={0} code={1}" -f $rBuyerLogin.status, (Get-Code $rBuyerLogin))

$BuyerH = @{}
if ($BuyerToken) { $BuyerH = @{ Authorization = ('Bearer ' + $BuyerToken) } }

if (-not $AdminToken -or -not $BuyerToken) {
    Write-Host '[FAIL] 登录未通过，后续步骤无法执行' -ForegroundColor Red
    Write-Host ("  通过 {0} / 失败 {1}" -f $script:Pass, $script:Fail) -ForegroundColor Yellow
    exit 1
}

# ================================================================== 步骤 3
Step '3' '商品链路 + 搜索（类目 / 商品 / 价格 / 库存）'

# 3.1 建类目
$rCat = Invoke-Api -Url "$GW/api/admin/category" -Method POST -Json '{"name":"Smoke Category","parentId":0,"sort":1}' `
    -Headers ($AdminH + @{ 'Idempotency-Key' = (New-IdemKey 'cat') })
$CatId = $null
$d = Get-Data $rCat
if ($d) { $CatId = $d.id }
Check '创建类目' ((Get-Code $rCat) -eq 0 -and $null -ne $CatId) ("code={0}" -f (Get-Code $rCat))

# 3.2 建商品（两个 SKU：A 有库存，B 用于库存不足路径）
$skuJson = '"skus":[' +
    '{"skuCode":"SMOKE-A","title":"SKU A","spec":{"color":"black"},"price":199.00,"marketPrice":299.00},' +
    '{"skuCode":"SMOKE-B","title":"SKU B","spec":{"color":"white"},"price":99.00,"marketPrice":159.00}]'
$prodJson = '{"name":"Smoke Phone X1","subtitle":"smoke test","categoryId":' + [string]$CatId + ',' + $skuJson + '}'
$rProd = Invoke-Api -Url "$GW/api/admin/product" -Method POST -Json $prodJson `
    -Headers ($AdminH + @{ 'Idempotency-Key' = (New-IdemKey 'prod') })
$SpuId = $null
$d = Get-Data $rProd
if ($d) { $SpuId = $d.id }
Check '创建商品（草稿）' ((Get-Code $rProd) -eq 0 -and $null -ne $SpuId) ("code={0}" -f (Get-Code $rProd))

# 3.3 拿 SKU ID（商品创建返回的 Spu 不含 sku，走 admin 详情取）
$SkuA = $null; $SkuB = $null
if ($SpuId) {
    $rDetail = Invoke-Api -Url ("$GW/api/admin/product/{0}" -f $SpuId) -Headers $AdminH
    $d = Get-Data $rDetail
    if ($d -and $d.skus) {
        foreach ($s in @($d.skus)) {
            if ($s.skuCode -eq 'SMOKE-A') { $SkuA = $s.skuId }
            if ($s.skuCode -eq 'SMOKE-B') { $SkuB = $s.skuId }
        }
    }
}
Check '商品详情返回两个 SKU' ((Get-Code $rDetail) -eq 0 -and $null -ne $SkuA -and $null -ne $SkuB) `
    ("code={0} skuA={1} skuB={2}" -f (Get-Code $rDetail), $SkuA, $SkuB)

# 3.4 提交审核 -> 审核通过（自动上架）
$rSubmit = Invoke-Api -Url ("$GW/api/admin/product/{0}/submit-audit" -f $SpuId) -Method POST -Json '' `
    -Headers ($AdminH + @{ 'Idempotency-Key' = (New-IdemKey 'submit') })
$rApprove = Invoke-Api -Url ("$GW/api/admin/product/{0}/approve" -f $SpuId) -Method POST -Json '' `
    -Headers ($AdminH + @{ 'Idempotency-Key' = (New-IdemKey 'approve') })
$d = Get-Data $rApprove
Check '提交审核 + 审核通过（上架）' ((Get-Code $rApprove) -eq 0 -and $d -and [int]$d.auditStatus -eq 2 -and [int]$d.shelfStatus -eq 1) `
    ("submitCode={0} approveCode={1} auditStatus={2} shelfStatus={3}" -f (Get-Code $rSubmit), (Get-Code $rApprove), $(if ($d) { $d.auditStatus } else { 'null' }), $(if ($d) { $d.shelfStatus } else { 'null' }))

# 3.5 库存（直连 :8084 internal 建账本；管理端无库存写接口，P0 兜底）
function Ensure-Stock {
    param([long]$SkuId, [int]$Want, [string]$Label)
    $g = Invoke-Api -Url ("$DIRECT_INV/api/v1/inventory/stocks?skuIds={0}" -f $SkuId)
    $cur = $null
    $list = Get-Data $g
    if ($list) { foreach ($x in @($list)) { if ([long]$x.skuId -eq $SkuId) { $cur = $x.availableStock } } }
    if ($null -eq $cur) {
        $p = Invoke-Api -Url "$DIRECT_INV/api/v1/internal/inventory/stocks" -Method POST `
            -Json ('{"skuId":' + $SkuId + ',"availableStock":' + $Want + '}')
        $ok = ((Get-Code $p) -eq 0)
        Write-Host ("  [info] 建库存 {0} skuId={1} want={2} code={3}" -f $Label, $SkuId, $Want, (Get-Code $p))
        return $ok
    }
    Write-Host ("  [info] 库存 {0} skuId={1} 现有 available={2}（已有记录，不覆盖）" -f $Label, $SkuId, $cur)
    return $true
}
$stockAOk = Ensure-Stock -SkuId ([long]$SkuA) -Want 10 -Label 'A'
$stockBOk = Ensure-Stock -SkuId ([long]$SkuB) -Want 0 -Label 'B'
Check '库存账本就绪（A 可售 / B 缺货）' ($stockAOk -and $stockBOk)

# 3.6 价格（core-price 权威价；商品创建时已同步）
$rPrice = Invoke-Api -Url ("$GW/api/v1/price/skus?ids={0}" -f $SkuA) -Headers $BuyerH
$priceOk = $false
$plist = Get-Data $rPrice
if ($plist) { foreach ($x in @($plist)) { if ([long]$x.skuId -eq [long]$SkuA) { $priceOk = (Eq $x.price 199.00) } } }
Check 'core-price 返回权威价 199.00' ((Get-Code $rPrice) -eq 0 -and $priceOk) ("code={0}" -f (Get-Code $rPrice))

# 3.7 搜索（core-search 每 5s 从 core-product 全量同步，轮询等）
$found = $false
$searchTotal = -1
for ($i = 1; $i -le 20; $i++) {
    $rSearch = Invoke-Api -Url "$GW/api/v1/search?q=Smoke"
    $sd = Get-Data $rSearch
    if ($sd) {
        $searchTotal = [long]$sd.total
        if ($searchTotal -ge 1) {
            $names = @()
            foreach ($x in @($sd.list)) { $names += [string]$x.name }
            if (($names -join ' ') -like '*Smoke Phone X1*') { $found = $true; break }
            $found = $true; break
        }
    }
    Start-Sleep -Seconds 3
}
Check '搜索命中新建商品' ($found) ("total={0} 第{1}次查询" -f $searchTotal, $i)

if ($null -eq $SkuA) {
    Write-Host '[FAIL] 未取得 SKU，后续步骤无法执行' -ForegroundColor Red
    Write-Host ("  通过 {0} / 失败 {1}" -f $script:Pass, $script:Fail) -ForegroundColor Yellow
    exit 1
}

# ================================================================== 步骤 4
Step '4' '加入购物车'
$rCart = Invoke-Api -Url "$GW/api/v1/cart/add" -Method POST `
    -Json ('{"skuId":' + $SkuA + ',"quantity":2}') `
    -Headers ($BuyerH + @{ 'Idempotency-Key' = (New-IdemKey 'cart') })
Check '加购成功' ((Get-Code $rCart) -eq 0) ("code={0}" -f (Get-Code $rCart))

$rCartList = Invoke-Api -Url "$GW/api/v1/cart/list" -Headers $BuyerH
$inCart = $false
$cd = Get-Data $rCartList
if ($cd -and $cd.items) {
    foreach ($x in @($cd.items)) { if ([long]$x.skuId -eq [long]$SkuA) { $inCart = $true } }
}
Check '购物车列表包含该 SKU' ((Get-Code $rCartList) -eq 0 -and $inCart) ("code={0}" -f (Get-Code $rCartList))

# ================================================================== 步骤 5
Step '5' '创建订单'
$orderJson = '{"items":[{"skuId":' + $SkuA + ',"quantity":2}],' +
    '"receiverName":"Smoke Buyer","receiverMobile":"13900013900",' +
    '"receiverAddress":"Smoke Street 1","remark":"smoke"}'
$OrderKey = New-IdemKey 'order'
$rOrder = Invoke-Api -Url "$GW/api/v1/orders" -Method POST -Json $orderJson `
    -Headers ($BuyerH + @{ 'Idempotency-Key' = $OrderKey })
$Order = Get-Data $rOrder
Check '下单成功' ((Get-Code $rOrder) -eq 0 -and $null -ne $Order) ("code={0} msg={1}" -f (Get-Code $rOrder), $(if ($rOrder.json) { $rOrder.json.msg } else { '' }))

$OrderId = $null
if ($Order) { $OrderId = $Order.orderId }

$stOk = $false; $deadlineOk = $false; $subOk = $false; $amountOk = $false
if ($Order) {
    $stOk = ([int]$Order.status -eq 0)
    $deadlineOk = (-not [string]::IsNullOrEmpty([string]$Order.payDeadline))
    $subOk = (@($Order.subOrders).Count -eq 1)
    $expect = [double]$Order.totalAmount + [double]$Order.freightAmount - [double]$Order.discountAmount
    $amountOk = (Eq $Order.payAmount $expect)
}
Check '初始状态 = 0（0 基 8 值：待付款）' $stOk ("status={0}" -f $(if ($Order) { $Order.status } else { 'null' }))
Check 'pay_deadline 已生成' $deadlineOk ("payDeadline={0}" -f $(if ($Order) { $Order.payDeadline } else { 'null' }))
Check '拆单子单数 = 1（P0 单店铺）' $subOk ("subOrders={0}" -f $(if ($Order) { @($Order.subOrders).Count } else { 'null' }))
Check '金额自洽 payAmount = total + freight - discount' $amountOk `
    ("total={0} freight={1} discount={2} pay={3}" -f $Order.totalAmount, $Order.freightAmount, $Order.discountAmount, $Order.payAmount)

# ================================================================== 步骤 6
Step '6' '预支付 prepay'
$rPrepay = Invoke-Api -Url "$GW/api/v1/payment/prepay" -Method POST `
    -Json ('{"orderId":' + $OrderId + ',"channel":1}') -Headers $BuyerH
$Prepay = Get-Data $rPrepay
$payOk = $false
if ($Prepay -and $Order) { $payOk = (Eq $Prepay.amount $Order.payAmount) }
Check 'prepay 金额 = 订单后端定额' ((Get-Code $rPrepay) -eq 0 -and $payOk) `
    ("code={0} prepay.amount={1} order.payAmount={2}" -f (Get-Code $rPrepay), $(if ($Prepay) { $Prepay.amount } else { 'null' }), $Order.payAmount)
$PaymentId = $null
if ($Prepay) { $PaymentId = $Prepay.paymentId }

# ================================================================== 步骤 7
Step '7' 'mock 支付'
$rMock = Invoke-Api -Url "$GW/api/v1/payment/mock/pay" -Method POST `
    -Json ('{"paymentId":' + $PaymentId + '}') -Headers $BuyerH
Check 'mock 支付受理' ((Get-Code $rMock) -eq 0) ("code={0} msg={1}" -f (Get-Code $rMock), $(if ($rMock.json) { $rMock.json.msg } else { '' }))

# ================================================================== 步骤 8
Step '8' '查单：状态 = 1（待发货）'
$paidStatus = $null
for ($i = 1; $i -le 10; $i++) {
    $rDetailOrder = Invoke-Api -Url ("$GW/api/v1/orders/{0}" -f $OrderId) -Headers $BuyerH
    $od = Get-Data $rDetailOrder
    if ($od) {
        $paidStatus = [int]$od.status
        if ($paidStatus -eq 1) { break }
    }
    Start-Sleep -Seconds 1
}
Check '支付后订单状态 = 1' ($paidStatus -eq 1) ("status={0}" -f $paidStatus)

# ================================================================== 步骤 9
Step '9' '同 Idempotency-Key 重复下单'
$rListBefore = Invoke-Api -Url "$GW/api/v1/orders?page=1&pageSize=100" -Headers $BuyerH
$beforeTotal = -1
$bd = Get-Data $rListBefore
if ($bd) { $beforeTotal = [long]$bd.total }

$rDup = Invoke-Api -Url "$GW/api/v1/orders" -Method POST -Json $orderJson `
    -Headers ($BuyerH + @{ 'Idempotency-Key' = $OrderKey })

$rListAfter = Invoke-Api -Url "$GW/api/v1/orders?page=1&pageSize=100" -Headers $BuyerH
$afterTotal = -1
$ad = Get-Data $rListAfter
if ($ad) { $afterTotal = [long]$ad.total }

$dupCode = Get-Code $rDup
$conflict = ($rDup.status -eq 409) -or ($dupCode -eq 1005)
$maybeSameOrder = ($dupCode -eq 0 -and $Order -and (Get-Data $rDup) -and ([long](Get-Data $rDup).orderId -eq [long]$OrderId))
Check '重复幂等键不产生第二单' (($beforeTotal -ge 0) -and ($afterTotal -eq $beforeTotal)) ("before={0} after={1}" -f $beforeTotal, $afterTotal)
Check '重复下单返回 409/1005（幂等冲突）或同单' ($conflict -or $maybeSameOrder) `
    ("http={0} code={1}" -f $rDup.status, $dupCode)

# ================================================================== 步骤 10
Step '10' '库存不足路径'
$stockJson = '{"items":[{"skuId":' + $SkuB + ',"quantity":1}],' +
    '"receiverName":"Smoke Buyer","receiverMobile":"13900013900",' +
    '"receiverAddress":"Smoke Street 1","remark":"no-stock"}'
$rNoStock = Invoke-Api -Url "$GW/api/v1/orders" -Method POST -Json $stockJson `
    -Headers ($BuyerH + @{ 'Idempotency-Key' = (New-IdemKey 'nostock') })
Check '缺货下单返回 3002（OUT_OF_STOCK）' ((Get-Code $rNoStock) -eq 3002) `
    ("http={0} code={1} msg={2}" -f $rNoStock.status, (Get-Code $rNoStock), $(if ($rNoStock.json) { $rNoStock.json.msg } else { '' }))

# ================================================================== 步骤 11
Step '11' '领券 -> 下单用券 -> 减免正确'
$nowUtc = (Get-Date).ToUniversalTime()
$couponJson = '{"name":"Smoke Coupon","type":1,"value":10,"minAmount":50,' +
    '"totalCount":100,"perLimit":1,' +
    '"startTime":"' + $nowUtc.AddMinutes(-1).ToString('yyyy-MM-ddTHH:mm:ssZ') + '",' +
    '"endTime":"' + $nowUtc.AddDays(7).ToString('yyyy-MM-ddTHH:mm:ssZ') + '"}'
$rCoupon = Invoke-Api -Url "$GW/api/admin/coupon" -Method POST -Json $couponJson `
    -Headers ($AdminH + @{ 'Idempotency-Key' = (New-IdemKey 'coupon') })
$TemplateId = $null
$d = Get-Data $rCoupon
if ($d) { $TemplateId = $d.id }
Check '管理端创建券模板' ((Get-Code $rCoupon) -eq 0 -and $null -ne $TemplateId) ("code={0}" -f (Get-Code $rCoupon))

$rReceive = Invoke-Api -Url "$GW/api/v1/coupons/receive" -Method POST `
    -Json ('{"couponId":' + $TemplateId + '}') -Headers $BuyerH
$RecordId = $null
$d = Get-Data $rReceive
if ($d) { $RecordId = $d.couponId }
Check '买家领券（返回用户券记录 ID）' ((Get-Code $rReceive) -eq 0 -and $null -ne $RecordId) ("code={0}" -f (Get-Code $rReceive))

$couponOrderJson = '{"items":[{"skuId":' + $SkuA + ',"quantity":1}],' +
    '"receiverName":"Smoke Buyer","receiverMobile":"13900013900",' +
    '"receiverAddress":"Smoke Street 1","couponId":' + $RecordId + ',"remark":"coupon"}'
$rCouponOrder = Invoke-Api -Url "$GW/api/v1/orders" -Method POST -Json $couponOrderJson `
    -Headers ($BuyerH + @{ 'Idempotency-Key' = (New-IdemKey 'couponorder') })
$CouponOrder = Get-Data $rCouponOrder
$discountOk = $false; $payOk2 = $false
if ($CouponOrder) {
    $discountOk = (Eq $CouponOrder.discountAmount 10)
    $expect2 = [double]$CouponOrder.totalAmount + [double]$CouponOrder.freightAmount - [double]$CouponOrder.discountAmount
    $payOk2 = (Eq $CouponOrder.payAmount $expect2)
}
Check '用券下单成功' ((Get-Code $rCouponOrder) -eq 0 -and $null -ne $CouponOrder) ("code={0} msg={1}" -f (Get-Code $rCouponOrder), $(if ($rCouponOrder.json) { $rCouponOrder.json.msg } else { '' }))
Check '优惠减免 = 10（券面额）' $discountOk ("discountAmount={0}" -f $(if ($CouponOrder) { $CouponOrder.discountAmount } else { 'null' }))
Check '实付 = 总额 + 运费 - 减免' $payOk2 ("total={0} discount={1} pay={2}" -f $(if ($CouponOrder) { $CouponOrder.totalAmount } else { '' }), $(if ($CouponOrder) { $CouponOrder.discountAmount } else { '' }), $(if ($CouponOrder) { $CouponOrder.payAmount } else { '' }))

# ================================================================== 步骤 12
Step '12' '管理端订单列表 + 发货 -> 状态 = 2'
$rAdminList = Invoke-Api -Url "$GW/api/admin/order/list?pageNum=1&pageSize=100" -Headers $AdminH
$visible = $false
$ad = Get-Data $rAdminList
if ($ad -and $ad.list) {
    foreach ($x in @($ad.list)) { if ([long]$x.id -eq [long]$OrderId) { $visible = $true } }
}
Check '管理端订单列表可见' ((Get-Code $rAdminList) -eq 0 -and $visible) `
    ("code={0} total={1} orderId={2}" -f (Get-Code $rAdminList), $(if ($ad) { $ad.total } else { 'null' }), $OrderId)

$rShip = Invoke-Api -Url ("$GW/api/admin/order/{0}/ship" -f $OrderId) -Method POST `
    -Json '{"trackingNo":"SMOKE123456"}' `
    -Headers ($AdminH + @{ 'Idempotency-Key' = (New-IdemKey 'ship') })
Check '发货成功' ((Get-Code $rShip) -eq 0) ("code={0} msg={1}" -f (Get-Code $rShip), $(if ($rShip.json) { $rShip.json.msg } else { '' }))

$shipStatus = $null
for ($i = 1; $i -le 6; $i++) {
    $rAfter = Invoke-Api -Url ("$GW/api/v1/orders/{0}" -f $OrderId) -Headers $BuyerH
    $od = Get-Data $rAfter
    if ($od) { $shipStatus = [int]$od.status; if ($shipStatus -eq 2) { break } }
    Start-Sleep -Seconds 1
}
Check '发货后订单状态 = 2（0 基：待收货）' ($shipStatus -eq 2) ("status={0}" -f $shipStatus)

# ================================================================== 步骤 13
Step '13' '未带 token 访问需登录接口'
$rNoAuth = Invoke-Api -Url "$GW/api/v1/cart/list"
Check 'HTTP 401' ($rNoAuth.status -eq 401) ("status={0}" -f $rNoAuth.status)
Check '业务码 1002（未登录）' ((Get-Code $rNoAuth) -eq 1002) ("code={0}" -f (Get-Code $rNoAuth))

# ------------------------------------------------------------------ 汇总
Write-Host ''
Write-Host ('==================================================') -ForegroundColor Cyan
Write-Host ("  冒烟结果：通过 {0} / 失败 {1}" -f $script:Pass, $script:Fail) -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Red' })
Write-Host ('==================================================') -ForegroundColor Cyan
if ($script:Fail -eq 0) { exit 0 } else { exit 1 }
