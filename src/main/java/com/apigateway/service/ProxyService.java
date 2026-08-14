package com.apigateway.service;

import com.apigateway.routing.matcher.RouteMatcher;
import com.apigateway.routing.model.Route;
import com.apigateway.routing.util.PathTransformer;
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
    private final RouteMatcher routeMatcher;

    public ProxyService(WebClient gatewayWebClient,
                        HeaderSanitization headerSanitization,
                        RouteMatcher routeMatcher) {
        this.webClient = gatewayWebClient;
        this.headerSanitization = headerSanitization;
        this.routeMatcher = routeMatcher;
    }

    public Mono<Void> proxy(ServerWebExchange exchange) {
        return routeMatcher.match(exchange)
                .flatMap(route -> proxyToRoute(exchange, route).thenReturn(true))
                .switchIfEmpty(Mono.defer(() -> renderNotFound(exchange).thenReturn(true)))
                .then();
    }

    private Mono<Void> proxyToRoute(ServerWebExchange exchange, Route route) {
        URI targetUri = buildTargetUri(exchange, route);

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
                .onErrorResume(throwable -> renderBadGateway(exchange));
    }

    private Mono<Void> renderNotFound(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String path = exchange.getRequest().getPath().value();
        String body = String.format(
                "{\"error\":\"Route Not Found\",\"path\":\"%s\"}",
                path
        );
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> renderBadGateway(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String errorJson = "{\"error\":\"Bad Gateway\",\"message\":\"The downstream service is unavailable or returned an invalid response\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(errorJson.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private URI buildTargetUri(ServerWebExchange exchange, Route route) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String query = exchange.getRequest().getURI().getRawQuery();

        String transformedPath = PathTransformer.transform(path, route.getStripPrefix());

        String base = route.getTargetUri().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        StringBuilder targetUrl = new StringBuilder(base);
        targetUrl.append(transformedPath);

        if (query != null && !query.isEmpty()) {
            targetUrl.append("?").append(query);
        }

        return URI.create(targetUrl.toString());
    }
}
