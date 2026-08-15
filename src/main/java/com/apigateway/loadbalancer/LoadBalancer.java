package com.apigateway.loadbalancer;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

public interface LoadBalancer {

    Mono<URI> choose(String serviceId, List<URI> instances);
}
