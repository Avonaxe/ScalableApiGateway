package com.apigateway.routing.model;

import java.net.URI;

public class Route {

    private String id;
    private String pathPattern;
    private URI targetUri;
    private int order;
    private int stripPrefix;

    public Route() {
    }

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

    public URI getTargetUri() {
        return targetUri;
    }

    public void setTargetUri(URI targetUri) {
        this.targetUri = targetUri;
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
}
