package com.collect.platform.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志写入：唯一的 SQL 与字段映射入口，供实时队列与 fallback 重放共用。
 */
@Repository
public class AuditRepository {

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO audit_logs
            (event_id, actor_id, operator, operator_type, triggered_by, action, method, uri, params,
             object_type, object_id, detail_json, ip, success, error_message, duration_ms, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEvent e) {
        jdbc.update(INSERT_SQL,
                e.getEventId(), e.getActorId(), e.getOperator(), e.getOperatorType(), e.getTriggeredBy(),
                e.getAction(), e.getMethod(), e.getUri(), e.getParams(),
                e.getObjectType(), e.getObjectId(), e.getDetailJson(), e.getIp(),
                e.isSuccess(), e.getErrorMessage(), e.getDurationMs(), createdAt(e));
    }

    public void batchInsert(List<AuditEvent> events) {
        jdbc.batchUpdate(INSERT_SQL, events, events.size(), AuditRepository::bind);
    }

    private static void bind(PreparedStatement ps, AuditEvent e) throws SQLException {
        ps.setString(1, e.getEventId());
        if (e.getActorId() == null) {
            ps.setNull(2, Types.BIGINT);
        } else {
            ps.setLong(2, e.getActorId());
        }
        ps.setString(3, e.getOperator());
        ps.setString(4, e.getOperatorType());
        if (e.getTriggeredBy() == null) {
            ps.setNull(5, Types.BIGINT);
        } else {
            ps.setLong(5, e.getTriggeredBy());
        }
        ps.setString(6, e.getAction());
        ps.setString(7, e.getMethod());
        ps.setString(8, e.getUri());
        ps.setString(9, e.getParams());
        ps.setString(10, e.getObjectType());
        ps.setString(11, e.getObjectId());
        ps.setString(12, e.getDetailJson());
        ps.setString(13, e.getIp());
        ps.setBoolean(14, e.isSuccess());
        ps.setString(15, e.getErrorMessage());
        if (e.getDurationMs() == null) {
            ps.setNull(16, Types.INTEGER);
        } else {
            ps.setInt(16, e.getDurationMs());
        }
        ps.setTimestamp(17, createdAt(e));
    }

    private static Timestamp createdAt(AuditEvent e) {
        return Timestamp.valueOf(e.getCreatedAt() == null ? LocalDateTime.now() : e.getCreatedAt());
    }
}
