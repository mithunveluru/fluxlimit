// Manual load test for the example app (not run in CI: shared runners make load numbers fiction).
//
//   ./gradlew :examples:spring-boot:bootRun
//   k6 run examples/spring-boot/k6-load.js
//
// Each virtual user has its own rate-limit key, so expect roughly
// fluxlimit.limit requests per user per period to pass and the rest to 429.
import http from "k6/http";
import { check } from "k6";

export const options = {
  vus: 50,
  duration: "15s",
};

export default function () {
  const res = http.get(`http://localhost:8080/api/search?user=vu-${__VU}`);
  check(res, {
    "status is 200 or 429": (r) => r.status === 200 || r.status === 429,
    "429 carries Retry-After": (r) => r.status !== 429 || r.headers["Retry-After"] !== undefined,
  });
}
