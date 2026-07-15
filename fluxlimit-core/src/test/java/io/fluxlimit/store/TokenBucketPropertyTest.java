package io.fluxlimit.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.fluxlimit.AlgorithmConfig;
import io.fluxlimit.internal.FakeClock;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class TokenBucketPropertyTest {

  @Property
  void neverOverAdmitsAndInvariantsHold(
      @ForAll @IntRange(min = 1, max = 200) int capacity,
      @ForAll @IntRange(min = 1, max = 60_000) int periodMillis,
      @ForAll @Size(min = 1, max = 200) List<@IntRange(min = 0, max = 5_000) Integer> advances) {

    var clock = new FakeClock();
    var config =
        new AlgorithmConfig.TokenBucket(capacity, capacity, Duration.ofMillis(periodMillis));
    try (var store = new InMemoryStore(clock)) {
      long admitted = 0;
      long totalElapsedNanos = 0;
      for (int advanceMillis : advances) {
        clock.advance(Duration.ofMillis(advanceMillis));
        totalElapsedNanos += Duration.ofMillis(advanceMillis).toNanos();

        StoreResult result = store.tryConsume("k", 1, config);
        if (result.allowed()) {
          admitted++;
          assertThat(result.retryAfter()).isEqualTo(Duration.ZERO);
        } else {
          assertThat(result.retryAfter()).isPositive();
        }
        assertThat(result.remaining()).isBetween(0L, (long) capacity);
      }

      // admitted can never exceed initial capacity plus everything refilled
      BigInteger refilled =
          BigInteger.valueOf(totalElapsedNanos)
              .multiply(BigInteger.valueOf(capacity))
              .divide(BigInteger.valueOf(Duration.ofMillis(periodMillis).toNanos()));
      assertThat(BigInteger.valueOf(admitted))
          .isLessThanOrEqualTo(BigInteger.valueOf(capacity).add(refilled).add(BigInteger.ONE));
    }
  }

  @Property
  void retryAfterIsAlwaysHonored(
      @ForAll @IntRange(min = 1, max = 100) int capacity,
      @ForAll @IntRange(min = 1, max = 60_000) int periodMillis,
      @ForAll @IntRange(min = 1, max = 50) int drains) {

    var clock = new FakeClock();
    var config =
        new AlgorithmConfig.TokenBucket(capacity, capacity, Duration.ofMillis(periodMillis));
    try (var store = new InMemoryStore(clock)) {
      for (int i = 0; i < drains; i++) {
        StoreResult result = store.tryConsume("k", 1, config);
        if (!result.allowed()) {
          clock.advance(result.retryAfter());
          assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
        }
      }
    }
  }
}
