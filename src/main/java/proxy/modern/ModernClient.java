package proxy.modern;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * A client-side RakNet (protocol 11) connection to the modern Nukkit-MOT backend.
 *
 * The RakNet handshake is version-agnostic at the transport level; only the protocol byte and
 * the game-layer packets differ between 0.14.3 and modern. This class opens the transport,
 * performs the handshake, and then hands decoded game packets to the translator.
 */
public class ModernClient {
    /** Verbose per-datagram logging for protocol debugging. */
    public static final boolean DEBUG_FRAMES = Boolean.getBoolean("proxy.modern.debug");

    private static final byte[] MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};

    private static final int ID_UNCONNECTED_PING = 0x01;
    private static final int ID_UNCONNECTED_PONG = 0x1c;
    private static final int ID_OPEN_CONNECTION_REQUEST_1 = 0x05;
    private static final int ID_OPEN_CONNECTION_REPLY_1 = 0x06;
    private static final int ID_OPEN_CONNECTION_REQUEST_2 = 0x07;
    private static final int ID_OPEN_CONNECTION_REPLY_2 = 0x08;
    private static final int ID_CONNECTION_REQUEST = 0x09;
    private static final int ID_CONNECTION_REQUEST_ACCEPTED = 0x10;
    private static final int ID_NEW_INCOMING_CONNECTION = 0x13;
    private static final int ID_DATA_PACKET_0 = 0x80;
    private static final int ID_ACK = 0xc0;
    private static final int GAME_PACKET_MARKER = 0xfe;
    /** Set -Dproxy.modern.batchdebug=true to dump every decompressed batch. */
    private static final boolean DEBUG_BATCH = Boolean.getBoolean("proxy.modern.batchdebug");

    /** RakNet protocol spoken by modern Bedrock (1.21.x) servers. */
    private static final int RAKNET_PROTOCOL = 11;

    private final InetSocketAddress backend;
    private final DatagramSocket socket;
    private int mtu = 1464;
    private long serverGuid;
    private int cookie;

    public ModernClient(InetSocketAddress backend) throws Exception {
        this.backend = backend;
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(5000);
    }

    public void close() {
        closed = true;
        socket.close();
    }

    // ---- varint helpers ----
    private static void putVarInt(java.io.ByteArrayOutputStream b, int v) {
        int value = v & 0xFFFFFFFF;
        while ((value & ~0x7F) != 0) {
            b.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        b.write(value);
    }

    private static void putIntBE(java.io.ByteArrayOutputStream b, int v) {
        b.write((v >>> 24) & 0xff);
        b.write((v >>> 16) & 0xff);
        b.write((v >>> 8) & 0xff);
        b.write(v & 0xff);
    }

    private static void putLInt(java.io.ByteArrayOutputStream b, int v) {
        b.write(v & 0xff);
        b.write((v >>> 8) & 0xff);
        b.write((v >>> 16) & 0xff);
        b.write((v >>> 24) & 0xff);
    }

    private static String base64Url(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String jwt(String payloadJson) {
        String h = base64Url("{\"alg\":\"ES384\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String p = base64Url(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String s = base64Url("legacy-signature".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return h + "." + p + "." + s;
    }

    private static String jwtBasic(String payloadJson) {
        String h = java.util.Base64.getEncoder().encodeToString("{\"alg\":\"ES384\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String p = java.util.Base64.getEncoder().encodeToString(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return h + "." + p + ".c2ln";
    }

    /**
     * Sends a modern LoginPacket for the given player name. xbox-auth=off on the backend, so a
     * synthesized single-entry (unsigned) chain passes — the server only parses claims.
     */
    public void sendLogin(String username) throws Exception {
        String identity = java.util.UUID.nameUUIDFromBytes(
                ("legacy:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        // The chain's identityPublicKey is not decoration: when the backend has encryption enabled
        // it derives the session key with ECDH over exactly this key
        // (PrepareEncryptionTask -> EncryptionUtils.getSecretKey(..., parseKey(identityPublicKey), ...)).
        // A fake value makes parseKey throw and the backend closes the connection with
        // "Network Encryption error".
        java.security.KeyPair identityPair = identityKeyPair();
        String identityPublicKey = java.util.Base64.getEncoder()
                .encodeToString(identityPair.getPublic().getEncoded());
        String payloadJson = "{\"identityPublicKey\":\"" + identityPublicKey +
                "\",\"extraData\":{\"displayName\":\"" + username + "\",\"identity\":\"" + identity + "\"}}";
        String chainJson = "{\"chain\":[\"" + jwt(payloadJson) + "\"]}";
        String authJson = "{\"Certificate\":\"" + chainJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        String skinJwt = jwtBasic(buildSkinJson());

        // body: [LInt chainLen][chain][LInt skinLen][skin]
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        byte[] chainBytes = authJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] skinBytes = skinJwt.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        putLInt(body, chainBytes.length);
        body.write(chainBytes, 0, chainBytes.length);
        putLInt(body, skinBytes.length);
        body.write(skinBytes, 0, skinBytes.length);
        byte[] bodyBytes = body.toByteArray();

        // login packet payload: [int protocol BE][varint bodyLen][body]
        int protocol = 2193;   // 1.26.50 — match the backend so it uses the right packet formats
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        putIntBE(payload, protocol);
        putVarInt(payload, bodyBytes.length);
        payload.write(bodyBytes, 0, bodyBytes.length);
        sendGamePacket(0x01, payload.toByteArray(), true);   // LOGIN_PACKET: compressed (compression negotiated before login)
        System.out.println("[modern] login sent for " + username);
    }

    /**
     * Sends a game packet to the backend, framed the modern way:
     * RakNet frame -> [0xfe][batch], batch = [varint len][varint header][payload].
     */
    private void sendGamePacket(int packetId, byte[] packetPayload) throws Exception {
        sendGamePacket(packetId, packetPayload, compressionEnabled);
    }

    private void sendGamePacket(int packetId, byte[] packetPayload, boolean compress) throws Exception {
        // batch entry: [varint len][varint header][payload]; header = packetId | 0 << 10 | 0 << 12
        java.io.ByteArrayOutputStream batchEntry = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream inner = new java.io.ByteArrayOutputStream();
        putVarInt(inner, packetId);
        inner.write(packetPayload, 0, packetPayload.length);
        byte[] innerBytes = inner.toByteArray();
        putVarInt(batchEntry, innerBytes.length);
        batchEntry.write(innerBytes, 0, innerBytes.length);

        // frame payload: [0xfe][batch]; compress the batch only when negotiated
        byte[] batchBytes = batchEntry.toByteArray();
        if (compress) {
            java.util.zip.Deflater def = new java.util.zip.Deflater(7, true);   // RAW deflate (nowrap) — Bedrock uses raw, not zlib
            def.setInput(batchBytes);
            def.finish();
            java.io.ByteArrayOutputStream comp = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!def.finished()) {
                int n = def.deflate(buf);
                if (n > 0) comp.write(buf, 0, n);
            }
            def.end();
            // the compressed batch carries a 1-byte compression prefix (ZLIB = 0x00)
            byte[] prefixed = new byte[comp.size() + 1];
            prefixed[0] = 0x00;
            System.arraycopy(comp.toByteArray(), 0, prefixed, 1, comp.size());
            batchBytes = prefixed;
        }
        java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
        frame.write(GAME_PACKET_MARKER);
        frame.write(batchBytes, 0, batchBytes.length);
        sendFrame(frame.toByteArray());
    }

    /** Builds a valid modern skin token (a 64x64 solid-tone skin + the humanoid geometry). */
    private static String buildSkinJson() {
        // 64x64 RGBA skin, filled with a skin-tone colour so the player isn't invisible
        byte[] skinImage = new byte[64 * 64 * 4];
        for (int i = 0; i < skinImage.length; i += 4) {
            skinImage[i] = (byte) 0xC0;     // R
            skinImage[i + 1] = (byte) 0x90; // G
            skinImage[i + 2] = (byte) 0x70; // B
            skinImage[i + 3] = (byte) 0xFF; // A (opaque)
        }
        String geometry = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.humanoid.custom\",\"texture_width\":64,\"texture_height\":64},\"bones\":[{\"name\":\"root\",\"pivot\":[0,0,0]},{\"name\":\"body\",\"parent\":\"root\",\"pivot\":[0,24,0],\"cubes\":[{\"origin\":[-4,12,-2],\"size\":[8,12,4],\"uv\":[16,16]}]},{\"name\":\"head\",\"parent\":\"body\",\"pivot\":[0,24,0],\"cubes\":[{\"origin\":[-4,24,-4],\"size\":[8,8,8],\"uv\":[0,0]}]}]}]}";
        String resourcePatch = "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}";

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"ClientRandomId\":").append(0x1122334455667788L).append(",");
        sb.append("\"SkinId\":\"Standard_Custom\",");
        sb.append("\"SkinData\":\"").append(java.util.Base64.getEncoder().encodeToString(skinImage)).append("\",");
        sb.append("\"SkinImageWidth\":64,");
        sb.append("\"SkinImageHeight\":64,");
        sb.append("\"SkinGeometryData\":\"").append(java.util.Base64.getEncoder().encodeToString(geometry.getBytes(java.nio.charset.StandardCharsets.UTF_8))).append("\",");
        sb.append("\"SkinGeometryName\":\"geometry.humanoid.custom\",");
        sb.append("\"SkinResourcePatch\":\"").append(java.util.Base64.getEncoder().encodeToString(resourcePatch.getBytes(java.nio.charset.StandardCharsets.UTF_8))).append("\",");
        sb.append("\"PremiumSkin\":false,");
        sb.append("\"PersonaSkin\":false,");
        sb.append("\"CapeOnClassicSkin\":false,");
        sb.append("\"SkinColor\":\"#0\",");
        sb.append("\"ArmSize\":\"classic\",");
        sb.append("\"TrustedSkin\":true,");
        sb.append("\"IsEmoteSettingsLocked\":false");
        sb.append("}");
        return sb.toString();
    }

    /** Test harness: connect to a backend and report. Usage: ModernClient <host> <port> */
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 19133;
        ModernClient c = new ModernClient(new InetSocketAddress(host, port));
        boolean ok = c.connect();
        if (!ok) {
            System.out.println("HANDSHAKE_FAILED");
            return;
        }
        System.out.println("HANDSHAKE_OK");
        // modern login flow: network settings first, then the login packet
        c.onNetworkSettings = () -> { try { c.sendLogin("ProxyBot"); } catch (Exception e) { System.out.println("[modern] login failed: " + e); } };
        c.onResourcePacksInfo = () -> { try { c.sendResourcePackStatus(4); } catch (Exception e) {} };
        c.onResourcePackStack = () -> { try { c.sendResourcePackStatus(4); } catch (Exception e) {} };
        c.onStartGame = () -> {
            try {
                java.io.ByteArrayOutputStream p = new java.io.ByteArrayOutputStream();
                p.write(3); p.write(3); p.write(3); p.write(3);   // RequestChunkRadiusPacket: varint radius=3
                c.sendGamePacket(0x45, p.toByteArray());
                System.out.println("[modern] chunk radius requested");
            } catch (Exception e) {}
        };
        c.sendRequestNetworkSettings();
        // read game packets for a while, decoding the batch wrapper
        long end = System.currentTimeMillis() + 30000;
        c.socket.setSoTimeout(2000);
        while (System.currentTimeMillis() < end) {
            try {
                java.util.List<byte[]> frames = c.readFrames();
                for (byte[] frame : frames) {
                    if (frame.length == 0) continue;
                    int fid = frame[0] & 0xff;
                    if (fid == 0xfe) {
                        c.decodeBatch(frame, 1);
                    } else if (fid == 0x00) {
                        c.sendConnectedPong(frame);
                    } else {
                        System.out.println("[modern] frame id=0x" + Integer.toHexString(fid) + " len=" + frame.length
                                + " head=" + bytesToHex(frame, Math.min(20, frame.length)));
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // keep waiting
            }
        }
    }

    /** Decodes a 0xfe game frame's batch, printing each contained packet's id. */
    public volatile boolean compressionEnabled = false;
    /** Continuous inflate stream — Bedrock's compression is a persistent raw-deflate stream, not per-packet. */
    private final java.util.zip.Inflater batchInflater = new java.util.zip.Inflater(true);

    /** Set once the server asks for an encrypted connection; every frame then goes through it. */
    private volatile BedrockEncryption encryption;
    /** -Dproxy.modern.encdebug=true dumps every encrypted frame (both directions). */
    private static final boolean DEBUG_ENC = Boolean.getBoolean("proxy.modern.encdebug");
    /** Exact bytes of the last encrypted frame, so it can be re-sent verbatim (same counter). */
    private volatile byte[] lastEncryptedFrame;
    /** True once the server has answered something we could decrypt. */
    private volatile boolean encryptedReplySeen;
    private volatile int lastSentSeq = -1;
    private final java.util.Set<Integer> ackedSeqs = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    /** Our login-chain identity key; the backend uses it for the encryption ECDH. */
    private java.security.KeyPair identityKeyPair;

    private java.security.KeyPair identityKeyPair() throws Exception {
        if (identityKeyPair == null) {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
            identityKeyPair = generator.generateKeyPair();
        }
        return identityKeyPair;
    }

    public void decodeBatch(byte[] frame, int off) {
        byte[] data;
        int startPos;
        if (encryption != null) {
            // The whole region after the 0xfe marker is one AES-CFB8 stream that also carries the
            // checksum in its last 8 bytes; decrypt() verifies it and strips both.
            byte[] region = java.util.Arrays.copyOfRange(frame, off, frame.length);
            if (DEBUG_ENC) {
                System.out.println("[enc] recv wire=" + region.length + "B head="
                        + bytesToHex(region, Math.min(20, region.length)));
            }
            data = encryption.decrypt(region);
            encryptedReplySeen = true;
            if (DEBUG_ENC) {
                System.out.println("[enc] recv plain=" + data.length + "B head="
                        + bytesToHex(data, Math.min(12, data.length)));
            }
            startPos = 0;
        } else {
            data = frame;
            startPos = off;
        }
        if (compressionEnabled) {
            try {
                // compressed batches carry a 1-byte compression prefix (ZLIB = 0x00); skip it
                int dataOff = startPos;
                if (dataOff < data.length && data[dataOff] == 0x00) dataOff++;
                // each batch is a COMPLETE raw-deflate stream (server calls reset+finish per packet) — fresh Inflater
                java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
                inf.setInput(data, dataOff, data.length - dataOff);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[131072];
                int n;
                while ((n = inf.inflate(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                inf.end();
                data = out.toByteArray();
                startPos = 0;
            } catch (Exception e) {
                System.out.println("[modern] batch decompress failed: " + e);
                return;
            }
        }
        frame = data;
        int pos = startPos;
        if (DEBUG_BATCH) {
            StringBuilder hx = new StringBuilder();
            for (int i = 0; i < Math.min(frame.length, 48); i++) hx.append(String.format("%02x ", frame[i]));
            System.out.println("[modern] batch len=" + frame.length + " head=" + hx.toString().trim());
        }
        while (pos < frame.length) {
            int entryStart = pos;
            // read varint length
            int len = 0, shift = 0;
            while (true) {
                int b = frame[pos++] & 0xff;
                len |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            if (len <= 0 || pos + len > frame.length) {
                if (DEBUG_BATCH) {
                    System.out.println("[modern] batch DESYNC at " + entryStart + " len=" + len
                            + " pos=" + pos + " frameLen=" + frame.length);
                }
                break;
            }
            // inner: [varint header][payload]
            int header = 0, hshift = 0, hpos = pos;
            while (true) {
                int b = frame[hpos++] & 0xff;
                header |= (b & 0x7f) << hshift;
                if ((b & 0x80) == 0) break;
                hshift += 7;
            }
            int packetId = header & 0x3ff;
            int payloadLen = pos + len - hpos;
            String note = "";
            // capture the StartGamePacket and the first chunk for offline parsing analysis
            if (packetId == 0x0b || (packetId == 0x3a && savedChunkCount < 1)) {
                try {
                    byte[] payload = java.util.Arrays.copyOfRange(frame, hpos, pos + len);
                    String fn = packetId == 0x0b ? "C:/Users/Administrator/LegacyProxy/capture_startgame.bin" : "C:/Users/Administrator/LegacyProxy/capture_chunk.bin";
                    java.nio.file.Files.write(java.nio.file.Paths.get(fn), payload);
                    if (packetId == 0x3a) savedChunkCount++;
                    System.out.println("[modern] saved " + fn + " (" + payload.length + " bytes)");
                } catch (Exception e) { System.out.println("[modern] save failed: " + e); }
            }
            if (packetId == 0x02 && payloadLen >= 4) {
                int status = ((frame[hpos] & 0xff) << 24) | ((frame[hpos+1]&0xff)<<16) | ((frame[hpos+2]&0xff)<<8) | (frame[hpos+3]&0xff);
                note = " PlayStatus=" + status + (status==0?"(LOGIN_SUCCESS)":status==1?"(LOGIN_FAILED_CLIENT)":status==2?"(LOGIN_FAILED_SERVER)":"(spawn?)");
            }
            if (packetId == 0x0b) note = " <<< StartGamePacket";
            if (packetId == 0x8f) {
                // NetworkSettingsPacket: [short compressionAlgo][short threshold][...]
                int algo = hpos + 1 < pos + len ? ((frame[hpos] & 0xff) | ((frame[hpos+1] & 0xff) << 8)) : -1;
                note = " NetworkSettings compressionAlgo=" + algo;
                this.compressionEnabled = (algo != 0);
            }
            if (packetId == 0x06) note = " ResourcePacksInfo payload=" + bytesToHex(java.util.Arrays.copyOfRange(frame, hpos, Math.min(hpos + 24, pos + len)), Math.min(24, pos + len - hpos));
            if (packetId == 0x03) {
                // ServerToClientHandshake: [varuint len][jwt]. Derive the session key and answer with
                // ClientToServerHandshake (0x04). The server enables its own ciphers at the same
                // moment it sends this packet, so the reply has to be encrypted already.
                try {
                    int lp = hpos;
                    int jlen = 0, jshift = 0;
                    while (true) {
                        int b = frame[lp++] & 0xff;
                        jlen |= (b & 0x7f) << jshift;
                        if ((b & 0x80) == 0) break;
                        jshift += 7;
                    }
                    String jwt = new String(frame, lp, jlen, java.nio.charset.StandardCharsets.UTF_8);
                    // NOTE: from the moment the server sends this packet its ciphers are live, so
                    // NOTHING may be sent in plaintext in between — a stray frame would advance the
                    // server's decrypt counter and desynchronise every following packet.
                    this.encryption = BedrockEncryption.fromHandshakeJwt(jwt, identityKeyPair().getPrivate());
                    System.out.println("[modern] encryption handshake received, session key derived");
                    sendGamePacket(0x04, new byte[0]);
                    System.out.println("[modern] ClientToServerHandshake sent (encrypted)");
                    retryHandshakeUntilAnswered();
                } catch (Exception e) {
                    System.out.println("[modern] encryption handshake failed: " + e);
                }
            }
            System.out.println("[modern] game packet id=0x" + Integer.toHexString(packetId) + " len=" + payloadLen + note);
            // Forward the packet to the bridge BEFORE running the login-phase callbacks.
            // The StartGame callback builds the whole 0.14 bootstrap, so the bridge must have
            // parsed the StartGame payload (spawn position, gamemode) first — otherwise the
            // bootstrap is built from default values: creative mode at 128,82,128 instead of the
            // real survival spawn, which left the old client stuck inside terrain.
            if (this.onGamePacket != null) {
                try {
                    this.onGamePacket.accept(packetId,
                            java.util.Arrays.copyOfRange(frame, hpos, pos + len));
                } catch (Exception e) {
                    System.out.println("[modern] onGamePacket failed: " + e);
                }
            }
            // the two-step resource-pack handshake
            if (packetId == 0x06 && this.onResourcePacksInfo != null) {
                this.onResourcePacksInfo.run();
            } else if (packetId == 0x07 && this.onResourcePackStack != null) {
                this.onResourcePackStack.run();
            } else if (packetId == 0x8f && this.onNetworkSettings != null) {
                this.onNetworkSettings.run();
            } else if (packetId == 0x0b && this.onStartGame != null) {
                this.onStartGame.run();
            }
            // advance past this batch entry: `len` covers [varint id][payload]
            pos += len;
        }
    }

    /** Callbacks for the login-phase packets. */
    public Runnable onResourcePacksInfo;
    public Runnable onResourcePackStack;
    public Runnable onStartGame;
    public Runnable onNetworkSettings;
    /** Every decoded game packet: (packetId, payload after the varint header). */
    public java.util.function.BiConsumer<Integer, byte[]> onGamePacket;
    private int savedChunkCount = 0;
    private volatile boolean closed = false;

    /** Reads and dispatches backend game packets until the socket closes. */
    public void runReadLoop() {
        try {
            socket.setSoTimeout(2000);
        } catch (Exception e) {
            return;
        }
        while (!closed) {
            try {
                java.util.List<byte[]> frames = readFrames();
                for (byte[] frame : frames) {
                    if (frame.length == 0) continue;
                    int fid = frame[0] & 0xff;
                    if (fid == 0xfe) {
                        decodeBatch(frame, 1);
                    } else if (fid == 0x00) {
                        sendConnectedPong(frame);
                    } else if (fid == 0xc0 || fid == 0xa0) {
                        // ACK / NACK for our own packets — handled inside readFrames
                    } else {
                        System.out.println("[modern] frame id=0x" + Integer.toHexString(fid)
                                + " len=" + frame.length + " head=" + bytesToHex(frame, Math.min(16, frame.length)));
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // idle — keep waiting
            } catch (Exception e) {
                if (!closed) System.out.println("[modern] read loop ended: " + e);
                break;
            }
        }
        close();
    }

    /** Sends a game packet framed with the given packet id (proxy helper). */
    public void sendPacket(int packetId, byte[] payload) throws Exception {
        sendGamePacket(packetId, payload);
    }

    /**
     * Sends an already-encoded modern packet body: the leading unsigned-varint packet id is split
     * off and re-framed. Lets callers hand us whole packets produced by the backend's own packet
     * classes (see {@link ModernCodec}) without duplicating the framing rules here.
     */
    public void sendBody(byte[] body) throws Exception {
        if (body == null || body.length == 0) return;
        int off = 0;
        int id = 0, shift = 0;
        while (off < body.length) {
            int b = body[off++] & 0xff;
            id |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) break;
        }
        byte[] payload = new byte[body.length - off];
        System.arraycopy(body, off, payload, 0, payload.length);
        sendPacket(id, payload);
    }

    private static void putString(java.io.ByteArrayOutputStream b, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        putVarInt(b, bytes.length);
        b.write(bytes, 0, bytes.length);
    }

    private static final String[] STATUS_NAMES = {"cancel", "downloading", "downloadingfinished", "resourcepackstackfinished"};

    /**
     * The encrypted handshake is the one packet that cannot be re-encrypted: its checksum is bound
     * to the packet counter, so a re-send must repeat the <b>same bytes</b>. Losing it is otherwise
     * fatal — the server sits in AWAITING_ENCRYPTION_RESPONSE until it times out — so if nothing
     * decryptable comes back we repeat the identical frame. Once the server answers we never
     * re-send, which keeps the counter in step.
     */
    private void retryHandshakeUntilAnswered() {
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 3 && !encryptedReplySeen; attempt++) {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    return;
                }
                if (encryptedReplySeen || ackedSeqs.contains(lastSentSeq)) {
                    return;
                }
                byte[] frame = lastEncryptedFrame;
                if (frame == null) {
                    return;
                }
                try {
                    System.out.println("[modern] no encrypted reply yet, re-sending handshake frame (attempt "
                            + (attempt + 2) + ")");
                    sendFrameSingle(frame, -1, -1, -1);
                } catch (Exception e) {
                    System.out.println("[modern] handshake retry failed: " + e);
                    return;
                }
            }
        }, "enc-handshake-retry");
        t.setDaemon(true);
        t.start();
    }

    /** Sends RequestNetworkSettingsPacket (0xc1) — the first step of the modern login flow. */
    public void sendRequestNetworkSettings() throws Exception {
        java.io.ByteArrayOutputStream p = new java.io.ByteArrayOutputStream();
        putIntBE(p, 2193);   // protocolVersion (big-endian int)
        sendGamePacket(0xc1, p.toByteArray());
        System.out.println("[modern] requested network settings (protocol 2193)");
    }

    /** Sends ResourcePackClientResponsePacket (protocol >= 1.26.40 format). */
    public void sendResourcePackStatus(int status) throws Exception {
        java.io.ByteArrayOutputStream p = new java.io.ByteArrayOutputStream();
        putVarInt(p, status - 1);                                  // status as varint, 0-based
        putString(p, STATUS_NAMES[Math.min(status - 1, 3)]);      // type-name string
        // NOTE: no entry count for HAVE_ALL_PACKS/COMPLETED — only STATUS_SEND_PACKS has entries
        System.out.println("[modern] response packet bytes: " + bytesToHex(p.toByteArray(), p.size()));
        sendGamePacket(0x08, p.toByteArray());
        System.out.println("[modern] resource-pack status " + status + " (" + STATUS_NAMES[Math.min(status-1,3)] + ") sent");
    }

    /** Opens the transport: ping, then the two-stage open-connection handshake. */
    private int seq = 0;
    private int msgIndex = 0;
    private int orderIndex = 0;

    private static byte[] ltriad(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16)};
    }

    /** Sends one payload as a reliable-ordered RakNet data frame (splitting across frames if big). */
    private void sendFrame(byte[] payload) throws Exception {
        if (encryption != null && payload.length > 1 && payload[0] == (byte) GAME_PACKET_MARKER) {
            // Bedrock encrypts the MCPE layer, i.e. everything after the 0xfe marker:
            // [0xfe][cipher(prefix || compressed || checksum)]. RakNet splitting happens on top.
            byte[] region = new byte[payload.length - 1];
            System.arraycopy(payload, 1, region, 0, region.length);
            byte[] encrypted = encryption.encrypt(region);
            byte[] framed = new byte[encrypted.length + 1];
            framed[0] = (byte) GAME_PACKET_MARKER;
            System.arraycopy(encrypted, 0, framed, 1, encrypted.length);
            if (DEBUG_ENC) {
                System.out.println("[enc] send plain=" + region.length + "B wire=" + framed.length + "B head="
                        + bytesToHex(framed, Math.min(20, framed.length)));
            }
            payload = framed;
            lastEncryptedFrame = framed;
        }
        int maxPayload = mtu - 60;   // leave room for headers + split header
        if (payload.length <= maxPayload) {
            sendFrameSingle(payload, -1, -1, -1);
            return;
        }
        // split the payload
        int splitId = 1;
        int count = (payload.length + maxPayload - 1) / maxPayload;
        for (int i = 0; i < count; i++) {
            int start = i * maxPayload;
            int end = Math.min(start + maxPayload, payload.length);
            byte[] part = new byte[end - start];
            System.arraycopy(payload, start, part, 0, part.length);
            sendFrameSingle(part, count, splitId, i);
        }
    }

    private void sendFrameSingle(byte[] payload, int splitCount, int splitId, int splitIndex) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0x84);                       // DATA_PACKET_4
        lastSentSeq = seq;
        b.write(ltriad(seq++), 0, 3);
        boolean split = splitCount > 0;
        b.write(split ? 0x70 : 0x60);        // reliability 3 + split flag if splitting
        int bits = payload.length << 3;
        b.write((bits >>> 8) & 0xff);
        b.write(bits & 0xff);
        b.write(ltriad(msgIndex++), 0, 3);
        b.write(ltriad(orderIndex), 0, 3);
        b.write(0);                          // order channel
        if (!split) {
            orderIndex++;
        }
        if (split) {
            b.write((splitCount >>> 24) & 0xff);
            b.write((splitCount >>> 16) & 0xff);
            b.write((splitCount >>> 8) & 0xff);
            b.write(splitCount & 0xff);
            b.write((splitId >>> 8) & 0xff);
            b.write(splitId & 0xff);
            b.write((splitIndex >>> 24) & 0xff);
            b.write((splitIndex >>> 16) & 0xff);
            b.write((splitIndex >>> 8) & 0xff);
            b.write(splitIndex & 0xff);
        }
        b.write(payload, 0, payload.length);
        send(b.toByteArray());
    }

    /** Split-frame reassembly buffers, keyed by splitId. */
    private final java.util.Map<Integer, byte[][]> splitBuffers = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> splitExpected = new java.util.HashMap<>();

    /** Buffers one split part; returns the reassembled payload when all parts are in, else null. */
    private byte[] addSplitPart(int splitId, int count, int index, byte[] part) {
        byte[][] buf = splitBuffers.computeIfAbsent(splitId, k -> new byte[count][]);
        splitExpected.putIfAbsent(splitId, count);
        buf[index] = part;
        for (byte[] p : buf) {
            if (p == null) return null;
        }
        // all parts present: reassemble
        int total = 0;
        for (byte[] p : buf) total += p.length;
        byte[] full = new byte[total];
        int o = 0;
        for (byte[] p : buf) {
            System.arraycopy(p, 0, full, o, p.length);
            o += p.length;
        }
        splitBuffers.remove(splitId);
        splitExpected.remove(splitId);
        System.out.println("[modern] reassembled split id=" + splitId + " total=" + total + " bytes firstByte=0x" + (full.length>0?Integer.toHexString(full[0]&0xff):"empty"));
        return full;
    }

    /** Reads one datagram, ACKs it if it's a data packet, and returns the list of frame payloads. */
    private java.util.List<byte[]> readFrames() throws Exception {
        java.util.List<byte[]> frames = new java.util.ArrayList<>();
        byte[] reply = recv();
        if (reply.length == 0) {
            return frames;
        }
        int id = reply[0] & 0xff;
        if (id == 0xa0 || id == 0xc0) {
            // NACK / ACK: decode which datagram seqs are referenced
            int count = ((reply[1] & 0xff) << 8) | (reply[2] & 0xff);
            StringBuilder sb = new StringBuilder();
            sb.append(id == 0xa0 ? "NACK" : "ACK").append(" count=").append(count).append(" seqs=");
            int pos = 3;
            for (int i = 0; i < count && pos + 1 < reply.length; i++) {
                boolean range = reply[pos++] == 0;
                int start = (reply[pos] & 0xff) | ((reply[pos + 1] & 0xff) << 8) | ((reply[pos + 2] & 0xff) << 16);
                pos += 3;
                if (range) {
                    int endSeq = (reply[pos] & 0xff) | ((reply[pos + 1] & 0xff) << 8) | ((reply[pos + 2] & 0xff) << 16);
                    pos += 3;
                    sb.append(start).append("-").append(endSeq).append(" ");
                    if (id == 0xc0) { for (int s = start; s <= endSeq && s <= start + 4096; s++) ackedSeqs.add(s); }
                } else {
                    sb.append(start).append(" ");
                    if (id == 0xc0) ackedSeqs.add(start);
                }
            }
            System.out.println("[modern] " + sb);
            return frames;
        }
        if (id >= 0x80 && id <= 0x8f) {
            // a data packet: ack it, then decode all frames inside
            int seq = (reply[1] & 0xff) | ((reply[2] & 0xff) << 8) | ((reply[3] & 0xff) << 16);
            if (DEBUG_FRAMES) {
                System.out.println("[modern] recv data seq=" + seq + " len=" + reply.length
                        + " head=" + bytesToHex(reply, Math.min(24, reply.length)));
            }
            sendAck(seq);
            int off = 4;
            while (off < reply.length) {
                int flags = reply[off] & 0xff;
                int reliability = flags >> 5;
                boolean split = (flags & 0x10) != 0;
                int lenBits = ((reply[off + 1] & 0xff) << 8) | (reply[off + 2] & 0xff);
                int len = (lenBits + 7) / 8;
                int pos = off + 3;
                if (reliability > 0) {
                    if (reliability >= 2 && reliability != 5) pos += 3;
                    if (reliability <= 4 && reliability != 2) pos += 4;
                }
                int splitCount = -1, splitId = -1, splitIndex = -1;
                if (split) {
                    splitCount = ((reply[pos] & 0xff) << 24) | ((reply[pos+1]&0xff)<<16) | ((reply[pos+2]&0xff)<<8) | (reply[pos+3]&0xff);
                    splitId = ((reply[pos+4] & 0xff) << 8) | (reply[pos+5] & 0xff);
                    splitIndex = ((reply[pos+6] & 0xff) << 24) | ((reply[pos+7]&0xff)<<16) | ((reply[pos+8]&0xff)<<8) | (reply[pos+9]&0xff);
                    pos += 10;
                }
                int copyLen = Math.min(len, reply.length - pos);
                if (copyLen <= 0) break;
                byte[] payload = new byte[copyLen];
                System.arraycopy(reply, pos, payload, 0, copyLen);
                if (split) {
                    // buffer the part; return the reassembled payload when all parts are in
                    byte[] full = addSplitPart(splitId, splitCount, splitIndex, payload);
                    if (full != null) frames.add(full);
                } else {
                    frames.add(payload);
                }
                off = pos + len;
            }
            return frames;
        }
        // not a data frame (offline packet / ack) — return as a single raw frame
        frames.add(reply);
        return frames;
    }

    /** Responds to a ConnectedPing (0x00) with a ConnectedPong (0x03). */
    public void sendConnectedPong(byte[] pingPayload) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0x03);
        if (pingPayload.length >= 9) {
            b.write(pingPayload, 1, 8);   // echo the ping id
        } else {
            for (int i = 0; i < 8; i++) b.write(0);
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) b.write((byte) (now >>> (56 - i * 8)));
        sendFrame(b.toByteArray());
    }

    /** Sends an ACK for a received data frame sequence number. */
    private void sendAck(int seq) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0xc0);                       // ACK
        b.write(0x00);                       // record count hi
        b.write(0x01);                       // record count lo (1)
        b.write(0x01);                       // single seq (not a range)
        b.write(seq & 0xff);
        b.write((seq >>> 8) & 0xff);
        b.write((seq >>> 16) & 0xff);
        send(b.toByteArray());
    }

    /** Stage 3 of the handshake: CONNECTION_REQUEST then NEW_INCOMING_CONNECTION. */
    private boolean finishHandshake() throws Exception {
        long clientGuid = 0x0123456789abcdefL;
        java.io.ByteArrayOutputStream cr = new java.io.ByteArrayOutputStream();
        cr.write(ID_CONNECTION_REQUEST);
        for (int i = 0; i < 8; i++) cr.write((byte) (clientGuid >>> (56 - i * 8)));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) cr.write((byte) (now >>> (56 - i * 8)));
        cr.write(0);                          // no security
        sendFrame(cr.toByteArray());

        // read frames until we get CONNECTION_REQUEST_ACCEPTED (0x10), answering pings
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            java.util.List<byte[]> frames;
            try {
                frames = readFrames();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            for (byte[] payload : frames) {
                if (payload.length == 0) continue;
                int pid = payload[0] & 0xff;
                if (pid == 0x00) {
                    // ConnectedPing -> ConnectedPong
                    java.io.ByteArrayOutputStream pong = new java.io.ByteArrayOutputStream();
                    pong.write(0x03);
                    pong.write(payload, 1, 8);            // echo the ping id
                    for (int i = 0; i < 8; i++) pong.write((byte) (now >>> (56 - i * 8)));
                    sendFrame(pong.toByteArray());
                } else if (pid == ID_CONNECTION_REQUEST_ACCEPTED) {
                // send NEW_INCOMING_CONNECTION
                java.io.ByteArrayOutputStream nic = new java.io.ByteArrayOutputStream();
                nic.write(ID_NEW_INCOMING_CONNECTION);
                writeAddress(nic, new InetSocketAddress("127.0.0.1", 0));
                for (int i = 0; i < 20; i++) nic.write(0);
                for (int i = 0; i < 8; i++) nic.write((byte) (now >>> (56 - i * 8)));
                for (int i = 0; i < 8; i++) nic.write((byte) ((now + 1000) >>> (56 - i * 8)));
                sendFrame(nic.toByteArray());
                return true;
                }
            }
        }
        return false;
    }

    private static int indexOf(byte[] data, byte target) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }

    public boolean connect() {
        try {
            ping();
            if (!openConnection1()) {
                return false;
            }
            if (!openConnection2()) {
                return false;
            }
            if (!finishHandshake()) {
                return false;
            }
            System.out.println("[modern] transport open, mtu=" + mtu + " serverGuid=" + serverGuid);
            return true;
        } catch (Exception e) {
            System.out.println("[modern] connect failed: " + e);
            return false;
        }
    }

    private void send(byte[] data) throws Exception {
        socket.send(new DatagramPacket(data, data.length, backend));
    }

    private byte[] recv() throws Exception {
        byte[] buf = new byte[65535];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        socket.receive(p);
        byte[] out = new byte[p.getLength()];
        System.arraycopy(p.getData(), 0, out, 0, p.getLength());
        return out;
    }

    private void ping() throws Exception {
        // modern unconnected ping: [0x01][long time][magic][long clientGuid]
        byte[] p = new byte[33];
        p[0] = (byte) ID_UNCONNECTED_PING;
        long t = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) {
            p[1 + i] = (byte) (t >>> (56 - i * 8));
        }
        System.arraycopy(MAGIC, 0, p, 9, 16);
        long guid = 0x0123456789abcdefL;
        for (int i = 0; i < 8; i++) {
            p[25 + i] = (byte) (guid >>> (56 - i * 8));
        }
        send(p);
        byte[] pong = recv(); // pong
        System.out.println("[modern] pong id=0x" + Integer.toHexString(pong[0] & 0xff) + " len=" + pong.length);
    }

    private boolean openConnection1() throws Exception {
        byte[] p = new byte[18 + 1446];
        p[0] = ID_OPEN_CONNECTION_REQUEST_1;
        System.arraycopy(MAGIC, 0, p, 1, 16);
        p[17] = (byte) RAKNET_PROTOCOL;
        send(p);
        byte[] reply = recv();
        System.out.println("[modern] reply1 len=" + reply.length + " raw: " + bytesToHex(reply, reply.length));
        if ((reply[0] & 0xff) != ID_OPEN_CONNECTION_REPLY_1) {
            return false;
        }
        // cloudburst layout: [0x06][magic][serverGuid][useSecurity byte][cookie int][mtu short]
        long guid = 0;
        for (int i = 0; i < 8; i++) {
            guid = (guid << 8) | (reply[17 + i] & 0xffL);
        }
        this.serverGuid = guid;
        int ck = 0;
        for (int i = 0; i < 4; i++) {
            ck = (ck << 8) | (reply[26 + i] & 0xff);
        }
        this.cookie = ck;
        this.mtu = ((reply[30] & 0xff) << 8) | (reply[31] & 0xff);
        System.out.println("[modern] reply1 parsed: guid=" + guid + " cookie=" + cookie + " mtu=" + mtu);
        return true;
    }

    private static String bytesToHex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }

    private boolean openConnection2() throws Exception {
        // cloudburst REQUEST_2 (cookie mode): [0x07][magic][cookie int][security byte]
        //                                      [server address][short mtu][long clientGuid]
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(ID_OPEN_CONNECTION_REQUEST_2);
        b.write(MAGIC);
        for (int i = 3; i >= 0; i--) b.write((cookie >>> (i * 8)) & 0xff);
        b.write(0);   // no security
        writeAddress(b, backend);
        b.write((mtu >>> 8) & 0xff);
        b.write(mtu & 0xff);
        long clientGuid = 0x0123456789abcdefL;
        for (int i = 0; i < 8; i++) {
            b.write((byte) (clientGuid >>> (56 - i * 8)));
        }
        send(b.toByteArray());
        byte[] reply = recv();
        System.out.println("[modern] reply2 len=" + reply.length + " raw: " + bytesToHex(reply, Math.min(40, reply.length)));
        return (reply[0] & 0xff) == ID_OPEN_CONNECTION_REPLY_2;
    }

    private static void writeAddress(java.io.ByteArrayOutputStream b, InetSocketAddress addr) {
        b.write(4);
        for (String part : addr.getAddress().getHostAddress().split("\\.")) {
            b.write((~Integer.parseInt(part)) & 0xff);
        }
        b.write((addr.getPort() >>> 8) & 0xff);
        b.write(addr.getPort() & 0xff);
    }
}
