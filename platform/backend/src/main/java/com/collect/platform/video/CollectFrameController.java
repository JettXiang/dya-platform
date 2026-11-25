package com.collect.platform.video;

import com.collect.platform.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展实时画面。扩展每秒调用 chrome.tabs.captureVisibleTab 截一次抖音标签，
 * 以 JPEG(base64) 推到本接口，最新一帧留在内存里给前端「抖音采集」页展示。
 * 不依赖 9222 调试端口，所以日常浏览器也能看。
 */
@RestController
@RequestMapping("/api/v1/collect")
public class CollectFrameController {

    /** 只保留最新一帧，避免占内存 */
    private final Map<String, Map<String, Object>> latest = new ConcurrentHashMap<>();

    @Value("${collect.ingest-key:dya-collect-2026}")
    private String ingestKey;

    @PostMapping("/frame")
    public Result<Map<String, Object>> frame(
            @RequestHeader(value = "X-Collect-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        if (key == null || !key.equals(ingestKey)) {
            return Result.fail("采集密钥无效");
        }
        String image = body.get("image") == null ? "" : String.valueOf(body.get("image"));
        if (image.isEmpty()) {
            return Result.fail("缺少画面数据");
        }
        String collector = body.get("collector") == null ? "扩展直传" : String.valueOf(body.get("collector")).trim();
        if (collector.isEmpty()) {
            collector = "扩展直传";
        }
        long ts = body.get("ts") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        String deviceId = body.get("deviceId") == null ? "" : String.valueOf(body.get("deviceId")).trim();
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("collector", collector);
        snap.put("deviceId", deviceId);
        snap.put("pageUrl", body.get("pageUrl") == null ? "" : String.valueOf(body.get("pageUrl")));
        snap.put("ts", ts);
        snap.put("receivedAt", System.currentTimeMillis());
        snap.put("image", image);
        // 多台采集端按 deviceId 分开存，避免互相覆盖
        latest.put(deviceId.isEmpty() ? collector : collector + "#" + deviceId, snap);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return Result.ok(out);
    }

    @GetMapping("/frame")
    public Result<Map<String, Object>> latestFrame(
            @RequestParam(value = "collector", required = false) String collector,
            @RequestParam(value = "deviceId", required = false) String deviceId) {
        Map<String, Object> snap = null;
        String who = collector == null || collector.isEmpty() ? "扩展直传" : collector.trim();
        if (deviceId != null && !deviceId.isEmpty()) {
            snap = latest.get(who + "#" + deviceId.trim());
        } else {
            for (Map<String, Object> item : latest.values()) {
                if (snap == null || asLong(item.get("receivedAt")) > asLong(snap.get("receivedAt"))) {
                    snap = item;
                }
            }
        }
        if (snap == null) {
            return Result.ok(new LinkedHashMap<>());
        }
        Map<String, Object> out = new LinkedHashMap<>(snap);
        long at = asLong(snap.get("receivedAt"));
        out.put("ageMs", System.currentTimeMillis() - at);
        return Result.ok(out);
    }

    private long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
