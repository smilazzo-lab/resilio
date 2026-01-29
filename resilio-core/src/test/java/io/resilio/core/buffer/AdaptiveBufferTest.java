package io.resilio.core.buffer;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for AdaptiveBuffer.
 *
 * Verifies:
 * - Capacity-based flush (watermarks)
 * - Traffic-aware rate limiting
 * - Age-based flush
 * - Idle timeout flush
 * - Thread safety
 */
@DisplayName("AdaptiveBuffer Tests")
class AdaptiveBufferTest {

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("should add and retrieve items")
        void shouldAddAndRetrieveItems() {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .batchSender(flushed::addAll)
                .build();

            buffer.put("key1", "value1");
            buffer.put("key2", "value2");

            assertThat(buffer.get("key1")).contains("value1");
            assertThat(buffer.get("key2")).contains("value2");
            assertThat(buffer.size()).isEqualTo(2);

            buffer.close();
        }

        @Test
        @DisplayName("should update existing items")
        void shouldUpdateExistingItems() {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .batchSender(flushed::addAll)
                .build();

            buffer.put("key1", "value1");
            buffer.put("key1", "value2"); // Update

            assertThat(buffer.get("key1")).contains("value2");
            assertThat(buffer.size()).isEqualTo(1);

            buffer.close();
        }

        @Test
        @DisplayName("should remove items")
        void shouldRemoveItems() {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .batchSender(flushed::addAll)
                .build();

            buffer.put("key1", "value1");
            var removed = buffer.remove("key1");

            assertThat(removed).contains("value1");
            assertThat(buffer.get("key1")).isEmpty();
            assertThat(buffer.size()).isEqualTo(0);

            buffer.close();
        }
    }

    @Nested
    @DisplayName("Capacity-Based Flush")
    class CapacityBasedFlush {

        @Test
        @DisplayName("should stay in LOW state when below lowWatermark")
        void shouldStayLowWhenBelowWatermark() {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .lowWatermark(0.3)  // 30 items
                .batchSender(flushed::addAll)
                .build();

            // Add 20 items (< 30% = LOW)
            for (int i = 0; i < 20; i++) {
                buffer.put("key" + i, "value" + i);
            }

            assertThat(buffer.getState()).isEqualTo(AdaptiveBuffer.BufferState.LOW);
            assertThat(buffer.size()).isEqualTo(20);

            buffer.close();
        }

        @Test
        @DisplayName("should transition to NORMAL when above lowWatermark")
        void shouldTransitionToNormal() {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .lowWatermark(0.3)
                .highWatermark(0.7)
                .batchSender(flushed::addAll)
                .build();

            // Add 40 items (40% = NORMAL)
            for (int i = 0; i < 40; i++) {
                buffer.put("key" + i, "value" + i);
            }

            assertThat(buffer.getState()).isEqualTo(AdaptiveBuffer.BufferState.NORMAL);

            buffer.close();
        }

        @Test
        @DisplayName("should transition to HIGH when above highWatermark")
        void shouldTransitionToHigh() {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .lowWatermark(0.3)
                .highWatermark(0.7)
                .criticalWatermark(0.9)
                .batchSender(flushed::addAll)
                .build();

            // Add 75 items (75% = HIGH)
            for (int i = 0; i < 75; i++) {
                buffer.put("key" + i, "value" + i);
            }

            assertThat(buffer.getState()).isEqualTo(AdaptiveBuffer.BufferState.HIGH);

            buffer.close();
        }

        @Test
        @DisplayName("should flush immediately at CRITICAL level")
        void shouldFlushAtCritical() throws InterruptedException {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .criticalWatermark(0.9)  // 90 items
                .batchSender(flushed::addAll)
                .build();

            // Add 95 items (> 90% = CRITICAL)
            for (int i = 0; i < 95; i++) {
                buffer.put("key" + i, "value" + i);
            }

            // Wait for flush
            Thread.sleep(200);

            // Should have flushed some items
            assertThat(flushed).isNotEmpty();
            assertThat(buffer.size()).isLessThan(95);

            buffer.close();
        }
    }

    @Nested
    @DisplayName("Idle Timeout Flush")
    class IdleTimeoutFlush {

        @Test
        @DisplayName("should flush after idle timeout")
        void shouldFlushAfterIdleTimeout() throws InterruptedException {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(1000)
                .idleTimeout(Duration.ofMillis(200))  // 200ms idle
                .batchSender(flushed::addAll)
                .build();

            // Add items
            for (int i = 0; i < 10; i++) {
                buffer.put("key" + i, "value" + i);
            }

            // Wait for idle timeout
            Thread.sleep(400);

            // Should have flushed
            assertThat(flushed).hasSize(10);
            assertThat(buffer.isEmpty()).isTrue();

            buffer.close();
        }
    }

    @Nested
    @DisplayName("Age-Based Flush")
    class AgeBasedFlush {

        @Test
        @DisplayName("should flush items older than maxAge")
        void shouldFlushOldItems() throws InterruptedException {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(1000)
                .maxItemAge(Duration.ofMillis(200))  // 200ms max age
                .idleTimeout(Duration.ofSeconds(60))  // Long idle to not interfere
                .batchSender(flushed::addAll)
                .build();

            // Add old items
            buffer.put("old1", "value1");
            buffer.put("old2", "value2");

            // Wait for items to become old
            Thread.sleep(300);

            // Add new item to prevent idle timeout
            buffer.put("new1", "valueNew");

            // Wait for age-based flush
            Thread.sleep(200);

            // Old items should be flushed
            assertThat(flushed).contains("value1", "value2");

            buffer.close();
        }
    }

    @Nested
    @DisplayName("Manual Flush")
    class ManualFlush {

        @Test
        @DisplayName("should flush on manual trigger")
        void shouldFlushManually() {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(1000)
                .idleTimeout(Duration.ofMinutes(10))  // Long timeout
                .batchSender(flushed::addAll)
                .build();

            for (int i = 0; i < 50; i++) {
                buffer.put("key" + i, "value" + i);
            }

            assertThat(buffer.size()).isEqualTo(50);

            // Manual flush
            buffer.flush();

            // Wait for flush to complete
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}

            assertThat(flushed).isNotEmpty();

            buffer.close();
        }
    }

    @Nested
    @DisplayName("Graceful Shutdown")
    class GracefulShutdown {

        @Test
        @DisplayName("should flush all items on close")
        void shouldFlushOnClose() {
            List<String> flushed = new CopyOnWriteArrayList<>();
            List<String> fallback = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(1000)
                .idleTimeout(Duration.ofMinutes(10))
                .batchSender(flushed::addAll)
                .fallbackHandler(fallback::add)
                .build();

            for (int i = 0; i < 100; i++) {
                buffer.put("key" + i, "value" + i);
            }

            buffer.close();

            // All items should be accounted for
            int total = flushed.size() + fallback.size();
            assertThat(total).isEqualTo(100);
        }

        @Test
        @DisplayName("should reject new items after close")
        void shouldRejectAfterClose() {
            List<String> flushed = new CopyOnWriteArrayList<>();
            List<String> fallback = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .batchSender(flushed::addAll)
                .fallbackHandler(fallback::add)
                .build();

            buffer.close();

            // Try to add after close
            buffer.put("late", "arrival");

            // Should go to fallback
            assertThat(fallback).contains("arrival");
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("should handle concurrent writes")
        void shouldHandleConcurrentWrites() throws InterruptedException {
            LongAdder flushedCount = new LongAdder();
            LongAdder fallbackCount = new LongAdder();

            AdaptiveBuffer<Integer, String> buffer = AdaptiveBuffer.<Integer, String>builder()
                .name("concurrent-test")
                .maxCapacity(10000)
                .lowWatermark(0.3)
                .highWatermark(0.7)
                .criticalWatermark(0.9)
                .idleTimeout(Duration.ofSeconds(1))
                .batchSender(batch -> flushedCount.add(batch.size()))
                .fallbackHandler(item -> fallbackCount.increment())
                .build();

            int threadCount = 10;
            int itemsPerThread = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < itemsPerThread; i++) {
                            int key = threadId * itemsPerThread + i;
                            buffer.put(key, "value-" + key);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            buffer.close();
            executor.shutdown();

            // All items should be accounted for (flushed OR fallback)
            long totalProcessed = flushedCount.sum() + fallbackCount.sum();
            assertThat(totalProcessed).isEqualTo(threadCount * itemsPerThread);
        }
    }

    @Nested
    @DisplayName("Metrics")
    class MetricsTests {

        @Test
        @DisplayName("should track metrics correctly")
        void shouldTrackMetrics() throws InterruptedException {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("metrics-test")
                .maxCapacity(100)
                .idleTimeout(Duration.ofMillis(100))
                .batchSender(flushed::addAll)
                .build();

            for (int i = 0; i < 50; i++) {
                buffer.put("key" + i, "value" + i);
            }

            // Wait for flush
            Thread.sleep(300);

            var metrics = buffer.getMetrics();

            assertThat(metrics.itemsAdded()).isEqualTo(50);
            assertThat(metrics.itemsFlushed()).isEqualTo(50);
            assertThat(metrics.flushCount()).isGreaterThan(0);

            buffer.close();
        }

        @Test
        @DisplayName("should calculate fill ratio")
        void shouldCalculateFillRatio() {
            List<String> flushed = new CopyOnWriteArrayList<>();

            AdaptiveBuffer<String, String> buffer = AdaptiveBuffer.<String, String>builder()
                .name("test")
                .maxCapacity(100)
                .batchSender(flushed::addAll)
                .build();

            for (int i = 0; i < 50; i++) {
                buffer.put("key" + i, "value" + i);
            }

            assertThat(buffer.getFillRatio()).isEqualTo(0.5);

            buffer.close();
        }
    }

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("should require batchSender")
        void shouldRequireBatchSender() {
            assertThatThrownBy(() ->
                AdaptiveBuffer.<String, String>builder()
                    .name("test")
                    .build()
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("batchSender");
        }

        @Test
        @DisplayName("should validate watermark order")
        void shouldValidateWatermarkOrder() {
            assertThatThrownBy(() ->
                AdaptiveBuffer.<String, String>builder()
                    .name("test")
                    .lowWatermark(0.8)   // > high!
                    .highWatermark(0.5)
                    .criticalWatermark(0.9)
                    .batchSender(batch -> {})
                    .build()
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("Watermarks");
        }
    }
}
