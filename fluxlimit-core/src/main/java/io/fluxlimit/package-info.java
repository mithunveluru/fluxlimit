/**
 * FluxLimit public API. Start at {@link io.fluxlimit.RateLimiter#builder()}.
 *
 * <p>One {@code RateLimiter} instance is thread safe and meant to be shared, like an HTTP client.
 * Checks never block and never throw for denials or store failures.
 */
package io.fluxlimit;
