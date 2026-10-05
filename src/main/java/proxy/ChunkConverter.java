package proxy;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Converts a modern (1.26.50) LevelChunk sub-chunk section into the 0.14.3 network chunk payload.
 *
 * 0.14 network chunk layout (from Nukkit-0143 ChunkRequestTask, ORDER_COLUMNS):
 *   [32768] block ids   idx = (x << 11) | (z << 7) | y
 *   [16384] block meta  idx = blockIdx >> 1 ; even y = low nibble, odd y = high nibble
 *   [16384] sky light   same nibble indexing
 *   [16384] block light same nibble indexing
 *   [ 256]  height map  idx = (z << 4) | x
 *   [1024]  biome colors idx = (z << 4) | x, big-endian int each
 *   [...]   block entities (concatenated NBT; empty here)
 *
 * Modern layout: sub-chunks (16x16x16) with a BitArray of palette indices whose palette holds
 * HASHED block network ids (signed varints). Those hashes are mapped back to legacy id+meta.
 */
public final class ChunkConverter {

    /** hash -> {legacyId, meta} */
    public static Map<Integer, int[]> HASH_TO_LEGACY = new HashMap<>();
    /** modern runtime block id -> {legacyId, meta}; used to translate UpdateBlock packets. */
    public static Map<Integer, int[]> RUNTIME_TO_LEGACY = new HashMap<>();
    /** legacy ids the 0.14.3 client does not know; everything above this is remapped. */
    public static final int MAX_LEGACY_ID = 450;
    /** block used when a new-version block is filtered out. */
    private static final int FALLBACK_ID = 1;   // stone

    private ChunkConverter() {}

    public static void loadMap(String path) throws IOException {
        Map<Integer, int[]> map = new HashMap<>(32768);
        Map<Integer, int[]> runtimeMap = new HashMap<>(32768);
        for (String line : readLines(path)) {
            String[] parts = line.trim().split(" ");
            if (parts.length >= 3) {
                int legacyId = Integer.parseInt(parts[1]);
                int meta = Integer.parseInt(parts[2]);
                map.put(Integer.parseInt(parts[0]), new int[]{legacyId, meta});
                if (parts.length >= 4) {
                    runtimeMap.put(Integer.parseInt(parts[3]), new int[]{legacyId, meta});
                }
            }
        }
        HASH_TO_LEGACY = map;
        RUNTIME_TO_LEGACY = runtimeMap;
        System.out.println("[chunk] block hash map loaded: " + map.size() + " states, "
                + runtimeMap.size() + " runtime ids");
    }

    /** Reads a data file from the working directory, falling back to the jar's resources. */
    static java.util.List<String> readLines(String path) throws IOException {
        java.nio.file.Path p = Paths.get(path);
        if (Files.isReadable(p)) {
            return Files.readAllLines(p);
        }
        try (java.io.InputStream in = ChunkConverter.class.getResourceAsStream("/" + path)) {
            if (in == null) {
                throw new IOException("data file not found: " + path
                        + " (not in the working directory nor on the classpath)");
            }
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .lines().collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * Maps a block id from a packet (UpdateBlock &amp; chunk palettes both carry the <b>hashed</b>
     * block id, not the runtime id) onto the 0.14 id+meta pair.
     *
     * <p>Air matters here: its hash maps to legacy id 0, and the old "unknown -&gt; stone"
     * fallback used to turn every destroyed block into stone instead of removing it.
     */
    public static int[] hashToLegacy(int hashedId) {
        int[] v = HASH_TO_LEGACY.get(hashedId);
        if (v == null) {
            return new int[]{FALLBACK_ID, 0};
        }
        if (v[0] == 0) {
            return new int[]{0, 0};                    // air stays air
        }
        if (!LegacyIds.isLegacyBlock(v[0])) {
            return new int[]{FALLBACK_ID, 0};          // block the 0.14 client cannot render
        }
        return v;
    }

    /** Maps a modern runtime block id onto the 0.14 id+meta pair the old client understands. */
    public static int[] runtimeToLegacy(int runtimeId) {
        // runtime id 0 is AIR. It has no entry in the block table, and the old fallback ("show
        // stone") turned every destroyed block into stone instead of removing it.
        if (runtimeId == 0) {
            return new int[]{0, 0};
        }
        int[] v = RUNTIME_TO_LEGACY.get(runtimeId);
        if (v == null) {
            return new int[]{FALLBACK_ID, 0};
        }
        // A mapping that resolves to air must stay air.
        if (v[0] == 0) {
            return new int[]{0, 0};
        }
        // Filter out anything the 0.14.3 client cannot render: show stone instead.
        if (v[0] < 0 || v[0] > MAX_LEGACY_ID) {
            return new int[]{FALLBACK_ID, 0};
        }
        return v;
    }

    /**
     * @param subChunkCount the sub-chunk count from the LevelChunkPacket header
     * @param data         the LevelChunkPacket data section
     * @return the full 0.14.3 FullChunkData payload
     */
    public static byte[] convertChunk(int chunkX, int chunkZ, int subChunkCount, byte[] data) {
        byte[] blocks = new byte[32768];
        byte[] meta = new byte[16384];
        int p = 0;

        for (int s = 0; s < subChunkCount; s++) {
            int version = data[p++] & 0xff;
            int storageCount = version >= 8 ? data[p++] & 0xff : 1;
            int yIndex = version >= 9 ? data[p++] : 0;
            int yBase = yIndex * 16;
            for (int st = 0; st < storageCount; st++) {
                int paletteHeader = data[p++] & 0xff;
                int bitsPerBlock = paletteHeader >> 1;
                if (bitsPerBlock == 0) {
                    int paletteSize = readSVarInt(data, p); p += varIntLen(data, p);
                    int entry = readSVarInt(data, p); p += varIntLen(data, p);
                    fillSubChunk(blocks, meta, yBase, entry, st == 0);
                } else {
                    int epw = entriesPerWord(bitsPerBlock);
                    int words = (4096 + epw - 1) / epw;
                    int blocksPos = p;
                    p += words * 4;
                    int paletteSize = readSVarInt(data, p); p += varIntLen(data, p);
                    int[] palette = new int[Math.max(paletteSize, 0)];
                    for (int i = 0; i < palette.length; i++) { palette[i] = readSVarInt(data, p); p += varIntLen(data, p); }
                    decodeStorage(data, blocksPos, words, bitsPerBlock, epw, palette, blocks, meta, yBase, st == 0);
                }
            }
        }
        return buildPacket(chunkX, chunkZ, blocks, meta);
    }

    /** Assembles the 0.14 payload from the decoded blocks/meta. */
    private static byte[] buildPacket(int chunkX, int chunkZ, byte[] blocks, byte[] meta) {
        byte[] sky = new byte[16384];
        byte[] blockLight = new byte[16384];
        Arrays.fill(sky, (byte) 0xFF);          // fully lit sky
        byte[] heightMap = new byte[256];
        byte[] biomeColors = new byte[1024];
        int grass = 0x79C05A;                   // plains grass tint (RGB)
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = 0;
                for (int y = 127; y >= 0; y--) {
                    if ((blocks[(x << 11) | (z << 7) | y] & 0xff) != 0) { h = y + 1; break; }
                }
                heightMap[(z << 4) | x] = (byte) h;
                int o = ((z << 4) | x) * 4;
                biomeColors[o]     = (byte) ((grass >> 24) & 0xff);
                biomeColors[o + 1] = (byte) ((grass >> 16) & 0xff);
                biomeColors[o + 2] = (byte) ((grass >> 8) & 0xff);
                biomeColors[o + 3] = (byte) (grass & 0xff);
            }
        }
        // +4 for the trailing ExtraData block (a big-endian int count; the 0.14 client reads it
        // and a payload that stops at the biome colours is rejected as too short).
        int total = 32768 + 16384 + 16384 + 16384 + 256 + 1024 + 4;
        byte[] out = new byte[total];
        int o = 0;
        System.arraycopy(blocks, 0, out, o, 32768); o += 32768;
        System.arraycopy(meta, 0, out, o, 16384); o += 16384;
        System.arraycopy(sky, 0, out, o, 16384); o += 16384;
        System.arraycopy(blockLight, 0, out, o, 16384); o += 16384;
        System.arraycopy(heightMap, 0, out, o, 256); o += 256;
        System.arraycopy(biomeColors, 0, out, o, 1024); o += 1024;
        // ExtraData: [int count] followed by count entries; empty for a plain generated chunk.
        out[o] = 0; out[o + 1] = 0; out[o + 2] = 0; out[o + 3] = 0;
        return out;
    }

    private static void decodeStorage(byte[] d, int blocksPos, int words, int bitsPerBlock, int epw,
                                      int[] palette, byte[] blocks, byte[] meta, int yBase, boolean primary) {
        int maxEntry = (1 << bitsPerBlock) - 1;
        for (int i = 0; i < 4096; i++) {
            int wordIdx = i / epw;
            int bitOffset = (i % epw) * bitsPerBlock;
            if (wordIdx >= words) break;
            int w = (d[blocksPos + wordIdx * 4] & 0xff)
                    | ((d[blocksPos + wordIdx * 4 + 1] & 0xff) << 8)
                    | ((d[blocksPos + wordIdx * 4 + 2] & 0xff) << 16)
                    | ((d[blocksPos + wordIdx * 4 + 3] & 0xff) << 24);
            int state = (w >>> bitOffset) & maxEntry;
            if (state >= palette.length) continue;
            // modern sub-chunk index: x = i >> 8, z = (i >> 4) & 15, y = i & 15
            putBlock(blocks, meta, (i >> 8) & 15, (i >> 4) & 15, yBase + (i & 15), palette[state], primary);
        }
    }

    private static void fillSubChunk(byte[] blocks, byte[] meta, int yBase, int hashedId, boolean primary) {
        for (int i = 0; i < 4096; i++) {
            putBlock(blocks, meta, (i >> 8) & 15, (i >> 4) & 15, yBase + (i & 15), hashedId, primary);
        }
    }

    private static void putBlock(byte[] blocks, byte[] meta, int x, int y, int gy, int hashedId, boolean primary) {
        if (!primary) return;                 // only the primary storage is visible to 0.14
        if (gy < 0 || gy > 127) return;       // 0.14 world is 128 high
        int[] legacy = HASH_TO_LEGACY.get(hashedId);
        int id = legacy == null ? FALLBACK_ID : legacy[0];
        int mt = legacy == null ? 0 : legacy[1];
        if (!LegacyIds.isLegacyBlock(id)) {   // filter blocks the old client cannot render
            id = FALLBACK_ID;
            mt = 0;
        }
        int idx = (x << 11) | (y << 7) | gy;
        blocks[idx] = (byte) id;
        int mi = idx >> 1;
        if ((gy & 1) == 0) meta[mi] = (byte) ((meta[mi] & 0xF0) | (mt & 0xF));
        else meta[mi] = (byte) ((meta[mi] & 0x0F) | ((mt & 0xF) << 4));
    }

    static int entriesPerWord(int b) {
        switch (b) { case 16: return 2; case 8: return 4; case 6: return 5; case 5: return 6;
                     case 4: return 8; case 3: return 10; case 2: return 16; case 1: return 32; default: return 1; }
    }
    static int varIntLen(byte[] d, int p) { int s = p; while (p < d.length && (d[p++] & 0x80) != 0) {} return p - s; }
    static int readUVarInt(byte[] d, int p) { int v = 0, s = 0; while (true) { int b = d[p++] & 0xff; v |= (b & 0x7f) << s; if ((b & 0x80) == 0) break; s += 7; } return v; }
    static int readSVarInt(byte[] d, int p) { int v = readUVarInt(d, p); return (v >>> 1) ^ -(v & 1); }

    /** Offline self-test / statistics over the captured chunk. */
    public static void main(String[] args) throws Exception {
        loadMap("C:/Users/Administrator/LegacyProxy/block_hash_map.txt");
        byte[] raw = Files.readAllBytes(Paths.get("C:/Users/Administrator/LegacyProxy/capture_chunk.bin"));
        int p = 0;
        int chunkX = readSVarInt(raw, p); p += varIntLen(raw, p);
        int chunkZ = readSVarInt(raw, p); p += varIntLen(raw, p);
        p += varIntLen(raw, p);                       // dimension
        int subChunkCount = readUVarInt(raw, p); p += varIntLen(raw, p);
        p += 1; p += 1;                               // requestSubChunks, cacheEnabled
        p += varIntLen(raw, p);                       // blob ids
        int dataLen = readUVarInt(raw, p); p += varIntLen(raw, p);
        byte[] data = Arrays.copyOfRange(raw, p, p + dataLen);
        byte[] out = convertChunk(chunkX, chunkZ, subChunkCount, data);
        System.out.println("chunk " + chunkX + "," + chunkZ + " -> 0.14 payload " + out.length + " bytes");
        int nonAir = 0;
        for (int i = 0; i < 32768; i++) if (out[i] != 0) nonAir++;
        System.out.println("non-air blocks: " + nonAir);
        TreeMap<Integer, Integer> hist = new TreeMap<>();
        for (int i = 0; i < 32768; i++) hist.merge(out[i] & 0xff, 1, Integer::sum);
        System.out.println("block histogram (id=count): " + hist);
        boolean filtered = hist.keySet().stream().allMatch(id -> id <= MAX_LEGACY_ID);
        System.out.println("all ids <= " + MAX_LEGACY_ID + ": " + filtered);
        System.out.println("CONVERT_OK");
    }
}
