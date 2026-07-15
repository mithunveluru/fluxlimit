package io.fluxlimit.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = {
      RateLimitInterceptorWebTest.App.class,
      RateLimitInterceptorWebTest.TestController.class
    },
    properties = {"fluxlimit.limit=2", "fluxlimit.period=10m"})
@AutoConfigureMockMvc
class RateLimitInterceptorWebTest {

  @Autowired MockMvc mvc;

  @Test
  void limitsAnnotatedEndpointWithHeaders() throws Exception {
    mvc.perform(get("/limited"))
        .andExpect(status().isOk())
        .andExpect(header().string("RateLimit-Limit", "2"))
        .andExpect(header().string("RateLimit-Remaining", "1"));
    mvc.perform(get("/limited")).andExpect(status().isOk());

    mvc.perform(get("/limited"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("RateLimit-Remaining", "0"))
        .andExpect(header().exists("RateLimit-Reset"))
        .andExpect(header().exists("Retry-After"));
  }

  @Test
  void spelKeyIsolatesCallers() throws Exception {
    mvc.perform(get("/keyed").param("user", "a")).andExpect(status().isOk());
    mvc.perform(get("/keyed").param("user", "a")).andExpect(status().isOk());
    mvc.perform(get("/keyed").param("user", "a")).andExpect(status().isTooManyRequests());

    mvc.perform(get("/keyed").param("user", "b")).andExpect(status().isOk());
  }

  @Test
  void unannotatedEndpointIsUnlimited() throws Exception {
    for (int i = 0; i < 10; i++) {
      mvc.perform(get("/free"))
          .andExpect(status().isOk())
          .andExpect(header().doesNotExist("RateLimit-Limit"));
    }
  }

  @SpringBootApplication
  static class App {}

  @RestController
  static class TestController {

    @RateLimit
    @GetMapping("/limited")
    String limited() {
      return "ok";
    }

    @RateLimit(key = "#{request.getParameter('user')}")
    @GetMapping("/keyed")
    String keyed() {
      return "ok";
    }

    @GetMapping("/free")
    String free() {
      return "ok";
    }
  }
}
