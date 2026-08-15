package com.apigateway;

import com.apigateway.util.JwtUtil;
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
class JwtAuthIntegrationTest {

    private static MockWebServer mockWebServer;

    @BeforeAll
    static void setUpAll() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        mockWebServer.shutdown();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.health-check.enabled", () -> "false");
        registry.add("gateway.rate-limit.enabled", () -> "false");

        registry.add("gateway.routes[0].id", () -> "auth-route");
        registry.add("gateway.routes[0].pathPattern", () -> "/secure/**");
        registry.add("gateway.routes[0].targetUri", () -> "http://localhost:" + mockWebServer.getPort());
        registry.add("gateway.routes[0].stripPrefix", () -> "0");
        registry.add("gateway.routes[0].order", () -> "0");
        registry.add("gateway.routes[0].requiresAuth", () -> "true");

        registry.add("gateway.routes[1].id", () -> "public-route");
        registry.add("gateway.routes[1].pathPattern", () -> "/public/**");
        registry.add("gateway.routes[1].targetUri", () -> "http://localhost:" + mockWebServer.getPort());
        registry.add("gateway.routes[1].stripPrefix", () -> "0");
        registry.add("gateway.routes[1].order", () -> "1");
        registry.add("gateway.routes[1].requiresAuth", () -> "false");
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void shouldRejectRequestWithoutToken() {
        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/secure/data")
                        .exchangeToMono(response -> {
                            if (response.statusCode().is4xxClientError()) {
                                return response.bodyToMono(String.class);
                            }
                            return Mono.just("UNEXPECTED:" + response.statusCode().value());
                        })
        )
                .assertNext(body -> {
                    assertThat(body).contains("\"error\":\"Unauthorized\"");
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectRequestWithInvalidToken() {
        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/secure/data")
                        .header("Authorization", "Bearer invalid-token")
                        .exchangeToMono(response -> {
                            if (response.statusCode().is4xxClientError()) {
                                return response.bodyToMono(String.class);
                            }
                            return Mono.just("UNEXPECTED:" + response.statusCode().value());
                        })
        )
                .assertNext(body -> {
                    assertThat(body).contains("\"error\":\"Unauthorized\"");
                    assertThat(body).contains("Invalid or expired token");
                })
                .verifyComplete();
    }

    @Test
    void shouldAllowRequestWithValidToken() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"secret\"}"));

        String token = jwtUtil.generateToken("test-user");

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/secure/data")
                        .header("Authorization", "Bearer " + token)
                        .exchangeToMono(response -> response.bodyToMono(String.class))
        )
                .assertNext(body -> {
                    assertThat(body).isEqualTo("{\"data\":\"secret\"}");
                })
                .verifyComplete();
    }

    @Test
    void shouldAllowPublicRouteWithoutToken() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"public\"}"));

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/public/data")
                        .exchangeToMono(response -> response.bodyToMono(String.class))
        )
                .assertNext(body -> {
                    assertThat(body).isEqualTo("{\"data\":\"public\"}");
                })
                .verifyComplete();
    }
}
