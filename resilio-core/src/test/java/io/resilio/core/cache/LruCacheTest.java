package io.resilio.core.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for LruCache.
 *
 * Tests cover:
 * - Basic get/put operations
 * - LRU eviction behavior
 * - Statistics tracking
 * - Thread safety
 * - Edge cases
 *
 * @author Test Engineer
 */
@DisplayName("LruCache")
class LruCacheTest {

    private LruCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = LruCache.<String, String>builder()
            .name("test-cache")
            .maxSize(3)
            .build();
    }

    // ========================================================================
    // BASIC OPERATIONS TESTS
    // ========================================================================

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("should get value using loader on miss")
        void shouldGetWithLoader() {
            AtomicInteger loaderCalls = new AtomicInteger(0);

            String result = cache.get("key1", k -> {
                loaderCalls.incrementAndGet();
                return "value1";
            });

            assertThat(result).isEqualTo("value1");
            assertThat(loaderCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return cached value on hit")
        void shouldReturnCachedValue() {
            AtomicInteger loaderCalls = new AtomicInteger(0);

            // First call - loader invoked
            cache.get("key1", k -> {
                loaderCalls.incrementAndGet();
                return "value1";
            });

            // Second call - should use cache
            String result = cache.get("key1", k -> {
                loaderCalls.incrementAndGet();
                return "different";
            });

            assertThat(result).isEqualTo("value1");
            assertThat(loaderCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should put value directly")
        void shouldPutValue() {
            cache.put("key1", "value1");

            Optional<String> result = cache.getIfPresent("key1");

            assertThat(result).contains("value1");
        }

        @Test
        @DisplayName("should return empty for missing key")
        void shouldReturnEmptyForMissingKey() {
            Optional<String> result = cache.getIfPresent("nonexistent");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should invalidate single key")
        void shouldInvalidateSingleKey() {
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            cache.invalidate("key1");

            assertThat(cache.getIfPresent("key1")).isEmpty();
            assertThat(cache.getIfPresent("key2")).contains("value2");
        }

        @Test
        @DisplayName("should invalidate all keys")
        void shouldInvalidateAll() {
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            cache.invalidateAll();

            assertThat(cache.size()).isZero();
        }

        @Test
        @DisplayName("should not put null values")
        void shouldNotPutNullValues() {
            cache.put("key1", null);

            assertThat(cache.getIfPresent("key1")).isEmpty();
            assertThat(cache.size()).isZero();
        }
    }

    // ========================================================================
    // LRU EVICTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("LRU Eviction")
    class LruEviction {

        @Test
        @DisplayName("should evict least recently used entry when full")
        void shouldEvictLru() {
            // Fill cache (maxSize=3)
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");

            // Add one more - should evict key1 (oldest)
            cache.put("key4", "value4");

            assertThat(cache.getIfPresent("key1")).isEmpty();
            assertThat(cache.getIfPresent("key2")).contains("value2");
            assertThat(cache.getIfPresent("key3")).contains("value3");
            assertThat(cache.getIfPresent("key4")).contains("value4");
            assertThat(cache.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("should update LRU order on access")
        void shouldUpdateLruOrderOnAccess() {
            // Fill cache
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");

            // Access key1 - makes it most recently used
            cache.getIfPresent("key1");

            // Add key4 - should evict key2 (now oldest)
            cache.put("key4", "value4");

            assertThat(cache.getIfPresent("key1")).contains("value1");
            assertThat(cache.getIfPresent("key2")).isEmpty(); // Evicted
            assertThat(cache.getIfPresent("key3")).contains("value3");
            assertThat(cache.getIfPresent("key4")).contains("value4");
        }

        @Test
        @DisplayName("should track eviction count")
        void shouldTrackEvictionCount() {
            // Fill cache
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");

            assertThat(cache.evictionCount()).isZero();

            // Cause 2 evictions
            cache.put("key4", "value4");
            cache.put("key5", "value5");

            assertThat(cache.evictionCount()).isEqualTo(2);
        }
    }

    // ========================================================================
    // STATISTICS TESTS
    // ========================================================================

    @Nested
    @DisplayName("Statistics")
    class Statistics {

        @Test
        @DisplayName("should track hit count")
        void shouldTrackHitCount() {
            cache.put("key1", "value1");

            cache.getIfPresent("key1");
            cache.getIfPresent("key1");
            cache.getIfPresent("key1");

            assertThat(cache.hitCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should track miss count")
        void shouldTrackMissCount() {
            cache.getIfPresent("missing1");
            cache.getIfPresent("missing2");

            assertThat(cache.missCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should calculate hit rate correctly")
        void shouldCalculateHitRate() {
            cache.put("key1", "value1");

            // 2 hits
            cache.getIfPresent("key1");
            cache.getIfPresent("key1");

            // 2 misses
            cache.getIfPresent("missing1");
            cache.getIfPresent("missing2");

            // Hit rate = 2 / 4 = 0.5
            assertThat(cache.hitRate()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("should reset statistics")
        void shouldResetStatistics() {
            cache.put("key1", "value1");
            cache.getIfPresent("key1");
            cache.getIfPresent("missing");

            cache.reset();

            assertThat(cache.hitCount()).isZero();
            assertThat(cache.missCount()).isZero();
            assertThat(cache.evictionCount()).isZero();
        }

        @Test
        @DisplayName("should return cache name")
        void shouldReturnCacheName() {
            assertThat(cache.name()).isEqualTo("test-cache");
        }

        @Test
        @DisplayName("should return stats interface")
        void shouldReturnStatsInterface() {
            assertThat(cache.stats()).isNotNull();
            assertThat(cache.stats().hitCount()).isGreaterThanOrEqualTo(0);
        }
    }

    // ========================================================================
    // BUILDER TESTS
    // ========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should create with default values")
        void shouldCreateWithDefaults() {
            LruCache<String, String> defaultCache = LruCache.<String, String>builder().build();

            assertThat(defaultCache.name()).isEqualTo("lru-cache");
            assertThat(defaultCache.getMaxSize()).isEqualTo(256);
        }

        @Test
        @DisplayName("should reject non-positive max size")
        void shouldRejectInvalidMaxSize() {
            assertThatThrownBy(() ->
                LruCache.builder().maxSize(0).build()
            ).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() ->
                LruCache.builder().maxSize(-1).build()
            ).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should configure custom max size")
        void shouldConfigureMaxSize() {
            LruCache<String, String> customCache = LruCache.<String, String>builder()
                .maxSize(10)
                .build();

            assertThat(customCache.getMaxSize()).isEqualTo(10);
        }
    }

    // ========================================================================
    // THREAD SAFETY TESTS
    // ========================================================================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("should handle concurrent gets safely")
        void shouldHandleConcurrentGets() throws InterruptedException {
            LruCache<Integer, Integer> concurrentCache = LruCache.<Integer, Integer>builder()
                .maxSize(100)
                .build();

            // Pre-populate
            for (int i = 0; i < 100; i++) {
                concurrentCache.put(i, i * 10);
            }

            int threadCount = 50;
            int operationsPerThread = 1000;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(10);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            int key = i % 100;
                            Optional<Integer> value = concurrentCache.getIfPresent(key);
                            if (value.isPresent() && value.get() != key * 10) {
                                errors.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(errors.get()).isZero();
        }

        @Test
        @DisplayName("should handle concurrent puts safely")
        void shouldHandleConcurrentPuts() throws InterruptedException {
            LruCache<Integer, Integer> concurrentCache = LruCache.<Integer, Integer>builder()
                .maxSize(50)
                .build();

            int threadCount = 20;
            int operationsPerThread = 500;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(10);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            int key = threadId * 1000 + i;
                            concurrentCache.put(key % 100, key);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            // Size should not exceed maxSize
            assertThat(concurrentCache.size()).isLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("should handle mixed concurrent operations")
        void shouldHandleMixedConcurrentOperations() throws InterruptedException {
            LruCache<Integer, String> concurrentCache = LruCache.<Integer, String>builder()
                .maxSize(20)
                .build();

            int threadCount = 30;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(10);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            int key = (threadId + i) % 30;
                            switch (i % 4) {
                                case 0 -> concurrentCache.put(key, "value" + key);
                                case 1 -> concurrentCache.getIfPresent(key);
                                case 2 -> concurrentCache.get(key, k -> "loaded" + k);
                                case 3 -> concurrentCache.invalidate(key);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(concurrentCache.size()).isLessThanOrEqualTo(20);
        }
    }

    // ========================================================================
    // KEYS SNAPSHOT TEST
    // ========================================================================

    @Nested
    @DisplayName("Keys Snapshot")
    class KeysSnapshot {

        @Test
        @DisplayName("should return snapshot of current keys")
        void shouldReturnKeysSnapshot() {
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            Set<String> keys = cache.keys();

            assertThat(keys).containsExactlyInAnyOrder("key1", "key2");
        }

        @Test
        @DisplayName("keys snapshot should be independent")
        void keysSnapshotShouldBeIndependent() {
            cache.put("key1", "value1");

            Set<String> keys = cache.keys();
            cache.put("key2", "value2");

            // Original snapshot should not change
            assertThat(keys).containsExactly("key1");
        }
    }
}
