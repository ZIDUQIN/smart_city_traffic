# 终检：H4 全库一致性 / I5 巡检基准 / 汇总
. "D:\IDEAproject\smart-city-traffic\tools\fulltest\common.ps1"
$bt = [char]96
Add-Content $script:ResultsFile "===== FINAL $(Get-Date -Format 'HH:mm:ss') =====" -Encoding utf8

$over = Sql @"
SELECT COUNT(*) FROM stock s JOIN stock_capacity c
  ON c.train_no=s.train_no AND c.seat_no=s.seat_no AND c.segment_no=s.segment_no
WHERE s.remain > c.total_capacity AND s.update_time >= DATE_SUB(NOW(), INTERVAL 2 DAY);
"@
$overCnt = [int]($over | Select-Object -First 1)

$drift = Sql @"
SELECT s.instance_no, s.seat_no, s.segment_no, s.remain,
  c.total_capacity-(SELECT COUNT(*) FROM ${bt}order${bt} o
    WHERE o.instance_no=s.instance_no AND o.seat_no=s.seat_no
      AND o.from_seq<=s.segment_no AND o.to_seq>s.segment_no
      AND o.status IN ('已确认','已支付')) AS expect
FROM stock s JOIN stock_capacity c
  ON c.train_no=s.train_no AND c.seat_no=s.seat_no AND c.segment_no=s.segment_no
WHERE s.update_time >= DATE_SUB(NOW(), INTERVAL 2 DAY)
  AND s.remain <> c.total_capacity-(SELECT COUNT(*) FROM ${bt}order${bt} o
    WHERE o.instance_no=s.instance_no AND o.seat_no=s.seat_no
      AND o.from_seq<=s.segment_no AND o.to_seq>s.segment_no
      AND o.status IN ('已确认','已支付'));
"@
$driftRows = @($drift | Where-Object { $_ -match "\t" })
Check "H4" ($overCnt -eq 0 -and $driftRows.Count -eq 0) "越界=$overCnt 漂移=$($driftRows.Count) $(if($driftRows.Count){$driftRows -join ' | '})"
Check "I5" ($overCnt -eq 0 -and $driftRows.Count -eq 0) "巡检同口径复核 越界=$overCnt 漂移=$($driftRows.Count)"

# 被测实例 Redis/DB 终态
$insts = @("D2636-20261118", "D2636-20261120")
$allOk = $true
foreach ($inst in $insts) {
    $raw = RedisCmd "HGETALL 'stock:{$inst}:SECOND_CLASS'"
    $lines = @($raw | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
    if ($lines.Count -eq 0) { continue }  # 缓存不存在=合法miss，下次查询回源，不算不一致
    $map = @{}
    for ($i = 0; $i -lt $lines.Count - 1; $i += 2) { $map[$lines[$i]] = $lines[$i + 1] }
    $rows = Sql "SELECT segment_no, remain FROM stock WHERE instance_no='$inst' AND seat_no='SECOND_CLASS';"
    foreach ($line in $rows) {
        $p = "$line" -split "\t"
        if ($p.Count -lt 2) { continue }
        if (-not $map.ContainsKey($p[0]) -or $map[$p[0]] -ne $p[1]) { $allOk = $false; Write-Host "不一致 $inst seg$($p[0]) redis=$($map[$p[0]]) db=$($p[1])" }
    }
}
Check "H5final" $allOk "两实例Redis与DB逐段一致=$allOk"

# 汇总
$all = Get-Content $script:ResultsFile -Encoding utf8
$pass = @($all | Where-Object { $_ -match "^PASS" }).Count
$fail = @($all | Where-Object { $_ -match "^FAIL" }).Count
Write-Host "===== 汇总 PASS=$pass FAIL=$fail ====="
$all | Where-Object { $_ -match "^FAIL" } | ForEach-Object { Write-Host $_ }