package com.collect.platform.auth;

import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final JdbcTemplate jdbc;

    public MeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Result<Map<String, Object>> me() {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            return Result.fail("未登录");
        }
        List<Map<String, Object>> users = jdbc.queryForList("""
                SELECT id, code, name, role, status, token_version
                FROM users WHERE id = ?
                """, ctx.getUserId());
        if (users.isEmpty()) {
            return Result.fail("用户不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", users.get(0));
        out.put("accounts", jdbc.queryForList("""
                SELECT id, code, machine_label, last_heartbeat_at, state
                FROM accounts WHERE user_id = ? ORDER BY code
                """, ctx.getUserId()));
        return Result.ok(out);
    }
}
