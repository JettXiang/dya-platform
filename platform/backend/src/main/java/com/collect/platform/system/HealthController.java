package com.collect.platform.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping({"/healthz", "/api/v1/healthz"})
    public Result<String> health() {
        return Result.ok("up");
    }
}
