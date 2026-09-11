# Publishes QobuzDLX to GitHub: creates the repo, pushes main, tags v1.0.4 and
# uploads the APK as a release asset.
#
# Authentication, in order of preference:
#   1. $env:GITHUB_TOKEN            (classic PAT with `repo` scope)
#   2. Windows Credential Manager   (Git Credential Manager, if already signed in)
#   3. GitHub device flow           (prints a code to enter in a browser)
#
# Usage:  pwsh -File scripts/publish.ps1
#         pwsh -File scripts/publish.ps1 -Owner someoneelse -Repo MyRepo

[CmdletBinding()]
param(
    [string]$Owner = "",
    [string]$Repo = "QobuzDownloaderX-Android",
    [string]$Tag = "v1.0.7",
    [switch]$Private
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function Info($m) { Write-Host "  $m" -ForegroundColor Gray }
function Ok($m)   { Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!] $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host "  [x] $m" -ForegroundColor Red; exit 1 }

Write-Host ""

# ---------------------------------------------------------------- 1. git repo
if (-not (Test-Path "$root/.git")) { Die "No git repository at $root" }
# Placeholder identity only; the owner is resolved from the credential later.
if (-not (git config user.name))  { git config user.name  "qbdlx-publisher" }
if (-not (git config user.email)) { git config user.email "qbdlx-publisher@users.noreply.github.com" }
Ok "git repository ready ($(git rev-parse --short HEAD))"

# ------------------------------------------------------------- 2. credentials
function Resolve-Token {
    if ($env:GITHUB_TOKEN) { Info "using `$env:GITHUB_TOKEN"; return $env:GITHUB_TOKEN }
    if ($env:GH_TOKEN)     { Info "using `$env:GH_TOKEN";     return $env:GH_TOKEN }

    # Git Credential Manager may already hold a GitHub login.
    try {
        $input = "protocol=https`nhost=github.com`n`n"
        $out = $input | & git credential fill 2>$null
        $line = $out | Where-Object { $_ -match '^password=' } | Select-Object -First 1
        if ($line) {
            Info "using a credential held by Git Credential Manager"
            return ($line -replace '^password=', '')
        }
    } catch { }

    # Fall back to the OAuth device flow, which needs one browser approval.
    Warn "No token found. Starting GitHub device-code login..."
    try {
        $device = Invoke-RestMethod -Method Post `
            -Uri "https://github.com/login/device/code" `
            -Headers @{ Accept = "application/json" } `
            -Body @{ client_id = $script:ClientId; scope = "repo" }

        Write-Host ""
        Write-Host "  Open:  $($device.verification_uri)" -ForegroundColor Cyan
        Write-Host "  Code:  $($device.user_code)"       -ForegroundColor Cyan
        Write-Host ""
        Warn "Waiting for you to approve in the browser..."

        $deadline = (Get-Date).AddSeconds([int]$device.expires_in)
        $interval = [Math]::Max(5, [int]$device.interval)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds $interval
            $res = Invoke-RestMethod -Method Post `
                -Uri "https://github.com/login/oauth/access_token" `
                -Headers @{ Accept = "application/json" } `
                -Body @{
                    client_id   = $script:ClientId
                    device_code = $device.device_code
                    grant_type  = "urn:ietf:params:oauth:grant-type:device_code"
                }
            if ($res.access_token) { Ok "device login approved"; return $res.access_token }
            if ($res.error -eq "authorization_pending") { continue }
            if ($res.error) { Die "device flow failed: $($res.error) - $($res.error_description)" }
        }
        Die "device code expired before it was approved"
    } catch {
        Die "could not obtain a GitHub token ($($_.Exception.Message)). Set `$env:GITHUB_TOKEN and retry."
    }
}

# The device flow needs an OAuth app client id. There is no public one to
# borrow, so this path only works if the user supplies their own.
$script:ClientId = if ($env:QBdlxOAuthClientId) { $env:QBdlxOAuthClientId } else { "" }
$token = Resolve-Token
if (-not $token) { Die "no usable credential" }

$api = @{
    Authorization          = "Bearer $token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "qbdlx-publish"
}

function Invoke-GitHub {
    param([string]$Method, [string]$Path, $Body)
    $args = @{ Method = $Method; Uri = "https://api.github.com$Path"; Headers = $api }
    if ($Body) {
        $args.Body = ($Body | ConvertTo-Json -Depth 10)
        $args.ContentType = "application/json"
    }
    return Invoke-RestMethod @args
}

# ------------------------------------------------------------- 3. who am I
try {
    $me = Invoke-GitHub -Method Get -Path "/user"
    Ok "authenticated as $($me.login)"
} catch {
    Die "token rejected by GitHub ($($_.Exception.Message))"
}

# Default the target owner to whoever the credential belongs to, so no personal
# account name is baked into the script.
if (-not $Owner) {
    $Owner = $me.login
    Info "target owner defaulted to the authenticated user: $Owner"
}

Write-Host ""
Write-Host "Publishing $Owner/$Repo" -ForegroundColor Cyan
Write-Host ("-" * 60)

# ------------------------------------------------------------- 4. create repo
try {
    $repoInfo = Invoke-GitHub -Method Get -Path "/repos/$Owner/$Repo"
    Ok "repository already exists"
} catch {
    Info "creating repository..."
    $body = @{
        name        = $Repo
        description = "Android client for downloading music from Qobuz, ported from QobuzDownloaderX (QBDLX)."
        private     = [bool]$Private
        has_issues  = $true
        has_wiki    = $false
    }
    $repoInfo = Invoke-GitHub -Method Post -Path "/user/repos" -Body $body
    Ok "created $($repoInfo.full_name)"
    Start-Sleep -Seconds 3
}

$remote = "https://github.com/$Owner/$Repo.git"
$existing = git remote 2>$null
if ($existing -contains "origin") { git remote set-url origin $remote } else { git remote add origin $remote }

# ----------------------------------------------------------------- 5. push
Info "pushing main..."
# Pass the token inline so no interactive prompt is attempted; the URL is not
# persisted because the remote itself is configured without credentials.
$pushUrl = "https://x-access-token:$token@github.com/$Owner/$Repo.git"
git push $pushUrl "HEAD:refs/heads/main" --force 2>&1 | ForEach-Object { Info $_ }
if ($LASTEXITCODE -ne 0) { Die "git push failed" }
Ok "pushed main"

# ------------------------------------------------------------------ 6. tag
if (git tag -l $Tag) { Info "tag $Tag already exists locally" }
else {
    git tag -a $Tag -m "QobuzDLX for Android $Tag"
    Ok "created tag $Tag"
}
git push $pushUrl "refs/tags/$Tag" 2>&1 | ForEach-Object { Info $_ }
Ok "pushed tag $Tag"

# -------------------------------------------------------------- 7. release
$apk = Get-ChildItem "$root/dist" -Filter "*$($Tag.TrimStart('v'))*-release.apk" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $apk) {
    $apk = Get-ChildItem "$root/dist" -Filter "*-release.apk" | Select-Object -First 1
}
if (-not $apk) { Die "no release APK found in dist/" }
Ok "release asset: $($apk.Name) ($([math]::Round($apk.Length/1MB,2)) MB)"

$notesFile = "$root/CHANGELOG.md"
$notes = if (Test-Path $notesFile) { Get-Content $notesFile -Raw } else { "See the repository README." }

try {
    $rel = Invoke-GitHub -Method Get -Path "/repos/$Owner/$Repo/releases/tags/$Tag"
    Ok "release $Tag already exists"
} catch {
    Info "creating release $Tag..."
    $rel = Invoke-GitHub -Method Post -Path "/repos/$Owner/$Repo/releases" -Body @{
        tag_name    = $Tag
        name        = "QobuzDLX for Android $Tag"
        body        = $notes
        draft       = $false
        prerelease  = $false
    }
    Ok "created release $($rel.html_url)"
}

# Uploading with the same name replaces the previous asset.
foreach ($a in @($rel.assets)) {
    if ($a.name -eq $apk.Name) {
        Invoke-GitHub -Method Delete -Path "/repos/$Owner/$Repo/releases/assets/$($a.id)" | Out-Null
        Info "removed previous asset $($a.name)"
    }
}

$uploadUrl = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($rel.id)/assets?name=$($apk.Name)"
Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $api `
    -ContentType "application/vnd.android.package-archive" `
    -InFile $apk.FullName | Out-Null
Ok "uploaded $($apk.Name)"

Write-Host ("-" * 60)
Write-Host "Done." -ForegroundColor Green
Write-Host "  Repository: https://github.com/$Owner/$Repo" -ForegroundColor Cyan
Write-Host "  Release:    https://github.com/$Owner/$Repo/releases/tag/$Tag" -ForegroundColor Cyan
Write-Host ""
