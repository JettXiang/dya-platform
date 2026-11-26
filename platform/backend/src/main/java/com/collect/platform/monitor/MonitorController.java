package com.collect.platform.monitor;

import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {

    private final MonitorService service;

    public MonitorController(MonitorService service) {
        this.service = service;
    }

    @GetMapping("/timeline")
    public Result<Map<String, Object>> timeline(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Boolean success,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Result.ok(service.timeline(actor, action, from, to, success, page, size));
    }

    @GetMapping("/batches/{batchNo}/chain")
    public Result<Map<String, Object>> chain(@PathVariable String batchNo) {
        Map<String, Object> out = service.chain(batchNo);
        return out == null ? Result.fail("批次不存在") : Result.ok(out);
    }

    @GetMapping("/anomalies")
    public Result<Map<String, Object>> anomalies(@RequestParam String date,
                                                 @RequestParam(defaultValue = "all") String type) {
        return Result.ok(service.anomalies(date, type));
    }
}
