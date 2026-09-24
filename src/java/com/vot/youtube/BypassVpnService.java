package com.vot.youtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import hev.sockstun.TProxyService;

public class BypassVpnService extends VpnService {
    private static final String TAG = "YouTubeVotBypass";

    public static final String ACTION_START = "com.vot.youtube.bypass.START";
    public static final String ACTION_STOP = "com.vot.youtube.bypass.STOP";
    public static final String PREFS_BYPASS = "bypass_prefs";
    public static final String PREF_ENABLED = "bypass_enabled";
    public static final int NOTIF_ID = 42;
    private static final String CHANNEL_ID = "bypass";
    private static final int PROXY_PORT = 1080;
    private static final String[] BYPASS_HOSTS = {
            "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com",
            "googleusercontent.com", "gstatic.com", "googleapis.com",
            "googlesyndication.com", "doubleclick.net", "google.com", "google.ru"
    };
    private static final String[] ALLOWED_PKGS = {
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary"
    };

    private static volatile boolean sActive;
    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private File tunnelConfig;
    private int proxyFd = -1;
    private Thread proxyThread;

    public static boolean isActive() {
        return sActive;
    }

    public static boolean isReady() {
        return ByeDpiNative.isAvailable() && TProxyService.isAvailable();
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
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            tun = establishTun();
            startProxy();
            startTunnel();
            running = true;
            sActive = true;
            setEnabled(true);
        } catch (Exception error) {
            Log.e(TAG, "VPN start failed", error);
            stopTunnel();
            sActive = false;
            setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private ParcelFileDescriptor establishTun() {
        Builder builder = new Builder();
        builder.setSession(getString(R.string.bypass_notif_title));
        builder.addAddress("10.9.0.2", 24);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("1.1.1.1");
        builder.setMtu(1500);
        allowWebViewApps(builder);
        builder.setBlocking(true);
        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) throw new IllegalStateException("VPN establish returned null");
        return descriptor;
    }

    private void allowWebViewApps(Builder builder) {
        boolean added = false;
        for (String packageName : ALLOWED_PKGS) {
            try {
                builder.addAllowedApplication(packageName);
                added = true;
            } catch (Exception ignored) {
            }
        }
        if (!added) throw new IllegalStateException("No WebView provider package available");
    }

    private void startProxy() {
        StringBuilder hosts = new StringBuilder();
        for (String host : BYPASS_HOSTS) {
            if (hosts.length() > 0) hosts.append('\n');
            hosts.append(host);
        }
        String[] args = {
                "byedpi",
                "--ip", "127.0.0.1",
                "--port", String.valueOf(PROXY_PORT),
                "--hosts", ":" + hosts,
                "--auto=torst",
                "--timeout", "3",
                "--split", "0+sm",
                "--tlsrec", "0+sm"
        };
        proxyFd = ByeDpiNative.createSocketWithCommandLine(args);
        if (proxyFd < 0) throw new IllegalStateException("ByeDPI proxy socket failed");
        final int fd = proxyFd;
        proxyThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int result = ByeDpiNative.startProxy(fd);
                if (result != 0 && running) {
                    Log.e(TAG, "ByeDPI proxy stopped with code " + result);
                    stopSelf();
                }
            }
        }, "byedpi-proxy");
        proxyThread.setDaemon(true);
        proxyThread.start();
    }

    private void startTunnel() throws Exception {
        tunnelConfig = new File(getCacheDir(), "hev-socks5-tunnel.yml");
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(tunnelConfig), "UTF-8"));
        writer.print("tunnel:\n");
        writer.print("  name: tun0\n");
        writer.print("  mtu: 1500\n");
        writer.print("  ipv4: 10.9.0.2\n");
        writer.print("  icmp: 'off'\n");
        writer.print("socks5:\n");
        writer.print("  mtu: 1500\n");
        writer.print("  address: 127.0.0.1\n");
        writer.print("  port: " + PROXY_PORT + "\n");
        writer.print("  udp: 'udp'\n");
        writer.print("misc:\n");
        writer.print("  task-stack-size: 81920\n");
        writer.close();
        if (!TProxyService.TProxyStartService(tunnelConfig.getAbsolutePath(), tun.getFd())) {
            throw new IllegalStateException("tun2socks start failed");
        }
    }

    private void stopTunnel() {
        running = false;
        if (TProxyService.isAvailable()) {
            try {
                TProxyService.TProxyStopService();
            } catch (Throwable ignored) {
            }
        }
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
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle(getString(R.string.bypass_notif_title))
                .setContentText(getString(R.string.bypass_notif_text))
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
