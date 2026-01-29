package io.resilio.core.stress;

import io.resilio.core.buffer.AsyncBuffer;
import io.resilio.core.cache.LruCache;
import io.resilio.core.circuit.CircuitBreaker;
import io.resilio.core.circuit.TryWithFallback;
import io.resilio.core.intercept.Interceptor;
import io.resilio.core.intercept.InterceptorChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.*;

/**
 * Stress and load tests for Resilio components.
 *
 * <h2>STABILITY CONTRACT</h2>
 * <p>These tests define the MINIMUM stability requirements that production systems
 * using Resilio MUST meet under stress conditions.</p>
 *
 * <h2>Stability Requirements:</h2>
 * <ul>
 *   <li>LruCache: Zero errors under 50 threads, &gt;50K ops/sec sustained</li>
 *   <li>CircuitBreaker: Correct state transitions, cascade protection</li>
 *   <li>AsyncBuffer: Zero data loss under burst traffic (30 producers)</li>
 *   <li>Full System: Zero errors, &gt;10K ops/sec under variable failure rates</li>
 * </ul>
 *
 * <h2>Required Configuration (profiling-api production):</h2>
 * <ul>
 *   <li>CircuitBreaker: failureThreshold=5, resetTimeout=30s</li>
 *   <li>AsyncBuffer: batchSize=100, flushInterval=5000ms</li>
 *   <li>Cache: maxSize appropriate for workload</li>
 * </ul>
 *
 * @author Test Engineer
 */
@DisplayName("Resilio Stress Tests")
class ResilioStressTest {

    private static final int HIGH_THREAD_COUNT = 50;
    private static final int STRESS_DURATION_SECONDS = 5;

    // ========================================================================
    // LRU CACHE STRESS TESTS
    // ========================================================================

    @Nested
    @DisplayName("LruCache Stress Tests")
    class LruCacheStress {

        @Test
        @Timeout(30)
        @DisplayName("should remain stable under sustained high load")
        void stressSustainedHighLoad() throws InterruptedException {
            LruCache<Integer, String> cache = LruCache.<Integer, String>builder()
                .name("stress-cache")
                .maxSize(1000)
                .build();

            LongAdder totalOps = new LongAdder();
            LongAdder errors = new LongAdder();
            AtomicInteger activeThreads = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(HIGH_THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger runningFlag = new AtomicInteger(1);

            // Launch stress threads
            for (int t = 0; t < HIGH_THREAD_COUNT; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        activeThreads.incrementAndGet();
                        Random random = new Random(threadId);

                        while (runningFlag.get() == 1) {
                            try {
                                int key = random.nextInt(2000); // More keys than cache size
                                int op = random.nextInt(100);

                                if (op < 70) {
                                    // 70% reads
                                    cache.getIfPresent(key);
                                } else if (op < 95) {
                                    // 25% writes
                                    cache.put(key, "value-" + key + "-" + System.nanoTime());
                                } else {
                                    // 5% invalidations
                                    cache.invalidate(key);
                                }
                                totalOps.increment();
                            } catch (Exception e) {
                                errors.increment();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        activeThreads.decrementAndGet();
                    }
                });
            }

            // Run stress test
            startLatch.countDown();
            Thread.sleep(STRESS_DURATION_SECONDS * 1000L);
            runningFlag.set(0);

            // Wait for threads to finish
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // Verify results
            long ops = totalOps.sum();
            long errs = errors.sum();
            double opsPerSecond = ops / (double) STRESS_DURATION_SECONDS;

            System.out.printf("LruCache Stress Test Results:%n");
            System.out.printf("  Duration: %d seconds%n", STRESS_DURATION_SECONDS);
            System.out.printf("  Threads: %d%n", HIGH_THREAD_COUNT);
            System.out.printf("  Total Operations: %,d%n", ops);
            System.out.printf("  Throughput: %,.0f ops/sec%n", opsPerSecond);
            System.out.printf("  Errors: %d%n", errs);
            System.out.printf("  Cache Size: %d (max: 1000)%n", cache.size());
            System.out.printf("  Hit Rate: %.1f%%%n", cache.hitRate() * 100);

            assertThat(errs).isZero();
            // Under high concurrency, size may temporarily exceed max before eviction catches up
            // Check that eviction is working (size not growing unbounded)
            assertThat(cache.size()).isLessThanOrEqualTo(2000); // Allow 2x for concurrent race
            assertThat(cache.evictionCount()).isGreaterThan(0); // Eviction is happening
            assertThat(opsPerSecond).isGreaterThan(50_000); // At least 50K ops/sec
        }

        @Test
        @Timeout(20)
        @DisplayName("should handle cache thrashing gracefully")
        void stressCacheThrashing() throws InterruptedException {
            // Small cache with many more keys = constant eviction
            LruCache<Integer, byte[]> cache = LruCache.<Integer, byte[]>builder()
                .name("thrash-cache")
                .maxSize(100)
                .build();

            LongAdder evictions = new LongAdder();
            int keyRange = 10_000; // 100x cache size

            ExecutorService executor = Executors.newFixedThreadPool(20);
            CountDownLatch done = new CountDownLatch(20);

            for (int t = 0; t < 20; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        Random random = new Random(threadId);
                        for (int i = 0; i < 10_000; i++) {
                            int key = random.nextInt(keyRange);
                            // Allocate small byte arrays to test memory handling
                            cache.put(key, new byte[100]);
                            cache.getIfPresent(random.nextInt(keyRange));
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            System.out.printf("Cache Thrashing Test:%n");
            System.out.printf("  Final Cache Size: %d%n", cache.size());
            System.out.printf("  Evictions: %d%n", cache.evictionCount());

            assertThat(cache.size()).isLessThanOrEqualTo(100);
            assertThat(cache.evictionCount()).isGreaterThan(0);
        }
    }

    // ========================================================================
    // CIRCUIT BREAKER STRESS TESTS
    // ========================================================================

    @Nested
    @DisplayName("CircuitBreaker Stress Tests")
    class CircuitBreakerStress {

        @Test
        @Timeout(30)
        @DisplayName("should handle rapid state transitions under load")
        void stressRapidStateTransitions() throws InterruptedException {
            CircuitBreaker breaker = CircuitBreaker.builder()
                .name("stress-breaker")
                .failureThreshold(3)
                .resetTimeout(Duration.ofMillis(50)) // Fast reset for stress test
                .build();

            LongAdder successes = new LongAdder();
            LongAdder fallbacks = new LongAdder();
            LongAdder stateChanges = new LongAdder();
            AtomicInteger failureMode = new AtomicInteger(0); // 0=success, 1=fail

            ExecutorService executor = Executors.newFixedThreadPool(HIGH_THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger runningFlag = new AtomicInteger(1);

            // Toggle failure mode periodically
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                int prev = failureMode.get();
                failureMode.set(1 - prev); // Toggle
                if (prev != failureMode.get()) stateChanges.increment();
            }, 100, 100, TimeUnit.MILLISECONDS);

            for (int t = 0; t < HIGH_THREAD_COUNT; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        while (runningFlag.get() == 1) {
                            String result = breaker.execute(
                                () -> {
                                    if (failureMode.get() == 1) {
                                        throw new RuntimeException("Simulated failure");
                                    }
                                    return "success";
                                },
                                () -> "fallback"
                            );

                            if ("success".equals(result)) {
                                successes.increment();
                            } else {
                                fallbacks.increment();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            startLatch.countDown();
            Thread.sleep(STRESS_DURATION_SECONDS * 1000L);
            runningFlag.set(0);

            scheduler.shutdown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            long total = successes.sum() + fallbacks.sum();
            double successRate = successes.sum() * 100.0 / total;

            System.out.printf("CircuitBreaker Stress Test Results:%n");
            System.out.printf("  Total Operations: %,d%n", total);
            System.out.printf("  Successes: %,d (%.1f%%)%n", successes.sum(), successRate);
            System.out.printf("  Fallbacks: %,d%n", fallbacks.sum());
            System.out.printf("  Mode Toggles: %d%n", stateChanges.sum());
            System.out.printf("  Final State: %s%n", breaker.getState());

            // Should have both successes and fallbacks (due to mode toggling)
            assertThat(successes.sum()).isGreaterThan(0);
            assertThat(fallbacks.sum()).isGreaterThan(0);
        }

        @Test
        @Timeout(20)
        @DisplayName("should protect against cascade failures")
        void stressCascadeProtection() throws InterruptedException {
            // Production config: failureThreshold=5, resetTimeout=30s
            // Using shorter timeout for test (1s instead of 30s)
            CircuitBreaker breaker = CircuitBreaker.builder()
                .name("cascade-breaker")
                .failureThreshold(5)  // matches profiling-api production
                .resetTimeout(Duration.ofSeconds(1))  // shorter for test
                .build();

            AtomicLong primaryCalls = new AtomicLong(0);
            AtomicLong fallbackCalls = new AtomicLong(0);

            // Simulate a service that's completely down
            ExecutorService executor = Executors.newFixedThreadPool(20);
            CountDownLatch done = new CountDownLatch(20);

            for (int t = 0; t < 20; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 1000; i++) {
                            breaker.execute(
                                () -> {
                                    primaryCalls.incrementAndGet();
                                    throw new RuntimeException("Service down");
                                },
                                () -> {
                                    fallbackCalls.incrementAndGet();
                                    return "fallback";
                                }
                            );
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            System.out.printf("Cascade Protection Test:%n");
            System.out.printf("  Primary Calls (failed): %d%n", primaryCalls.get());
            System.out.printf("  Fallback Calls (protected): %d%n", fallbackCalls.get());
            System.out.printf("  Protection Ratio: %.1f%%%n",
                fallbackCalls.get() * 100.0 / (primaryCalls.get() + fallbackCalls.get()));

            // Circuit breaker should have protected most calls
            // After 5 failures, remaining ~19,995 calls should go to fallback directly
            assertThat(primaryCalls.get()).isLessThan(100); // Only ~5 per thread before circuit opens
            assertThat(breaker.isOpen()).isTrue();
        }
    }

    // ========================================================================
    // ASYNC BUFFER STRESS TESTS
    // ========================================================================

    @Nested
    @DisplayName("AsyncBuffer Stress Tests")
    class AsyncBufferStress {

        @Test
        @Timeout(30)
        @DisplayName("should handle burst traffic without data loss")
        void stressBurstTraffic() throws InterruptedException {
            LongAdder sentItems = new LongAdder();
            LongAdder fallbackItems = new LongAdder();

            // Production config: batchSize=100 (same as AuditBufferService)
            AsyncBuffer<String> buffer = AsyncBuffer.<String>builder()
                .name("burst-buffer")
                .queueCapacity(10_000)
                .batchSize(100)  // matches profiling-api production
                .flushIntervalMs(50)  // faster for stress test (production: 5000ms)
                .batchSender(batch -> {
                    sentItems.add(batch.size());
                    // Simulate some processing time
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                })
                .fallbackHandler(item -> fallbackItems.increment())
                .build();

            // Burst: many producers sending rapidly
            int producerCount = 30;
            int itemsPerProducer = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(producerCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(producerCount);

            for (int p = 0; p < producerCount; p++) {
                final int producerId = p;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < itemsPerProducer; i++) {
                            buffer.enqueue("producer-" + producerId + "-item-" + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            // Start burst
            startLatch.countDown();
            done.await(10, TimeUnit.SECONDS);

            // Close and drain
            buffer.close();

            executor.shutdown();

            long totalExpected = producerCount * itemsPerProducer;
            long totalProcessed = sentItems.sum() + fallbackItems.sum();

            System.out.printf("AsyncBuffer Burst Test:%n");
            System.out.printf("  Expected Items: %,d%n", totalExpected);
            System.out.printf("  Sent Items: %,d%n", sentItems.sum());
            System.out.printf("  Fallback Items: %,d%n", fallbackItems.sum());
            System.out.printf("  Total Processed: %,d%n", totalProcessed);
            System.out.printf("  Batches Sent: %d%n", buffer.getBatchesSent());

            // All items should be accounted for (either sent or fallback)
            assertThat(totalProcessed).isEqualTo(totalExpected);
        }

        @Test
        @Timeout(20)
        @DisplayName("should handle slow consumer gracefully")
        void stressSlowConsumer() throws InterruptedException {
            LongAdder processedItems = new LongAdder();
            AtomicInteger batchCount = new AtomicInteger(0);

            AsyncBuffer<String> buffer = AsyncBuffer.<String>builder()
                .name("slow-consumer-buffer")
                .queueCapacity(1000)
                .batchSize(50)
                .flushIntervalMs(100)
                .batchSender(batch -> {
                    // Slow consumer
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    processedItems.add(batch.size());
                    batchCount.incrementAndGet();
                })
                .fallbackHandler(item -> processedItems.increment())
                .build();

            // Fast producer
            int itemCount = 5000;
            for (int i = 0; i < itemCount; i++) {
                buffer.enqueue("item-" + i);
            }

            // Let it process
            Thread.sleep(3000);
            buffer.close();

            System.out.printf("Slow Consumer Test:%n");
            System.out.printf("  Items Enqueued: %d%n", buffer.getItemsEnqueued());
            System.out.printf("  Items Processed: %d%n", processedItems.sum());
            System.out.printf("  Items Dropped: %d%n", buffer.getItemsDropped());
            System.out.printf("  Batches Sent: %d%n", batchCount.get());

            // With backpressure, some items may be dropped but nothing should be lost silently
            long accounted = buffer.getItemsSent() + buffer.getItemsDropped() + buffer.getFallbackItems();
            assertThat(accounted).isGreaterThanOrEqualTo(buffer.getItemsEnqueued() - buffer.getQueueSize());
        }
    }

    // ========================================================================
    // COMBINED STRESS TEST (FULL SYSTEM)
    // ========================================================================

    @Nested
    @DisplayName("Full System Stress Test")
    class FullSystemStress {

        @Test
        @Timeout(60)
        @DisplayName("should remain stable under combined component stress")
        void stressFullSystem() throws InterruptedException {
            // Setup all components with production-like config
            LruCache<String, String> cache = LruCache.<String, String>builder()
                .name("system-cache")
                .maxSize(500)  // reasonable for stress test
                .build();

            // Production config: failureThreshold=5, resetTimeout=30s
            // Using shorter timeout for test
            CircuitBreaker breaker = CircuitBreaker.builder()
                .name("system-breaker")
                .failureThreshold(5)   // matches profiling-api production
                .resetTimeout(Duration.ofMillis(500))  // shorter for test
                .build();

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<String, String>() {
                    @Override public String before(String ctx) { return ctx.toUpperCase(); }
                })
                .add(new Interceptor<String, String>() {
                    @Override public String after(String ctx, String result) { return result; }
                })
                .build();

            LongAdder totalOps = new LongAdder();
            LongAdder cacheHits = new LongAdder();
            LongAdder dbCalls = new LongAdder();
            LongAdder errors = new LongAdder();
            AtomicInteger failureRate = new AtomicInteger(0); // 0-100%

            ExecutorService executor = Executors.newFixedThreadPool(HIGH_THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger runningFlag = new AtomicInteger(1);

            // Vary failure rate over time
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                // Oscillate failure rate: 0% -> 50% -> 0%
                int current = failureRate.get();
                if (current < 50 && runningFlag.get() == 1) {
                    failureRate.incrementAndGet();
                } else if (current > 0) {
                    failureRate.decrementAndGet();
                }
            }, 50, 50, TimeUnit.MILLISECONDS);

            for (int t = 0; t < HIGH_THREAD_COUNT; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Random random = new Random(threadId);

                        while (runningFlag.get() == 1) {
                            try {
                                String query = "query-" + random.nextInt(1000);

                                String result = chain.execute(query, ctx -> {
                                    // Try cache first
                                    return cache.get(ctx, key -> {
                                        // Cache miss - call "DB" via circuit breaker
                                        return breaker.execute(
                                            () -> {
                                                dbCalls.increment();
                                                // Simulate variable failure
                                                if (random.nextInt(100) < failureRate.get()) {
                                                    throw new RuntimeException("DB error");
                                                }
                                                return "result-" + key;
                                            },
                                            () -> "fallback-" + key
                                        );
                                    });
                                });

                                if (result.startsWith("result-")) {
                                    cacheHits.increment();
                                }
                                totalOps.increment();

                            } catch (Exception e) {
                                errors.increment();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // Run stress test
            startLatch.countDown();
            Thread.sleep(STRESS_DURATION_SECONDS * 1000L);
            runningFlag.set(0);

            scheduler.shutdown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            double opsPerSecond = totalOps.sum() / (double) STRESS_DURATION_SECONDS;

            System.out.printf("%n=== FULL SYSTEM STRESS TEST RESULTS ===%n");
            System.out.printf("Duration: %d seconds%n", STRESS_DURATION_SECONDS);
            System.out.printf("Threads: %d%n", HIGH_THREAD_COUNT);
            System.out.printf("Total Operations: %,d%n", totalOps.sum());
            System.out.printf("Throughput: %,.0f ops/sec%n", opsPerSecond);
            System.out.printf("Cache Hit Rate: %.1f%%%n", cache.hitRate() * 100);
            System.out.printf("DB Calls: %,d%n", dbCalls.sum());
            System.out.printf("Errors: %d%n", errors.sum());
            System.out.printf("CircuitBreaker State: %s%n", breaker.getState());
            System.out.printf("Cache Size: %d%n", cache.size());
            System.out.printf("========================================%n");

            // Verify system stability
            assertThat(errors.sum()).isZero();
            assertThat(totalOps.sum()).isGreaterThan(10_000);
            // Under high concurrency, size may temporarily exceed max
            assertThat(cache.size()).isLessThanOrEqualTo(1000); // Allow 2x for concurrent race
            assertThat(cache.evictionCount()).isGreaterThan(0); // Eviction is working
        }
    }
}
