package com.collect.platform.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 上传批次、尝试记录、确认状态的数据访问层。
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listByDate(String date) {
        return jdbc.queryForList("""
                SELECT batch_no, `date`, type, total, dedup_total, state, retryable, next_retry_at,
                       attempt_count, created_at, closed_at
                FROM batches WHERE `date` = ? ORDER BY id
                """, date);
    }

    public Map<String, Object> findByNo(String batchNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM batches WHERE batch_no = ?", batchNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String findState(String batchNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT state FROM batches WHERE batch_no = ?", batchNo);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("state"));
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

    public List<Map<String, Object>> items(String batchNo) {
        return jdbc.queryForList("""
                SELECT seq, sec_uid, confirm_state, confirm_at, confirm_evidence, confirm_source
                FROM batch_items WHERE batch_no = ? ORDER BY seq LIMIT 500
                """, batchNo);
    }

    public int markReceived(String batchNo, String reason) {
        return jdbc.update("""
                UPDATE batches SET state = 'success', retryable = 0, next_retry_at = NULL,
                       note = CONCAT(COALESCE(note, ''), ' | 对账已接收:', ?)
                WHERE batch_no = ? AND state IN ('unknown','failed')
                """, reason, batchNo);
    }

    public void confirmItems(String batchNo, String evidence) {
        jdbc.update("""
                UPDATE batch_items SET confirm_state = 'confirmed', confirm_at = NOW(3),
                       confirm_evidence = ?, confirm_source = 'manual'
                WHERE batch_no = ?
                """, evidence, batchNo);
    }

    public int markFailed(String batchNo, String reason) {
        return jdbc.update("""
                UPDATE batches SET state = 'failed', retryable = 1,
                       next_retry_at = DATE_ADD(NOW(3), INTERVAL 300 SECOND),
                       note = CONCAT(COALESCE(note, ''), ' | 对账未收到:', ?)
                WHERE batch_no = ? AND state IN ('unknown','failed')
                """, reason, batchNo);
    }

    public void markItemsNotReceived(String batchNo, String evidence) {
        jdbc.update("""
                UPDATE batch_items SET confirm_state = 'not_received', confirm_at = NOW(3),
                       confirm_evidence = ?, confirm_source = 'manual'
                WHERE batch_no = ?
                """, evidence, batchNo);
    }

    public int voidBatch(String batchNo, String reason, String evidence) {
        return jdbc.update("""
                UPDATE batches SET state = 'cancelled', retryable = 0, void_reason = ?, void_evidence = ?
                WHERE batch_no = ? AND state IN ('pending','failed','unknown')
                """, reason, evidence, batchNo);
    }

    public void markItemsVoided(String batchNo) {
        jdbc.update("""
                UPDATE batch_items SET confirm_state = 'not_received', confirm_at = NOW(3),
                       confirm_evidence = '批次作废', confirm_source = 'manual'
                WHERE batch_no = ?
                """, batchNo);
    }

    public String findConfigValue(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT `value` AS v FROM configs WHERE `key` = ?", key);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("v"));
    }

    public void insertConfigJson(String key, String json, Long userId) {
        jdbc.update("INSERT INTO configs (`key`, `value`, updated_by) VALUES (?, CAST(? AS JSON), ?)",
                key, json, userId);
    }

    public void insertNotification(Long senderId, String title, String body) {
        jdbc.update("""
                INSERT INTO notifications (sender_id, scope, target_user_id, kind, title, body, require_next_day)
                VALUES (?, 'all', NULL, 'alert', ?, ?, 1)
                """, senderId, title, body);
    }
}
