package com.collect.platform.search;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.ConflictException;
import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索采集词库：
 *  - 每次搜索自动记词（hit_count / 首次、最近使用时间）
 *  - 每 10 分钟按人独立随机发一批可用词，供采集端直接点选
 *  - 每周按词汇总采集量与衍生建议，管理员可查可导出
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchKeywordController {

    /** 词库随机批的有效期：5 分钟，每人独立计算 */
    private static final long PICK_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_KEYWORD_LEN = 64;

    private final JdbcTemplate jdbc;
    private final Map<Long, Pick> picks = new ConcurrentHashMap<>();

    public SearchKeywordController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Pick(List<Map<String, Object>> items, long refreshAt) {}

    /* ================= 自动记词 ================= */

    @PostMapping("/keywords")
    public Result<Map<String, Object>> recordKeyword(@RequestBody Map<String, Object> body) {
        String keyword = text(body.get("keyword"));
        if (keyword == null || keyword.length() > MAX_KEYWORD_LEN) {
            throw new IllegalArgumentException("关键词必填且不超过 64 个字符");
        }
        UserContext me = UserContext.get();
        String code = me == null ? null : me.getCode();
        jdbc.update("""
                INSERT INTO search_keywords (keyword, source, created_by, hit_count, first_used_at, last_used_at)
                VALUES (?, 'auto', ?, 1, NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE
                  hit_count = hit_count + 1,
                  last_used_at = NOW(3),
                  created_by = COALESCE(created_by, VALUES(created_by))
                """, keyword, code);
        return Result.ok(Map.of("keyword", keyword));
    }

    /* ================= 随机词库（每 5 分钟一批，带近 7 天产出量） ================= */

    @GetMapping("/keywords/random")
    public Result<Map<String, Object>> randomKeywords(@RequestParam(required = false, defaultValue = "10") int n) {
        UserContext me = UserContext.get();
        long uid = me == null || me.getUserId() == null ? 0L : me.getUserId();
        int size = Math.min(Math.max(n, 1), 60);
        long now = System.currentTimeMillis();
        Pick pick = picks.get(uid);
        if (pick == null || pick.refreshAt() <= now || pick.items().size() != size) {
            List<Map<String, Object>> items = jdbc.queryForList("""
                    SELECT k.keyword,
                           COALESCE(v.cnt, 0) AS produced
                    FROM (SELECT keyword FROM search_keywords WHERE enabled = 1 ORDER BY RAND() LIMIT ?) k
                    LEFT JOIN (
                        SELECT search_keyword, COUNT(DISTINCT aweme_id) AS cnt
                        FROM videos
                        WHERE search_keyword IS NOT NULL
                          AND collected_at >= DATE_SUB(NOW(3), INTERVAL 7 DAY)
                        GROUP BY search_keyword
                    ) v ON v.search_keyword = k.keyword
                    """, size);
            pick = new Pick(items, now + PICK_TTL_MS);
            picks.put(uid, pick);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", pick.items());
        out.put("refreshAt", pick.refreshAt());
        out.put("serverNow", now);
        return Result.ok(out);
    }

    /* ================= 词库维护（管理员） ================= */

    @GetMapping("/keywords")
    public Result<List<Map<String, Object>>> listKeywords() {
        return Result.ok(jdbc.queryForList("""
                SELECT k.id, k.keyword, k.source, k.created_by, k.enabled, k.hit_count,
                       k.first_used_at, k.last_used_at,
                       COALESCE(v.collected_total, 0) AS collected_total,
                       v.last_collected_at
                FROM search_keywords k
                LEFT JOIN (
                    SELECT search_keyword, COUNT(*) AS collected_total, MAX(collected_at) AS last_collected_at
                    FROM videos WHERE search_keyword IS NOT NULL
                    GROUP BY search_keyword
                ) v ON v.search_keyword = k.keyword
                ORDER BY k.last_used_at DESC, k.id DESC
                LIMIT 500
                """));
    }

    @PostMapping("/keywords/manual")
    @Audited("新增采集词")
    public Result<Map<String, Object>> addKeyword(@RequestBody Map<String, Object> body) {
        String keyword = text(body.get("keyword"));
        if (keyword == null || keyword.length() > MAX_KEYWORD_LEN) {
            throw new IllegalArgumentException("关键词必填且不超过 64 个字符");
        }
        UserContext me = UserContext.get();
        try {
            jdbc.update("""
                    INSERT INTO search_keywords (keyword, source, created_by, hit_count, enabled, created_at)
                    VALUES (?, 'manual', ?, 0, 1, NOW(3))
                    """, keyword, me == null ? null : me.getCode());
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("keyword_exists", "词库里已有：" + keyword);
        }
        return Result.ok(Map.of("keyword", keyword));
    }

    @PutMapping("/keywords/{id}")
    @Audited("修改采集词")
    public Result<Map<String, Object>> updateKeyword(@PathVariable long id, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM search_keywords WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("关键词不存在");
        }
        Map<String, Object> cur = rows.get(0);
        String keyword = text(body.get("keyword"));
        String target = keyword != null ? keyword : String.valueOf(cur.get("keyword"));
        if (target.length() > MAX_KEYWORD_LEN) {
            throw new IllegalArgumentException("关键词不超过 64 个字符");
        }
        int enabled = body.get("enabled") == null
                ? ((Number) cur.get("enabled")).intValue()
                : (Boolean.TRUE.equals(body.get("enabled")) || "1".equals(String.valueOf(body.get("enabled"))) ? 1 : 0);
        try {
            jdbc.update("UPDATE search_keywords SET keyword = ?, enabled = ? WHERE id = ?", target, enabled, id);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("keyword_exists", "词库里已有：" + target);
        }
        picks.clear();
        return Result.ok(Map.of("id", id, "keyword", target, "enabled", enabled));
    }

    @DeleteMapping("/keywords/{id}")
    @Audited("删除采集词")
    public Result<Void> deleteKeyword(@PathVariable long id) {
        jdbc.update("DELETE FROM search_keywords WHERE id = ?", id);
        picks.clear();
        return Result.ok(null);
    }

    /* ================= 新词提交与审批 ================= */

    /** 采集员提交新搜索词，先进待审批，管理员通过后才进词库。 */
    @PostMapping("/keywords/suggest")
    public Result<Map<String, Object>> suggestKeyword(@RequestBody Map<String, Object> body) {
        String keyword = text(body.get("keyword"));
        if (keyword == null || keyword.length() > MAX_KEYWORD_LEN) {
            throw new IllegalArgumentException("关键词必填且不超过 64 个字符");
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_keywords WHERE keyword = ?", Integer.class, keyword);
        if (exists != null && exists > 0) {
            throw new ConflictException("keyword_exists", "词库里已有：" + keyword);
        }
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM keyword_suggestions WHERE keyword = ? AND status = 'pending'", Integer.class, keyword);
        if (pending != null && pending > 0) {
            throw new ConflictException("keyword_pending", "这个词已经在等审批：" + keyword);
        }
        UserContext me = UserContext.get();
        String code = me == null ? null : me.getCode();
        jdbc.update("""
                INSERT INTO keyword_suggestions (keyword, status, submitted_by, submitted_at)
                VALUES (?, 'pending', ?, NOW(3))
                """, keyword, code);
        return Result.ok(Map.of("keyword", keyword, "status", "pending"));
    }

    /** 待审批列表：管理员看全部，采集员只看自己提交的。 */
    @GetMapping("/keywords/suggestions")
    public Result<List<Map<String, Object>>> listSuggestions(@RequestParam(required = false) String status) {
        UserContext me = UserContext.get();
        boolean admin = me != null && "admin".equalsIgnoreCase(me.getRole());
        StringBuilder sql = new StringBuilder("""
                SELECT id, keyword, status, submitted_by, submitted_at, reviewed_by, reviewed_at, reason
                FROM keyword_suggestions WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        if (!admin) {
            sql.append(" AND submitted_by = ?");
            args.add(me == null ? "" : me.getCode());
        }
        sql.append(" ORDER BY FIELD(status, 'pending', 'approved', 'rejected'), submitted_at DESC LIMIT 200");
        return Result.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /** 审批：通过则自动写进词库，驳回需填原因。 */
    @PostMapping("/keywords/suggestions/{id}/review")
    @Audited("审批新搜索词")
    public Result<Map<String, Object>> reviewSuggestion(@PathVariable long id, @RequestBody Map<String, Object> body) {
        UserContext me = UserContext.get();
        if (me == null || !"admin".equalsIgnoreCase(me.getRole())) {
            throw new IllegalArgumentException("只有管理员可以审批");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, keyword, status FROM keyword_suggestions WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("提交记录不存在");
        }
        Map<String, Object> cur = rows.get(0);
        if (!"pending".equals(String.valueOf(cur.get("status")))) {
            throw new IllegalArgumentException("这条已经处理过了");
        }
        boolean approve = Boolean.TRUE.equals(body.get("approve"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("approve")));
        String keyword = String.valueOf(cur.get("keyword"));
        String reason = text(body.get("reason"));
        if (approve) {
            jdbc.update("""
                    INSERT INTO search_keywords (keyword, source, created_by, hit_count, enabled, created_at)
                    VALUES (?, 'manual', ?, 0, 1, NOW(3))
                    ON DUPLICATE KEY UPDATE enabled = 1
                    """, keyword, me.getCode());
            picks.clear();
        }
        jdbc.update("""
                UPDATE keyword_suggestions
                SET status = ?, reviewed_by = ?, reviewed_at = NOW(3), reason = ?
                WHERE id = ?
                """, approve ? "approved" : "rejected", me.getCode(), reason, id);
        return Result.ok(Map.of("id", id, "keyword", keyword, "status", approve ? "approved" : "rejected"));
    }

    /* ================= 每周分析 ================= */

    @GetMapping("/analysis")
    public Result<Map<String, Object>> analysis(@RequestParam(required = false) String week) {
        LocalDate start = weekStart(week);
        return Result.ok(buildAnalysis(start));
    }

    @GetMapping("/analysis/export")
    public void export(@RequestParam(required = false) String week, HttpServletResponse response) throws IOException {
        LocalDate start = weekStart(week);
        Map<String, Object> data = buildAnalysis(start);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        StringBuilder csv = new StringBuilder();
        csv.append("关键词,本周采集量,上周采集量,环比,去重视频数,作者数,采集人数,已上传,上传转化率,词库来源,累计使用次数,最近使用,衍生建议\n");
        for (Map<String, Object> it : items) {
            csv.append(cell(it.get("keyword"))).append(',')
                    .append(it.get("collected")).append(',')
                    .append(it.get("prevCollected")).append(',')
                    .append(cell(it.get("trend"))).append(',')
                    .append(it.get("collected")).append(',')
                    .append(it.get("authors")).append(',')
                    .append(it.get("collectors")).append(',')
                    .append(it.get("uploaded")).append(',')
                    .append(cell(it.get("uploadRate"))).append(',')
                    .append(cell(it.get("source"))).append(',')
                    .append(it.get("hitCount")).append(',')
                    .append(cell(it.get("lastUsedAt"))).append(',')
                    .append(cell(it.get("suggestion"))).append('\n');
        }
        String filename = "keyword-analysis-" + data.get("week") + ".csv";
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> buildAnalysis(LocalDate start) {
        LocalDate end = start.plusDays(7);
        Timestamp from = Timestamp.valueOf(start.atStartOfDay());
        Timestamp to = Timestamp.valueOf(end.atStartOfDay());
        Timestamp prevFrom = Timestamp.valueOf(start.minusDays(7).atStartOfDay());
        Timestamp prevTo = from;

        Map<String, Map<String, Object>> prev = new LinkedHashMap<>();
        for (Map<String, Object> row : weekStats(prevFrom, prevTo)) {
            prev.put(String.valueOf(row.get("keyword")), row);
        }
        List<Map<String, Object>> cur = weekStats(from, to);
        Map<String, Map<String, Object>> curByKeyword = new LinkedHashMap<>();
        for (Map<String, Object> row : cur) {
            curByKeyword.put(String.valueOf(row.get("keyword")), row);
        }

        List<Map<String, Object>> library = jdbc.queryForList("""
                SELECT id, keyword, source, enabled, hit_count, last_used_at
                FROM search_keywords ORDER BY hit_count DESC, id DESC
                """);
        List<Map<String, Object>> items = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Map<String, Object> lib : library) {
            String keyword = String.valueOf(lib.get("keyword"));
            Map<String, Object> row = curByKeyword.get(keyword);
            items.add(item(keyword, row, prev.get(keyword), lib.get("source"), lib.get("enabled"),
                    lib.get("hit_count"), lib.get("last_used_at")));
            used.add(keyword);
        }
        // 本周出现但不在词库里的词（历史数据/手动改过库名）也列出来
        for (Map<String, Object> row : cur) {
            String keyword = String.valueOf(row.get("keyword"));
            if (used.contains(keyword)) {
                continue;
            }
            items.add(item(keyword, row, prev.get(keyword), "auto", 1, row.get("collected"), row.get("last_at")));
        }
        items.sort((a, b) -> Long.compare(num(b.get("collected")), num(a.get("collected"))));

        long videos = 0;
        long prevVideos = 0;
        for (Map<String, Object> it : items) {
            videos += num(it.get("collected"));
            prevVideos += num(it.get("prevCollected"));
        }
        Long authorCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT author_sec_uid) FROM videos
                WHERE search_keyword IS NOT NULL AND author_sec_uid IS NOT NULL
                  AND collected_at >= ? AND collected_at < ?
                """, Long.class, from, to);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("videos", videos);
        summary.put("prevVideos", prevVideos);
        summary.put("keywords", items.size());
        summary.put("authors", authorCount == null ? 0 : authorCount);
        summary.put("multiKeywordAuthors", multiKeywordAuthors(from, to));
        summary.put("uploaded", jdbc.queryForObject("""
                SELECT COUNT(*) FROM videos
                WHERE search_keyword IS NOT NULL AND status = 'uploaded'
                  AND collected_at >= ? AND collected_at < ?
                """, Long.class, from, to));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("week", weekLabel(start));
        out.put("from", start.toString());
        out.put("to", end.minusDays(1).toString());
        out.put("summary", summary);
        out.put("items", items);
        out.put("overlapAuthors", overlapAuthors(from, to));
        return out;
    }

    /** 同一作者在本周被多个关键词采到的 TOP，用来判断词库是否重复。 */
    private List<Map<String, Object>> overlapAuthors(Timestamp from, Timestamp to) {
        return jdbc.queryForList("""
                SELECT v.author_sec_uid, MAX(v.author_nickname) AS nickname,
                       GROUP_CONCAT(DISTINCT v.search_keyword ORDER BY v.search_keyword) AS keywords,
                       COUNT(*) AS videos
                FROM videos v
                WHERE v.search_keyword IS NOT NULL AND v.author_sec_uid IS NOT NULL
                  AND v.collected_at >= ? AND v.collected_at < ?
                GROUP BY v.author_sec_uid
                HAVING COUNT(DISTINCT v.search_keyword) > 1
                ORDER BY videos DESC
                LIMIT 10
                """, from, to);
    }

    private long multiKeywordAuthors(Timestamp from, Timestamp to) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT author_sec_uid FROM videos
                    WHERE search_keyword IS NOT NULL AND author_sec_uid IS NOT NULL
                      AND collected_at >= ? AND collected_at < ?
                    GROUP BY author_sec_uid
                    HAVING COUNT(DISTINCT search_keyword) > 1
                ) t
                """, Long.class, from, to);
        return n == null ? 0 : n;
    }

    private List<Map<String, Object>> weekStats(Timestamp from, Timestamp to) {
        return jdbc.queryForList("""
                SELECT CASE WHEN k.keyword IS NULL THEN v.search_keyword ELSE k.keyword END AS keyword,
                       COUNT(DISTINCT v.aweme_id) AS collected,
                       COUNT(DISTINCT v.author_sec_uid) AS authors,
                       COUNT(DISTINCT v.collected_by) AS collectors,
                       COUNT(DISTINCT CASE WHEN v.status = 'uploaded' THEN v.aweme_id END) AS uploaded,
                       MAX(v.collected_at) AS last_at
                FROM videos v
                LEFT JOIN search_keywords k ON k.keyword = v.search_keyword
                WHERE v.search_keyword IS NOT NULL
                  AND v.collected_at >= ? AND v.collected_at < ?
                GROUP BY CASE WHEN k.keyword IS NULL THEN v.search_keyword ELSE k.keyword END
                """, from, to);
    }

    private Map<String, Object> item(String keyword, Map<String, Object> row, Map<String, Object> prevRow,
                                     Object source, Object enabled, Object hitCount, Object lastUsedAt) {
        long collected = row == null ? 0 : num(row.get("collected"));
        long prevCollected = prevRow == null ? 0 : num(prevRow.get("collected"));
        long authors = row == null ? 0 : num(row.get("authors"));
        long collectors = row == null ? 0 : num(row.get("collectors"));
        long uploaded = row == null ? 0 : num(row.get("uploaded"));
        long hits = hitCount == null ? 0 : num(hitCount);
        String trend;
        if (prevCollected <= 0) {
            trend = collected > 0 ? "新增" : "—";
        } else {
            double ratio = (collected - prevCollected) * 100.0 / prevCollected;
            if (prevCollected < 10) {
                // 基数太小，百分比会失真（出现 +2080% 这种），只给方向
                trend = collected >= prevCollected ? "明显上涨" : "明显下滑";
            } else if (ratio >= 300) {
                trend = "+300%以上";
            } else if (ratio <= -70) {
                trend = "-70%以上";
            } else {
                trend = (ratio >= 0 ? "+" : "") + Math.round(ratio) + "%";
            }
        }
        String uploadRate = collected <= 0 ? "—" : Math.round(uploaded * 100.0 / collected) + "%";

        Map<String, Object> it = new LinkedHashMap<>();
        it.put("keyword", keyword);
        it.put("collected", collected);
        it.put("prevCollected", prevCollected);
        it.put("trend", trend);
        it.put("authors", authors);
        it.put("collectors", collectors);
        it.put("uploaded", uploaded);
        it.put("uploadRate", uploadRate);
        it.put("hitCount", hits);
        it.put("source", source == null ? "auto" : source);
        it.put("enabled", enabled == null ? 1 : enabled);
        it.put("lastUsedAt", lastUsedAt);
        it.put("suggestion", suggest(collected, prevCollected, authors, collectors, hits));
        return it;
    }

    /** 规则化衍生建议：产量 / 作者分布 / 环比 / 上传转化一起看。 */
    private static String suggest(long collected, long prevCollected, long authors, long collectors, long hits) {
        if (hits == 0 && collected == 0) {
            return "还没用过：可以安排一次试采，看能出多少量";
        }
        if (collected == 0) {
            return "本周无产出：暂停该词或换成更具体的长尾词";
        }
        double perAuthor = authors <= 0 ? 0 : collected * 1.0 / authors;
        StringBuilder sb = new StringBuilder();
        if (collected >= 50 && authors >= 15) {
            sb.append("高产出宽词：可扩同义/场景/人群衍生词，做长尾矩阵");
        } else if (collected >= 50 && authors < 5) {
            sb.append("产量高但作者集中：头部垄断，建议换细分角度避免重复");
        } else if (collected < 10) {
            sb.append("产出偏低：换更具体的词或搭配热点词");
        } else {
            sb.append("表现稳定：保持投放，按上传转化率决定加量");
        }
        if (perAuthor > 0 && perAuthor < 2) {
            sb.append("；人均产量低（作者分散），适合做作者盘点");
        } else if (perAuthor >= 5) {
            sb.append("；单作者产量高，可优先盯头部作者做衍生选题");
        }
        if (prevCollected > 0 && collected >= prevCollected * 1.5) {
            sb.append("；环比明显上涨，可加量");
        } else if (prevCollected > 0 && collected <= prevCollected * 0.5) {
            sb.append("；环比下滑，先观察一周再决定去留");
        }
        if (collectors <= 1 && collected >= 30) {
            sb.append("；目前集中在 1 人采集，可分摊给多人提速");
        }
        return sb.toString();
    }

    /* ================= 周计算 ================= */

    private static LocalDate weekStart(String week) {
        if (week == null || week.isBlank()) {
            return LocalDate.now()
                    .with(IsoFields.WEEK_BASED_YEAR, LocalDate.now().get(IsoFields.WEEK_BASED_YEAR))
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR,
                            LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                    .with(ChronoField.DAY_OF_WEEK, 1);
        }
        String w = week.trim().toUpperCase();
        int idx = w.indexOf("-W");
        if (idx <= 0) {
            throw new IllegalArgumentException("周格式应为 2026-W45");
        }
        try {
            int year = Integer.parseInt(w.substring(0, idx));
            int weekNo = Integer.parseInt(w.substring(idx + 2));
            return LocalDate.now()
                    .with(IsoFields.WEEK_BASED_YEAR, year)
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, weekNo)
                    .with(ChronoField.DAY_OF_WEEK, 1);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("周格式应为 2026-W45");
        }
    }

    private static String weekLabel(LocalDate monday) {
        int year = monday.get(IsoFields.WEEK_BASED_YEAR);
        int week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return year + "-W" + (week < 10 ? "0" + week : String.valueOf(week));
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String cell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
