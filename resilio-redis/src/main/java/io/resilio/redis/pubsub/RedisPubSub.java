package io.resilio.redis.pubsub;

import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import io.resilio.redis.connection.RedisConnectionManager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Redis Pub/Sub abstraction using Lettuce.
 *
 * <p>Provides a simple API for publishing messages to channels
 * and subscribing to receive messages.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * RedisPubSub pubSub = new RedisPubSub(connectionManager);
 *
 * // Subscribe to a channel
 * pubSub.subscribe("my-channel", (channel, message) -> {
 *     System.out.println("Received: " + message);
 * });
 *
 * // Publish a message
 * pubSub.publish("my-channel", "Hello, World!");
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class RedisPubSub implements AutoCloseable {

    private final RedisConnectionManager connectionManager;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final Map<String, Set<MessageListener>> listeners;
    private volatile boolean closed = false;

    /**
     * Creates a new RedisPubSub instance.
     *
     * @param connectionManager the connection manager to use
     */
    public RedisPubSub(RedisConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.pubSubConnection = connectionManager.getPubSubConnection();
        this.listeners = new ConcurrentHashMap<>();

        setupListener();
    }

    private void setupListener() {
        pubSubConnection.addListener(new RedisPubSubListener<>() {
            @Override
            public void message(String channel, String message) {
                Set<MessageListener> channelListeners = listeners.get(channel);
                if (channelListeners != null) {
                    for (MessageListener listener : channelListeners) {
                        try {
                            listener.onMessage(channel, message);
                        } catch (Exception e) {
                            // Log and continue to other listeners
                            System.err.println("Error in message listener for channel " + channel + ": " + e.getMessage());
                        }
                    }
                }
            }

            @Override
            public void message(String pattern, String channel, String message) {
                // Pattern subscriptions - delegate to channel listeners
                Set<MessageListener> patternListeners = listeners.get(pattern);
                if (patternListeners != null) {
                    for (MessageListener listener : patternListeners) {
                        try {
                            listener.onMessage(channel, message);
                        } catch (Exception e) {
                            System.err.println("Error in pattern listener for " + pattern + ": " + e.getMessage());
                        }
                    }
                }
            }

            @Override
            public void subscribed(String channel, long count) {
                // Subscription confirmed
            }

            @Override
            public void psubscribed(String pattern, long count) {
                // Pattern subscription confirmed
            }

            @Override
            public void unsubscribed(String channel, long count) {
                // Unsubscription confirmed
            }

            @Override
            public void punsubscribed(String pattern, long count) {
                // Pattern unsubscription confirmed
            }
        });
    }

    /**
     * Publishes a message to a channel.
     *
     * @param channel the channel to publish to
     * @param message the message to publish
     * @return the number of clients that received the message
     */
    public long publish(String channel, String message) {
        if (closed) {
            throw new IllegalStateException("RedisPubSub is closed");
        }
        return connectionManager.executeWithConnection(commands ->
            commands.publish(channel, message)
        );
    }

    /**
     * Subscribes to a channel with the given listener.
     *
     * @param channel the channel to subscribe to
     * @param listener the listener to receive messages
     */
    public void subscribe(String channel, MessageListener listener) {
        if (closed) {
            throw new IllegalStateException("RedisPubSub is closed");
        }

        listeners.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>())
                .add(listener);

        RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
        sync.subscribe(channel);
    }

    /**
     * Subscribes to channels matching a pattern.
     *
     * @param pattern the pattern to match (e.g., "news.*")
     * @param listener the listener to receive messages
     */
    public void psubscribe(String pattern, MessageListener listener) {
        if (closed) {
            throw new IllegalStateException("RedisPubSub is closed");
        }

        listeners.computeIfAbsent(pattern, k -> new CopyOnWriteArraySet<>())
                .add(listener);

        RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
        sync.psubscribe(pattern);
    }

    /**
     * Unsubscribes from a channel.
     *
     * @param channel the channel to unsubscribe from
     */
    public void unsubscribe(String channel) {
        listeners.remove(channel);
        if (!closed) {
            RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
            sync.unsubscribe(channel);
        }
    }

    /**
     * Unsubscribes from a pattern.
     *
     * @param pattern the pattern to unsubscribe from
     */
    public void punsubscribe(String pattern) {
        listeners.remove(pattern);
        if (!closed) {
            RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
            sync.punsubscribe(pattern);
        }
    }

    /**
     * Returns the number of subscribed channels.
     *
     * @return the subscription count
     */
    public int getSubscriptionCount() {
        return listeners.size();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            listeners.clear();
            pubSubConnection.close();
        }
    }
}
