# 方言语音购票 · 验收跑批
#
# 覆盖设计方案第 13 章 14 条用例里所有能在接口层验证的部分。
# 第 10、11 条是前端乘车人匹配，第 13 条的下单在浏览器里验证过，脚本里只做接口侧复核。
#
# 用法：
#   pwsh -File tools\voice-acceptance.ps1
#   pwsh -File tools\voice-acceptance.ps1 -Base http://localhost:8081
#
# 会临时把 D2636 今天的库存清零来验证「售罄」分支，跑完自动恢复（-KeepStock 可跳过恢复）。

param(
    [string]$Base = "http://localhost:8083",
    [string]$TravelDate = "",
    [switch]$KeepStock
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$MYSQL = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$DB = "smart-city-traffic"
$env:MYSQL_PWD = "123456"
$INSTANCE = ""
if (-not $TravelDate) {
    $TravelDate = (Get-Date).ToString("yyyy-MM-dd")
}
$yyyymmdd = ($TravelDate -replace "-", "")
$INSTANCE = "D2636-$yyyymmdd"

$script:pass = 0
$script:fail = 0
$script:rows = New-Object System.Collections.ArrayList

function Record($ok, $id, $title, $detail) {
    if ($ok) { $script:pass++ } else { $script:fail++ }
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    [void]$script:rows.Add(("{0}  #{1,-2} {2}  |  {3}" -f $tag, $id, $title, $detail))
}

function Sql($query) {
    & $MYSQL -uroot -N -B --default-character-set=utf8mb4 $DB -e $query 2>&1
}

function SetStock($value) {
    Sql "UPDATE stock SET remain = $value WHERE instance_no = '$INSTANCE' AND segment_no = 1;" | Out-Null
    $keys = @(
        "stock:{$INSTANCE}:SECOND_CLASS",
        "stock:{$INSTANCE}:FIRST_CLASS",
        "stock:{$INSTANCE}:BUSINESS"
    )
    & python "D:\IDEAproject\smart-city-traffic\tools\redis_del.py" @keys | Out-Null
}

# ---- 登录 ----
$login = Invoke-RestMethod -Uri "$Base/api/auth/login" -Method Post -ContentType "application/json" `
    -Body '{"username":"admin","password":"123456"}'
$token = $login.data.accessToken
$headers = @{ Authorization = "Bearer $token" }

function Interpret([string]$text, $extra) {
    $body = @{ text = $text; todayHint = $TravelDate }
    if ($extra) {
        foreach ($k in $extra.Keys) { $body[$k] = $extra[$k] }
    }
    $json = $body | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Uri "$Base/api/voice/interpret" -Method Post -Headers $headers `
        -ContentType "application/json; charset=utf-8" -Body $json
    if ($null -eq $resp -or $null -eq $resp.data) {
        throw "接口返回空：多半是语音接口每分钟 10 次的限流。用 -Dvoice.rate-limit.per-user-per-minute=200 重启服务后再跑。"
    }
    $resp
}

function CandNames($cands) {
    if (-not $cands) { return "" }
    return (($cands | ForEach-Object { $_.stationName }) -join ",")
}

Write-Host "验收目标：$Base    日期：$TravelDate" -ForegroundColor Cyan

# ---------------- #1 ----------------
try {
    $r = Interpret "从宝鸡到咸阳"
    $ok = ($r.data.trains.Count -eq 1) -and ($r.data.trains[0].trainNo -eq "D2636")
    Record $ok 1 "从宝鸡到咸阳：只出这一趟" ("trains=" + $r.data.trains.Count + " no=" + $r.data.trains[0].trainNo + " recReason=" + $r.data.recommendReason)
} catch { Record $false 1 "从宝鸡到咸阳" $_.Exception.Message }

# ---------------- #2 ----------------
try {
    $r = Interpret "从西安到郑州"
    $ok = ($r.data.trains.Count -eq 9) -and (-not $r.data.needStationConfirm)
    Record $ok 2 "从西安到郑州：九趟全出" ("trains=" + $r.data.trains.Count + " needConfirm=" + $r.data.needStationConfirm)
} catch { Record $false 2 "从西安到郑州" $_.Exception.Message }

# ---------------- #3 ----------------
try {
    $r = Interpret "从西安到北京"
    $to = CandNames $r.data.stationCandidates.to
    # 北京两个站都要摆出来；北京南一列车没有、只有北京西能走，
    # 所以"直接定到北京西"和"让用户点"都算对，二者必居其一
    $listed = ($to -match "北京南") -and ($to -match "北京西")
    $actualTo = if ($r.data.trains.Count -gt 0) { $r.data.trains[0].toStation } else { "无车次" }
    $resolved = (-not $r.data.needStationConfirm) -and ($actualTo -eq "北京西")
    $ok = $listed -and ($r.data.needStationConfirm -or $resolved)
    Record $ok 3 "从西安到北京：两站都列出且不误导" ("needConfirm=" + $r.data.needStationConfirm + " to=[" + $to + "] 实际到站=" + $actualTo)
} catch { Record $false 3 "从西安到北京" $_.Exception.Message }

# ---------------- #4 ----------------
try {
    $r = Interpret "咸阳"
    $to = CandNames $r.data.stationCandidates.to
    $ok = $r.data.needStationConfirm -and ($to -match "咸阳秦都") -and ($to -match "咸阳北") -and ($to -match "咸阳西")
    Record $ok 4 "只说咸阳：三个站一个不少" ("to=[" + $to + "]")
} catch { Record $false 4 "只说咸阳" $_.Exception.Message }

# ---------------- #5 ----------------
try {
    $r = Interpret "从宝鸡到上海"
    # 宝鸡到上海这条线库里确实没有车。系统要说清"没有班次"，
    # 实在分不出上海两个站时可以退一步让用户点，但绝不能给出别的线路的车次
    $noWrongTrains = ($r.data.trains.Count -eq 0)
    $ok = $noWrongTrains -and (($r.data.emptyReason -eq "NO_ROUTE") -or ($r.data.needStationConfirm -eq $true))
    Record $ok 5 "没有这条线：如实说没有" ("emptyReason=" + $r.data.emptyReason + " needConfirm=" + $r.data.needStationConfirm + " trains=" + $r.data.trains.Count + " speech=" + $r.data.speech)
} catch { Record $false 5 "从宝鸡到上海" $_.Exception.Message }

# ---------------- #8 ----------------
try {
    $r = Interpret "从宝鸡到咸阳 七点来钟"
    $ok = ($r.data.recommendReason -eq "CLOCK") -and ($r.data.recommendedTrain -and $r.data.recommendedTrain.trainNo -eq "D2636")
    Record $ok 8 "说七点来钟：按时段推荐" ("recReason=" + $r.data.recommendReason + " rec=" + ($r.data.recommendedTrain.trainNo))
} catch { Record $false 8 "七点来钟" $_.Exception.Message }

# ---------------- #9 ----------------
try {
    $r = Interpret "从西安到郑州 下午三点"
    $ok = ($r.data.recommendReason -eq "CLOCK_FAR") -and (-not $r.data.recommendedTrain)
    Record $ok 9 "说下午三点：差太远不预选" ("recReason=" + $r.data.recommendReason + " rec空=" + (-not $r.data.recommendedTrain) + " speech=" + $r.data.speech)
} catch { Record $false 9 "下午三点" $_.Exception.Message }

# ---------------- #12 ----------------
try {
    $r = Interpret "从咸杨到北京"
    $ok = ($r.data.modelUsed -eq $true) -or ($r.data.modelFallback -eq $true)
    Record $ok 12 "同音错字：规则拿不准时找模型" ("modelUsed=" + $r.data.modelUsed + " fallback=" + $r.data.modelFallback + " needConfirm=" + $r.data.needStationConfirm)
} catch { Record $false 12 "咸杨" $_.Exception.Message }

# ---------------- #13（接口侧：下单链路是否走得通） ----------------
# 重复跑脚本时同一位乘车人已经买过这趟车，接口会拒（这是对的防止重复购票）。
# 所以这一条验的是"链路通"：要么出订单号，要么明确告诉"已购买"，两者都说明请求真的走到了业务层。
try {
    $wb = @{
        passengerId = 1003
        trainNo     = "D2636"
        travelDate  = $TravelDate
        seatNo      = "SECOND_CLASS"
        fromSeq     = 1
        toSeq       = 2
    } | ConvertTo-Json -Compress
    $resp = Invoke-WebRequest -Uri "$Base/api/orders" -Method Post -Headers $headers `
        -ContentType "application/json; charset=utf-8" -Body $wb -SkipHttpErrorCheck -TimeoutSec 30
    $obj = $resp.Content | ConvertFrom-Json
    $orderNo = $obj.data.orderNo
    $msg = $obj.data.message
    $ok = ($orderNo -ne $null -and $orderNo -ne "") -or ($msg -match "已购买")
    $detail = if ($orderNo) { "orderNo=" + $orderNo } else { "msg=" + $msg }
    Record $ok 13 "下单接口：请求真的走到业务层" $detail
} catch { Record $false 13 "下单" $_.Exception.Message }

# ---------------- #6 #7：临时清零验证售罄 ----------------
try {
    SetStock 0
    Start-Sleep -Milliseconds 300
    $r = Interpret "从宝鸡南到咸阳秦都"
    $ok6 = ($r.data.emptyReason -eq "SOLD_OUT") -and ($r.data.canAskTomorrow -eq $true)
    Record $ok6 6 "今天卖完：问要不要看明天" ("emptyReason=" + $r.data.emptyReason + " soldOut=" + $r.data.soldOutTrainCount + " canAsk=" + $r.data.canAskTomorrow + " speech=" + $r.data.speech)

    $nextDay = ([datetime]$TravelDate).AddDays(1).ToString("yyyy-MM-dd")
    $r7 = Interpret "从宝鸡南到咸阳秦都" @{ travelDate = $nextDay }
    $ok7 = ($r7.data.emptyReason -ne "SOLD_OUT")
    Record $ok7 7 "看明天：明天是满票" ("date=" + $nextDay + " trains=" + $r7.data.trains.Count + " emptyReason=" + $r7.data.emptyReason)
} catch {
    Record $false 6 "售罄分支" $_.Exception.Message
    Record $false 7 "看明天" $_.Exception.Message
} finally {
    if (-not $KeepStock) {
        # 恢复不能写死容量：验收期间可能真的卖出/退掉票，写死 50 会把已售座位凭空还回去（就是"多补"）。
        # 一律按订单重算，和巡检用的是同一个基准。
        $bt = [char]96
        $restoreSql = "UPDATE stock s JOIN stock_capacity c " +
            "ON c.train_no=s.train_no AND c.seat_no=s.seat_no AND c.segment_no=s.segment_no " +
            "SET s.remain = c.total_capacity - (SELECT COUNT(*) FROM $bt" + "order$bt" + " o " +
            "WHERE o.instance_no=s.instance_no AND o.seat_no=s.seat_no " +
            "AND o.from_seq<=s.segment_no AND o.to_seq>s.segment_no " +
            "AND o.status IN ('已确认','已支付')) " +
            "WHERE s.instance_no='$INSTANCE' AND s.segment_no=1;"
        Sql $restoreSql | Out-Null
        & python "D:\IDEAproject\smart-city-traffic\tools\redis_del.py" `
            "stock:{$INSTANCE}:SECOND_CLASS" "stock:{$INSTANCE}:FIRST_CLASS" "stock:{$INSTANCE}:BUSINESS" | Out-Null
    }
}

$script:rows | ForEach-Object { Write-Host $_ }
Write-Host ""
Write-Host ("通过 {0} 条，失败 {1} 条" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Yellow" })
Write-Host "第 10、11 条是前端乘车人匹配（说名字唯一命中 / 说姓氏多命中列候选），第 14 条要另起一个 voice.llm.enabled=false 的实例验证，这两类不在本脚本内。"
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
