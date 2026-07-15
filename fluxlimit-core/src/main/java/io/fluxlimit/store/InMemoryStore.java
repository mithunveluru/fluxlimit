package io.fluxlimit.store;

import io.fluxlimit.AlgorithmConfig;
import io.fluxlimit.internal.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single-JVM store. State lives in a {@link ConcurrentHashMap}; each transition runs atomically per
 * key inside {@code compute}.
 *
 * <p>One daemon thread periodically removes entries whose bucket has refilled completely — a full
 * bucket is indistinguishable from an absent one, so removal never changes behavior. Expect roughly
 * 100 bytes per active key.
 */
public final class InMemoryStore implements RateLimiterStore {

  private static final long SWEEP_INTERVAL_SECONDS = 60;

  private final ConcurrentHashMap<String, KeyState> states = new ConcurrentHashMap<>();
  private final Clock clock;
  private final ScheduledExecutorService sweeper;

  public InMemoryStore() {
    this(Clock.system());
  }

  InMemoryStore(Clock clock) {
    this.clock = clock;
    this.sweeper =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "fluxlimit-sweeper");
              thread.setDaemon(true);
              return thread;
            });
    var unused =
        sweeper.scheduleWithFixedDelay(
            this::sweep, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
  }

  @Override
  public StoreResult tryConsume(String key, long permits, AlgorithmConfig config) {
    return switch (config) {
      case AlgorithmConfig.TokenBucket tokenBucket -> tokenBucket(key, permits, tokenBucket);
      case AlgorithmConfig.SlidingWindow slidingWindow ->
          slidingWindow(key, permits, slidingWindow);
    };
  }

  private StoreResult tokenBucket(String key, long permits, AlgorithmConfig.TokenBucket config) {
    long now = clock.nanoTime();
    StoreResult[] result = new StoreResult[1];
    states.compute(
        key,
        (k, existing) -> {
          // algorithm switch on a key starts fresh
          TokenBucketState state =
              existing instanceof TokenBucketState tokenBucketState
                  ? tokenBucketState
                  : TokenBucketState.full(config, now);
          state.refill(config, now);
          result[0] = state.consume(permits);
          return state;
        });
    return result[0];
  }

  private StoreResult slidingWindow(
      String key, long permits, AlgorithmConfig.SlidingWindow config) {
    long now = clock.nanoTime();
    StoreResult[] result = new StoreResult[1];
    states.compute(
        key,
        (k, existing) -> {
          SlidingWindowState state =
              existing instanceof SlidingWindowState slidingWindowState
                  ? slidingWindowState
                  : SlidingWindowState.empty(config, now);
          result[0] = state.consume(config, now, permits);
          return state;
        });
    return result[0];
  }

  // ponytail: full-scan sweep is O(keys), swap to Caffeine if profiling shows pressure
  void sweep() {
    long now = clock.nanoTime();
    for (String key : states.keySet()) {
      // recheck under the bin lock
      states.computeIfPresent(key, (k, state) -> state.staleAt(now) ? null : state);
    }
  }

  int size() {
    return states.size();
  }

  @Override
  public void close() {
    sweeper.shutdownNow();
  }
}
