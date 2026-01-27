package io.resilio.core.circuit;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit Breaker implementation for resilient cache operations.
 *
 * <p>The circuit breaker pattern prevents cascading failures by temporarily
 * disabling operations that are likely to fail. It has three states:</p>
 *
 * <ul>
 *   <li><b>CLOSED</b> - Normal operation, requests pass through</li>
 *   <li><b>OPEN</b> - Failure threshold exceeded, requests are rejected immediately</li>
 *   <li><b>HALF_OPEN</b> - Testing if the underlying service has recovered</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * CircuitBreaker breaker = CircuitBreaker.builder()
 *     .failureThreshold(5)
 *     .resetTimeout(Duration.ofSeconds(30))
 *     .build();
 *
 * // Execute with circuit breaker protection
 * String result = breaker.execute(
 *     () -> externalService.call(),    // primary operation
 *     () -> "fallback-value"           // fallback if circuit is open
 * );
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class CircuitBreaker {

    /**
     * Circuit breaker states.
     */
    public enum State {
        /** Normal operation - requests pass through */
        CLOSED,
        /** Failure threshold exceeded - requests rejected */
        OPEN,
        /** Testing recovery - allowing limited requests */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration resetTimeout;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private volatile Instant lastFailureTime;
    private final String name;

    private CircuitBreaker(Builder builder) {
        this.name = builder.name;
        this.failureThreshold = builder.failureThreshold;
        this.resetTimeout = builder.resetTimeout;
    }

    /**
     * Creates a new builder for CircuitBreaker.
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a circuit breaker with default settings.
     * @return default circuit breaker (threshold=5, timeout=30s)
     */
    public static CircuitBreaker createDefault() {
        return builder().build();
    }

    /**
     * Executes the operation with circuit breaker protection.
     *
     * @param operation the primary operation to execute
     * @param fallback the fallback to use if circuit is open
     * @param <T> the return type
     * @return the result from operation or fallback
     */
    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        if (!allowRequest()) {
            return fallback.get();
        }

        try {
            T result = operation.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            return fallback.get();
        }
    }

    /**
     * Executes the operation, throwing CircuitBreakerOpenException if circuit is open.
     *
     * @param operation the operation to execute
     * @param <T> the return type
     * @return the result from operation
     * @throws CircuitBreakerOpenException if the circuit is open
     */
    public <T> T executeOrThrow(Supplier<T> operation) throws CircuitBreakerOpenException {
        if (!allowRequest()) {
            throw new CircuitBreakerOpenException(name, state.get());
        }

        try {
            T result = operation.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    /**
     * Checks if a request should be allowed through the circuit breaker.
     *
     * @return true if the request can proceed
     */
    public boolean allowRequest() {
        State currentState = state.get();

        if (currentState == State.CLOSED) {
            return true;
        }

        if (currentState == State.OPEN) {
            // Check if reset timeout has elapsed
            if (lastFailureTime != null &&
                Instant.now().isAfter(lastFailureTime.plus(resetTimeout))) {
                // Transition to HALF_OPEN
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    failureCount.set(0);
                    successCount.set(0);
                }
                return true;
            }
            return false;
        }

        // HALF_OPEN - allow request for testing
        return true;
    }

    /**
     * Records a successful operation.
     */
    public void recordSuccess() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            // After a few successes, close the circuit
            if (successes >= 3) {
                state.compareAndSet(State.HALF_OPEN, State.CLOSED);
                failureCount.set(0);
                successCount.set(0);
            }
        } else if (currentState == State.CLOSED) {
            // Reset failure count on success
            failureCount.set(0);
        }
    }

    /**
     * Records a failed operation.
     */
    public void recordFailure() {
        lastFailureTime = Instant.now();
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // Single failure in HALF_OPEN returns to OPEN
            state.compareAndSet(State.HALF_OPEN, State.OPEN);
            return;
        }

        if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                state.compareAndSet(State.CLOSED, State.OPEN);
            }
        }
    }

    /**
     * Manually resets the circuit breaker to CLOSED state.
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime = null;
    }

    /**
     * Returns the current state of the circuit breaker.
     * @return current state
     */
    public State getState() {
        return state.get();
    }

    /**
     * Returns true if the circuit is closed (normal operation).
     * @return true if closed
     */
    public boolean isClosed() {
        return state.get() == State.CLOSED;
    }

    /**
     * Returns true if the circuit is open (rejecting requests).
     * @return true if open
     */
    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    /**
     * Returns the name of this circuit breaker.
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the current failure count.
     * @return failure count
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Builder for CircuitBreaker.
     */
    public static class Builder {
        private String name = "default";
        private int failureThreshold = 5;
        private Duration resetTimeout = Duration.ofSeconds(30);

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder failureThreshold(int threshold) {
            if (threshold <= 0) {
                throw new IllegalArgumentException("Failure threshold must be positive");
            }
            this.failureThreshold = threshold;
            return this;
        }

        public Builder resetTimeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Reset timeout must be positive");
            }
            this.resetTimeout = timeout;
            return this;
        }

        public CircuitBreaker build() {
            return new CircuitBreaker(this);
        }
    }

    /**
     * Exception thrown when circuit breaker is open and request is rejected.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        private final String circuitName;
        private final State state;

        public CircuitBreakerOpenException(String circuitName, State state) {
            super("Circuit breaker '" + circuitName + "' is " + state);
            this.circuitName = circuitName;
            this.state = state;
        }

        public String getCircuitName() {
            return circuitName;
        }

        public State getState() {
            return state;
        }
    }
}
