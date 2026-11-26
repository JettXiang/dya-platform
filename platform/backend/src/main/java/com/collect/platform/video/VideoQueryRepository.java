package com.collect.platform.video;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 视频列表、统计与上传记录的数据访问层。
 */
@Repository
public class VideoQueryRepository {

    private final JdbcTemplate jdbc;

    public VideoQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int count(String where, Object[] args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM videos" + where, Integer.class, args);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> page(String where, Object[] pageArgs) {
        return jdbc.queryForList("""
                SELECT id, aweme_id, url, title, cover_url, author_sec_uid, author_nickname,
                       digg_count, comment_count, share_count, collect_count, status,
                       collected_by, terminal_code, collected_at, uploaded_at, upload_note
                FROM videos
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", pageArgs);
    }

    public List<Map<String, Object>> countByStatus(String where, Object[] args) {
        return jdbc.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM videos" + where + " GROUP BY status", args);
    }

    public List<Map<String, Object>> topCollectors(String where, Object[] args) {
        return jdbc.queryForList("""
                SELECT collected_by, COUNT(*) AS cnt, MAX(collected_at) AS last_at
                FROM videos""" + where + """
                 AND collected_by IS NOT NULL
                GROUP BY collected_by ORDER BY cnt DESC LIMIT 10
                """, args);
    }

    public List<Map<String, Object>> topTerminals(String where, Object[] args) {
        return jdbc.queryForList("""
                SELECT terminal_code, COUNT(*) AS cnt
                FROM videos""" + where + """
                 AND terminal_code IS NOT NULL
                GROUP BY terminal_code ORDER BY cnt DESC LIMIT 20
                """, args);
    }

    public int todayCount(String scope, Object[] args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM videos" + scope, Integer.class, args);
        return n == null ? 0 : n;
    }

    public Object lastCollectedAt(String where, Object[] args) {
        return jdbc.queryForObject("SELECT MAX(collected_at) FROM videos" + where, Object.class, args);
    }

    public List<Map<String, Object>> recentUploads() {
        return jdbc.queryForList("""
                SELECT id, uploader_id, operator, video_count, sec_uid_count, http_status,
                       remote_count, inserted_count, duplicate_count, success, error,
                       started_at, ended_at, duration_ms
                FROM video_uploads ORDER BY id DESC LIMIT 30
                """);
    }
}
