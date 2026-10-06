package app.plyvanta.offline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.junit.BeforeClass;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

public final class RsaContentKeyEnvelopeTest {
    private static final String ITEM_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String OTHER_ITEM_ID = "123e4567-e89b-42d3-a456-426614174001";
    private static final String CHANNEL_ID = "UCAAAAAAAAAAAAAAAAAAAAAA";
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static KeyPair wrappingKey;
    private static KeyPair anotherKey;

    @BeforeClass
    public static void generateFixtureKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        wrappingKey = generator.generateKeyPair();
        anotherKey = generator.generateKeyPair();
    }

    @Test
    public void publicWrappingRoundTripsAndUsesRandomizedCiphertext() throws Exception {
        byte[] contentKey = contentKey();
        byte[] expectedKey = contentKey.clone();
        ContentKeyProtector.Envelope first = RsaContentKeyEnvelope.wrap(
                wrappingKey.getPublic(), contentKey, ITEM_ID
        );
        ContentKeyProtector.Envelope second = RsaContentKeyEnvelope.wrap(
                wrappingKey.getPublic(), contentKey, ITEM_ID
        );

        assertArrayEquals(expectedKey, contentKey);
        assertEquals(ContentKeyProtector.Envelope.VERSION_RSA_OAEP, first.getVersion());
        assertFalse(Arrays.equals(first.getCiphertext(), second.getCiphertext()));
        assertArrayEquals(expectedKey, RsaContentKeyEnvelope.unwrap(
                wrappingKey.getPrivate(), first, ITEM_ID
        ));
        assertArrayEquals(expectedKey, RsaContentKeyEnvelope.unwrap(
                wrappingKey.getPrivate(), second, ITEM_ID
        ));
    }

    @Test
    public void rejectsMovedEnvelopeWrongPrivateKeyAndTamperedCiphertext() throws Exception {
        ContentKeyProtector.Envelope envelope = RsaContentKeyEnvelope.wrap(
                wrappingKey.getPublic(), contentKey(), ITEM_ID
        );

        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.unwrap(
                        wrappingKey.getPrivate(), envelope, OTHER_ITEM_ID
                ));
        assertThrows(GeneralSecurityException.class,
                () -> RsaContentKeyEnvelope.unwrap(anotherKey.getPrivate(), envelope, ITEM_ID));
        byte[] ciphertext = envelope.getCiphertext();
        ciphertext[101] ^= 1;
        ContentKeyProtector.Envelope corrupted = ContentKeyProtector.Envelope.createRsa(ciphertext);
        assertThrows(GeneralSecurityException.class,
                () -> RsaContentKeyEnvelope.unwrap(wrappingKey.getPrivate(), corrupted, ITEM_ID));
    }

    @Test
    public void rejectsNonCanonicalIdentityWrongContentKeyLengthAndSmallRsaKey() throws Exception {
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.wrap(
                        wrappingKey.getPublic(), contentKey(), ITEM_ID.toUpperCase()
                ));
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.wrap(
                        wrappingKey.getPublic(), new byte[31], ITEM_ID
                ));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair undersized = generator.generateKeyPair();
        assertThrows(ContentKeyProtector.KeyUnavailableException.class,
                () -> RsaContentKeyEnvelope.wrap(undersized.getPublic(), contentKey(), ITEM_ID));
        ContentKeyProtector.Envelope legacy = ContentKeyProtector.Envelope.create(
                new byte[12], new byte[48]
        );
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.unwrap(wrappingKey.getPrivate(), legacy, ITEM_ID));
    }

    @Test
    public void authenticatedOaepPayloadStillMustHaveExactLengthAndBinding() throws Exception {
        ContentKeyProtector.Envelope wrongLength = encryptPayload(new byte[32]);
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.unwrap(wrappingKey.getPrivate(), wrongLength, ITEM_ID));
        int expectedPayloadSize = "Plyvanta/offline-content-key/v2".length() + 16 + 32;
        ContentKeyProtector.Envelope wrongBinding = encryptPayload(new byte[expectedPayloadSize]);
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.unwrap(wrappingKey.getPrivate(), wrongBinding, ITEM_ID));
    }

    @Test
    public void subscriptionIdentityIsStableOpaqueAndBoundToDeviceChannelEpochAndVideo()
            throws Exception {
        UUID id = identity(new byte[32], CHANNEL_ID, 1000L, VIDEO_ID);
        assertEquals(id, identity(new byte[32], CHANNEL_ID, 1000L, VIDEO_ID));
        assertEquals(4, id.version());
        assertEquals(2, id.variant());
        assertNotEquals(id, identity(new byte[32], CHANNEL_ID, 1001L, VIDEO_ID));
        assertNotEquals(id, identity(new byte[32], CHANNEL_ID, 1000L, "AAAAAAAAAAA"));
        assertNotEquals(id, identity(new byte[32], "UCBBBBBBBBBBBBBBBBBBBBBB", 1000L, VIDEO_ID));
        byte[] anotherDeviceKey = new byte[32];
        anotherDeviceKey[0] = 1;
        assertNotEquals(id, identity(anotherDeviceKey, CHANNEL_ID, 1000L, VIDEO_ID));
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.subscriptionIdentityInput("bad", 1000L, VIDEO_ID));
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.subscriptionIdentityInput(CHANNEL_ID, -1L, VIDEO_ID));
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.subscriptionIdentityInput(CHANNEL_ID, 1000L, "bad"));
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> RsaContentKeyEnvelope.subscriptionItemId(new byte[31]));
    }

    private static UUID identity(byte[] deviceKey, String channelId, long cutoff, String videoId)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(deviceKey, "HmacSHA256"));
        return RsaContentKeyEnvelope.subscriptionItemId(mac.doFinal(
                RsaContentKeyEnvelope.subscriptionIdentityInput(channelId, cutoff, videoId)
        ));
    }

    private static ContentKeyProtector.Envelope encryptPayload(byte[] payload) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey.getPublic(), new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
        ));
        return ContentKeyProtector.Envelope.createRsa(cipher.doFinal(payload));
    }

    private static byte[] contentKey() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (index + 1);
        }
        return key;
    }
}
