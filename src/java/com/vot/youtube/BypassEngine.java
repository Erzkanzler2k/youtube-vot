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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

public final class BypassEngine {
    private static final String TAG = "YouTubeVotBypass";

    /** Домены, у которых ломаем первый ClientHello (суффиксы). */
    public static final String[] BLOCKED_SUFFIXES = {
            "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com",
            "googleusercontent.com", "gstatic.com", "googleapis.com",
            "googlesyndication.com", "doubleclick.net", "google.com", "google.ru"
    };

    private static final long FLOW_IDLE_MS = 120_000L;
    private static final long DNS_IDLE_MS = 60_000L;
    private static final long SWEEP_INTERVAL_MS = 30_000L;

    // DNS: переход UDP -> DoH (фолбэк если DPI отравил/съел ответ)
    private static final long DNS_DOH_TIMEOUT_MS = 2000L;
    private static final int DNS_UDP_POLL_MS = 1000;          // таймаут UDP receive
    private static final String DOH_URL = "https://dns.yandex.ru/dns-query";

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
        long seen = 0L;
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
                if (BypassProto.isIpFragment(buf, off)) {
                    // IP-фрагментацию не поддерживаем — дроп
                    off += ip.totalLen;
                    continue;
                }
                // Важно: НЕ фильтруем по srcIp == 10.9.0.2 (LOCAL_TUN_IP) — весь
                // трафик приложения через TUN идёт именно с этим адресом источника,
                // фильтр по нему молча убивал весь трафик (чёрная дыра).
                seen++;
                if (seen <= 3) {
                    // краткая наблюдаемость на старте: что реально читаем из TUN
                    Log.i(TAG, "tun pkt src=" + BypassProto.ipToString(ip.srcIp)
                            + " dst=" + BypassProto.ipToString(ip.dstIp)
                            + " proto=" + (ip.protocol == BypassProto.PROTO_TCP ? "tcp"
                            : ip.protocol == BypassProto.PROTO_UDP ? "udp"
                            : String.valueOf(ip.protocol)));
                } else if ((seen % 500) == 0) {
                    Log.i(TAG, "tun seen " + seen + " pkts");
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
        Log.i(TAG, "engine shutdown begin");
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
        Log.i(TAG, "engine shutdown done");
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
        private byte[] pendingQuery;  // последний DNS-запрос этого клиента
        private long pendingDeadline; // когда переходить на DoH
        private boolean dohSent;      // DoH уже запущен для этого запроса

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
                s.setSoTimeout(DNS_UDP_POLL_MS);
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
            if (!running) return;
            byte[] q = new byte[len];
            System.arraycopy(buf, off, q, 0, len);
            pendingQuery = q;
            pendingDeadline = System.currentTimeMillis() + DNS_DOH_TIMEOUT_MS;
            dohSent = false;
            if (!usable) return; // сокета нет — остаётся только DoH-фолбэк
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
            while (running) {
                if (!usable) {
                    // UDP-сокет не создался — крутимся по таймауту, отвечает DoH
                    try {
                        Thread.sleep(DNS_UDP_POLL_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                    maybeDoh();
                    continue;
                }
                try {
                    DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
                    sock.receive(rp); // с soTimeout — каждую секунду сверяем дедлайн
                    lastActive = System.currentTimeMillis();
                    sendUdpResponse(rp.getData(), rp.getOffset(), rp.getLength());
                    pendingQuery = null;
                } catch (SocketTimeoutException te) {
                    maybeDoh();
                } catch (IOException e) {
                    if (running) break;
                }
            }
        }

        /** Если UDP-ответ так и не пришёл — один раз запускаем DoH-запрос. */
        private void maybeDoh() {
            byte[] q = pendingQuery;
            if (q == null || dohSent) return;
            if (System.currentTimeMillis() < pendingDeadline) return;
            dohSent = true;
            pendingQuery = null;
            dohQuery(q);
        }

        private void sendUdpResponse(byte[] data, int off, int len) {
            if (len <= 0 || len > 4096) return;
            byte[] pkt = new byte[28 + len];
            int total = BypassProto.buildUdpPacket(pkt, dnsIp, cliIp, 53, cliPort,
                    data, off, len);
            engine.writeTun(pkt, total);
        }

        /**
         * DNS-over-HTTPS фолбэк (аналог nfqsd в zapret): запрос уходит открытым
         * текстом по 443 на dns.yandex.ru (внутри нашего же VPN), DPI его не
         * читает. Ответ собираем в UDP-пакет и отдаём клиенту в TUN.
         */
        private void dohQuery(final byte[] q) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpsURLConnection c = null;
                    try {
                        c = (HttpsURLConnection) new URL(DOH_URL).openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(5000);
                        c.setRequestMethod("POST");
                        c.setRequestProperty("Content-Type", "application/dns-message");
                        c.setRequestProperty("Accept", "application/dns-message");
                        c.setDoOutput(true);
                        OutputStream os = c.getOutputStream();
                        os.write(q);
                        os.flush();
                        int code = c.getResponseCode();
                        if (code == 200) {
                            byte[] resp = readAll(c.getInputStream(), 4096);
                            if (resp.length > 0) sendUdpResponse(resp, 0, resp.length);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            }, "bypass-doh");
            t.setDaemon(true);
            t.start();
        }

        private static byte[] readAll(InputStream is, int cap) throws IOException {
            byte[] tmp = new byte[cap];
            int pos = 0;
            int n;
            while (pos < cap && (n = is.read(tmp, pos, cap - pos)) > 0) {
                pos += n;
            }
            byte[] out = new byte[pos];
            System.arraycopy(tmp, 0, out, 0, pos);
            return out;
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