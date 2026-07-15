package io.fluxlimit.store;

import java.time.Duration;

/**
 * Store-level outcome of one atomic check.
 *
 * @param allowed whether the permits were consumed
 * @param remaining whole permits left right now
 * @param retryAfter wait until the same request could succeed; {@link Duration#ZERO} when allowed
 */
public record StoreResult(boolean allowed, long remaining, Duration retryAfter) {}
