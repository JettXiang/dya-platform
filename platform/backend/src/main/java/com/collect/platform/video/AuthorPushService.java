package com.collect.platform.video;

import com.collect.platform.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 中心端「汇总上传」：按作者 sec_uid 汇总，推给上游旧通道。
 * 与 {@link VideoUploadService} 的区别是去重口径——这里按作者永久台账，推过的作者不再出现在待推列表。
 */
@Service
public class AuthorPushService {

    private static final Logger log = LoggerFactory.getLogger(AuthorPushService.class);
    private static final String ENDPOINT = "http://43.165.183.142/api/file";
    private static final int MAX_LINES = 10000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public AuthorPushService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 待推作者：时间窗内采集到的作者，排除台账里已成功推过的 */
    public List<Map<String, Object>> pending(String from, String to, String collector, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT v.author_sec_uid AS secUid,
                       MAX(v.author_nickname) AS nickname,
                       COUNT(*) AS videoCount,
                       MIN(v.collected_at) AS firstCollectedAt,
                       MAX(v.collected_at) AS lastCollectedAt,
                       GROUP_CONCAT(DISTINCT v.collected_by ORDER BY v.collected_by SEPARATOR ',') AS collectors
                FROM videos v
                WHERE v.collected_at >= ? AND v.collected_at < ?
                  AND v.author_sec_uid IS NOT NULL AND v.author_sec_uid <> ''
                  AND NOT EXISTS (
                        SELECT 1 FROM author_uploads u
                        WHERE u.author_sec_uid = v.author_sec_uid AND u.success = 1)
                """);
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        if (collector != null && !collector.isBlank()) {
            sql.append(" AND v.collected_by = ?");
            args.add(collector.trim());
        }
        sql.append(" GROUP BY v.author_sec_uid ORDER BY lastCollectedAt DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 5000));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 待推统计：作者数 / 视频数 / 台账已推作者总数 */
    public Map<String, Object> stats(String from, String to, String collector) {
        long authors = 0;
        long videos = 0;
        StringBuilder where = new StringBuilder(" WHERE v.collected_at >= ? AND v.collected_at < ?"
                + " AND v.author_sec_uid IS NOT NULL AND v.author_sec_uid <> ''"
                + " AND NOT EXISTS (SELECT 1 FROM author_uploads u"
                + " WHERE u.author_sec_uid = v.author_sec_uid AND u.success = 1)");
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        if (collector != null && !collector.isBlank()) {
            where.append(" AND v.collected_by = ?");
            args.add(collector.trim());
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(DISTINCT v.author_sec_uid) AS a, COUNT(*) AS v FROM videos v" + where, args.toArray());
        authors = ((Number) row.get("a")).longValue();
        videos = ((Number) row.get("v")).longValue();

        Long pushed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM author_uploads WHERE success = 1", Long.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingAuthors", authors);
        out.put("pendingVideos", videos);
        out.put("pushedAuthors", pushed == null ? 0L : pushed);
        return out;
    }

    public Map<String, Object> push(List<String> secUids) {
        var ctx = UserContext.get();
        String uploaderId = ctx.getCode();

        Set<String> wanted = new LinkedHashSet<>();
        if (secUids != null) {
            for (String s : secUids) {
                if (s != null && !s.isBlank()) {
                    wanted.add(s.trim());
                }
            }
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("请先勾选要推送的作者");
        }

        List<String> todo = filterNotPushed(wanted);
        if (todo.isEmpty()) {
            return result(false, "所选作者都已推送过，没有新增", wanted.size(), 0, 0, null, null, null, null, 0L);
        }
        if (todo.size() > MAX_LINES) {
            throw new IllegalArgumentException("本次 " + todo.size() + " 条，超过单次上限 " + MAX_LINES + " 条");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        String boundary = "----dya" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = VideoUploadService.multipart(boundary, uploaderId, new LinkedHashSet<>(todo));
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        Integer httpStatus = null;
        String raw = null;
        String error = null;
        Integer count = null, inserted = null, duplicate = null;
        boolean success = false;
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            httpStatus = resp.statusCode();
            raw = resp.body();
            if (httpStatus / 100 == 2) {
                JsonNode node = objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
                JsonNode rec = node.path("record");
                count = rec.path("count").isMissingNode() ? todo.size() : rec.path("count").asInt();
                inserted = rec.path("inserted_count").asInt();
                duplicate = rec.path("remote_duplicate_count").asInt();
                success = true;
            } else {
                error = "HTTP " + httpStatus + " " + shorten(raw, 400);
            }
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + shorten(String.valueOf(ex.getMessage()), 300);
            log.warn("作者汇总推送失败 uploader={} count={} err={}", uploaderId, todo.size(), error);
        }
        long cost = System.currentTimeMillis() - t0;

        int videoCount = 0;
        String note = success
                ? "汇总推送 " + todo.size() + " 个作者"
                : shorten(error, 480);
        if (success) {
            videoCount = markUploaded(todo, note);
        } else {
            videoCount = markFailed(todo, note);
        }
        writeLedger(todo, success, uploaderId, httpStatus, inserted, duplicate, note);
        logUpload(uploaderId, ctx.getCode(), videoCount, todo.size(), httpStatus, count, inserted, duplicate,
                success, error, raw, startedAt, cost);

        Map<String, Object> out = result(success, success ? "推送成功" : "推送失败",
                wanted.size(), todo.size(), videoCount, httpStatus, count, inserted, duplicate, cost);
        out.put("error", error);
        return out;
    }

    public List<Map<String, Object>> history(int limit) {
        return jdbc.queryForList("""
                SELECT author_sec_uid AS secUid, success, push_count AS pushCount,
                       first_pushed_at AS firstPushedAt, last_pushed_at AS lastPushedAt,
                       uploader_id AS uploaderId, http_status AS httpStatus,
                       remote_inserted AS remoteInserted, remote_duplicate AS remoteDuplicate,
                       last_result AS lastResult
                FROM author_uploads ORDER BY last_pushed_at DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 200));
    }

    /** 分块查台账，剔掉已成功推过的作者（前端列表可能已经过期） */
    private List<String> filterNotPushed(Set<String> wanted) {
        Set<String> done = new LinkedHashSet<>();
        List<String> all = new ArrayList<>(wanted);
        int chunk = 500;
        for (int i = 0; i < all.size(); i += chunk) {
            List<String> part = all.subList(i, Math.min(i + chunk, all.size()));
            String marks = String.join(",", Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>(part);
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT author_sec_uid FROM author_uploads WHERE success = 1 AND author_sec_uid IN (" + marks + ")",
                    args.toArray())) {
                done.add(String.valueOf(row.get("author_sec_uid")));
            }
        }
        List<String> todo = new ArrayList<>();
        for (String s : all) {
            if (!done.contains(s)) {
                todo.add(s);
            }
        }
        return todo;
    }

    private int markUploaded(List<String> secUids, String note) {
        int total = 0;
        for (int i = 0; i < secUids.size(); i += 500) {
            List<String> part = secUids.subList(i, Math.min(i + 500, secUids.size()));
            String marks = String.join(",", Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(shorten(note, 480));
            args.addAll(part);
            total += jdbc.update("UPDATE videos SET status = 'uploaded', uploaded_at = NOW(3), upload_note = ?"
                    + " WHERE author_sec_uid IN (" + marks + ") AND status <> 'uploaded'", args.toArray());
        }
        return total;
    }

    private int markFailed(List<String> secUids, String note) {
        int total = 0;
        for (int i = 0; i < secUids.size(); i += 500) {
            List<String> part = secUids.subList(i, Math.min(i + 500, secUids.size()));
            String marks = String.join(",", Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(shorten(note, 480));
            args.addAll(part);
            total += jdbc.update("UPDATE videos SET status = 'failed', upload_note = ?"
                    + " WHERE author_sec_uid IN (" + marks + ") AND status = 'pending'", args.toArray());
        }
        return total;
    }

    private void writeLedger(List<String> secUids, boolean success, String uploaderId, Integer httpStatus,
                             Integer inserted, Integer duplicate, String note) {
        for (int i = 0; i < secUids.size(); i += 500) {
            List<String> part = secUids.subList(i, Math.min(i + 500, secUids.size()));
            StringBuilder values = new StringBuilder();
            List<Object> args = new ArrayList<>();
            for (String sec : part) {
                if (values.length() > 0) {
                    values.append(",");
                }
                values.append("(?,?,1,NOW(3),NOW(3),?,?,?,?,?)");
                args.add(sec);
                args.add(success ? 1 : 0);
                args.add(uploaderId);
                args.add(httpStatus);
                args.add(inserted);
                args.add(duplicate);
                args.add(shorten(note, 500));
            }
            try {
                jdbc.update("INSERT INTO author_uploads"
                        + " (author_sec_uid, success, push_count, first_pushed_at, last_pushed_at,"
                        + "  uploader_id, http_status, remote_inserted, remote_duplicate, last_result)"
                        + " VALUES " + values
                        + " ON DUPLICATE KEY UPDATE"
                        + "  success = VALUES(success),"
                        + "  push_count = push_count + 1,"
                        + "  last_pushed_at = NOW(3),"
                        + "  uploader_id = VALUES(uploader_id),"
                        + "  http_status = VALUES(http_status),"
                        + "  remote_inserted = VALUES(remote_inserted),"
                        + "  remote_duplicate = VALUES(remote_duplicate),"
                        + "  last_result = VALUES(last_result)", args.toArray());
            } catch (Exception ex) {
                log.warn("写作者推送台账失败: {}", ex.getMessage());
            }
        }
    }

    private void logUpload(String uploaderId, String operator, int videoCount, int secUidCount,
                           Integer httpStatus, Integer count, Integer inserted, Integer duplicate,
                           boolean success, String error, String raw, LocalDateTime startedAt, long cost) {
        try {
            jdbc.update("""
                    INSERT INTO video_uploads
                      (uploader_id, operator, video_count, sec_uid_count, http_status, remote_count,
                       inserted_count, duplicate_count, success, error, raw_response, started_at, ended_at, duration_ms)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW(3),?)
                    """, uploaderId, operator, videoCount, secUidCount, httpStatus, count,
                    inserted, duplicate, success ? 1 : 0, shorten(error, 1000), shorten(raw, 200000),
                    startedAt, (int) cost);
        } catch (Exception ex) {
            log.warn("写上传日志失败: {}", ex.getMessage());
        }
    }

    private Map<String, Object> result(boolean ok, String message, int requested, int pushed, int videos,
                                       Integer httpStatus, Integer remoteCount, Integer inserted,
                                       Integer duplicate, long costMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", ok);
        out.put("message", message);
        out.put("requestedCount", requested);
        out.put("pushedCount", pushed);
        out.put("videoCount", videos);
        out.put("httpStatus", httpStatus);
        out.put("remoteCount", remoteCount);
        out.put("insertedCount", inserted);
        out.put("duplicateCount", duplicate);
        out.put("durationMs", costMs);
        return out;
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
