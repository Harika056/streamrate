package com.streamrate.gateway.security;

import com.streamrate.gateway.config.JwtProperties;
import com.streamrate.gateway.util.Responses;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Validates `Authorization: Bearer <HS256 JWT>`. Protected prefixes require a valid token (401 otherwise).
 * A valid token's subject is stored on the exchange so the rate limiter can use it as the client identity.
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    public static final String SUBJECT_ATTR = "streamrate.jwt.subject";

    private final JwtProperties props;
    private final SecretKey key;

    public JwtAuthFilter(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        boolean protectedPath = props.getProtectedPrefixes().stream().anyMatch(path::startsWith);

        String subject = null;
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                subject = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(auth.substring(7).trim())
                        .getPayload()
                        .getSubject();
            } catch (JwtException | IllegalArgumentException e) {
                subject = null;
            }
        }

        if (protectedPath && subject == null) {
            return Responses.json(exchange, HttpStatus.UNAUTHORIZED,
                    "{\"error\":\"unauthorized\",\"message\":\"valid Bearer token required\"}", Map.of());
        }
        if (subject != null) {
            exchange.getAttributes().put(SUBJECT_ATTR, subject);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -200; // before the rate limiter
    }
}
