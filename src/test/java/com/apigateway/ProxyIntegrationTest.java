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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyIntegrationTest {

    private static MockWebServer mockWebServer;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.downstream.url", () -> "http://localhost:" + mockWebServer.getPort());
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

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void shouldProxyGetRequestAndStripHopByHopHeaders() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Connection", "keep-alive")
                .setHeader("Keep-Alive", "timeout=5")
                .setHeader("X-Custom-Response", "present")
                .setBody("{\"message\":\"hello\"}"));

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        StepVerifier.create(
                client.get()
                        .uri("/api/test?foo=bar")
                        .header("Connection", "keep-alive")
                        .header("Keep-Alive", "timeout=5")
                        .header("Host", "original-host")
                        .header("Proxy-Authenticate", "Basic")
                        .header("X-Custom-Request", "request-value")
                        .exchangeToMono(response ->
                                response.bodyToMono(String.class)
                                        .map(body -> new ResponseCapture(
                                                response.statusCode().value(),
                                                response.headers().asHttpHeaders().get("Connection"),
                                                response.headers().asHttpHeaders().get("Keep-Alive"),
                                                response.headers().asHttpHeaders().get("Transfer-Encoding"),
                                                response.headers().asHttpHeaders().get("X-Custom-Response"),
                                                body
                                        ))
                        )
        )
                .assertNext(capture -> {
                    assertThat(capture.statusCode).isEqualTo(200);
                    assertThat(capture.body).isEqualTo("{\"message\":\"hello\"}");

                    // Hop-by-hop headers stripped from response
                    assertThat(capture.connectionHeader).isNull();
                    assertThat(capture.keepAliveHeader).isNull();
                    assertThat(capture.transferEncodingHeader).isNull();

                    // End-to-end header preserved
                    assertThat(capture.xCustomResponse).containsExactly("present");
                })
                .verifyComplete();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();

        // Path and query forwarded correctly
        assertThat(recordedRequest.getMethod()).isEqualTo("GET");
        assertThat(recordedRequest.getPath()).isEqualTo("/api/test?foo=bar");

        // Hop-by-hop headers stripped from request
        assertThat(recordedRequest.getHeader("Connection")).isNull();
        assertThat(recordedRequest.getHeader("Keep-Alive")).isNull();
        assertThat(recordedRequest.getHeader("Proxy-Authenticate")).isNull();

        // Host header is updated to reflect the downstream target
        assertThat(recordedRequest.getHeader("Host")).isEqualTo("localhost:" + mockWebServer.getPort());

        // End-to-end header preserved
        assertThat(recordedRequest.getHeader("X-Custom-Request")).isEqualTo("request-value");
    }

    @Test
    void shouldProxyPostRequestWithJsonBody() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Resource-Id", "12345")
                .setBody("{\"id\":\"123\"}"));

        WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

        String requestBody = "{\"name\":\"test-user\"}";

        StepVerifier.create(
                client.post()
                        .uri("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .exchangeToMono(response ->
                                response.bodyToMono(String.class)
                                        .map(body -> new ResponseCapture(
                                                response.statusCode().value(),
                                                null, null, null,
                                                response.headers().asHttpHeaders().get("X-Resource-Id"),
                                                body
                                        ))
                        )
        )
                .assertNext(capture -> {
                    assertThat(capture.statusCode).isEqualTo(201);
                    assertThat(capture.body).isEqualTo("{\"id\":\"123\"}");
                    assertThat(capture.xCustomResponse).containsExactly("12345");
                })
                .verifyComplete();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();

        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/api/users");
        assertThat(recordedRequest.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(recordedRequest.getBody().readUtf8()).isEqualTo(requestBody);
    }

    @Test
    void shouldReturn502WhenDownstreamConnectionFails() {
        String originalUrl = gatewayProperties.getDownstream().getUrl();
        gatewayProperties.getDownstream().setUrl("http://localhost:65432");

        try {
            WebClient client = webClientBuilder.baseUrl("http://localhost:" + gatewayPort).build();

            StepVerifier.create(
                    client.get()
                            .uri("/api/error")
                            .exchangeToMono(response -> {
                                if (response.statusCode().is2xxSuccessful()) {
                                    return response.bodyToMono(String.class);
                                }
                                return Mono.just("STATUS:" + response.statusCode().value());
                            })
            )
                    .assertNext(result -> {
                        assertThat(result).isEqualTo("STATUS:502");
                    })
                    .verifyComplete();
        } finally {
            gatewayProperties.getDownstream().setUrl(originalUrl);
        }
    }

    private record ResponseCapture(
            int statusCode,
            List<String> connectionHeader,
            List<String> keepAliveHeader,
            List<String> transferEncodingHeader,
            List<String> xCustomResponse,
            String body
    ) {
    }
}