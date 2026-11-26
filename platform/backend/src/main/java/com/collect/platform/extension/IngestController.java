package com.collect.platform.ingest;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import com.collect.platform.ingest.dto.IngestDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/ingest/chunk")
    @Audited("分片上报")
    public Result<Map<String, Object>> chunk(@Valid @RequestBody IngestDtos.ChunkRequest req) {
        return Result.ok(ingestService.chunk(req));
    }

    @PostMapping("/ingest/day-final")
    @Audited("日终上报")
    public Result<Map<String, Object>> dayFinal(@Valid @RequestBody IngestDtos.DayFinalRequest req) {
        return Result.ok(ingestService.dayFinal(req));
    }

    @PostMapping("/ingest/heartbeat")
    @Audited("心跳")
    public Result<Void> heartbeat(@Valid @RequestBody IngestDtos.HeartbeatRequest req) {
        ingestService.heartbeat(req);
        return Result.ok(null);
    }

    @PostMapping("/ingest/run-void")
    @Audited("作废run")
    public Result<Map<String, Object>> runVoid(@Valid @RequestBody IngestDtos.RunVoidRequest req) {
        return Result.ok(ingestService.runVoid(req));
    }

    @GetMapping("/config/extension")
    public Result<Map<String, Object>> extensionConfig() {
        return Result.ok(Map.of(
                "heartbeat_sec", 90,
                "chunk_max_items", 50,
                "retry_hint", "本地保留未 ACK 分片与 day_final，禁止静默清除"));
    }
}
