package com.collect.platform.admin;

import com.collect.platform.common.ConflictException;
import com.collect.platform.security.UserContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户与采集账号管理：校验、密码加密、逻辑停用等业务规则。
 */
@Service
public class AdminService {

    private static final Set<String> ROLES = Set.of("admin", "uploader", "reviewer", "collector");
    private static final Set<String> ACCOUNT_STATES = Set.of("idle", "collecting", "stopped", "offline");

    private final AdminRepository repo;
    private final PasswordEncoder encoder;

    public AdminService(AdminRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    public List<Map<String, Object>> users() {
        List<Map<String, Object>> rows = repo.listUsers();
        Map<Long, List<Map<String, Object>>> byUser = new LinkedHashMap<>();
        for (Map<String, Object> acc : repo.listAccounts()) {
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
        return out;
    }

    public Map<String, Object> createUser(Map<String, Object> body) {
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
            repo.insertUser(code, name, role, encoder.encode(password));
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("user_exists", "账号已存在：" + code);
        }
        Long id = repo.findUserIdByCode(code);
        return Map.of("id", id, "code", code);
    }

    public Map<String, Object> updateUser(long id, Map<String, Object> body) {
        Map<String, Object> cur = repo.findUser(id);
        if (cur == null) {
            throw new IllegalArgumentException("用户不存在");
        }
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
        repo.updateUser(id, name, role, status, hash, invalidate ? 1 : 0);
        return Map.of("id", id, "code", code, "invalidated", invalidate);
    }

    /**
     * 停用账号（逻辑删除）：终端台账、采集数据全部保留，可随时重新启用。
     * 停用会让 token_version +1，已登录的会话立即失效。
     */
    public Map<String, Object> disableUser(long id) {
        UserContext me = UserContext.get();
        if (me != null && me.getUserId() != null && me.getUserId() == id) {
            throw new IllegalArgumentException("不能停用自己的账号");
        }
        Map<String, Object> cur = repo.findUser(id);
        if (cur == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if ("admin".equals(String.valueOf(cur.get("role"))) && repo.countEnabledAdminsExcluding(id) == 0) {
            throw new IllegalArgumentException("这是最后一个启用中的管理员，不能停用");
        }
        repo.disableUser(id);
        return Map.of("id", id, "code", String.valueOf(cur.get("code")), "status", 0);
    }

    public List<Map<String, Object>> accounts() {
        return repo.listAccountsWithUser();
    }

    public Map<String, Object> createAccount(Map<String, Object> body) {
        String code = text(body.get("code"));
        String machine = text(body.get("machineLabel"));
        if (code == null || code.length() > 32) {
            throw new IllegalArgumentException("账号编码必填且不超过 32 个字符");
        }
        if (body.get("userId") == null) {
            throw new IllegalArgumentException("必须绑定用户");
        }
        long userId = ((Number) body.get("userId")).longValue();
        if (!repo.userExists(userId)) {
            throw new IllegalArgumentException("绑定的用户不存在");
        }
        try {
            repo.insertAccount(code, userId, machine);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("account_exists", "账号编码已存在：" + code);
        }
        Long id = repo.findAccountIdByCode(code);
        return Map.of("id", id, "code", code);
    }

    public Map<String, Object> updateAccount(long id, Map<String, Object> body) {
        Map<String, Object> cur = repo.findAccount(id);
        if (cur == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        long userId = body.get("userId") == null
                ? ((Number) cur.get("user_id")).longValue()
                : ((Number) body.get("userId")).longValue();
        if (!repo.userExists(userId)) {
            throw new IllegalArgumentException("绑定的用户不存在");
        }
        String machine = text(body.get("machineLabel")) != null
                ? text(body.get("machineLabel")) : (String) cur.get("machine_label");
        String state = text(body.get("state")) != null
                ? text(body.get("state")) : String.valueOf(cur.get("state"));
        if (!ACCOUNT_STATES.contains(state)) {
            throw new IllegalArgumentException("账号状态不合法");
        }
        repo.updateAccount(id, userId, machine, state);
        return Map.of("id", id, "code", String.valueOf(cur.get("code")));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
