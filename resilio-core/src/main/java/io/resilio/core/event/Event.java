package io.resilio.core.event;

import java.time.Instant;

/**
 * Marker interface for events published on {@link EventBus}.
 *
 * <p>Events are immutable value objects (preferably records) that
 * carry information about something that happened.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public record CacheInvalidatedEvent(
 *     String cacheName,
 *     String key,
 *     Instant timestamp
 * ) implements Event {
 *     public CacheInvalidatedEvent(String cacheName, String key) {
 *         this(cacheName, key, Instant.now());
 *     }
 * }
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.1.0
 */
public interface Event {

    /**
     * Returns the timestamp when the event occurred.
     * Default implementation returns current time (override in records).
     */
    default Instant timestamp() {
        return Instant.now();
    }
}
