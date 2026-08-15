package com.apigateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class RateLimiterIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static final MockWebServer mockWebServer;

    static {
        try {
            mockWebServer = new MockWebServer();
            mockWebServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("gateway.rate-limit.enabled", () -> "true");
        registry.add("gateway.health-check.enabled", () -> "false");

        registry.add("gateway.routes[0].id", () -> "rate-limited-route");
        registry.add("gateway.routes[0].pathPattern", () -> "/api/**");
        registry.add("gateway.routes[0].targetUri", () -> "http://localhost:" + mockWebServer.getPort());
        registry.add("gateway.routes[0].stripPrefix", () -> "0");
        registry.add("gateway.routes[0].order", () -> "0");
        registry.add("gateway.routes[0].replenishRate", () -> "1");
        registry.add("gateway.routes[0].burstCapacity", () -> "2");
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
    void shouldAllowBurstAndRejectExcess() {
        // Enqueue 2 responses for the proxied requests
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        // Fire 3 concurrent requests
        List<Mono<Integer>> requests = List.of(
                client.get().uri("/api/test").exchangeToMono(r -> Mono.just(r.statusCode().value())),
                client.get().uri("/api/test").exchangeToMono(r -> Mono.just(r.statusCode().value())),
                client.get().uri("/api/test").exchangeToMono(r -> Mono.just(r.statusCode().value()))
        );

        StepVerifier.create(Flux.merge(requests).collectList())
                .assertNext(statuses -> {
                    long okCount = statuses.stream().filter(s -> s == 200).count();
                    long rateLimitedCount = statuses.stream().filter(s -> s == 429).count();
                    assertThat(okCount).isEqualTo(2);
                    assertThat(rateLimitedCount).isEqualTo(1);
                })
                .verifyComplete();
    }
}
