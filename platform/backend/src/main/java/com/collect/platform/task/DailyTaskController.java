package com.collect.platform.task;

import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/daily-tasks")
public class DailyTaskController {

    private final DailyTaskService service;

    public DailyTaskController(DailyTaskService service) {
        this.service = service;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(required = false) String date) {
        return Result.ok(service.list(date));
    }

    @PostMapping
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        return Result.ok(service.save(body));
    }

    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable long id) {
        return Result.ok(service.delete(id));
    }
}
