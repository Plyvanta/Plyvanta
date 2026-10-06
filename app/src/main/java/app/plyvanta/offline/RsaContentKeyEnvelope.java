package app.plyvanta.offline;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Public-key wrapping permits encryption while the authenticated private key stays locked.
 * Android Keystore supplies the StrongBox private key; this class never persists raw keys.
 */
final class RsaContentKeyEnvelope {
    private static final byte[] BINDING_PREFIX =
            "Plyvanta/offline-content-key/v2".getBytes(StandardCharsets.US_ASCII);
    private static final int CONTENT_KEY_BYTES = 32;
    private static final int UUID_BYTES = 2 * Long.BYTES;
    private static final int PAYLOAD_BYTES =
            BINDING_PREFIX.length + UUID_BYTES + CONTENT_KEY_BYTES;
    private static final byte[] SUBSCRIPTION_ID_PREFIX =
            "Plyvanta/subscription-item-id/v1".getBytes(StandardCharsets.US_ASCII);

    // Android's RSA OAEP implementation uses SHA-1 for MGF1 independently of the main
    // SHA-256 digest. Explicit parameters keep software public wrapping interoperable.
    private static final OAEPParameterSpec OAEP_PARAMETERS = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
    );
    private static final String TRANSFORMATION = "RSA/ECB/OAEPPadding";

    private RsaContentKeyEnvelope() {
    }

    /**
     * Only public channel/video identities and the local subscription boundary enter the MAC.
     */
    static byte[] subscriptionIdentityInput(String channelId, long cutoff, String videoId)
            throws ContentKeyProtector.InvalidEnvelopeException {
        if (channelId == null || !channelId.matches("UC[A-Za-z0-9_-]{22}")
                || videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")
                || cutoff < 0L) {
            throw new ContentKeyProtector.InvalidEnvelopeException(
                    "Subscription download identity is invalid."
            );
        }
        return ByteBuffer.allocate(SUBSCRIPTION_ID_PREFIX.length + 24 + Long.BYTES + 11)
                .put(SUBSCRIPTION_ID_PREFIX)
                .put(channelId.getBytes(StandardCharsets.US_ASCII))
                .putLong(cutoff)
                .put(videoId.getBytes(StandardCharsets.US_ASCII))
                .array();
    }

    static UUID subscriptionItemId(byte[] mac)
            throws ContentKeyProtector.InvalidEnvelopeException {
        if (mac == null || mac.length != 32) {
            throw new ContentKeyProtector.InvalidEnvelopeException(
                    "Subscription identity MAC must contain exactly 32 bytes."
            );
        }
        byte[] identifier = Arrays.copyOf(mac, UUID_BYTES);
        try {
            identifier[6] = (byte) ((identifier[6] & 0x0f) | 0x40);
            identifier[8] = (byte) ((identifier[8] & 0x3f) | 0x80);
            ByteBuffer input = ByteBuffer.wrap(identifier);
            return new UUID(input.getLong(), input.getLong());
        } finally {
            OfflineCrypto.wipe(identifier);
        }
    }

    static ContentKeyProtector.Envelope wrap(
            PublicKey publicKey,
            byte[] contentKey,
            String itemId
    ) throws GeneralSecurityException {
        if (!(publicKey instanceof RSAPublicKey)
                || ((RSAPublicKey) publicKey).getModulus().bitLength() != 2048) {
            throw new ContentKeyProtector.KeyUnavailableException(
                    "The offline wrapping public key must be RSA-2048."
            );
        }
        if (contentKey == null || contentKey.length != CONTENT_KEY_BYTES) {
            throw new ContentKeyProtector.InvalidEnvelopeException(
                    "Content keys must contain exactly 32 bytes."
            );
        }
        byte[] binding = itemBinding(itemId);
        byte[] payload = null;
        byte[] ciphertext = null;
        try {
            payload = ByteBuffer.allocate(PAYLOAD_BYTES)
                    .put(binding)
                    .put(contentKey)
                    .array();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_PARAMETERS);
            ciphertext = cipher.doFinal(payload);
            return ContentKeyProtector.Envelope.createRsa(ciphertext);
        } finally {
            OfflineCrypto.wipe(binding);
            OfflineCrypto.wipe(payload);
            OfflineCrypto.wipe(ciphertext);
        }
    }

    static byte[] unwrap(
            PrivateKey privateKey,
            ContentKeyProtector.Envelope envelope,
            String itemId
    ) throws GeneralSecurityException {
        if (envelope == null
                || envelope.getVersion() != ContentKeyProtector.Envelope.VERSION_RSA_OAEP) {
            throw new ContentKeyProtector.InvalidEnvelopeException(
                    "An RSA offline-key envelope is required."
            );
        }
        byte[] expectedBinding = itemBinding(itemId);
        byte[] ciphertext = envelope.getCiphertext();
        byte[] payload = null;
        byte[] actualBinding = null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_PARAMETERS);
            payload = cipher.doFinal(ciphertext);
            if (payload.length != PAYLOAD_BYTES) {
                throw new ContentKeyProtector.InvalidEnvelopeException(
                        "The RSA offline-key payload has an invalid length."
                );
            }
            actualBinding = Arrays.copyOf(payload, expectedBinding.length);
            if (!MessageDigest.isEqual(expectedBinding, actualBinding)) {
                throw new ContentKeyProtector.InvalidEnvelopeException(
                        "The wrapped key belongs to another offline item."
                );
            }
            return Arrays.copyOfRange(payload, expectedBinding.length, payload.length);
        } finally {
            OfflineCrypto.wipe(expectedBinding);
            OfflineCrypto.wipe(actualBinding);
            OfflineCrypto.wipe(ciphertext);
            OfflineCrypto.wipe(payload);
        }
    }

    private static byte[] itemBinding(String itemId)
            throws ContentKeyProtector.InvalidEnvelopeException {
        UUID uuid;
        try {
            uuid = UUID.fromString(itemId);
        } catch (RuntimeException exception) {
            throw new ContentKeyProtector.InvalidEnvelopeException(
                    "Item ID must be a canonical UUID."
            );
        }
        if (!uuid.toString().equals(itemId)) {
            throw new ContentKeyProtector.InvalidEnvelopeException(
                    "Item ID must be a lowercase canonical UUID."
            );
        }
        return ByteBuffer.allocate(BINDING_PREFIX.length + UUID_BYTES)
                .put(BINDING_PREFIX)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
