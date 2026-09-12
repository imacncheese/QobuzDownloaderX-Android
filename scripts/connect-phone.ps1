#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Keeps a wireless ADB connection to the test phone, automatically.

.DESCRIPTION
  Handles the whole lifecycle so there is no IP to remember and no manual
  reconnect after a reboot or a network change:

    * If the phone is already reachable over USB, switches it to TCP mode.
    * Otherwise discovers the phone over mDNS (`adb mdns services`), which yields
      the phone's *current* IP and port. Nothing is hardcoded, so a new DHCP lease
      does not break anything.
    * Falls back to a remembered last-known-good address.
    * With -Watch, retries periodically so it reconnects on its own.

  Windows cannot resolve `.local` mDNS names, so the discovered IP:port is used
  rather than the service name.

.EXAMPLE
  pwsh -File scripts/connect-phone.ps1
      Connect once, using discovery. Exits non-zero if the phone is unreachable.

.EXAMPLE
  pwsh -File scripts/connect-phone.ps1 -Setup
      First-time setup with the USB cable plugged in. Switches the phone to TCP
      mode so it never needs the cable again (until it reboots).

.EXAMPLE
  pwsh -File scripts/connect-phone.ps1 -Watch -IntervalSeconds 30
      Stay resident and keep the link alive. Intended for a background job or a
      scheduled task.
#>
[CmdletBinding()]
param(
    # Plug the cable in and pass this once to enable TCP mode on the phone.
    [switch]$Setup,

    # Stay resident, reconnecting whenever the phone comes back.
    [switch]$Watch,
    [int]$IntervalSeconds = 30,

    # Skip discovery and use these directly.
    [string]$Ip = "",
    [int]$Port = 5555,

    # Serial of the USB-attached phone, if more than one is plugged in.
    [string]$UsbSerial = "",

    # Register a Windows scheduled task so the phone reconnects on its own at
    # logon and every few minutes thereafter, surviving app restarts and reboots.
    [switch]$RegisterTask,
    [string]$TaskName = "QbdlxPhoneAdb",
    [int]$TaskEveryMinutes = 5,

    # Remove the scheduled task.
    [switch]$UnregisterTask,

    [switch]$Quiet
)

$ErrorActionPreference = "Continue"

function Get-Adb {
    $candidates = @(
        "C:\Users\Akash\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    throw "adb not found"
}

$adb = Get-Adb
$stateFile = Join-Path $env:TEMP "qbdlx-phone-address.txt"
# The phone model to look for, so the emulator is never mistaken for it.
$targetModel = "shiba"

function Write-Status($message, $colour = "Gray") {
    if (-not $Quiet) { Write-Host $message -ForegroundColor $colour }
}

function Get-ConnectedPhones {
    $lines = & $adb devices -l 2>$null
    $lines | Select-Object -Skip 1 | Where-Object {
        $_ -match '\bdevice\b' -and $_ -match $targetModel
    } | ForEach-Object { ($_ -split '\s+')[0] }
}

function Test-Serial([string]$serial) {
    if (-not $serial) { return $false }
    $state = (& $adb -s $serial get-state 2>&1) -join ""
    return ($state.Trim() -eq "device")
}

function Get-UsbSerial {
    if ($UsbSerial) { return $UsbSerial }
    # USB serials contain no colon; network ones always do.
    Get-ConnectedPhones | Where-Object { $_ -notmatch ':' } | Select-Object -First 1
}

function Get-DiscoveredAddress {
    # adb's mDNS daemon lists the phone's current address, so a new DHCP lease is
    # picked up automatically.
    $services = & $adb mdns services 2>$null
    if (-not $services) { return $null }

    # Prefer the plain _adb._tcp endpoint; fall back to the TLS one.
    foreach ($kind in @('_adb\._tcp', '_adb-tls-connect\._tcp')) {
        $match = $services | Select-String -Pattern $kind | Select-Object -First 1
        if ($match) {
            $value = ($match.Line -split '\s+')[-1].Trim()
            if ($value -match '^\d+\.\d+\.\d+\.\d+:\d+$') { return $value }
        }
    }
    return $null
}

function Connect-Once {
    # Already up?
    $existing = Get-ConnectedPhones | Where-Object { $_ -match ':' } | Select-Object -First 1
    if ($existing -and (Test-Serial $existing)) {
        Write-Status "already connected: $existing"
        return $existing
    }

    # A USB-attached phone is the most reliable path, and lets us enable TCP mode.
    $usb = Get-UsbSerial
    if ($usb) {
        Write-Status "USB device found ($usb), switching it to TCP mode..."
        & $adb -s $usb tcpip $Port 2>&1 | Out-Null
        Start-Sleep -Seconds 4
        $ipFromUsb = (& $adb -s $usb shell ip -f inet addr show wlan0 2>$null) |
            Select-String 'inet ' | Select-Object -First 1
        if ($ipFromUsb) {
            $ipOnly = ($ipFromUsb.Line.Trim() -split '\s+')[1] -replace '/.*', ''
            if ($ipOnly) {
                $addr = "${ipOnly}:${Port}"
                & $adb connect $addr 2>&1 | Out-Null
                Start-Sleep -Seconds 2
                if (Test-Serial $addr) { Set-Content -Path $stateFile -Value $addr; return $addr }
            }
        }
    }

    # Explicit address wins over discovery.
    $candidates = @()
    if ($Ip) { $candidates += "${Ip}:${Port}" }
    $discovered = Get-DiscoveredAddress
    if ($discovered) {
        Write-Status "discovered over mDNS: $discovered"
        $candidates += $discovered
    }
    if (Test-Path $stateFile) {
        $saved = (Get-Content $stateFile -Raw).Trim()
        if ($saved) { $candidates += $saved }
    }

    foreach ($addr in ($candidates | Select-Object -Unique)) {
        if (-not $addr) { continue }
        & $adb connect $addr 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        if (Test-Serial $addr) {
            Set-Content -Path $stateFile -Value $addr
            return $addr
        }
    }
    return $null
}

if ($UnregisterTask) {
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existing) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "Removed scheduled task '$TaskName'." -ForegroundColor Green
    } else {
        Write-Host "No scheduled task named '$TaskName'." -ForegroundColor Yellow
    }
    exit 0
}

if ($RegisterTask) {
    $self = $PSCommandPath
    if (-not $self) { $self = $MyInvocation.MyCommand.Path }

    # Run once at logon and then periodically, so a reboot or a network change
    # reconnects without anyone running anything.
    # Resolve a PowerShell host by absolute path. Task Scheduler cannot resolve
    # the WindowsApps `pwsh` alias, so fall back to the always-present 5.1 host.
    $hostExe = @(
        "$env:ProgramFiles\PowerShell\7\pwsh.exe",
        "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1

    if (-not $hostExe) {
        Write-Host "No PowerShell host found to schedule." -ForegroundColor Red
        exit 1
    }

    $action = New-ScheduledTaskAction -Execute $hostExe `
        -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$self`"" `
        -WorkingDirectory (Split-Path -Parent $self)
    $logon = New-ScheduledTaskTrigger -AtLogOn
    # RepetitionDuration must be a bounded value: TimeSpan::MaxValue serialises to
    # an out-of-range XML duration and the task registration is rejected.
    $repeat = New-ScheduledTaskTrigger -Once -At (Get-Date) `
        -RepetitionInterval (New-TimeSpan -Minutes $TaskEveryMinutes) `
        -RepetitionDuration (New-TimeSpan -Days 3650)
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew

    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    try {
        Register-ScheduledTask -TaskName $TaskName -Action $action `
            -Trigger @($logon, $repeat) -Settings $settings `
            -Description "Keeps a wireless ADB connection to the test Android phone." `
            -ErrorAction Stop | Out-Null
    } catch {
        Write-Host "Could not register the scheduled task: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "The connection itself still works; run this script manually, or start -Watch." -ForegroundColor Yellow
        exit 1
    }

    Write-Host "Registered scheduled task '$TaskName'." -ForegroundColor Green
    Write-Host "  runs at logon and every $TaskEveryMinutes minutes" -ForegroundColor Gray
    Write-Host "  remove with: pwsh -File scripts/connect-phone.ps1 -UnregisterTask" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Running it now to establish the connection..." -ForegroundColor Cyan
    $addr = Connect-Once
    if ($addr) {
        Write-Host "Connected: $addr" -ForegroundColor Green
        exit 0
    }
    Write-Host "Task registered, but the phone is not reachable yet." -ForegroundColor Yellow
    Write-Host "  Plug the cable in once and run with -Setup so TCP mode is enabled." -ForegroundColor Gray
    exit 1
}

if ($Setup) {
    $usb = Get-UsbSerial
    if (-not $usb) {
        Write-Host "No USB device detected. Plug the cable in and re-run with -Setup." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "Enabling TCP mode on $usb ..." -ForegroundColor Cyan
    & $adb -s $usb tcpip $Port
    Start-Sleep -Seconds 4
    Write-Host "Done. You can unplug the cable; the connection now survives until the phone reboots." -ForegroundColor Green
}

if (-not $Watch) {
    $addr = Connect-Once
    if ($addr) {
        $model = & $adb -s $addr shell getprop ro.product.model 2>$null
        $app = & $adb -s $addr shell dumpsys package com.qbdlx.mobile 2>$null |
            Select-String 'versionName' | Select-Object -First 1
        $appVer = if ($app) { ($app.Line -split '=')[1].Trim() } else { "not installed" }
        Write-Host ""
        Write-Host "Connected: $addr" -ForegroundColor Green
        Write-Host "  device: $model" -ForegroundColor Gray
        Write-Host "  app   : QobuzDLX $appVer" -ForegroundColor Gray
        Write-Host ""
        Write-Host "  `$env:ANDROID_SERIAL = `"$addr`"" -ForegroundColor Cyan
        exit 0
    }
    Write-Host "Phone not reachable." -ForegroundColor Yellow
    Write-Host "  * If it just rebooted: plug the cable in and run with -Setup." -ForegroundColor Gray
    Write-Host "  * Enable Developer options -> Wireless debugging on the phone for a" -ForegroundColor Gray
    Write-Host "    connection that survives reboots." -ForegroundColor Gray
    exit 1
}

# ------------------------------------------------------------------- watch
Write-Status "Watching for the phone every ${IntervalSeconds}s (Ctrl+C to stop)." Cyan
$current = $null
while ($true) {
    if ($current -and (Test-Serial $current)) {
        Start-Sleep -Seconds $IntervalSeconds
        continue
    }
    $addr = Connect-Once
    if ($addr) {
        $current = $addr
        Write-Host "$(Get-Date -Format 'HH:mm:ss')  connected: $addr" -ForegroundColor Green
    } else {
        $current = $null
        Write-Status "$(Get-Date -Format 'HH:mm:ss')  phone not reachable, retrying..."
    }
    Start-Sleep -Seconds $IntervalSeconds
}
