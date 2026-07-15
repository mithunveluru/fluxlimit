package io.fluxlimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.fluxlimit.internal.FakeClock;
import io.fluxlimit.store.InMemoryStore;
import io.fluxlimit.store.RateLimiterStore;
import io.fluxlimit.store.StoreResult;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  @Test
  void builderRequiresAlgorithm() {
    assertThatIllegalStateException()
        .isThrownBy(() -> RateLimiter.builder().build())
        .withMessageContaining("algorithm");
  }

  @Test
  void configValidationFailsFast() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RateLimiter.builder().tokenBucket(0, Duration.ofSeconds(1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RateLimiter.builder().tokenBucket(10, Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RateLimiter.builder().tokenBucket(10, Duration.ofSeconds(-1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RateLimiter.builder().tokenBucket(10, Duration.ofDays(400)));
  }

  @Test
  void keyValidation() {
    try (RateLimiter limiter =
        RateLimiter.builder().tokenBucket(1, Duration.ofSeconds(1)).build()) {
      assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(null));
      assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(""));
      assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire("x".repeat(257)));
      limiter.tryAcquire("x".repeat(256));
    }
  }

  @Test
  void permitsValidation() {
    try (RateLimiter limiter =
        RateLimiter.builder().tokenBucket(10, Duration.ofSeconds(1)).build()) {
      assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire("k", 0));
      assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire("k", -1));
      // can never succeed, must fail fast instead of denying
      assertThatIllegalArgumentException()
          .isThrownBy(() -> limiter.tryAcquire("k", 11))
          .withMessageContaining("exceeds");
      assertThat(limiter.tryAcquire("k", 10).allowed()).isTrue();
    }
  }

  @Test
  void weightedPermitsConsumeAndReport() {
    try (RateLimiter limiter =
        RateLimiter.builder().slidingWindow(10, Duration.ofSeconds(10)).build()) {
      RateLimitResult result = limiter.tryAcquire("k", 7);
      assertThat(result.allowed()).isTrue();
      assertThat(result.remaining()).isEqualTo(3);
      assertThat(limiter.tryAcquire("k", 4).allowed()).isFalse();
    }
  }

  @Test
  void slidingWindowLimiterEndToEnd() {
    try (RateLimiter limiter =
        RateLimiter.builder().slidingWindow(2, Duration.ofMinutes(1)).build()) {
      assertThat(limiter.tryAcquire("k").allowed()).isTrue();
      assertThat(limiter.tryAcquire("k").allowed()).isTrue();
      RateLimitResult denied = limiter.tryAcquire("k");
      assertThat(denied.allowed()).isFalse();
      assertThat(denied.limit()).isEqualTo(2);
      assertThat(denied.retryAfter()).isPositive();
    }
  }

  @Test
  void allowsThenDeniesWithFullResultFields() {
    try (RateLimiter limiter =
        RateLimiter.builder().tokenBucket(2, Duration.ofMinutes(1)).build()) {

      RateLimitResult first = limiter.tryAcquire("user:1");
      assertThat(first.allowed()).isTrue();
      assertThat(first.remaining()).isEqualTo(1);
      assertThat(first.limit()).isEqualTo(2);
      assertThat(first.retryAfter()).isEqualTo(Duration.ZERO);
      assertThat(first.degraded()).isFalse();

      limiter.tryAcquire("user:1");
      RateLimitResult denied = limiter.tryAcquire("user:1");
      assertThat(denied.allowed()).isFalse();
      assertThat(denied.remaining()).isEqualTo(0);
      assertThat(denied.retryAfter()).isPositive();
      assertThat(denied.degraded()).isFalse();
    }
  }

  @Test
  void distinctPrefixesIsolateLimitersOnSharedStore() {
    try (var store = new InMemoryStore();
        RateLimiter a =
            RateLimiter.builder()
                .tokenBucket(1, Duration.ofMinutes(1))
                .store(store)
                .keyPrefix("a:")
                .build();
        RateLimiter b =
            RateLimiter.builder()
                .tokenBucket(1, Duration.ofMinutes(1))
                .store(store)
                .keyPrefix("b:")
                .build()) {

      assertThat(a.tryAcquire("k").allowed()).isTrue();
      assertThat(b.tryAcquire("k").allowed()).isTrue();
      assertThat(a.tryAcquire("k").allowed()).isFalse();
    }
  }

  @Test
  void failOpenAllowsAndFlagsDegraded() {
    RateLimitResult result = withFailingStore(FailurePolicy.ALLOW).tryAcquire("k");
    assertThat(result.allowed()).isTrue();
    assertThat(result.degraded()).isTrue();
    assertThat(result.remaining()).isEqualTo(-1);
    assertThat(result.retryAfter()).isEqualTo(Duration.ZERO);
  }

  @Test
  void failClosedDeniesAndFlagsDegraded() {
    RateLimitResult result = withFailingStore(FailurePolicy.DENY).tryAcquire("k");
    assertThat(result.allowed()).isFalse();
    assertThat(result.degraded()).isTrue();
  }

  @Test
  void cooldownBypassesStoreForOneSecond() {
    var clock = new FakeClock();
    var calls = new AtomicInteger();
    RateLimiterStore failing =
        (key, permits, config) -> {
          calls.incrementAndGet();
          throw new IllegalStateException("store down");
        };
    RateLimiter limiter =
        RateLimiter.builder()
            .tokenBucket(1, Duration.ofSeconds(1))
            .store(failing)
            .clock(clock)
            .build();

    limiter.tryAcquire("k");
    limiter.tryAcquire("k");
    limiter.tryAcquire("k");
    assertThat(calls.get()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(1));
    limiter.tryAcquire("k");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void recoveryClearsDegradedFlag() {
    var clock = new FakeClock();
    var failing = new AtomicBoolean(true);
    RateLimiterStore flaky =
        (key, permits, config) -> {
          if (failing.get()) {
            throw new IllegalStateException("store down");
          }
          return new StoreResult(true, 0, Duration.ZERO);
        };
    RateLimiter limiter =
        RateLimiter.builder()
            .tokenBucket(1, Duration.ofSeconds(1))
            .store(flaky)
            .clock(clock)
            .build();

    assertThat(limiter.tryAcquire("k").degraded()).isTrue();

    failing.set(false);
    clock.advance(Duration.ofSeconds(1));
    assertThat(limiter.tryAcquire("k").degraded()).isFalse();
  }

  @Test
  void closeLeavesCallerSuppliedStoreOpen() {
    var closed = new AtomicBoolean(false);
    RateLimiterStore store =
        new RateLimiterStore() {
          @Override
          public StoreResult tryConsume(String key, long permits, AlgorithmConfig config) {
            return new StoreResult(true, 0, Duration.ZERO);
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    RateLimiter limiter =
        RateLimiter.builder().tokenBucket(1, Duration.ofSeconds(1)).store(store).build();
    limiter.close();
    assertThat(closed).isFalse();
  }

  private static RateLimiter withFailingStore(FailurePolicy policy) {
    RateLimiterStore failing =
        (key, permits, config) -> {
          throw new IllegalStateException("store down");
        };
    return RateLimiter.builder()
        .tokenBucket(5, Duration.ofSeconds(1))
        .store(failing)
        .failurePolicy(policy)
        .build();
  }
}
