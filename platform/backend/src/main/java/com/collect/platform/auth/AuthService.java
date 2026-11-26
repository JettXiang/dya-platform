package com.collect.platform.auth;

import com.collect.platform.common.Result;
import com.collect.platform.security.JwtService;
import com.collect.platform.security.UserContext;
import io.jsonwebtoken.Claims;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录、刷新令牌与退出登录。
 */
@Service
public class AuthService {

    private final AuthRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AuthRepository repo, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Result<Map<String, Object>> login(String code, String password) {
        Map<String, Object> row = repo.findActiveByCode(code);
        if (row == null) {
            return Result.fail("账号不存在或已停用");
        }
        if (!passwordEncoder.matches(password, String.valueOf(row.get("password_hash")))) {
            return Result.fail("密码错误");
        }
        return Result.ok(issue(row));
    }

    public Result<Map<String, Object>> refresh(String refreshToken) {
        try {
            Claims claims = jwtService.parse(refreshToken);
            if (!"refresh".equals(claims.get("type"))) {
                return Result.fail("令牌类型错误");
            }
            long userId = Long.parseLong(claims.getSubject());
            Map<String, Object> row = repo.findActiveById(userId);
            if (row == null) {
                return Result.fail("账号不存在或已停用");
            }
            int tv = ((Number) row.get("token_version")).intValue();
            if (tv != ((Number) claims.get("tv")).intValue()) {
                return Result.fail("令牌已失效，请重新登录");
            }
            return Result.ok(issue(row));
        } catch (Exception e) {
            return Result.fail("刷新令牌无效");
        }
    }

    public Result<Void> logout() {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            return Result.fail("未登录");
        }
        repo.bumpTokenVersion(ctx.getUserId());
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
}
