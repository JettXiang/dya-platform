package com.collect.platform.notify;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(service.list());
    }

    @PostMapping("/{id}/read")
    @Audited("通知已读")
    public Result<Void> read(@PathVariable long id) {
        service.read(id);
        return Result.ok(null);
    }

    @PostMapping
    @Audited("发送通知")
    public Result<Void> create(@RequestBody Map<String, Object> body) {
        service.create(body);
        return Result.ok(null);
    }
}
