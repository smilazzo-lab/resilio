package io.resilio.core.cache;

import io.resilio.core.stats.CacheStats;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Thread-safe cache implementation with Time-To-Live (TTL) based expiration.
 *
 * <p>Entries automatically expire after the configured TTL. Expired entries
 * are lazily evicted on access and periodically during size checks.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Configurable TTL per cache</li>
 *   <li>Thread-safe using ConcurrentHashMap</li>
 *   <li>Lock-free statistics with LongAdder</li>
 *   <li>Lazy expiration on access</li>
 *   <li>Optional max size with eviction</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * TtlCache<String, User> cache = TtlCache.<String, User>builder()
 *     .name("users")
 *     .ttl(Duration.ofMinutes(5))
 *     .maxSize(1000)
 *     .build();
 *
 * User user = cache.get("user:123", id -> userService.findById(id));
 * }</pre>
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of cached values
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class TtlCache<K, V> implements Cache<K, V>, CacheStats {

    private final ConcurrentHashMap<K, CacheEntry<V>> cache;
    private final Duration ttl;
    private final int maxSize;
    private final String name;

    // Statistics
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    /**
     * Internal cache entry with expiration time.
     */
    private record CacheEntry<V>(V value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private TtlCache(Builder<K, V> builder) {
        this.name = builder.name;
        this.ttl = builder.ttl;
        this.maxSize = builder.maxSize;
        this.cache = new ConcurrentHashMap<>(Math.min(maxSize, 256));
    }

    /**
     * Creates a new builder for TtlCache.
     * @param <K> key type
     * @param <V> value type
     * @return a new builder instance
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        CacheEntry<V> entry = cache.get(key);

        if (entry != null && !entry.isExpired()) {
            hits.increment();
            return entry.value();
        }

        // Miss - compute value
        misses.increment();
        V value = loader.apply(key);

        if (value != null) {
            put(key, value);
        } else if (entry != null) {
            // Remove expired entry
            cache.remove(key);
        }

        return value;
    }

    @Override
    public Optional<V> getIfPresent(K key) {
        CacheEntry<V> entry = cache.get(key);

        if (entry == null) {
            misses.increment();
            return Optional.empty();
        }

        if (entry.isExpired()) {
            cache.remove(key);
            misses.increment();
            return Optional.empty();
        }

        hits.increment();
        return Optional.of(entry.value());
    }

    @Override
    public void put(K key, V value) {
        if (value == null) {
            return;
        }

        // Check size limit before adding
        if (cache.size() >= maxSize) {
            evictExpired();
            // If still at capacity, evict oldest entries
            if (cache.size() >= maxSize) {
                evictOldest(maxSize / 10); // Evict 10%
            }
        }

        Instant expiresAt = Instant.now().plus(ttl);
        cache.put(key, new CacheEntry<>(value, expiresAt));
    }

    @Override
    public void invalidate(K key) {
        cache.remove(key);
    }

    @Override
    public void invalidateAll() {
        cache.clear();
    }

    @Override
    public long size() {
        return cache.size();
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

    /**
     * Removes all expired entries from the cache.
     * @return number of entries evicted
     */
    public int evictExpired() {
        int count = 0;
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
                evictions.increment();
                count++;
            }
        }
        return count;
    }

    /**
     * Evicts the oldest entries to make room for new ones.
     * @param count number of entries to evict
     */
    private void evictOldest(int count) {
        cache.entrySet().stream()
                .sorted((a, b) -> a.getValue().expiresAt().compareTo(b.getValue().expiresAt()))
                .limit(count)
                .map(entry -> entry.getKey())
                .forEach(key -> {
                    cache.remove(key);
                    evictions.increment();
                });
    }

    /**
     * Returns the configured TTL.
     * @return TTL duration
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * Returns the configured max size.
     * @return max size
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Builder for TtlCache.
     */
    public static class Builder<K, V> {
        private String name = "ttl-cache";
        private Duration ttl = Duration.ofMinutes(5);
        private int maxSize = 1000;

        public Builder<K, V> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<K, V> ttl(Duration ttl) {
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("TTL must be positive");
            }
            this.ttl = ttl;
            return this;
        }

        public Builder<K, V> maxSize(int maxSize) {
            if (maxSize <= 0) {
                throw new IllegalArgumentException("Max size must be positive");
            }
            this.maxSize = maxSize;
            return this;
        }

        public TtlCache<K, V> build() {
            return new TtlCache<>(this);
        }
    }
}
