package com.collect.platform.batch;

import com.collect.platform.common.ConflictException;
import com.collect.platform.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次对账、作废与补传请求的业务规则。
 */
@Service
public class BatchService {

    private final BatchRepository repo;
    private final ObjectMapper mapper;

    public BatchService(BatchRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** 返回给前端的 message + data，保持与原接口一致的 JSON 结构。 */
    public record Outcome(String message, Map<String, Object> data) {}

    public List<Map<String, Object>> list(String date) {
        return repo.listByDate(date);
    }

    /** 批次不存在时返回 null，由 controller 转成 Result.fail。 */
    public Map<String, Object> detail(String batchNo) {
        Map<String, Object> batch = repo.findByNo(batchNo);
        if (batch == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batch);
        out.put("attempts", repo.attempts(batchNo));
        out.put("confirm_states", repo.confirmStates(batchNo));
        out.put("items", repo.items(batchNo));
        return out;
    }

    public Outcome reconcile(String batchNo, Map<String, Object> body) {
        String result = text(body == null ? null : body.get("result"));
        String reason = text(body == null ? null : body.get("reason"));
        String evidence = text(body == null ? null : body.get("evidence"));
        if (result == null || (!"received".equals(result) && !"not_received".equals(result))) {
            throw new IllegalArgumentException("result 必须为 received 或 not_received");
        }
        if (reason == null) {
            throw new IllegalArgumentException("对账必须填写原因");
        }
        String state = repo.findState(batchNo);
        if (state == null) {
            throw new IllegalArgumentException("批次不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchNo", batchNo);

        if ("received".equals(result)) {
            if ("success".equals(state)) {
                out.put("result", "already_success");
                out.put("state", state);
                return new Outcome("already_success", out);
            }
            if (!"unknown".equals(state) && !"failed".equals(state)) {
                throw new ConflictException("batch_not_reconcilable", "当前状态不支持对账为已接收：" + state);
            }
            int updated = repo.markReceived(batchNo, cut(reason, 255));
            if (updated == 0) {
                throw new ConflictException("batch_not_reconcilable", "状态已变化，请刷新后重试");
            }
            repo.confirmItems(batchNo, cut(evidence == null ? reason : evidence, 255));
            out.put("result", "reconciled_received");
            out.put("state", "success");
            return new Outcome("reconciled_received", out);
        }

        if ("success".equals(state) || "rolled_back".equals(state) || "cancelled".equals(state)) {
            throw new ConflictException("batch_not_reconcilable", "当前状态不支持对账为未收到：" + state);
        }
        if (!"unknown".equals(state) && !"failed".equals(state)) {
            throw new ConflictException("batch_not_reconcilable", "仅 unknown/failed 可对账为未收到：" + state);
        }
        repo.markFailed(batchNo, cut(reason, 255));
        repo.markItemsNotReceived(batchNo, cut(evidence == null ? reason : evidence, 255));
        out.put("result", "reconciled_not_received");
        out.put("state", "failed");
        return new Outcome("reconciled_not_received", out);
    }

    public Outcome voidBatch(String batchNo, Map<String, Object> body) {
        String reason = text(body == null ? null : body.get("reason"));
        String evidence = text(body == null ? null : body.get("evidence"));
        if (reason == null) {
            throw new IllegalArgumentException("作废必须填写原因");
        }
        String state = repo.findState(batchNo);
        if (state == null) {
            throw new IllegalArgumentException("批次不存在");
        }
        if ("cancelled".equals(state)) {
            return new Outcome("already_void", Map.of("batchNo", batchNo, "state", state));
        }
        // 已成功、已回滚、正在上传的批次不允许作废，避免把在途或已入账数据抹掉
        if ("success".equals(state) || "rolled_back".equals(state) || "uploading".equals(state)) {
            throw new ConflictException("batch_not_voidable", "当前状态不允许作废：" + state);
        }
        int updated = repo.voidBatch(batchNo, cut(reason, 255), cut(evidence, 2000));
        if (updated == 0) {
            throw new ConflictException("batch_not_voidable", "状态已变化，请刷新后重试");
        }
        // 成员按“未收到”处理，保留批次记录与证据，不删除任何数据
        repo.markItemsVoided(batchNo);
        return new Outcome("voided", Map.of("batchNo", batchNo, "state", "cancelled", "reason", reason));
    }

    public Outcome uploadRequest(Map<String, Object> body) {
        String reason = text(body == null ? null : body.get("reason"));
        if (reason == null) {
            throw new IllegalArgumentException("请说明补传原因");
        }
        String date = body.get("date") == null ? LocalDate.now().toString() : String.valueOf(body.get("date"));
        String key = "upload_request:" + date;
        if (repo.findConfigValue(key) != null) {
            return new Outcome("exists", Map.of("date", date, "key", key, "status", "pending"));
        }

        UserContext ctx = UserContext.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", date);
        payload.put("requested_by", ctx.getCode());
        payload.put("requested_at", LocalDateTime.now().toString());
        payload.put("reason", reason);
        payload.put("status", "pending");
        try {
            repo.insertConfigJson(key, mapper.writeValueAsString(payload), ctx.getUserId());
        } catch (DataIntegrityViolationException dup) {
            return new Outcome("exists", Map.of("date", date, "key", key, "status", "pending"));
        } catch (Exception e) {
            throw new IllegalStateException("写入补传请求失败", e);
        }
        // 只提醒，不触发任何上传动作
        repo.insertNotification(ctx.getUserId(), "收到补传请求 " + date, reason);
        return new Outcome("submitted", payload);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
