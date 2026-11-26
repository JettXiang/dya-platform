package com.collect.platform.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 日报与采集日报导出的数据访问层。
 */
@Repository
public class ReportRepository {

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countScheduledWork(LocalDate d) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedules WHERE `date` = ? AND status = 'work'", Integer.class, d);
        return n == null ? 0 : n;
    }

    public int countDayFinals(LocalDate d, boolean onlyComplete) {
        String sql = onlyComplete
                ? "SELECT COUNT(*) FROM day_finals WHERE `date` = ? AND complete = 1"
                : "SELECT COUNT(*) FROM day_finals WHERE `date` = ?";
        Integer n = jdbc.queryForObject(sql, Integer.class, d);
        return n == null ? 0 : n;
    }

    public int countRuns(LocalDate d) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE `date` = ?", Integer.class, d);
        return n == null ? 0 : n;
    }

    public int countAuthorsCollected(LocalDate d) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT s.sec_uid) FROM authors_source s
                JOIN runs r ON r.id = s.run_id WHERE r.`date` = ?
                """, Integer.class, d);
        return n == null ? 0 : n;
    }

    public int countVideos(Object from, Object to) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM videos WHERE collected_at >= ? AND collected_at < ?", Integer.class, from, to);
        return n == null ? 0 : n;
    }

    public int countDirectAuthors(Object from, Object to) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT author_sec_uid) FROM videos
                WHERE collected_at >= ? AND collected_at < ? AND author_sec_uid IS NOT NULL
                """, Integer.class, from, to);
        return n == null ? 0 : n;
    }

    public Object maxCollectedAt(Object from, Object to) {
        return jdbc.queryForObject(
                "SELECT MAX(collected_at) FROM videos WHERE collected_at >= ? AND collected_at < ?", Object.class, from, to);
    }

    public List<Map<String, Object>> listBatches(LocalDate d) {
        return jdbc.queryForList(
                "SELECT batch_no, type, state, total, dedup_total, attempt_count FROM batches WHERE `date` = ? ORDER BY id", d);
    }

    public List<Map<String, Object>> listAccountRows(LocalDate d) {
        return jdbc.queryForList("""
                SELECT a.code AS account_code, u.name,
                       COALESCE(df.complete, 0) AS complete,
                       COALESCE(df.client_pending_chunks, 0) AS pending_chunks,
                       df.revision, df.evaluated_at, a.last_heartbeat_at,
                       (SELECT COUNT(*) FROM runs r
                        WHERE r.account_id = a.id AND r.`date` = ? AND r.voided_at IS NULL) AS runs,
                       (SELECT COUNT(DISTINCT src.sec_uid) FROM authors_source src
                        JOIN runs r ON r.id = src.run_id
                        WHERE r.account_id = a.id AND r.`date` = ? AND r.voided_at IS NULL) AS authors
                FROM schedules sc
                JOIN accounts a ON a.id = sc.account_id
                JOIN users u ON u.id = a.user_id
                LEFT JOIN day_finals df ON df.account_id = a.id AND df.`date` = ?
                WHERE sc.`date` = ? AND sc.status = 'work'
                ORDER BY a.code
                """, d, d, d, d);
    }

    /** 采集日报导出：collectorCode 非空时只导出该采集人。 */
    public List<Map<String, Object>> exportRows(Object from, Object to, String collectorCode) {
        StringBuilder sql = new StringBuilder("""
                SELECT v.collected_by,
                       COALESCE(u.name, v.collected_by) AS collector_name,
                       GROUP_CONCAT(DISTINCT v.terminal_code) AS terminals,
                       COUNT(*) AS video_count,
                       COUNT(DISTINCT v.author_sec_uid) AS author_count,
                       MIN(v.collected_at) AS first_at,
                       MAX(v.collected_at) AS last_at,
                       SUM(CASE WHEN v.status = 'uploaded' THEN 1 ELSE 0 END) AS uploaded,
                       SUM(CASE WHEN v.status = 'pending' THEN 1 ELSE 0 END) AS pending,
                       SUM(CASE WHEN v.status = 'failed' THEN 1 ELSE 0 END) AS failed
                FROM videos v LEFT JOIN users u ON u.code = v.collected_by
                WHERE v.collected_at >= ? AND v.collected_at < ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(from);
        args.add(to);
        if (collectorCode != null) {
            sql.append(" AND v.collected_by = ?");
            args.add(collectorCode);
        }
        sql.append(" GROUP BY v.collected_by, u.name ORDER BY video_count DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> listTaskRows(LocalDate d) {
        return jdbc.queryForList("""
                SELECT t.collector_code, u.name AS collector_name, t.target_count
                FROM daily_tasks t LEFT JOIN users u ON u.code = t.collector_code
                WHERE t.task_date = ?
                """, java.sql.Date.valueOf(d));
    }
}
