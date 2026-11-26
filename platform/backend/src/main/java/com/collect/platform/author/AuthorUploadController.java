package com.collect.platform.video;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇总上传：按作者 sec_uid 出待推清单、手动推送、查台账。
 * 去重口径是作者级永久台账（author_uploads），推成功过的作者不再出现。
 */
@RestController
@RequestMapping("/api/v1/author-uploads")
public class AuthorUploadController {

    private final AuthorPushService service;

    public AuthorUploadController(AuthorPushService service) {
        this.service = service;
    }

    /** 待推作者。不传 from/to 默认今天 00:00 ~ 明天 00:00 */
    @GetMapping("/pending")
    public Result<List<Map<String, Object>>> pending(@RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to,
                                                     @RequestParam(required = false) String collector,
                                                     @RequestParam(defaultValue = "500") int limit) {
        String[] range = range(from, to);
        return Result.ok(service.pending(range[0], range[1], collector, limit));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String collector) {
        String[] range = range(from, to);
        return Result.ok(service.stats(range[0], range[1], collector));
    }

    @PostMapping("/push")
    @Audited("汇总推送作者")
    public Result<Map<String, Object>> push(@RequestBody Map<String, Object> body) {
        List<String> secUids = new ArrayList<>();
        Object raw = body.get("secUids");
        if (raw instanceof List<?> list) {
            for (Object v : list) {
                if (v != null) {
                    secUids.add(String.valueOf(v));
                }
            }
        }
        Map<String, Object> data;
        try {
            data = service.push(secUids);
        } catch (IllegalArgumentException ex) {
            return Result.fail(ex.getMessage());
        }
        if (Boolean.TRUE.equals(data.get("success"))) {
            return Result.ok(data);
        }
        return new Result<>(false, String.valueOf(data.get("message")), data);
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(service.history(limit));
    }

    /** 只有日期时补成 [当天 00:00, 次日 00:00)；带时间原样用 */
    private String[] range(String from, String to) {
        String start = (from == null || from.isBlank())
                ? LocalDate.now().atStartOfDay().toString().replace('T', ' ')
                : from.trim();
        String end = (to == null || to.isBlank())
                ? LocalDate.now().plusDays(1).atStartOfDay().toString().replace('T', ' ')
                : to.trim();
        if (start.length() == 10) {
            start = start + " 00:00:00";
        }
        if (end.length() == 10) {
            end = end + " 00:00:00";
        }
        return new String[]{start, end};
    }
}
