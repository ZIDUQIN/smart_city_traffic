# Part2：E 支付 / G 退款 / F6 容量闸（专用实例 D2636-20261120）
. "D:\IDEAproject\smart-city-traffic\tools\fulltest\common.ps1"
$date = "2026-11-20"
$inst = "D2636-20261120"
$bt = [char]96
Add-Content $script:ResultsFile "===== PART2 $(Get-Date -Format 'HH:mm:ss') =====" -Encoding utf8
$admin = Login "admin"
$load1 = Login "load001"
# 专用实例先预热（Redis 无缓存时下单预检会 -2）
Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=$date" -Token $admin.access | Out-Null

function NewOrder([int]$passengerId) {
    Start-Sleep -Milliseconds 1300
    $r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody $passengerId "D2636" $date "SECOND_CLASS" 1 2)
    if ($r.data.status -ne "已确认") { throw "下单失败 pid=$passengerId : $($r.raw)" }
    return $r.data.orderNo
}
function PayOrder([string]$orderNo) {
    $r = Api -Method POST -Path "/api/pay/prepay?orderNo=$orderNo" -Token $admin.access
    $token = $r.data.payToken
    Api -Method POST -Path "/api/pay/mock/confirm?payToken=$token" -Token $admin.access | Out-Null
    Start-Sleep -Seconds 2
    return $r.data.paymentNo
}

# ---------- E ----------
$remainBefore = RemainDb $inst "SECOND_CLASS" 1
$oE = NewOrder 1001
$r = Api -Method POST -Path "/api/pay/prepay?orderNo=$oE" -Token $admin.access
$p1 = $r.data.paymentNo; $t1 = $r.data.payToken
$amtDb = [decimal](Sql "SELECT amount FROM ${bt}order${bt} WHERE order_no='$oE';" | Select-Object -First 1)
Check "E1" ($r.code -eq 200 -and $p1 -and $t1 -and [decimal]$r.data.amount -eq $amtDb) "prepay 金额=$($r.data.amount) db=$amtDb"

$r2 = Api -Method POST -Path "/api/pay/prepay?orderNo=$oE" -Token $admin.access
$p2 = $r2.data.paymentNo
$waiting = (Sql "SELECT COUNT(*) FROM payment_record WHERE order_no='$oE' AND status='待支付';" | Select-Object -First 1)
Check "E5a" ($r2.code -eq 200 -and $waiting -eq 2) "重复prepay 待支付笔数=$waiting"

$rX = Api -Method POST -Path "/api/pay/prepay?orderNo=$oE" -Token $load1.access
$rC = Api -Method POST -Path "/api/pay/prepay?orderNo=381556058272833536" -Token $admin.access
$rP = Api -Method POST -Path "/api/pay/prepay?orderNo=381556283582455808" -Token $admin.access
Check "E2" ($rX.code -eq 400 -and $rC.code -eq 400 -and $rP.code -eq 400) "他人=$($rX.code) 已取消=$($rC.code) 已支付=$($rP.code)"

$r = Api -Method POST -Path "/api/pay/mock/confirm?payToken=BOGUSTOKEN" -Token $admin.access
Check "E3" ($r.code -eq 400) "无效token code=$($r.code)"

# E6/E7/E9 回调探针（支付前）
$body = '{"orderNo":"' + $oE + '","paymentNo":"' + $p1 + '","channelTradeNo":"MOCKTEST","amount":' + $amtDb + '}'
$r6 = Api -Method POST -Path "/api/pay/notify" -RawBody $body -Headers @{ "X-Mock-Signature" = "deadbeef" }
$sig7 = HmacSign ('{"orderNo":"' + $oE + '","paymentNo":"' + $p1 + '","channelTradeNo":"MOCKTEST","amount":' + ($amtDb + 1) + '}')
$r7 = Api -Method POST -Path "/api/pay/notify" -RawBody ('{"orderNo":"' + $oE + '","paymentNo":"' + $p1 + '","channelTradeNo":"MOCKTEST","amount":' + ($amtDb + 1) + '}') -Headers @{ "X-Mock-Signature" = $sig7 }
$st6 = OrderStatus $oE
Check "E6" ($r6.raw -match "FAILURE" -and $st6 -eq "已确认") "错签名 $($r6.raw) 状态=$st6"
Check "E7" ($r7.raw -match "FAILURE" -and $st6 -eq "已确认") "篡改金额 $($r7.raw)"

$body9 = '{"orderNo":"' + $oE + '","paymentNo":"' + $p1 + '","channelTradeNo":"MOCKTEST"}'
$sig9 = HmacSign $body9
$r9 = Api -Method POST -Path "/api/pay/notify" -RawBody $body9 -Headers @{ "X-Mock-Signature" = $sig9 }
Check "E9" ($r9.http -eq 200 -and $r9.raw -match "FAILURE") "缺amount http=$($r9.http) raw=$($r9.raw)（期望FAILURE不500）"

# E4 真实支付
Api -Method POST -Path "/api/pay/mock/confirm?payToken=$t1" -Token $admin.access | Out-Null
Start-Sleep -Seconds 3
$stE = OrderStatus $oE
$p1st = (Sql "SELECT status FROM payment_record WHERE payment_no='$p1';" | Select-Object -First 1)
$p2st = (Sql "SELECT status FROM payment_record WHERE payment_no='$p2';" | Select-Object -First 1)
Check "E4" ($stE -eq "已支付" -and $p1st -eq "支付成功") "订单=$stE 流水1=$p1st"
Check "E5b" ($p2st -eq "已关闭") "另一笔待支付自动关闭=$p2st"

# E8 重放合法回调
$tradeNo = (Sql "SELECT channel_trade_no FROM payment_record WHERE payment_no='$p1';" | Select-Object -First 1)
$cbBefore = [int](Sql "SELECT callback_count FROM payment_record WHERE payment_no='$p1';" | Select-Object -First 1)
$replay = '{"orderNo":"' + $oE + '","paymentNo":"' + $p1 + '","channelTradeNo":"' + $tradeNo + '","amount":' + $amtDb + '}'
$r8 = Api -Method POST -Path "/api/pay/notify" -RawBody $replay -Headers @{ "X-Mock-Signature" = (HmacSign $replay) }
$cbAfter = [int](Sql "SELECT callback_count FROM payment_record WHERE payment_no='$p1';" | Select-Object -First 1)
Check "E8" ($r8.raw -match "SUCCESS" -and $cbAfter -eq ($cbBefore + 1) -and (OrderStatus $oE) -eq "已支付") "重放SUCCESS 回调次数 $cbBefore->$cbAfter"

$r = Api -Path "/api/pay/status/$oE" -Token $load1.access
Check "E10" ($r.code -ne 200) "越权查支付 code=$($r.code)（期望拒绝）"

# ---------- G ----------
$r = Api -Method POST -Path "/api/orders/$oE/refund" -Token $admin.access
$refundNo = $r.data.refundNo
Start-Sleep -Seconds 3
$stG = OrderStatus $oE
$rst = (Sql "SELECT status FROM refund_record WHERE refund_no='$refundNo';" | Select-Object -First 1)
$remainAfter = RemainDb $inst "SECOND_CLASS" 1
$recCnt = (Sql "SELECT COUNT(*) FROM stock_restore_record WHERE order_no='$oE';" | Select-Object -First 1)
Check "G1" ($stG -eq "已退款" -and $rst -eq "退款成功" -and $remainAfter -eq $remainBefore -and $recCnt -eq 1) "订单=$stG 流水=$rst remain $remainBefore->$remainAfter 幂等记录=$recCnt"

$rAgain = Api -Method POST -Path "/api/orders/$oE/refund" -Token $admin.access
$oG2 = NewOrder 1003
$rUnpaid = Api -Method POST -Path "/api/orders/$oG2/refund" -Token $admin.access
$rOther = Api -Method POST -Path "/api/orders/$oE/refund" -Token $load1.access
Check "G2" ($rAgain.code -eq 400 -and $rUnpaid.code -eq 400 -and $rOther.code -eq 400) "重复=$($rAgain.code) 未支付=$($rUnpaid.code) 他人=$($rOther.code)"

$r = Api -Path "/api/orders/$oE/refund" -Token $load1.access
Check "G8" ($r.code -ne 200) "越权查退款 code=$($r.code)（期望拒绝）"

# G6/G4/G5 退款回调探针
$chRefNo = (Sql "SELECT channel_refund_no FROM refund_record WHERE refund_no='$refundNo';" | Select-Object -First 1)
$refAmt = [decimal](Sql "SELECT refund_amount FROM refund_record WHERE refund_no='$refundNo';" | Select-Object -First 1)
$rcbBefore = [int](Sql "SELECT callback_count FROM refund_record WHERE refund_no='$refundNo';" | Select-Object -First 1)
$rbody = '{"refundNo":"' + $refundNo + '","orderNo":"' + $oE + '","channelRefundNo":"' + $chRefNo + '","amount":' + $refAmt + '}'
$r6 = Api -Method POST -Path "/api/pay/refund/notify" -RawBody $rbody -Headers @{ "X-Mock-Signature" = (HmacSign $rbody) }
$rcbAfter = [int](Sql "SELECT callback_count FROM refund_record WHERE refund_no='$refundNo';" | Select-Object -First 1)
Check "G6" ($r6.raw -match "SUCCESS" -and $rcbAfter -eq ($rcbBefore + 1)) "退款重放SUCCESS 次数 $rcbBefore->$rcbAfter"
$r4 = Api -Method POST -Path "/api/pay/refund/notify" -RawBody $rbody -Headers @{ "X-Mock-Signature" = "bad" }
Check "G4" ($r4.raw -match "FAILURE") "退款错签名 $($r4.raw)"
$badBody = '{"refundNo":"' + $refundNo + '","orderNo":"' + $oE + '","channelRefundNo":"' + $chRefNo + '","amount":' + ($refAmt + 1) + '}'
$r5 = Api -Method POST -Path "/api/pay/refund/notify" -RawBody $badBody -Headers @{ "X-Mock-Signature" = (HmacSign $badBody) }
Check "G5" ($r5.raw -match "FAILURE") "退款金额不符 $($r5.raw)"

# G3 并发双退票
$oG3 = NewOrder 1002
PayOrder $oG3 | Out-Null
$responses = 1..2 | ForEach-Object -Parallel {
    $a = $using:admin
    $on = $using:oG3
    $rr = Invoke-WebRequest -Uri "http://localhost:8083/api/orders/$on/refund" -Method Post -Headers @{ Authorization = "Bearer $($a.access)" } -SkipHttpErrorCheck -TimeoutSec 30
    return $rr.Content
}
$refCnt = (Sql "SELECT COUNT(*) FROM refund_record WHERE order_no='$oG3';" | Select-Object -First 1)
$okCnt = ($responses | Where-Object { $_ -match '"code":200' }).Count
Check "G3" ($refCnt -eq 1 -and $okCnt -eq 1) "并发退票 流水数=$refCnt 成功数=$okCnt"
Start-Sleep -Seconds 2

# G9 退款后再买
$oG9 = NewOrder 1001
Check "G9" ($null -ne $oG9) "退款后占位释放可再买 orderNo=$oG9"
Api -Method POST -Path "/api/orders/$oG9/cancel" -Token $admin.access | Out-Null

# F6 容量闸运行时验证
$oF6 = NewOrder 1004
Sql "UPDATE stock SET remain=50 WHERE instance_no='$inst' AND seat_no='SECOND_CLASS' AND segment_no=1;" | Out-Null
$rF6 = Api -Method POST -Path "/api/orders/$oF6/cancel" -Token $admin.access
$remainF6 = RemainDb $inst "SECOND_CLASS" 1
$stF6 = OrderStatus $oF6
$recF6 = (Sql "SELECT COUNT(*) FROM stock_restore_record WHERE order_no='$oF6';" | Select-Object -First 1)
Check "F6" ($remainF6 -eq 50 -and $stF6 -eq "已取消" -and $recF6 -eq 0) "顶格回补被拦 remain=$remainF6 订单=$stF6 幂等记录=$recF6 响应code=$($rF6.code) msg=$($rF6.message)"

# 清理：取消 O_G2，库存核对
Api -Method POST -Path "/api/orders/$oG2/cancel" -Token $admin.access | Out-Null
Start-Sleep -Seconds 1
$active = (Sql "SELECT COUNT(*) FROM ${bt}order${bt} WHERE instance_no='$inst' AND seat_no='SECOND_CLASS' AND from_seq<=1 AND to_seq>1 AND status IN ('已确认','已支付');" | Select-Object -First 1)
$remainEnd = RemainDb $inst "SECOND_CLASS" 1
Check "G10pre" ([int]$remainEnd -eq (50 - [int]$active)) "part2收尾一致性 remain=$remainEnd 有效订单=$active"

# 留档：供 part4 故障注入使用的订单号
@{ oE = $oE; refundNo = $refundNo; oG3 = $oG3 } | ConvertTo-Json | Set-Content "D:\IDEAproject\smart-city-traffic\tools\fulltest\part2-ids.json" -Encoding utf8
Write-Host "PART2 DONE"