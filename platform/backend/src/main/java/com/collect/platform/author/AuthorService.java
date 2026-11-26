package com.collect.platform.author;

import com.collect.platform.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 短剧达人列表与导出。
 */
@Service
public class AuthorService {

    private final AuthorRepository repo;

    public AuthorService(AuthorRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> list(String keyword, String collector, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (Math.max(page, 1) - 1) * safeSize;

        String forced = forcedCollector();
        if (forced != null) {
            collector = forced;
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, keyword, collector);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(offset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", repo.count(where.toString(), args.toArray()));
        out.put("page", page);
        out.put("size", safeSize);
        out.put("items", repo.page(where.toString(), pageArgs.toArray()));
        out.put("summary", repo.summary(where.toString(), args.toArray()));
        return out;
    }

    /** 导出 CSV（含 BOM，方便 Excel 直接打开）。 */
    public byte[] exportCsv(String keyword, String collector) {
        String forced = forcedCollector();
        if (forced != null) {
            collector = forced;
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, keyword, collector);
        List<Map<String, Object>> rows = repo.exportRows(where.toString(), args.toArray());

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
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void appendFilters(StringBuilder where, List<Object> args, String keyword, String collector) {
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
