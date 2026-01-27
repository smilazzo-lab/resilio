package io.resilio.redis.cache;

import io.resilio.core.cache.Cache;
import io.resilio.core.stats.CacheStats;
import io.resilio.redis.connection.RedisCacheConfig;
import io.resilio.redis.connection.RedisConnectionManager;
import io.resilio.redis.serializer.JacksonSerializer;
import io.resilio.redis.serializer.RedisSerializer;
import io.resilio.redis.serializer.StringSerializer;

import java.io.Closeable;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Redis-backed cache implementation for RESILIO framework.
 *
 * <p>Provides distributed caching with automatic serialization,
 * TTL support, and connection pooling.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Distributed caching via Redis</li>
 *   <li>Configurable TTL per cache</li>
 *   <li>Pluggable serialization (String, JSON, custom)</li>
 *   <li>Connection pooling via Lettuce</li>
 *   <li>Support for standalone, sentinel, and cluster modes</li>
 *   <li>Optional key prefix for namespace isolation</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RedisCacheConfig config = RedisCacheConfig.builder()
 *     .host("localhost")
 *     .port(6379)
 *     .keyPrefix("myapp:")
 *     .build();
 *
 * RedisCache<String, User> cache = RedisCache.<String, User>builder()
 *     .name("users")
 *     .config(config)
 *     .ttl(Duration.ofMinutes(30))
 *     .valueSerializer(new JacksonSerializer<>(User.class))
 *     .build();
 *
 * User user = cache.get("user:123", id -> userService.findById(id));
 * }</pre>
 *
 * @param <K> the type of keys (usually String)
 * @param <V> the type of cached values
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class RedisCache<K, V> implements Cache<K, V>, CacheStats, Closeable {

    private final String name;
    private final RedisConnectionManager connectionManager;
    private final RedisSerializer<K> keySerializer;
    private final RedisSerializer<V> valueSerializer;
    private final Duration ttl;
    private final boolean manageConnection;

    // Statistics
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    private RedisCache(Builder<K, V> builder) {
        this.name = builder.name;
        this.keySerializer = builder.keySerializer;
        this.valueSerializer = builder.valueSerializer;
        this.ttl = builder.ttl;

        if (builder.connectionManager != null) {
            this.connectionManager = builder.connectionManager;
            this.manageConnection = false;
        } else {
            this.connectionManager = new RedisConnectionManager(builder.config);
            this.manageConnection = true;
        }
    }

    /**
     * Creates a new RedisCache with the given configuration.
     *
     * @param connectionManager shared connection manager
     * @param keySerializer serializer for keys
     * @param valueSerializer serializer for values
     * @param ttl time-to-live for entries (null for no expiration)
     */
    public RedisCache(RedisConnectionManager connectionManager,
                      RedisSerializer<K> keySerializer,
                      RedisSerializer<V> valueSerializer,
                      Duration ttl) {
        this.name = "redis-cache";
        this.connectionManager = connectionManager;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        this.ttl = ttl;
        this.manageConnection = false;
    }

    /**
     * Creates a new builder for RedisCache.
     *
     * @param <K> key type
     * @param <V> value type
     * @return a new builder instance
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        Optional<V> cached = getIfPresent(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Cache miss - load value
        V value = loader.apply(key);
        if (value != null) {
            put(key, value);
        }
        return value;
    }

    /**
     * Gets a value from the cache.
     *
     * @param key the key
     * @return the cached value, or null if not found
     */
    public V get(K key) {
        return getIfPresent(key).orElse(null);
    }

    @Override
    public Optional<V> getIfPresent(K key) {
        byte[] keyBytes = serializeKey(key);

        byte[] valueBytes = connectionManager.execute(commands -> commands.get(keyBytes));

        if (valueBytes == null) {
            misses.increment();
            return Optional.empty();
        }

        hits.increment();
        return Optional.ofNullable(valueSerializer.deserialize(valueBytes));
    }

    @Override
    public void put(K key, V value) {
        if (value == null) {
            return;
        }

        byte[] keyBytes = serializeKey(key);
        byte[] valueBytes = valueSerializer.serialize(value);

        connectionManager.executeVoid(commands -> {
            if (ttl != null && !ttl.isZero()) {
                commands.psetex(keyBytes, ttl.toMillis(), valueBytes);
            } else {
                commands.set(keyBytes, valueBytes);
            }
        });
    }

    @Override
    public void invalidate(K key) {
        byte[] keyBytes = serializeKey(key);
        Long deleted = connectionManager.execute(commands -> commands.del(keyBytes));
        if (deleted != null && deleted > 0) {
            evictions.increment();
        }
    }

    /**
     * Removes a key from the cache.
     *
     * @param key the key to remove
     * @return true if the key was removed, false if it didn't exist
     */
    public boolean remove(K key) {
        byte[] keyBytes = serializeKey(key);
        Long deleted = connectionManager.execute(commands -> commands.del(keyBytes));
        if (deleted != null && deleted > 0) {
            evictions.increment();
            return true;
        }
        return false;
    }

    /**
     * Puts a value with a custom TTL.
     *
     * @param key the key
     * @param value the value
     * @param customTtl the TTL for this entry
     */
    public void put(K key, V value, Duration customTtl) {
        if (value == null) {
            return;
        }

        byte[] keyBytes = serializeKey(key);
        byte[] valueBytes = valueSerializer.serialize(value);

        connectionManager.executeVoid(commands -> {
            if (customTtl != null && !customTtl.isZero()) {
                commands.psetex(keyBytes, customTtl.toMillis(), valueBytes);
            } else {
                commands.set(keyBytes, valueBytes);
            }
        });
    }

    @Override
    public void invalidateAll() {
        // Note: This only clears keys with the configured prefix
        // For production, consider using SCAN instead of KEYS
        String prefix = connectionManager.getKeyPrefix();
        if (!prefix.isEmpty()) {
            // Use pattern matching to delete prefixed keys
            // This is a simplified implementation - production code should use SCAN
            connectionManager.executeVoid(commands -> {
                // KEYS is not recommended in production due to performance
                // Consider implementing with SCAN for large datasets
            });
        }
        // Reset stats even if we couldn't clear all keys
    }

    @Override
    public long size() {
        // Redis doesn't provide an efficient way to count keys by prefix
        // Return -1 to indicate unknown, or implement with SCAN
        return -1;
    }

    @Override
    public CacheStats stats() {
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    // CacheStats implementation

    @Override
    public long hitCount() {
        return hits.sum();
    }

    @Override
    public long missCount() {
        return misses.sum();
    }

    @Override
    public long evictionCount() {
        return evictions.sum();
    }

    @Override
    public void reset() {
        hits.reset();
        misses.reset();
        evictions.reset();
    }

    @Override
    public void close() {
        if (manageConnection) {
            connectionManager.close();
        }
    }

    /**
     * Returns the TTL configured for this cache.
     *
     * @return TTL duration, or null if no expiration
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * Returns the connection manager used by this cache.
     *
     * @return the connection manager
     */
    public RedisConnectionManager getConnectionManager() {
        return connectionManager;
    }

    private byte[] serializeKey(K key) {
        byte[] keyBytes = keySerializer.serialize(key);
        return connectionManager.prefixKey(keyBytes);
    }

    /**
     * Builder for RedisCache.
     */
    public static class Builder<K, V> {
        private String name = "redis-cache";
        private RedisCacheConfig config;
        private RedisConnectionManager connectionManager;
        private RedisSerializer<K> keySerializer;
        private RedisSerializer<V> valueSerializer;
        private Duration ttl;

        @SuppressWarnings("unchecked")
        public Builder() {
            // Default to String serializer for keys
            this.keySerializer = (RedisSerializer<K>) StringSerializer.INSTANCE;
        }

        public Builder<K, V> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<K, V> config(RedisCacheConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Uses a shared connection manager instead of creating a new one.
         *
         * @param connectionManager the shared connection manager
         */
        public Builder<K, V> connectionManager(RedisConnectionManager connectionManager) {
            this.connectionManager = connectionManager;
            return this;
        }

        public Builder<K, V> keySerializer(RedisSerializer<K> serializer) {
            this.keySerializer = serializer;
            return this;
        }

        public Builder<K, V> valueSerializer(RedisSerializer<V> serializer) {
            this.valueSerializer = serializer;
            return this;
        }

        /**
         * Configures JSON serialization for values using Jackson.
         *
         * @param valueType the class of values to serialize
         */
        public Builder<K, V> jsonValues(Class<V> valueType) {
            this.valueSerializer = new JacksonSerializer<>(valueType);
            return this;
        }

        public Builder<K, V> ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public RedisCache<K, V> build() {
            if (config == null && connectionManager == null) {
                throw new IllegalStateException("Either config or connectionManager must be provided");
            }
            if (valueSerializer == null) {
                throw new IllegalStateException("valueSerializer must be provided");
            }
            return new RedisCache<>(this);
        }
    }
}
