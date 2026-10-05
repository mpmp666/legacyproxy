package proxy;

import proxy.legacy.LegacyBinary;
import proxy.legacy.LegacyGameConstants;

import cn.nukkit.inventory.CraftingManager;
import cn.nukkit.inventory.FurnaceRecipe;
import cn.nukkit.inventory.Recipe;
import cn.nukkit.inventory.ShapedRecipe;
import cn.nukkit.inventory.ShapelessRecipe;
import cn.nukkit.item.Item;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the MCPE 0.14.3 CraftingData packet (0xba) from the <b>backend's own</b> recipe
 * registry, keeping only recipes whose every ingredient and result is an item id the old client
 * knows.
 *
 * <p>Why this is needed: the backend streams its modern CraftingDataPacket (0x34) which is ~1 MB
 * and contains thousands of post-0.14 recipes. Forwarding it would break the old client outright,
 * so we never forward it — we synthesise the legacy list instead and drop everything else.
 * This is the "自动剔除新版本的合成表" half of the filtering requirement.
 *
 * <p>Entry layout (matches the 0.14.3 server): [int type][int bodyLen][body]; the body depends on
 * the type — 0 = shapeless, 1 = shaped, 2 = furnace.
 */
public final class CraftingData {

    /** Item ids above this are invisible to the 0.14.3 client. */
    private static final int MAX_LEGACY_ID = 450;
    /** The client cannot hold an unbounded list. */
    private static final int MAX_RECIPES = 300;

    private static byte[] cached;
    private static int cachedCount = -1;

    private CraftingData() {
    }

    /** The 0.14 CraftingData payload, built once and cached. */
    public static synchronized byte[] build() {
        if (cached != null) {
            return cached;
        }
        try {
            // Constructing the manager is what registers the vanilla recipes; no Server needed.
            CraftingManager cm = new CraftingManager();
            List<byte[]> entries = new ArrayList<>();
            int skipped = 0;
            for (Recipe recipe : cm.getRecipes()) {
                try {
                    if (!isLegacyCraftable(recipe)) {
                        skipped++;
                        continue;
                    }
                    byte[] entry = encodeRecipe(recipe);
                    if (entry != null) {
                        entries.add(entry);
                    }
                    if (entries.size() >= MAX_RECIPES) {
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            LegacyBinary.Writer w = new LegacyBinary.Writer();
            w.putByte(LegacyGameConstants.CRAFTING_DATA);
            w.putInt(entries.size());
            for (byte[] e : entries) {
                w.putBytes(e);
            }
            w.putByte(0);                 // cleanRecipes = false
            cached = w.toByteArray();
            cachedCount = entries.size();
            System.out.println("[craft] 0.14 recipe list: " + cachedCount + " kept, " + skipped
                    + " new-version recipes filtered out (" + cached.length + " bytes)");
        } catch (Throwable t) {
            System.out.println("[craft] build failed, sending an empty list: " + t);
            LegacyBinary.Writer w = new LegacyBinary.Writer();
            w.putByte(LegacyGameConstants.CRAFTING_DATA);
            w.putInt(0);
            w.putByte(0);
            cached = w.toByteArray();
            cachedCount = 0;
        }
        return cached;
    }

    public static int count() {
        build();
        return cachedCount;
    }

    /** True when the recipe only touches item ids the 0.14.3 client knows (0..450). */
    private static boolean isLegacyCraftable(Recipe recipe) {
        try {
            List<Item> all = new ArrayList<>();
            Item result = recipe.getResult();
            if (result != null) {
                all.add(result);
            }
            if (recipe instanceof ShapelessRecipe) {
                all.addAll(((ShapelessRecipe) recipe).getIngredientList());
            } else if (recipe instanceof ShapedRecipe) {
                all.addAll(((ShapedRecipe) recipe).getIngredientList());
            } else if (recipe instanceof FurnaceRecipe) {
                all.add(((FurnaceRecipe) recipe).getInput());
            }
            for (Item it : all) {
                if (it != null && !it.isNull() && (it.getId() < 0 || it.getId() > MAX_LEGACY_ID)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] encodeRecipe(Recipe recipe) {
        LegacyBinary.Writer entry = new LegacyBinary.Writer();
        LegacyBinary.Writer body = new LegacyBinary.Writer();
        int type;
        if (recipe instanceof ShapelessRecipe) {
            ShapelessRecipe r = (ShapelessRecipe) recipe;
            List<Item> ing = r.getIngredientList();
            body.putInt(ing.size());
            for (Item it : ing) {
                writeSlot(body, it);
            }
            body.putInt(1);
            writeSlot(body, r.getResult());
            writeUUID(body, recipe.toString());
            type = 0;
        } else if (recipe instanceof ShapedRecipe) {
            ShapedRecipe r = (ShapedRecipe) recipe;
            int width = r.getWidth();
            int height = r.getHeight();
            body.putInt(width);
            body.putInt(height);
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    writeSlot(body, r.getIngredient(x, z));
                }
            }
            body.putInt(1);
            writeSlot(body, r.getResult());
            writeUUID(body, recipe.toString());
            type = 1;
        } else if (recipe instanceof FurnaceRecipe) {
            FurnaceRecipe r = (FurnaceRecipe) recipe;
            Item input = r.getInput();
            body.putInt((input.getId() << 16) | (input.getDamage() & 0xffff));
            writeSlot(body, r.getResult());
            type = 2;
        } else {
            return null;
        }
        byte[] bodyBytes = body.toByteArray();
        entry.putInt(type);
        entry.putInt(bodyBytes.length);
        entry.putBytes(bodyBytes);
        return entry.toByteArray();
    }

    /** 0.14 slot: [short id][byte count][short damage][LShort nbtLen]. */
    private static void writeSlot(LegacyBinary.Writer w, Item it) {
        if (it == null || it.isNull()) {
            w.putShort(0);
            w.putByte(0);
            w.putShort(0);
            w.putLShort(0);
            return;
        }
        w.putShort(it.getId());
        w.putByte(Math.max(1, it.getCount()));
        w.putShort(it.getDamage());
        w.putLShort(0);
    }

    /** Recipe uuid, written big-endian like the 0.14.3 server does. */
    private static void writeUUID(LegacyBinary.Writer w, Object key) {
        java.util.UUID id = java.util.UUID.nameUUIDFromBytes(
                ("recipe:" + key).getBytes(StandardCharsets.UTF_8));
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            w.putByte((int) (msb >>> (i * 8)));
        }
        for (int i = 7; i >= 0; i--) {
            w.putByte((int) (lsb >>> (i * 8)));
        }
    }
}
