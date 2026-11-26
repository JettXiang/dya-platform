package com.collect.platform.extension;

import com.collect.platform.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展运行状态：心跳快照、在线判定与今日入库量。
 */
@Service
public class ExtensionStatusService {

    /** 超过该时长没收到心跳即视为离线 */
    private static final long ONLINE_WINDOW_MS = 90_000L;

    private final ExtensionStatusRepository repo;
    private final Map<String, Map<String, Object>> latest = new ConcurrentHashMap<>();

    @Value("${collect.ingest-key:dya-collect-2026}")
    private String ingestKey;

    public ExtensionStatusService(ExtensionStatusRepository repo) {
        this.repo = repo;
    }

    public Result<Map<String, Object>> heartbeat(String key, Map<String, Object> body) {
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

    public List<Map<String, Object>> status() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> snap : latest.values()) {
            Map<String, Object> s = new LinkedHashMap<>(snap);
            long at = snap.get("receivedAt") instanceof Number n ? n.longValue() : 0L;
            s.put("online", now - at <= ONLINE_WINDOW_MS);
            s.put("ageMs", now - at);
            s.put("todayInserted", repo.todayInserted(String.valueOf(snap.get("collector"))));
            out.add(s);
        }
        return out;
    }
}
