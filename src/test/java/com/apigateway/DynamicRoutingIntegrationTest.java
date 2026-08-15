package com.apigateway;

import com.apigateway.config.GatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DynamicRoutingIntegrationTest {

    private static MockWebServer userServiceServer;
    private static MockWebServer orderServiceServer;
    private static MockWebServer catchAllServer;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.health-check.enabled", () -> "false");

        // Route 0: user-service
        registry.add("gateway.routes[0].id", () -> "user-service");
        registry.add("gateway.routes[0].pathPattern", () -> "/api/v1/users/**");
        registry.add("gateway.routes[0].targetUri", () -> "http://localhost:" + userServiceServer.getPort());
        registry.add("gateway.routes[0].stripPrefix", () -> "2");
        registry.add("gateway.routes[0].order", () -> "1");

        // Route 1: order-service
        registry.add("gateway.routes[1].id", () -> "order-service");
        registry.add("gateway.routes[1].pathPattern", () -> "/api/v1/orders/**");
        registry.add("gateway.routes[1].targetUri", () -> "http://localhost:" + orderServiceServer.getPort());
        registry.add("gateway.routes[1].stripPrefix", () -> "2");
        registry.add("gateway.routes[1].order", () -> "2");

        // Route 2: catch-all
        registry.add("gateway.routes[2].id", () -> "catch-all");
        registry.add("gateway.routes[2].pathPattern", () -> "/api/v1/**");
        registry.add("gateway.routes[2].targetUri", () -> "http://localhost:" + catchAllServer.getPort());
        registry.add("gateway.routes[2].stripPrefix", () -> "0");
        registry.add("gateway.routes[2].order", () -> "100");
    }

    @BeforeAll
    static void setUpAll() throws IOException {
        userServiceServer = new MockWebServer();
        userServiceServer.start();
        orderServiceServer = new MockWebServer();
        orderServiceServer.start();
        catchAllServer = new MockWebServer();
        catchAllServer.start();
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        userServiceServer.shutdown();
        orderServiceServer.shutdown();
        catchAllServer.shutdown();
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void shouldRouteUsersRequestToUserServiceWithPathStripped() throws InterruptedException {
        userServiceServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"user\":\"alice\"}"));

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/api/v1/users/123")
                        .exchangeToMono(response -> response.bodyToMono(String.class))
        )
                .assertNext(body -> {
                    assertThat(body).isEqualTo("{\"user\":\"alice\"}");
                })
                .verifyComplete();

        RecordedRequest recordedRequest = userServiceServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("GET");
        assertThat(recordedRequest.getPath()).isEqualTo("/users/123");
    }

    @Test
    void shouldRouteOrdersRequestToOrderServiceWithPathStripped() throws InterruptedException {
        orderServiceServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"order\":\"confirmed\"}"));

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/api/v1/orders/checkout")
                        .exchangeToMono(response -> response.bodyToMono(String.class))
        )
                .assertNext(body -> {
                    assertThat(body).isEqualTo("{\"order\":\"confirmed\"}");
                })
                .verifyComplete();

        RecordedRequest recordedRequest = orderServiceServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("GET");
        assertThat(recordedRequest.getPath()).isEqualTo("/orders/checkout");
    }

    @Test
    void shouldReturn404ForUnmappedPath() {
        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/unmapped/resource")
                        .exchangeToMono(response -> {
                            if (response.statusCode().is4xxClientError()) {
                                return response.bodyToMono(String.class);
                            }
                            return Mono.just("UNEXPECTED:" + response.statusCode().value());
                        })
        )
                .assertNext(body -> {
                    assertThat(body).contains("\"error\":\"Route Not Found\"");
                    assertThat(body).contains("\"path\":\"/unmapped/resource\"");
                })
                .verifyComplete();
    }

    @Test
    void shouldRespectRoutePrecedence() throws InterruptedException {
        userServiceServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"user\":\"bob\"}"));

        // Enqueue a response on catch-all so we can detect if the request goes there
        catchAllServer.enqueue(new MockResponse()
                .setResponseCode(418)
                .setBody("I am a teapot"));

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/api/v1/users/456")
                        .exchangeToMono(response -> response.bodyToMono(String.class))
        )
                .assertNext(body -> {
                    assertThat(body).isEqualTo("{\"user\":\"bob\"}");
                })
                .verifyComplete();

        RecordedRequest recordedRequest = userServiceServer.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo("/users/456");

        // Ensure the catch-all server did NOT receive the request
        assertThat(catchAllServer.getRequestCount()).isZero();
    }
}
