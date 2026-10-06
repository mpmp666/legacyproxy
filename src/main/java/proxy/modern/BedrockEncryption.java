package proxy.modern;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.Inflater;

/**
 * Client side of Bedrock's connection encryption ("ServerToClientHandshake").
 *
 * <p>The algorithm below is transcribed from the server implementation in Nukkit-MOT, so it is
 * exact rather than guessed:
 *
 * <ul>
 *   <li>{@code EncryptionUtils.getSecretKey}: {@code key = SHA-256(salt || ECDH(myPrivate,
 *       serverPublic))} over curve <b>P-384</b>, 32 bytes.</li>
 *   <li>{@code EncryptionUtils.createCipher}: {@code AES/CFB8/NoPadding} with
 *       {@code iv = key[0..16]}; one Cipher per direction whose stream state carries over from
 *       packet to packet (there is no per-packet IV reset).</li>
 *   <li>{@code RakNetPlayerSession.calculateChecksum}:
 *       {@code SHA-256(LE64(counter) || compressedPayload || key)[0..8]}.</li>
 *   <li>{@code RakNetPlayerSession.sendPacket}: the frame is
 *       {@code [0xfe][cipher(prefix || compressedPayload || checksum)]} — the checksum travels
 *       inside the encrypted stream, and it covers only the compressed payload (not the prefix).</li>
 *   <li>{@code RakNetPlayerSession.handleDatagram}: decrypt the whole region, skip the
 *       compression prefix byte, the last 8 bytes are the checksum over the plaintext payload.</li>
 * </ul>
 */
public final class BedrockEncryption {

    /** AES key derived from the handshake; also the SHA-256 trailer input. */
    private final byte[] key;
    private final byte[] iv;
    private final String transformation;
    private Cipher encryptCipher;
    private Cipher decryptCipher;
    private long encryptCounter;
    private long decryptCounter;
    /**
     * Ciphertext bytes consumed so far in the receive direction. The cipher is a stream, so this
     * is what has to be re-seeked to when a datagram is lost or duplicated in transit.
     */
    private long decryptStreamPos;

    private BedrockEncryption(byte[] key, byte[] iv, String transformation,
                              Cipher encryptCipher, Cipher decryptCipher) {
        this.key = key;
        this.iv = iv;
        this.transformation = transformation;
        this.encryptCipher = encryptCipher;
        this.decryptCipher = decryptCipher;
    }

    /**
     * Parses the {@code ServerToClientHandshakePacket} (0x03) JWT and derives the session key.
     *
     * <p>The JWT is only a carrier here: the header's {@code x5u} is the server's ephemeral
     * public key (base64 X.509 DER) and the payload's {@code salt} is the handshake token.
     * The signature is not verified — the client has no trust anchor for an ephemeral key and
     * the server proves possession by being able to decrypt our next packet.
     *
     * <p>{@code identityPrivate} must be the private half of the key that was put into the login
     * chain's {@code identityPublicKey}: the server derives the shared secret with
     * {@code ECDH(serverEphemeralPrivate, clientIdentityPublic)}, so the client has to use the
     * matching identity private key (not a fresh ephemeral one).
     */
    public static BedrockEncryption fromHandshakeJwt(String jwt, java.security.PrivateKey identityPrivate)
            throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("handshake JWT has no payload");
        }
        String header = new String(Base64.getUrlDecoder().decode(pad(parts[0])), StandardCharsets.UTF_8);
        String payload = new String(Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);

        String x5u = jsonString(header, "x5u");
        String saltB64 = jsonString(payload, "salt");
        if (x5u == null || saltB64 == null) {
            throw new IllegalArgumentException("handshake JWT missing x5u/salt: " + header + " / " + payload);
        }
        byte[] salt = Base64.getDecoder().decode(saltB64);
        PublicKey serverKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(x5u)));

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(identityPrivate);
        agreement.doPhase(serverKey, true);
        byte[] sharedSecret = agreement.generateSecret();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        digest.update(sharedSecret);
        byte[] key = digest.digest();

        byte[] iv = new byte[16];
        // EncryptionUtils.createCipher: for protocol > 1.16.210 the backend uses AES/CTR with
        // iv = key[0..12] || 00 00 02; older protocols use AES/CFB8 with iv = key[0..16]. Both are
        // stream modes, so one Cipher per direction carries its state across packets and must not
        // be re-initialised.
        // -Dproxy.enc.cfb8=true forces the legacy CFB8 variant (diagnostic).
        boolean cfb8 = Boolean.getBoolean("proxy.enc.cfb8");
        String transformation;
        if (cfb8) {
            System.arraycopy(key, 0, iv, 0, 16);
            transformation = "AES/CFB8/NoPadding";
        } else {
            System.arraycopy(key, 0, iv, 0, 12);
            iv[15] = 2;
            transformation = "AES/CTR/NoPadding";
        }
        Cipher enc = Cipher.getInstance(transformation);
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        Cipher dec = Cipher.getInstance(transformation);
        dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return new BedrockEncryption(key, iv, transformation, enc, dec);
    }

    /** Base64url without padding (JWT segments omit it). */
    private static String pad(String s) {
        int rem = s.length() % 4;
        if (rem == 0) {
            return s;
        }
        return s + "====".substring(rem);
    }

    /** Minimal extraction of a top-level string field; the JWT segments here are tiny. */
    private static String jsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        return json.substring(open + 1, close);
    }

    /**
     * Encrypts one outbound region: {@code plaintext = [prefix][compressedPayload]}.
     *
     * @return {@code cipher(plaintext || checksum)} — the caller wraps it in the RakNet frame.
     */
    public byte[] encrypt(byte[] prefixAndPayload) {
        // The checksum covers the WHOLE plaintext region including the compression prefix.
        // RakNetPlayerSession.handleDatagram does `buffer.slice(1, trailerIndex - 1)` with
        // trailerIndex = writerIndex - 8, and slice() takes ABSOLUTE indices while the reader
        // index is already 1 (the 0xfe was consumed) — so the slice spans [prefix][payload].
        byte[] trailer = checksum(encryptCounter++, prefixAndPayload, 0, prefixAndPayload.length);
        if (Boolean.getBoolean("proxy.modern.encdebug")) {
            StringBuilder k = new StringBuilder();
            StringBuilder ck = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                k.append(String.format("%02x", key[i]));
                ck.append(String.format("%02x", trailer[i]));
            }
            System.out.println("[enc] key=" + k + " count=" + (encryptCounter - 1)
                    + " ckInput=" + prefixAndPayload.length
                    + " ck=" + ck);
        }
        byte[] region = Arrays.copyOf(prefixAndPayload, prefixAndPayload.length + 8);
        System.arraycopy(trailer, 0, region, prefixAndPayload.length, 8);
        try {
            return encryptCipher.update(region);
        } catch (Exception e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    /**
     * Decrypts one inbound region and verifies its checksum.
     *
     * @return the plaintext {@code [prefix][compressedPayload]} (checksum stripped)
     */
    public byte[] decrypt(byte[] region) {
        // RakNet retransmits datagrams whose ACK we were slow to send, and a retransmitted region
        // is byte-identical: the same plaintext can never appear twice at two stream positions,
        // so an identical region is a repeat. Feeding it to the cipher again would advance the
        // stream a second time and shift every later packet.
        if (lastRegion != null && Arrays.equals(region, lastRegion)) {
            duplicateRegions++;
            return Arrays.copyOf(lastPlain, lastPlain.length);
        }
        byte[] plain;
        try {
            plain = decryptCipher.update(region);
        } catch (Exception e) {
            throw new IllegalStateException("decryption failed", e);
        }
        if (plain.length >= 9 && checksumMatches(plain, decryptCounter)) {
            decryptCounter++;
            decryptStreamPos += region.length;
            remember(region, plain);
            return strip(plain);
        }
        return resynchronise(region);
    }

    /** Number of retransmitted regions recognised and skipped. */
    private long duplicateRegions;
    private byte[] lastRegion;
    private byte[] lastPlain;

    /** Remembers the last accepted region so an identical retransmit can be recognised. */
    private void remember(byte[] region, byte[] plain) {
        this.lastRegion = region;
        this.lastPlain = strip(plain);      // callers get the payload, without the checksum
    }

    /** Strips the trailing checksum, returning {@code [prefix][compressedPayload]}. */
    private static byte[] strip(byte[] plain) {
        return Arrays.copyOf(plain, plain.length - 8);
    }

    /** Verifies the trailing 8 bytes of {@code plain} against {@code SHA-256(LE64(counter)||body||key)}. */
    private boolean checksumMatches(byte[] plain, long counter) {
        int trailer = plain.length - 8;
        byte[] expected = checksum(counter, plain, 0, trailer);
        for (int i = 0; i < 8; i++) {
            if (plain[trailer + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * How far back / forward the resynchroniser looks, in ciphertext bytes. A single missed chunk
     * packet is tens to hundreds of kilobytes, and it can be missed in either direction: a lost
     * datagram leaves the stream behind the server, while a packet that had to wait for a
     * retransmitted split part is decrypted late, i.e. behind the packets that overtook it.
     */
    private static final int RESYNC_BACK = 1 << 19;
    private static final int RESYNC_FORWARD = 1 << 19;
    /**
     * How many packet counters the resynchroniser tries behind the current one (a retransmitted
     * datagram holds several already-seen packets) and ahead of it (a lost datagram holds several
     * packets that never arrive). A 1400-byte datagram can carry a dozen small game packets, so
     * the skip is not bounded by one.
     */
    private static final int RESYNC_COUNTERS_BACK = 64;
    private static final int RESYNC_COUNTERS_FORWARD = 256;
    /**
     * Hard limit on one resynchronisation attempt. The scan is bounded work, but it runs on the
     * read-loop thread: when a session is being torn down (a kick, a shutdown) the remaining
     * packets are undecryptable and burning seconds here means the client is never told why it
     * was disconnected. Giving up quickly lets the loop end and the disconnect be delivered.
     */
    private static final long RESYNC_BUDGET_MS = 3000;

    /**
     * Recovers from a lost or duplicated datagram.
     *
     * <p>AES/CTR is a stream: one missing ciphertext byte shifts every later packet, and the
     * session dies with a checksum error even though nothing is actually corrupt. Both the
     * counter and the stream offset can be recovered, because the trailing checksum is an
     * 8-byte oracle over {@code (counter, plaintext)}: the code generates the keystream around
     * the expected position once, then scans small byte offsets and packet-counter skips until
     * a candidate reproduces its own checksum. On a hit the cipher is re-seeked past the
     * recovered packet, so the session continues instead of dropping.
     */
    private byte[] resynchronise(byte[] region) {
        long base = Math.max(0, decryptStreamPos - RESYNC_BACK);
        int window = (int) (decryptStreamPos + RESYNC_FORWARD + region.length + 16 - base);
        byte[] keystream;
        try {
            Cipher skip = newCipher(Cipher.ENCRYPT_MODE);   // CTR/CFB8 are symmetric
            advance(skip, base);
            keystream = skip.update(new byte[window]);
        } catch (Exception e) {
            throw new IllegalStateException("resync keystream failed", e);
        }
        int firstDelta = (int) (base - decryptStreamPos);
        byte[] candidate = new byte[region.length];
        int examined = 0;
        long deadline = System.currentTimeMillis() + RESYNC_BUDGET_MS;
        for (int delta = firstDelta; delta <= RESYNC_FORWARD; delta++) {
            if ((delta & 0xff) == 0 && System.currentTimeMillis() > deadline) {
                break;                       // out of time: let the caller end the session
            }
            int off = delta - firstDelta;
            if (off < 0 || off + region.length > keystream.length) {
                break;
            }
            // Cheap prefilter, then a very strong one: every compressed batch begins with its
            // compression prefix byte (0x00) and the rest is a raw-deflate stream. A candidate at
            // the wrong offset is essentially random bytes and will not inflate, so this throws
            // away ~all of the wrong offsets for the price of one small inflate, and the expensive
            // counter scan below only runs on the one or two offsets that can actually be right.
            if ((byte) (region[0] ^ keystream[off]) != 0x00) {
                continue;
            }
            for (int i = 0; i < candidate.length; i++) {
                candidate[i] = (byte) (region[i] ^ keystream[off + i]);
            }
            if (candidate.length < 9 || !inflates(candidate, 1, candidate.length - 9)) {
                continue;
            }
            examined++;
            for (int cOff = -RESYNC_COUNTERS_BACK; cOff <= RESYNC_COUNTERS_FORWARD; cOff++) {
                if (decryptCounter + cOff < 0) {
                    continue;
                }
                if (!checksumMatches(candidate, decryptCounter + cOff)) {
                    continue;
                }
                long newPos = decryptStreamPos + delta + region.length;
                decryptCounter += cOff + 1;
                decryptStreamPos = newPos;
                try {
                    decryptCipher = newCipher(Cipher.DECRYPT_MODE);
                    advance(decryptCipher, newPos);
                } catch (Exception e) {
                    throw new IllegalStateException("resync reseek failed", e);
                }
                System.out.println("[enc] RESYNC byteDelta=" + delta + " counterSkip=" + cOff
                        + " recovered packet " + (decryptCounter - 1) + " streamPos=" + newPos);
                remember(region, candidate);
                return strip(candidate);
            }
        }
        throw new IllegalStateException("bad encrypted checksum on packet " + decryptCounter
                + " (unrecoverable: streamPos=" + decryptStreamPos + " region=" + region.length
                + "B candidates=" + examined + " window=" + keystream.length + "B)");
    }

    /**
     * True when {@code [off, off+len)} is a raw-deflate stream that decompresses to something.
     *
     * <p>Used as the resynchroniser's structural prefilter: a candidate decrypted at the wrong
     * stream offset is noise and will not inflate, so this rejects it without hashing it against
     * every plausible packet counter.
     */
    private boolean inflates(byte[] data, int off, int len) {
        if (len <= 0) {
            return false;
        }
        try {
            Inflater probe = INFLATER.get();
            probe.reset();
            probe.setInput(data, off, len);
            byte[] sink = new byte[512];
            int produced = 0;
            while (produced < sink.length) {
                int n = probe.inflate(sink, produced, sink.length - produced);
                if (n == 0) {
                    break;
                }
                produced += n;
            }
            return produced > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final ThreadLocal<Inflater> INFLATER =
            ThreadLocal.withInitial(() -> new Inflater(true));

    /** A fresh cipher over the session key/IV. */
    private Cipher newCipher(int mode) throws Exception {
        Cipher c = Cipher.getInstance(transformation);
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c;
    }

    /** Consumes {@code bytes} of keystream so the cipher resumes at that stream offset. */
    private static void advance(Cipher c, long bytes) throws Exception {
        byte[] chunk = new byte[8192];
        long left = bytes;
        while (left > 0) {
            int n = (int) Math.min(left, chunk.length);
            c.update(chunk, 0, n);
            left -= n;
        }
    }

    /**
     * SHA-256(LE64(counter) || payload || key)[0..8] — RakNetPlayerSession.calculateChecksum.
     *
     * <p>The digest is pooled per thread: the resynchroniser calls this a few million times in a
     * row, and {@code MessageDigest.getInstance} per call dominated the scan.
     */
    private byte[] checksum(long counter, byte[] payload, int off, int len) {
        MessageDigest digest = DIGEST.get();
        digest.reset();
        byte[] counterBytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            counterBytes[i] = (byte) (counter >>> (i * 8));          // little endian
        }
        digest.update(counterBytes);
        digest.update(payload, off, len);
        digest.update(key);
        return Arrays.copyOf(digest.digest(), 8);
    }

    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    });
}
