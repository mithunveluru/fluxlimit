package io.fluxlimit.store;

import io.fluxlimit.AlgorithmConfig;
import java.time.Duration;

/**
 * Per-key sliding window counter state. All access happens under the owning map's per-key lock, so
 * fields are plain.
 *
 * <p>Estimate = current window count + previous window count weighted by overlap, the previous
 * contribution rounded up so the error skews conservative.
 */
final class SlidingWindowState implements KeyState {

  private long windowId;
  private long currentCount;
  private long previousCount;
  private long limit;
  private long windowNanos;

  private SlidingWindowState() {}

  static SlidingWindowState empty(AlgorithmConfig.SlidingWindow config, long nowNanos) {
    SlidingWindowState state = new SlidingWindowState();
    state.applyConfig(config);
    state.windowId = Math.floorDiv(nowNanos, state.windowNanos);
    return state;
  }

  StoreResult consume(AlgorithmConfig.SlidingWindow config, long nowNanos, long permits) {
    applyConfig(config);
    rotate(nowNanos);
    long offset = Math.floorMod(nowNanos, windowNanos);
    long previousContribution =
        ExactMath.mulDivCeil(previousCount, windowNanos - offset, windowNanos);
    long used = previousContribution + currentCount;
    if (used + permits <= limit) {
      currentCount += permits;
      return new StoreResult(true, limit - used - permits, Duration.ZERO);
    }
    long remaining = Math.max(0, limit - used);
    return new StoreResult(false, remaining, retryAfter(offset, permits));
  }

  @Override
  public boolean staleAt(long nowNanos) {
    if (currentCount == 0 && previousCount == 0) {
      return true;
    }
    // both windows fully in the past
    return Math.floorDiv(nowNanos, windowNanos) > windowId + 1;
  }

  private void rotate(long nowNanos) {
    long id = Math.floorDiv(nowNanos, windowNanos);
    if (id == windowId) {
      return;
    }
    if (id == windowId + 1) {
      previousCount = currentCount;
    } else {
      previousCount = 0;
    }
    currentCount = 0;
    windowId = id;
  }

  // exact for a quiet key, concurrent traffic can still deny
  private Duration retryAfter(long offset, long permits) {
    long budget = limit - currentCount - permits;
    long waitNanos;
    if (budget >= 0 && previousCount > 0) {
      // previous contribution decays linearly inside this window
      waitNanos = decayOffset(budget, previousCount) - offset;
    } else {
      // cannot fit here: current becomes previous at the boundary
      long boundary = windowNanos - offset;
      long nextBudget = limit - permits;
      long extra = currentCount > 0 && nextBudget >= 0 ? decayOffset(nextBudget, currentCount) : 0;
      waitNanos = boundary + extra;
    }
    return Duration.ofNanos(Math.max(waitNanos, 1));
  }

  // earliest offset where ceil(count * (window - offset) / window) <= budget
  private long decayOffset(long budget, long count) {
    return windowNanos - ExactMath.mulDivFloor(budget, windowNanos, count);
  }

  private void applyConfig(AlgorithmConfig.SlidingWindow config) {
    limit = config.limit();
    windowNanos = config.window().toNanos();
  }
}
