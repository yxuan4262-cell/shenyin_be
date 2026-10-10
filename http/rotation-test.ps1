# ---------------------------------------------------------------------------
#  Refresh Token Rotation probe
#
#  What it demonstrates, with real HTTP calls against the running app:
#    1. a successful refresh ROTATES the token: the old jti leaves the Redis
#       whitelist and a new jti enters it
#    2. a used refresh token is SINGLE-USE: replaying it gets rejected
#    3. the rejection is INDISTINGUISHABLE from any other invalid-token case,
#       which is exactly why "reuse detection" cannot be bolted on later
#
#  Run from the project dir:
#     powershell -NoProfile -ExecutionPolicy Bypass -File .\http\rotation-test.ps1
#
#  Prereq: %TEMP%\zg-refresh.txt holds a live refresh token (get-token.ps1
#          writes it). The app must be up on :8080 and zhiguang-redis running.
#
#  NOTE: ASCII only. PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so any
#        non-ASCII character here would turn into mojibake at parse time.
# ---------------------------------------------------------------------------

$ErrorActionPreference = "Continue"

$base  = "http://localhost:8080/api/v1/auth/token/refresh"
$redis = "zhiguang-redis"
$tmp   = $env:TEMP

function Decode-Payload([string]$t) {
    $p = $t.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p))
}

function Get-Jti([string]$t) {
    return [regex]::Match((Decode-Payload $t), '"jti":"([^"]+)"').Groups[1].Value
}

function Show-Whitelist([string]$label) {
    Write-Host "  whitelist $label"
    $keys = & docker exec $redis redis-cli --scan --pattern 'auth:rt:*' | Sort-Object
    foreach ($k in $keys) {
        $ttl = & docker exec $redis redis-cli TTL $k
        Write-Host "    $k   (ttl=${ttl}s)"
    }
    if (-not $keys) { Write-Host "    <empty>" }
}

function Call-Refresh([string]$token, [string]$label) {
    $bodyFile = Join-Path $tmp "zg-rot-body.json"
    $outFile  = Join-Path $tmp "zg-rot-out.json"
    if (Test-Path $outFile) { Remove-Item $outFile -Force }

    ('{"refreshToken":"' + $token + '"}') | Out-File $bodyFile -Encoding ascii -NoNewline

    $code = & curl.exe -s -o $outFile -w "%{http_code}" -X POST $base `
        -H "Content-Type: application/json" --data-binary "@$bodyFile"

    $body = ""
    if (Test-Path $outFile) { $body = (Get-Content $outFile -Raw) }

    Write-Host "  [$label] HTTP $code"
    if ($body.Length -gt 0) {
        # The token strings are ~600 chars each; show the shape, not the blob.
        $shown = $body -replace '"(accessToken|refreshToken)":"[^"]{20}[^"]*"', '"$1":"<...redacted...>"'
        if ($shown.Length -gt 300) { $shown = $shown.Substring(0, 300) + "..." }
        Write-Host "           $shown"
    }
    return @{ code = $code; body = $body }
}

# ---------------------------------------------------------------------------

$rtFile = Join-Path $tmp "zg-refresh.txt"
if (-not (Test-Path $rtFile)) {
    Write-Host "FATAL: $rtFile not found. Run get-token.ps1 first." -ForegroundColor Red
    exit 1
}
$old = (Get-Content $rtFile -Raw).Trim()

Write-Host ""
Write-Host "=== the refresh token we are going to spend ===" -ForegroundColor Cyan
Write-Host "  jti      = $(Get-Jti $old)"
Write-Host "  length   = $($old.Length) chars"
Write-Host "  payload  = $((Decode-Payload $old))"

Show-Whitelist "BEFORE anything"

Write-Host ""
Write-Host "=== CALL 1: spend it (expect 200 + rotation) ===" -ForegroundColor Cyan
$r1 = Call-Refresh $old "call-1-fresh"
$new = [regex]::Match($r1.body, '"refreshToken":"([^"]+)"').Groups[1].Value
if (-not $new) {
    Write-Host "  no refreshToken in response, aborting" -ForegroundColor Red
    exit 1
}
Write-Host "  rotated to jti = $(Get-Jti $new)"
$new | Out-File (Join-Path $tmp "zg-refresh2.txt") -Encoding ascii -NoNewline
Show-Whitelist "AFTER call 1"

Write-Host ""
Write-Host "=== CALL 2: REPLAY the token we just spent (expect rejection) ===" -ForegroundColor Cyan
$r2 = Call-Refresh $old "call-2-replay-old"
Show-Whitelist "AFTER call 2"

Write-Host ""
Write-Host "=== CALL 3: spend the rotated token (expect 200 again) ===" -ForegroundColor Cyan
$r3 = Call-Refresh $new "call-3-rotated"
$new2 = [regex]::Match($r3.body, '"refreshToken":"([^"]+)"').Groups[1].Value
if ($new2) {
    Write-Host "  rotated to jti = $(Get-Jti $new2)"
    $new2 | Out-File (Join-Path $tmp "zg-refresh3.txt") -Encoding ascii -NoNewline
}
Show-Whitelist "AFTER call 3"

Write-Host ""
Write-Host "=== CALL 4: replay THAT one too (expect rejection) ===" -ForegroundColor Cyan
$r4 = Call-Refresh $new "call-4-replay-rotated"

Write-Host ""
Write-Host "=== summary ===" -ForegroundColor Yellow
Write-Host "  call 1  fresh token            -> HTTP $($r1.code)"
Write-Host "  call 2  replay of spent token  -> HTTP $($r2.code)"
Write-Host "  call 3  rotated token          -> HTTP $($r3.code)"
Write-Host "  call 4  replay of spent token  -> HTTP $($r4.code)"
Write-Host ""
Write-Host "  Question to sit with: call 2 and call 4 look identical on the wire."
Write-Host "  One of them could be a browser retry, the other could be an attacker."
Write-Host "  Nothing in the response, and nothing in the app log, tells them apart."
