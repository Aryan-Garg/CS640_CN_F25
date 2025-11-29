

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Receiver side of TCPend.
 * - Creates connection state on first segment with seq == 0 (SYN)
 * - Buffers out-of-order data but only delivers contiguous bytes
 * - Sends cumulative ACKs (ack = next expected byte)
 * - For each ACK, copies the timestamp of the latest data segment that caused that ACK
 * - Logs sends/receives in required format
 * - Prints stats at end
 */
public class Receiver {

    static DatagramSocket sock;
    static int localPort;
    static String outFilename;
    static int mtu;
    static int sws;

    static long startNano;

    // delivery state
    static int expected = 0; // next expected byte
    static TreeMap<Integer, Packet> buffer = new TreeMap<>();

    // stats
    static int pktsRcvd = 0;
    static int pktsSent = 0;
    static long dataBytesDelivered = 0;
    static int outOfSeqDiscarded = 0;    // seq < expected -> discarded as dup
    static int checksumDiscarded = 0;

    public static void run(Map<String,String> opt) throws Exception {
        localPort = Integer.parseInt(opt.get("-p"));
        outFilename = opt.get("-f");
        mtu = Integer.parseInt(opt.get("-m"));
        sws = Integer.parseInt(opt.get("-c"));

        sock = new DatagramSocket(localPort);
        startNano = System.nanoTime();

        System.out.println("Receiver listening on port " + localPort +
                ", writing to " + outFilename);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (true) {
            byte[] buf = new byte[65536];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            sock.receive(dp);
            pktsRcvd++;
            Packet p;
            try {
                p = Packet.fromBytes(dp.getData(), 0, dp.getLength());
            } catch (Exception e) {
                // malformed
                continue;
            }
            if (!p.verifyChecksum()) {
                checksumDiscarded++;
                continue;
            }

            boolean isData = p.getLength() > 0;
            logPacket("rcv", p, isData);

            // Connection init on SYN with seq==0
            if (p.isFlagS() && p.seq == 0) {
                expected = 1; // after SYN, next expected byte
                Packet synAck = new Packet();
                synAck.seq = 0;
                synAck.ack = expected;
                synAck.ts = p.ts; // copy timestamp from SYN
                synAck.clearFlags();
                synAck.setFlag(Packet.FLAG_S);
                synAck.setFlag(Packet.FLAG_A);
                synAck.setLength(0);
                synAck.data = new byte[0];
                synAck.computeChecksum();
                sendTo(synAck, dp.getAddress(), dp.getPort(), false);
                continue;
            }

            // FIN handling: send ACK+FIN back and terminate
            if (p.isFlagF()) {
                Packet finAck = new Packet();
                finAck.seq = 0;
                finAck.ack = p.seq + 1;  // simple
                finAck.ts = p.ts;
                finAck.clearFlags();
                finAck.setFlag(Packet.FLAG_A);
                finAck.setFlag(Packet.FLAG_F);
                finAck.setLength(0);
                finAck.data = new byte[0];
                finAck.computeChecksum();
                sendTo(finAck, dp.getAddress(), dp.getPort(), false);

                // write file and print stats
                Files.write(Paths.get(outFilename), out.toByteArray());
                printStats();
                return;
            }

            // Data packet
            int seq = p.seq;
            int len = p.getLength();

            if (len == 0) {
                // pure ACK from sender in some weird direction; ignore
            } else if (seq == expected) {
                // in-order
                out.write(p.data);
                dataBytesDelivered += len;
                expected += len;
                // consume buffered immediately-following segments
                Iterator<Map.Entry<Integer, Packet>> it = buffer.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, Packet> e = it.next();
                    Packet bp = e.getValue();
                    if (bp.seq == expected) {
                        out.write(bp.data);
                        dataBytesDelivered += bp.getLength();
                        expected += bp.getLength();
                        it.remove();
                    } else {
                        break;
                    }
                }
            } else if (seq > expected) {
                // out-of-order: buffer
                if (!buffer.containsKey(seq)) {
                    buffer.put(seq, p);
                }
            } else {
                // seq < expected -> duplicate segment, discarded
                outOfSeqDiscarded++;
            }

            // send cumulative ACK with timestamp = timestamp of latest data causing this ack
            Packet ack = new Packet();
            ack.seq = 0;
            ack.ack = expected;
            ack.ts = p.ts; // spec: copy timestamp of latest received packet that causes this ACK
            ack.clearFlags();
            ack.setFlag(Packet.FLAG_A);
            ack.setLength(0);
            ack.data = new byte[0];
            ack.computeChecksum();
            sendTo(ack, dp.getAddress(), dp.getPort(), false);
        }
    }

    private static void sendTo(Packet p, InetAddress addr, int port, boolean isData) throws IOException {
        byte[] b = p.toBytes();
        DatagramPacket dp = new DatagramPacket(b, b.length, addr, port);
        sock.send(dp);
        pktsSent++;
        logPacket("snd", p, isData);
    }

    private static void logPacket(String dir, Packet p, boolean isData) {
        double t = (System.nanoTime() - startNano) / 1e9;
        String flags = buildFlagString(p, isData);
        int bytes = p.getLength();
        System.out.printf("%s %.3f %s %d %d %d%n",
                dir, t, flags, p.seq, bytes, p.ack);
    }

    private static String buildFlagString(Packet p, boolean isData) {
        String s = p.isFlagS() ? "S" : "-";
        String a = p.isFlagA() ? "A" : "-";
        String f = p.isFlagF() ? "F" : "-";
        String d = isData ? "D" : "-";
        return s + " " + a + " " + f + " " + d;
    }

    private static void printStats() {
        System.out.println("===== Receiver statistics =====");
        System.out.println("Amount of Data received: " + dataBytesDelivered + " bytes");
        System.out.println("Number of packets sent: " + pktsSent);
        System.out.println("Number of packets received: " + pktsRcvd);
        System.out.println("Number of out-of-sequence packets discarded: " + outOfSeqDiscarded);
        System.out.println("Number of packets discarded due to incorrect checksum: " + checksumDiscarded);
        System.out.println("Number of retransmissions: 0"); // receiver doesn't retransmit data
        System.out.println("Number of duplicate acknowledgements: 0"); // by definition here
    }
}
