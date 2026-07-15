# Redis operations guide

## Requirements

Redis **6.2+** (7.x recommended). The Lua scripts call `TIME` before writing, which requires
effect-based script replication — default since Redis 5; 6.2 is the tested floor.

## How it works

One Lua script per algorithm, loaded at store construction (`SCRIPT LOAD`) and invoked with
`EVALSHA` — exactly **one round trip per check**, no retries, no transactions, no locks. Redis
executes scripts serially, so two application servers can never double-spend a permit. Time comes
from Redis `TIME` (microseconds): one authoritative clock for the whole fleet, no clock-skew
handling anywhere.

After a Redis restart empties the script cache, the first `NOSCRIPT` error triggers a transparent
`EVAL` fallback which re-caches the script. No operator action needed.

## Keys and memory

- Every key is `<prefix><your key>`, default prefix `fluxlimit:`. Limiters sharing one Redis must
  use distinct prefixes if they have different configs.
- State is a small hash: token bucket `{tk, ts}`, sliding window `{wid, cur, prev}` —
  roughly 100–150 bytes per active key including Redis overhead.
- Every write sets `PEXPIRE` to the moment the state becomes indistinguishable from absent
  (bucket full again / both windows stale) plus grace. **Idle keys delete themselves**; Redis
  memory is self-bounding. A key expiring early is harmless — a cold read reconstructs a full
  bucket.

## Deployment topologies

| Topology | Support |
|---|---|
| Standalone / managed (ElastiCache, MemoryDB, Upstash…) | `RedisStore.create(connection)` |
| Sentinel | Same call — Sentinel discovery lives in your `RedisURI`; a failover looks like a brief outage (below) |
| Cluster | `RedisStore.create(clusterConnection)`. Scripts touch one key → one slot; no hash tags needed |

The Lettuce connection is **yours**: you create it, you close it. One connection is enough —
Lettuce multiplexes, and rate-limit traffic is tiny commands.

## Failure behavior

| Event | What happens |
|---|---|
| Redis unreachable / command slower than the timeout (default 50 ms) | The store throws; the limiter answers from `FailurePolicy` (`ALLOW` by default), flags the result `degraded`, logs one WARN per episode, and bypasses the store for 1 s before re-probing. A dead Redis costs ~1 timeout per second, not 50 ms per request |
| Redis restart (state lost) | Brief over-admission window; self-heals within one refill period / window. If that matters, run Redis with persistence — an operator decision, not a library one |
| Failover (Sentinel/Cluster) | Appears as a short outage: cooldown + policy, then normal service |

Watch `fluxlimit.store.failures` (counter) and `fluxlimit.store.latency` (timer) — a spike in
either is your signal that limiting is degraded.

## Tuning

- `commandTimeout` (default 50 ms): a rate-limit check slower than this hurts more than it
  helps. Raise it only for high-latency links — and then reconsider whether a limiter belongs on
  that path at all.
- Config changes deploy safely: state is reinterpreted under the new config and converges within
  one period/window.
