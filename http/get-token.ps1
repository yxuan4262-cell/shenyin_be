<#
    get-token.ps1  -  One-shot accessToken fetcher for local testing

    What it does:
      1. POST /api/v1/auth/send-code      (asks the server to issue a code)
      2. reads the code out of the app log (LoggingCodeSender only prints it)
      3. POST /api/v1/auth/login          (exchanges the code for tokens)
      4. saves accessToken to %TEMP%\zg-token.txt
      5. GET  /api/v1/auth/me             (proves the token actually works)

    Usage:
      powershell -ExecutionPolicy Bypass -File http\get-token.ps1
      powershell -ExecutionPolicy Bypass -File http\get-token.ps1 -Email rival@zhiguang.dev
      powershell -ExecutionPolicy Bypass -File http\get-token.ps1 -Scene REGISTER

    Note on -LogFile:
      It only works when the app was started with its stdout redirected to that
      file (e.g. via Start-Process -RedirectStandardOutput). If you start the app
      from the IDE Run button the log lives in the IDE console instead, so the
      script cannot grep it - it will then ask you to paste the code manually.
#>
param(
    [string]$Email    = "learner@zhiguang.dev",
    [string]$BaseUrl  = "http://localhost:8080",
    [string]$LogFile  = "$env:TEMP\zg-run.log",
    [ValidateSet("LOGIN", "REGISTER", "RESET_PASSWORD")]
    [string]$Scene    = "LOGIN"
)

$ErrorActionPreference = "Stop"

function Show-ErrorBody($err) {
    # Invoke-RestMethod hides the response body on 4xx; dig it out of the stream.
    $resp = $err.Exception.Response
    if ($resp) {
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        Write-Host ("HTTP {0} -> {1}" -f [int]$resp.StatusCode, $reader.ReadToEnd()) -ForegroundColor Red
    } else {
        Write-Host ("No response at all: {0}" -f $err.Exception.Message) -ForegroundColor Red
        Write-Host "Is the app running? Check: Get-NetTCPConnection -LocalPort 8080 -State Listen" -ForegroundColor Yellow
    }
}

# ---------- step 0: is the service even up? ----------
$listen = Get-NetTCPConnection -LocalPort ($BaseUrl -replace '.*:', '') -State Listen -ErrorAction SilentlyContinue
if (-not $listen) {
    Write-Host "Port 8080 is NOT listening. Start the application first." -ForegroundColor Red
    exit 1
}
Write-Host "Port 8080 listening (PID $($listen.OwningProcess))." -ForegroundColor DarkGray

# ---------- step 1: ask for a verification code ----------
Write-Host "`n[1/5] Requesting code (scene=$Scene, identifier=$Email)..." -ForegroundColor Cyan
$sendBody = @{ scene = $Scene; identifierType = "EMAIL"; identifier = $Email } | ConvertTo-Json -Compress
try {
    $sendResp = Invoke-RestMethod "$BaseUrl/api/v1/auth/send-code" -Method Post `
        -ContentType "application/json" -Body $sendBody
    Write-Host ("      ok, expires in {0}s" -f $sendResp.expireSeconds) -ForegroundColor Green
} catch {
    Write-Host "      send-code FAILED:" -ForegroundColor Red
    Show-ErrorBody $_
    Write-Host "      Hint: the same account must wait 60s between sends, max 10 per day." -ForegroundColor Yellow
    exit 1
}

# ---------- step 2: dig the code out of the log ----------
Start-Sleep -Milliseconds 800
Write-Host "`n[2/5] Looking for the code in $LogFile ..." -ForegroundColor Cyan
$code = $null
if (Test-Path $LogFile) {
    $line = (Get-Content $LogFile | Select-String "Send verification code" |
             Where-Object { $_.Line -match [regex]::Escape($Email) } |
             Select-Object -Last 1).Line
    if ($line) {
        $code = [regex]::Match($line, 'code=(\d+)').Groups[1].Value
        Write-Host "      $line" -ForegroundColor DarkGray
    }
}
if (-not $code) {
    Write-Host "      Could not auto-detect (app probably logs to the IDE console)." -ForegroundColor Yellow
    $code = Read-Host "      Paste the 6-digit code here"
}
if ($code -notmatch '^\d{4,8}$') {
    Write-Host "That does not look like a code: '$code'" -ForegroundColor Red
    exit 1
}
Write-Host "      code = $code" -ForegroundColor Green

# ---------- step 3: exchange the code for tokens ----------
$endpoint = if ($Scene -eq "REGISTER") { "register" } else { "login" }
Write-Host "`n[3/5] POST /api/v1/auth/$endpoint ..." -ForegroundColor Cyan
$authBody = @{ identifierType = "EMAIL"; identifier = $Email; code = $code }
if ($Scene -eq "REGISTER") { $authBody.password = "Abcd1234!"; $authBody.agreeTerms = $true }
try {
    $auth = Invoke-RestMethod "$BaseUrl/api/v1/auth/$endpoint" -Method Post `
        -ContentType "application/json" -Body ($authBody | ConvertTo-Json -Compress)
} catch {
    Write-Host "      FAILED:" -ForegroundColor Red
    Show-ErrorBody $_
    exit 1
}

# ---------- step 4: persist the token ----------
# NOTE: accessToken lives under .token.accessToken, NOT at the top level.
$accessToken  = $auth.token.accessToken
$refreshToken = $auth.token.refreshToken
if (-not $accessToken) {
    Write-Host "Login succeeded but no accessToken in response. Raw payload:" -ForegroundColor Yellow
    $auth | ConvertTo-Json -Depth 6
    exit 1
}
$tokenFile = "$env:TEMP\zg-token.txt"
Set-Content -Path $tokenFile -Value $accessToken -Encoding ascii -NoNewline
Write-Host "`n[4/5] Saved accessToken -> $tokenFile" -ForegroundColor Green
Write-Host "      userId      = $($auth.user.id)"
Write-Host "      expires at  = $($auth.token.accessTokenExpiresAt)  (15 min TTL)"
Write-Host "      refresh exp = $($auth.token.refreshTokenExpiresAt)"

# ---------- step 5: prove it works ----------
Write-Host "`n[5/5] Verifying with GET /api/v1/auth/me ..." -ForegroundColor Cyan
try {
    $me = Invoke-RestMethod "$BaseUrl/api/v1/auth/me" -Headers @{ Authorization = "Bearer $accessToken" }
    Write-Host "      token ACCEPTED. userId=$($me.id)" -ForegroundColor Green
} catch {
    Write-Host "      token REJECTED:" -ForegroundColor Red
    Show-ErrorBody $_
    exit 1
}

Write-Host "`nDone. Paste this into comment-api.http as @token :" -ForegroundColor Cyan
Write-Host $accessToken
Write-Host "`nRefresh token (keep it, used by /auth/token/refresh):" -ForegroundColor DarkGray
Write-Host $refreshToken
