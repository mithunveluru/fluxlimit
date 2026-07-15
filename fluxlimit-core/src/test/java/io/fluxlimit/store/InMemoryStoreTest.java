package io.fluxlimit.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.fluxlimit.AlgorithmConfig;
import io.fluxlimit.internal.FakeClock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InMemoryStoreTest {

  private final FakeClock clock = new FakeClock();
  private final InMemoryStore store = new InMemoryStore(clock);

  @AfterEach
  void close() {
    store.close();
  }

  private static AlgorithmConfig.TokenBucket bucket(long capacity, Duration period) {
    return new AlgorithmConfig.TokenBucket(capacity, capacity, period);
  }

  @Test
  void allowsUpToCapacityThenDenies() {
    var config = bucket(3, Duration.ofSeconds(3));
    assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    assertThat(store.tryConsume("k", 1, config).allowed()).isFalse();
  }

  @Test
  void remainingCountsDown() {
    var config = bucket(3, Duration.ofSeconds(3));
    assertThat(store.tryConsume("k", 1, config).remaining()).isEqualTo(2);
    assertThat(store.tryConsume("k", 1, config).remaining()).isEqualTo(1);
    assertThat(store.tryConsume("k", 1, config).remaining()).isEqualTo(0);
    assertThat(store.tryConsume("k", 1, config).remaining()).isEqualTo(0);
  }

  @Test
  void retryAfterIsExactAndHonored() {
    var config = bucket(1, Duration.ofSeconds(1));
    store.tryConsume("k", 1, config);

    StoreResult denied = store.tryConsume("k", 1, config);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfter()).isEqualTo(Duration.ofSeconds(1));

    clock.advance(Duration.ofMillis(999));
    denied = store.tryConsume("k", 1, config);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfter()).isEqualTo(Duration.ofMillis(1));

    clock.advance(Duration.ofMillis(1));
    assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
  }

  @Test
  void refillIsProportional() {
    var config = bucket(10, Duration.ofSeconds(10));
    for (int i = 0; i < 10; i++) {
      assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    }
    clock.advance(Duration.ofSeconds(3));
    for (int i = 0; i < 3; i++) {
      assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    }
    assertThat(store.tryConsume("k", 1, config).allowed()).isFalse();
  }

  @Test
  void refillCapsAtCapacity() {
    var config = bucket(5, Duration.ofSeconds(1));
    store.tryConsume("k", 1, config);
    clock.advance(Duration.ofHours(1));
    for (int i = 0; i < 5; i++) {
      assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
    }
    assertThat(store.tryConsume("k", 1, config).allowed()).isFalse();
  }

  @Test
  void fractionalRefillAccumulatesWithoutDrift() {
    // 1 token/2s at 500ms checks: allowed every 4th
    var config = bucket(1, Duration.ofSeconds(2));
    store.tryConsume("k", 1, config);

    int allowed = 0;
    for (int i = 0; i < 4000; i++) {
      clock.advance(Duration.ofMillis(500));
      if (store.tryConsume("k", 1, config).allowed()) {
        allowed++;
      }
    }
    assertThat(allowed).isEqualTo(1000);
  }

  @Test
  void slowRefillStaysExact() {
    var config = bucket(1, Duration.ofHours(1));
    store.tryConsume("k", 1, config);

    clock.advance(Duration.ofMinutes(59));
    assertThat(store.tryConsume("k", 1, config).allowed()).isFalse();
    clock.advance(Duration.ofMinutes(1));
    assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
  }

  @Test
  void weightedPermitsConsumeAtSpiLevel() {
    var config = bucket(10, Duration.ofSeconds(10));
    StoreResult result = store.tryConsume("k", 7, config);
    assertThat(result.allowed()).isTrue();
    assertThat(result.remaining()).isEqualTo(3);

    StoreResult denied = store.tryConsume("k", 5, config);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.remaining()).isEqualTo(3);
    // 2 missing tokens at 1 token/s
    assertThat(denied.retryAfter()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void keysAreIndependent() {
    var config = bucket(1, Duration.ofSeconds(1));
    assertThat(store.tryConsume("a", 1, config).allowed()).isTrue();
    assertThat(store.tryConsume("b", 1, config).allowed()).isTrue();
    assertThat(store.tryConsume("a", 1, config).allowed()).isFalse();
  }

  @Test
  void sweepRemovesFullBucketsOnly() {
    var config = bucket(2, Duration.ofSeconds(2));
    store.tryConsume("drained", 2, config);
    store.tryConsume("touched", 1, config);
    assertThat(store.size()).isEqualTo(2);

    store.sweep();
    assertThat(store.size()).isEqualTo(2);

    clock.advance(Duration.ofSeconds(1));
    store.sweep();
    // touched is full again after 1s, drained is not
    assertThat(store.size()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(1));
    store.sweep();
    assertThat(store.size()).isEqualTo(0);
  }

  @Test
  void sweptKeyBehavesLikeFresh() {
    var config = bucket(2, Duration.ofSeconds(1));
    store.tryConsume("k", 2, config);
    clock.advance(Duration.ofSeconds(1));
    store.sweep();

    assertThat(store.tryConsume("k", 2, config).allowed()).isTrue();
  }

  @Test
  void configChangeConvergesOnNewLimits() {
    var wide = bucket(10, Duration.ofSeconds(10));
    var narrow = bucket(5, Duration.ofSeconds(5));
    store.tryConsume("k", 1, wide);

    StoreResult result = store.tryConsume("k", 1, narrow);
    assertThat(result.remaining()).isLessThanOrEqualTo(4);
  }

  @Test
  void closeIsIdempotent() {
    store.close();
    store.close();
  }
}
