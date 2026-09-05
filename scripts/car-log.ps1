param(
    [switch]$Clear
)

$ErrorActionPreference = "Stop"
$Package = "com.kododake.aabrowser"

function Find-Adb {
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        "C:\Users\Adam\Desktop\Apps\Android\platform-tools\adb.exe"
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }
    throw "adb not found. Add platform-tools to PATH."
}

$adb = Find-Adb
Write-Host "adb: $adb"

if ($Clear) {
    & $adb logcat -c
    Write-Host "Cleared logcat. Reproduce the error, then run this script again without -Clear."
    return
}

Write-Host "Dumping recent AABrowser / crash lines. Reproduce the error first if this is empty."
Write-Host ""

& $adb logcat -d -v time -t 800 AndroidRuntime:E FATAL:E AABrowserCar:V CAR.APP:V androidx.car.app:V com.kododake.aabrowser:V *:S
Write-Host ""
Write-Host "---- unfiltered fatal / exception lines ----"
& $adb logcat -d -v time -t 1500 | Select-String -Pattern "AndroidRuntime|FATAL EXCEPTION|AABrowser|kododake|CarApp|IllegalState|IllegalArgument|NavigationTemplate" | Select-Object -Last 80
