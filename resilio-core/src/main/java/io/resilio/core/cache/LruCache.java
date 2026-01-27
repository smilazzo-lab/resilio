package io.resilio.core.cache;

import io.resilio.core.stats.CacheStats;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Thread-safe Least Recently Used (LRU) cache implementation.
 *
 * <p>When the cache reaches its maximum size, the least recently accessed
 * entry is automatically evicted to make room for new entries.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Fixed maximum size with automatic eviction</li>
 *   <li>Thread-safe using ReadWriteLock</li>
 *   <li>O(1) access and eviction using LinkedHashMap</li>
 *   <li>Lock-free statistics with LongAdder</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * LruCache<String, ParsedSql> cache = LruCache.<String, ParsedSql>builder()
 *     .name("sql-cache")
 *     .maxSize(256)
 *     .build();
 *
 * ParsedSql parsed = cache.get(sql, SqlParser::parse);
 * }</pre>
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of cached values
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class LruCache<K, V> implements Cache<K, V>, CacheStats {

    private final Map<K, V> cache;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxSize;
    private final String name;

    // Statistics
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    @SuppressWarnings("serial")
    private LruCache(Builder<K, V> builder) {
        this.name = builder.name;
        this.maxSize = builder.maxSize;

        // LinkedHashMap with access-order for LRU behavior
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                boolean shouldRemove = size() > maxSize;
                if (shouldRemove) {
                    evictions.increment();
                }
                return shouldRemove;
            }
        };
    }

    /**
     * Creates a new builder for LruCache.
     * @param <K> key type
     * @param <V> value type
     * @return a new builder instance
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        // Try read lock first
        lock.readLock().lock();
        try {
            V value = cache.get(key);
            if (value != null) {
                hits.increment();
                return value;
            }
        } finally {
            lock.readLock().unlock();
        }

        // Miss - compute with write lock
        misses.increment();
        V value = loader.apply(key);

        if (value != null) {
            lock.writeLock().lock();
            try {
                // Double-check pattern
                V existing = cache.get(key);
                if (existing != null) {
                    return existing;
                }
                cache.put(key, value);
            } finally {
                lock.writeLock().unlock();
            }
        }

        return value;
    }

    @Override
    public Optional<V> getIfPresent(K key) {
        lock.readLock().lock();
        try {
            V value = cache.get(key);
            if (value != null) {
                hits.increment();
                return Optional.of(value);
            }
            misses.increment();
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        if (value == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            cache.put(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void invalidate(K key) {
        lock.writeLock().lock();
        try {
            cache.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void invalidateAll() {
        lock.writeLock().lock();
        try {
            cache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public long size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
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
     * Returns the configured max size.
     * @return max size
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Returns a snapshot of all keys currently in the cache.
     *
     * @return an unmodifiable set of keys
     */
    public Set<K> keys() {
        lock.readLock().lock();
        try {
            return new HashSet<>(cache.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Builder for LruCache.
     */
    public static class Builder<K, V> {
        private String name = "lru-cache";
        private int maxSize = 256;

        public Builder<K, V> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<K, V> maxSize(int maxSize) {
            if (maxSize <= 0) {
                throw new IllegalArgumentException("Max size must be positive");
            }
            this.maxSize = maxSize;
            return this;
        }

        public LruCache<K, V> build() {
            return new LruCache<>(this);
        }
    }
}
