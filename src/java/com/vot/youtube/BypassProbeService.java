package com.vot.youtube;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Точка измерения обхода. Запускается как {@code android:isolatedProcess}.
 *
 * <p>Изолированность здесь не косметика, а единственный способ измерить обход.
 * Приложение исключает собственный пакет из туннеля
 * ({@code addDisallowedApplication("com.vot.youtube")}), а изолированный
 * сервис получает собственный динамический UID, который в этот список не
 * попадает. Поэтому его сокеты идут через туннель и проходят через десканк,
 * а сокеты процесса приложения идут напрямую и всегда выглядят рабочими.
 *
 * <p>Требует подтверждения на устройстве: вывод выше опирается на то, что
 * {@code addDisallowedApplication} исключает UID пакета, а не все UID,
 * принадлежащие пакету. Проверка обязательна до того, как доверять вердиктам.
 */
public class BypassProbeService extends Service {

    private static final String TAG = "YouTubeVotBypass";

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(final Message message) {
            if (message.what != BypassProbe.MSG_RUN) return;
            final int repeats = Math.max(1, message.arg1);
            final int timeoutMs = message.arg2 > 0 ? message.arg2 : BypassProbe.HARD_TIMEOUT_MS;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    BypassProbe.Result result = measure(repeats, timeoutMs);
                    reply(message, result);
                }
            });
        }
    });

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private void reply(Message request, BypassProbe.Result result) {
        Message response = Message.obtain(null, BypassProbe.MSG_RESULT);
        response.arg1 = result == null ? 0 : 1;
        if (result != null) response.setData(result.toBundle());
        Messenger target = request.replyTo;
        if (target == null) return;
        try {
            target.send(response);
        } catch (Exception error) {
            Log.w(TAG, "probe reply failed", error);
        }
    }

    private BypassProbe.Result measure(int repeats, int timeoutMs) {
        long[] hostMs = new long[BypassProbe.HOSTS.length];
        Arrays.fill(hostMs, BypassProbe.FAILED);
        for (int i = 0; i < BypassProbe.HOSTS.length; i++) {
            hostMs[i] = measureHostRepeatedly(BypassProbe.HOSTS[i], repeats, timeoutMs);
        }
        long quicMs = measureQuicRejection(BypassProbe.HOSTS[0], timeoutMs);
        long ipv6Ms = measureIpv6(BypassProbe.HOSTS[0], timeoutMs);
        Log.i(TAG, "battery: hosts=" + Arrays.toString(hostMs)
                + " quic=" + quicMs + " ipv6=" + ipv6Ms);
        return new BypassProbe.Result(hostMs, quicMs, false, ipv6Ms);
    }

    /** Медиана по нескольким попыткам: одна неудача не должна ронять стратегию. */
    private long measureHostRepeatedly(String host, int repeats, int timeoutMs) {
        long[] samples = new long[repeats];
        for (int i = 0; i < repeats; i++) {
            samples[i] = measureHostOnce(host, timeoutMs);
            if (samples[i] < 0 && i == 0 && repeats > 1) {
                // Первая попытка может не успеть из-за холодного старта, даём один шанс.
                samples[i] = measureHostOnce(host, timeoutMs);
            }
        }
        long[] good = new long[repeats];
        int count = 0;
        for (long sample : samples) {
            if (sample >= 0) good[count++] = sample;
        }
        if (count == 0) return BypassProbe.FAILED;
        Arrays.sort(good, 0, count);
        return good[count / 2];
    }

    /**
     * TCP-соединение, TLS-хендшейк и HTTP-ответ одним замером.
     *
     * <p>Замеряется всё вместе, потому что DPI обычно душит всю сессию, а не
     * точку рукопожатия. Требуется именно HTTP-ответ: успешное рукопожатие
     * бывает и у сетей, где видео всё равно не едет.
     */
    private long measureHostOnce(String host, int timeoutMs) {
        Socket tcp = null;
        SSLSocket tls = null;
        long started = System.nanoTime();
        try {
            tcp = new Socket();
            tcp.connect(new InetSocketAddress(host, BypassProbe.PROBE_PORT), timeoutMs);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            // Обёртка задаёт SNI, иначе byedpi не сопоставит хост со списком.
            tls = (SSLSocket) factory.createSocket(tcp, host, BypassProbe.PROBE_PORT, false);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            String request = "HEAD /generate_204 HTTP/1.1\r\nHost: " + host
                    + "\r\nConnection: close\r\nUser-Agent: Mozilla/5.0\r\n\r\n";
            out.write(request.getBytes("US-ASCII"));
            out.flush();
            String status = readStatusLine(tls.getInputStream());
            if (!status.startsWith("HTTP/")) return BypassProbe.FAILED;
            return (System.nanoTime() - started) / 1000000L;
        } catch (Exception error) {
            return BypassProbe.FAILED;
        } finally {
            closeQuietly(tls);
            closeQuietly(tcp);
        }
    }

    private String readStatusLine(InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') break;
            if (read != '\r') line.append((char) read);
            if (line.length() > 64) break;
        }
        return line.toString();
    }

    /**
     * Проверяет, что UDP отбивается быстро.
     *
     * <p>Мы форсируем TCP, поэтому UDP-пакет должен упереться в отказ и вернуться
     * мгновенно. Если вместо отказа приходит таймаут — это чёрная дыра, и
     * WebView будет висеть на каждом холодном старте, дожидаясь QUIC-таймаута.
     * Отдельно отмечаем получение данных: это означает, что UDP не отключился.
     */
    private long measureQuicRejection(String host, int timeoutMs) {
        DatagramSocket socket = null;
        long started = System.nanoTime();
        try {
            socket = new DatagramSocket();
            socket.connect(new InetSocketAddress(host, BypassProbe.PROBE_PORT));
            socket.setSoTimeout(timeoutMs);
            socket.send(new DatagramPacket(new byte[]{0x00}, 1));
            byte[] buffer = new byte[64];
            socket.receive(new DatagramPacket(buffer, buffer.length));
            return System.nanoTime() - started;
        } catch (SocketTimeoutException timeout) {
            return BypassProbe.TIMED_OUT;
        } catch (Exception refused) {
            return (System.nanoTime() - started) / 1000000L;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Проверяет IPv6 через туннель. Возвращает {@link BypassProbe#NO_IPV6},
     * если у хоста нет AAAA-записи: тогда и проверять нечего.
     */
    private long measureIpv6(String host, int timeoutMs) {
        InetAddress v6 = null;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address instanceof Inet6Address) {
                    v6 = address;
                    break;
                }
            }
        } catch (Exception error) {
            return BypassProbe.FAILED;
        }
        if (v6 == null) return BypassProbe.NO_IPV6;

        Socket tcp = null;
        SSLSocket tls = null;
        long started = System.nanoTime();
        try {
            tcp = new Socket();
            tcp.connect(new InetSocketAddress(v6, BypassProbe.PROBE_PORT), timeoutMs);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            tls = (SSLSocket) factory.createSocket(tcp, host, BypassProbe.PROBE_PORT, false);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();
            return (System.nanoTime() - started) / 1000000L;
        } catch (Exception error) {
            return BypassProbe.FAILED;
        } finally {
            closeQuietly(tls);
            closeQuietly(tcp);
        }
    }

    private void closeQuietly(Object closeable) {
        if (closeable == null) return;
        try {
            if (closeable instanceof SSLSocket) ((SSLSocket) closeable).close();
            else if (closeable instanceof DatagramSocket) ((DatagramSocket) closeable).close();
            else if (closeable instanceof Socket) ((Socket) closeable).close();
        } catch (Exception ignored) {
        }
    }
}
