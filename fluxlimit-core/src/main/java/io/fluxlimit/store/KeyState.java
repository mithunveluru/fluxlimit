package io.fluxlimit.store;

/** Per-key algorithm state held by {@link InMemoryStore}. */
sealed interface KeyState permits TokenBucketState, SlidingWindowState {

  /** True when this state is indistinguishable from an absent one and may be removed. */
  boolean staleAt(long nowNanos);
}
