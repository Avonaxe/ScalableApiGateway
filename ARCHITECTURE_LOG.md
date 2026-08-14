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

## Phase 3: Load Balancing
**Status:** ⏳ Pending

*(etc...)*