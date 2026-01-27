package io.resilio.core.cache;

import io.resilio.core.stats.CacheStats;

import java.util.Optional;
import java.util.function.Function;

/**
 * Core cache interface for RESILIO caching framework.
 *
 * <p>All implementations are thread-safe and designed for high-throughput
 * concurrent access.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Cache<String, User> cache = Resilio.cache()
 *     .ttl(Duration.ofMinutes(5))
 *     .maxSize(1000)
 *     .build();
 *
 * // Get or compute
 * User user = cache.get("user:123", key -> userService.findById(key));
 *
 * // Direct put
 * cache.put("user:456", newUser);
 *
 * // Check and get
 * Optional<User> cached = cache.getIfPresent("user:789");
 * }</pre>
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of cached values
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public interface Cache<K, V> {

    /**
     * Returns the value associated with the key, computing it if absent.
     *
     * <p>If the key is not present in the cache, the loader function is called
     * to compute the value, which is then stored in the cache and returned.</p>
     *
     * @param key the key whose associated value is to be returned
     * @param loader the function to compute the value if absent
     * @return the current (existing or computed) value associated with the key
     * @throws NullPointerException if key or loader is null
     */
    V get(K key, Function<K, V> loader);

    /**
     * Returns the value associated with the key if present.
     *
     * @param key the key whose associated value is to be returned
     * @return an Optional containing the value, or empty if not present
     */
    Optional<V> getIfPresent(K key);

    /**
     * Associates the specified value with the specified key.
     *
     * @param key the key with which the specified value is to be associated
     * @param value the value to be associated with the specified key
     */
    void put(K key, V value);

    /**
     * Removes the mapping for the specified key if present.
     *
     * @param key the key whose mapping is to be removed
     */
    void invalidate(K key);

    /**
     * Removes all entries from the cache.
     */
    void invalidateAll();

    /**
     * Returns the approximate number of entries in this cache.
     *
     * @return the estimated number of entries
     */
    long size();

    /**
     * Returns statistics for this cache.
     *
     * @return cache statistics
     */
    CacheStats stats();

    /**
     * Returns the name of this cache, if configured.
     *
     * @return cache name, or "unnamed" if not set
     */
    default String name() {
        return "unnamed";
    }
}
