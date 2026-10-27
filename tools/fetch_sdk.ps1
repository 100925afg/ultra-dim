# UltraDim - download a minimal Android SDK (for offline builds)
#
# Only build-tools 35.0.1 and platform 35 (android.jar) are needed,
# roughly 130 MB in total - no Android Studio required.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch_sdk.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch_sdk.ps1 -UseHttps
#
# Notes:
#   - Defaults to HTTP because dl.google.com HTTPS may be intercepted on some
#     networks while plain HTTP still works.
#   - Extracts into .sdk\ using the standard Android SDK layout.

[CmdletBinding()]
param(
    [string]$SdkDir = "",
    [switch]$UseHttps,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$Proj = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($SdkDir)) { $SdkDir = Join-Path $Proj ".sdk" }

$scheme = if ($UseHttps) { "https" } else { "http" }
$base = "$scheme://dl.google.com/android/repository/"

$targets = @(
    @{ File = "build-tools_r35.0.1_windows.zip"; Zip = "build-tools.zip";    Dest = "build-tools"; Top = "android-15"; Sub = "35.0.1" },
    @{ File = "platform-35_r02.zip";            Zip = "platform-35.zip";    Dest = "platforms";   Top = "android-35"; Sub = "android-35" },
    @{ File = "platform-tools_r37.0.1-win.zip"; Zip = "platform-tools.zip"; Dest = "";            Top = "platform-tools"; Sub = "platform-tools" }
)

New-Item -ItemType Directory -Force -Path $SdkDir | Out-Null

if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
    throw "tar.exe not found (bundled with Windows 10+), cannot extract zip"
}

foreach ($t in $targets) {
    $zipPath = Join-Path $SdkDir $t.Zip
    if ($t.Sub -eq "35.0.1") { $doneMarker = Join-Path $SdkDir "build-tools\35.0.1\aapt2.exe" }
    elseif ($t.Sub -eq "android-35") { $doneMarker = Join-Path $SdkDir "platforms\android-35\android.jar" }
    else { $doneMarker = Join-Path $SdkDir "platform-tools\adb.exe" }

    if ((Test-Path $doneMarker) -and -not $Force) {
        Write-Host "Already present, skipping: $doneMarker" -ForegroundColor DarkGray
        continue
    }

    if (-not (Test-Path $zipPath) -or $Force) {
        Write-Host "Downloading $($t.File) ..." -ForegroundColor Cyan
        curl.exe -sS -L --fail -m 1800 -o $zipPath ($base + $t.File)
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($t.File)" }
    }
    Write-Host ("  done: {0} MB" -f [math]::Round((Get-Item $zipPath).Length / 1MB, 1))

    Write-Host "Extracting into .sdk ..." -ForegroundColor Cyan
    if ($t.Dest -eq "") {
        tar.exe -xf $zipPath -C $SdkDir
    } else {
        $destDir = Join-Path $SdkDir $t.Dest
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        tar.exe -xf $zipPath -C $destDir
        $topDir = Join-Path $destDir $t.Top
        $finalDir = Join-Path $destDir $t.Sub
        if ((Test-Path $topDir) -and -not (Test-Path $finalDir)) {
            Move-Item $topDir $finalDir
        }
    }
}

Write-Host ""
Write-Host "SDK ready: $SdkDir" -ForegroundColor Green
Get-ChildItem $SdkDir -Directory | ForEach-Object { Write-Host "  $($_.Name)" }
Write-Host ""
Write-Host "Now build: powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1" -ForegroundColor Yellow
