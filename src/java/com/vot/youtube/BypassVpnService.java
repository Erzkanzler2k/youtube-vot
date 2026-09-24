/*
 * BypassVpnService — обход блокировок РКН без root (ветка bypass).
 *
 * Делает через системный Android VpnService: приложение получает разрешение
 * пользователя (системный диалог), поднимает виртуальный сетевой интерфейс
 * TUN и перехватывает трафик. DPI-обход (аналог zapret/ByeDPI) выполняет
 * BypassEngine поверх TUN.
 *
 * Текущий статус (этап 1): инфраструктура готова — разрешение, сервис,
 * foreground-уведомление, TUN, связка с настройками. Сам движок
 * (BypassEngine.isAvailable()) пока выключен, поэтому сервис НЕ поднимает
 * VPN в этом этапе — чтобы недоработанный перехват не отрубил интернет.
 *
 * Без AndroidX: только платформенные API. Java 8-совместимый код.
 */
package com.vot.youtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

public class BypassVpnService extends VpnService {
    private static final String TAG = "YouTubeVotBypass";

    public static final String ACTION_START = "com.vot.youtube.bypass.START";
    public static final String ACTION_STOP = "com.vot.youtube.bypass.STOP";
    public static final String PREFS_BYPASS = "bypass_prefs";
    public static final String PREF_ENABLED = "bypass_enabled";
    public static final int NOTIF_ID = 42;
    private static final String CHANNEL_ID = "bypass";

    private volatile boolean running;
    private BypassEngine engine;

    /** Готов ли движок реально перехватывать трафик (этап 2+). */
    public static boolean isReady() {
        return BypassEngine.isAvailable();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;

        // С API 26 сервис, запущенный через startForegroundService, обязан
        // вызвать startForeground в течение 5 секунд — делаем это сразу.
        startForeground(NOTIF_ID, buildNotification());

        if (!BypassEngine.isAvailable()) {
            // Этап 1: движок ещё не готов — не поднимаем VPN, чтобы не отрубить интернет.
            Log.i(TAG, "engine not ready, refusing to establish VPN");
            setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            ParcelFileDescriptor tun = establishTun();
            engine = new BypassEngine(tun);
            engine.start();
            running = true;
            setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "VPN establish failed", e);
            setEnabled(false);
            stopSelf();
        }
        return START_STICKY;
    }

    /** Создаёт TUN-интерфейс и перехватывает весь трафик устройства. */
    private ParcelFileDescriptor establishTun() throws Exception {
        Builder b = new Builder();
        b.setSession(getString(R.string.bypass_notif_title));
        b.addAddress("10.9.0.2", 24);
        b.addRoute("0.0.0.0", 0);
        b.addDnsServer("77.88.8.8");
        b.addDnsServer("8.8.8.8");
        b.setBlocking(true);
        ParcelFileDescriptor tun = b.establish();
        if (tun == null) throw new IllegalStateException("establish() returned null");
        return tun;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
        setEnabled(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setEnabled(boolean on) {
        getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE)
                .edit().putBoolean(PREF_ENABLED, on).apply();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bypass_notif_title),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.bypass_notif_title))
                .setContentText(getString(R.string.bypass_notif_text))
                .setSmallIcon(R.drawable.ic_settings)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}