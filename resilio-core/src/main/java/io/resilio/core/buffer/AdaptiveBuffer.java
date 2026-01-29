package io.resilio.core.buffer;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Buffer adattivo con strategia di flush intelligente.
 *
 * <h2>RESILIO AdaptiveBuffer</h2>
 * <p>A differenza di {@link AsyncBuffer} che usa intervalli fissi, AdaptiveBuffer
 * adatta dinamicamente il flush in base a:</p>
 *
 * <h3>1. CAPACITY-BASED (soglie di riempimento)</h3>
 * <pre>
 *   0-30% (LOW)      → accumula, flush solo per età
 *   30-70% (NORMAL)  → flush batch completi
 *   70-90% (HIGH)    → flush aggressivo (batch parziali)
 *   90%+ (CRITICAL)  → flush immediato
 * </pre>
 *
 * <h3>2. TRAFFIC-AWARE (rate limiting)</h3>
 * <ul>
 *   <li>Alto throughput in ingresso → riduce rate di flush</li>
 *   <li>Basso throughput → flush più frequente</li>
 *   <li>Idle detection → flush tutto dopo N secondi di inattività</li>
 * </ul>
 *
 * <h3>3. AGE-BASED (priorità)</h3>
 * <ul>
 *   <li>Items più vecchi di maxAge → flush prioritario</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AdaptiveBuffer<SessionSummary> buffer = AdaptiveBuffer.<SessionSummary>builder()
 *     .name("session-buffer")
 *     .maxCapacity(1000)
 *     .lowWatermark(0.3)    // 30%
 *     .highWatermark(0.7)   // 70%
 *     .criticalWatermark(0.9) // 90%
 *     .maxItemAge(Duration.ofMinutes(2))
 *     .idleTimeout(Duration.ofSeconds(30))
 *     .batchSender(batch -> apiClient.sendBatch(batch))
 *     .keyExtractor(SessionSummary::sessionId)
 *     .build();
 *
 * // Add items - flush is automatic based on strategy
 * buffer.put("session-123", summary);
 *
 * // Shutdown with final flush
 * buffer.close();
 * }</pre>
 *
 * @param <K> key type
 * @param <V> value type
 *
 * @author Salvatore Milazzo &lt;milazzosa@gmail.com&gt;
 * @since 1.2.0
 */
public class AdaptiveBuffer<K, V> implements AutoCloseable {

    // ========================================================================
    // CONFIGURATION
    // ========================================================================

    private final String name;
    private final int maxCapacity;
    private final double lowWatermark;      // 0.3 = 30%
    private final double highWatermark;     // 0.7 = 70%
    private final double criticalWatermark; // 0.9 = 90%
    private final long maxItemAgeMs;
    private final long idleTimeoutMs;
    private final long minFlushIntervalMs;
    private final int batchSize;

    // ========================================================================
    // COMPONENTS
    // ========================================================================

    private final ConcurrentHashMap<K, TimestampedValue<V>> buffer = new ConcurrentHashMap<>();
    private final Consumer<List<V>> batchSender;
    private final Consumer<V> fallbackHandler;
    private final ScheduledExecutorService scheduler;

    // ========================================================================
    // STATE
    // ========================================================================

    private volatile boolean running = true;
    private volatile Instant lastActivity = Instant.now();
    private volatile Instant lastFlush = Instant.now();

    // Traffic monitoring (sliding window)
    private final LongAdder inputRate = new LongAdder();
    private final AtomicLong lastRateReset = new AtomicLong(System.currentTimeMillis());
    private volatile double currentInputRate = 0.0; // items/sec

    // ========================================================================
    // METRICS
    // ========================================================================

    private final LongAdder itemsAdded = new LongAdder();
    private final LongAdder itemsFlushed = new LongAdder();
    private final LongAdder itemsDropped = new LongAdder();
    private final LongAdder flushCount = new LongAdder();
    private final LongAdder adaptiveDelays = new LongAdder();

    /**
     * Wrapper per tracciare l'età degli items.
     */
    private record TimestampedValue<V>(V value, Instant timestamp) {}

    /**
     * Stato corrente del buffer per decisioni di flush.
     */
    public enum BufferState {
        LOW,      // < lowWatermark: accumula
        NORMAL,   // lowWatermark - highWatermark: flush normale
        HIGH,     // highWatermark - critical: flush aggressivo
        CRITICAL  // > critical: flush immediato
    }

    // ========================================================================
    // CONSTRUCTOR
    // ========================================================================

    private AdaptiveBuffer(Builder<K, V> builder) {
        this.name = builder.name;
        this.maxCapacity = builder.maxCapacity;
        this.lowWatermark = builder.lowWatermark;
        this.highWatermark = builder.highWatermark;
        this.criticalWatermark = builder.criticalWatermark;
        this.maxItemAgeMs = builder.maxItemAgeMs;
        this.idleTimeoutMs = builder.idleTimeoutMs;
        this.minFlushIntervalMs = builder.minFlushIntervalMs;
        this.batchSize = builder.batchSize;
        this.batchSender = builder.batchSender;
        this.fallbackHandler = builder.fallbackHandler;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resilio-adaptive-" + name);
            t.setDaemon(true);
            return t;
        });

        // Scheduler per controllo periodico
        scheduler.scheduleAtFixedRate(this::adaptiveFlushCheck, 100, 100, TimeUnit.MILLISECONDS);

        // Rate calculator (ogni secondo)
        scheduler.scheduleAtFixedRate(this::calculateRate, 1, 1, TimeUnit.SECONDS);
    }

    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    /**
     * Aggiunge o aggiorna un item nel buffer.
     *
     * @param key chiave univoca
     * @param value valore
     */
    public void put(K key, V value) {
        if (!running) {
            handleFallback(value);
            return;
        }

        lastActivity = Instant.now();
        inputRate.increment();
        itemsAdded.increment();

        // Inserisci con timestamp
        buffer.put(key, new TimestampedValue<>(value, Instant.now()));

        // Check flush immediato se critico
        if (getState() == BufferState.CRITICAL) {
            triggerFlush(FlushReason.CRITICAL_CAPACITY);
        }
    }

    /**
     * Ottiene un item dal buffer senza rimuoverlo.
     */
    public Optional<V> get(K key) {
        TimestampedValue<V> tv = buffer.get(key);
        return tv != null ? Optional.of(tv.value()) : Optional.empty();
    }

    /**
     * Rimuove un item dal buffer.
     */
    public Optional<V> remove(K key) {
        TimestampedValue<V> tv = buffer.remove(key);
        return tv != null ? Optional.of(tv.value()) : Optional.empty();
    }

    /**
     * Forza un flush immediato.
     */
    public void flush() {
        triggerFlush(FlushReason.MANUAL);
    }

    /**
     * Chiude il buffer con flush finale.
     */
    @Override
    public void close() {
        if (!running) return;
        running = false;

        // Flush finale
        triggerFlush(FlushReason.SHUTDOWN);

        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Fallback per items rimanenti
        buffer.values().forEach(tv -> handleFallback(tv.value()));
        buffer.clear();
    }

    // ========================================================================
    // ADAPTIVE FLUSH LOGIC
    // ========================================================================

    /**
     * Check periodico per decidere se/quando fare flush.
     */
    private void adaptiveFlushCheck() {
        if (!running || buffer.isEmpty()) return;

        BufferState state = getState();
        Instant now = Instant.now();

        // 1. Check idle timeout
        long idleMs = Duration.between(lastActivity, now).toMillis();
        if (idleMs >= idleTimeoutMs && !buffer.isEmpty()) {
            triggerFlush(FlushReason.IDLE_TIMEOUT);
            return;
        }

        // 2. Check items troppo vecchi
        boolean hasOldItems = buffer.values().stream()
            .anyMatch(tv -> Duration.between(tv.timestamp(), now).toMillis() >= maxItemAgeMs);
        if (hasOldItems) {
            triggerFlush(FlushReason.MAX_AGE_EXCEEDED);
            return;
        }

        // 3. Strategia basata su stato
        switch (state) {
            case LOW:
                // Accumula - niente flush proattivo
                break;

            case NORMAL:
                // Flush se passato minFlushInterval
                long sinceLast = Duration.between(lastFlush, now).toMillis();
                if (sinceLast >= minFlushIntervalMs) {
                    triggerFlush(FlushReason.NORMAL_INTERVAL);
                }
                break;

            case HIGH:
                // Flush aggressivo - batch parziali OK
                // Ma riduci rate se c'è alto throughput in ingresso
                if (shouldDelayDueToTraffic()) {
                    adaptiveDelays.increment();
                } else {
                    triggerFlush(FlushReason.HIGH_CAPACITY);
                }
                break;

            case CRITICAL:
                // Flush immediato (già gestito in put())
                triggerFlush(FlushReason.CRITICAL_CAPACITY);
                break;
        }
    }

    /**
     * Determina se ritardare il flush a causa di alto traffico in ingresso.
     */
    private boolean shouldDelayDueToTraffic() {
        // Se il rate di input è > 2x il rate di output medio, ritarda
        // Questo evita di sovraccaricare il downstream durante i picchi
        double avgOutputRate = flushCount.sum() > 0
            ? (double) itemsFlushed.sum() / flushCount.sum()
            : batchSize;

        return currentInputRate > avgOutputRate * 2;
    }

    /**
     * Esegue il flush effettivo.
     */
    private synchronized void triggerFlush(FlushReason reason) {
        if (buffer.isEmpty()) return;

        lastFlush = Instant.now();
        BufferState state = getState();

        // Determina quanti items flushare
        int toFlush;
        switch (state) {
            case CRITICAL:
                // Flush tutto
                toFlush = buffer.size();
                break;
            case HIGH:
                // Flush metà per alleggerire
                toFlush = Math.max(batchSize, buffer.size() / 2);
                break;
            default:
                // Flush normale (batch size o items vecchi)
                toFlush = Math.min(batchSize, buffer.size());
        }

        // Seleziona items da flushare (priorità: più vecchi prima)
        List<Map.Entry<K, TimestampedValue<V>>> toProcess = buffer.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getValue().timestamp()))
            .limit(toFlush)
            .toList();

        if (toProcess.isEmpty()) return;

        // Rimuovi dalla mappa e prepara batch
        List<V> batch = new ArrayList<>(toProcess.size());
        for (var entry : toProcess) {
            buffer.remove(entry.getKey());
            batch.add(entry.getValue().value());
        }

        // Invia batch
        try {
            batchSender.accept(batch);
            itemsFlushed.add(batch.size());
            flushCount.increment();
        } catch (Exception e) {
            // Fallback per items falliti
            batch.forEach(this::handleFallback);
            itemsDropped.add(batch.size());
        }
    }

    private void handleFallback(V item) {
        if (fallbackHandler != null) {
            try {
                fallbackHandler.accept(item);
            } catch (Exception ignored) {}
        }
    }

    private void calculateRate() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRateReset.getAndSet(now);
        if (elapsed > 0) {
            long count = inputRate.sumThenReset();
            currentInputRate = count * 1000.0 / elapsed; // items/sec
        }
    }

    // ========================================================================
    // STATE & METRICS
    // ========================================================================

    /**
     * Calcola lo stato corrente del buffer.
     */
    public BufferState getState() {
        double fillRatio = (double) buffer.size() / maxCapacity;

        if (fillRatio >= criticalWatermark) return BufferState.CRITICAL;
        if (fillRatio >= highWatermark) return BufferState.HIGH;
        if (fillRatio >= lowWatermark) return BufferState.NORMAL;
        return BufferState.LOW;
    }

    public int size() {
        return buffer.size();
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public double getFillRatio() {
        return (double) buffer.size() / maxCapacity;
    }

    public double getCurrentInputRate() {
        return currentInputRate;
    }

    public Metrics getMetrics() {
        return new Metrics(
            buffer.size(),
            maxCapacity,
            getState(),
            currentInputRate,
            itemsAdded.sum(),
            itemsFlushed.sum(),
            itemsDropped.sum(),
            flushCount.sum(),
            adaptiveDelays.sum()
        );
    }

    public record Metrics(
        int currentSize,
        int maxCapacity,
        BufferState state,
        double inputRate,
        long itemsAdded,
        long itemsFlushed,
        long itemsDropped,
        long flushCount,
        long adaptiveDelays
    ) {
        public double fillRatio() {
            return (double) currentSize / maxCapacity;
        }
    }

    /**
     * Motivo del flush (per logging/debug).
     */
    public enum FlushReason {
        IDLE_TIMEOUT,
        MAX_AGE_EXCEEDED,
        NORMAL_INTERVAL,
        HIGH_CAPACITY,
        CRITICAL_CAPACITY,
        MANUAL,
        SHUTDOWN
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder<K, V> {
        private String name = "adaptive-buffer";
        private int maxCapacity = 1000;
        private double lowWatermark = 0.3;
        private double highWatermark = 0.7;
        private double criticalWatermark = 0.9;
        private long maxItemAgeMs = 120_000;        // 2 minuti
        private long idleTimeoutMs = 30_000;        // 30 secondi
        private long minFlushIntervalMs = 5_000;    // 5 secondi
        private int batchSize = 100;
        private Consumer<List<V>> batchSender;
        private Consumer<V> fallbackHandler;

        public Builder<K, V> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<K, V> maxCapacity(int maxCapacity) {
            this.maxCapacity = maxCapacity;
            return this;
        }

        /**
         * Soglia sotto la quale il buffer accumula senza flush proattivo.
         * Default: 0.3 (30%)
         */
        public Builder<K, V> lowWatermark(double lowWatermark) {
            this.lowWatermark = lowWatermark;
            return this;
        }

        /**
         * Soglia sopra la quale inizia flush aggressivo.
         * Default: 0.7 (70%)
         */
        public Builder<K, V> highWatermark(double highWatermark) {
            this.highWatermark = highWatermark;
            return this;
        }

        /**
         * Soglia critica - flush immediato.
         * Default: 0.9 (90%)
         */
        public Builder<K, V> criticalWatermark(double criticalWatermark) {
            this.criticalWatermark = criticalWatermark;
            return this;
        }

        /**
         * Età massima di un item prima del flush forzato.
         * Default: 2 minuti
         */
        public Builder<K, V> maxItemAge(Duration maxAge) {
            this.maxItemAgeMs = maxAge.toMillis();
            return this;
        }

        /**
         * Timeout di inattività dopo il quale fare flush.
         * Default: 30 secondi
         */
        public Builder<K, V> idleTimeout(Duration idleTimeout) {
            this.idleTimeoutMs = idleTimeout.toMillis();
            return this;
        }

        /**
         * Intervallo minimo tra flush in condizioni normali.
         * Default: 5 secondi
         */
        public Builder<K, V> minFlushInterval(Duration interval) {
            this.minFlushIntervalMs = interval.toMillis();
            return this;
        }

        /**
         * Dimensione batch per flush normale.
         * Default: 100
         */
        public Builder<K, V> batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sender per batch di items.
         */
        public Builder<K, V> batchSender(Consumer<List<V>> sender) {
            this.batchSender = sender;
            return this;
        }

        /**
         * Handler per items falliti/droppati.
         */
        public Builder<K, V> fallbackHandler(Consumer<V> handler) {
            this.fallbackHandler = handler;
            return this;
        }

        public AdaptiveBuffer<K, V> build() {
            Objects.requireNonNull(batchSender, "batchSender is required");

            if (lowWatermark >= highWatermark || highWatermark >= criticalWatermark) {
                throw new IllegalArgumentException(
                    "Watermarks must be: low < high < critical");
            }

            return new AdaptiveBuffer<>(this);
        }
    }
}
