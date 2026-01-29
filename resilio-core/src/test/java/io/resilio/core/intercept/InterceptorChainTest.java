package io.resilio.core.intercept;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for InterceptorChain.
 *
 * Tests cover:
 * - Basic execution flow (before → operation → after)
 * - Order of interceptor execution
 * - Context transformation
 * - Result transformation
 * - Error handling
 * - Lambda helpers
 * - Concurrent access
 *
 * @author Test Engineer
 */
@DisplayName("InterceptorChain")
class InterceptorChainTest {

    // ========================================================================
    // BASIC EXECUTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Basic Execution")
    class BasicExecution {

        @Test
        @DisplayName("should execute operation without interceptors")
        void shouldExecuteWithoutInterceptors() {
            InterceptorChain<String, Integer> chain = InterceptorChain.empty();

            Integer result = chain.execute("input", ctx -> ctx.length());

            assertThat(result).isEqualTo(5); // "input".length()
        }

        @Test
        @DisplayName("should execute operation with single interceptor")
        void shouldExecuteWithSingleInterceptor() {
            List<String> events = new ArrayList<>();

            InterceptorChain<String, Integer> chain = InterceptorChain.<String, Integer>builder()
                .add(new Interceptor<>() {
                    @Override
                    public String before(String context) {
                        events.add("before");
                        return context;
                    }

                    @Override
                    public Integer after(String context, Integer result) {
                        events.add("after");
                        return result;
                    }
                })
                .build();

            Integer result = chain.execute("test", ctx -> {
                events.add("operation");
                return ctx.length();
            });

            assertThat(result).isEqualTo(4);
            assertThat(events).containsExactly("before", "operation", "after");
        }

        @Test
        @DisplayName("should return chain size")
        void shouldReturnChainSize() {
            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<>() {})
                .add(new Interceptor<>() {})
                .add(new Interceptor<>() {})
                .build();

            assertThat(chain.size()).isEqualTo(3);
        }
    }

    // ========================================================================
    // EXECUTION ORDER TESTS
    // ========================================================================

    @Nested
    @DisplayName("Execution Order")
    class ExecutionOrder {

        @Test
        @DisplayName("should execute before() in order and after() in reverse order")
        void shouldExecuteInCorrectOrder() {
            List<String> events = new ArrayList<>();

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(createTracingInterceptor("A", events, 1))
                .add(createTracingInterceptor("B", events, 2))
                .add(createTracingInterceptor("C", events, 3))
                .build();

            chain.execute("ctx", ctx -> {
                events.add("operation");
                return "result";
            });

            // before: 1, 2, 3 (by order)
            // after: 3, 2, 1 (reverse)
            assertThat(events).containsExactly(
                "A:before", "B:before", "C:before",
                "operation",
                "C:after", "B:after", "A:after"
            );
        }

        @Test
        @DisplayName("should sort interceptors by order()")
        void shouldSortByOrder() {
            List<String> events = new ArrayList<>();

            // Add in wrong order
            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(createTracingInterceptor("C", events, 30))
                .add(createTracingInterceptor("A", events, 10))
                .add(createTracingInterceptor("B", events, 20))
                .build();

            chain.execute("ctx", ctx -> {
                events.add("op");
                return "result";
            });

            // Should be sorted: A(10), B(20), C(30)
            assertThat(events).containsExactly(
                "A:before", "B:before", "C:before",
                "op",
                "C:after", "B:after", "A:after"
            );
        }
    }

    // ========================================================================
    // CONTEXT TRANSFORMATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Context Transformation")
    class ContextTransformation {

        @Test
        @DisplayName("should pass transformed context to operation")
        void shouldPassTransformedContext() {
            InterceptorChain<StringBuilder, String> chain = InterceptorChain.<StringBuilder, String>builder()
                .add(new Interceptor<>() {
                    @Override
                    public StringBuilder before(StringBuilder context) {
                        context.append("-modified1");
                        return context;
                    }
                })
                .add(new Interceptor<>() {
                    @Override
                    public StringBuilder before(StringBuilder context) {
                        context.append("-modified2");
                        return context;
                    }
                })
                .build();

            String result = chain.execute(new StringBuilder("original"), ctx -> ctx.toString());

            assertThat(result).isEqualTo("original-modified1-modified2");
        }

        @Test
        @DisplayName("should allow context replacement")
        void shouldAllowContextReplacement() {
            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<>() {
                    @Override
                    public String before(String context) {
                        return "replaced";
                    }
                })
                .build();

            String result = chain.execute("original", ctx -> ctx);

            assertThat(result).isEqualTo("replaced");
        }
    }

    // ========================================================================
    // RESULT TRANSFORMATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Result Transformation")
    class ResultTransformation {

        @Test
        @DisplayName("should transform result in after()")
        void shouldTransformResult() {
            InterceptorChain<String, Integer> chain = InterceptorChain.<String, Integer>builder()
                .add(new Interceptor<>() {
                    @Override
                    public Integer after(String context, Integer result) {
                        return result * 2;
                    }
                })
                .build();

            Integer result = chain.execute("test", ctx -> 5);

            assertThat(result).isEqualTo(10);
        }

        @Test
        @DisplayName("should chain result transformations in reverse order")
        void shouldChainResultTransformations() {
            InterceptorChain<String, Integer> chain = InterceptorChain.<String, Integer>builder()
                .add(new Interceptor<>() {
                    @Override
                    public Integer after(String context, Integer result) {
                        return result + 1; // Called second (reverse order)
                    }

                    @Override
                    public int order() { return 1; }
                })
                .add(new Interceptor<>() {
                    @Override
                    public Integer after(String context, Integer result) {
                        return result * 10; // Called first (reverse order)
                    }

                    @Override
                    public int order() { return 2; }
                })
                .build();

            // Operation returns 5
            // Second interceptor (order=2): 5 * 10 = 50
            // First interceptor (order=1): 50 + 1 = 51
            Integer result = chain.execute("ctx", ctx -> 5);

            assertThat(result).isEqualTo(51);
        }
    }

    // ========================================================================
    // ERROR HANDLING TESTS
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("should notify all interceptors on error in reverse order")
        void shouldNotifyOnError() {
            List<String> errors = new ArrayList<>();

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<>() {
                    @Override
                    public void onError(String context, Exception error) {
                        errors.add("A:" + error.getMessage());
                    }
                    @Override
                    public int order() { return 1; }
                })
                .add(new Interceptor<>() {
                    @Override
                    public void onError(String context, Exception error) {
                        errors.add("B:" + error.getMessage());
                    }
                    @Override
                    public int order() { return 2; }
                })
                .build();

            assertThatThrownBy(() ->
                chain.execute("ctx", ctx -> {
                    throw new RuntimeException("test-error");
                })
            ).isInstanceOf(RuntimeException.class);

            // Reverse order: B, then A
            assertThat(errors).containsExactly("B:test-error", "A:test-error");
        }

        @Test
        @DisplayName("should propagate exception after notifying interceptors")
        void shouldPropagateException() {
            RuntimeException testException = new RuntimeException("Test");

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<>() {})
                .build();

            assertThatThrownBy(() ->
                chain.execute("ctx", ctx -> { throw testException; })
            ).isSameAs(testException);
        }

        @Test
        @DisplayName("should not call after() when operation throws")
        void shouldNotCallAfterOnError() {
            AtomicInteger afterCalled = new AtomicInteger(0);

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(new Interceptor<>() {
                    @Override
                    public String after(String context, String result) {
                        afterCalled.incrementAndGet();
                        return result;
                    }
                })
                .build();

            assertThatThrownBy(() ->
                chain.execute("ctx", ctx -> { throw new RuntimeException(); })
            );

            assertThat(afterCalled.get()).isZero();
        }
    }

    // ========================================================================
    // LAMBDA HELPER TESTS
    // ========================================================================

    @Nested
    @DisplayName("Lambda Helpers")
    class LambdaHelpers {

        @Test
        @DisplayName("should support before() lambda")
        void shouldSupportBeforeLambda() {
            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .before(ctx -> ctx.toUpperCase())
                .build();

            String result = chain.execute("hello", ctx -> ctx);

            assertThat(result).isEqualTo("HELLO");
        }

        @Test
        @DisplayName("should support after() lambda")
        void shouldSupportAfterLambda() {
            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .after((ctx, result) -> result + "!")
                .build();

            String result = chain.execute("ctx", ctx -> "hello");

            assertThat(result).isEqualTo("hello!");
        }

        @Test
        @DisplayName("should support mixed lambdas and interceptors")
        void shouldSupportMixedLambdasAndInterceptors() {
            List<String> events = new ArrayList<>();

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .before(ctx -> {
                    events.add("lambda-before");
                    return ctx;
                })
                .add(new Interceptor<>() {
                    @Override
                    public String before(String context) {
                        events.add("interceptor-before");
                        return context;
                    }

                    @Override
                    public String after(String context, String result) {
                        events.add("interceptor-after");
                        return result;
                    }
                })
                .after((ctx, result) -> {
                    events.add("lambda-after");
                    return result;
                })
                .build();

            chain.execute("ctx", ctx -> {
                events.add("operation");
                return "result";
            });

            assertThat(events).contains("lambda-before", "interceptor-before",
                "operation", "interceptor-after", "lambda-after");
        }
    }

    // ========================================================================
    // CONCURRENT EXECUTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Concurrent Execution")
    class ConcurrentExecution {

        @Test
        @DisplayName("should handle concurrent executions")
        void shouldHandleConcurrentExecutions() throws InterruptedException {
            AtomicInteger counter = new AtomicInteger(0);

            InterceptorChain<Integer, Integer> chain = InterceptorChain.<Integer, Integer>builder()
                .add(new Interceptor<>() {
                    @Override
                    public Integer before(Integer context) {
                        return context + 1;
                    }

                    @Override
                    public Integer after(Integer context, Integer result) {
                        counter.incrementAndGet();
                        return result;
                    }
                })
                .build();

            int threadCount = 100;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(10);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        chain.execute(index, ctx -> ctx * 2);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(counter.get()).isEqualTo(threadCount);
        }
    }

    // ========================================================================
    // REAL-WORLD SCENARIO TESTS
    // ========================================================================

    @Nested
    @DisplayName("Real-World Scenarios")
    class RealWorldScenarios {

        @Test
        @DisplayName("should simulate security + audit chain")
        void shouldSimulateSecurityAuditChain() {
            List<String> auditLog = new ArrayList<>();

            // Security interceptor: adds WHERE clause
            Interceptor<QueryContext, List<String>> securityInterceptor = new Interceptor<>() {
                @Override
                public QueryContext before(QueryContext context) {
                    return new QueryContext(
                        context.query + " WHERE org_unit = 'allowed'",
                        context.user
                    );
                }

                @Override
                public int order() { return 10; }
            };

            // Audit interceptor: logs access
            Interceptor<QueryContext, List<String>> auditInterceptor = new Interceptor<>() {
                @Override
                public List<String> after(QueryContext context, List<String> result) {
                    auditLog.add("User " + context.user + " queried " + result.size() + " records");
                    return result;
                }

                @Override
                public int order() { return 20; }
            };

            InterceptorChain<QueryContext, List<String>> chain = InterceptorChain.<QueryContext, List<String>>builder()
                .add(securityInterceptor)
                .add(auditInterceptor)
                .build();

            // Execute
            List<String> results = chain.execute(
                new QueryContext("SELECT * FROM data", "john"),
                ctx -> {
                    // Simulate filtered query
                    assertThat(ctx.query).contains("WHERE org_unit = 'allowed'");
                    return List.of("row1", "row2", "row3");
                }
            );

            assertThat(results).hasSize(3);
            assertThat(auditLog).containsExactly("User john queried 3 records");
        }

        @Test
        @DisplayName("should simulate cache interceptor")
        void shouldSimulateCacheInterceptor() {
            // Simple in-memory cache
            java.util.Map<String, String> cache = new java.util.HashMap<>();
            AtomicInteger dbCalls = new AtomicInteger(0);

            Interceptor<String, String> cacheInterceptor = new Interceptor<>() {
                @Override
                public String before(String key) {
                    // Check cache
                    if (cache.containsKey(key)) {
                        // Return from cache - we'll need to skip operation
                        // This is simplified - real impl would use context object
                    }
                    return key;
                }

                @Override
                public String after(String key, String result) {
                    // Populate cache
                    cache.put(key, result);
                    return result;
                }
            };

            InterceptorChain<String, String> chain = InterceptorChain.<String, String>builder()
                .add(cacheInterceptor)
                .build();

            // First call - should hit DB
            String result1 = chain.execute("key1", key -> {
                dbCalls.incrementAndGet();
                return "value-for-" + key;
            });

            assertThat(result1).isEqualTo("value-for-key1");
            assertThat(dbCalls.get()).isEqualTo(1);
            assertThat(cache).containsEntry("key1", "value-for-key1");
        }
    }

    // ========================================================================
    // HELPER CLASSES AND METHODS
    // ========================================================================

    private Interceptor<String, String> createTracingInterceptor(String name, List<String> events, int order) {
        return new Interceptor<>() {
            @Override
            public String before(String context) {
                events.add(name + ":before");
                return context;
            }

            @Override
            public String after(String context, String result) {
                events.add(name + ":after");
                return result;
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    /**
     * Simple query context for testing.
     */
    static class QueryContext {
        final String query;
        final String user;

        QueryContext(String query, String user) {
            this.query = query;
            this.user = user;
        }
    }
}
