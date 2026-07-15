# FluxLimit

Distributed API rate limiting for Java 21 — in-memory or Redis-backed, framework-optional,
zero dependencies in the core.

> **Status: pre-release (0.x).** API may still move before 1.0.

- **Two algorithms**: Token Bucket (controlled bursts) and Sliding Window Counter (smooth) —
  [the math](docs/algorithms.md)
- **Two stores, one API**: single-JVM in-memory, or Redis shared across a fleet — one atomic Lua
  round trip per check, on the *Redis server clock*, so there is no clock skew to mitigate —
  [ops guide](docs/redis.md)
- **Never takes your API down**: store failures answer from a configurable fail-open/fail-closed
  policy with a 1 s probe cooldown; a dead Redis costs ~one timeout per second, not 50 ms per
  request
- **Spring Boot starter** with `@RateLimit`, or plain Java with no framework at all
- **Micrometer metrics**, optional: requests, store failures, store latency

## Quick start

```java
RateLimiter limiter = RateLimiter.builder()
    .tokenBucket(100, Duration.ofMinutes(1))   // or .slidingWindow(100, Duration.ofMinutes(1))
    .build();                                  // in-memory store by default

RateLimitResult result = limiter.tryAcquire("user:" + userId);      // or (key, permits)
if (!result.allowed()) {
    // reject with 429; result.retryAfter() fills the Retry-After header
}
```

Sharing one limit across a fleet is one line — the same code, backed by Redis
(`fluxlimit-redis` module, Lettuce connection owned by you):

```java
RateLimiter limiter = RateLimiter.builder()
    .tokenBucket(100, Duration.ofMinutes(1))
    .store(RedisStore.create(redisConnection))   // atomic Lua, 1 round trip, Redis server clock
    .build();
```

In Spring Boot, add `fluxlimit-spring-boot-starter` and annotate:

```java
@RateLimit(key = "#{principal.name}")   // SpEL; default key is the client address
@GetMapping("/api/search")
SearchResult search(...) { ... }
```

```properties
fluxlimit.limit=100
fluxlimit.period=60s
# distributed: add the fluxlimit-redis dependency and
# fluxlimit.redis.enabled=true
# fluxlimit.redis.uri=redis://localhost:6379
```

Denied requests get HTTP 429 with `Retry-After` and `RateLimit-*` headers. Runnable apps live in
[`examples/`](examples/).

## Modules

| Coordinates | What | Extra dependencies |
|---|---|---|
| `io.github.mithunveluru:fluxlimit-core` | API, algorithms, in-memory store | **none** |
| `io.github.mithunveluru:fluxlimit-redis` | Redis store | Lettuce |
| `io.github.mithunveluru:fluxlimit-spring-boot-starter` | Auto-config + `@RateLimit` | Spring Boot, Micrometer |

## Benchmarks

JMH, JDK 21, allow path (see [docs/benchmarks.md](docs/benchmarks.md) for method and full
output; run them yourself with `./gradlew :benchmarks:jmh`):

| Scenario | Result |
|---|---|
| In-memory, single thread | ~4.9M checks/s (~0.2 µs/op) |
| In-memory, 8 threads on one hot key | ~3.5M checks/s |
| Under contention vs Bucket4j `LocalBucket` | ~4.0M vs ~2.4M ops/s |
| Allocation per check | 192 B, steady state |
| Redis (loopback), p50 / p99 | 0.25 ms / 2.2 ms — one round trip per check |

## Which limiter library should I use?

Honest answer:

- **Bucket4j** — mature, many storage integrations (JCache, several Redis clients), token bucket
  only. Choose it when you need a backend FluxLimit doesn't have yet.
- **Resilience4j RateLimiter** — right when you already use Resilience4j and want *client-side*
  self-throttling next to circuit breakers; it is not keyed per-user and not distributed.
- **Guava RateLimiter** — fine for smoothing a single hot path in one JVM; no keys, no
  distribution, no deny-with-retry-after.
- **FluxLimit** — per-key limiting with identical semantics from one JVM to a Redis-backed
  fleet, two algorithms, honest failure semantics, and a public API that fits on one screen.

## FAQ

**What happens when Redis goes down?** Your API stays up. Checks answer from the configured
`FailurePolicy` (default: allow) flagged `degraded`, a metric spikes, and the store is re-probed
once a second. [Details](docs/redis.md#failure-behavior).

**How accurate is the sliding window?** Two counters per key buy an estimate whose worst case is
bounded (≤ 2× limit under an adversarial burst pattern, near-exact for steady traffic) and
skews conservative. [The math](docs/algorithms.md#sliding-window-counter).

**Fail-open by default — really?** Yes: for API abuse protection, letting traffic through during
a Redis blip beats turning a cache outage into an API outage (Stripe reached the same
conclusion). Flip to `FailurePolicy.DENY` when the limiter guards something billable or
dangerous — and never use a fail-open limiter as a security boundary.

## Compatibility

| | Minimum |
|---|---|
| Java | 21 |
| Redis | 6.2 (7.x recommended) |
| Spring Boot | 3.x |
| Lettuce | 6.x |

## Documentation

[Algorithms & accuracy](docs/algorithms.md) · [Redis operations](docs/redis.md) ·
[Benchmarks](docs/benchmarks.md) · Javadoc on every public type

## License

[Apache-2.0](LICENSE)
