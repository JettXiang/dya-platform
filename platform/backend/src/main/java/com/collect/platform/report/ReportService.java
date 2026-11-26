package com.collect.platform.report;

import com.collect.platform.security.UserContext;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日报统计与采集日报 CSV 生成。
 */
@Service
public class ReportService {

    private final ReportRepository repo;

    public ReportService(ReportRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> daily(String date) {
        LocalDate d = LocalDate.parse(date);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("scheduled_work", repo.countScheduledWork(d));
        out.put("reported_accounts", repo.countDayFinals(d, false));
        out.put("complete_accounts", repo.countDayFinals(d, true));
        out.put("runs", repo.countRuns(d));
        out.put("authors_collected", repo.countAuthorsCollected(d));
        // 扩展直传口径：只统计 videos，不依赖排班/批次，供「扩展直传」流程看当日真实采集量
        Timestamp from = Timestamp.valueOf(d.atStartOfDay());
        Timestamp to = Timestamp.valueOf(d.plusDays(1).atStartOfDay());
        out.put("direct_videos", repo.countVideos(from, to));
        out.put("direct_authors", repo.countDirectAuthors(from, to));
        Object lastAt = repo.maxCollectedAt(from, to);
        out.put("direct_last_at", lastAt == null ? null : ((Timestamp) lastAt).getTime());
        out.put("batches", repo.listBatches(d));
        out.put("accounts", repo.listAccountRows(d));
        return out;
    }

    /** 采集日报 CSV：按采集人汇总当天入库量、任务完成率，可直接用 Excel 打开。 */
    public String collectionCsv(String date) {
        LocalDate d = (date == null || date.isBlank()) ? LocalDate.now() : LocalDate.parse(date.trim());
        Timestamp from = Timestamp.valueOf(d.atStartOfDay());
        Timestamp to = Timestamp.valueOf(d.plusDays(1).atStartOfDay());
        UserContext me = UserContext.get();
        boolean admin = me != null && ("admin".equalsIgnoreCase(me.getRole())
                || "uploader".equalsIgnoreCase(me.getRole()));
        String collectorCode = admin || me == null ? null : me.getCode();

        List<Map<String, Object>> rows = repo.exportRows(from, to, collectorCode);

        Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
        for (Map<String, Object> t : repo.listTaskRows(d)) {
            tasks.put(String.valueOf(t.get("collector_code")), t);
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder csv = new StringBuilder();
        csv.append("日期,采集人编码,采集人,采集终端,采集视频数,作者数,已上传,待上传,上传失败,任务目标,完成率,首次采集,最后采集\n");
        Set<String> seen = new HashSet<>();
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
        return csv.toString();
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
