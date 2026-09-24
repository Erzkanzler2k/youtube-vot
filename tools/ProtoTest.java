/*
 * ProtoTest — десктопный тест чистой протокольной логики BypassProto.
 * Запуск (JDK из Android Studio):
 *   javac -d tools/out tools/ProtoTest.java src/java/com/vot/youtube/BypassProto.java
 *   java -cp tools/out ProtoTest
 * Выход 0 = все проверки прошли.
 */
import com.vot.youtube.BypassProto;
import com.vot.youtube.BypassProto.*;

import java.io.ByteArrayOutputStream;

public class ProtoTest {
    private static int checks = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("FAIL: " + name);
        } else {
            System.out.println("ok:   " + name);
        }
    }

    /** Собирает синтетический TLS ClientHello с SNI = host. */
    private static byte[] buildClientHello(String host) {
        byte[] h = host.getBytes();
        int sidLen = 32;
        int csLen = 2;
        int bodyLen = 2 + 32 + 1 + sidLen + 2 + csLen + 1 + 1 + 2 + (2 + 1 + 2 + h.length) + 2 + 4 + 2 + 2 + 4;
        // extensions: server_name(2+2+data) + supported_groups(2+2+4)
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x03); body.write(0x03);                 // legacy_version
        for (int i = 0; i < 32; i++) body.write(0x11 + i);  // random
        body.write(sidLen);                                  // sid len
        for (int i = 0; i < sidLen; i++) body.write(i);
        body.write(0x00); body.write(0x02);                 // cipher suites len
        body.write(0x13); body.write(0x01);                 // AES_128_GCM_SHA256
        body.write(0x01); body.write(0x00);                 // compression: len1, null
        // extensions length = server_name data(2+1+2+h) + 4hdr + supported_groups data(4) + 4hdr
        int extLen = (2 + 1 + 2 + h.length) + 4 + 4 + 4;
        body.write((byte) (extLen >>> 8)); body.write((byte) (extLen & 0xFF));
        // server_name
        body.write(0x00); body.write(0x00);                 // type
        int snDataLen = 2 + 1 + 2 + h.length;
        body.write((byte) (snDataLen >>> 8)); body.write((byte) (snDataLen & 0xFF));
        body.write((byte) (snDataLen >>> 8)); body.write((byte) (snDataLen & 0xFF)); // list len = dataLen-2
        body.write(0x00);                                   // name_type = host_name
        body.write((byte) (h.length >>> 8)); body.write((byte) (h.length & 0xFF));
        body.write(h, 0, h.length);
        // supported_groups
        body.write(0x00); body.write(0x0A);
        body.write(0x00); body.write(0x02);
        body.write(0x00); body.write(0x04);
        body.write(0x00); body.write(0x1D);

        byte[] bodyB = body.toByteArray();
        // handshake header
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x16);                                     // record: handshake
        out.write(0x03); out.write(0x01);                    // record version
        out.write((byte) ((bodyB.length + 4) >>> 8)); out.write((byte) ((bodyB.length + 4) & 0xFF));
        out.write(0x01);                                     // ClientHello
        out.write((byte) (bodyB.length >>> 16));
        out.write((byte) (bodyB.length >>> 8));
        out.write((byte) (bodyB.length & 0xFF));
        out.write(bodyB, 0, bodyB.length);
        return out.toByteArray();
    }

    public static void main(String[] args) {
        // 1. SNI-парсер
        byte[] ch = buildClientHello("youtube.com");
        check("clienthello size > 100", ch.length > 100);

        Sni sni = BypassProto.parseClientHelloSni(ch, 0, ch.length);
        check("sni parsed", sni != null);
        if (sni != null) {
            check("sni host=youtube.com", "youtube.com".equals(sni.host));
            // байты по смещению nameOff действительно равны имени
            boolean bytesMatch = true;
            for (int i = 0; i < sni.nameLen; i++) {
                if ((ch[sni.nameOff + i] & 0xFF) != "youtube.com".charAt(i)) bytesMatch = false;
            }
            check("sni offset points at name", bytesMatch && sni.nameLen == 11);
        }

        // 2. Матчинг суффиксов
        String[] blocked = {"youtube.com", "googlevideo.com", "google.com"};
        check("www.youtube.com blocked", BypassProto.matchesBlocked("www.youtube.com", blocked));
        check("youtube.com blocked", BypassProto.matchesBlocked("youtube.com", blocked));
        check("r5---sn-xx.googlevideo.com blocked",
                BypassProto.matchesBlocked("r5---sn-xx.googlevideo.com", blocked));
        check("example.org NOT blocked", !BypassProto.matchesBlocked("example.org", blocked));
        check("notyoutube.com NOT blocked", !BypassProto.matchesBlocked("notyoutube.com", blocked));
        check("empty host NOT blocked", !BypassProto.matchesBlocked("", blocked));
        check("null host NOT blocked", !BypassProto.matchesBlocked(null, blocked));

        // 3. Сборка/разбор TCP-пакета + контрольные суммы
        byte[] tcpPkt = new byte[40 + 64];
        byte[] payload = new byte[64];
        for (int i = 0; i < 64; i++) payload[i] = (byte) i;
        int srcIp = 0x7F000001, dstIp = 0x0A090002;
        int n = BypassProto.buildTcpPacket(tcpPkt, srcIp, dstIp,
                40000, 443, 0x01020304L, 0x05060708L,
                BypassProto.FLAG_ACK | BypassProto.FLAG_PSH, payload, 0, 64);
        check("tcp packet total=104", n == 104);

        Ip4 ip = BypassProto.parseIp4(tcpPkt, 0, n);
        check("ip4 parse ok", ip.ok && ip.protocol == 6 && ip.payloadLen == 84);
        check("ip src/dst", ip.srcIp == srcIp && ip.dstIp == dstIp);
        Tcp4 tcp = BypassProto.parseTcp(tcpPkt, ip.payloadOff, ip.payloadLen);
        check("tcp parse ok", tcp.ok && tcp.srcPort == 40000 && tcp.dstPort == 443);
        check("tcp seq/ack", tcp.seq == 0x01020304L && tcp.ack == 0x05060708L);
        check("tcp flags ack+psh", tcp.ackFlag && tcp.psh);

        // контрольная сумма IP: обнулим поле и пересчитаем — должно совпасть
        byte[] ipCopy = tcpPkt.clone();
        ipCopy[10] = 0;
        ipCopy[11] = 0;
        BypassProto.setIpChecksum(ipCopy, 0, 20);
        check("ip checksum valid", ipCopy[10] == tcpPkt[10] && ipCopy[11] == tcpPkt[11]);

        // контрольная сумма TCP
        byte[] tcpCopy = tcpPkt.clone();
        tcpCopy[36] = 0;
        tcpCopy[37] = 0;
        BypassProto.setTcpChecksum(tcpCopy, 20, 84, srcIp, dstIp);
        check("tcp checksum valid", tcpCopy[36] == tcpPkt[36] && tcpCopy[37] == tcpPkt[37]);

        // 4. Сборка/разбор UDP-пакета
        byte[] udpPkt = new byte[28 + 12];
        byte[] dnsQ = new byte[12];
        for (int i = 0; i < 12; i++) dnsQ[i] = (byte) (0xA0 + i);
        int un = BypassProto.buildUdpPacket(udpPkt, srcIp, dstIp, 53000, 53, dnsQ, 0, 12);
        check("udp packet total=40", un == 40);
        Ip4 uip = BypassProto.parseIp4(udpPkt, 0, un);
        check("udp ip proto=17", uip.ok && uip.protocol == 17);
        Udp4 udp = BypassProto.parseUdp(udpPkt, uip.payloadOff, uip.payloadLen);
        check("udp parse", udp.ok && udp.srcPort == 53000 && udp.dstPort == 53 && udp.payloadLen == 12);

        byte[] udpCopy = udpPkt.clone();
        udpCopy[26] = 0;
        udpCopy[27] = 0;
        BypassProto.setUdpChecksum(udpCopy, 20, 20, srcIp, dstIp);
        check("udp checksum valid", udpCopy[26] == udpPkt[26] && udpCopy[27] == udpPkt[27]);

        // 5. Битый/укороченный ввод не роняет парсер
        check("empty parse", !BypassProto.parseIp4(new byte[5], 0, 5).ok);
        check("ipv6 dropped", !BypassProto.parseIp4(new byte[]{0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 0, 20).ok);
        check("intact packet NOT fragment", !BypassProto.isIpFragment(tcpPkt, 0));
        byte[] frag = tcpPkt.clone();
        frag[6] = 0x20; // MF
        check("mf detected", BypassProto.isIpFragment(frag, 0));

        System.out.println("----");
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}