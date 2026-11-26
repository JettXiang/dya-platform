package com.collect.platform.ingest;

import com.collect.platform.common.BadRequestException;
import com.collect.platform.common.ConflictException;
import com.collect.platform.common.ForbiddenException;
import com.collect.platform.ingest.dto.IngestDtos;
import com.collect.platform.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IngestService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IngestService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> chunk(IngestDtos.ChunkRequest req) {
        long accountId = ownedAccountId(req.getAccountCode());
        LocalDate businessDate = parseBusinessDate(req.getBusinessDate());
        /* 业务日以服务器为准；与服务器当日不一致（±1 天窗口内）标记时钟偏差 */
        int clockSkew = businessDate.equals(LocalDate.now(ZONE)) ? 0 : 1;

        Long runId = jdbc.query(
                "SELECT id FROM runs WHERE account_id = ? AND client_run_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                accountId, req.getClientRunId());
        if (runId != null) {
            // A01：同 run 的业务日不可变；已作废 run 不再接收新分片
            Map<String, Object> run = jdbc.queryForMap("""
                    SELECT DATE_FORMAT(`date`, '%Y-%m-%d') AS d, voided_at
                    FROM runs WHERE id = ?
                    """, runId);
            LocalDate runDate = LocalDate.parse(String.valueOf(run.get("d")));
            if (!runDate.equals(businessDate)) {
                throw new ConflictException("run_business_date_conflict",
                        "同 run 业务日不一致：run=" + req.getClientRunId()
                                + " stored=" + runDate + " request=" + businessDate);
            }
            if (run.get("voided_at") != null) {
                throw new ConflictException("run_already_voided",
                        "该 run 已作废，不再接收分片：" + req.getClientRunId());
            }
        }
        if (runId == null) {
            try {
                jdbc.update("""
                        INSERT INTO runs (account_id, client_run_id, `date`, keyword, started_at_client,
                                          total_count_client, received_count, contiguous_seq, clock_skew_flag)
                        VALUES (?,?,?,?,?,?,0,0,?)
                        """,
                        accountId, req.getClientRunId(), businessDate, req.getKeyword(),
                        parseTime(req.getStartedAtClient()), req.getTotalCount(), clockSkew);
            } catch (DataIntegrityViolationException race) {
                /* 并发建 run：另一个请求已插入，回读即可 */
            }
            runId = jdbc.queryForObject(
                    "SELECT id FROM runs WHERE account_id = ? AND client_run_id = ? FOR UPDATE",
                    Long.class, accountId, req.getClientRunId());
        }

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT payload_hash, ack_token FROM run_chunks WHERE run_id = ? AND chunk_seq = ?",
                runId, req.getChunkSeq());
        if (!existing.isEmpty()) {
            String hash = String.valueOf(existing.get(0).get("payload_hash"));
            if (!hash.equalsIgnoreCase(req.getPayloadHash())) {
                throw new ConflictException("chunk_payload_conflict",
                        "同分片键不同载荷：run=" + req.getClientRunId() + " seq=" + req.getChunkSeq());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ack_token", String.valueOf(existing.get(0).get("ack_token")));
            out.put("accepted", true);
            out.put("duplicated", true);
            return out;
        }

        String ackToken = ackToken();
        int itemCount = req.getItems() == null ? 0 : req.getItems().size();
        try {
            jdbc.update("""
                    INSERT INTO run_chunks (run_id, chunk_seq, payload_hash, item_count, ack_token, is_final)
                    VALUES (?,?,?,?,?,?)
                    """,
                    runId, req.getChunkSeq(), req.getPayloadHash(), itemCount, ackToken,
                    req.isFinalChunk() ? 1 : 0);
        } catch (DataIntegrityViolationException race) {
            /* 并发同分片：另一个请求先插入，按同键同 hash 返回原 ACK */
            List<Map<String, Object>> again = jdbc.queryForList(
                    "SELECT payload_hash, ack_token FROM run_chunks WHERE run_id = ? AND chunk_seq = ? FOR UPDATE",
                    runId, req.getChunkSeq());
            if (!again.isEmpty()) {
                String hash = String.valueOf(again.get(0).get("payload_hash"));
                if (!hash.equalsIgnoreCase(req.getPayloadHash())) {
                    throw new ConflictException("chunk_payload_conflict",
                            "同分片键不同载荷：run=" + req.getClientRunId() + " seq=" + req.getChunkSeq());
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ack_token", String.valueOf(again.get(0).get("ack_token")));
                out.put("accepted", true);
                out.put("duplicated", true);
                return out;
            }
            throw race;
        }

        /* 故障矩阵 2：乱序分片也要推进 contiguous_seq，从 1 开始连续扫描实际已收分片 */
        int contiguous = 0;
        List<Integer> seqs = jdbc.queryForList(
                "SELECT chunk_seq FROM run_chunks WHERE run_id = ? ORDER BY chunk_seq",
                Integer.class, runId);
        for (Integer s : seqs) {
            if (s != null && s == contiguous + 1) {
                contiguous = s;
            } else if (s != null && s > contiguous + 1) {
                break;
            }
        }
        jdbc.update("""
                UPDATE runs SET received_count = received_count + ?, contiguous_seq = ?,
                       final_received_at = CASE WHEN ? = 1 THEN NOW(3) ELSE final_received_at END,
                       final_seq = CASE WHEN ? = 1 THEN ? ELSE final_seq END,
                       total_count_client = CASE WHEN ? > 0 THEN ? ELSE total_count_client END
                WHERE id = ?
                """,
                itemCount, contiguous,
                req.isFinalChunk() ? 1 : 0,
                req.isFinalChunk() ? 1 : 0, req.getChunkSeq(), req.getTotalCount(),
                req.getTotalCount(), runId);

        if (req.getItems() != null) {
            for (IngestDtos.Item item : req.getItems()) {
                jdbc.update("""
                        INSERT INTO authors (sec_uid, has_mix, has_ai, first_collected_at, first_seen_run_id,
                                             first_seen_clock_skew, last_collected_at)
                        VALUES (?,?,?,?,?,?,?)
                        ON DUPLICATE KEY UPDATE has_mix = VALUES(has_mix), has_ai = VALUES(has_ai),
                                                last_collected_at = VALUES(last_collected_at)
                        """,
                        item.getSecUid(), item.isHasMix() ? 1 : 0, item.isHasAi() ? 1 : 0,
                        LocalDateTime.now(ZONE), runId, clockSkew, LocalDateTime.now(ZONE));
                jdbc.update("""
                        INSERT IGNORE INTO authors_source (run_id, sec_uid, account_id, collected_at_client, received_at_server)
                        VALUES (?,?,?,?,NOW(3))
                        """,
                        runId, item.getSecUid(), accountId, LocalDateTime.now(ZONE));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ack_token", ackToken);
        out.put("accepted", true);
        out.put("duplicated", false);
        return out;
    }

    @Transactional
    public Map<String, Object> dayFinal(IngestDtos.DayFinalRequest req) {
        long accountId = ownedAccountId(req.getAccountCode());
        LocalDate date = parseBusinessDate(req.getDate());

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT content_hash, revision, server_result, complete FROM day_finals WHERE account_id = ? AND `date` = ?",
                accountId, date);

        int revision;
        if (existing.isEmpty()) {
            revision = Math.max(req.getRevision(), 1);
            jdbc.update("""
                    INSERT INTO day_finals (account_id, `date`, expected_runs, expected_run_ids, client_pending_chunks,
                                            content_hash, revision, final_received_at)
                    VALUES (?,?,?,?,?,?,?,NOW(3))
                    """,
                    accountId, date, req.getExpectedRuns(),
                    expectedRunsJson(req.getExpectedRunIds()),
                    req.getClientPendingChunks(), req.getContentHash(), revision);
        } else {
            Map<String, Object> row = existing.get(0);
            String hash = String.valueOf(row.get("content_hash"));
            int currentRevision = ((Number) row.get("revision")).intValue();
            if (hash.equalsIgnoreCase(req.getContentHash())) {
                return evaluate(accountId, date, req, currentRevision);
            }
            if (req.getRevision() <= currentRevision) {
                throw new ConflictException("day_final_conflict",
                        "day_final 内容冲突，current_revision=" + currentRevision);
            }
            revision = req.getRevision();
            jdbc.update("""
                    UPDATE day_finals SET content_hash = ?, revision = ?, expected_runs = ?, expected_run_ids = ?,
                           client_pending_chunks = ?, final_received_at = NOW(3)
                    WHERE account_id = ? AND `date` = ?
                    """,
                    req.getContentHash(), revision, req.getExpectedRuns(),
                    expectedRunsJson(req.getExpectedRunIds()),
                    req.getClientPendingChunks(), accountId, date);
        }
        return evaluate(accountId, date, req, revision);
    }

    @Transactional
    public Map<String, Object> runVoid(IngestDtos.RunVoidRequest req) {
        long accountId = ownedAccountId(req.getAccountCode());
        LocalDate date = parseBusinessDate(req.getBusinessDate());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, DATE_FORMAT(`date`, '%Y-%m-%d') AS d, final_received_at, voided_at,
                       void_request_id, void_request_hash
                FROM runs WHERE account_id = ? AND client_run_id = ?
                """, accountId, req.getClientRunId());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("run 不存在：" + req.getClientRunId());
        }
        Map<String, Object> row = rows.get(0);
        LocalDate runDate = LocalDate.parse(String.valueOf(row.get("d")));
        if (!runDate.equals(date)) {
            throw new ConflictException("run_business_date_conflict",
                    "run 业务日不一致：stored=" + runDate + " request=" + date);
        }
        String reason = req.getReason().trim();
        String requestHash = sha256(accountId + "|" + req.getClientRunId() + "|" + date + "|" + reason);
        if (row.get("voided_at") != null) {
            String storedId = String.valueOf(row.get("void_request_id"));
            String storedHash = String.valueOf(row.get("void_request_hash"));
            if (req.getClientRequestId().equals(storedId) && requestHash.equals(storedHash)) {
                return Map.of("voided", true, "duplicated", true, "runId", req.getClientRunId());
            }
            throw new ConflictException("void_request_conflict",
                    "该 run 已用其它请求作废，不能重复使用同一幂等键改参数");
        }
        if (row.get("final_received_at") != null) {
            throw new ConflictException("run_already_final", "run 已终片，不允许作废");
        }
        jdbc.update("""
                UPDATE runs SET voided_at = NOW(3), void_reason = ?, void_request_id = ?, void_request_hash = ?
                WHERE id = ?
                """, reason.length() > 255 ? reason.substring(0, 255) : reason,
                req.getClientRequestId(), requestHash, ((Number) row.get("id")).longValue());
        return Map.of("voided", true, "duplicated", false, "runId", req.getClientRunId());
    }

    @Transactional
    public void heartbeat(IngestDtos.HeartbeatRequest req) {
        long accountId = ownedAccountId(req.getAccountCode());
        jdbc.update(
                "UPDATE accounts SET last_heartbeat_at = NOW(3), state = COALESCE(?, state) WHERE id = ?",
                req.getState(), accountId);
    }

    private Map<String, Object> evaluate(long accountId, LocalDate date,
                                         IngestDtos.DayFinalRequest req, int revision) {
        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT client_run_id, final_received_at, received_count, total_count_client,
                       contiguous_seq, final_seq, voided_at
                FROM runs WHERE account_id = ? AND `date` = ?
                """, accountId, date);

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> run : runs) {
            byId.put(String.valueOf(run.get("client_run_id")), run);
        }

        List<String> expected = req.getExpectedRunIds() == null ? List.of() : req.getExpectedRunIds();
        List<String> missing = new ArrayList<>();
        List<String> voided = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        int matched = 0;
        int expectedVoided = 0;

        for (Map<String, Object> run : runs) {
            String id = String.valueOf(run.get("client_run_id"));
            if (run.get("voided_at") != null) {
                voided.add(id);
            }
            // 额外 run：不在 expected、未 final、未作废 → 阻止当日 complete
            if (!expected.contains(id) && run.get("final_received_at") == null && run.get("voided_at") == null) {
                unexpected.add(id);
            }
        }

        for (String id : expected) {
            Map<String, Object> run = byId.get(id);
            if (run == null) {
                missing.add(id);
                continue;
            }
            if (run.get("voided_at") != null) {
                expectedVoided++;
                continue; // 已作废的 expected run 不参与 complete 判定
            }
            if (!runComplete(run)) {
                missing.add(id);
            } else {
                matched++;
            }
        }

        boolean complete = req.getClientPendingChunks() == 0
                && missing.isEmpty()
                && unexpected.isEmpty()
                && matched == expected.size() - expectedVoided;
        String result = complete ? "accepted" : "incomplete";

        String voidedJson = json(voided);
        String unexpectedJson = json(unexpected);
        jdbc.update("""
                UPDATE day_finals SET server_result = ?, complete = ?, evaluated_at = NOW(3),
                       voided_runs = CAST(? AS JSON), unexpected_runs = CAST(? AS JSON)
                WHERE account_id = ? AND `date` = ?
                """, result, complete ? 1 : 0, voidedJson, unexpectedJson, accountId, date);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        out.put("complete", complete);
        out.put("missing_runs", missing);
        out.put("unexpected_runs", unexpected);
        out.put("voided_runs", voided);
        out.put("revision", revision);
        return out;
    }

    private boolean runComplete(Map<String, Object> run) {
        return run.get("final_received_at") != null
                && ((Number) run.get("received_count")).intValue()
                == ((Number) run.get("total_count_client")).intValue()
                && run.get("final_seq") != null
                && ((Number) run.get("contiguous_seq")).intValue()
                == ((Number) run.get("final_seq")).intValue();
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String expectedRunsJson(java.util.List<String> ids) {
        if (ids == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalArgumentException("expected_run_ids 不是合法列表");
        }
    }

    /** A01：业务日只接受 Asia/Shanghai 当天及前后各 1 天，超出返回明确错误 */
    private LocalDate parseBusinessDate(String value) {
        try {
            LocalDate date = LocalDate.parse(value, DateTimeFormatter.ISO_DATE);
            LocalDate today = LocalDate.now(ZONE);
            if (date.isBefore(today.minusDays(1)) || date.isAfter(today.plusDays(1))) {
                throw new BadRequestException("business_date_out_of_range",
                        "业务日超出允许窗口：" + date + "（服务端 " + today + "）");
            }
            return date;
        } catch (DateTimeParseException e) {
            throw new BadRequestException("invalid_business_date", "业务日格式不合法：" + value);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private long accountId(String code) {
        Long id = jdbc.query(
                "SELECT id FROM accounts WHERE code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, code);
        if (id == null) {
            throw new IllegalArgumentException("账号不存在：" + code);
        }
        return id;
    }

    /** 采集类接口只允许本人（或管理员）上报本人名下账号，防止跨账号代报 */
    private long ownedAccountId(String code) {
        var ctx = UserContext.get();
        if (ctx == null) {
            throw new ForbiddenException("unauthenticated", "缺少登录身份");
        }
        long id = accountId(code);
        if ("admin".equals(ctx.getRole())) {
            return id;
        }
        Integer owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE id = ? AND user_id = ?",
                Integer.class, id, ctx.getUserId());
        if (owned == null || owned == 0) {
            throw new ForbiddenException("account_not_owned", "该账号不属于当前登录人：" + code);
        }
        return id;
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String ackToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
