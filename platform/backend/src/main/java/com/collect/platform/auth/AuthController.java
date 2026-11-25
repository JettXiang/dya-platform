package com.collect.platform.auth;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import com.collect.platform.security.JwtService;
import com.collect.platform.security.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    @Audited("登录")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, code, name, role, password_hash, token_version FROM users WHERE code = ? AND status = 1",
                req.getCode());
        if (rows.isEmpty()) {
            return Result.fail("账号不存在或已停用");
        }
        Map<String, Object> row = rows.get(0);
        if (!passwordEncoder.matches(req.getPassword(), String.valueOf(row.get("password_hash")))) {
            return Result.fail("密码错误");
        }
        return Result.ok(issue(row));
    }

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshRequest req) {
        try {
            Claims claims = jwtService.parse(req.getRefreshToken());
            if (!"refresh".equals(claims.get("type"))) {
                return Result.fail("令牌类型错误");
            }
            long userId = Long.parseLong(claims.getSubject());
            var rows = jdbcTemplate.queryForList(
                    "SELECT id, code, name, role, token_version FROM users WHERE id = ? AND status = 1", userId);
            if (rows.isEmpty()) {
                return Result.fail("账号不存在或已停用");
            }
            Map<String, Object> row = rows.get(0);
            int tv = ((Number) row.get("token_version")).intValue();
            if (tv != ((Number) claims.get("tv")).intValue()) {
                return Result.fail("令牌已失效，请重新登录");
            }
            return Result.ok(issue(row));
        } catch (Exception e) {
            return Result.fail("刷新令牌无效");
        }
    }

    @PostMapping("/logout")
    @Audited("退出登录")
    public Result<Void> logout() {
        var ctx = UserContext.get();
        if (ctx == null) {
            return Result.fail("未登录");
        }
        jdbcTemplate.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", ctx.getUserId());
        return Result.ok(null);
    }

    private Map<String, Object> issue(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        String code = String.valueOf(row.get("code"));
        String role = String.valueOf(row.get("role"));
        int tv = ((Number) row.get("token_version")).intValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", jwtService.issueAccess(id, code, role, tv));
        out.put("refreshToken", jwtService.issueRefresh(id, code, role, tv));
        out.put("user", Map.of("id", id, "code", code, "name", String.valueOf(row.get("name")), "role", role));
        return out;
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String code;
        @NotBlank
        private String password;
    }

    @Data
    public static class RefreshRequest {
        @NotBlank
        private String refreshToken;
    }
}
