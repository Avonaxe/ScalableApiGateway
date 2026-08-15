package com.apigateway.filter.ratelimit;

import com.apigateway.filter.GatewayFilter;
import com.apigateway.filter.GatewayFilterChain;
import com.apigateway.routing.matcher.RouteMatcher;
import com.apigateway.routing.model.Route;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true")
public class RedisRateLimitFilter implements GatewayFilter, Ordered {

    private static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local capacity = tonumber(ARGV[3])
            local bucket = redis.call('hmget', key, 'tokens', 'last_access')
            local tokens = tonumber(bucket[1])
            local last_access = tonumber(bucket[2])
            if tokens == nil then
                tokens = capacity
                last_access = now
            end
            local elapsed = now - last_access
            local tokens_to_add = (elapsed * rate) / 1000.0
            tokens = math.min(tokens + tokens_to_add, capacity)
            if tokens >= 1 then
                tokens = tokens - 1
                redis.call('hmset', key, 'tokens', tokens, 'last_access', now)
                redis.call('pexpire', key, 60000)
                return 1
            else
                redis.call('hmset', key, 'tokens', tokens, 'last_access', now)
                redis.call('pexpire', key, 60000)
                return 0
            end
            """;

    private final RouteMatcher routeMatcher;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    public RedisRateLimitFilter(RouteMatcher routeMatcher,
                                ReactiveRedisTemplate<String, String> redisTemplate) {
        this.routeMatcher = routeMatcher;
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return routeMatcher.match(exchange)
                .flatMap(route -> {
                    if (route.getReplenishRate() > 0 && route.getBurstCapacity() > 0) {
                        return applyRateLimit(exchange, route, chain);
                    }
                    return chain.filter(exchange);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Void> applyRateLimit(ServerWebExchange exchange, Route route, GatewayFilterChain chain) {
        String clientIp = extractClientIp(exchange);
        String key = "rate_limit:" + route.getId() + ":" + clientIp;
        long now = System.currentTimeMillis();

        return redisTemplate.execute(
                        rateLimitScript,
                        List.of(key),
                        List.of(String.valueOf(now), String.valueOf(route.getReplenishRate()), String.valueOf(route.getBurstCapacity())))
                .next()
                .flatMap(allowed -> {
                    if (allowed == 1L) {
                        return chain.filter(exchange);
                    }
                    return renderTooManyRequests(exchange);
                });
    }

    private String extractClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        if (exchange.getRequest().getRemoteAddress() != null) {
            return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private Mono<Void> renderTooManyRequests(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", "0");
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
