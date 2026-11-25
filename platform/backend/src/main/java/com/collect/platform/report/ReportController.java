package com.collect.platform.report;

import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final JdbcTemplate jdbc;

    public ReportController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/daily")
    public Result<Map<String, Object>> daily(@RequestParam String date) {
        LocalDate d = LocalDate.parse(date);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("scheduled_work", jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedules WHERE `date` = ? AND status = 'work'", Integer.class, d));
        out.put("reported_accounts", jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_finals WHERE `date` = ?", Integer.class, d));
        out.put("complete_accounts", jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_finals WHERE `date` = ? AND complete = 1", Integer.class, d));
        out.put("runs", jdbc.queryForObject(
                "SELECT COUNT(*) FROM runs WHERE `date` = ?", Integer.class, d));
        out.put("authors_collected", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT s.sec_uid) FROM authors_source s JOIN runs r ON r.id = s.run_id WHERE r.`date` = ?",
                Integer.class, d));
        // 扩展直传口径：只统计 videos，不依赖排班/批次，供「扩展直传」流程看当日真实采集量
        Timestamp from = Timestamp.valueOf(d.atStartOfDay());
        Timestamp to = Timestamp.valueOf(d.plusDays(1).atStartOfDay());
        out.put("direct_videos", jdbc.queryForObject(
                "SELECT COUNT(*) FROM videos WHERE collected_at >= ? AND collected_at < ?", Integer.class, from, to));
        out.put("direct_authors", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT author_sec_uid) FROM videos WHERE collected_at >= ? AND collected_at < ? AND author_sec_uid IS NOT NULL",
                Integer.class, from, to));
        Timestamp lastAt = jdbc.queryForObject(
                "SELECT MAX(collected_at) FROM videos WHERE collected_at >= ? AND collected_at < ?", Timestamp.class, from, to);
        out.put("direct_last_at", lastAt == null ? null : lastAt.getTime());
        out.put("batches", jdbc.queryForList(
                "SELECT batch_no, type, state, total, dedup_total, attempt_count FROM batches WHERE `date` = ? ORDER BY id",
                d));
        out.put("accounts", jdbc.queryForList("""
                SELECT a.code AS account_code, u.name,
                       COALESCE(df.complete, 0) AS complete,
                       COALESCE(df.client_pending_chunks, 0) AS pending_chunks,
                       df.revision, df.evaluated_at, a.last_heartbeat_at,
                       (SELECT COUNT(*) FROM runs r
                        WHERE r.account_id = a.id AND r.`date` = ? AND r.voided_at IS NULL) AS runs,
                       (SELECT COUNT(DISTINCT src.sec_uid) FROM authors_source src
                        JOIN runs r ON r.id = src.run_id
                        WHERE r.account_id = a.id AND r.`date` = ? AND r.voided_at IS NULL) AS authors
                FROM schedules sc
                JOIN accounts a ON a.id = sc.account_id
                JOIN users u ON u.id = a.user_id
                LEFT JOIN day_finals df ON df.account_id = a.id AND df.`date` = ?
                WHERE sc.`date` = ? AND sc.status = 'work'
                ORDER BY a.code
                """, d, d, d, d));
        return Result.ok(out);
    }

    /** 采集日报 CSV：按采集人汇总当天入库量、任务完成率，可直接用 Excel 打开。 */
    @GetMapping("/collection-export")
    public void collectionExport(@RequestParam(required = false) String date,
                                 HttpServletResponse response) throws IOException {
        LocalDate d = (date == null || date.isBlank()) ? LocalDate.now() : LocalDate.parse(date.trim());
        Timestamp from = Timestamp.valueOf(d.atStartOfDay());
        Timestamp to = Timestamp.valueOf(d.plusDays(1).atStartOfDay());
        UserContext me = UserContext.get();
        boolean admin = me != null && ("admin".equalsIgnoreCase(me.getRole())
                || "uploader".equalsIgnoreCase(me.getRole()));

        List<Object> args = new ArrayList<>(List.of(from, to));
        StringBuilder sql = new StringBuilder("""
                SELECT v.collected_by,
                       COALESCE(u.name, v.collected_by) AS collector_name,
                       GROUP_CONCAT(DISTINCT v.terminal_code) AS terminals,
                       COUNT(*) AS video_count,
                       COUNT(DISTINCT v.author_sec_uid) AS author_count,
                       MIN(v.collected_at) AS first_at,
                       MAX(v.collected_at) AS last_at,
                       SUM(CASE WHEN v.status = 'uploaded' THEN 1 ELSE 0 END) AS uploaded,
                       SUM(CASE WHEN v.status = 'pending' THEN 1 ELSE 0 END) AS pending,
                       SUM(CASE WHEN v.status = 'failed' THEN 1 ELSE 0 END) AS failed
                FROM videos v LEFT JOIN users u ON u.code = v.collected_by
                WHERE v.collected_at >= ? AND v.collected_at < ?
                """);
        if (!admin && me != null) {
            sql.append(" AND v.collected_by = ?");
            args.add(me.getCode());
        }
        sql.append(" GROUP BY v.collected_by, u.name ORDER BY video_count DESC");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());

        Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
        for (Map<String, Object> t : jdbc.queryForList("""
                SELECT t.collector_code, u.name AS collector_name, t.target_count
                FROM daily_tasks t LEFT JOIN users u ON u.code = t.collector_code
                WHERE t.task_date = ?
                """, java.sql.Date.valueOf(d))) {
            tasks.put(String.valueOf(t.get("collector_code")), t);
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder csv = new StringBuilder();
        csv.append("日期,采集人编码,采集人,采集终端,采集视频数,作者数,已上传,待上传,上传失败,任务目标,完成率,首次采集,最后采集\n");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> row : rows) {
            String code = String.valueOf(row.get("collected_by"));
            seen.add(code);
            csv.append(rowLine(d, fmt, code, row.get("collector_name"), row.get("terminals"),
                    number(row.get("video_count")), number(row.get("author_count")),
                    number(row.get("uploaded")), number(row.get("pending")), number(row.get("failed")),
                    tasks.get(code), row.get("first_at"), row.get("last_at")));
        }
        // 有任务但当天没采到数据的采集员也要出现在日报里
        for (Map.Entry<String, Map<String, Object>> e : tasks.entrySet()) {
            if (seen.contains(e.getKey())) {
                continue;
            }
            Map<String, Object> t = e.getValue();
            csv.append(rowLine(d, fmt, e.getKey(), t.get("collector_name"), "",
                    0, 0, 0, 0, 0, t, null, null));
        }

        String filename = "collection-report-" + d + ".csv";
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String rowLine(LocalDate d, DateTimeFormatter fmt, String code, Object collectorName,
                                  Object terminals, long videos, long authors, long uploaded,
                                  long pending, long failed, Map<String, Object> task,
                                  Object firstAt, Object lastAt) {
        long target = task == null ? 0 : number(task.get("target_count"));
        String rate = target > 0 ? Math.min(100, Math.round(videos * 100.0 / target)) + "%" : "—";
        return csvCell(d) + "," + csvCell(code) + "," + csvCell(collectorName) + ","
                + csvCell(terminals) + "," + videos + "," + authors + "," + uploaded + ","
                + pending + "," + failed + "," + target + "," + rate + ","
                + csvCell(time(firstAt, fmt)) + "," + csvCell(time(lastAt, fmt)) + "\n";
    }

    private static long number(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String time(Object v, DateTimeFormatter fmt) {
        if (v == null) {
            return "";
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(fmt);
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.format(fmt);
        }
        return String.valueOf(v);
    }

    private static String csvCell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
