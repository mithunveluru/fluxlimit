package io.fluxlimit.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.fluxlimit.RateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class FluxLimitAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxLimitAutoConfiguration.class));

  @Test
  void createsInMemoryLimiterByDefault() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(RateLimiter.class);
          RateLimiter limiter = context.getBean(RateLimiter.class);
          assertThat(limiter.tryAcquire("k").allowed()).isTrue();
          assertThat(limiter.tryAcquire("k").limit()).isEqualTo(100);
        });
  }

  @Test
  void propertiesConfigureTheLimiter() {
    runner
        .withPropertyValues(
            "fluxlimit.algorithm=sliding-window", "fluxlimit.limit=2", "fluxlimit.period=1m")
        .run(
            context -> {
              RateLimiter limiter = context.getBean(RateLimiter.class);
              assertThat(limiter.tryAcquire("k").allowed()).isTrue();
              assertThat(limiter.tryAcquire("k").allowed()).isTrue();
              assertThat(limiter.tryAcquire("k").allowed()).isFalse();
            });
  }

  @Test
  void disabledPropertyBacksOffEntirely() {
    runner
        .withPropertyValues("fluxlimit.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(RateLimiter.class));
  }

  @Test
  void userDefinedLimiterWins() {
    runner
        .withUserConfiguration(CustomLimiterConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RateLimiter.class);
              assertThat(context.getBean(RateLimiter.class).tryAcquire("k").limit()).isEqualTo(7);
            });
  }

  @Test
  void redisEnabledWithoutStoreFailsStartup() {
    // fluxlimit-redis is not on this test classpath
    runner
        .withPropertyValues("fluxlimit.redis.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("fluxlimit-redis");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomLimiterConfiguration {
    @Bean
    RateLimiter custom() {
      return RateLimiter.builder().tokenBucket(7, Duration.ofMinutes(1)).build();
    }
  }
}
