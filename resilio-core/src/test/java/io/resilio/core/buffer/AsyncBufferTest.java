package io.resilio.core.buffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for AsyncBuffer.
 *
 * Tests cover:
 * - Basic enqueue/batch operations
 * - Backpressure handling (queue full scenarios)
 * - Circuit breaker integration
 * - Fallback handler invocation
 * - Metrics tracking
 * - Graceful shutdown
 * - Thread safety
 *
 * @author Test Engineer
 */
@DisplayName("AsyncBuffer")
class AsyncBufferTest {

    private AsyncBuffer<String> buffer;
    private List<List<String>> sentBatches;
    private List<String> fallbackItems;

    @BeforeEach
    void setUp() {
        sentBatches = new CopyOnWriteArrayList<>();
        fallbackItems = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (buffer != null) {
            buffer.close();
        }
    }

    // ========================================================================
    // BASIC OPERATIONS TESTS
    // ========================================================================

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("should enqueue items successfully")
        void shouldEnqueueItems() {
            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(100)
                .batchSize(10)
                .flushIntervalMs(1000)
                .batchSender(batch -> sentBatches.add(new ArrayList<>(batch)))
                .build();

            boolean result1 = buffer.enqueue("item1");
            boolean result2 = buffer.enqueue("item2");
            boolean result3 = buffer.enqueue("item3");

            assertThat(result1).isTrue();
            assertThat(result2).isTrue();
            assertThat(result3).isTrue();
            assertThat(buffer.getItemsEnqueued()).isEqualTo(3);
        }

        @Test
        @DisplayName("should not enqueue null items")
        void shouldNotEnqueueNull() {
            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .batchSender(batch -> sentBatches.add(new ArrayList<>(batch)))
                .build();

            boolean result = buffer.enqueue(null);

            assertThat(result).isFalse();
            assertThat(buffer.getItemsEnqueued()).isZero();
        }

        @Test
        @DisplayName("should send batch when batch size is reached")
        void shouldSendBatchWhenSizeReached() throws InterruptedException {
            CountDownLatch batchSent = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(100)
                .batchSize(3)
                .flushIntervalMs(60000) // Long interval - rely on batch size
                .batchSender(batch -> {
                    sentBatches.add(new ArrayList<>(batch));
                    batchSent.countDown();
                })
                .build();

            buffer.enqueue("item1");
            buffer.enqueue("item2");
            buffer.enqueue("item3");

            boolean sent = batchSent.await(2, TimeUnit.SECONDS);

            assertThat(sent).isTrue();
            assertThat(sentBatches).hasSize(1);
            assertThat(sentBatches.get(0)).containsExactly("item1", "item2", "item3");
            assertThat(buffer.getItemsSent()).isEqualTo(3);
            assertThat(buffer.getBatchesSent()).isEqualTo(1);
        }

        @Test
        @DisplayName("should send batch on flush interval")
        void shouldSendBatchOnFlushInterval() throws InterruptedException {
            CountDownLatch batchSent = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(100)
                .batchSize(100) // Large batch size - rely on interval
                .flushIntervalMs(100)
                .batchSender(batch -> {
                    sentBatches.add(new ArrayList<>(batch));
                    batchSent.countDown();
                })
                .build();

            buffer.enqueue("item1");
            buffer.enqueue("item2");

            boolean sent = batchSent.await(2, TimeUnit.SECONDS);

            assertThat(sent).isTrue();
            assertThat(sentBatches).hasSize(1);
            assertThat(sentBatches.get(0)).containsExactly("item1", "item2");
        }

        @Test
        @DisplayName("should send batch on manual flush")
        void shouldSendBatchOnManualFlush() throws InterruptedException {
            CountDownLatch batchSent = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(100)
                .batchSize(100)
                .flushIntervalMs(60000)
                .batchSender(batch -> {
                    sentBatches.add(new ArrayList<>(batch));
                    batchSent.countDown();
                })
                .build();

            buffer.enqueue("item1");
            buffer.enqueue("item2");
            buffer.flush();

            boolean sent = batchSent.await(2, TimeUnit.SECONDS);

            assertThat(sent).isTrue();
            assertThat(sentBatches.get(0)).containsExactly("item1", "item2");
        }
    }

    // ========================================================================
    // BACKPRESSURE TESTS
    // ========================================================================

    @Nested
    @DisplayName("Backpressure Handling")
    class BackpressureHandling {

        @Test
        @DisplayName("should drop oldest item when queue is full")
        void shouldDropOldestWhenQueueFull() throws InterruptedException {
            CountDownLatch fallbackCalled = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(2)
                .batchSize(100)
                .flushIntervalMs(60000)
                .batchSender(batch -> {
                    // Block sender to fill queue
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                })
                .fallbackHandler(item -> {
                    fallbackItems.add(item);
                    fallbackCalled.countDown();
                })
                .build();

            // Fill queue
            buffer.enqueue("item1");
            buffer.enqueue("item2");

            // Wait a moment for sender thread to start processing
            Thread.sleep(100);

            // Now queue should be full, this should trigger drop
            buffer.enqueue("item3");
            buffer.enqueue("item4");
            buffer.enqueue("item5");

            boolean called = fallbackCalled.await(2, TimeUnit.SECONDS);

            assertThat(called).isTrue();
            assertThat(buffer.getItemsDropped()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should track dropped items")
        void shouldTrackDroppedItems() throws InterruptedException {
            AtomicInteger dropCount = new AtomicInteger(0);
            CountDownLatch senderStarted = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(2)
                .batchSize(2)
                .flushIntervalMs(100)
                .batchSender(batch -> {
                    senderStarted.countDown();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    sentBatches.add(new ArrayList<>(batch));
                })
                .fallbackHandler(item -> {
                    dropCount.incrementAndGet();
                    fallbackItems.add(item);
                })
                .build();

            // Enqueue multiple items rapidly
            for (int i = 0; i < 10; i++) {
                buffer.enqueue("item" + i);
            }

            // Wait for processing
            Thread.sleep(1500);

            assertThat(buffer.getItemsDropped()).isGreaterThan(0);
            assertThat(fallbackItems).isNotEmpty();
        }
    }

    // ========================================================================
    // CIRCUIT BREAKER TESTS
    // ========================================================================

    @Nested
    @DisplayName("Circuit Breaker Integration")
    class CircuitBreakerIntegration {

        @Test
        @DisplayName("should open circuit breaker after failures")
        void shouldOpenCircuitBreakerAfterFailures() throws InterruptedException {
            AtomicInteger sendAttempts = new AtomicInteger(0);
            CountDownLatch circuitOpened = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(100)
                .batchSize(1) // Send each item individually
                .flushIntervalMs(10)
                .circuitBreakerThreshold(3)
                .circuitBreakerResetMs(5000)
                .batchSender(batch -> {
                    int attempts = sendAttempts.incrementAndGet();
                    if (attempts <= 5) {
                        throw new RuntimeException("Simulated failure #" + attempts);
                    }
                })
                .fallbackHandler(item -> {
                    fallbackItems.add(item);
                    if (buffer.isCircuitBreakerOpen()) {
                        circuitOpened.countDown();
                    }
                })
                .build();

            // Send items - first 3 failures should open circuit
            for (int i = 0; i < 10; i++) {
                buffer.enqueue("item" + i);
            }

            boolean opened = circuitOpened.await(5, TimeUnit.SECONDS);

            assertThat(opened).isTrue();
            assertThat(buffer.isCircuitBreakerOpen()).isTrue();
            assertThat(buffer.getFallbackItems()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should record success and keep circuit closed")
        void shouldRecordSuccessAndKeepCircuitClosed() throws InterruptedException {
            CountDownLatch batchesSent = new CountDownLatch(3);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(100)
                .batchSize(1)
                .flushIntervalMs(10)
                .circuitBreakerThreshold(5)
                .batchSender(batch -> {
                    sentBatches.add(new ArrayList<>(batch));
                    batchesSent.countDown();
                })
                .build();

            buffer.enqueue("item1");
            buffer.enqueue("item2");
            buffer.enqueue("item3");

            boolean sent = batchesSent.await(2, TimeUnit.SECONDS);

            assertThat(sent).isTrue();
            assertThat(buffer.isCircuitBreakerOpen()).isFalse();
            assertThat(buffer.getCircuitBreaker().isClosed()).isTrue();
        }
    }

    // ========================================================================
    // FALLBACK HANDLER TESTS
    // ========================================================================

    @Nested
    @DisplayName("Fallback Handler")
    class FallbackHandler {

        @Test
        @DisplayName("should invoke fallback on sender failure")
        void shouldInvokeFallbackOnSenderFailure() throws InterruptedException {
            CountDownLatch fallbackCalled = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(100)
                .batchSize(1)
                .flushIntervalMs(10)
                .circuitBreakerThreshold(1) // Open immediately
                .batchSender(batch -> {
                    throw new RuntimeException("Sender failed");
                })
                .fallbackHandler(item -> {
                    fallbackItems.add(item);
                    fallbackCalled.countDown();
                })
                .build();

            buffer.enqueue("item1");

            boolean called = fallbackCalled.await(2, TimeUnit.SECONDS);

            assertThat(called).isTrue();
            assertThat(fallbackItems).contains("item1");
            assertThat(buffer.getFallbackItems()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should handle fallback exceptions gracefully")
        void shouldHandleFallbackExceptionsGracefully() throws InterruptedException {
            AtomicInteger fallbackCalls = new AtomicInteger(0);

            buffer = AsyncBuffer.<String>builder()
                .name("test-buffer")
                .queueCapacity(100)
                .batchSize(1)
                .flushIntervalMs(10)
                .circuitBreakerThreshold(1)
                .batchSender(batch -> {
                    throw new RuntimeException("Sender failed");
                })
                .fallbackHandler(item -> {
                    fallbackCalls.incrementAndGet();
                    throw new RuntimeException("Fallback also failed");
                })
                .build();

            buffer.enqueue("item1");
            buffer.enqueue("item2");

            Thread.sleep(500);

            // Buffer should continue working despite fallback exceptions
            assertThat(buffer.isRunning()).isTrue();
            assertThat(fallbackCalls.get()).isGreaterThan(0);
        }
    }

    // ========================================================================
    // METRICS TESTS
    // ========================================================================

    @Nested
    @DisplayName("Metrics")
    class Metrics {

        @Test
        @DisplayName("should track all metrics correctly")
        void shouldTrackAllMetrics() throws InterruptedException {
            CountDownLatch batchSent = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("metrics-test")
                .queueCapacity(100)
                .batchSize(3)
                .flushIntervalMs(60000)
                .batchSender(batch -> {
                    sentBatches.add(new ArrayList<>(batch));
                    batchSent.countDown();
                })
                .build();

            buffer.enqueue("a");
            buffer.enqueue("b");
            buffer.enqueue("c");

            batchSent.await(2, TimeUnit.SECONDS);

            assertThat(buffer.getName()).isEqualTo("metrics-test");
            assertThat(buffer.getItemsEnqueued()).isEqualTo(3);
            assertThat(buffer.getItemsSent()).isEqualTo(3);
            assertThat(buffer.getBatchesSent()).isEqualTo(1);
        }

        @Test
        @DisplayName("should provide metrics snapshot")
        void shouldProvideMetricsSnapshot() throws InterruptedException {
            CountDownLatch batchSent = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("snapshot-test")
                .queueCapacity(100)
                .batchSize(2)
                .flushIntervalMs(60000)
                .batchSender(batch -> {
                    sentBatches.add(new ArrayList<>(batch));
                    batchSent.countDown();
                })
                .build();

            buffer.enqueue("item1");
            buffer.enqueue("item2");

            batchSent.await(2, TimeUnit.SECONDS);

            AsyncBuffer.Metrics metrics = buffer.getMetrics();

            assertThat(metrics.itemsEnqueued()).isEqualTo(2);
            assertThat(metrics.itemsSent()).isEqualTo(2);
            assertThat(metrics.batchesSent()).isEqualTo(1);
            assertThat(metrics.circuitOpen()).isFalse();
        }
    }

    // ========================================================================
    // SHUTDOWN TESTS
    // ========================================================================

    @Nested
    @DisplayName("Graceful Shutdown")
    class GracefulShutdown {

        @Test
        @DisplayName("should drain queue on close")
        void shouldDrainQueueOnClose() throws InterruptedException {
            List<String> allSentItems = new CopyOnWriteArrayList<>();
            List<String> drainedItems = new CopyOnWriteArrayList<>();

            buffer = AsyncBuffer.<String>builder()
                .name("shutdown-test")
                .queueCapacity(100)
                .batchSize(100) // Large batch to prevent auto-flush
                .flushIntervalMs(60000)
                .shutdownTimeoutMs(5000)
                .batchSender(batch -> allSentItems.addAll(batch))
                .fallbackHandler(item -> drainedItems.add(item))
                .build();

            // Enqueue items
            buffer.enqueue("item1");
            buffer.enqueue("item2");
            buffer.enqueue("item3");

            // Close should flush or drain to fallback
            buffer.close();

            // Items should be processed - either sent or drained to fallback
            List<String> allProcessed = new ArrayList<>();
            allProcessed.addAll(allSentItems);
            allProcessed.addAll(drainedItems);

            assertThat(allProcessed).containsExactlyInAnyOrder("item1", "item2", "item3");
            assertThat(buffer.isRunning()).isFalse();
        }

        @Test
        @DisplayName("should reject items after close")
        void shouldRejectItemsAfterClose() throws InterruptedException {
            buffer = AsyncBuffer.<String>builder()
                .name("closed-test")
                .queueCapacity(100)
                .batchSize(100)
                .flushIntervalMs(60000)
                .batchSender(batch -> sentBatches.add(new ArrayList<>(batch)))
                .fallbackHandler(item -> fallbackItems.add(item))
                .build();

            buffer.close();

            boolean result = buffer.enqueue("after-close");

            assertThat(result).isFalse();
            assertThat(fallbackItems).contains("after-close");
        }

        @Test
        @DisplayName("should be idempotent on multiple closes")
        void shouldBeIdempotentOnMultipleCloses() {
            buffer = AsyncBuffer.<String>builder()
                .name("idempotent-test")
                .batchSender(batch -> {})
                .build();

            // Multiple closes should not throw
            assertThatCode(() -> {
                buffer.close();
                buffer.close();
                buffer.close();
            }).doesNotThrowAnyException();

            assertThat(buffer.isRunning()).isFalse();
        }
    }

    // ========================================================================
    // THREAD SAFETY TESTS
    // ========================================================================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("should handle concurrent enqueues safely")
        void shouldHandleConcurrentEnqueuesSafely() throws InterruptedException {
            List<String> allSentItems = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger fallbackCount = new AtomicInteger(0);

            buffer = AsyncBuffer.<String>builder()
                .name("concurrent-test")
                .queueCapacity(1000)
                .batchSize(50)
                .flushIntervalMs(50)
                .batchSender(batch -> allSentItems.addAll(batch))
                .fallbackHandler(item -> fallbackCount.incrementAndGet())
                .build();

            int threadCount = 10;
            int itemsPerThread = 100;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < itemsPerThread; i++) {
                            buffer.enqueue("thread" + threadId + "-item" + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);

            // Close to flush remaining items
            buffer.close();

            int totalProcessed = allSentItems.size() + fallbackCount.get();
            assertThat(totalProcessed).isEqualTo(threadCount * itemsPerThread);
        }
    }

    // ========================================================================
    // BUILDER TESTS
    // ========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should require batch sender")
        void shouldRequireBatchSender() {
            assertThatThrownBy(() ->
                AsyncBuffer.<String>builder()
                    .name("test")
                    .build()
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("batchSender is required");
        }

        @Test
        @DisplayName("should use default values")
        void shouldUseDefaultValues() {
            buffer = AsyncBuffer.<String>builder()
                .batchSender(batch -> {})
                .build();

            assertThat(buffer.getName()).isEqualTo("async-buffer");
            assertThat(buffer.isRunning()).isTrue();
        }

        @Test
        @DisplayName("should configure with Duration")
        void shouldConfigureWithDuration() throws InterruptedException {
            CountDownLatch sent = new CountDownLatch(1);

            buffer = AsyncBuffer.<String>builder()
                .name("duration-test")
                .flushInterval(Duration.ofMillis(50))
                .shutdownTimeout(Duration.ofSeconds(5))
                .circuitBreakerReset(Duration.ofSeconds(30))
                .batchSender(batch -> sent.countDown())
                .build();

            buffer.enqueue("item");

            boolean success = sent.await(1, TimeUnit.SECONDS);
            assertThat(success).isTrue();
        }
    }
}
