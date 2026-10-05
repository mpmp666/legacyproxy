package proxy.legacy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Binary reader/writer for the MCPE 0.14.x wire format.
 * Default primitive encoding is BIG-endian (matches Genisys Binary::writeInt etc).
 * "L"-prefixed helpers are little-endian.
 */
public final class LegacyBinary {

    private LegacyBinary() {
    }

    /** Growable writer over a big-endian stream. */
    public static final class Writer extends ByteArrayOutputStream {

        public void putByte(int v) {
            write(v & 0xff);
        }

        public void putBoolean(boolean v) {
            putByte(v ? 1 : 0);
        }

        public void putShort(int v) {
            write((v >>> 8) & 0xff);
            write(v & 0xff);
        }

        public void putLShort(int v) {
            write(v & 0xff);
            write((v >>> 8) & 0xff);
        }

        public void putInt(int v) {
            write((v >>> 24) & 0xff);
            write((v >>> 16) & 0xff);
            write((v >>> 8) & 0xff);
            write(v & 0xff);
        }

        public void putLInt(int v) {
            write(v & 0xff);
            write((v >>> 8) & 0xff);
            write((v >>> 16) & 0xff);
            write((v >>> 24) & 0xff);
        }

        public void putLong(long v) {
            putInt((int) (v >>> 32));
            putInt((int) (v & 0xffffffffL));
        }

        public void putFloat(float v) {
            putInt(Float.floatToIntBits(v));
        }

        public void putLFloat(float v) {
            putLInt(Float.floatToIntBits(v));
        }

        /** 3-byte big-endian triad */
        public void putTriad(int v) {
            write((v >>> 16) & 0xff);
            write((v >>> 8) & 0xff);
            write(v & 0xff);
        }

        /** 3-byte little-endian triad */
        public void putLTriad(int v) {
            write(v & 0xff);
            write((v >>> 8) & 0xff);
            write((v >>> 16) & 0xff);
        }

        /** Length-prefixed string (unsigned big-endian short) */
        public void putString(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            putShort(b.length);
            write(b, 0, b.length);
        }

        public void putBytes(byte[] b) {
            write(b, 0, b.length);
        }

        /** RakNet IPv4 address: version 4 + inverted octets + big-endian port */
        public void putAddress(String host, int port) {
            putByte(4);
            String[] parts = host.split("\\.");
            for (String p : parts) {
                putByte((~Integer.parseInt(p)) & 0xff);
            }
            putShort(port);
        }

        public void putUUID(byte[] uuid) {
            write(uuid, 0, uuid.length);
        }

        /** Unsigned LEB128 varint (0.16-era; kept for completeness) */
        public void putUnsignedVarInt(int v) {
            v &= 0xffffffff;
            int remaining = v;
            while ((remaining & ~0x7f) != 0) {
                putByte((remaining & 0x7f) | 0x80);
                remaining >>>= 7;
            }
            putByte(remaining);
        }
    }

    /** Cursor reader over a byte array. */
    public static final class Reader {
        private final byte[] buf;
        private int offset;

        public Reader(byte[] buf) {
            this(buf, 0);
        }

        public Reader(byte[] buf, int offset) {
            this.buf = buf;
            this.offset = offset;
        }

        public int getOffset() {
            return offset;
        }

        public int remaining() {
            return buf.length - offset;
        }

        public boolean eof() {
            return offset >= buf.length;
        }

        public byte[] get(int len) {
            if (len < 0 || offset + len > buf.length) {
                throw new IndexOutOfBoundsException("read past end");
            }
            byte[] r = new byte[len];
            System.arraycopy(buf, offset, r, 0, len);
            offset += len;
            return r;
        }

        public int getByte() {
            return buf[offset++] & 0xff;
        }

        public boolean getBoolean() {
            return getByte() != 0;
        }

        public int getShort() {
            return ((buf[offset++] & 0xff) << 8) | (buf[offset++] & 0xff);
        }

        public int getLShort() {
            return (buf[offset++] & 0xff) | ((buf[offset++] & 0xff) << 8);
        }

        public int getInt() {
            return ((buf[offset++] & 0xff) << 24) | ((buf[offset++] & 0xff) << 16)
                    | ((buf[offset++] & 0xff) << 8) | (buf[offset++] & 0xff);
        }

        public int getLInt() {
            return (buf[offset++] & 0xff) | ((buf[offset++] & 0xff) << 8)
                    | ((buf[offset++] & 0xff) << 16) | ((buf[offset++] & 0xff) << 24);
        }

        public long getLong() {
            long hi = getInt() & 0xffffffffL;
            long lo = getInt() & 0xffffffffL;
            return (hi << 32) | lo;
        }

        public float getFloat() {
            return Float.intBitsToFloat(getInt());
        }

        public float getLFloat() {
            return Float.intBitsToFloat(getLInt());
        }

        public int getTriad() {
            return ((buf[offset++] & 0xff) << 16) | ((buf[offset++] & 0xff) << 8) | (buf[offset++] & 0xff);
        }

        public int getLTriad() {
            return (buf[offset++] & 0xff) | ((buf[offset++] & 0xff) << 8) | ((buf[offset++] & 0xff) << 16);
        }

        public String getString() {
            return new String(get(getShort()), StandardCharsets.UTF_8);
        }

        public String getAddress(int[] outPort) {
            int version = getByte();
            if (version == 4) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    if (i > 0) sb.append('.');
                    sb.append((~getByte()) & 0xff);
                }
                outPort[0] = getShort();
                return sb.toString();
            }
            // IPv6 (version 6) or unknown: skip 16 bytes + port, return a placeholder.
            // MCPE 0.14 clients always connect over IPv4 in practice; being tolerant
            // here avoids an exception killing the reader thread.
            get(16);
            outPort[0] = getShort();
            return "0.0.0.0";
        }

        public byte[] getUUID() {
            return get(16);
        }

        public int getUnsignedVarInt() {
            int value = 0;
            int shift = 0;
            while (true) {
                int b = getByte();
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 28) {
                    return value;
                }
            }
        }
    }
}
