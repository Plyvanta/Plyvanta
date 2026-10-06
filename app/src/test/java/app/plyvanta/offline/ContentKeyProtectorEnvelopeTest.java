package app.plyvanta.offline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;

public final class ContentKeyProtectorEnvelopeTest {
    @Test
    public void envelopeRoundTripsThroughItsVersionedEncoding() throws Exception {
        byte[] iv = bytes(12, 1);
        byte[] ciphertext = bytes(48, 21);
        ContentKeyProtector.Envelope expected =
                ContentKeyProtector.Envelope.create(iv, ciphertext);

        ContentKeyProtector.Envelope decoded =
                ContentKeyProtector.Envelope.fromByteArray(
                        expected.toByteArray()
                );

        assertEquals(expected, decoded);
        assertArrayEquals(iv, decoded.getInitializationVector());
        assertArrayEquals(ciphertext, decoded.getCiphertext());
        assertEquals(ContentKeyProtector.Envelope.VERSION_AES_GCM, decoded.getVersion());
    }

    @Test
    public void rsaEnvelopeIsFixedSizeVersionedAndDefensivelyCopied() throws Exception {
        byte[] ciphertext = bytes(256, 7);
        byte[] expectedCiphertext = ciphertext.clone();
        ContentKeyProtector.Envelope envelope = ContentKeyProtector.Envelope.createRsa(ciphertext);
        Arrays.fill(ciphertext, (byte) 0);
        byte[] returnedCiphertext = envelope.getCiphertext();
        Arrays.fill(returnedCiphertext, (byte) 0);

        assertEquals(264, envelope.toByteArray().length);
        assertEquals(0, envelope.getInitializationVector().length);
        assertEquals(ContentKeyProtector.Envelope.VERSION_RSA_OAEP, envelope.getVersion());
        assertArrayEquals(expectedCiphertext, envelope.getCiphertext());
        assertEquals(envelope, ContentKeyProtector.Envelope.fromByteArray(envelope.toByteArray()));
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> ContentKeyProtector.Envelope.createRsa(new byte[255]));
        assertThrows(ContentKeyProtector.InvalidEnvelopeException.class,
                () -> ContentKeyProtector.Envelope.createRsa(new byte[257]));
    }

    @Test
    public void rsaDecoderRejectsTruncationTrailingBytesAndCrossVersionLayouts() throws Exception {
        byte[] encoded = ContentKeyProtector.Envelope.createRsa(bytes(256, 7)).toByteArray();
        for (int length = 0; length < encoded.length; length++) {
            assertRejected(Arrays.copyOf(encoded, length));
        }
        assertRejected(Arrays.copyOf(encoded, encoded.length + 1));
        byte[] wrongVersion = encoded.clone();
        wrongVersion[4] = ContentKeyProtector.Envelope.VERSION_AES_GCM;
        assertRejected(wrongVersion);
        byte[] withIv = encoded.clone();
        withIv[5] = 12;
        assertRejected(withIv);
        byte[] wrongLength = encoded.clone();
        wrongLength[7] = 1;
        assertRejected(wrongLength);
        byte[] unknownVersion = encoded.clone();
        unknownVersion[4] = 3;
        assertRejected(unknownVersion);
    }

    @Test
    public void envelopeDefensivelyCopiesInputsAndOutputs() throws Exception {
        byte[] iv = bytes(12, 1);
        byte[] ciphertext = bytes(48, 21);
        byte[] expectedIv = iv.clone();
        byte[] expectedCiphertext = ciphertext.clone();
        ContentKeyProtector.Envelope envelope =
                ContentKeyProtector.Envelope.create(iv, ciphertext);

        Arrays.fill(iv, (byte) 0);
        Arrays.fill(ciphertext, (byte) 0);
        byte[] returnedIv = envelope.getInitializationVector();
        byte[] returnedCiphertext = envelope.getCiphertext();
        Arrays.fill(returnedIv, (byte) 0);
        Arrays.fill(returnedCiphertext, (byte) 0);

        assertArrayEquals(expectedIv, envelope.getInitializationVector());
        assertArrayEquals(
                expectedCiphertext,
                envelope.getCiphertext()
        );
    }

    @Test
    public void envelopeRejectsInvalidFieldLengths() {
        assertThrows(
                ContentKeyProtector.InvalidEnvelopeException.class,
                () -> ContentKeyProtector.Envelope.create(
                        new byte[11],
                        new byte[48]
                )
        );
        assertThrows(
                ContentKeyProtector.InvalidEnvelopeException.class,
                () -> ContentKeyProtector.Envelope.create(
                        new byte[12],
                        new byte[47]
                )
        );
    }

    @Test
    public void decoderRejectsTamperedHeaderAndEveryTruncation()
            throws Exception {
        byte[] encoded = ContentKeyProtector.Envelope.create(
                bytes(12, 1),
                bytes(48, 21)
        ).toByteArray();

        byte[] badMagic = encoded.clone();
        badMagic[0] ^= 0x01;
        assertRejected(badMagic);

        byte[] badVersion = encoded.clone();
        badVersion[4] = 2;
        assertRejected(badVersion);

        byte[] badIvLength = encoded.clone();
        badIvLength[5] = 11;
        assertRejected(badIvLength);

        byte[] badCiphertextLength = encoded.clone();
        badCiphertextLength[7] = 47;
        assertRejected(badCiphertextLength);

        for (int length = 0; length < encoded.length; length++) {
            assertRejected(Arrays.copyOf(encoded, length));
        }
        assertRejected(Arrays.copyOf(encoded, encoded.length + 1));
    }

    @Test
    public void itemAadIsDeterministicAndBoundToTheCanonicalUuid()
            throws Exception {
        String first = "123e4567-e89b-42d3-a456-426614174000";
        String second = "123e4567-e89b-42d3-a456-426614174001";

        assertArrayEquals(
                DeviceBoundKeyManager.itemAad(first),
                DeviceBoundKeyManager.itemAad(first)
        );
        assertFalse(Arrays.equals(
                DeviceBoundKeyManager.itemAad(first),
                DeviceBoundKeyManager.itemAad(second)
        ));
        assertThrows(
                ContentKeyProtector.InvalidEnvelopeException.class,
                () -> DeviceBoundKeyManager.itemAad(first.toUpperCase())
        );
    }

    private static void assertRejected(byte[] encoded) {
        assertThrows(
                ContentKeyProtector.InvalidEnvelopeException.class,
                () -> ContentKeyProtector.Envelope.fromByteArray(encoded)
        );
    }

    private static byte[] bytes(int length, int seed) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) (seed + index);
        }
        return result;
    }
}
