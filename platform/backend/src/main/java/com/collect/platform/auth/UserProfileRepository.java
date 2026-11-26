package com.collect.platform.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 当前登录用户资料的数据访问层。
 */
@Repository
public class UserProfileRepository {

    private final JdbcTemplate jdbc;

    public UserProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> findUser(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code, name, role, status, token_version
                FROM users WHERE id = ?
                """, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listAccounts(long userId) {
        return jdbc.queryForList("""
                SELECT id, code, machine_label, last_heartbeat_at, state
                FROM accounts WHERE user_id = ? ORDER BY code
                """, userId);
    }
}
