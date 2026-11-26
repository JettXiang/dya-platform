package com.collect.platform.auth;

import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService service;

    public MeController(MeService service) {
        this.service = service;
    }

    @GetMapping
    public Result<Map<String, Object>> me() {
        if (UserContext.get() == null) {
            return Result.fail("未登录");
        }
        Map<String, Object> out = service.me();
        return out == null ? Result.fail("用户不存在") : Result.ok(out);
    }
}
