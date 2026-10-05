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
    private final Cipher encryptCipher;
    private final Cipher decryptCipher;
    private long encryptCounter;
    private long decryptCounter;

    private BedrockEncryption(byte[] key, Cipher encryptCipher, Cipher decryptCipher) {
        this.key = key;
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
        return new BedrockEncryption(key, enc, dec);
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
        byte[] plain;
        try {
            plain = decryptCipher.update(region);
        } catch (Exception e) {
            throw new IllegalStateException("decryption failed", e);
        }
        if (plain.length < 9) {
            throw new IllegalArgumentException("encrypted region too short: " + plain.length);
        }
        int trailer = plain.length - 8;
        byte[] payload = Arrays.copyOfRange(plain, 0, trailer);     // includes the compression prefix
        byte[] expected = checksum(decryptCounter++, payload, 0, payload.length);
        for (int i = 0; i < 8; i++) {
            if (plain[trailer + i] != expected[i]) {
                throw new IllegalStateException("bad encrypted checksum on packet " + (decryptCounter - 1));
            }
        }
        byte[] out = new byte[trailer];
        System.arraycopy(plain, 0, out, 0, trailer);
        return out;
    }

    /** SHA-256(LE64(counter) || payload || key)[0..8] — RakNetPlayerSession.calculateChecksum. */
    private byte[] checksum(long counter, byte[] payload, int off, int len) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        byte[] counterBytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            counterBytes[i] = (byte) (counter >>> (i * 8));          // little endian
        }
        digest.update(counterBytes);
        digest.update(payload, off, len);
        digest.update(key);
        return Arrays.copyOf(digest.digest(), 8);
    }
}
