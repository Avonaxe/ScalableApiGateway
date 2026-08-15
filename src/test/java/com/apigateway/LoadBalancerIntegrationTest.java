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
class LoadBalancerIntegrationTest {

    private static MockWebServer server1;
    private static MockWebServer server2;
    private static MockWebServer server3;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.health-check.enabled", () -> "false");
        // Route 0: multi-instance user service
        registry.add("gateway.routes[0].id", () -> "lb-user-service");
        registry.add("gateway.routes[0].pathPattern", () -> "/api/v1/users/**");
        registry.add("gateway.routes[0].targetUris[0]", () -> "http://localhost:" + server1.getPort());
        registry.add("gateway.routes[0].targetUris[1]", () -> "http://localhost:" + server2.getPort());
        registry.add("gateway.routes[0].targetUris[2]", () -> "http://localhost:" + server3.getPort());
        registry.add("gateway.routes[0].stripPrefix", () -> "2");
        registry.add("gateway.routes[0].order", () -> "0");

        // Route 1: dummy to keep indices contiguous
        registry.add("gateway.routes[1].id", () -> "dummy1");
        registry.add("gateway.routes[1].pathPattern", () -> "/dummy1/**");
        registry.add("gateway.routes[1].targetUri", () -> "http://localhost:1");
        registry.add("gateway.routes[1].stripPrefix", () -> "0");
        registry.add("gateway.routes[1].order", () -> "999");

        // Route 2: dummy to keep indices contiguous
        registry.add("gateway.routes[2].id", () -> "dummy2");
        registry.add("gateway.routes[2].pathPattern", () -> "/dummy2/**");
        registry.add("gateway.routes[2].targetUri", () -> "http://localhost:2");
        registry.add("gateway.routes[2].stripPrefix", () -> "0");
        registry.add("gateway.routes[2].order", () -> "999");

        // Route 3: empty target uris for 503 test
        registry.add("gateway.routes[3].id", () -> "empty-service");
        registry.add("gateway.routes[3].pathPattern", () -> "/empty/**");
        registry.add("gateway.routes[3].stripPrefix", () -> "0");
        registry.add("gateway.routes[3].order", () -> "0");
    }

    @BeforeAll
    static void setUpAll() throws IOException {
        server1 = new MockWebServer();
        server1.start();
        server2 = new MockWebServer();
        server2.start();
        server3 = new MockWebServer();
        server3.start();
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        server1.shutdown();
        server2.shutdown();
        server3.shutdown();
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Test
    void shouldDistributeRequestsRoundRobin() throws InterruptedException {
        // Enqueue 2 responses per server
        for (int i = 0; i < 2; i++) {
            server1.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            server2.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            server3.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        }

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        // Request 1 -> Server 1
        StepVerifier.create(
                client.get().uri("/api/v1/users/test").exchangeToMono(r -> r.bodyToMono(String.class))
        )
                .assertNext(body -> assertThat(body).isEqualTo("ok"))
                .verifyComplete();
        assertThat(server1.takeRequest().getPath()).isEqualTo("/users/test");

        // Request 2 -> Server 2
        StepVerifier.create(
                client.get().uri("/api/v1/users/test").exchangeToMono(r -> r.bodyToMono(String.class))
        )
                .assertNext(body -> assertThat(body).isEqualTo("ok"))
                .verifyComplete();
        assertThat(server2.takeRequest().getPath()).isEqualTo("/users/test");

        // Request 3 -> Server 3
        StepVerifier.create(
                client.get().uri("/api/v1/users/test").exchangeToMono(r -> r.bodyToMono(String.class))
        )
                .assertNext(body -> assertThat(body).isEqualTo("ok"))
                .verifyComplete();
        assertThat(server3.takeRequest().getPath()).isEqualTo("/users/test");

        // Request 4 -> Server 1 (wraps around)
        StepVerifier.create(
                client.get().uri("/api/v1/users/test").exchangeToMono(r -> r.bodyToMono(String.class))
        )
                .assertNext(body -> assertThat(body).isEqualTo("ok"))
                .verifyComplete();
        assertThat(server1.takeRequest().getPath()).isEqualTo("/users/test");

        // Request 5 -> Server 2
        StepVerifier.create(
                client.get().uri("/api/v1/users/test").exchangeToMono(r -> r.bodyToMono(String.class))
        )
                .assertNext(body -> assertThat(body).isEqualTo("ok"))
                .verifyComplete();
        assertThat(server2.takeRequest().getPath()).isEqualTo("/users/test");

        // Request 6 -> Server 3
        StepVerifier.create(
                client.get().uri("/api/v1/users/test").exchangeToMono(r -> r.bodyToMono(String.class))
        )
                .assertNext(body -> assertThat(body).isEqualTo("ok"))
                .verifyComplete();
        assertThat(server3.takeRequest().getPath()).isEqualTo("/users/test");
    }

    @Test
    void shouldReturn503WhenNoInstancesAvailable() {
        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/empty/test")
                        .exchangeToMono(response -> {
                            if (response.statusCode().value() == 503) {
                                return response.bodyToMono(String.class);
                            }
                            return Mono.just("UNEXPECTED:" + response.statusCode().value());
                        })
        )
                .assertNext(body -> {
                    assertThat(body).contains("\"error\":\"Service Unavailable\"");
                    assertThat(body).contains("No instances available for service: empty-service");
                })
                .verifyComplete();
    }
}
