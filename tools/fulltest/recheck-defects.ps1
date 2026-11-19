# 五缺陷修复回归：D-01 越权读 / D-02 幻影库存 / D-03 管理端点 / D-04 注册校验 / D-05 回调防御
. "D:\IDEAproject\smart-city-traffic\tools\fulltest\common.ps1"
$bt = [char]96
$date = "2026-11-20"
$inst = "D2636-20261120"
Add-Content $script:ResultsFile "===== RECHECK-DEFECTS $(Get-Date -Format 'HH:mm:ss') =====" -Encoding utf8

$admin = Login "admin"
$load1 = Login "load001"

# ---------- D-04 注册长度 ----------
$long = "u" * 200
$r = Api -Method POST -Path "/api/auth/register" -Body @{ username = $long; password = "123456" }
Check "D04" ($r.code -eq 400 -and $r.raw -notmatch "mapper|SQL|Data truncation") "200字符注册 code=$($r.code) msg=$($r.message)"
$r2 = Api -Method POST -Path "/api/auth/register" -Body @{ username = "shortpw"; password = "123" }
Check "D04b" ($r2.code -eq 400) "短密码注册 code=$($r2.code) msg=$($r2.message)"

# ---------- 准备专用实例 ----------
Sql "UPDATE stock SET remain=50 WHERE instance_no='$inst' AND seat_no='SECOND_CLASS' AND segment_no=1;" | Out-Null
RedisCmd "DEL 'stock:{$inst}:SECOND_CLASS'" | Out-Null
Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=$date" -Token $admin.access | Out-Null
Start-Sleep -Milliseconds 1300
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1002 "D2636" $date "SECOND_CLASS" 1 2)
$o1 = $r.data.orderNo
Check "prep" ($r.data.status -eq "已确认") "测试单=$o1 状态=$($r.data.status)"

# ---------- D-01 越权读三接口 ----------
$r1 = Api -Path "/api/orders/$o1" -Token $load1.access
Check "D01a" ($r1.code -eq 400) "他人查订单 code=$($r1.code) msg=$($r1.message)"
$rP = Api -Method POST -Path "/api/pay/prepay?orderNo=$o1" -Token $admin.access
$r2 = Api -Path "/api/pay/status/$o1" -Token $load1.access
Check "D01b" ($r2.code -eq 400) "他人查支付 code=$($r2.code) msg=$($r2.message)"
$r3 = Api -Path "/api/orders/$o1/refund" -Token $load1.access
Check "D01c" ($r3.code -eq 400) "他人查退款 code=$($r3.code) msg=$($r3.message)"
$r4 = Api -Path "/api/orders/$o1" -Token $admin.access
Check "D01d" ($r4.code -eq 200) "本人查订单 code=$($r4.code)"

# ---------- D-03 管理端点 ----------
$rE = Api -Method POST -Path "/api/instances/ensure?days=7" -Token $load1.access
$rC = Api -Method POST -Path "/api/instances/cleanup?delayDays=30" -Token $load1.access
$rO = Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=$date" -Token $load1.access
$rM = Api -Method POST -Path "/api/pay/mock/refund-result?channelRefundNo=PROBE2&result=SUCCESS" -Token $load1.access
Check "D03" ($rE.http -eq 403 -and $rC.http -eq 403 -and $rO.http -eq 403 -and $rM.http -eq 403) "普通用户 ensure=$($rE.http) cleanup=$($rC.http) preheat=$($rO.http) 注入=$($rM.http)"
$rA = Api -Method POST -Path "/api/instances/ensure?days=7" -Token $admin.access
Check "D03b" ($rA.code -eq 200) "管理员 ensure code=$($rA.code)"

# ---------- D-05 支付回调缺金额 ----------
$pNo = (Sql "SELECT payment_no FROM payment_record WHERE order_no='$o1' AND status='待支付' LIMIT 1;" | Select-Object -First 1)
$tradeNo = (Sql "SELECT channel_trade_no FROM payment_record WHERE payment_no='$pNo';" | Select-Object -First 1)
$body = '{"orderNo":"' + $o1 + '","paymentNo":"' + $pNo + '","channelTradeNo":"' + $tradeNo + '"}'
$r5 = Api -Method POST -Path "/api/pay/notify" -RawBody $body -Headers @{ "X-Mock-Signature" = (HmacSign $body) }
Check "D05" ($r5.raw -eq '{"code":"FAILURE"}') "缺amount回调 raw=$($r5.raw)"

# ---------- D-02 缓存缺失时回补不造幻影 ----------
$dedupKey = "passenger:dedup:D2636:1002"
RedisCmd "DEL 'stock:{$inst}:SECOND_CLASS'" | Out-Null
$rC1 = Api -Method POST -Path "/api/orders/$o1/cancel" -Token $admin.access
Start-Sleep -Seconds 1
$exists = RedisCmd "EXISTS 'stock:{$inst}:SECOND_CLASS'"
$dedup = RedisCmd "EXISTS '$dedupKey'"
$dbRemain = RemainDb $inst "SECOND_CLASS" 1
Check "D02a" ("$exists" -match "^0$" -and "$dedup" -match "^0$" -and $dbRemain -eq 50) "缓存缺失回补：key存在=$exists 占位存在=$dedup DB=$dbRemain"

# 回源重建后仍与 DB 一致
Api -Path "/api/ticket/search?fromStation=%E5%AE%9D%E9%B8%A1%E5%8D%97&toStation=%E5%92%B8%E9%98%B3%E7%A7%A6%E9%83%BD&travelDate=$date" -Token $admin.access | Out-Null
$rPh = Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=$date" -Token $admin.access
Start-Sleep -Milliseconds 1300
$rO2 = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1002 "D2636" $date "SECOND_CLASS" 1 2)
Check "D02b" ($rO2.data.status -eq "已确认") "占位释放后可再买 orderNo=$($rO2.data.orderNo) 状态=$($rO2.data.status)"
if ($rO2.data.orderNo) { Api -Method POST -Path "/api/orders/$($rO2.data.orderNo)/cancel" -Token $admin.access | Out-Null }
Start-Sleep -Seconds 1
Check "D02c" ((RemainDb $inst "SECOND_CLASS" 1) -eq 50) "清理后 DB remain=$(RemainDb $inst 'SECOND_CLASS' 1)"

Write-Host "RECHECK-DEFECTS DONE"