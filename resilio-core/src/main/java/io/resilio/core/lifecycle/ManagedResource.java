package io.resilio.core.lifecycle;

/**
 * Interface for resources that can be managed by ResilioResourceManager.
 *
 * <p>Implementors provide cleanup capabilities and memory usage information,
 * allowing the ResourceManager to release memory after periods of inactivity.</p>
 *
 * <h2>GOVERNANCE V16 - Memory Management</h2>
 * <p>All Resilio components that hold memory (caches, buffers, pools) should
 * implement this interface to participate in automatic memory cleanup.</p>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public interface ManagedResource {

    /**
     * Returns the unique name of this resource.
     * @return resource name
     */
    String name();

    /**
     * Returns the estimated memory usage in bytes.
     * <p>This is an approximation used for monitoring and cleanup decisions.</p>
     * @return estimated bytes used
     */
    long estimatedMemoryBytes();

    /**
     * Returns the number of items/entries currently held.
     * @return item count
     */
    long itemCount();

    /**
     * Releases all resources and clears all data.
     * <p>After this call, the resource should have minimal memory footprint.</p>
     * @return number of items cleared
     */
    long releaseAll();

    /**
     * Releases expired or stale entries only.
     * <p>This is a lighter cleanup that keeps valid data.</p>
     * @return number of items released
     */
    long releaseExpired();

    /**
     * Returns the timestamp of the last access (read or write).
     * @return last access time in milliseconds since epoch
     */
    long lastAccessTimeMillis();

    /**
     * Returns true if this resource is currently empty.
     * @return true if empty
     */
    default boolean isEmpty() {
        return itemCount() == 0;
    }

    /**
     * Returns true if this resource has been idle for the given duration.
     * @param idleThresholdMillis idle threshold in milliseconds
     * @return true if idle
     */
    default boolean isIdleSince(long idleThresholdMillis) {
        return System.currentTimeMillis() - lastAccessTimeMillis() > idleThresholdMillis;
    }
}
