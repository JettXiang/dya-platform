package com.collect.platform.search;

import com.collect.platform.common.ConflictException;
import com.collect.platform.security.UserContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索词库业务：自动记词、随机取词、维护、新词提交与审批。
 */
@Service
public class SearchKeywordService {

    /** 词库随机批的有效期：5 分钟，每人独立计算 */
    public static final long PICK_TTL_MS = 5 * 60 * 1000L;
    public static final int MAX_KEYWORD_LEN = 64;

    private final SearchKeywordRepository repo;
    private final Map<Long, Pick> picks = new ConcurrentHashMap<>();

    public SearchKeywordService(SearchKeywordRepository repo) {
        this.repo = repo;
    }

    private record Pick(List<Map<String, Object>> items, long refreshAt) {}

    /* ================= 自动记词 ================= */

    public Map<String, Object> record(Map<String, Object> body) {
        String keyword = text(body.get("keyword"));
        if (keyword == null || keyword.length() > MAX_KEYWORD_LEN) {
            throw new IllegalArgumentException("关键词必填且不超过 64 个字符");
        }
        UserContext me = UserContext.get();
        repo.recordUse(keyword, me == null ? null : me.getCode());
        return Map.of("keyword", keyword);
    }

    /* ================= 随机词库（每 5 分钟一批，带近 7 天产出量） ================= */

    public Map<String, Object> randomKeywords(int n) {
        UserContext me = UserContext.get();
        long uid = me == null || me.getUserId() == null ? 0L : me.getUserId();
        int size = Math.min(Math.max(n, 1), 60);
        long now = System.currentTimeMillis();
        Pick pick = picks.get(uid);
        if (pick == null || pick.refreshAt() <= now || pick.items().size() != size) {
            pick = new Pick(repo.randomKeywords(size), now + PICK_TTL_MS);
            picks.put(uid, pick);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", pick.items());
        out.put("refreshAt", pick.refreshAt());
        out.put("serverNow", now);
        return out;
    }

    /* ================= 词库维护（管理员） ================= */

    public List<Map<String, Object>> list() {
        return repo.listWithCollected();
    }

    public Map<String, Object> add(Map<String, Object> body) {
        String keyword = text(body.get("keyword"));
        if (keyword == null || keyword.length() > MAX_KEYWORD_LEN) {
            throw new IllegalArgumentException("关键词必填且不超过 64 个字符");
        }
        UserContext me = UserContext.get();
        try {
            repo.insertManual(keyword, me == null ? null : me.getCode());
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("keyword_exists", "词库里已有：" + keyword);
        }
        return Map.of("keyword", keyword);
    }

    public Map<String, Object> update(long id, Map<String, Object> body) {
        Map<String, Object> cur = repo.findById(id);
        if (cur == null) {
            throw new IllegalArgumentException("关键词不存在");
        }
        String keyword = text(body.get("keyword"));
        String target = keyword != null ? keyword : String.valueOf(cur.get("keyword"));
        if (target.length() > MAX_KEYWORD_LEN) {
            throw new IllegalArgumentException("关键词不超过 64 个字符");
        }
        int enabled = body.get("enabled") == null
                ? ((Number) cur.get("enabled")).intValue()
                : (Boolean.TRUE.equals(body.get("enabled")) || "1".equals(String.valueOf(body.get("enabled"))) ? 1 : 0);
        try {
            repo.updateKeyword(id, target, enabled);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("keyword_exists", "词库里已有：" + target);
        }
        picks.clear();
        return Map.of("id", id, "keyword", target, "enabled", enabled);
    }

    public void delete(long id) {
        repo.deleteKeyword(id);
        picks.clear();
    }

    /* ================= 新词提交与审批 ================= */

    /** 采集员提交新搜索词，先进待审批，管理员通过后才进词库。 */
    public Map<String, Object> suggest(Map<String, Object> body) {
        String keyword = text(body.get("keyword"));
        if (keyword == null || keyword.length() > MAX_KEYWORD_LEN) {
            throw new IllegalArgumentException("关键词必填且不超过 64 个字符");
        }
        if (repo.existsKeyword(keyword)) {
            throw new ConflictException("keyword_exists", "词库里已有：" + keyword);
        }
        if (repo.hasPendingSuggestion(keyword)) {
            throw new ConflictException("keyword_pending", "这个词已经在等审批：" + keyword);
        }
        UserContext me = UserContext.get();
        repo.insertSuggestion(keyword, me == null ? null : me.getCode());
        return Map.of("keyword", keyword, "status", "pending");
    }

    /** 待审批列表：管理员看全部，采集员只看自己提交的。 */
    public List<Map<String, Object>> listSuggestions(String status) {
        UserContext me = UserContext.get();
        boolean admin = me != null && "admin".equalsIgnoreCase(me.getRole());
        String submittedBy = admin ? null : (me == null ? "" : me.getCode());
        return repo.listSuggestions(status, submittedBy);
    }

    /** 审批：通过则自动写进词库，驳回需填原因。 */
    public Map<String, Object> review(long id, Map<String, Object> body) {
        UserContext me = UserContext.get();
        if (me == null || !"admin".equalsIgnoreCase(me.getRole())) {
            throw new IllegalArgumentException("只有管理员可以审批");
        }
        Map<String, Object> cur = repo.findSuggestion(id);
        if (cur == null) {
            throw new IllegalArgumentException("提交记录不存在");
        }
        if (!"pending".equals(String.valueOf(cur.get("status")))) {
            throw new IllegalArgumentException("这条已经处理过了");
        }
        boolean approve = Boolean.TRUE.equals(body.get("approve"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("approve")));
        String keyword = String.valueOf(cur.get("keyword"));
        String reason = text(body.get("reason"));
        if (approve) {
            repo.upsertApproved(keyword, me.getCode());
            picks.clear();
        }
        repo.reviewSuggestion(id, approve ? "approved" : "rejected", me.getCode(), reason);
        return Map.of("id", id, "keyword", keyword, "status", approve ? "approved" : "rejected");
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
