package io.resilio.redis.stream;

/**
 * Listener interface for Redis Stream messages.
 *
 * <p>Implementations must be thread-safe as messages may be
 * delivered from multiple threads.</p>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
@FunctionalInterface
public interface StreamListener {

    /**
     * Called when a message is received from the stream.
     *
     * @param message the received message
     */
    void onMessage(StreamMessage message);
}
