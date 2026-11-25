package com.collect.platform.video;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {

    private final JdbcTemplate jdbc;
    private final VideoUploadService uploadService;
    private final VideoIngestService ingestService;

    public VideoController(JdbcTemplate jdbc, VideoUploadService uploadService,
                           VideoIngestService ingestService) {
        this.jdbc = jdbc;
        this.uploadService = uploadService;
        this.ingestService = ingestService;
    }

    /**
     * 浏览器直连 CDP 采集到的 aweme 明细入库。
     * 采集人取自登录态（JWT），不信任前端传值。
     */
    @PostMapping("/ingest")
    public Result<Map<String, Object>> ingest(@RequestBody Map<String, Object> body) {
        Object raw = body.get("items");
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object v : list) {
                if (v instanceof Map<?, ?> m && items.size() < 500) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    m.forEach((k, val) -> item.put(String.valueOf(k), val));
                    items.add(item);
                }
            }
        }
        String sourceUrl = body.get("sourceUrl") == null ? null : String.valueOf(body.get("sourceUrl"));
        int inserted = ingestService.save(items, UserContext.get().getCode(), sourceUrl);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("inserted", inserted);
        return Result.ok(out);
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String collector,
                                            @RequestParam(required = false) String terminal,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (Math.max(page, 1) - 1) * safeSize;

        String forced = forcedCollector();
        if (forced != null) {
            collector = forced;
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (title LIKE ? OR author_nickname LIKE ? OR aweme_id LIKE ? OR url LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (collector != null && !collector.isBlank()) {
            where.append(" AND collected_by = ?");
            args.add(collector);
        }
        if (terminal != null && !terminal.isBlank()) {
            where.append(" AND terminal_code = ?");
            args.add(terminal);
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND collected_at >= ?");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND collected_at <= ?");
            args.add(to);
        }

        int total = jdbc.queryForObject("SELECT COUNT(*) FROM videos" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(offset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", safeSize);
        out.put("items", jdbc.queryForList("""
                SELECT id, aweme_id, url, title, cover_url, author_sec_uid, author_nickname,
                       digg_count, comment_count, share_count, collect_count, status,
                       collected_by, terminal_code, collected_at, uploaded_at, upload_note
                FROM videos
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", pageArgs.toArray()));
        return Result.ok(out);
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String collector,
                                             @RequestParam(required = false) String terminal) {
        String forced = forcedCollector();
        if (forced != null) {
            collector = forced;
        }
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null && !from.isBlank()) {
            where.append(" AND collected_at >= ?");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND collected_at <= ?");
            args.add(to);
        }
        if (collector != null && !collector.isBlank()) {
            where.append(" AND collected_by = ?");
            args.add(collector);
        }
        if (terminal != null && !terminal.isBlank()) {
            where.append(" AND terminal_code = ?");
            args.add(terminal);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM videos" + where + " GROUP BY status", args.toArray())) {
            long cnt = ((Number) row.get("cnt")).longValue();
            out.put(String.valueOf(row.get("status")), cnt);
            total += cnt;
        }
        out.put("total", total);
        out.put("collectors", jdbc.queryForList("""
                SELECT collected_by, COUNT(*) AS cnt, MAX(collected_at) AS last_at
                FROM videos""" + where + """
                 AND collected_by IS NOT NULL
                GROUP BY collected_by ORDER BY cnt DESC LIMIT 10
                """, args.toArray()));
        out.put("terminals", jdbc.queryForList("""
                SELECT terminal_code, COUNT(*) AS cnt
                FROM videos""" + where + """
                 AND terminal_code IS NOT NULL
                GROUP BY terminal_code ORDER BY cnt DESC LIMIT 20
                """, args.toArray()));
        StringBuilder scope = new StringBuilder(" WHERE collected_at >= CURDATE()");
        List<Object> scopeArgs = new ArrayList<>();
        if (collector != null && !collector.isBlank()) {
            scope.append(" AND collected_by = ?");
            scopeArgs.add(collector);
        }
        if (terminal != null && !terminal.isBlank()) {
            scope.append(" AND terminal_code = ?");
            scopeArgs.add(terminal);
        }
        out.put("today", jdbc.queryForObject(
                "SELECT COUNT(*) FROM videos" + scope, Integer.class, scopeArgs.toArray()));
        out.put("last_collected_at", jdbc.queryForObject(
                "SELECT MAX(collected_at) FROM videos" + where, Object.class, args.toArray()));
        return Result.ok(out);
    }

    /** 采集员只能看自己的入库明细；管理员 / 上传员看全量。 */
    private static String forcedCollector() {
        UserContext me = UserContext.get();
        if (me == null) {
            return null;
        }
        String role = String.valueOf(me.getRole()).toLowerCase();
        return "admin".equals(role) || "uploader".equals(role) ? null : me.getCode();
    }

    @PostMapping("/upload")
    @Audited("上传视频作者")
    public Result<Map<String, Object>> upload(@RequestBody Map<String, Object> body) {
        boolean all = Boolean.TRUE.equals(body.get("all"))
                || "true".equals(String.valueOf(body.get("all")));
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object v : list) {
                if (v instanceof Number n) {
                    ids.add(n.longValue());
                } else if (v != null) {
                    ids.add(Long.parseLong(String.valueOf(v)));
                }
            }
        }
        Map<String, Object> data = uploadService.upload(ids, all);
        if (Boolean.TRUE.equals(data.get("success"))) {
            return Result.ok(data);
        }
        return new Result<>(false, String.valueOf(data.get("message")), data);
    }

    @GetMapping("/uploads")
    public Result<List<Map<String, Object>>> uploads() {
        return Result.ok(jdbc.queryForList("""
                SELECT id, uploader_id, operator, video_count, sec_uid_count, http_status,
                       remote_count, inserted_count, duplicate_count, success, error,
                       started_at, ended_at, duration_ms
                FROM video_uploads ORDER BY id DESC LIMIT 30
                """));
    }
}
