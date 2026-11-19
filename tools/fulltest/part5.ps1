# Part5：J 语音边界 / K 安全杂项
. "D:\IDEAproject\smart-city-traffic\tools\fulltest\common.ps1"
Add-Content $script:ResultsFile "===== PART5 $(Get-Date -Format 'HH:mm:ss') =====" -Encoding utf8
$admin = Login "admin"
$load1 = Login "load001"
$tmp = "D:\IDEAproject\smart-city-traffic\tools\fulltest\wav"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function NewWav([int]$channels, [int]$rate, [int]$bits, [double]$seconds) {
    $dataLen = [int]($rate * $channels * ($bits / 8) * $seconds)
    $ms = New-Object IO.MemoryStream
    $bw = New-Object IO.BinaryWriter($ms)
    $bw.Write([Text.Encoding]::ASCII.GetBytes("RIFF"))
    $bw.Write([uint32](36 + $dataLen))
    $bw.Write([Text.Encoding]::ASCII.GetBytes("WAVE"))
    $bw.Write([Text.Encoding]::ASCII.GetBytes("fmt "))
    $bw.Write([uint32]16)
    $bw.Write([uint16]1)
    $bw.Write([uint16]$channels)
    $bw.Write([uint32]$rate)
    $bw.Write([uint32]($rate * $channels * ($bits / 8)))
    $bw.Write([uint16]($channels * ($bits / 8)))
    $bw.Write([uint16]$bits)
    $bw.Write([Text.Encoding]::ASCII.GetBytes("data"))
    $bw.Write([uint32]$dataLen)
    $bw.Write((New-Object byte[] $dataLen))
    $bw.Flush()
    return $ms.ToArray()
}
function SendWav([string]$file, [string]$token) {
    $r = Invoke-WebRequest -Uri "$script:Base/api/voice/recognize" -Method Post -Headers @{ Authorization = "Bearer $token" } -Form @{ audio = Get-Item $file; dialect = "auto" } -SkipHttpErrorCheck -TimeoutSec 60
    $j = $null; try { $j = $r.Content | ConvertFrom-Json } catch {}
    return @{ http = [int]$r.StatusCode; code = $(if ($j) { $j.code } else { $null }); msg = $(if ($j) { $j.message } else { $r.Content }) }
}

# J1 非WAV
[IO.File]::WriteAllBytes("$tmp\bad.wav", [Text.Encoding]::ASCII.GetBytes("this is definitely not a wav file, just plain text bytes here"))
$r = SendWav "$tmp\bad.wav" $admin.access
Check "J1" ($r.code -eq 400 -and $r.msg -match "WAV") "非WAV code=$($r.code) msg=$($r.msg)"

# J2 格式不对
[IO.File]::WriteAllBytes("$tmp\8k.wav", (NewWav 1 8000 16 2))
$r1 = SendWav "$tmp\8k.wav" $admin.access
[IO.File]::WriteAllBytes("$tmp\stereo.wav", (NewWav 2 16000 16 2))
$r2 = SendWav "$tmp\stereo.wav" $admin.access
Check "J2" ($r1.code -eq 400 -and $r2.code -eq 400 -and $r1.msg -match "格式") "8k=$($r1.code) 双声道=$($r2.code)"

# J3 太短
[IO.File]::WriteAllBytes("$tmp\short.wav", (NewWav 1 16000 16 0.5))
$r = SendWav "$tmp\short.wav" $admin.access
Check "J3" ($r.code -eq 400 -and $r.msg -match "太短") "0.5s code=$($r.code) msg=$($r.msg)"

# J4 太长
[IO.File]::WriteAllBytes("$tmp\long.wav", (NewWav 1 16000 16 31))
$r = SendWav "$tmp\long.wav" $admin.access
Check "J4" ($r.code -eq 400 -and $r.msg -match "太长") "31s code=$($r.code) msg=$($r.msg)"

# J5 超大
[IO.File]::WriteAllBytes("$tmp\big.wav", (NewWav 1 16000 16 66))
$r = SendWav "$tmp\big.wav" $admin.access
Check "J5" ($r.code -eq 400 -and $r.msg -match "太大") "66s/2.1MB code=$($r.code) msg=$($r.msg)"

# J5b 合法静音WAV：格式应被接受（走到ASR，可能"没听清"）
[IO.File]::WriteAllBytes("$tmp\ok.wav", (NewWav 1 16000 16 2))
$r = SendWav "$tmp\ok.wav" $admin.access
Check "J5b" ($r.msg -notmatch "格式|WAV|太短|太长|太大") "合法WAV未被格式拦截 code=$($r.code) msg=$($r.msg)"

# J6 空文本
$r = Api -Method POST -Path "/api/voice/interpret" -Token $admin.access -Body @{ text = "" }
Check "J6" ($r.code -eq 400) "空文本 code=$($r.code)"

# J7 日期窗口
$yest = (Get-Date).AddDays(-1).ToString("yyyy-MM-dd")
$far = (Get-Date).AddDays(7).ToString("yyyy-MM-dd")
$edge = (Get-Date).AddDays(6).ToString("yyyy-MM-dd")
$r1 = Api -Method POST -Path "/api/voice/interpret" -Token $admin.access -Body @{ text = "从宝鸡南到咸阳秦都"; travelDate = $yest }
$r2 = Api -Method POST -Path "/api/voice/interpret" -Token $admin.access -Body @{ text = "从宝鸡南到咸阳秦都"; travelDate = $far }
$r3 = Api -Method POST -Path "/api/voice/interpret" -Token $admin.access -Body @{ text = "从宝鸡南到咸阳秦都"; travelDate = $edge }
Check "J7" ($r1.data.emptyReason -eq "DATE_OUT_OF_WINDOW" -and $r2.data.emptyReason -eq "DATE_OUT_OF_WINDOW" -and $r3.data.emptyReason -ne "DATE_OUT_OF_WINDOW") "昨天=$($r1.data.emptyReason) +7=$($r2.data.emptyReason) +6=$($r3.data.emptyReason)"

# J8 脏日期覆盖
$r = Api -Method POST -Path "/api/voice/interpret" -Token $admin.access -Body @{ text = "从宝鸡南到咸阳秦都"; travelDate = "garbage-date" }
Check "J8" ($r.code -eq 200) "脏日期忽略 code=$($r.code) date=$($r.data.intent.travelDate)"

# J9 同站
$r = Api -Method POST -Path "/api/voice/interpret" -Token $admin.access -Body @{ text = "从西安北到西安北"; fromStation = "西安北"; toStation = "西安北" }
Check "J9" ($r.code -eq 400) "同站 code=$($r.code) msg=$($r.message)"

# J10 未登录
$r = Api -Method POST -Path "/api/voice/interpret" -Body @{ text = "从宝鸡南到咸阳秦都" }
Check "J10" ($r.http -eq 401) "未登录 http=$($r.http)"

# J12 失败不耗额度：3次坏WAV后计数应为0，再1次合法interpret计1
$minuteKey = "voice:rate:{1}:" + (Get-Date).ToString("yyyyMMddHHmm")
RedisCmd "DEL '$minuteKey'" | Out-Null
1..3 | ForEach-Object { SendWav "$tmp\bad.wav" $admin.access | Out-Null }
$cntAfterFail = RedisCmd "GET '$minuteKey'"
Api -Method POST -Path "/api/voice/interpret" -Token $admin.access -Body @{ text = "从宝鸡南到咸阳秦都" } | Out-Null
$minuteKey2 = "voice:rate:{1}:" + (Get-Date).ToString("yyyyMMddHHmm")
$cntAfterOk = RedisCmd "GET '$minuteKey2'"
Check "J12" ("$cntAfterFail" -match "^(0|)$" -and "$cntAfterOk" -match "1") "3次失败后计数=$cntAfterFail 1次成功后=$cntAfterOk"

# ---------- K ----------
$r = Invoke-WebRequest -Uri "$script:Base/actuator/health" -SkipHttpErrorCheck
Check "K1" ($r.StatusCode -eq 200 -and $r.Content -match "UP") "health匿名UP（metrics开放为已知取舍）"

$r = Api -Path "/api/ticket/search?fromStation=%27%20OR%201%3D1%20--&toStation=%E5%8C%97%E4%BA%AC%E8%A5%BF" -Token $admin.access
Check "K2" ($r.code -eq 200 -and $r.data.Count -eq 0) "注入查询 code=$($r.code) count=$($r.data.Count)"

$xss = '<script>alert(1)</script>'
$r = Api -Method POST -Path "/api/passengers" -Token $admin.access -Body @{ passengerName = $xss; idCard = "610302199001019999"; passengerType = 1 }
$xid = $r.data.id
$rL = Api -Path "/api/passengers" -Token $admin.access
$stored = ($rL.data | Where-Object { $_.id -eq $xid }).passengerName
Check "K3" ($r.code -eq 200 -and $stored -eq $xss) "XSS原样存储（前端esc()转义已核对app.js/voice.js）"
Api -Method DELETE -Path "/api/passengers/$xid" -Token $admin.access | Out-Null

$rE = Api -Method POST -Path "/api/instances/ensure?days=7" -Token $load1.access
$rC = Api -Method POST -Path "/api/instances/cleanup?delayDays=30" -Token $load1.access
$rP = Api -Method POST -Path "/api/orders/preheat?trainNo=D2636&travelDate=2026-11-20" -Token $load1.access
$rM = Api -Method POST -Path "/api/pay/mock/refund-result?channelRefundNo=PROBEONLY&result=SUCCESS" -Token $load1.access
Check "K4" ($rE.code -ne 200 -and $rC.code -ne 200 -and $rP.code -ne 200 -and $rM.code -ne 200) "普通用户调管理端点 ensure=$($rE.code) cleanup=$($rC.code) preheat=$($rP.code) mock注入=$($rM.code)（期望全拒绝）"

Write-Host "PART5 DONE"