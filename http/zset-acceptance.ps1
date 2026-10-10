# ---------------------------------------------------------------------------
#  Acceptance probe for the ZSET-indexed RedisRefreshTokenStore.
#
#  Checks, against the running app and a real Redis:
#    [1] app liveness, and what /actuator/health actually reports
#    [2] keyspace BEFORE: the old implementation never wrote an index, so
#        auth:rt:idx:1 must be absent
#    [3] one real refresh call - a single rotation exercises all three of
#        isTokenValid + revokeToken + storeToken
#    [4] the index ZSET now exists, with the right member, score and TTL,
#        and the spent jti is gone from BOTH the index and the token keyspace
#    [5] revokeAll's lookup cost does not grow with total keyspace size
#        (ZRANGE on the index vs the old KEYS, measured at 9 keys and at 1M)
#    [6] cleanup of the filler keys
#
#  Timings in [5] are taken INSIDE the container via `sh -c "time redis-cli ..."`.
#  Wrapping `docker exec` in Measure-Command instead would add ~150ms of process
#  startup to every sample and drown the actual difference.
#
#  ASCII only: PowerShell 5.1 reads a BOM-less .ps1 as ANSI.
# ---------------------------------------------------------------------------

$ErrorActionPreference = "Continue"

$app   = "http://localhost:8080"
$redis = "zhiguang-redis"
$tmp   = $env:TEMP

function Rc {
    param([Parameter(ValueFromRemainingArguments = $true)]$a)
    & docker exec $redis redis-cli @a
}

function TimeIt([string]$label, [string]$cmd) {
    $out = & docker exec $redis sh -c "time redis-cli $cmd > /dev/null" 2>&1
    $line = ($out | Select-String "real" | Select-Object -First 1)
    $real = if ($line) { $line.ToString().Trim() } else { "n/a" }
    Write-Host ("    {0,-24} {1}" -f $label, $real)
}

function Decode-Payload([string]$t) {
    $p = $t.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p))
}

function Get-Jti([string]$t) {
    return [regex]::Match((Decode-Payload $t), '"jti":"([^"]+)"').Groups[1].Value
}

# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== [1] app liveness ===" -ForegroundColor Cyan
$health = & curl.exe -s "$app/actuator/health"
Write-Host "  /actuator/health  -> $health"
$feedCode = & curl.exe -s -o "$tmp\zg-acc-feed.txt" -w "%{http_code}" "$app/api/v1/knowposts/feed?page=1&size=1"
Write-Host "  /knowposts/feed   -> HTTP $feedCode"

Write-Host ""
Write-Host "=== [2] keyspace BEFORE ===" -ForegroundColor Cyan
Write-Host "  dbsize = $(Rc DBSIZE)"
$before = Rc --scan --pattern 'auth:rt:*'
if ($before) { $before | ForEach-Object { Write-Host "    $_" } } else { Write-Host "    <none>" }
Write-Host "  auth:rt:idx:1 EXISTS = $(Rc EXISTS 'auth:rt:idx:1')   (0 expected: written by the old impl)"

Write-Host ""
Write-Host "=== [3] one real refresh call ===" -ForegroundColor Cyan
$rtFile = Join-Path $tmp "zg-refresh3.txt"
if (-not (Test-Path $rtFile)) { $rtFile = Join-Path $tmp "zg-refresh2.txt" }
if (-not (Test-Path $rtFile)) { $rtFile = Join-Path $tmp "zg-refresh.txt" }
if (-not (Test-Path $rtFile)) {
    Write-Host "  FATAL: no saved refresh token found in $tmp" -ForegroundColor Red
    exit 1
}
$rt = (Get-Content $rtFile -Raw).Trim()
$oldJti = Get-Jti $rt
Write-Host "  source file : $rtFile"
Write-Host "  spending jti: $oldJti"

('{"refreshToken":"' + $rt + '"}') | Out-File "$tmp\zg-acc-body.json" -Encoding ascii -NoNewline
$code = & curl.exe -s -o "$tmp\zg-acc-out.json" -w "%{http_code}" -X POST `
    "$app/api/v1/auth/token/refresh" -H "Content-Type: application/json" `
    --data-binary "@$tmp\zg-acc-body.json"
$respBody = if (Test-Path "$tmp\zg-acc-out.json") { (Get-Content "$tmp\zg-acc-out.json" -Raw) } else { "" }
Write-Host "  HTTP $code"
if ($code -ne "200") {
    Write-Host "  refresh FAILED: $respBody" -ForegroundColor Red
    Write-Host "  remaining checks depend on it, aborting" -ForegroundColor Red
    exit 1
}
$newRt = [regex]::Match($respBody, '"refreshToken":"([^"]+)"').Groups[1].Value
$newJti = Get-Jti $newRt
$newRt | Out-File (Join-Path $tmp "zg-refresh3.txt") -Encoding ascii -NoNewline
Write-Host "  rotated to jti: $newJti"

Write-Host ""
Write-Host "=== [4] index ZSET must now exist and be consistent ===" -ForegroundColor Cyan
Write-Host "  TYPE  auth:rt:idx:1     = $(Rc TYPE 'auth:rt:idx:1')      (expect zset)"
Write-Host "  ZCARD                   = $(Rc ZCARD 'auth:rt:idx:1')"
Write-Host "  ZRANGE WITHSCORES:"
$zr = Rc ZRANGE 'auth:rt:idx:1' 0 -1 WITHSCORES
if ($zr) { $zr | ForEach-Object { Write-Host "    $_" } } else { Write-Host "    <empty>" }
$idxTtl = Rc TTL 'auth:rt:idx:1'
$tokTtl = Rc TTL "auth:rt:1:$newJti"
Write-Host "  TTL index               = $idxTtl   (expect ~2592000 = 30d fallback)"
Write-Host "  TTL token key           = $tokTtl   (expect ~604800 = 7d)"
Write-Host "  spent jti in index?     ZSCORE = '$(Rc ZSCORE 'auth:rt:idx:1' $oldJti)'   (expect empty)"
Write-Host "  spent token key alive?  EXISTS = $(Rc EXISTS "auth:rt:1:$oldJti")   (expect 0)"

# score is the expiry epochSecond; it must agree with the token key's own TTL.
$score = Rc ZSCORE 'auth:rt:idx:1' $newJti
if ($score -and $tokTtl -and $tokTtl -match '^\d+$') {
    $fromScore = [DateTimeOffset]::FromUnixTimeSeconds([long]$score).UtcDateTime
    $fromTtl = [DateTime]::UtcNow.AddSeconds([long]$tokTtl)
    $drift = [math]::Abs(($fromTtl - $fromScore).TotalSeconds)
    Write-Host "  score vs TTL cross-check:"
    Write-Host "    score -> $($fromScore.ToString('o'))"
    Write-Host "    ttl   -> $($fromTtl.ToString('o'))"
    Write-Host "    drift = $([math]::Round($drift, 1)) s   (expect < 5)"
}

Write-Host ""
Write-Host "=== [5] revokeAll lookup cost vs keyspace size ===" -ForegroundColor Cyan
Write-Host "  -- small keyspace, dbsize = $(Rc DBSIZE) --"
TimeIt "ZRANGE index (new)" "ZRANGE 'auth:rt:idx:1' 0 -1"
TimeIt "KEYS pattern (old)" "KEYS 'auth:rt:1:*'"
TimeIt "PING (baseline)" "PING"

Write-Host "  -- filling 1,000,000 unrelated keys --"
$n = Rc EVAL "for i=1,1000000 do redis.call('SET','bench:key:'..i,'x') end; return redis.call('DBSIZE')" 0
Write-Host "    dbsize = $n"
TimeIt "ZRANGE index (new)" "ZRANGE 'auth:rt:idx:1' 0 -1"
TimeIt "KEYS pattern (old)" "KEYS 'auth:rt:1:*'"
TimeIt "PING (baseline)" "PING"

Write-Host ""
Write-Host "=== [6] cleanup ===" -ForegroundColor Cyan
$d = Rc EVAL "local c='0'; local n=0; repeat local r=redis.call('SCAN',c,'MATCH','bench:*','COUNT',2000); c=r[1]; for _,k in ipairs(r[2]) do redis.call('UNLINK',k); n=n+1 end; until c=='0'; return n" 0
Write-Host "  unlinked $d filler keys"
Write-Host "  dbsize = $(Rc DBSIZE)"
Write-Host "  index still intact: ZCARD = $(Rc ZCARD 'auth:rt:idx:1')"

Write-Host ""
Write-Host "=== done ===" -ForegroundColor Yellow
