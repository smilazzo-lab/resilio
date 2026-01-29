package io.resilio.core.intercept;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Chain of interceptors that wrap an operation.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Define interceptors
 * Interceptor<QueryContext, List<Entity>> securityFilter = new Interceptor<>() {
 *     public QueryContext before(QueryContext ctx) {
 *         return ctx.withWhereClause("uo IN :allowedUo");
 *     }
 * };
 *
 * Interceptor<QueryContext, List<Entity>> auditor = new Interceptor<>() {
 *     public List<Entity> after(QueryContext ctx, List<Entity> result) {
 *         auditLog.record(ctx, result.size());
 *         return result;
 *     }
 * };
 *
 * // Build chain
 * InterceptorChain<QueryContext, List<Entity>> chain = InterceptorChain
 *     .<QueryContext, List<Entity>>builder()
 *     .add(securityFilter)
 *     .add(auditor)
 *     .build();
 *
 * // Execute
 * List<Entity> result = chain.execute(context, ctx -> repository.findAll(ctx.spec()));
 * }</pre>
 *
 * <p>Interceptors are executed in order (by {@link Interceptor#order()}):</p>
 * <ol>
 *   <li>before() - in order (first to last)</li>
 *   <li>operation execution</li>
 *   <li>after() - in reverse order (last to first)</li>
 * </ol>
 *
 * @param <C> context type
 * @param <R> result type
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.1.0
 */
public class InterceptorChain<C, R> {

    private final List<Interceptor<C, R>> interceptors;

    private InterceptorChain(List<Interceptor<C, R>> interceptors) {
        this.interceptors = new ArrayList<>(interceptors);
        this.interceptors.sort(Comparator.comparingInt(Interceptor::order));
    }

    /**
     * Creates a new builder.
     */
    public static <C, R> Builder<C, R> builder() {
        return new Builder<>();
    }

    /**
     * Creates an empty chain (no interceptors).
     */
    public static <C, R> InterceptorChain<C, R> empty() {
        return new InterceptorChain<>(List.of());
    }

    /**
     * Executes the operation through the interceptor chain.
     *
     * @param context initial context
     * @param operation the actual operation to execute
     * @return the result (possibly transformed by interceptors)
     */
    public R execute(C context, Function<C, R> operation) {
        // 1. Run before() in order
        C currentContext = context;
        for (Interceptor<C, R> interceptor : interceptors) {
            currentContext = interceptor.before(currentContext);
        }

        // 2. Execute operation
        R result;
        try {
            result = operation.apply(currentContext);
        } catch (Exception e) {
            // Notify all interceptors of error (in reverse order)
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                interceptors.get(i).onError(currentContext, e);
            }
            throw e;
        }

        // 3. Run after() in reverse order
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            result = interceptors.get(i).after(currentContext, result);
        }

        return result;
    }

    /**
     * Returns the number of interceptors.
     */
    public int size() {
        return interceptors.size();
    }

    /**
     * Builder for InterceptorChain.
     */
    public static class Builder<C, R> {
        private final List<Interceptor<C, R>> interceptors = new ArrayList<>();

        /**
         * Adds an interceptor to the chain.
         */
        public Builder<C, R> add(Interceptor<C, R> interceptor) {
            interceptors.add(interceptor);
            return this;
        }

        /**
         * Adds a before-only interceptor (lambda-friendly).
         */
        public Builder<C, R> before(Function<C, C> beforeFn) {
            interceptors.add(new Interceptor<>() {
                @Override
                public C before(C context) {
                    return beforeFn.apply(context);
                }
            });
            return this;
        }

        /**
         * Adds an after-only interceptor (lambda-friendly).
         */
        public Builder<C, R> after(AfterHandler<C, R> afterFn) {
            interceptors.add(new Interceptor<>() {
                @Override
                public R after(C context, R result) {
                    return afterFn.handle(context, result);
                }
            });
            return this;
        }

        /**
         * Builds the chain.
         */
        public InterceptorChain<C, R> build() {
            return new InterceptorChain<>(interceptors);
        }
    }

    /**
     * Functional interface for after handlers.
     */
    @FunctionalInterface
    public interface AfterHandler<C, R> {
        R handle(C context, R result);
    }
}
