package com.collect.platform.collect;

import com.collect.platform.common.Result;
import com.collect.platform.video.VideoIngestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集端直传接口（免登录，走采集密钥）。
 * 供扩展 / 外部 collector 把抓到的 aweme 明细直接推到平台，采集人由请求体 collector 字段指定。
 * 平台内“抖音采集”页仍走带 JWT 的 /api/v1/videos/ingest。
 */
@RestController
@RequestMapping("/api/v1/collect")
public class CollectIngestController {

    private final VideoIngestService ingestService;

    @Value("${collect.ingest-key:dya-collect-2026}")
    private String ingestKey;

    public CollectIngestController(VideoIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/ingest")
    public Result<Map<String, Object>> ingest(
            @RequestHeader(value = "X-Collect-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        if (key == null || !key.equals(ingestKey)) {
            return Result.fail("采集密钥无效");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        Object raw = body.get("items");
        if (raw instanceof List<?> list) {
            for (Object v : list) {
                if (v instanceof Map<?, ?> m && items.size() < 500) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    m.forEach((k, val) -> item.put(String.valueOf(k), val));
                    items.add(item);
                }
            }
        }
        if (items.isEmpty()) {
            return Result.fail("items 不能为空");
        }
        String collector = body.get("collector") == null ? "external" : String.valueOf(body.get("collector")).trim();
        if (collector.isEmpty()) {
            collector = "external";
        }
        if (collector.length() > 64) {
            collector = collector.substring(0, 64);
        }
        String sourceUrl = body.get("sourceUrl") == null ? null : String.valueOf(body.get("sourceUrl"));
        int inserted = ingestService.save(items, collector, sourceUrl);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("inserted", inserted);
        out.put("collector", collector);
        return Result.ok(out);
    }
}
