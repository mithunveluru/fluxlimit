package io.fluxlimit.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.fluxlimit.AlgorithmConfig;
import io.fluxlimit.internal.FakeClock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class SlidingWindowPropertyTest {

  @Property
  void neverAdmitsMoreThanLimitPerWindow(
      @ForAll @IntRange(min = 1, max = 100) int limit,
      @ForAll @IntRange(min = 100, max = 10_000) int windowMillis,
      @ForAll @Size(min = 1, max = 300) List<@IntRange(min = 0, max = 3_000) Integer> advances) {

    var clock = new FakeClock();
    var config = new AlgorithmConfig.SlidingWindow(limit, Duration.ofMillis(windowMillis));
    long windowNanos = Duration.ofMillis(windowMillis).toNanos();

    try (var store = new InMemoryStore(clock)) {
      Map<Long, Long> admittedPerWindow = new HashMap<>();
      for (int advanceMillis : advances) {
        clock.advance(Duration.ofMillis(advanceMillis));

        StoreResult result = store.tryConsume("k", 1, config);
        assertThat(result.remaining()).isBetween(0L, (long) limit);
        if (result.allowed()) {
          assertThat(result.retryAfter()).isEqualTo(Duration.ZERO);
          admittedPerWindow.merge(Math.floorDiv(clock.nanoTime(), windowNanos), 1L, Long::sum);
        } else {
          assertThat(result.retryAfter()).isPositive();
        }
      }
      assertThat(admittedPerWindow.values())
          .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo((long) limit));
    }
  }

  @Property
  void retryAfterIsHonoredOnAQuietKey(
      @ForAll @IntRange(min = 1, max = 50) int limit,
      @ForAll @IntRange(min = 100, max = 10_000) int windowMillis,
      @ForAll @IntRange(min = 1, max = 30) int attempts) {

    var clock = new FakeClock();
    var config = new AlgorithmConfig.SlidingWindow(limit, Duration.ofMillis(windowMillis));
    try (var store = new InMemoryStore(clock)) {
      for (int i = 0; i < attempts; i++) {
        StoreResult result = store.tryConsume("k", 1, config);
        if (!result.allowed()) {
          clock.advance(result.retryAfter());
          assertThat(store.tryConsume("k", 1, config).allowed()).isTrue();
        }
      }
    }
  }
}
