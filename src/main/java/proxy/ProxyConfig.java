package proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Proxy configuration, loaded from proxy.properties next to the working directory.
 *
 * <pre>
 * listen-port=19132              # the port 0.14.3 clients connect to
 * backend-host=127.0.0.1         # the modern (1.26.50) server the proxy forwards to
 * backend-port=19133
 * </pre>
 */
public final class ProxyConfig {

    public final int listenPort;
    public final InetSocketAddress backend;

    private ProxyConfig(int listenPort, InetSocketAddress backend) {
        this.listenPort = listenPort;
        this.backend = backend;
    }

    public static ProxyConfig load(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        } else {
            // write defaults so the user can edit them
            p.setProperty("listen-port", "19132");
            p.setProperty("backend-host", "127.0.0.1");
            p.setProperty("backend-port", "19133");
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "LegacyProxy configuration");
            }
        }
        int listenPort = Integer.parseInt(p.getProperty("listen-port", "19132").trim());
        String backendHost = p.getProperty("backend-host", "127.0.0.1").trim();
        int backendPort = Integer.parseInt(p.getProperty("backend-port", "19133").trim());
        return new ProxyConfig(listenPort, new InetSocketAddress(backendHost, backendPort));
    }

    public static void main(String[] args) throws IOException {
        ProxyConfig c = load(Paths.get("proxy.properties"));
        System.out.println("listen=" + c.listenPort + " backend=" + c.backend);
    }
}
