# LegacyProxy

A **Minecraft Pocket Edition 0.14.3 → modern Bedrock** protocol bridge.

An old 0.14.3 client connects to the proxy's UDP port; the proxy logs into a modern
(Nukkit-MOT **1.26.50**, protocol 2193) server by itself and translates both directions, so
0.14.3 players share the same world as up-to-date clients.

Everything the old client cannot handle is filtered out: block ids, item ids and crafting
recipes are checked against the exact set 0.14.3 knows (extracted from the 0.14.3 server
source, see `src/main/resources/legacy_*_ids.txt`).

> 中文速览：0.14.3 客户端连代理端口 → 代理登录现代服 → 双向翻译。新版方块/物品/配方按 0.14.3
> 的精确白名单过滤。见下方「构建」和「运行」。

## Requirements

| | |
|---|---|
| JDK | 17+ (the build targets 17 bytecode) |
| Backend | Nukkit-MOT at tag **`1.26.50-R1`** (protocol 2193), reachable over UDP |
| Frontend | a MCPE **0.14.3** client |

The backend needs these settings in `server.properties`:

```properties
xbox-auth=off
# Leave connection encryption ON: the proxy implements the Bedrock handshake itself (see
# "Connection encryption" below). Turning it off still works, but only if the proxy is told
# require-encryption=false.
encryption=on
# The 0.14 client cannot send PlayerAuthInput, and this backend hard-codes server-authoritative
# movement for protocol >= 1.21.90, so the proxy synthesises PlayerAuthInput itself.
server-authoritative-movement=client-auth
# Legacy TYPE_NORMAL inventory transactions are how the proxy mirrors the old client's hotbar.
server-authoritative-inventory=off
```

## Build

The proxy builds every modern packet with **Nukkit-MOT's own packet classes**, so the wire
format can never drift from what the backend expects. That jar is not on Maven Central:

```bash
# 1. get the matching server jar (or copy one you already have)
mkdir -p libs
git clone --depth 1 --branch 1.26.50-R1 https://github.com/MemoriesOfTime/Nukkit-MOT.git /tmp/nukkit-mot
(cd /tmp/nukkit-mot && mvn -B package -DskipTests)
cp /tmp/nukkit-mot/target/*.jar libs/Nukkit-MOT-SNAPSHOT.jar

# 2. build the proxy
gradle build            # -> build/libs/legacyproxy-1.0.0.jar
```

Already have the jar somewhere else? Point at it instead of copying:

```bash
gradle -PnukkitJar=/path/to/Nukkit-MOT-SNAPSHOT.jar build
```

## Run

```bash
cp proxy.properties.example proxy.properties   # optional, it is created on first run
java -cp "build/libs/legacyproxy-1.0.0.jar:libs/Nukkit-MOT-SNAPSHOT.jar" proxy.ProxyMain
```

Windows (note `;` as the classpath separator):

```bat
java -cp "build\libs\legacyproxy-1.0.0.jar;libs\Nukkit-MOT-SNAPSHOT.jar" proxy.ProxyMain
```

### Configuration — `proxy.properties`

```properties
listen-port=19132        # 0.14.3 clients connect here
backend-host=127.0.0.1   # the modern server
backend-port=19133
require-encryption=true  # refuse to play unless the backend encrypts the connection
```

## Architecture

```
0.14.3 client ──RakNet v7──> proxy.legacy.*   (RakNet v7 server)
                                  │
                          ProxyClientSession  (the translation bridge)
                                  │
            proxy.modern.ModernClient ──RakNet v11──> Nukkit-MOT 1.26.50
```

| Path | Role |
|---|---|
| `proxy/legacy/` | hand-written RakNet v7 server stack the 0.14.3 clients connect to |
| `proxy/modern/ModernClient.java` | RakNet v11 client that logs into the backend |
| `proxy/modern/BedrockEncryption.java` | the client half of Bedrock's connection encryption (handshake JWT → AES stream) |
| `proxy/modern/ModernCodec.java` | builds client→server packets with the backend's own classes |
| `proxy/modern/BackendRuntime.java` | boots the backend jar's item/block/runtime-id registries without a server |
| `proxy/ChunkConverter.java` | modern sub-chunk palette → 0.14 columnar chunk format |
| `proxy/CraftingData.java` | builds a 0.14 recipe list from the backend's registry, new recipes filtered |
| `proxy/LegacyIds.java` | the exact block/item id set 0.14.3 understands |
| `src/main/resources/block_hash_map.txt` | hashed modern block id → legacy id+meta |

## What is implemented

* **Login** — the 0.14 client's `PlayStatus(LOGIN_SUCCESS)` must be wrapped in the `0x8e`
  encapsulation marker: the client reads the packet id from **byte 1** (`pid = buffer[1]`,
  `offset = 2`). A bare frame is decoded as id `0x00` and silently dropped, which leaves the
  client stuck on "locating server" forever.
* **Backend spawn** — `SetLocalPlayerAsInitialized` (0x71) is required, otherwise the backend
  never calls `doFirstSpawn()` and chat/commands/block interaction stay dead.
* **Movement** — the backend ignores `MovePlayerPacket` for protocol ≥ 1.21.90, so movement is
  sent as a synthesised `PlayerAuthInputPacket` (0x90). Teleports (`/tp`) come back as
  `MovePlayerPacket` and are forwarded to the old client as `MovePlayer` with `MODE_RESET`.
* **Other players** — `AddPlayer` / `MoveEntityAbsolute` / `MoveEntityDelta` / `RemoveEntity`
  are translated, and 0.14↔0.14 players are relayed to each other inside the proxy (the
  backend's per-chunk viewer bookkeeping does not spawn proxy players to one another).
* **Blocks** — `UpdateBlock` carries the **hashed** block id (air is `-604749536`), not the
  runtime id. Break/place go out as `InventoryTransaction` packets; block changes come back as
  0.14 `UpdateBlock`.
* **Creative placement** — the old client conjures items locally, so the proxy mirrors its
  hotbar into the backend inventory. Nukkit requires item *conservation*
  (`InventoryTransaction.matchItems`), so the slot change is padded with two creative
  create/delete actions that are validation no-ops.
* **Inventory & drops** — `InventoryContent` (0x31) → 0.14 `ContainerSetContent` (36 slots +
  HUD hotbar); `AddItemEntity` / `TakeItemEntity` / `RemoveEntity` keep drops in sync.
* **Chat, commands, gamemode, death** — chat goes through `TextPacket`; commands must be sent
  as `CommandRequestPacket` (the backend never treats chat text starting with `/` as a command);
  `SetPlayerGameType` and the death/respawn pair are translated too.
* **Filtering** — block ids, item ids, the creative menu and the recipe list are all filtered
  against the exact 0.14.3 id set.
* **Connection encryption** — the proxy is a full Bedrock encryption peer, so the backend can
  keep `encryption=on`. `proxy/modern/BedrockEncryption.java` derives the session key from the
  `ServerToClientHandshake` (0x03) JWT exactly like the server side does:
  `key = SHA-256(salt ‖ ECDH-P384(identityPrivate, serverEphemeralPublic))`, then
  `AES/CTR` with `iv = key[0..12] ‖ 00 00 02` for protocol > 1.16.210 (`AES/CFB8` below that),
  one stateful cipher per direction. Every frame after that is
  `[0xfe][cipher(prefix ‖ compressed ‖ checksum)]` with
  `checksum = SHA-256(LE64(counter) ‖ payload ‖ key)[0..8]`; the login chain carries a real
  P-384 identity key so the server's `ECDH` has something to agree with.
  With `require-encryption=true` (the default) a backend that never sends the handshake is
  refused instead of being played in the clear.

  Because that payload is a stream cipher, a single lost or duplicated datagram shifts every
  later packet and the session would die with a checksum error. Two defences: the backend socket
  asks for a 1 MiB receive buffer (the 75 KB `StartGame` burst is split over dozens of
  datagrams), and `BedrockEncryption` can *resynchronise* — the trailing checksum is an 8-byte
  oracle over `(counter, plaintext)`, so on mismatch it generates the keystream around the
  expected offset once and scans small byte/counter offsets until a candidate reproduces its own
  checksum, then re-seeks the cipher past the recovered packet and logs `[enc] RESYNC ...`.

## Limitations

* Survival inventory *management* (moving items between slots) is not translated — only the
  creative hotbar mirror and server→client inventory sync are.
* Modern players are visible to 0.14 clients (position, skin, movement), but their held item
  and animations are not.
* Only the player inventory window is synced; chests and other containers are not.
* The proxy logs into the backend as a normal modern client, so the backend sees one connection
  per 0.14 player.

## License

MIT — see [LICENSE](LICENSE).
