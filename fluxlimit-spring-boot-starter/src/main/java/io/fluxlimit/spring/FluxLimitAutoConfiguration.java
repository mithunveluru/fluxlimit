package io.fluxlimit.spring;

import io.fluxlimit.RateLimiter;
import io.fluxlimit.redis.RedisStore;
import io.fluxlimit.store.RateLimiterStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Builds one {@link RateLimiter} from {@code fluxlimit.*} properties and, in servlet apps,
 * registers the {@link RateLimit} interceptor. Every bean backs off to a user-defined one.
 */
@AutoConfiguration
@EnableConfigurationProperties(FluxLimitProperties.class)
@ConditionalOnProperty(
    prefix = "fluxlimit",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FluxLimitAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  RateLimiter fluxlimitRateLimiter(
      FluxLimitProperties properties,
      ObjectProvider<RateLimiterStore> store,
      ObjectProvider<MeterRegistry> meterRegistry) {
    RateLimiter.Builder builder = RateLimiter.builder();
    switch (properties.algorithm()) {
      case TOKEN_BUCKET -> builder.tokenBucket(properties.limit(), properties.period());
      case SLIDING_WINDOW -> builder.slidingWindow(properties.limit(), properties.period());
    }
    RateLimiterStore configuredStore = store.getIfAvailable();
    if (configuredStore != null) {
      builder.store(configuredStore);
    } else if (properties.redis().enabled()) {
      // never fall back to in-memory silently
      throw new IllegalStateException(
          "fluxlimit.redis.enabled=true but no store is available: add the fluxlimit-redis"
              + " dependency, then set fluxlimit.redis.uri or define a StatefulRedisConnection"
              + " or RateLimiterStore bean");
    }
    builder.failurePolicy(properties.failurePolicy()).keyPrefix(properties.keyPrefix());
    meterRegistry.ifAvailable(builder::meterRegistry);
    return builder.build();
  }

  /** Active only when the application also depends on {@code fluxlimit-redis}. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(RedisStore.class)
  @ConditionalOnProperty(prefix = "fluxlimit.redis", name = "enabled", havingValue = "true")
  static class RedisStoreConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "fluxlimit.redis", name = "uri")
    RedisClient fluxlimitRedisClient(FluxLimitProperties properties) {
      return RedisClient.create(properties.redis().uri());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    StatefulRedisConnection<String, String> fluxlimitRedisConnection(RedisClient client) {
      return client.connect();
    }

    // cluster deployments define their own RateLimiterStore bean instead
    @Bean
    @ConditionalOnMissingBean
    RateLimiterStore fluxlimitRedisStore(
        StatefulRedisConnection<String, String> connection, FluxLimitProperties properties) {
      return RedisStore.create(connection, properties.redis().commandTimeout());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(HandlerInterceptor.class)
  static class WebConfiguration {

    @Bean
    WebMvcConfigurer fluxlimitWebMvcConfigurer(RateLimiter limiter) {
      return new WebMvcConfigurer() {
        @Override
        public void addInterceptors(InterceptorRegistry registry) {
          registry.addInterceptor(new RateLimitInterceptor(limiter));
        }
      };
    }
  }
}
