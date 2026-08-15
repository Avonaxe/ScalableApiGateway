package com.apigateway.controller;

import com.apigateway.filter.DefaultGatewayFilterChain;
import com.apigateway.filter.GatewayFilter;
import com.apigateway.service.ProxyService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
public class GatewayController {

    private final List<GatewayFilter> filters;
    private final ProxyService proxyService;

    public GatewayController(List<GatewayFilter> filters, ProxyService proxyService) {
        this.filters = filters != null ? filters : List.of();
        this.proxyService = proxyService;
    }

    @RequestMapping(
            value = "/**",
            method = {
                    RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                    RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.OPTIONS,
                    RequestMethod.HEAD, RequestMethod.TRACE
            }
    )
    public Mono<Void> handle(ServerWebExchange exchange) {
        return new DefaultGatewayFilterChain(filters, proxyService::proxy).filter(exchange);
    }
}
