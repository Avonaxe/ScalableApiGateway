package com.apigateway.loadbalancer;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public Mono<URI> choose(String serviceId, List<URI> instances) {
        if (instances == null || instances.isEmpty()) {
            return Mono.error(new NoInstancesAvailableException(serviceId));
        }

        AtomicInteger counter = counters.computeIfAbsent(serviceId, k -> new AtomicInteger(0));
        int index = (counter.getAndIncrement() & 0x7FFFFFFF) % instances.size();
        return Mono.just(instances.get(index));
    }
}
