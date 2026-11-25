-- 采集平台 · 初始结构（15 张表）
-- 约定：时间 UTC DATETIME(3)；sec_uid 相关列显式 utf8mb4_bin；历史台账 ON DELETE RESTRICT

CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code CHAR(8) NOT NULL,
  name VARCHAR(64) NOT NULL,
  role ENUM('admin','uploader','reviewer','collector') NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  token_version INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE accounts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL,
  user_id BIGINT NOT NULL,
  machine_label VARCHAR(64) DEFAULT NULL,
  last_heartbeat_at DATETIME(3) DEFAULT NULL,
  state ENUM('idle','collecting','stopped','offline') NOT NULL DEFAULT 'idle',
  PRIMARY KEY (id),
  UNIQUE KEY uk_accounts_code (code),
  KEY idx_accounts_user (user_id),
  CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE schedules (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  `date` DATE NOT NULL,
  status ENUM('work','leave','vacation') NOT NULL,
  note VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedules_account_date (account_id, `date`),
  CONSTRAINT fk_schedules_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE runs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  client_run_id VARCHAR(64) NOT NULL,
  `date` DATE NOT NULL,
  keyword VARCHAR(128) DEFAULT NULL,
  started_at_client DATETIME(3) DEFAULT NULL,
  ended_at_client DATETIME(3) DEFAULT NULL,
  final_received_at DATETIME(3) DEFAULT NULL,
  final_seq INT DEFAULT NULL,
  total_count_client INT NOT NULL DEFAULT 0,
  received_count INT NOT NULL DEFAULT 0,
  contiguous_seq INT NOT NULL DEFAULT 0,
  clock_skew_flag TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_runs_account_client (account_id, client_run_id),
  KEY idx_runs_date (account_id, `date`),
  CONSTRAINT fk_runs_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE run_chunks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  chunk_seq INT NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  item_count INT NOT NULL DEFAULT 0,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ack_token CHAR(32) NOT NULL,
  is_final TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunks_run_seq (run_id, chunk_seq),
  CONSTRAINT fk_chunks_run FOREIGN KEY (run_id) REFERENCES runs (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE day_finals (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  `date` DATE NOT NULL,
  expected_runs INT NOT NULL DEFAULT 0,
  expected_run_ids JSON DEFAULT NULL,
  client_pending_chunks INT NOT NULL DEFAULT 0,
  content_hash CHAR(64) NOT NULL,
  revision INT NOT NULL DEFAULT 1,
  server_result ENUM('accepted','incomplete','conflict') DEFAULT NULL,
  complete TINYINT NOT NULL DEFAULT 0,
  evaluated_at DATETIME(3) DEFAULT NULL,
  final_received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_day_finals_account_date (account_id, `date`),
  CONSTRAINT fk_day_finals_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE authors (
  sec_uid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  has_mix TINYINT NOT NULL DEFAULT 0,
  has_ai TINYINT NOT NULL DEFAULT 0,
  first_collected_at DATETIME(3) DEFAULT NULL,
  first_seen_run_id BIGINT DEFAULT NULL,
  first_seen_clock_skew TINYINT NOT NULL DEFAULT 0,
  last_collected_at DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (sec_uid),
  KEY idx_authors_first_run (first_seen_run_id),
  CONSTRAINT fk_authors_first_run FOREIGN KEY (first_seen_run_id) REFERENCES runs (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE authors_source (
  run_id BIGINT NOT NULL,
  sec_uid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  account_id BIGINT NOT NULL,
  collected_at_client DATETIME(3) DEFAULT NULL,
  received_at_server DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (run_id, sec_uid),
  KEY idx_authors_source_account (account_id),
  CONSTRAINT fk_authors_source_run FOREIGN KEY (run_id) REFERENCES runs (id) ON DELETE RESTRICT,
  CONSTRAINT fk_authors_source_author FOREIGN KEY (sec_uid) REFERENCES authors (sec_uid) ON DELETE RESTRICT,
  CONSTRAINT fk_authors_source_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE batches (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_no VARCHAR(48) NOT NULL,
  `date` DATE NOT NULL,
  type ENUM('full','partial','supplement','fallback') NOT NULL,
  total INT NOT NULL DEFAULT 0,
  dedup_total INT NOT NULL DEFAULT 0,
  state ENUM('pending','uploading','unknown','success','failed','cancelled','rolled_back') NOT NULL DEFAULT 'pending',
  retryable TINYINT NOT NULL DEFAULT 1,
  next_retry_at DATETIME(3) DEFAULT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  void_reason VARCHAR(255) DEFAULT NULL,
  void_evidence TEXT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  closed_at DATETIME(3) DEFAULT NULL,
  note TEXT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_batches_no (batch_no),
  KEY idx_batches_date (date, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE batch_items (
  batch_no VARCHAR(48) NOT NULL,
  seq INT NOT NULL,
  sec_uid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  confirm_state ENUM('pending','confirmed','not_received','unknown') NOT NULL DEFAULT 'pending',
  confirm_at DATETIME(3) DEFAULT NULL,
  confirm_attempt_no INT DEFAULT NULL,
  confirm_evidence VARCHAR(255) DEFAULT NULL,
  confirm_source VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (batch_no, sec_uid),
  KEY idx_batch_items_seq (batch_no, seq),
  KEY idx_batch_items_sec (sec_uid),
  CONSTRAINT fk_batch_items_batch FOREIGN KEY (batch_no) REFERENCES batches (batch_no) ON DELETE RESTRICT,
  CONSTRAINT fk_batch_items_author FOREIGN KEY (sec_uid) REFERENCES authors (sec_uid) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE upload_attempts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_no VARCHAR(48) NOT NULL,
  attempt_no INT NOT NULL,
  idem_key VARCHAR(64) DEFAULT NULL,
  started_at DATETIME(3) NOT NULL,
  ended_at DATETIME(3) DEFAULT NULL,
  duration_ms INT DEFAULT NULL,
  transport_result ENUM('ok','timeout','conn_error','http_error') DEFAULT NULL,
  business_result ENUM('success','rejected','unknown') DEFAULT NULL,
  http_status INT DEFAULT NULL,
  receipt_raw MEDIUMTEXT DEFAULT NULL,
  error TEXT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_attempts_batch_no (batch_no, attempt_no),
  CONSTRAINT fk_attempts_batch FOREIGN KEY (batch_no) REFERENCES batches (batch_no) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  sender_id BIGINT NOT NULL,
  scope ENUM('all','one') NOT NULL,
  target_user_id BIGINT DEFAULT NULL,
  kind ENUM('daily','release','alert') NOT NULL,
  title VARCHAR(128) NOT NULL,
  body TEXT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  require_next_day TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_notifications_target (scope, target_user_id),
  CONSTRAINT fk_notifications_sender FOREIGN KEY (sender_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_reads (
  notification_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (notification_id, user_id),
  KEY idx_notification_reads_user (user_id),
  CONSTRAINT fk_reads_notification FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE RESTRICT,
  CONSTRAINT fk_reads_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_logs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL,
  actor_id BIGINT DEFAULT NULL,
  operator VARCHAR(64) NOT NULL,
  operator_type VARCHAR(32) NOT NULL,
  triggered_by BIGINT DEFAULT NULL,
  action VARCHAR(64) NOT NULL,
  method VARCHAR(128) DEFAULT NULL,
  uri VARCHAR(255) DEFAULT NULL,
  params VARCHAR(500) DEFAULT NULL,
  object_type VARCHAR(32) DEFAULT NULL,
  object_id VARCHAR(64) DEFAULT NULL,
  detail_json JSON DEFAULT NULL,
  ip VARCHAR(45) DEFAULT NULL,
  success TINYINT NOT NULL DEFAULT 1,
  error_message VARCHAR(2000) DEFAULT NULL,
  duration_ms INT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_audit_event_id (event_id),
  KEY idx_audit_created (created_at),
  KEY idx_audit_actor_created (actor_id, created_at),
  KEY idx_audit_operator_created (operator, created_at),
  KEY idx_audit_action_created (action, created_at),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE RESTRICT,
  CONSTRAINT fk_audit_triggered_by FOREIGN KEY (triggered_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE configs (
  `key` VARCHAR(64) NOT NULL,
  `value` JSON NOT NULL,
  updated_by BIGINT DEFAULT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`key`),
  CONSTRAINT fk_configs_user FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
