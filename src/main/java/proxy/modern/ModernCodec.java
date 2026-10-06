package proxy.modern;

import cn.nukkit.inventory.transaction.data.UseItemData;
import cn.nukkit.inventory.transaction.data.UseItemOnEntityData;
import cn.nukkit.item.Item;
import cn.nukkit.math.BlockFace;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.math.Vector3;
import cn.nukkit.math.Vector3f;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.InventoryTransactionPacket;
import cn.nukkit.network.protocol.MobEquipmentPacket;
import cn.nukkit.network.protocol.PlayerActionPacket;
import cn.nukkit.network.protocol.types.NetworkInventoryAction;
import cn.nukkit.network.protocol.SetLocalPlayerAsInitializedPacket;
import cn.nukkit.network.protocol.TextPacket;

/**
 * Builds modern Bedrock (protocol 2193 / 1.26.50) client-&gt;server packets.
 *
 * <p>Instead of hand-rolling the byte layout we instantiate the <b>backend server's own</b>
 * packet classes (the Nukkit-MOT jar is on our classpath) and call {@code encode()}. The wire
 * format is therefore exactly what the backend's decoder expects, for every protocol version,
 * with no duplicated protocol knowledge in the proxy.
 */
public final class ModernCodec {

    /** The backend speaks 1.26.50 = protocol 2193. */
    public static final int PROTOCOL = 2193;

    private ModernCodec() {
    }

    /** Pins the protocol and returns the encoded body (leading packet id included). */
    public static byte[] encode(DataPacket pk) {
        pk.protocol = PROTOCOL;
        pk.encode();
        return pk.getBuffer();
    }

    /**
     * SetLocalPlayerAsInitialized (0x71).
     *
     * <p>This is the packet that makes the backend actually spawn us: it runs
     * {@code PlayerHandle.doFirstSpawn()} which sets {@code locallyInitialized}/{@code spawned},
     * calls {@code spawnToAll()} (player list, visibility to everyone else) and unlocks every
     * interaction handler that checks {@code player.spawned}. Without it the backend keeps us in
     * a half-logged-in limbo: no player list, no chat, no commands, no block break/place.
     */
    public static byte[] setLocalPlayerAsInitialized(long eid) {
        SetLocalPlayerAsInitializedPacket pk = new SetLocalPlayerAsInitializedPacket();
        pk.eid = eid;
        return encode(pk);
    }

    /** TextPacket (0x09) carrying a chat line. */
    public static byte[] chat(String source, String message) {
        TextPacket pk = new TextPacket();
        pk.type = TextPacket.TYPE_CHAT;
        pk.source = source == null ? "" : source;
        pk.message = message == null ? "" : message;
        pk.isLocalized = false;
        return encode(pk);
    }

    /** PlayerActionPacket (0x24) — used for the break animation / start-stop break. */
    public static byte[] playerAction(long eid, int action, int x, int y, int z, int face) {
        PlayerActionPacket pk = new PlayerActionPacket();
        pk.entityId = eid;
        pk.action = action;
        pk.x = x;
        pk.y = y;
        pk.z = z;
        pk.resultPosition = new BlockVector3(x, y, z);
        pk.face = face;
        return encode(pk);
    }

    /**
     * InventoryTransactionPacket (0x1e) with UseItemData.
     *
     * <p>In modern Bedrock both breaking and placing a block travel in this packet:
     * {@code actionType = 2} (BREAK_BLOCK) and {@code actionType = 0} (CLICK_BLOCK). The backend
     * routes them to {@code level.useBreakOn(...)} / {@code level.useItemOn(...)}, which is what
     * makes the change authoritative and visible to every other player.
     */
    public static byte[] useItemOnBlock(long eid, int actionType, int x, int y, int z, int face,
                                        int hotbarSlot, int itemId, int itemMeta, int itemCount,
                                        double px, double py, double pz,
                                        float clickX, float clickY, float clickZ) {
        InventoryTransactionPacket pk = new InventoryTransactionPacket();
        pk.transactionType = InventoryTransactionPacket.TYPE_USE_ITEM;
        pk.actions = new NetworkInventoryAction[0];

        UseItemData data = new UseItemData();
        data.actionType = actionType;
        data.triggerType = 0;
        data.blockPos = new BlockVector3(x, y, z);
        data.face = BlockFace.fromIndex(face);
        data.hotbarSlot = hotbarSlot;
        data.hand = 0;
        data.itemInHand = itemId > 0 ? Item.get(itemId, itemMeta, Math.max(1, itemCount)) : Item.get(0, 0, 0);
        data.playerPos = new Vector3(px, py, pz);
        data.clickPos = new Vector3f(clickX, clickY, clickZ);
        data.blockRuntimeId = 0;
        data.clientInteractPrediction = 0;
        data.clientCooldownState = 0;
        pk.transactionData = data;
        return encode(pk);
    }

    /**
     * Puts an item into one of the player's own inventory slots (window 0).
     *
     * <p>Needed because {@code level.useItemOn(...)} uses the <b>server's</b> held item, not the
     * one carried in the use-item transaction: the old client picks a block out of its creative
     * list, so we have to mirror that choice into the backend's inventory or nothing is placed.
     * Uses a legacy {@code TYPE_NORMAL} container transaction, which the backend accepts when
     * {@code server-authoritative-inventory} is off.
     */
    /**
     * InventoryTransactionPacket (0x1e) with UseItemOnEntityData: a hit on another entity.
     *
     * <p>{@code actionType = 1} is the attack action; the backend routes it to
     * {@code target.attack(...)}, so the damage is computed and broadcast by the server exactly as
     * for a modern client. This is what a 0.14 left-click turns into.
     */
    public static byte[] attackEntity(long entityRuntimeId, int hotbarSlot,
                                      int itemId, int itemMeta, int itemCount,
                                      double px, double py, double pz,
                                      float clickX, float clickY, float clickZ) {
        InventoryTransactionPacket pk = new InventoryTransactionPacket();
        pk.transactionType = InventoryTransactionPacket.TYPE_USE_ITEM_ON_ENTITY;
        pk.actions = new NetworkInventoryAction[0];

        UseItemOnEntityData data = new UseItemOnEntityData();
        data.entityRuntimeId = entityRuntimeId;
        data.actionType = InventoryTransactionPacket.USE_ITEM_ON_ENTITY_ACTION_ATTACK;
        data.hotbarSlot = hotbarSlot;
        data.itemInHand = itemId > 0 ? Item.get(itemId, itemMeta, Math.max(1, itemCount)) : Item.get(0, 0, 0);
        data.playerPos = new Vector3(px, py, pz);
        data.clickPos = new Vector3(clickX, clickY, clickZ);
        pk.transactionData = data;
        return encode(pk);
    }

    public static byte[] setInventorySlot(int slot, int itemId, int itemMeta, int count) {
        return setInventorySlot(slot, 0, 0, 0, itemId, itemMeta, count);
    }

    /**
     * Puts an item into one of the player's own inventory slots (window 0).
     *
     * <p>Two things make this work at all:
     *
     * <ol>
     *   <li>{@code oldId} must be what the backend currently has in that slot — Nukkit's
     *       {@code SlotChangeAction.isValid} requires {@code inventory.getItem(slot).equalsExact(sourceItem)}.</li>
     *   <li>{@code InventoryTransaction.canExecute()} also runs {@code matchItems()}, which demands
     *       item <b>conservation</b>: everything you add must equal everything you give up. A plain
     *       "dirt -&gt; stone" swap looks like conjuring items and is thrown out.</li>
     * </ol>
     *
     * <p>So the change is padded with two creative actions that are validation no-ops
     * ({@code CreativeInventoryAction.execute()} does nothing): a CREATE carrying the old item and
     * a DELETE carrying the new one. That makes targets == sources, the balance check passes, and
     * only the real slot change is applied.
     */
    public static byte[] setInventorySlot(int slot, int oldId, int oldMeta, int oldCount,
                                          int itemId, int itemMeta, int count) {
        InventoryTransactionPacket pk = new InventoryTransactionPacket();
        pk.transactionType = InventoryTransactionPacket.TYPE_NORMAL;

        java.util.List<NetworkInventoryAction> list = new java.util.ArrayList<>(3);

        // the real change: slot contents -> the item the old client is holding
        NetworkInventoryAction change = new NetworkInventoryAction();
        change.sourceType = NetworkInventoryAction.SOURCE_CONTAINER;
        change.windowId = 0;                       // ContainerIds.INVENTORY
        change.flags = 0;
        change.inventorySlot = slot;
        change.oldItem = oldId > 0 ? Item.get(oldId, oldMeta, Math.max(1, oldCount)) : Item.get(0, 0, 0);
        change.newItem = Item.get(itemId, itemMeta, Math.max(1, count));
        list.add(change);

        // balance: the item leaving the slot is "created", the item entering it is "deleted"
        if (oldId > 0) {
            NetworkInventoryAction create = new NetworkInventoryAction();
            create.sourceType = NetworkInventoryAction.SOURCE_CREATIVE;
            create.inventorySlot = InventoryTransactionPacket.ACTION_MAGIC_SLOT_CREATIVE_CREATE_ITEM;
            create.oldItem = Item.get(0, 0, 0);
            create.newItem = Item.get(oldId, oldMeta, Math.max(1, oldCount));
            list.add(create);
        }
        if (itemId > 0) {
            NetworkInventoryAction delete = new NetworkInventoryAction();
            delete.sourceType = NetworkInventoryAction.SOURCE_CREATIVE;
            delete.inventorySlot = InventoryTransactionPacket.ACTION_MAGIC_SLOT_CREATIVE_DELETE_ITEM;
            delete.oldItem = Item.get(itemId, itemMeta, Math.max(1, count));
            delete.newItem = Item.get(0, 0, 0);
            list.add(delete);
        }

        pk.actions = list.toArray(new NetworkInventoryAction[0]);
        return encode(pk);
    }

    /** MobEquipmentPacket (0x1f): selects the hotbar slot and the item the backend sees in hand. */
    public static byte[] mobEquipment(long eid, int hotbarSlot, int itemId, int itemMeta, int count) {
        MobEquipmentPacket pk = new MobEquipmentPacket();
        pk.eid = eid;
        pk.hotbarSlot = hotbarSlot;
        pk.inventorySlot = hotbarSlot;
        pk.windowId = 0;
        pk.item = itemId > 0 ? Item.get(itemId, itemMeta, Math.max(1, count)) : Item.get(0, 0, 0);
        return encode(pk);
    }

    /**
     * CommandRequestPacket (0x4d) — how a modern client asks the backend to run a command.
     *
     * <p>The backend's {@code TextProcessor} only calls {@code player.chat(...)}; it never looks
     * for a leading "/", so a command sent as chat is broadcast verbatim (which is exactly what
     * used to happen to "/gm 1"). {@code CommandRequestProcessor_v137} is the only path that ends
     * in {@code dispatchCommand(...)}, and it expects {@code command} to include the "/".
     *
     * <p>Nukkit's own encoder for this packet is {@code encodeUnsupported()}, so the layout is
     * written by hand for protocol 2193 (v1_21_130_28+ shape):
     * {@code [string command][string originType][uuid][string requestId][LLong playerId][bool internal][string version]}.
     */
    public static byte[] command(String command, byte[] uuid, long playerId) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        putVarInt(out, PACKET_COMMAND_REQUEST);
        putString(out, command);
        putString(out, "player");                       // origin type
        byte[] id = (uuid != null && uuid.length == 16) ? uuid : new byte[16];
        out.write(id, 0, 16);
        putString(out, "");                             // request id
        for (int i = 0; i < 8; i++) {                   // playerId, little-endian long
            out.write((int) ((playerId >>> (i * 8)) & 0xff));
        }
        out.write(0);                                   // internal = false
        putString(out, "");                             // version
        return out.toByteArray();
    }

    private static final int PACKET_COMMAND_REQUEST = 0x4d;
    private static final int PACKET_RESPAWN = 0x2d;

    /**
     * RespawnPacket (0x2d) sent by the client after the player taps respawn.
     *
     * <p>{@code RespawnProcessor} only reacts to {@code STATE_CLIENT_READY_TO_SPAWN} (2) and only
     * while the player is dead; without it the backend never revives the player, so the old client
     * stays on the death screen forever.
     */
    public static byte[] respawn(float x, float y, float z, long eid) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(20);
        putVarInt(out, PACKET_RESPAWN);
        putFloatLE(out, x);
        putFloatLE(out, y);
        putFloatLE(out, z);
        out.write(2);                            // STATE_CLIENT_READY_TO_SPAWN
        putVarInt(out, eid);
        return out.toByteArray();
    }
    private static final int PACKET_PLAYER_AUTH_INPUT = 0x90;

    /**
     * PlayerAuthInputPacket (0x90) — the movement packet this backend actually consumes.
     *
     * <p>For protocol &gt;= 1.21.90 Nukkit-MOT hard-codes server-authoritative movement
     * ({@code Player.getAuthoritativeMovementMode()} returns SERVER_WITH_REWIND for every new
     * protocol, ignoring the server.properties setting), and in that mode it throws away every
     * {@code MovePlayerPacket} ("if (... || this.isMovementServerAuthoritative()) break;").
     * A 0.14 client only knows MovePlayer, so the proxy has to synthesise the auth-input packet
     * or the backend never learns that we moved: no movement is ever broadcast to other players
     * and block-interaction distance checks use a stale position.
     *
     * <p>Wire layout for protocol 2193 (v1_26_50_27+), from {@code PlayerAuthInputPacket.decode()}:
     * pitch, yaw, position(3f), motion(2f), headYaw, inputActionCount + ordinals, inputMode,
     * playMode, interactionModel(zigzag), interactRotation(2f), tick(uvarint64), delta(3f),
     * five optional-section presence bools, analogMoveVector(2f), cameraOrientation(3f),
     * rawMoveVector(2f).
     */
    public static byte[] playerAuthInput(float x, float y, float z, float pitch, float yaw,
                                         float headYaw, long tick) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64);
        putVarInt(out, PACKET_PLAYER_AUTH_INPUT);
        putFloatLE(out, pitch);
        putFloatLE(out, yaw);
        putFloatLE(out, x);
        putFloatLE(out, y);
        putFloatLE(out, z);
        putFloatLE(out, 0f);                    // motion.x
        putFloatLE(out, 0f);                    // motion.z
        putFloatLE(out, headYaw);
        putVarInt(out, 0);                      // no input actions
        putVarInt(out, 1);                      // InputMode.KEYBOARD_MOUSE
        putVarInt(out, 0);                      // ClientPlayMode.NORMAL
        putVarInt(out, 0);                      // interaction model (zigzag 0)
        putFloatLE(out, 0f);                    // interactRotation.x
        putFloatLE(out, 0f);                    // interactRotation.y
        putVarInt(out, tick & 0x7FFFFFFFFFFFFFFFL);
        putFloatLE(out, 0f);                    // delta.x
        putFloatLE(out, 0f);                    // delta.y
        putFloatLE(out, 0f);                    // delta.z
        out.write(0);                           // no item-use transaction
        out.write(0);                           // no item-stack request
        out.write(0);                           // no block actions
        out.write(0);                           // no vehicle rotation
        out.write(0);                           // no predicted vehicle
        putFloatLE(out, 0f);                    // analogMoveVector.x
        putFloatLE(out, 0f);                    // analogMoveVector.y
        putFloatLE(out, 0f);                    // cameraOrientation.x
        putFloatLE(out, 0f);                    // cameraOrientation.y
        putFloatLE(out, 0f);                    // cameraOrientation.z
        putFloatLE(out, 0f);                    // rawMoveVector.x
        putFloatLE(out, 0f);                    // rawMoveVector.y
        return out.toByteArray();
    }

    private static void putFloatLE(java.io.ByteArrayOutputStream b, float f) {
        int bits = Float.floatToIntBits(f);
        b.write(bits & 0xff);
        b.write((bits >> 8) & 0xff);
        b.write((bits >> 16) & 0xff);
        b.write((bits >> 24) & 0xff);
    }

    private static void putVarInt(java.io.ByteArrayOutputStream b, long v) {
        long value = v & 0xFFFFFFFFFFFFFFFFL;
        while ((value & ~0x7FL) != 0) {
            b.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        b.write((int) value);
    }

    private static void putString(java.io.ByteArrayOutputStream b, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        putVarInt(b, bytes.length);
        b.write(bytes, 0, bytes.length);
    }
}
