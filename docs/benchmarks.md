# Benchmarks

JMH 1.37, JDK 21, Linux x86-64 (consumer hardware), 1 fork, 3×2 s warmup, 5×2 s measurement,
GC profiler. Raw output: [jmh-2026-07-14.txt](benchmarks/jmh-2026-07-14.txt). Reproduce with:

```
./gradlew :benchmarks:jmh                 # in-memory + comparisons
./gradlew :benchmarks:jmh -PredisBench    # + redis (needs redis on localhost:6379)
```

Single-fork numbers on a non-quiesced machine are **indicative, not lab-grade** — error bars in
the raw output are wide. The shapes below were stable across runs; treat absolute values as ±30%.

## In-memory

Allow path, token bucket, via the full public API (`tryAcquire(key)` — includes key validation,
prefixing, and per-key map lookup):

| Scenario | Throughput | Allocation |
|---|---|---|
| Single thread | ~4.9M checks/s (~0.2 µs/op) | 192 B/op |
| 8 threads, one hot key | ~3.5M checks/s | 192 B/op |
| 8 threads, spread keys | ~3.3M checks/s | 192 B/op |

Two orders of magnitude above the design target (≥100k checks/s), and per-op latency ~50× under
the 10 µs p99 budget. The 192 B/op is the key-prefix concatenation plus the two immutable result
records; steady-state and young-gen only. Chasing true zero would mean flyweight results and
key interning — complexity the numbers don't yet justify.

## Comparison

Same-shape token bucket, allow path. **Read the caveat**: FluxLimit is measured through its keyed
API (map lookup per call); Bucket4j's `LocalBucket` and Resilience4j measure one pre-resolved
limiter object with no keying at all — that flatters them in the single-threaded column. Keyed
usage of those libraries requires your own map on top.

| Library | Single thread | 8 threads, contended | Alloc |
|---|---|---|---|
| FluxLimit (keyed API) | ~4.7M ops/s | ~4.0M ops/s | 192 B/op |
| Bucket4j `LocalBucket` (unkeyed) | ~12.8M ops/s | ~2.4M ops/s | 64 B/op |
| Resilience4j (unkeyed) | ~11.7M ops/s | ~9.7M ops/s | 40 B/op |

The interesting cell is contended: under 8-thread contention on one key, FluxLimit's
`ConcurrentHashMap.compute` design holds ~4M ops/s while Bucket4j's lock-based `LocalBucket`
drops to ~2.4M. Resilience4j stays fastest but solves a different problem (client-side
self-throttling; no per-user keys, no distribution).

## Redis

Local Docker Redis (so: dominated by loopback RTT — which is the claim being verified: one round
trip per check, no retries):

| Scenario | p50 | p99 |
|---|---|---|
| 1 client thread | 0.25 ms | 2.2 ms |
| 8 client threads, one key | 1.8 ms | 11.6 ms |

The 8-thread numbers reflect eight callers multiplexing one Lettuce connection against one Redis
executing scripts serially — the serialization that makes the answer correct. For production
numbers, substitute your real network RTT; the library adds microseconds around it.
