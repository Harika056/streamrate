package com.streamrate.gateway.ratelimit;

import com.streamrate.gateway.config.RateLimitProperties;
import com.streamrate.gateway.config.RateLimitProperties.Plan;
import java.util.Optional;

/** Decides who the caller is and which bucket size applies. Pure logic, no Spring or Redis. */
public class PlanResolver {

    public record Resolved(String clientId, String planName, Plan plan) {}

    private final RateLimitProperties props;

    public PlanResolver(RateLimitProperties props) {
        this.props = props;
    }

    /**
     * Priority: API key, then JWT subject, then client IP.
     * An API key that is present but unknown yields an empty Optional (caller should answer 401).
     */
    public Optional<Resolved> resolve(String apiKey, String jwtSubject, String ip) {
        if (apiKey != null && !apiKey.isBlank()) {
            String planName = props.getApiKeys().get(apiKey);
            if (planName == null) {
                return Optional.empty();
            }
            return Optional.of(new Resolved("key:" + apiKey, planName, planOrDefault(planName)));
        }
        if (jwtSubject != null && !jwtSubject.isBlank()) {
            return Optional.of(new Resolved("sub:" + jwtSubject, "jwt", planOrDefault("jwt")));
        }
        return Optional.of(new Resolved("ip:" + (ip == null ? "unknown" : ip), "anonymous", planOrDefault("anonymous")));
    }

    private Plan planOrDefault(String name) {
        return props.getPlans().getOrDefault(name, props.getDefaultPlan());
    }
}
