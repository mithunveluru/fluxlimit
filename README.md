# FluxLimit

**Distributed API rate limiting for Java 21 — the same five-line API from a single JVM to a Redis-backed fleet.**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mithunveluru/fluxlimit-core)](https://central.sonatype.com/artifact/io.github.mithunveluru/fluxlimit-core)
[![build](https://github.com/mithunveluru/fluxlimit/actions/workflows/build.yml/badge.svg)](https://github.com/mithunveluru/fluxlimit/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
![Java](https://img.shields.io/badge/java-21%2B-orange)

Token Bucket and Sliding Window Counter · in-memory or Redis · zero-dependency core ·
Spring Boot starter · honest failure semantics · Micrometer metrics.

> **Status: pre-release (0.x).** The API may still move before 1.0.

## Why FluxLimit

Rate limiting looks trivial until the second server. One JVM can count requests in a map;
a fleet needs *one* shared decision per request — atomic, fast, and correct while servers
disagree about the time. Most Java options give you one half: embedded libraries
(Guava, Resilience4j) don't share state, and wiring Redis yourself means Lua, clock skew,
and failure semantics become your problem.

FluxLimit's position: **the algorithm executes inside the store.** In memory that's one
atomic map operation; on Redis it's one Lua script in one round trip, timestamped by the
*Redis server clock* — so there is no read-modify-write race and no clock-skew handling
anywhere. Swapping single-JVM for distributed is one builder line, with identical
semantics.

The design philosophy is deliberate minimalism: two algorithms that cover the practical
design space, one storage SPI, no framework lock-in, and failure behavior you choose
explicitly instead of discovering during an outage.

**Alternatives, honestly:** [Bucket4j](https://github.com/bucket4j/bucket4j) is mature with
many storage backends (token bucket only) — pick it when you need a backend FluxLimit
doesn't have. [Resilience4j RateLimiter](https://resilience4j.readme.io) is client-side
self-throttling — not keyed per user, not distributed. Guava `RateLimiter` smooths one hot
path in one JVM. FluxLimit is for **per-key limits shared across a fleet**.

## Features

- **Token Bucket** — controlled bursts, continuous refill, exact integer math (no float drift)
- **Sliding Window Counter** — smooth limiting with a bounded error that skews conservative
- **In-memory store** — `ConcurrentHashMap`-based, ~4.9M checks/s, self-cleaning
- **Redis store** — one atomic Lua round trip per check; standalone, Sentinel, and Cluster
- **Spring Boot starter** — `@RateLimit` annotation, SpEL keys, auto-configuration
- **Zero-dependency core** — `fluxlimit-core` has no runtime dependencies at all
- **Weighted permits** — expensive endpoints can cost more than one permit
- **Standard headers** — `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, `Retry-After`
- **Fail-open or fail-closed** — a dead Redis never takes your API down, and never silently
- **Micrometer metrics** — requests, store failures, store latency; optional dependency
- **Thread-safe by construction** — one shared limiter serves all threads, no locks in your code

## Architecture

```
      your code  /  @RateLimit
              │
              ▼
        RateLimiter            key validation & prefixing, failure
              │                policy, cooldown, metrics
              ▼
      RateLimiterStore         SPI: one atomic state transition per check
        │           │
        ▼           ▼
  InMemoryStore   RedisStore
  map compute()   Lua via EVALSHA, one round trip,
  per key         Redis server clock
```

- **`RateLimiter`** is the public API: a builder, two `tryAcquire` methods, a result record.
- **`RateLimiterStore`** is the one seam: *execute one rate-limit state transition atomically*.
  Everything distributed-systems-hard lives behind it.
- **Stores** run the algorithm math inside their native atomicity primitive — per-key
  `compute` in memory, serialized Lua execution in Redis. There is no
  read-then-write anywhere, so two servers can never double-spend a permit.

## Quick start

Gradle:

```kotlin
implementation("io.github.mithunveluru:fluxlimit-core:0.1.0")
```

Maven:

```xml
<dependency>
  <groupId>io.github.mithunveluru</groupId>
  <artifactId>fluxlimit-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
import io.fluxlimit.RateLimitResult;
import io.fluxlimit.RateLimiter;
import java.time.Duration;

RateLimiter limiter = RateLimiter.builder()
    .tokenBucket(100, Duration.ofMinutes(1))   // or .slidingWindow(100, Duration.ofMinutes(1))
    .build();                                  // in-memory store by default

RateLimitResult result = limiter.tryAcquire("user:" + userId);
if (!result.allowed()) {
    // reject with 429; result.retryAfter() fills the Retry-After header
}
```

That's the whole API. `tryAcquire` never blocks and never throws for a denial — denial is a
result. The 101st request inside a minute returns `allowed() == false` with `remaining() == 0`
and a `retryAfter()` saying exactly how long until a retry can succeed. Weighted endpoints
use `tryAcquire(key, permits)`.

One limiter instance is shared by all threads, like an HTTP client.

## Spring Boot

```kotlin
implementation("io.github.mithunveluru:fluxlimit-spring-boot-starter:0.1.0")
```

```java
@RateLimit(key = "#{principal.name}")          // SpEL; default key is the client IP
@GetMapping("/api/search")
SearchResult search(@RequestParam String q) { ... }
```

```properties
fluxlimit.algorithm=token-bucket    # or sliding-window
fluxlimit.limit=100
fluxlimit.period=60s
```

Requests over the limit receive **HTTP 429** before your handler's arguments are even
resolved. Every limited response carries `RateLimit-Limit` and `RateLimit-Remaining`;
a 429 adds `RateLimit-Reset` and `Retry-After` (seconds).

The SpEL template sees `request` (the `HttpServletRequest`) and `principal` — e.g.
`#{request.getHeader('X-Api-Key')}`. An empty key falls back to `request.getRemoteAddr()`;
forwarding headers are never trusted implicitly. Expensive endpoints declare their cost with
`@RateLimit(permits = 5)`.

All properties:

| Property | Default | Meaning |
|---|---|---|
| `fluxlimit.enabled` | `true` | turn the auto-configuration off entirely |
| `fluxlimit.algorithm` | `token-bucket` | `token-bucket` or `sliding-window` |
| `fluxlimit.limit` | `100` | bucket capacity / window limit |
| `fluxlimit.period` | `60s` | refill period / window length |
| `fluxlimit.failure-policy` | `allow` | `allow` or `deny` while the store is down |
| `fluxlimit.key-prefix` | `fluxlimit:` | namespace prepended to every key |
| `fluxlimit.redis.enabled` | `false` | use Redis instead of in-memory |
| `fluxlimit.redis.uri` | — | e.g. `redis://localhost:6379` |
| `fluxlimit.redis.command-timeout` | `50ms` | slower answers count as store failures |

Every auto-configured bean backs off to one you define. If a `MeterRegistry` bean exists,
metrics are bound automatically.

## Redis: going distributed

One in-memory limiter per server means N servers enforce N× your limit. Backing the same
code with Redis gives the whole fleet **one shared budget** — every check is one atomic
Lua execution on one authoritative clock.

```kotlin
implementation("io.github.mithunveluru:fluxlimit-redis:0.1.0")
```

```java
RedisClient client = RedisClient.create("redis://localhost:6379");
StatefulRedisConnection<String, String> connection = client.connect();  // yours: you close it

RateLimiter limiter = RateLimiter.builder()
    .slidingWindow(1000, Duration.ofMinutes(1))
    .store(RedisStore.create(connection))
    .failurePolicy(FailurePolicy.ALLOW)        // the default, stated for clarity
    .build();
```

In Spring Boot it's two properties (`fluxlimit.redis.enabled=true` plus the URI) — the code
doesn't change.

What you get, concretely:

- **One round trip per check** — `EVALSHA`, no retries, no transactions, no locks.
- **No clock skew** — scripts read Redis `TIME`; application server clocks are never consulted.
- **Self-bounding memory** — every key carries a `PEXPIRE` to the moment its state becomes
  indistinguishable from absent; idle keys delete themselves.
- **Survivable failure** — if Redis is unreachable or slower than `commandTimeout` (default
  50 ms), the limiter answers from your `FailurePolicy`, flags the result `degraded()`, and
  re-probes once per second. A dead Redis costs the fleet ~one timeout per second, not 50 ms
  per request. Cluster and Sentinel are supported; see the [ops guide](docs/redis.md).

## Algorithms

**Token Bucket** — a bucket holds up to `capacity` tokens and refills continuously. Requests
consume tokens; an empty bucket denies. Bursts up to `capacity` are allowed by design.
Counts are stored as scaled integers (micro-tokens), never floats, so a bucket refilling
1 token per 2 s admits exactly one request per 2 s, forever. *Choose it when clients
legitimately burst* — batch jobs, retries, page loads firing several calls.

**Sliding Window Counter** — two counters per key (current and previous window); the previous
window contributes proportionally to its remaining overlap. Estimation error is bounded
(≤ 2× limit only under an adversarial burst pattern, near-exact for steady traffic) and
deliberately rounds toward denying. *Choose it when bursts are unwanted* and "100 per
minute" must never mean 200 in any 60-second span around a window boundary.

Both algorithms exist twice — Java for in-memory, Lua for Redis — with the same semantics.
Derivations and accuracy proofs: [docs/algorithms.md](docs/algorithms.md).

## How it works

The life of one request:

```
tryAcquire("user:42")
   │
   ├─ validate key & permits, prepend prefix        (caller thread, ~ns)
   │
   ├─ store cooling down after a failure?  ──yes──▶ answer from FailurePolicy,
   │                                                flagged degraded
   ▼
store.tryConsume("fluxlimit:user:42", 1, config)
   │
   ├─ InMemory: map.compute(key) — refill math + consume, atomic per key
   └─ Redis:    EVALSHA lua(key) — same math on the Redis clock, atomic per key
   │
   ▼
RateLimitResult { allowed, remaining, limit, retryAfter, degraded }
```

No background threads touch your request path (the in-memory store sweeps stale keys once
a minute on one daemon thread). No allocation beyond the result record and key prefix.
Store failures are absorbed, counted, logged once per episode — and never thrown at you.

## Project structure

| Module | Coordinates | What's inside | Runtime deps |
|---|---|---|---|
| `fluxlimit-core` | `io.github.mithunveluru:fluxlimit-core` | API, both algorithms, in-memory store, storage SPI | **none** |
| `fluxlimit-redis` | `io.github.mithunveluru:fluxlimit-redis` | `RedisStore` + the Lua scripts | Lettuce |
| `fluxlimit-spring-boot-starter` | `io.github.mithunveluru:fluxlimit-spring-boot-starter` | auto-configuration, `@RateLimit`, interceptor | Spring Boot, Micrometer |
| `benchmarks` | not published | JMH suites incl. Bucket4j/Resilience4j comparison | — |
| `examples/plain-java` | not published | 40-line framework-free demo | — |
| `examples/spring-boot` | not published | `@RateLimit` demo app + k6 load script | — |

## Performance

JMH on JDK 21 (method, caveats, and raw output in [docs/benchmarks.md](docs/benchmarks.md)):

| Scenario | Result |
|---|---|
| In-memory, single thread | ~4.9M checks/s (~0.2 µs/op) |
| In-memory, 8 threads on one hot key | ~3.5M checks/s |
| Contended vs Bucket4j `LocalBucket` | ~4.0M vs ~2.4M ops/s |
| Allocation per check | 192 B, steady state, young-gen only |
| Redis (loopback), p50 / p99 | 0.25 ms / 2.2 ms — one round trip per check |

Reproduce with `./gradlew :benchmarks:jmh` (add `-PredisBench` with a local Redis for the
Redis suite).

## Compatibility

| | Minimum |
|---|---|
| Java | 21 |
| Redis | 6.2 (7.x recommended) |
| Spring Boot | 3.x |
| Lettuce | 6.x |

## Documentation

- [docs/algorithms.md](docs/algorithms.md) — the math, with accuracy-bound derivations
- [docs/redis.md](docs/redis.md) — operations guide: topologies, memory, failure behavior, tuning
- [docs/benchmarks.md](docs/benchmarks.md) — benchmark method and honest caveats
- [examples/](examples/) — runnable plain-Java and Spring Boot apps
- Javadoc on every public type ([browse on javadoc.io](https://javadoc.io/doc/io.github.mithunveluru/fluxlimit-core))

## Roadmap

Planned extensions, each with a designed-in seam in the current API:

- Additional stores (Hazelcast, DynamoDB, Postgres) via the existing `RateLimiterStore` SPI
- Client-side token caching decorator for very high throughput (claim batches locally, sync to Redis)
- Async API (`tryAcquireAsync`) — Lettuce is already async underneath
- More algorithms (e.g. GCRA) as new `AlgorithmConfig` members
- Composite limiters for multi-tier policies (per-user *and* per-tenant)
- Spring Boot health indicator and tracing

Multi-region: the position is per-region independent limits; true global coordination is a
different product.

## Contributing

Issues and PRs welcome. To work on the code:

```bash
git clone https://github.com/mithunveluru/fluxlimit.git
cd fluxlimit
./gradlew build          # compiles, tests, checks format
```

- JDK 21 required; **Docker** required for the Redis integration tests (Testcontainers)
- Formatting is enforced (google-java-format) — run `./gradlew spotlessApply` before committing
- Behavior changes need a test; algorithm changes should keep the property-based tests green
- For anything non-trivial, open an issue first to agree on scope

## License

[Apache-2.0](LICENSE)
