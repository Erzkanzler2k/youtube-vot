# AGENTS.md — YouTube VoT

## Project shape
- This is a single native Android `Activity` that hosts YouTube Mobile in a `WebView`; there is no Gradle project, AndroidX, dependency manifest, or local JS build system.
- `src/java/com/vot/youtube/MainActivity.java` is the main entrypoint and contains the WebView setup, navigation, ad filtering, settings, downloads, and update flow.
- `src/res/layout/activity_main.xml` defines the shell; `src/res/values/colors.xml` uses semantic dark-theme tokens, with an Android 12+ override in `src/res/values-v31/styles.xml`.

## WebView assets
- `MainActivity` injects `src/assets/vot/bootstrap.js` and then `src/assets/vot/vot.user.js` on each page start; preserve that order and the `vot/` asset paths.
- `bootstrap.js` provides the minimal `GM_*` shims, localStorage defaults, forced `autoSubtitles=false`, and cosmetic ad/consent blocking. The main bundle is a checked-in bundled userscript with upstream source metadata, not a build output from this repository.

## Bypass transport
- `src/java/com/vot/youtube/BypassVpnService.java` is the Android TUN entrypoint; the working path is native `hev-socks5-tunnel` -> local ByeDPI SOCKS5, not the legacy `BypassEngine`/`TcpFlow` classes.
- `src/cpp/byedpi` and `src/cpp/hev-socks5-tunnel` are pinned git submodules; run `git submodule update --init --recursive` after checkout and do not casually rebase or edit upstream sources.
- The native bypass must keep the app process outside the TUN so the local proxy does not loop back into itself. The VPN captures other traffic (including the WebView isolated renderer); the app's own UID is explicitly excluded with `addDisallowedApplication`. Do not replace this with an allowlist: isolated WebView renderer UIDs are not covered by `addAllowedApplication`.
- `src/java/com/vot/youtube/BypassStrategy.java` holds the desync strategies. They are byedpi CLI flag sets taken from the public `CherretGit/zaprett-repo` catalog; no zaprett code is copied, and `hev-socks5-tunnel`/`byedpi` are MIT. Do not vendor zaprett-app (GPL-3.0) or `egor-white/zaprett` (GPL-2.0, Magisk-only) code into this project.
- The real zapret (`bol-van/zapret`, `nfqws`) cannot run rootless on Android: it needs `netfilter_queue` plus root. Any request to "add real zapret" must be answered with this constraint rather than an attempted port.
- The host list is owned by `BypassVpnService.BYPASS_HOSTS` and passed before strategy args because byedpi only honors the first `-H`; `BypassStrategy.getArgs()` strips strategy host lists for that reason.
- Never measure the bypass from the app process. It is excluded from the TUN, so its sockets bypass desync and always look healthy. `BypassProbeService` runs as `android:isolatedProcess="true"` so its own UID reaches the TUN. This is the riskiest assumption in the bypass: confirm it on a device before trusting any verdict.
- Strategy selection is measured, not configured: quick probe of the current strategy, then a two-step ladder (previous winner, default), then a full sweep with cooldown. Only the full sweep cools down; the cheap steps never do, because they are what earns the right to say "not working".
- Thresholds in `BypassProbe` are uncalibrated. Do not invent numbers; they come from measurements in blocked networks.
- `BypassVpnService` must not set DNS servers: the device uses its own network DNS, and forcing a resolver breaks every other app when that resolver is unreachable. There is no DNS loop to avoid because the app process is outside the TUN.
- Keep UDP disabled (`udp: 'off'` in the tunnel config, `--no-udp` in byedpi). byedpi desyncs TLS only, while YouTube in WebView tries QUIC first, so an open UDP path yields a green test and a black screen.

## Build and verify
- Run from the repository root: `powershell -ExecutionPolicy Bypass -File .\\build.ps1`.
- The build requires JDK 11+ (CI uses JDK 17; Java source/target is 8) and Android SDK build-tools `34.0.0` plus platform `android-35`; the script reads `ANDROID_SDK_ROOT` or falls back to `C:\\Temp\\opencode\\android-sdk`.
- The manual pipeline is `ndk-build (bypass) -> aapt2 -> javac -> d8 -> ZipFix -> zipalign -> apksigner`; on the `bypass` branch it also requires an Android NDK and initialized submodules.
- Keep `tools/ZipFix.java` in this pipeline: Windows `aapt2` can produce backslashes in asset entry names, and the APK must be repacked with normalized ZIP paths, `classes.dex`, and native `lib/<abi>/*.so` entries. `ZipFix` must be recompiled on every build: a stale `build/ZipFix.class` silently produces an APK with no native libraries or with `lib/lib/<abi>/...` paths.
- Without an NDK you can still verify the non-native part by running the `aapt2 -> javac -> d8 -> ZipFix -> zipalign` steps manually and reusing the previous release's `lib/<abi>/*.so`; the result is unsigned because release keys are not present.
- There is no repository test, lint, typecheck, or formatting command. For source changes, run the build and manually exercise the affected WebView, settings, download, and update paths on an Android device/emulator.
- `build/` is ignored; `out/YouTubeVot.apk` is the tracked release artifact and is intentionally regenerated by the build.

## Release and signing
- `.github/workflows/build-release.yml` runs the same PowerShell build on `windows-latest`; `workflow_dispatch` only builds, while a pushed `v*` tag also creates a GitHub Release.
- Keep `src/AndroidManifest.xml` `versionCode`/`versionName`, `release-manifest.json` `tag`/APK URL, and the matching release notes synchronized when cutting a release; the app uses the manifest as its jsDelivr fallback source.
- Release signing uses a private keystore supplied through `VOT_KEYSTORE`, `VOT_KEYSTORE_PASSWORD`, `VOT_KEY_ALIAS` and `VOT_KEY_PASSWORD`; no debug-signing fallback is allowed. Never commit the release keystore or its credentials.
