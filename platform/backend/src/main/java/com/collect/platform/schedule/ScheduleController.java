package com.collect.platform.schedule;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam String date) {
        return Result.ok(service.list(date));
    }

    @PutMapping
    @Audited("改排班")
    public Result<Void> upsert(@RequestBody Map<String, Object> body) {
        service.upsert(body);
        return Result.ok(null);
    }
}
