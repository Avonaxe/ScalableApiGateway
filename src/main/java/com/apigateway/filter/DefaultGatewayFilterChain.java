package com.apigateway.filter;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

public class DefaultGatewayFilterChain implements GatewayFilterChain {

    private final List<GatewayFilter> filters;
    private final int index;
    private final Function<ServerWebExchange, Mono<Void>> terminal;

    public DefaultGatewayFilterChain(List<GatewayFilter> filters, Function<ServerWebExchange, Mono<Void>> terminal) {
        this(filters, 0, terminal);
    }

    private DefaultGatewayFilterChain(List<GatewayFilter> filters, int index, Function<ServerWebExchange, Mono<Void>> terminal) {
        this.filters = filters;
        this.index = index;
        this.terminal = terminal;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange) {
        if (index < filters.size()) {
            return filters.get(index).filter(exchange,
                    new DefaultGatewayFilterChain(filters, index + 1, terminal));
        }
        return terminal.apply(exchange);
    }
}
