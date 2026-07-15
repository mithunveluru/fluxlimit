package io.fluxlimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.fluxlimit.FailurePolicy;
import io.fluxlimit.RateLimitResult;
import io.fluxlimit.RateLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Owns its container so it can kill redis mid-test. */
@Testcontainers
class RedisFailureIntegrationTest {

  @Container
  final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Test
  void redisDeathDegradesInsteadOfFailing() {
    RedisClient client =
        RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    try (StatefulRedisConnection<String, String> connection = client.connect()) {
      RateLimiter limiter =
          RateLimiter.builder()
              .tokenBucket(5, Duration.ofMinutes(1))
              .store(RedisStore.create(connection, Duration.ofMillis(500)))
              .failurePolicy(FailurePolicy.ALLOW)
              .build();

      RateLimitResult healthy = limiter.tryAcquire("user:1");
      assertThat(healthy.allowed()).isTrue();
      assertThat(healthy.degraded()).isFalse();

      redis.stop();

      RateLimitResult degraded = limiter.tryAcquire("user:1");
      assertThat(degraded.allowed()).isTrue();
      assertThat(degraded.degraded()).isTrue();

      // cooldown answers instantly without touching the dead store
      long start = System.nanoTime();
      RateLimitResult cooled = limiter.tryAcquire("user:1");
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
      assertThat(cooled.degraded()).isTrue();
      assertThat(elapsedMillis).isLessThan(100);
    } finally {
      client.shutdown();
    }
  }

  @Test
  void commandTimeoutIsAFailure() {
    RedisClient client =
        RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    try (StatefulRedisConnection<String, String> connection = client.connect()) {
      RedisStore store = RedisStore.create(connection, Duration.ofNanos(1));
      assertThatExceptionOfType(RuntimeException.class)
          .isThrownBy(
              () ->
                  store.tryConsume(
                      "k",
                      1,
                      new io.fluxlimit.AlgorithmConfig.SlidingWindow(5, Duration.ofMinutes(1))))
          .withMessageContaining("timed out");
    } finally {
      client.shutdown();
    }
  }
}
