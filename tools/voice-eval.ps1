<#
方言语音购票 · 语料跑批（M5）

用法：
  pwsh -File tools\voice-eval.ps1
  pwsh -File tools\voice-eval.ps1 -BaseUrl http://localhost:8083 -Csv docs\voice-testset.tsv

做的事：
  1. 读语料 tsv；audio 列有值的，先调 /api/voice/recognize 拿识别文本，没有的直接用 text 列
  2. 把文本送 /api/voice/interpret
  3. 按 expect_* 列逐项比对：空态、出发站、到达站、日期、人数、席别
  4. 输出逐条结果 + 按类别汇总的通过率

约定：
  expect_站名 带 # 结尾 = 同城任一（北京# 表示北京南或北京西都算对）
  非 OK 的用例（缺站/超窗）只校验"候选里有没有这个站"，因为 intent 这时本来是空的
#>
param(
    [string]$BaseUrl = "http://localhost:8083",
    [string]$Csv = "",
    [string]$User = "admin",
    [string]$Pass = "123456"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Csv)) {
    $Csv = Join-Path (Split-Path -Parent $PSScriptRoot) "docs\voice-testset.tsv"
}
if (-not (Test-Path -LiteralPath $Csv)) {
    throw "语料文件不存在：$Csv"
}

$today = Get-Date
$todayStr = $today.ToString("yyyy-MM-dd")

$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post `
    -ContentType "application/json;charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes((@{ username = $User; password = $Pass } | ConvertTo-Json -Compress)))
$headers = @{ Authorization = "Bearer $($login.data.accessToken)" }

$rows = Import-Csv -LiteralPath $Csv -Delimiter "`t" -Encoding utf8
$results = @()

foreach ($r in $rows) {
    $text = $r.text
    $stage = "text"
    $audioNote = ""

    # 有音频就先识别，走端到端
    if ($r.audio -and $r.audio.Trim().Length -gt 0) {
        $audioPath = Join-Path (Split-Path -Parent $Csv) $r.audio
        if (-not (Test-Path -LiteralPath $audioPath)) {
            $results += [pscustomobject]@{
                id = $r.id; category = $r.category; pass = $false
                detail = "音频缺失：$($r.audio)"; text = $text
            }
            continue
        }
        $stage = "audio"
        try {
            $asr = Invoke-RestMethod -Uri "$BaseUrl/api/voice/recognize" -Method Post -Headers $headers `
                -Form @{ audio = Get-Item -LiteralPath $audioPath; dialect = "auto" } -TimeoutSec 90
            if ($asr.code -ne 200 -or -not $asr.data.rawText) {
                $results += [pscustomobject]@{
                    id = $r.id; category = $r.category; pass = $false
                    detail = "识别失败：$($asr.message)"; text = ""
                }
                continue
            }
            $text = $asr.data.rawText
            $audioNote = "识别=「$text」 "
        } catch {
            $results += [pscustomobject]@{
                id = $r.id; category = $r.category; pass = $false
                detail = "识别异常：$($_.Exception.Message)"; text = ""
            }
            continue
        }
    }

    $body = @{ text = $text; todayHint = $todayStr } | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Uri "$BaseUrl/api/voice/interpret" -Method Post -Headers $headers `
        -ContentType "application/json;charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body))

    # ---- 实际空态 ----
    $data = $resp.data
    $outcome = "OTHER"
    if ($resp.code -ne 200) {
        if ($resp.message -match "最多买") { $outcome = "TOO_MANY" }
        else { $outcome = "OTHER($($resp.message))" }
    } elseif ($null -eq $data) {
        $outcome = "OTHER(null)"
    } elseif ($data.emptyReason -eq "DATE_OUT_OF_WINDOW") {
        $outcome = "DATE_OUT_OF_WINDOW"
    } elseif ($data.emptyReason -eq "STATION_UNCONFIRMED") {
        $outcome = "STATION_UNCONFIRMED"
    } elseif ($data.emptyReason -eq "NO_TRAIN") {
        $outcome = "NO_TRAIN"
    } elseif (@($data.trains).Count -gt 0) {
        $outcome = "OK"
    }

    $fails = @()
    if ($outcome -ne $r.expect_outcome) {
        $fails += "空态 expect=$($r.expect_outcome) actual=$outcome"
    }

    $isOk = ($outcome -eq "OK")
    $candFrom = if ($data) { $data.stationCandidates.from } else { @() }
    $candTo = if ($data) { $data.stationCandidates.to } else { @() }
    $intent = if ($data) { $data.intent } else { $null }

    # ---- 站名 ----
    foreach ($dir in @("from", "to")) {
        $expect = $r.$dir
        if ([string]::IsNullOrWhiteSpace($expect)) { continue }
        $expect = $expect.Trim()
        $needPrefix = $expect.EndsWith("#")
        $expectName = $expect.TrimEnd([char]'#')

        if ($isOk -and -not $needPrefix) {
            $actual = if ($dir -eq "from") { $intent.fromStation } else { $intent.toStation }
            if ($actual -ne $expectName) {
                $fails += "$dir expect=$expectName actual=$actual"
            }
        } else {
            $cands = if ($dir -eq "from") { $candFrom } else { $candTo }
            $hit = @($cands | Where-Object {
                if ($needPrefix) { $_.stationName.StartsWith($expectName) } else { $_.stationName -eq $expectName }
            }).Count -gt 0
            if (-not $hit) {
                $names = @($cands | ForEach-Object { $_.stationName }) -join "/"
                $fails += "$dir 候选里找不到 $expectName（实际候选：$names）"
            }
        }
    }

    # ---- 日期 / 人数 / 席别：只在 OK 时校验 ----
    if ($isOk) {
        if (-not [string]::IsNullOrWhiteSpace($r.expect_day)) {
            $expectDate = $today.AddDays([int]$r.expect_day).ToString("yyyy-MM-dd")
            if ($intent.travelDate -ne $expectDate) {
                $fails += "日期 expect=$expectDate actual=$($intent.travelDate)"
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($r.expect_count)) {
            if ([int]$intent.passengerCount -ne [int]$r.expect_count) {
                $fails += "人数 expect=$($r.expect_count) actual=$($intent.passengerCount)"
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($r.expect_seat)) {
            if ($intent.seatPreference -ne $r.expect_seat) {
                $fails += "席别 expect=$($r.expect_seat) actual=$($intent.seatPreference)"
            }
        }
    }

    $trains = if ($isOk) { @($data.trains).Count } else { 0 }
    $detail = if ($fails.Count -eq 0) {
        if ($isOk) { "$outcome（$trains 趟）" } else { $outcome }
    } else {
        $fails -join "；"
    }

    $results += [pscustomobject]@{
        id = $r.id
        category = $r.category
        pass = ($fails.Count -eq 0)
        detail = ($audioNote + $detail)
        text = $text
    }
}

# ---- 输出 ----
"语料：$Csv"
"接口：$BaseUrl    基准日期：$todayStr"
""
"{0,-4} {1,-14} {2,-6} {3}" -f "ID", "类别", "结果", "说明" | Out-String | Write-Output
foreach ($x in $results) {
    $mark = if ($x.pass) { "PASS" } else { "FAIL" }
    "{0,-4} {1,-14} {2,-6} {3}" -f $x.id, $x.category, $mark, $x.detail | Write-Output
}

$total = $results.Count
$passed = @($results | Where-Object { $_.pass }).Count
""
"总计 $passed / $total 通过，通过率 $([math]::Round(100 * $passed / $total, 1))%"

""
"按类别："
$results | Group-Object category | ForEach-Object {
    $p = @($_.Group | Where-Object { $_.pass }).Count
    "  {0,-14} {1}/{2}" -f $_.Name, $p, $_.Count
}
