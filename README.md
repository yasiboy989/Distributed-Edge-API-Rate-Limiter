# Distributed Edge API Rate Limiter & Quota Service

A REST service that answers one question fast: *may this request proceed?*

It implements a sliding-window rate limiter and per-client quota service in
**Java 21 / Spring Boot 3.3**, backed by Redis for the hot path and a
relational database for client/API-key metadata. Built for edge-style
deployments where a single check needs to be quick, accurate under
concurrency, and safe to depend on even when Redis isn't.

## Features

- **Sliding-window rate limiting** — a single atomic Redis Lua script per
  check, collision-free under concurrent load.
- **Single and batch checks** — check one key, or pipeline up to N checks in
  one round trip.
- **Peek and reset** — inspect a counter's state without consuming capacity,
  or clear it manually.
- **Per-client API keys** — SHA-256-hashed, cached in-process (Caffeine) so
  the hot path never hits the database once warm.
- **Configurable failure policy** — `FAIL_OPEN` (degrade gracefully) or
  `FAIL_CLOSED` (reject) when Redis is unreachable.
- **Multi-tenant namespacing** — the same rate-limit key from two different
  clients is tracked independently.
- **RFC 9457 problem-detail error responses**, standard `X-RateLimit-*`
  headers, and OpenAPI/Swagger documentation.

## Tech stack

Java 21 (virtual threads) · Spring Boot 3.3 (blocking MVC) · Lettuce /
`StringRedisTemplate` · H2 (dev/test) / PostgreSQL (prod) · Flyway ·
Caffeine · Spring Security (stateless, API-key auth) · JUnit 5 +
Testcontainers.

## Getting started

Requires a JDK 21 and a Redis instance. The Maven Wrapper is checked in, so
no local Maven install is needed.

```bash
# 1. Start Redis
docker run -d --name ratelimiter-redis -p 6379:6379 redis:7-alpine

# 2. Run the app (dev profile: in-memory H2, /h2-console enabled)
./mvnw spring-boot:run

# 3. Watch the console for the generated admin bootstrap key:
#    "No admin bootstrap key configured; generated one for this session: <key>"
#    (or set RATELIMITER_ADMIN_BOOTSTRAP_KEY yourself before starting)
```

The app listens on `:8080`. `GET /actuator/health` reports `UP` once Redis
is reachable.

### Running the tests

```bash
./mvnw verify
```

A couple of test classes use Testcontainers and are tagged `@Tag("docker")`;
if Docker isn't available in your environment, skip them explicitly rather
than losing the coverage silently:

```bash
./mvnw verify -DexcludedGroups=docker
```

## API usage

Mint a client key using the admin bootstrap key:

```bash
curl -X POST http://localhost:8080/v1/admin/keys \
  -H "X-API-Key: <bootstrap-key>" \
  -H "Content-Type: application/json" \
  -d '{"client_name": "Acme SaaS Gateway", "default_limit": 1000, "default_window_seconds": 3600}'
```

The response's `api_key` is shown **exactly once** — only its SHA-256 hash is
ever persisted. Use it against the hot-path endpoint:

```bash
curl -X POST http://localhost:8080/v1/check \
  -H "X-API-Key: <client-api-key>" \
  -H "Content-Type: application/json" \
  -d '{"key": "usr_99128_tier1", "limit": 100, "window_seconds": 60, "cost": 1}'
```

```json
{
  "allowed": true,
  "key": "usr_99128_tier1",
  "current_count": 1,
  "remaining": 99,
  "limit": 100,
  "window_seconds": 60,
  "reset_in_seconds": 60
}
```

Requests past the limit return `429` with a `Retry-After` header;
`X-RateLimit-*` headers are present on every response. Batch multiple checks
into one round trip:

```bash
curl -X POST http://localhost:8080/v1/check-batch \
  -H "X-API-Key: <client-api-key>" \
  -H "Content-Type: application/json" \
  -d '{
    "requests": [
      { "key": "usr_99128_tier1", "limit": 100, "window_seconds": 60, "cost": 1 },
      { "key": "ip_203_0_113_19", "limit": 10,  "window_seconds": 1,  "cost": 1 }
    ]
  }'
```

`check-batch` always returns `200`; inspect each entry's `allowed` field and
the top-level `all_allowed` flag.

## Configuration

| Property | Default | Description |
|---|---|---|
| `ratelimiter.admin.bootstrap-key` | *(env `RATELIMITER_ADMIN_BOOTSTRAP_KEY`)* | Admin key for `/v1/admin/**`. Required in the `prod` profile; auto-generated in `dev`. |
| `ratelimiter.redis.failure-mode` | `FAIL_OPEN` | `FAIL_OPEN` degrades gracefully (`200`, `degraded: true`) on Redis failure; `FAIL_CLOSED` returns `503`. |
| `ratelimiter.batch.max-size` | `50` | Max entries per `check-batch` request. |

## Design notes

- **Cost is bounded** to 1000 and to ≤ the effective limit. The algorithm
  stores one Redis ZSET member per unit of `cost`, so an unbounded `cost`
  would mean O(cost) memory and work inside a single Lua script call — a
  future high-`cost` use case would need a token-bucket or score-summing
  approach instead.
- **The p99 ≤ 10ms latency objective is a design target, not a measured
  result.** No load-testing harness exists yet in this repo.
- **Concurrency safety**: member names include a per-request unique ID, so
  concurrent requests in the same millisecond can't collide and silently
  undercount — verified under 200 concurrent virtual threads in the test
  suite.

## License

Not yet licensed for external use.
