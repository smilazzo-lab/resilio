package io.resilio.core.circuit;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Executor with automatic fallback and optional circuit breaker.
 *
 * <h2>Usage Patterns</h2>
 *
 * <pre>{@code
 * // 1. SIMPLE (no circuit breaker):
 * TryWithFallback.of(this::primary, this::fallback).execute();
 *
 * // 2. WITH CIRCUIT BREAKER:
 * TryWithFallback.of(this::primary, this::fallback)
 *     .withCircuitBreaker(circuitBreaker)
 *     .execute();
 *
 * // 3. WITH SUCCESS CALLBACK (for caching):
 * TryWithFallback.of(() -> delegate.call(), () -> Optional.empty())
 *     .withCircuitBreaker(circuitBreaker)
 *     .onSuccess(result -> cache.put(key, result))
 *     .onError(e -> log.error("Call failed", e))
 *     .execute();
 * }</pre>
 *
 * Thread-safe if primary, fallback and circuitBreaker are thread-safe.
 *
 * @param <T> return type of the operation
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.1.0
 */
public class TryWithFallback<T> {

    private final Supplier<T> primary;
    private final Supplier<T> fallback;
    private CircuitBreaker circuitBreaker;
    private Consumer<T> successHandler;
    private Consumer<Exception> errorHandler;

    private TryWithFallback(Supplier<T> primary, Supplier<T> fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /**
     * Main factory method.
     *
     * @param primary primary operation
     * @param fallback fallback operation
     * @param <T> return type
     * @return configurable instance
     */
    public static <T> TryWithFallback<T> of(Supplier<T> primary, Supplier<T> fallback) {
        if (primary == null) {
            throw new IllegalArgumentException("primary cannot be null");
        }
        if (fallback == null) {
            throw new IllegalArgumentException("fallback cannot be null");
        }
        return new TryWithFallback<>(primary, fallback);
    }

    /**
     * Factory method for constant fallback value.
     *
     * @param primary primary operation
     * @param fallbackValue constant fallback value
     * @param <T> return type
     * @return configurable instance
     */
    public static <T> TryWithFallback<T> ofValue(Supplier<T> primary, T fallbackValue) {
        return of(primary, () -> fallbackValue);
    }

    /**
     * Adds circuit breaker protection.
     *
     * @param circuitBreaker circuit breaker instance
     * @return this for chaining
     */
    public TryWithFallback<T> withCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
        return this;
    }

    /**
     * Adds success handler (for caching, logging, metrics, etc.).
     * Called with the result after successful primary execution.
     *
     * @param successHandler consumer called on success with the result
     * @return this for chaining
     */
    public TryWithFallback<T> onSuccess(Consumer<T> successHandler) {
        this.successHandler = successHandler;
        return this;
    }

    /**
     * Adds error handler (for logging, metrics, etc.).
     *
     * @param errorHandler consumer called on exception
     * @return this for chaining
     */
    public TryWithFallback<T> onError(Consumer<Exception> errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    /**
     * Executes the operation with automatic fallback.
     *
     * <p>Logic:</p>
     * <ol>
     *   <li>If circuit breaker present and OPEN → fallback directly</li>
     *   <li>Otherwise try primary</li>
     *   <li>On success → recordSuccess (if CB present)</li>
     *   <li>On exception → recordFailure (if CB present) → fallback</li>
     * </ol>
     *
     * @return result from primary or fallback
     */
    public T execute() {
        // Circuit breaker check
        if (circuitBreaker != null && circuitBreaker.isOpen()) {
            return fallback.get();
        }

        try {
            T result = primary.get();

            // Success: reset circuit breaker
            if (circuitBreaker != null) {
                circuitBreaker.recordSuccess();
            }

            // Success: notify handler (for caching, etc.)
            if (successHandler != null) {
                successHandler.accept(result);
            }

            return result;

        } catch (Exception e) {
            // Failure: record and notify
            if (circuitBreaker != null) {
                circuitBreaker.recordFailure();
            }

            if (errorHandler != null) {
                errorHandler.accept(e);
            }

            return fallback.get();
        }
    }

    /**
     * Executes the operation without returning a result (for void operations).
     * Useful for fire-and-forget operations with fallback.
     *
     * @param primary primary operation
     * @param fallback fallback operation
     * @param circuitBreaker optional circuit breaker (can be null)
     */
    public static void executeVoid(Runnable primary, Runnable fallback, CircuitBreaker circuitBreaker) {
        TryWithFallback.<Void>of(
            () -> { primary.run(); return null; },
            () -> { fallback.run(); return null; }
        ).withCircuitBreaker(circuitBreaker).execute();
    }

    /**
     * Simplified version without circuit breaker.
     *
     * @param primary primary operation
     * @param fallback fallback operation
     */
    public static void executeVoid(Runnable primary, Runnable fallback) {
        executeVoid(primary, fallback, null);
    }
}
