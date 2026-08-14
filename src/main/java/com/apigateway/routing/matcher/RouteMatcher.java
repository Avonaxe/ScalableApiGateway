package com.apigateway.routing.matcher;

import com.apigateway.routing.model.Route;
import com.apigateway.routing.repository.RouteRepository;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

@Component
public class RouteMatcher {

    private final RouteRepository routeRepository;

    public RouteMatcher(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    public Mono<Route> match(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        PathPatternParser parser = new PathPatternParser();

        return routeRepository.getRoutes()
                .filter(route -> {
                    PathPattern pattern = parser.parse(route.getPathPattern());
                    return pattern.matches(PathContainer.parsePath(path));
                })
                .next();
    }
}
