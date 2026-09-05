param(
    [Parameter(Position = 0)]
    [string]$Apk
)

$ErrorActionPreference = "Stop"
$Package = "com.kododake.aabrowser"
$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ExtractDir = Join-Path $env:TEMP "aabrowser-apk-install"

function Find-Adb {
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        "C:\Android\platform-tools\adb.exe"
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }
    throw "adb not found. Install platform-tools and add them to PATH, then plug the phone in with USB debugging on."
}

function Get-ConnectedDevice([string]$Adb) {
    $lines = & $Adb devices | Where-Object { $_ -match "\tdevice$" }
    if (-not $lines) {
        throw "No phone detected. Unlock it, allow USB debugging, and try again."
    }
    if ($lines.Count -gt 1 -and -not $env:ANDROID_SERIAL) {
        throw "More than one device is connected. Unplug extras, or set ANDROID_SERIAL to the serial you want."
    }
    return ($lines[0] -split "\t")[0]
}

function Find-LatestArchive {
    $searchRoots = @(
        (Join-Path $env:USERPROFILE "Downloads"),
        $RepoRoot
    ) | Where-Object { $_ -and (Test-Path $_) }

    $matches = foreach ($root in $searchRoots) {
        Get-ChildItem -Path $root -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^apk-archive( \(\d+\))?\.zip$' }
    }

    return $matches | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Find-LatestLooseApk {
    $searchRoots = @(
        $RepoRoot,
        (Join-Path $RepoRoot "app\build\outputs\apk"),
        (Join-Path $env:USERPROFILE "Downloads")
    ) | Where-Object { $_ -and (Test-Path $_) }

    $matches = foreach ($root in $searchRoots) {
        Get-ChildItem -Path $root -Filter *.apk -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match "(?i)aabrowser|app-debug|app-release" }
    }

    return $matches | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Get-ApkFromArchive([string]$ZipPath) {
    Write-Host "Processing archive: $ZipPath"

    if (Test-Path $ExtractDir) {
        Remove-Item $ExtractDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $ExtractDir | Out-Null
    Write-Host "Extracting to: $ExtractDir"

    Expand-Archive -LiteralPath $ZipPath -DestinationPath $ExtractDir -Force

    $apks = Get-ChildItem -Path $ExtractDir -Filter *.apk -File -Recurse -ErrorAction SilentlyContinue
    if (-not $apks) {
        throw "No APK found inside $ZipPath"
    }

    $preferred = $apks |
        Where-Object { $_.DirectoryName -match '\\apk\\debug$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $preferred) {
        $preferred = $apks | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }

    Write-Host "Found APK in zip: $($preferred.FullName)"
    return $preferred.FullName
}

function Resolve-InstallApk([string]$InputPath) {
    if ($InputPath) {
        if (-not (Test-Path $InputPath)) {
            throw "File not found: $InputPath"
        }
        $resolved = (Resolve-Path $InputPath).Path
        Write-Host "Processing file: $resolved"
        if ($resolved -match '\.zip$') {
            return Get-ApkFromArchive $resolved
        }
        return $resolved
    }

    $archive = Find-LatestArchive
    $loose = Find-LatestLooseApk

    if ($archive -and (-not $loose -or $archive.LastWriteTime -ge $loose.LastWriteTime)) {
        Write-Host "Latest archive: $($archive.FullName) ($($archive.LastWriteTime))"
        return Get-ApkFromArchive $archive.FullName
    }

    if ($loose) {
        Write-Host "Processing APK: $($loose.FullName)"
        return $loose.FullName
    }

    throw "No apk-archive.zip (or apk-archive (N).zip) found in Downloads, and no APK to fall back on."
}

$adb = Find-Adb
$serial = Get-ConnectedDevice $adb
Write-Host "Device: $serial"
Write-Host "adb:    $adb"

$Apk = Resolve-InstallApk $Apk
Write-Host "Installing this APK: $Apk"

Write-Host "Uninstalling $Package..."
& $adb uninstall $Package
if ($LASTEXITCODE -ne 0) {
    Write-Host "App was not installed (or uninstall failed). Continuing with install."
}

Write-Host "Installing..."
& $adb install -r -d -t $Apk
if ($LASTEXITCODE -ne 0) {
    throw "Install failed."
}

Write-Host "Done. $Package is on the phone."
