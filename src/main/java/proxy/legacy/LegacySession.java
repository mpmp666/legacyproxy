package proxy.legacy;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A single RakNet protocol v6 session (MCPE 0.14.x). Implements the offline handshake,
 * encapsulated frame reliability (ACK/NACK + retransmit), reliable ordering by message
 * index, and split-packet reassembly.
 *
 * Threading: {@link #handleDatagram(byte[])} and {@link #tick()} are synchronized on this
 * session; game-layer sends enqueue into a lock-free queue drained by {@link #tick()}.
 */
public final class LegacySession {

    private static final int WINDOW_SIZE = 2048;
    private static final int MAX_SPLIT_SIZE = 128;
    private static final int MAX_SPLIT_COUNT = 4;
    /**
     * How long a 0.14 client may stay silent before its session is dropped. A real client pings
     * every couple of seconds and sends a DisconnectionNotification when it quits, so this only
     * covers clients that vanish without saying goodbye. It used to be 60s, which meant the
     * backend kept the player in the world for up to a minute after the 0.14 client was gone —
     * modern players saw a ghost standing next to them.
     */
    private static final long TIMEOUT_MS = 15_000L;
    private static final long RESEND_MS = 8_000L;
    private static final int MAX_MTU = 1464;
    /** Frames drained from the pending queue per tick (paces large bursts). */
    private static final int MAX_FRAMES_PER_TICK = 6;
    /**
     * Outbound datagrams actually handed to the socket per tick. A large chunk is split into
     * ~60 fragments; releasing them all at once overflows a phone's UDP receive buffer (the
     * simulated client on loopback has a 4 MB buffer and never noticed). 16 per 10 ms tick
     * is ~1.6 MB/s, fast enough to stream the world without dropping.
     */
    private static final int MAX_DATAGRAMS_PER_TICK = 16;
    /** Upper bound on datagrams waiting in the paced outbound queue before we stop taking on more. */
    private static final int MAX_PACED_BACKLOG = 96;

    public volatile InetSocketAddress address;
    public final long serverGuid;
    final LegacyRakNetServer manager;
    private final LegacySessionListener listener;

    private int state = LegacyConstants.STATE_UNCONNECTED;
    private int mtuSize = 548;
    private long clientGuid = -1;
    private int splitId = 0;

    private int sendSeqNumber = 0;
    private int messageIndex = 0;
    private final int[] channelIndex = new int[32];

    private int lastSeqNumber = -1;
    private int windowStart = -1;
    private int windowEnd = WINDOW_SIZE;

    private int lastReliableIndex = -1;
    private int reliableWindowStart = 0;
    private int reliableWindowEnd = WINDOW_SIZE;

    private long lastUpdate = System.currentTimeMillis();
    private boolean active = false;

    // receive side
    private final TreeSet<Integer> ackQueue = new TreeSet<>();
    private final TreeSet<Integer> nackQueue = new TreeSet<>();
    private final TreeSet<Integer> receivedWindow = new TreeSet<>();
    private final TreeMap<Integer, LegacyEncapsulatedPacket> reliableWindow = new TreeMap<>();
    private final Map<Integer, Map<Integer, LegacyEncapsulatedPacket>> splitPackets = new HashMap<>();

    // send side
    private final Map<Integer, byte[]> recoveryQueue = new HashMap<>();
    private final List<byte[]> packetToSend = new ArrayList<>();
    /** Paced outbound datagrams: drained a few per tick instead of bursting. */
    private final ConcurrentLinkedQueue<byte[]> pacedOut = new ConcurrentLinkedQueue<>();
    private final List<LegacyEncapsulatedPacket> sendQueue = new ArrayList<>();
    private int sendQueueLength = 0;
    private final ConcurrentLinkedQueue<LegacyEncapsulatedPacket> pendingFrames = new ConcurrentLinkedQueue<>();

    private boolean closed = false;

    public LegacySession(LegacyRakNetServer manager, InetSocketAddress address, long serverGuid, LegacySessionListener listener) {
        this.manager = manager;
        this.address = address;
        this.serverGuid = serverGuid;
        this.listener = listener;
    }

    public int getState() {
        return state;
    }

    /**
     * Re-point this session at a new transport address. Some clients (or NAT/proxy
     * layers in front of them) send later datagrams from a different source port than
     * the handshake used; without this the follow-up packets would be dropped.
     */
    public synchronized void rebind(InetSocketAddress newAddress) {
        this.address = newAddress;
        this.active = true;
        this.lastUpdate = System.currentTimeMillis();
    }

    public long getClientGuid() {
        return clientGuid;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Enqueue a game payload to be sent (reliable ordered, channel 0). Thread-safe. */
    public void sendGameData(byte[] payload) {
        // Safety net: a 0.14 game payload must start with the 0x8e encapsulation marker, otherwise
        // the client reads pid from byte[1] and drops the frame. Every call site is expected to
        // have wrapped it already; shout loudly if one did not.
        if (payload.length > 0 && (payload[0] & 0xff) != 0x8e) {
            debug("WARNING: sending an UNWRAPPED 0.14 game payload (no 0x8e marker), head="
                    + Integer.toHexString(payload[0] & 0xff) + " len=" + payload.length);
        }
        LegacyEncapsulatedPacket p = new LegacyEncapsulatedPacket();
        p.reliability = LegacyConstants.RELIABILITY_RELIABLE_ORDERED;
        p.orderChannel = 0;
        p.buffer = payload;
        if (LegacyConstants.DEBUG_SESSION && payload.length > 0) {
            StringBuilder hx = new StringBuilder();
            for (int i = 0; i < Math.min(payload.length, 16); i++) hx.append(Integer.toHexString(payload[i] & 0xff)).append(' ');
            debug("SEND game packet id=0x" + Integer.toHexString(payload[0] & 0xff) + " len=" + payload.length + " head=" + hx);
        }
        pendingFrames.offer(p);
    }

    // ---------------------------------------------------------------------
    // Inbound
    // ---------------------------------------------------------------------

    public synchronized void handleDatagram(byte[] data) {
        if (closed || data.length == 0) {
            return;
        }
        active = true;
        lastUpdate = System.currentTimeMillis();

        int id = data[0] & 0xff;
        if (id != LegacyConstants.ID_ACK && id != LegacyConstants.ID_NACK) {
            debug("datagram id=0x" + Integer.toHexString(id) + " len=" + data.length + " state=" + state);
        }
        if (state == LegacyConstants.STATE_CONNECTED || state == LegacyConstants.STATE_CONNECTING_2) {
            if (id >= 0x80 && id <= 0x8f) {
                handleDataPacket(data);
            } else if (id == LegacyConstants.ID_ACK) {
                handleAck(data);
            } else if (id == LegacyConstants.ID_NACK) {
                handleNack(data);
            }
        } else if (id > 0x00 && id < 0x80) {
            handleOffline(data, id);
        }
    }

    private void debug(String msg) {
        if (LegacyConstants.DEBUG_SESSION) {
            System.out.println("[legacy-session:" + address.getPort() + "] " + msg);
        }
    }

    private void handleOffline(byte[] data, int id) {
        LegacyBinary.Reader r = new LegacyBinary.Reader(data, 1);
        if (id == LegacyConstants.ID_OPEN_CONNECTION_REQUEST_1) {
            r.get(16); // magic
            int protocol = r.getByte();
            int mtu = r.remaining() + 18;
            debug("OPEN_CONNECTION_REQUEST_1 protocol=" + protocol + " mtu=" + mtu);
            // reply 1
            LegacyBinary.Writer w = new LegacyBinary.Writer();
            w.putByte(LegacyConstants.ID_OPEN_CONNECTION_REPLY_1);
            w.putBytes(LegacyConstants.MAGIC);
            w.putLong(serverGuid);
            w.putByte(0); // no security
            w.putShort(mtu);
            manager.sendRaw(w.toByteArray(), address);
            state = LegacyConstants.STATE_CONNECTING_1;
        } else if (state == LegacyConstants.STATE_CONNECTING_1 && id == LegacyConstants.ID_OPEN_CONNECTION_REQUEST_2) {
            r.get(16); // magic
            int[] port = new int[1];
            r.getAddress(port);
            int mtu = r.getShort();
            clientGuid = r.getLong();
            debug("OPEN_CONNECTION_REQUEST_2 mtu=" + mtu + " clientGuid=" + clientGuid);
            mtuSize = Math.min(Math.abs(mtu), MAX_MTU);
            // reply 2
            LegacyBinary.Writer w = new LegacyBinary.Writer();
            w.putByte(LegacyConstants.ID_OPEN_CONNECTION_REPLY_2);
            w.putBytes(LegacyConstants.MAGIC);
            w.putLong(serverGuid);
            w.putAddress(address.getHostString(), address.getPort());
            w.putShort(mtuSize);
            w.putByte(0); // no security
            manager.sendRaw(w.toByteArray(), address);
            state = LegacyConstants.STATE_CONNECTING_2;
        }
    }

    private void handleDataPacket(byte[] data) {
        int seq = LegacyEncapsulatedPacket.readLTriadAt(data, 1);
        if (seq < windowStart || seq > windowEnd || receivedWindow.contains(seq)) {
            debug("DROP data packet seq=" + seq + " window=" + windowStart + ".." + windowEnd
                    + " alreadySeen=" + receivedWindow.contains(seq));
            return;
        }
        int diff = seq - lastSeqNumber;
        nackQueue.remove(seq);
        ackQueue.add(seq);
        receivedWindow.add(seq);
        if (diff != 1) {
            for (int i = lastSeqNumber + 1; i < seq; i++) {
                if (!receivedWindow.contains(i)) {
                    nackQueue.add(i);
                }
            }
        }
        if (diff >= 1) {
            lastSeqNumber = seq;
            windowStart += diff;
            windowEnd += diff;
        }

        int off = 4;
        while (off < data.length) {
            int[] consumed = new int[1];
            LegacyEncapsulatedPacket p = LegacyEncapsulatedPacket.decode(data, off, consumed);
            if (consumed[0] <= 0 || p.buffer.length == 0) {
                break;
            }
            off += consumed[0];
            handleEncapsulatedPacket(p);
        }
    }

    private void handleEncapsulatedPacket(LegacyEncapsulatedPacket packet) {
        if (packet.messageIndex == null) {
            handleEncapsulatedPacketRoute(packet);
            return;
        }
        if (packet.messageIndex < reliableWindowStart || packet.messageIndex > reliableWindowEnd) {
            return;
        }
        if (packet.messageIndex - lastReliableIndex == 1) {
            lastReliableIndex++;
            reliableWindowStart++;
            reliableWindowEnd++;
            handleEncapsulatedPacketRoute(packet);
            if (!reliableWindow.isEmpty()) {
                while (true) {
                    Integer next = reliableWindow.ceilingKey(lastReliableIndex + 1);
                    if (next == null || next - lastReliableIndex != 1) {
                        break;
                    }
                    LegacyEncapsulatedPacket pk = reliableWindow.remove(next);
                    lastReliableIndex++;
                    reliableWindowStart++;
                    reliableWindowEnd++;
                    handleEncapsulatedPacketRoute(pk);
                }
            }
        } else {
            reliableWindow.put(packet.messageIndex, packet);
        }
    }

    private void handleEncapsulatedPacketRoute(LegacyEncapsulatedPacket packet) {
        if (packet.hasSplit) {
            if (state == LegacyConstants.STATE_CONNECTED) {
                handleSplit(packet);
            }
            return;
        }
        if (packet.buffer.length == 0) {
            return;
        }
        int id = packet.buffer[0] & 0xff;
        if (id < 0x80) {
            debug("encapsulated id=0x" + Integer.toHexString(id) + " rel=" + packet.reliability + " len=" + packet.buffer.length);
        }
        if (id < 0x80) {
            if (state == LegacyConstants.STATE_CONNECTING_2) {
                if (id == LegacyConstants.ID_CONNECTION_REQUEST) {
                    // CLIENT_CONNECT: respond SERVER_HANDSHAKE
                    LegacyBinary.Reader r = new LegacyBinary.Reader(packet.buffer, 1);
                    long clientId = r.getLong();
                    long sendPing = r.getLong();
                    LegacyBinary.Writer w = new LegacyBinary.Writer();
                    w.putByte(LegacyConstants.ID_CONNECTION_REQUEST_ACCEPTED);
                    w.putAddress(address.getHostString(), address.getPort());
                    w.putShort(0);
                    // 10 internal addresses; first is loopback (matches Genisys SERVER_HANDSHAKE)
                    w.putAddress("127.0.0.1", 0);
                    for (int i = 1; i < 10; i++) {
                        w.putAddress("0.0.0.0", 0);
                    }
                    w.putLong(sendPing);
                    w.putLong(sendPing + 1000);
                    LegacyEncapsulatedPacket reply = new LegacyEncapsulatedPacket();
                    reply.reliability = LegacyConstants.RELIABILITY_UNRELIABLE;
                    reply.buffer = w.toByteArray();
                    debug("send SERVER_HANDSHAKE len=" + reply.buffer.length + " clientAddr=" + address.getHostString() + ":" + address.getPort());
                    addToQueue(reply, true);
                } else if (id == LegacyConstants.ID_NEW_INCOMING_CONNECTION) {
                    state = LegacyConstants.STATE_CONNECTED;
                    if (listener != null) {
                        listener.onConnected(this);
                    }
                }
            } else if (id == LegacyConstants.ID_DISCONNECTION_NOTIFICATION) {
                close("client disconnect");
            } else if (id == LegacyConstants.ID_CONNECTED_PING) {
                LegacyBinary.Reader r = new LegacyBinary.Reader(packet.buffer, 1);
                long pingId = r.getLong();
                LegacyBinary.Writer w = new LegacyBinary.Writer();
                w.putByte(LegacyConstants.ID_CONNECTED_PONG);
                w.putLong(pingId);
                LegacyEncapsulatedPacket reply = new LegacyEncapsulatedPacket();
                reply.reliability = LegacyConstants.RELIABILITY_UNRELIABLE;
                reply.buffer = w.toByteArray();
                addToQueue(reply, false);
            }
        } else if (state == LegacyConstants.STATE_CONNECTED) {
            if (listener != null) {
                if (LegacyConstants.DEBUG_SESSION && packet.buffer.length > 1) {
                    int pid = packet.buffer[0] == (byte) 0x8e ? (packet.buffer.length > 1 ? packet.buffer[1] & 0xff : 0) : (packet.buffer[0] & 0xff);
                    StringBuilder hx = new StringBuilder();
                    for (int i = 0; i < Math.min(packet.buffer.length, 16); i++) hx.append(Integer.toHexString(packet.buffer[i] & 0xff)).append(' ');
                    debug("RECV game packet id=0x" + Integer.toHexString(pid) + " len=" + packet.buffer.length + " head=" + hx);
                }
                listener.onGamePacket(this, packet.buffer);
            }
        }
    }

    private void handleSplit(LegacyEncapsulatedPacket packet) {
        if (packet.splitCount == null || packet.splitCount >= MAX_SPLIT_SIZE
                || packet.splitIndex == null || packet.splitIndex < 0 || packet.splitIndex >= MAX_SPLIT_SIZE) {
            return;
        }
        Map<Integer, LegacyEncapsulatedPacket> parts = splitPackets.computeIfAbsent(packet.splitId, k -> new HashMap<>());
        if (parts.isEmpty() && splitPackets.size() >= MAX_SPLIT_COUNT) {
            return;
        }
        parts.put(packet.splitIndex, packet);
        if (parts.size() == packet.splitCount) {
            LegacyEncapsulatedPacket merged = new LegacyEncapsulatedPacket();
            merged.reliability = packet.reliability;
            int total = 0;
            for (int i = 0; i < packet.splitCount; i++) {
                total += parts.get(i).buffer.length;
            }
            byte[] buf = new byte[total];
            int pos = 0;
            for (int i = 0; i < packet.splitCount; i++) {
                byte[] b = parts.get(i).buffer;
                System.arraycopy(b, 0, buf, pos, b.length);
                pos += b.length;
            }
            merged.buffer = buf;
            splitPackets.remove(packet.splitId);
            handleEncapsulatedPacketRoute(merged);
        }
    }

    private void handleAck(byte[] data) {
        for (int seq : decodeAcknowledge(data)) {
            recoveryQueue.remove(seq);
        }
    }

    private void handleNack(byte[] data) {
        for (int seq : decodeAcknowledge(data)) {
            byte[] pk = recoveryQueue.remove(seq);
            if (pk != null) {
                int newSeq = sendSeqNumber++;
                writeSeqNumber(pk, newSeq);
                packetToSend.add(pk);
            }
        }
    }

    private List<Integer> decodeAcknowledge(byte[] data) {
        List<Integer> out = new ArrayList<>();
        LegacyBinary.Reader r = new LegacyBinary.Reader(data, 1);
        int count = r.getShort();
        int cnt = 0;
        for (int i = 0; i < count && !r.eof() && cnt < 4096; i++) {
            if (r.getByte() == 0) {
                int start = r.getLTriad();
                int end = r.getLTriad();
                if (end - start > 512) {
                    end = start + 512;
                }
                for (int c = start; c <= end; c++) {
                    out.add(c);
                    cnt++;
                }
            } else {
                out.add(r.getLTriad());
                cnt++;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Outbound
    // ---------------------------------------------------------------------

    private void addToQueue(LegacyEncapsulatedPacket packet, boolean immediate) {
        if (immediate) {
            LegacyBinary.Writer w = new LegacyBinary.Writer();
            w.putByte(LegacyConstants.ID_DATA_PACKET_0);
            int seq = sendSeqNumber++;
            w.putLTriad(seq);
            w.putBytes(packet.encode());
            byte[] bytes = w.toByteArray();
            // paced: released by tick() a few datagrams at a time
            pacedOut.offer(bytes);
            recoveryQueue.put(seq, bytes);
            return;
        }
        int len = packet.getTotalLength();
        if (sendQueueLength + len > mtuSize) {
            flushSendQueue();
        }
        sendQueue.add(packet);
        sendQueueLength += len;
    }

    private void flushSendQueue() {
        if (sendQueue.isEmpty()) {
            return;
        }
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyConstants.ID_DATA_PACKET_4);
        int seq = sendSeqNumber++;
        w.putLTriad(seq);
        for (LegacyEncapsulatedPacket p : sendQueue) {
            w.putBytes(p.encode());
        }
        byte[] bytes = w.toByteArray();
        debug("SEND data seq=" + seq + " len=" + bytes.length + " frames=" + sendQueue.size());
        pacedOut.offer(bytes);
        recoveryQueue.put(seq, bytes);
        sendQueue.clear();
        sendQueueLength = 0;
    }

    private static void writeSeqNumber(byte[] data, int seq) {
        data[1] = (byte) (seq & 0xff);
        data[2] = (byte) ((seq >>> 8) & 0xff);
        data[3] = (byte) ((seq >>> 16) & 0xff);
    }

    // ---------------------------------------------------------------------
    // Tick (timeout, ACK/NACK flush, resend)
    // ---------------------------------------------------------------------

    public synchronized void tick() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!active && (now - lastUpdate) > TIMEOUT_MS) {
            close("timeout");
            return;
        }
        active = false;

        if (!ackQueue.isEmpty()) {
            LegacyBinary.Writer w = new LegacyBinary.Writer();
            w.putByte(LegacyConstants.ID_ACK);
            encodeAcknowledge(w, ackQueue);
            manager.sendRaw(w.toByteArray(), address);
            ackQueue.clear();
        }
        if (!nackQueue.isEmpty()) {
            LegacyBinary.Writer w = new LegacyBinary.Writer();
            w.putByte(LegacyConstants.ID_NACK);
            encodeAcknowledge(w, nackQueue);
            manager.sendRaw(w.toByteArray(), address);
            nackQueue.clear();
        }

        // Drain pending game frames into the send queue. Backpressure: only take on another
        // frame while the paced outbound queue is nearly drained, otherwise a burst of large
        // chunks would pile up unboundedly in memory.
        LegacyEncapsulatedPacket p;
        int drained = 0;
        while (drained < MAX_FRAMES_PER_TICK && pacedOut.size() < MAX_PACED_BACKLOG
                && (p = pendingFrames.poll()) != null) {
            addEncapsulatedToQueue(p);
            drained++;
        }

        // Release paced outbound datagrams: a few per tick so a phone's UDP receive buffer
        // never overflows. Everything queued here is already in recoveryQueue for NACK resend.
        int budget = MAX_DATAGRAMS_PER_TICK;
        byte[] out;
        while (budget > 0 && (out = pacedOut.poll()) != null) {
            manager.sendRaw(out, address);
            budget--;
        }

        // NACK retransmits share the same budget. Unsent entries stay queued for the next
        // tick — the old code cleared the list and then broke out of the loop, silently
        // throwing away every datagram past the limit.
        List<byte[]> toSend = new ArrayList<>(packetToSend);
        packetToSend.clear();
        for (byte[] bytes : toSend) {
            if (budget <= 0) {
                packetToSend.add(bytes);
                continue;
            }
            manager.sendRaw(bytes, address);
            int seq = LegacyEncapsulatedPacket.readLTriadAt(bytes, 1);
            recoveryQueue.put(seq, bytes);
            budget--;
        }

        // resend stale recovery entries
        long resendCutoff = now - RESEND_MS;
        for (Map.Entry<Integer, byte[]> e : recoveryQueue.entrySet()) {
            byte[] bytes = e.getValue();
            // we track send time implicitly via lastUpdate; simpler: resend entries
            // are only retriggered by NACK. Skip time-based resend here to avoid
            // needing a send-timestamp map for now (client NACKs cover loss).
        }

        // prune receivedWindow below windowStart
        receivedWindow.headSet(windowStart, false).clear();

        flushSendQueue();
    }

    private void addEncapsulatedToQueue(LegacyEncapsulatedPacket packet) {
        if (packet.reliability == LegacyConstants.RELIABILITY_RELIABLE
                || packet.reliability == LegacyConstants.RELIABILITY_RELIABLE_ORDERED
                || packet.reliability == LegacyConstants.RELIABILITY_RELIABLE_SEQUENCED) {
            packet.messageIndex = messageIndex++;
            if (packet.reliability == LegacyConstants.RELIABILITY_RELIABLE_ORDERED) {
                int ch = packet.orderChannel == null ? 0 : packet.orderChannel;
                packet.orderIndex = channelIndex[ch]++;
            }
        }
        if (packet.getTotalLength() + 4 > mtuSize) {
            int chunkSize = mtuSize - 34;
            int count = (packet.buffer.length + chunkSize - 1) / chunkSize;
            int sid = ++splitId % 65536;
            for (int i = 0; i < count; i++) {
                int from = i * chunkSize;
                int to = Math.min(from + chunkSize, packet.buffer.length);
                LegacyEncapsulatedPacket sp = new LegacyEncapsulatedPacket();
                sp.hasSplit = true;
                sp.splitId = sid;
                sp.splitCount = count;
                sp.splitIndex = i;
                sp.reliability = packet.reliability;
                sp.buffer = new byte[to - from];
                System.arraycopy(packet.buffer, from, sp.buffer, 0, to - from);
                sp.messageIndex = (i == 0) ? packet.messageIndex : messageIndex++;
                if (packet.reliability == LegacyConstants.RELIABILITY_RELIABLE_ORDERED) {
                    sp.orderChannel = packet.orderChannel;
                    sp.orderIndex = packet.orderIndex;
                }
                addToQueue(sp, true);
            }
        } else {
            addToQueue(packet, false);
        }
    }

    private void encodeAcknowledge(LegacyBinary.Writer w, TreeSet<Integer> packets) {
        // build ranges
        List<int[]> ranges = new ArrayList<>();
        Integer start = null, last = null;
        for (int seq : packets) {
            if (start == null) {
                start = last = seq;
            } else if (seq - last == 1) {
                last = seq;
            } else {
                ranges.add(new int[]{start, last});
                start = last = seq;
            }
        }
        if (start != null) {
            ranges.add(new int[]{start, last});
        }
        w.putShort(ranges.size());
        for (int[] r : ranges) {
            if (r[0] == r[1]) {
                w.putByte(1);
                w.putLTriad(r[0]);
            } else {
                w.putByte(0);
                w.putLTriad(r[0]);
                w.putLTriad(r[1]);
            }
        }
    }

    /**
     * Pushes every queued game frame out right now.
     *
     * <p>Outbound frames are normally queued ({@code pendingFrames} → {@code sendQueue} →
     * {@code pacedOut}) and released a few datagrams per tick, so a packet queued immediately
     * before {@link #close(String)} would never leave: the session is torn down before its turn
     * comes. A deliberate disconnect — the backend kicked us, or the backend connection died —
     * has to deliver its reason first, otherwise the old client just sits in a dead world.
     */
    public synchronized void flushNow() {
        LegacyEncapsulatedPacket p;
        while ((p = pendingFrames.poll()) != null) {
            addEncapsulatedToQueue(p);
        }
        flushSendQueue();
        byte[] out;
        while ((out = pacedOut.poll()) != null) {
            manager.sendRaw(out, address);
        }
    }

    public synchronized void close(String reason) {        if (closed) {
            return;
        }
        closed = true;
        if (LegacyConstants.DEBUG_SESSION) {
            StringBuilder sb = new StringBuilder("[legacy] close(" + reason + ") session=" + address + " from:");
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String cn = e.getClassName();
                if (cn.startsWith("cn.nukkit")) {
                    sb.append(" ").append(cn.substring(cn.lastIndexOf('.') + 1)).append(":").append(e.getLineNumber());
                }
            }
            System.out.println(sb);
        }
        // send disconnect notification
        LegacyEncapsulatedPacket p = new LegacyEncapsulatedPacket();
        p.reliability = LegacyConstants.RELIABILITY_UNRELIABLE;
        p.buffer = new byte[]{LegacyConstants.ID_DISCONNECTION_NOTIFICATION};
        LegacyBinary.Writer w = new LegacyBinary.Writer();
        w.putByte(LegacyConstants.ID_DATA_PACKET_0);
        int seq = sendSeqNumber++;
        w.putLTriad(seq);
        w.putBytes(p.encode());
        manager.sendRaw(w.toByteArray(), address);
        manager.removeSession(this);
        if (listener != null) {
            listener.onDisconnect(this, reason);
        }
    }
}
