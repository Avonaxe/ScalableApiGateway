package com.apigateway.health;

import com.apigateway.config.GatewayProperties;
import com.apigateway.routing.repository.RouteRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HealthMonitor {

    private final WebClient webClient;
    private final RouteRepository routeRepository;
    private final GatewayProperties gatewayProperties;
    private final ConcurrentHashMap<URI, Boolean> healthStatus = new ConcurrentHashMap<>();
    private Disposable healthCheckSubscription;

    public HealthMonitor(WebClient webClient,
                         RouteRepository routeRepository,
                         GatewayProperties gatewayProperties) {
        this.webClient = webClient;
        this.routeRepository = routeRepository;
        this.gatewayProperties = gatewayProperties;
    }

    @PostConstruct
    public void start() {
        if (!gatewayProperties.getHealthCheck().isEnabled()) {
            return;
        }
        Duration interval = gatewayProperties.getHealthCheck().getInterval();
        healthCheckSubscription = Flux.interval(Duration.ZERO, interval, Schedulers.boundedElastic())
                .flatMap(tick -> performHealthChecks())
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (healthCheckSubscription != null && !healthCheckSubscription.isDisposed()) {
            healthCheckSubscription.dispose();
        }
    }

    public List<URI> filterHealthy(List<URI> instances) {
        return instances.stream()
                .filter(uri -> healthStatus.getOrDefault(uri, true))
                .toList();
    }

    private Mono<Void> performHealthChecks() {
        return routeRepository.getRoutes()
                .flatMap(route -> Flux.fromIterable(route.getTargetUris())
                        .flatMap(uri -> checkHealth(uri, route.getHealthCheckPath())))
                .then();
    }

    private Mono<Void> checkHealth(URI baseUri, String healthPath) {
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = healthPath.startsWith("/") ? healthPath : "/" + healthPath;
        String url = base + path;

        return webClient.get()
                .uri(url)
                .exchangeToMono(response -> {
                    healthStatus.put(baseUri, response.statusCode().is2xxSuccessful());
                    return Mono.empty();
                })
                .timeout(gatewayProperties.getHealthCheck().getTimeout())
                .onErrorResume(error -> {
                    healthStatus.put(baseUri, false);
                    return Mono.empty();
                })
                .then();
    }
}
