package com.collect.platform.notify;

import com.collect.platform.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class NotificationService {

    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    public List<Map<String, Object>> list() {
        UserContext ctx = UserContext.get();
        return repo.listFor(ctx.getUserId());
    }

    public void read(long id) {
        repo.markRead(id, UserContext.get().getUserId());
    }

    public void create(Map<String, Object> body) {
        UserContext ctx = UserContext.get();
        long target = body.get("targetUserId") == null ? 0 : ((Number) body.get("targetUserId")).longValue();
        repo.insert(ctx.getUserId(),
                target == 0 ? "all" : "one",
                target == 0 ? null : target,
                String.valueOf(body.getOrDefault("kind", "daily")),
                String.valueOf(body.getOrDefault("title", "通知")),
                String.valueOf(body.getOrDefault("body", "")),
                Boolean.TRUE.equals(body.get("requireNextDay")) ? 1 : 0);
    }
}
