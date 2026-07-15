package io.fluxlimit.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rate-limits a controller handler method. Rejected requests receive HTTP 429 with {@code
 * Retry-After} and {@code RateLimit-*} headers before argument resolution runs.
 *
 * {@snippet :
 * @RateLimit(key = "#{principal.name}")
 * @GetMapping("/api/search")
 * SearchResult search(...) { ... }
 * }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

  /**
   * SpEL template deriving the rate-limit key; {@code request} ({@code HttpServletRequest}) and
   * {@code principal} are available, e.g. {@code "#{principal.name}"} or {@code
   * "#{request.getHeader('X-Api-Key')}"}. Empty (the default) uses {@code request.getRemoteAddr()}
   * — forwarding headers are never trusted implicitly.
   */
  String key() default "";

  /** Permits this endpoint costs per request. */
  long permits() default 1;
}
