package com.collect.platform.video;

import com.collect.platform.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 视频 -> 上游旧通道上传（POST http://43.165.183.142/api/file，multipart）。
 * 文件内容是去重后的作者 sec_uid 列表，每行一个。
 */
@Service
public class VideoUploadService {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadService.class);
    private static final String ENDPOINT = "http://43.165.183.142/api/file";
    private static final int MAX_LINES = 10000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public VideoUploadService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> upload(List<Long> ids, boolean all) {
        var ctx = UserContext.get();
        String uploaderId = ctx.getCode();
        List<Map<String, Object>> rows;
        if (all) {
            rows = jdbc.queryForList("""
                    SELECT id, author_sec_uid FROM videos
                    WHERE status <> 'uploaded' AND author_sec_uid IS NOT NULL
                    ORDER BY id
                    """);
        } else {
            if (ids == null || ids.isEmpty()) {
                throw new IllegalArgumentException("请先勾选视频");
            }
            String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            rows = jdbc.queryForList(
                    "SELECT id, author_sec_uid FROM videos WHERE id IN (" + marks + ") ORDER BY id",
                    ids.toArray());
        }
        if (rows.isEmpty()) {
            return result(false, "没有可上传的视频", 0, 0, null, null, null, null, 0);
        }

        List<Long> videoIds = new ArrayList<>(rows.size());
        Set<String> secUids = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            videoIds.add(((Number) row.get("id")).longValue());
            Object sec = row.get("author_sec_uid");
            if (sec != null && !String.valueOf(sec).isBlank()) {
                secUids.add(String.valueOf(sec));
            }
        }
        if (secUids.isEmpty()) {
            return result(false, "所选视频没有作者 sec_uid，无法上传", videoIds.size(), 0, null, null, null, null, 0);
        }
        if (secUids.size() > MAX_LINES) {
            throw new IllegalArgumentException("本次 " + secUids.size() + " 条，超过单次上限 " + MAX_LINES + " 条");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        String boundary = "----dya" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, uploaderId, secUids);
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
                JsonNode node = objectMapper.readTree(raw == null ? "{}" : raw);
                JsonNode rec = node.path("record");
                count = rec.path("count").isMissingNode() ? secUids.size() : rec.path("count").asInt();
                inserted = rec.path("inserted_count").asInt();
                duplicate = rec.path("remote_duplicate_count").asInt();
                success = true;
            } else {
                error = "HTTP " + httpStatus + " " + shorten(raw, 400);
            }
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + shorten(String.valueOf(ex.getMessage()), 300);
            log.warn("视频上传失败 uploader={} secUids={} err={}", uploaderId, secUids.size(), error);
        }
        long cost = System.currentTimeMillis() - t0;

        if (success) {
            String marks = String.join(",", java.util.Collections.nCopies(videoIds.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add("已上传 " + secUids.size() + " 个作者 / " + videoIds.size() + " 个视频");
            args.addAll(videoIds);
            jdbc.update("UPDATE videos SET status = 'uploaded', uploaded_at = NOW(3), upload_note = ? WHERE id IN (" + marks + ")",
                    args.toArray());
        } else {
            String marks = String.join(",", java.util.Collections.nCopies(videoIds.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(shorten(error, 480));
            args.addAll(videoIds);
            jdbc.update("UPDATE videos SET status = 'failed', upload_note = ? WHERE id IN (" + marks + ")",
                    args.toArray());
        }

        Long logId = null;
        try {
            jdbc.update("""
                    INSERT INTO video_uploads
                      (uploader_id, operator, video_count, sec_uid_count, http_status, remote_count,
                       inserted_count, duplicate_count, success, error, raw_response, started_at, ended_at, duration_ms)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW(3),?)
                    """, uploaderId, ctx.getCode(), videoIds.size(), secUids.size(), httpStatus, count,
                    inserted, duplicate, success ? 1 : 0, shorten(error, 1000), shorten(raw, 200000),
                    startedAt, (int) cost);
            logId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (Exception ex) {
            log.warn("写上传日志失败: {}", ex.getMessage());
        }

        Map<String, Object> out = result(success, success ? "上传成功" : "上传失败",
                videoIds.size(), secUids.size(), httpStatus, count, inserted, duplicate, cost);
        out.put("success", success);
        out.put("error", error);
        out.put("logId", logId);
        out.put("uploaderId", uploaderId);
        return out;
    }

    private Map<String, Object> result(boolean ok, String message, int videos, int secUids,
                                       Integer httpStatus, Integer count, Integer inserted,
                                       Integer duplicate, long costMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", ok);
        out.put("message", message);
        out.put("videoCount", videos);
        out.put("secUidCount", secUids);
        out.put("httpStatus", httpStatus);
        out.put("remoteCount", count);
        out.put("insertedCount", inserted);
        out.put("duplicateCount", duplicate);
        out.put("durationMs", costMs);
        return out;
    }

    static byte[] multipart(String boundary, String uploaderId, Set<String> secUids) {
        String text = String.join("\n", secUids);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() + 512);
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"uploader_id\"\r\n\r\n"
                    + uploaderId + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"douyin-ai-mix-authors.txt\"\r\n"
                    + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
