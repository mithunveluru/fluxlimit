package io.fluxlimit.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.fluxlimit.store.RateLimiterStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fail-open with a dead store: request passes, no rate-limit headers are emitted. */
@SpringBootTest(classes = {DegradedWebTest.App.class, DegradedWebTest.TestController.class})
@AutoConfigureMockMvc
class DegradedWebTest {

  @Autowired MockMvc mvc;

  @Test
  void degradedResponsesCarryNoRateLimitHeaders() throws Exception {
    mvc.perform(get("/limited"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("RateLimit-Limit"))
        .andExpect(header().doesNotExist("RateLimit-Remaining"));
  }

  @SpringBootApplication
  static class App {

    @Bean
    RateLimiterStore failingStore() {
      return (key, permits, config) -> {
        throw new IllegalStateException("store down");
      };
    }
  }

  @RestController
  static class TestController {

    @RateLimit
    @GetMapping("/limited")
    String limited() {
      return "ok";
    }
  }
}
