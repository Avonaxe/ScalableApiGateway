package com.apigateway.filter.routing;

import com.apigateway.filter.GatewayFilter;
import com.apigateway.filter.GatewayFilterChain;
import com.apigateway.routing.matcher.RouteMatcher;
import com.apigateway.routing.model.Route;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class RouteMatchingFilter implements GatewayFilter, Ordered {

    public static final String GATEWAY_ROUTE_ATTR = "gatewayRoute";

    private final RouteMatcher routeMatcher;

    public RouteMatchingFilter(RouteMatcher routeMatcher) {
        this.routeMatcher = routeMatcher;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return routeMatcher.match(exchange)
                .flatMap(route -> {
                    exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
                    return chain.filter(exchange).then(Mono.just(true));
                })
                .switchIfEmpty(Mono.defer(() -> renderNotFound(exchange).then(Mono.just(false))))
                .then();
    }

    private Mono<Void> renderNotFound(ServerWebExchange exchange) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
