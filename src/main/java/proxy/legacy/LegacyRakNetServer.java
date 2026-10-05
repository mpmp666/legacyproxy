package proxy.legacy;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UDP server for the legacy RakNet v6 protocol (MCPE 0.14.x). Runs a dedicated
 * socket + reader thread + tick thread, independent of the modern (cloudburst)
 * RakNet stack. Answers offline server-list pings and hosts {@link LegacySession}s.
 */
public final class LegacyRakNetServer {

    /** Creates the game-layer listener for a newly connected session. */
    public interface ListenerFactory {
        LegacySessionListener create();
    }

    private final int port;
    private final String motd;
    private final ListenerFactory listenerFactory;
    private final long serverGuid = new Random().nextLong() & Long.MAX_VALUE;

    private final Map<InetSocketAddress, LegacySession> sessions = new ConcurrentHashMap<>();

    private DatagramSocket socket;
    private Thread readerThread;
    private Thread tickThread;
    private volatile boolean running = false;

    public LegacyRakNetServer(int port, String motd, ListenerFactory listenerFactory) {
        this.port = port;
        this.motd = motd;
        this.listenerFactory = listenerFactory;
    }

    public int getPort() {
        return port;
    }

    public void start() throws SocketException {
        socket = new DatagramSocket(port);
        socket.setReceiveBufferSize(1024 * 1024);
        running = true;
        readerThread = new Thread(this::readLoop, "LegacyRakNet-Reader-" + port);
        readerThread.setDaemon(true);
        readerThread.start();
        tickThread = new Thread(this::tickLoop, "LegacyRakNet-Tick-" + port);
        tickThread.setDaemon(true);
        tickThread.start();
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void readLoop() {
        byte[] buffer = new byte[65535];
        while (running) {
            DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(pkt);
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            byte[] data = new byte[pkt.getLength()];
            System.arraycopy(pkt.getData(), pkt.getOffset(), data, 0, pkt.getLength());
            InetSocketAddress sender = (InetSocketAddress) pkt.getSocketAddress();
            try {
                dispatch(data, sender);
            } catch (Throwable t) {
                // A single malformed datagram must never kill the reader thread,
                // otherwise the whole legacy listener silently stops answering.
                System.out.println("[legacy] dropped malformed datagram from " + sender + ": " + t);
                if (LegacyConstants.DEBUG_SESSION) {
                    StringBuilder hx = new StringBuilder();
                    for (int i = 0; i < Math.min(data.length, 64); i++) hx.append(Integer.toHexString(data[i] & 0xff)).append(' ');
                    System.out.println("[legacy]   raw(" + data.length + "): " + hx);
                    t.printStackTrace(System.out);
                }
            }
        }
    }

    private void tickLoop() {
        while (running) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
            for (LegacySession s : sessions.values()) {
                s.tick();
            }
        }
    }

    private void dispatch(byte[] data, InetSocketAddress sender) {
        dispatch(data, sender, false);
    }

    private void dispatch(byte[] data, InetSocketAddress sender, boolean viaShared) {
        if (viaShared) {
            sharedAddrs.add(sender);
        }
        if (data.length == 0) {
            return;
        }
        int id = data[0] & 0xff;
        if (id == LegacyConstants.ID_UNCONNECTED_PING || id == LegacyConstants.ID_UNCONNECTED_PING_OPEN_CONNECTIONS) {
            handleUnconnectedPing(data, sender);
            return;
        }
        if (id == LegacyConstants.ID_OPEN_CONNECTION_REQUEST_1) {
            getOrCreateSession(sender).handleDatagram(data);
            return;
        }
        LegacySession s = sessions.get(sender);
        if (s == null) {
            // Fallback: same host, different source port. Only trust it for handshake-phase
            // packets 鈥?a fresh client from the same IP must never steal an existing
            // session's address (that is exactly what happened with two local test clients).
            int pktId = data[0] & 0xff;
            boolean handshakeOnly = pktId == LegacyConstants.ID_OPEN_CONNECTION_REQUEST_2;
            if (handshakeOnly) {
                LegacySession byIp = findByHost(sender);
                if (byIp != null) {
                    InetSocketAddress old = byIp.address;
                    sessions.remove(old);
                    byIp.rebind(sender);
                    sessions.put(sender, byIp);
                    System.out.println("[legacy] session rebound " + old + " -> " + sender);
                    s = byIp;
                }
            }
            if (s == null) {
                if (LegacyConstants.DEBUG_SESSION) {
                    System.out.println("[legacy] no session for " + sender + " id=0x"
                            + Integer.toHexString(data[0] & 0xff) + " len=" + data.length
                            + " sessions=" + sessions.keySet());
                }
                return;
            }
        }
        s.handleDatagram(data);
    }

    private LegacySession findByHost(InetSocketAddress sender) {
        if (sender.getAddress() == null) {
            return null;
        }
        for (LegacySession cand : sessions.values()) {
            if (sender.getAddress().equals(cand.address.getAddress())) {
                return cand;
            }
        }
        return null;
    }

    /**
     * Shared-port mode: when non-null, datagrams bound for a legacy client are written through
     * the server's modern (cloudburst) channel instead of this server's own socket, letting both
     * versions share one UDP port. Set by Server after wiring the Netty divider.
     */
    public volatile java.util.function.BiConsumer<byte[], InetSocketAddress> sharedSender;

    /** Inbound entry point used by the shared-port divider. */
    public void onSharedDatagram(byte[] data, InetSocketAddress sender) {
        try {
            dispatch(data, sender, true);
        } catch (Throwable t) {
            System.out.println("[legacy] shared datagram failed from " + sender + ": " + t);
        }
    }

    /** Per-session routing context: true when the client is using the shared modern port. */
    private final java.util.Set<InetSocketAddress> sharedAddrs =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private void handleUnconnectedPing(byte[] data, InetSocketAddress sender) {
        if (data.length < 25) {
            return;
        }
        LegacyBinary.Reader r = new LegacyBinary.Reader(data, 1);
        long pingId = r.getLong();
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyConstants.ID_UNCONNECTED_PONG);
        w.putLong(pingId);
        w.putLong(serverGuid);
        w.putBytes(LegacyConstants.MAGIC);
        w.putString(motd);
        sendRaw(w.toByteArray(), sender);
    }

    private LegacySession getOrCreateSession(InetSocketAddress sender) {
        // OPEN_CONNECTION_REQUEST_1 is always the start of a NEW handshake. A device that
        // reconnects from the same address must not inherit the previous session's windows,
        // sequence numbers or split buffers, so we always build a fresh session here.
        sessions.remove(sender);
        LegacySession created = new LegacySession(this, sender, serverGuid,
                listenerFactory == null ? null : listenerFactory.create());
        sessions.put(sender, created);
        return created;
    }

    void sendRaw(byte[] data, InetSocketAddress address) {
        // shared-port mode: reply from the client's own entry point (the cloudburst channel),
        // not from the legacy socket, otherwise the client rejects the reply as unsolicited.
        java.util.function.BiConsumer<byte[], InetSocketAddress> shared = sharedSender;
        if (shared != null && sharedAddrs.contains(address)) {
            try {
                shared.accept(data, address);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.send(new DatagramPacket(data, data.length, address));
        } catch (IOException ignored) {
        }
    }

    void removeSession(LegacySession session) {
        sessions.remove(session.address);
    }

    public int getSessionCount() {
        return sessions.size();
    }
}
