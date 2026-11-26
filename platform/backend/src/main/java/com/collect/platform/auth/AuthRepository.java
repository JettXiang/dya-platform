package com.collect.platform.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 登录相关用户查询的数据访问层。
 */
@Repository
public class AuthRepository {

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> findActiveByCode(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code, name, role, password_hash, token_version FROM users WHERE code = ? AND status = 1",
                code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findActiveById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code, name, role, token_version FROM users WHERE id = ? AND status = 1", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void bumpTokenVersion(long userId) {
        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", userId);
    }

    public boolean existsByCode(String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE code = ?", Integer.class, code);
        return n != null && n > 0;
    }

    public void insertAdmin(String code, String name, String passwordHash) {
        jdbc.update(
                "INSERT INTO users (code, name, role, password_hash, token_version, status, created_at) VALUES (?,?,?,?,0,1,NOW(3))",
                code, name, "admin", passwordHash);
    }
}
