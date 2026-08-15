package com.apigateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CircuitBreakerIntegrationTest {

    private static MockWebServer mockWebServer;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.health-check.enabled", () -> "false");
        registry.add("gateway.rate-limit.enabled", () -> "false");

        registry.add("gateway.routes[0].id", () -> "cb-test-service");
        registry.add("gateway.routes[0].pathPattern", () -> "/api/cb/**");
        registry.add("gateway.routes[0].targetUri", () -> "http://localhost:" + mockWebServer.getPort());
        registry.add("gateway.routes[0].stripPrefix", () -> "0");
        registry.add("gateway.routes[0].order", () -> "0");
        registry.add("gateway.routes[0].circuitBreakerEnabled", () -> "true");

        registry.add("gateway.routes[1].id", () -> "dummy1");
        registry.add("gateway.routes[1].pathPattern", () -> "/dummy1/**");
        registry.add("gateway.routes[1].targetUri", () -> "http://localhost:1");
        registry.add("gateway.routes[1].stripPrefix", () -> "0");
        registry.add("gateway.routes[1].order", () -> "999");

        registry.add("gateway.routes[2].id", () -> "dummy2");
        registry.add("gateway.routes[2].pathPattern", () -> "/dummy2/**");
        registry.add("gateway.routes[2].targetUri", () -> "http://localhost:2");
        registry.add("gateway.routes[2].stripPrefix", () -> "0");
        registry.add("gateway.routes[2].order", () -> "999");
    }

    @BeforeAll
    static void setUpAll() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        mockWebServer.shutdown();
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Test
    void shouldTripCircuitBreakerAfterConsecutiveFailures() throws InterruptedException {
        // Enqueue 5 HTTP 500 responses to trip the circuit breaker
        for (int i = 0; i < 5; i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("error"));
        }

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        // Fire 5 requests that will fail downstream (500)
        for (int i = 0; i < 5; i++) {
            StepVerifier.create(
                    client.get()
                            .uri("/api/cb/test")
                            .exchangeToMono(response -> {
                                if (response.statusCode().value() == 500) {
                                    return response.bodyToMono(String.class);
                                }
                                return Mono.just("UNEXPECTED:" + response.statusCode().value());
                            })
            )
                    .assertNext(body -> assertThat(body).isEqualTo("error"))
                    .verifyComplete();
        }

        // Verify the MockWebServer received exactly 5 requests so far
        assertThat(mockWebServer.getRequestCount()).isEqualTo(5);

        // Fire the 6th request — circuit should be OPEN, so it should fail fast with 503
        StepVerifier.create(
                client.get()
                        .uri("/api/cb/test")
                        .exchangeToMono(response -> {
                            if (response.statusCode().value() == 503) {
                                return response.bodyToMono(String.class);
                            }
                            return Mono.just("UNEXPECTED:" + response.statusCode().value());
                        })
        )
                .assertNext(body -> {
                    assertThat(body).contains("\"error\":\"Service Unavailable\"");
                    assertThat(body).contains("Circuit Breaker is OPEN");
                })
                .verifyComplete();

        // Verify the MockWebServer still only received 5 requests (6th was blocked)
        assertThat(mockWebServer.getRequestCount()).isEqualTo(5);
    }
}
