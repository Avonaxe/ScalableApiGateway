package com.apigateway.util;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class HeaderSanitization {

    private static final Set<String> HOP_BY_HOP_HEADERS = new HashSet<>(Arrays.asList(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host"
    ));

    public HttpHeaders filter(HttpHeaders originalHeaders) {
        HttpHeaders filtered = new HttpHeaders();
        Set<String> additionalHopByHop = new HashSet<>();

        List<String> connectionValues = originalHeaders.get("Connection");
        if (connectionValues != null) {
            connectionValues.forEach(v ->
                Arrays.stream(v.split(","))
                        .map(String::trim)
                        .map(String::toLowerCase)
                        .forEach(additionalHopByHop::add)
            );
        }

        originalHeaders.forEach((name, values) -> {
            String lowerName = name.toLowerCase();
            if (HOP_BY_HOP_HEADERS.contains(lowerName)) {
                return;
            }
            if (additionalHopByHop.contains(lowerName)) {
                return;
            }
            filtered.addAll(name, values);
        });

        return filtered;
    }
}