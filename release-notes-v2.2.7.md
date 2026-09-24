# Что нового в 2.2.7

Сборка с полностью исправленным автообновлением: проверка обновления на jsDelivr →
диалог → скачивание → проверка подписи → установка (проверено на эмуляторе,
Android 14, API 34).

Включает все исправления 2.2.3–2.2.6:
- манифестный receiver `DOWNLOAD_COMPLETE`;
- проверка подписи через `SigningInfo` вместо устаревшего `GET_SIGNATURES`;
- отказ от `setDestinationInExternalFilesDir` (падало на Android 11+);
- `exported="true"` для receiver'а + сверка id загрузки.

---
**YouTube VoT** — YouTube без рекламы и без входа, с закадровым переводом русским голосом. [Подробнее](https://github.com/Erzkanzler2k/youtube-vot)