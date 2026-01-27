package io.resilio.core.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for cache instances.
 *
 * <p>Use the builder pattern for fluent configuration:</p>
 * <pre>{@code
 * CacheConfig config = CacheConfig.builder()
 *     .name("users")
 *     .ttl(Duration.ofMinutes(5))
 *     .maxSize(1000)
 *     .recordStats(true)
 *     .build();
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public record CacheConfig(
        String name,
        Duration ttl,
        int maxSize,
        boolean recordStats,
        EvictionPolicy evictionPolicy
) {

    /**
     * Default TTL: 5 minutes.
     */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /**
     * Default max size: 1000 entries.
     */
    public static final int DEFAULT_MAX_SIZE = 1000;

    /**
     * Eviction policies supported by RESILIO caches.
     */
    public enum EvictionPolicy {
        /** Least Recently Used - evicts entries that haven't been accessed recently */
        LRU,
        /** Least Frequently Used - evicts entries with lowest access count */
        LFU,
        /** First In First Out - evicts oldest entries */
        FIFO,
        /** Time-To-Live based - evicts entries that have expired */
        TTL,
        /** No automatic eviction (use with caution) */
        NONE
    }

    public CacheConfig {
        Objects.requireNonNull(name, "name must not be null");
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        if (ttl != null && ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be negative");
        }
        if (evictionPolicy == null) {
            evictionPolicy = ttl != null ? EvictionPolicy.TTL : EvictionPolicy.LRU;
        }
    }

    /**
     * Creates a new builder for CacheConfig.
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a default configuration with the given name.
     * @param name the cache name
     * @return default configuration
     */
    public static CacheConfig defaultConfig(String name) {
        return builder().name(name).build();
    }

    /**
     * Builder for CacheConfig.
     */
    public static class Builder {
        private String name = "default";
        private Duration ttl = DEFAULT_TTL;
        private int maxSize = DEFAULT_MAX_SIZE;
        private boolean recordStats = true;
        private EvictionPolicy evictionPolicy = null;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder recordStats(boolean recordStats) {
            this.recordStats = recordStats;
            return this;
        }

        public Builder evictionPolicy(EvictionPolicy evictionPolicy) {
            this.evictionPolicy = evictionPolicy;
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(name, ttl, maxSize, recordStats, evictionPolicy);
        }
    }
}
