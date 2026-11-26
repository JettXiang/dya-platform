package com.collect.platform.video;

import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 短剧达人：只看「合集 + AI 声明」双命中的作品聚合出来的创作者。
 * 首见归因取 authors.first_seen_collector / first_seen_terminal。
 */
@RestController
@RequestMapping("/api/v1/authors")
public class AuthorDramaController {

    private static final String BASE_SQL = """
            SELECT a.sec_uid, a.nickname, a.unique_id, a.avatar_url,
                   a.first_collected_at, a.first_seen_collector, a.first_seen_terminal,
                   a.video_count, a.mix_video_count, a.last_video_at,
                   s.works, s.mixes, s.digg_total
            FROM authors a
            JOIN (
              SELECT author_sec_uid, COUNT(*) AS works, COUNT(DISTINCT mix_id) AS mixes,
                     COALESCE(SUM(digg_count), 0) AS digg_total
              FROM videos
              WHERE has_ai = 1 AND author_sec_uid IS NOT NULL
              GROUP BY author_sec_uid
            ) s ON s.author_sec_uid = a.sec_uid
            WHERE 1=1
            """;

    private final JdbcTemplate jdbc;

    public AuthorDramaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/ai-drama")
    public Result<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String collector,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (Math.max(page, 1) - 1) * safeSize;

        String forced = forcedCollector();
        if (forced != null) {
            collector = forced;
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (a.nickname LIKE ? OR a.unique_id LIKE ? OR a.sec_uid LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (collector != null && !collector.isBlank()) {
            where.append(" AND a.first_seen_collector = ?");
            args.add(collector);
        }

        int total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + BASE_SQL + where + ") t",
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(offset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", safeSize);
        out.put("items", jdbc.queryForList(BASE_SQL + where
                + " ORDER BY s.mixes DESC, s.works DESC, a.last_video_at DESC LIMIT ? OFFSET ?",
                pageArgs.toArray()));
        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT COUNT(*) AS authors,
                       COALESCE(SUM(s.works), 0) AS works,
                       COALESCE(SUM(s.mixes), 0) AS mixes
                FROM authors a
                JOIN (
                  SELECT author_sec_uid, COUNT(*) AS works, COUNT(DISTINCT mix_id) AS mixes
                  FROM videos
                  WHERE has_ai = 1 AND author_sec_uid IS NOT NULL
                  GROUP BY author_sec_uid
                ) s ON s.author_sec_uid = a.sec_uid
                WHERE 1=1
                """ + where, args.toArray());
        out.put("summary", summary);
        return Result.ok(out);
    }

    @GetMapping("/ai-drama/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String collector) {
        String forced = forcedCollector();
        if (forced != null) {
            collector = forced;
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (a.nickname LIKE ? OR a.unique_id LIKE ? OR a.sec_uid LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (collector != null && !collector.isBlank()) {
            where.append(" AND a.first_seen_collector = ?");
            args.add(collector);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(BASE_SQL + where
                + " ORDER BY s.mixes DESC, s.works DESC, a.last_video_at DESC LIMIT 5000", args.toArray());

        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("达人昵称,抖音号,sec_uid,作品数,合集数,总点赞,首见人,首见终端,首见时间,最近采集\n");
        for (Map<String, Object> r : rows) {
            csv.append(cell(r.get("nickname"))).append(',')
                    .append(cell(r.get("unique_id"))).append(',')
                    .append(cell(r.get("sec_uid"))).append(',')
                    .append(cell(r.get("works"))).append(',')
                    .append(cell(r.get("mixes"))).append(',')
                    .append(cell(r.get("digg_total"))).append(',')
                    .append(cell(r.get("first_seen_collector"))).append(',')
                    .append(cell(r.get("first_seen_terminal"))).append(',')
                    .append(cell(r.get("first_collected_at"))).append(',')
                    .append(cell(r.get("last_video_at"))).append('\n');
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-drama-authors.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bytes);
    }

    private static String cell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String forcedCollector() {
        UserContext me = UserContext.get();
        if (me == null) {
            return null;
        }
        String role = String.valueOf(me.getRole()).toLowerCase();
        return "admin".equals(role) || "uploader".equals(role) ? null : me.getCode();
    }
}
