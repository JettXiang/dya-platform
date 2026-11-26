package com.collect.platform.schedule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 排班数据访问层。
 */
@Repository
public class ScheduleRepository {

    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listByDate(LocalDate date) {
        return jdbc.queryForList("""
                SELECT a.code AS account_code, u.name, s.status, s.note
                FROM accounts a
                JOIN users u ON u.id = a.user_id
                LEFT JOIN schedules s ON s.account_id = a.id AND s.`date` = ?
                WHERE u.status = 1
                ORDER BY a.code
                """, date);
    }

    public Long findAccountId(String accountCode) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, accountCode);
    }

    public void upsert(long accountId, LocalDate date, String status, Object note) {
        jdbc.update("""
                INSERT INTO schedules (account_id, `date`, status, note) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE status = VALUES(status), note = VALUES(note)
                """, accountId, date, status, note);
    }
}
