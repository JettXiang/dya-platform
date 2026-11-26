package com.collect.platform.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 用户与账号台账的数据访问层。
 */
@Repository
public class AdminRepository {

    private final JdbcTemplate jdbc;

    public AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listUsers() {
        return jdbc.queryForList("""
                SELECT id, code, name, role, status, token_version, created_at
                FROM users ORDER BY id
                """);
    }

    public List<Map<String, Object>> listAccountsWithUser() {
        return jdbc.queryForList("""
                SELECT a.id, a.code, a.user_id, u.code AS user_code, a.machine_label,
                       a.last_heartbeat_at, a.state
                FROM accounts a JOIN users u ON u.id = a.user_id ORDER BY a.code
                """);
    }

    /** 用户列表里内嵌的账号信息（不带 user_code）。 */
    public List<Map<String, Object>> listAccounts() {
        return jdbc.queryForList("""
                SELECT id, code, user_id, machine_label, last_heartbeat_at, state
                FROM accounts ORDER BY code
                """);
    }

    public void insertUser(String code, String name, String role, String passwordHash) {
        jdbc.update("""
                INSERT INTO users (code, name, role, password_hash, token_version, status)
                VALUES (?,?,?,?,0,1)
                """, code, name, role, passwordHash);
    }

    public Long findUserIdByCode(String code) {
        return jdbc.queryForObject("SELECT id FROM users WHERE code = ?", Long.class, code);
    }

    public Map<String, Object> findUser(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM users WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateUser(long id, String name, String role, int status, String passwordHash, int invalidate) {
        jdbc.update("""
                UPDATE users SET name = ?, role = ?, status = ?, password_hash = ?,
                       token_version = token_version + ?
                WHERE id = ?
                """, name, role, status, passwordHash, invalidate, id);
    }

    public long countEnabledAdminsExcluding(long id) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'admin' AND status = 1 AND id <> ?", Long.class, id);
        return n == null ? 0 : n;
    }

    public void disableUser(long id) {
        jdbc.update("UPDATE users SET status = 0, token_version = token_version + 1 WHERE id = ?", id);
    }

    public boolean userExists(long id) {
        return !jdbc.queryForList("SELECT id FROM users WHERE id = ?", id).isEmpty();
    }

    public void insertAccount(String code, long userId, String machineLabel) {
        jdbc.update("""
                INSERT INTO accounts (code, user_id, machine_label, state)
                VALUES (?,?,?,'idle')
                """, code, userId, machineLabel);
    }

    public Long findAccountIdByCode(String code) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, code);
    }

    public Map<String, Object> findAccount(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM accounts WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateAccount(long id, long userId, String machineLabel, String state) {
        jdbc.update("UPDATE accounts SET user_id = ?, machine_label = ?, state = ? WHERE id = ?",
                userId, machineLabel, state, id);
    }
}
