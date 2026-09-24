package com.vot.youtube;

public final class ByeDpiNative {
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("byedpi");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private ByeDpiNative() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static native int createSocketWithCommandLine(String[] args);

    public static native int startProxy(int fd);

    public static native int stopProxy(int fd);
}
