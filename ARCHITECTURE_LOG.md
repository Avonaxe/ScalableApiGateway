# Scalable API Gateway: Architecture & Implementation Log

This document serves as the living architectural log for the API Gateway project. It is appended to **only after** a phase has been successfully implemented, tested, and reviewed.

---

## Table of Contents
*(This will grow as phases are completed)*
1. [Phase 1: Basic Reverse Proxy](#phase-1-basic-reverse-proxy)

---

## Phase 1: Basic Reverse Proxy & Core Transport
**Status:** ✅ Completed & Verified  
**Date Completed:** 2026-08-14

### Objective Achieved
Built a non-blocking, transparent HTTP reverse proxy using Java 21, Spring WebFlux, and Reactor Netty without relying on pre-built gateway frameworks. The gateway receives requests on a catch-all route, strips transport-specific hop-by-hop headers, forwards the payload as a reactive byte stream to a downstream service, and streams the response back to the client with constant memory overhead.

### Key Components Implemented
* `GatewayController`: Catch-all `@RestController` mapping `/**` across all HTTP verbs (`GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `OPTIONS`, `HEAD`, `TRACE`).
* `ProxyService`: Core proxy engine orchestrating request construction, header mapping, body forwarding, and streaming response writing.
* `WebClientConfig`: High-throughput HTTP transport configured with a pooled `ConnectionProvider` (500 max connections, 30s idle timeout, 60s max lifetime) and disabled automatic redirects.
* `HeaderSanitization`: RFC 7230 compliant utility stripping standard hop-by-hop headers (`Connection`, `Keep-Alive`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`, `Host`) and dynamically stripping token headers declared in the `Connection` header value.
* `GatewayProperties`: Externalized configuration binding for downstream target URLs.

### Architecture Decision Records (ADRs)
* **ADR-001 (Reactive Transport):** Adopted Spring WebFlux and Reactor Netty over Servlet-based MVC to allow event-loop multiplexing, ensuring high concurrency with minimal thread count.
* **ADR-002 (Zero Memory Aggregation):** Handled request/response payloads strictly as `Flux<DataBuffer>`. Disallowed buffering payloads into `byte[]` or `String` to prevent memory spikes on large payloads.

### Edge Cases Handled
* **Downstream Outage:** Caught transport failures (`WebClientRequestException`, connection refused, timeouts) and mapped them to clean `502 Bad Gateway` JSON error responses.
* **Dynamic Hop Headers:** Handled multi-value `Connection` header tokens to prevent hop-by-hop header leakage.

### Verification Proof
* `ProxyIntegrationTest` executed with `MockWebServer`:
  * Verified end-to-end `GET` proxying, query parameter preservation, and hop-by-hop header removal.
  * Verified end-to-end `POST` JSON streaming.
  * Verified `502 Bad Gateway` emission when the downstream service is unreachable.

---

## Phase 2: Dynamic Routing Engine & Path Transformation
**Status:** ✅ Completed & Verified  
**Date Completed:** 2026-08-14

### Objective Achieved
Replaced the static downstream endpoint with a dynamic, order-aware routing engine. The gateway inspects incoming request paths, matches them against ordered route patterns using Spring's high-performance `PathPatternParser`, applies prefix stripping transformations, and dispatches to the corresponding downstream service or emits an RFC-compliant `404 Not Found`.

### Key Components Implemented
* `Route`: Domain model defining routing rules (`id`, `pathPattern`, `targetUri`, `order`, `stripPrefix`).
* `RouteRepository` & `InMemoryRouteRepository`: Abstraction layer for fetching routes, eagerly sorted by priority (`order`).
* `RouteMatcher`: Reactive component resolving incoming `ServerWebExchange` paths to the highest-priority matching `Route`.
* `PathTransformer`: Non-blocking path utility that strips $N$ leading path segments while preserving query strings and trailing slashes.
* `DynamicRoutingIntegrationTest`: Integration suite using multiple `MockWebServer` instances verifying multi-route dispatching, path rewriting, route priority precedence, and 404 error payloads.

### Architecture Decision Records (ADRs)
* **ADR-003 (Path Pattern Matching Strategy):** Selected `PathPatternParser` over regular expressions (`java.util.regex`) and AntPathMatcher. `PathPatternParser` parses URLs as structured `PathContainer` tokens, providing lower memory overhead and significantly faster evaluation in non-blocking event loops.
* **ADR-004 (Reactive Void Stream Protection):** Enforced `.thenReturn(true)` on `Mono<Void>` response completion streams to prevent `switchIfEmpty()` fallback misfires on HTTP 200 OK empty responses.

### Edge Cases Handled
* **Unmapped Routes:** Unmatched requests instantly return HTTP `404 Not Found` with a descriptive JSON payload without hitting network I/O.
* **Route Precedence:** Specific routes with lower `order` numbers take precedence over broad catch-all patterns (`/**`).
* **Query Parameter Preservation:** Path segment transformations preserve raw and encoded query strings intact.

### Verification Proof
* `mvn clean verify` executed:
  * 7/7 tests passed (3 proxy tests + 4 dynamic routing tests).
  * Verified multi-service routing to distinct mock ports.
  * Verified route priority resolution.

---

## Phase 3: Load Balancing Engine (Round-Robin)
**Status:** ✅ Completed & Verified  
**Date Completed:** 2026-08-15

### Objective Achieved
Introduced a load-balancing layer to distribute incoming traffic across multiple instances of a downstream service. Implemented a lock-free Round-Robin strategy that operates seamlessly within the reactive pipeline, gracefully handling routes with zero available instances.

### Key Components Implemented
* `LoadBalancer`: Core strategy interface for instance selection (`Mono<URI> choose(String serviceId, List<URI> instances)`).
* `RoundRobinLoadBalancer`: Thread-safe, non-blocking implementation using `AtomicInteger` counters mapped by `serviceId`.
* `NoInstancesAvailableException`: Custom exception mapped to a clean HTTP 503 response.

### Architecture Decision Records (ADRs)
* **ADR-005 (Lock-Free Load Balancing & Overflow Safety):** Chose `AtomicInteger` with a bitwise mask `(counter.getAndIncrement() & 0x7FFFFFFF) % size` over `ReentrantLock` or `synchronized` blocks. This prevents thread contention in the Reactor Netty event loops and completely eliminates the `Integer.MAX_VALUE` negative modulo `ArrayIndexOutOfBoundsException` trap.
* **ADR-006 (Strategy Pattern for LB):** Decoupled the load-balancing logic from the `ProxyService` via a strict interface, allowing future implementations (e.g., Least Connections, Consistent Hashing) to be injected via Spring's DI without modifying the core proxy pipeline.

### Edge Cases Handled
* **Empty Instance Pools:** Routes configured with zero targets instantly return `503 Service Unavailable` without attempting network I/O.
* **Backward Configuration Compatibility:** Migrated `Route` to use a list of `targetUris` while preserving parsing for existing singular `targetUri` YAML configurations.

### Verification Proof
* `mvn clean verify` executed:
  * 9/9 integration tests passed.
  * Verified requests strictly alternate (1 -> 2 -> 3 -> 1) across multiple MockWebServers.
  * Verified 503 generation on empty route targets.

---

## Phase 4: Active Health Checking
**Status:** ✅ Completed & Verified  
**Date Completed:** 2026-08-15

### Objective Achieved
Introduced a background supervisor to continuously monitor downstream instance health. The gateway actively pings a configurable health endpoint (e.g., `/actuator/health`) on all target URIs at a defined interval. Unhealthy instances are automatically evicted from the load balancer rotation, preventing traffic from reaching unresponsive servers.

### Key Components Implemented
* `HealthMonitor`: Background service executing periodic, non-blocking health checks using `WebClient` on a `boundedElastic` scheduler. Maintains a thread-safe `ConcurrentHashMap` of instance statuses.
* `Route` enhancements: Added `healthCheckPath` to allow per-service health endpoint configuration.
* `HealthCheckIntegrationTest`: Validates that a server returning HTTP 500 on its health endpoint receives zero proxy traffic from the load balancer.

### Architecture Decision Records (ADRs)
* **ADR-007 (Optimistic Health Default):** By default, instances are assumed healthy (`true`) upon gateway startup. This prevents a "cold-start storm" where the gateway rejects all traffic with 503s while waiting for the first asynchronous health check sweep to complete.
* **ADR-008 (Scheduler Isolation):** Health-check intervals are scheduled on `Schedulers.boundedElastic()` rather than the main Netty event loop (`Schedulers.parallel()`) to ensure that polling overhead or timeout stalls do not degrade the latency of inbound client request processing.

### Edge Cases Handled
* **Timeout Protection:** Health checks enforce a strict reactive timeout (default 2s) to ensure unresponsive servers are quickly flagged as offline.
* **Graceful Teardown:** Bound the health-check `Disposable` subscription to the Spring lifecycle (`@PreDestroy`) to prevent thread leakage upon shutdown.

### Verification Proof
* `mvn clean verify` executed:
  * 10/10 integration tests passed.
  * Verified that traffic routes exclusively to healthy instances, dynamically bypassing instances that fail their active health checks.

  ---

  ## Phase 5: Distributed Rate Limiting (Redis Token Bucket)
**Status:** ✅ Completed & Verified  
**Date Completed:** 2026-08-15

### Objective Achieved
Transformed the Gateway from a stateless proxy into a stateful, distributed system. Implemented a pluggable middleware filter chain and introduced a Redis-backed Token Bucket rate limiter. This protects backend microservices from traffic spikes by enforcing precise, per-route and per-client-IP traffic limits across all horizontally scaled gateway instances.

### Key Components Implemented
* `GatewayFilter` / `GatewayFilterChain`: Pluggable middleware architecture (Chain of Responsibility pattern) for intercepting requests before they reach the proxy.
* `RedisRateLimitFilter`: Intercepts traffic and evaluates limits using a custom Redis Lua script. Injects `X-RateLimit-Remaining` headers or terminates the chain with an HTTP `429 Too Many Requests`.
* **Token Bucket Lua Script:** Calculates exact token replenishment based on millisecond elapsed time, tracking fractional tokens atomically inside Redis to prevent race conditions.

### Architecture Decision Records (ADRs)
* **ADR-009 (Pluggable Filter Chain):** Decoupled request inspection from proxy execution. All cross-cutting concerns (rate limiting, auth, caching) must now implement `GatewayFilter`, ensuring the core proxy transport layer remains untouched.
* **ADR-010 (Redis Lua Scripting for Concurrency):** Rate limit math (Read timestamp -> Calculate elapsed -> Decrement -> Write back) is executed as a single Lua script inside Redis. This guarantees atomic evaluation, preventing race conditions when multiple gateway replicas process requests simultaneously.

### Edge Cases Handled
* **IP Spoofing Protection:** The filter extracts the client IP strictly from the first hop of the `X-Forwarded-For` header, falling back to the raw `RemoteAddress` if no proxy exists.
* **Opt-In Degradation:** The Redis filter is annotated with `@ConditionalOnProperty`. If Redis is unavailable in an environment, rate limiting can be safely disabled without crashing the gateway.

### Verification Proof
* `mvn clean verify` executed.
* `RateLimiterIntegrationTest` executed via Testcontainers (spins up isolated `redis:7-alpine` Docker instance).
* Verified that configuring a `burstCapacity` of 2 correctly allows 2 concurrent requests through while explicitly rejecting the 3rd concurrent request with HTTP 429.

---

## Phase 6: Architectural Refactoring & JWT Authentication
**Status:** ✅ Completed & Verified  
**Date Completed:** 2026-08-15

### Objective Achieved
Refactored the Gateway filter chain to execute Route Resolution at the highest precedence, ensuring all subsequent filters (like Rate Limiting) have access to route-specific configurations. Implemented a custom JWT Authentication filter that acts as a gatekeeper, verifying cryptographic signatures and expirations before allowing traffic into the internal network.

### Key Components Implemented
* `RouteMatchingFilter`: Extracts routing logic out of the proxy service and places it at the front of the `GatewayFilterChain` (Order: 0). Stores the resolved `Route` in the exchange attributes.
* `JwtAuthFilter`: Intercepts requests for routes flagged with `requiresAuth: true`. Validates the `Authorization: Bearer <token>` header, immediately returning HTTP 401 Unauthorized for missing or invalid tokens.
* `JwtUtil`: Cryptographic utility for HMAC-SHA256 token generation and validation.

### Architecture Decision Records (ADRs)
* **ADR-011 (Route Resolution Precedence):** Moved Route Resolution to the very first step in the middleware pipeline. This architectural fix ensures that cross-cutting concerns (Rate Limiting, Authentication) can apply varying rules based on the matched destination route.
* **ADR-012 (Native JWT Validation):** Implemented native JWT validation using `jjwt` instead of relying on heavy frameworks like Spring Security. This keeps the gateway lightweight, strictly non-blocking, and focused purely on token validation rather than complex session or role-based access control.

### Edge Cases Handled
* **The Reactive Void Trap:** Implemented `.then(Mono.just(true))` projections in the `RouteMatchingFilter` to prevent `.switchIfEmpty()` fallbacks from eagerly firing on successful `Mono<Void>` completions, which previously caused `UnsupportedOperationException` and dropped TCP connections.
* **Committed Response Guards:** Added `isCommitted()` checks to all error renderers (404, 502, 503) to prevent double-writing headers if a downstream failure occurs mid-stream.

### Verification Proof
* `mvn clean verify` executed (15/15 tests passing).
* Verified `JwtAuthIntegrationTest`: requests without tokens yield 401; invalid tokens yield 401; valid tokens yield 200.
* Verified `RateLimiterIntegrationTest` successfully applies limits utilizing the newly refactored route resolution.