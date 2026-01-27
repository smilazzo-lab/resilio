# RESILIO

**High-Performance Resilient Caching Framework for Java**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/io.resilio/resilio-core.svg)](https://search.maven.org/search?q=g:io.resilio)

> *resilio* (Latin): to spring back, to rebound — representing the framework's core philosophy of resilience and recovery.

---

## Overview

RESILIO is a production-ready caching framework designed for high-performance Java applications. It provides a unified API for local and distributed caching with built-in resilience patterns, zero-GC optimizations, and comprehensive observability.

**Key Characteristics:**

- **Production-Tested**: Born from real-world systems handling thousands of concurrent users
- **Modular Design**: Use only what you need — from simple local caches to distributed Redis clusters
- **Zero Dependencies**: Core module has no external dependencies
- **Type-Safe**: Full generics support with compile-time safety
- **Thread-Safe**: All components designed for high concurrency
- **Observable**: Built-in metrics for monitoring and debugging

---

## Table of Contents

- [Modules](#modules)
- [Quick Start](#quick-start)
- [Installation](#installation)
- [User Guide](#user-guide)
  - [Local Caching](#local-caching)
  - [Distributed Caching with Redis](#distributed-caching-with-redis)
  - [Circuit Breaker](#circuit-breaker)
  - [Zero-GC Caching](#zero-gc-caching)
  - [Event Aggregation](#event-aggregation)
- [Configuration Reference](#configuration-reference)
- [Monitoring & Statistics](#monitoring--statistics)
- [Best Practices](#best-practices)
- [API Reference](#api-reference)
- [Contributing](#contributing)
- [License](#license)

---

## Modules

| Module | Description | Dependencies |
|--------|-------------|--------------|
| **resilio-core** | Local caches (LRU, TTL), circuit breaker, statistics | None |
| **resilio-zerogc** | Zero-allocation caching for GC-sensitive hot paths | resilio-core |
| **resilio-aggregator** | High-volume event aggregation with batching | resilio-core |
| **resilio-redis** | Distributed Redis cache, Pub/Sub, Streams | resilio-core, Lettuce, commons-pool2 |

### Module Dependency Graph

```
resilio-core (no dependencies)
    ├── resilio-zerogc
    ├── resilio-aggregator
    └── resilio-redis
            ├── lettuce-core
            └── commons-pool2
```

---

## Quick Start

### 5-Minute Example

```java
import io.resilio.core.cache.LruCache;
import io.resilio.core.cache.TtlCache;
import java.time.Duration;

public class QuickStart {
    public static void main(String[] args) {
        // 1. Simple LRU Cache (fixed size, no expiration)
        LruCache<String, String> lruCache = LruCache.<String, String>builder()
            .name("my-cache")
            .maxSize(1000)
            .build();

        // Get with loader (cache-aside pattern)
        String value = lruCache.get("key", k -> expensiveComputation(k));

        // 2. TTL Cache (time-based expiration)
        TtlCache<String, User> userCache = TtlCache.<String, User>builder()
            .name("users")
            .ttl(Duration.ofMinutes(30))
            .maxSize(10000)
            .build();

        User user = userCache.get("user:123", id -> userService.findById(id));

        // 3. Check statistics
        System.out.printf("Hit rate: %.1f%%%n", lruCache.stats().hitRate() * 100);
    }
}
```

---

## Installation

### Maven

```xml
<!-- Core module (required) -->
<dependency>
    <groupId>io.resilio</groupId>
    <artifactId>resilio-core</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Zero-GC module (optional) -->
<dependency>
    <groupId>io.resilio</groupId>
    <artifactId>resilio-zerogc</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Event Aggregator module (optional) -->
<dependency>
    <groupId>io.resilio</groupId>
    <artifactId>resilio-aggregator</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Redis module (optional) -->
<dependency>
    <groupId>io.resilio</groupId>
    <artifactId>resilio-redis</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.resilio:resilio-core:1.0.0'
implementation 'io.resilio:resilio-zerogc:1.0.0'      // optional
implementation 'io.resilio:resilio-aggregator:1.0.0' // optional
implementation 'io.resilio:resilio-redis:1.0.0'      // optional
```

### Requirements

- **Java 17** or higher
- **Redis 6+** (only for resilio-redis module)

---

## User Guide

### Local Caching

#### LRU Cache

The `LruCache` maintains a fixed number of entries, evicting the least recently used when full. Ideal for:
- Parsed SQL statements
- Compiled regex patterns
- Configuration lookups
- Any data with uniform access patterns

```java
import io.resilio.core.cache.LruCache;

// Create cache with builder
LruCache<String, ParsedQuery> queryCache = LruCache.<String, ParsedQuery>builder()
    .name("sql-cache")       // Name for monitoring
    .maxSize(256)            // Maximum entries
    .build();

// Get with automatic loading (cache-aside pattern)
ParsedQuery query = queryCache.get(sql, SqlParser::parse);

// Get without loading (returns Optional)
Optional<ParsedQuery> cached = queryCache.getIfPresent(sql);

// Manual put
queryCache.put("SELECT * FROM users", parsedQuery);

// Invalidation
queryCache.invalidate("SELECT * FROM users");
queryCache.invalidateAll();

// Get all keys (snapshot)
Set<String> keys = queryCache.keys();

// Statistics
CacheStats stats = queryCache.stats();
System.out.println("Hits: " + stats.hitCount());
System.out.println("Misses: " + stats.missCount());
System.out.println("Hit Rate: " + stats.hitRate());
System.out.println("Size: " + queryCache.size());
```

#### TTL Cache

The `TtlCache` automatically expires entries after a configurable time-to-live. Ideal for:
- User sessions
- API responses
- Temporary tokens
- Any time-sensitive data

```java
import io.resilio.core.cache.TtlCache;
import java.time.Duration;

// Create TTL cache
TtlCache<String, Session> sessionCache = TtlCache.<String, Session>builder()
    .name("sessions")
    .ttl(Duration.ofMinutes(30))    // Entries expire after 30 minutes
    .maxSize(50000)                  // Maximum entries (memory protection)
    .build();

// Usage is identical to LruCache
Session session = sessionCache.get(sessionId, id -> sessionService.load(id));

// Check if expired
Optional<Session> active = sessionCache.getIfPresent(sessionId);
```

### Distributed Caching with Redis

#### Basic Redis Cache

```java
import io.resilio.redis.cache.RedisCache;
import io.resilio.redis.connection.*;
import io.resilio.redis.serializer.*;
import java.time.Duration;

// 1. Configure connection
RedisCacheConfig config = RedisCacheConfig.builder()
    .host("localhost")
    .port(6379)
    .password("secret")           // Optional
    .database(0)                  // Redis DB number
    .keyPrefix("myapp:")          // Namespace isolation
    .ssl(false)                   // Enable for production
    .connectionTimeout(Duration.ofSeconds(5))
    .poolMinIdle(2)
    .poolMaxIdle(8)
    .poolMaxTotal(16)
    .build();

// 2. Create connection manager (reusable across caches)
RedisConnectionManager connectionManager = new RedisConnectionManager(config);

// 3. Create cache with serializers
RedisCache<String, User> userCache = new RedisCache<>(
    connectionManager,
    new StringSerializer(),                    // Key serializer
    new JacksonSerializer<>(User.class),       // Value serializer (JSON)
    Duration.ofHours(1)                        // TTL
);

// 4. Use the cache
userCache.put("user:123", user);
User cached = userCache.get("user:123");
boolean removed = userCache.remove("user:123");

// 5. Custom TTL per entry
userCache.put("user:456", adminUser, Duration.ofDays(7));

// 6. Clean shutdown
connectionManager.close();
```

#### Redis Pub/Sub

For distributed cache invalidation or real-time messaging:

```java
import io.resilio.redis.pubsub.RedisPubSub;

// Create Pub/Sub instance
RedisPubSub pubSub = new RedisPubSub(connectionManager);

// Subscribe to channel
pubSub.subscribe("cache-invalidation", (channel, message) -> {
    System.out.println("Received on " + channel + ": " + message);
    localCache.invalidate(message);
});

// Subscribe to pattern
pubSub.psubscribe("events.*", (channel, message) -> {
    System.out.println("Event from " + channel + ": " + message);
});

// Publish message
long receivers = pubSub.publish("cache-invalidation", "user:123");
System.out.println("Message delivered to " + receivers + " subscribers");

// Cleanup
pubSub.unsubscribe("cache-invalidation");
pubSub.close();
```

#### Redis Streams

For reliable message queues with consumer groups:

```java
import io.resilio.redis.stream.*;
import java.util.Map;

// Create stream
RedisStream stream = new RedisStream(connectionManager, "audit-events");

// Create consumer group (idempotent)
stream.createGroup("processors", "0");  // "0" = read from beginning

// Add message to stream
String messageId = stream.add(Map.of(
    "type", "USER_LOGIN",
    "userId", "123",
    "timestamp", Instant.now().toString()
));

// Add with max length (memory protection)
stream.add(Map.of("event", "data"), 10000);  // Keep max 10000 messages

// Read messages with consumer group
List<StreamMessage> messages = stream.readGroup(
    "processors",           // Group name
    "consumer-1",           // Consumer name
    10,                     // Max messages
    Duration.ofSeconds(2)   // Block timeout
);

for (StreamMessage msg : messages) {
    System.out.println("ID: " + msg.getId());
    System.out.println("Body: " + msg.getBody());

    // Process message...

    // Acknowledge
    stream.acknowledge("processors", msg.getId());
}

// Background consuming
stream.startConsuming("processors", "consumer-1", message -> {
    processMessage(message);
    // Auto-acknowledged after successful processing
});

// Stop consuming
stream.stopConsuming();

// Get stream length
long length = stream.length();

// Trim old messages
long trimmed = stream.trim(5000);

// Cleanup
stream.close();
```

### Circuit Breaker

Protect your application from cascading failures:

```java
import io.resilio.core.circuit.CircuitBreaker;
import java.time.Duration;

// Create circuit breaker
CircuitBreaker circuitBreaker = CircuitBreaker.builder()
    .name("external-api")
    .failureThreshold(5)                    // Open after 5 failures
    .successThreshold(3)                    // Close after 3 successes
    .resetTimeout(Duration.ofSeconds(30))   // Half-open after 30s
    .build();

// Execute with circuit breaker
try {
    String result = circuitBreaker.execute(() -> {
        return externalApi.call();
    });
} catch (CircuitBreaker.CircuitOpenException e) {
    // Circuit is open - use fallback
    return fallbackValue;
}

// Check state
CircuitBreaker.State state = circuitBreaker.getState();
// CLOSED, OPEN, or HALF_OPEN

// Statistics
System.out.println("Failures: " + circuitBreaker.getFailureCount());
System.out.println("Successes: " + circuitBreaker.getSuccessCount());

// Manual control (for testing)
circuitBreaker.reset();
```

### Zero-GC Caching

For latency-critical hot paths where GC pauses are unacceptable:

#### Primitive Array Cache

```java
import io.resilio.zerogc.PrimitiveArrayCache;

// Create cache with fixed capacity
PrimitiveArrayCache cache = PrimitiveArrayCache.builder()
    .name("permissions")
    .capacity(10000)        // Fixed slot count
    .build();

// Phase 1: Registration (allocates memory - do at startup)
int userSlot = cache.registerKey("user:123");
int adminSlot = cache.registerKey("admin:456");

// Phase 2: Hot path (zero allocation)
cache.putLong(userSlot, 0b1111_0000L);    // Store permissions bitmap
long perms = cache.getLong(userSlot);      // Retrieve (no allocation)

cache.putInt(userSlot, 42);
int count = cache.getInt(userSlot);

cache.putDouble(userSlot, 3.14159);
double value = cache.getDouble(userSlot);

// Check if slot is set
boolean hasValue = cache.isSet(userSlot);

// Clear slot
cache.clear(userSlot);

// Statistics
System.out.println("Registered keys: " + cache.getRegisteredCount());
System.out.println("Capacity: " + cache.getCapacity());
```

#### Object Pool

Reuse objects to avoid allocation in loops:

```java
import io.resilio.zerogc.ObjectPool;

// Create pool
ObjectPool<StringBuilder> pool = ObjectPool.<StringBuilder>builder()
    .name("string-builders")
    .factory(StringBuilder::new)              // How to create new
    .reset(sb -> sb.setLength(0))             // How to reset for reuse
    .maxSize(100)                             // Pool capacity
    .build();

// Option 1: Try-with-resources (recommended)
try (ObjectPool.Handle<StringBuilder> handle = pool.borrowHandle()) {
    StringBuilder sb = handle.get();
    sb.append("Hello").append(" ").append("World");
    String result = sb.toString();
    // Automatically returned to pool
}

// Option 2: Manual borrow/release
StringBuilder sb = pool.borrow();
try {
    sb.append("data");
    // use it...
} finally {
    pool.release(sb);  // Return to pool
}

// Statistics
System.out.println("Pool size: " + pool.getPoolSize());
System.out.println("Borrows: " + pool.getBorrowCount());
System.out.println("Returns: " + pool.getReturnCount());
System.out.println("Creates: " + pool.getCreateCount());
```

### Event Aggregation

Reduce write amplification for high-volume events:

```java
import io.resilio.aggregator.EventAggregator;
import java.time.Duration;

// Define your event and summary types
record AuditEvent(String sessionId, String action, Instant timestamp) {}

class SessionSummary {
    String sessionId;
    List<String> actions = new ArrayList<>();
    int eventCount = 0;
    Instant firstEvent;
    Instant lastEvent;

    void addEvent(AuditEvent event) {
        if (firstEvent == null) firstEvent = event.timestamp();
        lastEvent = event.timestamp();
        actions.add(event.action());
        eventCount++;
    }
}

// Create aggregator
EventAggregator<AuditEvent, SessionSummary> aggregator = EventAggregator
    .<AuditEvent, SessionSummary>builder()
    .name("audit-aggregator")
    .keyExtractor(AuditEvent::sessionId)           // Group by session
    .summaryFactory(SessionSummary::new)           // Create new summary
    .accumulator((summary, event) -> summary.addEvent(event))
    .flushInterval(Duration.ofMinutes(5))          // Flush every 5 min
    .maxEventsBeforeFlush(1000)                    // Or after 1000 events
    .maxBuckets(5000)                              // Memory protection
    .onFlush(summary -> auditRepository.save(summary))
    .build();

// Record events (aggregated automatically)
aggregator.record(new AuditEvent("session-1", "LOGIN", Instant.now()));
aggregator.record(new AuditEvent("session-1", "VIEW_PAGE", Instant.now()));
aggregator.record(new AuditEvent("session-1", "CLICK_BUTTON", Instant.now()));
// These 3 events become 1 summary write!

// Manual flush (e.g., on shutdown)
aggregator.flushAll();

// Statistics
System.out.println("Events recorded: " + aggregator.getEventsRecorded());
System.out.println("Summaries flushed: " + aggregator.getSummariesFlushed());
System.out.println("Aggregation ratio: " + aggregator.getAggregationRatio());
// Example: 1000 events → 50 summaries = 20:1 ratio

// Clean shutdown (flushes remaining)
aggregator.shutdown();
```

---

## Configuration Reference

### RedisCacheConfig

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `host` | String | localhost | Redis server hostname |
| `port` | int | 6379 | Redis server port |
| `password` | String | null | Authentication password |
| `database` | int | 0 | Redis database number (0-15) |
| `ssl` | boolean | false | Enable TLS/SSL |
| `keyPrefix` | String | "" | Prefix for all keys |
| `connectionTimeout` | Duration | 5s | Connection timeout |
| `poolMinIdle` | int | 2 | Minimum idle connections |
| `poolMaxIdle` | int | 8 | Maximum idle connections |
| `poolMaxTotal` | int | 16 | Maximum total connections |

### Sentinel Configuration

```java
RedisCacheConfig config = RedisCacheConfig.builder()
    .sentinelMasterId("mymaster")
    .sentinelNodes(new String[]{
        "sentinel1:26379",
        "sentinel2:26379",
        "sentinel3:26379"
    })
    .password("secret")
    .build();
```

---

## Monitoring & Statistics

All RESILIO components implement `CacheStats` for unified monitoring:

```java
public interface CacheStats {
    long hitCount();       // Number of cache hits
    long missCount();      // Number of cache misses
    long evictionCount();  // Number of evictions (LRU/TTL)
    long size();           // Current number of entries

    default double hitRate() {
        long total = hitCount() + missCount();
        return total == 0 ? 0.0 : (double) hitCount() / total;
    }

    void reset();          // Reset all counters
}
```

### Integration with Micrometer

```java
import io.micrometer.core.instrument.MeterRegistry;

public void registerMetrics(MeterRegistry registry, LruCache<?, ?> cache) {
    CacheStats stats = cache.stats();

    registry.gauge("cache.size", cache, c -> c.size());
    registry.gauge("cache.hit.rate", stats, s -> s.hitRate());

    registry.counter("cache.hits", "cache", cache.name())
        .increment(stats.hitCount());
    registry.counter("cache.misses", "cache", cache.name())
        .increment(stats.missCount());
}
```

---

## Best Practices

### 1. Choose the Right Cache Type

| Use Case | Recommended |
|----------|-------------|
| Parsed SQL, compiled patterns | `LruCache` |
| User sessions, API tokens | `TtlCache` |
| Distributed data | `RedisCache` |
| Hot-path permissions | `PrimitiveArrayCache` |
| High-volume audit logs | `EventAggregator` |

### 2. Size Your Caches Appropriately

```java
// Rule of thumb: estimate memory per entry
int estimatedEntrySize = 500; // bytes
int targetMemoryMB = 100;
int maxSize = (targetMemoryMB * 1024 * 1024) / estimatedEntrySize;

LruCache<String, Data> cache = LruCache.<String, Data>builder()
    .maxSize(maxSize)
    .build();
```

### 3. Use Connection Manager Sharing

```java
// DO: Share connection manager across caches
RedisConnectionManager manager = new RedisConnectionManager(config);
RedisCache<String, User> userCache = new RedisCache<>(manager, ...);
RedisCache<String, Order> orderCache = new RedisCache<>(manager, ...);

// DON'T: Create separate managers
RedisCache<String, User> userCache = RedisCache.builder()
    .config(config)  // Creates new manager
    .build();
```

### 4. Handle Cache Failures Gracefully

```java
public User getUser(String id) {
    try {
        return userCache.get(id, this::loadFromDb);
    } catch (Exception e) {
        log.warn("Cache failure, falling back to DB", e);
        return loadFromDb(id);
    }
}
```

### 5. Use Circuit Breaker for External Caches

```java
CircuitBreaker cb = CircuitBreaker.builder()
    .failureThreshold(3)
    .resetTimeout(Duration.ofSeconds(10))
    .build();

User user = cb.execute(() -> redisCache.get(id, this::loadFromDb));
```

---

## Performance

Benchmark results on typical hardware (Intel i7, 32GB RAM):

| Operation | Latency (p99) | Throughput |
|-----------|---------------|------------|
| LruCache get (hit) | 0.02ms | 5M ops/sec |
| TtlCache get (hit) | 0.03ms | 3M ops/sec |
| PrimitiveArrayCache get | 0.001ms | 50M ops/sec |
| ObjectPool borrow/release | 0.001ms | 50M ops/sec |
| RedisCache get (localhost) | 0.5ms | 100K ops/sec |
| RedisCache get (network) | 1-5ms | 20K ops/sec |

---

## API Reference

Full Javadoc available at: https://resilio.io/apidocs

### Core Interfaces

- `Cache<K, V>` - Base cache interface
- `CacheStats` - Statistics interface
- `CircuitBreaker` - Resilience pattern

### Implementations

- `LruCache<K, V>` - Fixed-size LRU eviction
- `TtlCache<K, V>` - Time-based expiration
- `RedisCache<K, V>` - Distributed Redis cache
- `PrimitiveArrayCache` - Zero-GC primitive storage
- `ObjectPool<T>` - Object reuse pool
- `EventAggregator<E, S>` - Event batching

---

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Building from Source

```bash
git clone https://github.com/resilio-cache/resilio.git
cd resilio
mvn clean install
```

### Running Tests

```bash
mvn test                           # Unit tests
mvn verify -P integration-tests    # Integration tests (requires Redis)
```

---

## License

```
Copyright 2024 Salvatore Milazzo

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## Author

**Salvatore Milazzo**
Email: milazzosa@gmail.com
GitHub: [@milazzosa](https://github.com/milazzosa)

---

<p align="center">
  <strong>RESILIO</strong> — Because your cache should bounce back.
</p>
