package io.resilio.core;

import io.resilio.core.cache.Cache;
import io.resilio.core.cache.CacheConfig;
import io.resilio.core.cache.LruCache;
import io.resilio.core.cache.TtlCache;
import io.resilio.core.circuit.CircuitBreaker;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Main entry point for the RESILIO caching framework.
 *
 * <p>Provides fluent builders for creating caches, circuit breakers,
 * and other resilience components.</p>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * // TTL-based cache
 * Cache<String, User> userCache = Resilio.<String, User>cache()
 *     .name("users")
 *     .ttl(Duration.ofMinutes(5))
 *     .maxSize(1000)
 *     .build();
 *
 * // LRU cache
 * Cache<String, ParsedQuery> queryCache = Resilio.<String, ParsedQuery>lru()
 *     .name("queries")
 *     .maxSize(256)
 *     .build();
 *
 * // Circuit breaker
 * CircuitBreaker breaker = Resilio.circuitBreaker()
 *     .name("external-api")
 *     .failureThreshold(5)
 *     .resetTimeout(Duration.ofSeconds(30))
 *     .build();
 * }</pre>
 *
 * <h2>Design Philosophy</h2>
 * <ul>
 *   <li><b>Resilience First</b> - Built-in circuit breakers and graceful degradation</li>
 *   <li><b>Zero Dependencies</b> - Core module has no external dependencies</li>
 *   <li><b>Performance</b> - Lock-free statistics, zero-allocation options</li>
 *   <li><b>Observable</b> - Built-in metrics for all components</li>
 * </ul>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public final class Resilio {

    /** RESILIO version */
    public static final String VERSION = "1.0.0-SNAPSHOT";

    private Resilio() {
        // Static utility class
    }

    /**
     * Creates a TTL-based cache builder.
     *
     * @param <K> key type
     * @param <V> value type
     * @return TTL cache builder
     */
    public static <K, V> TtlCacheBuilder<K, V> cache() {
        return new TtlCacheBuilder<>();
    }

    /**
     * Creates an LRU cache builder.
     *
     * @param <K> key type
     * @param <V> value type
     * @return LRU cache builder
     */
    public static <K, V> LruCacheBuilder<K, V> lru() {
        return new LruCacheBuilder<>();
    }

    /**
     * Creates a circuit breaker builder.
     *
     * @return circuit breaker builder
     */
    public static CircuitBreakerBuilder circuitBreaker() {
        return new CircuitBreakerBuilder();
    }

    /**
     * Fluent builder for TTL caches with optional circuit breaker.
     */
    public static class TtlCacheBuilder<K, V> {
        private String name = "cache";
        private Duration ttl = Duration.ofMinutes(5);
        private int maxSize = 1000;
        private CircuitBreaker circuitBreaker;
        private Function<K, V> fallbackLoader;

        public TtlCacheBuilder<K, V> name(String name) {
            this.name = name;
            return this;
        }

        public TtlCacheBuilder<K, V> ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public TtlCacheBuilder<K, V> maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /**
         * Adds a circuit breaker to protect cache loading operations.
         */
        public TtlCacheBuilder<K, V> withCircuitBreaker(Consumer<CircuitBreakerBuilder> config) {
            CircuitBreakerBuilder builder = new CircuitBreakerBuilder();
            config.accept(builder);
            this.circuitBreaker = builder.build();
            return this;
        }

        /**
         * Sets a fallback loader for when the circuit breaker is open.
         */
        public TtlCacheBuilder<K, V> fallback(Function<K, V> fallbackLoader) {
            this.fallbackLoader = fallbackLoader;
            return this;
        }

        public Cache<K, V> build() {
            TtlCache<K, V> cache = TtlCache.<K, V>builder()
                    .name(name)
                    .ttl(ttl)
                    .maxSize(maxSize)
                    .build();

            if (circuitBreaker != null) {
                return new CircuitBreakerCache<>(cache, circuitBreaker, fallbackLoader);
            }

            return cache;
        }
    }

    /**
     * Fluent builder for LRU caches.
     */
    public static class LruCacheBuilder<K, V> {
        private String name = "lru-cache";
        private int maxSize = 256;

        public LruCacheBuilder<K, V> name(String name) {
            this.name = name;
            return this;
        }

        public LruCacheBuilder<K, V> maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Cache<K, V> build() {
            return LruCache.<K, V>builder()
                    .name(name)
                    .maxSize(maxSize)
                    .build();
        }
    }

    /**
     * Fluent builder for circuit breakers.
     */
    public static class CircuitBreakerBuilder {
        private String name = "circuit-breaker";
        private int failureThreshold = 5;
        private Duration resetTimeout = Duration.ofSeconds(30);

        public CircuitBreakerBuilder name(String name) {
            this.name = name;
            return this;
        }

        public CircuitBreakerBuilder failureThreshold(int threshold) {
            this.failureThreshold = threshold;
            return this;
        }

        public CircuitBreakerBuilder resetTimeout(Duration timeout) {
            this.resetTimeout = timeout;
            return this;
        }

        public CircuitBreaker build() {
            return CircuitBreaker.builder()
                    .name(name)
                    .failureThreshold(failureThreshold)
                    .resetTimeout(resetTimeout)
                    .build();
        }
    }

    /**
     * Cache wrapper that adds circuit breaker protection.
     */
    private static class CircuitBreakerCache<K, V> implements Cache<K, V> {
        private final Cache<K, V> delegate;
        private final CircuitBreaker circuitBreaker;
        private final Function<K, V> fallbackLoader;

        CircuitBreakerCache(Cache<K, V> delegate, CircuitBreaker circuitBreaker,
                           Function<K, V> fallbackLoader) {
            this.delegate = delegate;
            this.circuitBreaker = circuitBreaker;
            this.fallbackLoader = fallbackLoader;
        }

        @Override
        public V get(K key, Function<K, V> loader) {
            return circuitBreaker.execute(
                    () -> delegate.get(key, loader),
                    () -> fallbackLoader != null ? fallbackLoader.apply(key) : null
            );
        }

        @Override
        public java.util.Optional<V> getIfPresent(K key) {
            return delegate.getIfPresent(key);
        }

        @Override
        public void put(K key, V value) {
            delegate.put(key, value);
        }

        @Override
        public void invalidate(K key) {
            delegate.invalidate(key);
        }

        @Override
        public void invalidateAll() {
            delegate.invalidateAll();
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public io.resilio.core.stats.CacheStats stats() {
            return delegate.stats();
        }

        @Override
        public String name() {
            return delegate.name();
        }

        /**
         * Returns the underlying circuit breaker.
         */
        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }
    }
}
