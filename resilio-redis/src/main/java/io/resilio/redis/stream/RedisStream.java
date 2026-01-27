package io.resilio.redis.stream;

import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.resilio.redis.connection.RedisConnectionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Streams abstraction using Lettuce.
 *
 * <p>Provides a simple API for publishing messages to streams
 * and consuming them with consumer groups.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * RedisStream stream = new RedisStream(connectionManager, "my-stream");
 *
 * // Add a message
 * String messageId = stream.add(Map.of("event", "user_login", "userId", "123"));
 *
 * // Create a consumer group
 * stream.createGroup("my-group");
 *
 * // Start consuming
 * stream.consume("my-group", "consumer-1", message -> {
 *     System.out.println("Received: " + message);
 *     stream.acknowledge("my-group", message.getId());
 * });
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class RedisStream implements AutoCloseable {

    private final RedisConnectionManager connectionManager;
    private final String streamKey;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor;

    /**
     * Creates a new RedisStream instance.
     *
     * @param connectionManager the connection manager
     * @param streamKey the Redis key for this stream
     */
    public RedisStream(RedisConnectionManager connectionManager, String streamKey) {
        this.connectionManager = connectionManager;
        this.streamKey = streamKey;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "resilio-stream-" + streamKey);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Adds a message to the stream.
     *
     * @param fields the message fields
     * @return the message ID assigned by Redis
     */
    public String add(Map<String, String> fields) {
        return connectionManager.executeWithConnection(commands ->
            commands.xadd(streamKey, fields)
        );
    }

    /**
     * Adds a message to the stream with a maximum length constraint.
     *
     * @param fields the message fields
     * @param maxLen the approximate maximum length of the stream
     * @return the message ID assigned by Redis
     */
    public String add(Map<String, String> fields, long maxLen) {
        return connectionManager.executeWithConnection(commands ->
            commands.xadd(streamKey, XAddArgs.Builder.maxlen(maxLen).approximateTrimming(), fields)
        );
    }

    /**
     * Creates a consumer group for this stream.
     *
     * @param groupName the name of the consumer group
     * @return true if the group was created, false if it already exists
     */
    public boolean createGroup(String groupName) {
        return createGroup(groupName, "0");
    }

    /**
     * Creates a consumer group starting from a specific message ID.
     *
     * @param groupName the name of the consumer group
     * @param startId the ID to start reading from ("0" for all, "$" for new messages only)
     * @return true if the group was created, false if it already exists
     */
    public boolean createGroup(String groupName, String startId) {
        try {
            connectionManager.executeWithConnection(commands -> {
                commands.xgroupCreate(
                    XReadArgs.StreamOffset.from(streamKey, startId),
                    groupName,
                    XGroupCreateArgs.Builder.mkstream()
                );
                return null;
            });
            return true;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return false; // Group already exists
            }
            throw e;
        }
    }

    /**
     * Reads messages from the stream using a consumer group.
     *
     * @param groupName the consumer group name
     * @param consumerName the consumer name within the group
     * @param count maximum number of messages to read
     * @param blockTimeout how long to block waiting for messages (null for non-blocking)
     * @return list of messages
     */
    public List<StreamMessage> readGroup(String groupName, String consumerName, int count, Duration blockTimeout) {
        return connectionManager.executeWithConnection(commands -> {
            XReadArgs args = XReadArgs.Builder.count(count);
            if (blockTimeout != null) {
                args.block(blockTimeout);
            }

            List<io.lettuce.core.StreamMessage<String, String>> messages = commands.xreadgroup(
                io.lettuce.core.Consumer.from(groupName, consumerName),
                args,
                XReadArgs.StreamOffset.lastConsumed(streamKey)
            );

            if (messages == null || messages.isEmpty()) {
                return Collections.emptyList();
            }

            List<StreamMessage> result = new ArrayList<>(messages.size());
            for (io.lettuce.core.StreamMessage<String, String> msg : messages) {
                result.add(new StreamMessage(
                    msg.getId(),
                    msg.getStream(),
                    new HashMap<>(msg.getBody())
                ));
            }
            return result;
        });
    }

    /**
     * Acknowledges a message as processed.
     *
     * @param groupName the consumer group name
     * @param messageIds the message IDs to acknowledge
     * @return the number of messages acknowledged
     */
    public long acknowledge(String groupName, String... messageIds) {
        return connectionManager.executeWithConnection(commands ->
            commands.xack(streamKey, groupName, messageIds)
        );
    }

    /**
     * Starts consuming messages in a background thread.
     *
     * @param groupName the consumer group name
     * @param consumerName the consumer name
     * @param listener the listener to receive messages
     */
    public void startConsuming(String groupName, String consumerName, StreamListener listener) {
        if (running.compareAndSet(false, true)) {
            executor.submit(() -> consumeLoop(groupName, consumerName, listener));
        }
    }

    private void consumeLoop(String groupName, String consumerName, StreamListener listener) {
        while (running.get()) {
            try {
                List<StreamMessage> messages = readGroup(groupName, consumerName, 10, Duration.ofSeconds(2));
                for (StreamMessage message : messages) {
                    try {
                        listener.onMessage(message);
                        acknowledge(groupName, message.getId());
                    } catch (Exception e) {
                        System.err.println("Error processing stream message: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Error in stream consume loop: " + e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Stops consuming messages.
     */
    public void stopConsuming() {
        running.set(false);
    }

    /**
     * Gets the length of the stream.
     *
     * @return the number of messages in the stream
     */
    public long length() {
        return connectionManager.executeWithConnection(commands ->
            commands.xlen(streamKey)
        );
    }

    /**
     * Trims the stream to the specified maximum length.
     *
     * @param maxLen the maximum length to trim to
     * @return the number of messages removed
     */
    public long trim(long maxLen) {
        return connectionManager.executeWithConnection(commands ->
            commands.xtrim(streamKey, maxLen)
        );
    }

    /**
     * Gets the stream key.
     *
     * @return the Redis key for this stream
     */
    public String getStreamKey() {
        return streamKey;
    }

    @Override
    public void close() {
        stopConsuming();
        executor.shutdown();
    }
}
