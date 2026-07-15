# Algorithms

FluxLimit ships two algorithms. They are the two behaviorally distinct points in the design
space: **Token Bucket** allows controlled bursts, **Sliding Window Counter** smooths traffic over
a rolling window. Everything else (fixed window, sliding log, leaky bucket) is either dominated
by one of these or has a disqualifying cost.

## Token Bucket

A bucket holds up to `capacity` tokens and refills continuously at `refillTokens / refillPeriod`.
Each request consumes `permits` tokens; an empty bucket denies.

**Lazy refill.** Nothing runs in the background. On each check the elapsed time since the last
refill is converted into tokens:

```
added = elapsed × refillTokens / refillPeriod   (exact integer math, micro-token scale)
```

**Exactness.** Token counts are stored as scaled longs (micro-tokens, 10⁻⁶ of a token), never
floats, so there is no drift at low refill rates. Two details make the math exact:

1. The refill timestamp advances only by the time corresponding to *whole micro-tokens granted*,
   so sub-micro-token remainders carry into the next check instead of being discarded. A bucket
   refilling 1 token per 2 s and checked every 500 ms admits exactly one request per 2 s,
   forever.
2. Products that could overflow a `long` fall back to `BigInteger` on that (cold) branch only.

**retryAfter** is exact for a quiet key: the time until the deficit refills, rounded up to the
next micro-token boundary.

**Choose it when** clients legitimately burst (batch jobs, retries, page loads firing several
calls). Burst size = `capacity`; sustained rate = `refillTokens / refillPeriod`. The
`tokenBucket(n, period)` convenience sets both to `n`.

## Sliding Window Counter

Two counters per key — the current window and the previous one. The estimated rate at offset `o`
into the current window (length `W`):

```
estimate = current + previous × (W − o) / W
```

A request is admitted while `estimate + permits ≤ limit`. The previous-window contribution is
rounded **up**, so ties resolve toward denying — the error skews conservative.

**Accuracy bound.** The estimator assumes the previous window's requests were uniformly
distributed. The worst case is a burst at the very end of the previous window: the true count in
the trailing `W` seconds can then reach

```
true ≤ limit + previous × o / W ≤ 2 × limit
```

— bounded, and reached only by an adversarial burst pattern; for steady traffic the estimate is
near-exact. This is the same accuracy/memory trade Cloudflare chose for production at millions of
domains: two counters per key instead of a timestamp per request.

**retryAfter** solves the linear decay of the previous window's contribution, looking past the
window boundary when the current window can no longer fit the request. Exact for a quiet key;
concurrent traffic can still deny after the suggested wait (true of any limiter).

**Choose it when** bursts are unwanted and you care that "100 per minute" never means 200 in any
60-second span around a boundary (the fixed-window failure mode).

## The same math in Redis

Each algorithm exists twice: once in Java (in-memory store) and once in Lua (Redis store),
executed atomically inside Redis in one round trip, on the Redis server clock (`TIME`) — so all
application servers share one clock and one state. Redis Lua numbers are 53-bit-exact doubles;
config validation caps limits at 10⁹ tokens and periods at ≥ 1 ms so every intermediate value
stays in the exact range. Worst-case rounding on the Redis path is one micro-token per check,
non-cumulative.
