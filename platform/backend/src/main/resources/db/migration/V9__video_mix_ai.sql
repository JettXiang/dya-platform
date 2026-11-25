-- 对齐插件 v1.4.0：只采集「挂载合集 + AI 声明」双命中的 AI 短剧内容
-- videos 记录合集与 AI 判定明细，便于回溯；authors 补达人档案与首见归因。

ALTER TABLE videos
  ADD COLUMN mix_id VARCHAR(64) DEFAULT NULL AFTER author_nickname,
  ADD COLUMN mix_name VARCHAR(255) DEFAULT NULL AFTER mix_id,
  ADD COLUMN has_mix TINYINT NOT NULL DEFAULT 0 AFTER mix_name,
  ADD COLUMN has_ai TINYINT NOT NULL DEFAULT 0 AFTER has_mix,
  ADD COLUMN aigc_label_type INT DEFAULT NULL AFTER has_ai,
  ADD COLUMN aweme_create_time DATETIME(3) DEFAULT NULL AFTER aigc_label_type,
  ADD COLUMN aweme_type INT DEFAULT NULL AFTER aweme_create_time,
  ADD KEY idx_videos_mix_ai (has_mix, has_ai);

ALTER TABLE authors
  ADD COLUMN nickname VARCHAR(128) DEFAULT NULL,
  ADD COLUMN unique_id VARCHAR(128) DEFAULT NULL,
  ADD COLUMN avatar_url VARCHAR(1024) DEFAULT NULL,
  ADD COLUMN signature VARCHAR(512) DEFAULT NULL,
  ADD COLUMN first_seen_collector VARCHAR(32) DEFAULT NULL,
  ADD COLUMN first_seen_terminal VARCHAR(64) DEFAULT NULL,
  ADD COLUMN video_count INT NOT NULL DEFAULT 0,
  ADD COLUMN mix_video_count INT NOT NULL DEFAULT 0,
  ADD COLUMN last_video_at DATETIME(3) DEFAULT NULL,
  ADD KEY idx_authors_collector (first_seen_collector);
