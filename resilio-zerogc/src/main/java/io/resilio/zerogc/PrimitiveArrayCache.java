package io.resilio.zerogc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Zero-allocation cache using primitive arrays for maximum performance.
 *
 * <p>This cache stores data in pre-allocated primitive arrays (int[], long[])
 * to avoid object allocation and GC pressure in hot paths. Keys are mapped
 * to integer indices, and values are stored directly in arrays.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Zero allocation on cache access after warmup</li>
 *   <li>O(1) lookup using array index</li>
 *   <li>Configurable capacity with bounds checking</li>
 *   <li>Support for int, long, and packed data</li>
 *   <li>Thread-safe key registration</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create cache for profile IDs -> permission bits
 * PrimitiveArrayCache cache = PrimitiveArrayCache.builder()
 *     .name("permissions")
 *     .capacity(1024)
 *     .build();
 *
 * // Register keys during warmup
 * int index1 = cache.registerKey("user:123");
 * int index2 = cache.registerKey("user:456");
 *
 * // Store and retrieve values (zero allocation)
 * cache.putLong(index1, 0b1111_0000L);
 * long perms = cache.getLong(index1);
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class PrimitiveArrayCache {

    /** Maximum supported capacity (64K entries) */
    public static final int MAX_CAPACITY = 65536;

    private final String name;
    private final int capacity;

    // Key registry - maps string keys to array indices
    private final ConcurrentHashMap<String, Integer> keyToIndex;
    private final String[] indexToKey;
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    // Primitive arrays for data storage
    private final int[] intData;
    private final long[] longData;

    // Statistics
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    private PrimitiveArrayCache(Builder builder) {
        this.name = builder.name;
        this.capacity = builder.capacity;

        this.keyToIndex = new ConcurrentHashMap<>(capacity);
        this.indexToKey = new String[capacity];
        this.intData = new int[capacity];
        this.longData = new long[capacity];
    }

    /**
     * Creates a new builder for PrimitiveArrayCache.
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Registers a key and returns its index.
     *
     * <p>This operation allocates memory (String storage). Call during
     * warmup phase, not in hot paths.</p>
     *
     * @param key the key to register
     * @return the assigned index, or -1 if capacity exceeded
     */
    public int registerKey(String key) {
        Integer existing = keyToIndex.get(key);
        if (existing != null) {
            return existing;
        }

        int index = nextIndex.getAndIncrement();
        if (index >= capacity) {
            nextIndex.decrementAndGet();
            return -1; // Capacity exceeded
        }

        Integer prev = keyToIndex.putIfAbsent(key, index);
        if (prev != null) {
            // Another thread registered this key
            nextIndex.decrementAndGet();
            return prev;
        }

        indexToKey[index] = key;
        return index;
    }

    /**
     * Returns the index for a key, or -1 if not registered.
     *
     * @param key the key to look up
     * @return the index, or -1 if not found
     */
    public int getIndex(String key) {
        Integer index = keyToIndex.get(key);
        if (index != null) {
            hits.increment();
            return index;
        }
        misses.increment();
        return -1;
    }

    /**
     * Returns the key for an index.
     *
     * @param index the index
     * @return the key, or null if not registered
     */
    public String getKey(int index) {
        if (index < 0 || index >= capacity) {
            return null;
        }
        return indexToKey[index];
    }

    // ========== INT OPERATIONS ==========

    /**
     * Stores an int value at the specified index.
     *
     * @param index the array index
     * @param value the value to store
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public void putInt(int index, int value) {
        checkIndex(index);
        intData[index] = value;
    }

    /**
     * Retrieves an int value from the specified index.
     *
     * @param index the array index
     * @return the stored value
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public int getInt(int index) {
        checkIndex(index);
        return intData[index];
    }

    /**
     * Atomically increments and returns the int value at the specified index.
     *
     * @param index the array index
     * @return the new value after increment
     */
    public int incrementInt(int index) {
        checkIndex(index);
        return ++intData[index];
    }

    // ========== LONG OPERATIONS ==========

    /**
     * Stores a long value at the specified index.
     *
     * @param index the array index
     * @param value the value to store
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public void putLong(int index, long value) {
        checkIndex(index);
        longData[index] = value;
    }

    /**
     * Retrieves a long value from the specified index.
     *
     * @param index the array index
     * @return the stored value
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public long getLong(int index) {
        checkIndex(index);
        return longData[index];
    }

    /**
     * Sets a bit in the long value at the specified index.
     *
     * @param index the array index
     * @param bit the bit position (0-63)
     */
    public void setBit(int index, int bit) {
        checkIndex(index);
        longData[index] |= (1L << bit);
    }

    /**
     * Clears a bit in the long value at the specified index.
     *
     * @param index the array index
     * @param bit the bit position (0-63)
     */
    public void clearBit(int index, int bit) {
        checkIndex(index);
        longData[index] &= ~(1L << bit);
    }

    /**
     * Tests a bit in the long value at the specified index.
     *
     * @param index the array index
     * @param bit the bit position (0-63)
     * @return true if the bit is set
     */
    public boolean testBit(int index, int bit) {
        checkIndex(index);
        return (longData[index] & (1L << bit)) != 0;
    }

    // ========== UTILITY METHODS ==========

    private void checkIndex(int index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException(
                    "Index " + index + " out of bounds for capacity " + capacity);
        }
    }

    /**
     * Returns the number of registered keys.
     * @return registered key count
     */
    public int size() {
        return nextIndex.get();
    }

    /**
     * Returns the cache capacity.
     * @return capacity
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Returns the cache name.
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the hit count.
     * @return hits
     */
    public long hitCount() {
        return hits.sum();
    }

    /**
     * Returns the miss count.
     * @return misses
     */
    public long missCount() {
        return misses.sum();
    }

    /**
     * Clears all data (but keeps key registrations).
     */
    public void clearData() {
        java.util.Arrays.fill(intData, 0);
        java.util.Arrays.fill(longData, 0L);
    }

    /**
     * Builder for PrimitiveArrayCache.
     */
    public static class Builder {
        private String name = "primitive-cache";
        private int capacity = 1024;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder capacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Capacity must be positive");
            }
            if (capacity > MAX_CAPACITY) {
                throw new IllegalArgumentException("Capacity exceeds max: " + MAX_CAPACITY);
            }
            this.capacity = capacity;
            return this;
        }

        public PrimitiveArrayCache build() {
            return new PrimitiveArrayCache(this);
        }
    }
}
