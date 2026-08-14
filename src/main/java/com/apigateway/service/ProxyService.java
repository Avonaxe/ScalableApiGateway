package com.apigateway.service;

import com.apigateway.config.GatewayProperties;
import com.apigateway.util.HeaderSanitization;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Service
public class ProxyService {

    private final WebClient webClient;
    private final HeaderSanitization headerSanitization;
    private final GatewayProperties gatewayProperties;

    public ProxyService(WebClient gatewayWebClient,
                        HeaderSanitization headerSanitization,
                        GatewayProperties gatewayProperties) {
        this.webClient = gatewayWebClient;
        this.headerSanitization = headerSanitization;
        this.gatewayProperties = gatewayProperties;
    }

    public Mono<Void> proxy(ServerWebExchange exchange) {
        URI targetUri = buildTargetUri(exchange);

        return webClient.method(exchange.getRequest().getMethod())
                .uri(targetUri)
                .headers(headers -> headers.addAll(
                        headerSanitization.filter(exchange.getRequest().getHeaders())
                ))
                .body(BodyInserters.fromDataBuffers(exchange.getRequest().getBody()))
                .exchangeToMono(clientResponse -> {
                    exchange.getResponse().setStatusCode(clientResponse.statusCode());
                    var filteredResponseHeaders = headerSanitization.filter(
                            clientResponse.headers().asHttpHeaders()
                    );
                    exchange.getResponse().getHeaders().addAll(filteredResponseHeaders);
                    return exchange.getResponse().writeWith(clientResponse.bodyToFlux(DataBuffer.class));
                })
                .onErrorResume(throwable -> {
                    exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    String errorJson = "{\"error\":\"Bad Gateway\",\"message\":\"The downstream service is unavailable or returned an invalid response\"}";
                    DataBuffer buffer = exchange.getResponse().bufferFactory()
                            .wrap(errorJson.getBytes(StandardCharsets.UTF_8));
                    return exchange.getResponse().writeWith(Mono.just(buffer));
                });
    }

    private URI buildTargetUri(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String query = exchange.getRequest().getURI().getRawQuery();
        String downstreamBaseUrl = gatewayProperties.getDownstream().getUrl();

        String base = downstreamBaseUrl.endsWith("/")
                ? downstreamBaseUrl.substring(0, downstreamBaseUrl.length() - 1)
                : downstreamBaseUrl;

        StringBuilder targetUrl = new StringBuilder(base);
        targetUrl.append(path);

        if (query != null && !query.isEmpty()) {
            targetUrl.append("?").append(query);
        }

        return URI.create(targetUrl.toString());
    }
}