# Part4：F4 超时关单 / F5 已支付不误关 / G7 退款对账矩阵 / G10 对账补库存撞幂等
# 手段：故障注入（改状态/回拨时间/注入渠道结果），等真实 cron（每分钟整点）处理
. "D:\IDEAproject\smart-city-traffic\tools\fulltest\common.ps1"
$date = "2026-11-20"
$inst = "D2636-20261120"
$bt = [char]96
Add-Content $script:ResultsFile "===== PART4 $(Get-Date -Format 'HH:mm:ss') =====" -Encoding utf8
$admin = Login "admin"
$ids = Get-Content "D:\IDEAproject\smart-city-traffic\tools\fulltest\part2-ids.json" -Raw | ConvertFrom-Json
$oE = $ids.oE; $refundNoE = $ids.refundNo; $oG3 = $ids.oG3

function WaitNextCron {
    $now = Get-Date
    $next = $now.Date.AddHours($now.Hour).AddMinutes($now.Minute + 1)
    $wait = [int](($next - $now).TotalSeconds) + 12
    Write-Host "等待下一轮cron ${wait}s ..."
    Start-Sleep -Seconds $wait
}
function NewOrder([int]$pid2) {
    Start-Sleep -Milliseconds 1300
    $r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody $pid2 "D2636" $date "SECOND_CLASS" 1 2)
    if ($r.data.status -ne "已确认") { throw "下单失败 pid=$pid2 : $($r.raw)" }
    return $r.data.orderNo
}
function PayOrder([string]$on) {
    $r = Api -Method POST -Path "/api/pay/prepay?orderNo=$on" -Token $admin.access
    Api -Method POST -Path "/api/pay/mock/confirm?payToken=$($r.data.payToken)" -Token $admin.access | Out-Null
    Start-Sleep -Seconds 2
}
function Inject([string]$chNo, [string]$result) {
    Api -Method POST -Path "/api/pay/mock/refund-result?channelRefundNo=$chNo&result=$result" -Token $admin.access | Out-Null
}

# ---------- 注入布置 ----------
# F4：已确认未支付，回拨16分钟
$oF4 = NewOrder 1003
Api -Method POST -Path "/api/pay/prepay?orderNo=$oF4" -Token $admin.access | Out-Null
Sql "UPDATE ${bt}order${bt} SET create_time = DATE_SUB(NOW(), INTERVAL 16 MINUTE) WHERE order_no='$oF4';" | Out-Null

# F5：已支付，回拨16分钟（应被跳过）
$oF5 = NewOrder 1001
PayOrder $oF5
Sql "UPDATE ${bt}order${bt} SET create_time = DATE_SUB(NOW(), INTERVAL 16 MINUTE) WHERE order_no='$oF5';" | Out-Null

# G7a：O_E 退款单回到退款中，渠道结果 PROCESSING
$chE = (Sql "SELECT channel_refund_no FROM refund_record WHERE refund_no='$refundNoE';" | Select-Object -First 1)
Inject $chE "PROCESSING"
Sql "UPDATE refund_record SET status='退款中', retry_count=0, stock_restored=1, update_time=DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE refund_no='$refundNoE';" | Out-Null
Sql "UPDATE ${bt}order${bt} SET status='退款中' WHERE order_no='$oE';" | Out-Null

# G7b：O_G3 退款单回到退款中且渠道单号清空，渠道结果 NOT_EXIST（应重发）
$refundNoG3 = (Sql "SELECT refund_no FROM refund_record WHERE order_no='$oG3';" | Select-Object -First 1)
$chG3 = (Sql "SELECT channel_refund_no FROM refund_record WHERE refund_no='$refundNoG3';" | Select-Object -First 1)
Inject $chG3 "NOT_EXIST"
Sql "UPDATE refund_record SET status='退款中', channel_refund_no=NULL, retry_count=0, stock_restored=1, update_time=DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE refund_no='$refundNoG3';" | Out-Null
Sql "UPDATE ${bt}order${bt} SET status='退款中' WHERE order_no='$oG3';" | Out-Null

# G7c：新单退款后注入 FAILED（应置失败+告警，不重发）
$oFail = NewOrder 1004
PayOrder $oFail
$rR = Api -Method POST -Path "/api/orders/$oFail/refund" -Token $admin.access
$refundNoFail = $rR.data.refundNo
Start-Sleep -Seconds 2
$chFail = (Sql "SELECT channel_refund_no FROM refund_record WHERE refund_no='$refundNoFail';" | Select-Object -First 1)
Inject $chFail "FAILED"
Sql "UPDATE refund_record SET status='退款中', retry_count=0, stock_restored=1, update_time=DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE refund_no='$refundNoFail';" | Out-Null
Sql "UPDATE ${bt}order${bt} SET status='退款中' WHERE order_no='$oFail';" | Out-Null

Write-Host "注入完成：oF4=$oF4 oF5=$oF5 oE=$oE oG3=$oG3 oFail=$oFail"
$remainBeforeF4 = RemainDb $inst "SECOND_CLASS" 1

# ---------- 第一轮 cron ----------
WaitNextCron

$stF4 = OrderStatus $oF4
$remainAfterF4 = RemainDb $inst "SECOND_CLASS" 1
$recF4 = (Sql "SELECT COUNT(*) FROM stock_restore_record WHERE order_no='$oF4';" | Select-Object -First 1)
$waitClosed = (Sql "SELECT COUNT(*) FROM payment_record WHERE order_no='$oF4' AND status='待支付';" | Select-Object -First 1)
Check "F4" ($stF4 -eq "已取消" -and $remainAfterF4 -eq ($remainBeforeF4 + 1) -and [int]$recF4 -eq 1 -and [int]$waitClosed -eq 0) "超时关单=$stF4 remain $remainBeforeF4->$remainAfterF4 幂等记录=$recF4 待支付残留=$waitClosed"

$stF5 = OrderStatus $oF5
Check "F5" ($stF5 -eq "已支付") "已支付超时单未被误关=$stF5"

$stE = (Sql "SELECT status FROM refund_record WHERE refund_no='$refundNoE';" | Select-Object -First 1)
$retryE = [int](Sql "SELECT retry_count FROM refund_record WHERE refund_no='$refundNoE';" | Select-Object -First 1)
$ageE = [int](Sql "SELECT TIMESTAMPDIFF(SECOND, update_time, NOW()) FROM refund_record WHERE refund_no='$refundNoE';" | Select-Object -First 1)
Check "G7a" ($stE -eq "退款中" -and $retryE -eq 0 -and $ageE -lt 90) "PROCESSING等待 状态=$stE 重试=$retryE 时间已刷新=${ageE}s前"

$chG3new = (Sql "SELECT channel_refund_no FROM refund_record WHERE refund_no='$refundNoG3';" | Select-Object -First 1)
$retryG3 = [int](Sql "SELECT retry_count FROM refund_record WHERE refund_no='$refundNoG3';" | Select-Object -First 1)
$stG3 = (Sql "SELECT status FROM refund_record WHERE refund_no='$refundNoG3';" | Select-Object -First 1)
Check "G7b" ($chG3new -and $retryG3 -ge 1 -and $stG3 -eq "退款中") "NOT_EXIST重发 渠道单号=$chG3new 重试=$retryG3 状态=$stG3"

$stFail = (Sql "SELECT status FROM refund_record WHERE refund_no='$refundNoFail';" | Select-Object -First 1)
$stFailOrder = OrderStatus $oFail
Check "G7c" ($stFail -eq "退款失败" -and $stFailOrder -eq "退款中") "FAILED置失败 流水=$stFail 订单=$stFailOrder（转人工）"

# ---------- 第二轮布置 ----------
# G7d + G10：O_E 渠道已成功（回调丢失）且 stock_restored=0（对账补库存应撞幂等表不重复+1）
Inject $chE "SUCCESS"
$remainBeforeR2 = RemainDb $inst "SECOND_CLASS" 1
Sql "UPDATE refund_record SET stock_restored=0, update_time=DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE refund_no='$refundNoE';" | Out-Null
# O_G3：重发已落库；把渠道结果修回 SUCCESS（模拟渠道其实早已受理），本轮应本地修复
Inject $chG3 "SUCCESS"
Sql "UPDATE refund_record SET update_time=DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE refund_no='$refundNoG3';" | Out-Null

WaitNextCron

$stE2 = (Sql "SELECT status FROM refund_record WHERE refund_no='$refundNoE';" | Select-Object -First 1)
$stE2order = OrderStatus $oE
$restoredE = [int](Sql "SELECT stock_restored FROM refund_record WHERE refund_no='$refundNoE';" | Select-Object -First 1)
$remainAfterR2 = RemainDb $inst "SECOND_CLASS" 1
$recE = (Sql "SELECT COUNT(*) FROM stock_restore_record WHERE order_no='$oE';" | Select-Object -First 1)
Check "G7d" ($stE2 -eq "退款成功" -and $stE2order -eq "已退款") "回调丢失本地修复 流水=$stE2 订单=$stE2order"
Check "G10" ($restoredE -eq 1 -and $remainAfterR2 -eq $remainBeforeR2 -and [int]$recE -eq 1) "对账补库存撞幂等 remain $remainBeforeR2->$remainAfterR2 标记=$restoredE 记录数=$recE"

$stG32 = (Sql "SELECT status FROM refund_record WHERE refund_no='$refundNoG3';" | Select-Object -First 1)
$stG32order = OrderStatus $oG3
Check "G7e" ($stG32 -eq "退款成功" -and $stG32order -eq "已退款") "重发后对账修复 流水=$stG32 订单=$stG32order"

# ---------- 清理 ----------
# F5 已支付单走正常退款
Api -Method POST -Path "/api/orders/$oF5/refund" -Token $admin.access | Out-Null
Start-Sleep -Seconds 2
# FAILED 测试单：人工修复口径收尾（测试数据清理，非业务操作）
Sql "UPDATE refund_record SET status='退款成功' WHERE refund_no='$refundNoFail';" | Out-Null
Sql "UPDATE ${bt}order${bt} SET status='已退款' WHERE order_no='$oFail';" | Out-Null

$endActive = [int](Sql "SELECT COUNT(*) FROM ${bt}order${bt} WHERE instance_no='$inst' AND status IN ('已确认','已支付');" | Select-Object -First 1)
$endRemain = RemainDb $inst "SECOND_CLASS" 1
Check "F4end" ($endActive -eq 0 -and $endRemain -eq 50) "part4收尾 有效单=$endActive remain=$endRemain"
Write-Host "PART4 DONE"