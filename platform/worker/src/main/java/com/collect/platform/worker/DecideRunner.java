package com.collect.platform.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单入口 decide：按当前时间 + DB 状态决定唯一动作。
 * 顺序：锁 → stale 清扫 → close（过点补跑）→ reminder → escalate → retry 窗口 → 最终告警。
 * 上传适配器受 X01/G04 阻断，close 只建批，不发起 attempt。
 */
@Component
public class DecideRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DecideRunner.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final String lockPath;
    private final String outDir;
    private final int timeoutStartSec;
    private final int staleMarginSec;
    private final UploadPort uploadPort;

    private final LocalTime reminderTime;
    private final LocalTime escalateTime;
    private final LocalTime closeTime;
    private final LocalTime retryStart;
    private final LocalTime retryEnd;
    private final LocalTime alertTime;

    public DecideRunner(JdbcTemplate jdbc,
                        @Value("${worker.lock-file}") String lockPath,
                        @Value("${worker.out-dir:./data/out}") String outDir,
                        @Value("${worker.timeout-start-sec:240}") int timeoutStartSec,
                        @Value("${worker.stale-margin-sec:60}") int staleMarginSec,
                        @Value("${worker.reminder-time:18:00}") String reminderTime,
                        @Value("${worker.escalate-time:18:10}") String escalateTime,
                        @Value("${worker.close-time:18:20}") String closeTime,
                        @Value("${worker.retry-start:18:25}") String retryStart,
                        @Value("${worker.retry-end:18:35}") String retryEnd,
                        @Value("${worker.alert-time:18:40}") String alertTime,
                        UploadPort uploadPort) {
        this.jdbc = jdbc;
        this.lockPath = lockPath;
        this.outDir = outDir;
        /* 故障矩阵 19：30 < timeout-start-sec < 300，非法配置直接拒绝启动 */
        if (timeoutStartSec < 30 || timeoutStartSec > 300) {
            throw new IllegalArgumentException(
                    "worker.timeout-start-sec 必须在 30..300 秒之间，当前=" + timeoutStartSec);
        }
        this.timeoutStartSec = timeoutStartSec;
        this.staleMarginSec = staleMarginSec;
        this.uploadPort = uploadPort;
        this.reminderTime = LocalTime.parse(reminderTime);
        this.escalateTime = LocalTime.parse(escalateTime);
        this.closeTime = LocalTime.parse(closeTime);
        this.retryStart = LocalTime.parse(retryStart);
        this.retryEnd = LocalTime.parse(retryEnd);
        this.alertTime = LocalTime.parse(alertTime);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        long startedAt = System.currentTimeMillis();
        try (FileChannel channel = FileChannel.open(Path.of(lockPath),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                log.warn("DECIDE_SKIPPED 未获得锁（另一个执行者正在运行）");
                return;
            }
            Map<String, Object> result = decide();
            result.put("elapsed_ms", System.currentTimeMillis() - startedAt);
            log.info("DECIDE_DONE {}", result);
        }
    }

    private Map<String, Object> decide() {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate date = LocalDate.now(ZONE);
        LocalTime now = LocalTime.now(ZONE);
        out.put("now", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        int noAttempt = jdbc.update("""
                UPDATE batches b SET b.state = 'unknown'
                WHERE b.state = 'uploading'
                  AND NOT EXISTS (SELECT 1 FROM upload_attempts a WHERE a.batch_no = b.batch_no)
                """);
        int stale = jdbc.update("""
                UPDATE batches b SET b.state = 'unknown'
                WHERE b.state = 'uploading'
                  AND (SELECT MAX(a.started_at) FROM upload_attempts a WHERE a.batch_no = b.batch_no)
                      < DATE_SUB(NOW(3), INTERVAL ? SECOND)
                """, timeoutStartSec + staleMarginSec);
        out.put("stale_no_attempt", noAttempt);
        out.put("stale_to_unknown", stale);

        int workAccounts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedules s WHERE s.`date` = ? AND s.status = 'work'",
                Integer.class, date);
        out.put("work_accounts", workAccounts);

        // close：过点即补跑，每天只成功执行一次；无排班也要跑 supplement
        if (!now.isBefore(closeTime) && !isDone("job:close:" + date)) {
            Map<String, Object> r = close(date, workAccounts);
            out.put("action", "close");
            out.put("close", r);
            out.put("upload_adapter", uploadPort.status());
            return out;
        }

        if (workAccounts == 0) {
            out.put("action", "noop:no_work_schedule");
            out.put("upload_adapter", uploadPort.status());
            return out;
        }

        // reminder：[18:00, 18:10)
        if (!now.isBefore(reminderTime) && now.isBefore(escalateTime)
                && !isDone("job:reminder:" + date)) {
            int missing = countMissingComplete(date);
            if (missing == 0) {
                markDone("job:reminder:" + date);
                out.put("action", "noop:all_complete");
            } else {
                int sent = remind(date);
                markDone("job:reminder:" + date);
                out.put("action", "reminder");
                out.put("reminded_users", sent);
                out.put("missing_accounts", missing);
            }
            out.put("upload_adapter", uploadPort.status());
            return out;
        }

        // escalate：[18:10, 18:20)
        if (!now.isBefore(escalateTime) && now.isBefore(closeTime)
                && !isDone("job:escalate:" + date)) {
            int missing = countMissingComplete(date);
            if (missing > 0) {
                escalate(date, missing);
            }
            markDone("job:escalate:" + date);
            out.put("action", missing > 0 ? "escalate" : "noop:all_complete");
            out.put("upload_adapter", uploadPort.status());
            return out;
        }

        // retry 窗口：只统计资格，不发起上传（适配器未启用）
        if (!now.isBefore(retryStart) && !now.isAfter(retryEnd)) {
            int eligible = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM batches WHERE `date` = ?
                      AND (state = 'pending' OR (state = 'failed' AND retryable = 1))
                    """, Integer.class, date);
            out.put("action", "retry_check");
            out.put("retry_eligible", eligible);
            out.put("upload_adapter", uploadPort.status());
            return out;
        }

        // 最终告警：过窗且仍有未完成批次
        if (!now.isBefore(alertTime) && !isDone("job:alert:" + date)) {
            int unfinished = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM batches WHERE `date` = ?
                      AND state IN ('pending','uploading','unknown','failed')
                    """, Integer.class, date);
            if (unfinished > 0) {
                alert(date, unfinished);
            }
            markDone("job:alert:" + date);
            out.put("action", unfinished > 0 ? "final_alert" : "noop:no_unfinished");
            out.put("unfinished_batches", unfinished);
            out.put("upload_adapter", uploadPort.status());
            return out;
        }

        out.put("action", "noop:outside_window");
        out.put("upload_adapter", uploadPort.status());
        return out;
    }

    private Map<String, Object> close(LocalDate date, int workAccounts) {
        // 不只看 day_finals.complete，逐个 run 复核：
        // final 已收 + chunk_seq 连续到 final + 分片总数等于 total + 来源计数等于分片总数
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.code,
                       COALESCE(d.complete, 0) AS df_complete,
                       COALESCE(d.client_pending_chunks, 0) AS pending_chunks,
                       COALESCE(d.expected_runs, 0) AS expected_runs,
                       (SELECT COUNT(*) FROM runs r
                        WHERE r.account_id = a.id AND r.`date` = ?
                          AND r.voided_at IS NULL) AS total_runs,
                       (SELECT COUNT(*) FROM runs r
                        WHERE r.account_id = a.id AND r.`date` = ?
                          AND r.voided_at IS NULL
                          AND r.final_received_at IS NOT NULL
                          AND r.final_seq IS NOT NULL
                          AND r.contiguous_seq = r.final_seq
                          AND r.received_count = r.total_count_client
                          AND (SELECT COUNT(*) FROM authors_source s WHERE s.run_id = r.id) = r.received_count
                       ) AS complete_runs,
                       (SELECT COUNT(*) FROM runs r2
                        WHERE r2.account_id = a.id AND r2.`date` = ?
                          AND r2.voided_at IS NOT NULL
                          AND JSON_CONTAINS(COALESCE(d.expected_run_ids, JSON_ARRAY()),
                                            JSON_QUOTE(r2.client_run_id))
                       ) AS expected_voided
                FROM schedules s JOIN accounts a ON a.id = s.account_id
                LEFT JOIN day_finals d ON d.account_id = a.id AND d.`date` = ?
                WHERE s.`date` = ? AND s.status = 'work'
                ORDER BY a.code
                """, date, date, date, date, date);

        List<Map<String, Object>> accounts = new ArrayList<>();
        int noDayFinal = 0;
        int pendingChunks = 0;
        int runMismatch = 0;
        for (Map<String, Object> row : rows) {
            int dfComplete = ((Number) row.get("df_complete")).intValue();
            int pending = ((Number) row.get("pending_chunks")).intValue();
            int expectedRuns = ((Number) row.get("expected_runs")).intValue();
            int expectedVoided = ((Number) row.get("expected_voided")).intValue();
            int expectedActive = Math.max(0, expectedRuns - expectedVoided);
            int totalRuns = ((Number) row.get("total_runs")).intValue();
            int completeRuns = ((Number) row.get("complete_runs")).intValue();
            if (dfComplete != 1) {
                noDayFinal++;
                continue;
            }
            if (pending != 0) {
                pendingChunks++;
                continue;
            }
            if (totalRuns != expectedActive || completeRuns != expectedActive) {
                runMismatch++;
                continue;
            }
            accounts.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("complete_accounts", accounts.size());
        out.put("excluded_no_day_final", noDayFinal);
        out.put("excluded_pending_chunks", pendingChunks);
        out.put("excluded_run_mismatch", runMismatch);
        if (accounts.isEmpty()) {
            out.put("supplement", createSupplement(date));
            markDone("job:close:" + date);
            audit("建批", "batch", "", true,
                    "无可入批账号 no_day_final=" + noDayFinal
                    + " pending_chunks=" + pendingChunks + " run_mismatch=" + runMismatch);
            out.put("batch_no", null);
            return out;
        }

        StringBuilder memberSql = new StringBuilder("""
                SELECT DISTINCT src.sec_uid FROM authors_source src
                JOIN runs r ON r.id = src.run_id
                WHERE r.`date` = ? AND r.account_id IN (
                """);
        Object[] params = new Object[accounts.size() + 1];
        params[0] = date;
        for (int i = 0; i < accounts.size(); i++) {
            memberSql.append(i == 0 ? "?" : ",?");
            params[i + 1] = ((Number) accounts.get(i).get("id")).longValue();
        }
        memberSql.append(") ORDER BY src.sec_uid");
        List<String> members = jdbc.queryForList(memberSql.toString(), String.class, params);

        if (members.isEmpty()) {
            out.put("supplement", createSupplement(date));
            markDone("job:close:" + date);
            audit("建批", "batch", "", true, "完整账号但无作者，close 空执行");
            out.put("batch_no", null);
            return out;
        }

        String type = accounts.size() == workAccounts ? "full" : "partial";
        String batchNo = batchNo(date, type);
        String note = "worker close 生成；上传适配器未启用（X01/G04），批次停留 pending";

        jdbc.update("""
                INSERT INTO batches (batch_no, `date`, type, total, dedup_total, state,
                                     retryable, attempt_count, created_at, closed_at, note)
                VALUES (?,?,?,?,?, 'pending', 1, 0, NOW(3), NOW(3), ?)
                """, batchNo, date, type, members.size(), members.size(), note);

        for (int i = 0; i < members.size(); i++) {
            jdbc.update("""
                    INSERT INTO batch_items (batch_no, seq, sec_uid, confirm_state)
                    VALUES (?,?,?, 'pending')
                    """, batchNo, i + 1, members.get(i));
        }

        Path file = writeTxt(batchNo, members);
        out.put("supplement", createSupplement(date));
        markDone("job:close:" + date);
        audit("建批", "batch", batchNo, true,
                "type=" + type + " accounts=" + accounts.size() + " members=" + members.size()
                + " run_level_verified=true");
        out.put("batch_no", batchNo);
        out.put("type", type);
        out.put("members", members.size());
        out.put("txt", file.toString());
        return out;
    }

    /**
     * 次日 supplement：昨日 failed 批次的 not_received 成员 + 昨日封账后才到的新成员。
     * 排除已 confirmed、当前 pending/uploading/unknown 中的成员。
     */
    private Map<String, Object> createSupplement(LocalDate date) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate prev = date.minusDays(1);
        int exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batches WHERE `date` = ? AND type = 'supplement'",
                Integer.class, date);
        if (exists > 0) {
            out.put("skipped", "already_exists");
            return out;
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(jdbc.queryForList("""
                SELECT DISTINCT bi.sec_uid
                FROM batch_items bi JOIN batches b ON b.batch_no = bi.batch_no
                WHERE b.`date` = ? AND b.state = 'failed' AND bi.confirm_state = 'not_received'
                  AND NOT EXISTS (SELECT 1 FROM batch_items x JOIN batches xb ON xb.batch_no = x.batch_no
                                  WHERE x.sec_uid = bi.sec_uid AND xb.state IN ('pending','uploading','unknown'))
                  AND NOT EXISTS (SELECT 1 FROM batch_items y JOIN batches yb ON yb.batch_no = y.batch_no
                                  WHERE y.sec_uid = bi.sec_uid AND y.confirm_state = 'confirmed')
                """, String.class, prev));

        candidates.addAll(jdbc.queryForList("""
                SELECT DISTINCT src.sec_uid
                FROM authors_source src JOIN runs r ON r.id = src.run_id
                WHERE r.`date` = ? AND r.voided_at IS NULL
                  AND src.received_at_server > TIMESTAMP(?, '18:20:00')
                  AND NOT EXISTS (SELECT 1 FROM batch_items x JOIN batches xb ON xb.batch_no = x.batch_no
                                  WHERE x.sec_uid = src.sec_uid AND xb.state IN ('pending','uploading','unknown'))
                  AND NOT EXISTS (SELECT 1 FROM batch_items y JOIN batches yb ON yb.batch_no = y.batch_no
                                  WHERE y.sec_uid = src.sec_uid AND y.confirm_state = 'confirmed')
                """, String.class, prev, prev.toString()));

        out.put("from_date", prev.toString());
        out.put("members", candidates.size());
        if (candidates.isEmpty()) {
            return out;
        }

        List<String> members = new ArrayList<>(candidates);
        members.sort(String::compareTo);
        String batchNo = batchNo(date, "supplement");
        String note = "supplement：来自 " + prev + " 的未收到/迟到成员；上传适配器未启用";
        jdbc.update("""
                INSERT INTO batches (batch_no, `date`, type, total, dedup_total, state,
                                     retryable, attempt_count, created_at, closed_at, note)
                VALUES (?,?, 'supplement', ?,?, 'pending', 1, 0, NOW(3), NOW(3), ?)
                """, batchNo, date, members.size(), members.size(), note);
        for (int i = 0; i < members.size(); i++) {
            jdbc.update("""
                    INSERT INTO batch_items (batch_no, seq, sec_uid, confirm_state)
                    VALUES (?,?,?, 'pending')
                    """, batchNo, i + 1, members.get(i));
        }
        Path file = writeTxt(batchNo, members);
        audit("建批", "batch", batchNo, true,
                "type=supplement from=" + prev + " members=" + members.size());
        out.put("batch_no", batchNo);
        out.put("txt", file.toString());
        return out;
    }

    private int countMissingComplete(LocalDate date) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM schedules s
                WHERE s.`date` = ? AND s.status = 'work'
                  AND NOT EXISTS (SELECT 1 FROM day_finals d
                                  WHERE d.account_id = s.account_id AND d.`date` = ? AND d.complete = 1)
                """, Integer.class, date, date);
    }

    private int remind(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.code, a.user_id FROM schedules s JOIN accounts a ON a.id = s.account_id
                WHERE s.`date` = ? AND s.status = 'work'
                  AND NOT EXISTS (SELECT 1 FROM day_finals d
                                  WHERE d.account_id = a.id AND d.`date` = ? AND d.complete = 1)
                ORDER BY a.code
                """, date, date);
        long sender = systemSender();
        if (sender == 0) {
            return 0;
        }
        int sent = 0;
        for (Map<String, Object> row : rows) {
            jdbc.update("""
                    INSERT INTO notifications (sender_id, scope, target_user_id, kind, title, body)
                    VALUES (?, 'one', ?, 'daily', ?, ?)
                    """, sender, ((Number) row.get("user_id")).longValue(),
                    "每日提醒：日终未完整", "账号 " + row.get("code") + " 在 " + date + " 未完成日终上报");
            sent++;
        }
        audit("每日提醒", "notification", date.toString(), true, "reminded=" + sent);
        return sent;
    }

    private void escalate(LocalDate date, int missing) {
        long sender = systemSender();
        if (sender == 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO notifications (sender_id, scope, target_user_id, kind, title, body)
                VALUES (?, 'all', NULL, 'alert', ?, ?)
                """, sender, "18:10 仍未收到日终上报：" + missing + " 个账号",
                "业务日 " + date + " 仍有 " + missing + " 个在岗账号未完成日终上报，请人工跟进");
        audit("升级提醒", "notification", date.toString(), true, "missing=" + missing);
    }

    private void alert(LocalDate date, int unfinished) {
        long sender = systemSender();
        if (sender == 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO notifications (sender_id, scope, target_user_id, kind, title, body)
                VALUES (?, 'all', NULL, 'alert', ?, ?)
                """, sender, "上传窗口结束：仍有 " + unfinished + " 个批次未完成",
                "业务日 " + date + " 过 18:35 仍有 " + unfinished + " 个批次处于 pending/uploading/unknown/failed");
        audit("最终告警", "batch", date.toString(), true, "unfinished=" + unfinished);
    }

    private long systemSender() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM users WHERE role = 'admin' ORDER BY id LIMIT 1", Long.class);
        return ids.isEmpty() ? 0 : ids.get(0);
    }

    private boolean isDone(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM configs WHERE `key` = ?",
                Integer.class, key) > 0;
    }

    private void markDone(String key) {
        jdbc.update("""
                INSERT IGNORE INTO configs (`key`, `value`, updated_by, updated_at)
                VALUES (?, JSON_OBJECT('done', true), NULL, NOW(3))
                """, key);
    }

    private String batchNo(LocalDate date, String type) {
        String prefix = "B" + date.format(DateTimeFormatter.BASIC_ISO_DATE)
                + (type.equals("full") ? "-F-" : type.equals("supplement") ? "-S-" : "-P-");
        int seq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batches WHERE batch_no LIKE ?", Integer.class, prefix + "%") + 1;
        return prefix + String.format("%02d", seq);
    }

    private Path writeTxt(String batchNo, List<String> members) {
        try {
            Path dir = Path.of(outDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(batchNo + ".txt");
            Files.writeString(file, String.join("\n", members) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("批次 txt 写出失败：" + batchNo, e);
        }
    }

    private void audit(String action, String objectType, String objectId, boolean success, String detail) {
        jdbc.update("""
                INSERT INTO audit_logs (event_id, operator, operator_type, action,
                                        object_type, object_id, params, success, created_at)
                VALUES (?, 'system', 'system', ?, ?, ?, ?, ?, NOW(3))
                """, UUID.randomUUID().toString(), action, objectType, objectId,
                detail.length() > 500 ? detail.substring(0, 500) : detail, success ? 1 : 0);
    }
}
