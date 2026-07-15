package io.fluxlimit.benchmarks;

import io.fluxlimit.RateLimitResult;
import io.fluxlimit.RateLimiter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

/** In-memory allow-path throughput: single thread, one contended key, spread keys. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class InMemoryBenchmark {

  RateLimiter limiter;

  @Setup
  public void setup() {
    // capacity and refill far above benchmark throughput: pure allow path
    limiter = RateLimiter.builder().tokenBucket(1_000_000_000, Duration.ofSeconds(1)).build();
  }

  @TearDown
  public void tearDown() {
    limiter.close();
  }

  @Benchmark
  public RateLimitResult singleThread() {
    return limiter.tryAcquire("bench");
  }

  @Benchmark
  @Threads(8)
  public RateLimitResult contendedOneKey() {
    return limiter.tryAcquire("hot");
  }

  @Benchmark
  @Threads(8)
  public RateLimitResult spreadKeys(ThreadKey key) {
    return limiter.tryAcquire(key.value);
  }

  @State(Scope.Thread)
  public static class ThreadKey {
    String value;

    @Setup
    public void setup() {
      value = "key:" + Thread.currentThread().threadId();
    }
  }
}
