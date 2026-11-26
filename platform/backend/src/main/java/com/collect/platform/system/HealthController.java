package com.collect.platform.system;

import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping({"/healthz", "/api/v1/healthz"})
    public Result<String> health() {
        return Result.ok("up");
    }
}
