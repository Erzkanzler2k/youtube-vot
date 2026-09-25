package hev.htproxy;

/**
 * Java-обвязка hev-socks5-tunnel (tun2socks).
 *
 * <p>Имя пакета и сигнатуры методов обязаны совпадать с тем, что регистрирует
 * нативный код в {@code JNI_OnLoad} (hev-jni.c):
 * <ul>
 *   <li>класс ищется как {@code hev/htproxy/TProxyService} — при несовпадении
 *       {@code FindClass} вернёт {@code null} и {@code RegisterNatives} уронит
 *       процесс с «JNI DETECTED ERROR IN APPLICATION: java_class == null»;</li>
 *   <li>{@code TProxyStartService(String,int)} и {@code TProxyStopService()}
 *       возвращают {@code void} ({@code ...V}), а не boolean;</li>
 *   <li>{@code TProxyIsRunning} в нативной таблице отсутствует — вызывать его
 *       нельзя.</li>
 * </ul>
 *
 * Сабмодуль {@code src/cpp/hev-socks5-tunnel} запинен и не модифицируется,
 * поэтому контракт соблюдаем здесь.
 */
public final class TProxyService {
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("hev-socks5-tunnel");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private TProxyService() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static native void TProxyStartService(String configPath, int fd);

    public static native void TProxyStopService();

    public static native long[] TProxyGetStats();
}
