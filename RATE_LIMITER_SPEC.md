# Technical Specification — Distributed API Rate Limiter & Quota Service

**Version:** 2.0 (corrected & expanded)
**Target build time:** ~7 hours of focused agent-assisted work
**Audience:** AI coding agent (Claude Code) + human reviewer

---

## 0. How to use this document

This spec is written to be executed top-to-bottom. Section 12 contains a phased
execution plan with checkpoints — **follow that order**. Sections 1–11 are the
reference material each phase needs.

**Rules for the agent:**

1. Do not deviate from the stack in §2. If a dependency seems missing, ask before adding.
2. Implement the Lua script in §5 **verbatim**. It is subtly correct; do not "simplify" it.
3. After each phase in §12, run the stated verification command and stop if it fails.
4. Do not write the load-testing harness (§11) unless explicitly asked. It is out of scope.
5. Prefer boring, explicit code over clever abstraction. This is a latency-sensitive service.

---

## 1. Executive overview

A REST service in **Java 21 / Spring Boot 3.3** that answers one question very
fast: *may this request proceed?*

Decisions are made by a **sliding-window counter** implemented as a Redis
sorted set, mutated by a single atomic Lua script. The service also stores
client metadata (API keys, default policies) in a relational database, but that
data is cached in-process so it never sits on the hot path.

**Latency objective (revised — see §11):** p99 ≤ 10 ms *server-side*, measured
from controller entry to response commit, at 500 rps on a single instance with
Redis on the same host. This is a measurable target, not a marketing claim.

### What changed from v1 of this spec

The original draft had four defects and several gaps. They are fixed here, and
listed so reviewers can see the reasoning:

| # | Problem in v1 | Fix in v2 |
|---|---|---|
| 1 | Listed *Spring Data Reactive Redis* alongside blocking JPA, then told the agent to use `StringRedisTemplate`. Mixing WebFlux with blocking JPA is a classic time sink and thread-starvation source. | Fully blocking stack. `spring-boot-starter-web` + `StringRedisTemplate`. No WebFlux anywhere. (§2) |
| 2 | Lua script returned `{allowed, remaining, window}` — the third element was the *window size*, not time-to-reset. The documented `reset_in_seconds: 42` on an allowed response was unproducible. `current_count` was never returned at all. | Script now returns a 4-tuple `{allowed, current_count, remaining, reset_in_seconds}` and computes reset from the oldest in-window member on **both** branches. (§5) |
| 3 | `math.random(100000, 999999)` generated ZSET member names. Two requests in the same millisecond can collide; `ZADD` then overwrites instead of inserting, silently undercounting and letting extra traffic through. | Java passes a unique request ID (`ULID`/`UUID`) as `ARGV[4]`. Members are `{now}:{reqId}:{i}`, collision-free by construction. (§5) |
| 4 | `/v1/check` required `X-API-Key`, implying a DB round-trip per hot-path call. Incompatible with a 10 ms budget. Also no way to mint the *first* admin key — chicken-and-egg. | Caffeine cache in front of key lookup (§7.2), keys stored as SHA-256 hashes, and a bootstrap admin key supplied via environment variable (§7.1). |

Gaps filled: multi-tenant key namespacing (§4.1), Redis-authoritative clock
(§5.2), Redis-outage policy (§8), `cost` memory bound (§4.3), standard rate-limit
response headers (§6.6), RFC 9457 error bodies (§9), client default-policy
fallback (§6.1), peek and reset endpoints (§6.4, §6.5), batch pipelining and
size caps (§6.2), observability (§10).

---

## 2. Technology stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Java 21 (LTS) | Use records for DTOs. Virtual threads **on** (see §2.1). |
| Framework | Spring Boot 3.3+ | `spring-boot-starter-web` (MVC, blocking) |
| Redis client | Lettuce (Boot default) via `spring-boot-starter-data-redis` | Blocking `StringRedisTemplate` only |
| Metadata DB | H2 (dev/test, PostgreSQL compatibility mode) → PostgreSQL (prod) | `spring-boot-starter-data-jpa` |
| Migrations | Flyway | Portable ANSI SQL so one migration set serves H2 and Postgres |
| Cache | Caffeine (`com.github.ben-manes.caffeine`) | API-key lookup cache |
| Validation | `spring-boot-starter-validation` | Jakarta Bean Validation |
| Docs | `springdoc-openapi-starter-webmvc-ui` | Swagger UI at `/swagger-ui.html` |
| Metrics | `spring-boot-starter-actuator` + `micrometer-registry-prometheus` | |
| Boilerplate | Lombok | Only on JPA entities; DTOs are records |
| Testing | JUnit 5, `spring-boot-starter-test`, Testcontainers (`testcontainers-redis` or generic `GenericContainer`), Awaitility | |
| Build | Maven wrapper (`./mvnw`) | |

### 2.1 Virtual threads

Set `spring.threads.virtual.enabled=true`. This service is I/O-bound on Redis;
virtual threads give reactive-like concurrency with blocking code, which is
exactly the tradeoff we want. Do **not** pin virtual threads with
`synchronized` blocks around Redis calls.

---

## 3. Architecture

```
                      ┌──────────────────────────┐
   X-API-Key ───────► │  ApiKeyAuthFilter        │
                      │  SHA-256 → Caffeine →    │
                      │  (miss) → JPA            │
                      └───────────┬──────────────┘
                                  │ AuthenticatedClient(clientId, role, defaults)
                      ┌───────────▼──────────────┐
                      │  RateCheckController     │
                      │  @Valid, header emission │
                      └───────────┬──────────────┘
                                  │
                      ┌───────────▼──────────────┐
                      │  RateLimiterService      │
                      │  namespacing, EVALSHA,   │
                      │  pipelining, fail policy │
                      └───────────┬──────────────┘
                                  │ single round-trip
                      ┌───────────▼──────────────┐
                      │  Redis 7 — ZSET per key  │
                      │  sliding_window.lua      │
                      └──────────────────────────┘
```

Two data planes, deliberately separated:

- **Hot path** (`/v1/check`, `/v1/check-batch`): one Redis round-trip, zero DB queries after cache warm.
- **Control plane** (`/v1/admin/**`): DB-backed, low volume, no latency target.

---

## 4. Core algorithm

### 4.1 Redis key namespacing

**Never** use the client-supplied key directly as a Redis key. Two tenants
could both send `key: "user_1"` and clobber each other's counters.

```
Redis key = "rl:{clientId}:{sha256(userKey) first 16 hex chars}:{userKey truncated 64}"
```

Simpler acceptable form (use this): `rl:{clientId}:{userKey}` after validating
`userKey` against `^[A-Za-z0-9:_.\-]{1,128}$`. The pattern check prevents
injection of `{}`, newlines, or wildcards that would break `KEYS`/cluster
hashing. Reject non-matching keys with `400`.

### 4.2 Sliding window, precisely

For Redis key `K`, window `W` seconds, limit `L`, cost `C`, request id `R`, and
current Redis time `T` (ms):

1. `ZREMRANGEBYSCORE K -inf (T - W*1000)` — evict expired entries.
2. `N = ZCARD K` — count survivors.
3. If `N + C ≤ L`:
   - Insert `C` members scored `T`, named `T:R:i` for `i in 1..C`.
   - `PEXPIRE K (W*1000)`.
   - `reset = ceil((score_of_oldest + W*1000 - T) / 1000)`, floored at `0`.
   - Return `{1, N+C, L-(N+C), reset}`.
4. Else:
   - `reset = ceil((score_of_oldest + W*1000 - T) / 1000)`, floored at `1`.
   - Return `{0, N, max(0, L-N), reset}`.

Note `current_count` on the **allow** branch includes the current request
(matching `current_count + remaining == limit`), and on the **block** branch is
the pre-existing count. This matches the response examples in §6.

Note also that on a block with `C > 1`, `remaining` may be non-zero — e.g.
`L=100, N=99, C=5` is blocked but 1 slot remains. Reporting the true remaining
is more useful than hard-coding `0`, and callers should key off `allowed`.

### 4.3 The `cost` memory bound

This algorithm stores **one ZSET member per unit of cost**. A request with
`cost: 10000` writes 10,000 members. That is O(cost) memory and O(cost) work
inside the script, which blocks the whole Redis instance.

Mitigation: validate `cost ≤ 1000` **and** `cost ≤ limit` at the DTO layer
(§6.1). If a future requirement needs high costs, the algorithm must change to
a token-bucket or a score-summing approach — note that as a known limitation,
do not attempt it now.

---

## 5. The Lua script

Store at `src/main/resources/scripts/sliding_window.lua`. Load it into a
`RedisScript<List>` bean at startup; Lettuce handles `EVALSHA` with automatic
`EVAL` fallback on `NOSCRIPT`.

### 5.1 Script

```lua
-- Sliding window counter, atomic.
-- KEYS[1] = namespaced rate limit key
-- ARGV[1] = window seconds
-- ARGV[2] = limit
-- ARGV[3] = cost
-- ARGV[4] = unique request id (collision-free member naming)
-- ARGV[5] = fixed now-millis, or "" to use Redis server clock
-- Returns  {allowed, current_count, remaining, reset_in_seconds}

local key    = KEYS[1]
local window = tonumber(ARGV[1])
local limit  = tonumber(ARGV[2])
local cost   = tonumber(ARGV[3])
local reqId  = ARGV[4]

local now
if ARGV[5] and ARGV[5] ~= '' then
  now = tonumber(ARGV[5])
else
  local t = redis.call('TIME')
  now = (tonumber(t[1]) * 1000) + math.floor(tonumber(t[2]) / 1000)
end

local windowMs = window * 1000

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)

local count = redis.call('ZCARD', key)

local function resetIn(floorValue)
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  if #oldest == 0 then
    return window
  end
  local r = math.ceil((tonumber(oldest[2]) + windowMs - now) / 1000)
  if r < floorValue then
    return floorValue
  end
  return r
end

if count + cost <= limit then
  for i = 1, cost do
    redis.call('ZADD', key, now, now .. ':' .. reqId .. ':' .. i)
  end
  redis.call('PEXPIRE', key, windowMs)
  return {1, count + cost, limit - (count + cost), resetIn(0)}
else
  return {0, count, math.max(0, limit - count), resetIn(1)}
end
```

### 5.2 Why `redis.call('TIME')`

If each application instance passes its own `System.currentTimeMillis()`, clock
skew across instances corrupts the shared window — a lagging instance evicts
entries a fast instance just wrote. Reading the clock from Redis makes the
window authoritative and skew-immune.

This is safe on Redis 5+ because scripts replicate by **effects**, not by
script body, so non-determinism inside the script does not desync replicas.
(On Redis 3.x/4.x this would have been rejected. We target Redis 7.)

`ARGV[5]` exists solely so tests can inject a fixed clock and assert
window-expiry behaviour without sleeping. Production always passes `""`.

### 5.3 Return marshalling

Lua numeric arrays arrive in Java as `List<Long>`. Read positionally:

```java
List<Long> r = redisTemplate.execute(script, List.of(redisKey),
        String.valueOf(windowSeconds), String.valueOf(limit),
        String.valueOf(cost), requestId, "");
boolean allowed = r.get(0) == 1L;
long currentCount = r.get(1);
long remaining = r.get(2);
long resetInSeconds = r.get(3);
```

Guard against `r == null` or `r.size() != 4` — treat as a Redis failure and
apply the §8 policy.

---

## 6. REST API

All request/response bodies use `snake_case`. Configure
`spring.jackson.property-naming-strategy=SNAKE_CASE` rather than annotating
every field.

### 6.1 `POST /v1/check`

Headers: `Content-Type: application/json`, `X-API-Key: <client key>`

**Request**

```json
{
  "key": "usr_99128_tier1",
  "limit": 100,
  "window_seconds": 60,
  "cost": 1
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `key` | string | yes | matches `^[A-Za-z0-9:_.\-]{1,128}$` |
| `limit` | integer | no | `1..1_000_000`; defaults to the client's `default_limit` |
| `window_seconds` | integer | no | `1..86_400`; defaults to the client's `default_window_seconds` |
| `cost` | integer | no | `1..1000`, default `1`; **must be ≤ effective limit** |

The `limit`/`window_seconds` fallback is why `ApiClient` stores defaults —
without it those columns are dead weight. Cross-field rule `cost ≤ limit` needs
a class-level constraint (custom `@ValidCost` or a `@AssertTrue` method),
because a request with `cost > limit` can never succeed and should be a `400`,
not a permanent `429`.

**`200 OK` — allowed**

```json
{
  "allowed": true,
  "key": "usr_99128_tier1",
  "current_count": 14,
  "remaining": 86,
  "limit": 100,
  "window_seconds": 60,
  "reset_in_seconds": 42
}
```

**`429 Too Many Requests` — blocked**

```json
{
  "allowed": false,
  "key": "usr_99128_tier1",
  "current_count": 100,
  "remaining": 0,
  "limit": 100,
  "window_seconds": 60,
  "reset_in_seconds": 18
}
```

Both responses carry the headers in §6.6. The `429` additionally carries
`Retry-After`.

### 6.2 `POST /v1/check-batch`

**Request** — max **50** entries; more returns `400`. Duplicate keys within one
batch are evaluated in array order (each consumes capacity).

```json
{
  "requests": [
    { "key": "usr_99128_tier1", "limit": 100, "window_seconds": 60, "cost": 1 },
    { "key": "ip_203_0_113_19", "limit": 10,  "window_seconds": 1,  "cost": 1 }
  ]
}
```

**`200 OK`** — always `200`, even when individual entries are blocked. The
caller inspects each result. Order of `results` matches the request array.

```json
{
  "results": [
    { "key": "usr_99128_tier1", "allowed": true,  "current_count": 14,  "remaining": 86, "limit": 100, "window_seconds": 60, "reset_in_seconds": 42 },
    { "key": "ip_203_0_113_19", "allowed": false, "current_count": 10,  "remaining": 0,  "limit": 10,  "window_seconds": 1,  "reset_in_seconds": 1 }
  ],
  "all_allowed": false
}
```

**Implementation requirement:** execute the batch in a **single pipelined
round-trip** via `redisTemplate.executePipelined(...)`. A sequential loop turns
a 50-item batch into 50 RTTs and blows the latency budget. Note that pipelined
execution returns raw deserialized results — you may need
`SessionCallback`/`RedisCallback` with the script SHA. Verify the return
marshalling with a test; this is the single most likely place to lose an hour.

### 6.3 `POST /v1/admin/keys`

Requires the **admin** role (§7.1).

**Request**

```json
{
  "client_name": "Acme SaaS Gateway",
  "default_limit": 1000,
  "default_window_seconds": 3600
}
```

**`201 Created`**

```json
{
  "client_id": "cli_8812a01",
  "client_name": "Acme SaaS Gateway",
  "api_key": "ratelimit_sec_9918231023912a",
  "default_limit": 1000,
  "default_window_seconds": 3600,
  "created_at": "2026-08-10T12:00:00Z"
}
```

The plaintext `api_key` is returned **exactly once, here**. Only its SHA-256
hash is persisted (§7.2). Include a `warning` field or document this in the
OpenAPI description.

Also implement:

- `GET /v1/admin/keys` — list clients (never returns key material)
- `DELETE /v1/admin/keys/{clientId}` — revoke; sets `active=false` and evicts the Caffeine entry

### 6.4 `GET /v1/check/{key}` — peek

Read-only. Returns current state without consuming capacity. Uses `limit` and
`window_seconds` query params, falling back to client defaults. Implement as a
second small Lua script (`peek.lua`) that does `ZREMRANGEBYSCORE` + `ZCARD` +
oldest-score lookup and returns `{current_count, remaining, reset_in_seconds}`.
Always `200`, even when at capacity — this endpoint reports, it does not decide.

### 6.5 `DELETE /v1/admin/counters/{key}` — reset

Admin-only. `DEL` on the namespaced key. Invaluable during manual testing;
takes five minutes to write.

### 6.6 Response headers

Emit on every `/v1/check` response (aligned with the IETF `RateLimit` header
draft, using the widely-deployed `X-` forms for compatibility):

| Header | Value |
|---|---|
| `X-RateLimit-Limit` | effective limit |
| `X-RateLimit-Remaining` | remaining |
| `X-RateLimit-Reset` | reset_in_seconds |
| `Retry-After` | reset_in_seconds — **only on 429** |

---

## 7. Security

### 7.1 Roles and bootstrap

Two roles:

- **ADMIN** — may call `/v1/admin/**`. Sourced from configuration:
  `ratelimiter.admin.bootstrap-key` (env `RATELIMITER_ADMIN_BOOTSTRAP_KEY`).
  The app **must refuse to start** if this is unset and the active profile is
  `prod`. In `dev`, generate a random one and log it at INFO on startup.
  This solves the chicken-and-egg problem: you cannot create the first client
  key with a client key.
- **CLIENT** — may call `/v1/check*`. Sourced from the `api_clients` table.

An admin key is also accepted on `/v1/check*` (convenient for smoke tests),
using a synthetic `clientId` of `admin`.

### 7.2 Key storage and verification

API keys are **high-entropy random secrets**, not user passwords. Therefore:

- Generate 32 bytes from `SecureRandom`, Base62/URL-safe-Base64 encode, prefix
  `ratelimit_sec_`.
- Store **SHA-256 hex** of the full key string. Do **not** use BCrypt — it is
  deliberately slow (~50–100 ms), which is fatal on a hot path, and its
  work-factor protection is only needed for low-entropy inputs.
- Verify by hashing the presented key and looking up the hash. Use a
  constant-time comparison where a comparison is done at all.

### 7.3 The lookup cache

```java
Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofSeconds(60))
    .recordStats()
    .build();
```

Cache **negative** results too (short TTL, e.g. 10 s) so a flood of invalid
keys cannot hammer the DB. Evict explicitly on revoke. Expose hit ratio via
Micrometer (§10).

### 7.4 Spring Security configuration

Use a single `OncePerRequestFilter` registered in the `SecurityFilterChain`:

- CSRF disabled (stateless API, no cookies)
- Session management `STATELESS`
- Permit: `/actuator/health`, `/actuator/prometheus`, `/swagger-ui/**`, `/v3/api-docs/**`, `/h2-console/**` (dev profile only)
- Everything else authenticated
- Missing/invalid key → `401` with a problem-detail body (§9), never a redirect to a login page

---

## 8. Redis failure policy

Redis being down is not hypothetical. Make the behaviour explicit and
configurable:

```yaml
ratelimiter:
  redis:
    failure-mode: FAIL_OPEN   # FAIL_OPEN | FAIL_CLOSED
```

- `FAIL_OPEN` (default): on `RedisConnectionFailureException`, timeout, or a
  malformed script result, return `allowed: true` with `remaining: -1` and a
  `degraded: true` flag in the body. Log at WARN, increment a counter. Rationale:
  a rate limiter should not become the outage.
- `FAIL_CLOSED`: return `503` with a problem detail. For deployments where
  unmetered traffic is worse than no traffic.

Set Lettuce timeouts aggressively — `spring.data.redis.timeout: 200ms` — so a
hung Redis does not hold request threads.

---

## 9. Error handling

Use Spring 6's `ProblemDetail` (RFC 9457) via `@RestControllerAdvice`.

```json
{
  "type": "https://api.example.com/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "cost must be less than or equal to limit",
  "instance": "/v1/check",
  "errors": [
    { "field": "cost", "message": "must be less than or equal to limit" }
  ]
}
```

Error catalogue:

| Condition | Status | `title` |
|---|---|---|
| Bean-validation failure (`MethodArgumentNotValidException`) | 400 | Validation failed |
| Malformed JSON (`HttpMessageNotReadableException`) | 400 | Malformed request body |
| Batch exceeds 50 entries | 400 | Batch too large |
| Missing `X-API-Key` | 401 | Missing API key |
| Unknown / revoked key (`InvalidKeyException`) | 401 | Invalid API key |
| Client key used on `/v1/admin/**` | 403 | Insufficient privileges |
| Unknown client on delete | 404 | Client not found |
| Rate limit exceeded | 429 | Rate limit exceeded (uses the §6.1 body, **not** a problem detail) |
| Redis unavailable + `FAIL_CLOSED` | 503 | Rate limiter unavailable |
| Anything else | 500 | Internal error (no stack trace, log with a correlation id) |

The `429` is deliberately the domain response shape, not a problem detail —
callers need `remaining` and `reset_in_seconds` programmatically.

---

## 10. Observability

Actuator endpoints exposed: `health`, `info`, `prometheus`, `metrics`.

Custom Micrometer instruments:

| Name | Type | Tags |
|---|---|---|
| `ratelimiter.check.duration` | Timer | `outcome` = allowed \| blocked \| degraded |
| `ratelimiter.redis.script.duration` | Timer | — |
| `ratelimiter.redis.failures` | Counter | `mode` |
| `ratelimiter.apikey.cache` | Gauge set | wire Caffeine via `CaffeineCacheMetrics` |
| `ratelimiter.batch.size` | DistributionSummary | — |

Health: add a custom `RedisHealthIndicator` contribution so `/actuator/health`
reflects Redis reachability without failing the whole liveness probe (report
Redis under a separate component; keep liveness on the app itself).

Logging: MDC with a `requestId` per request; log denials at DEBUG, never log
API keys or their hashes.

---

## 11. Performance validation (OUT OF SCOPE for the initial build)

Do **not** build this now. Recording it so the claim in §1 is falsifiable later.

- Harness: k6 or Gatling, 500 rps constant arrival rate, 5-minute run, 10,000 distinct keys.
- Metric: server-side p50/p95/p99 from `ratelimiter.check.duration`, excluding JVM warm-up (discard first 60 s).
- Environment: app and Redis on the same host, Redis 7-alpine, 2 vCPU / 4 GB.
- Report `redis-cli --latency` and `INFO commandstats` alongside.

Until this exists, describe the target as an objective, not an achieved result.

---

## 12. Execution plan

Commit after each phase. Run the verification command before moving on.

### Phase 1 — Scaffold (45 min)

Maven project, `com.ratelimiter`, Java 21. Dependencies per §2. Two profiles:
`dev` (H2 + `/h2-console`) and `test`. `application.yml`:

```yaml
spring:
  application: { name: ratelimiter }
  threads: { virtual: { enabled: true } }
  jackson:
    property-naming-strategy: SNAKE_CASE
    default-property-inclusion: non_null
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 200ms
      lettuce:
        pool: { max-active: 32, max-idle: 16, min-idle: 4 }
  datasource:
    url: jdbc:h2:mem:ratelimiter;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate: { ddl-auto: validate }
    open-in-view: false
  flyway: { enabled: true, locations: classpath:db/migration }
  h2: { console: { enabled: true, path: /h2-console } }

management:
  endpoints: { web: { exposure: { include: health,info,prometheus,metrics } } }

ratelimiter:
  admin:
    bootstrap-key: ${RATELIMITER_ADMIN_BOOTSTRAP_KEY:}
  redis:
    failure-mode: FAIL_OPEN
  batch:
    max-size: 50
```

`ddl-auto: validate` plus Flyway is intentional — `update` silently diverges
from the migration set and bites you on the Postgres switch.

**Verify:** `./mvnw spring-boot:run` starts; `GET /actuator/health` returns `UP`.

### Phase 2 — Redis engine (1 h 15 min)

`scripts/sliding_window.lua` and `peek.lua`, a `RedisScriptConfig` producing
`RedisScript<List>` beans, and `RateLimiterService` with:

```java
RateCheckResult check(String clientId, String key, int limit, int windowSeconds, int cost);
List<RateCheckResult> checkBatch(String clientId, List<RateCheckCommand> commands);
PeekResult peek(String clientId, String key, int limit, int windowSeconds);
void reset(String clientId, String key);
```

Namespacing per §4.1, failure policy per §8, request IDs via `UUID.randomUUID()`.

**Verify:** integration test against a Testcontainers Redis — 100 calls with
`limit=100` all allowed, the 101st blocked.

### Phase 3 — Persistence & keys (1 h)

Flyway migration `V1__init.sql` (portable SQL):

```sql
CREATE TABLE api_clients (
    id                     BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    client_id              VARCHAR(64)  NOT NULL UNIQUE,
    client_name            VARCHAR(255) NOT NULL,
    api_key_hash           VARCHAR(64)  NOT NULL UNIQUE,
    default_limit          INTEGER      NOT NULL,
    default_window_seconds INTEGER      NOT NULL,
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP    NOT NULL
);
CREATE INDEX idx_api_clients_key_hash ON api_clients (api_key_hash);
```

`ApiClient` entity, `ApiClientRepository`, `KeyManagementService` (generation,
hashing, revoke + cache eviction), `ApiKeyCache` (Caffeine per §7.3).

**Verify:** unit test — created key's SHA-256 resolves to the client; revoked
key does not.

### Phase 4 — Security filter (45 min)

`SecurityConfig` + `ApiKeyAuthFilter` per §7.4. Bootstrap-key handling per §7.1.
`AuthenticatedClient` available to controllers (via a `HandlerMethodArgumentResolver`
or `SecurityContext` principal — either is fine, pick one and be consistent).

**Verify:** `curl` without a key → `401`; with the dev bootstrap key → passes;
client key on `/v1/admin/keys` → `403`.

### Phase 5 — Controllers & errors (1 h)

`RateCheckController`, `KeyAdminController`, DTOs as records with validation
annotations, the `cost ≤ limit` cross-field constraint, `GlobalExceptionHandler`
per §9, headers per §6.6, batch pipelining per §6.2.

**Verify:** the three §6 example payloads produce the documented bodies and
status codes.

### Phase 6 — Tests (1 h 15 min)

- `RateCheckControllerTest` — `@WebMvcTest`, mocked service, asserts `200`/`429`, header presence, `400` on bad input.
- `RateLimiterServiceIT` — Testcontainers Redis. Cases: allow to exactly `limit`; block at `limit+1`; window expiry using the `ARGV[5]` injected clock (no `Thread.sleep`); `cost > 1` accounting; tenant isolation (same user key, two clientIds, independent counters).
- `ConcurrencyIT` — 200 virtual threads against `limit=100` on one key; assert **exactly** 100 allowed. This is the test that catches the v1 `math.random` collision bug; it must not be skipped.
- `KeyManagementServiceTest` — hashing, revoke, cache eviction.
- `FailureModeTest` — stub a failing Redis connection, assert `FAIL_OPEN` allows with `degraded: true` and `FAIL_CLOSED` returns `503`.

**Verify:** `./mvnw verify` green. If Docker is unavailable in the environment,
tag Testcontainers tests `@Tag("docker")` and make them skippable via
`-DexcludedGroups=docker` — but say so explicitly rather than silently deleting
coverage.

### Phase 7 — Packaging (45 min)

`Dockerfile` (multi-stage: Maven build → `eclipse-temurin:21-jre-alpine`,
non-root user, layered jar). `docker-compose.yml`:

```yaml
services:
  redis:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "no", "--maxmemory-policy", "allkeys-lru"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 2s
      timeout: 2s
      retries: 15
  app:
    build: .
    ports: ["8080:8080"]
    environment:
      REDIS_HOST: redis
      RATELIMITER_ADMIN_BOOTSTRAP_KEY: ${RATELIMITER_ADMIN_BOOTSTRAP_KEY:-dev_admin_key_change_me}
      SPRING_PROFILES_ACTIVE: dev
    depends_on:
      redis: { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 5s
      retries: 10
```

Plus `README.md`: quickstart, the three example `curl` commands, the §1 defect
table, and the §4.3 / §11 known limitations.

**Verify:** `docker compose up` → mint a client key → 100 allowed checks → the
101st returns `429`.

**Running total: ~6 h 45 min.** Roughly three hours of slack inside ten.

---

## 13. Project layout

```
src/main/java/com/ratelimiter/
├── RatelimiterApplication.java
├── config/
│   ├── RedisConfig.java             # StringRedisTemplate, timeouts
│   ├── RedisScriptConfig.java       # RedisScript<List> beans
│   ├── CacheConfig.java             # Caffeine + CaffeineCacheMetrics
│   ├── SecurityConfig.java
│   ├── OpenApiConfig.java
│   └── RateLimiterProperties.java   # @ConfigurationProperties("ratelimiter")
├── security/
│   ├── ApiKeyAuthFilter.java
│   ├── AuthenticatedClient.java     # record(clientId, role, defaultLimit, defaultWindowSeconds)
│   └── ApiKeyCache.java
├── controller/
│   ├── RateCheckController.java
│   └── KeyAdminController.java
├── service/
│   ├── RateLimiterService.java
│   ├── RedisKeyBuilder.java
│   └── KeyManagementService.java
├── model/
│   ├── dto/
│   │   ├── RateCheckRequest.java    ├── RateCheckResponse.java
│   │   ├── BatchCheckRequest.java   ├── BatchCheckResponse.java
│   │   ├── PeekResponse.java
│   │   ├── CreateClientRequest.java └── CreateClientResponse.java
│   ├── domain/
│   │   ├── RateCheckResult.java     └── FailureMode.java
│   └── entity/ApiClient.java
├── repository/ApiClientRepository.java
├── validation/ValidCost.java        # + CostValidator
└── exception/
    ├── GlobalExceptionHandler.java
    ├── InvalidKeyException.java
    ├── BatchTooLargeException.java
    └── RateLimiterUnavailableException.java

src/main/resources/
├── application.yml, application-dev.yml, application-test.yml
├── db/migration/V1__init.sql
└── scripts/sliding_window.lua, scripts/peek.lua
```

---

## 14. Definition of done

- [ ] `./mvnw verify` passes
- [ ] `docker compose up` yields a healthy app + Redis
- [ ] All five §6 endpoints behave as documented, including headers
- [ ] `ConcurrencyIT` proves exactly-`limit` admission under 200 concurrent callers
- [ ] Tenant isolation test passes (same user key, different clients)
- [ ] No plaintext API key in the database or in any log line
- [ ] App refuses to start in `prod` without a bootstrap admin key
- [ ] Swagger UI documents every endpoint and error shape
- [ ] `/actuator/prometheus` exposes the §10 metrics
- [ ] README states the §4.3 cost bound and the §11 unvalidated latency target honestly
