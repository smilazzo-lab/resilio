package io.resilio.redis.stream;

import java.util.Map;

/**
 * Represents a message in a Redis Stream.
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class StreamMessage {

    private final String id;
    private final String stream;
    private final Map<String, String> body;

    /**
     * Creates a new StreamMessage.
     *
     * @param id the message ID assigned by Redis
     * @param stream the stream name
     * @param body the message body as key-value pairs
     */
    public StreamMessage(String id, String stream, Map<String, String> body) {
        this.id = id;
        this.stream = stream;
        this.body = body;
    }

    /**
     * Gets the message ID assigned by Redis.
     *
     * @return the message ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the stream name.
     *
     * @return the stream name
     */
    public String getStream() {
        return stream;
    }

    /**
     * Gets the message body.
     *
     * @return the message body as an unmodifiable map
     */
    public Map<String, String> getBody() {
        return body;
    }

    /**
     * Gets a field value from the message body.
     *
     * @param field the field name
     * @return the field value, or null if not present
     */
    public String getField(String field) {
        return body.get(field);
    }

    @Override
    public String toString() {
        return "StreamMessage{" +
                "id='" + id + '\'' +
                ", stream='" + stream + '\'' +
                ", body=" + body +
                '}';
    }
}
