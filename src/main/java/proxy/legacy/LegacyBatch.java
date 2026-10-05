package proxy.legacy;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * MCPE 0.14.x BatchPacket (0x92) encoding/decoding.
 *
 * Outbound (server->client): [0x92][int len][ raw-deflate( [int len][pid][payload]... ) ]
 * Inbound  (client->server): [0x92][int len][ zlib( [int len][0x8e][pid][payload]... ) ]
 *
 * Inner items on the outbound path omit the 0x8e marker (matches MPMPESCore
 * Server::batchPackets / MultiProtocol::translateForFamily family 0).
 */
public final class LegacyBatch {

    private LegacyBatch() {
    }

    /** Encodes a list of [pid][payload] game packets into a single batch packet buffer. */
    public static byte[] encode(List<byte[]> packets) {
        LegacyBinary.Writer inner = new LegacyBinary.Writer();
        for (byte[] p : packets) {
            inner.putInt(p.length);
            inner.putBytes(p);
        }
        byte[] compressed = deflate(inner.toByteArray());
        LegacyBinary.Writer out = new LegacyBinary.Writer();
        out.putByte(LegacyGameConstants.BATCH);
        out.putInt(compressed.length);
        out.putBytes(compressed);
        return out.toByteArray();
    }

    /** Encodes a single [pid][payload] packet into a batch packet buffer. */
    public static byte[] encodeSingle(byte[] packet) {
        List<byte[]> l = new ArrayList<>(1);
        l.add(packet);
        return encode(l);
    }

    /**
     * Decodes a batch payload (the bytes after the batch header, already containing
     * only compressed data, or the full buffer starting with 0x92). Returns a list of
     * inner packets as [pid][payload] (0x8e marker stripped).
     */
    public static List<byte[]> decode(byte[] buffer) {
        int off = 0;
        if (buffer.length > 0 && (buffer[0] & 0xff) == LegacyGameConstants.BATCH) {
            off = 1;
            LegacyBinary.Reader hr = new LegacyBinary.Reader(buffer, off);
            int len = hr.getInt();
            off = hr.getOffset();
            byte[] payload = new byte[Math.min(len, buffer.length - off)];
            System.arraycopy(buffer, off, payload, 0, payload.length);
            return decodePayload(payload);
        }
        // already just the compressed payload
        return decodePayload(buffer);
    }

    public static List<byte[]> decodePayload(byte[] compressed) {
        List<byte[]> out = new ArrayList<>();
        byte[] data = inflate(compressed);
        if (data == null) {
            return out;
        }
        int len = data.length;
        int offset = 0;
        while (offset + 4 <= len) {
            int pkLen = readInt(data, offset);
            offset += 4;
            if (pkLen < 1 || offset + pkLen > len) {
                break;
            }
            byte[] buf = new byte[pkLen];
            System.arraycopy(data, offset, buf, 0, pkLen);
            offset += pkLen;
            if (buf.length == 0) {
                continue;
            }
            // strip 0x8e marker if present
            int pidOff = 0;
            if ((buf[0] & 0xff) == LegacyConstants.ENCAPSULATION_MARKER && buf.length >= 2) {
                pidOff = 1;
            }
            byte[] packet = new byte[buf.length - pidOff];
            System.arraycopy(buf, pidOff, packet, 0, packet.length);
            out.add(packet);
        }
        return out;
    }

    private static int readInt(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    public static byte[] deflate(byte[] data) {
        // RFC1950 zlib (matches PHP ZLIB_ENCODING_DEFLATE)
        Deflater deflater = new Deflater(7);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    public static byte[] inflate(byte[] data) {
        // try zlib (RFC1950), then raw deflate (RFC1951)
        byte[] r = tryInflate(data, false);
        if (r != null) {
            return r;
        }
        return tryInflate(data, true);
    }

    private static byte[] tryInflate(byte[] data, boolean nowrap) {
        Inflater inflater = new Inflater(nowrap);
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
