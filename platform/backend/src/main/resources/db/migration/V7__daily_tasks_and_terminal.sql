-- 采集终端归属到入库明细 + 每日采集任务
ALTER TABLE videos ADD COLUMN terminal_code VARCHAR(32) DEFAULT NULL AFTER collected_by;
ALTER TABLE videos ADD KEY idx_videos_terminal (terminal_code, collected_at);

CREATE TABLE IF NOT EXISTS daily_tasks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_date DATE NOT NULL,
  collector_code VARCHAR(32) NOT NULL,
  target_count INT NOT NULL DEFAULT 0,
  note VARCHAR(255) DEFAULT NULL,
  created_by VARCHAR(32) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_daily_tasks (task_date, collector_code),
  KEY idx_daily_tasks_date (task_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
