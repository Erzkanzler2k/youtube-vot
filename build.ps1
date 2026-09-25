# ============================================================================
#  build.ps1 - build YouTube VoT APK manually (no Android Studio / Gradle).
#
#  Pipeline: native NDK (bypass) -> aapt2 (resources) -> javac (Java 8) -> d8
#            -> ZipFix -> zipalign -> apksigner.
#
#  Requirements:
#    * Java 8+ (JDK 11+ recommended: d8/apksigner need Java 11)
#    * Android SDK at one of:
#        - $env:ANDROID_SDK_ROOT
#        - C:\Temp\opencode\android-sdk   (as set up on the build machine)
#      SDK layout: build-tools\34.0.0\ and platforms\android-35\
#    * Android NDK when src\cpp exists: ANDROID_NDK_ROOT or SDK\ndk\<version>
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
$nativeLibDir = $null

foreach ($p in @($bt, $plat)) { if (-not (Test-Path $p)) { throw "SDK dir missing: $p" } }
New-Item -ItemType Directory -Force -Path $out, $build | Out-Null

# --- Native bypass libraries -------------------------------------------------
$nativeRoot = Join-Path $root "src\cpp"
if (Test-Path (Join-Path $nativeRoot "Android.mk")) {
    $ndk = $env:ANDROID_NDK_ROOT
    if (-not $ndk) { $ndk = $env:ANDROID_NDK_HOME }
    if (-not $ndk) {
        $ndkRoot = Join-Path $sdk "ndk"
        if (Test-Path $ndkRoot) {
            $found = Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
            if ($found) { $ndk = $found.FullName }
        }
    }
    $ndkBuild = if ($ndk) { Join-Path $ndk "ndk-build.cmd" } else { "" }
    if (-not $ndk -or -not (Test-Path $ndkBuild)) {
        throw "Android NDK not found. Set ANDROID_NDK_ROOT or install an NDK under $sdk\ndk"
    }
    $nativeObj = Join-Path $build "native-obj"
    $nativeLibDir = Join-Path $build "native-libs"
    New-Item -ItemType Directory -Force -Path $nativeObj, $nativeLibDir | Out-Null
    Write-Host "[1/8] ndk-build (hev-socks5-tunnel)..." -ForegroundColor Cyan
    & $ndkBuild -C (Join-Path $nativeRoot "hev-socks5-tunnel") `
        APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk `
        NDK_OUT=$nativeObj NDK_LIBS_OUT=$nativeLibDir APP_ABI="armeabi-v7a arm64-v8a x86 x86_64"
    if ($LASTEXITCODE -ne 0) { throw "hev-socks5-tunnel native build failed" }
    Write-Host "[1/8] ndk-build (byedpi)..." -ForegroundColor Cyan
    & $ndkBuild -C $nativeRoot `
        APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk `
        NDK_OUT=$nativeObj NDK_LIBS_OUT=$nativeLibDir APP_ABI="armeabi-v7a arm64-v8a x86 x86_64"
    if ($LASTEXITCODE -ne 0) { throw "byedpi native build failed" }
}

# --- 1. Compile resources ---------------------------------------------------
Write-Host "[2/8] aapt2 compile (resources)..." -ForegroundColor Cyan
& "$bt\aapt2.exe" compile --dir (Join-Path $root "src\res") -o (Join-Path $build "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# --- 2. Link (manifest, assets, R.java, resources.arsc) ---------------------
Write-Host "[3/8] aapt2 link (manifest + assets + R.java)..." -ForegroundColor Cyan
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
Write-Host "[4/8] javac (MainActivity + R)..." -ForegroundColor Cyan
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
Write-Host "[5/8] d8 (classes -> classes.dex)..." -ForegroundColor Cyan
$dexDir = Join-Path $build "dex"
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
$classFiles = Get-ChildItem -Recurse $classesDir -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --release --lib (Join-Path $plat "android.jar") --output $dexDir @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# --- 5. Pack classes.dex, native libraries, and normalized ZIP names ---------
Write-Host "[6/8] pack classes.dex + native libraries + normalize zip names..." -ForegroundColor Cyan
$unsigned = Join-Path $build "app-unsigned.apk"
$zipfixClass = Join-Path $build "ZipFix.class"
if (-not (Test-Path $zipfixClass)) {
    & $javac -encoding UTF-8 -nowarn -d $build (Join-Path $root "tools\ZipFix.java")
    if ($LASTEXITCODE -ne 0) { throw "ZipFix compile failed" }
}
$java = Join-Path $jdkBin "java.exe"
$zipArgs = @((Join-Path $build "base.apk"), (Join-Path $dexDir "classes.dex"), $unsigned)
if ($nativeLibDir) { $zipArgs += $nativeLibDir }
& $java -cp $build ZipFix @zipArgs
if ($LASTEXITCODE -ne 0) { throw "ZipFix failed" }

# --- 6. Align ---------------------------------------------------------------
Write-Host "[7/8] zipalign..." -ForegroundColor Cyan
$aligned = Join-Path $build "aligned.apk"
& "$bt\zipalign.exe" -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# --- 7. Sign -----------------------------------------------------------------
Write-Host "[8/8] apksigner (release key, v1+v2+v3)..." -ForegroundColor Cyan
$ks = $env:VOT_KEYSTORE
$ksPass = $env:VOT_KEYSTORE_PASSWORD
$keyAlias = $env:VOT_KEY_ALIAS
$keyPass = $env:VOT_KEY_PASSWORD
if ([string]::IsNullOrWhiteSpace($ks) -or [string]::IsNullOrWhiteSpace($ksPass) -or
    [string]::IsNullOrWhiteSpace($keyAlias) -or [string]::IsNullOrWhiteSpace($keyPass)) {
    throw "Set VOT_KEYSTORE, VOT_KEYSTORE_PASSWORD, VOT_KEY_ALIAS and VOT_KEY_PASSWORD before building"
}
if (-not (Test-Path $ks)) { throw "Release keystore not found: $ks" }
$final = Join-Path $out "YouTubeVot.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias $keyAlias `
    --ks-pass "pass:$ksPass" --key-pass "pass:$keyPass" `
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