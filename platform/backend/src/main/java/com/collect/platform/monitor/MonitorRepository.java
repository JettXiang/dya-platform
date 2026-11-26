package com.collect.platform.monitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 监控页（操作时间线、批次链路、异常）的数据访问层。
 */
@Repository
public class MonitorRepository {

    private final JdbcTemplate jdbc;

    public MonitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countAuditLogs(String where, Object[] args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs" + where, Integer.class, args);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> listAuditLogs(String where, Object[] args) {
        return jdbc.queryForList("""
                SELECT event_id, operator, operator_type, action, method, uri, params, ip,
                       success, error_message, duration_ms, object_type, object_id, created_at
                FROM audit_logs
                """ + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", args);
    }

    public Map<String, Object> findBatch(String batchNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM batches WHERE batch_no = ?", batchNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> attempts(String batchNo) {
        return jdbc.queryForList("""
                SELECT attempt_no, started_at, ended_at, duration_ms, transport_result, business_result,
                       http_status, receipt_raw, error
                FROM upload_attempts WHERE batch_no = ? ORDER BY attempt_no
                """, batchNo);
    }

    public List<Map<String, Object>> confirmStates(String batchNo) {
        return jdbc.queryForList("""
                SELECT confirm_state, COUNT(*) AS cnt FROM batch_items
                WHERE batch_no = ? GROUP BY confirm_state
                """, batchNo);
    }

    public List<Map<String, Object>> operations(String batchNo) {
        return jdbc.queryForList("""
                SELECT operator, action, success, error_message, duration_ms, created_at
                FROM audit_logs WHERE object_id = ? ORDER BY created_at DESC LIMIT 50
                """, batchNo);
    }

    public List<Map<String, Object>> unknownBatches(String date) {
        return jdbc.queryForList(
                "SELECT batch_no, state, attempt_count, created_at FROM batches WHERE `date` = ? AND state = 'unknown'", date);
    }

    public List<Map<String, Object>> timeoutAttempts(String date) {
        return jdbc.queryForList("""
                SELECT a.batch_no, a.attempt_no, a.started_at, a.duration_ms
                FROM upload_attempts a JOIN batches b ON b.batch_no = a.batch_no
                WHERE b.`date` = ? AND a.transport_result = 'timeout'
                """, date);
    }

    public List<Map<String, Object>> unreadAlerts(String dayStart) {
        return jdbc.queryForList("""
                SELECT n.id, n.title, n.created_at FROM notifications n
                WHERE n.kind = 'alert' AND n.created_at >= ?
                  AND NOT EXISTS (SELECT 1 FROM notification_reads r WHERE r.notification_id = n.id)
                """, dayStart);
    }

    public List<Map<String, Object>> offlineAccounts() {
        return jdbc.queryForList("""
                SELECT code, state, last_heartbeat_at FROM accounts
                WHERE state = 'offline' OR last_heartbeat_at < DATE_SUB(NOW(3), INTERVAL 3 MINUTE)
                """);
    }
}
