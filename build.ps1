# ============================================================================
#  build.ps1 - build YouTube VoT APK manually (no Android Studio / Gradle).
#
#  Pipeline: aapt2 (resources) -> javac (Java 8) -> d8 (dex) -> zipalign
#            -> apksigner (debug key signing).
#
#  Requirements:
#    * Java 8+ (JDK 11+ recommended: d8/apksigner need Java 11)
#    * Android SDK at one of:
#        - $env:ANDROID_SDK_ROOT
#        - C:\Temp\opencode\android-sdk   (as set up on the build machine)
#      SDK layout: build-tools\34.0.0\ and platforms\android-35\
#
#  Output: .\out\YouTubeVot.apk  (signed, ready to install)
# ============================================================================
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# --- Locate SDK and JDK ------------------------------------------------------
$sdk = $null
if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { $sdk = $env:ANDROID_SDK_ROOT }
elseif (Test-Path "C:\Temp\opencode\android-sdk") { $sdk = "C:\Temp\opencode\android-sdk" }
if (-not $sdk) { throw "Android SDK not found. Set ANDROID_SDK_ROOT or unpack SDK into C:\Temp\opencode\android-sdk" }

# JDK: prefer env JAVAC/ANDROID_JDK, else look for a bundled JDK 8/11/17+, else PATH
$javac = $null
if ($env:JAVAC -and (Test-Path $env:JAVAC)) { $javac = $env:JAVAC }
elseif ($env:ANDROID_JDK -and (Test-Path (Join-Path $env:ANDROID_JDK "bin\javac.exe"))) { $javac = Join-Path $env:ANDROID_JDK "bin\javac.exe" }
if (-not $javac) {
    foreach ($candidate in @("C:\Temp\opencode\jdk-17*\bin\javac.exe", "C:\Program Files\Eclipse Adoptium\*\bin\javac.exe", "C:\Program Files\Java\jdk*\bin\javac.exe")) {
        $found = Get-ChildItem $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $javac = $found.FullName; break }
    }
}
if (-not $javac) { throw "javac not found. Install JDK 8+ or set JAVAC env var." }

# d8.bat / apksigner.bat / keytool вызывают "java" из PATH.
# Ставим найденный JDK (17+) в начало PATH, чтобы они не подхватили старую JRE 8.
$jdkBin = Split-Path -Parent $javac
$env:PATH = "$jdkBin;$env:PATH"

$bt    = Join-Path $sdk "build-tools\34.0.0"
$plat  = Join-Path $sdk "platforms\android-35"
$out   = Join-Path $root "out"
$build = Join-Path $root "build"

foreach ($p in @($bt, $plat)) { if (-not (Test-Path $p)) { throw "SDK dir missing: $p" } }
New-Item -ItemType Directory -Force -Path $out, $build | Out-Null

# --- 1. Compile resources ---------------------------------------------------
Write-Host "[1/7] aapt2 compile (resources)..." -ForegroundColor Cyan
& "$bt\aapt2.exe" compile --dir (Join-Path $root "src\res") -o (Join-Path $build "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# --- 2. Link (manifest, assets, R.java, resources.arsc) ---------------------
Write-Host "[2/7] aapt2 link (manifest + assets + R.java)..." -ForegroundColor Cyan
& "$bt\aapt2.exe" link `
    -o (Join-Path $build "base.apk") `
    -I (Join-Path $plat "android.jar") `
    --manifest (Join-Path $root "src\AndroidManifest.xml") `
    --java (Join-Path $build "gen") `
    -A (Join-Path $root "src\assets") `
    --min-sdk-version 24 `
    --target-sdk-version 35 `
    (Join-Path $build "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# --- 3. Compile Java --------------------------------------------------------
Write-Host "[3/7] javac (MainActivity + R)..." -ForegroundColor Cyan
$srcs = @()
$srcs += Get-ChildItem -Recurse (Join-Path $build "gen") -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem -Recurse (Join-Path $root "src\java") -Filter *.java | ForEach-Object { $_.FullName }
$classesDir = Join-Path $build "classes"
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn `
    -classpath (Join-Path $plat "android.jar") `
    -d $classesDir @srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# --- 4. Dex -----------------------------------------------------------------
Write-Host "[4/7] d8 (classes -> classes.dex)..." -ForegroundColor Cyan
$dexDir = Join-Path $build "dex"
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
$classFiles = Get-ChildItem -Recurse $classesDir -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --release --lib (Join-Path $plat "android.jar") --output $dexDir @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# --- 5. Pack classes.dex + normalize entry names (ZipFix, Java) -------------
#   aapt2 на Windows пишет ассеты как "assets/vot\bootstrap.js"; Android
#   может отклонить APK с такими именами при установке. ZipFix переписывает
#   все entry с '/' и дописывает classes.dex стандартным способом.
Write-Host "[5/7] pack classes.dex + normalize zip names..." -ForegroundColor Cyan
$unsigned = Join-Path $build "app-unsigned.apk"
$zipfixClass = Join-Path $build "ZipFix.class"
if (-not (Test-Path $zipfixClass)) {
    & $javac -encoding UTF-8 -nowarn -d $build (Join-Path $root "tools\ZipFix.java")
    if ($LASTEXITCODE -ne 0) { throw "ZipFix compile failed" }
}
$java = Join-Path $jdkBin "java.exe"
& $java -cp $build ZipFix (Join-Path $build "base.apk") (Join-Path $dexDir "classes.dex") $unsigned
if ($LASTEXITCODE -ne 0) { throw "ZipFix failed" }

# --- 6. Align ---------------------------------------------------------------
Write-Host "[6/7] zipalign..." -ForegroundColor Cyan
$aligned = Join-Path $build "aligned.apk"
& "$bt\zipalign.exe" -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# --- 7. Sign -----------------------------------------------------------------
Write-Host "[7/7] apksigner (debug key, v1+v2+v3)..." -ForegroundColor Cyan
$ks  = Join-Path $root "debug.keystore"
$final = Join-Path $out "YouTubeVot.apk"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -v -keystore $ks -alias vot -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass android -keypass android -dname "CN=YouTubeVot,O=VoT,C=RU" | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

# --- Verify ------------------------------------------------------------------
Write-Host "Verifying signature..." -ForegroundColor Cyan
& "$bt\apksigner.bat" verify --verbose $final
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Write-Host "Verifying alignment..." -ForegroundColor Cyan
& "$bt\zipalign.exe" -c 4 $final
if ($LASTEXITCODE -ne 0) { throw "zipalign verify failed" }

$size = [math]::Round((Get-Item $final).Length / 1MB, 2)
Write-Host ""
Write-Host "Done: $final  ($size MB)" -ForegroundColor Green