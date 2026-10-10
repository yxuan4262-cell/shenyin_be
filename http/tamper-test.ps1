# ---------------------------------------------------------------------------
#  Tampered / forged / expired JWT probe.
#
#  Purpose: prove empirically what Spring Security ACTUALLY returns when a
#           JWT fails validation, instead of guessing from the source code.
#
#  Run:  powershell -NoProfile -ExecutionPolicy Bypass -File .\http\tamper-test.ps1
#
#  NOTE: ASCII-only on purpose. PowerShell 5.1 reads BOM-less .ps1 as ANSI,
#        so Chinese text here would turn into mojibake.
# ---------------------------------------------------------------------------

$ErrorActionPreference = "Continue"

$endpoint = "http://localhost:8080/api/v1/relation/status?toUserId=1001"

function Decode-B64U([string]$s) {
    $s = $s.Replace('-', '+').Replace('_', '/')
    switch ($s.Length % 4) { 2 { $s += '==' } 3 { $s += '=' } }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))
}

function Encode-B64U([string]$s) {
    $b = [Text.Encoding]::UTF8.GetBytes($s)
    return [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Probe([string]$label, [string]$token) {
    ""
    "================================================================="
    "  $label"
    "================================================================="
    if ($token) {
        $p = $token -split '\.'
        $lens = ($p | ForEach-Object { $_.Length }) -join ' / '
        "  parts=$($p.Count)  lengths=$lens"
    }
    $out = & curl.exe -s -i -H "Authorization: Bearer $token" $endpoint 2>&1
    if (-not $out) { $out = @("<empty response>") }
    $out | ForEach-Object { "  | $_" }
}

# --------------------------------------------------------------------------
$live = $null
if (Test-Path "$env:TEMP\zg-token.txt") { $live = (Get-Content "$env:TEMP\zg-token.txt" -Raw).Trim() }
$old = $null
if (Test-Path "$env:TEMP\zg-token-expired.txt") { $old = (Get-Content "$env:TEMP\zg-token-expired.txt" -Raw).Trim() }

if (-not $live) { "FATAL: no live token at %TEMP%\zg-token.txt"; exit 1 }

$p = $live -split '\.'
$hdr = Decode-B64U $p[0]
$pl = Decode-B64U $p[1]

"### LIVE TOKEN HEADER  : $hdr"
"### LIVE TOKEN PAYLOAD : $pl"

# --- CASE A: control, untouched ------------------------------------------
Probe "CASE A  control - original valid token" $live

# --- CASE B: payload tampered, signature kept ----------------------------
$pl2 = $pl -replace '"uid":\d+', '"uid":2' -replace '"sub":"\d+"', '"sub":"2"'
$tokB = "$($p[0]).$(Encode-B64U $pl2).$($p[2])"
"### TAMPERED PAYLOAD   : $pl2"
Probe "CASE B  uid rewritten to 2, SIGNATURE LEFT UNTOUCHED" $tokB

# --- CASE C: signature tampered -----------------------------------------
$sigC = $p[2].Substring(0, $p[2].Length - 6) + "AAAAAA"
$tokC = "$($p[0]).$($p[1]).$sigC"
Probe "CASE C  signature last 6 chars overwritten" $tokC

# --- CASE D: classic alg=none forgery -----------------------------------
$tokD = "$(Encode-B64U '{"alg":"none"}').$($p[1])."
Probe "CASE D  classic alg=none forgery (empty signature)" $tokD

# --- CASE E: correctly signed but EXPIRED -------------------------------
if ($old) {
    $po = $old -split '\.'
    "### EXPIRED PAYLOAD    : $(Decode-B64U $po[1])"
    Probe "CASE E  genuine signature, token already past exp" $old
}

# --- CASE F: no Authorization header at all -----------------------------
Probe "CASE F  no Authorization header (baseline)" $null

# --- CASE G: garbage token ---------------------------------------------
Probe "CASE G  garbage string as token" "this.is.notajwt"

""
"================================================================="
"  done"
"================================================================="
