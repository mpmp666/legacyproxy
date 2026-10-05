package proxy.legacy;

/**
 * Encoders for MCPE 0.14.x outbound game packets. Each method returns a complete
 * [pid][payload] buffer (no encapsulation marker; the batch layer handles framing).
 */
public final class LegacyPackets {

    private LegacyPackets() {
    }

    public static byte[] playStatus(int status) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.PLAY_STATUS);
        w.putInt(status);
        return w.toByteArray();
    }

    public static byte[] startGame(int seed, int dimension, int generator, int gamemode,
                                   long eid, int spawnX, int spawnY, int spawnZ,
                                   float x, float y, float z) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.START_GAME);
        w.putInt(seed);
        w.putByte(dimension);
        w.putInt(generator);
        w.putInt(gamemode);
        w.putLong(eid);
        w.putInt(spawnX);
        w.putInt(spawnY);
        w.putInt(spawnZ);
        w.putFloat(x);
        w.putFloat(y);
        w.putFloat(z);
        w.putByte(1);
        w.putByte(1);
        w.putByte(0);
        w.putString("");
        return w.toByteArray();
    }

    public static byte[] setTime(int time, boolean started) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.SET_TIME);
        w.putInt(time);
        w.putByte(started ? 1 : 0);
        return w.toByteArray();
    }

    public static byte[] setSpawnPosition(int x, int y, int z) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.SET_SPAWN_POSITION);
        w.putInt(x);
        w.putInt(y);
        w.putInt(z);
        return w.toByteArray();
    }

    public static byte[] setHealth(int health) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.SET_HEALTH);
        w.putInt(health);
        return w.toByteArray();
    }

    public static byte[] setDifficulty(int difficulty) {        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.SET_DIFFICULTY);
        w.putInt(difficulty);
        return w.toByteArray();
    }

    /** 0.14 SetPlayerGameType (0xc2): [int gamemode] — survival 0, creative 1, adventure 2. */
    public static byte[] setPlayerGametype(int gamemode) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.SET_PLAYER_GAMETYPE);
        w.putInt(gamemode);
        return w.toByteArray();
    }

    public static byte[] adventureSettings(int flags, int userPermission, int globalPermission) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.ADVENTURE_SETTINGS);
        w.putInt(flags);
        w.putInt(userPermission);
        w.putInt(globalPermission);
        return w.toByteArray();
    }

    public static byte[] chunkRadiusUpdate(int radius) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.CHUNK_RADIUS_UPDATE);
        w.putInt(radius);
        return w.toByteArray();
    }

    public static byte[] fullChunkData(int chunkX, int chunkZ, int order, byte[] data) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.FULL_CHUNK_DATA);
        w.putInt(chunkX);
        w.putInt(chunkZ);
        w.putByte(order);
        w.putInt(data.length);
        w.putBytes(data);
        return w.toByteArray();
    }

    public static byte[] respawn(float x, float y, float z) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.RESPAWN);
        w.putFloat(x);
        w.putFloat(y);
        w.putFloat(z);
        return w.toByteArray();
    }

    /**
     * 0.14 UpdateAttributes (0xa6). The client expects health/speed attributes right after
     * StartGame; without them it never leaves the "locating server" screen.
     * {@code values[i]} is a [min, max, current] triple for {@code names[i]}.
     */
    public static byte[] updateAttributes(long eid, String[] names, float[][] values) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.UPDATE_ATTRIBUTES);
        w.putLong(eid);
        w.putShort(names.length);
        for (int i = 0; i < names.length; i++) {
            float[] v = values[i];
            w.putFloat(v[0]);
            w.putFloat(v[1]);
            w.putFloat(v[2]);
            w.putString(names[i]);
        }
        return w.toByteArray();
    }

    public static byte[] movePlayer(long eid, float x, float y, float z, float yaw, float bodyYaw, float pitch,
                                    int mode, boolean onGround) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.MOVE_PLAYER);
        w.putLong(eid);
        w.putFloat(x);
        w.putFloat(y);
        w.putFloat(z);
        w.putFloat(yaw);
        w.putFloat(bodyYaw);
        w.putFloat(pitch);
        w.putByte(mode);
        w.putByte(onGround ? 1 : 0);
        return w.toByteArray();
    }

    public static byte[] text(int type, String source, String message) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.TEXT);
        w.putByte(type);
        switch (type) {
            case 0: // raw
            case 4: // tip
            case 5: // system
                w.putString(message);
                break;
            case 1: // chat
            case 3: // popup
                w.putString(source);
                w.putString(message);
                break;
            default:
                w.putString(message);
                break;
        }
        return w.toByteArray();
    }

    public static byte[] updateBlock(int x, int z, int y, int blockId, int blockData, int flags) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.UPDATE_BLOCK);
        w.putInt(1);
        w.putInt(x);
        w.putInt(z);
        w.putByte(y);
        w.putByte(blockId);
        w.putByte((flags << 4) | blockData);
        return w.toByteArray();
    }

    public static byte[] containerSetContent(int windowId) {
        return containerSetContent(windowId, new int[0][]);
    }

    /**
     * 0.14 ContainerSetContent (0xb9). {@code items} is a list of [id, damage, count] triples;
     * an empty array produces an empty inventory window.
     */
    public static byte[] containerSetContent(int windowId, int[][] items) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.CONTAINER_SET_CONTENT);
        w.putByte(windowId);      // 0x79 = creative, 0 = player inventory
        w.putShort(items.length);
        for (int[] it : items) {
            w.putShort(it[0]);          // id
            w.putByte(it.length > 2 ? it[2] : 1); // count
            w.putShort(it.length > 1 ? it[1] : 0); // damage
            w.putLShort(0);             // no nbt
        }
        if (windowId == 0) {
            // The HUD hotbar is a separate array for the player inventory: it mirrors the first
            // nine slots, and without it the old client shows an empty hotbar.
            int hotbar = Math.min(9, items.length);
            w.putShort(hotbar);
            for (int i = 0; i < hotbar; i++) {
                int[] it = items[i];
                w.putShort(it[0]);
                w.putByte(it.length > 2 ? it[2] : 1);
                w.putShort(it.length > 1 ? it[1] : 0);
                w.putLShort(0);
            }
        }
        return w.toByteArray();
    }

    /** 0.14 PlayerList TYPE_REMOVE: [byte 1][int count][uuid ...]. */
    public static byte[] playerListRemove(byte[] uuid) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.PLAYER_LIST);
        w.putByte(1);             // TYPE_REMOVE
        w.putInt(1);
        w.putUUID(uuid);
        return w.toByteArray();
    }

    public static byte[] playerListAdd(byte[] uuid, long eid, String name, String skinName, byte[] skin) {        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.PLAYER_LIST);
        w.putByte(0);             // TYPE_ADD
        w.putInt(1);              // one entry
        w.putUUID(uuid);
        w.putLong(eid);
        w.putString(name);
        w.putString(skinName);
        byte[] skinBytes = skin == null ? new byte[0] : skin;
        w.putShort(skinBytes.length);
        w.putBytes(skinBytes);
        return w.toByteArray();
    }

    /**
     * 0.14 AddPlayer (id 0x96): makes a modern player appear to a legacy client.
     * Layout per the Genisys/MPMPESCore reference: name, eid, pos, speed, yaw, headYaw, pitch,
     * metadata terminator.
     */
    public static byte[] addPlayer(long eid, String name, float x, float y, float z,
                                   float yaw, float pitch) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.ADD_PLAYER);
        w.putString(name == null ? "" : name);
        w.putLong(eid);
        w.putFloat(x);
        w.putFloat(y);
        w.putFloat(z);
        w.putFloat(0f);
        w.putFloat(0f);
        w.putFloat(0f);
        w.putFloat(yaw);
        w.putFloat(yaw);
        w.putFloat(pitch);
        w.putByte(0x7f); // metadata terminator
        return w.toByteArray();
    }

    /** 0.14 RemoveEntity (id 0x99) 鈥?already exists further down. */

    /**
     * 0.14 AddEntity (id 0x98): shows a mob/animal to a legacy client.
     * Layout per the reference: eid, type, pos, speed, yaw, pitch, empty metadata, 0 links.
     */
    public static byte[] addEntity(long eid, int type, float x, float y, float z,
                                   float yaw, float pitch) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(0x98);
        w.putLong(eid);
        w.putInt(type);
        w.putFloat(x);
        w.putFloat(y);
        w.putFloat(z);
        w.putFloat(0f);
        w.putFloat(0f);
        w.putFloat(0f);
        w.putFloat(yaw);
        w.putFloat(pitch);
        w.putByte(0x7f); // empty metadata terminator
        w.putShort(0);   // no links
        return w.toByteArray();
    }

    /**
     * 0.14 AddItemEntity (id 0x9a): shows a dropped item to a legacy client.
     * Layout: eid, slot(item), x,y,z, speedX,speedY,speedZ.
     */
    public static byte[] addItemEntity(long eid, int itemId, int itemDamage,
                                       float x, float y, float z) {
        return addItemEntity(eid, itemId, itemDamage, 1, x, y, z);
    }

    /** Same as above but with an explicit stack size (drops are usually > 1). */
    public static byte[] addItemEntity(long eid, int itemId, int itemDamage, int count,
                                       float x, float y, float z) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(0x9a);
        w.putLong(eid);
        // slot: [short id][byte count][short damage][LShort nbtLen=0]
        w.putShort(itemId);
        w.putByte(Math.max(1, Math.min(64, count)));
        w.putShort(itemDamage);
        w.putLShort(0);
        w.putFloat(x);
        w.putFloat(y);
        w.putFloat(z);
        w.putFloat(0f);
        w.putFloat(0f);
        w.putFloat(0f);
        return w.toByteArray();
    }

    /**
     * 0.14 TakeItemEntity (id 0x9b): [item entity][collector entity].
     * The 0.14.3 server builds it as {@code pk.target = item; pk.entityId = player} and encodes
     * {@code putLong(target); putLong(entityId)} — so the ITEM comes first.
     */
    public static byte[] takeItemEntity(long itemEid, long collectorEid) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(0x9b);
        w.putLong(itemEid);
        w.putLong(collectorEid);
        return w.toByteArray();
    }

    public static byte[] setEntityData(long eid, byte[] metadata) {        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.SET_ENTITY_DATA);
        w.putLong(eid);
        w.putBytes(metadata);
        return w.toByteArray();
    }

    public static byte[] removeEntity(long eid) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.REMOVE_ENTITY);
        w.putLong(eid);
        return w.toByteArray();
    }

    public static byte[] disconnect(String reason) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyGameConstants.DISCONNECT);
        w.putString(reason);
        return w.toByteArray();
    }

    /** Encodes the 0.14 entity metadata dictionary (terminated with 0x7f). */
    public static byte[] writeMetadata(java.util.Map<Integer, Object> data) {
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        // entries are ordered by key; each entry: (type<<5 | key) + value
        java.util.TreeMap<Integer, Object> sorted = new java.util.TreeMap<>(data);
        for (java.util.Map.Entry<Integer, Object> e : sorted.entrySet()) {
            int key = e.getKey();
            Object v = e.getValue();
            if (v instanceof MetadataEntry) {
                MetadataEntry m = (MetadataEntry) v;
                w.putByte((m.type << 5) | (key & 0x1f));
                writeMetadataValue(w, m.type, m.value);
            }
        }
        w.putByte(0x7f);
        return w.toByteArray();
    }

    private static void writeMetadataValue(LegacyBinary.Writer w, int type, Object value) {
        switch (type) {
            case 0: // byte
                w.putByte((Integer) value);
                break;
            case 1: // short (LE)
                w.putLShort((Integer) value);
                break;
            case 2: // int (LE)
                w.putLInt((Integer) value);
                break;
            case 3: // float (LE)
                w.putLFloat((Float) value);
                break;
            case 4: // string (LE short len)
                w.putLShort(((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                w.putBytes(((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                break;
            case 7: // long (LE)
                // 0.14 metadata long is stored little-endian
                long l = (Long) value;
                w.putLInt((int) (l & 0xffffffffL));
                w.putLInt((int) (l >>> 32));
                break;
            default:
                break;
        }
    }

    public static final class MetadataEntry {
        public final int type;
        public final Object value;

        public MetadataEntry(int type, Object value) {
            this.type = type;
            this.value = value;
        }

        public static MetadataEntry of(int type, Object value) {
            return new MetadataEntry(type, value);
        }
    }

    // entity metadata types (0.14)
    public static final int DATA_TYPE_BYTE = 0;
    public static final int DATA_TYPE_SHORT = 1;
    public static final int DATA_TYPE_INT = 2;
    public static final int DATA_TYPE_FLOAT = 3;
    public static final int DATA_TYPE_STRING = 4;
    public static final int DATA_TYPE_LONG = 7;
}
