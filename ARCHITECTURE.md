# RESILIO Architecture Guide

**Version**: 1.0.0
**Author**: Salvatore Milazzo
**License**: Apache 2.0

A high-performance caching framework for Java with built-in resilience patterns, zero-GC optimizations, and distributed caching support.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Module Structure](#2-module-structure)
3. [Core Concepts](#3-core-concepts)
4. [Component Architecture](#4-component-architecture)
5. [Threading Model](#5-threading-model)
6. [Configuration Guide](#6-configuration-guide)
7. [Developer Manual](#7-developer-manual)
8. [Testing Guide](#8-testing-guide)
9. [Build and Deploy](#9-build-and-deploy)
10. [Best Practices](#10-best-practices)

---

## 1. Overview

### What is RESILIO?

RESILIO is a production-ready caching framework designed for Java 17+ applications. It provides:

- **Local caching** with LRU and TTL eviction strategies
- **Distributed caching** via Redis
- **Zero-GC caching** for latency-critical paths
- **Resilience patterns** like circuit breakers
- **Event aggregation** to reduce write amplification

### Design Philosophy

1. **Minimal dependencies** - Core module only needs SLF4J
2. **Thread-safe by default** - All components work in concurrent environments
3. **Builder pattern everywhere** - Fluent, type-safe configuration
4. **Observable** - Built-in metrics for monitoring
5. **Modular** - Use only what you need

---

## 2. Module Structure

```
resilio-parent (1.0.0)
│
├── resilio-core          # Foundation: caches, circuit breaker, lifecycle
│
├── resilio-zerogc        # Zero-allocation caching for hot paths
│
├── resilio-aggregator    # Event aggregation for write reduction
│
├── resilio-redis         # Distributed caching with Redis
│
└── resilio-all           # All modules in one dependency
```

### Module Dependencies

```
resilio-core (standalone)
     │
     ├──────────────────────────┐
     │                          │
     ▼                          ▼
resilio-zerogc           resilio-aggregator
     │                          │
     └──────────┬───────────────┘
                │
                ▼
          resilio-redis
                │
                ▼
           resilio-all (aggregates everything)
```

### Maven Coordinates

```xml
<!-- Single dependency for everything -->
<dependency>
    <groupId>io.resilio</groupId>
    <artifactId>resilio-all</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Or pick individual modules -->
<dependency>
    <groupId>io.resilio</groupId>
    <artifactId>resilio-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 3. Core Concepts

### 3.1 Cache Interface

All caches implement the `Cache<K, V>` interface:

```java
public interface Cache<K, V> {
    V get(K key, Function<K, V> loader);  // Get or compute
    V getIfPresent(K key);                 // Get without computing
    void put(K key, V value);              // Store value
    void invalidate(K key);                // Remove single entry
    void invalidateAll();                  // Clear all entries
    long size();                           // Entry count
    CacheStats stats();                    // Statistics
}
```

### 3.2 Cache Statistics

Every cache tracks performance metrics:

```java
public interface CacheStats {
    long hitCount();       // Successful lookups
    long missCount();      // Failed lookups
    double hitRate();      // hits / (hits + misses)
    long evictionCount();  // Entries removed
    long size();           // Current entries
    void reset();          // Clear counters
}
```

### 3.3 Managed Resources

Components implement `ManagedResource` for lifecycle management:

```java
public interface ManagedResource {
    long estimatedMemoryBytes();  // Memory usage estimate
    long itemCount();             // Number of items
    void releaseAll();            // Free all resources
    void releaseExpired();        // Free expired items
    long lastAccessTimeMillis();  // Last access time
}
```

### 3.4 Circuit Breaker States

```
     ┌─────────────────────────────────────┐
     │                                     │
     ▼                                     │
  CLOSED ──(failures exceed threshold)──► OPEN
     ▲                                     │
     │                                     │
     └───(success)─── HALF_OPEN ◄──(timeout expires)
```

---

## 4. Component Architecture

### 4.1 resilio-core

#### LruCache

Fixed-size cache with Least Recently Used eviction.

**Internal structure:**
- `LinkedHashMap` with access-order for LRU behavior
- `ReentrantReadWriteLock` for thread safety
- `LongAdder` for lock-free statistics

**Key characteristics:**
- O(1) get/put operations
- Automatic eviction when size limit reached
- ~256 bytes overhead per entry

```
┌─────────────────────────────────────────┐
│              LruCache<K,V>              │
├─────────────────────────────────────────┤
│  LinkedHashMap<K,V> (access-order)      │
│  ├── Entry 1 (most recent)              │
│  ├── Entry 2                            │
│  ├── ...                                │
│  └── Entry N (least recent) ← evicted   │
├─────────────────────────────────────────┤
│  ReadWriteLock (read-heavy optimization)│
│  LongAdder hits/misses (lock-free)      │
└─────────────────────────────────────────┘
```

#### TtlCache

Time-based expiration cache.

**Internal structure:**
- `ConcurrentHashMap` for lock-free access
- `CacheEntry` record with value and expiration time
- Lazy expiration on access + periodic cleanup

**Key characteristics:**
- Configurable TTL per cache
- Optional max size with LRU eviction
- Automatic expired entry cleanup

```
┌─────────────────────────────────────────┐
│              TtlCache<K,V>              │
├─────────────────────────────────────────┤
│  ConcurrentHashMap<K, CacheEntry<V>>    │
│  ├── key1 → {value, expiresAt: T+60s}   │
│  ├── key2 → {value, expiresAt: T+30s}   │
│  └── key3 → {value, expiresAt: T+90s}   │
├─────────────────────────────────────────┤
│  ScheduledExecutor (cleanup task)       │
│  LongAdder statistics                   │
└─────────────────────────────────────────┘
```

#### CircuitBreaker

Protects systems from cascading failures.

**Internal structure:**
- `AtomicReference<State>` for thread-safe state
- `AtomicInteger` for failure counting
- Configurable thresholds and timeouts

```
┌─────────────────────────────────────────┐
│           CircuitBreaker                │
├─────────────────────────────────────────┤
│  State: CLOSED | OPEN | HALF_OPEN       │
│  failureCount: AtomicInteger            │
│  lastFailureTime: AtomicLong            │
├─────────────────────────────────────────┤
│  Configuration:                         │
│  - failureThreshold (default: 5)        │
│  - resetTimeoutMs (default: 30000)      │
└─────────────────────────────────────────┘
```

#### AsyncBuffer

High-throughput event buffering with backpressure.

**Internal structure:**
- `ArrayBlockingQueue` for bounded buffering
- Background thread for batch sending
- Built-in circuit breaker protection

```
┌─────────────────────────────────────────┐
│           AsyncBuffer<E>                │
├─────────────────────────────────────────┤
│  ArrayBlockingQueue<E> (bounded)        │
│  ├── [event1, event2, event3, ...]      │
│  └── capacity: configurable             │
├─────────────────────────────────────────┤
│  Sender Thread:                         │
│  - Drains queue in batches              │
│  - Calls sender function                │
│  - Handles failures with circuit breaker│
├─────────────────────────────────────────┤
│  Metrics: enqueued, dropped, sent       │
└─────────────────────────────────────────┘
```

#### ResilioResourceManager

Centralized resource lifecycle management.

**Internal structure:**
- Singleton with double-checked locking
- `ConcurrentHashMap` of managed resources
- Scheduled tasks for cleanup

```
┌─────────────────────────────────────────┐
│       ResilioResourceManager            │
├─────────────────────────────────────────┤
│  Map<String, ManagedResource>           │
│  ├── "cache-users" → LruCache           │
│  ├── "cache-sessions" → TtlCache        │
│  └── "pool-objects" → ObjectPool        │
├─────────────────────────────────────────┤
│  Scheduled Tasks:                       │
│  - Idle cleanup (every 5 min)           │
│  - Expired entry eviction (every 60s)   │
└─────────────────────────────────────────┘
```

### 4.2 resilio-zerogc

#### PrimitiveArrayCache

Zero-allocation cache using pre-allocated arrays.

**Internal structure:**
- Pre-allocated `int[]` and `long[]` arrays
- Key-to-index mapping via `ConcurrentHashMap`
- Registration phase allocates, hot path never allocates

**Key characteristics:**
- Max 65,536 entries
- Zero GC pressure after warmup
- ~50M ops/sec throughput

```
┌─────────────────────────────────────────┐
│        PrimitiveArrayCache              │
├─────────────────────────────────────────┤
│  int[] intValues = new int[65536]       │
│  long[] longValues = new long[65536]    │
│  Map<String, Integer> keyToIndex        │
├─────────────────────────────────────────┤
│  Registration (startup):                │
│  - registerKey("user.count") → index 0  │
│  - registerKey("session.id") → index 1  │
├─────────────────────────────────────────┤
│  Hot Path (zero allocation):            │
│  - getInt(0), putInt(0, value)          │
│  - getLong(1), putLong(1, value)        │
└─────────────────────────────────────────┘
```

#### ObjectPool

High-performance object reuse pool.

**Internal structure:**
- `ConcurrentLinkedQueue` for pooled objects
- Factory supplier for creation
- Optional reset consumer for cleanup

```
┌─────────────────────────────────────────┐
│           ObjectPool<T>                 │
├─────────────────────────────────────────┤
│  ConcurrentLinkedQueue<T> pool          │
│  ├── [obj1, obj2, obj3, ...]            │
│  └── maxSize: configurable              │
├─────────────────────────────────────────┤
│  factory: Supplier<T>                   │
│  resetter: Consumer<T> (optional)       │
├─────────────────────────────────────────┤
│  Operations:                            │
│  - borrow() → T (from pool or factory)  │
│  - release(T) → back to pool            │
│  - borrowHandle() → AutoCloseable       │
└─────────────────────────────────────────┘
```

### 4.3 resilio-aggregator

#### EventAggregator

Reduces write amplification through batching.

**Internal structure:**
- `ConcurrentHashMap` of aggregation buckets
- Scheduled flush for time-based batching
- Memory protection with max buckets

```
┌─────────────────────────────────────────┐
│       EventAggregator<E, S>             │
├─────────────────────────────────────────┤
│  Map<String, Bucket<S>>                 │
│  ├── "user-123" → Bucket{summary, ...}  │
│  ├── "user-456" → Bucket{summary, ...}  │
│  └── "user-789" → Bucket{summary, ...}  │
├─────────────────────────────────────────┤
│  Bucket<S>:                             │
│  - summary: S (aggregated state)        │
│  - eventCount: int                      │
│  - createdAt, lastAccess: long          │
├─────────────────────────────────────────┤
│  Flush Triggers:                        │
│  - Time interval (default: 5 min)       │
│  - Event count (default: 1000)          │
│  - Manual flush                         │
└─────────────────────────────────────────┘
```

### 4.4 resilio-redis

#### RedisCache

Distributed caching via Redis.

**Internal structure:**
- Lettuce client for async/reactive operations
- Connection pooling via Commons Pool 2
- Pluggable serializers

```
┌─────────────────────────────────────────┐
│           RedisCache<K, V>              │
├─────────────────────────────────────────┤
│  RedisConnectionManager                 │
│  ├── Lettuce RedisClient                │
│  ├── Connection pool                    │
│  └── Key prefix for namespace           │
├─────────────────────────────────────────┤
│  Serializers:                           │
│  - keySerializer: RedisSerializer<K>    │
│  - valueSerializer: RedisSerializer<V>  │
├─────────────────────────────────────────┤
│  Configuration:                         │
│  - TTL (optional, per-cache or per-put) │
│  - Mode: standalone | sentinel | cluster│
└─────────────────────────────────────────┘
```

#### Redis Connection Modes

```
Standalone:
┌──────────┐
│  Client  │ ───────► Redis Server
└──────────┘

Sentinel (High Availability):
┌──────────┐     ┌───────────┐     ┌────────┐
│  Client  │ ──► │ Sentinel  │ ──► │ Master │
└──────────┘     │  Cluster  │     └────────┘
                 └───────────┘          │
                                   ┌────┴────┐
                                   │ Replica │
                                   └─────────┘

Cluster (Sharding):
┌──────────┐     ┌─────────────────────────┐
│  Client  │ ──► │ Cluster (16384 slots)   │
└──────────┘     │ ┌───────┐ ┌───────┐     │
                 │ │Node 1 │ │Node 2 │ ... │
                 │ └───────┘ └───────┘     │
                 └─────────────────────────┘
```

---

## 5. Threading Model

### Thread Safety Guarantees

| Component | Strategy | Read Performance | Write Performance |
|-----------|----------|------------------|-------------------|
| LruCache | ReadWriteLock | High (shared) | Medium (exclusive) |
| TtlCache | ConcurrentHashMap | Very High | Very High |
| CircuitBreaker | AtomicReference | Very High | Very High |
| PrimitiveArrayCache | Volatile arrays | Very High | High |
| ObjectPool | ConcurrentLinkedQueue | Very High | Very High |
| EventAggregator | ConcurrentHashMap | Very High | Very High |

### Lock-Free Statistics

All caches use `LongAdder` for statistics:

```java
// Traditional (contention under load)
private AtomicLong hits = new AtomicLong();
hits.incrementAndGet();  // CAS contention

// RESILIO (lock-free, scales with CPUs)
private LongAdder hits = new LongAdder();
hits.increment();  // No contention
```

### Background Threads

| Component | Thread Purpose | Daemon |
|-----------|----------------|--------|
| TtlCache | Expired entry cleanup | Yes |
| AsyncBuffer | Batch sender | Yes |
| EventAggregator | Periodic flush | Yes |
| ResourceManager | Idle cleanup | Yes |

---

## 6. Configuration Guide

### 6.1 LruCache Configuration

```java
LruCache<String, User> cache = LruCache.<String, User>builder()
    .name("user-cache")           // Required: unique name
    .maxSize(10_000)              // Required: max entries
    .build();
```

### 6.2 TtlCache Configuration

```java
TtlCache<String, Session> cache = TtlCache.<String, Session>builder()
    .name("session-cache")        // Required: unique name
    .ttl(Duration.ofMinutes(30))  // Required: time to live
    .maxSize(50_000)              // Optional: max entries
    .build();
```

### 6.3 CircuitBreaker Configuration

```java
CircuitBreaker breaker = CircuitBreaker.builder()
    .name("external-api")         // Required: unique name
    .failureThreshold(5)          // Failures before opening
    .resetTimeout(Duration.ofSeconds(30))  // Time before half-open
    .build();
```

### 6.4 PrimitiveArrayCache Configuration

```java
PrimitiveArrayCache cache = PrimitiveArrayCache.builder()
    .name("metrics-cache")        // Required: unique name
    .capacity(10_000)             // Max entries (≤65536)
    .build();

// Registration phase (at startup)
int userCountIdx = cache.registerKey("user.count");
int sessionCountIdx = cache.registerKey("session.count");

// Hot path (zero allocation)
cache.incrementInt(userCountIdx);
int count = cache.getInt(userCountIdx);
```

### 6.5 ObjectPool Configuration

```java
ObjectPool<StringBuilder> pool = ObjectPool.<StringBuilder>builder()
    .factory(StringBuilder::new)      // How to create
    .resetter(sb -> sb.setLength(0))  // How to reset
    .maxSize(100)                     // Pool size limit
    .build();

// Usage
try (var handle = pool.borrowHandle()) {
    StringBuilder sb = handle.get();
    sb.append("hello");
    // Automatically returned to pool
}
```

### 6.6 EventAggregator Configuration

```java
EventAggregator<ClickEvent, ClickSummary> aggregator =
    EventAggregator.<ClickEvent, ClickSummary>builder()
        .keyExtractor(e -> e.getUserId())       // Group by user
        .summaryFactory(ClickSummary::new)      // Create summary
        .accumulator((sum, e) -> sum.add(e))    // Aggregate
        .onFlush(sum -> db.save(sum))           // Persist
        .flushInterval(Duration.ofMinutes(5))   // Time-based flush
        .maxEventsBeforeFlush(1000)             // Count-based flush
        .maxBuckets(10_000)                     // Memory limit
        .build();

// Record events (batched automatically)
aggregator.record(new ClickEvent("user-123", "/page1"));
```

### 6.7 RedisCache Configuration

```java
RedisCacheConfig config = RedisCacheConfig.builder()
    .host("localhost")
    .port(6379)
    .password("secret")           // Optional
    .database(0)                  // Redis DB number
    .ssl(false)                   // TLS encryption
    .connectionTimeout(Duration.ofSeconds(5))
    .commandTimeout(Duration.ofSeconds(2))
    .keyPrefix("myapp:")          // Namespace isolation
    .build();

RedisCache<String, User> cache = RedisCache.<String, User>builder()
    .name("user-cache")
    .config(config)
    .keySerializer(new StringSerializer())
    .valueSerializer(new JacksonSerializer<>(User.class))
    .ttl(Duration.ofHours(1))
    .build();
```

### 6.8 AsyncBuffer Configuration

```java
AsyncBuffer<Event> buffer = AsyncBuffer.<Event>builder()
    .name("event-buffer")
    .queueCapacity(10_000)            // Max queued items
    .batchSize(100)                   // Items per batch
    .flushInterval(Duration.ofSeconds(1))
    .sender(batch -> sendToKafka(batch))
    .onDropped(event -> log.warn("Dropped: {}", event))
    .build();
```

---

## 7. Developer Manual

### 7.1 Quick Start

**Add dependency:**

```xml
<dependency>
    <groupId>io.resilio</groupId>
    <artifactId>resilio-all</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Create a cache:**

```java
import io.resilio.core.Resilio;

// LRU cache (fixed size)
var userCache = Resilio.lru()
    .name("users")
    .maxSize(1000)
    .build();

// TTL cache (time-based expiration)
var sessionCache = Resilio.cache()
    .name("sessions")
    .ttl(Duration.ofMinutes(30))
    .build();
```

**Use the cache:**

```java
// Get or compute
User user = userCache.get("user-123", id -> loadFromDb(id));

// Get without computing
User cached = userCache.getIfPresent("user-123");

// Store directly
userCache.put("user-456", newUser);

// Remove
userCache.invalidate("user-123");

// Check stats
CacheStats stats = userCache.stats();
System.out.println("Hit rate: " + stats.hitRate());
```

### 7.2 Using Circuit Breaker

```java
CircuitBreaker breaker = Resilio.circuitBreaker()
    .name("payment-api")
    .failureThreshold(3)
    .resetTimeout(Duration.ofSeconds(30))
    .build();

// Execute with protection
try {
    String result = breaker.execute(() -> callPaymentApi());
} catch (CircuitBreakerOpenException e) {
    // Circuit is open, use fallback
    return cachedResult;
}

// Or use tryExecute for optional result
Optional<String> result = breaker.tryExecute(() -> callApi());
```

### 7.3 Cache with Circuit Breaker

```java
// Wrap any cache with circuit breaker protection
var protectedCache = new CircuitBreakerCache<>(
    redisCache,
    circuitBreaker,
    key -> localCache.getIfPresent(key)  // Fallback
);
```

### 7.4 Zero-GC Caching

**For primitive counters/metrics:**

```java
PrimitiveArrayCache metrics = PrimitiveArrayCache.builder()
    .name("app-metrics")
    .capacity(1000)
    .build();

// Startup: register keys (allocates memory)
int requestCount = metrics.registerKey("http.requests");
int errorCount = metrics.registerKey("http.errors");

// Runtime: zero allocation operations
metrics.incrementInt(requestCount);
int total = metrics.getInt(requestCount);
```

**For object reuse:**

```java
ObjectPool<byte[]> bufferPool = ObjectPool.<byte[]>builder()
    .factory(() -> new byte[8192])
    .maxSize(50)
    .build();

// Borrow and auto-return
try (var handle = bufferPool.borrowHandle()) {
    byte[] buffer = handle.get();
    // Use buffer...
} // Automatically returned to pool
```

### 7.5 Event Aggregation

**Reduce database writes by 99%:**

```java
// Instead of writing every click to DB...
EventAggregator<Click, ClickStats> aggregator =
    EventAggregator.<Click, ClickStats>builder()
        .keyExtractor(click -> click.getPageId())
        .summaryFactory(ClickStats::new)
        .accumulator((stats, click) -> {
            stats.incrementCount();
            stats.addTimestamp(click.getTime());
        })
        .onFlush(stats -> database.saveClickStats(stats))
        .flushInterval(Duration.ofMinutes(5))
        .build();

// Record millions of clicks
aggregator.record(new Click("/home", now()));

// Aggregator batches them and writes summaries
// 1M clicks → ~1000 DB writes (1000x reduction)
```

### 7.6 Redis Distributed Cache

```java
// Configure connection
RedisCacheConfig config = RedisCacheConfig.builder()
    .host("redis.example.com")
    .port(6379)
    .keyPrefix("myapp:")
    .build();

// Create distributed cache
RedisCache<String, Product> cache = RedisCache.<String, Product>builder()
    .name("products")
    .config(config)
    .valueSerializer(new JacksonSerializer<>(Product.class))
    .ttl(Duration.ofHours(1))
    .build();

// Use like any other cache
Product product = cache.get("product-123", id -> loadProduct(id));
```

### 7.7 Redis Pub/Sub

```java
RedisPubSub pubsub = new RedisPubSub(connectionManager);

// Subscribe to channel
pubsub.subscribe("events", message -> {
    System.out.println("Received: " + message);
});

// Publish message
pubsub.publish("events", "Hello World");
```

### 7.8 Redis Streams

```java
RedisStream stream = new RedisStream(connectionManager, "my-stream");

// Produce messages
stream.add(Map.of("type", "order", "id", "12345"));

// Consume with consumer group
stream.createConsumerGroup("processors");
stream.consume("processors", "worker-1", messages -> {
    for (StreamMessage msg : messages) {
        processOrder(msg.getData());
        stream.acknowledge("processors", msg.getId());
    }
});
```

### 7.9 Resource Management

```java
// Get the global resource manager
ResilioResourceManager manager = ResilioResourceManager.getInstance();

// View all managed resources
List<ResourceSnapshot> snapshots = manager.getResourceSnapshots();
for (ResourceSnapshot snap : snapshots) {
    System.out.printf("%s: %d items, %d bytes%n",
        snap.name(), snap.itemCount(), snap.memoryBytes());
}

// Get overall metrics
Metrics metrics = manager.getMetrics();
System.out.println("Total memory: " + metrics.totalMemoryBytes());

// Manual cleanup
manager.releaseIdleResources(Duration.ofMinutes(10));
```

---

## 8. Testing Guide

### 8.1 Test Structure

```
resilio-core/src/test/java/io/resilio/core/
├── benchmark/          # Performance tests
│   └── ResilioBenchmarkTest.java
├── buffer/             # Buffer tests
│   ├── AdaptiveBufferTest.java
│   └── AsyncBufferTest.java
├── cache/              # Cache tests
│   └── LruCacheTest.java
├── circuit/            # Circuit breaker tests
│   ├── CircuitBreakerTest.java
│   └── TryWithFallbackTest.java
├── intercept/          # Interceptor tests
│   └── InterceptorChainTest.java
├── lifecycle/          # Resource manager tests
│   └── ResilioResourceManagerTest.java
├── integration/        # Integration tests
│   └── ResilioIntegrationTest.java
└── stress/             # Stress tests
    └── ResilioStressTest.java
```

### 8.2 Running Tests

```bash
# Run all tests
mvn test

# Run specific module tests
mvn test -pl resilio-core

# Run specific test class
mvn test -Dtest=LruCacheTest

# Run with coverage
mvn test jacoco:report
```

### 8.3 Writing Tests

**Unit test example:**

```java
class LruCacheTest {

    @Test
    void shouldEvictLeastRecentlyUsed() {
        LruCache<String, Integer> cache = LruCache.<String, Integer>builder()
            .name("test-cache")
            .maxSize(2)
            .build();

        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a", k -> 1);  // Access "a" to make it recent
        cache.put("c", 3);       // Should evict "b"

        assertThat(cache.getIfPresent("a")).isEqualTo(1);
        assertThat(cache.getIfPresent("b")).isNull();  // Evicted
        assertThat(cache.getIfPresent("c")).isEqualTo(3);
    }
}
```

**Integration test example:**

```java
class ResilioIntegrationTest {

    @Test
    void cacheWithCircuitBreaker() {
        var cache = Resilio.lru()
            .name("test")
            .maxSize(100)
            .build();

        var breaker = Resilio.circuitBreaker()
            .name("test-breaker")
            .failureThreshold(2)
            .build();

        // Test interaction between components
        String result = breaker.execute(() ->
            cache.get("key", k -> "computed"));

        assertThat(result).isEqualTo("computed");
        assertThat(cache.stats().hitCount()).isZero();
        assertThat(cache.stats().missCount()).isEqualTo(1);
    }
}
```

### 8.4 Benchmark Tests

```java
class ResilioBenchmarkTest {

    @Test
    void benchmarkLruCachePerformance() {
        LruCache<Integer, String> cache = LruCache.<Integer, String>builder()
            .name("benchmark")
            .maxSize(10_000)
            .build();

        // Warmup
        for (int i = 0; i < 10_000; i++) {
            cache.put(i, "value-" + i);
        }

        // Benchmark
        long start = System.nanoTime();
        int ops = 1_000_000;
        for (int i = 0; i < ops; i++) {
            cache.get(i % 10_000, k -> "value-" + k);
        }
        long elapsed = System.nanoTime() - start;

        double opsPerSec = ops / (elapsed / 1_000_000_000.0);
        System.out.printf("Throughput: %.2f ops/sec%n", opsPerSec);

        assertThat(opsPerSec).isGreaterThan(1_000_000);
    }
}
```

---

## 9. Build and Deploy

### 9.1 Build Commands

```bash
# Clean build
mvn clean install

# Skip tests
mvn clean install -DskipTests

# Build specific module
mvn clean install -pl resilio-core

# Build with dependencies
mvn clean install -pl resilio-redis -am
```

### 9.2 Project Structure

```
resilio/
├── pom.xml                    # Parent POM
├── resilio-core/
│   ├── pom.xml
│   └── src/
│       ├── main/java/         # Source code
│       └── test/java/         # Tests
├── resilio-zerogc/
│   ├── pom.xml
│   └── src/
├── resilio-aggregator/
│   ├── pom.xml
│   └── src/
├── resilio-redis/
│   ├── pom.xml
│   └── src/
└── resilio-all/
    └── pom.xml                # Aggregator only
```

### 9.3 Maven Configuration

**Parent POM properties:**

```xml
<properties>
    <java.version>17</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>5.10.2</junit.version>
    <assertj.version>3.25.3</assertj.version>
</properties>
```

**Required plugins:**
- `maven-compiler-plugin` - Java 17 compilation
- `maven-surefire-plugin` - Test execution
- `maven-jar-plugin` - JAR packaging
- `maven-source-plugin` - Source JAR
- `maven-javadoc-plugin` - Javadoc generation

### 9.4 Release Process

```bash
# Set release version
mvn versions:set -DnewVersion=1.0.0

# Build and deploy
mvn clean deploy -P release

# Tag release
git tag v1.0.0
git push origin v1.0.0
```

---

## 10. Best Practices

### 10.1 Cache Sizing

```java
// Good: Size based on memory budget
// ~256 bytes per entry for LruCache
// 100MB budget → ~400,000 entries
LruCache<String, User> cache = LruCache.<String, User>builder()
    .name("users")
    .maxSize(400_000)
    .build();

// Better: Consider value size
// If User objects are ~1KB each
// 100MB budget → ~100,000 entries
```

### 10.2 TTL Selection

```java
// Session data: Match session timeout
TtlCache<String, Session> sessions = TtlCache.<String, Session>builder()
    .ttl(Duration.ofMinutes(30))
    .build();

// Reference data: Longer TTL, lower miss rate
TtlCache<String, Country> countries = TtlCache.<String, Country>builder()
    .ttl(Duration.ofHours(24))
    .build();

// Real-time data: Short TTL, high freshness
TtlCache<String, StockPrice> prices = TtlCache.<String, StockPrice>builder()
    .ttl(Duration.ofSeconds(5))
    .build();
```

### 10.3 Circuit Breaker Tuning

```java
// Fast-fail for user-facing APIs
CircuitBreaker userApi = CircuitBreaker.builder()
    .failureThreshold(3)           // Open quickly
    .resetTimeout(Duration.ofSeconds(10))  // Recover quickly
    .build();

// More tolerance for background jobs
CircuitBreaker batchJob = CircuitBreaker.builder()
    .failureThreshold(10)          // More tolerance
    .resetTimeout(Duration.ofMinutes(5))   // Longer recovery
    .build();
```

### 10.4 Zero-GC Guidelines

```java
// DO: Register all keys at startup
void init() {
    metrics.registerKey("http.requests");
    metrics.registerKey("http.errors");
}

// DON'T: Register keys in hot path
void handleRequest() {
    // BAD: This allocates!
    int idx = metrics.registerKey("http.requests");
}

// DO: Store indices in constants
private static final int HTTP_REQUESTS;
static {
    HTTP_REQUESTS = metrics.registerKey("http.requests");
}

void handleRequest() {
    // GOOD: Zero allocation
    metrics.incrementInt(HTTP_REQUESTS);
}
```

### 10.5 Resource Cleanup

```java
// Always close resources
try (RedisCache<String, User> cache = RedisCache.<String, User>builder()
        .name("users")
        .config(config)
        .build()) {
    // Use cache
} // Automatically closed

// Or register shutdown hook
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    ResilioResourceManager.getInstance().releaseAll();
}));
```

### 10.6 Monitoring

```java
// Expose metrics for monitoring
@Scheduled(fixedRate = 60000)
void reportMetrics() {
    for (ResourceSnapshot snap : manager.getResourceSnapshots()) {
        metrics.gauge("cache.size", snap.itemCount(),
            "name", snap.name());
        metrics.gauge("cache.memory", snap.memoryBytes(),
            "name", snap.name());
    }

    CacheStats stats = cache.stats();
    metrics.gauge("cache.hit_rate", stats.hitRate());
}
```

---

## Performance Reference

| Operation | Latency (p99) | Throughput |
|-----------|--------------|------------|
| LruCache get (hit) | 0.02ms | 5M ops/sec |
| TtlCache get (hit) | 0.03ms | 3M ops/sec |
| PrimitiveArrayCache get | 0.001ms | 50M ops/sec |
| ObjectPool borrow/release | 0.001ms | 50M ops/sec |
| RedisCache get (localhost) | 0.5ms | 100K ops/sec |
| RedisCache get (network) | 1-5ms | 20K ops/sec |
| EventAggregator record | 0.01ms | 10M ops/sec |

---

## Summary

RESILIO provides a comprehensive caching solution for Java applications:

1. **Start simple** with `resilio-core` for local caching
2. **Add resilience** with circuit breakers when calling external services
3. **Go distributed** with `resilio-redis` for multi-instance deployments
4. **Optimize hot paths** with `resilio-zerogc` for latency-critical code
5. **Reduce writes** with `resilio-aggregator` for high-volume events

The modular design lets you adopt capabilities incrementally while maintaining minimal dependencies.
