package com.apigateway.routing.util;

public final class PathTransformer {

    private PathTransformer() {
    }

    public static String transform(String path, int stripPrefix) {
        if (stripPrefix <= 0 || path == null || path.isEmpty()) {
            return path;
        }

        int segmentsSkipped = 0;
        int index = 0;

        while (segmentsSkipped < stripPrefix && index < path.length()) {
            if (path.charAt(index) == '/') {
                index++;
            }
            while (index < path.length() && path.charAt(index) != '/') {
                index++;
            }
            segmentsSkipped++;
        }

        String result = path.substring(index);
        return result.isEmpty() ? "/" : result;
    }
}
