package io.resilio.core.stats;

/**
 * Interface for cache statistics reporting.
 *
 * <p>Implementations should use thread-safe counters (e.g., LongAdder)
 * for high-throughput scenarios.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * CacheStats stats = cache.stats();
 * double hitRate = stats.hitRate();
 * System.out.printf("Cache: %d hits, %d misses (%.1f%% hit rate)%n",
 *     stats.hitCount(), stats.missCount(), hitRate * 100);
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public interface CacheStats {

    /**
     * Returns the number of cache hits.
     * @return total hit count since cache creation or last reset
     */
    long hitCount();

    /**
     * Returns the number of cache misses.
     * @return total miss count since cache creation or last reset
     */
    long missCount();

    /**
     * Returns the total number of cache accesses (hits + misses).
     * @return total request count
     */
    default long requestCount() {
        return hitCount() + missCount();
    }

    /**
     * Returns the cache hit rate as a ratio between 0.0 and 1.0.
     * @return hit rate, or 0.0 if no requests have been made
     */
    default double hitRate() {
        long requests = requestCount();
        return requests == 0 ? 0.0 : (double) hitCount() / requests;
    }

    /**
     * Returns the current number of entries in the cache.
     * @return cache size
     */
    long size();

    /**
     * Returns the number of evictions performed.
     * @return eviction count
     */
    default long evictionCount() {
        return 0;
    }

    /**
     * Resets all statistics counters to zero.
     * <p>Not all implementations support this operation.</p>
     */
    default void reset() {
        // Optional operation
    }

    /**
     * Returns a snapshot of the current statistics.
     * @return immutable stats snapshot
     */
    default StatsSnapshot snapshot() {
        return new StatsSnapshot(hitCount(), missCount(), size(), evictionCount());
    }

    /**
     * Immutable snapshot of cache statistics at a point in time.
     */
    record StatsSnapshot(long hits, long misses, long size, long evictions) {
        public long requests() {
            return hits + misses;
        }

        public double hitRate() {
            long req = requests();
            return req == 0 ? 0.0 : (double) hits / req;
        }

        @Override
        public String toString() {
            return String.format("CacheStats[hits=%d, misses=%d, size=%d, evictions=%d, hitRate=%.2f%%]",
                    hits, misses, size, evictions, hitRate() * 100);
        }
    }
}
