package com.collect.platform.extension;

import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 扩展运行状态。扩展通过平台密钥每 30 秒上报一次心跳（页面地址、采集/自动下滑开关、
 * 本轮作者数、已扫视频数），前端「抖音采集」页据此在抖音窗口最小化或挂后台时也能监控。
 */
@RestController
@RequestMapping("/api/v1/collect")
public class ExtensionStatusController {

    private final ExtensionStatusService service;

    public ExtensionStatusController(ExtensionStatusService service) {
        this.service = service;
    }

    @PostMapping("/heartbeat")
    public Result<Map<String, Object>> heartbeat(
            @RequestHeader(value = "X-Collect-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        return service.heartbeat(key, body);
    }

    @GetMapping("/status")
    public Result<List<Map<String, Object>>> status() {
        return Result.ok(service.status());
    }
}
