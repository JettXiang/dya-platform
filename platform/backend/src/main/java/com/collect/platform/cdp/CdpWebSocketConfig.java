package com.collect.platform.cdp;

import com.collect.platform.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * /ws/cdp 握手：浏览器 WS 不能带 Authorization 头，令牌放查询参数 token。
 */
@Configuration
@EnableWebSocket
public class CdpWebSocketConfig implements WebSocketConfigurer {

    private final CdpWebSocketHandler handler;
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public CdpWebSocketConfig(CdpWebSocketHandler handler, JwtService jwtService, JdbcTemplate jdbc) {
        this.handler = handler;
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/cdp")
                .addInterceptors(new TokenInterceptor())
                .setAllowedOriginPatterns("*");
    }

    private class TokenInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            try {
                String query = request.getURI().getQuery();
                String token = null;
                String terminalId = null;
                String slot = null;
                if (query != null) {
                    for (String part : query.split("&")) {
                        if (part.startsWith("token=")) {
                            token = java.net.URLDecoder.decode(part.substring(6),
                                    java.nio.charset.StandardCharsets.UTF_8);
                        } else if (part.startsWith("terminalId=")) {
                            terminalId = java.net.URLDecoder.decode(part.substring(11),
                                    java.nio.charset.StandardCharsets.UTF_8);
                        } else if (part.startsWith("slot=")) {
                            slot = java.net.URLDecoder.decode(part.substring(5),
                                    java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                if (token == null || token.isBlank()) {
                    return false;
                }
                Claims claims = jwtService.parse(token);
                if (!"access".equals(claims.get("type"))) {
                    return false;
                }
                long userId = Long.parseLong(claims.getSubject());
                int tv = ((Number) claims.get("tv")).intValue();
                Integer current = jdbc.queryForObject(
                        "SELECT token_version FROM users WHERE id = ? AND status = 1",
                        Integer.class, userId);
                if (current == null || current != tv) {
                    return false;
                }
                attributes.put("code", String.valueOf(claims.get("code")));
                attributes.put("role", String.valueOf(claims.get("role")));
                attributes.put("terminalId", terminalId == null ? "" : terminalId);
                attributes.put("slot", slot == null ? "" : slot);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }

    @SuppressWarnings("unused")
    private static String host(ServerHttpRequest request) {
        URI uri = request.getURI();
        return uri.getHost();
    }
}
