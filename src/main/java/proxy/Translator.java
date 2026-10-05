package proxy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Packet-level translation between the 0.14.3 game layer and the modern (1.26.50) game layer.
 *
 * Conventions:
 *  - modern ids use varint lengths and little-endian floats; 0.14 ids use the big-endian
 *    helpers in LegacyBinary / LegacyPackets.
 *  - both sides encode the player Y as the EYE height, so y maps directly.
 */
public final class Translator {
    private Translator() {}

    // ---------- little varint helpers (modern side) ----------
    public static void putVarInt(ByteArrayOutputStream b, long v) {
        long value = v & 0xFFFFFFFFFFFFFFFFL;
        while ((value & ~0x7FL) != 0) { b.write((int) ((value & 0x7F) | 0x80)); value >>>= 7; }
        b.write((int) value);
    }
    public static void putString(ByteArrayOutputStream b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        putVarInt(b, bytes.length);
        b.write(bytes, 0, bytes.length);
    }
    public static void putFloatLE(ByteArrayOutputStream b, float f) {
        int bits = Float.floatToIntBits(f);
        b.write(bits & 0xff); b.write((bits >> 8) & 0xff); b.write((bits >> 16) & 0xff); b.write((bits >> 24) & 0xff);
    }
    public static void putLongLE(ByteArrayOutputStream b, long v) {
        for (int i = 0; i < 8; i++) b.write((int) ((v >> (i * 8)) & 0xff));
    }

    // ---------- modern readers ----------
    public static int readUVarInt(byte[] d, int[] p) { int v = 0, s = 0; while (true) { int b = d[p[0]++] & 0xff; v |= (b & 0x7f) << s; if ((b & 0x80) == 0) break; s += 7; } return v; }
    public static long readUVarInt64(byte[] d, int[] p) { long v = 0; int s = 0; while (true) { int b = d[p[0]++] & 0xff; v |= (long) (b & 0x7f) << s; if ((b & 0x80) == 0) break; s += 7; } return v; }
    public static float readFloatLE(byte[] d, int p) {
        return Float.intBitsToFloat((d[p] & 0xff) | ((d[p+1]&0xff)<<8) | ((d[p+2]&0xff)<<16) | ((d[p+3]&0xff)<<24));
    }
    public static int readIntLE(byte[] d, int p) {
        return (d[p] & 0xff) | ((d[p+1] & 0xff) << 8) | ((d[p+2] & 0xff) << 16) | ((d[p+3] & 0xff) << 24);
    }
    public static String readString(byte[] d, int[] p) {
        int len = readUVarInt(d, p);
        String s = new String(d, p[0], len, StandardCharsets.UTF_8);
        p[0] += len;
        return s;
    }

    // ================= 0.14 -> modern =================

    /** 0.14 MovePlayer (0x9d) -> modern MovePlayerPacket (0x13). buf starts at the packet id. */
    public static byte[] toModernMovePlayer(byte[] buf, int off, long runtimeEntityId) {
        // [0x9d][long eid][float x][y][z][yaw][headYaw][pitch][byte mode][byte onGround]
        int p = off + 1;
        // eid (big-endian long) — the 0.14 client sends its own id, which we ignore
        p += 8;
        float x  = readFloatBE(buf, p); p += 4;
        float y  = readFloatBE(buf, p); p += 4;
        float z  = readFloatBE(buf, p); p += 4;
        float yaw     = readFloatBE(buf, p); p += 4;
        float headYaw = readFloatBE(buf, p); p += 4;
        float pitch   = readFloatBE(buf, p); p += 4;
        int mode = buf[p++] & 0xff;
        boolean onGround = (buf[p++] & 0xff) != 0;

        // NOTE: no eye->feet conversion here. Modern MovePlayerPacket carries the EYE position
        // exactly like 0.14: the backend does `y - getBaseOffset()` itself
        // (Player.java: "new Vector3(x, y - this.getBaseOffset(), z)"). Subtracting 1.62 here
        // put the proxy player 1.62 blocks *below* the ground on the backend.

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        putVarInt(out, runtimeEntityId);   // the backend's runtime id for us
        putFloatLE(out, x);
        putFloatLE(out, y);
        putFloatLE(out, z);
        putFloatLE(out, pitch);
        putFloatLE(out, yaw);
        putFloatLE(out, headYaw);
        out.write(mode);
        out.write(onGround ? 1 : 0);
        putVarInt(out, 0);              // riding entity id = none
        out.write(0);                   // no teleport block
        putVarInt(out, 0);              // frame
        return out.toByteArray();
    }

    /** 0.14 MovePlayer carries the eye position; the modern backend tracks the feet. */
    public static final float EYE_HEIGHT = 1.62f;

    /** Just the message text of a 0.14 Text (0x93) packet (no source prefix). */
    public static String textMessage(byte[] buf, int off) {
        try {
            int p = off + 1;
            int type = buf[p++] & 0xff;
            if (type == 1 || type == 3) {
                int len = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff); p += 2 + len;
            }
            int len = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff); p += 2;
            return new String(buf, p, len, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Human-readable text of a 0.14 Text (0x93) packet, for logging. */
    public static String lastText(byte[] buf, int off) {
        try {
            int p = off + 1;
            int type = buf[p++] & 0xff;
            String source = "", message;
            if (type == 1 || type == 3) {
                int len = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff); p += 2;
                source = new String(buf, p, len, StandardCharsets.UTF_8); p += len;
                len = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff); p += 2;
                message = new String(buf, p, len, StandardCharsets.UTF_8);
            } else {
                int len = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff); p += 2;
                message = new String(buf, p, len, StandardCharsets.UTF_8);
            }
            return (source.isEmpty() ? "" : source + ": ") + message;
        } catch (Exception e) {
            return "<unparsed>";
        }
    }

    /** 0.14 Text (0x93) -> modern TextPacket (0x09, chat). buf starts at the packet id. */
    public static byte[] toModernText(byte[] buf, int off, String username) {
        // [0x93][byte type][if CHAT/POPUP: string source][string message]
        int p = off + 1;
        int type = buf[p++] & 0xff;
        String source, message;
        if (type == 1 || type == 3) {          // CHAT / POPUP carry a source string
            int len = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff); p += 2;
            source = new String(buf, p, len, StandardCharsets.UTF_8); p += len;
            len = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff); p += 2;
            message = new String(buf, p, len, StandardCharsets.UTF_8);
        } else {
            // RAW (0) and the other client->server forms have no source string. Commands
            // ("/help") arrive this way, so they must be forwarded too or they never reach
            // the backend's command dispatcher.
            source = "";
            int len = ((buf[p] & 0xff) << 8) | (buf[p + 1] & 0xff); p += 2;
            message = new String(buf, p, len, StandardCharsets.UTF_8);
        }
        if (message.isEmpty()) {
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);                  // isLocalized = false
        out.write(1);                  // kind = AuthorAndMessage
        out.write(1);                  // TYPE_CHAT — the backend dispatches "/cmd" from chat
        putString(out, source.isEmpty() ? username : source);
        putString(out, message);
        putString(out, "");            // xuid
        putString(out, "");            // platformChatId
        putString(out, "");            // filteredMessage
        return out.toByteArray();
    }

    // ================= modern -> 0.14 =================

    /** modern TextPacket (0x09) -> [type, source, message] for the 0.14 Text packet. */
    public static String[] fromModernText(byte[] payload) {
        int[] p = new int[]{0};
        boolean localized = (payload[p[0]++] & 0xff) != 0;
        int kind = payload[p[0]++] & 0xff;
        int type = payload[p[0]++] & 0xff;
        String source = "";
        String message;
        if (kind == 1) {                // AuthorAndMessage
            source = readString(payload, p);
            message = readString(payload, p);
        } else if (kind == 0) {         // MessageOnly
            message = readString(payload, p);
        } else {                        // AuthorAndMessageWithParameters / raw-with-params
            message = readString(payload, p);
            int count = readUVarInt(payload, p);
            for (int i = 0; i < count && i < 8; i++) readString(payload, p);
        }
        return new String[]{String.valueOf(type), source, message};
    }

    static float readFloatBE(byte[] d, int p) {
        return Float.intBitsToFloat(((d[p] & 0xff) << 24) | ((d[p+1]&0xff)<<16) | ((d[p+2]&0xff)<<8) | (d[p+3]&0xff));
    }
}
