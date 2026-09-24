/*
 * BypassVpnService — обход блокировок РКН без root (ветка bypass).
 *
 * Делает через системный Android VpnService: приложение получает разрешение
 * пользователя (системный диалог), поднимает виртуальный сетевой интерфейс
 * TUN и перехватывает трафик. DPI-обход (аналог zapret/ByeDPI) выполняет
 * BypassEngine поверх TUN.
 *
 * Статус (этап 2): движок реализован и доступен (BypassEngine.isAvailable()
 * возвращает true). В TUN попадает ТОЛЬКО трафик нашего приложения и
 * WebView-провайдеров (all-disallow + allowlist) — остальные приложения
 * работают как раньше, мимо VPN. Если движок падает — сервис сам
 * останавливается и VPN снимается, интернет остаётся живым.
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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.List;

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

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
    }

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
            // Снимаем VPN СИНХРОННО, не дожидаясь onDestroy: на эмуляторе/ряде
            // систем после прихода stop-команды AMS может не довести сервис до
            // onDestroy (startRequested=false), и без прямого shutdown() здесь
            // tun0 остался бы висеть. shutdown() идемпотентен и закрывает TUN
            // (fd) — система снимает VPN-сеть сразу при закрытии fd.
            Log.i(TAG, "stop cmd id=" + startId);
            running = false;
            if (engine != null) {
                engine.shutdown();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        Log.i(TAG, "start cmd id=" + startId);

        // С API 26 сервис, запущенный через startForegroundService, обязан
        // вызвать startForeground в течение 5 секунд — делаем это сразу.
        // С API 34 (targetSdk 34+) обязателен тип FGS; для VPN-сервиса это
        // specialUse (объявлен в манифесте). Без типа — MissingForegroundServiceTypeException.
        startForegroundCompat();

        if (!BypassEngine.isAvailable()) {
            // Движок должен быть доступен всегда (этап 2+); если нет — не поднимаем VPN.
            Log.i(TAG, "engine not ready, refusing to establish VPN");
            setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            ParcelFileDescriptor tun = establishTun();
            engine = new BypassEngine(tun, this, new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            });
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

    /** Пакеты, чей трафик разрешён в VPN (наше приложение + WebView-провайдеры). */
    private static final String[] ALLOWED_PKGS = {
            "com.vot.youtube",
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary"
    };

    /** Создаёт TUN-интерфейс и перехватывает весь трафик устройства. */
    private ParcelFileDescriptor establishTun() throws Exception {
        Builder b = new Builder();
        b.setSession(getString(R.string.bypass_notif_title));
        b.addAddress("10.9.0.2", 24);
        b.addRoute("0.0.0.0", 0);
        b.addDnsServer("77.88.8.8");
        b.addDnsServer("77.88.8.1");
        // Явный MTU: наш mini-TCP анонсирует клиенту MSS 1400 — пакеты всегда
        // укладываются в 1500, фрагментация по пути в TUN не нужна.
        b.setMtu(1500);
        denyOtherApps(b);
        b.setBlocking(true);
        ParcelFileDescriptor tun = b.establish();
        if (tun == null) throw new IllegalStateException("establish() returned null");
        return tun;
    }

    /** Пропускает через VPN только наше приложение и WebView-провайдеров;
     *  остальные приложения работают мимо VPN — интернет не ломаем. */
    private void denyOtherApps(Builder b) {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : apps) {
            if (isAllowedPkg(ai.packageName)) continue;
            try {
                b.addDisallowedApplication(ai.packageName);
            } catch (Exception ignored) {
                // невалидный пакет — пропускаем
            }
        }
    }

    private boolean isAllowedPkg(String pkg) {
        for (String a : ALLOWED_PKGS) {
            if (a.equals(pkg)) return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy begin");
        running = false;
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
        setEnabled(false);
        super.onDestroy();
        Log.i(TAG, "onDestroy done");
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