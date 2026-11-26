package com.collect.platform.search;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 搜索词库与「新词审批」的数据访问层，只负责 SQL，不处理业务规则。
 */
@Repository
public class SearchKeywordRepository {

    private final JdbcTemplate jdbc;

    public SearchKeywordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 采集端搜索时自动记词：命中已有词则累加使用次数。 */
    public void recordUse(String keyword, String code) {
        jdbc.update("""
                INSERT INTO search_keywords (keyword, source, created_by, hit_count, first_used_at, last_used_at)
                VALUES (?, 'auto', ?, 1, NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE
                  hit_count = hit_count + 1,
                  last_used_at = NOW(3),
                  created_by = COALESCE(created_by, VALUES(created_by))
                """, keyword, code);
    }

    /** 随机取 size 个启用中的词，并带近 7 天去重产出量。 */
    public List<Map<String, Object>> randomKeywords(int size) {
        return jdbc.queryForList("""
                SELECT k.keyword,
                       COALESCE(v.cnt, 0) AS produced
                FROM (SELECT keyword FROM search_keywords WHERE enabled = 1 ORDER BY RAND() LIMIT ?) k
                LEFT JOIN (
                    SELECT search_keyword, COUNT(DISTINCT aweme_id) AS cnt
                    FROM videos
                    WHERE search_keyword IS NOT NULL
                      AND collected_at >= DATE_SUB(NOW(3), INTERVAL 7 DAY)
                    GROUP BY search_keyword
                ) v ON v.search_keyword = k.keyword
                """, size);
    }

    /** 词库列表：带累计采集量与最近采集时间。 */
    public List<Map<String, Object>> listWithCollected() {
        return jdbc.queryForList("""
                SELECT k.id, k.keyword, k.source, k.created_by, k.enabled, k.hit_count,
                       k.first_used_at, k.last_used_at,
                       COALESCE(v.collected_total, 0) AS collected_total,
                       v.last_collected_at
                FROM search_keywords k
                LEFT JOIN (
                    SELECT search_keyword, COUNT(*) AS collected_total, MAX(collected_at) AS last_collected_at
                    FROM videos WHERE search_keyword IS NOT NULL
                    GROUP BY search_keyword
                ) v ON v.search_keyword = k.keyword
                ORDER BY k.last_used_at DESC, k.id DESC
                LIMIT 500
                """);
    }

    public boolean existsKeyword(String keyword) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_keywords WHERE keyword = ?", Integer.class, keyword);
        return n != null && n > 0;
    }

    /** 手工新增词，重复时抛出 DataIntegrityViolationException，由 service 转成业务错误。 */
    public void insertManual(String keyword, String code) {
        jdbc.update("""
                INSERT INTO search_keywords (keyword, source, created_by, hit_count, enabled, created_at)
                VALUES (?, 'manual', ?, 0, 1, NOW(3))
                """, keyword, code);
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM search_keywords WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateKeyword(long id, String keyword, int enabled) {
        jdbc.update("UPDATE search_keywords SET keyword = ?, enabled = ? WHERE id = ?", keyword, enabled, id);
    }

    public void deleteKeyword(long id) {
        jdbc.update("DELETE FROM search_keywords WHERE id = ?", id);
    }

    public boolean hasPendingSuggestion(String keyword) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM keyword_suggestions WHERE keyword = ? AND status = 'pending'", Integer.class, keyword);
        return n != null && n > 0;
    }

    public void insertSuggestion(String keyword, String code) {
        jdbc.update("""
                INSERT INTO keyword_suggestions (keyword, status, submitted_by, submitted_at)
                VALUES (?, 'pending', ?, NOW(3))
                """, keyword, code);
    }

    /** status 为空表示不过滤；submittedBy 为空表示管理员看全部。 */
    public List<Map<String, Object>> listSuggestions(String status, String submittedBy) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, keyword, status, submitted_by, submitted_at, reviewed_by, reviewed_at, reason
                FROM keyword_suggestions WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        if (submittedBy != null) {
            sql.append(" AND submitted_by = ?");
            args.add(submittedBy);
        }
        sql.append(" ORDER BY FIELD(status, 'pending', 'approved', 'rejected'), submitted_at DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> findSuggestion(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, keyword, status FROM keyword_suggestions WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 审批通过：写入词库，已存在则直接重新启用。 */
    public void upsertApproved(String keyword, String code) {
        jdbc.update("""
                INSERT INTO search_keywords (keyword, source, created_by, hit_count, enabled, created_at)
                VALUES (?, 'manual', ?, 0, 1, NOW(3))
                ON DUPLICATE KEY UPDATE enabled = 1
                """, keyword, code);
    }

    public void reviewSuggestion(long id, String status, String code, String reason) {
        jdbc.update("""
                UPDATE keyword_suggestions
                SET status = ?, reviewed_by = ?, reviewed_at = NOW(3), reason = ?
                WHERE id = ?
                """, status, code, reason, id);
    }
}
