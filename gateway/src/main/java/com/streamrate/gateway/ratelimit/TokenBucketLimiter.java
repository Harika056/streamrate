package com.streamrate.gateway.ratelimit;

import com.streamrate.gateway.config.RateLimitProperties;
import com.streamrate.gateway.config.RateLimitProperties.Plan;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Runs the atomic Lua token bucket in Redis (see resources/scripts/token_bucket.lua). */
@Component
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties props;
    private final DefaultRedisScript<List> script;

    public TokenBucketLimiter(ReactiveStringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        this.script.setResultType(List.class);
    }

    /** Opens the Redis connection and loads the script once at startup, so the first real request is not slow. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        redis.execute(script, List.of("streamrate:warmup"), List.of("1", "1", "0"))
                .next()
                .timeout(Duration.ofSeconds(5))
                .doOnError(e -> log.warn("Redis warm-up failed: {}", e.toString()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    public Mono<Decision> tryAcquire(String clientId, Plan plan) {
        List<String> keys = List.of("streamrate:bucket:" + clientId);
        List<String> args = List.of(
                String.valueOf(plan.getCapacity()),
                String.valueOf(plan.getRefillPerSecond()),
                "1");
        return redis.execute(script, keys, args)
                .next()
                .map(TokenBucketLimiter::toDecision)
                .switchIfEmpty(Mono.error(new IllegalStateException("empty script result")))
                .timeout(Duration.ofMillis(props.getRedisTimeoutMs()))
                .onErrorResume(e -> {
                    log.warn("Redis rate-limit check failed ({}); failOpen={}", e.toString(), props.isFailOpen());
                    return Mono.just(props.isFailOpen() ? Decision.degradedAllow() : Decision.degradedReject());
                });
    }

    private static Decision toDecision(List<?> r) {
        boolean allowed = ((Number) r.get(0)).longValue() == 1L;
        long remaining = ((Number) r.get(1)).longValue();
        long retryMs = ((Number) r.get(2)).longValue();
        return new Decision(allowed, remaining, retryMs, false);
    }
}
