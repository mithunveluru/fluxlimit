package example;

import io.fluxlimit.RateLimitResult;
import io.fluxlimit.RateLimiter;
import java.time.Duration;

/** Burst through a small bucket, wait, and watch it refill. */
public final class Main {

  public static void main(String[] args) throws InterruptedException {
    try (RateLimiter limiter =
        RateLimiter.builder().tokenBucket(5, Duration.ofSeconds(5)).build()) {

      System.out.println("bucket: 5 tokens, refills over 5s\n");
      for (int i = 1; i <= 7; i++) {
        print(i, limiter.tryAcquire("user:42"));
      }

      System.out.println("\nsleeping 2s...\n");
      Thread.sleep(2_000);

      for (int i = 8; i <= 10; i++) {
        print(i, limiter.tryAcquire("user:42"));
      }
    }
  }

  private static void print(int attempt, RateLimitResult result) {
    System.out.printf(
        "request %2d  %s  remaining=%d  retryAfter=%dms%n",
        attempt,
        result.allowed() ? "allowed" : "DENIED ",
        result.remaining(),
        result.retryAfter().toMillis());
  }

  private Main() {}
}
