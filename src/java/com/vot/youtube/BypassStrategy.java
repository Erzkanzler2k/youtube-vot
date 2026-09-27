package com.vot.youtube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Каталог стратегий десинка для локального SOCKS5-прокси byedpi.
 *
 * <p>Стратегии — это наборы CLI-флагов byedpi ({@code -s} сплиты, {@code -d}
 * disorder, {@code -f} fake TLS, {@code -A} авто-режим и т.д.), а не чужой код.
 * Наборы взяты из публичного каталога byedpi-стратегий, который использует
 * приложение zaprett: {@code https://github.com/CherretGit/zaprett-repo}
 * (файл {@code index.json}, тип {@code byedpi}). Код zaprett (GPL-3.0) здесь не
 * используется: перенесены только строки аргументов, что не создаёт
 * производной работы.
 *
 * <p>Сам zapret ({@code bol-van/zapret}, механизм {@code nfqws}) на Android
 * без root не работает: ему нужен {@code netfilter_queue} и root. Поэтому
 * rootless-обход здесь — это ровно то, что делает zaprett в rootless-режиме:
 * {@code VpnService} + {@code hev-socks5-tunnel} + byedpi, а «стратегии» —
 * это способы рассинхронизации, то есть то, ради чего zapret и нужен.
 *
 * <p>Все флаги проверены против нашей сборки byedpi v0.17.3: {@code -f},
 * {@code -n}, {@code -Q}, {@code -M}, {@code -t} доступны, потому что
 * {@code params.h} всегда определяет {@code FAKE_SUPPORT} и
 * {@code TIMEOUT_SUPPORT}.
 */
final class BypassStrategy {

    /** Стратегия по умолчанию: поведение v2.5.5, проверенное на устройстве. */
    static final String ID_DEFAULT = "default";

    private static final BypassStrategy[] ALL = {
            new BypassStrategy(ID_DEFAULT, R.string.strategy_default,
                    // Базовый десинк zaprett для TLS: сплит в начале + запись
                    // рекорда, авто-режим по всем позициям рассинхронизации.
                    "--auto=torst --split 0+sm --tlsrec 0+sm"),
            new BypassStrategy("zapret-default", R.string.strategy_zapret_default,
                    // Стратегия «по умолчанию» из каталога zapret: OOB-байт на
                    // позиции 2 плюс автоматический disorder.
                    "-o 2 --auto=t,r,a,s -d 2"),
            new BypassStrategy("yt-new-fix", R.string.strategy_yt_new_fix,
                    // YouTube new fix: серия disorder/split с растущим шагом.
                    "-d1 -s1+s -d3+s -d6+s -d9+s -d12+s -d15+s -d20+s"
                            + " -d25+s -d30+s -d35+s -a1"),
            new BypassStrategy("yt-fix-mts", R.string.strategy_yt_fix_mts,
                    // YouTube fix (MTS): fake TLS 204 + multisplit по SNI.
                    "-o1 -d1 -a1 -An -a2 -f-204 -n google.com -Qr"
                            + " -s1:5+sm -As -d1 -s3+s -s5+s -q7"),
            new BypassStrategy("yt-ds-r34", R.string.strategy_yt_ds_r34,
                    // YouTube DS r34: два прохода fake + disorder/OOB.
                    "-n google.com -Qr -f-204 -a1 -As -s1:3+sm -a1 -As"
                            + " -s5:8+sm -a1 -As -d3 -q7 -o2 -f-43 -f-80"
                            + " -f-160 -r5 -Mh -As"),
            new BypassStrategy("test-speed-1", R.string.strategy_test_speed_1,
                    // Тест скорости 1: авто-режим с TTL-подменой в конце.
                    "-a4 -o2 --auto=t,r,a,s -d2 -o1 -a2 -An -f-1 -a2"
                            + " -Ar,s -o0+sm -a2 -At -r1+s -a2 --fake -1"
                            + " --ttl 10 -a2 -An"),
            new BypassStrategy("test-speed-2", R.string.strategy_test_speed_2,
                    // Тест скорости 2: fake TLS 204 + disorder на HTTP-записи.
                    "-o1 -a2 -An -n google.com -Qr -f-204 -s1:5+sm -a1 -As"
                            + " -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1"
                            + " -As -r5 -Mh -s1:5+s -s3:7+sm -a1"),
            new BypassStrategy("crazy-chel", R.string.strategy_crazy_chel,
                    // Crazy Chel: длинная комбинация disorder/OOB/fake.
                    "-a3 -f-200 -Qr -s3:5+sm -As -d1 -s4+sm -s8+sh -f-300"
                            + " -d6+sh -At,r,s -o2 -f-30 -As -r5 -Mh -r6+sh"
                            + " -f-250+sm -s2:7+s -s3:6+sm -At,r,s -s3:5+sm"
                            + " -s6+s -s7:9+s -q30+sm"),
            new BypassStrategy("ultimate-fix", R.string.strategy_ultimate_fix,
                    // Ultimate fix: несколько авто-проходов с TTL-подменой.
                    "-d6+s -q4+hm -o2 -a1 -An -a4 -o2 --auto=t,r,a,s -d2"
                            + " -o1 -a2 -An -f-1 -a2 -Ar,s -o0+sm -a2 -At"
                            + " -r1+s --fake -1 --ttl 6 -a2 -An"),
    };

    private final String id;
    private final int labelRes;
    private final List<String> args;

    private BypassStrategy(String id, int labelRes, String argSpec) {
        this.id = id;
        this.labelRes = labelRes;
        this.args = Collections.unmodifiableList(tokenize(argSpec));
    }

    String getId() {
        return id;
    }

    int getLabelRes() {
        return labelRes;
    }

    static BypassStrategy[] all() {
        return ALL.clone();
    }

    static BypassStrategy byId(String id) {
        if (id != null) {
            for (BypassStrategy strategy : ALL) {
                if (strategy.id.equals(id)) return strategy;
            }
        }
        return ALL[0];
    }

    /**
     * Аргументы стратегии, пригодные для прямой передачи в
     * {@code ByeDpiNative.createSocketWithCommandLine}.
     *
     * <p>Списки хостов из стратегии отбрасываются: byedpi обрабатывает только
     * первое вхождение {@code -H} ({@code main.c}: {@code if (dp->file_ptr)
     * continue;}), а список доменов в приложении — надмножество
     * YouTube-профиля из {@link BypassVpnService}. Поэтому стратегия не должна
     * ни подменять, ни сужать наш список.
     */
    List<String> getArgs() {
        List<String> result = new ArrayList<String>(args.size());
        for (String arg : args) {
            if (arg.startsWith("-H") || "$hostlist".equals(arg)) continue;
            result.add(arg);
        }
        return result;
    }

    /**
     * Задаёт ли стратегия собственный таймаут. Если да, базовый
     * {@code --timeout} не добавляется, чтобы не спорить со стратегией.
     */
    boolean hasTimeout() {
        for (String arg : args) {
            if ("--timeout".equals(arg)) return true;
            if (arg.startsWith("--")) continue;
            if (arg.length() > 1 && arg.charAt(1) == 't') return true;
        }
        return false;
    }

    /**
     * Запрещает ли стратегия UDP сам. Если да, базовый {@code --no-udp} не
     * добавляется: стратегия уже выразила это намерение.
     */
    boolean forbidsUdp() {
        for (String arg : args) {
            if ("-U".equals(arg) || "--no-udp".equals(arg)) return true;
        }
        return false;
    }

    /**
     * Оценка агрессивности: сколько раз стратегия вставляет дополнительные
     * пакеты. Только правило ничьей при равных замерах — побеждает наименее
     * агрессивная, потому что она меньше жрёт батарею и меньше рискует.
     *
     * <p>Считаются обе формы флагов: короткая ({@code -s1:5+sm}) и длинная
     * ({@code --split 0+sm}). Раньше считались только короткие, и дефолтная
     * стратегия набирала ноль, то есть всегда выигрывала ничью.
     */
    int getAggression() {
        int count = 0;
        for (String arg : args) {
            if (isDesyncFlag(arg)) count++;
        }
        return count;
    }

    private static boolean isDesyncFlag(String arg) {
        if (arg.length() < 2) return false;
        if (arg.startsWith("--")) {
            String name = arg.substring(2);
            int assign = name.indexOf('=');
            if (assign >= 0) name = name.substring(0, assign);
            return "split".equals(name) || "disorder".equals(name) || "oob".equals(name)
                    || "disoob".equals(name) || "fake".equals(name)
                    || "udp-fake".equals(name) || "tlsrec".equals(name)
                    || "mod-http".equals(name) || "auto".equals(name);
        }
        char flag = arg.charAt(1);
        return flag == 's' || flag == 'd' || flag == 'o' || flag == 'q'
                || flag == 'f' || flag == 'a' || flag == 'r' || flag == 'M';
    }

    /**
     * Разбивает спецификацию аргументов на токены, уважая двойные кавычки:
     * {@code -H:"a b c"} — один токен. Нужен для стратегий, где в одном флаге
     * перечислены несколько доменов или позиций сплита.
     */
    private static List<String> tokenize(String spec) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean has = false;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                has = true;
            } else if (!inQuotes && (c == ' ' || c == '\n' || c == '\r' || c == '\t')) {
                if (has) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    has = false;
                }
            } else {
                current.append(c);
                has = true;
            }
        }
        if (has) tokens.add(current.toString());
        return tokens;
    }
}
