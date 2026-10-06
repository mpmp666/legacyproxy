package proxy;

import proxy.modern.BedrockEncryption;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

/**
 * Deterministic self-test for {@link BedrockEncryption}, runnable without a server.
 *
 * <p>It generates a client identity key and a server ephemeral key, builds the same handshake
 * JWT the backend would send (header {@code x5u} = base64 X.509 of the server key, payload
 * {@code salt}), and derives two cipher instances from the mirrored inputs. If both sides agree
 * on the key, a packet encrypted by one must decrypt (and checksum-verify) on the other.
 *
 * <p>The interesting cases are the transport failures: the payload is an AES stream, so a lost
 * or duplicated datagram shifts every later packet. The test drops one packet and replays
 * another, and requires the resynchroniser to recover both.
 *
 * <p>Run: {@code java -cp build/classes/java/main proxy.SelfTest} — exits non-zero on failure.
 */
public final class SelfTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair clientIdentity = generator.generateKeyPair();
        KeyPair serverEphemeral = generator.generateKeyPair();

        byte[] salt = new byte[16];
        new Random(20241006L).nextBytes(salt);

        // Client side: parses the server's handshake and agrees with the server's private key.
        BedrockEncryption client = BedrockEncryption.fromHandshakeJwt(
                jwt(serverEphemeral.getPublic().getEncoded(), salt), clientIdentity.getPrivate());
        // Mirrored "server" side: ECDH(serverPrivate, clientIdentityPublic) over the same salt.
        BedrockEncryption server = BedrockEncryption.fromHandshakeJwt(
                jwt(clientIdentity.getPublic().getEncoded(), salt), serverEphemeral.getPrivate());

        byte[] p1 = payload(0x01, "hello encrypted world");
        byte[] c1 = server.encrypt(p1);
        check("handshake key agreement + round trip", Arrays.equals(p1, client.decrypt(c1)));

        byte[] p2 = payload(0x02, "this datagram gets lost in transit");
        byte[] p3 = payload(0x03, "must still decrypt after the loss");
        byte[] c2 = server.encrypt(p2);
        byte[] c3 = server.encrypt(p3);
        // c2 is dropped by the network: the client never sees it.
        check("recovers a packet after a lost datagram", Arrays.equals(p3, client.decrypt(c3)));

        byte[] p4 = payload(0x04, "duplicated datagram");
        byte[] c4 = server.encrypt(p4);
        check("decrypts normally after recovery", Arrays.equals(p4, client.decrypt(c4)));
        check("ignores a retransmitted datagram", Arrays.equals(p4, client.decrypt(c4)));

        // Two packets that swap places in the network: the later one is decrypted first, which
        // makes the cipher seek forward, and the earlier one then has to seek back.
        byte[] p5 = payload(0x05, "arrives out of order: this packet is late");
        byte[] p6 = payload(0x06, "overtakes the packet before it");
        byte[] c5 = server.encrypt(p5);
        byte[] c6 = server.encrypt(p6);
        check("recovers a packet that overtook another", Arrays.equals(p6, client.decrypt(c6)));
        check("recovers the packet that arrived late", Arrays.equals(p5, client.decrypt(c5)));

        byte[] p7 = payload(0x07, "stream stays usable afterwards");
        check("stream continues after recovery", Arrays.equals(p7, client.decrypt(server.encrypt(p7))));

        // A corrupted packet must be reported, never silently accepted.
        byte[] c8 = server.encrypt(payload(0x08, "corrupted"));
        c8[c8.length - 1] ^= 0x40;
        boolean rejected = false;
        try {
            client.decrypt(c8);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        check("rejects a packet that matches no offset", rejected);

        System.out.println((failures == 0 ? "SELFTEST OK" : "SELFTEST FAILED") + " (" + checks
                + " checks, " + failures + " failed)");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** Builds the ServerToClientHandshake JWT the backend sends; the signature is not verified. */
    private static String jwt(byte[] serverPublicX509, byte[] salt) {
        String header = "{\"alg\":\"ES384\",\"x5u\":\""
                + Base64.getEncoder().encodeToString(serverPublicX509) + "\"}";
        String body = "{\"salt\":\"" + Base64.getEncoder().encodeToString(salt) + "\"}";
        return b64url(header) + "." + b64url(body) + "." + b64url("not-verified");
    }

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A plausible batch payload: the compression prefix byte followed by a real raw-deflate
     * stream. The resynchroniser recognises packet boundaries by trying to inflate them, so the
     * test data has to look like what the backend actually sends.
     */
    private static byte[] payload(int id, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[body.length + 1];
        raw[0] = (byte) id;
        System.arraycopy(body, 0, raw, 1, body.length);

        java.util.zip.Deflater deflater =
                new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(raw);
        deflater.finish();
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[512];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            compressed.write(buf, 0, n);
        }
        deflater.end();

        byte[] packed = compressed.toByteArray();
        byte[] out = new byte[packed.length + 1];
        out[0] = 0x00;                                   // ZLIB compression prefix
        System.arraycopy(packed, 0, out, 1, packed.length);
        return out;
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "[ok]   " : "[FAIL] ") + what);
    }
}
