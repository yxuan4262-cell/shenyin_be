# ---------------------------------------------------------------------------
#  Cache three-problems live probe (Stage 7 Lesson 1).
#
#  Demonstrates, against the running app + real Redis:
#    [PENETRATION] a non-existent id writes a "NULL" sentinel with a short
#                  randomized TTL, so the 2nd call is served from cache and
#                  never reaches the DB again.
#    [HIT]         a real id populates L2 (Redis JSON) with a jittered TTL.
#
#  ASCII only: PowerShell 5.1 reads a BOM-less .ps1 as ANSI.
# ---------------------------------------------------------------------------

$ErrorActionPreference = "Continue"
$app = "http://localhost:8080"
$R   = "zhiguang-redis"
$bad = 999999999

function Rc { param([Parameter(ValueFromRemainingArguments=$true)]$a) & docker exec $R redis-cli @a }

Write-Host ""
Write-Host "=== [PENETRATION] request a non-existent id TWICE ===" -ForegroundColor Cyan
foreach ($i in 1..2) {
    $code = & curl.exe -s -o "$env:TEMP\zg-d.txt" -w "%{http_code}" "$app/api/v1/knowposts/detail/$bad"
    $body = Get-Content "$env:TEMP\zg-d.txt" -Raw -EA SilentlyContinue
    if ($body.Length -gt 80) { $body = $body.Substring(0,80) }
    Write-Host "  call#$i  HTTP $code  body=$body"
}

Write-Host ""
Write-Host "=== NULL sentinel in Redis? what TTL? ===" -ForegroundColor Cyan
$keys = Rc --scan --pattern "knowpost:detail:${bad}:*"
if ($keys) {
    foreach ($k in $keys) {
        $v = Rc GET $k
        $t = Rc TTL $k
        Write-Host "    key=$k"
        Write-Host "      value='$v'   ttl=$t   (expect value=NULL, ttl in 30..60)"
    }
} else {
    Write-Host "    <no sentinel key found>"
}

Write-Host ""
Write-Host "=== [HIT] request a REAL id 9001, inspect L2 ===" -ForegroundColor Cyan
$code = & curl.exe -s -o "$env:TEMP\zg-d2.txt" -w "%{http_code}" "$app/api/v1/knowposts/detail/9001"
$body = Get-Content "$env:TEMP\zg-d2.txt" -Raw -EA SilentlyContinue
Write-Host "  HTTP $code  bodyLen=$($body.Length)"
$keys2 = Rc --scan --pattern "knowpost:detail:9001:*"
if ($keys2) {
    foreach ($k in $keys2) {
        $t = Rc TTL $k
        $len = (Rc GET $k).Length
        Write-Host "    key=$k   ttl=$t   jsonLen=$len   (ttl jittered: 60..89)"
    }
} else {
    Write-Host "    <no L2 key: detail 9001 may not exist in DB>"
}

Write-Host ""
Write-Host "=== done ===" -ForegroundColor Yellow
