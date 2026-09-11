#!/usr/bin/env pwsh
# Fails if anything that looks like a credential is about to be committed.
#
# The Qobuz web-player app_id/app_secret is resolved at runtime on device and must
# never enter the repository. Run this before committing, or install it as a
# pre-commit hook:
#
#   cp scripts/check-secrets.ps1 .git/hooks/pre-commit
#
# Exit codes: 0 = clean, 1 = something suspicious found.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

# Only inspect files that git would actually publish.
$files = git ls-files
if (-not $files) { Write-Host "nothing tracked"; exit 0 }

$patterns = @{
    "GitHub classic token"   = 'ghp_[A-Za-z0-9]{20,}'
    "GitHub fine-grained"    = 'github_pat_[A-Za-z0-9_]{20,}'
    "private key"            = '-----BEGIN [A-Z ]*PRIVATE KEY'
    "AWS access key"         = 'AKIA[0-9A-Z]{16}'
    "Slack token"            = 'xox[baprs]-[A-Za-z0-9-]{10,}'
    "Qobuz app_secret literal" = 'appSecret:"[0-9a-f]{32}"'
    "Qobuz app_id literal"   = 'appId:"\d{6,}"'
    "timezone-table secret"  = '05a4851e74ee47fda346f50cfdfc4f09'
}

$found = 0
foreach ($f in $files) {
    if (-not (Test-Path $f)) { continue }
    $text = Get-Content -Raw -ErrorAction SilentlyContinue $f
    if (-not $text) { continue }
    foreach ($name in $patterns.Keys) {
        foreach ($m in [regex]::Matches($text, $patterns[$name])) {
            # Tests and docs may name the pattern deliberately, with placeholders.
            if ($m.Value -match '<[^>]+>') { continue }
            Write-Host ("  [{0}] {1}: {2}" -f $name, $f, $m.Value.Substring(0, [Math]::Min(40, $m.Value.Length)))
            $found++
        }
    }
}

Write-Host ""
if ($found -gt 0) {
    Write-Host "FAIL: $found credential-looking value(s) in tracked files." -ForegroundColor Red
    Write-Host "The Qobuz app_id/app_secret must be read at runtime, never committed." -ForegroundColor Red
    exit 1
}

Write-Host "OK: no credentials found in $(($files | Measure-Object).Count) tracked files." -ForegroundColor Green
exit 0
