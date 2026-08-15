package com.apigateway;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthCheckIntegrationTest {

    private static MockWebServer healthyServer;
    private static MockWebServer unhealthyServer;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.health-check.enabled", () -> "true");
        registry.add("gateway.health-check.interval", () -> "500ms");
        registry.add("gateway.health-check.timeout", () -> "1s");

        registry.add("gateway.routes[0].id", () -> "hc-test-service");
        registry.add("gateway.routes[0].pathPattern", () -> "/api/**");
        registry.add("gateway.routes[0].targetUris[0]", () -> "http://localhost:" + healthyServer.getPort());
        registry.add("gateway.routes[0].targetUris[1]", () -> "http://localhost:" + unhealthyServer.getPort());
        registry.add("gateway.routes[0].stripPrefix", () -> "0");
        registry.add("gateway.routes[0].order", () -> "0");

        // Fill remaining route slots to avoid unbound property errors
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
        healthyServer = new MockWebServer();
        healthyServer.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                if ("/actuator/health".equals(request.getPath())) {
                    return new MockResponse().setResponseCode(200);
                }
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });
        healthyServer.start();

        unhealthyServer = new MockWebServer();
        unhealthyServer.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                if ("/actuator/health".equals(request.getPath())) {
                    return new MockResponse().setResponseCode(500);
                }
                return new MockResponse().setResponseCode(200).setBody("should-not-reach");
            }
        });
        unhealthyServer.start();
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        healthyServer.shutdown();
        unhealthyServer.shutdown();
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Test
    void shouldRouteOnlyToHealthyInstances() throws Exception {
        // Wait for the first health check cycle to complete and mark instances
        Thread.sleep(700);

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        for (int i = 0; i < 4; i++) {
            StepVerifier.create(
                    client.get()
                            .uri("/api/test")
                            .exchangeToMono(response -> response.bodyToMono(String.class))
            )
                    .assertNext(body -> assertThat(body).isEqualTo("ok"))
                    .verifyComplete();
        }

        // Verify unhealthy server received ONLY health check requests (no proxy traffic)
        int unhealthyProxyRequests = 0;
        int unhealthyHealthChecks = 0;
        for (int i = 0; i < unhealthyServer.getRequestCount(); i++) {
            RecordedRequest req = unhealthyServer.takeRequest();
            if ("/actuator/health".equals(req.getPath())) {
                unhealthyHealthChecks++;
            } else {
                unhealthyProxyRequests++;
            }
        }
        assertThat(unhealthyHealthChecks).isGreaterThanOrEqualTo(1);
        assertThat(unhealthyProxyRequests).isZero();

        // Verify healthy server received health checks + 4 proxy requests
        int healthyProxyRequests = 0;
        for (int i = 0; i < healthyServer.getRequestCount(); i++) {
            RecordedRequest req = healthyServer.takeRequest();
            if (!"/actuator/health".equals(req.getPath())) {
                healthyProxyRequests++;
                assertThat(req.getPath()).isEqualTo("/api/test");
            }
        }
        assertThat(healthyProxyRequests).isEqualTo(4);
    }
}
