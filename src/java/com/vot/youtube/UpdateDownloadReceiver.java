package com.vot.youtube;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Приёмник завершения загрузки APK через DownloadManager.
 *
 * <p>На Android 8+ (API 26) динамически зарегистрированный receiver не получает
 * implicit-броадкаст {@link DownloadManager#ACTION_DOWNLOAD_COMPLETE} от фонового
 * сервиса загрузок — только объявленные в манифесте. Без этого receiver'а загрузка
 * обновления завершалась, но установщик так и не открывался.
 *
 * <p>Receiver merely переадресует событие в MainActivity (явным Intent), где живут
 * проверка подписи и запуск PackageInstaller. Если приложение свёрнуто, запуск
 * Activity может быть заблокирован системой — тогда пользователь установит
 * обновление по клику на системное уведомление о загрузке.
 */
public class UpdateDownloadReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (id < 0) return;

        // Реагируем только на собственную загрузку: чужие загрузки в системе
        // тоже шлют этот броадкаст.
        SharedPreferences prefs = context.getSharedPreferences(
                BypassVpnService.PREFS_BYPASS, Context.MODE_PRIVATE);
        long expected = prefs.getLong(MainActivity.PREF_DOWNLOAD_ID, -1L);
        if (expected != id) return;
        prefs.edit().putLong(MainActivity.PREF_DOWNLOAD_ID, -1L).apply();

        Intent next = new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_HANDLE_DOWNLOAD)
                .putExtra(MainActivity.EXTRA_DOWNLOAD_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(next);
        } catch (Exception ignored) {
            // Фон в приложении свёрнут и система запретила запуск Activity —
            // установка доступна из системного уведомления о загрузке.
        }
    }
}
