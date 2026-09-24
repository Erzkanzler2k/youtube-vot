# Что нового в 2.2.4

### 🐛 Исправлено: падение при старте загрузки обновления (Android 11+)
- `DownloadManager.Request.setDestinationInExternalFilesDir()` на Android 11+ (scoped
  storage) бросал `IllegalStateException: Unable to create directory:
  …/Android/data/com.vot.youtube/files/Download`. Исключение летело на UI-потоке
  **до** `enqueue()` и **вне** try/catch — приложение падало, загрузка не стартовала,
  установщик не открывался.
- Теперь свой destination не задаётся: файл складывается в системное хранилище
  DownloadManager, а для проверки подписи копируется в `cacheDir` по `content-URI`.
- Добавлено логирование `YouTubeVotUpdate` на всех этапах установки (скачивание,
  копирование, проверка подписи, запуск установщика).

---
**YouTube VoT** — YouTube без рекламы и без входа, с закадровым переводом русским голосом. [Подробнее](https://github.com/Erzkanzler2k/youtube-vot)