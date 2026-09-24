/*
 * BypassEngine — движок DPI-обхода поверх TUN (аналог zapret/ByeDPI).
 *
 * Задача движка (этапы, подробнее в BYPASS.md):
 *   1) парсинг IPv4-пакетов, приходящих из TUN;
 *   2) DPI-эвейжн исходящих TCP (фрагментация TLS ClientHello, работа с SNI —
 *      как desync-режимы zapret) и доставка их наружу через обычные сокеты
 *      с protect();
 *   3) ответные пакеты — обратно в TUN.
 *
 * Статус: КАРКАС. isAvailable() возвращает false — сервис не поднимает VPN,
 * чтобы недоработанный перехват не отрубил интернет. Когда движок готов,
 * isAvailable() переключается на true и весь поток (разрешение -> VPN ->
 * перехват) включается.
 *
 * Без AndroidX. Java 8-совместимый код.
 */
package com.vot.youtube;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;

public final class BypassEngine {
    private static final String TAG = "YouTubeVotBypass";

    /** true, когда движок готов реально перехватывать и пересылать трафик. */
    public static boolean isAvailable() {
        // TODO(bypass): этап 2+. Пока безопасный режим — выключено.
        return false;
    }

    private final ParcelFileDescriptor tun;
    private volatile boolean running;

    public BypassEngine(ParcelFileDescriptor tun) {
        this.tun = tun;
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "bypass-engine");
        t.setDaemon(true);
        t.start();
    }

    /** Читает пакеты из TUN. Этап 1: только логируем, ничего не шлём в сеть. */
    private void loop() {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        byte[] buf = new byte[65535];
        try {
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                // TODO(bypass): этап 1 — разбор IPv4-заголовков и классификация;
                // этап 2 — DPI-эвейжн TCP ClientHello и проксирование наружу с protect().
                Log.v(TAG, "tun packet: " + n + " bytes");
            }
        } catch (IOException e) {
            Log.i(TAG, "tun closed: " + e.getMessage());
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void shutdown() {
        running = false;
        try {
            tun.close();
        } catch (IOException ignored) {
        }
    }
}