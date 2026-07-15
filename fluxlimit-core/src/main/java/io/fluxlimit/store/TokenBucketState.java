package io.fluxlimit.store;

import io.fluxlimit.AlgorithmConfig;
import java.time.Duration;

/**
 * Per-key token bucket state. All access happens under the owning map's per-key lock, so fields are
 * plain.
 */
final class TokenBucketState implements KeyState {

  // micro-tokens avoid fp drift
  private static final long MICROS_PER_TOKEN = 1_000_000L;

  private long microTokens;
  private long lastRefillNanos;
  private long capacityMicros;
  private long refillTokens;
  private long periodNanos;

  private TokenBucketState() {}

  static TokenBucketState full(AlgorithmConfig.TokenBucket config, long nowNanos) {
    TokenBucketState state = new TokenBucketState();
    state.lastRefillNanos = nowNanos;
    state.applyConfig(config);
    state.microTokens = state.capacityMicros;
    return state;
  }

  void refill(AlgorithmConfig.TokenBucket config, long nowNanos) {
    applyConfig(config);
    long elapsed = nowNanos - lastRefillNanos;
    if (elapsed <= 0) {
      return;
    }
    long numer = refillTokens * MICROS_PER_TOKEN;
    long added = ExactMath.mulDivFloor(elapsed, numer, periodNanos);
    long deficit = capacityMicros - microTokens;
    if (added >= deficit) {
      microTokens = capacityMicros;
      lastRefillNanos = nowNanos;
    } else {
      microTokens += added;
      // advance only by consumed time, keep sub-microtoken remainder
      lastRefillNanos += ExactMath.mulDivFloor(added, periodNanos, numer);
    }
  }

  StoreResult consume(long permits) {
    long neededMicros = permits * MICROS_PER_TOKEN;
    if (microTokens >= neededMicros) {
      microTokens -= neededMicros;
      return new StoreResult(true, microTokens / MICROS_PER_TOKEN, Duration.ZERO);
    }
    long deficitMicros = neededMicros - microTokens;
    long waitNanos =
        ExactMath.mulDivCeil(deficitMicros, periodNanos, refillTokens * MICROS_PER_TOKEN);
    return new StoreResult(false, microTokens / MICROS_PER_TOKEN, Duration.ofNanos(waitNanos));
  }

  // full bucket equals absent
  @Override
  public boolean staleAt(long nowNanos) {
    long deficit = capacityMicros - microTokens;
    if (deficit == 0) {
      return true;
    }
    long elapsed = nowNanos - lastRefillNanos;
    if (elapsed <= 0) {
      return false;
    }
    return ExactMath.mulDivFloor(elapsed, refillTokens * MICROS_PER_TOKEN, periodNanos) >= deficit;
  }

  // limits may change across deployments
  private void applyConfig(AlgorithmConfig.TokenBucket config) {
    capacityMicros = config.capacity() * MICROS_PER_TOKEN;
    refillTokens = config.refillTokens();
    periodNanos = config.refillPeriod().toNanos();
    if (microTokens > capacityMicros) {
      microTokens = capacityMicros;
    }
  }
}
