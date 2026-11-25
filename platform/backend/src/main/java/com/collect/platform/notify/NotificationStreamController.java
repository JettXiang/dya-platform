package com.collect.platform.notify;

import com.collect.platform.security.UserContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class NotificationStreamController {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final JdbcTemplate jdbc;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "notification-sse");
        t.setDaemon(true);
        return t;
    });

    public NotificationStreamController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 站内通知推送。浏览器 EventSource 不能带 Authorization 头，
     * 前端请用 fetch + ReadableStream 读取，或由网关注入令牌。
     */
    @GetMapping(value = {"/api/v1/notifications/stream", "/api/v1/events"},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        var ctx = UserContext.get();
        long uid = ctx.getUserId();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicLong lastId = new AtomicLong(maxId(uid));
        AtomicBoolean closed = new AtomicBoolean(false);

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT n.id, n.kind, n.title, n.body, n.created_at
                        FROM notifications n
                        WHERE (n.scope = 'all' OR n.target_user_id = ?)
                          AND n.id > ?
                          AND NOT EXISTS (SELECT 1 FROM notification_reads r
                                          WHERE r.notification_id = n.id AND r.user_id = ?)
                        ORDER BY n.id LIMIT 20
                        """, uid, lastId.get(), uid);
                for (Map<String, Object> row : rows) {
                    long id = ((Number) row.get("id")).longValue();
                    emitter.send(SseEmitter.event().id(String.valueOf(id))
                            .name("notification").data(row));
                    lastId.set(id);
                }
                // 心跳，避免中间层按空闲断开
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception ex) {
                closed.set(true);
                emitter.completeWithError(ex);
            }
        }, 0, 5, TimeUnit.SECONDS);

        emitter.onCompletion(() -> stop(closed, task));
        emitter.onTimeout(() -> stop(closed, task));
        emitter.onError(ex -> stop(closed, task));
        return emitter;
    }

    private long maxId(long uid) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(MAX(n.id), 0) FROM notifications n
                WHERE n.scope = 'all' OR n.target_user_id = ?
                """, Long.class, uid);
        return v == null ? 0 : v;
    }

    private void stop(AtomicBoolean closed, ScheduledFuture<?> task) {
        closed.set(true);
        task.cancel(true);
    }
}
