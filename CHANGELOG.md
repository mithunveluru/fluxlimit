# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[SemVer](https://semver.org) (0.x: minor bumps may break).

## [Unreleased]

## [0.1.0] - 2026-07-15

First public release.

### Added

- `fluxlimit-core`: `RateLimiter` builder API, Token Bucket and Sliding Window Counter
  algorithms, in-memory store, weighted permits, fail-open/fail-closed `FailurePolicy`,
  optional Micrometer metrics. Zero runtime dependencies.
- `fluxlimit-redis`: Redis store — one atomic Lua round trip per check on the Redis server
  clock, self-expiring keys, standalone/Sentinel/Cluster support via Lettuce.
- `fluxlimit-spring-boot-starter`: auto-configuration from `fluxlimit.*` properties,
  `@RateLimit` annotation with SpEL keys, 429 responses with `RateLimit-*` and
  `Retry-After` headers.

[Unreleased]: https://github.com/mithunveluru/fluxlimit/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/mithunveluru/fluxlimit/releases/tag/v0.1.0
