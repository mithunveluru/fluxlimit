package io.fluxlimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MetricsTest {

  @Test
  void countsAllowedAndDenied() {
    var registry = new SimpleMeterRegistry();
    try (RateLimiter limiter =
        RateLimiter.builder()
            .tokenBucket(2, Duration.ofMinutes(1))
            .meterRegistry(registry)
            .build()) {

      limiter.tryAcquire("k");
      limiter.tryAcquire("k");
      limiter.tryAcquire("k");

      assertThat(requests(registry, "allowed", false)).isEqualTo(2.0);
      assertThat(requests(registry, "denied", false)).isEqualTo(1.0);
      assertThat(registry.counter("fluxlimit.store.failures").count()).isZero();
      assertThat(registry.timer("fluxlimit.store.latency").count()).isEqualTo(3);
    }
  }

  @Test
  void countsStoreFailuresAndDegradedResults() {
    var registry = new SimpleMeterRegistry();
    RateLimiter limiter =
        RateLimiter.builder()
            .tokenBucket(2, Duration.ofMinutes(1))
            .store(
                (key, permits, config) -> {
                  throw new IllegalStateException("store down");
                })
            .meterRegistry(registry)
            .build();

    limiter.tryAcquire("k");
    limiter.tryAcquire("k");

    assertThat(registry.counter("fluxlimit.store.failures").count()).isEqualTo(1.0);
    // one probe failed, cooldown served the second, both degraded
    assertThat(requests(registry, "allowed", true)).isEqualTo(2.0);
    assertThat(requests(registry, "allowed", false)).isZero();
  }

  private static double requests(SimpleMeterRegistry registry, String result, boolean degraded) {
    return registry
        .counter("fluxlimit.requests", "result", result, "degraded", Boolean.toString(degraded))
        .count();
  }
}
