package com.collect.platform.admin;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        return Result.ok(service.users());
    }

    @PostMapping("/users")
    @Audited("新增用户")
    public Result<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        return Result.ok(service.createUser(body));
    }

    @PutMapping("/users/{id}")
    @Audited("修改用户")
    public Result<Map<String, Object>> updateUser(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return Result.ok(service.updateUser(id, body));
    }

    /**
     * 停用账号（逻辑删除）：终端台账、采集数据全部保留，可随时重新启用。
     */
    @DeleteMapping("/users/{id}")
    @Audited("停用用户")
    public Result<Map<String, Object>> disableUser(@PathVariable long id) {
        return Result.ok(service.disableUser(id));
    }

    @GetMapping("/accounts")
    public Result<List<Map<String, Object>>> accounts() {
        return Result.ok(service.accounts());
    }

    @PostMapping("/accounts")
    @Audited("新增账号")
    public Result<Map<String, Object>> createAccount(@RequestBody Map<String, Object> body) {
        return Result.ok(service.createAccount(body));
    }

    @PutMapping("/accounts/{id}")
    @Audited("修改账号")
    public Result<Map<String, Object>> updateAccount(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return Result.ok(service.updateAccount(id, body));
    }
}
