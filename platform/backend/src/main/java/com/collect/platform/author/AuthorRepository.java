package com.collect.platform.author;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * AI 短剧达人聚合查询的数据访问层。
 */
@Repository
public class AuthorRepository {

    /** 合集 + AI 声明双命中作品的创作者汇总口径 */
    public static final String BASE_SQL = """
            SELECT a.sec_uid, a.nickname, a.unique_id, a.avatar_url,
                   a.first_collected_at, a.first_seen_collector, a.first_seen_terminal,
                   a.video_count, a.mix_video_count, a.last_video_at,
                   s.works, s.mixes, s.digg_total
            FROM authors a
            JOIN (
              SELECT author_sec_uid, COUNT(*) AS works, COUNT(DISTINCT mix_id) AS mixes,
                     COALESCE(SUM(digg_count), 0) AS digg_total
              FROM videos
              WHERE has_ai = 1 AND author_sec_uid IS NOT NULL
              GROUP BY author_sec_uid
            ) s ON s.author_sec_uid = a.sec_uid
            WHERE 1=1
            """;

    private final JdbcTemplate jdbc;

    public AuthorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int count(String where, Object[] args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM (" + BASE_SQL + where + ") t",
                Integer.class, args);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> page(String where, Object[] pageArgs) {
        return jdbc.queryForList(BASE_SQL + where
                + " ORDER BY s.mixes DESC, s.works DESC, a.last_video_at DESC LIMIT ? OFFSET ?", pageArgs);
    }

    public Map<String, Object> summary(String where, Object[] args) {
        return jdbc.queryForMap("""
                SELECT COUNT(*) AS authors,
                       COALESCE(SUM(s.works), 0) AS works,
                       COALESCE(SUM(s.mixes), 0) AS mixes
                FROM authors a
                JOIN (
                  SELECT author_sec_uid, COUNT(*) AS works, COUNT(DISTINCT mix_id) AS mixes
                  FROM videos
                  WHERE has_ai = 1 AND author_sec_uid IS NOT NULL
                  GROUP BY author_sec_uid
                ) s ON s.author_sec_uid = a.sec_uid
                WHERE 1=1
                """ + where, args);
    }

    public List<Map<String, Object>> exportRows(String where, Object[] args) {
        return jdbc.queryForList(BASE_SQL + where
                + " ORDER BY s.mixes DESC, s.works DESC, a.last_video_at DESC LIMIT 5000", args);
    }
}
