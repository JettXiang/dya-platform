package com.collect.platform.search;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词周分析：生成周报数据与 CSV，业务规则与 SQL 访问分离。
 */
@Service
public class KeywordAnalysisService {

    private final KeywordAnalysisRepository repo;

    public KeywordAnalysisService(KeywordAnalysisRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> analysis(String week) {
        return buildAnalysis(weekStart(week));
    }

    /** 生成导出 CSV（含表头），编码由调用方决定。 */
    public String toCsv(Map<String, Object> data) {
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
        return csv.toString();
    }

    private Map<String, Object> buildAnalysis(LocalDate start) {
        LocalDate end = start.plusDays(7);
        Timestamp from = Timestamp.valueOf(start.atStartOfDay());
        Timestamp to = Timestamp.valueOf(end.atStartOfDay());
        Timestamp prevFrom = Timestamp.valueOf(start.minusDays(7).atStartOfDay());
        Timestamp prevTo = from;

        Map<String, Map<String, Object>> prev = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.weekStats(prevFrom, prevTo)) {
            prev.put(String.valueOf(row.get("keyword")), row);
        }
        List<Map<String, Object>> cur = repo.weekStats(from, to);
        Map<String, Map<String, Object>> curByKeyword = new LinkedHashMap<>();
        for (Map<String, Object> row : cur) {
            curByKeyword.put(String.valueOf(row.get("keyword")), row);
        }

        List<Map<String, Object>> library = repo.listLibrary();
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> used = new HashSet<>();
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
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("videos", videos);
        summary.put("prevVideos", prevVideos);
        summary.put("keywords", items.size());
        summary.put("authors", repo.countAuthors(from, to));
        summary.put("multiKeywordAuthors", repo.countMultiKeywordAuthors(from, to));
        summary.put("uploaded", repo.countUploaded(from, to));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("week", weekLabel(start));
        out.put("from", start.toString());
        out.put("to", end.minusDays(1).toString());
        out.put("summary", summary);
        out.put("items", items);
        out.put("overlapAuthors", repo.overlapAuthors(from, to));
        return out;
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
}
