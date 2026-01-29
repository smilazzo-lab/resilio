package io.resilio.core.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Simple in-process event bus for pub/sub within the same JVM.
 *
 * <p>Use cases:</p>
 * <ul>
 *   <li>Cache invalidation notifications</li>
 *   <li>Configuration change propagation</li>
 *   <li>Decoupled component communication</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create bus
 * EventBus bus = EventBus.create();
 *
 * // Subscribe to events
 * bus.subscribe(RuleChangedEvent.class, event -> {
 *     cache.invalidate(event.ruleCode());
 * });
 *
 * // Publish event (sync)
 * bus.publish(new RuleChangedEvent("RULE_001"));
 *
 * // Publish event (async)
 * bus.publishAsync(new RuleChangedEvent("RULE_002"));
 * }</pre>
 *
 * <p>Thread-safe. Handlers are called in order of registration.</p>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.1.0
 */
public class EventBus {

    private final Map<Class<?>, List<Consumer<?>>> handlers = new ConcurrentHashMap<>();
    private final Executor asyncExecutor;

    private EventBus(Executor asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Creates an event bus with default async executor (virtual threads if available).
     */
    public static EventBus create() {
        return new EventBus(Runnable::run); // sync by default
    }

    /**
     * Creates an event bus with custom async executor.
     *
     * @param asyncExecutor executor for async publish
     */
    public static EventBus create(Executor asyncExecutor) {
        return new EventBus(asyncExecutor);
    }

    /**
     * Subscribes to events of a specific type.
     *
     * @param eventType the event class to subscribe to
     * @param handler the handler to call when event is published
     * @param <E> event type
     * @return subscription that can be used to unsubscribe
     */
    public <E> Subscription subscribe(Class<E> eventType, Consumer<E> handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(handler);
        return () -> unsubscribe(eventType, handler);
    }

    /**
     * Unsubscribes a handler.
     *
     * @param eventType the event class
     * @param handler the handler to remove
     * @param <E> event type
     */
    public <E> void unsubscribe(Class<E> eventType, Consumer<E> handler) {
        List<Consumer<?>> list = handlers.get(eventType);
        if (list != null) {
            list.remove(handler);
        }
    }

    /**
     * Publishes an event synchronously.
     * All handlers are called in the current thread.
     *
     * @param event the event to publish
     * @param <E> event type
     */
    @SuppressWarnings("unchecked")
    public <E> void publish(E event) {
        if (event == null) return;

        List<Consumer<?>> list = handlers.get(event.getClass());
        if (list != null) {
            for (Consumer<?> handler : list) {
                try {
                    ((Consumer<E>) handler).accept(event);
                } catch (Exception e) {
                    // Don't let one handler break others
                }
            }
        }

        // Also notify handlers registered for superclasses/interfaces
        for (Map.Entry<Class<?>, List<Consumer<?>>> entry : handlers.entrySet()) {
            if (entry.getKey() != event.getClass() && entry.getKey().isInstance(event)) {
                for (Consumer<?> handler : entry.getValue()) {
                    try {
                        ((Consumer<E>) handler).accept(event);
                    } catch (Exception e) {
                        // Don't let one handler break others
                    }
                }
            }
        }
    }

    /**
     * Publishes an event asynchronously.
     * Handlers are called in the async executor.
     *
     * @param event the event to publish
     * @param <E> event type
     */
    public <E> void publishAsync(E event) {
        asyncExecutor.execute(() -> publish(event));
    }

    /**
     * Returns the number of handlers for a specific event type.
     *
     * @param eventType the event class
     * @return number of registered handlers
     */
    public int handlerCount(Class<?> eventType) {
        List<Consumer<?>> list = handlers.get(eventType);
        return list != null ? list.size() : 0;
    }

    /**
     * Clears all handlers.
     */
    public void clear() {
        handlers.clear();
    }

    /**
     * Subscription handle for unsubscribing.
     */
    @FunctionalInterface
    public interface Subscription {
        /**
         * Unsubscribes from the event.
         */
        void unsubscribe();
    }
}
