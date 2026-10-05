package proxy;

import proxy.legacy.LegacyRakNetServer;

import java.net.InetSocketAddress;
import java.nio.file.Paths;

/**
 * LegacyProxy: a Minecraft Pocket Edition 0.14.3 <-> modern Bedrock protocol bridge.
 *
 * 0.14.3 clients connect to the listen port; the proxy opens a backend connection to the
 * configured modern (Nukkit-MOT 1.26.50) server and translates packets in both directions,
 * filtering out items/blocks/recipes the old client cannot render.
 */
public final class ProxyMain {

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.load(Paths.get("proxy.properties"));
        System.out.println("[proxy] listening on udp/" + config.listenPort + " -> backend " + config.backend);

        // Block translation table: hashed modern block ids -> legacy id+meta.
        ChunkConverter.loadMap("block_hash_map.txt");
        // Exact set of ids the 0.14.3 client knows (from the 0.14.3 server source).
        LegacyIds.load();

        // Boot the backend jar's item/block/runtime-id registries so we can build modern packets
        // (inventory transactions, item descriptors) with the backend's own codecs.
        proxy.modern.BackendRuntime.init();

        // Frontend: RakNet v7 server that 0.14.3 clients connect to.
        LegacyRakNetServer frontend = new LegacyRakNetServer(config.listenPort,
                "MCPE;LegacyProxy;70;0.14.3;0;20", () -> new ProxyClientSession(config.backend, config.requireEncryption));
        frontend.start();
        System.out.println("[proxy] frontend up");

        // Keep the process alive
        Thread.currentThread().join();
    }
}
