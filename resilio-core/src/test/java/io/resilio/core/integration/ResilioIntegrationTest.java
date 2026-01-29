package io.resilio.core.integration;

import io.resilio.core.cache.LruCache;
import io.resilio.core.circuit.CircuitBreaker;
import io.resilio.core.circuit.TryWithFallback;
import io.resilio.core.intercept.Interceptor;
import io.resilio.core.intercept.InterceptorChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for RESILIO framework.
 *
 * These tests verify that all components work together correctly:
 * - CircuitBreaker + TryWithFallback
 * - LruCache + InterceptorChain
 * - TryWithFallback + InterceptorChain
 * - All components combined
 *
 * Simulates real-world profiling scenarios.
 *
 * @author Test Engineer
 */
@DisplayName("RESILIO Integration Tests")
class ResilioIntegrationTest {

    // ========================================================================
    // CIRCUIT BREAKER + TRY WITH FALLBACK INTEGRATION
    // ========================================================================

    @Nested
    @DisplayName("CircuitBreaker + TryWithFallback")
    class CircuitBreakerTryWithFallbackIntegration {

        private CircuitBreaker circuitBreaker;

        @BeforeEach
        void setUp() {
            circuitBreaker = CircuitBreaker.builder()
                .name("integration-breaker")
                .failureThreshold(3)
                .resetTimeout(Duration.ofMillis(100))
                .build();
        }

        @Test
        @DisplayName("should open circuit and use fallback after failures")
        void shouldOpenCircuitAndUseFallback() {
            AtomicInteger primaryCalls = new AtomicInteger(0);
            AtomicInteger fallbackCalls = new AtomicInteger(0);

            // Simulate failing service
            for (int i = 0; i < 5; i++) {
                TryWithFallback.of(
                    () -> {
                        primaryCalls.incrementAndGet();
                        throw new RuntimeException("Service down");
                    },
                    () -> {
                        fallbackCalls.incrementAndGet();
                        return "fallback";
                    }
                )
                .withCircuitBreaker(circuitBreaker)
                .execute();
            }

            // After 3 failures, circuit opens - remaining calls skip primary
            assertThat(primaryCalls.get()).isEqualTo(3);
            assertThat(fallbackCalls.get()).isEqualTo(5);
            assertThat(circuitBreaker.isOpen()).isTrue();
        }

        @Test
        @DisplayName("should recover after circuit resets")
        void shouldRecoverAfterCircuitResets() throws InterruptedException {
            // Open the circuit using CircuitBreaker.execute() which handles state transitions
            for (int i = 0; i < 3; i++) {
                circuitBreaker.execute(
                    () -> { throw new RuntimeException(); },
                    () -> "fallback"
                );
            }

            assertThat(circuitBreaker.isOpen()).isTrue();

            // Wait for reset timeout (100ms configured)
            Thread.sleep(150);

            // Use allowRequest() to trigger HALF_OPEN transition, then execute
            AtomicInteger successCalls = new AtomicInteger(0);
            for (int i = 0; i < 5; i++) {
                // Use CircuitBreaker.execute() which properly handles state transitions
                String result = circuitBreaker.execute(
                    () -> {
                        successCalls.incrementAndGet();
                        return "success";
                    },
                    () -> "fallback"
                );

                assertThat(result).isEqualTo("success");
            }

            assertThat(circuitBreaker.isClosed()).isTrue();
            assertThat(successCalls.get()).isEqualTo(5);
        }
    }

    // ========================================================================
    // LRU CACHE + INTERCEPTOR CHAIN INTEGRATION
    // ========================================================================

    @Nested
    @DisplayName("LruCache + InterceptorChain")
    class LruCacheInterceptorChainIntegration {

        private LruCache<String, String> cache;

        @BeforeEach
        void setUp() {
            cache = LruCache.<String, String>builder()
                .name("query-cache")
                .maxSize(10)
                .build();
        }

        @Test
        @DisplayName("should cache query results through interceptor")
        void shouldCacheQueryResultsThroughInterceptor() {
            AtomicInteger dbCalls = new AtomicInteger(0);

            // Cache interceptor
            Interceptor<QueryContext, String> cacheInterceptor = new Interceptor<>() {
                @Override
                public QueryContext before(QueryContext ctx) {
                    // Check cache
                    Optional<String> cached = cache.getIfPresent(ctx.cacheKey);
                    if (cached.isPresent()) {
                        return ctx.withCachedResult(cached.get());
                    }
                    return ctx;
                }

                @Override
                public String after(QueryContext ctx, String result) {
                    // Populate cache if not already cached
                    if (!ctx.hasCachedResult()) {
                        cache.put(ctx.cacheKey, result);
                    }
                    return result;
                }

                @Override
                public int order() { return 10; }
            };

            InterceptorChain<QueryContext, String> chain = InterceptorChain.<QueryContext, String>builder()
                .add(cacheInterceptor)
                .build();

            // First call - cache miss, DB call
            String result1 = chain.execute(new QueryContext("SELECT * FROM users", "users:all"), ctx -> {
                if (ctx.hasCachedResult()) {
                    return ctx.getCachedResult();
                }
                dbCalls.incrementAndGet();
                return "user1,user2,user3";
            });

            // Second call - cache hit, no DB call
            String result2 = chain.execute(new QueryContext("SELECT * FROM users", "users:all"), ctx -> {
                if (ctx.hasCachedResult()) {
                    return ctx.getCachedResult();
                }
                dbCalls.incrementAndGet();
                return "user1,user2,user3";
            });

            assertThat(result1).isEqualTo("user1,user2,user3");
            assertThat(result2).isEqualTo("user1,user2,user3");
            assertThat(dbCalls.get()).isEqualTo(1);
            assertThat(cache.hitCount()).isEqualTo(1);
            assertThat(cache.missCount()).isEqualTo(1);
        }
    }

    // ========================================================================
    // TRY WITH FALLBACK + INTERCEPTOR CHAIN INTEGRATION
    // ========================================================================

    @Nested
    @DisplayName("TryWithFallback + InterceptorChain")
    class TryWithFallbackInterceptorChainIntegration {

        @Test
        @DisplayName("should use fallback when interceptor chain fails")
        void shouldUseFallbackWhenChainFails() {
            CircuitBreaker circuitBreaker = CircuitBreaker.builder()
                .failureThreshold(2)
                .resetTimeout(Duration.ofSeconds(10))
                .build();

            // Chain that can fail
            InterceptorChain<String, String> riskyChain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<>() {
                    @Override
                    public String before(String context) {
                        if (context.equals("fail")) {
                            throw new RuntimeException("Chain failure");
                        }
                        return context;
                    }
                })
                .build();

            // Safe fallback chain
            InterceptorChain<String, String> fallbackChain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<>() {
                    @Override
                    public String after(String context, String result) {
                        return "fallback:" + result;
                    }
                })
                .build();

            // Execute with TryWithFallback wrapping chains
            String result = TryWithFallback.of(
                () -> riskyChain.execute("fail", ctx -> "risky-result"),
                () -> fallbackChain.execute("safe", ctx -> "safe-result")
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();

            assertThat(result).isEqualTo("fallback:safe-result");
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(1);
        }
    }

    // ========================================================================
    // FULL INTEGRATION: ALL COMPONENTS COMBINED
    // ========================================================================

    @Nested
    @DisplayName("Full Integration: Profiling Simulation")
    class FullProfilingSimulation {

        private CircuitBreaker circuitBreaker;
        private LruCache<String, List<String>> queryCache;
        private List<String> auditLog;

        @BeforeEach
        void setUp() {
            circuitBreaker = CircuitBreaker.builder()
                .name("profiling-circuit")
                .failureThreshold(3)
                .resetTimeout(Duration.ofMillis(200))
                .build();

            queryCache = LruCache.<String, List<String>>builder()
                .name("profiling-query-cache")
                .maxSize(100)
                .build();

            auditLog = new ArrayList<>();
        }

        @Test
        @DisplayName("should simulate complete profiling flow with all components")
        void shouldSimulateCompleteProfilingFlow() {
            // ================================================================
            // SIMULATED PROFILING ARCHITECTURE:
            //
            // 1. Security Interceptor: adds WHERE clause
            // 2. Cache Interceptor: checks/populates cache
            // 3. Audit Interceptor: logs access
            // 4. TryWithFallback: wraps DB call with circuit breaker
            // ================================================================

            // Security interceptor - adds data rule filter
            Interceptor<ProfilingContext, List<String>> securityInterceptor = new Interceptor<>() {
                @Override
                public ProfilingContext before(ProfilingContext ctx) {
                    // Simulate @DataRuleMapping: add WHERE clause
                    String filteredQuery = ctx.query + " WHERE org_unit IN ('OU1', 'OU2')";
                    return ctx.withQuery(filteredQuery);
                }

                @Override
                public int order() { return 10; }
            };

            // Cache interceptor - cache aside pattern
            Interceptor<ProfilingContext, List<String>> cacheInterceptor = new Interceptor<>() {
                @Override
                public ProfilingContext before(ProfilingContext ctx) {
                    Optional<List<String>> cached = queryCache.getIfPresent(ctx.cacheKey());
                    if (cached.isPresent()) {
                        return ctx.withCachedResult(cached.get());
                    }
                    return ctx;
                }

                @Override
                public List<String> after(ProfilingContext ctx, List<String> result) {
                    if (!ctx.hasCachedResult()) {
                        queryCache.put(ctx.cacheKey(), result);
                    }
                    return result;
                }

                @Override
                public int order() { return 20; }
            };

            // Audit interceptor - GDPR compliance
            Interceptor<ProfilingContext, List<String>> auditInterceptor = new Interceptor<>() {
                @Override
                public List<String> after(ProfilingContext ctx, List<String> result) {
                    auditLog.add(String.format("User=%s, Query=%s, Results=%d",
                        ctx.user, ctx.query, result.size()));
                    return result;
                }

                @Override
                public void onError(ProfilingContext ctx, Exception error) {
                    auditLog.add("ERROR: " + error.getMessage());
                }

                @Override
                public int order() { return 30; }
            };

            // Build the chain
            InterceptorChain<ProfilingContext, List<String>> profilingChain = InterceptorChain
                .<ProfilingContext, List<String>>builder()
                .add(securityInterceptor)
                .add(cacheInterceptor)
                .add(auditInterceptor)
                .build();

            // Simulated database
            AtomicInteger dbCalls = new AtomicInteger(0);

            // Execute profiled query with TryWithFallback + CircuitBreaker
            List<String> results = TryWithFallback.<List<String>>of(
                () -> profilingChain.execute(
                    new ProfilingContext("SELECT * FROM orders", "john"),
                    ctx -> {
                        if (ctx.hasCachedResult()) {
                            return ctx.getCachedResult();
                        }
                        // Simulate DB call
                        dbCalls.incrementAndGet();
                        assertThat(ctx.query).contains("WHERE org_unit IN");
                        return List.of("order1", "order2", "order3");
                    }
                ),
                () -> List.of() // Empty list on failure
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();

            // Verify results
            assertThat(results).containsExactly("order1", "order2", "order3");
            assertThat(dbCalls.get()).isEqualTo(1);
            assertThat(auditLog).hasSize(1);
            assertThat(auditLog.get(0)).contains("User=john").contains("Results=3");

            // Execute same query again - should hit cache
            List<String> cachedResults = TryWithFallback.<List<String>>of(
                () -> profilingChain.execute(
                    new ProfilingContext("SELECT * FROM orders", "john"),
                    ctx -> {
                        if (ctx.hasCachedResult()) {
                            return ctx.getCachedResult();
                        }
                        dbCalls.incrementAndGet();
                        return List.of("order1", "order2", "order3");
                    }
                ),
                () -> List.of()
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();

            assertThat(cachedResults).containsExactly("order1", "order2", "order3");
            assertThat(dbCalls.get()).isEqualTo(1); // Still 1 - cache hit
            assertThat(queryCache.hitCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle failure scenario with circuit breaker")
        void shouldHandleFailureWithCircuitBreaker() {
            InterceptorChain<ProfilingContext, List<String>> chain = InterceptorChain
                .<ProfilingContext, List<String>>builder()
                .add(new Interceptor<>() {
                    @Override
                    public void onError(ProfilingContext ctx, Exception error) {
                        auditLog.add("FAILURE: " + error.getMessage());
                    }
                })
                .build();

            AtomicInteger primaryAttempts = new AtomicInteger(0);

            // Simulate multiple failures
            for (int i = 0; i < 5; i++) {
                List<String> result = TryWithFallback.of(
                    () -> chain.execute(
                        new ProfilingContext("SELECT * FROM orders", "john"),
                        ctx -> {
                            primaryAttempts.incrementAndGet();
                            throw new RuntimeException("DB connection failed");
                        }
                    ),
                    () -> List.of("fallback-order")
                )
                .withCircuitBreaker(circuitBreaker)
                .execute();

                assertThat(result).containsExactly("fallback-order");
            }

            // Circuit should be open after 3 failures
            assertThat(circuitBreaker.isOpen()).isTrue();
            // Primary only attempted 3 times (threshold), then circuit opened
            assertThat(primaryAttempts.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should handle concurrent profiled queries")
        void shouldHandleConcurrentProfiledQueries() throws InterruptedException {
            InterceptorChain<ProfilingContext, List<String>> chain = InterceptorChain
                .<ProfilingContext, List<String>>builder()
                .add(new Interceptor<>() {
                    @Override
                    public ProfilingContext before(ProfilingContext ctx) {
                        return ctx.withQuery(ctx.query + " WHERE user = '" + ctx.user + "'");
                    }
                })
                .build();

            int threadCount = 50;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(10);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger fallbackCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int userId = i;
                executor.submit(() -> {
                    try {
                        List<String> result = TryWithFallback.of(
                            () -> chain.execute(
                                new ProfilingContext("SELECT *", "user" + userId),
                                ctx -> {
                                    if (userId % 10 == 0) {
                                        throw new RuntimeException("Simulated failure");
                                    }
                                    return List.of("data-for-" + ctx.user);
                                }
                            ),
                            () -> {
                                fallbackCount.incrementAndGet();
                                return List.of("fallback");
                            }
                        )
                        .withCircuitBreaker(circuitBreaker)
                        .onSuccess(r -> successCount.incrementAndGet())
                        .execute();

                        assertThat(result).isNotEmpty();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCount.get() + fallbackCount.get()).isEqualTo(threadCount);
        }
    }

    // ========================================================================
    // HELPER CLASSES
    // ========================================================================

    /**
     * Simple query context for testing.
     */
    static class QueryContext {
        final String query;
        final String cacheKey;
        private String cachedResult;

        QueryContext(String query, String cacheKey) {
            this.query = query;
            this.cacheKey = cacheKey;
        }

        QueryContext withCachedResult(String result) {
            QueryContext copy = new QueryContext(this.query, this.cacheKey);
            copy.cachedResult = result;
            return copy;
        }

        boolean hasCachedResult() {
            return cachedResult != null;
        }

        String getCachedResult() {
            return cachedResult;
        }
    }

    /**
     * Profiling context simulating real framework.
     */
    static class ProfilingContext {
        final String query;
        final String user;
        private List<String> cachedResult;

        ProfilingContext(String query, String user) {
            this.query = query;
            this.user = user;
        }

        private ProfilingContext(String query, String user, List<String> cachedResult) {
            this.query = query;
            this.user = user;
            this.cachedResult = cachedResult;
        }

        ProfilingContext withQuery(String newQuery) {
            return new ProfilingContext(newQuery, this.user, this.cachedResult);
        }

        ProfilingContext withCachedResult(List<String> result) {
            return new ProfilingContext(this.query, this.user, result);
        }

        boolean hasCachedResult() {
            return cachedResult != null;
        }

        List<String> getCachedResult() {
            return cachedResult;
        }

        String cacheKey() {
            return query + ":" + user;
        }
    }
}
