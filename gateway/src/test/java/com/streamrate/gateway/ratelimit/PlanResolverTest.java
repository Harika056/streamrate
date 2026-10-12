package com.streamrate.gateway.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.streamrate.gateway.config.RateLimitProperties;
import com.streamrate.gateway.config.RateLimitProperties.Plan;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanResolverTest {

    private PlanResolver resolver;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.getPlans().put("free", new Plan(10, 2));
        props.getPlans().put("jwt", new Plan(20, 10));
        props.getPlans().put("anonymous", new Plan(5, 1));
        props.getApiKeys().put("k-free", "free");
        resolver = new PlanResolver(props);
    }

    @Test
    void knownApiKeyUsesItsPlan() {
        PlanResolver.Resolved r = resolver.resolve("k-free", null, "1.2.3.4").orElseThrow();
        assertEquals("key:k-free", r.clientId());
        assertEquals(10, r.plan().getCapacity());
    }

    @Test
    void unknownApiKeyIsRejected() {
        assertTrue(resolver.resolve("nope", "alice", "1.2.3.4").isEmpty());
    }

    @Test
    void apiKeyBeatsJwtSubject() {
        assertEquals("key:k-free", resolver.resolve("k-free", "alice", "1.2.3.4").orElseThrow().clientId());
    }

    @Test
    void jwtSubjectUsedWhenNoApiKey() {
        PlanResolver.Resolved r = resolver.resolve(null, "alice", "1.2.3.4").orElseThrow();
        assertEquals("sub:alice", r.clientId());
        assertEquals(20, r.plan().getCapacity());
    }

    @Test
    void fallsBackToIpAndAnonymousPlan() {
        PlanResolver.Resolved r = resolver.resolve(null, null, "1.2.3.4").orElseThrow();
        assertEquals("ip:1.2.3.4", r.clientId());
        assertEquals(5, r.plan().getCapacity());
    }

    @Test
    void blankApiKeyIsTreatedAsMissing() {
        Optional<PlanResolver.Resolved> r = resolver.resolve("  ", null, "9.9.9.9");
        assertEquals("ip:9.9.9.9", r.orElseThrow().clientId());
    }

    @Test
    void missingPlanFallsBackToDefault() {
        RateLimitProperties props = new RateLimitProperties();
        props.getApiKeys().put("k", "ghost");
        PlanResolver.Resolved r = new PlanResolver(props).resolve("k", null, "x").orElseThrow();
        assertEquals(10, r.plan().getCapacity());
    }
}
