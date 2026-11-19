# Concurrency and Thread Safety

What the code does today to stay correct when many requests run at the same time, and what is still missing. The gateway sections are planned and will be updated when the code exists.

Context: Spring MVC on Tomcat uses **one thread per request**, and every controller, service and filter is a **singleton shared by all those threads**. Several API instances also share one MongoDB and one Redis. So there are two levels of concurrency: threads inside one instance, and instances against shared data.

## 1. Used today

### 1.1 Stateless, immutable singletons (thread safety by design)
- **Where:** `GameController`, `GameService`, `InstanceIdFilter`, `WhoAmIController`, `CacheConfig`.
- **How:** all fields are `final` and set once in the constructor (dependencies, `instanceId`, TTLs). No mutable shared state, so no locks are needed.
- A request's data lives only in method parameters and local variables, which are private to its thread.

### 1.2 Unique compound index in MongoDB
- **Where:** `Game` – `@CompoundIndex(name = "uq_title_dev_active", unique = true)` on `title + developer + deletedAt`, case-insensitive (collation strength 2). `spring.data.mongodb.auto-index-creation=true` makes Spring create it (this is off by default; before it was enabled the index did not exist at all).
- **How:** `GameService.create` first calls `existsBy...` and then `save`. That check-then-act is not atomic, so two parallel requests can both pass the check. The unique index is the real guard: MongoDB enforces it atomically, so only one insert wins. The loser gets a `DuplicateKeyException`, which `ErrorHandler` maps to **409**.
- **Why `deletedAt` is in the key:** active games have no `deletedAt`, so they must be unique; a soft-deleted game has a timestamp, so it does not block creating the same game again.
- **Tested:** 20 parallel creates of the same game (mixed upper/lower case) gave 1×201 and 19×409.

### 1.2b Optimistic locking (`@Version`)
- **Where:** `Game.version`; `ErrorHandler` maps `OptimisticLockingFailureException` to **409**.
- **How:** every save increments `version` and only succeeds if the stored version equals the one that was read. If another request saved in between, the save fails instead of silently overwriting. This protects the read-modify-write in `update` and `delete`, also across API instances.
- **Tested:** 20 parallel `PUT`s on one game gave 16×200 and 4×409; the stored version was 16, so no write was lost. The client retries on 409.
- **Note:** documents created before `@Version` have no `version` field and must get `version: 0` (`db.games.updateMany({version:{$exists:false}},{$set:{version:0}})`), otherwise saving them fails.

### 1.3 Redis as a shared cache between instances
- **Where:** `GameService` – `@Cacheable("games")` on `findById`, `@CacheEvict` on `update` and `delete`; `CacheConfig`.
- **How:** all API instances share one cache, so a write on instance A evicts the entry that instance B would read. Entries expire after 10 minutes (TTL), which bounds how long a stale value can survive. Redis runs commands one at a time, so single cache operations are atomic.
- **Stampede protection:** `findById` uses `@Cacheable(sync = true)`: on a miss only one thread per instance loads from MongoDB, the others wait for it and reuse the value.
- **Limits:** see section 2.

### 1.4 Thread-safe infrastructure we rely on
- The MongoDB driver's connection pool and `MongoTemplate`/repositories are thread-safe and shared by all request threads.
- The Redis client (Lettuce) is thread-safe and multiplexes many threads over a connection.
- Spring's cache abstraction handles concurrent calls to the cache manager.

### 1.5 Soft delete instead of removing documents
- **Where:** `Game.softDelete()` sets `deletedAt`; queries filter `DeletedAtIsNull`.
- **How:** a delete never removes a row another request may be reading, so readers see either the old or the new state, never a half-deleted one.

### 1.6 Gateway (`gateway/`)
Every client request runs on its own virtual thread; all gateway components are singletons shared by those threads.

| Where | Mechanism | Why |
|-------|-----------|-----|
| `RoundRobinBalancer` | `AtomicInteger.getAndIncrement` + `floorMod` | Lock-free: every thread gets a unique ticket, so the split is exact. **Tested:** 16 threads × 3000 picks → exactly 16 000 per backend. |
| `LeastConnectionsBalancer`, `Backend` | `AtomicInteger active`, `AtomicLong` counters, `volatile healthy` | Many threads count in-flight requests; health changes are visible at once to all threads. |
| `BackendClient` | Apache HttpClient `PoolingHttpClientConnectionManager` | Thread-safe pool: a keep-alive connection is leased by one thread at a time and returned after the response is read. |
| `ProxyService` coalescing | `ConcurrentHashMap.putIfAbsent(key, CompletableFuture)` | Only the first thread that misses a key calls the API; the others `join()` its future. The leader stores the answer in Redis *before* removing the future, so late threads find it cached. **Tested:** 20 concurrent misses → 1 backend call. |
| `RedisResponseCache` | Generation counter with Redis `INCR`; `SET key value EX ttl` | Atomic in Redis, also across several gateways. The generation is read before the backend call, so a GET racing with a write stores its answer under the old generation and it is never served. |
| `GatewayStats` | `LongAdder` | Cheap increments under heavy contention. |
| `HealthChecker` | One scheduled thread; writes only `volatile` / atomic fields | Request threads read the health flags without locks. |

**Tested:** 300 parallel requests → exactly 100/100/100 per instance, `active` back to 0 everywhere.

## 2. Known limits

- **Small stale-cache window.** `@CacheEvict` runs after the method. A `GET` that read the old row from MongoDB just before an update can write it into Redis just after the evict, and it stays until the 10 minute TTL ends. `sync = true` does not close this, and the lock behind it is per instance, not across instances. A full fix needs versioned cache keys or a distributed lock; for now the TTL is the safety net.
- **Soft-deleted games** with the same title and developer pile up (one document per delete), which is intended.

Fixed earlier gaps: lost updates on `PUT` (1.2b), cache stampede (1.3), create race returning 500 (1.2).

## 3. Planned (gateway and replication)

| Task | Mechanism | Purpose |
|------|-----------|---------|
| Priority (Task 3) | `PriorityBlockingQueue` + fixed worker pool (`ExecutorService`) | Thread-safe hand-off from request threads to workers, ordered by priority then arrival; a counter (`AtomicLong`) gives the arrival order. |
| Starvation guard (Task 3) | Aging of waiting requests | Low priority requests are not delayed forever. |
| Request journal (Task 4) | `ConcurrentHashMap<requestId, entry>` with `compute` for state changes | Many threads record and update requests without locks; each state change is atomic per entry. |
| Circuit breaker (Task 4) | Resilience4j (atomic state machine) or `AtomicReference<State>` + `AtomicInteger` failure counter | Safe state transitions CLOSED/OPEN/HALF_OPEN; only one trial request in HALF_OPEN. |
| Timeouts and retries (Task 4) | `CompletableFuture` with timeout | A request never blocks a worker forever. |
| DB sync (Task 5) | MongoDB replica set: primary takes writes, secondaries copy them via the oplog | Keeps the 3 databases consistent; write concern `majority` makes a write count only when most nodes have it, so a failover does not lose acknowledged writes. |

## 4. How we will test it
- Parallel `create` of the same game (many threads, one `CountDownLatch` start signal): exactly one 201, the rest 409.
- Parallel `update` of the same game: exactly one succeeds per version, the others get 409.
- Priority queue and journal: stress test with many threads and a check that no entry is lost or duplicated.
- Circuit breaker: concurrent failures open the circuit exactly once.
