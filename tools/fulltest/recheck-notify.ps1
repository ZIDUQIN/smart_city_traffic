# 复核 E6/E7/G4/G5：回调验签/金额防护（修正解析后重测）
. "D:\IDEAproject\smart-city-traffic\tools\fulltest\common.ps1"
Add-Content $script:ResultsFile "===== RECHECK-NOTIFY $(Get-Date -Format 'HH:mm:ss') =====" -Encoding utf8
$admin = Login "admin"
$ids = Get-Content "D:\IDEAproject\smart-city-traffic\tools\fulltest\part2-ids.json" -Raw | ConvertFrom-Json
$oE = $ids.oE; $refundNoE = $ids.refundNo
$bt = [char]96
$p1 = (Sql "SELECT payment_no FROM payment_record WHERE order_no='$oE' AND status='支付成功';" | Select-Object -First 1)
$amt = [decimal](Sql "SELECT amount FROM ${bt}order${bt} WHERE order_no='$oE';" | Select-Object -First 1)
$tradeNo = (Sql "SELECT channel_trade_no FROM payment_record WHERE payment_no='$p1';" | Select-Object -First 1)

$body = '{"orderNo":"' + $oE + '","paymentNo":"' + $p1 + '","channelTradeNo":"' + $tradeNo + '","amount":' + $amt + '}'
$r6 = Api -Method POST -Path "/api/pay/notify" -RawBody $body -Headers @{ "X-Mock-Signature" = "deadbeef" }
Check "E6" ($r6.raw -eq '{"code":"FAILURE"}') "支付错签名 raw=$($r6.raw)"

$tamper = '{"orderNo":"' + $oE + '","paymentNo":"' + $p1 + '","channelTradeNo":"' + $tradeNo + '","amount":' + ($amt + 1) + '}'
$r7 = Api -Method POST -Path "/api/pay/notify" -RawBody $tamper -Headers @{ "X-Mock-Signature" = (HmacSign $tamper) }
Check "E7" ($r7.raw -eq '{"code":"FAILURE"}') "支付篡改金额 raw=$($r7.raw)"

$chE = (Sql "SELECT channel_refund_no FROM refund_record WHERE refund_no='$refundNoE';" | Select-Object -First 1)
$refAmt = [decimal](Sql "SELECT refund_amount FROM refund_record WHERE refund_no='$refundNoE';" | Select-Object -First 1)
$rbody = '{"refundNo":"' + $refundNoE + '","orderNo":"' + $oE + '","channelRefundNo":"' + $chE + '","amount":' + $refAmt + '}'
$r4 = Api -Method POST -Path "/api/pay/refund/notify" -RawBody $rbody -Headers @{ "X-Mock-Signature" = "bad" }
Check "G4" ($r4.raw -eq '{"code":"FAILURE"}') "退款错签名 raw=$($r4.raw)"
$badBody = '{"refundNo":"' + $refundNoE + '","orderNo":"' + $oE + '","channelRefundNo":"' + $chE + '","amount":' + ($refAmt + 1) + '}'
$r5 = Api -Method POST -Path "/api/pay/refund/notify" -RawBody $badBody -Headers @{ "X-Mock-Signature" = (HmacSign $badBody) }
Check "G5" ($r5.raw -eq '{"code":"FAILURE"}') "退款金额不符 raw=$($r5.raw)"
Write-Host "RECHECK DONE"