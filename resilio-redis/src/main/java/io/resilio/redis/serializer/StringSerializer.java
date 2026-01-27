package io.resilio.redis.serializer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Simple String serializer using UTF-8 encoding.
 *
 * <p>This is the default serializer for String keys.</p>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class StringSerializer implements RedisSerializer<String> {

    public static final StringSerializer INSTANCE = new StringSerializer();

    private final Charset charset;

    public StringSerializer() {
        this(StandardCharsets.UTF_8);
    }

    public StringSerializer(Charset charset) {
        this.charset = charset;
    }

    @Override
    public byte[] serialize(String value) {
        if (value == null) {
            return null;
        }
        return value.getBytes(charset);
    }

    @Override
    public String deserialize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, charset);
    }
}
