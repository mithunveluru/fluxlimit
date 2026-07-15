package io.fluxlimit.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.fluxlimit.AlgorithmConfig;
import io.fluxlimit.store.StoreResult;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisStoreIntegrationTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static RedisClient client;
  static StatefulRedisConnection<String, String> connection;
  static RedisStore store;

  @BeforeAll
  static void connect() {
    client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    connection = client.connect();
    store = RedisStore.create(connection, Duration.ofSeconds(2));
  }

  @AfterAll
  static void disconnect() {
    connection.close();
    client.shutdown();
  }

  private static String key() {
    return "test:" + UUID.randomUUID();
  }

  private static AlgorithmConfig.TokenBucket bucket(long capacity, Duration period) {
    return new AlgorithmConfig.TokenBucket(capacity, capacity, period);
  }

  @Test
  void tokenBucketAllowsUpToCapacityThenDenies() {
    var config = bucket(3, Duration.ofMinutes(1));
    String key = key();
    for (int i = 0; i < 3; i++) {
      StoreResult result = store.tryConsume(key, 1, config);
      assertThat(result.allowed()).isTrue();
      assertThat(result.remaining()).isEqualTo(2 - i);
    }
    StoreResult denied = store.tryConsume(key, 1, config);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfter()).isPositive();
  }

  @Test
  void tokenBucketRefillsOverRealTime() throws Exception {
    var config = bucket(2, Duration.ofMillis(500));
    String key = key();
    store.tryConsume(key, 2, config);
    assertThat(store.tryConsume(key, 1, config).allowed()).isFalse();

    Thread.sleep(700);
    assertThat(store.tryConsume(key, 1, config).allowed()).isTrue();
  }

  @Test
  void retryAfterIsHonored() throws Exception {
    var config = bucket(1, Duration.ofMillis(300));
    String key = key();
    store.tryConsume(key, 1, config);
    StoreResult denied = store.tryConsume(key, 1, config);
    assertThat(denied.allowed()).isFalse();

    Thread.sleep(denied.retryAfter().toMillis() + 50);
    assertThat(store.tryConsume(key, 1, config).allowed()).isTrue();
  }

  @Test
  void slidingWindowAllowsUpToLimitThenDenies() {
    var config = new AlgorithmConfig.SlidingWindow(5, Duration.ofMinutes(1));
    String key = key();
    for (int i = 0; i < 5; i++) {
      assertThat(store.tryConsume(key, 1, config).allowed()).isTrue();
    }
    assertThat(store.tryConsume(key, 1, config).allowed()).isFalse();
  }

  @Test
  void slidingWindowDecaysAcrossWindows() throws Exception {
    var config = new AlgorithmConfig.SlidingWindow(4, Duration.ofMillis(400));
    String key = key();
    for (int i = 0; i < 4; i++) {
      store.tryConsume(key, 1, config);
    }
    assertThat(store.tryConsume(key, 1, config).allowed()).isFalse();

    // two full windows later everything has decayed
    Thread.sleep(900);
    assertThat(store.tryConsume(key, 1, config).allowed()).isTrue();
  }

  @Test
  void weightedPermits() {
    var config = bucket(10, Duration.ofMinutes(1));
    String key = key();
    assertThat(store.tryConsume(key, 7, config).allowed()).isTrue();
    assertThat(store.tryConsume(key, 4, config).allowed()).isFalse();
    assertThat(store.tryConsume(key, 3, config).allowed()).isTrue();
  }

  @Test
  void stateCarriesTtlAndExpires() throws Exception {
    var config = bucket(1, Duration.ofMillis(200));
    String key = key();
    store.tryConsume(key, 1, config);

    Long ttl = connection.sync().pttl(key);
    // time to full plus one period of grace
    assertThat(ttl).isBetween(1L, 400L);

    Thread.sleep(600);
    assertThat(connection.sync().exists(key)).isZero();
  }

  @Test
  void expiredKeyBehavesLikeFresh() throws Exception {
    var config = bucket(2, Duration.ofMillis(200));
    String key = key();
    store.tryConsume(key, 2, config);
    Thread.sleep(700);

    assertThat(connection.sync().exists(key)).isZero();
    assertThat(store.tryConsume(key, 2, config).allowed()).isTrue();
  }

  @Test
  void recoversAfterScriptFlush() {
    var config = bucket(5, Duration.ofMinutes(1));
    String key = key();
    assertThat(store.tryConsume(key, 1, config).allowed()).isTrue();

    // simulates a redis restart losing the script cache
    connection.sync().scriptFlush();

    StoreResult result = store.tryConsume(key, 1, config);
    assertThat(result.allowed()).isTrue();
    assertThat(result.remaining()).isEqualTo(3);
    // and evalsha works again on the next call
    assertThat(store.tryConsume(key, 1, config).allowed()).isTrue();
  }

  @Test
  void parallelClientsNeverOverAdmit() throws Exception {
    // many clients, one key, one atomic answer
    var config = bucket(100, Duration.ofMinutes(1));
    String key = key();
    int threads = 16;
    LongAdder admitted = new LongAdder();
    List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
    List<Thread> workers = new ArrayList<>();
    CyclicBarrier start = new CyclicBarrier(threads);
    try {
      for (int t = 0; t < threads; t++) {
        StatefulRedisConnection<String, String> conn = client.connect();
        connections.add(conn);
        RedisStore parallelStore = RedisStore.create(conn, Duration.ofSeconds(2));
        Thread worker =
            new Thread(
                () -> {
                  try {
                    start.await();
                    for (int i = 0; i < 25; i++) {
                      if (parallelStore.tryConsume(key, 1, config).allowed()) {
                        admitted.increment();
                      }
                    }
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
        workers.add(worker);
        worker.start();
      }
      for (Thread worker : workers) {
        worker.join();
      }
    } finally {
      connections.forEach(StatefulRedisConnection::close);
    }
    assertThat(admitted.sum()).isEqualTo(100);
  }
}
