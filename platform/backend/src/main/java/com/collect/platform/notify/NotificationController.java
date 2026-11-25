package com.collect.platform.notify;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        var ctx = UserContext.get();
        return Result.ok(jdbc.queryForList("""
                SELECT n.id, n.kind, n.title, n.body, n.created_at, n.require_next_day,
                       (SELECT COUNT(*) FROM notification_reads r WHERE r.notification_id = n.id AND r.user_id = ?) AS read_flag
                FROM notifications n
                WHERE n.scope = 'all' OR n.target_user_id = ?
                ORDER BY n.id DESC LIMIT 50
                """, ctx.getUserId(), ctx.getUserId()));
    }

    @PostMapping("/{id}/read")
    @Audited("通知已读")
    public Result<Void> read(@PathVariable long id) {
        var ctx = UserContext.get();
        jdbc.update("INSERT IGNORE INTO notification_reads (notification_id, user_id) VALUES (?,?)",
                id, ctx.getUserId());
        return Result.ok(null);
    }

    @PostMapping
    @Audited("发送通知")
    public Result<Void> create(@RequestBody Map<String, Object> body) {
        var ctx = UserContext.get();
        long target = body.get("targetUserId") == null ? 0 : ((Number) body.get("targetUserId")).longValue();
        jdbc.update("""
                INSERT INTO notifications (sender_id, scope, target_user_id, kind, title, body, require_next_day)
                VALUES (?,?,?,?,?,?,?)
                """,
                ctx.getUserId(),
                target == 0 ? "all" : "one",
                target == 0 ? null : target,
                String.valueOf(body.getOrDefault("kind", "daily")),
                String.valueOf(body.getOrDefault("title", "通知")),
                String.valueOf(body.getOrDefault("body", "")),
                Boolean.TRUE.equals(body.get("requireNextDay")) ? 1 : 0);
        return Result.ok(null);
    }
}
