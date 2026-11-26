package com.collect.platform.auth;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    @Audited("登录")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req) {
        return service.login(req.getCode(), req.getPassword());
    }

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshRequest req) {
        return service.refresh(req.getRefreshToken());
    }

    @PostMapping("/logout")
    @Audited("退出登录")
    public Result<Void> logout() {
        return service.logout();
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String code;
        @NotBlank
        private String password;
    }

    @Data
    public static class RefreshRequest {
        @NotBlank
        private String refreshToken;
    }
}
