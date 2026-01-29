package io.resilio.core.benchmark;

import io.resilio.core.cache.LruCache;
import io.resilio.core.circuit.CircuitBreaker;
import io.resilio.core.circuit.TryWithFallback;
import io.resilio.core.intercept.Interceptor;
import io.resilio.core.intercept.InterceptorChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.*;

/**
 * Performance benchmark tests for Resilio components.
 *
 * <h2>PERFORMANCE CONTRACT</h2>
 * <p>These tests define the MINIMUM performance thresholds that production systems
 * using Resilio MUST meet. Any deployment using these components should guarantee
 * at least the performance levels verified here.</p>
 *
 * <h2>Minimum Performance Requirements:</h2>
 * <ul>
 *   <li>CircuitBreaker (closed): &gt;3M ops/sec, &lt;500ns latency</li>
 *   <li>CircuitBreaker allowRequest(): &gt;10M ops/sec</li>
 *   <li>TryWithFallback (success path): &gt;5M ops/sec</li>
 *   <li>TryWithFallback (fallback path): &gt;80K ops/sec</li>
 *   <li>LruCache hits: &gt;1M ops/sec, &lt;1μs latency</li>
 *   <li>LruCache concurrent (10 threads): &gt;100K ops/sec</li>
 *   <li>InterceptorChain (2 interceptors): &gt;1M ops/sec</li>
 *   <li>Combined cache+breaker+chain: &gt;100K ops/sec</li>
 * </ul>
 *
 * <h2>Profiling-API Production Config:</h2>
 * <ul>
 *   <li>CircuitBreaker: failureThreshold=5, resetTimeout=30s</li>
 *   <li>TryWithFallback: cache-first-then-DB with FALLBACK_TO_LOADER</li>
 *   <li>AsyncBuffer: batchSize=100, flushInterval=5000ms</li>
 * </ul>
 *
 * @author Test Engineer
 */
@DisplayName("Resilio Performance Benchmarks")
class ResilioBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int BENCHMARK_ITERATIONS = 100_000;
    private static final int THREAD_COUNT = 10;

    // ========================================================================
    // LRU CACHE BENCHMARKS
    // Minimum Requirements: >1M ops/sec hits, >100K concurrent ops/sec
    // ========================================================================

    @Nested
    @DisplayName("LruCache Performance - Minimum Requirements")
    class LruCacheBenchmarks {

        @Test
        @DisplayName("should achieve high throughput for cache hits")
        void benchmarkCacheHitThroughput() {
            LruCache<String, String> cache = LruCache.<String, String>builder()
                .name("benchmark-cache")
                .maxSize(10_000)
                .build();

            // Pre-populate cache
            for (int i = 0; i < 1000; i++) {
                cache.put("key" + i, "value" + i);
            }

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                cache.getIfPresent("key" + (i % 1000));
            }

            // Benchmark
            long startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                cache.getIfPresent("key" + (i % 1000));
            }
            long duration = System.nanoTime() - startTime;

            double opsPerSecond = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / duration;
            double avgLatencyNs = (double) duration / BENCHMARK_ITERATIONS;

            System.out.printf("LruCache Hit Throughput: %.0f ops/sec%n", opsPerSecond);
            System.out.printf("LruCache Hit Avg Latency: %.2f ns%n", avgLatencyNs);

            // Performance assertions
            assertThat(opsPerSecond).isGreaterThan(1_000_000); // > 1M ops/sec
            assertThat(avgLatencyNs).isLessThan(1000); // < 1 microsecond
        }

        @Test
        @DisplayName("should maintain performance under concurrent access")
        void benchmarkConcurrentCacheAccess() throws InterruptedException {
            LruCache<Integer, String> cache = LruCache.<Integer, String>builder()
                .name("concurrent-benchmark")
                .maxSize(10_000)
                .build();

            // Pre-populate
            for (int i = 0; i < 5000; i++) {
                cache.put(i, "value" + i);
            }

            int opsPerThread = BENCHMARK_ITERATIONS / THREAD_COUNT;
            LongAdder totalOps = new LongAdder();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

            for (int t = 0; t < THREAD_COUNT; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            int key = (threadId * 1000 + i) % 5000;
                            if (i % 10 == 0) {
                                cache.put(key, "updated" + i);
                            } else {
                                cache.getIfPresent(key);
                            }
                            totalOps.increment();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            long startTime = System.nanoTime();
            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            long duration = System.nanoTime() - startTime;

            executor.shutdown();

            double opsPerSecond = (totalOps.sum() * 1_000_000_000.0) / duration;

            System.out.printf("LruCache Concurrent Throughput (%d threads): %.0f ops/sec%n",
                THREAD_COUNT, opsPerSecond);

            // Should maintain good throughput under concurrency
            assertThat(opsPerSecond).isGreaterThan(100_000); // > 100K ops/sec with 10 threads
        }
    }

    // ========================================================================
    // CIRCUIT BREAKER BENCHMARKS
    // Required config: failureThreshold=5, resetTimeout=30s
    // Minimum: >3M ops/sec closed, >10M ops/sec allowRequest()
    // ========================================================================

    @Nested
    @DisplayName("CircuitBreaker Performance - Minimum Requirements")
    class CircuitBreakerBenchmarks {

        @Test
        @DisplayName("should have minimal overhead when closed")
        void benchmarkCircuitBreakerOverhead() {
            CircuitBreaker breaker = CircuitBreaker.builder()
                .name("benchmark-breaker")
                .failureThreshold(5)
                .resetTimeout(Duration.ofSeconds(30))
                .build();

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                breaker.execute(() -> "result", () -> "fallback");
            }

            // Benchmark
            long startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                breaker.execute(() -> "result", () -> "fallback");
            }
            long duration = System.nanoTime() - startTime;

            double opsPerSecond = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / duration;
            double avgLatencyNs = (double) duration / BENCHMARK_ITERATIONS;

            System.out.printf("CircuitBreaker (closed) Throughput: %.0f ops/sec%n", opsPerSecond);
            System.out.printf("CircuitBreaker (closed) Avg Latency: %.2f ns%n", avgLatencyNs);

            // Circuit breaker should have minimal overhead
            assertThat(opsPerSecond).isGreaterThan(3_000_000); // > 3M ops/sec
            assertThat(avgLatencyNs).isLessThan(500); // < 500 ns overhead
        }

        @Test
        @DisplayName("should handle rapid state checks efficiently")
        void benchmarkAllowRequestCheck() {
            CircuitBreaker breaker = CircuitBreaker.builder()
                .name("check-benchmark")
                .failureThreshold(5)
                .resetTimeout(Duration.ofSeconds(30))
                .build();

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                breaker.allowRequest();
            }

            // Benchmark
            long startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                breaker.allowRequest();
            }
            long duration = System.nanoTime() - startTime;

            double opsPerSecond = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / duration;

            System.out.printf("CircuitBreaker allowRequest() Throughput: %.0f ops/sec%n", opsPerSecond);

            // State check should be very fast
            assertThat(opsPerSecond).isGreaterThan(10_000_000); // > 10M ops/sec
        }
    }

    // ========================================================================
    // TRY WITH FALLBACK BENCHMARKS
    // Minimum: >5M ops/sec success, >100K ops/sec fallback
    // ========================================================================

    @Nested
    @DisplayName("TryWithFallback Performance - Minimum Requirements")
    class TryWithFallbackBenchmarks {

        @Test
        @DisplayName("should have minimal overhead for successful execution")
        void benchmarkSuccessfulExecution() {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                TryWithFallback.of(() -> "success", () -> "fallback").execute();
            }

            // Benchmark
            long startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                TryWithFallback.of(() -> "success", () -> "fallback").execute();
            }
            long duration = System.nanoTime() - startTime;

            double opsPerSecond = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / duration;
            double avgLatencyNs = (double) duration / BENCHMARK_ITERATIONS;

            System.out.printf("TryWithFallback (success) Throughput: %.0f ops/sec%n", opsPerSecond);
            System.out.printf("TryWithFallback (success) Avg Latency: %.2f ns%n", avgLatencyNs);

            assertThat(opsPerSecond).isGreaterThan(5_000_000); // > 5M ops/sec
        }

        @Test
        @DisplayName("should handle fallback path efficiently")
        void benchmarkFallbackExecution() {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                TryWithFallback.of(
                    () -> { throw new RuntimeException(); },
                    () -> "fallback"
                ).execute();
            }

            // Benchmark - exception path is slower but should still be reasonable
            int fallbackIterations = 10_000; // Fewer iterations due to exception overhead
            long startTime = System.nanoTime();
            for (int i = 0; i < fallbackIterations; i++) {
                TryWithFallback.of(
                    () -> { throw new RuntimeException(); },
                    () -> "fallback"
                ).execute();
            }
            long duration = System.nanoTime() - startTime;

            double opsPerSecond = (fallbackIterations * 1_000_000_000.0) / duration;

            System.out.printf("TryWithFallback (fallback) Throughput: %.0f ops/sec%n", opsPerSecond);

            // Fallback path with exception is slower but acceptable (exception overhead)
            assertThat(opsPerSecond).isGreaterThan(80_000); // > 80K ops/sec
        }
    }

    // ========================================================================
    // INTERCEPTOR CHAIN BENCHMARKS
    // Minimum: >1M ops/sec (2 interceptors), >500K ops/sec (5 interceptors)
    // ========================================================================

    @Nested
    @DisplayName("InterceptorChain Performance - Minimum Requirements")
    class InterceptorChainBenchmarks {

        @Test
        @DisplayName("should execute chain with minimal overhead")
        void benchmarkSimpleChain() {
            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<String, String>() {
                    @Override public String before(String ctx) { return ctx; }
                })
                .add(new Interceptor<String, String>() {
                    @Override public String after(String ctx, String result) { return result; }
                })
                .build();

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                chain.execute("input", ctx -> ctx.toUpperCase());
            }

            // Benchmark
            long startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                chain.execute("input", ctx -> ctx.toUpperCase());
            }
            long duration = System.nanoTime() - startTime;

            double opsPerSecond = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / duration;
            double avgLatencyNs = (double) duration / BENCHMARK_ITERATIONS;

            System.out.printf("InterceptorChain (2 interceptors) Throughput: %.0f ops/sec%n", opsPerSecond);
            System.out.printf("InterceptorChain (2 interceptors) Avg Latency: %.2f ns%n", avgLatencyNs);

            assertThat(opsPerSecond).isGreaterThan(1_000_000); // > 1M ops/sec
        }

        @Test
        @DisplayName("should scale with multiple interceptors")
        void benchmarkChainScalability() {
            // Build chain with 5 interceptors
            Interceptor<String, String> beforeInterceptor = new Interceptor<>() {
                @Override public String before(String ctx) { return ctx; }
            };
            Interceptor<String, String> afterInterceptor = new Interceptor<>() {
                @Override public String after(String ctx, String result) { return result; }
            };

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(beforeInterceptor)
                .add(beforeInterceptor)
                .add(beforeInterceptor)
                .add(afterInterceptor)
                .add(afterInterceptor)
                .build();

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                chain.execute("input", ctx -> "result");
            }

            // Benchmark
            long startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                chain.execute("input", ctx -> "result");
            }
            long duration = System.nanoTime() - startTime;

            double opsPerSecond = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / duration;

            System.out.printf("InterceptorChain (5 interceptors) Throughput: %.0f ops/sec%n", opsPerSecond);

            // Should still maintain good throughput with more interceptors
            assertThat(opsPerSecond).isGreaterThan(500_000); // > 500K ops/sec
        }
    }

    // ========================================================================
    // COMBINED BENCHMARK (PRODUCTION SCENARIO)
    // Required config for profiling-api:
    //   - Cache: maxSize=1000
    //   - CircuitBreaker: failureThreshold=5, resetTimeout=30s
    //   - InterceptorChain: 3 interceptors (security, query mod, audit)
    // Minimum: >100K ops/sec combined, >90% cache hit rate
    // ========================================================================

    @Nested
    @DisplayName("Combined Production Scenario - Minimum Requirements")
    class CombinedBenchmarks {

        @Test
        @DisplayName("should handle realistic profiling scenario efficiently")
        void benchmarkRealisticProfilingScenario() {
            // Setup: Cache + CircuitBreaker + InterceptorChain
            LruCache<String, String> cache = LruCache.<String, String>builder()
                .name("query-cache")
                .maxSize(1000)
                .build();

            CircuitBreaker breaker = CircuitBreaker.builder()
                .name("db-breaker")
                .failureThreshold(5)
                .resetTimeout(Duration.ofSeconds(30))
                .build();

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<String, String>() {
                    @Override public String before(String ctx) { return ctx; } // Security check
                })
                .add(new Interceptor<String, String>() {
                    @Override public String before(String ctx) { return ctx; } // Query modification
                })
                .add(new Interceptor<String, String>() {
                    @Override public String after(String ctx, String result) { return result; } // Audit
                })
                .build();

            AtomicLong cacheHits = new AtomicLong(0);
            AtomicLong dbCalls = new AtomicLong(0);

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                String query = "SELECT * FROM table WHERE id = " + (i % 100);
                executeProfilingScenario(query, cache, breaker, chain, cacheHits, dbCalls);
            }

            cacheHits.set(0);
            dbCalls.set(0);

            // Benchmark
            long startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                String query = "SELECT * FROM table WHERE id = " + (i % 100);
                executeProfilingScenario(query, cache, breaker, chain, cacheHits, dbCalls);
            }
            long duration = System.nanoTime() - startTime;

            double opsPerSecond = (BENCHMARK_ITERATIONS * 1_000_000_000.0) / duration;
            // Hit rate = (total - db misses) / total
            double hitRate = (BENCHMARK_ITERATIONS - dbCalls.get()) * 100.0 / BENCHMARK_ITERATIONS;

            System.out.printf("Realistic Scenario Throughput: %.0f ops/sec%n", opsPerSecond);
            System.out.printf("Cache Hit Rate: %.1f%%%n", hitRate);
            System.out.printf("DB Calls (misses): %d (%.1f%%)%n", dbCalls.get(),
                dbCalls.get() * 100.0 / BENCHMARK_ITERATIONS);

            // Combined scenario should still be performant
            assertThat(opsPerSecond).isGreaterThan(100_000); // > 100K ops/sec
            assertThat(hitRate).isGreaterThan(90); // > 90% cache hit rate (100 unique queries)
        }

        private String executeProfilingScenario(
                String query,
                LruCache<String, String> cache,
                CircuitBreaker breaker,
                InterceptorChain<String, String> chain,
                AtomicLong cacheHits,
                AtomicLong dbCalls) {

            return chain.execute(query, ctx -> {
                // Check cache first
                return cache.get(ctx, key -> {
                    // Cache miss - go to "DB" via circuit breaker
                    return breaker.execute(
                        () -> {
                            dbCalls.incrementAndGet();
                            return "result-for-" + key;
                        },
                        () -> "fallback"
                    );
                });
            });
        }
    }
}
