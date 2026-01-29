package io.resilio.core.buffer;

import io.resilio.core.circuit.CircuitBreaker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Generic asynchronous buffer with batching and circuit breaker.
 *
 * <p>Provides high-throughput event buffering with:</p>
 * <ul>
 *   <li>Bounded BlockingQueue for backpressure</li>
 *   <li>Asynchronous batch sending (flush every N items or T milliseconds)</li>
 *   <li>Circuit breaker for protection against destination failures</li>
 *   <li>Configurable fallback handler for rejected/failed items</li>
 *   <li>Graceful shutdown with queue drain</li>
 *   <li>Metrics for monitoring</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AsyncBuffer<MyEvent> buffer = AsyncBuffer.<MyEvent>builder()
 *     .name("events")
 *     .queueCapacity(10_000)
 *     .batchSize(100)
 *     .flushInterval(Duration.ofSeconds(5))
 *     .batchSender(batch -> httpClient.post("/events", batch))
 *     .fallbackHandler(event -> log.warn("Dropped: {}", event))
 *     .build();
 *
 * // Enqueue events (non-blocking)
 * buffer.enqueue(new MyEvent(...));
 *
 * // Shutdown with drain
 * buffer.close();
 * }</pre>
 *
 * @param <E> the element type
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.1.0
 */
public class AsyncBuffer<E> implements AutoCloseable {

    // Configuration
    private final String name;
    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final long shutdownTimeoutMs;

    // Components
    private final BlockingQueue<E> queue;
    private final Consumer<List<E>> batchSender;
    private final Consumer<E> fallbackHandler;
    private final Thread senderThread;
    private final CircuitBreaker circuitBreaker;

    // State
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Metrics
    private final AtomicLong itemsEnqueued = new AtomicLong(0);
    private final AtomicLong itemsDropped = new AtomicLong(0);
    private final AtomicLong itemsSent = new AtomicLong(0);
    private final AtomicLong batchesSent = new AtomicLong(0);
    private final AtomicLong fallbackItems = new AtomicLong(0);

    private AsyncBuffer(Builder<E> builder) {
        this.name = builder.name;
        this.queueCapacity = builder.queueCapacity;
        this.batchSize = builder.batchSize;
        this.flushIntervalMs = builder.flushIntervalMs;
        this.shutdownTimeoutMs = builder.shutdownTimeoutMs;
        this.batchSender = builder.batchSender;
        this.fallbackHandler = builder.fallbackHandler;

        this.circuitBreaker = CircuitBreaker.builder()
            .name(name + "-circuit")
            .failureThreshold(builder.circuitBreakerThreshold)
            .resetTimeout(Duration.ofMillis(builder.circuitBreakerResetMs))
            .build();

        this.queue = new ArrayBlockingQueue<>(queueCapacity);

        // Sender thread (non-daemon to ensure flush on shutdown)
        this.senderThread = new Thread(this::senderLoop, "resilio-buffer-" + name);
        this.senderThread.setDaemon(false);
        this.senderThread.start();
    }

    /**
     * Creates a new builder.
     * @param <E> element type
     * @return new builder
     */
    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    /**
     * Enqueues an item for asynchronous sending.
     * Non-blocking - if queue is full, drops oldest item.
     *
     * @param item item to enqueue
     * @return true if enqueued, false if dropped to fallback
     */
    public boolean enqueue(E item) {
        if (item == null) return false;

        if (!running.get()) {
            // Buffer closed - direct fallback
            handleFallback(item);
            return false;
        }

        // Try non-blocking insert
        if (queue.offer(item)) {
            itemsEnqueued.incrementAndGet();
            return true;
        }

        // Queue full - backpressure: drop oldest
        E dropped = queue.poll();
        if (dropped != null) {
            itemsDropped.incrementAndGet();
            handleFallback(dropped);
        }

        // Retry insert
        if (queue.offer(item)) {
            itemsEnqueued.incrementAndGet();
            return true;
        }

        // Still full - current item goes to fallback
        itemsDropped.incrementAndGet();
        handleFallback(item);
        return false;
    }

    /**
     * Forces immediate flush (useful for testing).
     */
    public void flush() {
        senderThread.interrupt();
    }

    /**
     * Closes the buffer with graceful shutdown.
     * Waits for pending items to be sent.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return; // Already closed
        }

        // Interrupt wait and force final flush
        senderThread.interrupt();

        try {
            senderThread.join(shutdownTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Remaining items go to fallback
        drainToFallback();
    }

    // ========================================================================
    // SENDER LOOP
    // ========================================================================

    private void senderLoop() {
        List<E> batch = new ArrayList<>(batchSize);

        while (running.get() || !queue.isEmpty()) {
            try {
                // Wait for first item or timeout
                E item = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);

                if (item != null) {
                    batch.add(item);
                    // Drain up to batchSize
                    queue.drainTo(batch, batchSize - 1);
                }

                // Send if batch not empty
                if (!batch.isEmpty()) {
                    sendBatch(batch);
                    batch.clear();
                }

            } catch (InterruptedException e) {
                // Forced flush or shutdown
                if (!batch.isEmpty()) {
                    sendBatch(batch);
                    batch.clear();
                }
                if (!running.get()) {
                    break;
                }
            }
        }

        // Final flush
        if (!batch.isEmpty()) {
            sendBatch(batch);
        }
    }

    private void sendBatch(List<E> batch) {
        // Circuit breaker check
        if (circuitBreaker.isOpen()) {
            sendToFallback(batch);
            return;
        }

        try {
            batchSender.accept(new ArrayList<>(batch)); // defensive copy
            itemsSent.addAndGet(batch.size());
            batchesSent.incrementAndGet();
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            sendToFallback(batch);
        }
    }

    private void sendToFallback(List<E> batch) {
        batch.forEach(this::handleFallback);
    }

    private void handleFallback(E item) {
        if (fallbackHandler != null) {
            try {
                fallbackHandler.accept(item);
            } catch (Exception ignored) {
                // Fallback should never throw
            }
            fallbackItems.incrementAndGet();
        }
    }

    private void drainToFallback() {
        E item;
        while ((item = queue.poll()) != null) {
            handleFallback(item);
        }
    }

    // ========================================================================
    // METRICS
    // ========================================================================

    public String getName() { return name; }
    public long getItemsEnqueued() { return itemsEnqueued.get(); }
    public long getItemsDropped() { return itemsDropped.get(); }
    public long getItemsSent() { return itemsSent.get(); }
    public long getBatchesSent() { return batchesSent.get(); }
    public long getFallbackItems() { return fallbackItems.get(); }
    public int getQueueSize() { return queue.size(); }
    public boolean isCircuitBreakerOpen() { return circuitBreaker.isOpen(); }
    public boolean isRunning() { return running.get(); }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }

    /**
     * Snapshot of metrics for monitoring.
     */
    public Metrics getMetrics() {
        return new Metrics(
            itemsEnqueued.get(),
            itemsDropped.get(),
            itemsSent.get(),
            batchesSent.get(),
            fallbackItems.get(),
            queue.size(),
            circuitBreaker.isOpen()
        );
    }

    public record Metrics(
        long itemsEnqueued,
        long itemsDropped,
        long itemsSent,
        long batchesSent,
        long fallbackItems,
        int queueSize,
        boolean circuitOpen
    ) {}

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder<E> {
        private String name = "async-buffer";
        private int queueCapacity = 10_000;
        private int batchSize = 100;
        private long flushIntervalMs = 5_000;
        private int circuitBreakerThreshold = 5;
        private long circuitBreakerResetMs = 30_000;
        private long shutdownTimeoutMs = 10_000;
        private Consumer<List<E>> batchSender;
        private Consumer<E> fallbackHandler;

        public Builder<E> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<E> queueCapacity(int capacity) {
            this.queueCapacity = capacity;
            return this;
        }

        public Builder<E> batchSize(int size) {
            this.batchSize = size;
            return this;
        }

        public Builder<E> flushInterval(Duration interval) {
            this.flushIntervalMs = interval.toMillis();
            return this;
        }

        public Builder<E> flushIntervalMs(long ms) {
            this.flushIntervalMs = ms;
            return this;
        }

        public Builder<E> circuitBreakerThreshold(int threshold) {
            this.circuitBreakerThreshold = threshold;
            return this;
        }

        public Builder<E> circuitBreakerResetMs(long ms) {
            this.circuitBreakerResetMs = ms;
            return this;
        }

        public Builder<E> circuitBreakerReset(Duration duration) {
            this.circuitBreakerResetMs = duration.toMillis();
            return this;
        }

        public Builder<E> shutdownTimeoutMs(long ms) {
            this.shutdownTimeoutMs = ms;
            return this;
        }

        public Builder<E> shutdownTimeout(Duration timeout) {
            this.shutdownTimeoutMs = timeout.toMillis();
            return this;
        }

        /**
         * Sets the batch sender - the destination where batches are sent.
         * This is where you configure WHERE items go (HTTP, Kafka, file, etc.)
         */
        public Builder<E> batchSender(Consumer<List<E>> sender) {
            this.batchSender = sender;
            return this;
        }

        /**
         * Sets the fallback handler for dropped/failed items.
         */
        public Builder<E> fallbackHandler(Consumer<E> handler) {
            this.fallbackHandler = handler;
            return this;
        }

        public AsyncBuffer<E> build() {
            Objects.requireNonNull(batchSender, "batchSender is required");
            return new AsyncBuffer<>(this);
        }
    }
}
