package com.collect.platform.task;

import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每日采集任务：管理员给采集员分配当天目标，采集员只看自己的。 */
@RestController
@RequestMapping("/api/v1/daily-tasks")
public class DailyTaskController {

    private final JdbcTemplate jdbc;

    public DailyTaskController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(required = false) String date) {
        LocalDate day = parseDate(date);
        Timestamp from = Timestamp.valueOf(day.atStartOfDay());
        Timestamp to = Timestamp.valueOf(day.plusDays(1).atStartOfDay());
        UserContext me = UserContext.get();
        boolean admin = me != null && "admin".equalsIgnoreCase(me.getRole());

        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        args.add(from);
        args.add(to);
        args.add(from);
        args.add(to);
        args.add(java.sql.Date.valueOf(day));
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.task_date, t.collector_code, u.name AS collector_name,
                       t.target_count, t.note, t.created_by,
                       (SELECT COUNT(*) FROM videos v WHERE v.collected_by = t.collector_code
                          AND v.collected_at >= ? AND v.collected_at < ?) AS actual_count,
                       (SELECT COUNT(DISTINCT v.author_sec_uid) FROM videos v WHERE v.collected_by = t.collector_code
                          AND v.collected_at >= ? AND v.collected_at < ? AND v.author_sec_uid IS NOT NULL) AS author_count,
                       (SELECT MAX(v.collected_at) FROM videos v WHERE v.collected_by = t.collector_code
                          AND v.collected_at >= ? AND v.collected_at < ?) AS last_at
                FROM daily_tasks t
                LEFT JOIN users u ON u.code = t.collector_code
                WHERE t.task_date = ?
                """);
        if (!admin && me != null) {
            sql.append(" AND t.collector_code = ?");
            args.add(me.getCode());
        }
        sql.append(" ORDER BY t.id");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("items", rows);
        return Result.ok(out);
    }

    @PostMapping
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        String collectorCode = text(body.get("collectorCode"));
        if (collectorCode == null) {
            throw new IllegalArgumentException("请选择采集员");
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE code = ?", Integer.class, collectorCode);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("采集员不存在：" + collectorCode);
        }
        LocalDate day = parseDate(text(body.get("date")));
        int target = body.get("targetCount") == null ? 0 : ((Number) body.get("targetCount")).intValue();
        String note = text(body.get("note"));
        UserContext me = UserContext.get();

        jdbc.update("""
                INSERT INTO daily_tasks (task_date, collector_code, target_count, note, created_by)
                VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE target_count = VALUES(target_count),
                                        note = VALUES(note), updated_at = NOW(3)
                """, java.sql.Date.valueOf(day), collectorCode, Math.max(target, 0), note,
                me == null ? null : me.getCode());
        return Result.ok(Map.of("date", day.toString(), "collectorCode", collectorCode, "targetCount", target));
    }

    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable long id) {
        int n = jdbc.update("DELETE FROM daily_tasks WHERE id = ?", id);
        if (n == 0) {
            throw new IllegalArgumentException("任务不存在");
        }
        return Result.ok(Map.of("id", id, "deleted", true));
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now(ZoneId.of("Asia/Shanghai"));
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
