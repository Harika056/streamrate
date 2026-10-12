package com.streamrate.gateway.ratelimit;

import com.streamrate.gateway.config.RateLimitProperties;
import com.streamrate.gateway.security.JwtAuthFilter;
import com.streamrate.gateway.util.Responses;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Applies the per-client token bucket to every routed request. */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimitProperties props;
    private final PlanResolver resolver;
    private final TokenBucketLimiter limiter;
    private final MeterRegistry meters;

    public RateLimitFilter(RateLimitProperties props, TokenBucketLimiter limiter, MeterRegistry meters) {
        this.props = props;
        this.resolver = new PlanResolver(props);
        this.limiter = limiter;
        this.meters = meters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String apiKey = request.getHeaders().getFirst("X-API-Key");
        String subject = exchange.getAttribute(JwtAuthFilter.SUBJECT_ATTR);

        Optional<PlanResolver.Resolved> resolved = resolver.resolve(apiKey, subject, clientIp(request));
        if (resolved.isEmpty()) {
            count("unauthorized", "none");
            return Responses.json(exchange, HttpStatus.UNAUTHORIZED,
                    "{\"error\":\"invalid_api_key\"}", Map.of());
        }
        PlanResolver.Resolved who = resolved.get();

        return limiter.tryAcquire(who.clientId(), who.plan()).flatMap(decision -> {
            Map<String, String> headers = Map.of(
                    "X-RateLimit-Limit", String.valueOf(who.plan().getCapacity()),
                    "X-RateLimit-Remaining", String.valueOf(Math.max(0, decision.remaining())));

            if (decision.allowed()) {
                count(decision.degraded() ? "allowed_degraded" : "allowed", who.planName());
                if (!decision.degraded()) {
                    headers.forEach((k, v) -> exchange.getResponse().getHeaders().set(k, v));
                }
                return chain.filter(exchange);
            }
            if (decision.degraded()) {
                count("redis_unavailable", who.planName());
                return Responses.json(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "{\"error\":\"rate_limiter_unavailable\"}", Map.of("Retry-After", "1"));
            }
            long seconds = RetryAfter.seconds(decision.retryAfterMs());
            count("limited", who.planName());
            return Responses.json(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    "{\"error\":\"rate_limit_exceeded\",\"retry_after_seconds\":" + seconds + "}",
                    Map.of("Retry-After", String.valueOf(seconds),
                           "X-RateLimit-Limit", headers.get("X-RateLimit-Limit"),
                           "X-RateLimit-Remaining", "0"));
        });
    }

    private String clientIp(ServerHttpRequest request) {
        if (props.isTrustForwardedFor()) {
            String xff = request.getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return (remote == null || remote.getAddress() == null) ? "unknown" : remote.getAddress().getHostAddress();
    }

    private void count(String result, String plan) {
        meters.counter("streamrate.requests", "result", result, "plan", plan).increment();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
