package io.resilio.core.intercept;

/**
 * Generic interceptor for operations.
 *
 * <p>Interceptors can:</p>
 * <ul>
 *   <li>Transform context before execution</li>
 *   <li>Transform result after execution</li>
 *   <li>Handle errors with fallback</li>
 * </ul>
 *
 * @param <C> context type (carries input + metadata)
 * @param <R> result type
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.1.0
 */
public interface Interceptor<C, R> {

    /**
     * Called before operation execution.
     * Can modify the context (e.g., add WHERE clause to query).
     *
     * @param context the operation context
     * @return modified context (or same if no changes)
     */
    default C before(C context) {
        return context;
    }

    /**
     * Called after successful operation execution.
     * Can modify the result (e.g., filter rows).
     *
     * @param context the operation context
     * @param result the operation result
     * @return modified result (or same if no changes)
     */
    default R after(C context, R result) {
        return result;
    }

    /**
     * Called when operation throws an exception.
     *
     * @param context the operation context
     * @param error the exception
     */
    default void onError(C context, Exception error) {
        // Default: do nothing, let exception propagate
    }

    /**
     * Order of this interceptor (lower = earlier).
     * Default is 0.
     */
    default int order() {
        return 0;
    }
}
