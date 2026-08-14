package com.apigateway.routing.repository;

import com.apigateway.config.GatewayProperties;
import com.apigateway.routing.model.Route;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Component
public class InMemoryRouteRepository implements RouteRepository {

    private final List<Route> routes;

    public InMemoryRouteRepository(GatewayProperties gatewayProperties) {
        this.routes = gatewayProperties.getRoutes() != null
                ? gatewayProperties.getRoutes().stream()
                        .sorted(Comparator.comparingInt(Route::getOrder))
                        .toList()
                : Collections.emptyList();
    }

    @Override
    public Flux<Route> getRoutes() {
        return Flux.fromIterable(routes);
    }
}
