package proxy.legacy;

/**
 * Receives deframed, ordered, reassembled game payloads from a legacy RakNet session.
 * The buffer is the raw game packet bytes (including any encapsulation marker);
 * interpretation (batch decoding / 0x8e stripping) is the caller's responsibility.
 */
public interface LegacySessionListener {

    /** Called on the reader thread when a complete, in-order game packet arrives. */
    void onGamePacket(LegacySession session, byte[] buffer);

    /** Called when the session reaches the CONNECTED state. */
    default void onConnected(LegacySession session) {
    }

    /** Called when the session is removed (timeout or disconnect). */
    default void onDisconnect(LegacySession session, String reason) {
    }
}
