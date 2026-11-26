package com.collect.platform.author;

import com.collect.platform.common.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 短剧达人：只看「合集 + AI 声明」双命中的作品聚合出来的创作者。
 * 首见归因取 authors.first_seen_collector / first_seen_terminal。
 */
@RestController
@RequestMapping("/api/v1/authors")
public class AuthorDramaController {

    private final AuthorService service;

    public AuthorDramaController(AuthorService service) {
        this.service = service;
    }

    @GetMapping("/ai-drama")
    public Result<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String collector,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.list(keyword, collector, page, size));
    }

    @GetMapping("/ai-drama/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String collector) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-drama-authors.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(service.exportCsv(keyword, collector));
    }
}
