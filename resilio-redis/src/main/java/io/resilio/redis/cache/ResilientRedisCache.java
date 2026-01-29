package io.resilio.redis.cache;

import io.resilio.core.circuit.CircuitBreaker;
import io.resilio.core.circuit.TryWithFallback;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resilient Redis Cache with explicit failure policy.
 *
 * <p>Wraps {@link RedisCache} with RESILIO resilience patterns, providing
 * the same interface used client-side in the profiling framework:</p>
 *
 * <pre>
 * ┌────────────────────────────────────────────────────────────────────┐
 * │               ResilientRedisCache                                  │
 * ├────────────────────────────────────────────────────────────────────┤
 * │                                                                    │
 * │  get(key, loader)                                                  │
 * │  ┌──────────────────────────────────────────────────────────────┐ │
 * │  │  TryWithFallback.of(                                         │ │
 * │  │      () -> redisCache.get(key),     // PRIMARY: Redis        │ │
 * │  │      () -> applyPolicy(loader)      // FALLBACK: policy      │ │
 * │  │  )                                                           │ │
 * │  │  .withCircuitBreaker(circuitBreaker)                         │ │
 * │  │  .execute()                                                  │ │
 * │  └──────────────────────────────────────────────────────────────┘ │
 * │                                                                    │
 * │  FailurePolicy:                                                    │
 * │    FALLBACK_TO_LOADER  → call loader directly (bypass cache)      │
 * │    RETURN_EMPTY        → return empty (no data)                   │
 * │    THROW_ERROR         → propagate exception                      │
 * │                                                                    │
 * └────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Usage in profiling-api (server-side)</h2>
 * <pre>{@code
 * ResilientRedisCache<String, RuleInfo> ruleCache = ResilientRedisCache.<String, RuleInfo>builder()
 *     .redisCache(redisCache)
 *     .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)  // ← EXPLICIT
 *     .build();
 *
 * // Same pattern as client-side TryWithFallback
 * Optional<RuleInfo> rule = ruleCache.get(ruleCode, code -> loadFromDb(code));
 * }</pre>
 *
 * @param <K> key type
 * @param <V> value type
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.1.0
 */
public class ResilientRedisCache<K, V> {

    private final RedisCache<K, V> delegate;
    private final CircuitBreaker circuitBreaker;
    private final FailurePolicy failurePolicy;

    /**
     * Failure policy for Redis operations.
     */
    public enum FailurePolicy {
        /**
         * On Redis failure, call the loader directly (bypass cache).
         * Data is served but not cached.
         */
        FALLBACK_TO_LOADER,

        /**
         * On Redis failure, return empty.
         * No data served.
         */
        RETURN_EMPTY,

        /**
         * On Redis failure, propagate the exception.
         * Operation fails explicitly.
         */
        THROW_ERROR
    }

    private ResilientRedisCache(Builder<K, V> builder) {
        this.delegate = builder.redisCache;
        this.failurePolicy = builder.failurePolicy;
        this.circuitBreaker = builder.circuitBreaker != null
            ? builder.circuitBreaker
            : CircuitBreaker.builder()
                .name("redis-cache")
                .failureThreshold(5)
                .resetTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Gets a value from cache, with resilient fallback.
     *
     * <p>Uses TryWithFallback pattern:</p>
     * <ul>
     *   <li>PRIMARY: get from Redis</li>
     *   <li>FALLBACK: apply FailurePolicy</li>
     * </ul>
     *
     * @param key the key
     * @param loader function to load value if not in cache
     * @return the value, or empty based on policy
     */
    public Optional<V> get(K key, Function<K, V> loader) {
        // ════════════════════════════════════════════════════════════════
        // TryWithFallback - stesso pattern del client-side
        // ════════════════════════════════════════════════════════════════
        return TryWithFallback.of(
                // PRIMARY: Redis cache
                () -> {
                    V cached = delegate.get(key, loader);
                    return Optional.ofNullable(cached);
                },
                // FALLBACK: apply policy
                () -> applyFailurePolicy(key, loader)
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();
    }

    /**
     * Gets a value from cache without loader.
     *
     * @param key the key
     * @return the cached value, or empty
     */
    public Optional<V> getIfPresent(K key) {
        return TryWithFallback.of(
                () -> delegate.getIfPresent(key),
                Optional::<V>empty
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();
    }

    /**
     * Puts a value into cache with resilience.
     *
     * @param key the key
     * @param value the value
     */
    public void put(K key, V value) {
        TryWithFallback.of(
                () -> {
                    delegate.put(key, value);
                    return null;
                },
                () -> null  // Silent fail on put
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();
    }

    /**
     * Puts a value with custom TTL.
     *
     * @param key the key
     * @param value the value
     * @param ttl the TTL
     */
    public void put(K key, V value, Duration ttl) {
        TryWithFallback.of(
                () -> {
                    delegate.put(key, value, ttl);
                    return null;
                },
                () -> null
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();
    }

    /**
     * Invalidates a key.
     *
     * @param key the key to invalidate
     */
    public void invalidate(K key) {
        TryWithFallback.of(
                () -> {
                    delegate.invalidate(key);
                    return null;
                },
                () -> null
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();
    }

    /**
     * Applies the configured failure policy.
     */
    private Optional<V> applyFailurePolicy(K key, Function<K, V> loader) {
        switch (failurePolicy) {
            case FALLBACK_TO_LOADER:
                // Bypass cache, load directly
                V value = loader.apply(key);
                return Optional.ofNullable(value);

            case RETURN_EMPTY:
                return Optional.empty();

            case THROW_ERROR:
            default:
                throw new RedisCacheException("Redis unavailable and policy is THROW_ERROR");
        }
    }

    /**
     * Returns the circuit breaker for monitoring.
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Returns the configured failure policy.
     */
    public FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    /**
     * Returns the underlying RedisCache.
     */
    public RedisCache<K, V> getDelegate() {
        return delegate;
    }

    /**
     * Creates a new builder.
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /**
     * Builder for ResilientRedisCache.
     */
    public static class Builder<K, V> {
        private RedisCache<K, V> redisCache;
        private FailurePolicy failurePolicy = FailurePolicy.FALLBACK_TO_LOADER;
        private CircuitBreaker circuitBreaker;

        /**
         * Sets the underlying RedisCache.
         */
        public Builder<K, V> redisCache(RedisCache<K, V> redisCache) {
            this.redisCache = redisCache;
            return this;
        }

        /**
         * Sets the failure policy - THIS IS THE KEY CONFIGURATION.
         *
         * @param policy what to do when Redis is unavailable
         */
        public Builder<K, V> failurePolicy(FailurePolicy policy) {
            this.failurePolicy = policy;
            return this;
        }

        /**
         * Sets a custom circuit breaker.
         */
        public Builder<K, V> circuitBreaker(CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
            return this;
        }

        public ResilientRedisCache<K, V> build() {
            if (redisCache == null) {
                throw new IllegalStateException("redisCache is required");
            }
            return new ResilientRedisCache<>(this);
        }
    }

    /**
     * Exception thrown when Redis is unavailable and policy is THROW_ERROR.
     */
    public static class RedisCacheException extends RuntimeException {
        public RedisCacheException(String message) {
            super(message);
        }

        public RedisCacheException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
