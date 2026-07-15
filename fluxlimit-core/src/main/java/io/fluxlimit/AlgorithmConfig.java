package io.fluxlimit;

import java.time.Duration;
import java.util.Objects;

/**
 * Algorithm selection and parameters for a {@link RateLimiter}.
 *
 * <p>Sealed by design: stores execute algorithms atomically inside the backend, so the algorithm
 * set is closed per major version and every store handles every member exhaustively.
 */
public sealed interface AlgorithmConfig
    permits AlgorithmConfig.TokenBucket, AlgorithmConfig.SlidingWindow {

  /**
   * Token bucket: bursts up to {@code capacity}, refilled at {@code refillTokens} per {@code
   * refillPeriod}.
   *
   * @param capacity maximum tokens the bucket holds; the allowed burst size
   * @param refillTokens tokens added per {@code refillPeriod}, accrued continuously
   * @param refillPeriod period over which {@code refillTokens} are added
   */
  record TokenBucket(long capacity, long refillTokens, Duration refillPeriod)
      implements AlgorithmConfig {

    private static final long MAX_TOKENS = 1_000_000_000L;

    public TokenBucket {
      require(capacity >= 1, "capacity must be at least 1");
      require(capacity <= MAX_TOKENS, "capacity must be at most 1e9");
      require(refillTokens >= 1, "refillTokens must be at least 1");
      require(refillTokens <= MAX_TOKENS, "refillTokens must be at most 1e9");
      Objects.requireNonNull(refillPeriod, "refillPeriod");
      require(
          refillPeriod.compareTo(Duration.ofMillis(1)) >= 0, "refillPeriod must be at least 1 ms");
      require(
          refillPeriod.compareTo(Duration.ofDays(365)) <= 0,
          "refillPeriod must be at most 365 days");
    }
  }

  /**
   * Sliding window counter: at most {@code limit} permits per {@code window}, smoothed by weighting
   * the previous window's count by its remaining overlap.
   *
   * <p>The estimate is approximate but the error is bounded and skews conservative; accuracy is
   * traded for two counters per key.
   *
   * @param limit maximum permits per window
   * @param window window length
   */
  record SlidingWindow(long limit, Duration window) implements AlgorithmConfig {

    private static final long MAX_LIMIT = 1_000_000_000L;

    public SlidingWindow {
      require(limit >= 1, "limit must be at least 1");
      require(limit <= MAX_LIMIT, "limit must be at most 1e9");
      Objects.requireNonNull(window, "window");
      require(window.compareTo(Duration.ofMillis(1)) >= 0, "window must be at least 1 ms");
      require(window.compareTo(Duration.ofDays(365)) <= 0, "window must be at most 365 days");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
