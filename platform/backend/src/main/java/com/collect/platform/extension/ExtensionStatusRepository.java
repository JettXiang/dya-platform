package com.collect.platform.extension;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * 扩展状态相关的数据访问层。
 */
@Repository
public class ExtensionStatusRepository {

    private final JdbcTemplate jdbc;

    public ExtensionStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 今日该采集人入库条数，供前端展示“确实在涨”的证据。 */
    public long todayInserted(String collector) {
        Timestamp start = Timestamp.valueOf(LocalDate.now().atStartOfDay());
        Long count = jdbc.queryForObject(
                "select count(*) from videos where collected_by = ? and collected_at >= ?",
                Long.class, collector, start);
        return count == null ? 0L : count;
    }
}
