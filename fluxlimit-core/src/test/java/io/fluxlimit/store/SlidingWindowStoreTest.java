package io.fluxlimit.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.fluxlimit.AlgorithmConfig;
import io.fluxlimit.internal.FakeClock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SlidingWindowStoreTest {

  private static final Duration WINDOW = Duration.ofSeconds(10);

  private final FakeClock clock = new FakeClock();
  private final InMemoryStore store = new InMemoryStore(clock);
  private final AlgorithmConfig.SlidingWindow config =
      new AlgorithmConfig.SlidingWindow(10, WINDOW);

  @AfterEach
  void close() {
    store.close();
  }

  @Test
  void allowsUpToLimitThenDenies() {
    for (int i = 0; i < 10; i++) {
      assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    }
    assertThat(store.tryConsume("k", 1, config).allowed()).isFalse();
  }

  @Test
  void previousWindowWeightsByOverlap() {
    // 50% into window two, half the previous count still applies
    for (int i = 0; i < 10; i++) {
      store.tryConsume("k", 1, config);
    }
    clock.advance(WINDOW.plus(WINDOW.dividedBy(2)));

    for (int i = 0; i < 5; i++) {
      assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    }
    assertThat(store.tryConsume("k", 1, config).allowed()).isFalse();
  }

  @Test
  void noBoundaryBurst() {
    // no 2x burst across a window boundary
    for (int i = 0; i < 10; i++) {
      store.tryConsume("k", 1, config);
    }
    clock.advance(WINDOW.plus(Duration.ofMillis(1)));

    int admitted = 0;
    for (int i = 0; i < 10; i++) {
      if (store.tryConsume("k", 1, config).allowed()) {
        admitted++;
      }
    }
    assertThat(admitted).isLessThanOrEqualTo(1);
  }

  @Test
  void fullyDecaysAfterTwoWindows() {
    for (int i = 0; i < 10; i++) {
      store.tryConsume("k", 1, config);
    }
    clock.advance(WINDOW.multipliedBy(2));

    for (int i = 0; i < 10; i++) {
      assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    }
  }

  @Test
  void weightedPermits() {
    StoreResult result = store.tryConsume("k", 7, config);
    assertThat(result.allowed()).isTrue();
    assertThat(result.remaining()).isEqualTo(3);

    assertThat(store.tryConsume("k", 4, config).allowed()).isFalse();
    assertThat(store.tryConsume("k", 3, config).allowed()).isTrue();
  }

  @Test
  void retryAfterEstimateIsHonoredAfterQuietPeriod() {
    for (int i = 0; i < 10; i++) {
      store.tryConsume("k", 1, config);
    }
    StoreResult denied = store.tryConsume("k", 1, config);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfter()).isPositive();

    clock.advance(denied.retryAfter());
    assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
  }

  @Test
  void staleStateIsSwept() {
    store.tryConsume("k", 1, config);
    assertThat(store.size()).isEqualTo(1);

    store.sweep();
    assertThat(store.size()).isEqualTo(1);

    clock.advance(WINDOW.multipliedBy(2).plus(Duration.ofNanos(1)));
    store.sweep();
    assertThat(store.size()).isEqualTo(0);
  }

  @Test
  void sweptKeyBehavesLikeFresh() {
    for (int i = 0; i < 10; i++) {
      store.tryConsume("k", 1, config);
    }
    clock.advance(WINDOW.multipliedBy(2).plus(Duration.ofNanos(1)));
    store.sweep();

    for (int i = 0; i < 10; i++) {
      assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    }
  }
}
