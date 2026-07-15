package io.fluxlimit.internal;

/**
 * Receives one callback per rate-limit outcome. Implementations must be cheap and thread safe —
 * they run on the caller's hot path.
 */
public interface MetricsListener {

  MetricsListener NOOP =
      new MetricsListener() {
        @Override
        public void permitted(boolean degraded) {}

        @Override
        public void denied(boolean degraded) {}

        @Override
        public void storeFailure() {}
      };

  void permitted(boolean degraded);

  void denied(boolean degraded);

  void storeFailure();

  /** Duration of one store call. Only invoked when metrics are enabled. */
  default void storeLatency(long nanos) {}
}
