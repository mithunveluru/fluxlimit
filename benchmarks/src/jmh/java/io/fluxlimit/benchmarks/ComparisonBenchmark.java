package io.fluxlimit.benchmarks;

import io.fluxlimit.RateLimiter;
import io.github.bucket4j.Bucket;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
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

/** Same-shape token bucket across libraries, allow path only. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ComparisonBenchmark {

  private static final long CAPACITY = 1_000_000_000L;

  RateLimiter fluxlimit;
  Bucket bucket4j;
  io.github.resilience4j.ratelimiter.RateLimiter resilience4j;

  @Setup
  public void setup() {
    fluxlimit = RateLimiter.builder().tokenBucket(CAPACITY, Duration.ofSeconds(1)).build();
    bucket4j =
        Bucket.builder()
            .addLimit(
                limit -> limit.capacity(CAPACITY).refillGreedy(CAPACITY, Duration.ofSeconds(1)))
            .build();
    resilience4j =
        io.github.resilience4j.ratelimiter.RateLimiter.of(
            "bench",
            RateLimiterConfig.custom()
                .limitForPeriod((int) Math.min(Integer.MAX_VALUE, CAPACITY))
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());
  }

  @TearDown
  public void tearDown() {
    fluxlimit.close();
  }

  @Benchmark
  public boolean fluxlimitSingle() {
    return fluxlimit.tryAcquire("bench").allowed();
  }

  @Benchmark
  public boolean bucket4jSingle() {
    return bucket4j.tryConsume(1);
  }

  @Benchmark
  public boolean resilience4jSingle() {
    return resilience4j.acquirePermission();
  }

  @Benchmark
  @Threads(8)
  public boolean fluxlimitContended() {
    return fluxlimit.tryAcquire("hot").allowed();
  }

  @Benchmark
  @Threads(8)
  public boolean bucket4jContended() {
    return bucket4j.tryConsume(1);
  }

  @Benchmark
  @Threads(8)
  public boolean resilience4jContended() {
    return resilience4j.acquirePermission();
  }
}
