package com.collect.platform.admin;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.ConflictException;
import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class AdminController {

    private static final Set<String> ROLES = Set.of("admin", "uploader", "reviewer", "collector");
    private static final Set<String> ACCOUNT_STATES = Set.of("idle", "collecting", "stopped", "offline");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public AdminController(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @GetMapping("/users")
    public Result<List<Map<String, Object>> > users() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code, name, role, status, token_version, created_at
                FROM users ORDER BY id
                """);
        Map<Long, List<Map<String, Object>>> byUser = new LinkedHashMap<>();
        for (Map<String, Object> acc : jdbc.queryForList("""
                SELECT id, code, user_id, machine_label, last_heartbeat_at, state
                FROM accounts ORDER BY code
                """)) {
            long uid = ((Number) acc.get("user_id")).longValue();
            byUser.computeIfAbsent(uid, k -> new ArrayList<>()).add(acc);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("accounts", byUser.getOrDefault(id, List.of()));
            out.add(item);
        }
        return Result.ok(out);
    }

    @PostMapping("/users")
    @Audited("新增用户")
    public Result<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        String code = text(body.get("code"));
        String name = text(body.get("name"));
        String role = text(body.get("role"));
        String password = text(body.get("password"));
        if (code == null || code.length() > 32) {
            throw new IllegalArgumentException("账号必填且不超过 32 个字符");
        }
        if (name == null) {
            throw new IllegalArgumentException("姓名必填");
        }
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("角色不合法");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("初始密码至少 6 位");
        }
        try {
            jdbc.update("""
                    INSERT INTO users (code, name, role, password_hash, token_version, status)
                    VALUES (?,?,?,?,0,1)
                    """, code, name, role, encoder.encode(password));
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("user_exists", "账号已存在：" + code);
        }
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE code = ?", Long.class, code);
        return Result.ok(Map.of("id", id, "code", code));
    }

    @PutMapping("/users/{id}")
    @Audited("修改用户")
    public Result<Map<String, Object>> updateUser(@PathVariable long id, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM users WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("用户不存在");
        }
        Map<String, Object> cur = rows.get(0);
        String code = String.valueOf(cur.get("code"));
        String name = text(body.get("name")) != null ? text(body.get("name")) : String.valueOf(cur.get("name"));
        String role = text(body.get("role")) != null ? text(body.get("role")) : String.valueOf(cur.get("role"));
        int status = body.get("status") == null
                ? ((Number) cur.get("status")).intValue()
                : (Boolean.TRUE.equals(body.get("status")) || "1".equals(String.valueOf(body.get("status"))) ? 1 : 0);
        String password = text(body.get("password"));
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("角色不合法");
        }
        if (password != null && password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        boolean invalidate = !role.equals(String.valueOf(cur.get("role")))
                || status != ((Number) cur.get("status")).intValue()
                || password != null;
        String hash = password == null ? String.valueOf(cur.get("password_hash")) : encoder.encode(password);
        jdbc.update("""
                UPDATE users SET name = ?, role = ?, status = ?, password_hash = ?,
                       token_version = token_version + ?
                WHERE id = ?
                """, name, role, status, hash, invalidate ? 1 : 0, id);
        return Result.ok(Map.of("id", id, "code", code, "invalidated", invalidate));
    }

    /**
     * 停用账号（逻辑删除）：终端台账、采集数据全部保留，可随时重新启用。
     * 停用会让 token_version +1，已登录的会话立即失效。
     */
    @DeleteMapping("/users/{id}")
    @Audited("停用用户")
    public Result<Map<String, Object>> disableUser(@PathVariable long id) {
        UserContext me = UserContext.get();
        if (me != null && me.getUserId() != null && me.getUserId() == id) {
            throw new IllegalArgumentException("不能停用自己的账号");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code, role, status FROM users WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("用户不存在");
        }
        Map<String, Object> cur = rows.get(0);
        if ("admin".equals(String.valueOf(cur.get("role")))) {
            Long others = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE role = 'admin' AND status = 1 AND id <> ?", Long.class, id);
            if (others == null || others == 0) {
                throw new IllegalArgumentException("这是最后一个启用中的管理员，不能停用");
            }
        }
        jdbc.update("UPDATE users SET status = 0, token_version = token_version + 1 WHERE id = ?", id);
        return Result.ok(Map.of("id", id, "code", String.valueOf(cur.get("code")), "status", 0));
    }

    @GetMapping("/accounts")
    public Result<List<Map<String, Object>>> accounts() {
        return Result.ok(jdbc.queryForList("""
                SELECT a.id, a.code, a.user_id, u.code AS user_code, a.machine_label,
                       a.last_heartbeat_at, a.state
                FROM accounts a JOIN users u ON u.id = a.user_id ORDER BY a.code
                """));
    }

    @PostMapping("/accounts")
    @Audited("新增账号")
    public Result<Map<String, Object>> createAccount(@RequestBody Map<String, Object> body) {
        String code = text(body.get("code"));
        String machine = text(body.get("machineLabel"));
        if (code == null || code.length() > 32) {
            throw new IllegalArgumentException("账号编码必填且不超过 32 个字符");
        }
        if (body.get("userId") == null) {
            throw new IllegalArgumentException("必须绑定用户");
        }
        long userId = ((Number) body.get("userId")).longValue();
        if (jdbc.queryForList("SELECT id FROM users WHERE id = ?", userId).isEmpty()) {
            throw new IllegalArgumentException("绑定的用户不存在");
        }
        try {
            jdbc.update("""
                    INSERT INTO accounts (code, user_id, machine_label, state)
                    VALUES (?,?,?,'idle')
                    """, code, userId, machine);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("account_exists", "账号编码已存在：" + code);
        }
        Long id = jdbc.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, code);
        return Result.ok(Map.of("id", id, "code", code));
    }

    @PutMapping("/accounts/{id}")
    @Audited("修改账号")
    public Result<Map<String, Object>> updateAccount(@PathVariable long id, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM accounts WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("账号不存在");
        }
        Map<String, Object> cur = rows.get(0);
        long userId = body.get("userId") == null
                ? ((Number) cur.get("user_id")).longValue()
                : ((Number) body.get("userId")).longValue();
        if (jdbc.queryForList("SELECT id FROM users WHERE id = ?", userId).isEmpty()) {
            throw new IllegalArgumentException("绑定的用户不存在");
        }
        String machine = text(body.get("machineLabel")) != null
                ? text(body.get("machineLabel")) : (String) cur.get("machine_label");
        String state = text(body.get("state")) != null
                ? text(body.get("state")) : String.valueOf(cur.get("state"));
        if (!ACCOUNT_STATES.contains(state)) {
            throw new IllegalArgumentException("账号状态不合法");
        }
        jdbc.update("UPDATE accounts SET user_id = ?, machine_label = ?, state = ? WHERE id = ?",
                userId, machine, state, id);
        return Result.ok(Map.of("id", id, "code", String.valueOf(cur.get("code"))));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
