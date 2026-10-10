<#
.SYNOPSIS
    Comment advanced-search endpoint acceptance test (24 probes).

.DESCRIPTION
    Requires:
      1) app running on localhost:8080
      2) a fresh accessToken in $env:TEMP\zg-token.txt   (run get-token.ps1 first)

    Every probe asserts an exact HTTP status and, where meaningful, a substring
    in the response body. Run it after any change to CommentMapper.xml /
    CommentServiceImpl / CommentController.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\http\search-acceptance.ps1
#>

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$base  = 'http://localhost:8080/api/v1/comments/search'
$token = (Get-Content "$env:TEMP\zg-token.txt" -Raw).Trim()
$H     = @{ Authorization = "Bearer $token" }

$script:pass = 0; $script:fail = 0

function Show-Body($ex) {
    try {
        $sr = New-Object IO.StreamReader($ex.Exception.Response.GetResponseStream())
        return $sr.ReadToEnd()
    } catch { return '<no body>' }
}

function Probe {
    param(
        [string]$Name,
        [string]$Query = '',
        [int]$ExpectStatus,
        [hashtable]$Headers = $H,
        [string]$ExpectContains = '',
        [string]$ExpectNotContains = ''
    )
    $url = if ($Query) { "$base`?$Query" } else { $base }
    try {
        $resp = Invoke-WebRequest -Uri $url -Headers $Headers -Method GET -UseBasicParsing
        $code = [int]$resp.StatusCode
        $body = $resp.Content
    } catch {
        $code = [int]$_.Exception.Response.StatusCode
        $body = Show-Body $_
    }

    $ok = ($code -eq $ExpectStatus)
    if ($ok -and $ExpectContains)    { $ok = $body.Contains($ExpectContains) }
    if ($ok -and $ExpectNotContains) { $ok = -not $body.Contains($ExpectNotContains) }

    if ($ok) { $script:pass++ } else { $script:fail++ }
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1}" -f $mark, $Name) -ForegroundColor $(if ($ok) { 'Green' } else { 'Red' })
    Write-Host ("       expect={0} actual={1}" -f $ExpectStatus, $code)
    if (-not $ok) {
        $snippet = if ($body.Length -gt 400) { $body.Substring(0,400) + '...' } else { $body }
        Write-Host ("       body: {0}" -f $snippet)
    }
    Write-Host ''
}

Write-Host '================ SEARCH ENDPOINT ACCEPTANCE ================' -ForegroundColor Cyan
Write-Host ''

# ---- auth boundary ----
Probe -Name '01 no token -> 401 (endpoint is NOT permitAll)' -ExpectStatus 401 -Headers @{}

# ---- all <if> false: <where> must vanish entirely, default status = normal ----
Probe -Name '02 no params -> 200, only normal rows' -ExpectStatus 200 `
      -ExpectContains '"status":"normal"' -ExpectNotContains '"status":"deleted"'

# ---- equality filters ----
Probe -Name '03 postId=9001 -> 200'                  -Query 'postId=9001'     -ExpectStatus 200 -ExpectContains '"page":1'
Probe -Name '04 postId=99999999 -> 200 empty items'  -Query 'postId=99999999' -ExpectStatus 200 -ExpectContains '"items":[]'
Probe -Name '05 creatorId=1 -> 200'                  -Query 'creatorId=1'     -ExpectStatus 200

# ---- LIKE: must really work, and must not be silently broken ----
Probe -Name '06 keyword=zhi-guang-cn -> 200'         -Query 'keyword=%E7%9F%A5%E5%85%89'      -ExpectStatus 200
Probe -Name '07 keyword unmatched -> 200 empty'      -Query 'keyword=zzz_no_such_text'        -ExpectStatus 200 -ExpectContains '"items":[]'
Probe -Name '08 keyword blank -> treated as absent'  -Query 'keyword=%20%20%20'               -ExpectStatus 200 -ExpectContains '"status":"normal"'
Probe -Name '09 keyword literal % -> escaped, empty' -Query 'keyword=%25'                     -ExpectStatus 200 -ExpectContains '"items":[]'

# ---- <foreach>: empty list must NOT render "status IN ()" ----
Probe -Name '10 statusList=deleted -> 200'                    -Query 'statusList=deleted'                    -ExpectStatus 200
Probe -Name '11 statusList=normal&statusList=deleted -> 200'  -Query 'statusList=normal&statusList=deleted'  -ExpectStatus 200
Probe -Name '12 statusList=hacked -> 400 whitelist reject'    -Query 'statusList=hacked'                     -ExpectStatus 400 -ExpectContains 'BAD_REQUEST'

# ---- <choose>: ORDER BY must never take user text ----
Probe -Name '13a sort=time_asc -> 200'      -Query 'sort=time_asc' -ExpectStatus 200
Probe -Name '13b sort=creator -> 200'       -Query 'sort=creator'  -ExpectStatus 200
Probe -Name '13c sort=time alias -> 200'    -Query 'sort=time'     -ExpectStatus 200
Probe -Name '13d sort=bogus -> 400'         -Query 'sort=bogus'    -ExpectStatus 400 -ExpectContains 'BAD_REQUEST'

# ---- time range ----
Probe -Name '14a startTime only -> 200'          -Query 'startTime=2026-01-01T00:00:00Z' -ExpectStatus 200
Probe -Name '14b endTime only -> 200'            -Query 'endTime=2030-01-01T00:00:00Z'   -ExpectStatus 200
Probe -Name '14c both wide range -> 200'         -Query 'startTime=2026-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z' -ExpectStatus 200
Probe -Name '14d bad format -> 400'              -Query 'startTime=2026-09-01'           -ExpectStatus 400 -ExpectContains 'BAD_REQUEST'
Probe -Name '14e start after end -> 400'         -Query 'startTime=2030-01-01T00:00:00Z&endTime=2026-01-01T00:00:00Z' -ExpectStatus 400

# ---- pagination clamp ----
Probe -Name '15a size=999 -> clamped to 50'   -Query 'size=999'   -ExpectStatus 200 -ExpectContains '"size":50'
Probe -Name '15b size=0 -> clamped to 1'      -Query 'size=0'     -ExpectStatus 200 -ExpectContains '"size":1'
Probe -Name '15c page=0 -> clamped to 1'      -Query 'page=0'     -ExpectStatus 200 -ExpectContains '"page":1'
Probe -Name '15d page=99999 -> clamped 10000' -Query 'page=99999' -ExpectStatus 200 -ExpectContains '"page":10000'

# ---- everything at once ----
Probe -Name '16 combined filters -> 200' -Query 'postId=9001&creatorId=1&statusList=normal&sort=time_asc&page=1&size=10' -ExpectStatus 200

Write-Host '============================================================' -ForegroundColor Cyan
Write-Host ("PASS={0}  FAIL={1}" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
