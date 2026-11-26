package com.collect.platform.task;

import com.collect.platform.security.UserContext;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每日采集任务：管理员分配，采集员只看自己的。 */
@Service
public class DailyTaskService {

    private final DailyTaskRepository repo;

    public DailyTaskService(DailyTaskRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> list(String date) {
        LocalDate day = parseDate(date);
        Timestamp from = Timestamp.valueOf(day.atStartOfDay());
        Timestamp to = Timestamp.valueOf(day.plusDays(1).atStartOfDay());
        UserContext me = UserContext.get();
        boolean admin = me != null && "admin".equalsIgnoreCase(me.getRole());
        String collectorCode = admin || me == null ? null : me.getCode();

        List<Map<String, Object>> rows = repo.listByDate(from, to, java.sql.Date.valueOf(day), collectorCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("items", rows);
        return out;
    }

    public Map<String, Object> save(Map<String, Object> body) {
        String collectorCode = text(body.get("collectorCode"));
        if (collectorCode == null) {
            throw new IllegalArgumentException("请选择采集员");
        }
        if (!repo.userExists(collectorCode)) {
            throw new IllegalArgumentException("采集员不存在：" + collectorCode);
        }
        LocalDate day = parseDate(text(body.get("date")));
        int target = body.get("targetCount") == null ? 0 : ((Number) body.get("targetCount")).intValue();
        String note = text(body.get("note"));
        UserContext me = UserContext.get();
        repo.upsert(java.sql.Date.valueOf(day), collectorCode, target, note, me == null ? null : me.getCode());
        return Map.of("date", day.toString(), "collectorCode", collectorCode, "targetCount", target);
    }

    public Map<String, Object> delete(long id) {
        if (repo.delete(id) == 0) {
            throw new IllegalArgumentException("任务不存在");
        }
        return Map.of("id", id, "deleted", true);
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
