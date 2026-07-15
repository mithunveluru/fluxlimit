package io.fluxlimit.redis;

import io.fluxlimit.AlgorithmConfig;
import io.fluxlimit.store.RateLimiterStore;
import io.fluxlimit.store.StoreResult;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Redis-backed store: one Lua script per algorithm, executed atomically in exactly one round trip.
 * The script reads Redis {@code TIME}, so every application server shares one clock — no clock
 * skew.
 *
 * <p>State self-expires via {@code PEXPIRE} once it is indistinguishable from absent. Requires
 * Redis 6.2+ (7.x recommended). The connection is owned by the caller and is never closed by this
 * store; commands are bounded by {@code commandTimeout} (default 50 ms) independently of the
 * connection's own timeout — a slow answer is treated as a failure.
 */
public final class RedisStore implements RateLimiterStore {

  private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMillis(50);
  private static final Duration SCRIPT_LOAD_TIMEOUT = Duration.ofSeconds(5);
  private static final String TOKEN_BUCKET_SCRIPT = loadScript("token_bucket.lua");
  private static final String SLIDING_WINDOW_SCRIPT = loadScript("sliding_window.lua");
  private static final long MICROS_PER_TOKEN = 1_000_000L;

  private final RedisScriptingAsyncCommands<String, String> commands;
  private final Duration commandTimeout;
  private final String tokenBucketSha;
  private final String slidingWindowSha;

  private RedisStore(
      RedisScriptingAsyncCommands<String, String> commands, Duration commandTimeout) {
    Objects.requireNonNull(commandTimeout, "commandTimeout");
    if (commandTimeout.isZero() || commandTimeout.isNegative()) {
      throw new IllegalArgumentException("commandTimeout must be positive");
    }
    this.commands = commands;
    this.commandTimeout = commandTimeout;
    // fail fast at startup if redis is unreachable
    this.tokenBucketSha = await(commands.scriptLoad(TOKEN_BUCKET_SCRIPT), SCRIPT_LOAD_TIMEOUT);
    this.slidingWindowSha = await(commands.scriptLoad(SLIDING_WINDOW_SCRIPT), SCRIPT_LOAD_TIMEOUT);
  }

  /** Creates a store on a standalone (or Sentinel-discovered) connection. */
  public static RedisStore create(StatefulRedisConnection<String, String> connection) {
    return create(connection, DEFAULT_COMMAND_TIMEOUT);
  }

  public static RedisStore create(
      StatefulRedisConnection<String, String> connection, Duration commandTimeout) {
    return new RedisStore(connection.async(), commandTimeout);
  }

  /** Creates a store on a Redis Cluster connection. Scripts touch one key, one slot. */
  public static RedisStore create(StatefulRedisClusterConnection<String, String> connection) {
    return create(connection, DEFAULT_COMMAND_TIMEOUT);
  }

  public static RedisStore create(
      StatefulRedisClusterConnection<String, String> connection, Duration commandTimeout) {
    return new RedisStore(connection.async(), commandTimeout);
  }

  @Override
  public StoreResult tryConsume(String key, long permits, AlgorithmConfig config) {
    return switch (config) {
      case AlgorithmConfig.TokenBucket tokenBucket ->
          execute(
              tokenBucketSha,
              TOKEN_BUCKET_SCRIPT,
              key,
              Long.toString(tokenBucket.capacity() * MICROS_PER_TOKEN),
              Long.toString(tokenBucket.refillTokens()),
              Long.toString(micros(tokenBucket.refillPeriod())),
              Long.toString(permits * MICROS_PER_TOKEN));
      case AlgorithmConfig.SlidingWindow slidingWindow ->
          execute(
              slidingWindowSha,
              SLIDING_WINDOW_SCRIPT,
              key,
              Long.toString(slidingWindow.limit()),
              Long.toString(micros(slidingWindow.window())),
              Long.toString(permits));
    };
  }

  private StoreResult execute(String sha, String script, String key, String... argv) {
    String[] keys = {key};
    List<?> reply;
    try {
      reply =
          await(commands.<List<?>>evalsha(sha, ScriptOutputType.MULTI, keys, argv), commandTimeout);
    } catch (RedisNoScriptException e) {
      // redis restarted, eval re-caches the script
      reply =
          await(commands.<List<?>>eval(script, ScriptOutputType.MULTI, keys, argv), commandTimeout);
    }
    boolean allowed = (Long) reply.get(0) == 1L;
    long remaining = (Long) reply.get(1);
    long retryAfterMicros = (Long) reply.get(2);
    return new StoreResult(
        allowed, remaining, allowed ? Duration.ZERO : Duration.ofNanos(retryAfterMicros * 1_000));
  }

  private static <T> T await(RedisFuture<T> future, Duration timeout) {
    try {
      return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RedisNoScriptException noScript) {
        throw noScript;
      }
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("redis command failed", e);
    } catch (TimeoutException e) {
      future.cancel(false);
      throw new IllegalStateException("redis command timed out after " + timeout, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for redis", e);
    }
  }

  private static long micros(Duration duration) {
    return duration.toNanos() / 1_000;
  }

  private static String loadScript(String name) {
    try (InputStream in = RedisStore.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing script resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
