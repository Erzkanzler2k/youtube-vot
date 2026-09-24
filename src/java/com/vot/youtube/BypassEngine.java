/*
 * BypassEngine — движок DPI-обхода поверх TUN (аналог zapret/ByeDPI). Этап 2.
 *
 * Читает сырые IPv4-пакеты из TUN и:
 *  - TCP: перед приложением ведёт себя как TCP-сервер (свой SYN+ACK, свои
 *    seq/ack), наружу открывает обычный Socket с protect(). Первый payload
 *    с порта 443 анализируется: из TLS ClientHello достаётся SNI; если домен
 *    в списке заблокированных — ClientHello уходит фрагментами (разрез внутри
 *    имени хоста), чтобы DPI не увидел SNI в первом сегменте.
 *  - UDP: порт 53 релеится на реальный DNS через DatagramSocket с protect();
 *    остальной UDP (QUIC и пр.) дропаем — браузер откатывается на TCP.
 *  - IPv6 и IP-фрагменты — дропаем (не поддерживаем).
 *
 * isAvailable() == true (этап 2 реализован). Главный принцип: недоработанный
 * перехват НЕ должен отрубать интернет, поэтому если TUN закрылся или write
 * падает — движок сам останавливает сервис (onStop), VPN снимается.
 *
 * Без AndroidX. Java 8-совместимый код.
 */
package com.vot.youtube;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class BypassEngine {
    private static final String TAG = "YouTubeVotBypass";

    /** Домены, у которых ломаем первый ClientHello (суффиксы). */
    public static final String[] BLOCKED_SUFFIXES = {
            "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com",
            "googleusercontent.com", "gstatic.com", "googleapis.com",
            "googlesyndication.com", "doubleclick.net", "google.com", "google.ru"
    };

    /** Адрес TUN, который мы сами назначаем приложению (см. BypassVpnService). */
    private static final int LOCAL_TUN_IP = 0x0A090002; // 10.9.0.2

    private static final long FLOW_IDLE_MS = 120_000L;
    private static final long DNS_IDLE_MS = 60_000L;
    private static final long SWEEP_INTERVAL_MS = 30_000L;

    /** true, когда движок реально перехватывает и пересылает трафик. */
    public static boolean isAvailable() {
        return true;
    }

    private final ParcelFileDescriptor tun;
    private final VpnService vpn;
    private final Runnable onStop;

    private final Object writeLock = new Object();
    private volatile boolean running;
    private volatile boolean stopped;

    private FileInputStream in;
    private FileOutputStream outFs;

    private final Map<Long, TcpFlow> flows = new HashMap<>();
    private final Map<String, DnsRelay> dnsRelays = new HashMap<>();

    public BypassEngine(ParcelFileDescriptor tun, VpnService vpn, Runnable onStop) {
        this.tun = tun;
        this.vpn = vpn;
        this.onStop = onStop;
    }

    public void start() {
        if (running) return;
        running = true;
        in = new FileInputStream(tun.getFileDescriptor());
        outFs = new FileOutputStream(tun.getFileDescriptor());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "bypass-engine");
        t.setDaemon(true);
        t.start();

        Thread sw = new Thread(new Runnable() {
            @Override
            public void run() {
                sweeper();
            }
        }, "bypass-sweeper");
        sw.setDaemon(true);
        sw.start();
    }

    /* ---------------- читающий цикл ---------------- */

    private void loop() {
        byte[] buf = new byte[65535];
        while (running) {
            int n;
            try {
                n = in.read(buf);
            } catch (IOException e) {
                Log.i(TAG, "tun closed: " + e.getMessage());
                break;
            }
            if (n <= 0) continue;

            int off = 0;
            while (off < n) {
                BypassProto.Ip4 ip = BypassProto.parseIp4(buf, off, n);
                if (!ip.ok) {
                    // мусор / IPv6 — выходим из буфера
                    off = n;
                    break;
                }
                if (ip.srcIp == LOCAL_TUN_IP) {
                    off += ip.totalLen;
                    continue;
                }
                if (BypassProto.isIpFragment(buf, off)) {
                    // IP-фрагментацию не поддерживаем — дроп
                    off += ip.totalLen;
                    continue;
                }
                switch (ip.protocol) {
                    case BypassProto.PROTO_TCP:
                        onTcp(buf, ip);
                        break;
                    case BypassProto.PROTO_UDP:
                        onUdp(buf, ip);
                        break;
                    default:
                        break;
                }
                off += ip.totalLen;
            }
        }
        shutdown();
    }

    private void onTcp(byte[] buf, BypassProto.Ip4 ip) {
        BypassProto.Tcp4 t = BypassProto.parseTcp(buf, ip.payloadOff, ip.payloadLen);
        if (!t.ok) return;
        long key = flowKey(ip.srcIp, t.srcPort, t.dstPort);
        TcpFlow f = flows.get(key);
        if (f == null) {
            if (!t.syn || t.ackFlag) return; // пакет без SYN — не наш поток
            f = new TcpFlow(this, vpn, ip.srcIp, ip.dstIp, t.srcPort, t.dstPort);
            flows.put(key, f);
        } else if (f.dstIp != ip.dstIp) {
            // коллизия ключа на разных серверах — пересоздаём
            f.closeFlow();
            flows.remove(key);
            if (!t.syn || t.ackFlag) return;
            f = new TcpFlow(this, vpn, ip.srcIp, ip.dstIp, t.srcPort, t.dstPort);
            flows.put(key, f);
        }
        f.onPacket(t, buf);
        if (f.done) flows.remove(key);
    }

    private void onUdp(byte[] buf, BypassProto.Ip4 ip) {
        BypassProto.Udp4 u = BypassProto.parseUdp(buf, ip.payloadOff, ip.payloadLen);
        if (!u.ok) return;
        if (u.dstPort != 53) return; // QUIC и прочий UDP дропаем — откат на TCP
        String key = BypassProto.ipToString(ip.srcIp) + ":" + u.srcPort + "->"
                + BypassProto.ipToString(ip.dstIp);
        DnsRelay r = dnsRelays.get(key);
        if (r == null) {
            r = new DnsRelay(this, vpn, ip.srcIp, u.srcPort, ip.dstIp);
            if (!r.usable) {
                // не смогли открыть сокет — просто молча дропаем
                return;
            }
            dnsRelays.put(key, r);
            r.startThread();
        }
        r.onQuery(buf, u.payloadOff, u.payloadLen);
    }

    /** Ключ потока. dstIp не влезает в 64 бита — сверяется в TcpFlow. */
    private static long flowKey(int srcIp, int srcPort, int dstPort) {
        return (((long) srcIp) << 32) | ((long) srcPort << 16) | (dstPort & 0xFFFFL);
    }

    /** Пишет готовый IP-пакет в TUN (вызывается из потоков движка). */
    void writeTun(byte[] pkt, int len) {
        if (!running || stopped) return;
        synchronized (writeLock) {
            try {
                outFs.write(pkt, 0, len);
                outFs.flush();
            } catch (IOException e) {
                // TUN закрыт/сломан — двигаться дальше нельзя, иначе интернет умрёт
                fail();
            }
        }
    }

    /* ---------------- вахтёр ---------------- */

    private void sweeper() {
        while (running && !stopped) {
            try {
                Thread.sleep(SWEEP_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<Long, TcpFlow>> it = flows.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, TcpFlow> e = it.next();
                if (e.getValue().isIdle(now, FLOW_IDLE_MS)) {
                    e.getValue().closeFlow();
                    it.remove();
                }
            }
            Iterator<Map.Entry<String, DnsRelay>> dit = dnsRelays.entrySet().iterator();
            while (dit.hasNext()) {
                Map.Entry<String, DnsRelay> e = dit.next();
                if (e.getValue().isIdle(now, DNS_IDLE_MS)) {
                    e.getValue().close();
                    dit.remove();
                }
            }
        }
    }

    /* ---------------- остановка ---------------- */

    /** Штатная остановка (из сервиса) — без onStop. */
    public void shutdown() {
        if (stopped) return;
        stopped = true;
        running = false;
        synchronized (writeLock) {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (outFs != null) {
                try {
                    outFs.close();
                } catch (IOException ignored) {
                }
            }
        }
        for (TcpFlow f : flows.values()) f.closeFlow();
        flows.clear();
        for (DnsRelay r : dnsRelays.values()) r.close();
        dnsRelays.clear();
        try {
            tun.close();
        } catch (IOException ignored) {
        }
    }

    /** Аварийная остановка: движок сам просит сервис снять VPN. */
    private void fail() {
        if (stopped) return;
        Log.e(TAG, "engine failed — requesting VPN shutdown");
        shutdown();
        if (onStop != null) onStop.run();
    }

    /* ---------------- DNS-релей ---------------- */

    private static final class DnsRelay implements Runnable {
        private final BypassEngine engine;
        private final VpnService vpn;
        private final int cliIp;
        private final int cliPort;
        private final int dnsIp;
        private final DatagramSocket sock;
        private final boolean usable;
        private volatile boolean running = true;
        private long lastActive = System.currentTimeMillis();
        private Thread thread;

        DnsRelay(BypassEngine engine, VpnService vpn, int cliIp, int cliPort, int dnsIp) {
            this.engine = engine;
            this.vpn = vpn;
            this.cliIp = cliIp;
            this.cliPort = cliPort;
            this.dnsIp = dnsIp;
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                vpn.protect(s);
            } catch (IOException e) {
                Log.i(TAG, "dns relay socket failed");
            }
            sock = s;
            usable = s != null;
        }

        void startThread() {
            thread = new Thread(this, "bypass-dns");
            thread.setDaemon(true);
            thread.start();
        }

        void onQuery(byte[] buf, int off, int len) {
            lastActive = System.currentTimeMillis();
            if (!running || !usable) return;
            byte[] q = new byte[len];
            System.arraycopy(buf, off, q, 0, len);
            try {
                DatagramPacket dp = new DatagramPacket(q, len,
                        InetAddress.getByName(BypassProto.ipToString(dnsIp)), 53);
                sock.send(dp);
            } catch (IOException ignored) {
            }
        }

        @Override
        public void run() {
            byte[] rbuf = new byte[4096];
            while (running && usable) {
                try {
                    DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
                    sock.receive(rp);
                    lastActive = System.currentTimeMillis();
                    int rl = rp.getLength();
                    byte[] pkt = new byte[28 + rl];
                    int total = BypassProto.buildUdpPacket(pkt, dnsIp, cliIp, 53, cliPort,
                            rp.getData(), rp.getOffset(), rl);
                    engine.writeTun(pkt, total);
                } catch (IOException e) {
                    if (running) break;
                }
            }
        }

        boolean isIdle(long now, long maxIdleMs) {
            return now - lastActive > maxIdleMs;
        }

        void close() {
            running = false;
            if (sock != null) {
                try {
                    sock.close();
                } catch (Exception ignored) {
                }
            }
            if (thread != null) thread.interrupt();
        }
    }
}