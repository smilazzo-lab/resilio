package io.resilio.redis.pubsub;

/**
 * Listener interface for Redis Pub/Sub messages.
 *
 * <p>Implementations must be thread-safe as messages may be
 * delivered from multiple threads.</p>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
@FunctionalInterface
public interface MessageListener {

    /**
     * Called when a message is received on a subscribed channel.
     *
     * @param channel the channel the message was received on
     * @param message the message content
     */
    void onMessage(String channel, String message);
}
