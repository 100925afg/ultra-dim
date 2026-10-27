# UltraDim - offline build script (no Gradle, no network)
#
# Requirements:
#   - JDK 17 (javac / keytool / apksigner need JAVA_HOME on PATH)
#   - .sdk directory (build-tools 35.0.1 + platforms/android-35/android.jar)
#     Run tools\fetch_sdk.ps1 first if missing.
#   - Python 3 (only used to insert classes.dex into the APK)
#
# Usage (from the project root, Windows PowerShell 5.1 is fine):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1 -VersionName 1.1 -VersionCode 2
#
# Output: dist\UltraDim-<version>.apk (zipaligned + signed)

[CmdletBinding()]
param(
    [string]$SdkDir = "",
    [string]$VersionName = "1.0",
    [int]$VersionCode = 1,
    [switch]$SkipSign
)

$ErrorActionPreference = 'Stop'

$Proj = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($SdkDir)) { $SdkDir = Join-Path $Proj ".sdk" }

$Main      = Join-Path $Proj "app\src\main"
$ResDir    = Join-Path $Main "res"
$JavaDir   = Join-Path $Main "java"
$Manifest  = Join-Path $Main "AndroidManifest.xml"
$BuildDir  = Join-Path $Proj "build"
$DistDir   = Join-Path $Proj "dist"
$Keystore  = Join-Path $Proj "keystore\ultradim.jks"

$Bt         = Join-Path $SdkDir "build-tools\35.0.1"
$AndroidJar = Join-Path $SdkDir "platforms\android-35\android.jar"
$Aapt2      = Join-Path $Bt "aapt2.exe"
$ZipAlign   = Join-Path $Bt "zipalign.exe"
$D8         = Join-Path $Bt "d8.bat"
$ApkSigner  = Join-Path $Bt "apksigner.bat"

function Assert-Path([string]$Path, [string]$Name) {
    if (-not (Test-Path $Path)) { throw "Missing $Name : $Path (run tools\fetch_sdk.ps1)" }
}

function Invoke-Step([string]$Message, [scriptblock]$Action) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "Step failed: $Message" }
}

# ---------- preflight ----------
Assert-Path $Aapt2 "aapt2"
Assert-Path $ZipAlign "zipalign"
Assert-Path $D8 "d8"
Assert-Path $ApkSigner "apksigner"
Assert-Path $AndroidJar "android.jar (platform-35)"
Assert-Path $Manifest "AndroidManifest.xml"
Assert-Path $ResDir "res directory"

if ($null -eq (Get-Command python -ErrorAction SilentlyContinue)) { throw "python not found (needed to pack classes.dex)" }
if ($null -eq (Get-Command javac -ErrorAction SilentlyContinue)) { throw "javac not found (check JAVA_HOME / PATH)" }
if ($null -eq (Get-Command keytool -ErrorAction SilentlyContinue)) { throw "keytool not found (check JAVA_HOME / PATH)" }

if (Test-Path $BuildDir) { Remove-Item $BuildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path `
    (Join-Path $BuildDir "gen"), `
    (Join-Path $BuildDir "classes"), `
    (Join-Path $BuildDir "dex"), `
    $DistDir | Out-Null

# ---------- 1. compile resources ----------
Invoke-Step "aapt2 compile (resources)" {
    & $Aapt2 compile --dir $ResDir -o (Join-Path $BuildDir "res.zip")
}

# ---------- 2. link resources + R.java ----------
$linkArgs = @(
    "link",
    "-o", (Join-Path $BuildDir "base.apk"),
    "-I", $AndroidJar,
    "--manifest", $Manifest,
    "--java", (Join-Path $BuildDir "gen"),
    "--min-sdk-version", "34",
    "--target-sdk-version", "35",
    "--version-code", "$VersionCode",
    "--version-name", "$VersionName",
    "--auto-add-overlay",
    (Join-Path $BuildDir "res.zip")
)
Invoke-Step "aapt2 link (R.java + base.apk)" { & $Aapt2 @linkArgs }

# ---------- 3. javac ----------
$sources = @()
$sources += (Get-ChildItem $JavaDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem (Join-Path $BuildDir "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw "No Java sources found" }

$javacArgs = @("-encoding", "UTF-8", "--release", "11",
               "-classpath", $AndroidJar,
               "-d", (Join-Path $BuildDir "classes")) + $sources

Invoke-Step "javac ($(($sources).Count) .java files)" { & javac @javacArgs }

# ---------- 4. d8 ----------
$classes = @(Get-ChildItem (Join-Path $BuildDir "classes") -Recurse -Filter *.class |
    ForEach-Object { $_.FullName })
if ($classes.Count -eq 0) { throw "javac produced no .class files" }

$d8Args = @("--release", "--min-api", "34", "--lib", $AndroidJar,
            "--output", (Join-Path $BuildDir "dex")) + $classes
Invoke-Step "d8 (classes.dex)" { & $D8 @d8Args }

# ---------- 5. pack classes.dex into APK ----------
$baseApk    = Join-Path $BuildDir "base.apk"
$dexFile    = Join-Path $BuildDir "dex\classes.dex"
$withDexApk = Join-Path $BuildDir "app-unaligned.apk"
Invoke-Step "pack classes.dex into APK" {
    & python (Join-Path $PSScriptRoot "add_dex.py") $baseApk $dexFile $withDexApk
}

# ---------- 6. zipalign ----------
$alignedApk = Join-Path $BuildDir "app-aligned.apk"
Invoke-Step "zipalign 4-byte" { & $ZipAlign -f -p 4 $withDexApk $alignedApk }

# ---------- 7. sign ----------
$outApk = Join-Path $DistDir ("UltraDim-{0}.apk" -f $VersionName)
if (-not $SkipSign) {
    # Keystore password is never hardcoded here (this file is public).
    # Priority: env ULTRADIM_KS_PASS > keystore\keystore.properties > newly generated.
    $KsProps = Join-Path (Split-Path $Keystore) "keystore.properties"
    $ksPass  = $env:ULTRADIM_KS_PASS
    if (-not $ksPass -and (Test-Path $KsProps)) {
        foreach ($line in Get-Content $KsProps) {
            if ($line -match '^\s*kspass\s*=\s*(.+?)\s*$') { $ksPass = $Matches[1] }
        }
    }
    if (-not (Test-Path $Keystore)) {
        if (-not $ksPass) {
            $ksPass = [guid]::NewGuid().ToString("N")
            New-Item -ItemType Directory -Force -Path (Split-Path $Keystore) | Out-Null
            Set-Content -Path $KsProps -Value ("kspass=" + $ksPass) -Encoding ASCII
            Write-Host ("    generated keystore password -> " + $KsProps + " (gitignored)") -ForegroundColor Yellow
        }
        Invoke-Step "generate signing key (first time only)" {
            & keytool -genkeypair -v -keystore $Keystore -alias ultradim `
                -keyalg RSA -keysize 2048 -validity 10000 `
                -storepass $ksPass -keypass $ksPass `
                -dname "CN=UltraDim,O=UltraDim,C=CN" -noprompt
        }
    }
    if (-not $ksPass) {
        throw ("Keystore exists but password is unknown: set ULTRADIM_KS_PASS or create " + $KsProps)
    }
    Invoke-Step "apksigner sign" {
        & $ApkSigner sign --ks $Keystore --ks-key-alias ultradim `
            --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
            --min-sdk-version 34 `
            --out $outApk $alignedApk
    }
    Invoke-Step "verify signature" { & $ApkSigner verify --min-sdk-version 34 --print-certs $outApk }
} else {
    Copy-Item $alignedApk $outApk -Force
}

# ---------- 8. summary ----------
Write-Host ""
Write-Host "==> Output: $outApk" -ForegroundColor Green
Write-Host ("    Size: {0} KB" -f [math]::Round((Get-Item $outApk).Length / 1KB, 1))
Write-Host ("    SHA256: " + (Get-FileHash $outApk -Algorithm SHA256).Hash)
Write-Host ""
Write-Host "==> badging summary" -ForegroundColor Cyan
& $Aapt2 dump badging $outApk | Select-String "^package:|^sdkVersion:|^targetSdkVersion:|^application-label:|^launchable-activity:"
Write-Host ""
Write-Host ("Install: .sdk\platform-tools\adb.exe install -r `"" + $outApk + "`"") -ForegroundColor Yellow
