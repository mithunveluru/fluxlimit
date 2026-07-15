package io.fluxlimit;

/**
 * What {@link RateLimiter#tryAcquire} answers while the store is unreachable.
 *
 * <p>Results produced this way are flagged {@link RateLimitResult#degraded()}. The store is
 * re-probed after a short cooldown, so a struggling store never adds its timeout to every request.
 */
public enum FailurePolicy {

  /** Fail open: requests are allowed while the store is down. The default. */
  ALLOW,

  /** Fail closed: requests are denied while the store is down. */
  DENY
}
