# YouTube VoT — обход блокировок (ветка `bypass`)

Без root приложение использует `VpnService`, чтобы перехватывать только трафик WebView-провайдера. Целевой путь обхода:

```text
WebView provider -> Android TUN -> hev-socks5-tunnel -> ByeDPI SOCKS5 -> сеть
```

Обычный `Service` не может изменить сетевой маршрут приложения без root; `VpnService` нужен именно как способ получить TUN file descriptor.

## Компоненты

- `src/java/com/vot/youtube/BypassVpnService.java` — lifecycle VPN, TUN и запуск транспорта.
- `src/java/com/vot/youtube/ByeDpiNative.java` — JNI-обёртка локального SOCKS5-прокси.
- `src/java/hev/sockstun/TProxyService.java` — JNI-обёртка `hev-socks5-tunnel`.
- `src/cpp/byedpi` — pinned submodule с ByeDPI C core.
- `src/cpp/hev-socks5-tunnel` — pinned submodule с tun2socks и его Android build files.
- `BYPASS.md` не заменяет проверку на устройстве: после изменения нативного транспорта нужно проверить загрузку YouTube, TLS и остановку VPN.

## Сборка

- Инициализировать submodules: `git submodule update --init --recursive`.
- Установить Android NDK и задать `ANDROID_NDK_ROOT` либо использовать `$ANDROID_SDK_ROOT\ndk\<version>`.
- Запустить из корня: `powershell -ExecutionPolicy Bypass -File .\\build.ps1`.
- `build.ps1` собирает native-библиотеки, затем выполняет `aapt2 -> javac -> d8 -> ZipFix -> zipalign -> apksigner`.
- `tools/ZipFix.java` нормализует ZIP entries и добавляет native-библиотеки как `lib/<abi>/*.so`.

## Важные ограничения

- Текущий bypass не должен захватывать собственный UDP/TCP трафик приложения: процесс `com.vot.youtube` исключается из TUN, иначе локальный SOCKS5-прокси зациклится сам на себя.
- Основной WebView должен работать в пакете WebView-провайдера, который есть в `BypassVpnService.ALLOWED_PKGS`.
- Старые `BypassEngine`, `TcpFlow` и `BypassProto` оставлены как legacy-протокольный код и не являются рабочим транспортом приложения.
- Статус проверки на реальном заблокированном домене должен подтверждаться на устройстве; статическая проверка нативных символов и протоколов не заменяет сетевой тест.
