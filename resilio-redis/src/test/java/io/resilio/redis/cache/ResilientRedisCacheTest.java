package io.resilio.redis.cache;

import io.resilio.core.circuit.CircuitBreaker;
import io.resilio.redis.cache.ResilientRedisCache.FailurePolicy;
import io.resilio.redis.cache.ResilientRedisCache.RedisCacheException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for ResilientRedisCache.
 *
 * Tests cover:
 * - All FailurePolicy behaviors (FALLBACK_TO_LOADER, RETURN_EMPTY, THROW_ERROR)
 * - TryWithFallback integration
 * - CircuitBreaker integration
 * - get/getIfPresent/put/invalidate operations
 * - Thread safety
 *
 * @author Test Engineer
 */
@DisplayName("ResilientRedisCache")
@ExtendWith(MockitoExtension.class)
class ResilientRedisCacheTest {

    @Mock
    private RedisCache<String, String> mockRedisCache;

    private ResilientRedisCache<String, String> resilientCache;

    // ========================================================================
    // BASIC OPERATIONS TESTS
    // ========================================================================

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @BeforeEach
        void setUp() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .build();
        }

        @Test
        @DisplayName("should get value from cache on hit")
        void shouldGetValueFromCacheOnHit() {
            when(mockRedisCache.get(eq("key1"), any())).thenReturn("cached-value");

            Optional<String> result = resilientCache.get("key1", k -> "loaded-value");

            assertThat(result).contains("cached-value");
            verify(mockRedisCache).get(eq("key1"), any());
        }

        @Test
        @DisplayName("should load and cache value on miss")
        void shouldLoadAndCacheValueOnMiss() {
            when(mockRedisCache.get(eq("key1"), any())).thenAnswer(invocation -> {
                Function<String, String> loader = invocation.getArgument(1);
                return loader.apply("key1");
            });

            Optional<String> result = resilientCache.get("key1", k -> "loaded-value");

            assertThat(result).contains("loaded-value");
        }

        @Test
        @DisplayName("should return empty from getIfPresent when not in cache")
        void shouldReturnEmptyFromGetIfPresentWhenNotInCache() {
            when(mockRedisCache.getIfPresent("missing")).thenReturn(Optional.empty());

            Optional<String> result = resilientCache.getIfPresent("missing");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return value from getIfPresent when in cache")
        void shouldReturnValueFromGetIfPresentWhenInCache() {
            when(mockRedisCache.getIfPresent("key1")).thenReturn(Optional.of("value1"));

            Optional<String> result = resilientCache.getIfPresent("key1");

            assertThat(result).contains("value1");
        }

        @Test
        @DisplayName("should put value into cache")
        void shouldPutValueIntoCache() {
            doNothing().when(mockRedisCache).put("key1", "value1");

            resilientCache.put("key1", "value1");

            verify(mockRedisCache).put("key1", "value1");
        }

        @Test
        @DisplayName("should put value with TTL")
        void shouldPutValueWithTtl() {
            Duration ttl = Duration.ofMinutes(5);
            doNothing().when(mockRedisCache).put("key1", "value1", ttl);

            resilientCache.put("key1", "value1", ttl);

            verify(mockRedisCache).put("key1", "value1", ttl);
        }

        @Test
        @DisplayName("should invalidate key")
        void shouldInvalidateKey() {
            doNothing().when(mockRedisCache).invalidate("key1");

            resilientCache.invalidate("key1");

            verify(mockRedisCache).invalidate("key1");
        }
    }

    // ========================================================================
    // FAILURE POLICY: FALLBACK_TO_LOADER
    // ========================================================================

    @Nested
    @DisplayName("Failure Policy: FALLBACK_TO_LOADER")
    class FallbackToLoaderPolicy {

        @BeforeEach
        void setUp() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .build();
        }

        @Test
        @DisplayName("should call loader when Redis fails")
        void shouldCallLoaderWhenRedisFails() {
            when(mockRedisCache.get(eq("key1"), any())).thenThrow(new RuntimeException("Redis down"));

            AtomicInteger loaderCalls = new AtomicInteger(0);
            Optional<String> result = resilientCache.get("key1", k -> {
                loaderCalls.incrementAndGet();
                return "fallback-value";
            });

            assertThat(result).contains("fallback-value");
            assertThat(loaderCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return empty when loader returns null")
        void shouldReturnEmptyWhenLoaderReturnsNull() {
            when(mockRedisCache.get(eq("key1"), any())).thenThrow(new RuntimeException("Redis down"));

            Optional<String> result = resilientCache.get("key1", k -> null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should report correct policy")
        void shouldReportCorrectPolicy() {
            assertThat(resilientCache.getFailurePolicy()).isEqualTo(FailurePolicy.FALLBACK_TO_LOADER);
        }
    }

    // ========================================================================
    // FAILURE POLICY: RETURN_EMPTY
    // ========================================================================

    @Nested
    @DisplayName("Failure Policy: RETURN_EMPTY")
    class ReturnEmptyPolicy {

        @BeforeEach
        void setUp() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.RETURN_EMPTY)
                .build();
        }

        @Test
        @DisplayName("should return empty when Redis fails")
        void shouldReturnEmptyWhenRedisFails() {
            when(mockRedisCache.get(eq("key1"), any())).thenThrow(new RuntimeException("Redis down"));

            AtomicInteger loaderCalls = new AtomicInteger(0);
            Optional<String> result = resilientCache.get("key1", k -> {
                loaderCalls.incrementAndGet();
                return "should-not-be-called";
            });

            assertThat(result).isEmpty();
            assertThat(loaderCalls.get()).isZero(); // Loader should NOT be called
        }

        @Test
        @DisplayName("should return cached value when Redis works")
        void shouldReturnCachedValueWhenRedisWorks() {
            when(mockRedisCache.get(eq("key1"), any())).thenReturn("cached-value");

            Optional<String> result = resilientCache.get("key1", k -> "unused");

            assertThat(result).contains("cached-value");
        }
    }

    // ========================================================================
    // FAILURE POLICY: THROW_ERROR
    // ========================================================================

    @Nested
    @DisplayName("Failure Policy: THROW_ERROR")
    class ThrowErrorPolicy {

        @BeforeEach
        void setUp() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.THROW_ERROR)
                .build();
        }

        @Test
        @DisplayName("should throw RedisCacheException when Redis fails")
        void shouldThrowWhenRedisFails() {
            when(mockRedisCache.get(eq("key1"), any())).thenThrow(new RuntimeException("Redis down"));

            assertThatThrownBy(() -> resilientCache.get("key1", k -> "unused"))
                .isInstanceOf(RedisCacheException.class)
                .hasMessageContaining("Redis unavailable and policy is THROW_ERROR");
        }

        @Test
        @DisplayName("should return value when Redis works")
        void shouldReturnValueWhenRedisWorks() {
            when(mockRedisCache.get(eq("key1"), any())).thenReturn("cached-value");

            Optional<String> result = resilientCache.get("key1", k -> "unused");

            assertThat(result).contains("cached-value");
        }
    }

    // ========================================================================
    // CIRCUIT BREAKER INTEGRATION
    // ========================================================================

    @Nested
    @DisplayName("Circuit Breaker Integration")
    class CircuitBreakerIntegration {

        @Test
        @DisplayName("should use default circuit breaker")
        void shouldUseDefaultCircuitBreaker() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .build();

            assertThat(resilientCache.getCircuitBreaker()).isNotNull();
            assertThat(resilientCache.getCircuitBreaker().isClosed()).isTrue();
        }

        @Test
        @DisplayName("should use custom circuit breaker")
        void shouldUseCustomCircuitBreaker() {
            CircuitBreaker customBreaker = CircuitBreaker.builder()
                .name("custom-cb")
                .failureThreshold(10)
                .resetTimeout(Duration.ofMinutes(1))
                .build();

            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .circuitBreaker(customBreaker)
                .build();

            assertThat(resilientCache.getCircuitBreaker()).isSameAs(customBreaker);
        }

        @Test
        @DisplayName("should open circuit after multiple failures")
        void shouldOpenCircuitAfterMultipleFailures() {
            CircuitBreaker breaker = CircuitBreaker.builder()
                .name("test-cb")
                .failureThreshold(3)
                .resetTimeout(Duration.ofSeconds(60))
                .build();

            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .circuitBreaker(breaker)
                .build();

            when(mockRedisCache.get(any(), any())).thenThrow(new RuntimeException("Redis down"));

            // Trigger failures
            for (int i = 0; i < 5; i++) {
                resilientCache.get("key" + i, k -> "fallback");
            }

            assertThat(breaker.isOpen()).isTrue();
        }

        @Test
        @DisplayName("should use fallback when circuit is open")
        void shouldUseFallbackWhenCircuitIsOpen() {
            CircuitBreaker breaker = CircuitBreaker.builder()
                .name("test-cb")
                .failureThreshold(1)
                .resetTimeout(Duration.ofSeconds(60))
                .build();

            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .circuitBreaker(breaker)
                .build();

            when(mockRedisCache.get(any(), any())).thenThrow(new RuntimeException("Redis down"));

            // Open circuit
            resilientCache.get("key1", k -> "fallback1");

            // Reset mock to track new calls
            reset(mockRedisCache);

            // Now circuit is open - should go straight to fallback
            AtomicInteger loaderCalls = new AtomicInteger(0);
            Optional<String> result = resilientCache.get("key2", k -> {
                loaderCalls.incrementAndGet();
                return "direct-fallback";
            });

            assertThat(result).contains("direct-fallback");
            assertThat(loaderCalls.get()).isEqualTo(1);
            // Redis should not be called when circuit is open
            verifyNoInteractions(mockRedisCache);
        }
    }

    // ========================================================================
    // ERROR HANDLING TESTS
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("should handle put failures silently")
        void shouldHandlePutFailuresSilently() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .build();

            doThrow(new RuntimeException("Redis write failed")).when(mockRedisCache).put(any(), any());

            // Should not throw
            assertThatCode(() -> resilientCache.put("key1", "value1"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle invalidate failures silently")
        void shouldHandleInvalidateFailuresSilently() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .build();

            doThrow(new RuntimeException("Redis delete failed")).when(mockRedisCache).invalidate(any());

            // Should not throw
            assertThatCode(() -> resilientCache.invalidate("key1"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should return empty on getIfPresent failure")
        void shouldReturnEmptyOnGetIfPresentFailure() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.THROW_ERROR) // Even with THROW_ERROR, getIfPresent returns empty
                .build();

            when(mockRedisCache.getIfPresent(any())).thenThrow(new RuntimeException("Redis read failed"));

            Optional<String> result = resilientCache.getIfPresent("key1");

            assertThat(result).isEmpty();
        }
    }

    // ========================================================================
    // BUILDER TESTS
    // ========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should require redisCache")
        void shouldRequireRedisCache() {
            assertThatThrownBy(() ->
                ResilientRedisCache.<String, String>builder()
                    .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                    .build()
            ).isInstanceOf(IllegalStateException.class)
             .hasMessageContaining("redisCache is required");
        }

        @Test
        @DisplayName("should use default failure policy")
        void shouldUseDefaultFailurePolicy() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .build();

            assertThat(resilientCache.getFailurePolicy()).isEqualTo(FailurePolicy.FALLBACK_TO_LOADER);
        }

        @Test
        @DisplayName("should provide access to delegate")
        void shouldProvideAccessToDelegate() {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .build();

            assertThat(resilientCache.getDelegate()).isSameAs(mockRedisCache);
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
        void shouldHandleConcurrentGetsSafely() throws InterruptedException {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .build();

            when(mockRedisCache.get(any(), any())).thenAnswer(invocation -> {
                Thread.sleep(10); // Simulate some latency
                return "cached-value";
            });

            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(10);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 50; i++) {
                            Optional<String> result = resilientCache.get("key" + (i % 10), k -> "fallback");
                            if (result.isEmpty()) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(errors.get()).isZero();
        }

        @Test
        @DisplayName("should handle concurrent mixed operations safely")
        void shouldHandleConcurrentMixedOperationsSafely() throws InterruptedException {
            resilientCache = ResilientRedisCache.<String, String>builder()
                .redisCache(mockRedisCache)
                .failurePolicy(FailurePolicy.FALLBACK_TO_LOADER)
                .build();

            when(mockRedisCache.get(any(), any())).thenReturn("cached");
            when(mockRedisCache.getIfPresent(any())).thenReturn(Optional.of("cached"));
            doNothing().when(mockRedisCache).put(any(), any());
            doNothing().when(mockRedisCache).invalidate(any());

            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(10);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 100; i++) {
                            switch (i % 4) {
                                case 0 -> resilientCache.get("key" + i, k -> "value");
                                case 1 -> resilientCache.getIfPresent("key" + i);
                                case 2 -> resilientCache.put("key" + i, "value" + i);
                                case 3 -> resilientCache.invalidate("key" + i);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(errors.get()).isZero();
        }
    }
}
