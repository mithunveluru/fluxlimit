package io.fluxlimit;

import io.fluxlimit.internal.Clock;
import io.fluxlimit.internal.MetricsListener;
import io.fluxlimit.store.RateLimiterStore;
import io.fluxlimit.store.StoreResult;
import java.lang.System.Logger.Level;
import java.time.Duration;

final class DefaultRateLimiter implements RateLimiter {

  private static final System.Logger LOG = System.getLogger("io.fluxlimit.RateLimiter");
  private static final int MAX_KEY_LENGTH = 256;
  private static final long COOLDOWN_NANOS = 1_000_000_000L;

  private final RateLimiterStore store;
  private final boolean ownsStore;
  private final AlgorithmConfig config;
  private final FailurePolicy failurePolicy;
  private final String keyPrefix;
  private final long limit;
  private final Clock clock;
  private final MetricsListener metrics;

  private volatile long cooldownUntilNanos;
  private volatile boolean storeHealthy = true;

  DefaultRateLimiter(
      RateLimiterStore store,
      boolean ownsStore,
      AlgorithmConfig config,
      FailurePolicy failurePolicy,
      String keyPrefix,
      Clock clock,
      MetricsListener metrics) {
    this.store = store;
    this.ownsStore = ownsStore;
    this.config = config;
    this.failurePolicy = failurePolicy;
    this.keyPrefix = keyPrefix;
    this.limit =
        switch (config) {
          case AlgorithmConfig.TokenBucket tokenBucket -> tokenBucket.capacity();
          case AlgorithmConfig.SlidingWindow slidingWindow -> slidingWindow.limit();
        };
    this.clock = clock;
    this.metrics = metrics;
    this.cooldownUntilNanos = clock.nanoTime();
  }

  @Override
  public RateLimitResult tryAcquire(String key) {
    return tryAcquire(key, 1);
  }

  @Override
  public RateLimitResult tryAcquire(String key, long permits) {
    validateKey(key);
    validatePermits(permits);
    long now = clock.nanoTime();
    if (now - cooldownUntilNanos < 0) {
      return degraded();
    }
    // clock reads only when metrics are enabled
    boolean timed = metrics != MetricsListener.NOOP;
    StoreResult result;
    try {
      result = store.tryConsume(keyPrefix + key, permits, config);
    } catch (RuntimeException e) {
      onStoreFailure(now, e);
      return degraded();
    }
    if (timed) {
      metrics.storeLatency(clock.nanoTime() - now);
    }
    storeHealthy = true;
    if (result.allowed()) {
      metrics.permitted(false);
    } else {
      metrics.denied(false);
    }
    return new RateLimitResult(
        result.allowed(), result.remaining(), limit, result.retryAfter(), false);
  }

  @Override
  public void close() {
    if (ownsStore) {
      store.close();
    }
  }

  private void onStoreFailure(long now, RuntimeException e) {
    metrics.storeFailure();
    cooldownUntilNanos = now + COOLDOWN_NANOS;
    if (storeHealthy) {
      storeHealthy = false;
      // one warn per failure episode, key never logged
      LOG.log(Level.WARNING, () -> "store failure, applying " + failurePolicy + " policy", e);
    }
  }

  private RateLimitResult degraded() {
    boolean allowed = failurePolicy == FailurePolicy.ALLOW;
    if (allowed) {
      metrics.permitted(true);
    } else {
      metrics.denied(true);
    }
    return new RateLimitResult(allowed, -1, limit, Duration.ZERO, true);
  }

  private void validatePermits(long permits) {
    if (permits < 1) {
      throw new IllegalArgumentException("permits must be at least 1");
    }
    if (permits > limit) {
      // can never succeed, deny would cause futile retries
      throw new IllegalArgumentException(
          "permits (" + permits + ") exceeds the configured limit (" + limit + ")");
    }
  }

  private static void validateKey(String key) {
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    if (key.isEmpty()) {
      throw new IllegalArgumentException("key must not be empty");
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("key must be at most " + MAX_KEY_LENGTH + " chars");
    }
  }
}
