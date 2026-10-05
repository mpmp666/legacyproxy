package proxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * The exact set of block/item ids MCPE 0.14.3 understands, extracted from the 0.14.3 server
 * source (Nukkit-0143-ref {@code Block.java} / {@code Item.java} constants).
 *
 * <p>This replaces the old "id &gt; 450 means new" heuristic, which was wrong in both directions:
 * 0.14.3 really knows ids up to 466 (456-466 exist), while plenty of ids below 450 are modern
 * additions. Anything not in these tables is filtered out before it can reach the old client.
 */
public final class LegacyIds {

    private static final boolean[] BLOCKS = new boolean[512];
    private static final boolean[] ITEMS = new boolean[512];
    private static boolean loaded = false;

    private LegacyIds() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        int b = read("legacy_block_ids.txt", BLOCKS);
        int i = read("legacy_item_ids.txt", ITEMS);
        System.out.println("[ids] 0.14.3 allowlist: " + b + " block ids, " + i + " item ids");
    }

    private static int read(String path, boolean[] table) {
        int n = 0;
        try {
            for (String line : ChunkConverter.readLines(path)) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    int id = Integer.parseInt(s);
                    if (id >= 0 && id < table.length) {
                        table[id] = true;
                        n++;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            System.out.println("[ids] cannot read " + path + ": " + e);
        }
        return n;
    }

    /** True when the 0.14.3 client can render this block id. */
    public static boolean isLegacyBlock(int id) {
        load();
        return id >= 0 && id < BLOCKS.length && BLOCKS[id];
    }

    /** True when the 0.14.3 client knows this item id. */
    public static boolean isLegacyItem(int id) {
        load();
        return id >= 0 && id < ITEMS.length && ITEMS[id];
    }

    /** True when the id is known either as a block or as an item. */
    public static boolean isLegacyAny(int id) {
        return isLegacyBlock(id) || isLegacyItem(id);
    }
}
