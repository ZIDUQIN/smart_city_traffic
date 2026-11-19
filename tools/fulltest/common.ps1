# 全量测试公共函数。各 part 脚本 dot-source 本文件。
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$script:Base = "http://localhost:8083"
$script:Mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$script:Python = "python"
$script:RedisCmdPy = "D:\IDEAproject\smart-city-traffic\tools\redis_cmd.py"
$script:ResultsFile = "D:\IDEAproject\smart-city-traffic\tools\fulltest\results.txt"
$script:HmacSecret = "smart_city_mock_secret_2026"
$env:MYSQL_PWD = "123456"

function Sql([string]$q) {
    & $script:Mysql -uroot -N -B --default-character-set=utf8mb4 "smart-city-traffic" -e $q 2>&1 |
        Where-Object { $_ -notmatch "^mysql:" }
}

function RedisCmd([string]$cmd) {
    & $script:Python $script:RedisCmdPy $cmd 2>&1
}

function Check([string]$id, [bool]$ok, [string]$detail) {
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    $line = "$tag  $id  $detail"
    Add-Content -Path $script:ResultsFile -Value $line -Encoding utf8
    Write-Host $line
}

# 统一 API 调用：返回 @{http; code; message; data; raw}
function Api {
    param(
        [string]$Method = "GET",
        [string]$Path,
        $Body = $null,
        [string]$Token = $null,
        [string]$RawBody = $null,
        [hashtable]$Headers = @{},
        [string]$ContentType = "application/json; charset=utf-8"
    )
    $h = @{}
    if ($Token) { $h["Authorization"] = "Bearer $Token" }
    foreach ($k in $Headers.Keys) { $h[$k] = $Headers[$k] }
    $params = @{
        Uri = "$script:Base$Path"; Method = $Method; Headers = $h
        SkipHttpErrorCheck = $true; TimeoutSec = 40
    }
    if ($RawBody) {
        $params["Body"] = $RawBody
        $params["ContentType"] = $ContentType
    } elseif ($null -ne $Body) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 8 -Compress)
        $params["ContentType"] = $ContentType
    }
    try {
        $r = Invoke-WebRequest @params
        $content = $r.Content
        $json = $null
        if ($content -and $content.TrimStart().StartsWith("{")) {
            try { $json = $content | ConvertFrom-Json } catch {}
        }
        return @{
            http = [int]$r.StatusCode; raw = $content
            code = if ($json -and $json.PSObject.Properties["code"]) { $json.code } else { $null }
            message = if ($json) { $json.message } else { $null }
            data = if ($json) { $json.data } else { $null }
        }
    } catch {
        return @{ http = -1; raw = $_.Exception.Message; code = $null; message = $_.Exception.Message; data = $null }
    }
}

function Login([string]$username, [string]$password = "123456") {
    $r = Api -Method POST -Path "/api/auth/login" -Body @{ username = $username; password = $password }
    if ($r.code -ne 200) { throw "登录失败 $username : $($r.raw)" }
    return @{ access = $r.data.accessToken; refresh = $r.data.refreshToken }
}

function HmacSign([string]$content) {
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($script:HmacSecret))
    $bytes = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($content))
    return ([BitConverter]::ToString($bytes) -replace "-", "").ToLower()
}

function OrderBody([int]$passengerId, [string]$trainNo, [string]$date, [string]$seat, [int]$from, [int]$to) {
    $b = @{ passengerId = $passengerId; trainNo = $trainNo; seatNo = $seat; fromSeq = $from; toSeq = $to }
    if ($date) { $b["travelDate"] = $date }
    return $b
}

function RemainDb([string]$instance, [string]$seat, [int]$seg) {
    $v = Sql "SELECT remain FROM stock WHERE instance_no='$instance' AND seat_no='$seat' AND segment_no=$seg;"
    return [int]($v | Select-Object -First 1)
}

function OrderStatus([string]$orderNo) {
    $bt = [char]96
    $v = Sql "SELECT status FROM ${bt}order${bt} WHERE order_no='$orderNo';"
    return ($v | Select-Object -First 1)
}