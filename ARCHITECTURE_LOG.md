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

## Phase 2: Dynamic Routing
**Status:** ⏳ Pending

---

## Phase 3: Load Balancing
**Status:** ⏳ Pending

*(etc...)*