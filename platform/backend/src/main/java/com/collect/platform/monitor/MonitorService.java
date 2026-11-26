package com.collect.platform.monitor;

import com.collect.platform.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonitorService {

    private final MonitorRepository repo;

    public MonitorService(MonitorRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> timeline(String actor, String action, String from, String to,
                                        Boolean success, int page, int size) {
        UserContext ctx = UserContext.get();
        String effectiveActor = actor;
        if (ctx != null && "collector".equalsIgnoreCase(ctx.getRole())) {
            effectiveActor = ctx.getCode();
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (Math.max(page, 1) - 1) * safeSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (effectiveActor != null && !effectiveActor.isBlank()) {
            where.append(" AND operator = ?");
            args.add(effectiveActor);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND action = ?");
            args.add(action);
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND created_at >= ?");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND created_at <= ?");
            args.add(to);
        }
        if (success != null) {
            where.append(" AND success = ?");
            args.add(success ? 1 : 0);
        }

        int total = repo.countAuditLogs(where.toString(), args.toArray());
        args.add(safeSize);
        args.add(offset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", safeSize);
        out.put("items", repo.listAuditLogs(where.toString(), args.toArray()));
        return out;
    }

    /** 批次链路，批次不存在返回 null。 */
    public Map<String, Object> chain(String batchNo) {
        Map<String, Object> batch = repo.findBatch(batchNo);
        if (batch == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batch);
        out.put("attempts", repo.attempts(batchNo));
        out.put("confirm_states", repo.confirmStates(batchNo));
        out.put("operations", repo.operations(batchNo));
        return out;
    }

    public Map<String, Object> anomalies(String date, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (type.equals("all") || type.equals("unknown")) {
            out.put("unknown_batches", repo.unknownBatches(date));
        }
        if (type.equals("all") || type.equals("timeout")) {
            out.put("timeout_attempts", repo.timeoutAttempts(date));
        }
        if (type.equals("all") || type.equals("alert_unread")) {
            out.put("unread_alerts", repo.unreadAlerts(date + " 00:00:00"));
        }
        if (type.equals("all") || type.equals("offline")) {
            out.put("offline_accounts", repo.offlineAccounts());
        }
        return out;
    }
}
