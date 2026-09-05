param(
    [Parameter(Position = 0)]
    [string]$Apk,

    [switch]$FromGitHub,
    [switch]$Wait
)

$ErrorActionPreference = "Stop"
$Package = "com.kododake.aabrowser"
$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ExtractDir = Join-Path $env:TEMP "aabrowser-apk-install"
$WorkflowFile = "build-apk.yml"

function Find-Adb {
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        "C:\Android\platform-tools\adb.exe",
        "C:\Users\Adam\Desktop\Apps\Android\platform-tools\adb.exe"
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

function Get-GitHubRepo {
    $url = git -C $RepoRoot remote get-url origin 2>$null
    if ($url -match "github\.com[:/](.+?)(\.git)?$") {
        return $Matches[1]
    }
    return "adam-skelton/AABrowser"
}

function Download-ApkFromGitHub {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $gh) {
        throw "GitHub CLI (gh) is not installed. Install it, run 'gh auth login', or download the APK from Actions yourself."
    }

    $repo = Get-GitHubRepo
    Write-Host "GitHub repo: $repo"
    Write-Host "Looking up APK workflow runs..."

    $runJson = gh run list --repo $repo --workflow $WorkflowFile --branch main --limit 1 --json databaseId,status,conclusion,displayTitle,url,headSha,updatedAt
    $run = $runJson | ConvertFrom-Json | Select-Object -First 1
    if (-not $run) {
        throw "No Build Android APK runs found on main yet. Push an app change first, or run the workflow from the Actions tab."
    }

    Write-Host "Processing run: $($run.displayTitle)"
    Write-Host "URL: $($run.url)"
    Write-Host "Status: $($run.status) $($run.conclusion)"

    if ($Wait -and $run.status -ne "completed") {
        Write-Host "Waiting for the build to finish..."
        gh run watch $run.databaseId --repo $repo --exit-status
        if ($LASTEXITCODE -ne 0) {
            throw "APK build failed. See $($run.url)"
        }
        $runJson = gh run view $run.databaseId --repo $repo --json databaseId,status,conclusion,displayTitle,url
        $run = $runJson | ConvertFrom-Json
    }

    if ($run.status -ne "completed" -or $run.conclusion -ne "success") {
        throw "Latest APK build is not successful yet (status=$($run.status) conclusion=$($run.conclusion)). Re-run with -FromGitHub -Wait, or wait for Actions to finish."
    }

    if (Test-Path $ExtractDir) {
        Remove-Item $ExtractDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $ExtractDir | Out-Null
    Write-Host "Downloading artifact apk-archive to $ExtractDir"
    gh run download $run.databaseId --repo $repo -n apk-archive -D $ExtractDir
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to download apk-archive from GitHub."
    }

    $apks = Get-ChildItem -Path $ExtractDir -Filter *.apk -File -Recurse -ErrorAction SilentlyContinue
    if (-not $apks) {
        throw "Downloaded artifact did not contain an APK."
    }

    $preferred = $apks |
        Where-Object { $_.DirectoryName -match '\\apk\\debug$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $preferred) {
        $preferred = $apks | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }

    Write-Host "Found APK from GitHub: $($preferred.FullName)"
    return $preferred.FullName
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

if ($FromGitHub) {
    $Apk = Download-ApkFromGitHub
} else {
    $Apk = Resolve-InstallApk $Apk
}
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
