package io.resilio.core.lifecycle;

import io.resilio.core.cache.LruCache;
import io.resilio.core.cache.TtlCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ResilioResourceManager - GOVERNANCE V16.
 *
 * Verifies:
 * - Resource registration and deregistration
 * - Idle-based cleanup
 * - Memory release functionality
 * - Metrics tracking
 */
@DisplayName("ResilioResourceManager")
class ResilioResourceManagerTest {

    private ResilioResourceManager manager;

    @BeforeEach
    void setUp() {
        // Create a new manager for each test with short intervals for testing
        manager = ResilioResourceManager.builder()
                .idleTimeout(Duration.ofMillis(100))
                .cleanupInterval(Duration.ofMillis(50))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Nested
    @DisplayName("Resource Registration")
    class ResourceRegistration {

        @Test
        @DisplayName("should register TtlCache automatically")
        void shouldRegisterTtlCacheAutomatically() {
            // Create a TtlCache - it auto-registers with global manager
            TtlCache<String, String> cache = TtlCache.<String, String>builder()
                    .name("test-ttl-cache")
                    .ttl(Duration.ofMinutes(5))
                    .maxSize(100)
                    .build();

            // Put some data
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            // Verify via global manager
            ResilioResourceManager globalManager = ResilioResourceManager.getInstance();
            List<ResilioResourceManager.ResourceSnapshot> snapshots = globalManager.getResourceSnapshots();

            assertThat(snapshots)
                    .extracting(ResilioResourceManager.ResourceSnapshot::name)
                    .contains("test-ttl-cache");
        }

        @Test
        @DisplayName("should register LruCache automatically")
        void shouldRegisterLruCacheAutomatically() {
            // Create an LruCache - it auto-registers with global manager
            LruCache<String, String> cache = LruCache.<String, String>builder()
                    .name("test-lru-cache")
                    .maxSize(100)
                    .build();

            // Put some data
            cache.put("key1", "value1");

            // Verify via global manager
            ResilioResourceManager globalManager = ResilioResourceManager.getInstance();
            List<ResilioResourceManager.ResourceSnapshot> snapshots = globalManager.getResourceSnapshots();

            assertThat(snapshots)
                    .extracting(ResilioResourceManager.ResourceSnapshot::name)
                    .contains("test-lru-cache");
        }

        @Test
        @DisplayName("should track item count correctly")
        void shouldTrackItemCountCorrectly() {
            TtlCache<String, Integer> cache = TtlCache.<String, Integer>builder()
                    .name("item-count-test")
                    .ttl(Duration.ofMinutes(1))
                    .maxSize(100)
                    .build();

            // Add items
            for (int i = 0; i < 50; i++) {
                cache.put("key" + i, i);
            }

            assertThat(cache.itemCount()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("Memory Release")
    class MemoryRelease {

        @Test
        @DisplayName("should release all items from TtlCache")
        void shouldReleaseAllItemsFromTtlCache() {
            TtlCache<String, String> cache = TtlCache.<String, String>builder()
                    .name("release-test-ttl")
                    .ttl(Duration.ofMinutes(5))
                    .maxSize(100)
                    .build();

            // Add items
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");

            assertThat(cache.itemCount()).isEqualTo(3);

            // Release all
            long released = cache.releaseAll();

            assertThat(released).isEqualTo(3);
            assertThat(cache.itemCount()).isZero();
        }

        @Test
        @DisplayName("should release all items from LruCache")
        void shouldReleaseAllItemsFromLruCache() {
            LruCache<String, String> cache = LruCache.<String, String>builder()
                    .name("release-test-lru")
                    .maxSize(100)
                    .build();

            // Add items
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            assertThat(cache.itemCount()).isEqualTo(2);

            // Release all
            long released = cache.releaseAll();

            assertThat(released).isEqualTo(2);
            assertThat(cache.itemCount()).isZero();
        }

        @Test
        @DisplayName("should release expired items from TtlCache")
        void shouldReleaseExpiredItemsFromTtlCache() throws InterruptedException {
            TtlCache<String, String> cache = TtlCache.<String, String>builder()
                    .name("expire-test")
                    .ttl(Duration.ofMillis(50))
                    .maxSize(100)
                    .build();

            // Add items
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            assertThat(cache.itemCount()).isEqualTo(2);

            // Wait for expiration
            Thread.sleep(100);

            // Release expired
            long released = cache.releaseExpired();

            assertThat(released).isEqualTo(2);
            assertThat(cache.itemCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Idle Detection")
    class IdleDetection {

        @Test
        @DisplayName("should detect cache as idle after threshold")
        void shouldDetectCacheAsIdleAfterThreshold() throws InterruptedException {
            TtlCache<String, String> cache = TtlCache.<String, String>builder()
                    .name("idle-detection-test")
                    .ttl(Duration.ofMinutes(5))
                    .maxSize(100)
                    .build();

            // Access cache
            cache.put("key", "value");

            // Initially not idle
            assertThat(cache.isIdleSince(50)).isFalse();

            // Wait past threshold
            Thread.sleep(100);

            // Now should be idle
            assertThat(cache.isIdleSince(50)).isTrue();
        }

        @Test
        @DisplayName("should reset idle on access")
        void shouldResetIdleOnAccess() throws InterruptedException {
            TtlCache<String, String> cache = TtlCache.<String, String>builder()
                    .name("idle-reset-test")
                    .ttl(Duration.ofMinutes(5))
                    .maxSize(100)
                    .build();

            cache.put("key", "value");

            // Wait
            Thread.sleep(60);

            // Access cache
            cache.getIfPresent("key");

            // Should not be idle (just accessed)
            assertThat(cache.isIdleSince(50)).isFalse();
        }
    }

    @Nested
    @DisplayName("Metrics")
    class Metrics {

        @Test
        @DisplayName("should report estimated memory correctly")
        void shouldReportEstimatedMemoryCorrectly() {
            TtlCache<String, String> cache = TtlCache.<String, String>builder()
                    .name("memory-test")
                    .ttl(Duration.ofMinutes(5))
                    .maxSize(100)
                    .build();

            // Empty cache should have 0 memory
            assertThat(cache.estimatedMemoryBytes()).isZero();

            // Add items
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            // Should report non-zero memory
            assertThat(cache.estimatedMemoryBytes()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should track manager metrics")
        void shouldTrackManagerMetrics() {
            // Create a test resource
            TestResource resource = new TestResource("metrics-test", 100);
            manager.register(resource);

            ResilioResourceManager.Metrics metrics = manager.getMetrics();

            assertThat(metrics.resourceCount()).isGreaterThanOrEqualTo(1);
            assertThat(metrics.totalItems()).isGreaterThanOrEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Manager Lifecycle")
    class ManagerLifecycle {

        @Test
        @DisplayName("should release all on shutdown")
        void shouldReleaseAllOnShutdown() {
            TestResource resource = new TestResource("shutdown-test", 50);
            manager.register(resource);

            assertThat(resource.itemCount()).isEqualTo(50);

            // Shutdown manager
            manager.close();

            // Resource should be cleared
            assertThat(resource.itemCount()).isZero();
        }

        @Test
        @DisplayName("should trigger cleanup after idle timeout")
        void shouldTriggerCleanupAfterIdleTimeout() throws InterruptedException {
            // Create manager with very short timeout
            ResilioResourceManager shortManager = ResilioResourceManager.builder()
                    .idleTimeout(Duration.ofMillis(50))
                    .cleanupInterval(Duration.ofMillis(25))
                    .build();

            try {
                TestResource resource = new TestResource("auto-cleanup-test", 100);
                shortManager.register(resource);

                // Record activity
                shortManager.recordActivity();

                // Initially has items
                assertThat(resource.itemCount()).isEqualTo(100);

                // Wait for idle cleanup (timeout + cleanup interval + buffer)
                Thread.sleep(200);

                // Should have been cleaned up
                assertThat(resource.itemCount()).isZero();
            } finally {
                shortManager.close();
            }
        }
    }

    /**
     * Simple test implementation of ManagedResource.
     */
    private static class TestResource implements ManagedResource {
        private final String name;
        private long items;
        private long lastAccess;

        TestResource(String name, long initialItems) {
            this.name = name;
            this.items = initialItems;
            this.lastAccess = System.currentTimeMillis();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long estimatedMemoryBytes() {
            return items * 100; // 100 bytes per item
        }

        @Override
        public long itemCount() {
            return items;
        }

        @Override
        public long releaseAll() {
            long released = items;
            items = 0;
            return released;
        }

        @Override
        public long releaseExpired() {
            return 0; // No expiration in test
        }

        @Override
        public long lastAccessTimeMillis() {
            return lastAccess;
        }

        void access() {
            lastAccess = System.currentTimeMillis();
        }
    }
}
