package io.fluxlimit.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.fluxlimit.AlgorithmConfig;
import io.fluxlimit.internal.FakeClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

class InMemoryStoreConcurrencyTest {

  private static final int THREADS = 16;

  @Test
  void oneKeyNeverOverAdmits() throws Exception {
    // frozen clock: no refill, admitted must equal capacity exactly
    var clock = new FakeClock();
    var config = new AlgorithmConfig.TokenBucket(1_000, 1_000, Duration.ofSeconds(1));
    try (var store = new InMemoryStore(clock)) {
      LongAdder admitted = new LongAdder();
      runOnAllThreads(
          () -> {
            for (int i = 0; i < 500; i++) {
              if (store.tryConsume("hot", 1, config).allowed()) {
                admitted.increment();
              }
            }
          });
      assertThat(admitted.sum()).isEqualTo(1_000);
    }
  }

  @Test
  void spreadKeysEachAdmitExactlyCapacity() throws Exception {
    var clock = new FakeClock();
    var config = new AlgorithmConfig.TokenBucket(50, 50, Duration.ofSeconds(1));
    try (var store = new InMemoryStore(clock)) {
      LongAdder admitted = new LongAdder();
      runOnAllThreads(
          () -> {
            for (int i = 0; i < 500; i++) {
              if (store.tryConsume("key:" + (i % 8), 1, config).allowed()) {
                admitted.increment();
              }
            }
          });
      assertThat(admitted.sum()).isEqualTo(8 * 50);
    }
  }

  @Test
  void remainingIsNeverNegative() throws Exception {
    var clock = new FakeClock();
    var config = new AlgorithmConfig.TokenBucket(100, 100, Duration.ofSeconds(1));
    try (var store = new InMemoryStore(clock)) {
      LongAdder negatives = new LongAdder();
      runOnAllThreads(
          () -> {
            for (int i = 0; i < 500; i++) {
              if (store.tryConsume("hot", 3, config).remaining() < 0) {
                negatives.increment();
              }
            }
          });
      assertThat(negatives.sum()).isZero();
    }
  }

  private static void runOnAllThreads(Runnable task) throws Exception {
    CyclicBarrier start = new CyclicBarrier(THREADS);
    List<Thread> threads = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  start.await();
                  task.run();
                } catch (Exception e) {
                  synchronized (failures) {
                    failures.add(e);
                  }
                }
              });
      threads.add(thread);
      thread.start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(failures).isEmpty();
  }
}
