package io.resilio.aggregator;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * High-volume event aggregator for reducing write amplification.
 *
 * <p>Aggregates events by a key (e.g., session ID) and periodically flushes
 * summaries instead of individual events. This can reduce write volume by
 * 99%+ in high-throughput scenarios.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Configurable flush interval and max events threshold</li>
 *   <li>Automatic eviction of idle or oversized buckets</li>
 *   <li>Thread-safe using ConcurrentHashMap</li>
 *   <li>Graceful shutdown with final flush</li>
 *   <li>Statistics for monitoring aggregation efficiency</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * EventAggregator<AuditEvent, AuditSummary> aggregator = EventAggregator
 *     .<AuditEvent, AuditSummary>builder()
 *     .keyExtractor(AuditEvent::getSessionId)
 *     .summaryFactory(AuditSummary::new)
 *     .accumulator((summary, event) -> summary.addEvent(event))
 *     .flushInterval(Duration.ofMinutes(5))
 *     .maxEventsBeforeFlush(1000)
 *     .onFlush(summary -> auditService.save(summary))
 *     .build();
 *
 * // Record events (aggregated automatically)
 * aggregator.record(new AuditEvent(sessionId, "LOGIN", user));
 * aggregator.record(new AuditEvent(sessionId, "ACCESS", resource));
 *
 * // Shutdown flushes remaining events
 * aggregator.shutdown();
 * }</pre>
 *
 * @param <E> the event type
 * @param <S> the summary type
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class EventAggregator<E, S> {

    private static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 300;
    private static final int DEFAULT_MAX_EVENTS_BEFORE_FLUSH = 1000;
    private static final int DEFAULT_MAX_BUCKETS = 2000;
    private static final int DEFAULT_EVICTION_BATCH_SIZE = 500;

    private final ConcurrentHashMap<String, Bucket<S>> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Function<E, String> keyExtractor;
    private final java.util.function.Supplier<S> summaryFactory;
    private final java.util.function.BiConsumer<S, E> accumulator;
    private final Consumer<S> flushCallback;
    private final int flushIntervalSeconds;
    private final int maxEventsBeforeFlush;
    private final int maxBuckets;
    private final String name;

    // Statistics
    private final LongAdder eventsReceived = new LongAdder();
    private final LongAdder summariesFlushed = new LongAdder();
    private final LongAdder evictionCount = new LongAdder();

    /**
     * Internal bucket for accumulating events.
     */
    private static class Bucket<S> {
        final S summary;
        final Instant createdAt;
        volatile Instant lastAccess;
        final AtomicInteger eventCount = new AtomicInteger(0);
        volatile boolean flushed = false;

        Bucket(S summary) {
            this.summary = summary;
            this.createdAt = Instant.now();
            this.lastAccess = createdAt;
        }

        int addEvent() {
            lastAccess = Instant.now();
            return eventCount.incrementAndGet();
        }

        boolean markFlushed() {
            if (flushed) return false;
            flushed = true;
            return true;
        }
    }

    private EventAggregator(Builder<E, S> builder) {
        this.name = builder.name;
        this.keyExtractor = builder.keyExtractor;
        this.summaryFactory = builder.summaryFactory;
        this.accumulator = builder.accumulator;
        this.flushCallback = builder.flushCallback;
        this.flushIntervalSeconds = builder.flushIntervalSeconds;
        this.maxEventsBeforeFlush = builder.maxEventsBeforeFlush;
        this.maxBuckets = builder.maxBuckets;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resilio-aggregator-" + name);
            t.setDaemon(true);
            return t;
        });

        // Schedule periodic flush of idle buckets
        scheduler.scheduleAtFixedRate(
                this::flushIdleBuckets,
                flushIntervalSeconds,
                flushIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Creates a new builder for EventAggregator.
     * @param <E> event type
     * @param <S> summary type
     * @return a new builder instance
     */
    public static <E, S> Builder<E, S> builder() {
        return new Builder<>();
    }

    /**
     * Records an event for aggregation.
     *
     * @param event the event to record
     */
    public void record(E event) {
        if (event == null) return;

        eventsReceived.increment();
        String key = keyExtractor.apply(event);
        if (key == null) return;

        Bucket<S> bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.flushed) {
                return new Bucket<>(summaryFactory.get());
            }
            return existing;
        });

        // Accumulate event into summary
        accumulator.accept(bucket.summary, event);
        int count = bucket.addEvent();

        // Flush if max events reached
        if (count >= maxEventsBeforeFlush) {
            flushBucket(key);
        }

        // Evict if too many buckets
        evictIfNeeded();
    }

    /**
     * Flushes a specific bucket by key.
     *
     * @param key the bucket key
     */
    public void flushBucket(String key) {
        Bucket<S> bucket = buckets.get(key);
        if (bucket == null || !bucket.markFlushed()) {
            return;
        }

        buckets.remove(key, bucket);

        if (bucket.eventCount.get() > 0) {
            try {
                flushCallback.accept(bucket.summary);
                summariesFlushed.increment();
            } catch (Exception e) {
                // Log and continue - don't fail the aggregator
            }
        }
    }

    /**
     * Flushes all idle buckets (last access > flush interval).
     */
    private void flushIdleBuckets() {
        Instant threshold = Instant.now().minusSeconds(flushIntervalSeconds);
        List<String> toFlush = new ArrayList<>();

        buckets.forEach((key, bucket) -> {
            if (bucket.lastAccess.isBefore(threshold)) {
                toFlush.add(key);
            }
        });

        toFlush.forEach(this::flushBucket);
    }

    /**
     * Evicts oldest buckets if max buckets exceeded.
     */
    private void evictIfNeeded() {
        if (buckets.size() <= maxBuckets) {
            return;
        }

        // Find and flush oldest buckets
        buckets.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().lastAccess))
                .limit(DEFAULT_EVICTION_BATCH_SIZE)
                .map(Map.Entry::getKey)
                .forEach(key -> {
                    flushBucket(key);
                    evictionCount.increment();
                });
    }

    /**
     * Flushes all buckets.
     */
    public void flushAll() {
        List<String> allKeys = new ArrayList<>(buckets.keySet());
        allKeys.forEach(this::flushBucket);
    }

    /**
     * Shuts down the aggregator, flushing all remaining buckets.
     */
    public void shutdown() {
        flushAll();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the number of active buckets.
     * @return bucket count
     */
    public int getActiveBucketCount() {
        return buckets.size();
    }

    /**
     * Returns the total events received.
     * @return events received
     */
    public long getEventsReceived() {
        return eventsReceived.sum();
    }

    /**
     * Returns the total summaries flushed.
     * @return summaries flushed
     */
    public long getSummariesFlushed() {
        return summariesFlushed.sum();
    }

    /**
     * Returns the aggregation ratio (events received / summaries flushed).
     * @return aggregation ratio
     */
    public double getAggregationRatio() {
        long flushed = summariesFlushed.sum();
        if (flushed == 0) return 0.0;
        return (double) eventsReceived.sum() / flushed;
    }

    /**
     * Returns the number of evictions performed.
     * @return eviction count
     */
    public long getEvictionCount() {
        return evictionCount.sum();
    }

    /**
     * Returns the aggregator name.
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * Builder for EventAggregator.
     */
    public static class Builder<E, S> {
        private String name = "event-aggregator";
        private Function<E, String> keyExtractor;
        private java.util.function.Supplier<S> summaryFactory;
        private java.util.function.BiConsumer<S, E> accumulator;
        private Consumer<S> flushCallback;
        private int flushIntervalSeconds = DEFAULT_FLUSH_INTERVAL_SECONDS;
        private int maxEventsBeforeFlush = DEFAULT_MAX_EVENTS_BEFORE_FLUSH;
        private int maxBuckets = DEFAULT_MAX_BUCKETS;

        public Builder<E, S> name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the function to extract the aggregation key from events.
         */
        public Builder<E, S> keyExtractor(Function<E, String> keyExtractor) {
            this.keyExtractor = keyExtractor;
            return this;
        }

        /**
         * Sets the factory for creating new summary objects.
         */
        public Builder<E, S> summaryFactory(java.util.function.Supplier<S> summaryFactory) {
            this.summaryFactory = summaryFactory;
            return this;
        }

        /**
         * Sets the function to accumulate events into summaries.
         */
        public Builder<E, S> accumulator(java.util.function.BiConsumer<S, E> accumulator) {
            this.accumulator = accumulator;
            return this;
        }

        /**
         * Sets the callback for flushing summaries.
         */
        public Builder<E, S> onFlush(Consumer<S> flushCallback) {
            this.flushCallback = flushCallback;
            return this;
        }

        /**
         * Sets the flush interval for idle buckets.
         */
        public Builder<E, S> flushInterval(Duration interval) {
            this.flushIntervalSeconds = (int) interval.toSeconds();
            return this;
        }

        /**
         * Sets the maximum events before automatic flush.
         */
        public Builder<E, S> maxEventsBeforeFlush(int max) {
            this.maxEventsBeforeFlush = max;
            return this;
        }

        /**
         * Sets the maximum number of active buckets.
         */
        public Builder<E, S> maxBuckets(int max) {
            this.maxBuckets = max;
            return this;
        }

        public EventAggregator<E, S> build() {
            Objects.requireNonNull(keyExtractor, "keyExtractor is required");
            Objects.requireNonNull(summaryFactory, "summaryFactory is required");
            Objects.requireNonNull(accumulator, "accumulator is required");
            Objects.requireNonNull(flushCallback, "flushCallback (onFlush) is required");
            return new EventAggregator<>(this);
        }
    }
}
