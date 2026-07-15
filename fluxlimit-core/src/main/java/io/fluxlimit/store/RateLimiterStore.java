package io.fluxlimit.store;

import io.fluxlimit.AlgorithmConfig;

/**
 * Storage SPI: executes one rate-limit state transition atomically inside the backend.
 *
 * <p>Implementations must make the read-evaluate-write cycle atomic per key, however is natural for
 * the backend, and must be unconditionally thread safe. One store instance may back several
 * limiters as long as each limiter uses a distinct key prefix.
 */
public interface RateLimiterStore extends AutoCloseable {

  /**
   * Atomically evaluates one check against {@code config}, consuming {@code permits} when allowed.
   *
   * @throws RuntimeException when the backend is unreachable; the limiter translates this into its
   *     {@code FailurePolicy}
   */
  StoreResult tryConsume(String key, long permits, AlgorithmConfig config);

  /** Releases store-owned resources. Never closes resources the caller created. */
  @Override
  default void close() {}
}
