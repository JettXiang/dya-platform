package com.collect.platform.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 每日采集任务的数据访问层。
 */
@Repository
public class DailyTaskRepository {

    private final JdbcTemplate jdbc;

    public DailyTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** collectorCode 为空表示管理员看当天全部。 */
    public List<Map<String, Object>> listByDate(Timestamp from, Timestamp to, Date day, String collectorCode) {
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        args.add(from);
        args.add(to);
        args.add(from);
        args.add(to);
        args.add(day);
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.task_date, t.collector_code, u.name AS collector_name,
                       t.target_count, t.note, t.created_by,
                       (SELECT COUNT(*) FROM videos v WHERE v.collected_by = t.collector_code
                          AND v.collected_at >= ? AND v.collected_at < ?) AS actual_count,
                       (SELECT COUNT(DISTINCT v.author_sec_uid) FROM videos v WHERE v.collected_by = t.collector_code
                          AND v.collected_at >= ? AND v.collected_at < ? AND v.author_sec_uid IS NOT NULL) AS author_count,
                       (SELECT MAX(v.collected_at) FROM videos v WHERE v.collected_by = t.collector_code
                          AND v.collected_at >= ? AND v.collected_at < ?) AS last_at
                FROM daily_tasks t
                LEFT JOIN users u ON u.code = t.collector_code
                WHERE t.task_date = ?
                """);
        if (collectorCode != null) {
            sql.append(" AND t.collector_code = ?");
            args.add(collectorCode);
        }
        sql.append(" ORDER BY t.id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public boolean userExists(String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE code = ?", Integer.class, code);
        return n != null && n > 0;
    }

    public void upsert(Date day, String collectorCode, int target, String note, String createdBy) {
        jdbc.update("""
                INSERT INTO daily_tasks (task_date, collector_code, target_count, note, created_by)
                VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE target_count = VALUES(target_count),
                                        note = VALUES(note), updated_at = NOW(3)
                """, day, collectorCode, Math.max(target, 0), note, createdBy);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM daily_tasks WHERE id = ?", id);
    }
}
