package com.collect.platform.batch;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam String date) {
        return Result.ok(service.list(date));
    }

    @GetMapping("/{batchNo}")
    public Result<Map<String, Object>> detail(@PathVariable String batchNo) {
        Map<String, Object> out = service.detail(batchNo);
        return out == null ? Result.fail("批次不存在") : Result.ok(out);
    }

    @PostMapping("/{batchNo}/reconcile")
    @Audited("批次对账")
    public Result<Map<String, Object>> reconcile(@PathVariable String batchNo,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        BatchService.Outcome outcome = service.reconcile(batchNo, body);
        return Result.ok(outcome.message(), outcome.data());
    }

    @PostMapping("/{batchNo}/void")
    @Audited("批次作废")
    public Result<Map<String, Object>> voidBatch(@PathVariable String batchNo,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        BatchService.Outcome outcome = service.voidBatch(batchNo, body);
        return Result.ok(outcome.message(), outcome.data());
    }

    @PostMapping("/upload-request")
    @Audited("请求补传")
    public Result<Map<String, Object>> uploadRequest(@RequestBody(required = false) Map<String, Object> body) {
        BatchService.Outcome outcome = service.uploadRequest(body);
        return Result.ok(outcome.message(), outcome.data());
    }
}
