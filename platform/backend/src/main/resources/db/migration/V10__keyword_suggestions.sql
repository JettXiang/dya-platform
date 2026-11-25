-- 采集员提交的新搜索词，需管理员在平台上审批通过后才进词库
CREATE TABLE IF NOT EXISTS keyword_suggestions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  keyword VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending / approved / rejected',
  submitted_by VARCHAR(32) DEFAULT NULL,
  submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_by VARCHAR(32) DEFAULT NULL,
  reviewed_at DATETIME(3) DEFAULT NULL,
  reason VARCHAR(255) DEFAULT NULL,
  KEY idx_ks_status (status),
  KEY idx_ks_keyword (keyword),
  KEY idx_ks_submitter (submitted_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新搜索词审批记录';
