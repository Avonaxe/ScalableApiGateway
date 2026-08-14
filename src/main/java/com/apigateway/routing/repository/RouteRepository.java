package com.apigateway.routing.repository;

import com.apigateway.routing.model.Route;
import reactor.core.publisher.Flux;

public interface RouteRepository {

    Flux<Route> getRoutes();
}
