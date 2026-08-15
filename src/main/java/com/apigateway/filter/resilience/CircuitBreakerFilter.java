package com.apigateway.filter.resilience;

import com.apigateway.filter.GatewayFilter;
import com.apigateway.filter.GatewayFilterChain;
import com.apigateway.filter.routing.RouteMatchingFilter;
import com.apigateway.routing.model.Route;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class CircuitBreakerFilter implements GatewayFilter, Ordered {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerFilter(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(RouteMatchingFilter.GATEWAY_ROUTE_ATTR);
        if (route == null || !route.isCircuitBreakerEnabled()) {
            return chain.filter(exchange);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(route.getId());

        if (!circuitBreaker.tryAcquirePermission()) {
            return renderCircuitOpen(exchange);
        }

        long start = System.nanoTime();
        return chain.filter(exchange)
                .doOnSuccess(v -> {
                    long duration = System.nanoTime() - start;
                    if (exchange.getResponse().getStatusCode() != null
                            && exchange.getResponse().getStatusCode().is5xxServerError()) {
                        circuitBreaker.onError(duration, TimeUnit.NANOSECONDS,
                                new RuntimeException("Downstream service returned 5xx"));
                    } else {
                        circuitBreaker.onSuccess(duration, TimeUnit.NANOSECONDS);
                    }
                })
                .doOnError(throwable -> {
                    long duration = System.nanoTime() - start;
                    circuitBreaker.onError(duration, TimeUnit.NANOSECONDS, throwable);
                });
    }

    private Mono<Void> renderCircuitOpen(ServerWebExchange exchange) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":\"Service Unavailable\",\"message\":\"Circuit Breaker is OPEN\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
