package com.collect.platform.video;

import com.collect.platform.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频入库与查询的业务门面：列表/统计的过滤条件拼装，SQL 交给 VideoQueryRepository。
 */
@Service
public class VideoService {

    private final VideoQueryRepository repo;
    private final VideoIngestService ingestService;
    private final VideoUploadService uploadService;

    public VideoService(VideoQueryRepository repo, VideoIngestService ingestService,
                        VideoUploadService uploadService) {
        this.repo = repo;
        this.ingestService = ingestService;
        this.uploadService = uploadService;
    }

    /**
     * 浏览器直连 CDP 采集到的 aweme 明细入库。
     * 采集人取自登录态（JWT），不信任前端传值。
     */
    public Map<String, Object> ingest(Map<String, Object> body) {
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
        return out;
    }

    public Map<String, Object> list(String status, String keyword, String collector, String terminal,
                                    String from, String to, int page, int size) {
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

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(offset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", repo.count(where.toString(), args.toArray()));
        out.put("page", page);
        out.put("size", safeSize);
        out.put("items", repo.page(where.toString(), pageArgs.toArray()));
        return out;
    }

    public Map<String, Object> stats(String from, String to, String collector, String terminal) {
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
        for (Map<String, Object> row : repo.countByStatus(where.toString(), args.toArray())) {
            long cnt = ((Number) row.get("cnt")).longValue();
            out.put(String.valueOf(row.get("status")), cnt);
            total += cnt;
        }
        out.put("total", total);
        out.put("collectors", repo.topCollectors(where.toString(), args.toArray()));
        out.put("terminals", repo.topTerminals(where.toString(), args.toArray()));

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
        out.put("today", repo.todayCount(scope.toString(), scopeArgs.toArray()));
        out.put("last_collected_at", repo.lastCollectedAt(where.toString(), args.toArray()));
        return out;
    }

    public Map<String, Object> upload(List<Long> ids, boolean all) {
        return uploadService.upload(ids, all);
    }

    public List<Map<String, Object>> uploads() {
        return repo.recentUploads();
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
}
