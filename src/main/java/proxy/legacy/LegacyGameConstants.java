package proxy.legacy;

/**
 * MCPE 0.14.x (protocol 70) game packet IDs, matching MPMPESCore Info.php.
 */
public final class LegacyGameConstants {

    private LegacyGameConstants() {
    }

    public static final int LOGIN = 0x8f;
    public static final int PLAY_STATUS = 0x90;
    public static final int DISCONNECT = 0x91;
    public static final int BATCH = 0x92;
    public static final int TEXT = 0x93;
    public static final int SET_TIME = 0x94;
    public static final int START_GAME = 0x95;
    public static final int ADD_PLAYER = 0x96;
    public static final int REMOVE_PLAYER = 0x97;
    public static final int ADD_ENTITY = 0x98;
    public static final int REMOVE_ENTITY = 0x99;
    public static final int ADD_ITEM_ENTITY = 0x9a;
    public static final int TAKE_ITEM_ENTITY = 0x9b;
    public static final int MOVE_ENTITY = 0x9c;
    public static final int MOVE_PLAYER = 0x9d;
    public static final int REMOVE_BLOCK = 0x9e;
    public static final int UPDATE_BLOCK = 0x9f;
    public static final int ADD_PAINTING = 0xa0;
    public static final int EXPLODE = 0xa1;
    public static final int LEVEL_EVENT = 0xa2;
    public static final int BLOCK_EVENT = 0xa3;
    public static final int ENTITY_EVENT = 0xa4;
    public static final int MOB_EFFECT = 0xa5;
    public static final int UPDATE_ATTRIBUTES = 0xa6;
    public static final int MOB_EQUIPMENT = 0xa7;
    public static final int MOB_ARMOR_EQUIPMENT = 0xa8;
    public static final int INTERACT = 0xa9;
    public static final int USE_ITEM = 0xaa;
    public static final int PLAYER_ACTION = 0xab;
    public static final int HURT_ARMOR = 0xac;
    public static final int SET_ENTITY_DATA = 0xad;
    public static final int SET_ENTITY_MOTION = 0xae;
    public static final int SET_ENTITY_LINK = 0xaf;
    public static final int SET_HEALTH = 0xb0;
    public static final int SET_SPAWN_POSITION = 0xb1;
    public static final int ANIMATE = 0xb2;
    public static final int RESPAWN = 0xb3;
    public static final int DROP_ITEM = 0xb4;
    public static final int CONTAINER_OPEN = 0xb5;
    public static final int CONTAINER_CLOSE = 0xb6;
    public static final int CONTAINER_SET_SLOT = 0xb7;
    public static final int CONTAINER_SET_DATA = 0xb8;
    public static final int CONTAINER_SET_CONTENT = 0xb9;
    public static final int CRAFTING_DATA = 0xba;
    public static final int CRAFTING_EVENT = 0xbb;
    public static final int ADVENTURE_SETTINGS = 0xbc;
    public static final int BLOCK_ENTITY_DATA = 0xbd;
    public static final int PLAYER_INPUT = 0xbe;
    public static final int FULL_CHUNK_DATA = 0xbf;
    public static final int SET_DIFFICULTY = 0xc0;
    public static final int CHANGE_DIMENSION = 0xc1;
    public static final int SET_PLAYER_GAMETYPE = 0xc2;
    public static final int PLAYER_LIST = 0xc3;
    public static final int CLIENTBOUND_MAP_ITEM_DATA = 0xc6;
    public static final int MAP_INFO_REQUEST = 0xc7;
    public static final int REQUEST_CHUNK_RADIUS = 0xc8;
    public static final int CHUNK_RADIUS_UPDATE = 0xc9;
    public static final int ITEM_FRAME_DROP_ITEM = 0xca;

    // Play status
    public static final int PLAY_STATUS_LOGIN_SUCCESS = 0;
    public static final int PLAY_STATUS_LOGIN_FAILED_CLIENT = 1;
    public static final int PLAY_STATUS_LOGIN_FAILED_SERVER = 2;
    public static final int PLAY_STATUS_PLAYER_SPAWN = 3;
}
