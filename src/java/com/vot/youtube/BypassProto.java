/*
 * BypassProto — чистая логика протоколов (IPv4/TCP/UDP/TLS-SNI) для движка
 * обхода блокировок. НЕ использует Android API — этот класс тестируется
 * на десктопе (tools/ProtoTest.java) обычным javac/java.
 *
 * Java 8-совместимый код, без AndroidX.
 */
package com.vot.youtube;

public final class BypassProto {

    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;

    private BypassProto() {
    }

    /* ---------------- базовые helpers ---------------- */

    public static int readInt(byte[] p, int off) {
        return ((p[off] & 0xFF) << 24) | ((p[off + 1] & 0xFF) << 16)
                | ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
    }

    public static long readUInt(byte[] p, int off) {
        return readInt(p, off) & 0xFFFFFFFFL;
    }

    public static void writeInt(byte[] p, int off, int v) {
        p[off] = (byte) (v >>> 24);
        p[off + 1] = (byte) (v >>> 16);
        p[off + 2] = (byte) (v >>> 8);
        p[off + 3] = (byte) (v & 0xFF);
    }

    public static void writeUInt(byte[] p, int off, long v) {
        writeInt(p, off, (int) v);
    }

    public static String ipToString(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "."
                + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    /* ---------------- IPv4 ---------------- */

    public static final class Ip4 {
        public boolean ok;
        public int headerLen;
        public int totalLen;
        public int protocol;
        public int srcIp;
        public int dstIp;
        public int payloadOff;
        public int payloadLen;
    }

    /** Разбирает IPv4-пакет из буфера. off — начало пакета, len — доступно байт. */
    public static Ip4 parseIp4(byte[] p, int off, int len) {
        Ip4 r = new Ip4();
        int avail = len - off;
        if (avail < 20) return r;
        int verIhl = p[off] & 0xFF;
        if ((verIhl >>> 4) != 4) return r; // IPv6 или мусор — не поддерживаем
        int ih = (verIhl & 0x0F) << 2;
        if (ih < 20 || ih > avail) return r;
        int total = ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
        if (total < ih || total > avail) return r; // битый/склеенный буфер
        r.ok = true;
        r.headerLen = ih;
        r.totalLen = total;
        r.protocol = p[off + 9] & 0xFF;
        r.srcIp = readInt(p, off + 12);
        r.dstIp = readInt(p, off + 16);
        r.payloadOff = off + ih;
        r.payloadLen = total - ih;
        return r;
    }

    /**
     * IP-фрагмент? (offset != 0 или MF). Пока IP-пересборку не делаем —
     * фрагментированные пакеты дропаем.
     */
    public static boolean isIpFragment(byte[] p, int off) {
        int fo = ((p[off + 6] & 0x1F) << 8) | (p[off + 7] & 0xFF);
        if ((p[off + 6] & 0x20) != 0) return true; // MF
        return fo != 0;
    }

    /* ---------------- TCP ---------------- */

    public static final class Tcp4 {
        public boolean ok;
        public int srcPort;
        public int dstPort;
        public long seq;
        public long ack;
        public int hdrLen;
        public boolean syn;
        public boolean ackFlag;
        public boolean fin;
        public boolean rst;
        public boolean psh;
        public int payloadOff;
        public int payloadLen;
        /** MSS из TCP-опций клиента (0, если опции не присланы). */
        public int mss;
        /** Window Scale из TCP-опций клиента (0, если scaling не предложен). */
        public int wscale;
    }

    /** Разбирает TCP-сегмент. off — начало TCP-заголовка (уже после IPv4). */
    public static Tcp4 parseTcp(byte[] p, int off, int len) {
        Tcp4 r = new Tcp4();
        if (len < 20) return r;
        r.srcPort = ((p[off] & 0xFF) << 8) | (p[off + 1] & 0xFF);
        r.dstPort = ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
        r.seq = readUInt(p, off + 4);
        r.ack = readUInt(p, off + 8);
        int hl = (p[off + 12] >>> 4) & 0x0F;
        r.hdrLen = hl << 2;
        if (r.hdrLen < 20 || r.hdrLen > len) return r;
        int flags = p[off + 13] & 0xFF;
        r.syn = (flags & 0x02) != 0;
        r.ackFlag = (flags & 0x10) != 0;
        r.fin = (flags & 0x01) != 0;
        r.rst = (flags & 0x04) != 0;
        r.psh = (flags & 0x08) != 0;
        r.payloadOff = off + r.hdrLen;
        r.payloadLen = len - r.hdrLen;
        r.ok = true;

        // Опции TCP (MSS kind=2, Window Scale kind=3) — нужны для корректного
        // SYN+ACK: без MSS клиент урежет сегменты до 536, без WS окно ≤ 64 КБ.
        r.mss = 0;
        r.wscale = 0;
        if (r.hdrLen > 20) {
            int o = off + 20;
            int end = off + r.hdrLen;
            while (o + 1 < end) {
                int kind = p[o] & 0xFF;
                if (kind == 0) break; // EOL
                if (kind == 1) { // NOP
                    o++;
                    continue;
                }
                int olen = p[o + 1] & 0xFF;
                if (olen < 2 || o + olen > end) break;
                if (kind == 2 && olen == 4) {
                    r.mss = ((p[o + 2] & 0xFF) << 8) | (p[o + 3] & 0xFF);
                } else if (kind == 3 && olen == 3) {
                    r.wscale = p[o + 2] & 0xFF;
                }
                o += olen;
            }
        }
        return r;
    }

    /* ---------------- UDP ---------------- */

    public static final class Udp4 {
        public boolean ok;
        public int srcPort;
        public int dstPort;
        public int payloadOff;
        public int payloadLen;
    }

    public static Udp4 parseUdp(byte[] p, int off, int len) {
        Udp4 r = new Udp4();
        if (len < 8) return r;
        r.srcPort = ((p[off] & 0xFF) << 8) | (p[off + 1] & 0xFF);
        r.dstPort = ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
        r.payloadOff = off + 8;
        r.payloadLen = len - 8;
        r.ok = true;
        return r;
    }

    /* ---------------- контрольные суммы ---------------- */

    private static long sumWords(byte[] p, int off, int len) {
        long s = 0;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            s += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) s += (p[i] & 0xFF) << 8;
        return s;
    }

    private static int finishSum(long s) {
        while ((s >>> 16) != 0) s = (s & 0xFFFF) + (s >>> 16);
        long v = (~s) & 0xFFFF;
        return (int) v;
    }

    /** Считает контрольную сумму TCP/UDP сегмента с pseudo-заголовком IPv4. */
    private static int tcpUdpChecksum(byte[] p, int segOff, int segLen, int srcIp, int dstIp, int proto) {
        long s = sumWords(p, segOff, segLen);
        s += (((srcIp >>> 16) & 0xFFFF) + (srcIp & 0xFFFF));
        s += (((dstIp >>> 16) & 0xFFFF) + (dstIp & 0xFFFF));
        s += proto + segLen;
        return finishSum(s);
    }

    /** Ставит IP checksum (поле уже должно быть нулём). */
    public static void setIpChecksum(byte[] p, int off, int hdrLen) {
        int c = finishSum(sumWords(p, off, hdrLen));
        p[off + 10] = (byte) (c >>> 8);
        p[off + 11] = (byte) (c & 0xFF);
    }

    /** Ставит TCP checksum. Поле cksum в сегменте должно быть нулём. */
    public static void setTcpChecksum(byte[] p, int segOff, int segLen, int srcIp, int dstIp) {
        int c = tcpUdpChecksum(p, segOff, segLen, srcIp, dstIp, PROTO_TCP);
        p[segOff + 16] = (byte) (c >>> 8);
        p[segOff + 17] = (byte) (c & 0xFF);
    }

    /** Ставит UDP checksum (для IPv4 допустим даже ноль, но считаем честно). */
    public static void setUdpChecksum(byte[] p, int segOff, int segLen, int srcIp, int dstIp) {
        if (segLen < 8) return;
        int c = tcpUdpChecksum(p, segOff, segLen, srcIp, dstIp, PROTO_UDP);
        p[segOff + 6] = (byte) (c >>> 8);
        p[segOff + 7] = (byte) (c & 0xFF);
    }

    /* ---------------- сборка пакетов ---------------- */

    /** Флаги TCP: 0x02 SYN, 0x01 FIN, 0x04 RST, 0x10 ACK, 0x08 PSH. */
    public static final int FLAG_FIN = 0x01;
    public static final int FLAG_SYN = 0x02;
    public static final int FLAG_RST = 0x04;
    public static final int FLAG_PSH = 0x08;
    public static final int FLAG_ACK = 0x10;

    /** Собирает IPv4+TCP пакет в buf (buf должен быть >= 40 + payloadLen). */
    public static int buildTcpPacket(byte[] buf, int srcIp, int dstIp,
                                     int srcPort, int dstPort,
                                     long seq, long ack, int flags,
                                     byte[] payload, int payloadOff, int payloadLen) {
        return buildTcpPacketCommon(buf, srcIp, dstIp, srcPort, dstPort,
                seq, ack, flags, 0, 0, payload, payloadOff, payloadLen);
    }

    /** Как buildTcpPacket, но с опциями MSS и Window Scale (для SYN+ACK).
     *  buf должен быть >= 48 + payloadLen. */
    public static int buildTcpPacketOpt(byte[] buf, int srcIp, int dstIp,
                                        int srcPort, int dstPort,
                                        long seq, long ack, int flags,
                                        int mss, int wscale,
                                        byte[] payload, int payloadOff, int payloadLen) {
        return buildTcpPacketCommon(buf, srcIp, dstIp, srcPort, dstPort,
                seq, ack, flags, mss, wscale, payload, payloadOff, payloadLen);
    }

    private static int buildTcpPacketCommon(byte[] buf, int srcIp, int dstIp,
                                            int srcPort, int dstPort,
                                            long seq, long ack, int flags,
                                            int mss, int wscale,
                                            byte[] payload, int payloadOff, int payloadLen) {
        int optLen = 0;
        if (mss > 0) optLen += 4;    // MSS: kind(1)+len(1)+value(2)
        if (wscale > 0) optLen += 4; // WS: kind(1)+len(1)+value(1)+NOP(1) — выравнивание по 4
        int tcpHdr = 20 + optLen;
        int total = 20 + tcpHdr + payloadLen;

        // IPv4-заголовок (20 байт)
        buf[0] = 0x45;
        buf[1] = 0;
        buf[2] = (byte) (total >>> 8);
        buf[3] = (byte) (total & 0xFF);
        buf[4] = 0;
        buf[5] = 0;
        buf[6] = 0;
        buf[7] = 0;
        buf[8] = 64; // TTL
        buf[9] = PROTO_TCP;
        buf[10] = 0;
        buf[11] = 0; // ip checksum (заполним)
        writeInt(buf, 12, srcIp);
        writeInt(buf, 16, dstIp);

        // TCP-заголовок
        buf[20] = (byte) (srcPort >>> 8);
        buf[21] = (byte) (srcPort & 0xFF);
        buf[22] = (byte) (dstPort >>> 8);
        buf[23] = (byte) (dstPort & 0xFF);
        writeUInt(buf, 24, seq);
        writeUInt(buf, 28, ack);
        buf[32] = (byte) ((tcpHdr >>> 2) << 4); // data offset (в 32-битных словах)
        buf[33] = (byte) (flags & 0xFF);
        buf[34] = (byte) (0xFFFF >>> 8); // window 65535
        buf[35] = (byte) (0xFFFF & 0xFF);
        buf[36] = 0;
        buf[37] = 0; // tcp checksum (заполним)
        buf[38] = 0;
        buf[39] = 0; // urgent

        int o = 40;
        if (mss > 0) {
            buf[o] = 2;
            buf[o + 1] = 4;
            buf[o + 2] = (byte) (mss >>> 8);
            buf[o + 3] = (byte) (mss & 0xFF);
            o += 4;
        }
        if (wscale > 0) {
            buf[o] = 3;
            buf[o + 1] = 3;
            buf[o + 2] = (byte) (wscale & 0xFF);
            buf[o + 3] = 1; // NOP padding
        }

        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOff, buf, 20 + tcpHdr, payloadLen);
        }
        setTcpChecksum(buf, 20, tcpHdr + payloadLen, srcIp, dstIp);
        setIpChecksum(buf, 0, 20);
        return total;
    }

    /** Собирает IPv4+UDP пакет в buf (buf >= 28 + payloadLen). */
    public static int buildUdpPacket(byte[] buf, int srcIp, int dstIp,
                                     int srcPort, int dstPort,
                                     byte[] payload, int payloadOff, int payloadLen) {
        int total = 28 + payloadLen;

        buf[0] = 0x45;
        buf[1] = 0;
        buf[2] = (byte) (total >>> 8);
        buf[3] = (byte) (total & 0xFF);
        buf[4] = 0;
        buf[5] = 0;
        buf[6] = 0;
        buf[7] = 0;
        buf[8] = 64;
        buf[9] = PROTO_UDP;
        buf[10] = 0;
        buf[11] = 0;
        writeInt(buf, 12, srcIp);
        writeInt(buf, 16, dstIp);

        buf[20] = (byte) (srcPort >>> 8);
        buf[21] = (byte) (srcPort & 0xFF);
        buf[22] = (byte) (dstPort >>> 8);
        buf[23] = (byte) (dstPort & 0xFF);
        buf[24] = (byte) (total - 20 >>> 8); // udp length = 8 + payloadLen
        buf[25] = (byte) ((total - 20) & 0xFF);
        buf[26] = 0;
        buf[27] = 0; // udp checksum

        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOff, buf, 28, payloadLen);
        }
        setUdpChecksum(buf, 20, 8 + payloadLen, srcIp, dstIp);
        setIpChecksum(buf, 0, 20);
        return total;
    }

    /* ---------------- TLS ClientHello -> SNI ---------------- */

    public static final class Sni {
        public String host;
        public int nameOff; // смещение первого байта имени хоста относительно начала TLS-записи
        public int nameLen;
    }

    /**
     * Достаёт SNI (server_name) из TLS ClientHello. Возвращает null, если
     * данных мало, это не ClientHello, или имя невалидное.
     * p — буфер, off — начало TLS-записи (0x16...), len — доступно байт.
     */
    public static Sni parseClientHelloSni(byte[] p, int off, int len) {
        if (len < 5 || (p[off] & 0xFF) != 0x16) return null; // не handshake-запись
        int recLen = ((p[off + 3] & 0xFF) << 8) | (p[off + 4] & 0xFF);
        if (recLen + 5 > len) return null; // запись не целиком
        int hs = off + 5;
        int hsType = p[hs] & 0xFF;
        if (hsType != 0x01) return null; // не ClientHello
        if (hs + 4 > off + len) return null;
        int body = hs + 4;
        // legacy_version(2) + random(32)
        int pos = body + 34;
        if (pos + 1 > off + len) return null;
        int sidLen = p[pos] & 0xFF;
        pos += 1 + sidLen;
        if (pos + 2 > off + len) return null;
        int csLen = ((p[pos] & 0xFF) << 8) | (p[pos + 1] & 0xFF);
        pos += 2 + csLen;
        if (pos + 1 > off + len) return null;
        int compLen = p[pos] & 0xFF;
        pos += 1 + compLen;
        if (pos + 2 > off + len) return null;
        int extLen = ((p[pos] & 0xFF) << 8) | (p[pos + 1] & 0xFF);
        pos += 2;
        int extEnd = Math.min(pos + extLen, off + len);
        while (pos + 4 <= extEnd) {
            int type = ((p[pos] & 0xFF) << 8) | (p[pos + 1] & 0xFF);
            int elen = ((p[pos + 2] & 0xFF) << 8) | (p[pos + 3] & 0xFF);
            int eoff = pos + 4;
            if (eoff + elen > extEnd) return null; // расширение обрезано
            if (type == 0x0000) { // server_name
                if (eoff + 5 <= extEnd) {
                    int nt = p[eoff + 2] & 0xFF;
                    int nl = ((p[eoff + 3] & 0xFF) << 8) | (p[eoff + 4] & 0xFF);
                    if (nt == 0 && nl > 0 && eoff + 5 + nl <= off + len) {
                        Sni s = new Sni();
                        s.nameOff = eoff + 5 - off;
                        s.nameLen = nl;
                        StringBuilder sb = new StringBuilder(nl);
                        boolean bad = false;
                        for (int i = 0; i < nl; i++) {
                            char c = (char) (p[eoff + 5 + i] & 0xFF);
                            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z')
                                    && (c < '0' || c > '9') && c != '.' && c != '-' && c != '_') {
                                bad = true;
                                break;
                            }
                            sb.append(c);
                        }
                        if (bad) return null;
                        s.host = sb.toString();
                        return s;
                    }
                }
                return null;
            }
            pos = eoff + elen;
        }
        return null; // SNI-расширения нет (или записи больше)
    }

    /* ---------------- матчинг заблокированных доменов ---------------- */

    /** Хост соответствует суффиксу (точное имя или любой поддомен). */
    public static boolean matchesBlocked(String host, String[] suffixes) {
        if (host == null || host.isEmpty()) return false;
        String h = host;
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        for (String suf0 : suffixes) {
            if (suf0 == null) continue;
            String su = suf0.trim();
            if (su.isEmpty()) continue;
            if (su.startsWith(".")) su = su.substring(1);
            if (h.equalsIgnoreCase(su)) return true;
            if (h.length() > su.length() && h.regionMatches(true,
                    h.length() - su.length() - 1, "." + su, 0, su.length() + 1)) {
                return true;
            }
        }
        return false;
    }
}