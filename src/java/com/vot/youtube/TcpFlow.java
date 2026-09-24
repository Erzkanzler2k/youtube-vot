/*
 * TcpFlow — одно TCP-соединение движка обхода.
 *
 * Со стороны приложения (TUN) ведём себя как TCP-сервер: принимаем SYN,
 * отвечаем своим SYN+ACK с нашим ISN, подтверждаем данные, шлём данные с
 * нашими seq/ack. Наружу открываем обычный Socket с protect() и прозрачно
 * пересылаем поток.
 *
 * DPI-обход: первый payload с порта 443 перехватываем, вытаскиваем SNI из
 * TLS ClientHello; если домен в списке заблокированных — отправляем
 * ClientHello фрагментами (разрез внутри имени хоста, с паузой между
 * сегментами), чтобы DPI не увидел SNI в первом сегменте. Сервер (Linux TCP
 * reassembly + TLS record reassembly) спокойно переживает такой разрез.
 *
 * Java 8-совместимый код, без AndroidX.
 */
package com.vot.youtube;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class TcpFlow {
    private static final String TAG = "YouTubeVotBypass";

    /** Сколько байт первого payload максимум буферизуем в поисках SNI. */
    private static final int SNI_BUFFER_CAP = 4096;

    // состояние перехвата первого payload
    private static final int SNI_NONE = 0; // порт не 443 - прозрачно
    private static final int SNI_WAIT = 1; // копим начало потока, ищем SNI
    private static final int SNI_DONE = 2; // решение принято - дальше прозрачно

    final int srcIp;   // приложение (TUN), адрес вида 10.9.0.2
    final int dstIp;   // реальный сервер
    final int srcPort;
    final int dstPort;

    private final BypassEngine engine;
    private final VpnService vpn;

    // seq/ack состояние серверной стороны (адресуем клиенту)
    private boolean synAckSent;
    private boolean synSeqKnown;
    private long synSeq;
    private long sIsn;              // наш ISN в SYN+ACK
    private long sSeq;              // следующий seq наших данных
    private long clientNext;        // следующий ожидаемый seq данных клиента

    volatile boolean done;
    private boolean clientCloseSeen;
    private boolean serverClosed;

    private Socket out;
    private Thread pumpThread;

    // перехват первого payload (SNI)
    private int sniState;
    private byte[] pending;
    private int pendingLen;
    private boolean fragMode;
    private int fragSniOff;
    private int fragSniLen;

    private long lastActive = System.currentTimeMillis();

    public TcpFlow(BypassEngine engine, VpnService vpn,
                   int srcIp, int dstIp, int srcPort, int dstPort) {
        this.engine = engine;
        this.vpn = vpn;
        this.srcIp = srcIp;
        this.dstIp = dstIp;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.sniState = dstPort == 443 ? SNI_WAIT : SNI_NONE;
    }

    /* ---------------- вход из движка ---------------- */

    public void onPacket(BypassProto.Tcp4 t, byte[] buf) {
        lastActive = System.currentTimeMillis();
        if (done) return;

        if (t.syn && !t.ackFlag) {
            handleSyn(t);
            if (t.payloadLen <= 0) return;
            // данные в SYN (TCP Fast Open) - обрабатываем как обычные данные ниже
        }
        if (t.rst) {
            if (out != null) closeOut();
            done = true;
            return;
        }
        if (!synAckSent) {
            // пакет без SYN - осиротевший, игнорируем
            return;
        }

        if (t.payloadLen > 0) {
            long plen = t.payloadLen;
            if (t.seq == clientNext) {
                clientNext = (clientNext + plen) & 0xFFFFFFFFL;
                byte[] data = copyRange(buf, t.payloadOff, t.payloadLen);
                sendAck(clientNext);
                forward(data);
            } else if (t.seq + plen <= clientNext) {
                sendAck(clientNext); // ретрансмит уже принятого
            } else {
                sendAck(clientNext); // разрыв/пробел: подтверждаем ожидаемое
            }
        }

        if (t.fin) {
            long finAck = (t.seq + t.payloadLen + 1) & 0xFFFFFFFFL;
            if (finAck <= clientNext) {
                sendAck(clientNext); // FIN уже подтверждён
            } else {
                clientNext = finAck;
                sendAck(clientNext);
                clientCloseSeen = true;
                if (out != null) {
                    try {
                        out.shutdownOutput();
                    } catch (IOException ignored) {
                    }
                }
                if (serverClosed) closeFlow();
            }
        }
    }

    private void handleSyn(BypassProto.Tcp4 t) {
        if (synAckSent) {
            if (synSeqKnown && t.seq == synSeq) sendSynAck(); // ретрансмит SYN
            return;
        }
        synAckSent = true;
        synSeqKnown = true;
        synSeq = t.seq;
        sIsn = (System.nanoTime() ^ ((long) srcPort << 16 | dstPort)) & 0xFFFFFFFFL;
        clientNext = (synSeq + 1) & 0xFFFFFFFFL;
        sSeq = (sIsn + 1) & 0xFFFFFFFFL;
        sendSynAck();
    }

    /* ---------------- выход к приложению (в TUN) ---------------- */

    private void sendSynAck() {
        byte[] pkt = new byte[40];
        int total = BypassProto.buildTcpPacket(pkt,
                dstIp, srcIp, dstPort, srcPort,
                sIsn, clientNext, BypassProto.FLAG_SYN | BypassProto.FLAG_ACK,
                null, 0, 0);
        engine.writeTun(pkt, total);
    }

    private void sendAck(long ack) {
        if (done) return;
        byte[] pkt = new byte[40];
        int total = BypassProto.buildTcpPacket(pkt,
                dstIp, srcIp, dstPort, srcPort,
                sSeq, ack, BypassProto.FLAG_ACK,
                null, 0, 0);
        engine.writeTun(pkt, total);
    }

    private void sendRstToApp() {
        if (done) return;
        byte[] pkt = new byte[40];
        int total = BypassProto.buildTcpPacket(pkt,
                dstIp, srcIp, dstPort, srcPort,
                sSeq, clientNext, BypassProto.FLAG_RST | BypassProto.FLAG_ACK,
                null, 0, 0);
        engine.writeTun(pkt, total);
    }

    /** Данные от сервера -> клиенту (из потока pump). */
    private void writeToClient(byte[] data, int len) {
        if (done || len <= 0) return;
        byte[] pkt = new byte[40 + len];
        int total = BypassProto.buildTcpPacket(pkt,
                dstIp, srcIp, dstPort, srcPort,
                sSeq, clientNext, BypassProto.FLAG_ACK,
                data, 0, len);
        engine.writeTun(pkt, total);
        sSeq = (sSeq + len) & 0xFFFFFFFFL;
    }

    /* ---------------- наружу (Socket + protect) ---------------- */

    private void forward(byte[] data) {
        try {
            if (out == null) {
                connectOut();
                if (out == null) return; // не удалось - уже отправили RST
            }
        } catch (Exception e) {
            sendRstToApp();
            closeFlow();
            return;
        }
        if (sniState == SNI_WAIT) {
            sniffAndWrite(data); // внутри переведёт в SNI_DONE и зафлатчит
            return;
        }
        writeAll(data, 0, data.length);
    }

    private void connectOut() {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            if (vpn != null) vpn.protect(s);
            s.connect(new InetSocketAddress(BypassProto.ipToString(dstIp), dstPort), 3000);
            out = s;
            pumpThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump();
                }
            }, "bypass-flow-" + srcPort);
            pumpThread.setDaemon(true);
            pumpThread.start();
        } catch (Exception e) {
            Log.i(TAG, "connect fail " + BypassProto.ipToString(dstIp) + ":" + dstPort);
            try {
                s.close();
            } catch (IOException ignored) {
            }
            out = null;
            sendRstToApp();
            done = true;
        }
    }

    private void pump() {
        try {
            InputStream inStream = out.getInputStream();
            byte[] rbuf = new byte[1400];
            int n;
            while (!done) {
                n = inStream.read(rbuf);
                if (n < 0) break;
                if (n > 0) writeToClient(rbuf, n);
            }
        } catch (IOException ignored) {
        }
        serverEof();
    }

    private void serverEof() {
        if (done) return;
        byte[] pkt = new byte[40];
        long finSeq = sSeq;
        int total = BypassProto.buildTcpPacket(pkt,
                dstIp, srcIp, dstPort, srcPort,
                finSeq, clientNext, BypassProto.FLAG_FIN | BypassProto.FLAG_ACK,
                null, 0, 0);
        engine.writeTun(pkt, total);
        sSeq = (finSeq + 1) & 0xFFFFFFFFL;
        serverClosed = true;
        if (clientCloseSeen) closeFlow();
    }

    /* ---------------- SNI + фрагментация ---------------- */

    private void sniffAndWrite(byte[] data) {
        appendPending(data);
        if (pendingLen == 0 || pending[0] != 0x16) {
            // не TLS - просто наружу
            sniState = SNI_DONE;
            flushPending();
            return;
        }
        BypassProto.Sni sni = BypassProto.parseClientHelloSni(pending, 0, pendingLen);
        if (sni != null) {
            if (BypassProto.matchesBlocked(sni.host, BypassEngine.BLOCKED_SUFFIXES)) {
                fragMode = true;
                fragSniOff = sni.nameOff;
                fragSniLen = sni.nameLen;
            }
            sniState = SNI_DONE;
            flushPending();
            return;
        }
        if (pendingLen >= SNI_BUFFER_CAP) {
            sniState = SNI_DONE;
            flushPending(); // SNI найти не удалось - отдаём как есть
            return;
        }
        // запись ещё не доехала целиком - ждём следующий сегмент
    }

    private void flushPending() {
        if (pendingLen <= 0) return;
        if (fragMode && pendingLen > 0) {
            writeFragmented(pending, 0, pendingLen, fragSniOff, fragSniLen);
        } else {
            writeAll(pending, 0, pendingLen);
        }
        pendingLen = 0;
    }

    private void appendPending(byte[] d) {
        if (d.length == 0) return;
        if (pending == null) pending = new byte[Math.max(256, d.length)];
        if (pendingLen + d.length > pending.length) {
            byte[] np = new byte[Math.max(pending.length * 2, pendingLen + d.length)];
            System.arraycopy(pending, 0, np, 0, pendingLen);
            pending = np;
        }
        System.arraycopy(d, 0, pending, pendingLen, d.length);
        pendingLen += d.length;
    }

    /** Первый ClientHello ломаем на два сегмента внутри имени SNI. */
    private void writeFragmented(byte[] buf, int off, int len, int sniOff, int sniLen) {
        int split = sniOff + (sniLen * 3) / 4;
        if (split < 1) split = 1;
        if (split >= len) split = len - 1;
        if (split < 1) {
            writeAll(buf, off, len);
            return;
        }
        writeAll(buf, off, split);
        try {
            Thread.sleep(7);
        } catch (InterruptedException ignored) {
        }
        writeAll(buf, off + split, len - split);
    }

    private void writeAll(byte[] buf, int off, int len) {
        if (done || out == null || len <= 0) return;
        try {
            OutputStream os = out.getOutputStream();
            os.write(buf, off, len);
            os.flush();
        } catch (IOException e) {
            closeOut();
            done = true;
        }
    }

    /* ---------------- служебное ---------------- */

    private static byte[] copyRange(byte[] buf, int off, int len) {
        byte[] d = new byte[len];
        System.arraycopy(buf, off, d, 0, len);
        return d;
    }

    boolean isIdle(long now, long maxIdleMs) {
        return now - lastActive > maxIdleMs;
    }

    void closeFlow() {
        done = true;
        closeOut();
        if (pumpThread != null) pumpThread.interrupt();
    }

    private void closeOut() {
        Socket s = out;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}