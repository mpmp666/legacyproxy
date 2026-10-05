package proxy.modern;

import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.item.Item;
import cn.nukkit.item.RuntimeItems;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Boots the parts of the backend server jar that its own packet classes need, <b>without</b>
 * running a server.
 *
 * <p>Why this is needed: {@code BinaryStream.putNetworkItemStackDescriptor} (used by every modern
 * inventory/item packet) calls {@code GlobalBlockPalette}, whose static initialiser reads
 * {@code Server.getInstance().netEaseMode}. Likewise the item/block registries are populated by
 * {@code Server}'s start-up. In a proxy there is no Server, so we install a bare (constructor-less)
 * {@code Server} instance as the singleton and run the registries' {@code init()} methods by hand.
 *
 * <p>Everything here is best-effort: if a step fails the proxy still runs, it just cannot build
 * packets that need that particular registry.
 */
public final class BackendRuntime {

    private static boolean ready;

    private BackendRuntime() {
    }

    public static synchronized void init() {
        if (ready) {
            return;
        }
        installServerStub();
        // RuntimeItems must come before Item.init(): Item.init() maps every item through it.
        step("Block.init", () -> Block.init());
        step("RuntimeItems.init", () -> RuntimeItems.init());
        step("Item.init", () -> Item.init());
        // The creative menu contents live in a resource the server loads at start-up; the proxy
        // needs them to build the 0.14 creative inventory with the real damage values.
        step("Item.initCreativeItems", () -> Item.initCreativeItems());
        ready = true;
    }

    /** Puts a constructor-less Server in {@code Server.instance} so static initialisers can read it. */
    private static void installServerStub() {
        try {
            Field instanceField = Server.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            if (instanceField.get(null) != null) {
                return;                          // a real server already lives here
            }
            Object stub;
            try {
                // sun.misc.Unsafe.allocateInstance: skips the (heavy) Server constructor and every
                // field initialiser, leaving primitives at their defaults (netEaseMode=false).
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                Object unsafe = theUnsafe.get(null);
                Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
                stub = allocate.invoke(unsafe, Server.class);
            } catch (Throwable t) {
                // Fallback for JDKs that hide Unsafe: a plain reflective instance is impossible,
                // so give up loudly rather than silently producing broken packets.
                System.out.println("[codec] cannot allocate a Server stub: " + t);
                return;
            }
            instanceField.set(null, stub);
            System.out.println("[codec] installed a stub Server instance for the packet codecs");
        } catch (Throwable t) {
            System.out.println("[codec] Server stub failed: " + t);
        }
    }

    private interface Step {
        void run() throws Throwable;
    }

    private static void step(String name, Step s) {
        try {
            s.run();
            System.out.println("[codec] " + name + " ok");
        } catch (Throwable t) {
            System.out.println("[codec] " + name + " failed: " + t);
        }
    }
}
