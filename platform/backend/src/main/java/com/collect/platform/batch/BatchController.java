package com.collect.platform.batch;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.ConflictException;
import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BatchController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam String date) {
        return Result.ok(jdbc.queryForList("""
                SELECT batch_no, `date`, type, total, dedup_total, state, retryable, next_retry_at,
                       attempt_count, created_at, closed_at
                FROM batches WHERE `date` = ? ORDER BY id
                """, date));
    }

    @GetMapping("/{batchNo}")
    public Result<Map<String, Object>> detail(@PathVariable String batchNo) {
        List<Map<String, Object>> batches = jdbc.queryForList(
                "SELECT * FROM batches WHERE batch_no = ?", batchNo);
        if (batches.isEmpty()) {
            return Result.fail("批次不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batches.get(0));
        out.put("attempts", jdbc.queryForList("""
                SELECT attempt_no, started_at, ended_at, duration_ms, transport_result, business_result,
                       http_status, receipt_raw, error
                FROM upload_attempts WHERE batch_no = ? ORDER BY attempt_no
                """, batchNo));
        out.put("confirm_states", jdbc.queryForList("""
                SELECT confirm_state, COUNT(*) AS cnt FROM batch_items
                WHERE batch_no = ? GROUP BY confirm_state
                """, batchNo));
        out.put("items", jdbc.queryForList("""
                SELECT seq, sec_uid, confirm_state, confirm_at, confirm_evidence, confirm_source
                FROM batch_items WHERE batch_no = ? ORDER BY seq LIMIT 500
                """, batchNo));
        return Result.ok(out);
    }

    @PostMapping("/{batchNo}/reconcile")
    @Audited("批次对账")
    public Result<Map<String, Object>> reconcile(@PathVariable String batchNo,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        String result = text(body == null ? null : body.get("result"));
        String reason = text(body == null ? null : body.get("reason"));
        String evidence = text(body == null ? null : body.get("evidence"));
        if (result == null || (!"received".equals(result) && !"not_received".equals(result))) {
            throw new IllegalArgumentException("result 必须为 received 或 not_received");
        }
        if (reason == null) {
            throw new IllegalArgumentException("对账必须填写原因");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT state FROM batches WHERE batch_no = ?", batchNo);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("批次不存在");
        }
        String state = String.valueOf(rows.get(0).get("state"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchNo", batchNo);

        if ("received".equals(result)) {
            if ("success".equals(state)) {
                out.put("result", "already_success");
                out.put("state", state);
                return Result.ok("already_success", out);
            }
            if (!"unknown".equals(state) && !"failed".equals(state)) {
                throw new ConflictException("batch_not_reconcilable", "当前状态不支持对账为已接收：" + state);
            }
            int updated = jdbc.update("""
                    UPDATE batches SET state = 'success', retryable = 0, next_retry_at = NULL,
                           note = CONCAT(COALESCE(note, ''), ' | 对账已接收:', ?)
                    WHERE batch_no = ? AND state IN ('unknown','failed')
                    """, cut(reason, 255), batchNo);
            if (updated == 0) {
                throw new ConflictException("batch_not_reconcilable", "状态已变化，请刷新后重试");
            }
            jdbc.update("""
                    UPDATE batch_items SET confirm_state = 'confirmed', confirm_at = NOW(3),
                           confirm_evidence = ?, confirm_source = 'manual'
                    WHERE batch_no = ?
                    """, cut(evidence == null ? reason : evidence, 255), batchNo);
            out.put("result", "reconciled_received");
            out.put("state", "success");
            return Result.ok("reconciled_received", out);
        }

        if ("success".equals(state) || "rolled_back".equals(state) || "cancelled".equals(state)) {
            throw new ConflictException("batch_not_reconcilable", "当前状态不支持对账为未收到：" + state);
        }
        if (!"unknown".equals(state) && !"failed".equals(state)) {
            throw new ConflictException("batch_not_reconcilable", "仅 unknown/failed 可对账为未收到：" + state);
        }
        jdbc.update("""
                UPDATE batches SET state = 'failed', retryable = 1,
                       next_retry_at = DATE_ADD(NOW(3), INTERVAL 300 SECOND),
                       note = CONCAT(COALESCE(note, ''), ' | 对账未收到:', ?)
                WHERE batch_no = ? AND state IN ('unknown','failed')
                """, cut(reason, 255), batchNo);
        jdbc.update("""
                UPDATE batch_items SET confirm_state = 'not_received', confirm_at = NOW(3),
                       confirm_evidence = ?, confirm_source = 'manual'
                WHERE batch_no = ?
                """, cut(evidence == null ? reason : evidence, 255), batchNo);
        out.put("result", "reconciled_not_received");
        out.put("state", "failed");
        return Result.ok("reconciled_not_received", out);
    }

    @PostMapping("/{batchNo}/void")
    @Audited("批次作废")
    public Result<Map<String, Object>> voidBatch(@PathVariable String batchNo,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        String reason = text(body == null ? null : body.get("reason"));
        String evidence = text(body == null ? null : body.get("evidence"));
        if (reason == null) {
            throw new IllegalArgumentException("作废必须填写原因");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT state FROM batches WHERE batch_no = ?", batchNo);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("批次不存在");
        }
        String state = String.valueOf(rows.get(0).get("state"));
        if ("cancelled".equals(state)) {
            return Result.ok("already_void", Map.of("batchNo", batchNo, "state", state));
        }
        // 已成功、已回滚、正在上传的批次不允许作废，避免把在途或已入账数据抹掉
        if ("success".equals(state) || "rolled_back".equals(state) || "uploading".equals(state)) {
            throw new ConflictException("batch_not_voidable", "当前状态不允许作废：" + state);
        }
        int updated = jdbc.update("""
                UPDATE batches SET state = 'cancelled', retryable = 0, void_reason = ?, void_evidence = ?
                WHERE batch_no = ? AND state IN ('pending','failed','unknown')
                """, cut(reason, 255), cut(evidence, 2000), batchNo);
        if (updated == 0) {
            throw new ConflictException("batch_not_voidable", "状态已变化，请刷新后重试");
        }
        // 成员按“未收到”处理，保留批次记录与证据，不删除任何数据
        jdbc.update("""
                UPDATE batch_items SET confirm_state = 'not_received', confirm_at = NOW(3),
                       confirm_evidence = '批次作废', confirm_source = 'manual'
                WHERE batch_no = ?
                """, batchNo);
        return Result.ok("voided", Map.of("batchNo", batchNo, "state", "cancelled", "reason", reason));
    }

    @PostMapping("/upload-request")
    @Audited("请求补传")
    public Result<Map<String, Object>> uploadRequest(@RequestBody(required = false) Map<String, Object> body) {
        String reason = text(body == null ? null : body.get("reason"));
        if (reason == null) {
            throw new IllegalArgumentException("请说明补传原因");
        }
        String date = body.get("date") == null ? LocalDate.now().toString() : String.valueOf(body.get("date"));
        String key = "upload_request:" + date;
        List<Map<String, Object>> exists = jdbc.queryForList(
                "SELECT `value` AS v FROM configs WHERE `key` = ?", key);
        if (!exists.isEmpty()) {
            return Result.ok("exists", Map.of("date", date, "key", key, "status", "pending"));
        }

        var ctx = UserContext.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", date);
        payload.put("requested_by", ctx.getCode());
        payload.put("requested_at", LocalDateTime.now().toString());
        payload.put("reason", reason);
        payload.put("status", "pending");
        try {
            jdbc.update("INSERT INTO configs (`key`, `value`, updated_by) VALUES (?, CAST(? AS JSON), ?)",
                    key, mapper.writeValueAsString(payload), ctx.getUserId());
        } catch (DataIntegrityViolationException dup) {
            return Result.ok("exists", Map.of("date", date, "key", key, "status", "pending"));
        } catch (Exception e) {
            throw new IllegalStateException("写入补传请求失败", e);
        }
        // 只提醒，不触发任何上传动作
        jdbc.update("""
                INSERT INTO notifications (sender_id, scope, target_user_id, kind, title, body, require_next_day)
                VALUES (?, 'all', NULL, 'alert', ?, ?, 1)
                """, ctx.getUserId(), "收到补传请求 " + date, reason);
        return Result.ok("submitted", payload);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
