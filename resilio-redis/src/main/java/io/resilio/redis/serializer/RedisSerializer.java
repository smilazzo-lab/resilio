package io.resilio.redis.serializer;

/**
 * Serializer interface for Redis key/value serialization.
 *
 * <p>Implementations must be thread-safe.</p>
 *
 * @param <T> the type to serialize/deserialize
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public interface RedisSerializer<T> {

    /**
     * Serializes the given object to a byte array.
     *
     * @param value the object to serialize (may be null)
     * @return the serialized bytes, or null if value is null
     * @throws SerializationException if serialization fails
     */
    byte[] serialize(T value);

    /**
     * Deserializes the given byte array to an object.
     *
     * @param bytes the bytes to deserialize (may be null)
     * @return the deserialized object, or null if bytes is null
     * @throws SerializationException if deserialization fails
     */
    T deserialize(byte[] bytes);

    /**
     * Exception thrown when serialization or deserialization fails.
     */
    class SerializationException extends RuntimeException {
        public SerializationException(String message) {
            super(message);
        }

        public SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
