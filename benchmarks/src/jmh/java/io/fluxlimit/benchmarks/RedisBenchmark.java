package io.fluxlimit.benchmarks;

import io.fluxlimit.RateLimitResult;
import io.fluxlimit.RateLimiter;
import io.fluxlimit.redis.RedisStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
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

/**
 * Latency against a real Redis (default {@code redis://localhost:6379}, override with {@code
 * -Dfluxlimit.bench.redis=...}). Dominated by the network round trip — that is the point.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class RedisBenchmark {

  RedisClient client;
  StatefulRedisConnection<String, String> connection;
  RateLimiter limiter;

  @Setup
  public void setup() {
    String uri = System.getProperty("fluxlimit.bench.redis", "redis://localhost:6379");
    client = RedisClient.create(uri);
    connection = client.connect();
    limiter =
        RateLimiter.builder()
            .tokenBucket(1_000_000_000, Duration.ofSeconds(1))
            .store(RedisStore.create(connection, Duration.ofSeconds(1)))
            .build();
  }

  @TearDown
  public void tearDown() {
    limiter.close();
    connection.close();
    client.shutdown();
  }

  @Benchmark
  public RateLimitResult singleClient() {
    return limiter.tryAcquire("bench");
  }

  @Benchmark
  @Threads(8)
  public RateLimitResult eightClients() {
    return limiter.tryAcquire("hot");
  }
}
