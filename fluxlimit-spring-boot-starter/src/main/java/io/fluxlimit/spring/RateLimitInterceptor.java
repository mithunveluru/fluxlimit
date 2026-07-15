package io.fluxlimit.spring;

import io.fluxlimit.RateLimitResult;
import io.fluxlimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.expression.Expression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RateLimit} before argument resolution. Emits {@code RateLimit-Limit} and {@code
 * RateLimit-Remaining} on every limited response, plus {@code RateLimit-Reset} and {@code
 * Retry-After} on 429. Degraded results emit no rate-limit headers.
 */
public final class RateLimitInterceptor implements HandlerInterceptor {

  private static final SpelExpressionParser PARSER = new SpelExpressionParser();
  private static final TemplateParserContext TEMPLATE = new TemplateParserContext("#{", "}");

  private final RateLimiter limiter;
  private final ConcurrentHashMap<String, Expression> expressions = new ConcurrentHashMap<>();

  public RateLimitInterceptor(RateLimiter limiter) {
    this.limiter = Objects.requireNonNull(limiter, "limiter");
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }
    RateLimit annotation = handlerMethod.getMethodAnnotation(RateLimit.class);
    if (annotation == null) {
      return true;
    }

    RateLimitResult result = limiter.tryAcquire(key(annotation, request), annotation.permits());

    if (!result.degraded()) {
      response.setHeader("RateLimit-Limit", Long.toString(result.limit()));
      response.setHeader("RateLimit-Remaining", Long.toString(Math.max(0, result.remaining())));
    }
    if (result.allowed()) {
      return true;
    }
    if (!result.degraded()) {
      long retrySeconds = Math.max(1, (result.retryAfter().toMillis() + 999) / 1000);
      response.setHeader("RateLimit-Reset", Long.toString(retrySeconds));
      response.setHeader("Retry-After", Long.toString(retrySeconds));
    }
    response.setStatus(429);
    return false;
  }

  private String key(RateLimit annotation, HttpServletRequest request) {
    if (annotation.key().isEmpty()) {
      // no implicit forwarding-header trust
      return request.getRemoteAddr();
    }
    Expression expression =
        expressions.computeIfAbsent(
            annotation.key(), spel -> PARSER.parseExpression(spel, TEMPLATE));
    Object value = expression.getValue(new StandardEvaluationContext(new Root(request)));
    if (value == null || value.toString().isEmpty()) {
      throw new IllegalStateException("@RateLimit key expression evaluated to nothing");
    }
    return value.toString();
  }

  /** SpEL root: exposes {@code request} and {@code principal}. */
  public static final class Root {

    private final HttpServletRequest request;

    Root(HttpServletRequest request) {
      this.request = request;
    }

    public HttpServletRequest getRequest() {
      return request;
    }

    public Principal getPrincipal() {
      return request.getUserPrincipal();
    }
  }
}
