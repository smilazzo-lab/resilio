package io.resilio.core.circuit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for TryWithFallback.
 *
 * Tests cover:
 * - Primary execution success
 * - Fallback on failure
 * - Circuit breaker integration
 * - Callbacks (onSuccess, onError)
 * - Edge cases and null handling
 * - Concurrent execution
 *
 * @author Test Engineer
 */
@DisplayName("TryWithFallback")
class TryWithFallbackTest {

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
    // BASIC EXECUTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Basic Execution")
    class BasicExecution {

        @Test
        @DisplayName("should execute primary and return result")
        void shouldExecutePrimary() {
            String result = TryWithFallback.of(
                () -> "primary-result",
                () -> "fallback-result"
            ).execute();

            assertThat(result).isEqualTo("primary-result");
        }

        @Test
        @DisplayName("should execute fallback when primary throws")
        void shouldExecuteFallbackOnException() {
            String result = TryWithFallback.of(
                () -> { throw new RuntimeException("Simulated failure"); },
                () -> "fallback-result"
            ).execute();

            assertThat(result).isEqualTo("fallback-result");
        }

        @Test
        @DisplayName("should return null from primary if primary returns null")
        void shouldReturnNullFromPrimary() {
            String result = TryWithFallback.<String>of(
                () -> null,
                () -> "fallback"
            ).execute();

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should work with ofValue factory method")
        void shouldWorkWithOfValue() {
            String result = TryWithFallback.ofValue(
                () -> { throw new RuntimeException(); },
                "constant-fallback"
            ).execute();

            assertThat(result).isEqualTo("constant-fallback");
        }
    }

    // ========================================================================
    // CIRCUIT BREAKER INTEGRATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Circuit Breaker Integration")
    class CircuitBreakerIntegration {

        @Test
        @DisplayName("should skip primary when circuit is OPEN")
        void shouldSkipPrimaryWhenOpen() {
            // Open the circuit
            openCircuit(circuitBreaker);

            AtomicBoolean primaryCalled = new AtomicBoolean(false);

            String result = TryWithFallback.of(
                () -> {
                    primaryCalled.set(true);
                    return "primary";
                },
                () -> "fallback"
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();

            assertThat(result).isEqualTo("fallback");
            assertThat(primaryCalled.get()).isFalse();
        }

        @Test
        @DisplayName("should record success to circuit breaker")
        void shouldRecordSuccessToCircuitBreaker() {
            // Record some failures (but not enough to open)
            circuitBreaker.recordFailure();
            circuitBreaker.recordFailure();
            assertThat(circuitBreaker.getFailureCount()).isEqualTo(2);

            TryWithFallback.of(
                () -> "success",
                () -> "fallback"
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();

            // Success should reset failure count
            assertThat(circuitBreaker.getFailureCount()).isZero();
        }

        @Test
        @DisplayName("should record failure to circuit breaker")
        void shouldRecordFailureToCircuitBreaker() {
            assertThat(circuitBreaker.getFailureCount()).isZero();

            TryWithFallback.of(
                () -> { throw new RuntimeException(); },
                () -> "fallback"
            )
            .withCircuitBreaker(circuitBreaker)
            .execute();

            assertThat(circuitBreaker.getFailureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should open circuit after threshold failures")
        void shouldOpenCircuitAfterThreshold() {
            for (int i = 0; i < 3; i++) {
                final int iteration = i;
                TryWithFallback.of(
                    () -> { throw new RuntimeException("Fail " + iteration); },
                    () -> "fallback"
                )
                .withCircuitBreaker(circuitBreaker)
                .execute();
            }

            assertThat(circuitBreaker.isOpen()).isTrue();
        }

        @Test
        @DisplayName("should work without circuit breaker")
        void shouldWorkWithoutCircuitBreaker() {
            String result = TryWithFallback.of(
                () -> "primary",
                () -> "fallback"
            )
            // No withCircuitBreaker() call
            .execute();

            assertThat(result).isEqualTo("primary");
        }
    }

    // ========================================================================
    // CALLBACK TESTS
    // ========================================================================

    @Nested
    @DisplayName("Callbacks")
    class Callbacks {

        @Test
        @DisplayName("should call onSuccess with result")
        void shouldCallOnSuccess() {
            AtomicReference<String> capturedResult = new AtomicReference<>();

            TryWithFallback.of(
                () -> "success-value",
                () -> "fallback"
            )
            .onSuccess(capturedResult::set)
            .execute();

            assertThat(capturedResult.get()).isEqualTo("success-value");
        }

        @Test
        @DisplayName("should NOT call onSuccess when primary fails")
        void shouldNotCallOnSuccessOnFailure() {
            AtomicBoolean successCalled = new AtomicBoolean(false);

            TryWithFallback.of(
                () -> { throw new RuntimeException(); },
                () -> "fallback"
            )
            .onSuccess(r -> successCalled.set(true))
            .execute();

            assertThat(successCalled.get()).isFalse();
        }

        @Test
        @DisplayName("should call onError with exception")
        void shouldCallOnError() {
            AtomicReference<Exception> capturedException = new AtomicReference<>();
            RuntimeException testException = new RuntimeException("Test error");

            TryWithFallback.of(
                () -> { throw testException; },
                () -> "fallback"
            )
            .onError(capturedException::set)
            .execute();

            assertThat(capturedException.get()).isSameAs(testException);
        }

        @Test
        @DisplayName("should NOT call onError when primary succeeds")
        void shouldNotCallOnErrorOnSuccess() {
            AtomicBoolean errorCalled = new AtomicBoolean(false);

            TryWithFallback.of(
                () -> "success",
                () -> "fallback"
            )
            .onError(e -> errorCalled.set(true))
            .execute();

            assertThat(errorCalled.get()).isFalse();
        }

        @Test
        @DisplayName("should call both callbacks in appropriate scenarios")
        void shouldCallBothCallbacksAppropriately() {
            List<String> events = new ArrayList<>();

            // First call - success
            TryWithFallback.of(
                () -> "success",
                () -> "fallback"
            )
            .onSuccess(r -> events.add("success:" + r))
            .onError(e -> events.add("error:" + e.getMessage()))
            .execute();

            // Second call - failure
            TryWithFallback.of(
                () -> { throw new RuntimeException("boom"); },
                () -> "fallback"
            )
            .onSuccess(r -> events.add("success:" + r))
            .onError(e -> events.add("error:" + e.getMessage()))
            .execute();

            assertThat(events).containsExactly("success:success", "error:boom");
        }
    }

    // ========================================================================
    // VOID EXECUTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Void Execution")
    class VoidExecution {

        @Test
        @DisplayName("should execute primary runnable")
        void shouldExecutePrimaryRunnable() {
            AtomicBoolean executed = new AtomicBoolean(false);

            TryWithFallback.executeVoid(
                () -> executed.set(true),
                () -> fail("Fallback should not be called")
            );

            assertThat(executed.get()).isTrue();
        }

        @Test
        @DisplayName("should execute fallback runnable on exception")
        void shouldExecuteFallbackRunnable() {
            AtomicBoolean fallbackExecuted = new AtomicBoolean(false);

            TryWithFallback.executeVoid(
                () -> { throw new RuntimeException(); },
                () -> fallbackExecuted.set(true)
            );

            assertThat(fallbackExecuted.get()).isTrue();
        }

        @Test
        @DisplayName("should work with circuit breaker")
        void shouldWorkWithCircuitBreakerVoid() {
            AtomicBoolean primaryCalled = new AtomicBoolean(false);
            AtomicBoolean fallbackCalled = new AtomicBoolean(false);

            openCircuit(circuitBreaker);

            TryWithFallback.executeVoid(
                () -> primaryCalled.set(true),
                () -> fallbackCalled.set(true),
                circuitBreaker
            );

            assertThat(primaryCalled.get()).isFalse();
            assertThat(fallbackCalled.get()).isTrue();
        }
    }

    // ========================================================================
    // VALIDATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("should reject null primary")
        void shouldRejectNullPrimary() {
            assertThatThrownBy(() ->
                TryWithFallback.of(null, () -> "fallback")
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("primary");
        }

        @Test
        @DisplayName("should reject null fallback")
        void shouldRejectNullFallback() {
            assertThatThrownBy(() ->
                TryWithFallback.of(() -> "primary", null)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("fallback");
        }

        @Test
        @DisplayName("should accept null circuit breaker")
        void shouldAcceptNullCircuitBreaker() {
            String result = TryWithFallback.of(
                () -> "primary",
                () -> "fallback"
            )
            .withCircuitBreaker(null)
            .execute();

            assertThat(result).isEqualTo("primary");
        }
    }

    // ========================================================================
    // CHAINING TESTS
    // ========================================================================

    @Nested
    @DisplayName("Fluent Chaining")
    class FluentChaining {

        @Test
        @DisplayName("should support full fluent chain")
        void shouldSupportFullFluentChain() {
            AtomicReference<String> successResult = new AtomicReference<>();
            AtomicReference<Exception> errorResult = new AtomicReference<>();

            String result = TryWithFallback.of(
                () -> "primary-value",
                () -> "fallback-value"
            )
            .withCircuitBreaker(circuitBreaker)
            .onSuccess(successResult::set)
            .onError(errorResult::set)
            .execute();

            assertThat(result).isEqualTo("primary-value");
            assertThat(successResult.get()).isEqualTo("primary-value");
            assertThat(errorResult.get()).isNull();
        }

        @Test
        @DisplayName("should allow chaining in any order")
        void shouldAllowChainingInAnyOrder() {
            AtomicBoolean successCalled = new AtomicBoolean(false);

            // Different order
            String result = TryWithFallback.of(
                () -> "value",
                () -> "fallback"
            )
            .onSuccess(v -> successCalled.set(true))
            .withCircuitBreaker(circuitBreaker)
            .onError(e -> {})
            .execute();

            assertThat(result).isEqualTo("value");
            assertThat(successCalled.get()).isTrue();
        }
    }

    // ========================================================================
    // CONCURRENT EXECUTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Concurrent Execution")
    class ConcurrentExecution {

        @Test
        @DisplayName("should handle concurrent executions safely")
        void shouldHandleConcurrentExecutions() throws InterruptedException {
            int threadCount = 100;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger fallbackCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(10);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        TryWithFallback.of(
                            () -> {
                                if (index % 4 == 0) {
                                    throw new RuntimeException();
                                }
                                return "success";
                            },
                            () -> "fallback"
                        )
                        .withCircuitBreaker(circuitBreaker)
                        .onSuccess(r -> successCount.incrementAndGet())
                        .onError(e -> fallbackCount.incrementAndGet())
                        .execute();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            // Some succeeded, some failed - exact counts depend on circuit breaker state
            assertThat(successCount.get() + fallbackCount.get()).isLessThanOrEqualTo(threadCount);
        }
    }

    // ========================================================================
    // EXCEPTION HANDLING TESTS
    // ========================================================================

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandling {

        @Test
        @DisplayName("should catch all Exception subclasses")
        void shouldCatchAllExceptions() {
            // Checked exception wrapped in RuntimeException
            String result1 = TryWithFallback.of(
                () -> { throw new IllegalStateException(); },
                () -> "fallback"
            ).execute();

            // Runtime exception
            String result2 = TryWithFallback.of(
                () -> { throw new NullPointerException(); },
                () -> "fallback"
            ).execute();

            assertThat(result1).isEqualTo("fallback");
            assertThat(result2).isEqualTo("fallback");
        }

        @Test
        @DisplayName("should propagate Error (not caught)")
        void shouldPropagateError() {
            assertThatThrownBy(() ->
                TryWithFallback.of(
                    () -> { throw new OutOfMemoryError("Test"); },
                    () -> "fallback"
                ).execute()
            ).isInstanceOf(OutOfMemoryError.class);
        }

        @Test
        @DisplayName("should handle exception in fallback")
        void shouldHandleExceptionInFallback() {
            // If both primary and fallback throw, the fallback exception wins
            assertThatThrownBy(() ->
                TryWithFallback.of(
                    () -> { throw new RuntimeException("Primary"); },
                    () -> { throw new IllegalStateException("Fallback"); }
                ).execute()
            ).isInstanceOf(IllegalStateException.class)
             .hasMessage("Fallback");
        }

        @Test
        @DisplayName("should trigger fallback when onSuccess callback throws")
        void shouldTriggerFallbackWhenOnSuccessThrows() {
            // Exception in onSuccess callback is caught and triggers fallback
            String result = TryWithFallback.of(
                () -> "success",
                () -> "fallback"
            )
            .onSuccess(r -> { throw new RuntimeException("Callback error"); })
            .execute();

            // The implementation catches callback exceptions and returns fallback
            assertThat(result).isEqualTo("fallback");
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private void openCircuit(CircuitBreaker breaker) {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
    }
}
