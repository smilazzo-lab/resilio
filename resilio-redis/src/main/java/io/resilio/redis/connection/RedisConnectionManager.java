package io.resilio.redis.connection;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.support.ConnectionPoolSupport;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.io.Closeable;
import java.time.Duration;

/**
 * Manages Redis connections with connection pooling.
 *
 * <p>Supports standalone and sentinel configurations.
 * Uses Lettuce client with commons-pool2 for connection pooling.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RedisCacheConfig config = RedisCacheConfig.builder()
 *     .host("localhost")
 *     .port(6379)
 *     .poolSize(10)
 *     .build();
 *
 * try (RedisConnectionManager manager = new RedisConnectionManager(config)) {
 *     manager.execute(commands -> {
 *         commands.set("key".getBytes(), "value".getBytes());
 *         return commands.get("key".getBytes());
 *     });
 * }
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class RedisConnectionManager implements Closeable {

    private final RedisCacheConfig config;
    private final ClientResources clientResources;
    private final RedisClient redisClient;
    private final GenericObjectPool<StatefulRedisConnection<byte[], byte[]>> connectionPool;

    /**
     * Creates a new connection manager with the given configuration.
     *
     * @param config the Redis configuration
     */
    public RedisConnectionManager(RedisCacheConfig config) {
        this.config = config;
        this.clientResources = DefaultClientResources.builder()
                .ioThreadPoolSize(4)
                .computationThreadPoolSize(4)
                .build();

        if (config.isCluster()) {
            throw new UnsupportedOperationException(
                "Redis Cluster mode is not yet supported. Use standalone or sentinel configuration.");
        }

        this.redisClient = createClient();
        this.connectionPool = createConnectionPool();
    }

    private RedisClient createClient() {
        RedisURI.Builder uriBuilder;

        if (config.isSentinel()) {
            uriBuilder = RedisURI.builder()
                    .withSentinelMasterId(config.getSentinelMasterId());

            for (String node : config.getSentinelNodes()) {
                String[] parts = node.split(":");
                uriBuilder.withSentinel(parts[0], Integer.parseInt(parts[1]));
            }
        } else {
            uriBuilder = RedisURI.builder()
                    .withHost(config.getHost())
                    .withPort(config.getPort());
        }

        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            uriBuilder.withPassword(config.getPassword().toCharArray());
        }

        uriBuilder.withDatabase(config.getDatabase())
                .withSsl(config.isSsl())
                .withTimeout(config.getConnectionTimeout());

        return RedisClient.create(clientResources, uriBuilder.build());
    }

    private GenericObjectPool<StatefulRedisConnection<byte[], byte[]>> createConnectionPool() {
        GenericObjectPoolConfig<StatefulRedisConnection<byte[], byte[]>> poolConfig =
                new GenericObjectPoolConfig<>();
        poolConfig.setMinIdle(config.getPoolMinIdle());
        poolConfig.setMaxIdle(config.getPoolMaxIdle());
        poolConfig.setMaxTotal(config.getPoolMaxTotal());
        poolConfig.setMaxWait(Duration.ofSeconds(10));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        return ConnectionPoolSupport.createGenericObjectPool(
                () -> redisClient.connect(ByteArrayCodec.INSTANCE),
                poolConfig
        );
    }

    /**
     * Executes a command using a pooled connection.
     *
     * @param action the action to execute with Redis commands
     * @param <T> the return type
     * @return the result of the action
     */
    public <T> T execute(RedisCallback<T> action) {
        try (StatefulRedisConnection<byte[], byte[]> connection = connectionPool.borrowObject()) {
            return action.doInRedis(connection.sync());
        } catch (Exception e) {
            throw new RedisConnectionException("Failed to execute Redis command", e);
        }
    }

    /**
     * Executes a command without return value.
     *
     * @param action the action to execute
     */
    public void executeVoid(RedisVoidCallback action) {
        execute(commands -> {
            action.doInRedis(commands);
            return null;
        });
    }

    /**
     * Gets the key prefix configured for this connection.
     *
     * @return the key prefix
     */
    public String getKeyPrefix() {
        return config.getKeyPrefix();
    }

    /**
     * Prefixes a key with the configured prefix.
     *
     * @param key the original key
     * @return the prefixed key
     */
    public byte[] prefixKey(byte[] key) {
        if (config.getKeyPrefix().isEmpty()) {
            return key;
        }
        byte[] prefix = config.getKeyPrefix().getBytes();
        byte[] result = new byte[prefix.length + key.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(key, 0, result, prefix.length, key.length);
        return result;
    }

    /**
     * Creates a new Pub/Sub connection.
     *
     * <p>The caller is responsible for closing this connection.</p>
     *
     * @return a new stateful Pub/Sub connection
     */
    public StatefulRedisPubSubConnection<String, String> getPubSubConnection() {
        return redisClient.connectPubSub(StringCodec.UTF8);
    }

    /**
     * Executes a command using a String-based connection.
     *
     * @param action the action to execute with String Redis commands
     * @param <T> the return type
     * @return the result of the action
     */
    public <T> T executeWithConnection(StringRedisCallback<T> action) {
        try (StatefulRedisConnection<String, String> connection = redisClient.connect(StringCodec.UTF8)) {
            return action.doInRedis(connection.sync());
        } catch (Exception e) {
            throw new RedisConnectionException("Failed to execute Redis command", e);
        }
    }

    /**
     * Callback interface for String-based Redis operations.
     */
    @FunctionalInterface
    public interface StringRedisCallback<T> {
        T doInRedis(RedisCommands<String, String> commands);
    }

    @Override
    public void close() {
        if (connectionPool != null) {
            connectionPool.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        clientResources.shutdown();
    }

    /**
     * Callback interface for Redis operations.
     */
    @FunctionalInterface
    public interface RedisCallback<T> {
        T doInRedis(RedisCommands<byte[], byte[]> commands);
    }

    /**
     * Callback interface for void Redis operations.
     */
    @FunctionalInterface
    public interface RedisVoidCallback {
        void doInRedis(RedisCommands<byte[], byte[]> commands);
    }

    /**
     * Exception thrown when Redis connection fails.
     */
    public static class RedisConnectionException extends RuntimeException {
        public RedisConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
