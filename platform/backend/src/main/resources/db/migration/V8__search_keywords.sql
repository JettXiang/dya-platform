-- 搜索采集词库 + 视频关键词归属
CREATE TABLE IF NOT EXISTS search_keywords (
  id BIGINT NOT NULL AUTO_INCREMENT,
  keyword VARCHAR(128) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'auto',
  created_by VARCHAR(32) DEFAULT NULL,
  hit_count INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  first_used_at DATETIME(3) DEFAULT NULL,
  last_used_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_search_keywords (keyword),
  KEY idx_search_keywords_enabled (enabled, last_used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE videos ADD COLUMN search_keyword VARCHAR(128) DEFAULT NULL AFTER terminal_code;
ALTER TABLE videos ADD KEY idx_videos_keyword (search_keyword, collected_at);

-- 历史词回灌：扩展链路上报的 runs.keyword
INSERT IGNORE INTO search_keywords (keyword, source, hit_count, first_used_at, last_used_at)
SELECT TRIM(keyword), 'auto', COUNT(*), MIN(started_at_client), MAX(started_at_client)
FROM runs
WHERE keyword IS NOT NULL AND TRIM(keyword) <> ''
GROUP BY TRIM(keyword);
