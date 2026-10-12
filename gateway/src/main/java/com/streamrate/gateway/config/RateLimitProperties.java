package com.streamrate.gateway.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Rate-limit settings bound from `streamrate.ratelimit.*` in application.yml. */
@ConfigurationProperties(prefix = "streamrate.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;
    /** If Redis is down or slow: true = let requests through, false = reject with 503. */
    private boolean failOpen = true;
    private long redisTimeoutMs = 250;
    /** Only turn on behind a proxy you control (nginx), otherwise X-Forwarded-For can be spoofed. */
    private boolean trustForwardedFor = false;
    private Plan defaultPlan = new Plan(10, 5);
    private Map<String, Plan> plans = new HashMap<>();
    /** API key -> plan name. */
    private Map<String, String> apiKeys = new HashMap<>();

    public static class Plan {
        private int capacity = 10;
        private double refillPerSecond = 5;

        public Plan() {}

        public Plan(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public double getRefillPerSecond() { return refillPerSecond; }
        public void setRefillPerSecond(double refillPerSecond) { this.refillPerSecond = refillPerSecond; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    public long getRedisTimeoutMs() { return redisTimeoutMs; }
    public void setRedisTimeoutMs(long redisTimeoutMs) { this.redisTimeoutMs = redisTimeoutMs; }
    public boolean isTrustForwardedFor() { return trustForwardedFor; }
    public void setTrustForwardedFor(boolean trustForwardedFor) { this.trustForwardedFor = trustForwardedFor; }
    public Plan getDefaultPlan() { return defaultPlan; }
    public void setDefaultPlan(Plan defaultPlan) { this.defaultPlan = defaultPlan; }
    public Map<String, Plan> getPlans() { return plans; }
    public void setPlans(Map<String, Plan> plans) { this.plans = plans; }
    public Map<String, String> getApiKeys() { return apiKeys; }
    public void setApiKeys(Map<String, String> apiKeys) { this.apiKeys = apiKeys; }
}
