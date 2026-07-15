/**
 * Storage SPI and the in-memory store. Implement {@link io.fluxlimit.store.RateLimiterStore} to add
 * a backend — the one rule: the read-evaluate-write cycle must be atomic per key.
 */
package io.fluxlimit.store;
