package io.resilio.core.circuit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for CircuitBreaker.
 *
 * Tests cover:
 * - State transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)
 * - Failure threshold behavior
 * - Reset timeout behavior
 * - Concurrent access
 * - Edge cases
 *
 * @author Test Engineer
 */
@DisplayName("CircuitBreaker")
class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.builder()
            .name("test-breaker")
            .failureThreshold(3)
            .resetTimeout(Duration.ofMillis(100))
            .build();
    }

    // ========================================================================
    // INITIAL STATE TESTS
    // ========================================================================

    @Nested
    @DisplayName("Initial State")
    class InitialState {

        @Test
        @DisplayName("should start in CLOSED state")
        void shouldStartClosed() {
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(circuitBreaker.isClosed()).isTrue();
            assertThat(circuitBreaker.isOpen()).isFalse();
        }

        @Test
        @DisplayName("should allow requests when CLOSED")
        void shouldAllowRequestsWhenClosed() {
            assertThat(circuitBreaker.allowRequest()).isTrue();
        }

        @Test
        @DisplayName("should have zero failure count initially")
        void shouldHaveZeroFailureCount() {
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }

        @Test
        @DisplayName("should have configured name")
        void shouldHaveConfiguredName() {
            assertThat(circuitBreaker.getName()).isEqualTo("test-breaker");
        }
    }

    // ========================================================================
    // STATE TRANSITION TESTS
    // ========================================================================

    @Nested
    @DisplayName("State Transitions")
    class StateTransitions {

        @Test
        @DisplayName("should transition to OPEN after reaching failure threshold")
        void shouldOpenAfterFailureThreshold() {
            // Given: circuit is CLOSED
            assertThat(circuitBreaker.isClosed()).isTrue();

            // When: failure threshold is reached
            circuitBreaker.recordFailure();
            circuitBreaker.recordFailure();
            assertThat(circuitBreaker.isClosed()).isTrue(); // Still closed

            circuitBreaker.recordFailure(); // 3rd failure = threshold

            // Then: circuit should be OPEN
            assertThat(circuitBreaker.isOpen()).isTrue();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("should transition to HALF_OPEN after reset timeout")
        void shouldTransitionToHalfOpenAfterTimeout() throws InterruptedException {
            // Given: circuit is OPEN
            openCircuit();
            assertThat(circuitBreaker.isOpen()).isTrue();

            // When: reset timeout elapses
            Thread.sleep(150); // > 100ms reset timeout

            // Then: should transition to HALF_OPEN on next request
            assertThat(circuitBreaker.allowRequest()).isTrue();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        }

        @Test
        @DisplayName("should transition from HALF_OPEN to CLOSED after successes")
        void shouldCloseAfterSuccessesInHalfOpen() throws InterruptedException {
            // Given: circuit is HALF_OPEN
            openCircuit();
            Thread.sleep(150);
            circuitBreaker.allowRequest(); // Triggers HALF_OPEN

            // When: multiple successes occur
            circuitBreaker.recordSuccess();
            circuitBreaker.recordSuccess();
            circuitBreaker.recordSuccess(); // 3 successes required

            // Then: circuit should be CLOSED
            assertThat(circuitBreaker.isClosed()).isTrue();
        }

        @Test
        @DisplayName("should transition from HALF_OPEN to OPEN on single failure")
        void shouldReopenOnFailureInHalfOpen() throws InterruptedException {
            // Given: circuit is HALF_OPEN
            openCircuit();
            Thread.sleep(150);
            circuitBreaker.allowRequest();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            // When: a failure occurs
            circuitBreaker.recordFailure();

            // Then: circuit should be OPEN again
            assertThat(circuitBreaker.isOpen()).isTrue();
        }
    }

    // ========================================================================
    // EXECUTE METHOD TESTS
    // ========================================================================

    @Nested
    @DisplayName("Execute with Fallback")
    class ExecuteWithFallback {

        @Test
        @DisplayName("should execute primary operation when CLOSED")
        void shouldExecutePrimaryWhenClosed() {
            AtomicInteger counter = new AtomicInteger(0);

            String result = circuitBreaker.execute(
                () -> {
                    counter.incrementAndGet();
                    return "primary";
                },
                () -> "fallback"
            );

            assertThat(result).isEqualTo("primary");
            assertThat(counter.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should execute fallback when OPEN")
        void shouldExecuteFallbackWhenOpen() {
            // Given: circuit is OPEN
            openCircuit();

            AtomicInteger primaryCounter = new AtomicInteger(0);
            AtomicInteger fallbackCounter = new AtomicInteger(0);

            // When
            String result = circuitBreaker.execute(
                () -> {
                    primaryCounter.incrementAndGet();
                    return "primary";
                },
                () -> {
                    fallbackCounter.incrementAndGet();
                    return "fallback";
                }
            );

            // Then
            assertThat(result).isEqualTo("fallback");
            assertThat(primaryCounter.get()).isZero();
            assertThat(fallbackCounter.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should execute fallback when primary throws exception")
        void shouldExecuteFallbackOnException() {
            String result = circuitBreaker.execute(
                () -> {
                    throw new RuntimeException("Simulated failure");
                },
                () -> "fallback"
            );

            assertThat(result).isEqualTo("fallback");
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should record success on successful execution")
        void shouldRecordSuccessOnSuccessfulExecution() {
            // First, record some failures
            circuitBreaker.recordFailure();
            circuitBreaker.recordFailure();
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(2);

            // Execute successfully
            circuitBreaker.execute(() -> "success", () -> "fallback");

            // Failure count should be reset
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }
    }

    // ========================================================================
    // EXECUTE OR THROW TESTS
    // ========================================================================

    @Nested
    @DisplayName("Execute or Throw")
    class ExecuteOrThrow {

        @Test
        @DisplayName("should execute and return result when CLOSED")
        void shouldExecuteWhenClosed() {
            String result = circuitBreaker.executeOrThrow(() -> "result");
            assertThat(result).isEqualTo("result");
        }

        @Test
        @DisplayName("should throw CircuitBreakerOpenException when OPEN")
        void shouldThrowWhenOpen() {
            openCircuit();

            assertThatThrownBy(() -> circuitBreaker.executeOrThrow(() -> "result"))
                .isInstanceOf(CircuitBreaker.CircuitBreakerOpenException.class)
                .hasMessageContaining("test-breaker")
                .hasMessageContaining("OPEN");
        }

        @Test
        @DisplayName("should propagate exception from operation")
        void shouldPropagateException() {
            assertThatThrownBy(() ->
                circuitBreaker.executeOrThrow(() -> {
                    throw new IllegalStateException("Test exception");
                })
            ).isInstanceOf(IllegalStateException.class)
             .hasMessage("Test exception");

            // Should also record failure
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(1);
        }
    }

    // ========================================================================
    // RESET TESTS
    // ========================================================================

    @Nested
    @DisplayName("Reset")
    class Reset {

        @Test
        @DisplayName("should reset to CLOSED state")
        void shouldResetToClosed() {
            openCircuit();
            assertThat(circuitBreaker.isOpen()).isTrue();

            circuitBreaker.reset();

            assertThat(circuitBreaker.isClosed()).isTrue();
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }

        @Test
        @DisplayName("should reset failure count")
        void shouldResetFailureCount() {
            circuitBreaker.recordFailure();
            circuitBreaker.recordFailure();
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(2);

            circuitBreaker.reset();

            assertThat(circuitBreaker.getFailureCount()).isZero();
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
            CircuitBreaker breaker = CircuitBreaker.createDefault();

            assertThat(breaker.getName()).isEqualTo("default");
            assertThat(breaker.isClosed()).isTrue();
        }

        @Test
        @DisplayName("should reject non-positive failure threshold")
        void shouldRejectInvalidThreshold() {
            assertThatThrownBy(() ->
                CircuitBreaker.builder()
                    .failureThreshold(0)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("positive");

            assertThatThrownBy(() ->
                CircuitBreaker.builder()
                    .failureThreshold(-1)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject invalid reset timeout")
        void shouldRejectInvalidTimeout() {
            assertThatThrownBy(() ->
                CircuitBreaker.builder()
                    .resetTimeout(Duration.ZERO)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() ->
                CircuitBreaker.builder()
                    .resetTimeout(Duration.ofMillis(-100))
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() ->
                CircuitBreaker.builder()
                    .resetTimeout(null)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ========================================================================
    // CONCURRENT ACCESS TESTS
    // ========================================================================

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccess {

        @Test
        @DisplayName("should handle concurrent failures correctly")
        void shouldHandleConcurrentFailures() throws InterruptedException {
            CircuitBreaker breaker = CircuitBreaker.builder()
                .failureThreshold(100)
                .resetTimeout(Duration.ofSeconds(30))
                .build();

            int threadCount = 10;
            int failuresPerThread = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < failuresPerThread; j++) {
                            breaker.recordFailure();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Start all threads
            boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            // Should be OPEN after 100 failures
            assertThat(breaker.isOpen()).isTrue();
        }

        @Test
        @DisplayName("should handle concurrent execute calls")
        void shouldHandleConcurrentExecute() throws InterruptedException {
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger fallbackCount = new AtomicInteger(0);

            int threadCount = 50;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(10);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        circuitBreaker.execute(
                            () -> {
                                if (index % 3 == 0) {
                                    throw new RuntimeException("Fail");
                                }
                                successCount.incrementAndGet();
                                return "ok";
                            },
                            () -> {
                                fallbackCount.incrementAndGet();
                                return "fallback";
                            }
                        );
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCount.get() + fallbackCount.get()).isEqualTo(threadCount);
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private void openCircuit() {
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
    }
}
