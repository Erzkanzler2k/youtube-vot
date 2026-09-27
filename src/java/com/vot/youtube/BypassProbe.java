package com.vot.youtube;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Батарея замеров: хосты, пороги и модель результата.
 *
 * <p>Замеры идут не из процесса приложения, а из изолированного сервиса
 * ({@link BypassProbeService}). Причина обязательна: процесс приложения
 * исключён из туннеля через {@code addDisallowedApplication}, поэтому его
 * сокеты всегда идут напрямую мимо десинка и всегда показывают «работает».
 * Только чужой UID попадает в туннель.
 *
 * <p>Список хостов подобран так, чтобы они покрывались списком доменов
 * {@link BypassVpnService#BYPASS_HOSTS}: byedpi сопоставляет хосты по суффиксу
 * с точкой ({@code host_cmp} в {@code mpool.c}), поэтому
 * {@code www.youtube.com} подпадает под {@code youtube.com}, а
 * {@code redirector.googlevideo.com} — под {@code googlevideo.com}.
 */
final class BypassProbe {

    /**
     * Хосты батареи. Все четыре нужны приложению, и в РФ они режутся
     * по-разному, поэтому проверка одного хоста даёт ложный вердикт:
     * API может работать, пока видео-CDN зарезан.
     */
    static final String[] HOSTS = {
            "www.youtube.com",
            "youtubei.googleapis.com",
            "i.ytimg.com",
            "redirector.googlevideo.com"
    };

    static final int PROBE_PORT = 443;

    /**
     * Пороги. НЕ ОТКАЛИБРОВАНЫ: осмысленные значения можно получить только
     * из замеров в реальных заблокированных сетях. Пока таких данных нет,
     * значения — разумные стартовые, а не проверенные факты.
     */
    static final int HARD_TIMEOUT_MS = 5000;
    static final int SOFT_NORM_MS = 1500;

    /** Одиночный замер идёт без повторов, полный перебор — с повторами для медианы. */
    static final int QUICK_REPEATS = 1;
    static final int SWEEP_REPEATS = 3;

    /**
     * Значимый выигрыш: кандидат должен быть быстрее текущей стратегии
     * в {@value} раза, иначе стратегия не меняется. Без порога приложение
     * скачет между стратегиями от запуска к запуску на шумных замерах.
     */
    static final float SIGNIFICANT_WIN = 1.5f;

    /** Остывание полного перебора. Быстрый замер и шаги лестницы не остывают. */
    static final long SWEEP_COOLDOWN_MS = 30L * 60L * 1000L;

    /** Хост не ответил либо ответил не HTTP-ответом. */
    static final long FAILED = -1L;
    /** У хоста нет AAAA-записи, проверять нечего. */
    static final long NO_IPV6 = -2L;
    /** Ответа не было до таймаута: чёрная дыра вместо быстрого отказа. */
    static final long TIMED_OUT = -3L;

    static final String KEY_HOST_MS = "hostMs";
    static final String KEY_REPEATS = "repeats";
    static final String KEY_QUIC_MS = "quicMs";
    static final String KEY_QUIC_GOT_DATA = "quicGotData";
    static final String KEY_IPV6_MS = "ipv6Ms";

    static final int MSG_RUN = 1;
    static final int MSG_RESULT = 2;

    /** Вердикт стратегии по батарее. */
    static final int VERDICT_FAIL = 0;
    static final int VERDICT_FAST = 1;
    static final int VERDICT_SLOW = 2;

    private BypassProbe() {
    }

    /** Результат одного прогона батареи. */
    static final class Result {
        final long[] hostMs;
        final long quicMs;
        final boolean quicGotData;
        final long ipv6Ms;

        Result(long[] hostMs, long quicMs, boolean quicGotData, long ipv6Ms) {
            this.hostMs = hostMs;
            this.quicMs = quicMs;
            this.quicGotData = quicGotData;
            this.ipv6Ms = ipv6Ms;
        }

        static Result fromBundle(Bundle data) {
            return new Result(data.getLongArray(KEY_HOST_MS),
                    data.getLong(KEY_QUIC_MS, FAILED),
                    data.getBoolean(KEY_QUIC_GOT_DATA, false),
                    data.getLong(KEY_IPV6_MS, FAILED));
        }

        Bundle toBundle() {
            Bundle data = new Bundle();
            data.putLongArray(KEY_HOST_MS, hostMs);
            data.putLong(KEY_QUIC_MS, quicMs);
            data.putBoolean(KEY_QUIC_GOT_DATA, quicGotData);
            data.putLong(KEY_IPV6_MS, ipv6Ms);
            return data;
        }

        boolean allHostsAnswered() {
            if (hostMs == null || hostMs.length != HOSTS.length) return false;
            for (long value : hostMs) {
                if (value < 0) return false;
            }
            return true;
        }

        int verdict() {
            if (!allHostsAnswered()) return VERDICT_FAIL;
            return worstHostMs() <= SOFT_NORM_MS ? VERDICT_FAST : VERDICT_SLOW;
        }

        int worstHostMs() {
            int worst = 0;
            for (long value : hostMs) {
                if (value > worst) worst = (int) value;
            }
            return worst;
        }

        /** Медиана по худшему хосту: устойчивее среднего к выбросам. */
        long medianOfWorst() {
            if (!allHostsAnswered()) return Long.MAX_VALUE;
            long[] sorted = hostMs.clone();
            java.util.Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }

        /**
         * Быстрый ли отказ UDP. Это дефект нашего транспорта, а не свойство
         * стратегии, поэтому он не дисквалифицирует стратегию, а попадает в
         * отчёт: перебор стратегий его не чинит.
         */
        boolean quicFailsFast() {
            return quicMs >= 0 && quicMs < HARD_TIMEOUT_MS && !quicGotData;
        }

        /** Есть ли смысл считать IPv6 проверенным. */
        boolean ipv6Checked() {
            return ipv6Ms != NO_IPV6;
        }

        boolean ipv6Usable() {
            return ipv6Ms >= 0;
        }
    }

    /**
     * Прогоняет батарею в изолированном сервисе и ждёт ответ.
     *
     * <p>Блокирующий вызов: вызывать только с рабочего потока. Возвращает
     * {@code null}, если сервис не поднялся или не ответил вовремя, — это
     * трактуется как «проверить не удалось», а не как «работает».
     */
    static Result runBlocking(Context context, int repeats, int timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Result[] holder = new Result[1];
        final Messenger[] proxy = new Messenger[1];

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                proxy[0] = new Messenger(binder);
                Message request = Message.obtain(null, MSG_RUN);
                request.arg1 = repeats;
                request.arg2 = timeoutMs;
                request.replyTo = new Messenger(new Latch(latch, holder));
                try {
                    proxy[0].send(request);
                } catch (RemoteException error) {
                    latch.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                latch.countDown();
            }
        };

        Intent intent = new Intent(context, BypassProbeService.class);
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            return null;
        }
        try {
            if (!latch.await(timeoutMs + 4000L, TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return holder[0];
    }

    /** Приёмник ответа из изолированного процесса. */
    private static final class Latch extends android.os.Handler {
        private final CountDownLatch latch;
        private final Result[] holder;

        Latch(CountDownLatch latch, Result[] holder) {
            super(Looper.getMainLooper());
            this.latch = latch;
            this.holder = holder;
        }

        @Override
        public void handleMessage(Message message) {
            if (message.what == MSG_RESULT && message.arg1 == 1) {
                Bundle data = message.getData();
                if (data != null) holder[0] = Result.fromBundle(data);
            }
            latch.countDown();
        }
    }
}
