# Part1：A 认证 / B 乘车人 / C 查询 / D 下单边界 / F1-F3 取消
. "D:\IDEAproject\smart-city-traffic\tools\fulltest\common.ps1"
$today = (Get-Date).ToString("yyyy-MM-dd")
$instToday = "D2636-20261118"
Add-Content $script:ResultsFile "===== PART1 $(Get-Date -Format 'HH:mm:ss') =====" -Encoding utf8

# ---------- A 认证 ----------
$admin = Login "admin"
Check "A1" ($null -ne $admin.access -and $null -ne $admin.refresh) "登录返回双令牌"

$r = Api -Method POST -Path "/api/auth/login" -Body @{ username = "admin"; password = "wrong" }
Check "A2a" ($r.code -eq 401) "错误密码 code=$($r.code)"
$r = Api -Method POST -Path "/api/auth/login" -Body @{ username = ""; password = "x" }
Check "A2b" ($r.code -eq 400) "空用户名 code=$($r.code)"

$ftName = "ftuser" + (Get-Random -Maximum 99999)
$r = Api -Method POST -Path "/api/auth/register" -Body @{ username = $ftName; password = "123456"; nickname = "测试" }
$regOk = ($r.code -eq 200)
$ft = Login $ftName
$r2 = Api -Method POST -Path "/api/auth/register" -Body @{ username = $ftName; password = "123456" }
Check "A3" ($regOk -and $ft.access -and $r2.code -eq 400) "注册+登录 ok=$regOk 重复注册 code=$($r2.code)"

$r = Api -Path "/api/orders/my"
Check "A4" ($r.http -eq 401) "无token http=$($r.http)"
$r = Api -Path "/api/orders/my" -Token "faketoken123"
Check "A5" ($r.http -eq 401) "伪造token http=$($r.http)"

$r = Api -Method POST -Path "/api/auth/refresh" -Body @{ refreshToken = $ft.refresh }
$newAccess = $r.data.accessToken; $newRefresh = $r.data.refreshToken
$rOld = Api -Method POST -Path "/api/auth/refresh" -Body @{ refreshToken = $ft.refresh }
$rMe = Api -Path "/api/auth/me" -Token $newAccess
Check "A6" ($r.code -eq 200 -and $rOld.code -eq 400 -and $rMe.code -eq 200) "旋转后旧refresh code=$($rOld.code) 新access可用=$($rMe.code -eq 200)"

$r = Api -Method POST -Path "/api/auth/logout" -Token $newAccess -Body @{ refreshToken = $newRefresh }
$rMe = Api -Path "/api/auth/me" -Token $newAccess
$rRe = Api -Method POST -Path "/api/auth/refresh" -Body @{ refreshToken = $newRefresh }
Check "A7" ($rMe.http -eq 401 -and $rRe.code -eq 400) "logout后 access=$($rMe.http) refresh=$($rRe.code)"

$r = Api -Path "/api/auth/me" -Token $admin.access
Check "A8" ($r.code -eq 200 -and $null -eq $r.data.password) "me不含密码 password=$($r.data.password)"

$longName = "x" * 200
$r = Api -Method POST -Path "/api/auth/register" -Body @{ username = $longName; password = "123456" }
Check "A9" ($r.code -eq 400) "200字符用户名 code=$($r.code) msg=$($r.message)"

$r = Api -Method POST -Path "/api/auth/login" -Body @{ username = "admin' OR '1'='1"; password = "x" }
Check "A10" ($r.code -eq 401) "注入登录 code=$($r.code)"

# ---------- B 乘车人 ----------
$idA = "610302199001011234"; $idB = "610302199001015678"
$r = Api -Method POST -Path "/api/passengers" -Token $admin.access -Body @{ passengerName = "测试甲"; idCard = $idA; passengerType = 1 }
$pidA = $r.data.id
Check "B1" ($r.code -eq 200 -and $r.data.idCard -match "\*") "新增+脱敏 id=$pidA card=$($r.data.idCard)"

$bad = @("61030219900101123", "010302199001011234", "61030219900101123A")
$all400 = $true
foreach ($c in $bad) {
    $r = Api -Method POST -Path "/api/passengers" -Token $admin.access -Body @{ passengerName = "坏"; idCard = $c; passengerType = 1 }
    if ($r.code -ne 400) { $all400 = $false }
}
Check "B2" $all400 "三种非法身份证均400=$all400"

$r0 = Api -Method POST -Path "/api/passengers" -Token $admin.access -Body @{ passengerName = "坏"; idCard = $idB; passengerType = 0 }
$r4 = Api -Method POST -Path "/api/passengers" -Token $admin.access -Body @{ passengerName = "坏"; idCard = $idB; passengerType = 4 }
Check "B3" ($r0.code -eq 400 -and $r4.code -eq 400) "type0=$($r0.code) type4=$($r4.code)"

$rDup = Api -Method POST -Path "/api/passengers" -Token $admin.access -Body @{ passengerName = "重复"; idCard = $idA; passengerType = 1 }
$load1 = Login "load001"
$rOther = Api -Method POST -Path "/api/passengers" -Token $load1.access -Body @{ passengerName = "他用户同证"; idCard = $idA; passengerType = 1 }
$pidOther = $rOther.data.id
Check "B4" ($rDup.code -eq 400 -and $rOther.code -eq 200) "同用户重复=$($rDup.code) 跨用户同证=$($rOther.code)"

$rU = Api -Method PUT -Path "/api/passengers/$pidA" -Token $load1.access -Body @{ passengerName = "劫持"; idCard = $idA; passengerType = 1 }
$rD = Api -Method DELETE -Path "/api/passengers/$pidA" -Token $load1.access
Check "B5" ($rU.code -eq 400 -and $rD.code -eq 400) "改他人=$($rU.code) 删他人=$($rD.code)"

$r = Api -Method POST -Path "/api/passengers" -Token $admin.access -Body @{ passengerName = "测试乙"; idCard = $idB; passengerType = 2 }
$pidB = $r.data.id
$rClash = Api -Method PUT -Path "/api/passengers/$pidB" -Token $admin.access -Body @{ passengerName = "测试乙"; idCard = $idA; passengerType = 2 }
Check "B6" ($rClash.code -eq 400) "改证撞车 code=$($rClash.code)"

$r = Api -Path "/api/passengers" -Token $admin.access
$leak = $r.data | Where-Object { $_.id -eq $pidOther }
Check "B7" ($r.code -eq 200 -and $null -eq $leak) "列表不含他人乘车人"

$rD1 = Api -Method DELETE -Path "/api/passengers/$pidB" -Token $admin.access
$rD2 = Api -Method DELETE -Path "/api/passengers/$pidB" -Token $admin.access
$rD3 = Api -Method DELETE -Path "/api/passengers/999999999" -Token $admin.access
Check "B8" ($rD1.code -eq 200 -and $rD2.code -eq 400 -and $rD3.code -eq 400) "删=$($rD1.code) 再删=$($rD2.code) 不存在=$($rD3.code)"
# 清理 B 阶段临时乘车人
Api -Method DELETE -Path "/api/passengers/$pidA" -Token $admin.access | Out-Null
Api -Method DELETE -Path "/api/passengers/$pidOther" -Token $load1.access | Out-Null

# ---------- C 查询 ----------
$r = Api -Path "/api/ticket/stations" -Token $admin.access
$names = $r.data | ForEach-Object { $_.stationName }
Check "C1" (($names -contains "西安北") -and ($names -contains "咸阳秦都") -and ($names -contains "宝鸡南")) "站数=$($names.Count)"

$r = Api -Path "/api/ticket/search?fromStation=%E5%AE%9D%E9%B8%A1%E5%8D%97&toStation=%E5%92%B8%E9%98%B3%E7%A7%A6%E9%83%BD&travelDate=$today" -Token $admin.access
$d2636 = $r.data | Where-Object { $_.trainNo -eq "D2636" }
Check "C2" ($null -ne $d2636 -and $d2636.seats[0].price -gt 0 -and $d2636.fromSeq -lt $d2636.toSeq) "D2636在列 价=$($d2636.seats[0].price) seq=$($d2636.fromSeq)->$($d2636.toSeq)"

$rSame = Api -Path "/api/ticket/search?fromStation=%E5%92%B8%E9%98%B3%E7%A7%A6%E9%83%BD&toStation=%E5%92%B8%E9%98%B3%E7%A7%A6%E9%83%BD" -Token $admin.access
$rRev = Api -Path "/api/ticket/search?fromStation=%E5%92%B8%E9%98%B3%E7%A7%A6%E9%83%BD&toStation=%E5%AE%9D%E9%B8%A1%E5%8D%97&travelDate=$today" -Token $admin.access
$revHas = $rRev.data | Where-Object { $_.trainNo -eq "D2636" }
Check "C3" ($rSame.code -eq 400 -and $null -eq $revHas) "同站=$($rSame.code) 反向含D2636=$($null -ne $revHas)"

$r = Api -Path "/api/ticket/search?fromStation=%E4%B8%8D%E5%AD%98%E5%9C%A8%E7%AB%99XYZ&toStation=%E5%8C%97%E4%BA%AC%E8%A5%BF" -Token $admin.access
Check "C4" ($r.code -eq 200 -and $r.data.Count -eq 0) "不存在站 空列表 count=$($r.data.Count)"

$dbRemain = RemainDb $instToday "SECOND_CLASS" 1
$r = Api -Path "/api/ticket/remain?trainNo=D2636&travelDate=$today&fromSeq=1&toSeq=2&seatNo=SECOND_CLASS" -Token $admin.access
Check "C5" ($r.code -eq 200 -and $r.data.remain -eq $dbRemain) "remain=$($r.data.remain) db=$dbRemain source=$($r.data.source)"

$r1 = Api -Path "/api/ticket/remain?trainNo=D2636&fromSeq=0&toSeq=2&seatNo=SECOND_CLASS" -Token $admin.access
$r2 = Api -Path "/api/ticket/remain?trainNo=D2636&fromSeq=1&toSeq=1&seatNo=SECOND_CLASS" -Token $admin.access
$r3 = Api -Path "/api/ticket/remain?trainNo=D2636&fromSeq=1&toSeq=2&seatNo=ECONOMY" -Token $admin.access
Check "C6" ($r1.code -eq 400 -and $r2.code -eq 400 -and $r3.code -eq 400) "seq0=$($r1.code) 相等=$($r2.code) 席别=$($r3.code)"

$r = Api -Path "/api/ticket/remain?trainNo=D2636&travelDate=$today&fromSeq=1&toSeq=99&seatNo=SECOND_CLASS" -Token $admin.access
Check "C7" ($r.code -eq 400) "toSeq99 code=$($r.code) msg=$($r.message)"

$r1 = Api -Path "/api/ticket/remain?trainNo=NOEXIST999&travelDate=$today&fromSeq=1&toSeq=2&seatNo=SECOND_CLASS" -Token $admin.access
$nullKey = RedisCmd "EXISTS 'null:train:NOEXIST999-20261118'"
$r2 = Api -Path "/api/ticket/remain?trainNo=NOEXIST999&travelDate=$today&fromSeq=1&toSeq=2&seatNo=SECOND_CLASS" -Token $admin.access
Check "C8" ($r1.code -eq 400 -and $r2.code -eq 400 -and "$nullKey" -match "1") "两次400 空值标记=$nullKey"

# C10 窗口外日期
$farDate = (Get-Date).AddDays(10).ToString("yyyy-MM-dd")
$r = Api -Path "/api/ticket/search?fromStation=%E5%AE%9D%E9%B8%A1%E5%8D%97&toStation=%E5%92%B8%E9%98%B3%E7%A7%A6%E9%83%BD&travelDate=$farDate" -Token $admin.access
$allZero = $true
foreach ($t in $r.data) { foreach ($s in $t.seats) { if ($s.remain -ne 0) { $allZero = $false } } }
Check "C10" ($r.code -eq 200 -and $allZero) "窗口外车次在但余票全0=$allZero（观察项）"

# ---------- D 下单 + F1/F2/F3 + C9 ----------
$before = RemainDb $instToday "SECOND_CLASS" 1
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $today "SECOND_CLASS" 1 2)
$oD1 = $r.data.orderNo
$after = RemainDb $instToday "SECOND_CLASS" 1
$recStatus = (Sql "SELECT status FROM ticket_deduct_record WHERE order_no='$oD1';" | Select-Object -First 1)
Check "D1" ($r.data.status -eq "已确认" -and $after -eq ($before - 1) -and $recStatus -eq "已建单") "status=$($r.data.status) remain $before->$after 流水=$recStatus"
Start-Sleep -Milliseconds 1300

$r = Api -Path "/api/ticket/remain?trainNo=D2636&travelDate=$today&fromSeq=1&toSeq=2&seatNo=SECOND_CLASS" -Token $admin.access
Check "C9" ($r.data.remain -eq $after) "下单后查询=$($r.data.remain) 期望=$after"

$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $today "SECOND_CLASS" 1 2)
Check "D6" ($r.data.status -eq "已取消" -and $r.data.message -match "已购买") "判重 msg=$($r.data.message)"

$r = Api -Path "/api/orders/$oD1" -Token $admin.access
$rN = Api -Path "/api/orders/999999999999" -Token $admin.access
Check "D10" ($r.data.status -eq "已确认" -and $rN.code -eq 400) "查单=$($r.data.status) 不存在=$($rN.code)"

$r = Api -Path "/api/orders/$oD1" -Token $load1.access
Check "D11" ($r.code -ne 200) "越权查单 code=$($r.code) http=$($r.http)（期望拒绝）"

$rMy = Api -Path "/api/orders/my" -Token $admin.access
$mine = $rMy.data | Where-Object { $_.orderNo -eq $oD1 }
$rMyL = Api -Path "/api/orders/my" -Token $load1.access
$leak = $rMyL.data | Where-Object { $_.orderNo -eq $oD1 }
Check "D12" ($null -ne $mine -and $mine.fromStation -and $null -eq $leak) "my含本人单带站名=$($mine.fromStation) 他人不可见=$($null -eq $leak)"

# F3 越权/状态取消探针（在 F1 之前，订单仍已确认）
$rX = Api -Method POST -Path "/api/orders/$oD1/cancel" -Token $load1.access
$rPaid = Api -Method POST -Path "/api/orders/381556283582455808/cancel" -Token $admin.access
$rNone = Api -Method POST -Path "/api/orders/999999999999/cancel" -Token $admin.access
Check "F3" ($rX.code -eq 400 -and $rPaid.code -eq 400 -and $rNone.code -eq 400) "他人=$($rX.code) 已支付=$($rPaid.code) 不存在=$($rNone.code)"

# F1 取消
$beforeCancel = RemainDb $instToday "SECOND_CLASS" 1
$r = Api -Method POST -Path "/api/orders/$oD1/cancel" -Token $admin.access
$afterCancel = RemainDb $instToday "SECOND_CLASS" 1
$recCount = (Sql "SELECT COUNT(*) FROM stock_restore_record WHERE order_no='$oD1';" | Select-Object -First 1)
Check "F1" ($r.code -eq 200 -and (OrderStatus $oD1) -eq "已取消" -and $afterCancel -eq ($beforeCancel + 1) -and $recCount -eq 1) "remain $beforeCancel->$afterCancel 幂等记录=$recCount"

# F2 重复取消
$r = Api -Method POST -Path "/api/orders/$oD1/cancel" -Token $admin.access
$after2 = RemainDb $instToday "SECOND_CLASS" 1
$recCount2 = (Sql "SELECT COUNT(*) FROM stock_restore_record WHERE order_no='$oD1';" | Select-Object -First 1)
Check "F2" ($r.code -eq 200 -and $after2 -eq $afterCancel -and $recCount2 -eq 1) "幂等 remain=$after2 记录=$recCount2"
Start-Sleep -Milliseconds 1300

# D7 取消后再买
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $today "SECOND_CLASS" 1 2)
$oD7 = $r.data.orderNo
Check "D7" ($r.data.status -eq "已确认") "占位释放后再买 status=$($r.data.status)"
Api -Method POST -Path "/api/orders/$oD7/cancel" -Token $admin.access | Out-Null

# D2 参数边界
$cases = @(
    @{ b = @{ trainNo = "D2636"; seatNo = "SECOND_CLASS"; fromSeq = 1; toSeq = 2 }; n = "缺乘车人" },
    @{ b = (OrderBody 1001 "D2636" $today "ECONOMY" 1 2); n = "非法席别" },
    @{ b = (OrderBody 1001 "D2636" $today "SECOND_CLASS" 0 2); n = "fromSeq0" },
    @{ b = (OrderBody 1001 "D2636" $today "SECOND_CLASS" 2 2); n = "toSeq相等" }
)
$all400 = $true; $detail = ""
foreach ($c in $cases) {
    $r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body $c.b
    $detail += "$($c.n)=$($r.code) "
    if ($r.code -ne 400) { $all400 = $false }
}
Check "D2" $all400 $detail

$r = Api -Method POST -Path "/api/orders" -Token $load1.access -Body (OrderBody 1001 "D2636" $today "SECOND_CLASS" 1 2)
Check "D3" ($r.code -eq 400) "他人乘车人 code=$($r.code) msg=$($r.message)"

$stockBefore = RemainDb $instToday "SECOND_CLASS" 1
Start-Sleep -Milliseconds 1200
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "NOEXIST999" $today "SECOND_CLASS" 1 2)
Check "D4" ($r.data.status -eq "已取消") "不存在车次 status=$($r.data.status) msg=$($r.data.message)（文案观察）"

Start-Sleep -Milliseconds 1200
$r = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $today "SECOND_CLASS" 1 99)
$stockAfter = RemainDb $instToday "SECOND_CLASS" 1
Check "D5" ($r.data.status -eq "已取消" -and $stockAfter -eq $stockBefore) "toSeq99 status=$($r.data.status) 库存不动=$($stockAfter -eq $stockBefore)"

$far = (Get-Date).AddDays(10).ToString("yyyy-MM-dd")
$yest = (Get-Date).AddDays(-1).ToString("yyyy-MM-dd")
$rF = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $far "SECOND_CLASS" 1 2)
Start-Sleep -Milliseconds 1200
$rY = Api -Method POST -Path "/api/orders" -Token $admin.access -Body (OrderBody 1001 "D2636" $yest "SECOND_CLASS" 1 2)
Check "D13" ($rF.data.status -eq "已取消" -and $rY.data.status -eq "已取消") "未来=$($rF.data.status) 昨天=$($rY.data.status)"

Write-Host "PART1 DONE"