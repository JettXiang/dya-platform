package com.collect.platform.video;

import com.collect.platform.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展运行状态。扩展通过平台密钥每 30 秒上报一次心跳（页面地址、采集/自动下滑开关、
 * 本轮作者数、已扫视频数），前端「抖音采集」页据此在抖音窗口最小化或挂后台时也能监控。
 */
@RestController
@RequestMapping("/api/v1/collect")
public class ExtensionStatusController {

    /** 超过该时长没收到心跳即视为离线 */
    private static final long ONLINE_WINDOW_MS = 90_000L;

    private final JdbcTemplate jdbc;
    private final Map<String, Map<String, Object>> latest = new ConcurrentHashMap<>();

    @Value("${collect.ingest-key:dya-collect-2026}")
    private String ingestKey;

    public ExtensionStatusController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/heartbeat")
    public Result<Map<String, Object>> heartbeat(
            @RequestHeader(value = "X-Collect-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        if (key == null || !key.equals(ingestKey)) {
            return Result.fail("采集密钥无效");
        }
        String collector = body.get("collector") == null ? "扩展直传" : String.valueOf(body.get("collector")).trim();
        if (collector.isEmpty()) {
            collector = "扩展直传";
        }
        Map<String, Object> snap = new LinkedHashMap<>(body);
        snap.put("collector", collector);
        snap.put("receivedAt", System.currentTimeMillis());
        // 多台采集端都叫「扩展直传」，按 deviceId 分开存，避免互相覆盖
        String deviceId = body.get("deviceId") == null ? "" : String.valueOf(body.get("deviceId")).trim();
        snap.put("deviceId", deviceId);
        latest.put(deviceId.isEmpty() ? collector : collector + "#" + deviceId, snap);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return Result.ok(out);
    }

    @GetMapping("/status")
    public Result<List<Map<String, Object>>> status() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> snap : latest.values()) {
            Map<String, Object> s = new LinkedHashMap<>(snap);
            long at = snap.get("receivedAt") instanceof Number n ? n.longValue() : 0L;
            s.put("online", now - at <= ONLINE_WINDOW_MS);
            s.put("ageMs", now - at);
            s.put("todayInserted", todayInserted(String.valueOf(snap.get("collector"))));
            out.add(s);
        }
        return Result.ok(out);
    }

    /** 今日该采集人入库条数，供前端展示“确实在涨”的证据 */
    private long todayInserted(String collector) {
        Timestamp start = Timestamp.valueOf(LocalDate.now().atStartOfDay());
        Long count = jdbc.queryForObject(
                "select count(*) from videos where collected_by = ? and collected_at >= ?",
                Long.class, collector, start);
        return count == null ? 0L : count;
    }
}
