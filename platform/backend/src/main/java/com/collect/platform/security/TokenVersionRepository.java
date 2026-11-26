package com.collect.platform.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 令牌版本查询：用户停用或改密后 token_version 变化，旧令牌立即失效。
 */
@Repository
public class TokenVersionRepository {

    private final JdbcTemplate jdbc;

    public TokenVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Integer currentVersion(long userId) {
        return jdbc.queryForObject(
                "SELECT token_version FROM users WHERE id = ? AND status = 1",
                Integer.class, userId);
    }
}
