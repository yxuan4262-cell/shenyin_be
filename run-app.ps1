# ---------------------------------------------------------------------------
#  ZhiGuang backend launcher  (compile + run, output -> %TEMP%\zg-run.log)
#
#  Usage (from the project dir):
#     powershell -NoProfile -ExecutionPolicy Bypass -File .\run-app.ps1
#
#  Or detached, so it survives the calling terminal being closed:
#     Start-Process powershell.exe -ArgumentList @("-NoProfile","-ExecutionPolicy","Bypass","-File",".\run-app.ps1")
#
#  Stop it:
#     Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
#
#  NOTE: this file is intentionally ASCII-only. PowerShell 5.1 reads .ps1
#        files without a BOM as ANSI, so Chinese comments here would turn
#        into mojibake. $PSScriptRoot is used instead of a hardcoded path
#        because the project directory name contains non-ASCII characters.
# ---------------------------------------------------------------------------

$ErrorActionPreference = "Continue"

$mvn  = "C:\tools\apache-maven-3.9.16\bin\mvn.cmd"
$proj = $PSScriptRoot
$log  = Join-Path $env:TEMP "zg-run.log"

if (-not (Test-Path $mvn)) {
    "FATAL: maven not found at $mvn" | Out-File -FilePath $log -Encoding UTF8
    exit 1
}

Set-Location $proj

"=== run-app.ps1 started at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===" | Out-File -FilePath $log -Encoding UTF8
"=== cwd = $((Get-Location).Path) ==="                                    | Out-File -FilePath $log -Encoding UTF8 -Append
"=== mvn = $mvn ==="                                                       | Out-File -FilePath $log -Encoding UTF8 -Append
"=== java.home = $env:JAVA_HOME ==="                                       | Out-File -FilePath $log -Encoding UTF8 -Append
""                                                                         | Out-File -FilePath $log -Encoding UTF8 -Append

# *>&1 merges all streams (including stderr) so compile errors land in the log too.
& $mvn spring-boot:run *>&1 | Out-File -FilePath $log -Encoding UTF8 -Append
