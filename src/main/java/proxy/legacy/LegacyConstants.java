package proxy.legacy;

/**
 * RakNet protocol v6 constants used by MCPE 0.14.x (protocol 70).
 * Reference: MPMPESCore (Genisys) raklib.
 */
public final class LegacyConstants {

    private LegacyConstants() {
    }

    /** RakNet protocol version for MCPE 0.14.x */
    public static final int RAKNET_PROTOCOL = 6;

    /** RakNet offline message data magic */
    public static final byte[] MAGIC = new byte[]{
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78
    };

    // ---- Offline / handshake packet IDs ----
    public static final int ID_CONNECTED_PING = 0x00;
    public static final int ID_UNCONNECTED_PING = 0x01;
    public static final int ID_UNCONNECTED_PING_OPEN_CONNECTIONS = 0x02;
    public static final int ID_CONNECTED_PONG = 0x03;
    public static final int ID_OPEN_CONNECTION_REQUEST_1 = 0x05;
    public static final int ID_OPEN_CONNECTION_REPLY_1 = 0x06;
    public static final int ID_OPEN_CONNECTION_REQUEST_2 = 0x07;
    public static final int ID_OPEN_CONNECTION_REPLY_2 = 0x08;
    public static final int ID_CONNECTION_REQUEST = 0x09;
    public static final int ID_CONNECTION_REQUEST_ACCEPTED = 0x10;
    public static final int ID_NEW_INCOMING_CONNECTION = 0x13;
    public static final int ID_DISCONNECTION_NOTIFICATION = 0x15;
    public static final int ID_UNCONNECTED_PONG = 0x1c;

    // ---- Data packet IDs (0x80 - 0x8f) ----
    public static final int ID_DATA_PACKET_0 = 0x80;
    public static final int ID_DATA_PACKET_4 = 0x84;
    public static final int ID_DATA_PACKET_F = 0x8f;

    // ---- ACK / NACK ----
    public static final int ID_NACK = 0xa0;
    public static final int ID_ACK = 0xc0;

    // ---- Encapsulated frame reliability ----
    public static final int RELIABILITY_UNRELIABLE = 0;
    public static final int RELIABILITY_UNRELIABLE_SEQUENCED = 1;
    public static final int RELIABILITY_RELIABLE = 2;
    public static final int RELIABILITY_RELIABLE_ORDERED = 3;
    public static final int RELIABILITY_RELIABLE_SEQUENCED = 4;

    // ---- Session states ----
    public static final int STATE_UNCONNECTED = 0;
    public static final int STATE_CONNECTING_1 = 1;
    public static final int STATE_CONNECTING_2 = 2;
    public static final int STATE_CONNECTED = 3;

    // ---- MCPE 0.14.x game protocol ----
    public static final int MCPE_PROTOCOL_70 = 70;
    public static final String MCPE_VERSION = "0.14.3";

    // 0.14 batch encapsulation marker prepended before each packet id (client->server
    // and direct server->client sends). Batched server->client inner items omit it.
    public static final int ENCAPSULATION_MARKER = 0x8e;

    /** Verbose per-datagram logging for diagnosing handshake stalls. */
    public static final boolean DEBUG_SESSION = Boolean.getBoolean("nukkit.legacy.debug");
}
