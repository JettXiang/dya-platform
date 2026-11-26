package com.collect.platform.video;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {

    private final VideoService service;

    public VideoController(VideoService service) {
        this.service = service;
    }

    /**
     * 浏览器直连 CDP 采集到的 aweme 明细入库。
     * 采集人取自登录态（JWT），不信任前端传值。
     */
    @PostMapping("/ingest")
    public Result<Map<String, Object>> ingest(@RequestBody Map<String, Object> body) {
        return Result.ok(service.ingest(body));
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String collector,
                                            @RequestParam(required = false) String terminal,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.list(status, keyword, collector, terminal, from, to, page, size));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String collector,
                                             @RequestParam(required = false) String terminal) {
        return Result.ok(service.stats(from, to, collector, terminal));
    }

    @PostMapping("/upload")
    @Audited("上传视频作者")
    public Result<Map<String, Object>> upload(@RequestBody Map<String, Object> body) {
        boolean all = Boolean.TRUE.equals(body.get("all"))
                || "true".equals(String.valueOf(body.get("all")));
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object v : list) {
                if (v instanceof Number n) {
                    ids.add(n.longValue());
                } else if (v != null) {
                    ids.add(Long.parseLong(String.valueOf(v)));
                }
            }
        }
        Map<String, Object> data = service.upload(ids, all);
        if (Boolean.TRUE.equals(data.get("success"))) {
            return Result.ok(data);
        }
        return new Result<>(false, String.valueOf(data.get("message")), data);
    }

    @GetMapping("/uploads")
    public Result<List<Map<String, Object>>> uploads() {
        return Result.ok(service.uploads());
    }
}
