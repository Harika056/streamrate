package com.streamrate.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT settings bound from `streamrate.jwt.*`. The default secret is for local demos only. */
@ConfigurationProperties(prefix = "streamrate.jwt")
public class JwtProperties {

    /** HS256 secret, at least 32 characters. Override with the JWT_SECRET environment variable. */
    private String secret = "dev-only-secret-change-me-0123456789abcdef";
    /** Paths that require a valid Bearer token. */
    private List<String> protectedPrefixes = List.of("/secure/");

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public List<String> getProtectedPrefixes() { return protectedPrefixes; }
    public void setProtectedPrefixes(List<String> protectedPrefixes) { this.protectedPrefixes = protectedPrefixes; }
}
