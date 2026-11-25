package com.collect.platform.monitor;

import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {

    private final JdbcTemplate jdbc;

    public MonitorController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/timeline")
    public Result<Map<String, Object>> timeline(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Boolean success,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {

        var ctx = UserContext.get();
        String effectiveActor = actor;
        if ("collector".equalsIgnoreCase(ctx.getRole())) {
            effectiveActor = ctx.getCode();
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (Math.max(page, 1) - 1) * safeSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (effectiveActor != null && !effectiveActor.isBlank()) where.append(" AND operator = ?");
        if (action != null && !action.isBlank()) where.append(" AND action = ?");
        if (from != null && !from.isBlank()) where.append(" AND created_at >= ?");
        if (to != null && !to.isBlank()) where.append(" AND created_at <= ?");
        if (success != null) where.append(" AND success = ?");

        var args = new java.util.ArrayList<Object>();
        if (effectiveActor != null && !effectiveActor.isBlank()) args.add(effectiveActor);
        if (action != null && !action.isBlank()) args.add(action);
        if (from != null && !from.isBlank()) args.add(from);
        if (to != null && !to.isBlank()) args.add(to);
        if (success != null) args.add(success ? 1 : 0);

        int total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs" + where, Integer.class, args.toArray());
        args.add(safeSize);
        args.add(offset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", safeSize);
        out.put("items", jdbc.queryForList("""
                        SELECT event_id, operator, operator_type, action, method, uri, params, ip,
                               success, error_message, duration_ms, object_type, object_id, created_at
                        FROM audit_logs
                        """ + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", args.toArray()));
        return Result.ok(out);
    }

    @GetMapping("/batches/{batchNo}/chain")
    public Result<Map<String, Object>> chain(@PathVariable String batchNo) {
        List<Map<String, Object>> batch = jdbc.queryForList(
                "SELECT * FROM batches WHERE batch_no = ?", batchNo);
        if (batch.isEmpty()) {
            return Result.fail("批次不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batch.get(0));
        out.put("attempts", jdbc.queryForList("""
                SELECT attempt_no, started_at, ended_at, duration_ms, transport_result, business_result,
                       http_status, receipt_raw, error
                FROM upload_attempts WHERE batch_no = ? ORDER BY attempt_no
                """, batchNo));
        out.put("confirm_states", jdbc.queryForList("""
                SELECT confirm_state, COUNT(*) AS cnt FROM batch_items
                WHERE batch_no = ? GROUP BY confirm_state
                """, batchNo));
        out.put("operations", jdbc.queryForList("""
                SELECT operator, action, success, error_message, duration_ms, created_at
                FROM audit_logs WHERE object_id = ? ORDER BY created_at DESC LIMIT 50
                """, batchNo));
        return Result.ok(out);
    }

    @GetMapping("/anomalies")
    public Result<Map<String, Object>> anomalies(@RequestParam String date,
                                                 @RequestParam(defaultValue = "all") String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (type.equals("all") || type.equals("unknown")) {
            out.put("unknown_batches", jdbc.queryForList(
                    "SELECT batch_no, state, attempt_count, created_at FROM batches WHERE `date` = ? AND state = 'unknown'", date));
        }
        if (type.equals("all") || type.equals("timeout")) {
            out.put("timeout_attempts", jdbc.queryForList("""
                    SELECT a.batch_no, a.attempt_no, a.started_at, a.duration_ms
                    FROM upload_attempts a JOIN batches b ON b.batch_no = a.batch_no
                    WHERE b.`date` = ? AND a.transport_result = 'timeout'
                    """, date));
        }
        if (type.equals("all") || type.equals("alert_unread")) {
            out.put("unread_alerts", jdbc.queryForList("""
                    SELECT n.id, n.title, n.created_at FROM notifications n
                    WHERE n.kind = 'alert' AND n.created_at >= ?
                      AND NOT EXISTS (SELECT 1 FROM notification_reads r WHERE r.notification_id = n.id)
                    """, date + " 00:00:00"));
        }
        if (type.equals("all") || type.equals("offline")) {
            out.put("offline_accounts", jdbc.queryForList("""
                    SELECT code, state, last_heartbeat_at FROM accounts
                    WHERE state = 'offline' OR last_heartbeat_at < DATE_SUB(NOW(3), INTERVAL 3 MINUTE)
                    """));
        }
        return Result.ok(out);
    }
}
