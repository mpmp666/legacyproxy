package proxy.legacy;

/**
 * A single RakNet encapsulated frame (the payload carried inside DATA_PACKET_0..F datagrams).
 * Reference: MPMPESCore raklib/protocol/EncapsulatedPacket.php.
 */
public final class LegacyEncapsulatedPacket {

    public int reliability = LegacyConstants.RELIABILITY_RELIABLE_ORDERED;
    public boolean hasSplit = false;
    public byte[] buffer = new byte[0];

    public Integer messageIndex;
    public Integer orderIndex;
    public Integer orderChannel;

    public Integer splitCount;
    public Integer splitId;
    public Integer splitIndex;

    public boolean needAck = false;
    public Integer identifierAck;

    public static LegacyEncapsulatedPacket decode(byte[] data, int off, int[] outOffset) {
        LegacyEncapsulatedPacket p = new LegacyEncapsulatedPacket();
        int flags = data[off] & 0xff;
        p.reliability = (flags & 0xe0) >>> 5;
        p.hasSplit = (flags & 0x10) != 0;
        // wire length is a big-endian unsigned short of BITS
        int lengthBits = ((data[off + 1] & 0xff) << 8) | (data[off + 2] & 0xff);
        int length = (lengthBits + 7) / 8;
        int pos = off + 3;

        if (p.reliability > 0) {
            if (p.reliability >= 2 && p.reliability != 5) {
                p.messageIndex = readLTriad(data, pos);
                pos += 3;
            }
            if (p.reliability <= 4 && p.reliability != 2) {
                p.orderIndex = readLTriad(data, pos);
                pos += 3;
                p.orderChannel = data[pos++] & 0xff;
            }
        }
        if (p.hasSplit) {
            // A truncated frame (short datagram, or a misaligned frame boundary) must not
            // throw: clamp the split header reads to what is actually present.
            if (pos + 10 > data.length) {
                p.hasSplit = false;
            } else {
                p.splitCount = readInt(data, pos);
                pos += 4;
                p.splitId = readShort(data, pos);
                pos += 2;
                p.splitIndex = readInt(data, pos);
                pos += 4;
            }
        }
        // Clamp: a malformed/partially-written frame must not blow up the packet loop (the
        // shared-port divider path passes us whatever arrives on the wire).
        int available = data.length - pos;
        if (available < 0) {
            available = 0;
        }
        if (length > available) {
            length = available;
        }
        p.buffer = new byte[length];
        if (length > 0) {
            System.arraycopy(data, pos, p.buffer, 0, length);
        }
        pos += length;
        outOffset[0] = pos - off;
        return p;
    }

    public int getTotalLength() {
        return 3 + buffer.length
                + (messageIndex != null ? 3 : 0)
                + (orderIndex != null ? 4 : 0)
                + (hasSplit ? 10 : 0);
    }

    public byte[] encode() {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte((reliability << 5) | (hasSplit ? 0x10 : 0));
        w.putShort(buffer.length << 3);
        if (reliability > 0) {
            if (reliability >= 2 && reliability != 5) {
                w.putLTriad(messageIndex == null ? 0 : messageIndex);
            }
            if (reliability <= 4 && reliability != 2) {
                w.putLTriad(orderIndex == null ? 0 : orderIndex);
                w.putByte(orderChannel == null ? 0 : orderChannel);
            }
        }
        if (hasSplit) {
            w.putInt(splitCount == null ? 0 : splitCount);
            w.putShort(splitId == null ? 0 : splitId);
            w.putInt(splitIndex == null ? 0 : splitIndex);
        }
        w.putBytes(buffer);
        return w.toByteArray();
    }

    private static int readShort(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static int readInt(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    private static int readLTriad(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8) | ((b[i + 2] & 0xff) << 16);
    }

    /** Little-endian 24-bit sequence number at byte offset {@code i}. */
    public static int readLTriadAt(byte[] b, int i) {
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8) | ((b[i + 2] & 0xff) << 16);
    }
}
