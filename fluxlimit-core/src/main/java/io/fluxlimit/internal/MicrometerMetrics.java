package io.fluxlimit.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer adapter. Only ever loaded when the user supplies a {@link MeterRegistry}, keeping
 * Micrometer an optional dependency.
 *
 * <p>Counters are pre-registered per tag combination — never the rate-limit key, whose cardinality
 * is unbounded.
 */
public final class MicrometerMetrics implements MetricsListener {

  private final Counter allowed;
  private final Counter denied;
  private final Counter allowedDegraded;
  private final Counter deniedDegraded;
  private final Counter storeFailures;
  private final Timer storeLatency;

  public MicrometerMetrics(MeterRegistry registry) {
    this.allowed = requests(registry, "allowed", false);
    this.denied = requests(registry, "denied", false);
    this.allowedDegraded = requests(registry, "allowed", true);
    this.deniedDegraded = requests(registry, "denied", true);
    this.storeFailures = registry.counter("fluxlimit.store.failures");
    this.storeLatency = registry.timer("fluxlimit.store.latency");
  }

  private static Counter requests(MeterRegistry registry, String result, boolean degraded) {
    return registry.counter(
        "fluxlimit.requests", "result", result, "degraded", Boolean.toString(degraded));
  }

  @Override
  public void permitted(boolean degraded) {
    (degraded ? allowedDegraded : allowed).increment();
  }

  @Override
  public void denied(boolean degraded) {
    (degraded ? deniedDegraded : denied).increment();
  }

  @Override
  public void storeFailure() {
    storeFailures.increment();
  }

  @Override
  public void storeLatency(long nanos) {
    storeLatency.record(nanos, TimeUnit.NANOSECONDS);
  }
}
