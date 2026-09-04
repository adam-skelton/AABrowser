# Copies app/src/main/assets/web to the connected phone (USB or wireless adb).
#
#   .\scripts\push-web.ps1
#   .\scripts\push-web.ps1 -Watch

param(
    [switch]$Watch,
    [int]$IntervalSeconds = 2
)

$ErrorActionPreference = "Stop"
$package = "com.kododake.aabrowser"
$remote = "/storage/emulated/0/Android/data/$package/files/web/"
$local = (Resolve-Path (Join-Path $PSScriptRoot "..\app\src\main\assets\web")).Path

function Get-AdbDevice {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw "adb not found. Install Android platform-tools and add them to PATH."
    }
    $unauthorized = $false
    foreach ($line in (adb devices)) {
        if ($line -match '^(\S+)\s+device$') {
            return $Matches[1]
        }
        if ($line -match 'unauthorized') {
            $unauthorized = $true
        }
    }
    if ($unauthorized) {
        throw "Phone is unauthorized. Accept the USB/wireless debugging prompt on the phone."
    }
    throw "No phone connected. Plug it in with USB debugging, or use wireless debugging then run this again."
}

function Push-Web {
    param([string]$Serial)
    adb -s $Serial shell mkdir -p $remote
    adb -s $Serial shell rm -f "${remote}live.txt" 2>$null | Out-Null
    adb -s $Serial push "$local\." $remote | Out-Host
    Write-Host "Pushed $local"
    Write-Host "     -> $remote"
    Write-Host "Reload the page on the car screen to pick up changes."
}

$device = Get-AdbDevice
Write-Host "Using device $device"
Push-Web -Serial $device

if ($Watch) {
    Write-Host "Watching $local every $IntervalSeconds s. Ctrl+C to stop."
    while ($true) {
        Start-Sleep -Seconds $IntervalSeconds
        Push-Web -Serial $device
    }
}
