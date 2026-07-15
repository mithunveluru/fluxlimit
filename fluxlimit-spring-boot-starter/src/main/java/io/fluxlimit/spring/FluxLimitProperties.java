package io.fluxlimit.spring;

import io.fluxlimit.FailurePolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code fluxlimit.*} configuration.
 *
 * @param enabled turns the auto-configuration off entirely when false
 * @param algorithm which algorithm the limiter uses
 * @param limit maximum permits (bucket capacity / window limit)
 * @param period token bucket refill period or sliding window length
 * @param failurePolicy behavior when the store is unreachable
 * @param keyPrefix namespace prepended to every key
 * @param redis Redis store settings; requires the {@code fluxlimit-redis} dependency
 */
@ConfigurationProperties("fluxlimit")
public record FluxLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("token-bucket") Algorithm algorithm,
    @DefaultValue("100") long limit,
    @DefaultValue("60s") Duration period,
    @DefaultValue("allow") FailurePolicy failurePolicy,
    @DefaultValue("fluxlimit:") String keyPrefix,
    @DefaultValue Redis redis) {

  public enum Algorithm {
    TOKEN_BUCKET,
    SLIDING_WINDOW
  }

  /**
   * Redis store settings.
   *
   * @param enabled use the Redis store instead of in-memory
   * @param uri Redis URI for a starter-managed connection; omit to supply your own {@code
   *     StatefulRedisConnection} or {@code RateLimiterStore} bean
   * @param commandTimeout per-command bound; a slow answer is treated as a store failure
   */
  public record Redis(
      @DefaultValue("false") boolean enabled,
      String uri,
      @DefaultValue("50ms") Duration commandTimeout) {}
}
