package com.apigateway.routing.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class Route {

    private String id;
    private String pathPattern;
    private List<URI> targetUris = new ArrayList<>();
    private int order;
    private int stripPrefix;
    private String healthCheckPath = "/actuator/health";
    private int replenishRate = 0;
    private int burstCapacity = 0;
    private boolean requiresAuth = false;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public void setPathPattern(String pathPattern) {
        this.pathPattern = pathPattern;
    }

    public List<URI> getTargetUris() {
        return targetUris;
    }

    public void setTargetUris(List<URI> targetUris) {
        this.targetUris = targetUris;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public int getStripPrefix() {
        return stripPrefix;
    }

    public void setStripPrefix(int stripPrefix) {
        this.stripPrefix = stripPrefix;
    }

    public String getHealthCheckPath() {
        return healthCheckPath;
    }

    public void setHealthCheckPath(String healthCheckPath) {
        this.healthCheckPath = healthCheckPath;
    }

    public int getReplenishRate() {
        return replenishRate;
    }

    public void setReplenishRate(int replenishRate) {
        this.replenishRate = replenishRate;
    }

    public int getBurstCapacity() {
        return burstCapacity;
    }

    public void setBurstCapacity(int burstCapacity) {
        this.burstCapacity = burstCapacity;
    }

    public boolean isRequiresAuth() {
        return requiresAuth;
    }

    public void setRequiresAuth(boolean requiresAuth) {
        this.requiresAuth = requiresAuth;
    }

    // Backward compatibility for single-target-uri configurations
    public URI getTargetUri() {
        return targetUris.isEmpty() ? null : targetUris.get(0);
    }

    public void setTargetUri(URI targetUri) {
        this.targetUris = new ArrayList<>();
        if (targetUri != null) {
            this.targetUris.add(targetUri);
        }
    }
}
