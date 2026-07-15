package io.fluxlimit;

import io.fluxlimit.internal.Clock;
import io.fluxlimit.internal.MetricsListener;
import io.fluxlimit.internal.MicrometerMetrics;
import io.fluxlimit.store.InMemoryStore;
import io.fluxlimit.store.RateLimiterStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;

/**
 * A rate limiter. One shared instance serves all threads, like an HTTP client.
 *
 * <p>Checks never block, never queue, and never throw for a denied or failed check — denial is a
 * result, and store failures are absorbed by the configured {@link FailurePolicy}.
 *
 * {@snippet :
 * RateLimiter limiter = RateLimiter.builder()
 *     .tokenBucket(100, Duration.ofMinutes(1))
 *     .build();
 *
 * RateLimitResult result = limiter.tryAcquire("user:" + userId);
 * if (!result.allowed()) {
 *   // reject with 429, result.retryAfter() fills the Retry-After header
 * }
 * }
 */
public interface RateLimiter extends AutoCloseable {

  /**
   * Checks and consumes one permit for {@code key}. Returns immediately.
   *
   * @param key caller-chosen identity to limit on (user id, API key, IP); at most 256 chars
   * @throws IllegalArgumentException when the key is null, empty, or too long
   */
  RateLimitResult tryAcquire(String key);

  /**
   * Checks and consumes {@code permits} permits for {@code key} — for endpoints with different
   * costs. Returns immediately.
   *
   * @throws IllegalArgumentException when the key is invalid, {@code permits < 1}, or {@code
   *     permits} exceeds the configured limit and could never succeed
   */
  RateLimitResult tryAcquire(String key, long permits);

  /**
   * Releases resources this limiter created. A store the caller supplied via {@link Builder#store}
   * stays open — the caller owns it.
   */
  @Override
  void close();

  static Builder builder() {
    return new Builder();
  }

  /** Configures and creates a {@link RateLimiter}. Validation happens at {@link #build()}. */
  final class Builder {

    private static final String DEFAULT_KEY_PREFIX = "fluxlimit:";

    private AlgorithmConfig config;
    private RateLimiterStore store;
    private FailurePolicy failurePolicy = FailurePolicy.ALLOW;
    private String keyPrefix = DEFAULT_KEY_PREFIX;
    private Clock clock = Clock.system();
    private MeterRegistry meterRegistry;

    Builder() {}

    /**
     * Token bucket allowing bursts up to {@code capacity}, refilled completely over {@code
     * refillPeriod}.
     */
    public Builder tokenBucket(long capacity, Duration refillPeriod) {
      this.config = new AlgorithmConfig.TokenBucket(capacity, capacity, refillPeriod);
      return this;
    }

    /**
     * Sliding window counter: at most {@code limit} permits per {@code window}, smoothed across
     * window boundaries. Prefer over a token bucket when burstiness is unwanted.
     */
    public Builder slidingWindow(long limit, Duration window) {
      this.config = new AlgorithmConfig.SlidingWindow(limit, window);
      return this;
    }

    /**
     * Publishes {@code fluxlimit.requests}, {@code fluxlimit.store.failures}, and {@code
     * fluxlimit.store.latency} meters to this registry. Micrometer is an optional dependency — only
     * needed when this method is used.
     */
    public Builder meterRegistry(MeterRegistry meterRegistry) {
      this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
      return this;
    }

    /**
     * Storage backend. Defaults to a new in-memory store owned (and closed) by the limiter; a store
     * supplied here is owned by the caller. Limiters sharing one store must use distinct {@link
     * #keyPrefix} values.
     */
    public Builder store(RateLimiterStore store) {
      this.store = Objects.requireNonNull(store, "store");
      return this;
    }

    /** Behavior when the store is unreachable. Defaults to {@link FailurePolicy#ALLOW}. */
    public Builder failurePolicy(FailurePolicy failurePolicy) {
      this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
      return this;
    }

    /** Namespace prepended to every key. Defaults to {@code "fluxlimit:"}. */
    public Builder keyPrefix(String keyPrefix) {
      this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
      return this;
    }

    Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    /**
     * Builds the configured {@link RateLimiter}.
     *
     * @throws IllegalStateException when no algorithm was configured
     */
    public RateLimiter build() {
      if (config == null) {
        throw new IllegalStateException(
            "algorithm required: call tokenBucket(...) or slidingWindow(...)");
      }
      boolean ownsStore = store == null;
      RateLimiterStore effectiveStore = ownsStore ? new InMemoryStore() : store;
      // micrometer classes load only on this branch
      MetricsListener metrics =
          meterRegistry == null ? MetricsListener.NOOP : new MicrometerMetrics(meterRegistry);
      return new DefaultRateLimiter(
          effectiveStore, ownsStore, config, failurePolicy, keyPrefix, clock, metrics);
    }
  }
}
