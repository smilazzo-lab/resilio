package io.resilio.redis.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON serializer using Jackson.
 *
 * <p>Suitable for complex objects that need to be stored in Redis.
 * Requires Jackson on the classpath.</p>
 *
 * @param <T> the type to serialize
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class JacksonSerializer<T> implements RedisSerializer<T> {

    private final ObjectMapper objectMapper;
    private final Class<T> type;

    /**
     * Creates a new JacksonSerializer with default ObjectMapper.
     *
     * @param type the class to serialize/deserialize
     */
    public JacksonSerializer(Class<T> type) {
        this(type, createDefaultObjectMapper());
    }

    /**
     * Creates a new JacksonSerializer with custom ObjectMapper.
     *
     * @param type the class to serialize/deserialize
     * @param objectMapper the ObjectMapper to use
     */
    public JacksonSerializer(Class<T> type, ObjectMapper objectMapper) {
        this.type = type;
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper createDefaultObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    @Override
    public byte[] serialize(T value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize value to JSON", e);
        }
    }

    @Override
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize JSON to " + type.getName(), e);
        }
    }

    /**
     * Returns the ObjectMapper used by this serializer.
     *
     * @return the ObjectMapper
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
