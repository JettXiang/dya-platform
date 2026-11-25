package com.collect.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    public JwtAuthFilter(JwtService jwtService, JdbcTemplate jdbcTemplate) {
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            Claims claims = jwtService.parse(token);
            if (!"access".equals(claims.get("type"))) {
                writeUnauthorized(response, "请使用访问令牌");
                return;
            }
            long userId = Long.parseLong(claims.getSubject());
            int tokenVersion = ((Number) claims.get("tv")).intValue();
            Integer current = jdbcTemplate.queryForObject(
                    "SELECT token_version FROM users WHERE id = ? AND status = 1",
                    Integer.class, userId);
            if (current == null || current != tokenVersion) {
                writeUnauthorized(response, "登录状态已变更，请重新登录");
                return;
            }

            String role = String.valueOf(claims.get("role"));
            var auth = new UsernamePasswordAuthenticationToken(
                    claims.get("code"), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));
            SecurityContextHolder.getContext().setAuthentication(auth);
            UserContext.set(new UserContext(userId, String.valueOf(claims.get("code")),
                    role, clientIp(request)));
            try {
                chain.doFilter(request, response);
            } finally {
                UserContext.clear();
            }
            return;
        } catch (JwtException | IllegalArgumentException ex) {
            writeUnauthorized(response, "登录已失效，请重新登录");
            return;
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
