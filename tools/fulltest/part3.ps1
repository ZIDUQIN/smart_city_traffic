# Part3：I 实例预热 / D8 售罄 / D9 限流 / H1 超卖 / H2 支付取消竞态 / H3 双取消 / H5 缓存一致
. "D:\IDEAproject\smart-city-traffic\tools\fulltest\common.ps1"
$date = "2026-11-20"
$inst = "D2636-20261120"
$bt = [char]96
Add-Content $script:ResultsFile "===== PART3 $(Get-Date -Format 'HH:mm:ss') =====" -Encoding utf8
$admin = Login "admin"
Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=$date" -Token $admin.access | Out-Null

function ResetStock([int]$v) {
    Sql "UPDATE stock SET remain=$v WHERE instance_no='$inst' AND seat_no='SECOND_CLASS' AND segment_no=1;" | Out-Null
    RedisCmd "DEL 'stock:{$inst}:SECOND_CLASS'" | Out-Null
    Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=$date&seatNo=SECOND_CLASS" -Token $admin.access | Out-Null
}
ResetStock 50

# ---------- I ----------
$r = Api -Path "/api/instances/window?trainNo=D2636&days=7" -Token $admin.access
$nonNull = ($r.data | Where-Object { $null -ne $_ }).Count
$r1 = Api -Method POST -Path "/api/instances/ensure?days=7" -Token $admin.access
$r2 = Api -Method POST -Path "/api/instances/ensure?days=7" -Token $admin.access
Check "I1" ($nonNull -eq 7 -and [int]$r2.data -eq 0) "窗口7天=$nonNull 二次ensure新增=$($r2.data)"

Start-Sleep -Milliseconds 1300
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $date "SECOND_CLASS" 1 2)
$oI2 = $r.data.orderNo
$beforePh = RemainDb $inst "SECOND_CLASS" 1
$rPh = Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=$date" -Token $admin.access
$afterPh = RemainDb $inst "SECOND_CLASS" 1
Check "I2" ($afterPh -eq ($beforePh - 1) -and [int]$rPh.data -eq 0) "预热不覆盖已售库存 before=$beforePh after=$afterPh 新写入=$($rPh.data)（HSETNX）"
Api -Method POST -Path "/api/orders/$oI2/cancel" -Token $admin.access | Out-Null

$r = Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=2027-01-01" -Token $admin.access
Check "I3" ($r.code -eq 400) "不存在日期预热 code=$($r.code)"

$r = Api -Method POST -Path "/api/instances/cleanup?delayDays=30" -Token $admin.access
$rW = Api -Path "/api/instances/window?trainNo=D2636&days=7" -Token $admin.access
$still7 = ($rW.data | Where-Object { $null -ne $_ }).Count
Check "I4" ([int]$r.data -eq 0 -and $still7 -eq 7) "cleanup删=$($r.data) 窗口完好=$still7"

# ---------- D8 售罄 ----------
ResetStock 0
Start-Sleep -Milliseconds 1300
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $date "SECOND_CLASS" 1 2)
Check "D8" ($r.data.status -eq "已取消" -and $r.data.message -match "余票不足") "售罄下单 status=$($r.data.status) msg=$($r.data.message)"

# ---------- H1 超卖 ----------
ResetStock 3
$results = 1..20 | ForEach-Object -Parallel {
    $i = $_
    $uname = "load{0:D3}" -f $i
    $pid2 = 5000 + $i
    try {
        $lg = Invoke-RestMethod -Uri "http://localhost:8083/api/auth/login" -Method Post -ContentType "application/json" -Body (@{ username = $uname; password = "123456" } | ConvertTo-Json) -TimeoutSec 30
        $tk = $lg.data.accessToken
        $body = @{ passengerId = $pid2; trainNo = "D2636"; travelDate = "2026-11-20"; seatNo = "SECOND_CLASS"; fromSeq = 1; toSeq = 2 } | ConvertTo-Json
        $rr = Invoke-WebRequest -Uri "http://localhost:8083/api/orders" -Method Post -Headers @{ Authorization = "Bearer $tk" } -ContentType "application/json; charset=utf-8" -Body $body -SkipHttpErrorCheck -TimeoutSec 40
        return "$uname|$($rr.Content)"
    } catch {
        return "$uname|ERR $($_.Exception.Message)"
    }
}
$succ = @($results | Where-Object { $_ -match '"status":"已确认"' })
$dbAfter = RemainDb $inst "SECOND_CLASS" 1
$redisAfter = RedisCmd "HGET 'stock:{$inst}:SECOND_CLASS' 1"
$ordCnt = (Sql "SELECT COUNT(*) FROM ${bt}order${bt} WHERE instance_no='$inst' AND status IN ('已确认','已支付');" | Select-Object -First 1)
Check "H1" ($succ.Count -eq 3 -and $dbAfter -eq 0 -and "$redisAfter" -match "^0$" -and [int]$ordCnt -eq 3) "20并发成功=$($succ.Count) DB=$dbAfter Redis=$redisAfter 订单=$ordCnt"

# ---------- D9 单用户限流 ----------
$burst = @()
foreach ($pid3 in @(1001, 1002, 1003, 1004, 1001, 1002)) {
    $r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody $pid3 "D2636" $date "SECOND_CLASS" 1 2)
    $burst += $r.code
}
$cnt429 = ($burst | Where-Object { $_ -eq 429 }).Count
Check "D9" ($cnt429 -ge 1) "6连发 codes=$($burst -join ',') 429数=$cnt429"

# 清理 H1 的 3 单（各用户自己取消）
$h1Orders = Sql "SELECT order_no, user_id FROM ${bt}order${bt} WHERE instance_no='$inst' AND status='已确认';"
foreach ($line in $h1Orders) {
    $parts = "$line" -split "\t"
    if ($parts.Count -lt 2) { continue }
    $ono = $parts[0]; $uid = [int]$parts[1]
    $uname = "load{0:D3}" -f ($uid - 3000)
    $tk = (Login $uname).access
    Api -Method POST -Path "/api/orders/$ono/cancel" -Token $tk | Out-Null
}
Start-Sleep -Seconds 1
$afterCleanup = RemainDb $inst "SECOND_CLASS" 1
Check "H1cleanup" ($afterCleanup -eq 3) "取消3单后 remain=$afterCleanup"

# ---------- H2 取消 vs 支付竞态 ----------
Start-Sleep -Milliseconds 1300
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $date "SECOND_CLASS" 1 2)
$oH2 = $r.data.orderNo
$rP = Api -Method POST -Path "/api/pay/prepay?orderNo=$oH2" -Token $admin.access
$payToken = $rP.data.payToken
$race = @("cancel", "confirm") | ForEach-Object -Parallel {
    $op = $_
    $a = $using:admin
    $on = $using:oH2
    $pt = $using:payToken
    $uri = if ($op -eq "cancel") { "http://localhost:8083/api/orders/$on/cancel" } else { "http://localhost:8083/api/pay/mock/confirm?payToken=$pt" }
    $rr = Invoke-WebRequest -Uri $uri -Method Post -Headers @{ Authorization = "Bearer $($a.access)" } -SkipHttpErrorCheck -TimeoutSec 30
    return "$op|$($rr.Content)"
}
Start-Sleep -Seconds 3
$stH2 = OrderStatus $oH2
$activeH2 = [int](Sql "SELECT COUNT(*) FROM ${bt}order${bt} WHERE instance_no='$inst' AND seat_no='SECOND_CLASS' AND from_seq<=1 AND to_seq>1 AND status IN ('已确认','已支付');" | Select-Object -First 1)
$remainH2 = RemainDb $inst "SECOND_CLASS" 1
$consistent = ($remainH2 -eq (3 - $activeH2))
Check "H2" (($stH2 -eq "已支付" -or $stH2 -eq "已取消") -and $consistent) "竞态终态=$stH2 remain=$remainH2 有效单=$activeH2 一致=$consistent race=$($race -join ' // ')"
if ($stH2 -eq "已支付") {
    Api -Method POST -Path "/api/orders/$oH2/refund" -Token $admin.access | Out-Null
    Start-Sleep -Seconds 2
}

# ---------- H3 并发双取消 ----------
Start-Sleep -Milliseconds 1300
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1002 "D2636" $date "SECOND_CLASS" 1 2)
$oH3 = $r.data.orderNo
$beforeH3 = RemainDb $inst "SECOND_CLASS" 1
$dc = 1..2 | ForEach-Object -Parallel {
    $a = $using:admin
    $on = $using:oH3
    $rr = Invoke-WebRequest -Uri "http://localhost:8083/api/orders/$on/cancel" -Method Post -Headers @{ Authorization = "Bearer $($a.access)" } -SkipHttpErrorCheck -TimeoutSec 30
    return $rr.Content
}
Start-Sleep -Seconds 1
$afterH3 = RemainDb $inst "SECOND_CLASS" 1
$recH3 = (Sql "SELECT COUNT(*) FROM stock_restore_record WHERE order_no='$oH3';" | Select-Object -First 1)
Check "H3" ($afterH3 -eq ($beforeH3 + 1) -and [int]$recH3 -eq 1) "双取消 remain $beforeH3->$afterH3 幂等记录=$recH3"

# ---------- H5 Redis 与 DB 逐段一致 ----------
function HashVsDb([string]$instance, [string]$seat) {
    $raw = RedisCmd "HGETALL 'stock:{$instance}:$seat'"
    $lines = @($raw | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
    $map = @{}
    for ($i = 0; $i -lt $lines.Count - 1; $i += 2) { $map[$lines[$i]] = $lines[$i + 1] }
    $rows = Sql "SELECT segment_no, remain FROM stock WHERE instance_no='$instance' AND seat_no='$seat';"
    $ok = $true
    foreach ($line in $rows) {
        $p = "$line" -split "\t"
        if ($p.Count -lt 2) { continue }
        if (-not $map.ContainsKey($p[0]) -or $map[$p[0]] -ne $p[1]) { $ok = $false }
    }
    return $ok
}
$ok1 = HashVsDb $inst "SECOND_CLASS"
$ok2 = HashVsDb "D2636-20261118" "SECOND_CLASS"
Check "H5" ($ok1 -and $ok2) "专用实例一致=$ok1 今日实例一致=$ok2"

# 收尾：专用实例重置回满库存（H1 曾把库存设为 3）
ResetStock 50
$endRemain = RemainDb $inst "SECOND_CLASS" 1
$endActive = [int](Sql "SELECT COUNT(*) FROM ${bt}order${bt} WHERE instance_no='$inst' AND status IN ('已确认','已支付');" | Select-Object -First 1)
Check "Hend" ($endRemain -eq 50 -and $endActive -eq 0) "part3收尾 remain=$endRemain 有效单=$endActive"
Write-Host "PART3 DONE"