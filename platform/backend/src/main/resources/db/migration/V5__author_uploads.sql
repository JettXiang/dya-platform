-- 作者级永久推送台账：同一 sec_uid 成功推到上游后不再重复推
CREATE TABLE IF NOT EXISTS author_uploads (
  author_sec_uid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  success TINYINT NOT NULL DEFAULT 0,
  push_count INT NOT NULL DEFAULT 0,
  first_pushed_at DATETIME(3) DEFAULT NULL,
  last_pushed_at DATETIME(3) DEFAULT NULL,
  uploader_id VARCHAR(64) DEFAULT NULL,
  http_status INT DEFAULT NULL,
  remote_inserted INT DEFAULT NULL,
  remote_duplicate INT DEFAULT NULL,
  last_result VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (author_sec_uid),
  KEY idx_author_uploads_pushed (last_pushed_at),
  KEY idx_author_uploads_success (success)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
