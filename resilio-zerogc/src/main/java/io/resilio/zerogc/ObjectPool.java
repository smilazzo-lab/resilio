package io.resilio.zerogc;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * High-performance object pool for zero-allocation caching.
 *
 * <p>Reuses objects to avoid GC pressure in hot paths. Objects are borrowed
 * from the pool and returned when done. The pool maintains a maximum size
 * and lazily creates objects as needed.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Thread-safe using ConcurrentLinkedQueue</li>
 *   <li>Configurable max pool size</li>
 *   <li>Optional reset function to clean objects before reuse</li>
 *   <li>Statistics for monitoring pool efficiency</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ObjectPool<StringBuilder> pool = ObjectPool.<StringBuilder>builder()
 *     .factory(StringBuilder::new)
 *     .reset(sb -> sb.setLength(0))
 *     .maxSize(100)
 *     .build();
 *
 * // Borrow, use, and return
 * StringBuilder sb = pool.borrow();
 * try {
 *     sb.append("data");
 *     // use sb...
 * } finally {
 *     pool.release(sb);
 * }
 *
 * // Or use with try-with-resources
 * try (var handle = pool.borrowHandle()) {
 *     handle.get().append("data");
 * }
 * }</pre>
 *
 * @param <T> the type of objects in the pool
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class ObjectPool<T> {

    private final ConcurrentLinkedQueue<T> pool = new ConcurrentLinkedQueue<>();
    private final Supplier<T> factory;
    private final Consumer<T> reset;
    private final int maxSize;
    private final String name;

    // Statistics
    private final AtomicInteger created = new AtomicInteger(0);
    private final AtomicInteger borrowed = new AtomicInteger(0);
    private final AtomicInteger returned = new AtomicInteger(0);

    private ObjectPool(Builder<T> builder) {
        this.name = builder.name;
        this.factory = builder.factory;
        this.reset = builder.reset;
        this.maxSize = builder.maxSize;
    }

    /**
     * Creates a new builder for ObjectPool.
     * @param <T> the object type
     * @return a new builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Borrows an object from the pool.
     *
     * <p>If the pool is empty, a new object is created using the factory.
     * The borrowed object should be returned via {@link #release(Object)}.</p>
     *
     * @return an object from the pool or newly created
     */
    public T borrow() {
        borrowed.incrementAndGet();
        T obj = pool.poll();
        if (obj == null) {
            created.incrementAndGet();
            return factory.get();
        }
        return obj;
    }

    /**
     * Returns an object to the pool.
     *
     * <p>If a reset function is configured, it will be called to clean
     * the object before making it available for reuse.</p>
     *
     * @param obj the object to return
     */
    public void release(T obj) {
        if (obj == null) {
            return;
        }

        returned.incrementAndGet();

        // Only keep if pool isn't full
        if (pool.size() < maxSize) {
            if (reset != null) {
                reset.accept(obj);
            }
            pool.offer(obj);
        }
        // Otherwise discard (will be GC'd)
    }

    /**
     * Borrows an object wrapped in an AutoCloseable handle.
     *
     * <p>Use with try-with-resources for automatic release:</p>
     * <pre>{@code
     * try (var handle = pool.borrowHandle()) {
     *     handle.get().doSomething();
     * }
     * }</pre>
     *
     * @return a handle that releases the object when closed
     */
    public PooledHandle<T> borrowHandle() {
        return new PooledHandle<>(this, borrow());
    }

    /**
     * Returns the current number of objects in the pool.
     * @return pool size
     */
    public int size() {
        return pool.size();
    }

    /**
     * Returns the total number of objects created by this pool.
     * @return created count
     */
    public int getCreatedCount() {
        return created.get();
    }

    /**
     * Returns the total number of borrow operations.
     * @return borrow count
     */
    public int getBorrowedCount() {
        return borrowed.get();
    }

    /**
     * Returns the total number of release operations.
     * @return release count
     */
    public int getReturnedCount() {
        return returned.get();
    }

    /**
     * Returns the reuse ratio (returned / borrowed).
     * @return reuse ratio between 0.0 and 1.0
     */
    public double getReuseRatio() {
        int bCount = borrowed.get();
        int cCount = created.get();
        if (bCount == 0) return 0.0;
        return 1.0 - ((double) cCount / bCount);
    }

    /**
     * Returns the pool name.
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * Clears all objects from the pool.
     */
    public void clear() {
        pool.clear();
    }

    /**
     * Pre-warms the pool by creating objects up to the specified count.
     * @param count number of objects to pre-create
     */
    public void prewarm(int count) {
        int toCreate = Math.min(count, maxSize) - pool.size();
        for (int i = 0; i < toCreate; i++) {
            pool.offer(factory.get());
            created.incrementAndGet();
        }
    }

    /**
     * Handle for automatic release of pooled objects.
     */
    public static class PooledHandle<T> implements AutoCloseable {
        private final ObjectPool<T> pool;
        private final T object;
        private boolean released = false;

        PooledHandle(ObjectPool<T> pool, T object) {
            this.pool = pool;
            this.object = object;
        }

        /**
         * Returns the borrowed object.
         * @return the pooled object
         */
        public T get() {
            if (released) {
                throw new IllegalStateException("Object already released");
            }
            return object;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                pool.release(object);
            }
        }
    }

    /**
     * Builder for ObjectPool.
     */
    public static class Builder<T> {
        private String name = "object-pool";
        private Supplier<T> factory;
        private Consumer<T> reset;
        private int maxSize = 100;

        public Builder<T> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<T> factory(Supplier<T> factory) {
            this.factory = factory;
            return this;
        }

        public Builder<T> reset(Consumer<T> reset) {
            this.reset = reset;
            return this;
        }

        public Builder<T> maxSize(int maxSize) {
            if (maxSize <= 0) {
                throw new IllegalArgumentException("Max size must be positive");
            }
            this.maxSize = maxSize;
            return this;
        }

        public ObjectPool<T> build() {
            if (factory == null) {
                throw new IllegalStateException("Factory is required");
            }
            return new ObjectPool<>(this);
        }
    }
}
