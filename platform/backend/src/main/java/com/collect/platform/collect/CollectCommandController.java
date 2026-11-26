package com.collect.platform.video;

import com.collect.platform.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遥控指令队列：平台下发（JWT）→ 扩展轮询取走（采集密钥）→ 扩展回报执行结果。
 *
 * 单实例内存队列，重启即清空；指令 2 分钟内没被取走自动作废；
 * 取走即出队，避免同一条指令被执行两次。
 */
@RestController
@RequestMapping("/api/v1/collect/command")
public class CollectCommandController {

    /** 指令存活时长：超过即作废，防止扩展晚连上来把很久以前的动作再执行一遍 */
    private static final long TTL_MS = 120_000L;
    /** 单设备最多堆积的指令数 */
    private static final int MAX_PER_DEVICE = 20;
    /** 保留的执行结果条数 */
    private static final int MAX_RESULTS = 50;

    private final Map<String, Map<String, Object>> queue = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> results = new ConcurrentHashMap<>();

    @Value("${collect.ingest-key:dya-collect-2026}")
    private String ingestKey;

    /** 平台下发：{deviceId, type, payload} */
    @PostMapping
    public Result<Map<String, Object>> push(@RequestBody Map<String, Object> body) {
        String deviceId = text(body.get("deviceId"));
        String type = text(body.get("type"));
        if (deviceId.isEmpty() || type.isEmpty()) {
            return Result.fail("deviceId / type 不能为空");
        }
        long alive = 0;
        for (Map<String, Object> c : queue.values()) {
            if (deviceId.equals(c.get("deviceId")) && !expired(c)) {
                alive++;
            }
        }
        if (alive >= MAX_PER_DEVICE) {
            return Result.fail("该设备待执行指令过多，等它消化完再发");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("id", id);
        cmd.put("deviceId", deviceId);
        cmd.put("type", type);
        cmd.put("payload", body.get("payload"));
        cmd.put("createdAt", System.currentTimeMillis());
        queue.put(id, cmd);
        return Result.ok(cmd);
    }

    /** 扩展轮询：取走该设备全部待执行指令（出队即删，保证只执行一次） */
    @GetMapping("/pending")
    public Result<List<Map<String, Object>>> pending(
            @RequestHeader(value = "X-Collect-Key", required = false) String key,
            @RequestParam String deviceId) {
        if (!Objects.equals(key, ingestKey)) {
            return Result.fail("采集密钥无效");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Iterator<Map<String, Object>> it = queue.values().iterator();
        while (it.hasNext()) {
            Map<String, Object> c = it.next();
            if (expired(c)) {
                it.remove();
                continue;
            }
            if (!deviceId.equals(c.get("deviceId"))) {
                continue;
            }
            it.remove();
            out.add(c);
        }
        out.sort(Comparator.comparingLong(c -> ((Number) c.get("createdAt")).longValue()));
        return Result.ok(out);
    }

    /** 扩展回报：{id, deviceId, ok, message} */
    @PostMapping("/ack")
    public Result<Map<String, Object>> ack(
            @RequestHeader(value = "X-Collect-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        if (!Objects.equals(key, ingestKey)) {
            return Result.fail("采集密钥无效");
        }
        String id = text(body.get("id"));
        if (id.isEmpty()) {
            return Result.fail("id 不能为空");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("deviceId", text(body.get("deviceId")));
        r.put("ok", Boolean.parseBoolean(String.valueOf(body.get("ok"))));
        r.put("message", text(body.get("message")));
        r.put("at", System.currentTimeMillis());
        results.put(id, r);
        trimResults();
        return Result.ok(r);
    }

    /** 前端查最近执行结果：?deviceId=xxx */
    @GetMapping("/results")
    public Result<List<Map<String, Object>>> results(@RequestParam String deviceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : results.values()) {
            if (deviceId.equals(r.get("deviceId"))) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(r -> ((Number) r.get("at")).longValue()));
        if (out.size() > 20) {
            out = new ArrayList<>(out.subList(out.size() - 20, out.size()));
        }
        return Result.ok(out);
    }

    private boolean expired(Map<String, Object> c) {
        Object at = c.get("createdAt");
        long created = at instanceof Number n ? n.longValue() : 0L;
        return System.currentTimeMillis() - created > TTL_MS;
    }

    private void trimResults() {
        if (results.size() <= MAX_RESULTS) {
            return;
        }
        List<Map<String, Object>> all = new ArrayList<>(results.values());
        all.sort(Comparator.comparingLong(r -> ((Number) r.get("at")).longValue()));
        int drop = all.size() - MAX_RESULTS;
        for (int i = 0; i < drop; i++) {
            results.remove(all.get(i).get("id"));
        }
    }

    private String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
