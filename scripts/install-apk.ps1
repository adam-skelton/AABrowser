param(
    [Parameter(Position = 0)]
    [string]$Apk,

    [switch]$Wait
)

$ErrorActionPreference = "Stop"
$Package = "com.kododake.aabrowser"
$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ExtractDir = Join-Path $env:TEMP "aabrowser-apk-install"
$ApiBase = "https://api.github.com"
$Repo = "adam-skelton/AABrowser"
$WorkflowFile = "build-apk.yml"

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

function Get-GitHubRepo {
    $url = git -C $RepoRoot remote get-url origin 2>$null
    if ($url -match "github\.com[:/](.+?)(\.git)?$") {
        return $Matches[1]
    }
    return $Repo
}

function Get-GitHubToken {
    if ($env:GITHUB_TOKEN) { return $env:GITHUB_TOKEN }
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($gh) {
        $token = gh auth token 2>$null
        if ($LASTEXITCODE -eq 0 -and $token) { return $token.Trim() }
    }
    return $null
}

function Get-GitHubHeaders([string]$Token) {
    $headers = @{
        Accept        = "application/vnd.github+json"
        "User-Agent"  = "AABrowser-install-apk"
        "X-GitHub-Api-Version" = "2022-11-28"
    }
    if ($Token) {
        $headers.Authorization = "Bearer $Token"
    }
    return $headers
}

function Invoke-GitHubApi {
    param(
        [string]$Url,
        [hashtable]$Headers,
        [string]$OutFile
    )
    $params = @{
        Uri             = $Url
        Headers         = $Headers
        UseBasicParsing = $true
    }
    if ($OutFile) {
        $params.OutFile = $OutFile
    }
    return Invoke-WebRequest @params
}

function Format-TimeAgo([datetime]$When) {
    $span = [DateTime]::UtcNow - $When.ToUniversalTime()
    if ($span.TotalSeconds -lt 45) { return "just now" }
    if ($span.TotalMinutes -lt 1.5) { return "1 minute ago" }
    if ($span.TotalMinutes -lt 60) { return "{0} minutes ago" -f [int]$span.TotalMinutes }
    if ($span.TotalHours -lt 1.5) { return "1 hour ago" }
    if ($span.TotalHours -lt 24) { return "{0} hours ago" -f [int]$span.TotalHours }
    if ($span.TotalDays -lt 1.5) { return "1 day ago" }
    return "{0} days ago" -f [int]$span.TotalDays
}

function Get-CommitTitle([string]$RepoName, [string]$Sha, [hashtable]$Headers) {
    try {
        $response = Invoke-GitHubApi -Url "$ApiBase/repos/$RepoName/commits/$Sha" -Headers $Headers
        $commit = $response.Content | ConvertFrom-Json
        $message = [string]$commit.commit.message
        return ($message -split "(\r\n|\n)")[0]
    } catch {
        return $null
    }
}

function Find-ApkInDir([string]$Dir) {
    $apks = Get-ChildItem -Path $Dir -Filter *.apk -File -Recurse -ErrorAction SilentlyContinue
    if (-not $apks) { return $null }
    $preferred = $apks |
        Where-Object { $_.DirectoryName -match '\\apk\\debug$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($preferred) { return $preferred.FullName }
    return ($apks | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

function Download-ApkFromGitHub {
    $repoName = Get-GitHubRepo
    $token = Get-GitHubToken
    $headers = Get-GitHubHeaders $token

    Write-Host "GitHub repo: $repoName"
    Write-Host "Finding latest APK workflow run..."

    $runsUrl = "$ApiBase/repos/$repoName/actions/workflows/$WorkflowFile/runs?per_page=5"
    $runsResponse = Invoke-GitHubApi -Url $runsUrl -Headers $headers
    $runs = ($runsResponse.Content | ConvertFrom-Json).workflow_runs
    if (-not $runs -or $runs.Count -eq 0) {
        throw "No Build Android APK runs found. See https://github.com/$repoName/actions"
    }

    $run = $runs[0]
    Write-Host "Latest run id: $($run.id)"
    Write-Host "Run URL: $($run.html_url)"

    $when = [datetime]$run.updated_at
    Write-Host "Generated: $(Format-TimeAgo $when) ($($when.ToLocalTime().ToString('yyyy-MM-dd HH:mm')))"

    $commitTitle = Get-CommitTitle $repoName $run.head_sha $headers
    if (-not $commitTitle -and $run.display_title) {
        $commitTitle = [string]$run.display_title
    }
    if ($commitTitle) {
        Write-Host "Commit: $commitTitle"
    }
    if ($run.head_sha) {
        Write-Host "SHA: $($run.head_sha.Substring(0, [Math]::Min(7, $run.head_sha.Length)))"
    }
    Write-Host "Status: $($run.status) $($run.conclusion)"

    if ($Wait -and $run.status -ne "completed") {
        $gh = Get-Command gh -ErrorAction SilentlyContinue
        if (-not $gh) {
            throw "Latest run is still $($run.status). Install GitHub CLI and re-run with -Wait, or wait until it finishes."
        }
        Write-Host "Waiting for the build to finish..."
        gh run watch $run.id --repo $repoName --exit-status
        if ($LASTEXITCODE -ne 0) {
            throw "APK build failed. See $($run.html_url)"
        }
        $runResponse = Invoke-GitHubApi -Url "$ApiBase/repos/$repoName/actions/runs/$($run.id)" -Headers $headers
        $run = $runResponse.Content | ConvertFrom-Json
    }

    if ($run.status -ne "completed" -or $run.conclusion -ne "success") {
        throw "Latest APK build is not successful yet (status=$($run.status) conclusion=$($run.conclusion)). Re-run with -Wait after the Action finishes."
    }

    $artifactsResponse = Invoke-GitHubApi -Url "$ApiBase/repos/$repoName/actions/runs/$($run.id)/artifacts" -Headers $headers
    $artifacts = ($artifactsResponse.Content | ConvertFrom-Json).artifacts
    $artifact = $artifacts | Where-Object { $_.name -eq "apk-archive" } | Select-Object -First 1
    if (-not $artifact) {
        $artifact = $artifacts | Select-Object -First 1
    }
    if (-not $artifact) {
        throw "No artifacts on run $($run.id). See $($run.html_url)"
    }

    $artifactPage = "https://github.com/$repoName/actions/runs/$($run.id)/artifacts/$($artifact.id)"
    Write-Host "Artifact: $($artifact.name)"
    Write-Host "Artifact URL: $artifactPage"

    if (-not $token) {
        throw "GitHub login is required to download artifacts. Run 'gh auth login' once, then try again."
    }

    if (Test-Path $ExtractDir) {
        Remove-Item $ExtractDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $ExtractDir | Out-Null
    $zipPath = Join-Path $ExtractDir "apk-archive.zip"

    Write-Host "Downloading artifact..."
    $downloadHeaders = Get-GitHubHeaders $token
    try {
        Invoke-WebRequest -Uri "$ApiBase/repos/$repoName/actions/artifacts/$($artifact.id)/zip" -Headers $downloadHeaders -OutFile $zipPath -UseBasicParsing
    } catch {
        Write-Host "Direct download failed, retrying with gh..."
        $gh = Get-Command gh -ErrorAction SilentlyContinue
        if (-not $gh) { throw }
        gh run download $run.id --repo $repoName -n $artifact.name -D $ExtractDir
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to download apk-archive from GitHub."
        }
        $apkPath = Find-ApkInDir $ExtractDir
        if (-not $apkPath) {
            throw "Downloaded artifact did not contain an APK."
        }
        Write-Host "Found APK: $apkPath"
        return $apkPath
    }

    Write-Host "Extracting to: $ExtractDir"
    Expand-Archive -LiteralPath $zipPath -DestinationPath $ExtractDir -Force

    $apkPath = Find-ApkInDir $ExtractDir
    if (-not $apkPath) {
        throw "Downloaded artifact did not contain an APK."
    }

    Write-Host "Found APK: $apkPath"
    return $apkPath
}

function Get-ApkFromArchive([string]$ZipPath) {
    Write-Host "Processing archive: $ZipPath"
    if (Test-Path $ExtractDir) {
        Remove-Item $ExtractDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $ExtractDir | Out-Null
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $ExtractDir -Force
    $apkPath = Find-ApkInDir $ExtractDir
    if (-not $apkPath) {
        throw "No APK found inside $ZipPath"
    }
    Write-Host "Found APK in zip: $apkPath"
    return $apkPath
}

$adb = Find-Adb
$serial = Get-ConnectedDevice $adb
Write-Host "Device: $serial"
Write-Host "adb:    $adb"

if ($Apk) {
    if (-not (Test-Path $Apk)) {
        throw "File not found: $Apk"
    }
    $resolved = (Resolve-Path $Apk).Path
    Write-Host "Processing file: $resolved"
    if ($resolved -match '\.zip$') {
        $Apk = Get-ApkFromArchive $resolved
    } else {
        $Apk = $resolved
    }
} else {
    $Apk = Download-ApkFromGitHub
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
