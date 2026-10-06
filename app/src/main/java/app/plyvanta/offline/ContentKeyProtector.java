package app.plyvanta.offline;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Protects a per-item content-encryption key with a device-bound wrapping key.
 *
 * <p>Implementations must bind both wrapping and unwrapping to the canonical item UUID supplied
 * by the caller. The interface permits deterministic fakes in JVM storage tests without exposing
 * Android Keystore APIs to the storage layer.</p>
 */
public interface ContentKeyProtector {
    Envelope wrap(byte[] contentKey, String itemId)
            throws KeyProtectionException;

    byte[] unwrap(Envelope envelope, String itemId)
            throws KeyProtectionException;

    OfflineSecurityPolicy.Decision status();

    void deleteKey() throws KeyUnavailableException;

    class KeyProtectionException extends GeneralSecurityException {
        public KeyProtectionException(String message) {
            super(message);
        }

        public KeyProtectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class AuthenticationRequiredException extends KeyProtectionException {
        public AuthenticationRequiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class KeyInvalidatedException extends KeyProtectionException {
        public KeyInvalidatedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class KeyUnavailableException extends KeyProtectionException {
        public KeyUnavailableException(String message) {
            super(message);
        }

        public KeyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class InvalidEnvelopeException extends KeyProtectionException {
        public InvalidEnvelopeException(String message) {
            super(message);
        }

        public InvalidEnvelopeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Versioned persistent representation of one AES-256 key wrapped with AES-GCM.
     */
    final class Envelope {
        private static final int MAGIC = 0x50564b45; // PVKE
        public static final int VERSION_AES_GCM = 1;
        public static final int VERSION_RSA_OAEP = 2;
        private static final int IV_LENGTH = 12;
        private static final int WRAPPED_KEY_LENGTH = 32 + 16;
        private static final int RSA_WRAPPED_KEY_LENGTH = 256;
        private static final int HEADER_LENGTH =
                Integer.BYTES + Byte.BYTES + Byte.BYTES + Short.BYTES;
        private static final int MAX_ENCODED_LENGTH = HEADER_LENGTH + RSA_WRAPPED_KEY_LENGTH;

        private final int version;
        private final byte[] initializationVector;
        private final byte[] ciphertext;

        private Envelope(int version, byte[] initializationVector, byte[] ciphertext) {
            this.version = version;
            this.initializationVector = initializationVector.clone();
            this.ciphertext = ciphertext.clone();
        }

        public static Envelope create(
                byte[] initializationVector,
                byte[] ciphertext
        ) throws InvalidEnvelopeException {
            if (initializationVector == null
                    || initializationVector.length != IV_LENGTH) {
                throw new InvalidEnvelopeException(
                        "Envelope IV must contain exactly " + IV_LENGTH + " bytes."
                );
            }
            if (ciphertext == null || ciphertext.length != WRAPPED_KEY_LENGTH) {
                throw new InvalidEnvelopeException(
                        "Envelope ciphertext must contain exactly "
                                + WRAPPED_KEY_LENGTH + " bytes."
                );
            }
            return new Envelope(VERSION_AES_GCM, initializationVector, ciphertext);
        }

        /**
         * Fixed-size RSA-2048 OAEP envelope. The encrypted payload binds the item UUID.
         */
        public static Envelope createRsa(byte[] ciphertext)
                throws InvalidEnvelopeException {
            if (ciphertext == null || ciphertext.length != RSA_WRAPPED_KEY_LENGTH) {
                throw new InvalidEnvelopeException(
                        "RSA envelope ciphertext must contain exactly 256 bytes."
                );
            }
            return new Envelope(VERSION_RSA_OAEP, new byte[0], ciphertext);
        }

        public static Envelope fromByteArray(byte[] encoded)
                throws InvalidEnvelopeException {
            if (encoded == null
                    || encoded.length < HEADER_LENGTH
                    || encoded.length > MAX_ENCODED_LENGTH) {
                throw new InvalidEnvelopeException(
                        "Envelope has an invalid encoded length."
                );
            }

            try {
                ByteBuffer input = ByteBuffer.wrap(encoded);
                if (input.getInt() != MAGIC) {
                    throw new InvalidEnvelopeException(
                            "Envelope magic is invalid."
                    );
                }
                int version = Byte.toUnsignedInt(input.get());
                if (version != VERSION_AES_GCM && version != VERSION_RSA_OAEP) {
                    throw new InvalidEnvelopeException(
                            "Envelope version is unsupported."
                    );
                }
                int ivLength = Byte.toUnsignedInt(input.get());
                int ciphertextLength = Short.toUnsignedInt(input.getShort());
                int expectedIvLength = version == VERSION_AES_GCM ? IV_LENGTH : 0;
                int expectedCiphertextLength = version == VERSION_AES_GCM
                        ? WRAPPED_KEY_LENGTH : RSA_WRAPPED_KEY_LENGTH;
                if (ivLength != expectedIvLength
                        || ciphertextLength != expectedCiphertextLength
                        || input.remaining() != ivLength + ciphertextLength) {
                    throw new InvalidEnvelopeException(
                            "Envelope field lengths are invalid."
                    );
                }
                byte[] iv = new byte[ivLength];
                byte[] wrapped = new byte[ciphertextLength];
                input.get(iv);
                input.get(wrapped);
                if (input.hasRemaining()) {
                    throw new InvalidEnvelopeException(
                            "Envelope contains trailing data."
                    );
                }
                return version == VERSION_AES_GCM ? create(iv, wrapped) : createRsa(wrapped);
            } catch (InvalidEnvelopeException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new InvalidEnvelopeException(
                        "Envelope is malformed.",
                        exception
                );
            }
        }

        public byte[] toByteArray() {
            return ByteBuffer.allocate(
                            HEADER_LENGTH + initializationVector.length + ciphertext.length
                    )
                    .putInt(MAGIC)
                    .put((byte) version)
                    .put((byte) initializationVector.length)
                    .putShort((short) ciphertext.length)
                    .put(initializationVector)
                    .put(ciphertext)
                    .array();
        }

        public byte[] getInitializationVector() {
            return initializationVector.clone();
        }

        public byte[] getCiphertext() {
            return ciphertext.clone();
        }

        public int getVersion() {
            return version;
        }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) {
                return true;
            }
            if (!(candidate instanceof Envelope)) {
                return false;
            }
            Envelope other = (Envelope) candidate;
            return version == other.version
                    && Arrays.equals(initializationVector, other.initializationVector)
                    && Arrays.equals(ciphertext, other.ciphertext);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * version + Arrays.hashCode(initializationVector))
                    + Arrays.hashCode(ciphertext);
        }

        @Override
        public String toString() {
            return "Envelope{version=" + version
                    + ", encodedBytes="
                    + (HEADER_LENGTH + initializationVector.length + ciphertext.length) + '}';
        }
    }
}
