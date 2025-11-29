

import java.nio.ByteBuffer;

/**
 * Packet format:
 *   seq (4 bytes)
 *   ack (4 bytes)
 *   timestamp (8 bytes, System.nanoTime())
 *   lengthAndFlags (4 bytes): upper 29 bits = length, lower 3 bits = flags
 *   checksum (2 bytes, one's complement over whole packet with checksum=0)
 *   data[length]
 *
 * Flags bits (lower 3 bits of lengthAndFlags):
 *   bit2: S, bit1: F, bit0: A
 */
public class Packet {
    public int seq;
    public int ack;
    public long ts;
    public int lengthAndFlags;
    public short checksum;
    public byte[] data;

    public static final int FLAG_S = 1 << 2;
    public static final int FLAG_F = 1 << 1;
    public static final int FLAG_A = 1 << 0;

    public Packet() {}

    public int getLength() {
        return (lengthAndFlags >>> 3) & 0x1FFFFFFF; // 29 bits
    }
    public void setLength(int len) {
        lengthAndFlags = ((len & 0x1FFFFFFF) << 3) | (lengthAndFlags & 0x7);
    }
    public void clearFlags() {
        lengthAndFlags = (lengthAndFlags & (~0x7));
    }
    public void setFlag(int flag) {
        lengthAndFlags = lengthAndFlags | (flag & 0x7);
    }
    public boolean isFlagS() { return (lengthAndFlags & FLAG_S) != 0; }
    public boolean isFlagF() { return (lengthAndFlags & FLAG_F) != 0; }
    public boolean isFlagA() { return (lengthAndFlags & FLAG_A) != 0; }

    private byte[] toBytesWithChecksum(short checksumValue) {
        int len = getLength();
        int dataLen = (data == null ? 0 : data.length);
        if (len != dataLen) len = dataLen; // sanity
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 8 + 4 + 2 + len);
        bb.putInt(seq);
        bb.putInt(ack);
        bb.putLong(ts);
        int laf = ((len & 0x1FFFFFFF) << 3) | (lengthAndFlags & 0x7);
        bb.putInt(laf);
        bb.putShort(checksumValue);
        if (len > 0) {
            bb.put(data, 0, len);
        }
        return bb.array();
    }

    public byte[] toBytes() {
        return toBytesWithChecksum(checksum);
    }

    public void computeChecksum() {
        byte[] tmp = toBytesWithChecksum((short)0);
        this.checksum = onesComplementChecksum(tmp);
    }

    public static Packet fromBytes(byte[] buf, int off, int len) throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        Packet p = new Packet();
        p.seq = bb.getInt();
        p.ack = bb.getInt();
        p.ts = bb.getLong();
        p.lengthAndFlags = bb.getInt();
        p.checksum = bb.getShort();
        int dataLen = p.getLength();
        if (dataLen < 0 || dataLen > bb.remaining()) {
            throw new Exception("Invalid data length in header");
        }
        if (dataLen > 0) {
            p.data = new byte[dataLen];
            bb.get(p.data);
        } else {
            p.data = new byte[0];
        }
        return p;
    }

    public boolean verifyChecksum() {
        byte[] tmp = toBytesWithChecksum((short)0);
        short c = onesComplementChecksum(tmp);
        return c == this.checksum;
    }

    /** Appendix A one's complement checksum */
    public static short onesComplementChecksum(byte[] buf) {
        long sum = 0;
        int i = 0;
        while (i + 1 < buf.length) {
            int w = ((buf[i] & 0xff) << 8) | (buf[i + 1] & 0xff);
            sum += w;
            if ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
            i += 2;
        }
        if (i < buf.length) {
            int w = (buf[i] & 0xff) << 8; // pad low byte with 0
            sum += w;
            if ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
        }
        sum = (sum & 0xFFFF) + (sum >> 16);
        return (short)(~sum & 0xFFFF);
    }
}
