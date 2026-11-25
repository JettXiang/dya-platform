package com.collect.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;

@Component
public class JwtService {

    private final Key key;
    private final long accessMillis;
    private final long refreshMillis;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-hours:8}") long accessHours,
                      @Value("${app.jwt.refresh-days:7}") long refreshDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessMillis = accessHours * 3600_000L;
        this.refreshMillis = refreshDays * 86400_000L;
    }

    public String issueAccess(long userId, String code, String role, int tokenVersion) {
        return issue(userId, code, role, tokenVersion, "access", accessMillis);
    }

    public String issueRefresh(long userId, String code, String role, int tokenVersion) {
        return issue(userId, code, role, tokenVersion, "refresh", refreshMillis);
    }

    private String issue(long userId, String code, String role, int tokenVersion, String type, long ttl) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(Long.toString(userId))
                .addClaims(Map.of(
                        "code", code,
                        "role", role,
                        "tv", tokenVersion,
                        "type", type))
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttl))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build()
                .parseClaimsJws(token).getBody();
    }
}
