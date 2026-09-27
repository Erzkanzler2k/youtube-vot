package com.vot.youtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import hev.htproxy.TProxyService;

public class BypassVpnService extends VpnService {
    private static final String TAG = "YouTubeVotBypass";

    public static final String ACTION_START = "com.vot.youtube.bypass.START";
    public static final String ACTION_STOP = "com.vot.youtube.bypass.STOP";
    public static final String PREFS_BYPASS = "bypass_prefs";
    public static final String PREF_ENABLED = "bypass_enabled";
    public static final String PREF_STRATEGY = "strategy";
    public static final String PREF_MTU = "mtu";
    public static final String PREF_STATE = "state";
    public static final String PREF_REPORT = "report";
    public static final String PREF_LAST_SWEEP = "last_sweep";

    /** Обход поднят, но ещё не измерен. */
    public static final int STATE_UNVERIFIED = 0;
    /** Идёт измерение. */
    public static final int STATE_VERIFYING = 1;
    /** Измерен и работает в пределах нормы. */
    public static final int STATE_OK = 2;
    /** Измерен и отвечает, но медленно — последний выбор, не первый. */
    public static final int STATE_SLOW = 3;
    /** Не прошла ни одна стратегия: обход выключен по решению приложения. */
    public static final int STATE_FAILED = 4;

    public static final int DEFAULT_MTU = 1400;
    private static final int MIN_MTU = 1280;
    private static final int MAX_MTU = 1500;

    public static final int NOTIF_ID = 42;
    private static final String CHANNEL_ID = "bypass";
    private static final int PROXY_PORT = 1080;
    private static final int PROXY_TIMEOUT_SEC = 3;
    private static final String[] BYPASS_HOSTS = {
            "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com",
            "googleusercontent.com", "gstatic.com", "googleapis.com",
            "googlesyndication.com", "doubleclick.net", "google.com", "google.ru"
    };
    private static final String TUN_ADDRESS_V4 = "10.9.0.2";
    private static final String TUN_ADDRESS_V6 = "fd00:9::2";

    /**
     * Пакеты, которые остаются ЗА пределами VPN.
     *
     * <p>Раньше здесь стоял allowlist ({@code addAllowedApplication}) с
     * WebView-пакетами. Так не работает: allowlist строится по UID пакета, а
     * процесс, который реально грузит страницы
     * ({@code com.google.android.webview:sandboxed_process0}), — изолированный и
     * имеет собственный UID (на проверенном телефоне u0_i9132 при разрешённом
     * 10352). Он в allowlist не попадал, трафик YouTube мимо туннеля уходил
     * напрямую, и обход не давал эффекта: VPN поднят, а пробировать нечего.
     *
     * <p>Поэтому вместо allowlist используем disallowlist: из VPN исключается
     * только наше собственное приложение. Это нужно ещё и для защиты от петли —
     * исходящие сокеты нативного прокси создаются в нашем процессе, и если бы его
     * UID попал в туннель, трафик пошёл бы по кругу.
     *
     * <p>Побочный эффект, который неочевиден и на котором держится всё измерение:
     * сокеты процесса приложения не проходят через десканк, поэтому измерять
     * обход из него нельзя. Замеряет отдельный изолированный сервис
     * ({@link BypassProbeService}), у которого собственный UID и который в
     * disallowlist не значится.
     */
    private static final String[] DISALLOWED_PKGS = {
            "com.vot.youtube"
    };

    private static volatile boolean sActive;
    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private File tunnelConfig;
    private int proxyFd = -1;
    private Thread proxyThread;
    /** Стратегия, под которой поднят текущий прокси. */
    private volatile BypassStrategy activeStrategy;
    /** Защита от гонки: подменять прокси и проверять должен только один поток. */
    private final Object proxyLock = new Object();
    private Thread verifyThread;

    public static boolean isActive() {
        return sActive;
    }

    public static boolean isReady() {
        return ByeDpiNative.isAvailable() && TProxyService.isAvailable();
    }

    /** Выбранная пользователем стратегия десинка. */
    static BypassStrategy getSelectedStrategy(Context context) {
        return BypassStrategy.byId(context.getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE)
                .getString(PREF_STRATEGY, BypassStrategy.ID_DEFAULT));
    }

    /** Состояние по данным измерения, а не по факту нажатия. */
    public static int getState(Context context) {
        return context.getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE)
                .getInt(PREF_STATE, STATE_UNVERIFIED);
    }

    public static int getMtu(Context context) {
        int stored = context.getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE)
                .getInt(PREF_MTU, DEFAULT_MTU);
        return Math.max(MIN_MTU, Math.min(MAX_MTU, stored));
    }

    public static void setMtu(Context context, int mtu) {
        context.getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE).edit()
                .putInt(PREF_MTU, Math.max(MIN_MTU, Math.min(MAX_MTU, mtu))).apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        startForegroundCompat();
        if (!isReady()) {
            sActive = false;
            setEnabled(false);
            setState(STATE_FAILED, null);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            tun = establishTun();
            startProxy(getSelectedStrategy(this));
            startTunnel();
            running = true;
            sActive = true;
            setEnabled(true);
            startVerification();
        } catch (Exception error) {
            Log.e(TAG, "VPN start failed", error);
            stopTunnel();
            sActive = false;
            setEnabled(false);
            setState(STATE_FAILED, null);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private ParcelFileDescriptor establishTun() {
        int mtu = getMtu(this);
        Builder builder = new Builder();
        builder.setSession(getString(R.string.bypass_notif_title));
        builder.addAddress(TUN_ADDRESS_V4, 24);
        // IPv6 в туннеле — иначе AAAA-адреса YouTube уходят напрямую мимо
        // десканка, и приложение врало бы о покрытии трафика. Вердикт по IPv6
        // подтверждается замером, иначе приложение честно откатывается.
        builder.addAddress(TUN_ADDRESS_V6, 128);
        builder.addRoute("0.0.0.0", 0);
        builder.addRoute("::", 0);
        // DNS не задаём намеренно: телефон использует DNS своей сети. Раньше
        // здесь стоял addDnsServer("1.1.1.1"), из-за чего весь телефон
        // резолвил имена через Cloudflare, и недоступность этого резолвера
        // ломала сеть у всех приложений. Петли DNS не было и нет: процесс
        // приложения исключён из туннеля и резолвит напрямую.
        builder.setMtu(mtu);
        excludeSelfFromVpn(builder);
        builder.setBlocking(true);
        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) throw new IllegalStateException("VPN establish returned null");
        return descriptor;
    }

    private void excludeSelfFromVpn(Builder builder) {
        // Исключаем из туннеля только себя — см. комментарий у DISALLOWED_PKGS.
        // Так в туннель попадает изолированный процесс WebView, который
        // allowlist не покрывал.
        boolean excluded = false;
        for (String packageName : DISALLOWED_PKGS) {
            try {
                builder.addDisallowedApplication(packageName);
                excluded = true;
            } catch (Exception e) {
                Log.w(TAG, "disallow failed: " + packageName + " (" + e + ")");
            }
        }
        if (!excluded) throw new IllegalStateException("self package not excluded from VPN");
    }

    private void startProxy(BypassStrategy strategy) {
        if (!ByeDpiNative.isAvailable()) {
            throw new IllegalStateException("ByeDPI native library unavailable");
        }
        StringBuilder hosts = new StringBuilder();
        for (String host : BYPASS_HOSTS) {
            if (hosts.length() > 0) hosts.append('\n');
            hosts.append(host);
        }
        List<String> argv = new ArrayList<String>();
        argv.add("byedpi");
        argv.add("--ip");
        argv.add("127.0.0.1");
        argv.add("--port");
        argv.add(String.valueOf(PROXY_PORT));
        // Список доменов всегда наш и всегда первый: byedpi принимает только
        // первое вхождение -H, а BypassStrategy.getArgs() вырезает списки
        // стратегии, чтобы они не сужали YouTube-профиль.
        argv.add("--hosts");
        argv.add(":" + hosts);
        if (!strategy.hasTimeout()) {
            argv.add("--timeout");
            argv.add(String.valueOf(PROXY_TIMEOUT_SEC));
        }
        // Форсируем TCP. QUIC не десканкается нашими стратегиями (--auto=torst
        // работает только по TLS), а YouTube в WebView сначала идёт именно по
        // QUIC: без этого получаем зелёный тест и чёрный экран.
        if (!strategy.forbidsUdp()) {
            argv.add("--no-udp");
        }
        argv.addAll(strategy.getArgs());
        Log.i(TAG, "byedpi " + strategy.getId() + " -> " + argv);
        int fd = ByeDpiNative.createSocketWithCommandLine(argv.toArray(new String[0]));
        if (fd < 0) throw new IllegalStateException("ByeDPI proxy socket failed");
        proxyFd = fd;
        activeStrategy = strategy;
        startProxyThread(fd);
    }

    private void startProxyThread(int fd) {
        proxyThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int result = ByeDpiNative.startProxy(fd);
                if (result != 0 && running) {
                    // Ненулевой код во время плановой подмены стратегии — норма,
                    // поэтому о неисправности сообщаем только при отсутствии
                    // работающей замены.
                    Log.e(TAG, "ByeDPI proxy stopped with code " + result);
                }
            }
        }, "byedpi-proxy");
        proxyThread.setDaemon(true);
        proxyThread.start();
    }

    /**
     * Меняет стратегию на лету: останавливает текущий прокси и поднимает новый
     * на том же порту. Транспорт при этом не трогаем — подмена десканка не
     * затрагивает трафик остальных приложений, потому что список доменов
     * остаётся нашим и не меняется от стратегии к стратегии.
     *
     * @return true, если прокси работает под новой стратегией.
     */
    private boolean swapProxy(BypassStrategy strategy) {
        synchronized (proxyLock) {
            if (!running) return false;
            stopProxyOnly();
            try {
                startProxy(strategy);
                return proxyFd >= 0;
            } catch (Exception error) {
                Log.e(TAG, "proxy swap to " + strategy.getId() + " failed", error);
                return false;
            }
        }
    }

    private void stopProxyOnly() {
        if (proxyFd >= 0 && ByeDpiNative.isAvailable()) {
            try {
                ByeDpiNative.stopProxy(proxyFd);
            } catch (Throwable ignored) {
            }
            proxyFd = -1;
        }
        if (proxyThread != null) {
            try {
                proxyThread.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            proxyThread = null;
        }
    }

    private void startTunnel() throws Exception {
        int mtu = getMtu(this);
        tunnelConfig = new File(getCacheDir(), "hev-socks5-tunnel.yml");
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(tunnelConfig), "UTF-8"));
        writer.print("tunnel:\n");
        writer.print("  name: tun0\n");
        writer.print("  mtu: " + mtu + "\n");
        writer.print("  ipv4: " + TUN_ADDRESS_V4 + "\n");
        writer.print("  ipv6: '" + TUN_ADDRESS_V6 + "'\n");
        writer.print("  icmp: 'off'\n");
        writer.print("socks5:\n");
        writer.print("  mtu: " + mtu + "\n");
        writer.print("  address: 127.0.0.1\n");
        writer.print("  port: " + PROXY_PORT + "\n");
        // UDP выключен. В конфиге транспорта UDP включается ТОЛЬКО при значении
        // ровно 'udp' (hev-config.c: strcasecmp(udpm, "udp")), любое другое
        // значение оставляет srv.udp_in_udp = 0. Здесь это сделано намеренно:
        // стратегии не десканкают QUIC, поэтому UDP должен отбиваться сразу, а не
        // висеть до таймаута рукопожатия WebView.
        writer.print("  udp: 'off'\n");
        writer.print("misc:\n");
        writer.print("  task-stack-size: 81920\n");
        writer.close();
        // TProxyStartService возвращает void: нативный код регистрирует его с
        // сигнатурой (Ljava/lang/String;I)V, поэтому успех проверить нечем —
        // падение поймаем по отсутствию туннеля и логам.
        TProxyService.TProxyStartService(tunnelConfig.getAbsolutePath(), tun.getFd());
    }

    /* ---------------- Измерение и подбор стратегии ---------------- */

    /**
     * Полный цикл проверки: быстрый замер → лестница → перебор.
     *
     * <p>Быстрый замер делается всегда и без повторов: на большинстве сетей
     * дефолт проходит, и полный перебор там был бы чистым налогом на батарею и
     * мобильный трафик. Лестница — две дешёвые проверки перед дорогим перебором.
     * Остывание действует только на перебор: право сказать «не работает» даёт
     * именно он, и запрещать его нельзя, иначе приложение станет врать в другую
     * сторону.
     */
    private void startVerification() {
        setState(STATE_VERIFYING, null);
        verifyThread = new Thread(new Runnable() {
            @Override
            public void run() {
                verify();
            }
        }, "bypass-verify");
        verifyThread.setDaemon(true);
        verifyThread.start();
    }

    private void verify() {
        BypassStrategy current = activeStrategy != null ? activeStrategy : getSelectedStrategy(this);

        BypassProbe.Result quick = measure(BypassProbe.QUICK_REPEATS);
        if (quick != null && quick.verdict() != BypassProbe.VERDICT_FAIL) {
            accept(quick, current, false);
            return;
        }

        // Лестница: прошлый победитель и стратегия по умолчанию, если это не
        // текущая стратегия. Две дешёвые проверки вместо полного перебора.
        BypassStrategy hypothesis = getSelectedStrategy(this);
        List<BypassStrategy> ladder = new ArrayList<BypassStrategy>();
        if (!hypothesis.getId().equals(current.getId())) ladder.add(hypothesis);
        if (!BypassStrategy.ID_DEFAULT.equals(current.getId())
                && !BypassStrategy.ID_DEFAULT.equals(hypothesis.getId())) {
            ladder.add(BypassStrategy.byId(BypassStrategy.ID_DEFAULT));
        }
        for (BypassStrategy candidate : ladder) {
            if (!swapProxy(candidate)) continue;
            BypassProbe.Result result = measure(BypassProbe.QUICK_REPEATS);
            if (result != null && result.verdict() != BypassProbe.VERDICT_FAIL) {
                remember(candidate);
                accept(result, candidate, false);
                return;
            }
        }

        if (!swapProxy(current)) {
            fail("не удалось поднять прокси ни под одной стратегией");
            return;
        }
        if (!sweep(current)) {
            fail("ни одна стратегия не прошла проверку");
        }
    }

    /**
     * Перебор всех стратегий каталога с выбором победителя.
     *
     * <p>Побеждает наименьшая медиана по худшему хосту; при равенстве —
     * наименее агрессивная стратегия, потому что она вставляет меньше пакетов
     * и меньше рискует. Текущая стратегия уходит только при значимом выигрыше,
     * иначе приложение скачет между стратегиями на шумных замерах.
     */
    private boolean sweep(BypassStrategy current) {
        long lastSweep = getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE)
                .getLong(PREF_LAST_SWEEP, 0L);
        if (lastSweep > 0 && System.currentTimeMillis() - lastSweep
                < BypassProbe.SWEEP_COOLDOWN_MS) {
            return false;
        }

        BypassStrategy[] catalog = BypassStrategy.all();
        long currentMs = Long.MAX_VALUE;
        BypassProbe.Result currentResult = null;
        List<long[]> timings = new ArrayList<long[]>();
        List<BypassStrategy> tried = new ArrayList<BypassStrategy>();

        for (BypassStrategy candidate : catalog) {
            if (!swapProxy(candidate)) continue;
            BypassProbe.Result result = measure(BypassProbe.SWEEP_REPEATS);
            tried.add(candidate);
            if (result == null || result.verdict() == BypassProbe.VERDICT_FAIL) continue;
            long median = result.medianOfWorst();
            if (candidate.getId().equals(current.getId())) {
                currentMs = median;
                currentResult = result;
            }
            timings.add(new long[]{median, candidate.getAggression(), timings.size()});
        }
        if (timings.isEmpty()) return false;
        getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE).edit()
                .putLong(PREF_LAST_SWEEP, System.currentTimeMillis()).apply();

        BypassStrategy best = pickWinner(timings, catalog);
        if (best == null) return false;
        // Значимый выигрыш: лучшая в пределах шума текущей — не повод её менять.
        if (currentResult != null && best.getId().equals(current.getId())) return false;
        long bestMs = bestMedian(timings, best, catalog);
        if (currentResult != null && currentMs < Long.MAX_VALUE
                && bestMs * BypassProbe.SIGNIFICANT_WIN > currentMs) {
            best = current;
        }
        if (!swapProxy(best)) return false;
        BypassProbe.Result finalResult = measure(BypassProbe.QUICK_REPEATS);
        if (finalResult == null || finalResult.verdict() == BypassProbe.VERDICT_FAIL) {
            return false;
        }
        remember(best);
        accept(finalResult, best, !best.getId().equals(current.getId()));
        return true;
    }

    /** Наименьшая медиана по худшему хосту; при равенстве — наименее агрессивная. */
    private BypassStrategy pickWinner(List<long[]> timings, BypassStrategy[] catalog) {
        BypassStrategy best = null;
        long bestMs = Long.MAX_VALUE;
        long bestAggression = Long.MAX_VALUE;
        for (long[] row : timings) {
            long median = row[0];
            long aggression = row[1];
            BypassStrategy candidate = catalog[(int) row[2]];
            if (median < bestMs
                    || (median == bestMs && aggression < bestAggression)) {
                bestMs = median;
                bestAggression = aggression;
                best = candidate;
            }
        }
        return best;
    }

    private long bestMedian(List<long[]> timings, BypassStrategy best, BypassStrategy[] catalog) {
        int index = -1;
        for (int i = 0; i < catalog.length; i++) {
            if (catalog[i].getId().equals(best.getId())) index = i;
        }
        for (long[] row : timings) {
            if (row[2] == index) return row[0];
        }
        return Long.MAX_VALUE;
    }

    private BypassProbe.Result measure(int repeats) {
        return BypassProbe.runBlocking(this, repeats, BypassProbe.HARD_TIMEOUT_MS);
    }

    /** Обход измерен и работает. */
    private void accept(BypassProbe.Result result, BypassStrategy strategy, boolean swept) {
        remember(strategy);
        setState(result.verdict() == BypassProbe.VERDICT_SLOW ? STATE_SLOW : STATE_OK,
                buildReport(result, strategy, swept, null));
        Log.i(TAG, "bypass accepted: " + strategy.getId() + " verdict=" + result.verdict());
    }

    /** Ни одна стратегия не подошла: обход выключается, а не остаётся врёт. */
    private void fail(String reason) {
        Log.e(TAG, "bypass failed: " + reason);
        setState(STATE_FAILED, buildReport(null, null, true, reason));
        running = false;
        stopTunnel();
        sActive = false;
        setEnabled(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void remember(BypassStrategy strategy) {
        getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE).edit()
                .putString(PREF_STRATEGY, strategy.getId()).apply();
    }

    private void setState(int state, String report) {
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE).edit()
                        .putInt(PREF_STATE, state);
        if (report != null) editor.putString(PREF_REPORT, report);
        if (state == STATE_FAILED) editor.remove(PREF_REPORT);
        editor.apply();
    }

    /**
     * Отчёт о провале или медленной работе. Показывает, что именно проверяли и
     * сколько стратегий перебрали: «не работает» бесполезно, а перечень хостов
     * и результатов — уже диагноз.
     */
    private String buildReport(BypassProbe.Result result, BypassStrategy strategy,
                               boolean swept, String reason) {
        try {
            JSONObject json = new JSONObject();
            json.put("reason", reason != null ? reason : "");
            json.put("swept", swept);
            json.put("strategy", strategy != null ? strategy.getId() : "");
            json.put("mtu", getMtu(this));
            if (result != null) {
                JSONArray hosts = new JSONArray();
                for (int i = 0; i < BypassProbe.HOSTS.length; i++) {
                    hosts.put(result.hostMs[i]);
                }
                json.put("hosts", hosts);
                json.put("quicMs", result.quicMs);
                json.put("quicGotData", result.quicGotData);
                json.put("ipv6Ms", result.ipv6Ms);
            }
            return json.toString();
        } catch (JSONException error) {
            Log.w(TAG, "report build failed", error);
            return null;
        }
    }

    private void stopTunnel() {
        running = false;
        if (verifyThread != null) {
            verifyThread.interrupt();
            try {
                verifyThread.join(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            verifyThread = null;
        }
        if (TProxyService.isAvailable()) {
            try {
                TProxyService.TProxyStopService();
            } catch (Throwable ignored) {
            }
        }
        synchronized (proxyLock) {
            stopProxyOnly();
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
        }
        if (tunnelConfig != null) {
            tunnelConfig.delete();
            tunnelConfig = null;
        }
        sActive = false;
        setEnabled(false);
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
    }

    private void setEnabled(boolean enabled) {
        getSharedPreferences(PREFS_BYPASS, MODE_PRIVATE)
                .edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.bypass_notif_title),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = getString(R.string.bypass_notif_text);
        if (getState(this) == STATE_SLOW) {
            text = getString(R.string.bypass_notif_text_slow);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle(getString(R.string.bypass_notif_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
