package com.collect.platform.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**", "/healthz", "/api/v1/healthz",
                                "/error", "/actuator/health", "/ws/**",
                                "/api/v1/collect/ingest", "/api/v1/collect/heartbeat").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/collect/frame").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/cdp/announce").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/collect/command/pending").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/collect/command/ack").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/monitor/batches/**",
                                "/api/v1/monitor/anomalies").hasAnyRole("ADMIN", "UPLOADER")
                        .requestMatchers("/api/v1/monitor/timeline").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/notifications").hasRole("ADMIN")
                        .requestMatchers("/api/v1/users/**", "/api/v1/accounts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/cdp/terminals").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/cdp/terminals/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/cdp/terminals/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/daily-tasks", "/api/v1/daily-tasks/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/daily-tasks/**").hasRole("ADMIN")
                        // 词库：采集员可取词、记词、看周分析、提交新词；维护/删除/导出/审批仍仅管理员
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/keywords/random").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/search/keywords").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/keywords").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/analysis").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/search/keywords/suggest").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/keywords/suggestions").authenticated()
                        .requestMatchers("/api/v1/search/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/batches/**").hasAnyRole("ADMIN", "UPLOADER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/videos/upload").hasAnyRole("ADMIN", "UPLOADER")
                        .requestMatchers("/actuator/**").hasAnyRole("ADMIN", "UPLOADER")
                        .anyRequest().authenticated())
                // 未认证统一返回 401（而不是 Spring 默认的 403），前端据此清理会话并回到登录页
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "登录已失效，请重新登录"))
                        .accessDeniedHandler((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "无权限访问该资源")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
