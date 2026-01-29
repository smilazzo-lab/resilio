package io.resilio.core.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized resource manager for Resilio components.
 *
 * <h2>GOVERNANCE V16 - Automatic Memory Cleanup</h2>
 * <p>This manager tracks all registered ManagedResource instances and automatically
 * releases memory after periods of inactivity. This allows applications using
 * Resilio to see memory decrease in Docker/container metrics when idle.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Automatic idle-based cleanup (default: 5 minutes of inactivity)</li>
 *   <li>Periodic expired entry eviction (default: every 60 seconds)</li>
 *   <li>Full cleanup on demand via {@link #releaseAll()}</li>
 *   <li>Memory usage metrics for monitoring</li>
 *   <li>Thread-safe resource registration/deregistration</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ResilioResourceManager manager = ResilioResourceManager.builder()
 *     .idleTimeout(Duration.ofMinutes(5))
 *     .cleanupInterval(Duration.ofSeconds(60))
 *     .build();
 *
 * // Register resources
 * manager.register(myCache);
 * manager.register(myBuffer);
 *
 * // ... application runs ...
 *
 * // On shutdown
 * manager.shutdown();
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class ResilioResourceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResilioResourceManager.class);

    // Singleton instance for global access
    private static volatile ResilioResourceManager instance;
    private static final Object INSTANCE_LOCK = new Object();

    private final Map<String, ManagedResource> resources = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long idleTimeoutMillis;
    private final long cleanupIntervalMillis;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong lastActivityMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong totalReleasedItems = new AtomicLong(0);
    private final AtomicLong totalReleasedBytes = new AtomicLong(0);

    private ScheduledFuture<?> cleanupTask;
    private ScheduledFuture<?> idleCheckTask;

    private ResilioResourceManager(Builder builder) {
        this.idleTimeoutMillis = builder.idleTimeout.toMillis();
        this.cleanupIntervalMillis = builder.cleanupInterval.toMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resilio-resource-manager");
            t.setDaemon(true);
            return t;
        });

        startScheduledTasks();
        log.info("[RESILIO] ResourceManager started - idleTimeout={}s, cleanupInterval={}s",
                builder.idleTimeout.toSeconds(), builder.cleanupInterval.toSeconds());
    }

    /**
     * Returns the global singleton instance, creating it with defaults if needed.
     * @return the global ResourceManager instance
     */
    public static ResilioResourceManager getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = builder().build();
                }
            }
        }
        return instance;
    }

    /**
     * Sets the global singleton instance.
     * <p>Use this to configure a custom instance at application startup.</p>
     * @param manager the manager to use as global instance
     */
    public static void setInstance(ResilioResourceManager manager) {
        synchronized (INSTANCE_LOCK) {
            if (instance != null) {
                instance.close();
            }
            instance = manager;
        }
    }

    /**
     * Creates a new builder.
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Registers a resource for management.
     * @param resource the resource to manage
     */
    public void register(ManagedResource resource) {
        if (resource == null || resource.name() == null) {
            return;
        }
        resources.put(resource.name(), resource);
        recordActivity();
        log.debug("[RESILIO] Resource registered: {}", resource.name());
    }

    /**
     * Deregisters a resource.
     * @param name the resource name
     * @return the removed resource, or null if not found
     */
    public ManagedResource deregister(String name) {
        ManagedResource removed = resources.remove(name);
        if (removed != null) {
            log.debug("[RESILIO] Resource deregistered: {}", name);
        }
        return removed;
    }

    /**
     * Records activity to reset the idle timer.
     * <p>Call this when any managed resource is accessed.</p>
     */
    public void recordActivity() {
        lastActivityMillis.set(System.currentTimeMillis());
    }

    /**
     * Returns the total estimated memory usage across all resources.
     * @return total bytes
     */
    public long totalMemoryBytes() {
        return resources.values().stream()
                .mapToLong(ManagedResource::estimatedMemoryBytes)
                .sum();
    }

    /**
     * Returns the total item count across all resources.
     * @return total items
     */
    public long totalItemCount() {
        return resources.values().stream()
                .mapToLong(ManagedResource::itemCount)
                .sum();
    }

    /**
     * Releases expired entries from all resources.
     * @return total items released
     */
    public long releaseExpired() {
        long released = 0;
        for (ManagedResource resource : resources.values()) {
            try {
                long count = resource.releaseExpired();
                released += count;
                if (count > 0) {
                    log.debug("[RESILIO] Released {} expired items from {}", count, resource.name());
                }
            } catch (Exception e) {
                log.warn("[RESILIO] Error releasing expired from {}: {}", resource.name(), e.getMessage());
            }
        }
        if (released > 0) {
            totalReleasedItems.addAndGet(released);
            log.info("[RESILIO] Periodic cleanup released {} expired items", released);
        }
        return released;
    }

    /**
     * Releases ALL data from all resources.
     * <p>Use this for full memory cleanup during extended idle periods.</p>
     * @return total items released
     */
    public long releaseAll() {
        long released = 0;
        long bytesBeforeCleanup = totalMemoryBytes();

        for (ManagedResource resource : resources.values()) {
            try {
                long count = resource.releaseAll();
                released += count;
                if (count > 0) {
                    log.info("[RESILIO] Released {} items from {} (full cleanup)", count, resource.name());
                }
            } catch (Exception e) {
                log.warn("[RESILIO] Error releasing from {}: {}", resource.name(), e.getMessage());
            }
        }

        if (released > 0) {
            totalReleasedItems.addAndGet(released);
            totalReleasedBytes.addAndGet(bytesBeforeCleanup);
            log.info("[RESILIO] Full cleanup completed: released {} items, ~{} MB freed",
                    released, bytesBeforeCleanup / (1024 * 1024));
        }

        // Hint to GC that we freed memory
        System.gc();

        return released;
    }

    /**
     * Releases resources that have been idle beyond the threshold.
     * @return total items released
     */
    public long releaseIdleResources() {
        long released = 0;
        for (ManagedResource resource : resources.values()) {
            if (resource.isIdleSince(idleTimeoutMillis)) {
                try {
                    long count = resource.releaseAll();
                    released += count;
                    if (count > 0) {
                        log.info("[RESILIO] Released {} items from idle resource: {}", count, resource.name());
                    }
                } catch (Exception e) {
                    log.warn("[RESILIO] Error releasing idle resource {}: {}", resource.name(), e.getMessage());
                }
            }
        }
        return released;
    }

    /**
     * Checks if the system has been globally idle.
     * @return true if idle beyond threshold
     */
    public boolean isGloballyIdle() {
        return System.currentTimeMillis() - lastActivityMillis.get() > idleTimeoutMillis;
    }

    /**
     * Returns metrics about this manager.
     * @return metrics snapshot
     */
    public Metrics getMetrics() {
        return new Metrics(
                resources.size(),
                totalItemCount(),
                totalMemoryBytes(),
                totalReleasedItems.get(),
                totalReleasedBytes.get(),
                System.currentTimeMillis() - lastActivityMillis.get(),
                isGloballyIdle()
        );
    }

    /**
     * Returns a snapshot of all managed resources.
     * @return list of resource snapshots
     */
    public List<ResourceSnapshot> getResourceSnapshots() {
        List<ResourceSnapshot> snapshots = new ArrayList<>();
        for (ManagedResource resource : resources.values()) {
            snapshots.add(new ResourceSnapshot(
                    resource.name(),
                    resource.itemCount(),
                    resource.estimatedMemoryBytes(),
                    resource.lastAccessTimeMillis(),
                    resource.isIdleSince(idleTimeoutMillis)
            ));
        }
        return snapshots;
    }

    private void startScheduledTasks() {
        // Periodic cleanup of expired entries
        cleanupTask = scheduler.scheduleAtFixedRate(
                this::periodicCleanup,
                cleanupIntervalMillis,
                cleanupIntervalMillis,
                TimeUnit.MILLISECONDS
        );

        // Check for global idle state
        idleCheckTask = scheduler.scheduleAtFixedRate(
                this::checkIdleState,
                idleTimeoutMillis,
                idleTimeoutMillis / 2,
                TimeUnit.MILLISECONDS
        );
    }

    private void periodicCleanup() {
        if (!running.get()) return;
        try {
            releaseExpired();
        } catch (Exception e) {
            log.error("[RESILIO] Error in periodic cleanup", e);
        }
    }

    private void checkIdleState() {
        if (!running.get()) return;
        try {
            if (isGloballyIdle() && totalItemCount() > 0) {
                log.info("[RESILIO] System idle for {}ms, triggering full cleanup",
                        System.currentTimeMillis() - lastActivityMillis.get());
                releaseAll();
            }
        } catch (Exception e) {
            log.error("[RESILIO] Error in idle check", e);
        }
    }

    /**
     * Shuts down the resource manager and releases all resources.
     */
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            log.info("[RESILIO] ResourceManager shutting down...");

            if (cleanupTask != null) {
                cleanupTask.cancel(false);
            }
            if (idleCheckTask != null) {
                idleCheckTask.cancel(false);
            }

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            releaseAll();
            resources.clear();

            log.info("[RESILIO] ResourceManager shutdown complete");
        }
    }

    /**
     * Metrics snapshot.
     */
    public record Metrics(
            int resourceCount,
            long totalItems,
            long totalMemoryBytes,
            long totalReleasedItems,
            long totalReleasedBytes,
            long idleTimeMillis,
            boolean isIdle
    ) {
        public long totalMemoryMB() {
            return totalMemoryBytes / (1024 * 1024);
        }
    }

    /**
     * Resource snapshot for monitoring.
     */
    public record ResourceSnapshot(
            String name,
            long itemCount,
            long memoryBytes,
            long lastAccessMillis,
            boolean isIdle
    ) {}

    /**
     * Builder for ResilioResourceManager.
     */
    public static class Builder {
        private Duration idleTimeout = Duration.ofMinutes(5);
        private Duration cleanupInterval = Duration.ofSeconds(60);

        /**
         * Sets the idle timeout after which full cleanup is triggered.
         * @param idleTimeout idle timeout duration
         * @return this builder
         */
        public Builder idleTimeout(Duration idleTimeout) {
            if (idleTimeout != null && !idleTimeout.isNegative() && !idleTimeout.isZero()) {
                this.idleTimeout = idleTimeout;
            }
            return this;
        }

        /**
         * Sets the interval for periodic expired entry cleanup.
         * @param cleanupInterval cleanup interval duration
         * @return this builder
         */
        public Builder cleanupInterval(Duration cleanupInterval) {
            if (cleanupInterval != null && !cleanupInterval.isNegative() && !cleanupInterval.isZero()) {
                this.cleanupInterval = cleanupInterval;
            }
            return this;
        }

        /**
         * Builds the ResourceManager.
         * @return new ResourceManager instance
         */
        public ResilioResourceManager build() {
            return new ResilioResourceManager(this);
        }
    }
}
