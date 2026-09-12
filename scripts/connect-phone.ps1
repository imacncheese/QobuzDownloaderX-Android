#!/usr/bin/env pwsh
# Reconnects to the phone over wireless ADB.
#
# Wireless ADB set up with `adb tcpip 5555` does NOT survive a reboot: the phone
# falls back to USB-only until the cable is plugged in once more. This script
# reconnects when the phone is up, and tells you exactly what to do when it is not.
#
# Usage:
#   pwsh -File scripts/connect-phone.ps1
#   pwsh -File scripts/connect-phone.ps1 -Ip 192.168.4.57

[CmdletBinding()]
param(
    [string]$Ip = "192.168.4.57",
    [int]$Port = 5555,
    # Connect by serial (USB) instead of IP, e.g. for the initial switch.
    [string]$Serial = "",
    # Switch a USB-attached phone into TCP mode first.
    [switch]$EnableTcp
)

$ErrorActionPreference = "Continue"
$adb = "C:\Users\Akash\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { $adb = $found.Source } else { Write-Host "adb not found" -ForegroundColor Red; exit 1 }
}

$target = "${Ip}:${Port}"

function Show-Devices {
    Write-Host "  attached:" -ForegroundColor Gray
    & $adb devices -l | Select-Object -Skip 1 | Where-Object { $_.Trim() } | ForEach-Object {
        Write-Host "    $_" -ForegroundColor Gray
    }
}

# Optional: flip a USB-connected phone into TCP mode.
if ($EnableTcp) {
    $list = & $adb devices | Select-String 'device$'
    $usbSerial = if ($Serial) { $Serial } else {
        ($list | Where-Object { $_ -notmatch ':' } | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1)
    }
    if (-not $usbSerial) {
        Write-Host "No USB device found to switch to TCP mode." -ForegroundColor Yellow
        Write-Host "Plug the cable in, then re-run with -EnableTcp." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "Switching $usbSerial to TCP mode on port $Port..." -ForegroundColor Cyan
    & $adb -s $usbSerial tcpip $Port
    Start-Sleep -Seconds 4
}

Write-Host "Connecting to $target ..." -ForegroundColor Cyan
$result = & $adb connect $target 2>&1
Write-Host "  $result" -ForegroundColor Gray
Start-Sleep -Seconds 2

$connected = & $adb devices | Select-String ([regex]::Escape($target))
if ($connected -and $connected -notmatch 'offline') {
    $model = & $adb -s $target shell getprop ro.product.model 2>&1
    $ver = & $adb -s $target shell getprop ro.build.version.release 2>&1
    $app = & $adb -s $target shell dumpsys package com.qbdlx.mobile 2>&1 |
        Select-String 'versionName' | Select-Object -First 1
    $appVer = if ($app) { ($app.Line -split '=')[1].Trim() } else { "not installed" }

    Write-Host ""
    Write-Host "Connected." -ForegroundColor Green
    Write-Host "  device : $model (Android $ver)" -ForegroundColor Gray
    Write-Host "  app    : QobuzDLX $appVer" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Use it with:" -ForegroundColor Cyan
    Write-Host "  `$env:ANDROID_SERIAL = `"$target`"" -ForegroundColor Gray
    Write-Host "  adb -s $target logcat -s QbdlxApi:* QbdlxAlbum:* QbdlxPlayer:*" -ForegroundColor Gray
    exit 0
}

Write-Host ""
Write-Host "Could not connect." -ForegroundColor Yellow
Show-Devices
Write-Host ""
Write-Host "Most likely cause: the phone rebooted or the Wi-Fi network changed." -ForegroundColor Yellow
Write-Host "Wireless ADB set up with 'adb tcpip' does not survive a reboot." -ForegroundColor Yellow
Write-Host ""
Write-Host "Fix:" -ForegroundColor Cyan
Write-Host "  1. Plug the USB cable in." -ForegroundColor Gray
Write-Host "  2. pwsh -File scripts/connect-phone.ps1 -EnableTcp" -ForegroundColor Gray
Write-Host "  3. Unplug and run it again without -EnableTcp." -ForegroundColor Gray
Write-Host ""
Write-Host "Permanent alternative: enable Developer options -> Wireless debugging on" -ForegroundColor Cyan
Write-Host "the phone. That survives reboots, then use 'adb pair' once." -ForegroundColor Cyan
exit 1
