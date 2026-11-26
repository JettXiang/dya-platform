package com.collect.platform.notify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 站内通知与已读状态的数据访问层。
 */
@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listFor(long userId) {
        return jdbc.queryForList("""
                SELECT n.id, n.kind, n.title, n.body, n.created_at, n.require_next_day,
                       (SELECT COUNT(*) FROM notification_reads r WHERE r.notification_id = n.id AND r.user_id = ?) AS read_flag
                FROM notifications n
                WHERE n.scope = 'all' OR n.target_user_id = ?
                ORDER BY n.id DESC LIMIT 50
                """, userId, userId);
    }

    public void markRead(long id, long userId) {
        jdbc.update("INSERT IGNORE INTO notification_reads (notification_id, user_id) VALUES (?,?)",
                id, userId);
    }

    public void insert(long senderId, String scope, Long targetUserId, String kind,
                       String title, String body, int requireNextDay) {
        jdbc.update("""
                INSERT INTO notifications (sender_id, scope, target_user_id, kind, title, body, require_next_day)
                VALUES (?,?,?,?,?,?,?)
                """, senderId, scope, targetUserId, kind, title, body, requireNextDay);
    }

    public List<Map<String, Object>> streamRows(long userId, long lastId) {
        return jdbc.queryForList("""
                SELECT n.id, n.kind, n.title, n.body, n.created_at
                FROM notifications n
                WHERE (n.scope = 'all' OR n.target_user_id = ?)
                  AND n.id > ?
                  AND NOT EXISTS (SELECT 1 FROM notification_reads r
                                  WHERE r.notification_id = n.id AND r.user_id = ?)
                ORDER BY n.id LIMIT 20
                """, userId, lastId, userId);
    }

    public long maxId(long userId) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(MAX(n.id), 0) FROM notifications n
                WHERE n.scope = 'all' OR n.target_user_id = ?
                """, Long.class, userId);
        return v == null ? 0 : v;
    }
}
