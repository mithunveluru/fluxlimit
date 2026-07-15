package io.fluxlimit;

import java.time.Duration;

/**
 * Outcome of one rate-limit check.
 *
 * <p>Carries everything needed to populate IETF {@code RateLimit-*} and {@code Retry-After}
 * response headers.
 *
 * @param allowed whether the request may proceed
 * @param remaining whole permits left right now, or {@code -1} when {@code degraded}
 * @param limit the configured maximum
 * @param retryAfter wait until a retry can succeed; {@link Duration#ZERO} when allowed or degraded
 * @param degraded true when the store failed and {@link FailurePolicy} decided this answer
 */
public record RateLimitResult(
    boolean allowed, long remaining, long limit, Duration retryAfter, boolean degraded) {}
