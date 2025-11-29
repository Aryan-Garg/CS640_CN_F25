

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * Sender side of TCPend.
 * Uses:
 *  - MTU-based fragmentation (mss = mtu)
 *  - byte-based sequence numbers
 *  - one's complement checksum
 *  - RTT estimation & timeout per spec (ERTT/EDEV, alpha=0.875, beta=0.75)
 *  - fast retransmit on 3 duplicate ACKs
 *  - max 16 retransmissions per segment
 *  - spec-compliant logging & statistics
 */
public class Sender {

    static final int MAX_RETX = 16;

    static DatagramSocket sock;
    static InetAddress remoteAddr;
    static int remotePort;
    static int localPort;
    static String filename;
    static int mtu;
    static int sws;
    static int mss;

    // Sliding window state: key = seq (first byte)
    static Map<Integer, Packet> outstanding = new ConcurrentHashMap<>();
    static Map<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    static Map<Integer, Integer> retxCount = new ConcurrentHashMap<>();

    static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // RTT estimator and timeout (in nanoseconds)
    static double ERTT = 0.0; // nanoseconds
    static double EDEV = 0.0;
    static final double ALPHA = 0.875;
    static final double BETA = 0.75;
    static long timeoutNanos = 5_000_000_000L; // initial 5 seconds

    // stats
    static long startNano;
    static long dataBytesSent = 0;
    static int pktsSent = 0;
    static int pktsRcvd = 0;
    static int checksumErrors = 0;
    static int retransmissions = 0;
    static int duplicateAcks = 0;  // total duplicate ack events
    static Map<Integer,Integer> ackSeenCount = new ConcurrentHashMap<>();

    public static void run(Map<String,String> opt) throws Exception {
        localPort = Integer.parseInt(opt.get("-p"));
        remoteAddr = InetAddress.getByName(opt.get("-s"));
        remotePort = Integer.parseInt(opt.get("-a"));
        filename = opt.get("-f");
        mtu = Integer.parseInt(opt.get("-m"));
        sws = Integer.parseInt(opt.get("-c"));

        mss = mtu; // spec update: MTU is payload limit

        sock = new DatagramSocket(localPort);
        sock.setSoTimeout(1000);
        startNano = System.nanoTime();

        byte[] fileData = Files.readAllBytes(Paths.get(filename));
        int fileLen = fileData.length;

        // --- SYN (seq 0) ---
        Packet syn = new Packet();
        syn.seq = 0;
        syn.ack = 0;
        syn.ts = System.nanoTime();
        syn.clearFlags();
        syn.setFlag(Packet.FLAG_S);
        syn.setLength(0);
        syn.data = new byte[0];
        syn.computeChecksum();
        sendRawAndLog(syn, true, false);

        // wait for SYN-ACK
        Packet synAck = waitForFlags(Packet.FLAG_S | Packet.FLAG_A);
        if (synAck == null || !synAck.verifyChecksum()) {
            System.err.println("Failed to receive valid SYN-ACK");
            printStats(fileLen);
            return;
        }
        pktsRcvd++;
        logPacket("rcv", synAck, false);

        // RTT initial sample using this SYN-ACK. Spec: if S=0 in ACK, base case.
        rttSample(synAck.seq, synAck.ts);

        // --- optional third handshake ACK (ACK-only, seq=1, ack=1) ---
        Packet ack3 = new Packet();
        ack3.seq = 1;
        ack3.ack = 1;
        ack3.ts = System.nanoTime();
        ack3.clearFlags();
        ack3.setFlag(Packet.FLAG_A);
        ack3.setLength(0);
        ack3.data = new byte[0];
        ack3.computeChecksum();
        sendRawAndLog(ack3, true, false);

        // --- build data segments ---
        List<Packet> segments = new ArrayList<>();
        int offset = 0;
        int nextSeq = 1; // first data byte seq = 1
        while (offset < fileLen) {
            int take = Math.min(mss, fileLen - offset);
            Packet p = new Packet();
            p.seq = nextSeq;
            p.ack = 1; // we don't receive data from other side
            p.ts = System.nanoTime();
            p.clearFlags();
            p.setFlag(Packet.FLAG_A); // assignment update: data packets must have ACK set
            p.data = Arrays.copyOfRange(fileData, offset, offset + take);
            p.setLength(p.data.length);
            p.computeChecksum();
            segments.add(p);
            offset += take;
            nextSeq += take;
        }

        int base = 1;  // first unacked byte
        int sendIndex = 0;

        while (base <= fileLen) {
            // send new segments while window not full
            while (sendIndex < segments.size() && outstanding.size() < sws) {
                Packet p = segments.get(sendIndex);
                outstanding.put(p.seq, p);
                retxCount.put(p.seq, 0);
                sendPacketWithTimer(p, true);
                dataBytesSent += p.data.length;
                sendIndex++;
            }

            // receive ACKs
            Packet ackPkt = receivePacket();
            if (ackPkt == null) continue;
            pktsRcvd++;

            if (!ackPkt.verifyChecksum()) {
                checksumErrors++;
                continue;
            }
            logPacket("rcv", ackPkt, false);

            if (!ackPkt.isFlagA()) continue;

            int ackNum = ackPkt.ack;

            // count duplicates
            int seen = ackSeenCount.getOrDefault(ackNum, 0);
            if (seen > 0) duplicateAcks++;
            seen++;
            ackSeenCount.put(ackNum, seen);

            // RTT sample: ackPkt.seq = S, ackPkt.ts = T
            rttSample(ackPkt.seq, ackPkt.ts);

            // cumulative ACK: remove all segments whose end <= ackNum
            List<Integer> toRemove = new ArrayList<>();
            for (Map.Entry<Integer, Packet> e : outstanding.entrySet()) {
                Packet p = e.getValue();
                int end = p.seq + p.getLength();
                if (end <= ackNum) {
                    toRemove.add(e.getKey());
                }
            }
            for (Integer key : toRemove) {
                outstanding.remove(key);
                ScheduledFuture<?> f = timers.remove(key);
                if (f != null) f.cancel(false);
                retxCount.remove(key);
            }
            base = ackNum;

            // fast retransmit on 3 dup ACKs for this ackNum
            if (seen == 3) {
                // retransmit lowest outstanding seq
                if (!outstanding.isEmpty()) {
                    int lowest = Collections.min(outstanding.keySet());
                    Packet lost = outstanding.get(lowest);
                    fastRetransmit(lost);
                }
            }
        }

        // --- FIN ---
        Packet fin = new Packet();
        fin.seq = fileLen + 1;
        fin.ack = 1;
        fin.ts = System.nanoTime();
        fin.clearFlags();
        fin.setFlag(Packet.FLAG_F);
        fin.setFlag(Packet.FLAG_A);
        fin.setLength(0);
        fin.data = new byte[0];
        fin.computeChecksum();
        sendRawAndLog(fin, true, false);

        // we could optionally wait for final ACK, but spec doesn’t force it here

        printStats(fileLen);
    }

    // ---- helper methods ----

    private static void sendRawAndLog(Packet p, boolean isSend, boolean isData) throws IOException {
        byte[] b = p.toBytes();
        DatagramPacket dp = new DatagramPacket(b, b.length, remoteAddr, remotePort);
        sock.send(dp);
        pktsSent++;
        logPacket("snd", p, isData);
    }

    private static void sendPacketWithTimer(Packet p, boolean isData) throws IOException {
        p.ts = System.nanoTime();
        p.computeChecksum();
        sendRawAndLog(p, true, isData);
        scheduleTimeout(p.seq);
    }

    private static void retransmit(Packet p) {
        try {
            int seq = p.seq;
            int count = retxCount.getOrDefault(seq, 0) + 1;
            if (count > MAX_RETX) {
                System.err.println("ERROR: max retransmissions reached for seq=" + seq);
                printStats(-1);
                System.exit(1);
            }
            retxCount.put(seq, count);
            retransmissions++;
            p.ts = System.nanoTime();
            p.computeChecksum();
            byte[] b = p.toBytes();
            DatagramPacket dp = new DatagramPacket(b, b.length, remoteAddr, remotePort);
            sock.send(dp);
            pktsSent++;
            logPacket("snd", p, p.getLength() > 0);
            scheduleTimeout(seq);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void fastRetransmit(Packet p) {
        System.out.println("# fast retransmit seq=" + p.seq);
        retransmit(p);
    }

    private static void scheduleTimeout(int seq) {
        Runnable task = () -> {
            if (outstanding.containsKey(seq)) {
                retransmit(outstanding.get(seq));
            }
        };
        ScheduledFuture<?> old = timers.get(seq);
        if (old != null) old.cancel(false);
        long delay = Math.max(1_000_000L, timeoutNanos); // at least 1ms
        ScheduledFuture<?> f = scheduler.schedule(task, delay, TimeUnit.NANOSECONDS);
        timers.put(seq, f);
    }

    private static Packet receivePacket() throws IOException {
        byte[] buf = new byte[65536];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        try {
            sock.receive(dp);
            return Packet.fromBytes(dp.getData(), 0, dp.getLength());
        } catch (SocketTimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Packet waitForFlags(int flagsMask) throws IOException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 10_000) {
            Packet p = receivePacket();
            if (p == null) continue;
            if (!p.verifyChecksum()) continue;
            if ((p.lengthAndFlags & flagsMask) == flagsMask) return p;
        }
        return null;
    }

    // RTT: given ack with sequence S and timestamp T
    private static void rttSample(int S, long T) {
        long C = System.nanoTime();
        double sample = (double)(C - T); // nanoseconds
        if (ERTT == 0.0 || S == 0) {
            // base case per spec
            ERTT = sample;
            EDEV = 0.0;
            timeoutNanos = (long)(2.0 * ERTT);
        } else {
            double SRTT = sample;
            double SDEV = Math.abs(SRTT - ERTT);
            ERTT = ALPHA * ERTT + (1.0 - ALPHA) * SRTT;
            EDEV = BETA * EDEV + (1.0 - BETA) * SDEV;
            timeoutNanos = (long)(ERTT + 4.0 * EDEV);
        }
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

    private static void printStats(int fileLen) {
        System.out.println("===== Sender statistics =====");
        if (fileLen >= 0)
            System.out.println("Amount of Data transferred: " + fileLen + " bytes");
        System.out.println("Number of packets sent: " + pktsSent);
        System.out.println("Number of packets received: " + pktsRcvd);
        System.out.println("Number of out-of-sequence packets discarded: 0"); // sender doesn't discard data packets
        System.out.println("Number of packets discarded due to incorrect checksum: " + checksumErrors);
        System.out.println("Number of retransmissions: " + retransmissions);
        System.out.println("Number of duplicate acknowledgements: " + duplicateAcks);
    }
}
