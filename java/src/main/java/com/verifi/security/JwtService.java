package com.verifi.security;

import com.verifi.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final AppProperties props;
    private final SecretKey key;

    public JwtService(AppProperties props) {
        this.props = props;
        this.key = key(props.getJwtSecret());
    }

    public String sign(String userId, String email, String role) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.expirySeconds());
        var builder = Jwts.builder()
                .subject(userId)
                .claim("userId", userId)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp));
        if (email != null && !email.isBlank()) builder.claim("email", email);
        return builder.signWith(key).compact();
    }

    public AuthUser verify(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        String userId = claims.get("userId", String.class);
        if (userId == null || userId.isBlank()) userId = claims.getSubject();
        String role = claims.get("role", String.class);
        if (userId == null || role == null) throw new io.jsonwebtoken.JwtException("missing claims");
        return new AuthUser(userId, claims.get("email", String.class), role);
    }

    private static SecretKey key(String secret) {
        byte[] bytes = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            try {
                bytes = MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
