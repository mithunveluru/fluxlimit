package example;

import io.fluxlimit.spring.RateLimit;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Try it: {@code curl -i 'localhost:8080/api/search?user=alice'} — the sixth call within ten
 * seconds returns 429 with Retry-After.
 */
@RestController
public class SearchController {

  @RateLimit(key = "#{request.getParameter('user') ?: request.remoteAddr}")
  @GetMapping("/api/search")
  public Map<String, String> search(@RequestParam(defaultValue = "anonymous") String user) {
    return Map.of("user", user, "result", "42 things found");
  }
}
