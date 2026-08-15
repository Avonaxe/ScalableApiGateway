package com.apigateway.loadbalancer;

public class NoInstancesAvailableException extends RuntimeException {

    private final String serviceId;

    public NoInstancesAvailableException(String serviceId) {
        super("No instances available for service: " + serviceId);
        this.serviceId = serviceId;
    }

    public String getServiceId() {
        return serviceId;
    }
}
