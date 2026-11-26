package com.collect.platform.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 关键词周分析的数据访问层。
 */
@Repository
public class KeywordAnalysisRepository {

    private final JdbcTemplate jdbc;

    public KeywordAnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listLibrary() {
        return jdbc.queryForList("""
                SELECT id, keyword, source, enabled, hit_count, last_used_at
                FROM search_keywords ORDER BY hit_count DESC, id DESC
                """);
    }

    /** 按词统计区间内的采集量、作者数、采集人数、已上传数与最近采集时间。 */
    public List<Map<String, Object>> weekStats(Timestamp from, Timestamp to) {
        return jdbc.queryForList("""
                SELECT CASE WHEN k.keyword IS NULL THEN v.search_keyword ELSE k.keyword END AS keyword,
                       COUNT(DISTINCT v.aweme_id) AS collected,
                       COUNT(DISTINCT v.author_sec_uid) AS authors,
                       COUNT(DISTINCT v.collected_by) AS collectors,
                       COUNT(DISTINCT CASE WHEN v.status = 'uploaded' THEN v.aweme_id END) AS uploaded,
                       MAX(v.collected_at) AS last_at
                FROM videos v
                LEFT JOIN search_keywords k ON k.keyword = v.search_keyword
                WHERE v.search_keyword IS NOT NULL
                  AND v.collected_at >= ? AND v.collected_at < ?
                GROUP BY CASE WHEN k.keyword IS NULL THEN v.search_keyword ELSE k.keyword END
                """, from, to);
    }

    public long countAuthors(Timestamp from, Timestamp to) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT author_sec_uid) FROM videos
                WHERE search_keyword IS NOT NULL AND author_sec_uid IS NOT NULL
                  AND collected_at >= ? AND collected_at < ?
                """, Long.class, from, to);
        return n == null ? 0 : n;
    }

    public long countUploaded(Timestamp from, Timestamp to) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM videos
                WHERE search_keyword IS NOT NULL AND status = 'uploaded'
                  AND collected_at >= ? AND collected_at < ?
                """, Long.class, from, to);
        return n == null ? 0 : n;
    }

    public long countMultiKeywordAuthors(Timestamp from, Timestamp to) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT author_sec_uid FROM videos
                    WHERE search_keyword IS NOT NULL AND author_sec_uid IS NOT NULL
                      AND collected_at >= ? AND collected_at < ?
                    GROUP BY author_sec_uid
                    HAVING COUNT(DISTINCT search_keyword) > 1
                ) t
                """, Long.class, from, to);
        return n == null ? 0 : n;
    }

    /** 同一作者在本周被多个关键词采到的 TOP，用来判断词库是否重复。 */
    public List<Map<String, Object>> overlapAuthors(Timestamp from, Timestamp to) {
        return jdbc.queryForList("""
                SELECT v.author_sec_uid, MAX(v.author_nickname) AS nickname,
                       GROUP_CONCAT(DISTINCT v.search_keyword ORDER BY v.search_keyword) AS keywords,
                       COUNT(*) AS videos
                FROM videos v
                WHERE v.search_keyword IS NOT NULL AND v.author_sec_uid IS NOT NULL
                  AND v.collected_at >= ? AND v.collected_at < ?
                GROUP BY v.author_sec_uid
                HAVING COUNT(DISTINCT v.search_keyword) > 1
                ORDER BY videos DESC
                LIMIT 10
                """, from, to);
    }
}
