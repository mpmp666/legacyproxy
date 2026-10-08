package proxy;

import proxy.legacy.LegacySession;
import proxy.legacy.LegacySessionListener;
import proxy.legacy.LegacyPackets;
import proxy.modern.ModernClient;
import proxy.modern.ModernCodec;

import cn.nukkit.network.protocol.InventoryTransactionPacket;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One bridge per 0.14.3 client: owns the frontend legacy session and the backend modern
 * connection, and translates packets in both directions.
 *
 * Flow: 0.14 client logs in -> PlayStatus(LOGIN_SUCCESS) -> we log into the backend ->
 * backend StartGame becomes the 0.14 StartGame -> every backend chunk is converted to the
 * 0.14 columnar format and streamed to the old client.
 */
public class ProxyClientSession implements LegacySessionListener {

    private static final int GAMEMODE_CREATIVE = 1;

    /** 0.14 MovePlayer carries the EYE position; the modern backend tracks the FEET position. */
    private static final float EYE_HEIGHT = 1.62f;

    private final InetSocketAddress backend;
    /**
     * When true (the default) the bridge refuses to serve a 0.14.3 client unless the backend
     * negotiated connection encryption, i.e. it sent a ServerToClientHandshake we answered.
     */
    private final boolean requireEncryption;
    private LegacySession legacySession;
    private ModernClient modernClient;
    private String username = "Player";
    private byte[] uuid = new byte[16];
    private String skinName = "Standard_Custom";
    private byte[] skin = new byte[0];

    /**
     * Set once the 0.14 world bootstrap has gone out. Anything the old client cannot accept
     * before that is queued instead of being sent into the void: an AddPlayer that arrives while
     * it is still on its loading screen is dropped because its level does not exist yet, which is
     * what used to make modern players permanently invisible.
     */
    private volatile boolean worldReady;
    /** Rate limit for the entity-metadata diagnostics. */
    private volatile long lastMetadataLog;    private final java.util.List<Runnable> deferredLegacy = new java.util.concurrent.CopyOnWriteArrayList<>();

    // world state learned from the backend
    // 0.14.3 requires the local player's entity id to be 0.
    private final long entityId = 0L;
    /** The backend's runtime entity id for us 閳?needed by every client->server packet. */
    private volatile long runtimeEntityId = 0L;
    /** Last inventory contents the backend reported: slot -> {id, damage, count}. */
    private volatile int[][] invCache = new int[0][];

    /** Last known FEET position (0.14 reports eye height; we convert on the way in). */
    private volatile float lastX = 0f, lastY = 0f, lastZ = 0f;
    private volatile float lastYaw = 0f, lastPitch = 0f, lastHeadYaw = 0f;
    /** Monotonic tick for PlayerAuthInputPacket. */
    private final java.util.concurrent.atomic.AtomicLong authTick = new java.util.concurrent.atomic.AtomicLong(1);
    private volatile boolean locallyInitialized = false;

    /** Other players the backend told us about: modern runtime id -> 0.14 view of them. */
    private final java.util.Map<Long, TrackedEntity> entities = new java.util.concurrent.ConcurrentHashMap<>();
    /** 0.14 entity ids handed out to other players (0 is us; the backend starts at 1). */
    private final java.util.concurrent.atomic.AtomicLong nextLegacyEid = new java.util.concurrent.atomic.AtomicLong(1000);

    /** One remote player as the 0.14 client sees it. */
    private static final class TrackedEntity {
        final long legacyId;
        final String name;
        final byte[] uuid;
        float x, y, z, yaw, headYaw, pitch;
        boolean onGround;

        TrackedEntity(long legacyId, String name, byte[] uuid) {
            this.legacyId = legacyId;
            this.name = name;
            this.uuid = uuid;
        }
    }

    /** Real skins lifted out of the backend's PlayerList packet, keyed by player uuid. */
    private final java.util.Map<String, byte[]> playerSkins = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * All live 0.14 sessions in this proxy, keyed by username.
     *
     * <p>The backend's viewer tracking does not reliably spawn proxy players to each other (its
     * per-chunk loader bookkeeping is built around real clients), so 0.14 clients are relayed to
     * each other directly here 閳?the same thing the in-server legacy layer had to do. Modern
     * players still arrive through the AddPlayer translation.
     */
    private static final java.util.Map<String, ProxyClientSession> SESSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Entity ids used for the 0.14<->0.14 relay (kept clear of the backend-translated 1000+ range). */
    private static final java.util.concurrent.atomic.AtomicLong RELAY_EID =
            new java.util.concurrent.atomic.AtomicLong(5000);
    private long relayEid = 0;
    private volatile boolean relayRegistered = false;
    private volatile float relayYaw = 0f, relayHeadYaw = 0f, relayPitch = 0f;
    private volatile long lastRelayMove = 0L;
    /** Dropped items: modern entity UNIQUE id -> the 0.14 entity id we showed them under.
     *  (RemoveEntityPacket carries the unique id, not the runtime id.) */
    private final java.util.Map<Long, Long> itemEntities = new java.util.concurrent.ConcurrentHashMap<>();
    /** runtime id -> unique id, because TakeItemEntity carries runtime ids. */
    private final java.util.Map<Long, Long> itemRuntimeToUnique = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Entities the backend already removed before we ever showed them.
     *
     * <p>A block broken at the player's feet drops an item that is picked up in the same tick, so
     * the RemoveEntity can arrive BEFORE the AddItemEntity. Showing it afterwards leaves a ghost
     * drop that only disappears on relog.
     */
    private final java.util.Map<Long, Long> removedBeforeShown = new java.util.concurrent.ConcurrentHashMap<>();
    /** Temporary diagnostic counter for untracked movement deltas. */
    private int deltaDebug = 0;
    private volatile float spawnX = 128, spawnY = 82, spawnZ = 128;
    private volatile int gamemode = GAMEMODE_CREATIVE;
    private volatile boolean worldSent = false;
    private volatile boolean spawned = false;
    private final java.util.Set<Long> sentChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ProxyClientSession(InetSocketAddress backend, boolean requireEncryption) {
        this.backend = backend;
        this.requireEncryption = requireEncryption;
    }

    @Override
    public void onConnected(LegacySession session) {
        this.legacySession = session;
    }

    @Override
    public void onGamePacket(LegacySession session, byte[] buffer) {
        if (buffer.length == 0) return;
        // Game payloads arrive wrapped in the 0x8e marker byte; strip it.
        int off = (buffer[0] & 0xff) == 0x8e ? 1 : 0;
        if (buffer.length <= off) return;
        int packetId = buffer[off] & 0xff;
        if (packetId == 0x8f) {              // LOGIN_PACKET (0.14)
            handleLegacyLogin(session, buffer, off);
            return;
        }
        if (!worldSent || modernClient == null) return;   // nothing to forward until the backend is up
        switch (packetId) {
            case 0x9d:  // MovePlayer (0.14) -> modern PlayerAuthInputPacket (0x90)
                trackPosition(buffer, off);
                relayYaw = lastYaw;
                relayHeadYaw = lastHeadYaw;
                relayPitch = lastPitch;
                relayMove();
                // This backend ignores MovePlayerPacket for every protocol >= 1.21.90 (movement is
                // forced server-authoritative), so movement has to travel as PlayerAuthInput or
                // other players never see us move.
                quiet(() -> modernClient.sendBody(ModernCodec.playerAuthInput(
                        lastX, lastY, lastZ, lastPitch, lastYaw, lastHeadYaw, authTick.incrementAndGet())));
                break;
            case 0x93:  // Text (0.14) -> modern TextPacket (0x09), or CommandRequest (0x4d) for "/cmd"
            {
                int textType = (off + 1) < buffer.length ? (buffer[off + 1] & 0xff) : -1;
                String text = Translator.lastText(buffer, off);
                System.out.println("[proxy] 0.14 text type=" + textType + " : " + text);
                // A leading "/" is a command: the backend only dispatches those from
                // CommandRequestPacket, so sending it as chat would just broadcast it.
                String message = Translator.textMessage(buffer, off).trim();
                if (message.startsWith("/")) {
                    final String cmd = message;
                    quiet(() -> modernClient.sendBody(ModernCodec.command(cmd, uuid, runtimeEntityId)));
                    System.out.println("[proxy] command -> backend: " + cmd);
                } else {
                    byte[] chat = Translator.toModernText(buffer, off, username);
                    if (chat != null) quiet(() -> modernClient.sendPacket(0x09, chat));
                }
                break;
            }
            case 0x9e:  // RemoveBlock (0.14) -> modern InventoryTransaction BREAK_BLOCK
                handleLegacyRemoveBlock(buffer, off);
                break;
            case 0xaa:  // UseItem (0.14) -> modern InventoryTransaction CLICK_BLOCK
                handleLegacyUseItem(buffer, off);
                break;
            case 0xa9:  // Interact (0.14) -> modern InventoryTransaction USE_ITEM_ON_ENTITY
                handleLegacyInteract(buffer, off);
                break;
            case 0xab:  // PlayerAction (0.14) -> modern PlayerActionPacket (0x24)
                handleLegacyPlayerAction(buffer, off);
                break;
            case 0xb3:  // Respawn (0.14): the player tapped respawn on the death screen
                handleLegacyRespawn(buffer, off);
                break;
            default:
                break;
        }
    }

    /**
     * 0.14 RemoveBlock (0x9e): [long eid][int x][int z][byte y] 閳?the creative instant-break.
     * Modern Bedrock breaks blocks through an InventoryTransaction with actionType BREAK_BLOCK.
     */
    private void handleLegacyRemoveBlock(byte[] buffer, int off) {
        try {
            proxy.legacy.LegacyBinary.Reader r = new proxy.legacy.LegacyBinary.Reader(buffer, off + 1);
            r.getLong();                     // player eid (always 0 for 0.14)
            int x = r.getInt();
            int z = r.getInt();
            int y = r.getByte();
            System.out.println("[proxy] break block " + x + "," + y + "," + z);
            quiet(() -> {
                byte[] brk = ModernCodec.useItemOnBlock(runtimeEntityId,
                        InventoryTransactionPacket.USE_ITEM_ACTION_BREAK_BLOCK,
                        x, y, z, 1, 0, 0, 0, 0,
                        lastX, lastY, lastZ, 0.5f, 0.5f, 0.5f);
                System.out.println("[proxy] break -> modern " + brk.length + "B " + hex(brk));
                modernClient.sendBody(brk);
            });
        } catch (Exception e) {
            System.out.println("[proxy] remove block translate failed: " + e);
        }
    }

    /**
     * 0.14 UseItem (0xaa): [int x][int y][int z][byte face][float fx][fy][fz][float px][py][pz]
     * [int hotbarSlot][short itemId][byte count][short damage][LShort nbtLen].
     * Maps onto a modern InventoryTransaction with actionType CLICK_BLOCK (place / interact).
     */
    private void handleLegacyUseItem(byte[] buffer, int off) {
        try {
            proxy.legacy.LegacyBinary.Reader r = new proxy.legacy.LegacyBinary.Reader(buffer, off + 1);
            int x = r.getInt();
            int y = r.getInt();
            int z = r.getInt();
            int face = r.getByte();
            float fx = r.getFloat();
            float fy = r.getFloat();
            float fz = r.getFloat();
            float px = r.getFloat();
            float py = r.getFloat();
            float pz = r.getFloat();
            int slot = r.getInt();
            int itemId = r.getShort();
            int count = r.getByte();
            int damage = r.getShort();
            System.out.println("[proxy] use/place item=" + itemId + ":" + damage + " at "
                    + x + "," + y + "," + z + " face=" + face + " slot=" + slot);
            // The backend places the item it believes we hold, so mirror the old client's hotbar
            // choice into the backend inventory and select that slot before the click.
            final int fSlot = slot, fId = itemId, fDamage = damage, fCount = count;
            final int fx2 = x, fy2 = y, fz2 = z, fFace = face;
            final float cfx = fx, cfy = fy, cfz = fz;
            final float ppx = px, ppy = py, ppz = pz;
            // What the backend currently holds in that slot: its validation compares the slot
            // contents against the oldItem we claim, so we must mirror it exactly.
            int[] old = (fSlot >= 0 && fSlot < invCache.length) ? invCache[fSlot] : new int[]{0, 0, 0};
            quiet(() -> {
                // Only creative needs this: there the old client conjures items locally and the
                // backend has no idea what it holds. In survival the backend already owns the
                // inventory (we forward its contents), and the balancing actions below are
                // creative-only.
                if (fId > 0 && gamemode == GAMEMODE_CREATIVE) {
                    byte[] set = ModernCodec.setInventorySlot(fSlot,
                            old[0], old[1], old[2], fId, fDamage, fCount);
                    System.out.println("[proxy] place -> setslot " + set.length + "B " + hex(set));
                    modernClient.sendBody(set);
                }
                if (fId > 0) {
                    byte[] eq = ModernCodec.mobEquipment(runtimeEntityId, fSlot, fId, fDamage, fCount);
                    System.out.println("[proxy] place -> mobeq   " + eq.length + "B " + hex(eq));
                    modernClient.sendBody(eq);
                }
                byte[] click = ModernCodec.useItemOnBlock(runtimeEntityId,
                        InventoryTransactionPacket.USE_ITEM_ACTION_CLICK_BLOCK,
                        fx2, fy2, fz2, fFace, fSlot, fId, fDamage, fCount,
                        ppx, ppy, ppz, cfx, cfy, cfz);
                System.out.println("[proxy] place -> click   " + click.length + "B " + hex(click));
                modernClient.sendBody(click);
            });
        } catch (Exception e) {
            System.out.println("[proxy] use item translate failed: " + e);
        }
    }

    /** 0.14 PlayerAction (0xab): [long eid][int action][int x][int y][int z][int face]. */
    private void handleLegacyPlayerAction(byte[] buffer, int off) {
        try {
            proxy.legacy.LegacyBinary.Reader r = new proxy.legacy.LegacyBinary.Reader(buffer, off + 1);
            r.getLong();
            int action = r.getInt();
            int x = r.getInt();
            int y = r.getInt();
            int z = r.getInt();
            int face = r.getInt();
            // actions 0..12 share the same numbering on both sides (start/abort/stop break,
            // jump, sprint, sneak...); forward only the ones the backend actually acts on.
            if (action >= 0 && action <= 12) {
                quiet(() -> modernClient.sendBody(ModernCodec.playerAction(runtimeEntityId, action, x, y, z, face)));
            }
        } catch (Exception e) {
            System.out.println("[proxy] player action translate failed: " + e);
        }
    }

    /**
     * 0.14 Interact (0xa9): {@code [byte action][long target]}, action 2 = left click.
     *
     * <p>A left click is how the old client attacks. It has to become a modern
     * {@code InventoryTransaction} with {@code TYPE_USE_ITEM_ON_ENTITY} so that the backend runs
     * the damage calculation and broadcasts the result itself. Without this translation a 0.14
     * player simply cannot hit anybody — no matter what the target's version is.
     *
     * <p>The packet does not carry the held item, and it does not need to: the server damages with
     * the item in its own authoritative inventory, which this bridge already mirrors.
     */
    private void handleLegacyInteract(byte[] buffer, int off) {
        if (modernClient == null) return;
        try {
            proxy.legacy.LegacyBinary.Reader r = new proxy.legacy.LegacyBinary.Reader(buffer, off + 1);
            int action = r.getByte();
            long targetEid = r.getLong();
            if (action != 2) {                       // ACTION_LEFT_CLICK; the rest is not an attack
                return;
            }
            long modernEid = modernEidForLegacy(targetEid);
            if (modernEid == 0) {
                // 0.14↔0.14 players are shown by this proxy's own relay, not by the backend, so the
                // target may be another session rather than one of our tracked entities: map its
                // relay id onto that session's backend entity id.
                for (ProxyClientSession other : SESSIONS.values()) {
                    if (other != this && other.relayEid == targetEid && other.runtimeEntityId != 0) {
                        modernEid = other.runtimeEntityId;
                        System.out.println("[" + username + "] attack on relayed " + other.username
                                + " -> backend modernEid=" + modernEid);
                        break;
                    }
                }
            }
            if (modernEid == 0) {
                System.out.println("[" + username + "] attack on an untracked entity legacyEid=" + targetEid);
                return;
            }
            final long attackTarget = modernEid;
            // The click position is where the hit lands: the target's own position. Sending zeros
            // here (or a stale player position) is what makes the backend treat the hit as
            // out of range and drop it.
            double tx = lastX, ty = lastY, tz = lastZ;
            TrackedEntity target = entities.get(attackTarget);
            if (target != null) {
                tx = target.x; ty = target.y; tz = target.z;
            } else {
                for (ProxyClientSession other : SESSIONS.values()) {
                    if (other != this && other.runtimeEntityId == attackTarget) {
                        tx = other.lastX; ty = other.lastY; tz = other.lastZ;
                        break;
                    }
                }
            }
            final float clickX = (float) tx, clickY = (float) ty, clickZ = (float) tz;
            final float playerX = (float) lastX, playerY = (float) lastY, playerZ = (float) lastZ;
            System.out.println("[" + username + "] attack -> backend modernEid=" + attackTarget
                    + " click=" + clickX + "," + clickY + "," + clickZ);
            quiet(() -> modernClient.sendBody(ModernCodec.attackEntity(attackTarget, 0,
                    0, 0, 0, playerX, playerY, playerZ, clickX, clickY, clickZ)));
        } catch (Exception e) {
            System.out.println("[proxy] interact translate failed: " + e);
        }
    }

    /** The modern runtime id behind a 0.14 entity id, or 0 when we are not tracking it. */
    private long modernEidForLegacy(long legacyEid) {
        for (java.util.Map.Entry<Long, TrackedEntity> e : entities.entrySet()) {
            if (e.getValue().legacyId == legacyEid) {
                return e.getKey();
            }
        }
        return 0L;
    }

    /** 0.14 Respawn (0xb3) -> modern RespawnPacket (0x2d) with STATE_CLIENT_READY_TO_SPAWN. */
    private void handleLegacyRespawn(byte[] buffer, int off) {
        try {
            proxy.legacy.LegacyBinary.Reader r = new proxy.legacy.LegacyBinary.Reader(buffer, off + 1);
            float x = r.getFloat();
            float y = r.getFloat();
            float z = r.getFloat();
            System.out.println("[" + username + "] client respawn request at " + x + "," + y + "," + z);
            quiet(() -> modernClient.sendBody(ModernCodec.respawn(x, y, z, runtimeEntityId)));
        } catch (Exception e) {
            System.out.println("[proxy] legacy respawn translate failed: " + e);
        }
    }

    /** Remembers where the player is and which way they face (both sides use the EYE position). */
    private void trackPosition(byte[] buffer, int off) {
        try {
            int p = off + 1 + 8;
            lastX = Translator.readFloatBE(buffer, p);
            lastY = Translator.readFloatBE(buffer, p + 4);
            lastZ = Translator.readFloatBE(buffer, p + 8);
            lastYaw = Translator.readFloatBE(buffer, p + 12);
            lastHeadYaw = Translator.readFloatBE(buffer, p + 16);
            lastPitch = Translator.readFloatBE(buffer, p + 20);
        } catch (Exception ignored) {
        }
    }

    private void handleLegacyLogin(LegacySession session, byte[] loginPacket, int off) {
        parseLegacyLogin(loginPacket, off);
        System.out.println("[proxy] 0.14 client logged in as " + username + " uuid="
                + bytesToUuid(uuid) + " skin=" + skinName + " skinLen=" + skin.length);

        // *** THE BUG THAT KEPT THE CLIENT ON "鐎规矮缍呴張宥呭閸? ***
        // Every 0.14 game packet MUST be wrapped in the 0x8e encapsulation marker. The client
        // (like the 0.14.3 server, Nukkit-0143 RakNetInterface.getPacket) reads the real packet
        // id from byte[1] -- "byte pid = buffer[1]; data.setOffset(2)" -- so a bare frame is
        // decoded as pid 0x00, matches no packet and is silently discarded.
        // This PlayStatus(LOGIN_SUCCESS) used to be sent BARE, so the client never learned that
        // login succeeded: it sat on the "locating server" screen forever, ignored StartGame and
        // never sent RequestChunkRadius. Everything below goes through sendLegacy().
        java.util.Map<Integer, Object> meta = new java.util.HashMap<>();
        meta.put(2, LegacyPackets.MetadataEntry.of(LegacyPackets.DATA_TYPE_STRING, username));
        sendLegacy(LegacyPackets.setEntityData(this.entityId, LegacyPackets.writeMetadata(meta)));
        sendLegacy(LegacyPackets.playStatus(0));   // LOGIN_SUCCESS
        // NOTE: our own PlayerList entry is deliberately NOT sent here. The 0.14.3 client ignores
        // player-list entries that arrive before StartGame, which is why the pause menu stayed
        // empty. The 0.14.3 server sends it after the world bootstrap (see onBackendStartGame).
        new Thread(this::connectBackend, "proxy-backend-" + username).start();
    }

    /** Full 0.14 Login decode: [u16 username][int proto][int proto][long clientId][16B uuid]
     *  [string serverAddress][string clientSecret][string skinName][short skinLen][skin]. */
    private void parseLegacyLogin(byte[] buf, int off) {
        try {
            proxy.legacy.LegacyBinary.Reader r = new proxy.legacy.LegacyBinary.Reader(buf, off + 1);
            String name = r.getString();
            if (name != null && !name.trim().isEmpty()) username = name.trim();
            r.getInt();                                  // protocol 1
            r.getInt();                                  // protocol 2
            r.getLong();                                 // client id
            byte[] id = r.getUUID();
            if (id != null && id.length == 16) uuid = id;
            r.getString();                               // server address
            r.getString();                               // client secret
            String sName = r.getString();
            if (sName != null && !sName.isEmpty()) skinName = sName;
            int skinLen = r.getShort();
            if (skinLen > 0 && skinLen <= r.remaining()) skin = r.get(skinLen);
        } catch (Exception e) {
            System.out.println("[proxy] login decode failed (using defaults): " + e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) s.append(String.format("%02x ", b[i]));
        return s.toString().trim();
    }

    private static String bytesToUuid(byte[] b) {        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) s.append('-');
            s.append(String.format("%02x", b[i]));
        }
        return s.toString();
    }

    private void connectBackend() {
        try {
            modernClient = new ModernClient(backend);
            modernClient.onNetworkSettings = () -> quiet(() -> modernClient.sendLogin(username));
            // The backend verifies the login chain asynchronously and only continues while the
            // session is still in LOGIN_RECEIVED. Replying COMPLETED instantly can advance the
            // phase first and make that callback bail out, so give the verification a moment.
            modernClient.onResourcePacksInfo = () -> new Thread(() -> {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
                quiet(() -> modernClient.sendResourcePackStatus(4));
            }, "proxy-pack-reply").start();
            modernClient.onResourcePackStack = () -> quiet(() -> modernClient.sendResourcePackStatus(4));
            modernClient.onStartGame = () -> quiet(this::onBackendStartGame);
            modernClient.onGamePacket = this::onBackendGamePacket;
            // A dropped backend connection is as final as a kick: tell the old client instead of
            // leaving it in a world nothing is driving any more.
            modernClient.onClosed = () -> kickLegacy("Connection to the server was lost");
            if (!modernClient.connect()) {
                System.out.println("[proxy] backend handshake failed");
                legacySession.close("backend handshake failed");
                return;
            }
            System.out.println("[proxy] backend handshake ok, logging in as " + username);
            modernClient.sendRequestNetworkSettings();
            modernClient.runReadLoop();
        } catch (Exception e) {
            System.out.println("[proxy] backend connection failed: " + e);
            legacySession.close("backend connection failed");
        }
    }

    /** The backend finished logging us in: send the 0.14 world bootstrap. */
    private void onBackendStartGame() throws Exception {
        // The backend only reaches StartGame once it has accepted the login. If it never asked for
        // connection encryption the traffic is in the clear, which is exactly what
        // require-encryption=true forbids: drop the legacy client instead of playing.
        if (requireEncryption && (modernClient == null || !modernClient.isEncrypted())) {
            System.out.println("[proxy] refusing backend: connection encryption was not negotiated"
                    + " (require-encryption=true)");
            legacySession.close("This server requires an encrypted connection");
            return;
        }
        System.out.println("[proxy] backend StartGame -> sending 0.14 world (spawn "
                + spawnX + "," + spawnY + "," + spawnZ + " gamemode " + gamemode + ")");
        // 0.14 StartGame: the player's own entity id MUST be 0 (Nukkit-0143:
        // "Always use EntityID as zero for the actual player"), and the spawn y is the EYE height.
        float eyeY = spawnY + 1.62f;
        sendLegacy(LegacyPackets.startGame(0, 0, 1, gamemode,
                this.entityId, (int) spawnX, (int) spawnY, (int) spawnZ, spawnX, eyeY, spawnZ));
        sendLegacy(LegacyPackets.setTime(6000, true));
        sendLegacy(LegacyPackets.setSpawnPosition((int) spawnX, (int) spawnY, (int) spawnZ));
        // UpdateAttributes must follow StartGame or the client never leaves "locating server".
        sendLegacy(LegacyPackets.updateAttributes(this.entityId,
                new String[]{"generic.health", "generic.movementSpeed"},
                new float[][]{{0f, 20f, 20f}, {0f, 1f, 0.1f}}));
        sendLegacy(LegacyPackets.setDifficulty(1));
        sendLegacy(LegacyPackets.setHealth(20));
        // userPermission=2 (OPERATOR) so the old client may build; flags=0
        sendAdventureSettings();
        sendLegacy(LegacyPackets.chunkRadiusUpdate(8));
        // Only a creative player gets the creative item list. Sending window 0x79 while the
        // backend is in survival is what made the old client *look* like creative mode while
        // actually being unable to fly (the backend still enforced survival).
        if (gamemode == GAMEMODE_CREATIVE) {
            sendLegacy(LegacyPackets.containerSetContent(0x79, buildCreativeItems()));
        }
        // The recipe list that drives the 0.14 crafting UI. Built from the backend's own recipe
        // registry with every post-0.14 recipe filtered out 閳?the modern list (~1 MB) is never
        // forwarded. Batched because it is large.
        try {
            byte[] crafting = CraftingData.build();
            if (crafting != null && crafting.length > 5) {
                sendLegacyBatched(crafting);
            }
        } catch (Throwable t) {
            System.out.println("[proxy] crafting data send failed: " + t);
        }
        // Our own player-list entry, sent AFTER StartGame like the 0.14.3 server does. Before
        // StartGame the client has no local player yet and drops the entry, leaving the pause
        // menu's player list blank.
        sendLegacy(LegacyPackets.playerListAdd(uuid, this.entityId, username,
                skinName, skin.length > 0 ? skin : placeholderSkin()));
        worldSent = true;
        // Tell the backend where we are right away. Its chunk stream is driven by the player's
        // position, and without a first movement/auth-input a session can sit there with no
        // chunks at all (which also means it never reaches PLAYER_SPAWN and times out).
        quiet(() -> modernClient.sendBody(ModernCodec.playerAuthInput(
                spawnX, spawnY + EYE_HEIGHT, spawnZ, 0f, 0f, 0f, authTick.incrementAndGet())));
        // ask the backend for chunks around the player; Respawn + PLAYER_SPAWN are sent
        // once the first chunks are in (see forwardChunk), matching the 0.14.3 server order.
        requestChunks();
        // Some sessions do not get their first chunk on the first request; keep asking until the
        // stream starts, otherwise the old client hangs on the loading screen forever.
        Thread retry = new Thread(() -> {
            for (int i = 0; i < 6; i++) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
                if (!sentChunks.isEmpty() || legacySession == null || legacySession.isClosed()) {
                    return;
                }
                // Chunks have not started after 1.5s. The backend spawns every entity that is
                // already around us during doFirstSpawn, and it reads the chunks *we* have loaded
                // at that moment — so the normal order is chunks first, spawn handshake second
                // (see forwardChunk). Some sessions never get a proactive chunk stream though, and
                // then that order deadlocks: the old client sits on its loading screen until the
                // backend times it out. Breaking the deadlock costs the spawns of already-present
                // players, which is strictly better than never loading at all.
                if (i == 0) {
                    System.out.println("[" + username + "] no chunk stream, forcing the spawn handshake");
                    markLocallyInitialized();
                }
                System.out.println("[" + username + "] no chunks yet, re-requesting (attempt "
                        + (i + 2) + ")");
                quiet(this::requestChunks);
                quiet(() -> modernClient.sendBody(ModernCodec.playerAuthInput(
                        spawnX, spawnY + EYE_HEIGHT, spawnZ, 0f, 0f, 0f, authTick.incrementAndGet())));
            }
        }, "chunk-retry-" + username);
        retry.setDaemon(true);
        retry.start();
        System.out.println("[proxy] 0.14 bootstrap sent for " + username);
    }

    /** Runs {@code send} now if the 0.14 client already has its world, else queues it. */
    private void sendLegacyWhenReady(Runnable send) {
        if (worldReady) {
            send.run();
        } else {
            deferredLegacy.add(send);
        }
    }

    /** Delivers everything that was queued before the world bootstrap. */
    private void flushDeferredLegacy() {
        if (deferredLegacy.isEmpty()) {
            return;
        }
        java.util.List<Runnable> pending = new java.util.ArrayList<>(deferredLegacy);
        deferredLegacy.clear();
        System.out.println("[" + username + "] flushing " + pending.size()
                + " entity spawn(s) queued during loading");
        for (Runnable send : pending) {
            try {
                send.run();
            } catch (Throwable t) {
                System.out.println("[" + username + "] deferred spawn failed: " + t);
            }
        }
    }

    private void requestChunks() throws Exception {
        java.io.ByteArrayOutputStream p = new java.io.ByteArrayOutputStream();
        p.write(8);        // varint radius = 8
        p.write(8);        // maxRadius byte (protocol >= 1.19.80)
        modernClient.sendPacket(0x45, p.toByteArray());
        System.out.println("[proxy] requested backend chunk radius 8");
    }

    /**
     * modern DisconnectPacket (0x05) — the backend kicked us or is shutting down.
     *
     * <p>Layout for protocol 2193: {@code [varint reason][uvarint hideDisconnectionScreen]
     * [string message][string filteredMessage]}. The old client has its own DisconnectPacket
     * (0x91) carrying only a message, so the reason and the filtered copy are dropped. Without
     * this the 0.14 client is simply never told: it sits in the world with a dead backend
     * connection until it times out on its own.
     */
    private void handleBackendDisconnect(byte[] payload) {
        String message = "Disconnected from server";
        try {
            int[] p = new int[]{0};
            Translator.readUVarInt64(payload, p);                  // DisconnectFailReason
            boolean hide = Translator.readUVarInt64(payload, p) != 0;
            if (!hide) {
                message = Translator.readString(payload, p);
            }
        } catch (Exception e) {
            System.out.println("[" + username + "] disconnect parse failed: " + e);
        }
        System.out.println("[" + username + "] backend disconnected us: " + message);
        kickLegacy(message);
    }

    /** Sends the 0.14 DisconnectPacket (0x91) and closes the old client's session. */
    private void kickLegacy(String message) {
        if (legacySession == null || legacySession.isClosed()) {
            return;                                               // already gone
        }
        System.out.println("[" + username + "] telling the 0.14 client: " + message);
        sendLegacy(LegacyPackets.disconnect(message));
        // The packet is only queued; it has to be on the wire before the session goes away.
        legacySession.flushNow();
        legacySession.close(message);
    }

    /** Tells the backend we are locally initialised, once. This is what triggers doFirstSpawn(). */
    private void markLocallyInitialized() {
        if (locallyInitialized || modernClient == null) {
            return;
        }
        locallyInitialized = true;
        quiet(() -> {
            modernClient.sendBody(ModernCodec.setLocalPlayerAsInitialized(runtimeEntityId));
            System.out.println("[proxy] sent SetLocalPlayerAsInitialized to backend (eid "
                    + runtimeEntityId + ")");
        });
    }

    /** Every backend game packet: translate the interesting ones to 0.14. */
    private void onBackendGamePacket(int packetId, byte[] payload) {        try {
            switch (packetId) {
                case 0x05:  // DisconnectPacket — the backend kicked us or is shutting down
                    handleBackendDisconnect(payload);
                    break;
                case 0x0b:  // StartGame 闁?record spawn/gamemode for the 0.14 StartGame
                    parseStartGame(payload);
                    break;
                case 0x3a:  // LevelChunk 闁?convert and forward
                    forwardChunk(payload);
                    break;
                case 0x09:  // TextPacket 闁?forward chat to the 0.14 client
                    forwardText(payload);
                    break;
                case 0x15:  // UpdateBlockPacket 閳?a block changed (our own break/place confirmed,
                            // or someone else's edit); mirror it into the 0.14 world.
                    forwardUpdateBlock(payload);
                    break;
                case 0x0c:  // AddPlayerPacket 閳?someone else joined our view
                    handleAddPlayer(payload);
                    break;
                case 0x0e:  // RemoveEntityPacket 閳?they left
                    handleRemoveEntity(payload);
                    break;
                case 0x6f:  // MoveEntityDeltaPacket 鈥?their movement
                    handleMoveEntityDelta(payload);
                    break;
                case 0xbd:  // DeathInfoPacket 鈥?forward the death message as 0.14 chat
                    handleDeathInfo(payload);
                    break;
                case 0x12:  // MoveEntityAbsolutePacket 閳?how this backend actually broadcasts
                            // player movement to viewers (Entity.broadcastMovement)
                    handleMoveEntityAbsolute(payload);
                    break;
                case 0x13:  // MovePlayerPacket 閳?their teleport / big jump
                    handleModernMovePlayer(payload);
                    break;
                case 0x0f:  // AddItemEntityPacket 閳?a dropped item (mining drops, etc.)
                    handleAddItemEntity(payload);
                    break;
                case 0x11:  // TakeItemEntityPacket 閳?someone picked a drop up
                    handleTakeItemEntity(payload);
                    break;
                case 0x31:  // InventoryContentPacket 閳?the player's inventory contents
                    handleInventoryContent(payload);
                    break;
                case 0x3f:  // PlayerListPacket 閳?carries everyone's real skin
                    handleModernPlayerList(payload);
                    break;
                case 0x3e:  // SetPlayerGameTypePacket 閳?/gamemode n from the backend
                    handleSetGameType(payload);
                    break;
                case 0x27:  // SetEntityDataPacket — carries our own health as DATA_HEALTH metadata
                    handleSetEntityData(payload);
                    break;
                case 0x2a:  // SetHealthPacket: health 0 shows the old client's death screen
                    handleSetHealth(payload);
                    break;
                case 0x2d:  // RespawnPacket: the backend put us back in the world
                    handleBackendRespawn(payload);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            System.out.println("[proxy] translate 0x" + Integer.toHexString(packetId) + " failed: " + e);
        }
    }

    /**
     * modern UpdateBlockPacket (0x15) -> 0.14 UpdateBlock (0x9f).
     * Body for protocol 2193: [svarint x][svarint y][svarint z][uvarint runtimeId][uvarint flags][uvarint layer].
     */    private void forwardUpdateBlock(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(payload);
            int x = readSVarIntBB(bb);
            int y = readSVarIntBB(bb);
            int z = readSVarIntBB(bb);
            int runtimeId = readUVarIntBB(bb);
            // UpdateBlock carries the HASHED block id (same space as the chunk palettes), not the
            // runtime id 閳?air is -604749536, and looking it up in the runtime table turned every
            // destroyed block into stone.
            int[] legacy = ChunkConverter.hashToLegacy(runtimeId);
            StringBuilder hx = new StringBuilder();
            for (int i = 0; i < Math.min(payload.length, 12); i++) hx.append(String.format("%02x ", payload[i]));
            System.out.println("[proxy] updateBlock raw=" + hx.toString().trim()
                    + " -> runtime=" + runtimeId + " legacy " + legacy[0] + ":" + legacy[1]
                    + " at " + x + "," + y + "," + z);
            sendLegacy(LegacyPackets.updateBlock(x, z, y, legacy[0], legacy[1], 0));
        } catch (Exception e) {
            System.out.println("[proxy] update block translate failed: " + e);
        }
    }

    // ------------------------------------------------------------------
    // Other players: modern AddPlayer / MoveEntityDelta / RemoveEntity -> 0.14
    // ------------------------------------------------------------------

    /**
     * modern AddPlayerPacket (0x0c) -> 0.14 AddPlayer (0x96).
     * Layout for protocol 2193: [uuid 16][string name][uvarint runtimeEid][string platformChatId]
     * [LFloat x][y][z][LFloat speedX][speedY][speedZ][LFloat pitch][LFloat yaw][LFloat headYaw]...
     */
    private void handleAddPlayer(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            byte[] id = new byte[16];
            System.arraycopy(payload, 0, id, 0, 16);
            p[0] = 16;
            String name = Translator.readString(payload, p);
            long eid = Translator.readUVarInt64(payload, p);
            Translator.readString(payload, p);                 // platformChatId
            float x = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float y = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float z = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            p[0] += 12;                                        // speedX/Y/Z
            float pitch = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float yaw = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float headYaw = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            spawnTracked(id, name, eid, x, y, z, yaw, headYaw, pitch);
        } catch (Exception e) {
            System.out.println("[proxy] AddPlayer translate failed: " + e);
        }
    }

    private void spawnTracked(byte[] uuid, String name, long eid, float x, float y, float z,
                              float yaw, float headYaw, float pitch) {
        if (eid == runtimeEntityId || eid == 0) {
            return;                                            // that is us
        }
        TrackedEntity t = entities.get(eid);
        boolean fresh = t == null;
        if (fresh) {
            t = new TrackedEntity(nextLegacyEid.getAndIncrement(), name == null ? "" : name, uuid.clone());
            entities.put(eid, t);
        }
        t.x = x; t.y = y; t.z = z; t.yaw = yaw; t.headYaw = headYaw; t.pitch = pitch;
        if (fresh) {
            final TrackedEntity tracked = t;
            // The backend announces a player the moment it enters our view, which is usually
            // before we have finished sending the 0.14 world. Delivering it then is useless — the
            // old client has no level to put the entity in and silently drops it — so it waits for
            // the bootstrap (see sendLegacyWhenReady). The lambda reads the *current* position, so
            // a player that keeps moving still lands where it actually is.
            sendLegacyWhenReady(() -> {
                if (entities.get(eid) != tracked) {
                    return;                       // it left again while we were still loading
                }
                // 0.14 tracks the EYE position; the modern packet carries the FEET position.
                sendLegacy(LegacyPackets.addPlayer(uuid, tracked.legacyId, tracked.name,
                        tracked.x, tracked.y + EYE_HEIGHT, tracked.z, tracked.yaw, tracked.pitch));
                // Also add a player-list entry, otherwise the pause menu stays empty and the client
                // has no skin to build the model from. "Standard_Steve"/"Standard_Alex" are the only
                // model ids the 0.14.3 client knows; anything else and it drops the entry.
                sendLegacy(LegacyPackets.playerListAdd(uuid, tracked.legacyId, tracked.name,
                        "Standard_Steve", skinFor(uuid)));
                System.out.println("[" + username + "] other player visible: " + tracked.name
                        + " modernEid=" + eid + " legacyEid=" + tracked.legacyId);
            });
        }
    }

    /** modern MoveEntityDeltaPacket (0x6f) -> 0.14 MovePlayer (0x9d). */
    private void handleMoveEntityDelta(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            cn.nukkit.network.protocol.MoveEntityDeltaPacket pk =
                    new cn.nukkit.network.protocol.MoveEntityDeltaPacket();
            pk.protocol = ModernCodec.PROTOCOL;
            pk.setBuffer(payload);
            pk.setOffset(0);
            pk.decode();

            TrackedEntity t = entities.get(pk.eid);
            if (t == null) {
                if (deltaDebug < 40) {
                    deltaDebug++;
                    System.out.println("[" + username + "] MoveEntityDelta untracked eid=" + pk.eid
                            + " tracked=" + entities.keySet());
                }
                return;                                        // not someone we show (or it is us)
            }
            if ((pk.flags & cn.nukkit.network.protocol.MoveEntityDeltaPacket.FLAG_HAS_X) != 0) t.x = pk.x;
            if ((pk.flags & cn.nukkit.network.protocol.MoveEntityDeltaPacket.FLAG_HAS_Y) != 0) t.y = pk.y;
            if ((pk.flags & cn.nukkit.network.protocol.MoveEntityDeltaPacket.FLAG_HAS_Z) != 0) t.z = pk.z;
            // pitch/yaw/headYaw arrive as signed byte deltas scaled by 1.40625
            if ((pk.flags & cn.nukkit.network.protocol.MoveEntityDeltaPacket.FLAG_HAS_PITCH) != 0) t.pitch += (float) pk.pitchDelta;
            if ((pk.flags & cn.nukkit.network.protocol.MoveEntityDeltaPacket.FLAG_HAS_YAW) != 0) t.yaw += (float) pk.yawDelta;
            if ((pk.flags & cn.nukkit.network.protocol.MoveEntityDeltaPacket.FLAG_HAS_HEAD_YAW) != 0) t.headYaw += (float) pk.headYawDelta;
            t.onGround = pk.onGround;
            if (deltaDebug < 40) {
                deltaDebug++;
                System.out.println("[" + username + "] delta -> 0x9d for " + t.name + " (eid " + pk.eid
                        + ") at " + t.x + "," + t.y + "," + t.z);
            }
            sendLegacy(LegacyPackets.movePlayer(t.legacyId, t.x, t.y + EYE_HEIGHT, t.z,
                    t.yaw, t.headYaw, t.pitch, 0, t.onGround));
        } catch (Exception e) {
            System.out.println("[proxy] MoveEntityDelta translate failed: " + e);
        }
    }

    /** modern MovePlayerPacket (0x13) -> 0.14 MovePlayer (0x9d); handles our own teleports too. */
    private void handleModernMovePlayer(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            long eid = Translator.readUVarInt64(payload, p);
            float x = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float y = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float z = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float pitch = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float yaw = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float headYaw = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            int mode = payload.length > p[0] ? payload[p[0]] & 0xff : 0;

            if (eid == runtimeEntityId) {
                // The backend is teleporting US (/tp, respawn correction, ...). Without this the
                // old client keeps standing where it was and only catches up after a relog.
                // Player.sendPosition writes pos.y + getBaseOffset(), i.e. the EYE height, which
                // is exactly what 0.14 MovePlayer expects — no conversion needed.
                lastX = x; lastY = y; lastZ = z;
                System.out.println("[" + username + "] teleport -> 0.14 MovePlayer(0) at "
                        + x + "," + y + "," + z + " mode=" + mode);
                // Always MODE_RESET (1) for our own teleports: the backend sometimes marks them
                // MODE_ROTATION (2), and 0.14 only applies the POSITION for a reset — with mode 2
                // the client just turns and stays where it was.
                sendLegacy(LegacyPackets.movePlayer(this.entityId, x, y, z, yaw, headYaw, pitch,
                        1, true));
                return;
            }

            TrackedEntity t = entities.get(eid);
            if (t == null) {
                if (deltaDebug < 40) {
                    deltaDebug++;
                    System.out.println("[proxy] MovePlayer(0x13) for untracked eid=" + eid
                            + " tracked=" + entities.keySet());
                }
                return;
            }
            t.x = x; t.y = y; t.z = z; t.pitch = pitch; t.yaw = yaw; t.headYaw = headYaw;
            if (deltaDebug < 40) {
                deltaDebug++;
                System.out.println("[proxy] MovePlayer(0x13) -> 0x9d for " + t.name + " at " + x + "," + y + "," + z);
            }
            sendLegacy(LegacyPackets.movePlayer(t.legacyId, x, y + EYE_HEIGHT, z, yaw, headYaw, pitch, 0, true));
        } catch (Exception e) {
            System.out.println("[proxy] MovePlayer translate failed: " + e);
        }
    }

    /**
     * modern PlayerListPacket (0x3f) -> the real skins for the 0.14 PlayerList entries.
     *
     * <p>Entry layout for protocol 2193 (v1_26_40+): [uvarint present][byte type][uuid 16]
     * [varint64 entityUniqueId][string name][string xboxUserId][string platformChatId]
     * [LInt buildPlatform][skin: string id, string playFabId, string resourcePatch,
     * image = LInt width, LInt height, uvarint len + RGBA]...
     *
     * <p>Only the primary image is read, and only if it is a shape the 0.14.3 client can wear
     * (64x32 or 64x64 RGBA); HD/persona skins fall back to the placeholder.
     */
    private void handleModernPlayerList(byte[] payload) {
        try {
            int[] p = new int[]{0};
            int count = Translator.readUVarInt(payload, p);
            for (int i = 0; i < count && i < 64 && p[0] < payload.length; i++) {
                Translator.readUVarInt(payload, p);                 // present flag
                int type = payload[p[0]++] & 0xff;
                byte[] uuid = new byte[16];
                System.arraycopy(payload, p[0], uuid, 0, 16);
                p[0] += 16;
                if (type != 0) {
                    continue;                                       // REMOVE entry: uuid only
                }
                Translator.readUVarInt64(payload, p);               // entityUniqueId
                String name = Translator.readString(payload, p);
                Translator.readString(payload, p);                  // xboxUserId
                Translator.readString(payload, p);                  // platformChatId
                p[0] += 4;                                          // buildPlatform
                Translator.readString(payload, p);                  // skinId
                Translator.readString(payload, p);                  // playFabId
                Translator.readString(payload, p);                  // skinResourcePatch
                int w = Translator.readIntLE(payload, p[0]); p[0] += 4;
                int h = Translator.readIntLE(payload, p[0]); p[0] += 4;
                int len = Translator.readUVarInt(payload, p);
                if (len < 0 || p[0] + len > payload.length) {
                    break;
                }
                byte[] data = new byte[len];
                System.arraycopy(payload, p[0], data, 0, len);
                p[0] += len;

                boolean legacyShape = (w == 64 && (h == 32 || h == 64) && data.length == w * h * 4);
                if (!legacyShape) {
                    continue;                                       // HD / persona skin: not for 0.14
                }
                String key = uuidKey(uuid);
                if (playerSkins.put(key, data) == null) {
                    System.out.println("[proxy] skin captured for " + name + " (" + w + "x" + h
                            + ", " + data.length + " bytes)");
                }
                // If we already show this player, upgrade their player-list entry to the real skin.
                for (TrackedEntity t : entities.values()) {
                    if (key.equals(uuidKey(t.uuid))) {
                        sendLegacy(LegacyPackets.playerListAdd(t.uuid, t.legacyId, t.name,
                                "Standard_Steve", data));
                        System.out.println("[proxy] real skin applied to " + t.name);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[proxy] PlayerList skin decode failed: " + e);
        }
    }

    /** The 0.14 skin to advertise for a uuid: the real one when known, else a placeholder. */
    private byte[] skinFor(byte[] uuid) {
        byte[] s = playerSkins.get(uuidKey(uuid));
        return s != null ? s : placeholderSkin();
    }

    private static String uuidKey(byte[] uuid) {
        StringBuilder s = new StringBuilder(32);
        for (byte b : uuid) {
            s.append(String.format("%02x", b));
        }
        return s.toString();
    }

    /** Our own skin, or the placeholder if the login did not carry a usable one. */
    private byte[] realSkin() {
        return skin != null && skin.length > 0 ? skin : placeholderSkin();
    }

    /** The 0.14.3 client only accepts the two built-in model ids; anything else breaks the entry. */
    private String modelName() {
        if ("Standard_Alex".equals(skinName) || "Standard_Steve".equals(skinName)) {
            return skinName;
        }
        return "Standard_Steve";
    }

    /**
     * A valid 0.14 skin (64x64 RGBA, solid grey) used until a remote player's real skin is
     * decoded. Without a skin the old client has nothing to build the model from and the player
     * can end up invisible.
     */
    private static byte[] placeholderSkin() {
        if (PLACEHOLDER_SKIN == null) {
            byte[] s = new byte[64 * 64 * 4];
            for (int i = 0; i < s.length; i += 4) {
                s[i] = (byte) 0xB4;
                s[i + 1] = (byte) 0xB4;
                s[i + 2] = (byte) 0xC8;
                s[i + 3] = (byte) 0xFF;
            }
            PLACEHOLDER_SKIN = s;
        }
        return PLACEHOLDER_SKIN;
    }

    private static volatile byte[] PLACEHOLDER_SKIN;

    /** modern RemoveEntityPacket (0x0e) -> 0.14 RemoveEntity (0x99).
     *  The eid here is the entity UNIQUE id (zigzag varLong), not the runtime id. */
    private void handleRemoveEntity(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            long eid = readZigZagVarLong(payload, p);
            TrackedEntity t = entities.remove(eid);
            if (t != null) {
                sendLegacy(LegacyPackets.removeEntity(t.legacyId));
                System.out.println("[proxy] other player gone: " + t.name);
                return;
            }
            // dropped items share the same remove packet
            Long itemLegacy = itemEntities.remove(eid);
            System.out.println("[proxy] RemoveEntity unique=" + eid + " item=" + (itemLegacy != null));
            if (itemLegacy != null) {
                sendLegacy(LegacyPackets.removeEntity(itemLegacy));
            } else {
                // not (yet) shown to us: remember it so a late AddItemEntity does not create a ghost
                removedBeforeShown.put(eid, System.currentTimeMillis());
            }
        } catch (Exception e) {
            System.out.println("[proxy] RemoveEntity translate failed: " + e);
        }
    }

    /** Reads a zigzag varLong (Nukkit's getEntityUniqueId / putEntityUniqueId). */
    private static long readZigZagVarLong(byte[] d, int[] p) {
        long raw = Translator.readUVarInt64(d, p);
        return (raw >>> 1) ^ -(raw & 1);
    }

    /** Forgets removal records older than 60s so the map cannot grow forever. */
    private void pruneRemoved() {
        if (removedBeforeShown.size() > 256) {
            long cut = System.currentTimeMillis() - 60_000L;
            removedBeforeShown.entrySet().removeIf(e -> e.getValue() < cut);
        }
    }

    /**
     * modern SetPlayerGameTypePacket (0x3e) -> 0.14 SetPlayerGametype (0xc2).
     * This is what makes "/gamemode 1" actually change the old client's mode; without it the
     * backend switches but the client keeps showing survival.
     */
    private void handleSetGameType(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            int raw = Translator.readUVarInt(payload, p);
            int mode = (raw >>> 1) ^ -(raw & 1);        // zigzag varint
            if (mode < 0 || mode > 2) {
                return;
            }
            gamemode = mode;
            sendLegacy(LegacyPackets.setPlayerGametype(mode));
            // Adventure settings carry the build permissions; refresh them for the new mode.
            sendAdventureSettings();
            // Switching INTO creative needs the creative item list, otherwise the inventory is
            // empty and nothing can be picked to place.
            if (mode == GAMEMODE_CREATIVE) {
                sendLegacy(LegacyPackets.containerSetContent(0x79, buildCreativeItems()));
            }
            System.out.println("[proxy] gamemode changed to " + mode);
        } catch (Exception e) {
            System.out.println("[proxy] SetGameType translate failed: " + e);
        }
    }

    /**
     * modern MoveEntityAbsolutePacket (0x12) -> 0.14 MovePlayer (0x9d).
     *
     * <p>This is the packet that actually carries other players' movement: Nukkit's
     * {@code Entity.broadcastMovement()} sends MoveEntityAbsolute (not MovePlayer/MoveEntityDelta)
     * to a player's viewers, and it already writes the EYE height
     * ({@code pk.y = this.y + this.getBaseOffset()}), exactly like 0.14 does.
     *
     * <p>Layout for protocol 2193: [uvarint64 eid][byte flags][LFloat x][y][z]
     * [byte pitch][byte yaw][byte headYaw] with angles as byte * 360/256.
     */
    private void handleMoveEntityAbsolute(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            long eid = Translator.readUVarInt64(payload, p);
            TrackedEntity t = entities.get(eid);
            if (t == null) {
                return;
            }
            p[0]++;                                        // flags
            float x = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float y = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float z = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float pitch = rotationByte(payload[p[0]++]);
            float yaw = rotationByte(payload[p[0]++]);
            float headYaw = rotationByte(payload[p[0]++]);
            t.x = x; t.y = y; t.z = z; t.yaw = yaw; t.headYaw = headYaw; t.pitch = pitch;
            if (deltaDebug < 40) {
                deltaDebug++;
                System.out.println("[" + username + "] MoveEntityAbsolute -> 0x9d for " + t.name
                        + " at " + x + "," + y + "," + z);
            }
            sendLegacy(LegacyPackets.movePlayer(t.legacyId, x, y, z, yaw, headYaw, pitch, 0, true));
        } catch (Exception e) {
            System.out.println("[proxy] MoveEntityAbsolute translate failed: " + e);
        }
    }

    /** Nukkit's getRotationByte(): signed byte scaled to degrees. */
    private static float rotationByte(byte b) {
        return b * (360f / 256f);
    }

    /**
     * modern AddItemEntityPacket (0x0f) -> 0.14 AddItemEntity (0x9a).
     *
     * <p>Without this, mining a block removes it on the backend but the old client never sees the
     * drop ("楠炶埖鐥呴張澶嬪竴閽€鐣屽⒖"). The item descriptor is decoded with the backend's own
     * {@code BinaryStream.getSlot}, which returns an Item already carrying the legacy id.
     *
     * <p>Layout for 2193: [varint64 entityUniqueId][uvarint64 runtimeId][slot][LFloat x][y][z]...
     */
    private void handleAddItemEntity(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            long uniqueId = readZigZagVarLong(payload, p);      // entityUniqueId (zigzag)
            long eid = Translator.readUVarInt64(payload, p);    // runtime id
            // Was this entity already removed before we could show it? Then never show it.
            pruneRemoved();
            if (removedBeforeShown.containsKey(uniqueId)) {
                System.out.println("[proxy] drop already picked up before we could show it ("
                        + uniqueId + ") - skipping");
                return;
            }
            cn.nukkit.utils.BinaryStream bs = new cn.nukkit.utils.BinaryStream();
            bs.setBuffer(payload);
            bs.setOffset(p[0]);
            cn.nukkit.item.Item item = bs.getSlot(cn.nukkit.GameVersion.getLastVersion());
            int off = bs.getOffset();
            float x = Translator.readFloatLE(payload, off);
            float y = Translator.readFloatLE(payload, off + 4);
            float z = Translator.readFloatLE(payload, off + 8);
            if (item == null || item.isNull()) {
                return;
            }
            int legacyId = item.getId();
            if (!LegacyIds.isLegacyAny(legacyId)) {
                return;                                     // item the 0.14 client cannot show
            }
            long legacy = nextLegacyEid.getAndIncrement();
            itemEntities.put(uniqueId, legacy);
            itemRuntimeToUnique.put(eid, uniqueId);
            System.out.println("[proxy] drop visible: " + item + " -> legacy " + legacyId
                    + " unique=" + uniqueId + " runtime=" + eid + " at " + x + "," + y + "," + z);
            sendLegacy(LegacyPackets.addItemEntity(legacy, legacyId, item.getDamage(),
                    item.getCount(), x, y, z));
        } catch (Exception e) {
            System.out.println("[proxy] AddItemEntity translate failed: " + e);
        }
    }

    /** modern TakeItemEntityPacket (0x11) -> 0.14 TakeItemEntity (0x9b). */
    private void handleTakeItemEntity(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            // Wire order is [target][entityId] and Nukkit fills them as target = the item,
            // entityId = the collector (Player.pickupEntity), so the ITEM comes first.
            long itemEid = Translator.readUVarInt64(payload, p);
            long collectorEid = Translator.readUVarInt64(payload, p);
            Long unique = itemRuntimeToUnique.remove(itemEid);
            Long legacyItem = unique == null ? null : itemEntities.remove(unique);
            System.out.println("[proxy] TakeItemEntity item=" + itemEid + " collector=" + collectorEid
                    + " known=" + (legacyItem != null));
            if (legacyItem == null) {
                return;
            }
            long collectorLegacy;
            if (collectorEid == runtimeEntityId) {
                collectorLegacy = this.entityId;
            } else {
                TrackedEntity t = entities.get(collectorEid);
                collectorLegacy = t != null ? t.legacyId : this.entityId;
            }
            sendLegacy(LegacyPackets.takeItemEntity(legacyItem, collectorLegacy));
            // The pickup packet only plays the animation: the 0.14.3 server follows it with
            // entity.kill(), which despawns the drop and sends RemoveEntity. Without this the
            // item stays on the ground forever, because the backend's own RemoveEntityPacket
            // arrives later, finds the mapping already consumed here and is dropped as "not yet
            // shown to us".
            sendLegacy(LegacyPackets.removeEntity(legacyItem));
        } catch (Exception e) {
            System.out.println("[proxy] TakeItemEntity translate failed: " + e);
        }
    }

    /**
     * modern InventoryContentPacket (0x31) -> 0.14 ContainerSetContent (0xb9) for the player's own
     * inventory (window 0). This is what makes picked-up items actually appear in the backpack.
     *
     * <p>Layout for 2193: [varuint windowId][varuint count][item descriptor x count]
     * [byte containerName][optional dynamicId][storage item].
     */
    private void handleInventoryContent(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            int windowId = Translator.readUVarInt(payload, p);
            int count = Translator.readUVarInt(payload, p);
            if (windowId != 0 || count <= 0 || count > 128) {
                return;                                    // only the player inventory for now
            }
            // In creative the old client runs its own creative inventory (window 0x79); pushing
            // the backend's (empty) window-0 contents over it wipes the hotbar the player picked.
            // The cache must still be updated first: the hotbar sync needs to know what the
            // backend currently has in each slot or its transaction is rejected.
            cn.nukkit.utils.BinaryStream bs = new cn.nukkit.utils.BinaryStream();
            bs.setBuffer(payload);
            bs.setOffset(p[0]);
            int[][] items = new int[count][];
            for (int i = 0; i < count; i++) {
                cn.nukkit.item.Item it = bs.getSlot(cn.nukkit.GameVersion.getLastVersion());
                int id = (it == null || it.isNull()) ? 0 : it.getId();
                int damage = (it == null || it.isNull()) ? 0 : it.getDamage();
                int n = (it == null || it.isNull()) ? 0 : it.getCount();
                if (id < 0 || !LegacyIds.isLegacyAny(id)) {
                    id = 0;                                // drop items the 0.14 client cannot show
                    damage = 0;
                    n = 0;
                }
                items[i] = new int[]{id, damage, n};
            }
            invCache = items;
            if (gamemode != GAMEMODE_CREATIVE) {
                sendLegacy(LegacyPackets.containerSetContent(windowId, items));
            }
            invCache = items;
            StringBuilder inv = new StringBuilder();
            for (int i = 0; i < Math.min(items.length, 9); i++) {
                inv.append(items[i][0]).append(':').append(items[i][2]).append(' ');
            }
            System.out.println("[proxy] inventory synced: " + count + " slots, hotbar=[" + inv.toString().trim() + "]");
        } catch (Exception e) {
            System.out.println("[proxy] InventoryContent translate failed: " + e);
        }
    }

    /**
     * Publishes this player to every other 0.14 client in the proxy and shows them to us.
     * Called once the old client is standing in the world.
     */
    private void relaySpawn() {
        if (relayRegistered) {
            return;
        }
        relayRegistered = true;
        relayEid = RELAY_EID.getAndIncrement();
        SESSIONS.put(username, this);
        System.out.println("[" + username + "] relay registered eid=" + relayEid
                + " sessions=" + SESSIONS.keySet());
        // Pair a moment later: AddPlayer arriving while the old client is still on its loading
        // screen (right after PLAYER_SPAWN) is dropped, because the level does not exist yet.
        Thread pair = new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            relayPair();
        }, "relay-pair-" + username);
        pair.setDaemon(true);
        pair.start();
    }

    /** Shows every other live 0.14 session to us, and us to them. */
    private void relayPair() {
        for (ProxyClientSession other : SESSIONS.values()) {
            if (other == this || !other.relayRegistered || other.relayEid == 0) {
                continue;                              // not in the world yet
            }
            try {
                // show them to us, with their real skin (we parsed it from their own login)
                sendLegacy(LegacyPackets.addPlayer(other.uuid, other.relayEid, other.username,
                        other.lastX, other.lastY, other.lastZ, other.relayYaw, other.relayPitch));
                sendLegacy(LegacyPackets.playerListAdd(other.uuid, other.relayEid, other.username,
                        other.modelName(), other.realSkin()));
                // and show us to them
                other.sendLegacy(LegacyPackets.addPlayer(uuid, relayEid, username,
                        lastX, lastY, lastZ, relayYaw, relayPitch));
                other.sendLegacy(LegacyPackets.playerListAdd(uuid, relayEid, username,
                        modelName(), realSkin()));
                System.out.println("[" + username + "] relay: paired with " + other.username);
            } catch (Throwable t) {
                System.out.println("[" + username + "] relay spawn failed: " + t);
            }
        }
    }

    /** Relays our 0.14 movement to the other 0.14 clients (throttled). */
    private void relayMove() {
        if (!relayRegistered || relayEid == 0 || SESSIONS.size() < 2) {
            return;                                   // nothing to relay to yet
        }
        long now = System.currentTimeMillis();
        if (now - lastRelayMove < 45) {
            return;                                   // ~22 updates/s is plenty for the old client
        }
        lastRelayMove = now;
        for (ProxyClientSession other : SESSIONS.values()) {
            if (other != this && other.relayRegistered) {
                other.sendLegacy(LegacyPackets.movePlayer(relayEid, lastX, lastY, lastZ,
                        relayYaw, relayHeadYaw, relayPitch, 0, true));
            }
        }
    }

    /** Tells the other 0.14 clients that we are gone. */
    private void relayDespawn() {
        SESSIONS.remove(username, this);
        if (!relayRegistered) {
            return;
        }
        relayRegistered = false;
        for (ProxyClientSession other : SESSIONS.values()) {
            other.sendLegacy(LegacyPackets.removeEntity(relayEid));
            other.sendLegacy(LegacyPackets.playerListRemove(uuid));
        }
    }

    /**
     * modern SetEntityDataPacket (0x27): entity metadata.
     *
     * <p>The backend reports the player's own health this way when they take damage —
     * {@code SetHealthPacket} is only sent on respawn — so without reading DATA_HEALTH out of it
     * the 0.14 health bar never moves and the player simply drops dead with a full bar.
     *
     * <p>Layout: {@code [zigzag varint uniqueId][uvarint runtimeEid][uvarint count]} then per entry
     * {@code [uvarint key][uvarint type][value]}. The unique id comes first and is easy to miss —
     * skipping it wrongly shifts every metadata entry.
     */
    private void handleSetEntityData(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            long uniqueId = readZigZagVarLong(payload, p);
            long eid = Translator.readUVarInt64(payload, p);
            if (eid != runtimeEntityId && uniqueId != runtimeEntityId) {
                // Someone else's metadata. Logged at a low rate because the backend sends a lot of
                // it and the id is the only way to tell whether our own health ever arrives.
                if (System.currentTimeMillis() - lastMetadataLog > 5000) {
                    lastMetadataLog = System.currentTimeMillis();
                    System.out.println("[" + username + "] metadata for eid=" + eid
                            + " unique=" + uniqueId + " (ours=" + runtimeEntityId + ")");
                }
                return;
            }
            int count = (int) Translator.readUVarInt64(payload, p);
            StringBuilder keys = new StringBuilder();
            for (int i = 0; i < count && p[0] < payload.length; i++) {
                long key = Translator.readUVarInt64(payload, p);
                int type = (int) Translator.readUVarInt64(payload, p);
                keys.append(key).append(':').append(type).append(' ');
                if (key == 1 && (type == 2 || type == 3)) {   // DATA_HEALTH as int or float
                    int health;
                    if (type == 2) {
                        long raw = Translator.readUVarInt64(payload, p);
                        health = (int) ((raw >>> 1) ^ -(raw & 1));
                    } else {
                        health = (int) Translator.readFloatLE(payload, p[0]);
                        p[0] += 4;
                    }
                    health = Math.max(0, Math.min(20, health));
                    System.out.println("[" + username + "] health -> " + health
                            + " (metadata: " + keys.toString().trim() + ")");
                    sendLegacy(LegacyPackets.setHealth(health));
                    return;
                }
                skipMetadataValue(payload, p, type);
            }
            System.out.println("[" + username + "] own metadata without DATA_HEALTH: "
                    + keys.toString().trim());
        } catch (Exception e) {
            System.out.println("[proxy] SetEntityData translate failed: " + e);
        }
    }

    /** Advances past one metadata value of the given type. */
    private static void skipMetadataValue(byte[] d, int[] p, int type) {
        switch (type) {
            case 0: p[0] += 1; break;                       // byte
            case 1: p[0] += 2; break;                       // short
            case 2: Translator.readUVarInt64(d, p); break;  // int (zigzag varint)
            case 3: p[0] += 4; break;                       // float
            case 4: Translator.readString(d, p); break;     // string
            case 5: {                                       // NBT: short length + bytes
                int len = (d[p[0]] & 0xff) | ((d[p[0] + 1] & 0xff) << 8);
                p[0] += 2 + len;
                break;
            }
            case 6: p[0] += 12; break;                      // vec3i
            case 7: Translator.readUVarInt64(d, p); break;  // long
            case 8: p[0] += 12; break;                      // vec3f
            default: throw new IllegalArgumentException("unknown metadata type " + type);
        }
    }

    /** modern SetHealthPacket (0x2a) -> 0.14 SetHealth (0xb0). Health 0 = death screen. */
    private void handleSetHealth(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            long raw = Translator.readUVarInt(payload, p);
            int health = (int) ((raw >>> 1) ^ -(raw & 1));      // zigzag varint
            sendLegacy(LegacyPackets.setHealth(Math.max(0, Math.min(20, health))));
        } catch (Exception e) {
            System.out.println("[proxy] SetHealth translate failed: " + e);
        }
    }

    /** modern RespawnPacket (0x2d) -> 0.14 Respawn (0xb3). */
    private void handleBackendRespawn(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            float x = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float y = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            float z = Translator.readFloatLE(payload, p[0]); p[0] += 4;
            int state = payload.length > p[0] ? payload[p[0]] & 0xff : 1;
            if (state == 0) {
                // STATE_SEARCHING_FOR_SPAWN 鈥?this is Nukkit's DEATH signal (Player.java sends it
                // from the death path). The 0.14 client opens its death screen when its health
                // reaches 0, and the backend never sends SetHealth(0) itself.
                sendLegacy(LegacyPackets.setHealth(0));
                sendLegacy(LegacyPackets.respawn(x, y + EYE_HEIGHT, z));
                System.out.println("[" + username + "] death signal -> 0.14 SetHealth(0)");
                return;
            }
            if (state != 1) {
                return;                                        // only READY_TO_SPAWN matters
            }
            // modern packets carry the feet position; 0.14 wants the eye position
            sendLegacy(LegacyPackets.respawn(x, y + EYE_HEIGHT, z));
            sendLegacy(LegacyPackets.setHealth(20));
            System.out.println("[" + username + "] respawned at " + x + "," + y + "," + z);
        } catch (Exception e) {
            System.out.println("[proxy] Respawn translate failed: " + e);
        }
    }

    /**
     * modern DeathInfoPacket (0xbd) -> a 0.14 chat line.
     * Layout: [string messageTranslationKey][uvarint parameterCount][string ...].
     */
    private void handleDeathInfo(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            int[] p = new int[]{0};
            String key = Translator.readString(payload, p);
            int n = Translator.readUVarInt(payload, p);
            StringBuilder msg = new StringBuilder(key == null ? "" : key);
            for (int i = 0; i < n && i < 16 && p[0] < payload.length; i++) {
                String param = Translator.readString(payload, p);
                if (param != null && !param.isEmpty()) {
                    msg.append(' ').append(param);
                }
            }
            String text = msg.toString().trim();
            if (!text.isEmpty()) {
                System.out.println("[" + username + "] death message: " + text);
                sendLegacy(LegacyPackets.text(1, "", text));
            }
        } catch (Exception e) {
            System.out.println("[proxy] DeathInfo translate failed: " + e);
        }
    }

    /**
     * 0.14 AdventureSettings flags (Nukkit-0143 Player.sendSettings):
     * 0x01 = adventure (no build), 0x40 = auto-jump, 0x80 = <b>ALLOW_FLIGHT</b>, 0x100 = spectator.
     *
     * <p>The old client only offers flight (double-tap jump) when ALLOW_FLIGHT is set 鈥?with
     * flags 0 a creative player is stuck on the ground.
     */
    private int adventureFlags() {
        if (gamemode == GAMEMODE_CREATIVE) {
            return 0x80 | 0x40;      // allow flight + auto jump
        }
        if (gamemode == 2) {
            return 0x01;             // adventure: no placing/breaking
        }
        return 0;                    // survival
    }

    private void sendAdventureSettings() {
        int flags = adventureFlags();
        sendLegacy(LegacyPackets.adventureSettings(flags, 2, 2));
        System.out.println("[" + username + "] adventure settings flags=0x"
                + Integer.toHexString(flags) + " (gamemode " + gamemode + ")");
    }

    private void forwardText(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        String[] t = Translator.fromModernText(payload);
        int type;
        try { type = Integer.parseInt(t[0]); } catch (NumberFormatException e) { type = 0; }
        // map modern types onto the 0.14 ones (both use RAW=0, CHAT=1, TIP=5...)
        // Queued until the old client is actually in the world: text delivered while it is still
        // on the loading screen is dropped, which is why chat could be sent but not received.
        final int textType = type;
        final String source = t[1];
        final String message = t[2];
        sendLegacyWhenReady(() -> sendLegacy(LegacyPackets.text(textType, source, message)));
    }

    /** Reads entityId/gamemode/position out of the modern StartGamePacket. */
    private void parseStartGame(byte[] p) {
        try {
            StringBuilder hx = new StringBuilder();
            for (int i = 0; i < Math.min(p.length, 20); i++) hx.append(String.format("%02x ", p[i]));
            System.out.println("[proxy] StartGame header: " + hx.toString().trim());
            int pos = 0;
            pos = skipVarInt64(p, pos);          // entityId (zigzag varint64)
            // runtimeEntityId: every client->server packet must quote this back.
            int rStart = pos;
            long rt = readVarInt(p, pos);
            pos += varIntLen(p, rStart);
            if (rt > 0) runtimeEntityId = rt;
            long gmRaw = readVarInt(p, pos); pos += varIntLen(p, pos);
            // playerGamemode is written with putVarInt, which is a ZIGZAG varint in Nukkit
            // (VarInt.writeVarInt -> encodeZigZag32). Reading it as unsigned turned creative (1,
            // wire 2) into adventure (2) and made the old client show the wrong mode.
            int gm = (int) ((gmRaw >>> 1) ^ -(gmRaw & 1));
            if (gm >= 0 && gm <= 3) {
                gamemode = gm;
            }
            float x = readFloatLE(p, pos); pos += 4;
            float y = readFloatLE(p, pos); pos += 4;
            float z = readFloatLE(p, pos); pos += 4;
            spawnX = x; spawnY = y; spawnZ = z;
            lastX = x; lastY = y; lastZ = z;
            System.out.println("[proxy] backend StartGame: runtimeEid=" + runtimeEntityId
                    + " gamemode=" + gamemode + " spawn=" + x + "," + y + "," + z);
        } catch (Exception e) {
            System.out.println("[proxy] StartGame parse failed (using defaults): " + e);
        }
    }

    /** Converts a modern LevelChunk payload into a 0.14 FullChunkData packet. */
    private void forwardChunk(byte[] payload) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int chunkX = readSVarIntBB(bb);
            int chunkZ = readSVarIntBB(bb);
            // skip the rest of the header to reach the data section
            int dimension = readSVarIntBB(bb);
            int subChunkCount = readUVarIntBB(bb);
            bb.get();  // requestSubChunks
            bb.get();  // cacheEnabled
            readUVarIntBB(bb);   // blobIds count
            int dataLen = readUVarIntBB(bb);
            byte[] data = new byte[dataLen];
            bb.get(data);
            byte[] legacyData = ChunkConverter.convertChunk(chunkX, chunkZ, subChunkCount, data);
            long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
            if (!sentChunks.add(key)) return;
            sendLegacyBatched(LegacyPackets.fullChunkData(chunkX, chunkZ, 0, legacyData));
            // Once a few chunks are in, finish the spawn the way the 0.14.3 server does:
            // Respawn(position) then PlayStatus(PLAYER_SPAWN). Sending PLAYER_SPAWN before any
            // chunk existed left the client on the loading screen.
            if (!spawned && sentChunks.size() >= 3) {
                spawned = true;
                float eyeY = spawnY + 1.62f;
                sendLegacy(LegacyPackets.respawn(spawnX, eyeY, spawnZ));
                sendLegacy(LegacyPackets.playStatus(3));
                // Only now does the old client actually have a level: everything that arrived
                // while it was loading (other players, chat) was queued and can be delivered.
                worldReady = true;
                flushDeferredLegacy();
                // Now that the old client is standing in the world, make sure the backend has been
                // told we are locally initialised (it usually already has — see
                // markLocallyInitialized — but this is the path that guarantees it).
                markLocallyInitialized();
                System.out.println("[proxy] 0.14 client " + username + " spawned after "
                        + sentChunks.size() + " chunks");
                relaySpawn();
            }
            if (sentChunks.size() % 10 == 0)
                System.out.println("[proxy] sent " + sentChunks.size() + " chunks to 0.14 client");
        } catch (Exception e) {
            System.out.println("[proxy] chunk convert failed: " + e);
        }
    }

    // ---- small readers ----
    private static int readSVarIntBB(java.nio.ByteBuffer bb) { int v = readUVarIntBB(bb); return (v >>> 1) ^ -(v & 1); }
    private static int readUVarIntBB(java.nio.ByteBuffer bb) {
        int v = 0, s = 0;
        while (true) { int b = bb.get() & 0xff; v |= (b & 0x7f) << s; if ((b & 0x80) == 0) break; s += 7; }
        return v;
    }
    private static int varIntLen(byte[] d, int p) { int s = p; while ((d[p++] & 0x80) != 0) {} return p - s; }
    private static long readVarInt(byte[] d, int p) { long v = 0; int s = 0; while (true) { int b = d[p++] & 0xff; v |= (long) (b & 0x7f) << s; if ((b & 0x80) == 0) break; s += 7; } return v; }
    private static int skipVarInt(byte[] d, int p) { return p + varIntLen(d, p); }
    private static int skipVarInt64(byte[] d, int p) { return p + varIntLen(d, p); }
    private static float readFloatLE(byte[] d, int p) { return Float.intBitsToFloat((d[p] & 0xff) | ((d[p+1] & 0xff) << 8) | ((d[p+2] & 0xff) << 16) | ((d[p+3] & 0xff) << 24)); }

    /** 0.14 game packets must be wrapped in the 0x8e marker; the client ignores bare ones. */
    private void sendLegacy(byte[] packet) {
        if (legacySession == null || legacySession.isClosed()) return;
        byte[] framed = new byte[packet.length + 1];
        framed[0] = (byte) 0x8e;
        System.arraycopy(packet, 0, framed, 1, packet.length);
        legacySession.sendGameData(framed);
    }

    /**
     * Sends one game packet inside a 0x92 batch: [0x8e][0x92][int compressedLen][zlib].
     * The 0.14.3 server sends level chunks this way; a raw 83 KB chunk has to be split into
     * ~60 datagrams, which a real client cannot keep up with.
     */
    private void sendLegacyBatched(byte[] packet) {
        if (legacySession == null || legacySession.isClosed()) return;
        try {
            java.io.ByteArrayOutputStream inner = new java.io.ByteArrayOutputStream(packet.length + 4);
            inner.write((packet.length >>> 24) & 0xff);
            inner.write((packet.length >>> 16) & 0xff);
            inner.write((packet.length >>> 8) & 0xff);
            inner.write(packet.length & 0xff);
            inner.write(packet);
            byte[] raw = inner.toByteArray();
            java.util.zip.Deflater d = new java.util.zip.Deflater(7);
            d.setInput(raw);
            d.finish();
            byte[] comp = new byte[raw.length + 64];
            int n = d.deflate(comp);
            d.end();
            byte[] framed = new byte[6 + n];
            framed[0] = (byte) 0x8e;   // encapsulation marker
            framed[1] = (byte) 0x92;   // BATCH packet
            framed[2] = (byte) ((n >>> 24) & 0xff);
            framed[3] = (byte) ((n >>> 16) & 0xff);
            framed[4] = (byte) ((n >>> 8) & 0xff);
            framed[5] = (byte) (n & 0xff);
            System.arraycopy(comp, 0, framed, 6, n);
            legacySession.sendGameData(framed);
        } catch (Exception e) {
            System.out.println("[proxy] batch encode failed: " + e);
        }
    }

    /**
     * Creative-mode contents: the backend's own creative registry, filtered down to the ids the
     * 0.14.3 client knows.
     *
     * <p>Using the server's list matters because it carries the real damage values (wool colours,
     * wood types, wall variants...). The old synthesised list had every entry at meta 0, which
     * made the creative inventory a mess of duplicates and invalid ids.
     */
    private static int[][] buildCreativeItems() {
        java.util.List<int[]> items = new java.util.ArrayList<>();
        try {
            for (cn.nukkit.item.Item it : cn.nukkit.item.Item.getCreativeItems()) {
                if (it == null || it.isNull()) {
                    continue;
                }
                int id = it.getId();
                if (!LegacyIds.isLegacyAny(id)) {
                    continue;                              // item the 0.14 client does not know
                }
                items.add(new int[]{id, it.getDamage(), Math.max(1, it.getCount())});
                if (items.size() >= MAX_CREATIVE_ITEMS) {
                    break;
                }
            }
        } catch (Throwable t) {
            System.out.println("[proxy] creative list failed, falling back to id ranges: " + t);
        }
        if (items.isEmpty()) {
            for (int id = 1; id <= 246; id++) {            // fallback: block id range
                if (LegacyIds.isLegacyBlock(id)) {
                    items.add(new int[]{id, 0, 64});
                }
            }
            for (int id = 256; id <= 466; id++) {          // fallback: item id range
                if (LegacyIds.isLegacyItem(id)) {
                    items.add(new int[]{id, 0, 1});
                }
            }
        }
        System.out.println("[proxy] creative list: " + items.size() + " entries");
        return items.toArray(new int[0][]);
    }

    /** The old client paginates the creative menu; keep the list bounded. */
    private static final int MAX_CREATIVE_ITEMS = 600;

    private void quiet(ThrowingRunnable r) {
        try { r.run(); } catch (Exception e) { System.out.println("[proxy] " + e); }
    }
    interface ThrowingRunnable { void run() throws Exception; }

    /** 0.14 login payload: [0x8e][0x8f][u16 len][username][int proto1][int proto2]... */
    private static String parseLegacyLoginName(byte[] buf) {
        try {
            int off = (buf[0] & 0xff) == 0x8e ? 1 : 0;   // strip the game marker
            off++;                                       // LOGIN packet id
            if (off + 2 > buf.length) return "Player";
            int len = ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
            off += 2;
            if (len <= 0 || len > 32 || off + len > buf.length) return "Player";
            String s = new String(buf, off, len, java.nio.charset.StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? "Player" : s;
        } catch (Exception e) {
            return "Player";
        }
    }

    @Override
    public void onDisconnect(LegacySession session, String reason) {
        relayDespawn();
        // Close the backend session politely, otherwise it keeps the player in the world until
        // its own timeout and modern players see a ghost.
        if (modernClient != null) { modernClient.closeGracefully(); modernClient = null; }
    }
}

